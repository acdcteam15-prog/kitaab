package com.pdfreader.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per (book, owner). Room is the single source of truth for pending uploads:
 * a row with isSynced = false IS the upload queue (no separate queue files).
 *
 * Field groups are merged independently during sync so one device can never wipe
 * another device's newer data:
 *  - reading position  → newest lastReadTimestamp wins
 *  - position pin      → newest pinUpdatedAt wins
 *  - finished state    → newest finishedUpdatedAt wins
 *  - reading time      → max()
 */
@Entity(tableName = "reading_progress")
data class ReadingProgress(
    @PrimaryKey
    val bookId: String,
    val ownerName: String,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val scrollOffset: Float = 0f,
    val lastReadTimestamp: Long = System.currentTimeMillis(),
    val totalReadingTimeMs: Long = 0L,
    val isSynced: Boolean = false,
    val bookName: String = "",
    // Reading position pin: page index (-1 = not set) and vertical ratio (0.0–1.0)
    val positionPinPage: Int = -1,
    val positionPinYRatio: Float = -1f,
    // v4 ────────────────────────────────────────────────────────────────────
    /** Local modification stamp — used so an in-flight upload never marks a newer edit as synced. */
    val updatedAt: Long = System.currentTimeMillis(),
    /** When the pin was last set/cleared (0 = never touched). */
    val pinUpdatedAt: Long = 0L,
    /** When the book was marked finished (0 = not finished). */
    val finishedAt: Long = 0L,
    /** When the finished flag last changed (finish / read-again). */
    val finishedUpdatedAt: Long = 0L
) {
    val isFinished: Boolean get() = finishedAt > 0L
    val percent: Int get() = if (totalPages > 0) ((currentPage + 1f) / totalPages * 100).toInt().coerceIn(0, 100) else 0
}
