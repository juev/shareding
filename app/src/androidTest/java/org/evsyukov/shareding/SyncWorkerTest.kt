package org.evsyukov.shareding

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.gson.JsonParser
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
    @Test fun workerDrainsFiftyQueuedBookmarks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().use { server ->
            try {
                app.container.settings.save(server.url("/").toString(), "test-token", "", true, false,
                    ProxyConfig())
                repeat(50) { index ->
                    dao.insert(Bookmark(url = "https://example.com/worker/$index", title = "Item $index"))
                }
                server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                repeat(50) { server.enqueue(MockResponse().setResponseCode(201).setBody("{}")) }

                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

                assertEquals(0, dao.count())
                assertEquals(51, server.requestCount)
                assertEquals("GET", server.takeRequest().method)
                val sentUrls = (1..50).map {
                    val request = server.takeRequest()
                    assertEquals("POST", request.method)
                    JsonParser.parseString(request.body.readUtf8()).asJsonObject.get("url").asString
                }
                assertEquals((0 until 50).map { "https://example.com/worker/$it" }, sentUrls)
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
            }
        }
    }

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
                dao.insert(Bookmark(url = "https://example.com/worker", title = "Worker test",
                    metadataFetched = true))
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

    @Test fun sharedTitleSendsWithoutFetchingMissingDescription() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().use { proxy ->
            try {
                app.container.settings.save("http://linkding.invalid/", "test-token", "", true, false,
                    ProxyConfig(true, proxy.hostName, proxy.port))
                val suppliedTitle = "Заголовок браузера — café"
                dao.insert(Bookmark(url = "http://page.invalid/article", title = suppliedTitle))
                proxy.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                proxy.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

                assertEquals(0, dao.count())
                assertEquals(2, proxy.requestCount)
                assertEquals("GET", proxy.takeRequest().method)
                val post = proxy.takeRequest()
                assertEquals("POST", post.method)
                val rawJson = post.body.readUtf8()
                val body = JsonParser.parseString(rawJson).asJsonObject
                assertEquals("http://page.invalid/article", body.get("url").asString)
                assertEquals(suppliedTitle, body.get("title").asString)
                assertEquals("", body.get("description").asString)
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
            }
        }
    }

    @Test fun titleFetchFailureDoesNotBlockBookmarkPost() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().use { proxy ->
            try {
                app.container.settings.save("http://linkding.invalid/", "test-token", "", true, false,
                    ProxyConfig(true, proxy.hostName, proxy.port))
                dao.insert(Bookmark(url = "http://page.invalid/unavailable"))
                proxy.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                proxy.enqueue(MockResponse().setResponseCode(502))
                proxy.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

                assertEquals(0, dao.count())
                assertEquals("GET", proxy.takeRequest().method)
                assertEquals("GET", proxy.takeRequest().method)
                val post = proxy.takeRequest()
                assertEquals("POST", post.method)
                val body = JsonParser.parseString(post.body.readUtf8()).asJsonObject
                assertEquals("", body.get("title").asString)
                assertEquals("", body.get("description").asString)
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
            }
        }
    }

    @Test fun suppliedTitleRetriesWithoutPageRequest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().use { proxy ->
            try {
                app.container.settings.save("http://linkding.invalid/", "test-token", "", true, false,
                    ProxyConfig(true, proxy.hostName, proxy.port))
                val url = "http://page.invalid/retry"
                dao.insert(Bookmark(url = url, title = "Browser title"))
                proxy.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                proxy.enqueue(MockResponse().setResponseCode(503))

                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

                val queued = dao.findByUrl(url)
                assertEquals("Browser title", queued?.title)
                assertEquals("", queued?.description)
                assertEquals(true, queued?.metadataFetched)
                assertEquals("failed", queued?.status)

                proxy.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                proxy.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

                assertEquals(0, dao.count())
                assertEquals(4, proxy.requestCount)
                assertEquals("GET", proxy.takeRequest().method)
                assertEquals("POST", proxy.takeRequest().method)
                assertEquals("GET", proxy.takeRequest().method)
                val retry = proxy.takeRequest()
                assertEquals("POST", retry.method)
                val body = JsonParser.parseString(retry.body.readUtf8()).asJsonObject
                assertEquals("Browser title", body.get("title").asString)
                assertEquals("", body.get("description").asString)
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
            }
        }
    }
}
