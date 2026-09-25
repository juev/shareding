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

    @Test fun acceptsHttpServerAndRejectsCredentials() {
        assertEquals("http://example.com/linkding/", Urls.server("http://example.com/linkding").toString())
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
