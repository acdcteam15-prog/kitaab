package com.pdfreader.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.media.MediaHttpUploader
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import com.pdfreader.app.data.pdf.cleanBookName
import com.pdfreader.app.data.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class DriveRepository(private val context: Context) {

    private val TAG = "DriveRepository"

    private fun getBooksDir(): File =
        File(context.getExternalFilesDir(null), "books").also { it.mkdirs() }

    private fun getThumbDir(): File =
        File(context.getExternalFilesDir(null), "thumbs").also { it.mkdirs() }

    fun getDriveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE)
        ).apply { selectedAccount = account.account }
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("PDFReader").build()
    }

    // ── Cover thumbnail ───────────────────────────────────────────────────────

    /**
     * Returns the path to a cached cover PNG for [fileId], generating it
     * from page 0 of the local PDF if not yet created.
     */
    fun getOrGenerateCoverThumb(fileId: String): String? {
        val thumbFile = File(getThumbDir(), "$fileId.png")
        if (thumbFile.exists() && thumbFile.length() > 0) return thumbFile.absolutePath

        val pdfFile = getLocalFile(fileId) ?: return null
        return try {
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val page = renderer.openPage(0)

            // Render at fixed width 300px, maintain aspect ratio
            val width  = 300
            val height = (width.toFloat() / page.width * page.height).toInt()
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            pfd.close()

            FileOutputStream(thumbFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 85, out)
            }
            bmp.recycle()
            thumbFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Thumb generation failed for $fileId: ${e.message}")
            null
        }
    }

    // ── Local book list ───────────────────────────────────────────────────────

    fun getAllDownloadedBooks(ownerName: String): List<Book> {
        val dir = getBooksDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "pdf" && f.length() > 0 }
            ?.mapNotNull { pdfFile ->
                val metaFile = File(dir, "${pdfFile.nameWithoutExtension}.json")
                if (metaFile.exists()) {
                    try {
                        val json = org.json.JSONObject(metaFile.readText())
                        if (json.optString("owner") != ownerName) return@mapNotNull null
                        val fid = json.getString("id")
                        Book(
                            id           = fid,
                            name         = json.getString("name"),
                            owner        = json.getString("owner"),
                            driveFileId  = fid,
                            thumbnailUrl = json.optString("thumbnailUrl").ifEmpty { null },
                            localThumbPath = getOrGenerateCoverThumb(fid),
                            fileSize     = pdfFile.length(),
                            localPath    = pdfFile.absolutePath
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read metadata for ${pdfFile.name}", e)
                        null
                    }
                } else null
            } ?: emptyList()
    }

    suspend fun getBooksForUser(userName: String): Result<List<Book>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val drive = getDriveService()
                    ?: throw IllegalStateException("Not signed in to Google")

                val rootFolder = drive.files().list()
                    .setQ("name = 'Books' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                    .setFields("files(id, name)").setSpaces("drive").execute()
                    .files.firstOrNull()
                    ?: throw Exception("'Books' folder not found in your Drive")

                val userFolder = drive.files().list()
                    .setQ("name = '$userName' and mimeType = 'application/vnd.google-apps.folder' and '${rootFolder.id}' in parents and trashed = false")
                    .setFields("files(id, name)").setSpaces("drive").execute()
                    .files.firstOrNull()
                    ?: throw Exception("Folder '$userName' not found inside 'Books'")

                val pdfFiles = drive.files().list()
                    .setQ("mimeType = 'application/pdf' and '${userFolder.id}' in parents and trashed = false")
                    .setFields("files(id, name, size, thumbnailLink)")
                    .setOrderBy("name").setSpaces("drive").execute().files

                Log.d(TAG, "PDFs found: ${pdfFiles.size}")

                pdfFiles.map { file ->
                    val fid = file.id
                    Book(
                        id             = fid,
                        name           = file.name.removeSuffix(".pdf"),
                        owner          = userName,
                        driveFileId    = fid,
                        thumbnailUrl   = file.thumbnailLink,
                        localThumbPath = getOrGenerateCoverThumb(fid),
                        fileSize       = file.getSize() ?: 0L,
                        localPath      = getLocalFile(fid)?.absolutePath
                    )
                }
            }
        }

    suspend fun downloadPdf(
        fileId: String,
        fileName: String,
        ownerName: String = "",
        thumbnailUrl: String? = null,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir       = getBooksDir()
            val cacheFile = File(dir, "$fileId.pdf")

            if (cacheFile.exists() && cacheFile.length() > 0) {
                onProgress(100)
                return@runCatching cacheFile
            }

            val drive    = getDriveService() ?: throw IllegalStateException("Not signed in")
            val tempFile = File(context.cacheDir, "$fileId.tmp")

            // Chunked download so we get real progress callbacks
            val request = drive.files().get(fileId)
            request.mediaHttpDownloader.apply {
                isDirectDownloadEnabled = false
                chunkSize = 2 * 1024 * 1024
                setProgressListener { d ->
                    if (d.downloadState == com.google.api.client.googleapis.media.MediaHttpDownloader.DownloadState.MEDIA_IN_PROGRESS)
                        onProgress((d.progress * 100).toInt().coerceIn(0, 99))
                }
            }
            FileOutputStream(tempFile).use { out -> request.executeMediaAndDownloadTo(out) }
            onProgress(100)
            tempFile.copyTo(cacheFile, overwrite = true)
            tempFile.delete()

            if (ownerName.isNotEmpty()) {
                val meta = org.json.JSONObject().apply {
                    put("id",           fileId)
                    put("name",         fileName)
                    put("owner",        ownerName)
                    put("thumbnailUrl", thumbnailUrl ?: "")
                }
                File(dir, "$fileId.json").writeText(meta.toString())
            }

            // Generate cover thumb right after download
            getOrGenerateCoverThumb(fileId)

            cacheFile
        }
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Uploads [localFile] to My Drive › Books › [ownerName] (folders are created if missing), then
     * keeps a local copy so the book shows up and opens immediately — no re-download.
     * A name clash gets " (2)", " (3)"… — an existing book is never overwritten.
     */
    suspend fun uploadPdf(
        localFile: File,
        desiredName: String,
        ownerName: String,
        onProgress: (Int) -> Unit = {}
    ): Result<Book> = withContext(Dispatchers.IO) {
        runCatching {
            val drive = getDriveService() ?: throw IllegalStateException("Not signed in to Google")
            val folderId = findOrCreateUserFolder(drive, ownerName)
            val finalName = uniqueName(drive, folderId, cleanBookName(desiredName))

            val meta = DriveFile().apply {
                name     = "$finalName.pdf"
                mimeType = "application/pdf"
                parents  = listOf(folderId)
            }
            val request = drive.files()
                .create(meta, FileContent("application/pdf", localFile))
                .setFields("id, name, size, thumbnailLink")
            request.mediaHttpUploader.apply {
                isDirectUploadEnabled = false
                chunkSize = 2 * 1024 * 1024          // must be a multiple of 256 KB
                setProgressListener { u ->
                    if (u.uploadState == MediaHttpUploader.UploadState.MEDIA_IN_PROGRESS)
                        onProgress((u.progress * 100).toInt().coerceIn(0, 99))
                }
            }
            val created = request.execute()
            onProgress(100)

            // Local copy + metadata, same layout downloadPdf() writes
            val dir = getBooksDir()
            localFile.copyTo(File(dir, "${created.id}.pdf"), overwrite = true)
            File(dir, "${created.id}.json").writeText(
                org.json.JSONObject().apply {
                    put("id",           created.id)
                    put("name",         finalName)
                    put("owner",        ownerName)
                    put("thumbnailUrl", created.thumbnailLink ?: "")
                }.toString()
            )
            val thumb = getOrGenerateCoverThumb(created.id)

            Book(
                id             = created.id,
                name           = finalName,
                owner          = ownerName,
                driveFileId    = created.id,
                thumbnailUrl   = created.thumbnailLink,
                localThumbPath = thumb,
                fileSize       = localFile.length(),
                localPath      = getLocalFile(created.id)?.absolutePath
            )
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private fun findOrCreateUserFolder(drive: Drive, userName: String): String {
        val folderMime = "application/vnd.google-apps.folder"
        val root = drive.files().list()
            .setQ("name = 'Books' and mimeType = '$folderMime' and trashed = false")
            .setFields("files(id, name)").setSpaces("drive").execute()
            .files?.firstOrNull()
            ?: drive.files().create(DriveFile().apply { name = "Books"; mimeType = folderMime })
                .setFields("id").execute()

        val user = drive.files().list()
            .setQ("name = '${esc(userName)}' and mimeType = '$folderMime' and '${root.id}' in parents and trashed = false")
            .setFields("files(id, name)").setSpaces("drive").execute()
            .files?.firstOrNull()
            ?: drive.files().create(DriveFile().apply {
                name = userName; mimeType = folderMime; parents = listOf(root.id)
            }).setFields("id").execute()
        return user.id
    }

    private fun uniqueName(drive: Drive, folderId: String, base: String): String {
        fun taken(n: String) = !drive.files().list()
            .setQ("name = '${esc("$n.pdf")}' and '$folderId' in parents and trashed = false")
            .setFields("files(id)").setSpaces("drive").execute().files.isNullOrEmpty()
        var candidate = base
        var i = 2
        while (taken(candidate)) { candidate = "$base ($i)"; i++ }
        return candidate
    }

    fun deleteLocalPdf(fileId: String): Boolean {
        val dir = getBooksDir()
        File(dir, "$fileId.json").delete()
        File(getThumbDir(), "$fileId.png").delete()
        val file = File(dir, "$fileId.pdf")
        return if (file.exists()) file.delete() else false
    }

    fun getLocalFile(fileId: String): File? {
        val file = File(getBooksDir(), "$fileId.pdf")
        return if (file.exists() && file.length() > 0) file else null
    }

    fun isDownloaded(fileId: String): Boolean = getLocalFile(fileId) != null
}
