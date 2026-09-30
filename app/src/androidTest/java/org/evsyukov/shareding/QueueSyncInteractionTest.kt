package org.evsyukov.shareding

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.evsyukov.shareding.data.Bookmark
import org.evsyukov.shareding.network.ProxyConfig
import org.evsyukov.shareding.sync.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class QueueSyncInteractionTest {
    @Test fun deletedEntryInPreviouslyReadBatchIsNeverPosted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        val tls = AndroidTestTls(InstrumentationRegistry.getInstrumentation().context)
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("linkding-sync").result.get()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        val dao = app.container.db.bookmarks()
        MockWebServer().apply { tls.start(this) }.use { server ->
            try {
                app.container.settings.save(tls.url(server), "secret", "", true, false, ProxyConfig())
                dao.insert(Bookmark(url = "https://example.com/first", createdAt = 1))
                val deleted = dao.insert(Bookmark(url = "https://example.com/deleted", createdAt = 2))
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method == "POST") runBlocking { dao.delete(deleted) }
                        return MockResponse().setResponseCode(if (request.method == "POST") 201 else 200)
                            .setBody("{}")
                    }
                }
                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()
                assertEquals(0, dao.count())
                assertEquals(2, server.requestCount)
                assertEquals("GET", server.takeRequest().method)
                val body = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
                assertEquals("https://example.com/first", body.get("url").asString)
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
            }
        }
    }

    @Test fun deletingStuckEntryCancelsUploadAndImmediatelyDrainsRemainingQueue() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        val tls = AndroidTestTls(InstrumentationRegistry.getInstrumentation().context)
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork("linkding-sync").result.get()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        val dao = app.container.db.bookmarks()
        val posted = Collections.synchronizedList(mutableListOf<String>())
        MockWebServer().apply { tls.start(this) }.use { server ->
            try {
                app.container.settings.save(tls.url(server), "secret", "", true, false, ProxyConfig())
                val deleted = dao.insert(Bookmark(url = "https://example.com/stuck", createdAt = 1))
                dao.insert(Bookmark(url = "https://example.com/remaining", createdAt = 2))
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method != "POST") return MockResponse().setBody("{}")
                        val url = JsonParser.parseString(request.body.readUtf8()).asJsonObject.get("url").asString
                        posted.add(url)
                        return if (url.endsWith("stuck")) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            else MockResponse().setResponseCode(201).setBody("{}")
                    }
                }
                app.container.scheduler.restartSync()
                withTimeout(10_000) { while (posted.isEmpty()) delay(50) }
                val running = manager.getWorkInfosForUniqueWork("linkding-sync").get()
                    .single { !it.state.isFinished }
                app.container.deleteQueuedBookmark(deleted)
                // The old request read timeout is 15 seconds; replacement must not wait for it.
                withTimeout(8_000) { while (dao.count() != 0) delay(50) }
                val old = manager.getWorkInfoById(running.id).get()
                assertTrue(old == null || old.state == WorkInfo.State.CANCELLED)
                assertEquals(listOf("https://example.com/stuck", "https://example.com/remaining"), posted.toList())
                assertTrue(manager.getWorkInfosForUniqueWork("linkding-sync").get().any { it.id != running.id })
            } finally {
                manager.cancelUniqueWork("linkding-sync").result.get()
                app.container.settings.save("", null, "", true, false, ProxyConfig())
                withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
            }
        }
    }
}
