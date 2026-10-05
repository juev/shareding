package org.evsyukov.shareding.network

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.evsyukov.shareding.data.Bookmark
import org.evsyukov.shareding.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LinkdingApiTest {
    @Test fun sendsExpectedPayloadAndToken() = runBlocking {
        TlsMockServer().use { tls ->
            val server = tls.server
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val bookmark = Bookmark(url = "https://example.com", title = "Example", tags = "two words, one")
            tls.api().send(server.url("/").toString(), "secret", bookmark,
                Settings(defaultTags = "one", unread = true, archived = false))
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

    @Test fun sendsUnicodeJsonAndAlreadyEncodedUrlAsUtf8() = runBlocking {
        TlsMockServer().use { tls ->
            val server = tls.server
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val bookmark = Bookmark(
                url = "https://example.com/%D1%81%D1%82%D1%80%D0%B0%D0%BD%D0%B8%D1%86%D0%B0?q=caf%C3%A9",
                title = "Заголовок — café 東京",
                sendTitle = true,
                description = "Описание — déjà vu",
            )

            tls.api().send(server.url("/").toString(), "secret", bookmark, Settings())

            val request = server.takeRequest()
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            val rawJson = request.body.readUtf8()
            assertTrue(rawJson.contains("Заголовок — café 東京"))
            assertTrue(rawJson.contains("Описание — déjà vu"))
            val body = JsonParser.parseString(rawJson).asJsonObject
            assertEquals(bookmark.url, body.get("url").asString)
            assertEquals(bookmark.title, body.get("title").asString)
            assertEquals(bookmark.description, body.get("description").asString)
        }
    }

    @Test fun rejectsRedirectWithoutForwardingToken() = runBlocking {
        TlsMockServer().use { originalTls ->
            TlsMockServer().use { destinationTls ->
                val original = originalTls.server
                val destination = destinationTls.server
                original.enqueue(MockResponse().setResponseCode(302)
                    .addHeader("Location", destination.url("/stolen")))
                try {
                    originalTls.api().send(original.url("/").toString(), "secret",
                        Bookmark(url = "https://example.com"), Settings())
                    fail("Redirect must not count as success")
                } catch (error: ApiException) {
                    assertEquals(302, error.code)
                }
                assertEquals(0, destination.requestCount)
            }
        }
    }

    @Test fun reportsServerError() = runBlocking {
        TlsMockServer().use { tls ->
            val server = tls.server
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"temporarily unavailable"}"""))
            try {
                tls.api().check(server.url("/").toString(), "secret")
                fail("HTTP error must fail")
            } catch (error: ApiException) {
                assertEquals(503, error.code)
                assertTrue(error.message.orEmpty().contains("linkding returned HTTP 503"))
                assertTrue(error.message.orEmpty().contains("""{"detail":"temporarily unavailable"}"""))
            }
        }
    }

    @Test fun reportsPlainTextErrorBody() = runBlocking {
        TlsMockServer().use { tls ->
            tls.server.enqueue(MockResponse().setResponseCode(502).setBody("upstream failed"))
            val error = runCatching { tls.api().check(tls.server.url("/").toString(), "secret") }
                .exceptionOrNull() as ApiException
            assertEquals(502, error.code)
            assertTrue(error.message.orEmpty().contains("upstream failed"))
        }
    }

    @Test fun emptyErrorBodyUsesHttpFallback() = runBlocking {
        TlsMockServer().use { tls ->
            tls.server.enqueue(MockResponse().setResponseCode(503))
            val error = runCatching { tls.api().check(tls.server.url("/").toString(), "secret") }
                .exceptionOrNull() as ApiException
            assertEquals(503, error.code)
            assertEquals("linkding returned HTTP 503", error.message)
        }
    }

    @Test fun pendingRequestIsCancelledByTimeoutWithoutSuccess() = runBlocking {
        TlsMockServer().use { tls ->
            tls.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            var succeeded = false
            val error = runCatching {
                withTimeout(500) {
                    tls.api().check(tls.server.url("/").toString(), "secret")
                    succeeded = true
                }
            }.exceptionOrNull()
            assertTrue(error is TimeoutCancellationException)
            assertFalse(succeeded)
            assertEquals(1, tls.server.requestCount)
        }
    }

    @Test fun omitsDefaultTitleEvenWhenLongAndMultiline() = runBlocking {
        TlsMockServer().use { tls ->
            tls.server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val bookmark = Bookmark(url = "https://example.com", title = "first line\n" + "x".repeat(600))
            tls.api().send(tls.server.url("/").toString(), "secret", bookmark, Settings())
            val body = JsonParser.parseString(tls.server.takeRequest().body.readUtf8()).asJsonObject
            assertFalse(body.has("title"))
        }
    }

    @Test fun omitsBlankManualTitle() = runBlocking {
        TlsMockServer().use { tls ->
            tls.server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            tls.api().send(tls.server.url("/").toString(), "secret",
                Bookmark(url = "https://example.com", title = " \n\t ", sendTitle = true), Settings())
            val body = JsonParser.parseString(tls.server.takeRequest().body.readUtf8()).asJsonObject
            assertFalse(body.has("title"))
        }
    }

    @Test fun truncatesManualTitleTo512CodePointsWithoutSplittingSurrogatePair() = runBlocking {
        TlsMockServer().use { tls ->
            tls.server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            val title = "a".repeat(511) + "😀" + "b" + "\nextra line"
            tls.api().send(tls.server.url("/").toString(), "secret",
                Bookmark(url = "https://example.com", title = title, sendTitle = true), Settings())
            val body = JsonParser.parseString(tls.server.takeRequest().body.readUtf8()).asJsonObject
            val sentTitle = body.get("title").asString
            assertEquals(512, sentTitle.codePointCount(0, sentTitle.length))
            assertTrue(sentTitle.endsWith("😀"))
            assertFalse(sentTitle.contains('\n'))
            assertFalse(sentTitle.last().isHighSurrogate())
        }
    }

    @Test fun listsAllTagPagesWithoutFollowingServerSuppliedNextUrl() = runBlocking {
        TlsMockServer().use { tls ->
            val server = tls.server
            server.enqueue(MockResponse().setBody("""{"count":3,"next":"http://other.example/api/tags/?offset=2","results":[{"name":"reading"},{"name":"work notes"}]}"""))
            server.enqueue(MockResponse().setBody("""{"count":3,"next":null,"results":[{"name":"research"}]}"""))

            assertEquals(setOf("reading", "work notes", "research"),
                tls.api().listTags(server.url("/linkding/").toString(), "secret").toSet())
            assertEquals("/linkding/api/tags/?limit=100&offset=0", server.takeRequest().path)
            val second = server.takeRequest()
            assertEquals("/linkding/api/tags/?limit=100&offset=2", second.path)
            assertEquals("Token secret", second.getHeader("Authorization"))
        }
    }

    @Test fun tagListFailureDoesNotProducePartialSuggestions() = runBlocking {
        TlsMockServer().use { tls ->
            val server = tls.server
            server.enqueue(MockResponse().setBody("""{"count":2,"next":"more","results":[{"name":"reading"}]}"""))
            server.enqueue(MockResponse().setResponseCode(503))
            try {
                tls.api().listTags(server.url("/").toString(), "secret")
                fail("A failed page must fail the whole tag list")
            } catch (error: ApiException) {
                assertEquals(503, error.code)
            }
        }
    }

    @Test fun rejectsHttpLinkdingBeforeSendingTokenOrBookmark() = runBlocking {
        MockWebServer().use { server ->
            val error = runCatching {
                LinkdingApi().send(server.url("/").toString(), "secret",
                    Bookmark(url = "http://example.com/article"), Settings())
            }.exceptionOrNull()
            assertEquals("Use an HTTPS linkding server URL", error?.message)
            assertEquals(0, server.requestCount)
        }
    }
}
