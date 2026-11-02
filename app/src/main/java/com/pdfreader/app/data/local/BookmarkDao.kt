package com.pdfreader.app.data.local

import androidx.room.*
import com.pdfreader.app.data.model.Bookmark
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND ownerName = :owner AND isDeleted = 0 ORDER BY pageNumber ASC")
    fun getBookmarks(bookId: String, owner: String): Flow<List<Bookmark>>

    /** Includes tombstones — used to build the Drive payload. */
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId AND ownerName = :owner")
    suspend fun getAllIncludingDeleted(bookId: String, owner: String): List<Bookmark>

    @Query("SELECT * FROM bookmarks WHERE ownerName = :owner")
    suspend fun allForOwner(owner: String): List<Bookmark>

    @Query("SELECT * FROM bookmarks WHERE uid = :uid LIMIT 1")
    suspend fun getByUid(uid: String): Bookmark?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: Bookmark)

    @Query("SELECT COUNT(*) FROM bookmarks WHERE bookId = :bookId AND ownerName = :owner AND pageNumber = :page AND isDeleted = 0")
    suspend fun isPageBookmarked(bookId: String, owner: String, page: Int): Int

    /** Soft-delete every bookmark on a page (tombstone so the delete syncs). */
    @Query("UPDATE bookmarks SET isDeleted = 1, isSynced = 0, updatedAt = :now WHERE bookId = :bookId AND ownerName = :owner AND pageNumber = :page AND isDeleted = 0")
    suspend fun softDeletePage(bookId: String, owner: String, page: Int, now: Long): Int

    @Query("UPDATE bookmarks SET isDeleted = 1, isSynced = 0, updatedAt = :now WHERE uid = :uid")
    suspend fun softDeleteUid(uid: String, now: Long)

    @Query("SELECT DISTINCT bookId FROM bookmarks WHERE ownerName = :owner AND isSynced = 0")
    suspend fun booksWithUnsynced(owner: String): List<String>

    @Query("UPDATE bookmarks SET isSynced = 1 WHERE uid = :uid AND updatedAt = :updatedAt")
    suspend fun markSyncedIfUnchanged(uid: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM bookmarks WHERE ownerName = :owner AND isSynced = 0")
    fun countUnsyncedFlow(owner: String): Flow<Int>
}
