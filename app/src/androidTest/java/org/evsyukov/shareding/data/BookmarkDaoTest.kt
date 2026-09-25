package org.evsyukov.shareding.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkDaoTest {
    @Test fun duplicateKeepsOriginalAndInterruptedEntryIsRecovered() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.bookmarks()
            val first = dao.insert(Bookmark(url = "https://example.com", title = "Original"))
            assertEquals(-1L, dao.insert(Bookmark(url = "https://example.com", title = "Changed")))
            dao.markSyncing(first)
            dao.recoverInterrupted()
            assertEquals("Original", dao.batch(10).single().title)
            assertEquals("pending", dao.batch(10).single().status)
            dao.markFailed(first, "HTTP 503")
            assertEquals(1, dao.count())
            assertEquals("HTTP 503", dao.batch(10).single().lastError)
            dao.delete(first)
            assertEquals(0, dao.count())
        } finally {
            db.close()
        }
    }
}
