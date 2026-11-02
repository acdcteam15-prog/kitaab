package com.pdfreader.app.data.sync

import com.pdfreader.app.data.model.Bookmark
import com.pdfreader.app.data.model.ReadingProgress

/**
 * The sync conflict rules, as pure functions (no Android, no Drive, no Room) so they
 * can be unit-tested. Every change to sync behaviour should start with a test here.
 *
 * Rules
 *  • reading position (page, offset, name) → newest lastReadTimestamp wins
 *  • pin                                   → newest pinUpdatedAt wins
 *  • finished / read-again                 → newest finishedUpdatedAt wins
 *  • total pages, reading time             → max()
 *  • bookmarks                             → per uid, newest updatedAt wins; deletes are tombstones
 */
object SyncMerge {

    fun merge(local: ReadingProgress, cloud: ReadingProgress): ReadingProgress {
        val pos = if (cloud.lastReadTimestamp > local.lastReadTimestamp) cloud else local
        val pin = if (cloud.pinUpdatedAt > local.pinUpdatedAt) cloud else local
        val fin = if (cloud.finishedUpdatedAt > local.finishedUpdatedAt) cloud else local
        return local.copy(
            bookName           = pos.bookName.ifEmpty { local.bookName.ifEmpty { cloud.bookName } },
            currentPage        = pos.currentPage,
            totalPages         = maxOf(local.totalPages, cloud.totalPages),
            scrollOffset       = pos.scrollOffset,
            lastReadTimestamp  = pos.lastReadTimestamp,
            totalReadingTimeMs = maxOf(local.totalReadingTimeMs, cloud.totalReadingTimeMs),
            positionPinPage    = pin.positionPinPage,
            positionPinYRatio  = pin.positionPinYRatio,
            pinUpdatedAt       = pin.pinUpdatedAt,
            finishedAt         = fin.finishedAt,
            finishedUpdatedAt  = fin.finishedUpdatedAt
        )
    }

    /** Compares only the fields that sync (ignores isSynced / updatedAt). */
    fun sameContent(a: ReadingProgress, b: ReadingProgress) =
        a.currentPage == b.currentPage && a.totalPages == b.totalPages &&
        a.lastReadTimestamp == b.lastReadTimestamp && a.totalReadingTimeMs == b.totalReadingTimeMs &&
        a.positionPinPage == b.positionPinPage && a.positionPinYRatio == b.positionPinYRatio &&
        a.pinUpdatedAt == b.pinUpdatedAt && a.finishedAt == b.finishedAt &&
        a.finishedUpdatedAt == b.finishedUpdatedAt

    /** Result of applying a cloud record to the local row. */
    sealed class Apply {
        /** No local row: store cloud as-is (synced). */
        data class Insert(val row: ReadingProgress) : Apply()
        /** Store [row]; [synced] = cloud fully supersedes local. */
        data class Update(val row: ReadingProgress, val synced: Boolean) : Apply()
        object NoChange : Apply()
    }

    fun applyCloud(local: ReadingProgress?, cloud: ReadingProgress): Apply {
        if (local == null) return Apply.Insert(cloud.copy(isSynced = true))
        val merged = merge(local, cloud)
        if (sameContent(merged, local)) return Apply.NoChange
        return Apply.Update(merged, synced = sameContent(merged, cloud))
    }

    /** Legacy bookmarks (no uid) get the same deterministic id on both devices. */
    fun legacyUid(bookId: String, page: Int, createdAt: Long) = "legacy_${bookId}_${page}_${createdAt}"

    /** What to do with a remote bookmark: insert, overwrite local, or ignore. */
    enum class BookmarkAction { INSERT, OVERWRITE, IGNORE }

    /** Same rule for highlights: per uid, newest wins, deletes never resurrect. */
    fun decideHighlight(localUpdatedAt: Long?, remoteUpdatedAt: Long, remoteDeleted: Boolean): BookmarkAction = when {
        localUpdatedAt == null && !remoteDeleted -> BookmarkAction.INSERT
        localUpdatedAt == null                   -> BookmarkAction.IGNORE
        remoteUpdatedAt > localUpdatedAt         -> BookmarkAction.OVERWRITE
        else                                     -> BookmarkAction.IGNORE
    }

    fun decideBookmark(local: Bookmark?, remote: Bookmark): BookmarkAction = when {
        local == null && !remote.isDeleted          -> BookmarkAction.INSERT
        local == null                               -> BookmarkAction.IGNORE   // never resurrect a delete
        remote.updatedAt > local.updatedAt          -> BookmarkAction.OVERWRITE
        else                                        -> BookmarkAction.IGNORE
    }
}
