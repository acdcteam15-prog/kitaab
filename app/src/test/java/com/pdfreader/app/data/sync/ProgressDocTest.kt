package com.pdfreader.app.data.sync

import com.pdfreader.app.data.model.Bookmark
import com.pdfreader.app.data.model.Highlight
import com.pdfreader.app.data.model.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressDocTest {

    private fun prog(book: String, page: Int, ts: Long, finished: Long = 0L) = ReadingProgress(
        bookId = book, ownerName = "bader", currentPage = page, totalPages = 100,
        lastReadTimestamp = ts, bookName = "Book $book", totalReadingTimeMs = ts,
        finishedAt = finished, finishedUpdatedAt = finished
    )

    private fun bm(uid: String, page: Int, updated: Long, deleted: Boolean = false) = Bookmark(
        bookId = "b1", ownerName = "bader", pageNumber = page, label = "p$page",
        createdAt = 1L, uid = uid, updatedAt = updated, isDeleted = deleted
    )

    private fun hl(uid: String, updated: Long) = Highlight(
        uid = uid, bookId = "b1", ownerName = "bader", page = 3,
        left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.3f, color = 5, note = "n",
        createdAt = 1L, updatedAt = updated
    )

    @Test fun roundTrip_keepsEverything() {
        val doc = ProgressDoc("bader")
        doc.putProgress(prog("b1", 41, 1000))
        doc.putProgress(prog("b2", 7, 2000, finished = 3000))
        doc.putBookmarks("b1", listOf(bm("u1", 5, 10), bm("u2", 9, 20, deleted = true)))
        doc.putHighlights("b1", listOf(hl("h1", 30)))
        doc.weekly = """{"week":1}"""

        val back = ProgressDoc.parse(doc.toJson(), "bader")
        assertNotNull(back)
        back!!
        assertEquals(2, back.books.size)
        assertEquals(41, back.books["b1"]!!.progress!!.currentPage)
        assertEquals(1000L, back.books["b1"]!!.progress!!.lastReadTimestamp)
        assertTrue(back.books["b2"]!!.progress!!.isFinished)
        assertEquals(2, back.books["b1"]!!.bookmarks.size)
        assertTrue(back.books["b1"]!!.bookmarks.first { it.uid == "u2" }.isDeleted)
        assertEquals(0.9f, back.books["b1"]!!.highlights[0].right, 0.0001f)
        assertEquals("""{"week":1}""", back.weekly)
    }

    @Test fun parse_ignoresBrokenInput() {
        assertNull(ProgressDoc.parse(null, "bader"))
        assertNull(ProgressDoc.parse("", "bader"))
        assertNull(ProgressDoc.parse("not json", "bader"))
        assertNull(ProgressDoc.parse("[1,2]", "bader"))
        val empty = ProgressDoc.parse("{}", "bader")
        assertNotNull(empty)
        assertTrue(empty!!.isBlank())
    }

    @Test fun progress_newestSavedTimeWinsPerBook() {
        val doc = ProgressDoc("bader")
        doc.putProgress(prog("b1", 10, 2000))
        val merged = doc.putProgress(prog("b1", 99, 1000))   // an older save from another phone
        assertEquals(10, merged.currentPage)
        val newer = doc.putProgress(prog("b1", 55, 3000))
        assertEquals(55, newer.currentPage)
        // a different book is untouched
        doc.putProgress(prog("b2", 1, 10))
        assertEquals(55, doc.books["b1"]!!.progress!!.currentPage)
    }

    @Test fun bookmarks_perUidNewestWins_deletesAreKept() {
        val doc = ProgressDoc("bader")
        doc.putBookmarks("b1", listOf(bm("u1", 5, 100)))
        doc.putBookmarks("b1", listOf(bm("u1", 6, 50)))                     // older: ignored
        assertEquals(5, doc.books["b1"]!!.bookmarks[0].pageNumber)
        doc.putBookmarks("b1", listOf(bm("u1", 5, 200, deleted = true)))    // newer delete: tombstone wins
        assertEquals(1, doc.books["b1"]!!.bookmarks.size)
        assertTrue(doc.books["b1"]!!.bookmarks[0].isDeleted)
        doc.putBookmarks("b1", listOf(bm("u2", 8, 10)))
        assertEquals(2, doc.books["b1"]!!.bookmarks.size)
    }

    @Test fun highlights_newestWins() {
        val doc = ProgressDoc("bader")
        doc.putHighlights("b1", listOf(hl("h1", 10)))
        doc.putHighlights("b1", listOf(hl("h1", 20).copy(note = "newer")))
        doc.putHighlights("b1", listOf(hl("h1", 5).copy(note = "older")))
        assertEquals(1, doc.books["b1"]!!.highlights.size)
        assertEquals("newer", doc.books["b1"]!!.highlights[0].note)
    }

    @Test fun mergeFrom_combinesTwoDocuments() {
        val a = ProgressDoc("bader"); a.putProgress(prog("b1", 10, 1000)); a.weekly = null
        val b = ProgressDoc("bader"); b.putProgress(prog("b1", 20, 2000)); b.putProgress(prog("b2", 3, 500)); b.weekly = "w"
        a.mergeFrom(b)
        assertEquals(20, a.books["b1"]!!.progress!!.currentPage)
        assertEquals(3, a.books["b2"]!!.progress!!.currentPage)
        assertEquals("w", a.weekly)
    }

    @Test fun emptyEntriesAreNotWritten() {
        val doc = ProgressDoc("bader")
        doc.entry("ghost")
        doc.putProgress(prog("b1", 1, 1))
        val back = ProgressDoc.parse(doc.toJson(), "bader")!!
        assertEquals(setOf("b1"), back.books.keys)
        assertFalse(doc.isBlank())
    }

    // ---- the old separate files -----------------------------------------------------------------------
    @Test fun legacyProgress_isRead() {
        val text = """{"bookId":"b9","ownerName":"Bader","bookName":"Old","currentPage":12,"totalPages":80,"timestamp":777,
            "totalReadingTimeMs":5,"positionPinPage":4,"positionPinYRatio":0.5,"finishedAt":0,"schema":4}"""
        val p = ProgressDoc.legacyProgress(text, "bader")
        assertNotNull(p)
        assertEquals("b9", p!!.bookId)
        assertEquals("bader", p.ownerName)
        assertEquals(12, p.currentPage)
        assertEquals(777L, p.pinUpdatedAt)        // pin as old as the record
        assertNull(ProgressDoc.legacyProgress("{}", "bader"))
        assertNull(ProgressDoc.legacyProgress("oops", "bader"))
    }

    @Test fun legacyBookmarks_andHighlights_areRead() {
        val bms = ProgressDoc.legacyBookmarks(
            """{"bookId":"b9","ownerName":"Bader","bookmarks":[{"pageNumber":3,"createdAt":5},{"uid":"x","pageNumber":4,"label":"L","updatedAt":9,"isDeleted":true}]}""",
            "bader")
        assertNotNull(bms)
        assertEquals("b9", bms!!.first)
        assertEquals(2, bms.second.size)
        assertEquals("legacy_b9_3_5", bms.second[0].uid)
        assertTrue(bms.second[1].isDeleted)

        val hls = ProgressDoc.legacyHighlights(
            """{"bookId":"b9","highlights":[{"uid":"h","page":2,"l":0.1,"t":0.2,"r":0.3,"b":0.4,"color":1,"updatedAt":3}]}""",
            "bader")
        assertEquals(1, hls!!.second.size)
        assertEquals("b9", hls.second[0].bookId)
    }

    @Test fun migration_foldsOldFilesIntoOneDocument() {
        val doc = ProgressDoc("bader")
        doc.putProgress(prog("b1", 5, 100))                       // already in progress.json
        val old = ProgressDoc("bader")
        ProgressDoc.legacyProgress("""{"bookId":"b1","currentPage":30,"totalPages":100,"timestamp":900}""", "bader")
            ?.let { old.putProgress(it) }
        ProgressDoc.legacyProgress("""{"bookId":"b2","currentPage":2,"totalPages":50,"timestamp":10}""", "bader")
            ?.let { old.putProgress(it) }
        doc.mergeFrom(old)
        assertEquals(30, doc.books["b1"]!!.progress!!.currentPage)   // the newer old file wins
        assertEquals(2, doc.books["b2"]!!.progress!!.currentPage)
    }
}
