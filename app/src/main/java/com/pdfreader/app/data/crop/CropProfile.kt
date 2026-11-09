package com.pdfreader.app.data.crop

import com.pdfreader.app.ui.reader.PageAnalyzer
import com.pdfreader.app.ui.reader.PageInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** What the reader needs to show one page: zoom box, footnote fold line, deskew angle. */
class PageCrop(
    /** Area to zoom onto [l,t,r,b] (page fractions); null = don't zoom this page. */
    val box: FloatArray?,
    /** Page fraction where the footnote curtain starts; -1 = nothing folded on this page. */
    val foldY: Float,
    /** Degrees clockwise to level the text; 0 = leave as is. */
    val skew: Float,
    /** Region covered by the curtain [l,t,r,b] for the footnote sheet; null when nothing is folded. */
    val footRect: FloatArray?
)

/**
 * Everything the reader remembers about HOW to show one book: the text box for odd and even
 * pages (they often differ — binding side), footnote-fold settings and manual fixes, and the
 * scan clean-up options. Stored on the phone and synced through Drive ([CropProfileStore]).
 */
class CropProfile(val bookId: String) {

    /** Hide footnotes below the separator line behind a pull-up handle. */
    @Volatile var fold = true
    /** Text box [l,t,r,b] for odd page numbers (page index 0, 2, 4 …) / even page numbers. */
    @Volatile var odd: FloatArray? = null
    @Volatile var even: FloatArray? = null
    /** "Same line on every page" set by hand; beats auto-detection. -1 = unused. */
    @Volatile var fixedFold = -1f
    /** Manual fix per page index: fold line (page fraction) or [NO_FOOTNOTES]. Beats everything. */
    val overrides = ConcurrentHashMap<Int, Float>()
    /** 0 = off, 1 = light, 2 = strong levels clean-up. */
    @Volatile var cleanLevel = 0
    @Volatile var deskew = false
    @Volatile var updatedAt = 0L

    val hasBoxes get() = odd != null || even != null

    fun boxFor(page: Int): FloatArray? = if (page % 2 == 0) (odd ?: even) else (even ?: odd)

    /** Resolve the look of [page]. [info] may be null while analysis is still pending. */
    fun resolve(page: Int, info: PageInfo?, cropEnabled: Boolean): PageCrop {
        val skew = if (deskew) info?.skew ?: 0f else 0f
        val base = boxFor(page)
        if (!cropEnabled || base == null) return PageCrop(null, -1f, skew, null)

        val auto = info?.sepY?.takeIf { it > 0f }?.let { it + 0.004f } ?: -1f
        var fy = when {
            !fold -> -1f
            overrides.containsKey(page) -> overrides[page] ?: -1f
            fixedFold > 0f -> fixedFold
            else -> auto
        }
        // A fold line outside the text block makes no sense — treat as "no footnotes"
        if (fy > 0f) {
            fy = fy.coerceIn(base[1] + 0.10f, 0.995f)
            if (fy >= base[3] - 0.01f) fy = -1f
        }
        val bottom = if (fy > 0f) fy else base[3]
        val box = floatArrayOf(base[0], base[1], base[2], bottom)
        val foot: FloatArray? = if (fy > 0f) {
            val fb = ((info?.box?.get(3) ?: 1f) + 0.01f).coerceAtMost(1f)
            if (fb - fy > 0.012f) floatArrayOf(base[0], fy, base[2], fb) else null
        } else null
        return PageCrop(box, if (foot != null) fy else -1f, skew, foot)
    }

    /**
     * Fold line for [page] when crop is OFF (any reading mode): only lines set by hand count.
     * -1 = nothing folded on this page.
     */
    fun manualFold(page: Int): Float = when {
        !fold -> -1f
        overrides.containsKey(page) -> (overrides[page] ?: -1f).takeIf { it > 0f } ?: -1f
        fixedFold > 0f -> fixedFold
        else -> -1f
    }

    fun clearManual() { overrides.clear(); fixedFold = -1f }

    fun toJson(): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("fold", fold)
        odd?.let { o.put("odd", JSONArray(it.toList())) }
        even?.let { o.put("even", JSONArray(it.toList())) }
        o.put("fixedFold", fixedFold.toDouble())
        val ov = JSONObject()
        overrides.forEach { (k, v) -> ov.put(k.toString(), v.toDouble()) }
        o.put("overrides", ov)
        o.put("clean", cleanLevel)
        o.put("deskew", deskew)
        o.put("updatedAt", updatedAt)
        return o.toString()
    }

    companion object {
        const val NO_FOOTNOTES = -1f

        fun fromJson(bookId: String, json: String): CropProfile? = try {
            val o = JSONObject(json)
            CropProfile(bookId).apply {
                fold = o.optBoolean("fold", true)
                odd = o.optJSONArray("odd")?.toBox()
                even = o.optJSONArray("even")?.toBox()
                fixedFold = o.optDouble("fixedFold", -1.0).toFloat()
                o.optJSONObject("overrides")?.let { ov ->
                    ov.keys().forEach { k -> k.toIntOrNull()?.let { overrides[it] = ov.optDouble(k, -1.0).toFloat() } }
                }
                cleanLevel = o.optInt("clean", 0).coerceIn(0, 2)
                deskew = o.optBoolean("deskew", false)
                updatedAt = o.optLong("updatedAt", 0L)
            }
        } catch (e: Exception) { null }

        private fun JSONArray.toBox(): FloatArray? =
            if (length() == 4) FloatArray(4) { optDouble(it).toFloat() } else null
    }
}

/** Works out the odd/even text boxes of a whole book from a handful of sample pages. */
object CropProfileBuilder {

    /** Returns (oddBox, evenBox); a side with no usable sample page is null. */
    suspend fun build(analyzer: PageAnalyzer): Pair<FloatArray?, FloatArray?>? {
        val n = analyzer.pageCount()
        if (n == 0) return null
        // Skip covers / title pages and the back matter: sample the body, 16 pages
        val samples = (0 until 16).map { (n * (0.08 + 0.05 * it)).toInt() }
            .filter { it in 0 until n }.distinct().ifEmpty { listOf(0) }
        val u = arrayOf(floatArrayOf(1f, 1f, 0f, 0f), floatArrayOf(1f, 1f, 0f, 0f))
        val seen = booleanArrayOf(false, false)
        for (p in samples) {
            val b = analyzer.info(p)?.box ?: continue
            val side = if (p % 2 == 0) 0 else 1
            seen[side] = true
            u[side][0] = minOf(u[side][0], b[0]); u[side][1] = minOf(u[side][1], b[1])
            u[side][2] = maxOf(u[side][2], b[2]); u[side][3] = maxOf(u[side][3], b[3])
        }
        if (!seen[0] && !seen[1]) return null
        val pad = 0.015f
        fun padded(b: FloatArray) = floatArrayOf(
            (b[0] - pad).coerceAtLeast(0f), (b[1] - pad).coerceAtLeast(0f),
            (b[2] + pad).coerceAtMost(1f), (b[3] + pad).coerceAtMost(1f))
        return Pair(if (seen[0]) padded(u[0]) else null, if (seen[1]) padded(u[1]) else null)
    }
}
