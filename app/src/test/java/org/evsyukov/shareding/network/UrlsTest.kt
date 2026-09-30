package org.evsyukov.shareding.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class UrlsTest {
    @Test fun extractsSharedLinkAndTrimsPunctuation() {
        assertEquals("https://example.com/article", Urls.sharedText("Read https://example.com/article)."))
        assertNull(Urls.sharedText("just a message"))
    }

    @Test fun bookmarkUrlsDropTrackingParameters() {
        assertEquals("https://example.com/a?id=5",
            Urls.sharedText("see https://example.com/a?utm_source=share&id=5 thanks"))
        assertEquals("https://youtu.be/abc", Urls.bookmark(" https://youtu.be/abc?si=xyz "))
    }

    @Test fun serverRequiresHttpsButBookmarksMayUseHttp() {
        assertEquals("https://example.com/linkding/", Urls.server("https://example.com/linkding").toString())
        assertEquals("http://example.com/article", Urls.parse("http://example.com/article").toString())
        try {
            Urls.server("http://example.com/linkding")
            fail("HTTP linkding servers must be rejected")
        } catch (error: IllegalArgumentException) {
            assertEquals("Use an HTTPS linkding server URL", error.message)
        }
        try {
            Urls.server("https://user:pass@example.com")
            fail("Credentials must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun tagsKeepSpacesAndMergeDefaults() {
        assertEquals("two words, one", TagNames.combine("two words", "one, two words"))
    }

    @Test fun tagSuggestionsReplaceOnlyTheCurrentName() {
        val available = listOf("work", "reading", "read later", "research")
        assertEquals(listOf("reading", "read later"),
            TagNames.suggestions("work, rea", available))
        assertEquals("work, reading, ", TagNames.complete("work, rea", "reading"))
        assertEquals(emptyList<String>(), TagNames.suggestions("work, ", available))
        assertEquals("reading, ", TagNames.complete("rea", "reading"))
    }
}
