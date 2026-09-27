package org.evsyukov.shareding.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.evsyukov.shareding.ShareDingApplication
import org.evsyukov.shareding.network.ApiException

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        sync()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Log.e("ShareDing", "Sync worker could not finish", error)
        Result.retry()
    }

    private suspend fun sync(): Result {
        val container = (applicationContext.applicationContext as ShareDingApplication).container
        val dao = container.db.bookmarks()
        dao.recoverInterrupted()
        val settings = container.settings.state.value
        if (settings.serverUrl.isBlank() || !settings.hasToken) return Result.success()
        val token = try { container.settings.token() } catch (error: Exception) {
            Log.e("ShareDing", "Cannot read API token", error)
            runCatching { container.settings.recordError("Cannot read API token: ${error.message}") }
            return Result.success()
        } ?: return Result.success()
        if (dao.count() == 0) return Result.success()
        val (proxy, selectedNetwork) = try {
            val savedProxy = container.settings.proxyConfig()
            savedProxy to container.networkSelector.checkAndSelect(settings.serverUrl, token,
                network, savedProxy)
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
            if (batch.isEmpty()) return if (failed) Result.retry() else Result.success()
            for (bookmark in batch) {
                if (isStopped) return Result.retry()
                val title = if (bookmark.title.isBlank() && !bookmark.metadataFetched) {
                    try { container.networkSelector.fetchPageMetadata(bookmark.url, selectedNetwork, proxy)
                        ?.title.orEmpty() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { "" }
                } else bookmark.title
                if (!bookmark.metadataFetched) dao.markMetadataFetched(bookmark.id)
                if (title.isNotBlank() && bookmark.title.isBlank()) dao.updateTitleIfEmpty(bookmark.id, title)
                dao.markSyncing(bookmark.id)
                try {
                    container.api.send(settings.serverUrl, token, bookmark.copy(title = title), selectedNetwork, proxy)
                    dao.delete(bookmark.id)
                    runCatching { container.settings.recordSuccess() }
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
