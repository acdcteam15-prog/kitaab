package com.pdfreader.app.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors

/**
 * Real-book page fold for VERTICAL reading.
 *
 * It never touches PDFView's gestures (the reason the earlier custom animation broke
 * horizontal mode). Instead it draws two non-clickable ImageViews ABOVE the PDFView:
 *
 *   forward (swipe up)  : the old page is the "flap" — it lifts from the bottom edge and
 *                         folds up over the top edge, revealing the new page beneath.
 *   backward (swipe down): the old page stays as a static "base", the new page folds
 *                         down from the top edge onto it.
 *
 * Page bitmaps are rendered by Android's own PdfRenderer on a private single thread
 * (independent of PDFView's pdfium) and cached for the current page ±1, so a turn
 * starts instantly.
 */
class PageTurnAnimator(
    private val pdfFile: File,
    private val base: ImageView,
    private val flap: ImageView,
    private val shade: View,
    private val backgroundColor: () -> Int
) {
    private val executor     = Executors.newSingleThreadExecutor()
    private val renderThread = executor.asCoroutineDispatcher()
    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cache  = object : LruCache<Int, Bitmap>(4) {}
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var animating = false
    var enabled = true

    init {
        flap.cameraDistance = 12_000f * flap.resources.displayMetrics.density
        listOf(base, flap, shade).forEach { it.isClickable = false; it.isFocusable = false; it.visibility = View.GONE }
    }

    /** Warm the cache around [page]. Call after load and after every page change. */
    fun prefetch(page: Int, pageCount: Int) {
        if (!enabled) return
        scope.launch {
            for (p in listOf(page, page + 1, page - 1)) {
                if (p !in 0 until pageCount || cache.get(p) != null) continue
                render(p)?.let { cache.put(p, it) }
            }
        }
    }

    /**
     * Play the fold for a single-step page change. Multi-page jumps (seekbar, bookmarks,
     * go-to-page) are not animated — a real book wouldn't fold 40 pages at once.
     */
    fun onPageChanged(oldPage: Int, newPage: Int, pageCount: Int, zoom: Float) {
        if (!enabled || animating || zoom > 1.05f || oldPage < 0 || kotlin.math.abs(newPage - oldPage) != 1) {
            prefetch(newPage, pageCount); return
        }
        val oldBmp = cache.get(oldPage)
        val newBmp = cache.get(newPage)
        if (oldBmp == null || (newPage < oldPage && newBmp == null)) {
            prefetch(newPage, pageCount); return
        }
        if (newPage > oldPage) turnForward(oldBmp) else turnBackward(oldBmp, newBmp!!)
        prefetch(newPage, pageCount)
    }

    private fun turnForward(oldBmp: Bitmap) {
        animating = true
        prepare(flap, oldBmp)
        flap.pivotX = flap.width / 2f
        flap.pivotY = 0f
        flap.rotationX = 0f
        shade.alpha = 0.45f
        shade.visibility = View.VISIBLE
        flap.visibility  = View.VISIBLE

        shade.animate().alpha(0f).setDuration(DURATION).start()
        flap.animate()
            .rotationX(-90f)
            .setDuration(DURATION)
            .setInterpolator(AccelerateInterpolator(1.2f))
            .withEndAction { finish() }
            .start()
    }

    private fun turnBackward(oldBmp: Bitmap, newBmp: Bitmap) {
        animating = true
        prepare(base, oldBmp)
        prepare(flap, newBmp)
        flap.pivotX = flap.width / 2f
        flap.pivotY = 0f
        flap.rotationX = -90f
        shade.alpha = 0f
        base.visibility  = View.VISIBLE
        shade.visibility = View.VISIBLE
        flap.visibility  = View.VISIBLE

        shade.animate().alpha(0.45f).setDuration(DURATION).start()
        flap.animate()
            .rotationX(0f)
            .setDuration(DURATION)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction { finish() }
            .start()
    }

    private fun prepare(v: ImageView, bmp: Bitmap) {
        v.setImageBitmap(bmp)
        v.setBackgroundColor(backgroundColor())
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        v.animate().cancel()
    }

    private fun finish() {
        flap.visibility  = View.GONE
        base.visibility  = View.GONE
        shade.visibility = View.GONE
        flap.rotationX   = 0f
        flap.setImageDrawable(null)
        base.setImageDrawable(null)
        animating = false
    }

    private suspend fun render(page: Int): Bitmap? = withContext(renderThread) {
        try {
            val r = renderer ?: run {
                pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfRenderer(pfd!!).also { renderer = it }
            }
            if (page >= r.pageCount) return@withContext null
            val p = r.openPage(page)
            val targetW = flap.resources.displayMetrics.widthPixels.coerceAtMost(1400)
            val targetH = (targetW.toFloat() / p.width * p.height).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close()
            bmp
        } catch (e: Exception) {
            Log.e("PageTurnAnimator", "render $page failed: ${e.message}")
            null
        }
    }

    fun release() {
        scope.cancel()
        flap.animate().cancel()
        // Close on the render thread so an in-flight render can't touch a closed renderer
        executor.execute {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            renderer = null; pfd = null
        }
        executor.shutdown()
        cache.evictAll()
    }

    companion object { private const val DURATION = 380L }
}
