package org.evsyukov.shareding.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.evsyukov.shareding.ShareDingApplication

/** Restores a missing one-time sync request without sending bookmarks itself. */
class SyncRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        (applicationContext.applicationContext as ShareDingApplication).container.recoverQueuedSync()
        Result.success()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Log.e("ShareDing", "Could not recover queued sync", error)
        Result.retry()
    }
}
