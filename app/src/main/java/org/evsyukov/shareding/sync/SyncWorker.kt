package org.evsyukov.shareding.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.evsyukov.shareding.ShareDingApplication
import org.evsyukov.shareding.AppContainer

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
            return stop(container, SyncProblem.TOKEN, "Cannot read API token: ${error.message}")
        } ?: return Result.success()
        // An empty queue still checks the server, so "Sync now" reports a fresh result.
        val selectedNetwork = try {
            container.networkSelector.checkAndSelect(settings.serverUrl, token, network)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val message = error.message ?: "Cannot reach linkding"
            val queued = dao.count() > 0
            if (queued) dao.batch(50).forEach { dao.markFailed(it.id, message) }
            SyncProblem.of(error)?.let { return stop(container, it, message, notify = queued) }
            runCatching { container.settings.recordError(message) }
            // Nothing is waiting, so there is nothing to retry or notify about.
            if (!queued) return Result.success()
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
                    SyncProblem.of(error)?.let { return stop(container, it, message) }
                    runCatching { container.settings.recordError(message) }
                    failed = true
                }
            }
            if (failed) {
                container.notifier.checkStale(dao.oldestCreatedAt())
                return Result.retry()
            }
        }
        return Result.retry()
    }

    /**
     * Ends the run after a problem that retrying cannot fix. Sync stays off, with no scheduled work,
     * until the user saves settings, taps Sync now, or retries a link.
     */
    private suspend fun stop(container: AppContainer, problem: SyncProblem, message: String,
                             notify: Boolean = true): Result {
        container.settings.stopSync(problem.name, message)
        if (notify) {
            container.notifier.problem(problem, message)
            container.notifier.checkStale(container.db.bookmarks().oldestCreatedAt())
        }
        container.updateRecovery()
        return Result.success()
    }
}
