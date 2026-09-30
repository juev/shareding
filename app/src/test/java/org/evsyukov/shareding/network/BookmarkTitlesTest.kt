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
}
