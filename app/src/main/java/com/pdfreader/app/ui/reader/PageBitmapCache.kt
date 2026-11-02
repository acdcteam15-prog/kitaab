package com.pdfreader.app.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import com.pdfreader.app.data.crop.PageCrop
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors

/**
 * Renders PDF pages to screen-shaped bitmaps for the page-curl, using Android's own
 * PdfRenderer on a private thread (independent from PDFView's pdfium instance).
 * Each bitmap is the full view area: the page is fit-centred on the reading-theme
 * background, exactly like PDFView draws it in book mode (FitPolicy.BOTH), so the
 * hand-off between curl and real page is seamless — including crop zoom, deskew rotation,
 * the folded-footnote curtain and the clean-up / dark-page colour filter.
 */
class PageBitmapCache(
    private val pdfFile: File,
    /** Colour around the page (theme background, or inverted paper colour). */
    private val backgroundColor: () -> Int,
    private val density: Float
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cache = LruCache<Int, Bitmap>(5)
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var targetW = 0
    private var targetH = 0
    private var viewW = 0f
    private var viewH = 0f

    /** Per-page look (crop zoom, fold line, skew). Called on the render thread; may analyse the page. */
    @Volatile var look: ((Int) -> PageCrop?)? = null
        set(v) { field = v; cache.evictAll() }

    /** Clean-up / dark-page filter drawn onto the page image (not onto the background). */
    @Volatile var filter: android.graphics.ColorFilter? = null
        set(v) { field = v; cache.evictAll() }

    /** Must be called once the reader view has its size. */
    fun setViewSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        // Cap resolution: memory stays ~6–8 MB per page; the curl is in motion anyway
        val scale = minOf(1f, 1200f / w)
        viewW = w.toFloat(); viewH = h.toFloat()
        val nw = (w * scale).toInt(); val nh = (h * scale).toInt()
        if (nw != targetW || nh != targetH) { targetW = nw; targetH = nh; cache.evictAll() }
    }

    fun get(page: Int): Bitmap? = cache.get(page)

    /** Drop all bitmaps (theme colour / crop / filter changed). */
    fun invalidate() = cache.evictAll()

    fun prefetch(page: Int, pageCount: Int) {
        if (targetW == 0) return
        scope.launch {
            for (p in listOf(page, page + 1, page - 1)) {
                if (p !in 0 until pageCount || cache.get(p) != null) continue
                render(p)?.let { cache.put(p, it) }
            }
        }
    }

    private suspend fun render(page: Int): Bitmap? = withContext(dispatcher) {
        try {
            val r = renderer ?: run {
                pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfRenderer(pfd!!).also { renderer = it }
            }
            if (page >= r.pageCount) return@withContext null
            val crop = look?.invoke(page)
            val p = r.openPage(page)
            val fit = minOf(targetW.toFloat() / p.width, targetH.toFloat() / p.height)
            val pw = (p.width * fit).toInt().coerceAtLeast(1)
            val ph = (p.height * fit).toInt().coerceAtLeast(1)

            val pageBmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            pageBmp.eraseColor(Color.WHITE)
            p.render(pageBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close()

            val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val bg = backgroundColor()
            c.drawColor(bg)
            val sc = targetW / viewW
            var zoom = 1f
            // Same transform the reader applies to the live view, expressed in bitmap coords:
            // first the view rotation (deskew), then the crop zoom
            if (crop != null && crop.skew != 0f) c.rotate(crop.skew, targetW / 2f, targetH / 2f)
            val box = crop?.box
            if (box != null) {
                val lay = CropDetector.layout(viewW, viewH, pw / sc, ph / sc, box)
                if (lay.zoom > 1.01f) {
                    zoom = lay.zoom
                    c.scale(lay.zoom, lay.zoom, lay.pivotX * sc, lay.pivotY * sc)
                }
            }
            val left = (targetW - pw) / 2f; val top = (targetH - ph) / 2f
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter }
            c.drawBitmap(pageBmp, Matrix().apply { postTranslate(left, top) }, paint)
            pageBmp.recycle()

            if (crop != null && crop.foldY > 0f && box != null) {
                FoldPainter.draw(c, left, top, pw.toFloat(), ph.toFloat(), crop.foldY,
                    (box[0] + box[2]) / 2f, bg, density * sc / zoom, { it }, null)
            }
            out
        } catch (e: Exception) {
            Log.e("PageBitmapCache", "render $page failed: ${e.message}")
            null
        }
    }

    fun release() {
        scope.cancel()
        executor.execute {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            renderer = null; pfd = null
        }
        executor.shutdown()
        cache.evictAll()
    }
}
