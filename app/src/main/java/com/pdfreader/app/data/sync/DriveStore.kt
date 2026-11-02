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
    }
}
