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
    override suspend fun doWork(): Result = try {
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
        if (!settings.canSync) return Result.success()
        val token = try { container.settings.token() } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.e("ShareDing", "Cannot read API token", error)
            val message = "Cannot read API token: ${error.message}"
            runCatching { container.settings.recordError(message) }
            container.notifier.problem(SyncProblem.TOKEN, message)
            return Result.success()
        } ?: return Result.success()
        // An empty queue still checks the server, so "Sync now" reports a fresh result.
        val selectedNetwork = try {
            container.networkSelector.checkAndSelect(settings.serverUrl, token, network)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val message = error.message ?: "Cannot reach linkding"
            if (dao.count() == 0) {
                // Nothing is waiting, so there is nothing to retry or notify about.
                runCatching { container.settings.recordError(message) }
                return Result.success()
            }
            dao.batch(50).forEach { dao.markFailed(it.id, message) }
            runCatching { container.settings.recordError(message) }
            SyncProblem.of(error)?.let { container.notifier.problem(it, message) }
            container.notifier.checkStale(dao.oldestCreatedAt())
            return Result.retry()
        }

        if (dao.count() == 0) {
            runCatching { container.settings.recordSuccess() }
            container.notifier.resolved()
            return Result.success()
        }

        var failed = false
        while (!isStopped) {
            val batch = dao.batch(50)
            if (batch.isEmpty()) {
                if (!failed) {
                    runCatching { container.settings.recordSuccess() }
                    container.notifier.resolved()
                }
                return if (failed) Result.retry() else Result.success()
            }
            for (bookmark in batch) {
                if (isStopped) return Result.retry()
                currentCoroutineContext().ensureActive()
                // A bookmark can have been removed since this batch was read.
                if (dao.markSyncing(bookmark.id) == 0) continue
                try {
                    val outgoing = container.shortLinks.resolve(bookmark, selectedNetwork)
                    container.api.send(settings.serverUrl, token, outgoing, selectedNetwork)
                    dao.delete(bookmark.id)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    val message = error.message ?: "Cannot send bookmark"
                    dao.markFailed(bookmark.id, message)
                    runCatching { container.settings.recordError(message) }
                    SyncProblem.of(error)?.let { container.notifier.problem(it, message) }
                    failed = true
                    if (error is ApiException && error.code == 401) break
                }
            }
            if (failed) {
                container.notifier.checkStale(dao.oldestCreatedAt())
                return Result.retry()
            }
        }
        return Result.retry()
    }
}
