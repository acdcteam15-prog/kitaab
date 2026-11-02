package com.pdfreader.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.pdfreader.app.data.model.SyncEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncEventDao {
    @Insert
    suspend fun insert(e: SyncEvent)

    @Query("SELECT * FROM sync_events WHERE ownerName = :owner ORDER BY time DESC LIMIT 300")
    fun recent(owner: String): Flow<List<SyncEvent>>

    @Query("DELETE FROM sync_events WHERE id NOT IN (SELECT id FROM sync_events ORDER BY time DESC LIMIT 300)")
    suspend fun trim()

    @Query("DELETE FROM sync_events WHERE ownerName = :owner")
    suspend fun clear(owner: String)
}
