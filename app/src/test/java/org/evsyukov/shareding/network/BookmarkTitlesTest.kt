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

    @Test fun sharedLinkSplitByWhitespaceIsDropped() {
        // Mastodon builds a link from a hidden scheme, a visible part and a hidden tail.
        assertEquals("", BookmarkTitles.shared(
            "https:// buttondown.com/chadkoh/archive /getting-my-hands-dirty/",
            "https://buttondown.com/chadkoh/archive/getting-my-hands-dirty/"))
        assertEquals("", BookmarkTitles.shared("http://www. youtube.com/watch?v=GxG2NG1ZLtw",
            "http://www.youtube.com/watch?v=GxG2NG1ZLtw"))
        assertEquals("", BookmarkTitles.shared("https://\nexample.com/a\n?utm_source=feed&id=5",
            "https://example.com/a?id=5"))
        assertEquals("", BookmarkTitles.shared("example.com/news /2026/9...",
            "https://example.com/news/2026/9/article/"))
    }

    @Test fun sharedTitleStartingWithALinkIsKept() {
        assertEquals("https://example.com is down",
            BookmarkTitles.shared("https://example.com is down", "https://example.com"))
        assertEquals("https:// other.example/page",
            BookmarkTitles.shared("https:// other.example/page", "https://example.com/page"))
    }

    @Test fun sharedTitleWithTextIsKeptUnchanged() {
        assertEquals("Read this:\nhttps://example.com",
            BookmarkTitles.shared("Read this:\nhttps://example.com", "https://example.com"))
        assertEquals(" Article\ttitle ", BookmarkTitles.shared(" Article\ttitle ", "https://example.com/a"))
    }
}
