package com.pdfreader.app.data.backup

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import com.pdfreader.app.data.local.AppDatabase
import com.pdfreader.app.data.model.BookCategory
import com.pdfreader.app.data.model.Bookmark
import com.pdfreader.app.data.model.Highlight
import com.pdfreader.app.data.model.ReadingProgress
import com.pdfreader.app.data.sync.DriveStore
import com.pdfreader.app.data.sync.SyncMerge
import com.pdfreader.app.utils.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full backup of everything that isn't a PDF: reading progress, pins, finished records,
 * bookmarks, highlights & notes, categories, goal and streaks.
 *
 * • Stored on Drive as kitaab_backup_<owner>_<date>.json; the newest [KEEP] are kept.
 * • Restore MERGES using the same rules as sync (SyncMerge): an old backup can never
 *   overwrite newer reading. Restored items are marked pending so both phones get them.
 */
class BackupManager(private val context: Context) {

    private val db    = AppDatabase.getInstance(context)
    private val store = DriveStore(context)
    private val prefs = PreferenceManager(context)

    data class BackupInfo(val fileId: String, val name: String, val time: Long, val sizeBytes: Long)
    data class RestoreResult(val progress: Int, val bookmarks: Int, val highlights: Int, val categories: Int)

    private fun prefix(owner: String) = "kitaab_backup_${owner}_"

    // ── Backup ───────────────────────────────────────────────────────────────

    suspend fun backupNow(): Result<BackupInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val owner = prefs.currentUser
            require(owner.isNotEmpty()) { "No user signed in" }
            check(store.isOnline()) { "No internet connection" }
            val drive = store.driveService() ?: error("Not signed in to Google Drive")

            val json = export(owner).toString()
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            val name = "${prefix(owner)}$stamp.json"
            store.uploadJson(drive, name, json)
            prefs.lastBackupAt = System.currentTimeMillis()

            // Keep only the newest KEEP backups
            list(owner).drop(KEEP).forEach { runCatching { drive.files().delete(it.fileId).execute() } }
            Log.d(TAG, "Backup ✓ $name (${json.length} bytes)")
            BackupInfo("", name, System.currentTimeMillis(), json.length.toLong())
        }
    }

    suspend fun list(owner: String = prefs.currentUser): List<BackupInfo> = withContext(Dispatchers.IO) {
        val drive = store.driveService() ?: return@withContext emptyList()
        drive.files().list()
            .setQ("name contains '${store.esc(prefix(owner))}' and trashed = false")
            .setFields("files(id, name, modifiedTime, size)")
            .setOrderBy("modifiedTime desc")
            .setSpaces("drive").execute().files.orEmpty()
            .map { BackupInfo(it.id, it.name, it.modifiedTime?.value ?: 0L, it.getSize() ?: 0L) }
    }

    private suspend fun export(owner: String): JSONObject {
        val progress   = db.progressDao().getAllProgressOnce(owner)
        val bookmarks  = db.bookmarkDao().allForOwner(owner)
        val highlights = db.highlightDao().allForOwner(owner)
        val categories = db.bookCategoryDao().getAllOnce(owner)
        return JSONObject().apply {
            put("schema", 1)
            put("owner", owner)
            put("createdAt", System.currentTimeMillis())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("progress", JSONArray().apply { progress.forEach { put(progressJson(it)) } })
            put("bookmarks", JSONArray().apply { bookmarks.forEach { put(bookmarkJson(it)) } })
            put("highlights", JSONArray().apply { highlights.forEach { put(highlightJson(it)) } })
            put("categories", JSONArray().apply { categories.forEach { c ->
                put(JSONObject().put("bookId", c.bookId).put("category", c.category).put("lastRead", c.lastReadTimestamp))
            } })
            put("prefs", JSONObject()
                .put("dailyPageGoal", prefs.dailyPageGoal)
                .put("bestStreak", maxOf(prefs.bestStreak, prefs.readingStreakDays)))
        }
    }

    // ── Restore (merge) ──────────────────────────────────────────────────────

    suspend fun restore(fileId: String): Result<RestoreResult> = withContext(Dispatchers.IO) {
        runCatching {
            check(store.isOnline()) { "No internet connection" }
            val drive = store.driveService() ?: error("Not signed in to Google Drive")
            val root  = JSONObject(store.download(drive, fileId))
            val owner = prefs.currentUser
            require(root.optString("owner") == owner) { "This backup belongs to another profile" }

            var p = 0; var b = 0; var h = 0; var c = 0
            db.withTransaction {
                val pDao = db.progressDao(); val bDao = db.bookmarkDao()
                val hDao = db.highlightDao(); val cDao = db.bookCategoryDao()

                root.optJSONArray("progress")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val backup = parseProgress(arr.getJSONObject(i))
                        val local  = pDao.getProgress(backup.bookId, backup.ownerName)
                        val now = System.currentTimeMillis()
                        when (val r = SyncMerge.applyCloud(local, backup)) {
                            is SyncMerge.Apply.Insert -> { pDao.saveProgress(r.row.copy(isSynced = false, updatedAt = now)); p++ }
                            is SyncMerge.Apply.Update -> { pDao.saveProgress(r.row.copy(isSynced = false, updatedAt = now)); p++ }
                            SyncMerge.Apply.NoChange  -> {}
                        }
                    }
                }
                root.optJSONArray("bookmarks")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val bk = parseBookmark(arr.getJSONObject(i))
                        val local = bDao.getByUid(bk.uid)
                        when (SyncMerge.decideBookmark(local, bk)) {
                            SyncMerge.BookmarkAction.INSERT    -> { bDao.upsert(bk.copy(id = 0, isSynced = false)); b++ }
                            SyncMerge.BookmarkAction.OVERWRITE -> { bDao.upsert(bk.copy(id = local!!.id, isSynced = false)); b++ }
                            SyncMerge.BookmarkAction.IGNORE    -> {}
                        }
                    }
                }
                root.optJSONArray("highlights")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val hl = parseHighlight(arr.getJSONObject(i))
                        val local = hDao.byUid(hl.uid)
                        when (SyncMerge.decideHighlight(local?.updatedAt, hl.updatedAt, hl.isDeleted)) {
                            SyncMerge.BookmarkAction.INSERT    -> { hDao.upsert(hl.copy(id = 0, isSynced = false)); h++ }
                            SyncMerge.BookmarkAction.OVERWRITE -> { hDao.upsert(hl.copy(id = local!!.id, isSynced = false)); h++ }
                            SyncMerge.BookmarkAction.IGNORE    -> {}
                        }
                    }
                }
                root.optJSONArray("categories")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val id = o.getString("bookId")
                        if (cDao.getCategory(id, owner) == null) {
                            cDao.setCategory(BookCategory(bookId = id, ownerName = owner,
                                category = o.optString("category", "Uncategorized"),
                                lastReadTimestamp = o.optLong("lastRead", 0L)))
                            c++
                        }
                    }
                }
            }
            root.optJSONObject("prefs")?.let {
                prefs.bestStreak = maxOf(prefs.bestStreak, it.optInt("bestStreak", 0))
                if (it.has("dailyPageGoal")) prefs.dailyPageGoal = it.getInt("dailyPageGoal")
            }
            RestoreResult(p, b, h, c)
        }
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    private fun progressJson(p: ReadingProgress) = JSONObject()
        .put("bookId", p.bookId).put("ownerName", p.ownerName).put("bookName", p.bookName)
        .put("currentPage", p.currentPage).put("totalPages", p.totalPages)
        .put("scrollOffset", p.scrollOffset.toDouble()).put("lastRead", p.lastReadTimestamp)
        .put("timeMs", p.totalReadingTimeMs)
        .put("pinPage", p.positionPinPage).put("pinY", p.positionPinYRatio.toDouble()).put("pinAt", p.pinUpdatedAt)
        .put("finishedAt", p.finishedAt).put("finishedUpdatedAt", p.finishedUpdatedAt)

    private fun parseProgress(o: JSONObject) = ReadingProgress(
        bookId = o.getString("bookId"), ownerName = o.getString("ownerName"),
        bookName = o.optString("bookName", ""), currentPage = o.getInt("currentPage"),
        totalPages = o.getInt("totalPages"), scrollOffset = o.optDouble("scrollOffset", 0.0).toFloat(),
        lastReadTimestamp = o.getLong("lastRead"), totalReadingTimeMs = o.optLong("timeMs", 0L),
        positionPinPage = o.optInt("pinPage", -1), positionPinYRatio = o.optDouble("pinY", -1.0).toFloat(),
        pinUpdatedAt = o.optLong("pinAt", 0L), finishedAt = o.optLong("finishedAt", 0L),
        finishedUpdatedAt = o.optLong("finishedUpdatedAt", 0L), isSynced = false
    )

    private fun bookmarkJson(b: Bookmark) = JSONObject()
        .put("uid", b.uid).put("bookId", b.bookId).put("ownerName", b.ownerName)
        .put("page", b.pageNumber).put("label", b.label).put("createdAt", b.createdAt)
        .put("updatedAt", b.updatedAt).put("isDeleted", b.isDeleted)

    private fun parseBookmark(o: JSONObject) = Bookmark(
        uid = o.getString("uid"), bookId = o.getString("bookId"), ownerName = o.getString("ownerName"),
        pageNumber = o.getInt("page"), label = o.optString("label", ""), createdAt = o.optLong("createdAt"),
        updatedAt = o.optLong("updatedAt"), isDeleted = o.optBoolean("isDeleted", false)
    )

    private fun highlightJson(h: Highlight) = JSONObject()
        .put("uid", h.uid).put("bookId", h.bookId).put("ownerName", h.ownerName).put("page", h.page)
        .put("l", h.left.toDouble()).put("t", h.top.toDouble()).put("r", h.right.toDouble()).put("b", h.bottom.toDouble())
        .put("color", h.color).put("note", h.note).put("createdAt", h.createdAt)
        .put("updatedAt", h.updatedAt).put("isDeleted", h.isDeleted)

    private fun parseHighlight(o: JSONObject) = Highlight(
        uid = o.getString("uid"), bookId = o.getString("bookId"), ownerName = o.getString("ownerName"),
        page = o.getInt("page"), left = o.getDouble("l").toFloat(), top = o.getDouble("t").toFloat(),
        right = o.getDouble("r").toFloat(), bottom = o.getDouble("b").toFloat(), color = o.getInt("color"),
        note = o.optString("note", ""), createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
        isDeleted = o.optBoolean("isDeleted", false)
    )

    companion object {
        private const val TAG = "BackupManager"
        const val KEEP = 8
        /** Automatic backup if the last one is older than this. */
        const val AUTO_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
