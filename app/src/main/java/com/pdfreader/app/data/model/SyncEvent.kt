package com.pdfreader.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One line in the Sync History screen. Kept locally only (last 300). */
@Entity(tableName = "sync_events", indices = [Index("ownerName"), Index("time")])
data class SyncEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerName: String,
    val time: Long = System.currentTimeMillis(),
    /** PUSH, PULL, CONFLICT, ERROR, SYNC */
    val type: String,
    val bookId: String = "",
    val bookName: String = "",
    val detail: String = ""
)
