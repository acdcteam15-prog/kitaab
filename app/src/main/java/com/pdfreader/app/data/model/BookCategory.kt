package com.pdfreader.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "book_categories")
data class BookCategory(
    @PrimaryKey
    val bookId: String,
    val ownerName: String,
    val category: String = "Uncategorized",
    val lastReadTimestamp: Long = 0L
)
