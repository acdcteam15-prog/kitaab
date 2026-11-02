package com.pdfreader.app.data.local

import androidx.room.*
import com.pdfreader.app.data.model.Highlight
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND ownerName = :owner AND isDeleted = 0 ORDER BY page, top")
    fun forBook(bookId: String, owner: String): Flow<List<Highlight>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId AND ownerName = :owner")
    suspend fun allIncludingDeleted(bookId: String, owner: String): List<Highlight>

    @Query("SELECT * FROM highlights WHERE ownerName = :owner")
    suspend fun allForOwner(owner: String): List<Highlight>

    @Query("SELECT * FROM highlights WHERE uid = :uid LIMIT 1")
    suspend fun byUid(uid: String): Highlight?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(h: Highlight)

    @Query("SELECT DISTINCT bookId FROM highlights WHERE ownerName = :owner AND isSynced = 0")
    suspend fun booksWithUnsynced(owner: String): List<String>

    @Query("UPDATE highlights SET isSynced = 1 WHERE uid = :uid AND updatedAt = :updatedAt")
    suspend fun markSyncedIfUnchanged(uid: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM highlights WHERE ownerName = :owner AND isSynced = 0")
    fun countUnsyncedFlow(owner: String): Flow<Int>
}
