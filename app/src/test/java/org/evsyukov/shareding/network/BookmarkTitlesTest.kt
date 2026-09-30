package org.evsyukov.shareding.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BookmarkTitlesTest {
    @Test fun normalizesWhitespaceAndKeepsAtMost512UnicodeCodePoints() {
        val normalized = BookmarkTitles.normalize("first\tline\n" + "😀".repeat(600))

        assertEquals(BookmarkTitles.MAX_LENGTH, normalized.codePointCount(0, normalized.length))
        assertEquals("first line", normalized.substring(0, "first line".length))
        assertFalse(normalized.last().isHighSurrogate())
    }

    @Test fun blankTitleNormalizesToEmptyString() {
        assertEquals("", BookmarkTitles.normalize(" \n\t "))
    }

    @Test fun sharedTitleThatIsJustTheLinkIsDropped() {
        val url = "https://example.com/article"
        assertEquals("", BookmarkTitles.shared("https://example.com/article", url))
        assertEquals("", BookmarkTitles.shared(" HTTP://other.example/page\n", url))
        assertEquals("", BookmarkTitles.shared("www.example.com/article/", url))
        assertEquals("", BookmarkTitles.shared("example.com/article", url))
    }

    @Test fun sharedShortenedLinkLabelIsDropped() {
        val url = "https://alternativeto.net/news/2026/9/openshot-4-0-1-released/"
        assertEquals("", BookmarkTitles.shared("alternativeto.net/news/2026/9...", url))
        assertEquals("", BookmarkTitles.shared("alternativeto.net/news/2026/9…", url))
        assertEquals("Release notes...", BookmarkTitles.shared("Release notes...", url))
        assertEquals("other.net/news...", BookmarkTitles.shared("other.net/news...", url))
    }

    @Test fun sharedTitleWithTextIsKeptUnchanged() {
        assertEquals("Read this:\nhttps://example.com",
            BookmarkTitles.shared("Read this:\nhttps://example.com", "https://example.com"))
        assertEquals(" Article\ttitle ", BookmarkTitles.shared(" Article\ttitle ", "https://example.com/a"))
    }
}
