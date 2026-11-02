package com.pdfreader.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Bookmarks are synced per book as a single JSON file on Drive and merged by [uid].
 * Deletions are soft (tombstones, isDeleted = true) so a delete on one device
 * is propagated instead of the bookmark being resurrected by the other device.
 */
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["uid"], unique = true)]
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val bookId: String,
    val ownerName: String,
    val pageNumber: Int,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
    // v4 ────────────────────────────────────────────────────────────────────
    val uid: String = java.util.UUID.randomUUID().toString(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val isSynced: Boolean = false
)
