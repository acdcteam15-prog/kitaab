package com.pdfreader.app.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Low-level Drive file operations used by sync: find by name, download, upload
 * (create/update), and a modified-time cache so unchanged files are never re-downloaded.
 * No sync rules live here — see [SyncMerge] and ProgressRepository.
 */
class DriveStore(private val context: Context) {

    fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private val driveMeta get() = context.getSharedPreferences("drive_file_meta", Context.MODE_PRIVATE)

    fun listByName(drive: Drive, name: String): List<DriveFile> =
        drive.files().list()
            .setQ("name = '${esc(name)}' and trashed = false")
            .setFields("files(id, modifiedTime)").setSpaces("drive").execute().files ?: emptyList()

    fun download(drive: Drive, fileId: String): String {
        val out = ByteArrayOutputStream()
        drive.files().get(fileId).executeMediaAndDownloadTo(out)
        return out.toString(Charsets.UTF_8.name())
    }

    /** Upload (create or update). Returns the new modifiedTime (ms) or 0. */
    fun uploadJson(drive: Drive, fileName: String, json: String): Long {
        val content  = InputStreamContent("application/json", ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)))
        val existing = listByName(drive, fileName)
        val result = if (existing.isNotEmpty())
            drive.files().update(existing[0].id, null, content).setFields("id, modifiedTime").execute()
        else
            drive.files().create(DriveFile().apply { name = fileName }, content).setFields("id, modifiedTime").execute()
        // Remove accidental duplicates created by older versions
        existing.drop(1).forEach { runCatching { drive.files().delete(it.id).execute() } }
        return result.modifiedTime?.value ?: 0L
    }

    // ---- books/<name>/progress.json -----------------------------------------------------------------------

    /** Folders directly inside [parentId] ("root" = My Drive), all pages. */
    private fun childFolders(drive: Drive, parentId: String): List<DriveFile> {
        val out = mutableListOf<DriveFile>()
        var token: String? = null
        do {
            val res = drive.files().list()
                .setQ("'$parentId' in parents and mimeType = '$FOLDER_MIME' and trashed = false")
                .setFields("nextPageToken, files(id, name)").setSpaces("drive").setPageSize(1000)
                .setPageToken(token).execute()
            res.files?.let { out.addAll(it) }
            token = res.nextPageToken
        } while (token != null)
        return out
    }

    /** Finds the folder (ignoring case) or creates it. Returns its id and its real name on Drive. */
    private fun findOrCreateFolder(drive: Drive, name: String, parentId: String): Pair<String, String> {
        // Match ignoring case, so an older "Books" folder is reused as "books"
        val existing = childFolders(drive, parentId).firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) return existing.id to existing.name
        val id = drive.files().create(DriveFile().apply {
            this.name = name; mimeType = FOLDER_MIME; parents = listOf(parentId)
        }).setFields("id").execute().id
        return id to name
    }

    /**
     * My Drive / books / [userName]. Both folders are created when missing. Returns the id and the real
     * folder name (an existing "Bader" folder is reused when the user types "bader").
     * The result is remembered in memory so the folders are not looked up again on every sync.
     */
    fun resolveUserFolder(drive: Drive, userName: String): Pair<String, String> {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        val key = (account?.email ?: account?.id ?: "") + "|" + userName.lowercase()
        folderCache[key]?.let { return it }
        val root = findOrCreateFolder(drive, ROOT_FOLDER, "root").first
        val user = findOrCreateFolder(drive, userName, root)
        folderCache[key] = user
        return user
    }

    fun ensureUserFolder(drive: Drive, userName: String): String = resolveUserFolder(drive, userName).first

    fun forgetFolders() = folderCache.clear()

    /** What was read from the user's folder. [fileId] is null when progress.json does not exist yet. */
    class DocFile(
        val folderId: String, val fileId: String?, val doc: ProgressDoc, val mtime: Long,
        /** True when the file is exactly the version already pulled before (the document was not downloaded). */
        val unchanged: Boolean = false
    )

    fun docMtimeKey(userName: String): String {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return "progress_doc|" + (account?.email ?: account?.id ?: "") + "|" + userName.lowercase()
    }

    fun readDoc(drive: Drive, userName: String, skipIfMtime: Long = -1L): DocFile {
        val folder = ensureUserFolder(drive, userName)
        val files = drive.files().list()
            .setQ("name = '${esc(ProgressDoc.FILE_NAME)}' and '$folder' in parents and trashed = false")
            .setFields("files(id, modifiedTime)").setSpaces("drive").execute().files ?: emptyList()
        val first = files.firstOrNull() ?: return DocFile(folder, null, ProgressDoc(userName), 0L)
        val mtime = first.modifiedTime?.value ?: 0L
        if (skipIfMtime > 0 && mtime == skipIfMtime) return DocFile(folder, first.id, ProgressDoc(userName), mtime, unchanged = true)
        val text = download(drive, first.id)
        // A damaged file must not be overwritten silently: stop and report.
        val doc = if (text.isBlank()) ProgressDoc(userName)
        else ProgressDoc.parse(text, userName) ?: throw IllegalStateException("progress.json in your books/$userName folder is damaged")
        return DocFile(folder, first.id, doc, mtime)
    }

    /** Writes the document (create or update) and returns the file as it now exists (with its id and time). */
    fun writeDoc(drive: Drive, userName: String, file: DocFile): DocFile {
        val content = InputStreamContent("application/json", ByteArrayInputStream(file.doc.toJson().toByteArray(Charsets.UTF_8)))
        val result = if (file.fileId != null)
            drive.files().update(file.fileId, null, content).setFields("id, modifiedTime").execute()
        else
            drive.files().create(DriveFile().apply {
                name = ProgressDoc.FILE_NAME; parents = listOf(file.folderId); mimeType = "application/json"
            }, content).setFields("id, modifiedTime").execute()
        return DocFile(file.folderId, result.id, file.doc, result.modifiedTime?.value ?: 0L)
    }

    /** Old per-book files (progress_/bookmarks_/highlights_ and the weekly snapshot) of [userName]. */
    fun listLegacyFiles(drive: Drive, userName: String): List<DriveFile> {
        val out = mutableListOf<DriveFile>()
        for (prefix in listOf("progress_${userName}_", "bookmarks_${userName}_", "highlights_${userName}_", "weekly_snapshot_${userName}.json")) {
            var token: String? = null
            do {
                val res = drive.files().list()
                    .setQ("name contains '${esc(prefix)}' and trashed = false")
                    .setFields("nextPageToken, files(id, name)").setSpaces("drive").setPageSize(1000)
                    .setPageToken(token).execute()
                res.files?.let { f -> out.addAll(f.filter { it.name.startsWith(prefix) && it.name.endsWith(".json") }) }
                token = res.nextPageToken
            } while (token != null)
        }
        return out
    }

    fun trash(drive: Drive, fileId: String) {
        drive.files().update(fileId, DriveFile().apply { trashed = true }).setFields("id").execute()
    }

    fun isMigrated(userName: String): Boolean = driveMeta.getBoolean(migrationKey(userName), false)
    fun markMigrated(userName: String) { driveMeta.edit().putBoolean(migrationKey(userName), true).apply() }
    private fun migrationKey(userName: String): String {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return "migrated_v5|" + (account?.email ?: account?.id ?: "") + "|" + userName.lowercase()
    }

    fun knownMtime(name: String) = driveMeta.getLong(name, -1L)
    fun rememberMtime(name: String, mtime: Long) {
        if (mtime > 0) driveMeta.edit().putLong(name, mtime).apply()
    }

    fun driveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val key = account.email ?: account.id ?: return null
        cachedDrive?.let { (k, d) -> if (k == key) return d }
        val cred = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE))
            .apply { selectedAccount = account.account }
        val drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), cred)
            .setApplicationName("PDFReader").build()
        cachedDrive = key to drive
        return drive
    }

    fun isOnline(): Boolean {
        val cm   = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        @Volatile private var cachedDrive: Pair<String, Drive>? = null
        private val folderCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
        const val ROOT_FOLDER = "books"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
