package com.pdfreader.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pdfreader.app.data.model.Bookmark
import com.pdfreader.app.data.model.BookCategory
import com.pdfreader.app.data.model.ReadingProgress
import com.pdfreader.app.data.model.SyncEvent
import com.pdfreader.app.data.model.Highlight

@Database(
    entities = [ReadingProgress::class, Bookmark::class, BookCategory::class, SyncEvent::class, Highlight::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun bookCategoryDao(): BookCategoryDao
    abstract fun syncEventDao(): SyncEventDao
    abstract fun highlightDao(): HighlightDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN bookName TEXT NOT NULL DEFAULT ''")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS book_categories (
                        bookId TEXT NOT NULL PRIMARY KEY,
                        ownerName TEXT NOT NULL,
                        category TEXT NOT NULL DEFAULT 'Uncategorized',
                        lastReadTimestamp INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        // Migration v2 → v3: add position pin columns
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN positionPinPage INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN positionPinYRatio REAL NOT NULL DEFAULT -1.0")
            }
        }

        // Migration v3 → v4: field-level sync stamps, finished shelf, bookmark sync
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // reading_progress
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN pinUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN finishedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN finishedUpdatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE reading_progress SET updatedAt = lastReadTimestamp")
                // Existing pins get their row timestamp so they win against a never-touched remote pin
                db.execSQL("UPDATE reading_progress SET pinUpdatedAt = lastReadTimestamp WHERE positionPinPage >= 0")

                // bookmarks
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN isSynced INTEGER NOT NULL DEFAULT 0")
                // Deterministic legacy uid → identical on both devices, so they merge instead of duplicating
                db.execSQL("UPDATE bookmarks SET uid = 'legacy_' || bookId || '_' || pageNumber || '_' || createdAt, updatedAt = createdAt")
                // Remove accidental duplicates before the unique index is created
                db.execSQL("DELETE FROM bookmarks WHERE id NOT IN (SELECT MIN(id) FROM bookmarks GROUP BY uid)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bookmarks_uid ON bookmarks(uid)")
            }
        }

        // Migration v4 → v5: sync history
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ownerName TEXT NOT NULL,
                        time INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        bookId TEXT NOT NULL,
                        bookName TEXT NOT NULL,
                        detail TEXT NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_events_ownerName ON sync_events(ownerName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_events_time ON sync_events(time)")
            }
        }

        // Migration v5 → v6: highlights & notes
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS highlights (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        uid TEXT NOT NULL,
                        bookId TEXT NOT NULL,
                        ownerName TEXT NOT NULL,
                        page INTEGER NOT NULL,
                        `left` REAL NOT NULL,
                        `top` REAL NOT NULL,
                        `right` REAL NOT NULL,
                        `bottom` REAL NOT NULL,
                        color INTEGER NOT NULL,
                        note TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        isDeleted INTEGER NOT NULL,
                        isSynced INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_highlights_uid ON highlights(uid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_highlights_bookId ON highlights(bookId)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pdfreader_db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    // NOTE: no fallbackToDestructiveMigration() — a missing migration must crash
                    // loudly in testing rather than silently wiping bookmarks and progress.
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
