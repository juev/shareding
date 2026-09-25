package org.evsyukov.shareding

import android.app.Application
import androidx.room.Room
import org.evsyukov.shareding.data.AppDatabase
import org.evsyukov.shareding.data.SettingsStore
import org.evsyukov.shareding.network.LinkdingApi
import org.evsyukov.shareding.network.NetworkSelector
import org.evsyukov.shareding.network.NetworkTracker
import org.evsyukov.shareding.sync.SyncScheduler

class ShareDingApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scheduler.enqueue()
    }
}

class AppContainer(application: Application) {
    val db: AppDatabase = Room.databaseBuilder(application, AppDatabase::class.java, "bookmarks.db").build()
    val settings = SettingsStore(application)
    val api = LinkdingApi()
    val networkTracker = NetworkTracker(application)
    val networkSelector = NetworkSelector(networkTracker, api)
    val scheduler = SyncScheduler(application)
}
