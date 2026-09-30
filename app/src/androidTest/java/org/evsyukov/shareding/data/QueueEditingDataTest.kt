package org.evsyukov.shareding.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueEditingDataTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: BookmarkDao

    @Before fun createDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.bookmarks()
    }

    @After fun closeDatabase() {
        db.close()
    }

    @Test fun editUpdatesRequestedFieldsAndPreservesQueueState() = runBlocking {
        val original = Bookmark(
            url = "https://example.com/old",
            title = "Old title",
            sendTitle = false,
            description = "Old description",
            notes = "Keep these notes",
            tags = "old-tag",
            unread = true,
            archived = true,
            metadataFetched = true,
            createdAt = 1_234_567L,
            status = "failed",
            attempts = 4,
            lastAttemptAt = 1_234_000L,
            lastError = "HTTP 503",
        )
        val id = dao.insert(original)

        assertEquals(1, dao.edit(
            id = id,
            url = "https://example.com/new",
            title = "New title",
            sendTitle = true,
            description = "New description",
            tags = "new-tag, reading",
        ))

        assertEquals(original.copy(
            id = id,
            url = "https://example.com/new",
            title = "New title",
            sendTitle = true,
            description = "New description",
            tags = "new-tag, reading",
            status = "pending",
            attempts = 0,
            lastAttemptAt = null,
            lastError = null,
        ), dao.findById(id))
    }

    @Test fun duplicateUrlThrowsAndLeavesBothRowsUnchanged() = runBlocking {
        val first = Bookmark(
            url = "https://example.com/first", title = "First", notes = "First notes",
            unread = true, createdAt = 100L, status = "failed", attempts = 2,
            lastAttemptAt = 90L, lastError = "First error",
        )
        val second = Bookmark(
            url = "https://example.com/second", title = "Second", notes = "Second notes",
            archived = true, createdAt = 200L, metadataFetched = true,
        )
        val firstId = dao.insert(first)
        val secondId = dao.insert(second)

        try {
            dao.edit(firstId, second.url, "Changed", true, "Changed description", "changed")
            fail("Editing to an existing URL must fail")
        } catch (_: SQLiteConstraintException) {
            // The unique URL constraint rejects the update atomically.
        }

        assertEquals(2, dao.count())
        assertEquals(first.copy(id = firstId), dao.findById(firstId))
        assertEquals(second.copy(id = secondId), dao.findById(secondId))
    }

    @Test fun editingMissingIdReturnsZeroWithoutInserting() = runBlocking {
        assertEquals(0, dao.edit(
            id = 9876L,
            url = "https://example.com/missing",
            title = "Title",
            sendTitle = true,
            description = "Description",
            tags = "tag",
        ))

        assertEquals(0, dao.count())
        assertNull(dao.findById(9876L))
    }
}
