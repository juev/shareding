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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Schedules nothing while [canSync] is false: no server is set up, or a persistent error stopped sync. */
class SyncScheduler(context: Context, private val canSync: () -> Boolean = { true }) {
    private val workManager = WorkManager.getInstance(context)
    private val scheduling = Mutex()

    val workInfos = workManager.getWorkInfosForUniqueWorkFlow("linkding-sync")

    suspend fun restartSync() = scheduling.withLock {
        withContext(Dispatchers.IO) {
            if (canSync()) {
                workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.REPLACE, newRequest())
                    .result.get()
            } else {
                workManager.cancelUniqueWork("linkding-sync").result.get()
            }
        }
    }

    suspend fun ensureScheduled() {
        if (!canSync()) return
        withContext(Dispatchers.IO) {
            workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.KEEP, newRequest())
                .result.get()
        }
    }

    suspend fun ensureRecoveryScheduled() = withContext(Dispatchers.IO) {
        workManager.enqueueUniquePeriodicWork("linkding-sync-recovery", ExistingPeriodicWorkPolicy.KEEP,
            newRecoveryRequest()).result.get()
    }

    suspend fun cancelRecovery() = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork("linkding-sync-recovery").result.get()
    }

    suspend fun requestSync() {
        if (!canSync()) return
        try {
            scheduling.withLock {
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
