package org.evsyukov.shareding.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)
    private val scheduling = Mutex()
    private val delivery = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paused = MutableStateFlow(false)
    private var editorOwner: String? = null
    private var activeDelivery: Job? = null

    val isPaused = paused.asStateFlow()

    val workInfos = workManager.getWorkInfosForUniqueWorkFlow("linkding-sync")

    suspend fun restartSync() = scheduling.withLock {
        if (paused.value) return@withLock
        enqueueReplacement()
    }

    private suspend fun enqueueReplacement() {
        withContext(Dispatchers.IO) {
            workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.REPLACE, newRequest())
                .result.get()
        }
    }

    suspend fun ensureScheduled() = scheduling.withLock {
        if (paused.value) return@withLock
        withContext(Dispatchers.IO) {
            workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.KEEP, newRequest())
                .result.get()
        }
    }

    suspend fun ensureRecoveryScheduled() = withContext(Dispatchers.IO) {
        workManager.enqueueUniquePeriodicWork("linkding-sync-recovery", ExistingPeriodicWorkPolicy.KEEP,
            newRecoveryRequest()).result.get()
    }

    suspend fun requestSync() {
        try {
            scheduling.withLock {
                if (paused.value) return@withLock
                withContext(Dispatchers.IO) {
                    val work = workManager.getWorkInfosForUniqueWork("linkding-sync").get()
                    // Pending work will read this save; running work may have read an empty queue.
                    val alreadyWaiting = work.any { it.state == WorkInfo.State.ENQUEUED ||
                        it.state == WorkInfo.State.BLOCKED }
                    if (!alreadyWaiting) {
                        workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
                            newRequest()).result.get()
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.e("ShareDing", "Could not schedule sync", error)
        }
    }

    suspend fun pauseForEdit(owner: String) {
        scheduling.withLock {
            check(editorOwner == null || editorOwner == owner) { "Another bookmark is being edited" }
            editorOwner = owner
            paused.value = true
            activeDelivery?.cancel()
            withContext(Dispatchers.IO) {
                workManager.cancelUniqueWork("linkding-sync").result.get()
            }
        }
        // Cancellation has been requested; wait until all worker database writes finish.
        delivery.withLock { }
    }

    suspend fun finishEdit(owner: String, save: suspend () -> Unit = {}) =
        withContext(NonCancellable) {
            scheduling.withLock {
                if (editorOwner != owner) return@withLock
                save() // A failed update keeps the editor and pause intact.
                editorOwner = null
                paused.value = false
                enqueueReplacement()
            }
        }

    fun releaseEditor(owner: String) {
        cleanupScope.launch {
            try {
                finishEdit(owner)
            } catch (error: Exception) {
                Log.e("ShareDing", "Could not resume sync after closing editor", error)
            }
        }
    }

    internal suspend fun <T> withSyncPermit(action: suspend () -> T): T? = coroutineScope {
        delivery.withLock {
            val admitted = scheduling.withLock {
                if (paused.value) false else {
                    activeDelivery = currentCoroutineContext()[Job]
                    true
                }
            }
            if (!admitted) return@withLock null
            try {
                action()
            } finally {
                withContext(NonCancellable) { scheduling.withLock { activeDelivery = null } }
            }
        }
    }

    internal fun newRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(networkConstraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    internal fun newRecoveryRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<SyncRecoveryWorker>(30, TimeUnit.MINUTES)
            .setInitialDelay(30, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .build()

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkRequest(NetworkRequests.linkdingCandidate(), NetworkType.CONNECTED)
        .build()
}
