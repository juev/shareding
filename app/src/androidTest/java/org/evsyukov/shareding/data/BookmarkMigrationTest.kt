package org.evsyukov.shareding.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkMigrationTest {
    @Test fun upgradePreservesQueuedDataAndTreatsUnknownTitlesAsAutomatic() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${System.nanoTime()}.db"
        val oldTitle = "Site\nReal title\nPreview"
        context.openOrCreateDatabase(name, 0, null).use { legacy ->
            legacy.execSQL("""CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, url TEXT NOT NULL,
                title TEXT NOT NULL, description TEXT NOT NULL, notes TEXT NOT NULL,
                tags TEXT NOT NULL, unread INTEGER NOT NULL, archived INTEGER NOT NULL,
                metadataFetched INTEGER NOT NULL, createdAt INTEGER NOT NULL,
                status TEXT NOT NULL, attempts INTEGER NOT NULL, lastAttemptAt INTEGER,
                lastError TEXT)""")
            legacy.execSQL("CREATE UNIQUE INDEX index_bookmarks_url ON bookmarks(url)")
            legacy.execSQL("INSERT INTO bookmarks VALUES (7, ?, ?, 'Summary', 'Notes', 'inbox', 1, 0, 1, 123, 'failed', 2, 456, 'HTTP 400')",
                arrayOf("https://example.com/legacy", oldTitle))
            legacy.version = 1
        }
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
        try {
            val entry = upgraded.bookmarks().batch(1).single()
            assertEquals(7L, entry.id)
            assertEquals(oldTitle, entry.title)
            assertEquals(false, entry.sendTitle)
            assertEquals("Summary", entry.description)
            assertEquals("Notes", entry.notes)
            assertEquals("inbox", entry.tags)
            assertEquals("failed", entry.status)
            assertEquals(2, entry.attempts)
            assertEquals("HTTP 400", entry.lastError)
            val id = upgraded.bookmarks().insert(Bookmark(url = "https://example.com/manual",
                title = "Manual", sendTitle = true))
            assertEquals(true, upgraded.bookmarks().findByUrl("https://example.com/manual")?.sendTitle)
            upgraded.bookmarks().delete(id)
            assertEquals(0, upgraded.bookmarks().markSyncing(id))
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun upgradeDropsStoredFlagsAndKeepsTheQueue() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-${System.nanoTime()}.db"
        context.openOrCreateDatabase(name, 0, null).use { legacy ->
            legacy.execSQL("""CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, url TEXT NOT NULL,
                title TEXT NOT NULL, sendTitle INTEGER NOT NULL DEFAULT 0,
                description TEXT NOT NULL, notes TEXT NOT NULL,
                tags TEXT NOT NULL, unread INTEGER NOT NULL, archived INTEGER NOT NULL,
                metadataFetched INTEGER NOT NULL, createdAt INTEGER NOT NULL,
                status TEXT NOT NULL, attempts INTEGER NOT NULL, lastAttemptAt INTEGER,
                lastError TEXT)""")
            legacy.execSQL("CREATE UNIQUE INDEX index_bookmarks_url ON bookmarks(url)")
            legacy.execSQL("INSERT INTO bookmarks VALUES (7, 'https://example.com/queued', 'Manual', 1, 'Summary', 'Notes', 'inbox, work', 0, 1, 1, 123, 'failed', 2, 456, 'HTTP 401')")
            legacy.version = 2
        }
        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
        try {
            assertEquals(Bookmark(id = 7, url = "https://example.com/queued", title = "Manual",
                sendTitle = true, description = "Summary", notes = "Notes", tags = "inbox, work",
                metadataFetched = true, createdAt = 123, status = "failed", attempts = 2,
                lastAttemptAt = 456, lastError = "HTTP 401"), upgraded.bookmarks().batch(1).single())
            val columns = mutableListOf<String>()
            upgraded.openHelper.readableDatabase.query("PRAGMA table_info(bookmarks)").use { cursor ->
                while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertEquals(false, "unread" in columns || "archived" in columns)
            assertEquals(-1L, upgraded.bookmarks().insert(Bookmark(url = "https://example.com/queued")))
            // The rebuilt table keeps counting ids after the migrated rows.
            assertEquals(true, upgraded.bookmarks().insert(Bookmark(url = "https://example.com/next")) > 7)
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
