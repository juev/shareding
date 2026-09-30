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
    @Test fun successfulLaterEntryDoesNotHideEarlierFailureInSameAttempt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        val tls = AndroidTestTls(InstrumentationRegistry.getInstrumentation().context)
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        val dao = app.container.db.bookmarks()
        MockWebServer().apply { tls.start(this) }.use { server ->
            try {
                app.container.settings.save(tls.url(server), "secret", "", true, false, ProxyConfig())
                dao.insert(Bookmark(url = "https://example.com/failure", createdAt = 1))
                dao.insert(Bookmark(url = "https://example.com/success", createdAt = 2))
                server.enqueue(MockResponse().setBody("{}"))
                server.enqueue(MockResponse().setResponseCode(400).setBody("Invalid bookmark"))
                server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()
                assertEquals(1, dao.count())
                assertEquals("https://example.com/failure", dao.batch(1).single().url)
                assertEquals("linkding returned HTTP 400\nInvalid bookmark", app.container.settings.state.value.lastError)
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
                withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
            }
        }
    }

    @Test fun workerDrainsFiftyQueuedBookmarks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tls = AndroidTestTls(InstrumentationRegistry.getInstrumentation().context)
        val app = context.applicationContext as ShareDingApplication
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().apply { tls.start(this) }.use { server ->
            try {
                app.container.settings.save(tls.url(server), "test-token", "", true, false,
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

    @Test fun workerSendsWithoutFetchingPageThroughProxy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tls = AndroidTestTls(InstrumentationRegistry.getInstrumentation().context)
        val app = context.applicationContext as ShareDingApplication
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().apply { tls.start(this, tunnelProxy = true) }.use { proxy ->
            try {
                app.container.settings.save("https://linkding.invalid/", "test-token", "", true, false,
                    ProxyConfig(true, proxy.hostName, proxy.port))
                dao.insert(Bookmark(url = "http://page.invalid/worker"))
                proxy.enqueue(tls.connectResponse())
                proxy.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
                proxy.enqueue(tls.connectResponse())
                proxy.enqueue(MockResponse().setResponseCode(201))
                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

                assertEquals(0, dao.count())
                assertEquals(4, proxy.requestCount)
                val requests = (1..4).map { proxy.takeRequest() }
                assertEquals("CONNECT", requests[0].method)
                assertEquals("GET", requests[1].method)
                assertEquals("CONNECT", requests[2].method)
                assertEquals("POST", requests[3].method)
                val body = JsonParser.parseString(requests[3].body.readUtf8()).asJsonObject
                assertEquals("http://page.invalid/worker", body.get("url").asString)
                assertEquals(false, body.has("title"))
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
            }
        }
    }

    @Test fun serverErrorKeepsQueueUntilSuccessfulPost() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tls = AndroidTestTls(InstrumentationRegistry.getInstrumentation().context)
        val app = context.applicationContext as ShareDingApplication
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().apply { tls.start(this) }.use { server ->
            try {
                app.container.settings.save(tls.url(server), "test-token", "", true, false,
                    ProxyConfig())
                dao.insert(Bookmark(url = "https://example.com/worker", title = "Worker test",
                    metadataFetched = true))
                assertEquals("test-token", app.container.settings.token())
                assertEquals(1, dao.count())
                server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                server.enqueue(MockResponse().setResponseCode(503).setBody("Service unavailable: maintenance"))
                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()
                assertEquals(2, server.requestCount)
                assertEquals(1, dao.count())
                assertEquals("failed", dao.batch(1).single().status)
                assertEquals("linkding returned HTTP 503\nService unavailable: maintenance",
                    dao.batch(1).single().lastError)
                assertEquals(true, app.container.settings.state.value.lastSyncAttempt > 0)

                server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()
                assertEquals(0, dao.count())
                assertEquals(4, server.requestCount)
                assertEquals("", app.container.settings.state.value.lastError)
                assertEquals("/api/tags/", server.takeRequest().path)
                assertEquals("/api/bookmarks/", server.takeRequest().path)
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
            }
        }
    }

    @Test fun httpLinkdingServerRetainsQueueWithoutSendingToken() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ShareDingApplication
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().use { server ->
            try {
                app.container.settings.save(server.url("/").toString(), "must-not-send", "", true, false,
                    ProxyConfig())
                val url = "https://example.com/queued-after-http-server"
                dao.insert(Bookmark(url = url, title = "Keep this bookmark", metadataFetched = true))

                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

                assertEquals(0, server.requestCount)
                assertEquals(1, dao.count())
                assertEquals("Keep this bookmark", dao.findByUrl(url)?.title)
                assertEquals("failed", dao.findByUrl(url)?.status)
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
            }
        }
    }

    @Test fun sharedTitleSendsWithoutFetchingMissingDescription() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tls = AndroidTestTls(InstrumentationRegistry.getInstrumentation().context)
        val app = context.applicationContext as ShareDingApplication
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().apply { tls.start(this, tunnelProxy = true) }.use { proxy ->
            try {
                app.container.settings.save("https://linkding.invalid/", "test-token", "", true, false,
                    ProxyConfig(true, proxy.hostName, proxy.port))
                val suppliedTitle = "Заголовок браузера — café"
                dao.insert(Bookmark(url = "http://page.invalid/article", title = suppliedTitle))
                proxy.enqueue(tls.connectResponse())
                proxy.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                proxy.enqueue(tls.connectResponse())
                proxy.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

                assertEquals(0, dao.count())
                assertEquals(4, proxy.requestCount)
                assertEquals("CONNECT", proxy.takeRequest().method)
                assertEquals("GET", proxy.takeRequest().method)
                assertEquals("CONNECT", proxy.takeRequest().method)
                val post = proxy.takeRequest()
                assertEquals("POST", post.method)
                val rawJson = post.body.readUtf8()
                val body = JsonParser.parseString(rawJson).asJsonObject
                assertEquals("http://page.invalid/article", body.get("url").asString)
                assertEquals(false, body.has("title"))
                assertEquals("", body.get("description").asString)
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
            }
        }
    }

    @Test fun missingTitleDelegatesMetadataToLinkdingWithoutPageRequest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tls = AndroidTestTls(InstrumentationRegistry.getInstrumentation().context)
        val app = context.applicationContext as ShareDingApplication
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().apply { tls.start(this, tunnelProxy = true) }.use { proxy ->
            try {
                app.container.settings.save("https://linkding.invalid/", "test-token", "", true, false,
                    ProxyConfig(true, proxy.hostName, proxy.port))
                dao.insert(Bookmark(url = "http://page.invalid/unavailable"))
                proxy.enqueue(tls.connectResponse())
                proxy.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                proxy.enqueue(tls.connectResponse())
                proxy.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

                assertEquals(0, dao.count())
                assertEquals("CONNECT", proxy.takeRequest().method)
                assertEquals("GET", proxy.takeRequest().method)
                assertEquals("CONNECT", proxy.takeRequest().method)
                val post = proxy.takeRequest()
                assertEquals("POST", post.method)
                val body = JsonParser.parseString(post.body.readUtf8()).asJsonObject
                assertEquals(false, body.has("title"))
                assertEquals("", body.get("description").asString)
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
            }
        }
    }

    @Test fun manualTitleRetriesWithoutPageRequest() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tls = AndroidTestTls(InstrumentationRegistry.getInstrumentation().context)
        val app = context.applicationContext as ShareDingApplication
        WorkManager.getInstance(context).cancelUniqueWork("linkding-sync").result.get()
        val dao = app.container.db.bookmarks()
        withContext(Dispatchers.IO) { app.container.db.clearAllTables() }
        MockWebServer().apply { tls.start(this, tunnelProxy = true) }.use { proxy ->
            try {
                app.container.settings.save("https://linkding.invalid/", "test-token", "", true, false,
                    ProxyConfig(true, proxy.hostName, proxy.port))
                val url = "http://page.invalid/retry"
                dao.insert(Bookmark(url = url, title = "Manual title", sendTitle = true))
                proxy.enqueue(tls.connectResponse())
                proxy.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                proxy.enqueue(tls.connectResponse())
                proxy.enqueue(MockResponse().setResponseCode(503))

                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

                val queued = dao.findByUrl(url)
                assertEquals("Manual title", queued?.title)
                assertEquals("", queued?.description)
                assertEquals(true, queued?.sendTitle)
                assertEquals("failed", queued?.status)

                proxy.enqueue(tls.connectResponse())
                proxy.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
                proxy.enqueue(tls.connectResponse())
                proxy.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
                TestListenableWorkerBuilder<SyncWorker>(context).build().doWork()

                assertEquals(0, dao.count())
                assertEquals(8, proxy.requestCount)
                assertEquals("CONNECT", proxy.takeRequest().method)
                assertEquals("GET", proxy.takeRequest().method)
                assertEquals("CONNECT", proxy.takeRequest().method)
                assertEquals("POST", proxy.takeRequest().method)
                assertEquals("CONNECT", proxy.takeRequest().method)
                assertEquals("GET", proxy.takeRequest().method)
                assertEquals("CONNECT", proxy.takeRequest().method)
                val retry = proxy.takeRequest()
                assertEquals("POST", retry.method)
                val body = JsonParser.parseString(retry.body.readUtf8()).asJsonObject
                assertEquals("Manual title", body.get("title").asString)
                assertEquals("", body.get("description").asString)
            } finally {
                app.container.settings.save("", null, "", true, false, ProxyConfig())
            }
        }
    }
}
