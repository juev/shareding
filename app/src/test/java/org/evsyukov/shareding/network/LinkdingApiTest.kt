package org.evsyukov.shareding.network

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.evsyukov.shareding.data.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LinkdingApiTest {
    @Test fun sendsExpectedPayloadAndToken() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val bookmark = Bookmark(url = "https://example.com", title = "Example", tags = "one, two words",
                unread = true, archived = false)
            LinkdingApi().send(server.url("/").toString(), "secret", bookmark)
            val request = server.takeRequest()
            assertEquals("/api/bookmarks/", request.path)
            assertEquals("Token secret", request.getHeader("Authorization"))
            val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("https://example.com", body.get("url").asString)
            assertEquals(listOf("one", "two words"), body.getAsJsonArray("tag_names").map { it.asString })
            assertTrue(body.get("unread").asBoolean)
            assertFalse(body.get("is_archived").asBoolean)
        }
    }

    @Test fun rejectsRedirectWithoutForwardingToken() = runBlocking {
        MockWebServer().use { original ->
            MockWebServer().use { destination ->
                original.enqueue(MockResponse().setResponseCode(302)
                    .addHeader("Location", destination.url("/stolen")))
                try {
                    LinkdingApi().send(original.url("/").toString(), "secret",
                        Bookmark(url = "https://example.com"))
                    fail("Redirect must not count as success")
                } catch (error: ApiException) {
                    assertEquals(302, error.code)
                }
                assertEquals(0, destination.requestCount)
            }
        }
    }

    @Test fun reportsServerError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            try {
                LinkdingApi().check(server.url("/").toString(), "secret")
                fail("HTTP error must fail")
            } catch (error: ApiException) {
                assertEquals(503, error.code)
            }
        }
    }
}
