package org.evsyukov.shareding.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.evsyukov.shareding.data.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRoutingTest {
    @Test fun allServerAndPageRequestsUseEnabledProxy() = runBlocking {
        TlsMockServer(tunnelProxy = true).use { tls ->
            val proxy = tls.server
            val config = ProxyConfig(true, proxy.hostName, proxy.port)
            proxy.enqueue(MockResponse().setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END))
            proxy.enqueue(MockResponse().setBody("{}"))
            proxy.enqueue(MockResponse().setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END))
            proxy.enqueue(MockResponse().setBody("""{"next":null,"results":[{"name":"reading"}]}"""))
            proxy.enqueue(MockResponse().setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END))
            proxy.enqueue(MockResponse().setResponseCode(201))
            proxy.enqueue(MockResponse().addHeader("Content-Type", "text/html")
                .setBody("<title>Through proxy</title>"))
            val api = tls.api { config }
            api.check("https://origin.invalid/", "secret")
            assertEquals(listOf("reading"), api.listTags("https://origin.invalid/", "secret"))
            api.send("https://origin.invalid/", "secret", Bookmark(url = "https://example.com"))
            assertEquals("Through proxy", PageMetadataFetcher { config }
                .fetch("http://page.invalid/article")?.title)

            assertEquals(7, proxy.requestCount)
            val requests = (1..7).map { proxy.takeRequest() }
            val lines = requests.map { it.requestLine }
            assertTrue(lines.toString(), lines[0].startsWith("CONNECT origin.invalid:443"))
            assertEquals("Token secret", requests[1].getHeader("Authorization"))
            assertTrue(lines.toString(), lines[1].contains("/api/tags/"))
            assertTrue(lines.toString(), lines[3].contains("/api/tags/"))
            assertTrue(lines.toString(), lines[5].contains("/api/bookmarks/"))
            assertTrue(lines.toString(), lines[6].contains("page.invalid/article"))
        }
    }

    @Test fun proxyAuthOnConnectDoesNotExposeLinkdingToken() = runBlocking {
        TlsMockServer(tunnelProxy = true).use { tls ->
            val proxy = tls.server
            proxy.enqueue(MockResponse().setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END))
            proxy.enqueue(MockResponse().setBody("{}"))
            tls.api().check("https://origin.invalid/", "secret", proxy =
                ProxyConfig(true, proxy.hostName, proxy.port, "alice", "password"))
            val connect = proxy.takeRequest()
            assertEquals("Basic YWxpY2U6cGFzc3dvcmQ=",
                connect.getHeader("Proxy-Authorization"))
            assertNull(connect.getHeader("Authorization"))
            assertEquals("Token secret", proxy.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun failedProxyAuthenticationHasClearError() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(407)
                .addHeader("Proxy-Authenticate", "Basic realm=\"proxy\""))
            val error = runCatching {
                LinkdingApi().check("https://origin.invalid/", "secret", proxy =
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
                val url = "https://origin.invalid/"
                assertTrue(runCatching { LinkdingApi().check(url, "secret", proxy = config) }.isFailure)
                assertNull(PageMetadataFetcher().fetch(origin.url("/page").toString(), proxy = config))
                assertEquals(0, origin.requestCount)
                assertEquals(2, proxy.requestCount)
            }
        }
    }

    @Test fun disabledProxyConnectsDirectly() = runBlocking {
        TlsMockServer().use { tls ->
            val origin = tls.server
            origin.enqueue(MockResponse().setBody("{}"))
            tls.api().check(origin.url("/").toString(), "secret", proxy = ProxyConfig())
            assertNotNull(origin.takeRequest())
        }
    }
}
