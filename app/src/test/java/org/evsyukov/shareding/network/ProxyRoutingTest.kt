package org.evsyukov.shareding.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.evsyukov.shareding.data.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRoutingTest {
    @Test fun allServerAndPageRequestsUseEnabledProxy() = runBlocking {
        MockWebServer().use { proxy ->
            val config = ProxyConfig(true, proxy.hostName, proxy.port)
            proxy.enqueue(MockResponse().setBody("{}"))
            proxy.enqueue(MockResponse().setBody("""{"next":null,"results":[{"name":"reading"}]}"""))
            proxy.enqueue(MockResponse().setResponseCode(201))
            proxy.enqueue(MockResponse().addHeader("Content-Type", "text/html")
                .setBody("<title>Through proxy</title>"))
            val api = LinkdingApi { config }
            api.check("http://origin.invalid/", "secret")
            assertEquals(listOf("reading"), api.listTags("http://origin.invalid/", "secret"))
            api.send("http://origin.invalid/", "secret", Bookmark(url = "https://example.com"))
            assertEquals("Through proxy", PageMetadataFetcher { config }
                .fetch("http://page.invalid/article")?.title)

            assertEquals(4, proxy.requestCount)
            val requests = (1..4).map { proxy.takeRequest() }
            val lines = requests.map { it.requestLine }
            assertTrue(lines.toString(), lines[0].contains("origin.invalid/api/tags/"))
            assertTrue(lines[1].contains("origin.invalid/api/tags/"))
            assertTrue(lines[2].contains("origin.invalid/api/bookmarks/"))
            assertTrue(lines[3].contains("page.invalid/article"))
        }
    }

    @Test fun proxyAuthIsSentOnlyAfterChallenge() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(407)
                .addHeader("Proxy-Authenticate", "Basic realm=\"proxy\""))
            proxy.enqueue(MockResponse().setBody("{}"))
            LinkdingApi().check("http://origin.invalid/", "secret", proxy =
                ProxyConfig(true, proxy.hostName, proxy.port, "alice", "password"))
            assertNull(proxy.takeRequest().getHeader("Proxy-Authorization"))
            assertEquals("Basic YWxpY2U6cGFzc3dvcmQ=",
                proxy.takeRequest().getHeader("Proxy-Authorization"))
        }
    }

    @Test fun failedProxyAuthenticationHasClearError() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(407)
                .addHeader("Proxy-Authenticate", "Basic realm=\"proxy\""))
            val error = runCatching {
                LinkdingApi().check("http://origin.invalid/", "secret", proxy =
                    ProxyConfig(true, proxy.hostName, proxy.port))
            }.exceptionOrNull()
            assertEquals("Proxy authentication failed", error?.message)
        }
    }

    @Test fun failedProxyNeverFallsBackToDirectServerOrPage() = runBlocking {
        MockWebServer().use { origin ->
            MockWebServer().use { proxy ->
                proxy.enqueue(MockResponse().setResponseCode(503))
                proxy.enqueue(MockResponse().setResponseCode(503))
                val config = ProxyConfig(true, proxy.hostName, proxy.port)
                val url = origin.url("/").toString()
                assertTrue(runCatching { LinkdingApi().check(url, "secret", proxy = config) }.isFailure)
                assertNull(PageMetadataFetcher().fetch(origin.url("/page").toString(), proxy = config))
                assertEquals(0, origin.requestCount)
                assertEquals(2, proxy.requestCount)
            }
        }
    }

    @Test fun disabledProxyConnectsDirectly() = runBlocking {
        MockWebServer().use { origin ->
            origin.enqueue(MockResponse().setBody("{}"))
            LinkdingApi().check(origin.url("/").toString(), "secret", proxy = ProxyConfig())
            assertNotNull(origin.takeRequest())
        }
    }
}
