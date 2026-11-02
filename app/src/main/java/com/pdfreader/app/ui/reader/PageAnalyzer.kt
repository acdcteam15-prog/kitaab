package com.pdfreader.app.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * What the analyzer learned about one page. All numbers are fractions of the page (0..1).
 *  • [box]   printed area (despeckled, border shadows ignored); null for a blank page
 *  • [sepY]  bottom edge of the short footnote separator line; -1 when the page has none
 *  • [skew]  degrees CLOCKWISE the page must be turned to make its text lines level (0 = leave)
 */
class PageInfo(val box: FloatArray?, val sepY: Float, val skew: Float) {
    companion object { val EMPTY = PageInfo(null, -1f, 0f) }
}

/**
 * Looks at a page rendered at low resolution and finds its text block, the footnote
 * separator line and the scan skew. Results are cached per page, so page turns are instant
 * once the neighbouring pages have been looked at (the curl cache prefetches them).
 *
 * Thread-safe: it owns a private [PdfRenderer] guarded by a lock.
 */
class PageAnalyzer(private val pdfFile: File) {

    private val cache = ConcurrentHashMap<Int, PageInfo>()
    private val lock = Any()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    @Volatile private var closed = false

    fun peek(page: Int): PageInfo? = cache[page]

    fun clearCache() = cache.clear()

    suspend fun info(page: Int): PageInfo? =
        cache[page] ?: withContext(Dispatchers.Default) { infoBlocking(page) }

    /** Cached or computed right now on the calling thread (call from a background thread). */
    fun infoBlocking(page: Int): PageInfo? {
        cache[page]?.let { return it }
        return synchronized(lock) {
            val again = cache[page]
            if (again != null) again else computeLocked(page)?.also { cache[page] = it }
        }
    }

    fun pageCount(): Int = synchronized(lock) { openLocked()?.pageCount ?: 0 }

    private fun openLocked(): PdfRenderer? {
        if (closed) return null
        renderer?.let { return it }
        return try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(pfd!!).also { renderer = it }
        } catch (e: Exception) {
            Log.e(TAG, "open failed: ${e.message}"); null
        }
    }

    private fun computeLocked(page: Int): PageInfo? {
        val r = openLocked() ?: return null
        if (page !in 0 until r.pageCount) return null
        return try {
            val p = r.openPage(page)
            val w = ANALYSIS_WIDTH
            val h = (w.toFloat() * p.height / p.width).roundToInt().coerceIn(1, 1800)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close()
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            for (i in px.indices) {
                val c = px[i]
                px[i] = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
            }
            analyze(px, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "analyze $page failed: ${e.message}"); null
        }
    }

    /**
     * Renders the part [l,t,r,b] (page fractions) of a page so that it is [outW] pixels wide.
     * Used by the footnote sheet — sharp text, independent from what PDFView shows.
     */
    fun renderRegion(page: Int, l: Float, t: Float, r: Float, b: Float, outW: Int): Bitmap? =
        synchronized(lock) { renderRegionLocked(page, l, t, r, b, outW) }

    private fun renderRegionLocked(page: Int, l: Float, t: Float, r: Float, b: Float, outW: Int): Bitmap? {
        val rd = openLocked() ?: return null
        if (page !in 0 until rd.pageCount || r <= l || b <= t) return null
        return try {
            val p = rd.openPage(page)
            val pw = p.width.toFloat(); val ph = p.height.toFloat()
            val s = outW / ((r - l) * pw)
            val outH = (((b - t) * ph) * s).roundToInt().coerceIn(1, 4000)
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix().apply {
                setScale(s, s)
                postTranslate(-l * pw * s, -t * ph * s)
            }
            p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close()
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "renderRegion $page failed: ${e.message}"); null
        }
    }

    fun close() {
        closed = true
        synchronized(lock) {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            renderer = null; pfd = null
        }
        cache.clear()
    }

    companion object {
        private const val TAG = "PageAnalyzer"
        const val ANALYSIS_WIDTH = 480

        /** Otsu's method: the gray level that best separates ink from paper. */
        private fun otsu(hist: IntArray, total: Int): Int {
            var sumAll = 0L
            for (i in 0 until 256) sumAll += i.toLong() * hist[i]
            var sumB = 0L; var wB = 0L; var best = -1.0; var thr = 128
            for (t in 0 until 256) {
                wB += hist[t]
                if (wB == 0L) continue
                val wF = total - wB
                if (wF == 0L) break
                sumB += t.toLong() * hist[t]
                val mB = sumB.toDouble() / wB
                val mF = (sumAll - sumB).toDouble() / wF
                val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
                if (v > best) { best = v; thr = t }
            }
            return thr
        }

        /**
         * Pure analysis of a gray-level page image ([luma] 0..255, row-major, [w]×[h]).
         * Kept free of Android classes so the maths can be reasoned about (and tested) alone.
         */
        fun analyze(luma: IntArray, w: Int, h: Int): PageInfo {
            // 1. Ink threshold adapts to yellowed / grey scans
            val hist = IntArray(256)
            for (v in luma) hist[v.coerceIn(0, 255)]++
            val thr = otsu(hist, luma.size).coerceIn(100, 190)

            // 2. Despeckle: a dark pixel only counts when ≥2 of its 8 neighbours are dark too.
            //    Scanner borders (outermost ~1 %) are ignored.
            val bx = maxOf(2, (w * 0.012f).toInt()); val by = maxOf(2, (h * 0.008f).toInt())
            val ink = BooleanArray(w * h)
            for (y in by until h - by) {
                for (x in bx until w - bx) {
                    if (luma[y * w + x] >= thr) continue
                    var n = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        if ((dx != 0 || dy != 0) && luma[(y + dy) * w + x + dx] < thr) n++
                    }
                    if (n >= 2) ink[y * w + x] = true
                }
            }

            // 3. Dark scanner shadows along the page edges: tall/wide streaks near the border
            val colAll = IntArray(w); val rowAll = IntArray(h)
            for (y in 0 until h) for (x in 0 until w) if (ink[y * w + x]) { colAll[x]++; rowAll[y]++ }
            for (x in 0 until w) {
                if ((x < w * 0.10f || x >= w * 0.90f) && colAll[x] > 0.45f * h) {
                    for (y in 0 until h) ink[y * w + x] = false
                }
            }
            for (y in 0 until h) {
                if ((y < h * 0.08f || y >= h * 0.92f) && rowAll[y] > 0.60f * w) {
                    for (x in 0 until w) ink[y * w + x] = false
                }
            }

            // 4. Text block from projection profiles
            val rowSum = IntArray(h)
            for (y in 0 until h) { var c = 0; for (x in 0 until w) if (ink[y * w + x]) c++; rowSum[y] = c }
            val rowThr = maxOf(3, (w * 0.010f).toInt())
            var top = -1; var bottom = -1
            for (y in 0 until h) if (rowSum[y] >= rowThr) { if (top < 0) top = y; bottom = y }
            if (top < 0) return PageInfo.EMPTY
            val colSum = IntArray(w)
            for (y in top..bottom) for (x in 0 until w) if (ink[y * w + x]) colSum[x]++
            var left = -1; var right = -1
            for (x in 0 until w) if (colSum[x] >= 2) { if (left < 0) left = x; right = x }
            if (left < 0 || right <= left) return PageInfo.EMPTY
            val box = floatArrayOf(left / w.toFloat(), top / h.toFloat(),
                (right + 1) / w.toFloat(), (bottom + 1) / h.toFloat())

            // 5. Footnote separator: a short, thin horizontal rule with clear space above and
            //    below it, in the lower part of the text block, with text underneath.
            val boxW = right - left + 1
            val minRun = maxOf(8, (w * 0.07f).toInt()); val maxRun = (boxW * 0.66f).toInt()
            val runs = IntArray(h)
            for (y in top..bottom) {
                var best = 0; var cur = 0; var gap = 0
                for (x in left..right) {
                    if (ink[y * w + x]) { cur += gap + 1; gap = 0; if (cur > best) best = cur }
                    else { gap++; if (gap > 2) { cur = 0; gap = 0 } }
                }
                runs[y] = best
            }
            val quietMax = (w * 0.03f).toInt()
            fun lineRow(y: Int) = runs[y] in minRun..maxRun && rowSum[y] <= runs[y] * 1.8f + 2
            var sepTop = -1; var sepY = -1f
            var y = top
            while (y <= bottom) {
                if (lineRow(y)) {
                    val y0 = y; var y1 = y
                    while (y1 + 1 <= bottom && runs[y1 + 1] >= minRun * 0.8f &&
                        runs[y1 + 1] <= maxRun && rowSum[y1 + 1] <= runs[y1 + 1] * 1.8f + 2) y1++
                    val thick = y1 - y0 + 1
                    var above = 0; var below = 0
                    for (k in 0 until 3) {
                        val ya = y0 - 1 - k; val yb = y1 + 1 + k
                        if (ya < 0 || rowSum[ya] <= quietMax) above++
                        if (yb >= h || rowSum[yb] <= quietMax) below++
                    }
                    val centre = (y0 + y1) / 2f / h
                    if (thick <= 6 && above >= 2 && below >= 2 && centre > 0.30f &&
                        (y0 - top) > 0.25f * (bottom - top) && (bottom - y1) >= 0.035f * h) {
                        sepTop = y0; sepY = (y1 + 1) / h.toFloat()     // lowest qualifying line wins
                    }
                    y = y1 + 1
                } else y++
            }

            // 6. Skew: the angle at which text lines pile up into the sharpest row profile.
            val yEnd = if (sepTop > 0) sepTop else bottom
            var n = 0
            for (yy in top..yEnd) for (xx in left..right) if (ink[yy * w + xx]) n++
            var skew = 0f
            if (n >= 300) {
                val xs = IntArray(n); val ys = IntArray(n); var k = 0
                for (yy in top..yEnd) for (xx in left..right) if (ink[yy * w + xx]) { xs[k] = xx; ys[k] = yy; k++ }
                val cx = (left + right) / 2f
                val bins = IntArray(3 * h + 8)
                fun score(deg: Float): Double {
                    java.util.Arrays.fill(bins, 0)
                    val r = Math.toRadians(deg.toDouble()); val c = cos(r); val s = sin(r)
                    for (i in 0 until n) {
                        val v = (ys[i] * c + (xs[i] - cx) * s + h).roundToInt()
                        if (v in bins.indices) bins[v]++
                    }
                    var sum = 0.0
                    for (b in bins) sum += b.toDouble() * b
                    return sum
                }
                val base = score(0f)
                var best = 0f; var bs = base
                var d = -3f
                while (d <= 3.001f) { val sc = score(d); if (sc > bs) { bs = sc; best = d }; d += 0.25f }
                d = best - 0.25f
                val stop = best + 0.2501f
                while (d <= stop) { val sc = score(d); if (sc > bs) { bs = sc; best = d }; d += 0.05f }
                if (bs > base * 1.02 && abs(best) >= 0.3f) skew = best.coerceIn(-3f, 3f)
            }
            return PageInfo(box, sepY, skew)
        }
    }
}
