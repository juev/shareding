package org.evsyukov.shareding

import android.app.Application
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.shareding.data.AppDatabase
import org.evsyukov.shareding.data.SettingsStore
import org.evsyukov.shareding.network.LinkdingApi
import org.evsyukov.shareding.network.NetworkSelector
import org.evsyukov.shareding.network.NetworkTracker
import org.evsyukov.shareding.network.PageMetadataFetcher
import org.evsyukov.shareding.network.ShortLinkResolver
import org.evsyukov.shareding.sync.SyncNotifier
import org.evsyukov.shareding.sync.SyncProblem
import org.evsyukov.shareding.sync.SyncScheduler

class ShareDingApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        startupScope.launch {
            try {
                container.updateRecovery()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.e("ShareDing", "Could not schedule periodic recovery", error)
            }
            try {
                container.recoverQueuedSync()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.e("ShareDing", "Could not schedule startup sync", error)
            }
        }
    }
}

class AppContainer(application: Application) {
    val db: AppDatabase = Room.databaseBuilder(application, AppDatabase::class.java, "bookmarks.db")
        .addMigrations(AppDatabase.MIGRATION_1_2).build()
    val settings = SettingsStore(application)
    val api = LinkdingApi()
    val pageFetcher = PageMetadataFetcher()
    @VisibleForTesting
    internal var shortLinks = ShortLinkResolver()
    val networkTracker = NetworkTracker(application)
    val networkSelector = NetworkSelector(networkTracker, api, pageFetcher)
    val scheduler = SyncScheduler(application) { settings.state.value.canSync }
    val notifier = SyncNotifier(application)

    suspend fun deleteQueuedBookmark(id: Long) {
        db.bookmarks().delete(id)
        scheduler.restartSync()
    }

    /** Keeps the periodic recovery check only while sync can run. */
    suspend fun updateRecovery() {
        if (settings.state.value.canSync) scheduler.ensureRecoveryScheduled() else scheduler.cancelRecovery()
    }

    /** After a link is saved: schedules sync, or tells the user why the link will not be sent. */
    suspend fun linkSaved(): SyncProblem? {
        val current = settings.state.value
        val blocked = SyncProblem.blocking(current)
        if (blocked == null) {
            scheduler.requestSync()
        } else {
            notifier.remind(blocked, if (blocked == SyncProblem.NOT_CONFIGURED)
                SyncProblem.NOT_CONFIGURED_DETAIL else current.lastError)
        }
        return blocked
    }

    /** Sync now and Retry ask for another attempt, so a stopped sync starts again. */
    suspend fun resumeSync() {
        withContext(Dispatchers.IO) { settings.resumeSync() }
        updateRecovery()
        scheduler.restartSync()
    }

    suspend fun recoverQueuedSync() {
        if (db.bookmarks().count() == 0) {
            notifier.checkStale(null)
            return
        }
        if (!settings.state.value.canSync) return
        notifier.checkStale(db.bookmarks().oldestCreatedAt())
        scheduler.ensureScheduled()
    }
}
