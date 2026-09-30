package org.evsyukov.shareding.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.evsyukov.shareding.ShareDingApplication
import org.evsyukov.shareding.network.ApiException

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext.applicationContext as ShareDingApplication).container
        return container.scheduler.withSyncPermit { runSync() } ?: Result.success()
    }

    private suspend fun runSync(): Result = try {
        sync()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Log.e("ShareDing", "Sync worker could not finish", error)
        val container = (applicationContext.applicationContext as ShareDingApplication).container
        runCatching { container.settings.recordError(error.message ?: "Cannot sync queue") }
        Result.retry()
    }

    private suspend fun sync(): Result {
        val container = (applicationContext.applicationContext as ShareDingApplication).container
        val dao = container.db.bookmarks()
        dao.recoverInterrupted()
        val settings = container.settings.state.value
        if (settings.serverUrl.isBlank() || !settings.hasToken) return Result.success()
        val token = try { container.settings.token() } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.e("ShareDing", "Cannot read API token", error)
            runCatching { container.settings.recordError("Cannot read API token: ${error.message}") }
            return Result.success()
        } ?: return Result.success()
        if (dao.count() == 0) return Result.success()
        val selectedNetwork = try {
            container.networkSelector.checkAndSelect(settings.serverUrl, token, network)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val message = error.message ?: "Cannot reach linkding"
            dao.batch(50).forEach { dao.markFailed(it.id, message) }
            runCatching { container.settings.recordError(message) }
            return Result.retry()
        }

        var failed = false
        while (!isStopped) {
            val batch = dao.batch(50)
            if (batch.isEmpty()) {
                if (!failed) runCatching { container.settings.recordSuccess() }
                return if (failed) Result.retry() else Result.success()
            }
            for (bookmark in batch) {
                if (isStopped) return Result.retry()
                currentCoroutineContext().ensureActive()
                // A bookmark can have been removed since this batch was read.
                if (dao.markSyncing(bookmark.id) == 0) continue
                try {
                    container.api.send(settings.serverUrl, token, bookmark, selectedNetwork)
                    dao.delete(bookmark.id)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    val message = error.message ?: "Cannot send bookmark"
                    dao.markFailed(bookmark.id, message)
                    runCatching { container.settings.recordError(message) }
                    failed = true
                    if (error is ApiException && error.code == 401) return Result.retry()
                }
            }
            if (failed) return Result.retry()
        }
        return Result.retry()
    }
}
