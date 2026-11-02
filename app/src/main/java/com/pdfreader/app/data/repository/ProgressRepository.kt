package com.pdfreader.app.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.room.withTransaction
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import com.pdfreader.app.data.local.AppDatabase
import com.pdfreader.app.data.model.Bookmark
import com.pdfreader.app.data.model.BookCategory
import com.pdfreader.app.data.model.ReadingProgress
import com.pdfreader.app.sync.FirebaseSyncManager
import com.pdfreader.app.data.sync.SyncMerge
import com.pdfreader.app.data.sync.DriveStore
import com.pdfreader.app.worker.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.pdfreader.app.data.model.SyncEvent
import com.pdfreader.app.data.model.Highlight
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Sync design (v4)
 * ────────────────
 * • Room is the ONLY queue: isSynced = false rows are pending uploads. No queue files.
 * • Every Drive operation runs under ONE process-wide Mutex, so a pull can never interleave
 *   with a push (the old "came back online and got the old state" race).
 * • Every sync is PUSH FIRST, THEN PULL.
 * • Push is read-merge-write: download cloud → merge → upload merged. A stale device can
 *   no longer overwrite newer cloud data.
 * • Merge is per field group (position / pin / finished), each with its own timestamp,
 *   so closing a book on Device B can't wipe the pin set on Device A.
 * • markSynced is conditional on updatedAt: an edit made while an upload is in flight
 *   stays pending instead of being silently marked synced.
 */
class ProgressRepository(private val context: Context) {

    private val db          = AppDatabase.getInstance(context)
    private val progressDao = db.progressDao()
    private val bookmarkDao = db.bookmarkDao()
    private val categoryDao = db.bookCategoryDao()
    private val eventDao    = db.syncEventDao()
    private val highlightDao = db.highlightDao()

    private val store = DriveStore(context)

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC SYNC ENTRY POINTS
    // ═════════════════════════════════════════════════════════════════════════

    /** Push everything pending, then pull everything newer. Safe to call from anywhere, any time. */
    suspend fun syncNow(ownerName: String, reason: String = ""): Int = withContext(Dispatchers.IO) {
        if (ownerName.isEmpty() || !isOnline()) {
            Log.d(TAG, "syncNow($reason): skipped (offline or no user)")
            return@withContext 0
        }
        val drive = getDriveService() ?: return@withContext 0
        syncMutex.withLock {
            Log.d(TAG, "syncNow($reason) ▶ push then pull")
            try {
                cleanupLegacyQueue()
                val pushed  = pushPendingLocked(drive, ownerName)
                val updated = pullLocked(drive, ownerName)
                if (pushed > 0) FirebaseSyncManager.notifyPush(ownerName)
                Log.d(TAG, "syncNow($reason) ✓ pushed=$pushed pulled=$updated")
                if (pushed > 0 || updated > 0)
                    logEvent(ownerName, "SYNC", detail = "$reason — sent $pushed, received $updated")
                updated
            } catch (e: Exception) {
                logEvent(ownerName, "ERROR", detail = "$reason — ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                throw e
            }
        }
    }

    /** Push only (used right after a local change). Throws on failure so callers can reschedule. */
    suspend fun pushPending(ownerName: String): Int = withContext(Dispatchers.IO) {
        if (ownerName.isEmpty() || !isOnline()) return@withContext 0
        val drive = getDriveService() ?: return@withContext 0
        val pushed = syncMutex.withLock { pushPendingLocked(drive, ownerName) }
        if (pushed > 0) FirebaseSyncManager.notifyPush(ownerName)
        pushed
    }

    /** Kept for existing callers — now always push-then-pull. */
    suspend fun pullAllProgressFromDrive(ownerName: String): Int = syncNow(ownerName, "pull")

    /** Kept for existing callers (SyncWorker / network callback). */
    suspend fun pushQueueToDrive() {
        val owner = com.pdfreader.app.utils.PreferenceManager(context).currentUser
        pushPending(owner)
    }

    /** Fire-and-forget push after a local write; falls back to WorkManager on failure. */
    private fun schedulePush(ownerName: String) {
        bgScope.launch {
            try {
                if (isOnline()) pushPending(ownerName) else SyncWorker.schedule(context)
                if (hasPendingNow(ownerName)) SyncWorker.schedule(context)
            } catch (e: Exception) {
                Log.e(TAG, "Immediate push failed: ${e.message} — WorkManager will retry")
                SyncWorker.schedule(context)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // OPEN BOOK
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Returns the freshest progress for opening a book. Local unsynced edits are pushed
     * first, then the cloud copy is merged in. Never blocks the UI for more than ~3.5s:
     * the Drive round-trip keeps running in the background if it's slow.
     */
    suspend fun getProgress(bookId: String, ownerName: String): ReadingProgress? {
        val local = progressDao.getProgress(bookId, ownerName)
        if (!isOnline()) return local
        val job = bgScope.async { syncBookLocked(bookId, ownerName) }
        return withTimeoutOrNull(3_500) { job.await() } ?: progressDao.getProgress(bookId, ownerName)
    }

    private suspend fun syncBookLocked(bookId: String, ownerName: String): ReadingProgress? {
        val drive = getDriveService() ?: return progressDao.getProgress(bookId, ownerName)
        return syncMutex.withLock {
            try {
                val local = progressDao.getProgress(bookId, ownerName)
                if (local != null && !local.isSynced) {
                    pushOneLocked(drive, local)
                    FirebaseSyncManager.notifyPush(ownerName)
                } else {
                    val cloud = fetchProgressFromDrive(drive, bookId, ownerName)
                    if (cloud != null) applyCloud(cloud)
                }
            } catch (e: Exception) {
                Log.e(TAG, "syncBook failed for $bookId: ${e.message}")
            }
            progressDao.getProgress(bookId, ownerName)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LOCAL WRITES (all atomic read-modify-write)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Checkpoint the reading position. Called on every pause/close and periodically.
     * [sessionDeltaMs] must be the time read SINCE THE LAST CHECKPOINT (not the whole session).
     * The pin is never touched here — it has its own timestamped path.
     */
    suspend fun saveAndSync(
        bookId: String, ownerName: String, bookName: String,
        currentPage: Int, totalPages: Int,
        scrollOffset: Float, sessionDeltaMs: Long,
        pushNow: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val existing = progressDao.getProgress(bookId, ownerName)
            val base = existing ?: ReadingProgress(bookId = bookId, ownerName = ownerName)
            progressDao.saveProgress(
                base.copy(
                    bookName           = bookName.ifEmpty { base.bookName },
                    currentPage        = currentPage,
                    totalPages         = if (totalPages > 0) totalPages else base.totalPages,
                    scrollOffset       = scrollOffset,
                    lastReadTimestamp  = now,
                    totalReadingTimeMs = base.totalReadingTimeMs + sessionDeltaMs.coerceAtLeast(0),
                    isSynced           = false,
                    updatedAt          = now
                )
            )
            categoryDao.updateLastRead(bookId, ownerName, now)
        }
        Log.d(TAG, "saveAndSync: saved locally page=$currentPage (+${sessionDeltaMs}ms)")
        if (pushNow) schedulePush(ownerName)
    }

    /** Set (page ≥ 0) or clear (page = -1) the reading pin. Creates the row if needed. */
    suspend fun savePositionPin(
        bookId: String, ownerName: String, bookName: String,
        pinPage: Int, pinYRatio: Float, totalPages: Int
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val base = progressDao.getProgress(bookId, ownerName)
                ?: ReadingProgress(bookId = bookId, ownerName = ownerName, bookName = bookName,
                    currentPage = pinPage.coerceAtLeast(0), totalPages = totalPages)
            progressDao.saveProgress(
                base.copy(
                    positionPinPage   = pinPage,
                    positionPinYRatio = if (pinPage >= 0) pinYRatio else -1f,
                    pinUpdatedAt      = now,
                    isSynced          = false,
                    updatedAt         = now
                )
            )
        }
        Log.d(TAG, "Pin saved page=$pinPage")
        schedulePush(ownerName)
    }

    /** Mark finished (moves the book to the Finished shelf) or un-finish ("read again"). */
    suspend fun setFinished(
        bookId: String, ownerName: String, bookName: String,
        finished: Boolean, totalPages: Int
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (finished) archiveCover(bookId)
        db.withTransaction {
            val base = progressDao.getProgress(bookId, ownerName)
                ?: ReadingProgress(bookId = bookId, ownerName = ownerName, bookName = bookName, totalPages = totalPages)
            progressDao.saveProgress(
                base.copy(
                    bookName          = bookName.ifEmpty { base.bookName },
                    finishedAt        = if (finished) now else 0L,
                    finishedUpdatedAt = now,
                    currentPage       = if (finished) base.currentPage else 0,
                    positionPinPage   = if (finished) base.positionPinPage else -1,
                    positionPinYRatio = if (finished) base.positionPinYRatio else -1f,
                    pinUpdatedAt      = if (finished) base.pinUpdatedAt else now,
                    lastReadTimestamp = if (finished) base.lastReadTimestamp else now,
                    isSynced          = false,
                    updatedAt         = now
                )
            )
        }
        schedulePush(ownerName)
    }

    /**
     * Permanently remove a finished record (local + Drive). Requires internet, otherwise the
     * next pull would restore it. Returns false if offline.
     */
    suspend fun deleteFinishedRecord(bookId: String, ownerName: String): Boolean = withContext(Dispatchers.IO) {
        if (!isOnline()) return@withContext false
        val drive = getDriveService() ?: return@withContext false
        syncMutex.withLock {
            try {
                store.listByName(drive, progressFileName(ownerName, bookId)).forEach { drive.files().delete(it.id).execute() }
                progressDao.deleteProgress(bookId, ownerName)
                archivedCoverFile(bookId).delete()
                true
            } catch (e: Exception) {
                Log.e(TAG, "deleteFinishedRecord failed: ${e.message}")
                false
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SYNC HISTORY + REMOTE POSITION
    // ═════════════════════════════════════════════════════════════════════════

    fun syncEventsFlow(owner: String): Flow<List<SyncEvent>> = eventDao.recent(owner)

    suspend fun clearSyncHistory(owner: String) = eventDao.clear(owner)

    private suspend fun logEvent(owner: String, type: String, bookId: String = "", bookName: String = "", detail: String = "") {
        try {
            eventDao.insert(SyncEvent(ownerName = owner, type = type, bookId = bookId, bookName = bookName, detail = detail))
            eventDao.trim()
        } catch (e: Exception) { Log.e(TAG, "logEvent: ${e.message}") }
    }

    /** Emitted when a pull moves a book to a different page (another device read on). */
    val remotePosition: SharedFlow<RemotePosition> get() = _remotePosition.asSharedFlow()

    // ═════════════════════════════════════════════════════════════════════════
    // BOOKMARKS
    // ═════════════════════════════════════════════════════════════════════════

    fun getBookmarksFlow(bookId: String, owner: String): Flow<List<Bookmark>> =
        bookmarkDao.getBookmarks(bookId, owner)

    suspend fun addBookmark(bookId: String, owner: String, page: Int, label: String) = withContext(Dispatchers.IO) {
        bookmarkDao.upsert(Bookmark(bookId = bookId, ownerName = owner, pageNumber = page, label = label))
        schedulePush(owner)
    }

    /** Removes every bookmark on [page] (soft delete, synced as a tombstone). */
    suspend fun removeBookmarksOnPage(bookId: String, owner: String, page: Int) = withContext(Dispatchers.IO) {
        bookmarkDao.softDeletePage(bookId, owner, page, System.currentTimeMillis())
        schedulePush(owner)
    }

    suspend fun deleteBookmark(bookmark: Bookmark) = withContext(Dispatchers.IO) {
        bookmarkDao.softDeleteUid(bookmark.uid, System.currentTimeMillis())
        schedulePush(bookmark.ownerName)
    }

    suspend fun isPageBookmarked(bookId: String, owner: String, page: Int): Boolean =
        bookmarkDao.isPageBookmarked(bookId, owner, page) > 0

    // ═════════════════════════════════════════════════════════════════════════
    // SYNC STATUS
    // ═════════════════════════════════════════════════════════════════════════

    /** Live count of items waiting for upload (progress rows + bookmarks). */
    fun pendingCountFlow(owner: String): Flow<Int> =
        combine(progressDao.countUnsyncedFlow(owner), bookmarkDao.countUnsyncedFlow(owner),
            highlightDao.countUnsyncedFlow(owner)) { a, b, c -> a + b + c }

    private suspend fun hasPendingNow(owner: String): Boolean =
        progressDao.getUnsynced(owner).isNotEmpty() || bookmarkDao.booksWithUnsynced(owner).isNotEmpty() ||
        highlightDao.booksWithUnsynced(owner).isNotEmpty()

    // ═════════════════════════════════════════════════════════════════════════
    // HIGHLIGHTS & NOTES
    // ═════════════════════════════════════════════════════════════════════════

    fun highlightsFlow(bookId: String, owner: String): Flow<List<Highlight>> = highlightDao.forBook(bookId, owner)

    suspend fun saveHighlight(h: Highlight) = withContext(Dispatchers.IO) {
        highlightDao.upsert(h.copy(updatedAt = System.currentTimeMillis(), isSynced = false))
        schedulePush(h.ownerName)
    }

    suspend fun deleteHighlight(h: Highlight) = withContext(Dispatchers.IO) {
        highlightDao.upsert(h.copy(isDeleted = true, updatedAt = System.currentTimeMillis(), isSynced = false))
        schedulePush(h.ownerName)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CATEGORIES
    // ═════════════════════════════════════════════════════════════════════════

    fun getCategoriesFlow(owner: String): Flow<List<BookCategory>> =
        categoryDao.getAllForOwner(owner)

    suspend fun setCategory(bookId: String, owner: String, category: String) {
        val existing = categoryDao.getCategory(bookId, owner)
        categoryDao.setCategory(
            BookCategory(bookId = bookId, ownerName = owner, category = category,
                lastReadTimestamp = existing?.lastReadTimestamp ?: 0L)
        )
    }

    suspend fun getCategory(bookId: String, owner: String): String =
        categoryDao.getCategory(bookId, owner)?.category ?: "Uncategorized"

    // ═════════════════════════════════════════════════════════════════════════
    // LOCAL READS
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun getProgressLocal(bookId: String, ownerName: String): ReadingProgress? =
        progressDao.getProgress(bookId, ownerName)

    fun getAllProgressFlow(ownerName: String): Flow<List<ReadingProgress>> =
        progressDao.getAllProgressForOwner(ownerName)

    fun getFinishedFlow(ownerName: String): Flow<List<ReadingProgress>> =
        progressDao.getFinishedFlow(ownerName)

    // ── Archived covers (survive deleting the PDF) ────────────────────────────
    fun archivedCoverFile(bookId: String): File =
        File(File(context.filesDir, "covers").also { it.mkdirs() }, "$bookId.png")

    private fun archiveCover(bookId: String) {
        try {
            val thumb = File(File(context.getExternalFilesDir(null), "thumbs"), "$bookId.png")
            val dest  = archivedCoverFile(bookId)
            if (thumb.exists() && thumb.length() > 0 && !dest.exists()) thumb.copyTo(dest, overwrite = true)
        } catch (e: Exception) { Log.e(TAG, "archiveCover: ${e.message}") }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // WEEKLY SUMMARY (unchanged behaviour)
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun recordWeeklySnapshot(prefs: com.pdfreader.app.utils.PreferenceManager) =
        withContext(Dispatchers.IO) {
            val all        = progressDao.getAllProgressOnce(prefs.currentUser)
            val totalPages = all.sumOf { it.currentPage }
            val totalMs    = all.sumOf { it.totalReadingTimeMs }
            prefs.recordWeeklyActivity(totalPages, totalMs)
            if (isOnline()) {
                val raw = prefs.getRawWeeklySnapshot()
                if (raw != null) {
                    pushWeeklySnapshotToDrive(prefs.currentUser, raw)
                    FirebaseSyncManager.pushWeeklyData(
                        ownerName   = prefs.currentUser,
                        weeklyJson  = raw,
                        streakDays  = prefs.readingStreakDays,
                        lastReadDay = prefs.lastReadEpochDay
                    )
                }
            }
        }

    suspend fun getWeeklySummary(prefs: com.pdfreader.app.utils.PreferenceManager): com.pdfreader.app.utils.PreferenceManager.WeekSummary =
        withContext(Dispatchers.IO) {
            val all        = progressDao.getAllProgressOnce(prefs.currentUser)
            val totalPages = all.sumOf { it.currentPage }
            val totalMs    = all.sumOf { it.totalReadingTimeMs }
            prefs.getWeeklySummary(totalPages, totalMs)
        }

    fun pushWeeklySnapshotToDrive(ownerName: String, snapshotJson: String) {
        val drive = getDriveService() ?: return
        try { store.uploadJson(drive, "weekly_snapshot_${ownerName}.json", snapshotJson) }
        catch (e: Exception) { Log.e(TAG, "Weekly push failed: ${e.message}") }
    }

    fun fetchWeeklySnapshotFromDrive(ownerName: String): String? {
        val drive = getDriveService() ?: return null
        return try {
            val f = store.listByName(drive, "weekly_snapshot_${ownerName}.json").firstOrNull() ?: return null
            store.download(drive, f.id)
        } catch (e: Exception) { null }
    }

    fun deleteWeeklySnapshotFromDrive(ownerName: String) {
        val drive = getDriveService() ?: return
        try {
            store.listByName(drive, "weekly_snapshot_${ownerName}.json").forEach { drive.files().delete(it.id).execute() }
        } catch (e: Exception) { Log.e(TAG, "Weekly delete failed: ${e.message}") }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PUSH (caller holds syncMutex)
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun pushPendingLocked(drive: Drive, ownerName: String): Int {
        var pushed = 0
        progressDao.getUnsynced(ownerName).forEach { p ->
            pushOneLocked(drive, p); pushed++
        }
        bookmarkDao.booksWithUnsynced(ownerName).forEach { bookId ->
            pushBookmarksLocked(drive, bookId, ownerName); pushed++
        }
        highlightDao.booksWithUnsynced(ownerName).forEach { bookId ->
            pushHighlightsLocked(drive, bookId, ownerName); pushed++
        }
        return pushed
    }

    /** Read-merge-write for one progress row. Throws on network failure. */
    private suspend fun pushOneLocked(drive: Drive, local: ReadingProgress) {
        val cloud  = fetchProgressFromDrive(drive, local.bookId, local.ownerName)
        val merged = if (cloud != null) merge(local, cloud) else local
        val mtime  = store.uploadJson(drive, progressFileName(local.ownerName, local.bookId), toJson(merged))
        store.rememberMtime(progressFileName(local.ownerName, local.bookId), mtime)

        db.withTransaction {
            val current = progressDao.getProgress(local.bookId, local.ownerName)
            if (current != null && current.updatedAt == local.updatedAt) {
                // Nothing changed while uploading → store merged result as synced
                progressDao.saveProgress(merged.copy(isSynced = true, updatedAt = local.updatedAt))
            } else {
                Log.d(TAG, "push: ${local.bookId} edited during upload — stays pending")
            }
        }
        Log.d(TAG, "Pushed ✓ ${local.bookId} page=${merged.currentPage} pin=${merged.positionPinPage}")
        val note = when {
            cloud != null && cloud.lastReadTimestamp > local.lastReadTimestamp ->
                "kept newer page ${cloud.currentPage + 1} from other device"
            merged.isFinished && (cloud?.isFinished != true) -> "marked finished"
            else -> "page ${merged.currentPage + 1} / ${merged.totalPages}"
        }
        logEvent(local.ownerName, if (cloud != null && cloud.lastReadTimestamp > local.lastReadTimestamp) "CONFLICT" else "PUSH",
            local.bookId, merged.bookName, note)
    }

    private suspend fun pushBookmarksLocked(drive: Drive, bookId: String, ownerName: String) {
        val name = bookmarksFileName(ownerName, bookId)
        // 1. merge remote into local first so we upload the union
        store.listByName(drive, name).firstOrNull()?.let { f -> mergeBookmarksJson(store.download(drive, f.id)) }
        // 2. upload full local set (incl. tombstones)
        val all = bookmarkDao.getAllIncludingDeleted(bookId, ownerName)
        val arr = JSONArray()
        all.forEach { bm ->
            arr.put(JSONObject().apply {
                put("uid", bm.uid); put("bookId", bm.bookId); put("ownerName", bm.ownerName)
                put("pageNumber", bm.pageNumber); put("label", bm.label)
                put("createdAt", bm.createdAt); put("updatedAt", bm.updatedAt); put("isDeleted", bm.isDeleted)
            })
        }
        val json = JSONObject().apply { put("bookId", bookId); put("ownerName", ownerName); put("bookmarks", arr) }
        val mtime = store.uploadJson(drive, name, json.toString())
        store.rememberMtime(name, mtime)
        all.forEach { bookmarkDao.markSyncedIfUnchanged(it.uid, it.updatedAt) }
        Log.d(TAG, "Bookmarks pushed ✓ $bookId (${all.count { !it.isDeleted }} active)")
        logEvent(ownerName, "PUSH", bookId, progressDao.getProgress(bookId, ownerName)?.bookName ?: "",
            "bookmarks (${all.count { !it.isDeleted }})")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PULL (caller holds syncMutex)
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun pullLocked(drive: Drive, ownerName: String): Int {
        var updated = 0
        val owner = store.esc(ownerName)

        // Progress files
        val progressFiles = drive.files().list()
            .setQ("name contains 'progress_${owner}_' and trashed = false")
            .setFields("files(id, name, modifiedTime)").setSpaces("drive").execute().files
        for (f in progressFiles) {
            try {
                val mtime = f.modifiedTime?.value ?: 0L
                val bookId = f.name.removePrefix("progress_${ownerName}_").removeSuffix(".json")
                if (mtime > 0 && store.knownMtime(f.name) == mtime && progressDao.getProgress(bookId, ownerName) != null) continue
                val cloud = parseProgress(store.download(drive, f.id)) ?: continue
                if (applyCloud(cloud)) updated++
                store.rememberMtime(f.name, mtime)
            } catch (e: Exception) { Log.e(TAG, "pull ${f.name}: ${e.message}") }
        }

        // Bookmark files
        val bmFiles = drive.files().list()
            .setQ("name contains 'bookmarks_${owner}_' and trashed = false")
            .setFields("files(id, name, modifiedTime)").setSpaces("drive").execute().files
        for (f in bmFiles) {
            try {
                val mtime = f.modifiedTime?.value ?: 0L
                if (mtime > 0 && store.knownMtime(f.name) == mtime) continue
                mergeBookmarksJson(store.download(drive, f.id))
                store.rememberMtime(f.name, mtime)
            } catch (e: Exception) { Log.e(TAG, "pull ${f.name}: ${e.message}") }
        }

        // Highlight files
        val hlFiles = drive.files().list()
            .setQ("name contains 'highlights_${owner}_' and trashed = false")
            .setFields("files(id, name, modifiedTime)").setSpaces("drive").execute().files
        for (f in hlFiles) {
            try {
                val mtime = f.modifiedTime?.value ?: 0L
                if (mtime > 0 && store.knownMtime(f.name) == mtime) continue
                mergeHighlightsJson(store.download(drive, f.id))
                store.rememberMtime(f.name, mtime)
            } catch (e: Exception) { Log.e(TAG, "pull ${f.name}: ${e.message}") }
        }
        return updated
    }

    private fun highlightsFileName(owner: String, bookId: String) = "highlights_${owner}_${bookId}.json"

    private suspend fun pushHighlightsLocked(drive: Drive, bookId: String, ownerName: String) {
        val name = highlightsFileName(ownerName, bookId)
        store.listByName(drive, name).firstOrNull()?.let { f -> mergeHighlightsJson(store.download(drive, f.id)) }
        val all = highlightDao.allIncludingDeleted(bookId, ownerName)
        val arr = JSONArray()
        all.forEach { h ->
            arr.put(JSONObject().apply {
                put("uid", h.uid); put("bookId", h.bookId); put("ownerName", h.ownerName); put("page", h.page)
                put("l", h.left.toDouble()); put("t", h.top.toDouble()); put("r", h.right.toDouble()); put("b", h.bottom.toDouble())
                put("color", h.color); put("note", h.note)
                put("createdAt", h.createdAt); put("updatedAt", h.updatedAt); put("isDeleted", h.isDeleted)
            })
        }
        val json = JSONObject().apply { put("bookId", bookId); put("ownerName", ownerName); put("highlights", arr) }
        store.rememberMtime(name, store.uploadJson(drive, name, json.toString()))
        all.forEach { highlightDao.markSyncedIfUnchanged(it.uid, it.updatedAt) }
        logEvent(ownerName, "PUSH", bookId, progressDao.getProgress(bookId, ownerName)?.bookName ?: "",
            "highlights (${all.count { !it.isDeleted }})")
    }

    private suspend fun mergeHighlightsJson(text: String) {
        val root = JSONObject(text)
        val arr  = root.optJSONArray("highlights") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val remote = Highlight(
                uid = o.getString("uid"),
                bookId = o.optString("bookId", root.optString("bookId")),
                ownerName = o.optString("ownerName", root.optString("ownerName")),
                page = o.getInt("page"),
                left = o.getDouble("l").toFloat(), top = o.getDouble("t").toFloat(),
                right = o.getDouble("r").toFloat(), bottom = o.getDouble("b").toFloat(),
                color = o.getInt("color"), note = o.optString("note", ""),
                createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
                isDeleted = o.optBoolean("isDeleted", false), isSynced = true
            )
            val local = highlightDao.byUid(remote.uid)
            when (SyncMerge.decideHighlight(local?.updatedAt, remote.updatedAt, remote.isDeleted)) {
                SyncMerge.BookmarkAction.INSERT    -> highlightDao.upsert(remote)
                SyncMerge.BookmarkAction.OVERWRITE -> highlightDao.upsert(remote.copy(id = local!!.id))
                SyncMerge.BookmarkAction.IGNORE    -> {}
            }
        }
    }

    /** Merge a cloud record into Room atomically. Returns true if Room changed. */
    private suspend fun applyCloud(cloud: ReadingProgress): Boolean = db.withTransaction {
        val local = progressDao.getProgress(cloud.bookId, cloud.ownerName)
        if (local == null) {
            progressDao.saveProgress(cloud.copy(isSynced = true, updatedAt = System.currentTimeMillis()))
            return@withTransaction true
        }
        val merged = merge(local, cloud)
        if (sameContent(merged, local)) return@withTransaction false
        // If the merge equals the cloud copy, cloud fully supersedes local → synced.
        // Otherwise local still has something newer → keep pending so it gets pushed.
        progressDao.saveProgress(merged.copy(
            isSynced  = sameContent(merged, cloud),
            updatedAt = System.currentTimeMillis()
        ))
        Log.d(TAG, "Merged cloud → ${cloud.bookId}: page ${local.currentPage}→${merged.currentPage}, pin ${local.positionPinPage}→${merged.positionPinPage}")
        val parts = mutableListOf<String>()
        if (merged.currentPage != local.currentPage) parts += "page ${local.currentPage + 1} → ${merged.currentPage + 1}"
        if (merged.positionPinPage != local.positionPinPage) parts += "pin updated"
        if (merged.finishedAt != local.finishedAt) parts += if (merged.isFinished) "finished" else "read again"
        logEvent(cloud.ownerName, "PULL", cloud.bookId, merged.bookName, parts.joinToString(", ").ifEmpty { "updated" })
        if (merged.currentPage != local.currentPage)
            _remotePosition.tryEmit(RemotePosition(cloud.bookId, local.currentPage, merged.currentPage))
        true
    }

    private suspend fun mergeBookmarksJson(text: String) {
        val root = JSONObject(text)
        val arr  = root.optJSONArray("bookmarks") ?: return
        for (i in 0 until arr.length()) {
            val o         = arr.getJSONObject(i)
            val bookId    = o.optString("bookId", root.optString("bookId"))
            val owner     = o.optString("ownerName", root.optString("ownerName"))
            val page      = o.getInt("pageNumber")
            val createdAt = o.optLong("createdAt", 0L)
            val uid       = o.optString("uid").ifEmpty { SyncMerge.legacyUid(bookId, page, createdAt) }
            val remote = Bookmark(
                bookId = bookId, ownerName = owner, pageNumber = page,
                label = o.optString("label", "Page ${page + 1}"), createdAt = createdAt,
                uid = uid, updatedAt = o.optLong("updatedAt", createdAt),
                isDeleted = o.optBoolean("isDeleted", false), isSynced = true
            )
            val local = bookmarkDao.getByUid(uid)
            when (SyncMerge.decideBookmark(local, remote)) {
                SyncMerge.BookmarkAction.INSERT    -> bookmarkDao.upsert(remote)
                SyncMerge.BookmarkAction.OVERWRITE -> bookmarkDao.upsert(remote.copy(id = local!!.id))
                SyncMerge.BookmarkAction.IGNORE    -> {}
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MERGE LOGIC
    // ═════════════════════════════════════════════════════════════════════════

    private fun merge(local: ReadingProgress, cloud: ReadingProgress) = SyncMerge.merge(local, cloud)
    private fun sameContent(a: ReadingProgress, b: ReadingProgress) = SyncMerge.sameContent(a, b)

    // ═════════════════════════════════════════════════════════════════════════
    // JSON
    // ═════════════════════════════════════════════════════════════════════════

    private fun toJson(p: ReadingProgress): String = JSONObject().apply {
        put("bookId",             p.bookId)
        put("ownerName",          p.ownerName)
        put("bookName",           p.bookName)
        put("currentPage",        p.currentPage)
        put("totalPages",         p.totalPages)
        put("scrollOffset",       p.scrollOffset.toDouble())
        put("timestamp",          p.lastReadTimestamp)
        put("totalReadingTimeMs", p.totalReadingTimeMs)
        put("positionPinPage",    p.positionPinPage)
        put("positionPinYRatio",  p.positionPinYRatio.toDouble())
        put("pinUpdatedAt",       p.pinUpdatedAt)
        put("finishedAt",         p.finishedAt)
        put("finishedUpdatedAt",  p.finishedUpdatedAt)
        put("schema",             4)
    }.toString()

    private fun parseProgress(text: String): ReadingProgress? = try {
        val j  = JSONObject(text)
        val ts = j.getLong("timestamp")
        val pinPage = j.optInt("positionPinPage", -1)
        ReadingProgress(
            bookId             = j.getString("bookId"),
            ownerName          = j.getString("ownerName"),
            bookName           = j.optString("bookName", ""),
            currentPage        = j.getInt("currentPage"),
            totalPages         = j.getInt("totalPages"),
            scrollOffset       = j.optDouble("scrollOffset", 0.0).toFloat(),
            lastReadTimestamp  = ts,
            totalReadingTimeMs = j.optLong("totalReadingTimeMs", 0L),
            isSynced           = true,
            positionPinPage    = pinPage,
            positionPinYRatio  = j.optDouble("positionPinYRatio", -1.0).toFloat(),
            // Legacy files (schema < 4) have no pin stamp: treat their pin as old as the record
            pinUpdatedAt       = j.optLong("pinUpdatedAt", if (pinPage >= 0) ts else 0L),
            finishedAt         = j.optLong("finishedAt", 0L),
            finishedUpdatedAt  = j.optLong("finishedUpdatedAt", 0L)
        )
    } catch (e: Exception) { Log.e(TAG, "parseProgress: ${e.message}"); null }

    // ═════════════════════════════════════════════════════════════════════════
    // DRIVE PRIMITIVES
    // ═════════════════════════════════════════════════════════════════════════

    private fun progressFileName(owner: String, bookId: String)  = "progress_${owner}_${bookId}.json"
    private fun bookmarksFileName(owner: String, bookId: String) = "bookmarks_${owner}_${bookId}.json"

    private fun fetchProgressFromDrive(drive: Drive, bookId: String, ownerName: String): ReadingProgress? {
        val f = store.listByName(drive, progressFileName(ownerName, bookId)).firstOrNull() ?: return null
        return parseProgress(store.download(drive, f.id))
    }

    /** Old builds kept a file queue; Room already holds that data as isSynced = false. */
    private fun cleanupLegacyQueue() {
        val dir = File(context.filesDir, "sync_queue")
        if (dir.exists()) dir.deleteRecursively()
    }

    fun getDriveService(): Drive? = store.driveService()
    fun isOnline(): Boolean = store.isOnline()

    companion object {
        private const val TAG = "SYNC_DEBUG"
        /** ONE lock for every Drive read/write in the process. */
        private val syncMutex = Mutex()
        private val bgScope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val _remotePosition = MutableSharedFlow<RemotePosition>(extraBufferCapacity = 8)
    }

    data class RemotePosition(val bookId: String, val oldPage: Int, val newPage: Int)
}
