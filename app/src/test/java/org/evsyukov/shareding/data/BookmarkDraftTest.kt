package org.evsyukov.shareding.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkDraftTest {
    @Test fun changingOtherFieldsPreservesCompleteSharedPreviewAndOmittedTitle() {
        val original = Bookmark(id = 12, url = "https://example.com/old",
            title = "Bitwarden\nActual title\n" + "Content ".repeat(150), sendTitle = false,
            notes = "notes", unread = true, archived = true, metadataFetched = true, createdAt = 42,
            status = "failed", attempts = 3, lastError = "HTTP 400", lastAttemptAt = 45)
        val edited = BookmarkDraft(original).copy(url = " https://example.com/new ",
            description = " Description ", tags = "inbox, reading, inbox").applyTo(original)
        assertEquals(original.copy(url = "https://example.com/new", description = "Description",
            tags = "inbox, reading", status = "pending", attempts = 0, lastError = null,
            lastAttemptAt = null), edited)
    }

    @Test fun editedUrlDropsTrackingParameters() {
        val original = Bookmark(url = "https://example.com/old")
        val edited = BookmarkDraft(original).copy(url = "https://example.com/new?utm_medium=x&page=2")
            .applyTo(original)
        assertEquals("https://example.com/new?page=2", edited.url)
    }

    @Test fun changedTitleBecomesManualSingleLineAndLimitedTo512CodePoints() {
        val original = Bookmark(url = "https://example.com", title = "Shared\nPreview")
        val edited = BookmarkDraft(original).copy(title = "  Custom\n  title " + "😀".repeat(600))
            .applyTo(original)
        assertTrue(edited.sendTitle)
        assertTrue(edited.title.startsWith("Custom title "))
        assertEquals(512, edited.title.codePointCount(0, edited.title.length))
        assertFalse(edited.title.contains('\n'))
    }

    @Test fun unchangedManualTitleRemainsManual() {
        val original = Bookmark(url = "https://example.com", title = "Manual title", sendTitle = true)
        val edited = BookmarkDraft(original).copy(description = "new").applyTo(original)
        assertEquals("Manual title", edited.title)
        assertTrue(edited.sendTitle)
    }

    @Test fun clearingManualTitleLetsLinkdingFetchIt() {
        val original = Bookmark(url = "https://example.com", title = "Manual", sendTitle = true)
        val edited = BookmarkDraft(original).copy(title = " \n ").applyTo(original)
        assertEquals("", edited.title)
        assertFalse(edited.sendTitle)
    }

    @Test fun tagTooLongForLinkdingIsRejected() {
        val original = Bookmark(url = "https://example.com")
        val draft = BookmarkDraft(original).copy(tags = "ok, " + "a".repeat(65))
        assertThrows(IllegalArgumentException::class.java) { draft.applyTo(original) }
    }
}
