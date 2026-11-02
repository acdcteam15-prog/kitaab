package com.pdfreader.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A highlighted area on a page, optionally with a note.
 * The rectangle is stored as FRACTIONS of the page (0..1), so it stays in place at any
 * zoom, crop, orientation or screen size — and on the other device.
 * Synced like bookmarks (per-book JSON on Drive, merged by [uid], soft deletes).
 */
@Entity(
    tableName = "highlights",
    indices = [Index(value = ["uid"], unique = true), Index("bookId")]
)
data class Highlight(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val uid: String = java.util.UUID.randomUUID().toString(),
    val bookId: String,
    val ownerName: String,
    val page: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val color: Int,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val isSynced: Boolean = false
) {
    companion object {
        /** Highlighter colours: amber, green, blue, pink. */
        val COLORS = intArrayOf(0xFFFFC928.toInt(), 0xFF7BD88F.toInt(), 0xFF6EC1FF.toInt(), 0xFFFF8FB8.toInt())
    }
}
