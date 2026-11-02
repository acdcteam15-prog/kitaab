package com.pdfreader.app.data.local

import androidx.room.*
import com.pdfreader.app.data.model.ReadingProgress
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId AND ownerName = :owner LIMIT 1")
    suspend fun getProgress(bookId: String, owner: String): ReadingProgress?

    @Query("SELECT * FROM reading_progress WHERE ownerName = :owner ORDER BY lastReadTimestamp DESC")
    fun getAllProgressForOwner(owner: String): Flow<List<ReadingProgress>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: ReadingProgress)

    @Query("DELETE FROM reading_progress WHERE bookId = :bookId AND ownerName = :owner")
    suspend fun deleteProgress(bookId: String, owner: String)

    /**
     * Mark synced ONLY if the row was not modified while the upload was in flight.
     * Returns the number of rows updated (0 = a newer local edit exists → stays pending).
     */
    @Query("UPDATE reading_progress SET isSynced = 1 WHERE bookId = :bookId AND ownerName = :owner AND updatedAt = :updatedAt")
    suspend fun markSyncedIfUnchanged(bookId: String, owner: String, updatedAt: Long): Int

    @Query("SELECT * FROM reading_progress WHERE ownerName = :owner")
    suspend fun getAllProgressOnce(owner: String): List<ReadingProgress>

    @Query("SELECT * FROM reading_progress WHERE ownerName = :owner AND isSynced = 0")
    suspend fun getUnsynced(owner: String): List<ReadingProgress>

    @Query("SELECT COUNT(*) FROM reading_progress WHERE ownerName = :owner AND isSynced = 0")
    fun countUnsyncedFlow(owner: String): Flow<Int>

    @Query("SELECT * FROM reading_progress WHERE ownerName = :owner AND finishedAt > 0 ORDER BY finishedAt DESC")
    fun getFinishedFlow(owner: String): Flow<List<ReadingProgress>>
}
