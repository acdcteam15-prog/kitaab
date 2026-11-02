package com.pdfreader.app.data.sync

import com.pdfreader.app.data.model.Bookmark
import com.pdfreader.app.data.model.ReadingProgress
import com.pdfreader.app.data.sync.SyncMerge.Apply
import com.pdfreader.app.data.sync.SyncMerge.BookmarkAction
import org.junit.Assert.*
import org.junit.Test

/**
 * Guards the sync rules. Run with: ./gradlew testDebugUnitTest
 * Each test is a real scenario between two devices (A = this phone, B = the other one).
 */
class SyncMergeTest {

    private fun p(
        page: Int = 10, ts: Long = 1_000, pin: Int = -1, pinTs: Long = 0,
        fin: Long = 0, finTs: Long = 0, time: Long = 0, total: Int = 100, synced: Boolean = true
    ) = ReadingProgress(
        bookId = "b1", ownerName = "Bader", currentPage = page, totalPages = total,
        lastReadTimestamp = ts, totalReadingTimeMs = time, isSynced = synced,
        positionPinPage = pin, positionPinYRatio = if (pin >= 0) 0.5f else -1f,
        pinUpdatedAt = pinTs, finishedAt = fin, finishedUpdatedAt = finTs, updatedAt = ts
    )

    // ── Reading position ─────────────────────────────────────────────────────

    @Test fun newerCloudPositionWins() {
        val m = SyncMerge.merge(p(page = 10, ts = 1_000), p(page = 40, ts = 2_000))
        assertEquals(40, m.currentPage)
        assertEquals(2_000, m.lastReadTimestamp)
    }

    @Test fun newerLocalPositionIsKept() {
        // A read offline to page 80; B's older page 50 must not overwrite it
        val m = SyncMerge.merge(p(page = 80, ts = 3_000, synced = false), p(page = 50, ts = 2_000))
        assertEquals(80, m.currentPage)
    }

    @Test fun equalTimestampsKeepLocal() {
        assertEquals(10, SyncMerge.merge(p(page = 10, ts = 5), p(page = 20, ts = 5)).currentPage)
    }

    @Test fun readingTimeAndTotalPagesTakeMax() {
        val m = SyncMerge.merge(p(time = 5_000, total = 90), p(time = 9_000, total = 100))
        assertEquals(9_000, m.totalReadingTimeMs)
        assertEquals(100, m.totalPages)
    }

    // ── Pin (the "disappearing mark" bug) ───────────────────────────────────

    @Test fun pinSurvivesNewerPositionFromOtherDevice() {
        // A sets pin at t=2000. B later closes the book (newer position) but never saw the pin.
        val local = p(page = 10, ts = 1_000, pin = 12, pinTs = 2_000)
        val cloud = p(page = 30, ts = 3_000, pin = -1, pinTs = 0)
        val m = SyncMerge.merge(local, cloud)
        assertEquals(30, m.currentPage)        // position from B
        assertEquals(12, m.positionPinPage)    // pin from A survives
    }

    @Test fun newerPinRemovalWins() {
        val m = SyncMerge.merge(p(pin = 12, pinTs = 1_000), p(pin = -1, pinTs = 2_000))
        assertEquals(-1, m.positionPinPage)
    }

    // ── Finished shelf ───────────────────────────────────────────────────────

    @Test fun finishedStateFollowsItsOwnTimestamp() {
        val local = p(ts = 5_000)                              // A kept reading metadata
        val cloud = p(ts = 1_000, fin = 4_000, finTs = 4_000)  // B marked finished
        assertEquals(4_000, SyncMerge.merge(local, cloud).finishedAt)
    }

    @Test fun readAgainAfterFinishWins() {
        val m = SyncMerge.merge(p(fin = 1_000, finTs = 1_000), p(fin = 0, finTs = 2_000))
        assertEquals(0, m.finishedAt)
    }

    // ── applyCloud decisions ─────────────────────────────────────────────────

    @Test fun noLocalRowInsertsCloudAsSynced() {
        val r = SyncMerge.applyCloud(null, p(synced = false))
        assertTrue(r is Apply.Insert && r.row.isSynced)
    }

    @Test fun identicalDataIsNoChange() {
        assertSame(Apply.NoChange, SyncMerge.applyCloud(p(), p()))
    }

    @Test fun cloudFullyNewerMarksSynced() {
        val r = SyncMerge.applyCloud(p(page = 1, ts = 1), p(page = 9, ts = 9)) as Apply.Update
        assertTrue(r.synced)
        assertEquals(9, r.row.currentPage)
    }

    @Test fun mixedMergeStaysPendingSoItGetsPushed() {
        // Cloud has newer position, local has newer pin → result differs from cloud → must push
        val r = SyncMerge.applyCloud(p(page = 1, ts = 1, pin = 5, pinTs = 50), p(page = 9, ts = 9)) as Apply.Update
        assertFalse(r.synced)
    }

    // ── Bookmarks ────────────────────────────────────────────────────────────

    private fun bm(uid: String = "u1", ts: Long = 100, deleted: Boolean = false) = Bookmark(
        bookId = "b1", ownerName = "Bader", pageNumber = 3, label = "x", createdAt = 1,
        uid = uid, updatedAt = ts, isDeleted = deleted
    )

    @Test fun newRemoteBookmarkIsInserted() =
        assertEquals(BookmarkAction.INSERT, SyncMerge.decideBookmark(null, bm()))

    @Test fun remoteDeleteNeverResurrectsUnknownBookmark() =
        assertEquals(BookmarkAction.IGNORE, SyncMerge.decideBookmark(null, bm(deleted = true)))

    @Test fun newerRemoteDeleteRemovesLocal() =
        assertEquals(BookmarkAction.OVERWRITE, SyncMerge.decideBookmark(bm(ts = 100), bm(ts = 200, deleted = true)))

    @Test fun olderRemoteIsIgnored() =
        assertEquals(BookmarkAction.IGNORE, SyncMerge.decideBookmark(bm(ts = 300), bm(ts = 200)))

    @Test fun legacyUidIsDeterministic() =
        assertEquals(SyncMerge.legacyUid("b1", 3, 42), SyncMerge.legacyUid("b1", 3, 42))

    // ── Highlights ───────────────────────────────────────────────────────────

    @Test fun highlightRulesMatchBookmarks() {
        assertEquals(BookmarkAction.INSERT,    SyncMerge.decideHighlight(null, 10, false))
        assertEquals(BookmarkAction.IGNORE,    SyncMerge.decideHighlight(null, 10, true))
        assertEquals(BookmarkAction.OVERWRITE, SyncMerge.decideHighlight(5, 10, true))
        assertEquals(BookmarkAction.IGNORE,    SyncMerge.decideHighlight(20, 10, false))
    }
}
