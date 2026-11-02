package com.pdfreader.app.ui.upload

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.pdfreader.app.R
import com.pdfreader.app.data.pdf.PdfPageEditor
import com.pdfreader.app.data.pdf.cleanBookName
import com.pdfreader.app.databinding.ActivityPdfEditBinding
import com.pdfreader.app.databinding.ItemEditPageBinding
import com.pdfreader.app.utils.PreferenceManager
import com.pdfreader.app.worker.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Preview a picked PDF before it goes to Drive: delete pages, drag to reorder, rename the book.
 * "Save & upload" writes the edited PDF and queues the upload; Back cancels (nothing is uploaded).
 *
 * Takes ownership of the staged file passed in [EXTRA_PATH]. Results: RESULT_OK = upload queued.
 */
class PdfEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        private const val THUMB_WIDTH = 360
        private const val PAYLOAD_NUMBER = "number"
    }

    private lateinit var binding: ActivityPdfEditBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var source: File
    private lateinit var initialName: String

    private var renderer: PageRenderer? = null
    private lateinit var adapter: PageAdapter

    /** Original (0-based) page indices in their CURRENT order — deleting/reordering only touches this list. */
    private val pages = mutableListOf<Int>()
    private var total = 0
    private var saving = false

    private val thumbCache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null || !File(path).exists()) { finish(); return }
        source = File(path)
        prefs = PreferenceManager(this)

        binding = ActivityPdfEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupInsets()

        binding.toolbar.title = "Preview & edit"
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!saving) confirmDiscard() }
        })

        initialName = cleanBookName(intent.getStringExtra(EXTRA_NAME).orEmpty())
        binding.etName.setText(initialName)
        binding.btnSave.setOnClickListener { saveAndUpload() }

        val r = try {
            PageRenderer(source)
        } catch (e: SecurityException) {
            showPreviewUnavailable("This PDF is password-protected."); return
        } catch (e: Exception) {
            showPreviewUnavailable("It couldn't be opened for preview (${e.message ?: "unknown error"})."); return
        }
        renderer = r
        total = r.pageCount
        if (total == 0) { showPreviewUnavailable("It has no pages."); return }
        pages.addAll(0 until total)

        adapter = PageAdapter()
        // Same page direction as the reader: Arabic books flow right-to-left
        binding.rvPages.layoutDirection =
            if (prefs.rtlPageOrder) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        binding.rvPages.layoutManager = GridLayoutManager(this, spanCount())
        binding.rvPages.adapter = adapter
        binding.rvPages.setHasFixedSize(true)
        touchHelper.attachToRecyclerView(binding.rvPages)
        updateInfo()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun spanCount() = (resources.configuration.screenWidthDp / 120).coerceIn(2, 6)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (binding.rvPages.layoutManager as? GridLayoutManager)?.spanCount = spanCount()
    }

    private fun setupInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        window.navigationBarColor = getColor(R.color.surface)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.appBar.updatePadding(top = bars.top)
            binding.bottomBar.updatePadding(bottom = bars.bottom + dp(12))
            insets
        }
    }

    // ── Editing ──────────────────────────────────────────────────────────────

    private fun hasPageChanges() = pages != (0 until total).toList()

    private fun currentName() = cleanBookName(binding.etName.text?.toString().orEmpty())

    private fun updateInfo() {
        val removed = total - pages.size
        binding.tvInfo.text =
            if (removed > 0) "${pages.size} of $total pages  ·  $removed removed" else "$total pages"
    }

    private fun deleteAt(pos: Int) {
        if (pos !in pages.indices) return
        if (pages.size <= 1) {
            Toast.makeText(this, "A book needs at least one page", Toast.LENGTH_SHORT).show()
            return
        }
        val orig = pages.removeAt(pos)
        adapter.notifyItemRemoved(pos)
        adapter.refreshNumbers()
        updateInfo()
        Snackbar.make(binding.root, "Page deleted", Snackbar.LENGTH_LONG)
            .setAnchorView(binding.bottomBar)
            .setAction("Undo") {
                val at = pos.coerceAtMost(pages.size)
                pages.add(at, orig)
                adapter.notifyItemInserted(at)
                adapter.refreshNumbers()
                updateInfo()
            }.show()
    }

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
    ) {
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = vh.bindingAdapterPosition
            val to   = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            pages.add(to, pages.removeAt(from))
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) vh?.itemView?.alpha = 0.8f
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.alpha = 1f
            adapter.refreshNumbers()    // page numbers follow the new order
        }
    })

    /** Large single-page preview, with a delete shortcut. */
    private fun showPagePreview(pos: Int) {
        val orig = pages.getOrNull(pos) ?: return
        val dm = resources.displayMetrics
        val iv = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            maxHeight = (dm.heightPixels * 0.62f).toInt()
        }
        val frame = FrameLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Page ${pos + 1} of ${pages.size}")
            .setView(frame)
            .setNegativeButton("Close", null)
            .setNeutralButton("🗑 Delete page") { _, _ ->
                pages.indexOf(orig).takeIf { it >= 0 }?.let { deleteAt(it) }
            }
            .show()
        lifecycleScope.launch {
            renderer?.render(orig, minOf(dm.widthPixels, 1400))?.let { iv.setImageBitmap(it) }
        }
    }

    // ── Save & upload / cancel ───────────────────────────────────────────────

    private fun setBusy(busy: Boolean, message: String = "") {
        saving = busy
        binding.tvBusy.text = message
        binding.layoutBusy.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun saveAndUpload() {
        if (saving) return
        val name   = currentName()
        val order  = pages.toList()
        val edited = hasPageChanges()
        setBusy(true, if (edited) "Saving your edits…" else "Preparing upload…")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (!edited) source
                    else File(source.parentFile, UUID.randomUUID().toString() + ".pdf").also {
                        PdfPageEditor.rewrite(applicationContext, source, order, it)
                    }
                }
            }
            result.fold(
                onSuccess = { file ->
                    if (file != source) source.delete()
                    UploadWorker.enqueue(applicationContext, file, name, prefs.currentUser)
                    setResult(RESULT_OK)
                    finish()
                },
                onFailure = { e ->
                    setBusy(false)
                    AlertDialog.Builder(this@PdfEditActivity)
                        .setTitle("Couldn't apply your edits")
                        .setMessage("${e.message ?: "Unknown error"}\n\nYou can upload the original PDF instead.")
                        .setPositiveButton("Upload original") { _, _ -> uploadOriginalAndFinish() }
                        .setNegativeButton("Back to editing", null)
                        .show()
                }
            )
        }
    }

    private fun uploadOriginalAndFinish() {
        UploadWorker.enqueue(applicationContext, source, currentName(), prefs.currentUser)
        setResult(RESULT_OK)
        finish()
    }

    private fun confirmDiscard() {
        if (!hasPageChanges() && currentName() == initialName) { discardAndFinish(); return }
        AlertDialog.Builder(this)
            .setTitle("Discard your edits?")
            .setMessage("The PDF won't be uploaded.")
            .setPositiveButton("Discard") { _, _ -> discardAndFinish() }
            .setNegativeButton("Keep editing", null)
            .show()
    }

    private fun discardAndFinish() {
        source.delete()
        setResult(RESULT_CANCELED)
        finish()
    }

    /** Password-protected / damaged PDFs can't be previewed — offer a plain upload instead. */
    private fun showPreviewUnavailable(reason: String) {
        AlertDialog.Builder(this)
            .setTitle("Can't preview this PDF")
            .setMessage("$reason\n\nYou can still upload it as it is.")
            .setCancelable(false)
            .setPositiveButton("Upload as is") { _, _ -> uploadOriginalAndFinish() }
            .setNegativeButton("Cancel") { _, _ -> discardAndFinish() }
            .show()
    }

    override fun onDestroy() {
        renderer?.close()
        renderer = null
        thumbCache.evictAll()
        super.onDestroy()
    }

    // ── Page grid ────────────────────────────────────────────────────────────

    private class VH(val b: ItemEditPageBinding) : RecyclerView.ViewHolder(b.root) {
        var job: Job? = null
        var orig = -1
    }

    private inner class PageAdapter : RecyclerView.Adapter<VH>() {

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vh = VH(ItemEditPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            vh.b.root.setOnClickListener {
                vh.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { showPagePreview(it) }
            }
            vh.b.root.setOnLongClickListener {
                touchHelper.startDrag(vh); true
            }
            vh.b.btnDelete.setOnClickListener {
                vh.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { deleteAt(it) }
            }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val orig = pages[position]
            h.orig = orig
            h.b.tvNumber.text = "${position + 1}"
            h.job?.cancel()
            val cached = thumbCache.get(orig)
            if (cached != null) {
                h.b.ivPage.setImageBitmap(cached)
            } else {
                h.b.ivPage.setImageDrawable(null)
                h.job = lifecycleScope.launch {
                    val bmp = renderer?.render(orig, THUMB_WIDTH) ?: return@launch
                    thumbCache.put(orig, bmp)
                    if (h.orig == orig) h.b.ivPage.setImageBitmap(bmp)
                }
            }
        }

        override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_NUMBER)) { h.b.tvNumber.text = "${position + 1}"; return }
            super.onBindViewHolder(h, position, payloads)
        }

        override fun onViewRecycled(h: VH) {
            h.job?.cancel(); h.job = null
            super.onViewRecycled(h)
        }

        fun refreshNumbers() = notifyItemRangeChanged(0, itemCount, PAYLOAD_NUMBER)
    }
}

/**
 * Thread-safe wrapper over [PdfRenderer] (which allows ONE open page at a time).
 * Rendering is serialised with a mutex; close() waits for any render in flight.
 */
private class PageRenderer(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close(); throw e
    }
    private val mutex = Mutex()
    private val closeScope = CoroutineScope(Dispatchers.IO)
    @Volatile private var closed = false

    val pageCount: Int = renderer.pageCount

    suspend fun render(index: Int, width: Int): Bitmap? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (closed) return@withContext null
            try {
                val page = renderer.openPage(index)
                try {
                    val h = (width.toFloat() / page.width * page.height).toInt().coerceIn(1, width * 4)
                    val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                } finally {
                    page.close()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun close() {
        closed = true
        closeScope.launch {
            mutex.withLock {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
    }
}
