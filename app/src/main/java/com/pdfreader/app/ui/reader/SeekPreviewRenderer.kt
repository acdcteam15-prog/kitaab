package com.pdfreader.app.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors

/**
 * Small page thumbnails for the seekbar preview. Renders on its own thread, keeps the
 * last ~40 thumbnails, and only renders the LATEST requested page (older requests are
 * dropped while the finger is moving fast).
 */
class SeekPreviewRenderer(private val pdf: File) {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cache = LruCache<Int, Bitmap>(40)
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var job: Job? = null

    fun request(page: Int, onReady: (Int, Bitmap) -> Unit) {
        cache.get(page)?.let { onReady(page, it); return }
        job?.cancel()
        job = scope.launch {
            delay(40)   // debounce while dragging
            val bmp = withContext(dispatcher) { render(page) } ?: return@launch
            cache.put(page, bmp)
            onReady(page, bmp)
        }
    }

    private fun render(page: Int): Bitmap? = try {
        val r = renderer ?: PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            .also { pfd = it }).also { renderer = it }
        if (page !in 0 until r.pageCount) null else {
            val p = r.openPage(page)
            val w = 216
            val h = (w.toFloat() / p.width * p.height).toInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close(); b
        }
    } catch (e: Exception) { null }

    fun release() {
        scope.cancel()
        executor.execute { runCatching { renderer?.close() }; runCatching { pfd?.close() } }
        executor.shutdown()
        cache.evictAll()
    }
}
