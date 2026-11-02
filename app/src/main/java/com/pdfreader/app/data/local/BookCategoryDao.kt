package com.pdfreader.app.data.local

import androidx.room.*
import com.pdfreader.app.data.model.BookCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface BookCategoryDao {

    @Query("SELECT * FROM book_categories WHERE ownerName = :owner")
    fun getAllForOwner(owner: String): Flow<List<BookCategory>>

    @Query("SELECT * FROM book_categories WHERE bookId = :bookId AND ownerName = :owner LIMIT 1")
    suspend fun getCategory(bookId: String, owner: String): BookCategory?

    @Query("SELECT * FROM book_categories WHERE ownerName = :owner")
    suspend fun getAllOnce(owner: String): List<BookCategory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCategory(category: BookCategory)

    @Query("UPDATE book_categories SET lastReadTimestamp = :ts WHERE bookId = :bookId AND ownerName = :owner")
    suspend fun updateLastRead(bookId: String, owner: String, ts: Long)
}
