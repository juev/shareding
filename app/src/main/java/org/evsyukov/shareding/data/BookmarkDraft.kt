package org.evsyukov.shareding.data

import org.evsyukov.shareding.network.BookmarkTitles
import org.evsyukov.shareding.network.TagNames
import org.evsyukov.shareding.network.Urls

data class BookmarkDraft(
    val url: String,
    val title: String,
    val description: String,
    val tags: String,
) {
    constructor(bookmark: Bookmark) : this(bookmark.url, bookmark.title, bookmark.description, bookmark.tags)

    fun applyTo(original: Bookmark): Bookmark {
        val changedTitle = title != original.title
        val normalizedTitle = if (changedTitle) BookmarkTitles.normalize(title) else original.title
        return original.copy(url = Urls.bookmark(url), title = normalizedTitle,
            sendTitle = if (changedTitle) normalizedTitle.isNotBlank() else original.sendTitle,
            description = description.trim(), tags = TagNames.parse(tags).joinToString(", "),
            status = "pending", attempts = 0, lastAttemptAt = null, lastError = null)
    }
}
