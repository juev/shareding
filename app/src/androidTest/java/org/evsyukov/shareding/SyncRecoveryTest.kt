package org.evsyukov.shareding

import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.evsyukov.shareding.data.Bookmark
import org.evsyukov.shareding.network.ProxyConfig
import org.evsyukov.shareding.sync.BlockingTestWorker
import org.evsyukov.shareding.sync.SyncRecoveryWorker
import org.evsyukov.shareding.sync.SyncScheduler
import org.evsyukov.shareding.sync.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SyncRecoveryTest {
    @Test fun periodicRecoveryIsUniqueAndRequiresOnlyAConnectedNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workManager = WorkManager.getInstance(context)
        val scheduler = SyncScheduler(context)
        workManager.cancelUniqueWork("linkding-sync-recovery").result.get()
        try {
            scheduler.ensureRecoveryScheduled()
            val first = workManager.getWorkInfosForUniqueWork("linkding-sync-recovery").get()
                .filter { !it.state.isFinished }.single()
            scheduler.ensureRecoveryScheduled()
            val second = workManager.getWorkInfosForUniqueWork("linkding-sync-recovery").get()
                .filter { !it.state.isFinished }.single()
            assertEquals(first.id, second.id)

            val spec = scheduler.newRecoveryRequest().workSpec
            assertEquals(TimeUnit.MINUTES.toMillis(30), spec.intervalDuration)
            assertEquals(TimeUnit.MINUTES.toMillis(30), spec.initialDelay)
            val request = requireNotNull(spec.constraints.requiredNetworkRequest)
            assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        } finally {
            workManager.cancelUniqueWork("linkding-sync-recovery").result.get()
            scheduler.ensureRecoveryScheduled()
        }
    }

    @Test fun emptyQueueDoesNotScheduleSync() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("linkding-sync").result.get()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        try {
            app.container.settings.save("http://127.0.0.1:1/", "test-token", "", true, false,
                ProxyConfig())
            val before = workManager.getWorkInfosForUniqueWork("linkding-sync").get().map { it.id }
            val result = TestListenableWorkerBuilder<SyncRecoveryWorker>(context).build().doWork()
            assertTrue(result is ListenableWorker.Result.Success)
            val after = workManager.getWorkInfosForUniqueWork("linkding-sync").get().map { it.id }
            assertEquals(before, after)
        } finally {
            app.container.settings.save("", null, "", true, false, ProxyConfig())
        }
    }

    @Test fun missingSettingsDoNotScheduleSyncForQueuedLink() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("linkding-sync").result.get()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        try {
            app.container.settings.save("", null, "", true, false, ProxyConfig())
            app.container.db.bookmarks().insert(Bookmark(url = "https://example.com/unconfigured"))
            val before = workManager.getWorkInfosForUniqueWork("linkding-sync").get().map { it.id }
            val result = TestListenableWorkerBuilder<SyncRecoveryWorker>(context).build().doWork()
            assertTrue(result is ListenableWorker.Result.Success)
            val after = workManager.getWorkInfosForUniqueWork("linkding-sync").get().map { it.id }
            assertEquals(before, after)
            assertEquals(1, app.container.db.bookmarks().count())
        } finally {
            withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        }
    }

    @Test fun orphanedQueueSchedulesOneNormalSync() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("linkding-sync").result.get()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        try {
            app.container.settings.save("http://127.0.0.1:1/", "test-token", "", true, false,
                ProxyConfig())
            app.container.db.bookmarks().insert(Bookmark(url = "https://example.com/orphaned"))
            val before = workManager.getWorkInfosForUniqueWork("linkding-sync").get()
                .map { it.id }.toSet()

            val result = TestListenableWorkerBuilder<SyncRecoveryWorker>(context).build().doWork()
            assertTrue(result is ListenableWorker.Result.Success)
            val created = workManager.getWorkInfosForUniqueWork("linkding-sync").get()
                .filter { it.id !in before }
            assertEquals(1, created.size)
            assertEquals(1, app.container.db.bookmarks().count())
        } finally {
            workManager.cancelUniqueWork("linkding-sync").result.get()
            app.container.settings.save("", null, "", true, false, ProxyConfig())
            withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        }
    }

    @Test fun pendingSyncKeepsItsIdAndDelay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("linkding-sync").result.get()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        try {
            app.container.settings.save("http://127.0.0.1:1/", "test-token", "", true, false,
                ProxyConfig())
            app.container.db.bookmarks().insert(Bookmark(url = "https://example.com/pending"))
            val existing = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(1, TimeUnit.DAYS).build()
            workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.KEEP,
                existing).result.get()

            val result = TestListenableWorkerBuilder<SyncRecoveryWorker>(context).build().doWork()
            assertTrue(result is ListenableWorker.Result.Success)
            val unfinished = workManager.getWorkInfosForUniqueWork("linkding-sync").get()
                .filter { !it.state.isFinished }
            assertEquals(listOf(existing.id), unfinished.map { it.id })
            assertEquals(WorkInfo.State.ENQUEUED, unfinished.single().state)
        } finally {
            workManager.cancelUniqueWork("linkding-sync").result.get()
            app.container.settings.save("", null, "", true, false, ProxyConfig())
            withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        }
    }

    @Test fun runningSyncDoesNotGainASuccessor() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("linkding-sync").result.get()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        BlockingTestWorker.reset()
        try {
            app.container.settings.save("http://127.0.0.1:1/", "test-token", "", true, false,
                ProxyConfig())
            app.container.db.bookmarks().insert(Bookmark(url = "https://example.com/running"))
            val existing = OneTimeWorkRequestBuilder<BlockingTestWorker>().build()
            workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.KEEP,
                existing).result.get()
            assertTrue(BlockingTestWorker.started.await(10, TimeUnit.SECONDS))

            val result = TestListenableWorkerBuilder<SyncRecoveryWorker>(context).build().doWork()
            assertTrue(result is ListenableWorker.Result.Success)
            val unfinished = workManager.getWorkInfosForUniqueWork("linkding-sync").get()
                .filter { !it.state.isFinished }
            assertEquals(listOf(existing.id), unfinished.map { it.id })
            assertEquals(WorkInfo.State.RUNNING, unfinished.single().state)
        } finally {
            BlockingTestWorker.release.countDown()
            workManager.cancelUniqueWork("linkding-sync").result.get()
            app.container.settings.save("", null, "", true, false, ProxyConfig())
            withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        }
    }
}
