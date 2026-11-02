package com.pdfreader.app.data.crop

import android.content.Context
import android.util.Log
import com.pdfreader.app.KitaabApplication
import com.pdfreader.app.data.sync.DriveStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps each book's [CropProfile] on the phone and in a small JSON file on Drive
 * (cropprofile_<owner>_<bookId>.json), so the crop, footnote fixes and clean-up options
 * set on one device show up on the other. Newest change wins. Works offline: a change made
 * without internet is pushed the next time the book is opened online.
 */
class CropProfileStore(context: Context) {

    private val app = context.applicationContext
    private val sp get() = app.getSharedPreferences("crop_profiles", Context.MODE_PRIVATE)
    private val drive = DriveStore(app)

    private fun fileName(owner: String, bookId: String) = "cropprofile_${owner}_${bookId}.json"
    private fun dirtyKey(bookId: String) = "dirty_$bookId"

    fun load(bookId: String): CropProfile? =
        sp.getString(bookId, null)?.let { CropProfile.fromJson(bookId, it) }

    /** Save locally right away; upload a moment later (several edits collapse into one push). */
    fun save(p: CropProfile, owner: String) {
        p.updatedAt = System.currentTimeMillis()
        sp.edit().putString(p.bookId, p.toJson()).putBoolean(dirtyKey(p.bookId), true).apply()
        if (owner.isEmpty()) return
        pending[p.bookId]?.cancel()
        pending[p.bookId] = scope().launch {
            delay(1500)
            pushNow(p, owner)
        }
    }

    private fun scope(): CoroutineScope =
        (app as? KitaabApplication)?.appScope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun pushNow(p: CropProfile, owner: String): Boolean {
        return try {
            if (!drive.isOnline()) return false
            val svc = drive.driveService() ?: return false
            drive.uploadJson(svc, fileName(owner, p.bookId), p.toJson())
            sp.edit().putBoolean(dirtyKey(p.bookId), false).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "push failed: ${e.message}"); false
        }
    }

    /**
     * Compare with Drive. Returns the remote profile when it is newer (caller adopts it),
     * otherwise null. A local change that never reached Drive is pushed here.
     */
    suspend fun sync(local: CropProfile, owner: String): CropProfile? = withContext(Dispatchers.IO) {
        if (owner.isEmpty()) return@withContext null
        try {
            if (!drive.isOnline()) return@withContext null
            val svc = drive.driveService() ?: return@withContext null
            val name = fileName(owner, local.bookId)
            val file = drive.listByName(svc, name).firstOrNull()
            val remote = file?.let { CropProfile.fromJson(local.bookId, drive.download(svc, it.id)) }
            when {
                remote != null && remote.updatedAt > local.updatedAt -> {
                    sp.edit().putString(local.bookId, remote.toJson()).putBoolean(dirtyKey(local.bookId), false).apply()
                    remote
                }
                (remote == null && local.updatedAt > 0L) ||
                    (remote != null && sp.getBoolean(dirtyKey(local.bookId), false) && local.updatedAt > remote.updatedAt) -> {
                    pushNow(local, owner); null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "sync failed: ${e.message}"); null
        }
    }

    companion object {
        private const val TAG = "CropProfileStore"
        private val pending = HashMap<String, Job>()
    }
}
