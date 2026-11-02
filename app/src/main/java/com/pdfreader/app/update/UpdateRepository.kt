package com.pdfreader.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.pdfreader.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed class CheckResult {
    /** No GitHub repository is built into this app (a local build). */
    object NotConfigured : CheckResult()
    object UpToDate : CheckResult()
    data class Available(val info: UpdateInfo) : CheckResult()
    data class Failed(val message: String) : CheckResult()
}

class UpdateException(message: String) : IOException(message)

/** Check, download and verify. Talks to the GitHub API with plain HttpURLConnection (no extra libraries). */
class UpdateRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = UpdatePrefs(appContext)

    val repo: String get() = BuildConfig.UPDATE_REPO
    val isConfigured: Boolean get() = repo.contains('/')

    fun installedCode(): Long = try {
        PackageInfoCompat.getLongVersionCode(appContext.packageManager.getPackageInfo(appContext.packageName, 0))
    } catch (e: Exception) { 0L }

    fun installedName(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    /** The release saved by the last successful check, if any. */
    fun cachedUpdate(): UpdateInfo? = UpdateParser.parseRelease(prefs.cachedReleaseJson)

    /** What the Home banner and the notification should show right now (null = nothing). */
    fun pendingOffer(): UpdateInfo? {
        val info = cachedUpdate() ?: return null
        return if (UpdateParser.shouldOffer(info, installedCode(), prefs.skippedCode)) info else null
    }

    /** If the app was updated since it last ran, returns the new version name once. */
    fun consumeUpdatedVersionName(): String? {
        val now = installedCode()
        val before = prefs.lastRunCode
        prefs.lastRunCode = now
        return if (before in 1 until now) installedName() else null
    }

    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext CheckResult.NotConfigured
        try {
            val json = readText("https://api.github.com/repos/$repo/releases/latest", json = true)
            prefs.lastCheckAt = System.currentTimeMillis()
            val info = UpdateParser.parseRelease(json)
            if (info == null) {
                // A release without a matching APK (or a pre-release) means "nothing new".
                prefs.cachedReleaseJson = ""
                return@withContext CheckResult.UpToDate
            }
            prefs.cachedReleaseJson = json
            if (UpdateParser.isNewer(info, installedCode())) CheckResult.Available(info) else CheckResult.UpToDate
        } catch (e: UpdateException) {
            CheckResult.Failed(e.message ?: "Update check failed")
        } catch (e: Exception) {
            CheckResult.Failed("Could not reach GitHub (${e.javaClass.simpleName}). Check your internet connection.")
        }
    }

    /**
     * Downloads the APK into the cache (or reuses a verified earlier download), then verifies the SHA-256
     * from the release, the package name and the version code. Returns the verified file.
     */
    suspend fun download(info: UpdateInfo, onProgress: (done: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val expected = loadExpectedSha(info)
            val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, info.apk.name)

            if (target.exists() && target.length() > 0) {
                if (runCatching { verify(target, info, expected) }.isSuccess) {
                    onProgress(target.length(), target.length())
                    return@withContext target
                }
                target.delete()
            }
            dir.listFiles()?.forEach { if (it.name != target.name) it.delete() }

            val part = File(dir, info.apk.name + ".part")
            val digest = MessageDigest.getInstance("SHA-256")
            val total = info.apk.size
            var done = 0L
            openStream(assetUrl(info.apk)) { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != expected) {
                part.delete()
                throw UpdateException("The downloaded file is damaged (checksum mismatch). Try again.")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw UpdateException("Could not save the downloaded update.")
            verifyPackage(target, info)
            target
        }

    /** True when an earlier Wi-Fi download of this exact build is still in the cache. */
    fun hasDownloaded(info: UpdateInfo): Boolean {
        val f = File(File(appContext.cacheDir, "updates"), info.apk.name)
        return f.exists() && f.length() > 0 && prefs.predownloadedCode == info.versionCode
    }

    // ---- verification ------------------------------------------------------------------------------
    private fun loadExpectedSha(info: UpdateInfo): String {
        val asset = info.sha256Asset
            ?: throw UpdateException("This release has no checksum file (.sha256), so the update was not installed.")
        val text = readText(assetUrl(asset), json = false)
        return UpdateParser.parseSha256(text)
            ?: throw UpdateException("The checksum file of this release is not valid.")
    }

    private fun verify(file: File, info: UpdateInfo, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (got != expected) throw UpdateException("checksum mismatch")
        verifyPackage(file, info)
    }

    private fun verifyPackage(file: File, info: UpdateInfo) {
        val pm = appContext.packageManager
        @Suppress("DEPRECATION")
        val archive = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageArchiveInfo(file.path, 0)
        }
        val pi = archive ?: throw UpdateException("The downloaded file is not a valid app package.")
        if (pi.packageName != appContext.packageName) {
            throw UpdateException("The downloaded file belongs to a different app (${pi.packageName}).")
        }
        val code = PackageInfoCompat.getLongVersionCode(pi)
        if (code != info.versionCode) {
            throw UpdateException("The downloaded file has build $code but the release says ${info.versionCode}.")
        }
    }

    // ---- HTTP --------------------------------------------------------------------------------------
    /** A private repository needs the API asset URL plus the token; a public one can use the plain link. */
    private fun assetUrl(a: ReleaseAsset): String =
        if (prefs.token.isNotEmpty() && a.apiUrl.isNotEmpty()) a.apiUrl else a.downloadUrl

    private fun readText(url: String, json: Boolean): String {
        var text = ""
        openStream(url, json) { text = it.readBytes().toString(Charsets.UTF_8) }
        return text
    }

    /**
     * Opens [url] and hands the body to [consume]. Redirects are followed by hand so the GitHub token is never
     * sent to another host (GitHub redirects downloads to a signed storage URL that rejects it).
     */
    private fun openStream(url: String, json: Boolean = false, consume: (java.io.InputStream) -> Unit) {
        var current = url
        val startHost = URL(url).host
        var hops = 0
        while (true) {
            if (!current.startsWith("https://")) throw UpdateException("The update address is not https.")
            val conn = URL(current).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "Kitaab-Updater")
                conn.setRequestProperty("Accept", if (json) "application/vnd.github+json" else "application/octet-stream")
                if (json) conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                val sameHost = URL(current).host == startHost
                if (prefs.token.isNotEmpty() && sameHost) {
                    conn.setRequestProperty("Authorization", "Bearer ${prefs.token}")
                }
                val code = conn.responseCode
                when {
                    code in listOf(301, 302, 303, 307, 308) -> {
                        val loc = conn.getHeaderField("Location") ?: throw UpdateException("Bad redirect from GitHub.")
                        if (++hops > 6) throw UpdateException("Too many redirects.")
                        current = URL(URL(current), loc).toString()
                    }
                    code == 200 -> {
                        conn.inputStream.use { consume(it) }
                        return
                    }
                    code == 404 -> throw UpdateException(
                        if (prefs.token.isEmpty())
                            "No release found. If the repository is private, add a GitHub token below."
                        else "No release found (or the token cannot read this repository)."
                    )
                    code == 401 -> throw UpdateException("GitHub refused the token. Check it and try again.")
                    code == 403 || code == 429 -> throw UpdateException("GitHub is limiting requests right now. Try again in a while.")
                    else -> throw UpdateException("GitHub answered with error $code.")
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
