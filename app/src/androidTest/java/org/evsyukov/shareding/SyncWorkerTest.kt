package org.evsyukov.shareding

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.evsyukov.shareding.data.Bookmark
import org.evsyukov.shareding.network.ProxyConfig
import org.evsyukov.shareding.sync.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {
    @Test fun workerFetchesPageAndSendsBookmarkThroughProxy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().use { proxy ->
            try {
                app.container.settings.save("http://linkding.invalid/", "test-token", "", true, false,
                    ProxyConfig(true, proxy.hostName, proxy.port))
                dao.insert(Bookmark(url = "http://page.invalid/worker"))
                proxy.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                proxy.enqueue(MockResponse().addHeader("Content-Type", "text/html")
                    .setBody("<title>Fetched in worker</title>"))
                proxy.enqueue(MockResponse().setResponseCode(201))
                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

                assertEquals(0, dao.count())
                assertEquals(3, proxy.requestCount)
                val requests = (1..3).map { proxy.takeRequest() }
                assertEquals("GET", requests[0].method)
                assertEquals("GET", requests[1].method)
                assertEquals("POST", requests[2].method)
                assertEquals(true, requests[1].requestLine.contains("page.invalid/worker"))
                assertEquals(true, requests[2].body.readUtf8().contains("Fetched in worker"))
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
            }
        }
    }

    @Test fun serverErrorKeepsQueueUntilSuccessfulPost() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().use { server ->
            try {
                app.container.settings.save(server.url("/").toString(), "test-token", "", true, false,
                    ProxyConfig())
                dao.insert(Bookmark(url = "https://example.com/worker", title = "Worker test"))
                assertEquals("test-token", app.container.settings.token())
                assertEquals(1, dao.count())
                server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                server.enqueue(MockResponse().setResponseCode(503))
                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()
                assertEquals(2, server.requestCount)
                assertEquals(1, dao.count())
                assertEquals("failed", dao.batch(1).single().status)

                server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()
                assertEquals(0, dao.count())
                assertEquals(4, server.requestCount)
                assertEquals("/api/tags/", server.takeRequest().path)
                assertEquals("/api/bookmarks/", server.takeRequest().path)
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
            }
        }
    }
}
