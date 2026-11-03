package com.pdfreader.app.data.sync

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pdfreader.app.data.model.Bookmark
import com.pdfreader.app.data.model.Highlight
import com.pdfreader.app.data.model.ReadingProgress

/** Everything the cloud knows about one book: reading progress, bookmarks and highlights. */
class BookEntry(
    var progress: ReadingProgress? = null,
    val bookmarks: MutableList<Bookmark> = mutableListOf(),
    val highlights: MutableList<Highlight> = mutableListOf()
) {
    val isEmpty: Boolean get() = progress == null && bookmarks.isEmpty() && highlights.isEmpty()
}

/**
 * The single `progress.json` file that lives in the user's folder (books/<name>/progress.json).
 * One file holds every book. Pure Kotlin (Gson only), so it is unit-tested on the JVM.
 *
 * Conflict rules are the existing [SyncMerge] rules, applied per book:
 *  - reading position: the newest saved time wins
 *  - bookmarks / highlights: per uid, newest updatedAt wins; deletes stay as tombstones
 */
class ProgressDoc(
    val owner: String,
    val books: MutableMap<String, BookEntry> = linkedMapOf(),
    /** The weekly-summary snapshot (raw JSON text) that used to be a separate file. */
    var weekly: String? = null
) {

    fun entry(bookId: String): BookEntry = books.getOrPut(bookId) { BookEntry() }

    /** Merges [p] into the document and returns the merged row (the newest save wins). */
    fun putProgress(p: ReadingProgress): ReadingProgress {
        val e = entry(p.bookId)
        val old = e.progress
        val merged = if (old == null) p else SyncMerge.merge(p, old)
        e.progress = merged
        return merged
    }

    fun putBookmarks(bookId: String, incoming: List<Bookmark>) {
        val e = entry(bookId)
        for (b in incoming) {
            val i = e.bookmarks.indexOfFirst { it.uid == b.uid }
            if (i < 0) e.bookmarks.add(b) else if (b.updatedAt > e.bookmarks[i].updatedAt) e.bookmarks[i] = b
        }
    }

    fun putHighlights(bookId: String, incoming: List<Highlight>) {
        val e = entry(bookId)
        for (h in incoming) {
            val i = e.highlights.indexOfFirst { it.uid == h.uid }
            if (i < 0) e.highlights.add(h) else if (h.updatedAt > e.highlights[i].updatedAt) e.highlights[i] = h
        }
    }

    /** Folds another document (for example the old separate files) into this one. */
    fun mergeFrom(other: ProgressDoc) {
        for ((id, e) in other.books) {
            e.progress?.let { putProgress(it) }
            putBookmarks(id, e.bookmarks)
            putHighlights(id, e.highlights)
        }
        if (weekly == null) weekly = other.weekly
    }

    /** True when the document holds nothing at all. */
    fun isBlank(): Boolean = books.values.all { it.isEmpty } && weekly == null

    fun toJson(): String {
        val root = JsonObject()
        root.addProperty("schema", SCHEMA)
        root.addProperty("owner", owner)
        root.addProperty("updatedAt", System.currentTimeMillis())
        val booksJson = JsonObject()
        for ((id, e) in books) {
            if (e.isEmpty) continue
            val o = JsonObject()
            e.progress?.let { o.add("progress", progressToJson(it)) }
            if (e.bookmarks.isNotEmpty()) o.add("bookmarks", JsonArray().also { a -> e.bookmarks.forEach { a.add(bookmarkToJson(it)) } })
            if (e.highlights.isNotEmpty()) o.add("highlights", JsonArray().also { a -> e.highlights.forEach { a.add(highlightToJson(it)) } })
            booksJson.add(id, o)
        }
        root.add("books", booksJson)
        weekly?.let { root.addProperty("weekly", it) }
        return root.toString()
    }

    companion object {
        const val SCHEMA = 5
        const val FILE_NAME = "progress.json"

        /** Reads a progress.json. Null when the text is not a valid document. */
        fun parse(text: String?, owner: String): ProgressDoc? {
            if (text.isNullOrBlank()) return null
            val root = try { JsonParser.parseString(text) } catch (e: Exception) { return null }
            if (!root.isJsonObject) return null
            val r = root.asJsonObject
            val doc = ProgressDoc(owner)
            doc.weekly = r.str("weekly").ifEmpty { null }
            val booksEl = r.get("books")
            if (booksEl != null && booksEl.isJsonObject) {
                for ((id, el) in booksEl.asJsonObject.entrySet()) {
                    if (!el.isJsonObject) continue
                    val o = el.asJsonObject
                    val entry = BookEntry()
                    o.obj("progress")?.let { j ->
                        progressFromJson(j, id, owner)?.let { entry.progress = it }
                    }
                    o.arr("bookmarks")?.forEach { b -> if (b.isJsonObject) bookmarkFromJson(b.asJsonObject, id, owner)?.let { entry.bookmarks.add(it) } }
                    o.arr("highlights")?.forEach { h -> if (h.isJsonObject) highlightFromJson(h.asJsonObject, id, owner)?.let { entry.highlights.add(it) } }
                    if (!entry.isEmpty) doc.books[id] = entry
                }
            }
            return doc
        }

        // ---- the old per-book files (read once, to move them into progress.json) ----------------------

        fun legacyProgress(text: String?, owner: String): ReadingProgress? {
            val j = rootObject(text) ?: return null
            return progressFromJson(j, j.str("bookId"), owner)
        }

        fun legacyBookmarks(text: String?, owner: String): Pair<String, List<Bookmark>>? {
            val j = rootObject(text) ?: return null
            val bookId = j.str("bookId")
            if (bookId.isEmpty()) return null
            val list = mutableListOf<Bookmark>()
            j.arr("bookmarks")?.forEach { b ->
                if (b.isJsonObject) bookmarkFromJson(b.asJsonObject, bookId, owner)?.let { list.add(it) }
            }
            return bookId to list
        }

        fun legacyHighlights(text: String?, owner: String): Pair<String, List<Highlight>>? {
            val j = rootObject(text) ?: return null
            val bookId = j.str("bookId")
            if (bookId.isEmpty()) return null
            val list = mutableListOf<Highlight>()
            j.arr("highlights")?.forEach { h ->
                if (h.isJsonObject) highlightFromJson(h.asJsonObject, bookId, owner)?.let { list.add(it) }
            }
            return bookId to list
        }

        // ---- field codecs ---------------------------------------------------------------------------------

        fun progressToJson(p: ReadingProgress): JsonObject = JsonObject().apply {
            addProperty("bookName", p.bookName)
            addProperty("currentPage", p.currentPage)
            addProperty("totalPages", p.totalPages)
            addProperty("scrollOffset", p.scrollOffset.toDouble())
            addProperty("timestamp", p.lastReadTimestamp)
            addProperty("totalReadingTimeMs", p.totalReadingTimeMs)
            addProperty("positionPinPage", p.positionPinPage)
            addProperty("positionPinYRatio", p.positionPinYRatio.toDouble())
            addProperty("pinUpdatedAt", p.pinUpdatedAt)
            addProperty("finishedAt", p.finishedAt)
            addProperty("finishedUpdatedAt", p.finishedUpdatedAt)
        }

        fun progressFromJson(j: JsonObject, bookId: String, owner: String): ReadingProgress? {
            if (bookId.isEmpty() || !j.has("currentPage")) return null
            val ts = j.long("timestamp", 0L)
            val pinPage = j.int("positionPinPage", -1)
            return ReadingProgress(
                bookId = bookId,
                ownerName = owner,
                bookName = j.str("bookName"),
                currentPage = j.int("currentPage", 0),
                totalPages = j.int("totalPages", 0),
                scrollOffset = j.dbl("scrollOffset", 0.0).toFloat(),
                lastReadTimestamp = ts,
                totalReadingTimeMs = j.long("totalReadingTimeMs", 0L),
                isSynced = true,
                positionPinPage = pinPage,
                positionPinYRatio = j.dbl("positionPinYRatio", -1.0).toFloat(),
                // Old records carry no pin time: treat the pin as old as the record
                pinUpdatedAt = j.long("pinUpdatedAt", if (pinPage >= 0) ts else 0L),
                finishedAt = j.long("finishedAt", 0L),
                finishedUpdatedAt = j.long("finishedUpdatedAt", 0L)
            )
        }

        fun bookmarkToJson(b: Bookmark): JsonObject = JsonObject().apply {
            addProperty("uid", b.uid)
            addProperty("pageNumber", b.pageNumber)
            addProperty("label", b.label)
            addProperty("createdAt", b.createdAt)
            addProperty("updatedAt", b.updatedAt)
            addProperty("isDeleted", b.isDeleted)
        }

        fun bookmarkFromJson(o: JsonObject, bookId: String, owner: String): Bookmark? {
            if (!o.has("pageNumber")) return null
            val page = o.int("pageNumber", 0)
            val createdAt = o.long("createdAt", 0L)
            val uid = o.str("uid").ifEmpty { SyncMerge.legacyUid(bookId, page, createdAt) }
            return Bookmark(
                bookId = bookId, ownerName = owner, pageNumber = page,
                label = o.str("label").ifEmpty { "Page ${page + 1}" },
                createdAt = createdAt, uid = uid,
                updatedAt = o.long("updatedAt", createdAt),
                isDeleted = o.bool("isDeleted"), isSynced = true
            )
        }

        fun highlightToJson(h: Highlight): JsonObject = JsonObject().apply {
            addProperty("uid", h.uid)
            addProperty("page", h.page)
            addProperty("l", h.left.toDouble()); addProperty("t", h.top.toDouble())
            addProperty("r", h.right.toDouble()); addProperty("b", h.bottom.toDouble())
            addProperty("color", h.color)
            addProperty("note", h.note)
            addProperty("createdAt", h.createdAt)
            addProperty("updatedAt", h.updatedAt)
            addProperty("isDeleted", h.isDeleted)
        }

        fun highlightFromJson(o: JsonObject, bookId: String, owner: String): Highlight? {
            val uid = o.str("uid")
            if (uid.isEmpty() || !o.has("page")) return null
            return Highlight(
                uid = uid, bookId = bookId, ownerName = owner, page = o.int("page", 0),
                left = o.dbl("l", 0.0).toFloat(), top = o.dbl("t", 0.0).toFloat(),
                right = o.dbl("r", 0.0).toFloat(), bottom = o.dbl("b", 0.0).toFloat(),
                color = o.int("color", 0), note = o.str("note"),
                createdAt = o.long("createdAt", 0L), updatedAt = o.long("updatedAt", 0L),
                isDeleted = o.bool("isDeleted"), isSynced = true
            )
        }

        // ---- null-safe JSON helpers -----------------------------------------------------------------------
        private fun rootObject(text: String?): JsonObject? {
            if (text.isNullOrBlank()) return null
            val el = try { JsonParser.parseString(text) } catch (e: Exception) { return null }
            return if (el.isJsonObject) el.asJsonObject else null
        }

        private fun JsonElement?.usable(): JsonElement? = if (this == null || this.isJsonNull) null else this

        private fun JsonObject.str(k: String): String {
            val e = get(k).usable() ?: return ""
            return if (e.isJsonPrimitive) e.asString else ""
        }

        private fun JsonObject.long(k: String, d: Long): Long {
            val e = get(k).usable() ?: return d
            return try { if (e.isJsonPrimitive) e.asLong else d } catch (x: Exception) { d }
        }

        private fun JsonObject.int(k: String, d: Int): Int {
            val e = get(k).usable() ?: return d
            return try { if (e.isJsonPrimitive) e.asInt else d } catch (x: Exception) { d }
        }

        private fun JsonObject.dbl(k: String, d: Double): Double {
            val e = get(k).usable() ?: return d
            return try { if (e.isJsonPrimitive) e.asDouble else d } catch (x: Exception) { d }
        }

        private fun JsonObject.bool(k: String): Boolean {
            val e = get(k).usable() ?: return false
            return try { e.isJsonPrimitive && e.asBoolean } catch (x: Exception) { false }
        }

        private fun JsonObject.obj(k: String): JsonObject? {
            val e = get(k).usable() ?: return null
            return if (e.isJsonObject) e.asJsonObject else null
        }

        private fun JsonObject.arr(k: String): JsonArray? {
            val e = get(k).usable() ?: return null
            return if (e.isJsonArray) e.asJsonArray else null
        }
    }
}
