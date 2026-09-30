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
import org.evsyukov.shareding.data.AppDatabase
import org.evsyukov.shareding.data.SettingsStore
import org.evsyukov.shareding.network.LinkdingApi
import org.evsyukov.shareding.network.NetworkSelector
import org.evsyukov.shareding.network.NetworkTracker
import org.evsyukov.shareding.network.PageMetadataFetcher
import org.evsyukov.shareding.network.ShortLinkResolver
import org.evsyukov.shareding.sync.SyncNotifier
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
                container.scheduler.ensureRecoveryScheduled()
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
    val scheduler = SyncScheduler(application)
    val notifier = SyncNotifier(application)

    suspend fun deleteQueuedBookmark(id: Long) {
        db.bookmarks().delete(id)
        scheduler.restartSync()
    }

    suspend fun recoverQueuedSync() {
        if (db.bookmarks().count() == 0) {
            notifier.checkStale(null)
            return
        }
        val configured = settings.state.value
        if (configured.serverUrl.isBlank() || !configured.hasToken) return
        notifier.checkStale(db.bookmarks().oldestCreatedAt())
        scheduler.ensureScheduled()
    }
}
