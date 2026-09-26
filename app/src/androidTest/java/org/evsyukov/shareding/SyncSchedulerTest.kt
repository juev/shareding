package org.evsyukov.shareding

import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.evsyukov.shareding.data.Bookmark
import org.evsyukov.shareding.network.ProxyConfig
import org.evsyukov.shareding.sync.BlockingTestWorker
import org.evsyukov.shareding.sync.SyncScheduler
import org.evsyukov.shareding.sync.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SyncSchedulerTest {
    @Test fun repeatedRequestsReuseAnExistingPendingSync() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("linkding-sync").result.get()
        try {
            val existing = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(1, TimeUnit.DAYS).build()
            workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.KEEP,
                existing).result.get()

            val scheduler = SyncScheduler(context)
            repeat(50) { scheduler.requestSync() }

            val unfinished = workManager.getWorkInfosForUniqueWork("linkding-sync").get()
                .filter { !it.state.isFinished }
            assertEquals(listOf(existing.id), unfinished.map { it.id })
        } finally {
            workManager.cancelUniqueWork("linkding-sync").result.get()
        }
    }

    @Test fun appStartupKeepsExistingPendingWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("linkding-sync").result.get()
        try {
            val existing = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(1, TimeUnit.DAYS).build()
            workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.KEEP,
                existing).result.get()

            SyncScheduler(context).ensureScheduled()

            val pending = workManager.getWorkInfosForUniqueWork("linkding-sync").get()
                .filter { it.state == WorkInfo.State.ENQUEUED }
            assertEquals(listOf(existing.id), pending.map { it.id })
        } finally {
            workManager.cancelUniqueWork("linkding-sync").result.get()
        }
    }

    @Test fun repeatedRequestsAddOnlyOneSuccessorToRunningWork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("linkding-sync").result.get()
        BlockingTestWorker.reset()
        try {
            val existing = OneTimeWorkRequestBuilder<BlockingTestWorker>().build()
            workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.KEEP,
                existing).result.get()
            assertTrue(BlockingTestWorker.started.await(10, TimeUnit.SECONDS))

            val scheduler = SyncScheduler(context)
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val requests = List(10) {
                    async(Dispatchers.Default) { start.await(); scheduler.requestSync() }
                }
                start.complete(Unit)
                requests.awaitAll()
            }

            val infos = workManager.getWorkInfosForUniqueWork("linkding-sync").get()
            assertEquals(WorkInfo.State.RUNNING, infos.single { it.id == existing.id }.state)
            assertEquals(1, infos.count { it.state == WorkInfo.State.BLOCKED })
        } finally {
            BlockingTestWorker.release.countDown()
            workManager.cancelUniqueWork("linkding-sync").result.get()
        }
    }

    @Test fun requestAfterCompletedWorkSchedulesANewSync() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("linkding-sync").result.get()
        try {
            val before = workManager.getWorkInfosForUniqueWork("linkding-sync").get()
                .map { it.id }.toSet()

            SyncScheduler(context).requestSync()

            val after = workManager.getWorkInfosForUniqueWork("linkding-sync").get()
            assertEquals(1, after.count { it.id !in before })
        } finally {
            workManager.cancelUniqueWork("linkding-sync").result.get()
        }
    }

    @Test fun successorSendsLinkSavedAfterCurrentWorkStarted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("linkding-sync").result.get()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        BlockingTestWorker.reset()
        MockWebServer().use { server ->
            try {
                app.container.settings.save(server.url("/").toString(), "test-token", "", true, false,
                    ProxyConfig())
                server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
                val current = OneTimeWorkRequestBuilder<BlockingTestWorker>().build()
                workManager.enqueueUniqueWork("linkding-sync", ExistingWorkPolicy.KEEP,
                    current).result.get()
                assertTrue(BlockingTestWorker.started.await(10, TimeUnit.SECONDS))

                app.container.db.bookmarks().insert(Bookmark(
                    url = "https://example.com/late", title = "Late link"))
                app.container.scheduler.requestSync()
                val successor = workManager.getWorkInfosForUniqueWork("linkding-sync").get()
                    .single { it.state == WorkInfo.State.BLOCKED }
                BlockingTestWorker.release.countDown()

                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                var state: WorkInfo.State
                do {
                    state = requireNotNull(workManager.getWorkInfoById(successor.id).get()).state
                    if (!state.isFinished) delay(100)
                } while (!state.isFinished && System.nanoTime() < deadline)
                assertEquals(WorkInfo.State.SUCCEEDED, state)
                assertEquals(0, app.container.db.bookmarks().count())
                assertEquals(2, server.requestCount)
                assertEquals("GET", server.takeRequest().method)
                assertEquals("POST", server.takeRequest().method)
            } finally {
                BlockingTestWorker.release.countDown()
                workManager.cancelUniqueWork("linkding-sync").result.get()
                app.container.settings.save("", null, "", true, false, ProxyConfig())
                withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
            }
        }
    }

    @Test fun retriesAcceptNetworksWithoutPublicInternetValidationAndIncludeVpn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val constraints = SyncScheduler(context).newRequest().workSpec.constraints
        val request = requireNotNull(constraints.requiredNetworkRequest)

        assertTrue(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertFalse(request.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
    }
}
