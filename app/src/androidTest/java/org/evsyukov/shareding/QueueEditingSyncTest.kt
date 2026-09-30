package org.evsyukov.shareding

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
import org.evsyukov.shareding.sync.SyncRecoveryWorker
import org.evsyukov.shareding.sync.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class QueueEditingSyncTest {
    @Test fun schedulingRecoveryAndDirectWorkerAreBlockedWhileEditing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        val scheduler = app.container.scheduler
        val manager = WorkManager.getInstance(context)
        val tls = AndroidTestTls(InstrumentationRegistry.getInstrumentation().context)
        manager.cancelUniqueWork("linkding-sync").result.get()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        val dao = app.container.db.bookmarks()
        MockWebServer().apply { tls.start(this) }.use { server ->
            val owner = "queue-edit-blocked"
            try {
                app.container.settings.save(tls.url(server), "secret", "", true, false, ProxyConfig())
                val id = dao.insert(Bookmark(url = "https://example.com/original", createdAt = 1))
                scheduler.pauseForEdit(owner)
                val sharedId = dao.insert(Bookmark(url = "https://example.com/shared", createdAt = 2))

                scheduler.ensureScheduled()
                scheduler.requestSync()
                scheduler.restartSync()
                app.container.recoverQueuedSync()
                TestListenableWorkerBuilder<SyncRecoveryWorker>(context).build().doWork()
                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()
                scheduler.finishEdit("stale-owner")
                val failedSave = runCatching {
                    scheduler.finishEdit(owner) { error("save failed") }
                }

                assertTrue(scheduler.isPaused.value)
                assertTrue(failedSave.isFailure)
                assertTrue(manager.getWorkInfosForUniqueWork("linkding-sync").get()
                    .none { !it.state.isFinished })
                assertEquals(0, server.requestCount)
                val original = dao.findById(id)
                val shared = dao.findById(sharedId)
                assertEquals("https://example.com/original", original?.url)
                assertEquals("pending", original?.status)
                assertEquals("https://example.com/shared", shared?.url)
                assertEquals("pending", shared?.status)
            } finally {
                scheduler.finishEdit(owner)
                manager.cancelUniqueWork("linkding-sync").result.get()
                app.container.settings.save("", null, "", true, false, ProxyConfig())
                withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
            }
        }
    }

    @Test fun pauseCancelsPostAndEditedBookmarkResumesImmediatelyWithSameId() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        val scheduler = app.container.scheduler
        val manager = WorkManager.getInstance(context)
        val tls = AndroidTestTls(InstrumentationRegistry.getInstrumentation().context)
        manager.cancelUniqueWork("linkding-sync").result.get()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        val dao = app.container.db.bookmarks()
        val posted = Collections.synchronizedList(mutableListOf<String>())
        MockWebServer().apply { tls.start(this) }.use { server ->
            val owner = "queue-edit-save"
            try {
                app.container.settings.save(tls.url(server), "secret", "", true, false, ProxyConfig())
                val id = dao.insert(Bookmark(url = "https://example.com/stuck", createdAt = 1))
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method != "POST") return MockResponse().setBody("[]")
                        val url = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                            .get("url").asString
                        posted.add(url)
                        return if (url.endsWith("stuck")) {
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        } else {
                            MockResponse().setResponseCode(201).setBody("{}")
                        }
                    }
                }

                scheduler.restartSync()
                withTimeout(10_000) { while (posted.isEmpty()) delay(50) }
                val oldWork = manager.getWorkInfosForUniqueWork("linkding-sync").get()
                    .single { !it.state.isFinished }
                scheduler.pauseForEdit(owner)
                assertEquals(listOf("https://example.com/stuck"), posted.toList())

                assertEquals(1, dao.edit(id, "https://example.com/edited", "Edited", true,
                    "Description", "inbox"))
                assertEquals(id, dao.findById(id)?.id)
                scheduler.finishEdit(owner)

                withTimeout(10_000) { while (dao.findById(id) != null) delay(50) }
                assertFalse(scheduler.isPaused.value)
                assertEquals(listOf("https://example.com/stuck", "https://example.com/edited"), posted.toList())
                val oldState = manager.getWorkInfoById(oldWork.id).get()?.state
                assertTrue(oldState == null || oldState == WorkInfo.State.CANCELLED)
            } finally {
                scheduler.finishEdit(owner)
                manager.cancelUniqueWork("linkding-sync").result.get()
                app.container.settings.save("", null, "", true, false, ProxyConfig())
                withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
            }
        }
    }

    @Test fun cancellingEditResumesAndRestartsSync() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        val scheduler = app.container.scheduler
        val manager = WorkManager.getInstance(context)
        val tls = AndroidTestTls(InstrumentationRegistry.getInstrumentation().context)
        manager.cancelUniqueWork("linkding-sync").result.get()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().apply { tls.start(this) }.use { server ->
            val owner = "queue-edit-cancel"
            try {
                app.container.settings.save(tls.url(server), "secret", "", true, false, ProxyConfig())
                scheduler.pauseForEdit(owner)
                val before = manager.getWorkInfosForUniqueWork("linkding-sync").get()
                    .map { it.id }.toSet()

                scheduler.finishEdit(owner)

                assertFalse(scheduler.isPaused.value)
                withTimeout(10_000) {
                    while (manager.getWorkInfosForUniqueWork("linkding-sync").get()
                            .none { it.id !in before }) delay(50)
                }
            } finally {
                scheduler.finishEdit(owner)
                manager.cancelUniqueWork("linkding-sync").result.get()
                app.container.settings.save("", null, "", true, false, ProxyConfig())
                withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
            }
        }
    }
}
