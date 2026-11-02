package com.pdfreader.app.update

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One file attached to a GitHub release. */
data class ReleaseAsset(
    val name: String,
    val size: Long,
    /** https://api.github.com/repos/.../releases/assets/ID (works for private repositories with a token). */
    val apiUrl: String,
    /** The public browser_download_url. */
    val downloadUrl: String
)

/** A release that holds a newer app, ready to show or install. */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val required: Boolean,
    val notes: String,
    val tag: String,
    val pageUrl: String,
    val publishedAt: String,
    val apk: ReleaseAsset,
    val sha256Asset: ReleaseAsset?
)

/** Parsed form of the file name `<App>-<version with underscores>-build<code>.apk`. */
data class ApkName(val app: String, val versionName: String, val versionCode: Long)

/**
 * Pure logic of the updater (no Android classes), so it is unit-tested on the JVM.
 * It reads the JSON that GitHub returns for `releases/latest`.
 */
object UpdateParser {

    /** Put this anywhere in the release notes to make the update required. */
    const val REQUIRED_MARKER = "[required]"

    private const val MAX_NOTES = 4000
    private val APK_NAME = Regex("""^(.+?)-([0-9][0-9A-Za-z_]*)-build([0-9]+)\.apk$""")
    private val SHA256 = Regex("""(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])""")
    private val MARKER = Regex("""(?i)\[required]""")

    /** `Kitaab-1_2_0-build1007.apk` -> app Kitaab, version 1.2.0, code 1007. Null when the name does not fit. */
    fun parseApkName(fileName: String): ApkName? {
        val m = APK_NAME.matchEntire(fileName.trim()) ?: return null
        val code = m.groupValues[3].toLongOrNull() ?: return null
        return ApkName(m.groupValues[1], m.groupValues[2].replace('_', '.'), code)
    }

    fun isRequired(body: String?): Boolean = body != null && MARKER.containsMatchIn(body)

    /** Release notes without the "[required]" marker, trimmed and capped. */
    fun cleanNotes(body: String?): String {
        if (body == null) return ""
        val t = body.replace("\r\n", "\n").replace(MARKER, "").trim()
        return if (t.length > MAX_NOTES) t.substring(0, MAX_NOTES).trimEnd() + "\u2026" else t
    }

    /** First 64-hex-digit word in the text (the format of `sha256sum` and of a bare hash), lower case. */
    fun parseSha256(text: String?): String? =
        if (text == null) null else SHA256.find(text)?.value?.lowercase()

    /**
     * Reads the JSON of one release. Returns null for drafts, pre-releases, broken JSON, or a release that
     * has no `<App>-<version>-build<code>.apk` file. With several APKs the highest build code wins.
     */
    fun parseRelease(json: String?): UpdateInfo? {
        if (json.isNullOrBlank()) return null
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            return null
        }
        if (!root.isJsonObject) return null
        val o = root.asJsonObject
        if (o.bool("draft") || o.bool("prerelease")) return null

        val assets = mutableListOf<ReleaseAsset>()
        val arr = o.get("assets")
        if (arr != null && arr.isJsonArray) {
            for (el in arr.asJsonArray) {
                if (!el.isJsonObject) continue
                val a = el.asJsonObject
                val name = a.str("name")
                if (name.isEmpty()) continue
                assets += ReleaseAsset(name, a.long("size"), a.str("url"), a.str("browser_download_url"))
            }
        }
        var best: Pair<ReleaseAsset, ApkName>? = null
        for (a in assets) {
            if (!a.name.endsWith(".apk")) continue
            val parsed = parseApkName(a.name) ?: continue
            if (best == null || parsed.versionCode > best.second.versionCode) best = a to parsed
        }
        val (apk, apkName) = best ?: return null
        val sha = assets.firstOrNull { it.name == apk.name + ".sha256" }
        val body = o.str("body")
        return UpdateInfo(
            versionName = apkName.versionName,
            versionCode = apkName.versionCode,
            required = isRequired(body),
            notes = cleanNotes(body),
            tag = o.str("tag_name"),
            pageUrl = o.str("html_url"),
            publishedAt = o.str("published_at"),
            apk = apk,
            sha256Asset = sha
        )
    }

    /** True when [info] is newer than the installed build. */
    fun isNewer(info: UpdateInfo, installedCode: Long): Boolean = info.versionCode > installedCode

    /**
     * Should the user be told about [info]? A required update is always offered. Otherwise the version the
     * user chose to skip stays hidden; the next newer version is offered again.
     */
    fun shouldOffer(info: UpdateInfo, installedCode: Long, skippedCode: Long): Boolean {
        if (!isNewer(info, installedCode)) return false
        if (info.required) return true
        return info.versionCode != skippedCode
    }

    /** "12.3 MB"-style size text. */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 10) "${Math.round(mb)} MB" else String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    // ---- small JSON helpers: missing keys and JSON null never throw ------------------------------------
    private fun JsonElement?.usable(): JsonElement? = if (this == null || this.isJsonNull) null else this

    private fun JsonObject.str(key: String): String {
        val e = get(key).usable() ?: return ""
        return if (e.isJsonPrimitive) e.asString else ""
    }

    private fun JsonObject.bool(key: String): Boolean {
        val e = get(key).usable() ?: return false
        return e.isJsonPrimitive && e.asJsonPrimitive.isBoolean && e.asBoolean
    }

    private fun JsonObject.long(key: String): Long {
        val e = get(key).usable() ?: return 0L
        return if (e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asLong else 0L
    }
}
