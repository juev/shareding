package org.evsyukov.shareding.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bookmarks", indices = [Index(value = ["url"], unique = true)])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String = "",
    @ColumnInfo(defaultValue = "0") val sendTitle: Boolean = false,
    val description: String = "",
    val notes: String = "",
    /** Tags entered for this link only; default tags and flags are applied when it is sent. */
    val tags: String = "",
    val metadataFetched: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "pending",
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
)

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: Bookmark): Long

    @Query("SELECT * FROM bookmarks ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun batch(limit: Int): List<Bookmark>

    @Query("SELECT COUNT(*) FROM bookmarks")
    suspend fun count(): Int

    @Query("SELECT MIN(createdAt) FROM bookmarks")
    suspend fun oldestCreatedAt(): Long?

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): Bookmark?

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE bookmarks SET status = 'syncing', attempts = attempts + 1, lastAttemptAt = :at, lastError = NULL WHERE id = :id")
    suspend fun markSyncing(id: Long, at: Long = System.currentTimeMillis()): Int

    @Query("UPDATE bookmarks SET status = 'failed', lastError = :message WHERE id = :id")
    suspend fun markFailed(id: Long, message: String)

    @Query("UPDATE bookmarks SET status = 'pending' WHERE id = :id")
    suspend fun markPending(id: Long)

    @Query("UPDATE bookmarks SET status = 'pending' WHERE status = 'syncing'")
    suspend fun recoverInterrupted()

    @Query("UPDATE bookmarks SET title = :title, metadataFetched = 1 WHERE id = :id AND title = ''")
    suspend fun updateTitleIfEmpty(id: Long, title: String)

    @Query("UPDATE bookmarks SET metadataFetched = 1 WHERE id = :id")
    suspend fun markMetadataFetched(id: Long)
}

@Database(entities = [Bookmark::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarks(): BookmarkDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN sendTitle INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Drops `unread` and `archived`: both now come from settings when a link is sent. The table
         * is rebuilt because SQLite before 3.35 (Android 13 and older) cannot drop a column.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val columns = "id, url, title, sendTitle, description, notes, tags, metadataFetched, createdAt, status, attempts, lastAttemptAt, lastError"
                db.execSQL("""CREATE TABLE bookmarks_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, url TEXT NOT NULL,
                    title TEXT NOT NULL, sendTitle INTEGER NOT NULL DEFAULT 0,
                    description TEXT NOT NULL, notes TEXT NOT NULL, tags TEXT NOT NULL,
                    metadataFetched INTEGER NOT NULL, createdAt INTEGER NOT NULL,
                    status TEXT NOT NULL, attempts INTEGER NOT NULL, lastAttemptAt INTEGER,
                    lastError TEXT)""")
                db.execSQL("INSERT INTO bookmarks_new ($columns) SELECT $columns FROM bookmarks")
                db.execSQL("DROP TABLE bookmarks")
                db.execSQL("ALTER TABLE bookmarks_new RENAME TO bookmarks")
                db.execSQL("CREATE UNIQUE INDEX index_bookmarks_url ON bookmarks(url)")
            }
        }
    }
}
