package org.evsyukov.shareding.network

import kotlinx.coroutines.runBlocking
import java.nio.charset.Charset
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageMetadataTest {
    @Test fun parsesTitleDescriptionAndKeywordsRegardlessOfAttributeOrder() {
        val html = """<html><head><title>Fallback</title>
            <meta content='A &amp; B' property='og:title'>
            <meta content='Useful &amp; brief' name='description'>
            <meta name='keywords' content='reading, work notes, reading'>
            </head></html>"""
        assertEquals(PageMetadata("A & B", "Useful & brief", "reading, work notes"),
            PageMetadataParser.parse(html))
    }

    @Test fun keywordsTooLongForLinkdingTagsAreSkipped() {
        val long = "a".repeat(TagNames.MAX_LENGTH + 1)
        val html = "<html><head><meta name='keywords' content='reading, $long, work'></head></html>"
        assertEquals("reading, work", PageMetadataParser.parse(html).tags)
    }

    @Test fun absentMetadataDoesNotEraseManualValues() {
        val found = PageMetadataParser.parse("<html><head><title>Page title</title></head></html>")
        assertEquals(PageMetadata("My title", "My notes", "local"),
            found.fillEmpty("My title", "My notes", "local"))
        assertEquals(PageMetadata("Page title", "My notes", "local"),
            found.fillEmpty("", "My notes", "local"))
    }

    @Test fun fetchesHtmlOnlyAndDoesNotFollowRedirect() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<title>Fetched</title><meta name='description' content='Summary'>"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/another"))
            assertEquals(PageMetadata("Fetched", "Summary", ""),
                PageMetadataFetcher().fetch(server.url("/page").toString()))
            assertNull(PageMetadataFetcher().fetch(server.url("/redirect").toString()))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun honorsHtmlMetaCharsetWhenResponseHeaderOmitsCharset() = runBlocking {
        MockWebServer().use { server ->
            val html = "<meta charset='windows-1252'><meta name='description' content='caf\u00e9'>"
            server.enqueue(MockResponse().addHeader("Content-Type", "text/html")
                .setBody(okio.Buffer().write(html.toByteArray(Charset.forName("windows-1252")))))

            assertEquals("café", PageMetadataFetcher().fetch(server.url("/page").toString())?.description)
        }
    }

    @Test fun httpCharsetTakesPrecedenceOverHtmlMetaCharset() = runBlocking {
        MockWebServer().use { server ->
            val html = "<meta charset='utf-8'><meta name='description' content='caf\u00e9'>"
            server.enqueue(MockResponse().addHeader("Content-Type", "text/html; charset=windows-1252")
                .setBody(okio.Buffer().write(html.toByteArray(Charset.forName("windows-1252")))))

            assertEquals("café", PageMetadataFetcher().fetch(server.url("/page").toString())?.description)
        }
    }
}
