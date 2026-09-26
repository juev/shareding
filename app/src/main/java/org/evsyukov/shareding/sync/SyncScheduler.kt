package org.evsyukov.shareding.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)
    private val scheduling = Mutex()

    fun ensureScheduled() {
        workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.KEEP, newRequest())
    }

    suspend fun requestSync() {
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
        .setConstraints(Constraints.Builder()
            .setRequiredNetworkRequest(NetworkRequests.linkdingCandidate(), NetworkType.CONNECTED)
            .build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
}
