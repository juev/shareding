package org.evsyukov.shareding.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.evsyukov.shareding.data.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ShortLinkResolverTest {
    private fun resolver(server: MockWebServer) = ShortLinkResolver(hosts = setOf(server.hostName))

    private fun redirect(location: String, code: Int = 301) =
        MockResponse().setResponseCode(code).addHeader("Location", location)

    @Test fun replacesShortLinkWithTargetAndKeepsOriginalInNotes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(redirect("https://example.com/article?utm_source=x&id=1"))
            val short = server.url("/abc").toString()
            val resolved = resolver(server).resolve(Bookmark(url = short, notes = "my note"))
            assertEquals("https://example.com/article?id=1", resolved.url)
            assertEquals("my note\n\nOriginal URL: $short", resolved.notes)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals(null, request.getHeader("Authorization"))
        }
    }

    @Test fun followsChainOfShortenersAndStopsAtFirstOtherHost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(redirect("/second", 302))
            server.enqueue(redirect("https://blocked.invalid/page", 307))
            val resolved = resolver(server).resolve(Bookmark(url = server.url("/first").toString()))
            assertEquals("https://blocked.invalid/page", resolved.url)
            assertEquals("Original URL: ${server.url("/first")}", resolved.notes)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun keepsOriginalOnFailures() = runBlocking {
        val failures = listOf(
            MockResponse().setResponseCode(200).setBody("<meta http-equiv=refresh>"),
            MockResponse().setResponseCode(301),
            redirect("ftp://example.com/file"),
            redirect("http://[bad"),
            redirect("https://example.com/" + "a".repeat(Urls.MAX_LENGTH)),
            MockResponse().setResponseCode(404),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START),
            MockResponse().setHeadersDelay(7, TimeUnit.SECONDS),
        )
        for (failure in failures) {
            MockWebServer().use { server ->
                server.enqueue(failure)
                val bookmark = Bookmark(url = server.url("/x").toString(), notes = "keep")
                assertSame(bookmark, resolver(server).resolve(bookmark))
            }
        }
    }

    @Test fun keepsOriginalAfterTooManyHops() = runBlocking {
        MockWebServer().use { server ->
            repeat(6) { server.enqueue(redirect("/hop$it")) }
            val bookmark = Bookmark(url = server.url("/start").toString())
            assertSame(bookmark, resolver(server).resolve(bookmark))
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun onlyListedHostsAreContacted() = runBlocking {
        var clients = 0
        val resolver = ShortLinkResolver(clientFactory = { network, connect, read ->
            clients++
            HttpClients.create(network, connect, read)
        })
        listOf("https://example.com/t.co/abc", "https://nott.co/abc", "https://t.co.example.com/abc")
            .forEach { url ->
                val bookmark = Bookmark(url = url)
                assertSame(bookmark, resolver.resolve(bookmark))
            }
        assertEquals(0, clients)
        resolver.resolve(Bookmark(url = "https://www.t.co.invalid/abc"))
        assertEquals(0, clients)
    }
}
