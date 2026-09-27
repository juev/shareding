package org.evsyukov.shareding.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bookmarks", indices = [Index(value = ["url"], unique = true)])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String = "",
    val description: String = "",
    val notes: String = "",
    val tags: String = "",
    val unread: Boolean = false,
    val archived: Boolean = false,
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

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): Bookmark?

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE bookmarks SET status = 'syncing', attempts = attempts + 1, lastAttemptAt = :at, lastError = NULL WHERE id = :id")
    suspend fun markSyncing(id: Long, at: Long = System.currentTimeMillis())

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

@Database(entities = [Bookmark::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookmarks(): BookmarkDao
}
