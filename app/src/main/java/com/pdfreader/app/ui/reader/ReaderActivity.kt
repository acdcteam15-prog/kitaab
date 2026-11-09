package com.pdfreader.app.ui.reader

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.barteksc.pdfviewer.listener.OnDrawListener
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener
import com.pdfreader.app.data.model.Highlight
import com.github.barteksc.pdfviewer.listener.OnLongPressListener
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.github.barteksc.pdfviewer.listener.OnRenderListener
import com.github.barteksc.pdfviewer.listener.OnTapListener
import com.github.barteksc.pdfviewer.listener.OnErrorListener
import com.github.barteksc.pdfviewer.listener.OnPageErrorListener
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import com.github.barteksc.pdfviewer.util.FitPolicy
import com.google.android.material.snackbar.Snackbar
import com.pdfreader.app.R
import com.pdfreader.app.data.crop.CropProfile
import com.pdfreader.app.data.crop.CropProfileBuilder
import com.pdfreader.app.data.crop.CropProfileStore
import com.pdfreader.app.data.crop.PageCrop
import com.pdfreader.app.data.model.ReadingTheme
import com.pdfreader.app.data.repository.DriveRepository
import com.pdfreader.app.data.repository.ProgressRepository
import com.pdfreader.app.databinding.ActivityReaderBinding
import com.pdfreader.app.utils.PreferenceManager
import com.pdfreader.app.utils.WakeLockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class ReaderActivity : com.pdfreader.app.ui.base.BaseActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var prefs: PreferenceManager
    private val extras by lazy { com.pdfreader.app.utils.ReaderPrefs(this) }
    private lateinit var progressRepo: ProgressRepository
    private lateinit var wakeLock: WakeLockManager
    private lateinit var bookmarkAdapter: BookmarkAdapter
    private var pageCache: PageBitmapCache? = null

    // Scan tools: per-book profile (synced through Drive), page analysis, colour filter
    @Volatile private var analyzer: PageAnalyzer? = null
    @Volatile private var profile: CropProfile? = null
    private val profileStore by lazy { CropProfileStore(applicationContext) }
    @Volatile private var cropOn = false
    private var curSpec = ScanFilter.Spec(false, 0, android.graphics.Color.WHITE)

    private var bookId           = ""
    private var bookName         = ""
    private var ownerName        = ""
    private var localPath        = ""
    private var currentPage      = 0
    private var totalPages       = 0
    private var isUiVisible      = true
    private var isRotationLocked = false
    private var isFinished       = false
    private var endPromptShown   = false

    // Time tracking: each checkpoint stores only the time since the previous checkpoint,
    // so saving on every pause never double-counts and background time is never counted.
    private var sessionStartMs    = 0L
    private var lastCheckpointMs  = 0L
    private var allTimeMsAtOpen   = 0L
    private var checkpointJob: Job? = null

    // Use application-level scope so Drive push survives activity destruction
    private val ioScope get() = (application as? com.pdfreader.app.KitaabApplication)?.appScope
        ?: CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Sleep timer
    private var sleepTimer: CountDownTimer? = null
    private var sleepTimerMinutes = 0

    // Position pin
    private var pinPage   = -1
    private var pinYRatio = -1f

    // Seekbar: jump only on release (dragging re-rendered every page → stutter)
    private var isUpdatingSeekbar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: the page uses the whole screen; bars float over it
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        // Keep the window the same size when the bars hide: draw under the camera cut-out as well
        // (otherwise Android letterboxes the page below the notch and it jumps)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                if (android.os.Build.VERSION.SDK_INT >= 30) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bookId      = intent.getStringExtra("BOOK_ID")    ?: ""
        bookName    = intent.getStringExtra("BOOK_NAME")  ?: ""
        ownerName   = intent.getStringExtra("OWNER")      ?: ""
        localPath   = intent.getStringExtra("LOCAL_PATH") ?: ""
        currentPage = savedInstanceState?.getInt(STATE_PAGE) ?: intent.getIntExtra("START_PAGE", 0)
        totalPages  = intent.getIntExtra("TOTAL_PAGES", 0)
        sessionStartMs   = System.currentTimeMillis()
        lastCheckpointMs = sessionStartMs

        prefs            = PreferenceManager(this)
        progressRepo     = ProgressRepository(this)
        wakeLock         = WakeLockManager(this)
        isRotationLocked = prefs.lockRotation
        profile          = profileStore.load(bookId) ?: CropProfile(bookId)
        timeGoalToastShown = extras.dailyMinuteGoal > 0 && extras.msToday >= extras.dailyMinuteGoal * 60_000L

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply { title = com.pdfreader.app.data.model.prettyTitle(bookName); setDisplayHomeAsUpEnabled(true) }

        applySettings()
        applyReadingTheme()
        applyPinStyle()
        setupPdfViewer()
        setupBottomBar()
        setupBookmarkPanel()
        setupBrightness()
        setupImmersive()
        setupHighlights()
        setupFootnoteEditor()
        loadSavedState()
        observeOtherDevice(savedInstanceState == null)
    }

    // ── Other device moved ahead (#12) ────────────────────────────────────────

    /**
     * Never silently move the reader. If the other device is on a different page — at open
     * (REMOTE_PAGE from Home) or while reading (live pull) — offer a one-tap jump instead.
     */
    private fun observeOtherDevice(isFreshOpen: Boolean) {
        val remoteAtOpen = intent.getIntExtra("REMOTE_PAGE", -1)
        if (isFreshOpen && remoteAtOpen >= 0 && remoteAtOpen != currentPage) {
            binding.root.postDelayed({ offerJump(remoteAtOpen) }, 700)
        }
        lifecycleScope.launch {
            progressRepo.remotePosition.collect { rp ->
                if (rp.bookId == bookId && rp.newPage != currentPage) offerJump(rp.newPage)
            }
        }
    }

    private fun offerJump(page: Int) {
        Snackbar.make(binding.root, "📱 Your other device is on page ${page + 1}", 8_000)
            .setAction("Jump") { goTo(page, true) }
            .setActionTextColor(themeColor(R.attr.kAccent))
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, currentPage)
    }

    /** Pin + reading time + finished flag. Redraws once loaded (fixes the invisible-pin race). */
    private fun loadSavedState() {
        lifecycleScope.launch {
            val saved = progressRepo.getProgressLocal(bookId, ownerName)
            pinPage         = saved?.positionPinPage   ?: -1
            pinYRatio       = saved?.positionPinYRatio ?: -1f
            allTimeMsAtOpen = saved?.totalReadingTimeMs ?: 0L
            isFinished      = saved?.isFinished ?: false
            drawPinOverlay()
            invalidateOptionsMenu()
        }
    }

    private fun applySettings() {
        wakeLock.applyKeepScreenOn(prefs.keepScreenOn)
        applyRotationLock()
    }

    private fun applyRotationLock() {
        requestedOrientation = if (isRotationLocked)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR
        invalidateOptionsMenu()
    }

    private fun currentTheme(): ReadingTheme =
        try { ReadingTheme.valueOf(prefs.readingTheme) } catch (e: Exception) { ReadingTheme.WHITE }

    private fun applyReadingTheme() {
        val theme = currentTheme()
        applyScanFilter()          // page background / dark-page filter follow the theme
        pageCache?.invalidate()
        if (totalPages > 0) pageCache?.prefetch(currentPage, totalPages)
        if (theme == ReadingTheme.NO_BLUE || theme == ReadingTheme.GREEN) {
            binding.blueFilterOverlay.visibility = View.VISIBLE
            binding.blueFilterOverlay.setBackgroundColor(theme.nightFilterColor)
        } else {
            binding.blueFilterOverlay.visibility = View.GONE
        }
    }

    // ── Right-to-left page order (#RTL) ───────────────────────────────────────
    /**
     * For Arabic books in horizontal mode the pages are loaded in reverse, so the next page
     * is to the LEFT and a swipe → turns forward, like a printed Arabic book. PDFView then
     * works in "display" indexes; everything else in the app (saved page, pin, bookmarks,
     * sync) keeps using the real page index. The count is read BEFORE load so the mapping
     * is valid for the very first onPageChange (see pitfall: rtlTotalPages).
     */
    private var reversed = false
    private var rtlCount = 0

    private fun toDisplayPage(realPage: Int): Int = if (reversed) rtlCount - 1 - realPage else realPage
    private fun toRealPage(displayPage: Int): Int = if (reversed) rtlCount - 1 - displayPage else displayPage

    /** Jump to a REAL page index. All navigation goes through here. */
    private fun goTo(realPage: Int, animate: Boolean) {
        val last = maxOf(totalPages, rtlCount) - 1
        val page = if (last >= 0) realPage.coerceIn(0, last) else realPage
        binding.pdfView.jumpTo(toDisplayPage(page), animate)
    }

    private fun countPages(file: File): Int = try {
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { it.pageCount }
        }
    } catch (e: Exception) { 0 }

    private val isHorizontal get() = prefs.pageScrollMode == "HORIZONTAL"
    /** Book mode: one page per screen, turned with a real page curl. */
    private val isBookMode get() = prefs.pageTurnAnimation

    private fun setupPdfViewer() {
        val file = File(localPath)
        if (!file.exists()) {
            Toast.makeText(this, "Book file not found", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val horizontal = isHorizontal
        val bookMode   = isBookMode
        val paged      = horizontal || bookMode

        if (horizontal && prefs.rtlPageOrder) {
            rtlCount = countPages(file)
            reversed = rtlCount > 1
        }
        binding.seekbarPages.layoutDirection =
            if (prefs.rtlPageOrder) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        analyzer = PageAnalyzer(file)      // also used outside book mode (footnote line)
        if (bookMode) {
            pageCache = PageBitmapCache(file, { surroundColor() }, resources.displayMetrics.density).also { pc ->
                pc.filter = ScanFilter.colorFilter(curSpec)
                pc.look = { page -> lookFor(page) }
            }
            setupCurl(horizontal)
        }

        val config = binding.pdfView.fromFile(file)
        if (reversed) config.pages(*IntArray(rtlCount) { rtlCount - 1 - it })
        config
            .defaultPage(toDisplayPage(currentPage.coerceIn(0, maxOf(0, (if (reversed) rtlCount else Int.MAX_VALUE) - 1))))
            .enableSwipe(true)
            .swipeHorizontal(horizontal)
            .pageSnap(paged)
            .pageFling(paged && !bookMode)   // book mode: turns only via the curl (no hair-trigger flings)
            .autoSpacing(paged)
            .fitEachPage(paged)
            .pageFitPolicy(if (bookMode) FitPolicy.BOTH else FitPolicy.WIDTH)
            .enableDoubletap(true)
            .enableAnnotationRendering(true)
            .scrollHandle(if (paged) null else DefaultScrollHandle(this))
            .spacing(if (paged) 0 else 8)
            .onLoad(OnLoadCompleteListener { pages ->
                totalPages = pages
                updateProgressUi()
                drawPinOverlay()
                restoreZoom()
                pageCache?.setViewSize(binding.curlLayout.width, binding.curlLayout.height)
                pageCache?.prefetch(currentPage, pages)
                if (bookMode) initScanTools() else initPlainTools()
            })
            .onRender(OnRenderListener { _ -> restoreZoom() })
            .onPageChange(OnPageChangeListener { displayPage, count ->
                val page = toRealPage(displayPage)
                // Daily goal: count each page turned forward (jumps don't count)
                if (!applyingCrop && page == currentPage + 1) prefs.addPagesToday(1)
                currentPage = page
                totalPages  = count
                if (applyingCrop) return@OnPageChangeListener
                if (cropOn || profile?.deskew == true) binding.pdfView.post { refreshPageLook() }
                if (!isBookMode) analyzeAround(page)
                pageCache?.prefetch(page, count)
                updateProgressUi()
                updateBookmarkIcon()
                drawPinOverlay(if (pendingPinPulse && page == pinPage) "pulse" else "appear")
                if (page == pinPage) pendingPinPulse = false
                maybePromptFinished()
            })
            .onError(OnErrorListener { t -> showOpenError(t) })
            .onPageError(OnPageErrorListener { page, t ->
                android.util.Log.e("Reader", "Page ${page + 1} could not be drawn: ${t.message}")
                // Several pages failing means the file itself is the problem, not one odd page
                if (++pageErrors >= 3) showOpenError(t)
            })
            .onTap(OnTapListener { e -> handleTap(e); true })
            .onDrawAll(OnDrawListener { canvas, pw, ph, displayPage -> drawHighlights(canvas, pw, ph, displayPage) })
            .onLongPress(OnLongPressListener { event ->
                // Long-press on the footer (the folded area) → move its line, in any reading mode
                if (footerHit(event.x, event.y)) {
                    binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    startFootnoteEdit()
                    return@OnLongPressListener
                }
                val yRatio = localToScreenY(event.x, event.y) / binding.pdfView.height.toFloat()
                setPinAt(currentPage, yRatio.coerceIn(0f, 1f))
            })
            .load()
    }

    // ── Page curl (book mode) ─────────────────────────────────────────────────

    private var pageErrors = 0
    private var openErrorShown = false

    /** The viewer could not read the book: say so instead of leaving a screen of blank pages. */
    private fun showOpenError(t: Throwable) {
        android.util.Log.e("Reader", "Book could not be displayed: ${t.javaClass.simpleName}: ${t.message}")
        if (openErrorShown || isFinishing) return
        openErrorShown = true
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("This book can't be displayed")
            .setMessage("The file on this phone may be damaged or incomplete.\n\n" +
                "Go back, long-press the book in your library, choose \"Delete local copy\", then tap the book to download it again.\n\n" +
                "(${t.javaClass.simpleName}: ${t.message})")
            .setPositiveButton("Back to library") { _, _ -> finish() }
            .setNegativeButton("Stay", null)
            .setCancelable(true)
            .show()
    }

    private fun setupCurl(horizontal: Boolean) {
        binding.curlLayout.vertical = !horizontal
        binding.curlLayout.rtl      = prefs.rtlPageOrder
        binding.curlLayout.host = object : PageCurlLayout.Host {
            override fun canCurl() = totalPages > 0 && !highlightMode && binding.pdfView.zoom <= baseZoom + 0.05f &&
                    binding.panelBookmarks.visibility != View.VISIBLE
            override fun currentPage() = currentPage
            override fun pageCount()   = totalPages
            override fun pageBitmap(page: Int) = pageCache?.get(page)
            override fun paperColor()  = surroundColor()
            override fun onCurlStart() {
                binding.pinMarker.hideNow()
            }
            override fun onTurnCommitted(newPage: Int) {
                goTo(newPage, false)
                // Let PDFView draw the new page before the curl layer disappears
                binding.pdfView.postDelayed({ binding.curlLayout.finishCurl(); drawPinOverlay() }, 90)
            }
            override fun onCurlCancelled() {
                goTo(currentPage, false)   // undo any few-pixel drag PDFView saw
                if (cropOn || profile?.deskew == true) refreshPageLook()
                drawPinOverlay()
            }
        }
    }

    /** Book mode: tapping the outer edges turns the page (with the curl); centre toggles the bars. */
    private fun handleTap(e: MotionEvent?) {
        // Tapping a highlight opens it (note, colour, delete)
        if (e != null && !highlightMode) {
            hitHighlight(e.x, e.y)?.let { editHighlight(it); return }
        }
        // Tapping the "▲ Footnotes" handle opens the folded footnotes of this page
        if (e != null && !highlightMode && !foldHandle.isEmpty) {
            val pad = 12f * resources.displayMetrics.density
            if (e.x >= foldHandle.left - pad && e.x <= foldHandle.right + pad &&
                e.y >= foldHandle.top - pad && e.y <= foldHandle.bottom + pad) {
                showFootnotes(); return
            }
        }
        if (e != null && isBookMode && binding.pdfView.zoom <= baseZoom + 0.05f) {
            val w = binding.pdfView.width.toFloat(); val h = binding.pdfView.height.toFloat()
            val fwd: Boolean? = if (isHorizontal) {
                val left = e.x < w * 0.10f; val right = e.x > w * 0.90f
                when {
                    left  -> prefs.rtlPageOrder      // RTL: left edge = next page
                    right -> !prefs.rtlPageOrder
                    else  -> null
                }
            } else {
                when {
                    e.y > h * 0.90f -> true
                    e.y < h * 0.10f -> false
                    else            -> null
                }
            }
            if (fwd != null && binding.curlLayout.animateTurn(fwd)) return
        }
        toggleUi()
    }

    // ── Highlights & notes (UI 4) ─────────────────────────────────────────────

    private var highlights: List<Highlight> = emptyList()
    private var highlightMode = false
    private var highlightColor = Highlight.COLORS[0]
    /** Where each page was last drawn (PDFView coordinates) + when — for hit-testing. */
    private val pageRects = HashMap<Int, android.graphics.RectF>()
    private val pageRectTime = HashMap<Int, Long>()
    private val hlPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val hlRect = android.graphics.RectF()
    private val mVals = FloatArray(9)

    private fun setupHighlights() {
        lifecycleScope.launch {
            progressRepo.highlightsFlow(bookId, ownerName).collectLatest {
                highlights = it
                binding.pdfView.invalidate()
            }
        }
        binding.highlightOverlay.color = highlightColor
        binding.highlightOverlay.onRectDrawn = { r -> createHighlight(r) }
        val d = resources.displayMetrics.density
        Highlight.COLORS.forEach { c ->
            val dot = View(this).apply {
                tag = c
                layoutParams = android.widget.LinearLayout.LayoutParams((30 * d).toInt(), (30 * d).toInt())
                    .apply { marginEnd = (8 * d).toInt() }
                setOnClickListener {
                    highlightColor = c
                    binding.highlightOverlay.color = c
                    refreshColorDots()
                }
            }
            // insert before the "Done" button
            binding.highlightColors.addView(dot, binding.highlightColors.childCount - 1)
        }
        refreshColorDots()
        binding.tvHighlightDone.setOnClickListener { setHighlightMode(false) }
        onBackPressedDispatcher.addCallback(this, exitHighlightOnBack)
    }

    /** Back leaves highlighter mode instead of closing the book. */
    private val exitHighlightOnBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setHighlightMode(false)
    }

    private fun refreshColorDots() {
        val d = resources.displayMetrics.density
        for (i in 0 until binding.highlightColors.childCount) {
            val v = binding.highlightColors.getChildAt(i)
            val c = v.tag as? Int ?: continue
            v.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(c)
                if (c == highlightColor) setStroke((3 * d).toInt(), android.graphics.Color.WHITE)
            }
            v.animate().scaleX(if (c == highlightColor) 1.15f else 1f)
                .scaleY(if (c == highlightColor) 1.15f else 1f).setDuration(120).start()
        }
    }

    private fun setHighlightMode(on: Boolean) {
        highlightMode = on
        exitHighlightOnBack.isEnabled = on
        // Highlighter maths assumes an upright page: straighten while highlighting
        if (on) { binding.pdfView.animate().cancel(); binding.pdfView.rotation = 0f }
        else refreshPageLook()
        val bar = binding.highlightBar
        if (on) {
            if (isUiVisible) toggleUi()
            binding.highlightOverlay.visibility = View.VISIBLE
            bar.visibility = View.VISIBLE
            bar.alpha = 0f; bar.translationY = -40f
            bar.animate().alpha(1f).translationY(0f).setDuration(220).start()
        } else {
            binding.highlightOverlay.visibility = View.GONE
            bar.animate().alpha(0f).translationY(-40f).setDuration(180)
                .withEndAction { bar.visibility = View.GONE }.start()
        }
    }

    /** Called by PDFView for every visible page, with the canvas at the page's top-left. */
    private fun drawHighlights(canvas: android.graphics.Canvas, pw: Float, ph: Float, displayPage: Int) {
        val real = toRealPage(displayPage)
        @Suppress("DEPRECATION")
        canvas.matrix.getValues(mVals)
        val l = mVals[android.graphics.Matrix.MTRANS_X]; val t = mVals[android.graphics.Matrix.MTRANS_Y]
        pageRects[real] = android.graphics.RectF(l, t, l + pw, t + ph)
        pageRectTime[real] = android.os.SystemClock.uptimeMillis()

        val d = resources.displayMetrics.density
        for (h in highlights) {
            if (h.page != real) continue
            hlRect.set(h.left * pw, h.top * ph, h.right * pw, h.bottom * ph)
            hlPaint.style = android.graphics.Paint.Style.FILL
            hlPaint.color = (fixColor(h.color) and 0x00FFFFFF) or 0x5E000000
            canvas.drawRoundRect(hlRect, 4 * d, 4 * d, hlPaint)
            if (h.note.isNotBlank()) {
                // note badge on the top corner
                val cx = hlRect.right - 2 * d; val cy = hlRect.top + 2 * d
                hlPaint.color = fixColor(h.color)
                canvas.drawCircle(cx, cy, 7 * d, hlPaint)
                hlPaint.color = fixColor(android.graphics.Color.WHITE)
                canvas.drawCircle(cx, cy, 2.5f * d, hlPaint)
            }
        }
        drawFold(canvas, pw, ph, real, l, t)
    }

    /** Page rects from the latest draw pass only (older ones may be stale after scrolling). */
    private fun freshPageRects(): Map<Int, android.graphics.RectF> {
        val latest = pageRectTime.values.maxOrNull() ?: return emptyMap()
        return pageRects.filterKeys { (pageRectTime[it] ?: 0) >= latest - 60 }
    }

    private fun createHighlight(r: android.graphics.RectF) {
        val entry = freshPageRects().entries.firstOrNull { it.value.contains(r.centerX(), r.centerY()) }
        if (entry == null) {
            Toast.makeText(this, "Draw over the page text", Toast.LENGTH_SHORT).show(); return
        }
        val (page, pr) = entry.key to entry.value
        fun fx(x: Float) = ((x - pr.left) / pr.width()).coerceIn(0f, 1f)
        fun fy(y: Float) = ((y - pr.top) / pr.height()).coerceIn(0f, 1f)
        val h = Highlight(bookId = bookId, ownerName = ownerName, page = page,
            left = fx(r.left), top = fy(r.top), right = fx(r.right), bottom = fy(r.bottom), color = highlightColor)
        lifecycleScope.launch {
            progressRepo.saveHighlight(h)
            Snackbar.make(binding.root, "🖍 Highlighted", Snackbar.LENGTH_LONG)
                .setAction("Add note") { editHighlight(h) }
                .setActionTextColor(themeColor(R.attr.kAccent))
                .show()
        }
    }

    private fun hitHighlight(x: Float, y: Float): Highlight? {
        val rects = freshPageRects()
        return highlights.lastOrNull { h ->
            val pr = rects[h.page] ?: return@lastOrNull false
            val pad = 6 * resources.displayMetrics.density
            x >= pr.left + h.left * pr.width() - pad && x <= pr.left + h.right * pr.width() + pad &&
            y >= pr.top + h.top * pr.height() - pad && y <= pr.top + h.bottom * pr.height() + pad
        }
    }

    /** Note + colour + delete for one highlight. */
    private fun editHighlight(h: Highlight) {
        val d = resources.displayMetrics.density
        var color = h.color
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        val input = EditText(this).apply {
            hint = "Write a note (optional)"
            setText(h.note)
            minLines = 2
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        val colors = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, (12 * d).toInt(), 0, 0)
        }
        fun paintDots() {
            for (i in 0 until colors.childCount) {
                val v = colors.getChildAt(i); val c = v.tag as Int
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c)
                    if (c == color) setStroke((3 * d).toInt(), themeColor(R.attr.kTextPrimary))
                }
            }
        }
        Highlight.COLORS.forEach { c ->
            colors.addView(View(this).apply {
                tag = c
                layoutParams = android.widget.LinearLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt())
                    .apply { marginEnd = (12 * d).toInt() }
                setOnClickListener { color = c; paintDots() }
            })
        }
        paintDots()
        box.addView(input); box.addView(colors)

        AlertDialog.Builder(this)
            .setTitle("Highlight · page ${h.page + 1}")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch { progressRepo.saveHighlight(h.copy(note = input.text.toString().trim(), color = color)) }
            }
            .setNeutralButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    progressRepo.deleteHighlight(h)
                    Snackbar.make(binding.root, "Highlight deleted", Snackbar.LENGTH_LONG)
                        .setAction("Undo") { lifecycleScope.launch { progressRepo.saveHighlight(h.copy(isDeleted = false)) } }
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHighlightsList() {
        val sorted = highlights.sortedWith(compareBy({ it.page }, { it.top }))
        val labels = sorted.map { h ->
            val dot = when (h.color) {
                Highlight.COLORS[1] -> "🟩"; Highlight.COLORS[2] -> "🟦"; Highlight.COLORS[3] -> "🟪"; else -> "🟨"
            }
            "$dot  p. ${h.page + 1}   " + (h.note.ifBlank { "Highlight" }).take(80)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("📝 Highlights & notes")
            .setItems(labels) { _, i -> goTo(sorted[i].page, true) }
            .setNegativeButton("Close", null)
            .show()
    }

    // ── Scan tools: crop margins, footnote fold, deskew, clean-up (book mode) ─────────────

    private var applyingCrop = false
    /** Zoom that counts as "not zoomed by the user" (1, or the crop zoom). */
    private var baseZoom = 1f
    /** Where the current page sits after the crop zoom (null = not cropped). */
    private var layoutNow: CropDetector.Layout? = null
    private var curCrop: PageCrop? = null
    private val cropMemo = HashMap<Int, PageCrop>()
    private val foldHandle = android.graphics.RectF()
    private val foldHandleTmp = android.graphics.RectF()
    private var editingFootnote = false

    private fun saveProfile() { profile?.let { profileStore.save(it, ownerName) } }

    // ·· colour filter (clean scan + dark pages) ··

    private fun filterSpecNow() =
        ScanFilter.Spec(prefs.invertPages, profile?.cleanLevel ?: 0, currentTheme().backgroundColor)

    /** Colour around the page: theme background, or the inverted paper colour in dark pages. */
    private fun surroundColor() = ScanFilter.surround(curSpec, currentTheme().backgroundColor)

    /** Colour to draw inside the filtered PDFView so it ends up as [c] on screen. */
    private fun fixColor(c: Int) = ScanFilter.preimage(c, curSpec)

    /**
     * One colour matrix over the whole PDFView (hardware layer): levels clean-up and a hue-safe
     * dark-page inversion at no per-frame cost. The view's own background turns transparent so
     * the area around the pages keeps the theme colour (set on the curl layout behind it).
     */
    private fun applyScanFilter() {
        curSpec = filterSpecNow()
        val surround = surroundColor()
        binding.curlLayout.setBackgroundColor(surround)
        val f = ScanFilter.colorFilter(curSpec)
        if (f != null) {
            binding.pdfView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.pdfView.setLayerType(View.LAYER_TYPE_HARDWARE,
                android.graphics.Paint().apply { colorFilter = f })
        } else {
            binding.pdfView.setLayerType(View.LAYER_TYPE_NONE, null)
            binding.pdfView.setBackgroundColor(surround)
        }
        pageCache?.filter = f
        binding.pdfView.invalidate()
    }

    // ·· per-page look ··

    /** Look of [page] for the curl images — runs on the render thread and may analyse the page. */
    private fun lookFor(page: Int): PageCrop? {
        val pr = profile ?: return null
        if (!cropOn && !pr.deskew) return null
        return pr.resolve(page, analyzer?.infoBlocking(page), cropOn)
    }

    /** Look of [page] for the live view; null until the page has been analysed. */
    private fun cropOf(page: Int): PageCrop? {
        val pr = profile ?: return null
        if (!cropOn && !pr.deskew) return null
        cropMemo[page]?.let { return it }
        val info = analyzer?.peek(page) ?: return null
        return pr.resolve(page, info, cropOn).also { cropMemo[page] = it }
    }

    private fun invalidateLook() {
        cropMemo.clear()
        pageCache?.invalidate()
        if (totalPages > 0) pageCache?.prefetch(currentPage, totalPages)
    }

    private fun initScanTools() {
        if (prefs.cropMargins) enableCrop() else refreshPageLook()
        // Another device may have tuned this book's crop / footnote lines / clean-up meanwhile
        lifecycleScope.launch {
            val local = profile ?: return@launch
            val remote = profileStore.sync(local, ownerName) ?: return@launch
            profile = remote
            applyReadingTheme()
            if (prefs.cropMargins && remote.hasBoxes) setCropOn()
            else { invalidateLook(); refreshPageLook() }
        }
    }

    private fun enableCrop() {
        val an = analyzer ?: return
        val pr = profile ?: return
        if (pr.hasBoxes) { setCropOn(); return }
        lifecycleScope.launch {
            Toast.makeText(this@ReaderActivity, "✂ Analysing pages…", Toast.LENGTH_SHORT).show()
            val boxes = CropProfileBuilder.build(an)
            if (boxes == null) {
                Toast.makeText(this@ReaderActivity, "✂ Couldn't find printed text to crop", Toast.LENGTH_SHORT).show()
                return@launch
            }
            pr.odd = boxes.first; pr.even = boxes.second
            saveProfile()
            if (prefs.cropMargins) setCropOn()
        }
    }

    private fun setCropOn() {
        cropOn = true
        invalidateLook()
        refreshPageLook()
    }

    private fun disableCrop() {
        cropOn = false
        layoutNow = null
        baseZoom = 1f
        applyingCrop = true
        binding.pdfView.zoomTo(1f)
        goTo(currentPage, false)
        applyingCrop = false
        binding.pdfView.loadPages()
        invalidateLook()
        refreshPageLook()
    }

    private fun redetectCrop() {
        val pr = profile ?: return
        pr.odd = null; pr.even = null; pr.clearManual()
        analyzer?.clearCache()
        cropOn = false
        cropMemo.clear()
        saveProfile()
        enableCrop()
    }

    /**
     * Brings the live view in line with the current page: crop zoom + footnote curtain and the
     * deskew rotation. Waits (without blocking) for the page analysis the first time.
     */
    private fun refreshPageLook() {
        val pr = profile ?: return
        val an = analyzer
        if (an == null || !isBookMode || (!cropOn && !pr.deskew)) {
            curCrop = null; layoutNow = null
            binding.pdfView.animate().cancel()
            binding.pdfView.rotation = 0f
            binding.pdfView.invalidate()
            return
        }
        val page = currentPage
        if (an.peek(page) == null) {
            lifecycleScope.launch { if (an.info(page) != null && page == currentPage) refreshPageLook() }
            return
        }
        val crop = cropOf(page)
        curCrop = crop
        if (cropOn && crop?.box != null) applyCrop()
        binding.pdfView.animate().cancel()
        binding.pdfView.rotation = if (highlightMode || editingFootnote) 0f else (crop?.skew ?: 0f)
        binding.pdfView.invalidate()
    }

    /** Re-centre and zoom the current page onto its text block (footnote area folded away). */
    private fun applyCrop() {
        val box = curCrop?.box ?: return
        if (totalPages == 0) return
        val size = binding.pdfView.getPageSize(toDisplayPage(currentPage))
        val w = binding.pdfView.width.toFloat(); val h = binding.pdfView.height.toFloat()
        if (w <= 0 || size.width <= 0) return
        val lay = CropDetector.layout(w, h, size.width, size.height, box)
        layoutNow = lay
        applyingCrop = true
        binding.pdfView.zoomTo(1f)
        goTo(currentPage, false)
        if (lay.zoom > 1.01f) binding.pdfView.zoomCenteredTo(lay.zoom, android.graphics.PointF(lay.pivotX, lay.pivotY))
        applyingCrop = false
        baseZoom = lay.zoom
        binding.pdfView.loadPages()
    }

    /** Screen y of a touch given in PDFView coordinates (the view may be rotated by deskew). */
    private fun localToScreenY(x: Float, y: Float): Float {
        val r = Math.toRadians(binding.pdfView.rotation.toDouble())
        val dx = x - binding.pdfView.width / 2f; val dy = y - binding.pdfView.height / 2f
        return (binding.pdfView.height / 2f + dx * Math.sin(r) + dy * Math.cos(r)).toFloat()
    }

    // ·· footnote fold: curtain, handle, sheet, manual line ··

    // ·· footnote line in EVERY reading mode (book mode only adds the crop zoom on top) ··

    /** Fold line (page fraction) of [page] as shown right now; -1 = nothing folded. */
    private fun foldNow(page: Int): Float =
        if (cropOn) (cropOf(page)?.foldY ?: -1f) else (profile?.manualFold(page) ?: -1f)

    /** Area hidden behind the curtain of [page], for the footnote sheet. */
    private fun footRectNow(page: Int): FloatArray? {
        if (cropOn) return cropOf(page)?.footRect
        val fy = foldNow(page)
        if (fy <= 0f) return null
        val b = analyzer?.peek(page)?.box
        val l = b?.get(0) ?: 0.04f; val r = b?.get(2) ?: 0.96f
        val fb = ((b?.get(3) ?: 1f) + 0.01f).coerceAtMost(1f)
        return if (fb - fy > 0.012f) floatArrayOf(l, fy, r, fb) else null
    }

    /** Is the "Footnote fold" switch really doing something in the current mode? */
    private fun foldActive(): Boolean {
        val pr = profile ?: return false
        if (!pr.fold) return false
        return cropOn || pr.fixedFold > 0f || pr.overrides.isNotEmpty()
    }

    /** Screen y (PDFView coordinates) of a page fraction on the current page; null if not drawn yet. */
    private fun fracToScreenY(f: Float): Float? {
        layoutNow?.takeIf { cropOn }?.let { return it.screenY(f) }
        val r = freshPageRects()[currentPage] ?: return null
        return r.top + f * r.height()
    }

    private fun screenYToFrac(y: Float): Float? {
        layoutNow?.takeIf { cropOn }?.let { return it.pageFracY(y) }
        val r = freshPageRects()[currentPage] ?: return null
        return if (r.height() > 0f) (y - r.top) / r.height() else null
    }

    /** A finger held on the footer: on the folded area or within a thumb's width above its line. */
    private fun footerHit(x: Float, y: Float): Boolean {
        if (highlightMode || editingFootnote) return false
        val fy = foldNow(currentPage)
        if (fy <= 0f) return false
        val r = freshPageRects()[currentPage] ?: return false
        if (x < r.left || x > r.right) return false
        val line = r.top + fy * r.height()
        val pad = 36f * resources.displayMetrics.density
        return y >= line - pad && y <= r.bottom
    }

    /** Looks at the pages around [page] so their footnote separator is known (plain reading modes). */
    private fun analyzeAround(page: Int) {
        val an = analyzer ?: return
        lifecycleScope.launch {
            for (p in intArrayOf(page, page + 1, page - 1)) {
                if (p < 0 || (totalPages > 0 && p >= totalPages)) continue
                an.info(p)
                if (p == page && !cropOn) binding.pdfView.invalidate()
            }
        }
    }

    /** Book mode brings crop + tuning (initScanTools); other modes only need the synced profile. */
    private fun initPlainTools() {
        analyzeAround(currentPage)
        lifecycleScope.launch {
            val local = profile ?: return@launch
            val remote = profileStore.sync(local, ownerName) ?: return@launch
            profile = remote
            applyReadingTheme()
            binding.pdfView.invalidate()
        }
    }

    /** Called from the PDFView draw pass for every visible page (canvas is at the page's top-left). */
    private fun drawFold(canvas: android.graphics.Canvas, pw: Float, ph: Float, real: Int, ox: Float, oy: Float) {
        if (real == currentPage) foldHandle.setEmpty()
        if (editingFootnote) return
        val fy: Float; val cx: Float
        if (cropOn) {
            val crop = cropOf(real) ?: return
            val box = crop.box ?: return
            if (crop.foldY <= 0f) return
            fy = crop.foldY; cx = (box[0] + box[2]) / 2f
        } else {
            fy = profile?.manualFold(real) ?: -1f
            if (fy <= 0f) return
            val b = analyzer?.peek(real)?.box
            cx = if (b != null) (b[0] + b[2]) / 2f else 0.5f
        }
        val out = if (real == currentPage) foldHandleTmp else null
        FoldPainter.draw(canvas, 0f, 0f, pw, ph, fy, cx,
            surroundColor(), resources.displayMetrics.density, { fixColor(it) }, out)
        if (out != null) foldHandle.set(out.left + ox, out.top + oy, out.right + ox, out.bottom + oy)
    }

    /** Slide-up sheet with the folded footnotes of the current page, rendered sharp. */
    private fun showFootnotes() {
        val fr = footRectNow(currentPage)
        if (fr == null) {
            Toast.makeText(this, "No folded footnotes on this page", Toast.LENGTH_SHORT).show()
            return
        }
        val an = analyzer ?: return
        val page = currentPage
        lifecycleScope.launch {
            val outW = minOf(resources.displayMetrics.widthPixels, 1500)
            val raw = withContext(Dispatchers.Default) {
                an.renderRegion(page, fr[0], fr[1], fr[2], fr[3], outW)
            } ?: return@launch
            val bmp = ScanFilter.apply(raw, curSpec)
            val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this@ReaderActivity)
            val scroll = androidx.core.widget.NestedScrollView(this@ReaderActivity)
            val iv = android.widget.ImageView(this@ReaderActivity).apply {
                adjustViewBounds = true
                setImageBitmap(bmp)
                setBackgroundColor(surroundColor())
            }
            scroll.addView(iv, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            dialog.setContentView(scroll)
            dialog.setOnShowListener {
                dialog.behavior.skipCollapsed = true
                dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
            dialog.show()
        }
    }

    private val exitFootnoteEditOnBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = stopFootnoteEdit()
    }

    private fun setupFootnoteEditor() {
        onBackPressedDispatcher.addCallback(this, exitFootnoteEditOnBack)
        binding.tvFnCancel.setOnClickListener { stopFootnoteEdit() }
        binding.tvFnThis.setOnClickListener { commitFootnoteLine(FN_THIS) }
        binding.tvFnAll.setOnClickListener { commitFootnoteLine(FN_ALL) }
        binding.tvFnNone.setOnClickListener { commitFootnoteLine(FN_NONE) }
    }

    private fun startFootnoteEdit() {
        val an = analyzer
        if (!cropOn && an != null && an.peek(currentPage) == null) {
            // Plain reading mode: look at the page first so the line starts at its separator
            val page = currentPage
            lifecycleScope.launch { an.info(page); if (page == currentPage) beginFootnoteEdit() }
            return
        }
        beginFootnoteEdit()
    }

    private fun beginFootnoteEdit() {
        val pr = profile ?: return
        val base = if (cropOn) pr.boxFor(currentPage) else null
        val info = analyzer?.peek(currentPage)
        val topFrac = base?.get(1) ?: info?.box?.get(1) ?: 0f
        val start = foldNow(currentPage).takeIf { it > 0f }
            ?: info?.sepY?.takeIf { it > 0f }?.plus(0.004f)
            ?: (topFrac + (1f - topFrac) * 0.8f)
        val minS = fracToScreenY(topFrac + 0.10f)
        val maxS = fracToScreenY(0.995f)
        val startS = fracToScreenY(start)
        if (minS == null || maxS == null || startS == null) {
            Toast.makeText(this, "Page not ready yet — try again", Toast.LENGTH_SHORT).show()
            return
        }
        editingFootnote = true
        exitFootnoteEditOnBack.isEnabled = true
        binding.pdfView.animate().cancel()
        binding.pdfView.rotation = 0f          // the line maths assumes an upright page
        val ed = binding.footnoteEditor
        ed.minY = minS
        ed.maxY = minOf(maxS, binding.pdfView.height.toFloat())
        ed.lineY = startS
        ed.visibility = View.VISIBLE
        binding.footnoteBar.visibility = View.VISIBLE
        if (isUiVisible) toggleUi()
        binding.pdfView.invalidate()
    }

    private fun stopFootnoteEdit() {
        editingFootnote = false
        exitFootnoteEditOnBack.isEnabled = false
        binding.footnoteEditor.visibility = View.GONE
        binding.footnoteBar.visibility = View.GONE
        refreshPageLook()
    }

    private fun commitFootnoteLine(mode: Int) {
        val pr = profile
        val frac = screenYToFrac(binding.footnoteEditor.lineY)
        if (pr == null || frac == null) { stopFootnoteEdit(); return }
        val y = frac.coerceIn(0.05f, 0.995f)
        when (mode) {
            FN_THIS -> pr.overrides[currentPage] = y
            FN_ALL  -> pr.fixedFold = y
            else    -> pr.overrides[currentPage] = CropProfile.NO_FOOTNOTES
        }
        pr.fold = true
        saveProfile()
        invalidateLook()
        stopFootnoteEdit()
        Toast.makeText(this, when (mode) {
            FN_THIS -> "📎 Footnote line saved for this page"
            FN_ALL  -> "📎 Same line on all pages — use “None here” on pages without footnotes"
            else    -> "📎 This page has no footnotes"
        }, Toast.LENGTH_LONG).show()
    }

    private fun resetFootnoteLine() {
        profile?.overrides?.remove(currentPage)
        saveProfile(); invalidateLook(); refreshPageLook()
    }

    private fun toggleFold() {
        val pr = profile ?: return
        pr.fold = !pr.fold
        saveProfile(); invalidateLook(); refreshPageLook()
        binding.pdfView.invalidate()
        Toast.makeText(this, when {
            !pr.fold -> "Footnotes shown"
            cropOn   -> "📎 Footnotes folded — tap “▲ Footnotes” to read them"
            else     -> "📎 Footnote fold on — long-press a footer to set its line"
        }, Toast.LENGTH_SHORT).show()
    }

    private fun cycleClean() {
        val pr = profile ?: return
        pr.cleanLevel = (pr.cleanLevel + 1) % 3
        saveProfile()
        applyReadingTheme()
        Toast.makeText(this, "🧼 Clean scan: " + listOf("off", "light", "strong")[pr.cleanLevel],
            Toast.LENGTH_SHORT).show()
    }

    private fun toggleDeskew() {
        val pr = profile ?: return
        if (!isBookMode) {
            Toast.makeText(this, "Turn on Book mode first", Toast.LENGTH_SHORT).show(); return
        }
        pr.deskew = !pr.deskew
        saveProfile(); invalidateLook(); refreshPageLook()
        Toast.makeText(this, if (pr.deskew) "📐 Pages straightened" else "Deskew off", Toast.LENGTH_SHORT).show()
    }

    private fun toggleInvertPages() {
        prefs.invertPages = !prefs.invertPages
        applyReadingTheme()
        Toast.makeText(this, if (prefs.invertPages) "🌓 Dark pages on" else "Dark pages off", Toast.LENGTH_SHORT).show()
    }

    // ── Pinch-zoom memory ─────────────────────────────────────────────────────

    private var zoomRestored = false
    private fun restoreZoom() {
        if (zoomRestored) return   // onRender fires repeatedly; restoring every time fought the user's pinch
        zoomRestored = true
        val savedZoom = prefs.getBookZoom(bookId)
        if (savedZoom > 0f && !isBookMode) {
            binding.pdfView.post {
                // PDFView.zoomTo() only changes a number: it neither re-aligns the page nor redraws.
                // Without the jumpTo() the strip offset (computed at zoom 1) pointed at a DIFFERENT
                // page once scaled — blank screen, and a swipe landed on e.g. p.200 instead of p.180.
                // Re-jumping recomputes the offset at the new zoom and triggers the render.
                binding.pdfView.zoomTo(savedZoom)
                goTo(currentPage, false)
            }
        }
    }

    private fun saveZoom() {
        val zoom = binding.pdfView.zoom
        if (zoom > 0f) prefs.setBookZoom(bookId, zoom)
    }

    // ── Brightness (edge swipe) ───────────────────────────────────────────────

    private lateinit var brightness: BrightnessController

    /**
     * Swipe up/down along one screen edge. The edge is the one page turns DON'T use:
     *  • horizontal Arabic (RTL): right edge   • horizontal LTR: left edge
     *  • vertical: left edge (the scroll handle lives on the right)
     */
    private fun setupBrightness() {
        brightness = BrightnessController(
            activity   = this,
            prefs      = prefs,
            dimOverlay = binding.dimOverlay,
            hud        = binding.hudBrightness,
            hudFill    = binding.hudFill,
            hudValue   = binding.tvHudValue,
            hudIcon    = binding.tvHudIcon
        )
        binding.curlLayout.brightnessEdge =
            if (isHorizontal && prefs.rtlPageOrder) android.view.Gravity.RIGHT else android.view.Gravity.LEFT
        binding.curlLayout.brightnessListener = object : PageCurlLayout.BrightnessListener {
            override fun canAdjust() = binding.pdfView.zoom <= baseZoom + 0.05f
            override fun onBrightnessStart() = brightness.onStart()
            override fun onBrightnessDelta(fraction: Float) = brightness.onDelta(fraction)
            override fun onBrightnessEnd() = brightness.onEnd()
        }
    }

    // ── Pin overlay ───────────────────────────────────────────────────────────

    private fun applyPinStyle() {
        binding.pinMarker.style = try { PinMarkerView.Style.valueOf(prefs.pinStyle) }
            catch (e: Exception) { PinMarkerView.Style.RIBBON }
        // Ribbon on the side you don't turn from: right for Arabic books
        binding.pinMarker.onRightEdge = prefs.rtlPageOrder
    }

    /** Show/hide the marker for the current page. [mode]: "appear", "drop" or "pulse". */
    private fun drawPinOverlay(mode: String = "appear") {
        if (pinPage < 0 || pinYRatio < 0f || pinPage != currentPage) {
            binding.pinMarker.hideNow()
            return
        }
        binding.pinMarker.post {
            val yPx = pinYRatio * binding.pdfView.height
            when (mode) {
                "drop"  -> binding.pinMarker.drop(yPx)
                "pulse" -> { binding.pinMarker.appear(yPx); binding.pinMarker.postDelayed({ binding.pinMarker.pulse() }, 250) }
                else    -> binding.pinMarker.appear(yPx)
            }
        }
    }

    private fun setPinAt(page: Int, yRatio: Float) {
        binding.pdfView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // Long-press on the marker line again (±6 % of the screen) removes it; elsewhere moves it
        val removing = pinPage == page && kotlin.math.abs(pinYRatio - yRatio) < 0.06f
        if (removing) {
            pinPage = -1; pinYRatio = -1f
            binding.pinMarker.retract()
        } else {
            pinPage = page; pinYRatio = yRatio
            drawPinOverlay("drop")
        }
        invalidateOptionsMenu()
        Snackbar.make(binding.root,
            if (removing) "Marker removed" else "📍 Marker saved — long-press it again to remove",
            Snackbar.LENGTH_SHORT).show()
        val p = pinPage; val r = pinYRatio
        ioScope.launch {
            progressRepo.savePositionPin(bookId, ownerName, bookName, p, r, totalPages)
        }
    }

    private fun jumpToPin() {
        if (pinPage < 0) return
        if (pinPage == currentPage) drawPinOverlay("pulse")
        else { pendingPinPulse = true; goTo(pinPage, true) }
    }
    private var pendingPinPulse = false

    // ── Bottom bar ────────────────────────────────────────────────────────────

    // ── Seekbar with page preview (UI 2) ──────────────────────────────────────

    private var seekPreview: SeekPreviewRenderer? = null

    private fun setupBottomBar() {
        seekPreview = SeekPreviewRenderer(File(localPath))
        binding.tvPageInfo.setOnClickListener { cycleInfoMode() }
        binding.seekbarPages.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingSeekbar) return
                binding.tvPageInfo.text = "Go to page ${p + 1} / $totalPages"
                updateSeekPreview(sb, p)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                cancelAutoHide()
                val card = binding.cardSeekPreview
                card.visibility = View.VISIBLE
                card.scaleX = 0.85f; card.scaleY = 0.85f
                card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
                updateSeekPreview(sb, sb.progress)
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                binding.cardSeekPreview.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(140)
                    .withEndAction { binding.cardSeekPreview.visibility = View.GONE }.start()
                goTo(sb.progress, true)
                scheduleAutoHide()
            }
        })
    }

    /** Position the preview card above the thumb and load the page thumbnail. */
    private fun updateSeekPreview(sb: SeekBar, p: Int) {
        val card = binding.cardSeekPreview
        binding.tvSeekPreview.text = "${p + 1}"
        // Thumb x in screen coordinates (respects RTL seekbar)
        val frac = if (sb.max > 0) p.toFloat() / sb.max else 0f
        val usable = sb.width - sb.paddingLeft - sb.paddingRight
        val thumbX = if (sb.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            sb.x + sb.width - sb.paddingRight - frac * usable
        else sb.x + sb.paddingLeft + frac * usable
        val parentW = (card.parent as View).width
        card.translationX = (binding.bottomBar.x + thumbX - card.width / 2f)
            .coerceIn(8f, (parentW - card.width - 8).toFloat().coerceAtLeast(8f))
        card.translationY = -(binding.bottomBar.height + 8f)
        seekPreview?.request(p) { page, bmp ->
            if (binding.seekbarPages.progress == page) binding.ivSeekPreview.setImageBitmap(bmp)
        }
    }

    // ── Immersive reading (UI 1) ──────────────────────────────────────────────

    private val insetsController by lazy {
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
    }
    private val autoHide = Runnable { if (isUiVisible) toggleUi() }

    private fun setupImmersive() {
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false
        insetsController.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Bars sit below the status bar / above the gesture bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            binding.toolbar.setPadding(0, bars.top, 0, 0)
            binding.bottomBar.setPadding(binding.bottomBar.paddingLeft, binding.bottomBar.paddingTop,
                binding.bottomBar.paddingRight, (16 * resources.displayMetrics.density).toInt() + bars.bottom)
            binding.panelBookmarks.setPadding(binding.panelBookmarks.paddingLeft, bars.top,
                binding.panelBookmarks.paddingRight, bars.bottom)
            insets
        }
        // Start with the bars visible briefly, then get out of the way
        scheduleAutoHide(2500)
    }

    private fun scheduleAutoHide(delay: Long = 3500) {
        binding.root.removeCallbacks(autoHide)
        binding.root.postDelayed(autoHide, delay)
    }
    private fun cancelAutoHide() = binding.root.removeCallbacks(autoHide)

    private fun setupBookmarkPanel() {
        bookmarkAdapter = BookmarkAdapter(
            onItemClick = { bookmark ->
                goTo(bookmark.pageNumber, true)
                toggleBookmarkPanel(false)
            },
            onItemLongClick = { bookmark ->
                AlertDialog.Builder(this)
                    .setTitle("Delete bookmark?")
                    .setMessage("${bookmark.label} (page ${bookmark.pageNumber + 1})")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            progressRepo.deleteBookmark(bookmark)
                            updateBookmarkIcon()
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        )
        binding.rvBookmarks.apply {
            layoutManager = LinearLayoutManager(this@ReaderActivity)
            adapter = bookmarkAdapter
        }
        lifecycleScope.launch {
            progressRepo.getBookmarksFlow(bookId, ownerName).collectLatest { bookmarks ->
                bookmarkAdapter.submitList(bookmarks)
                binding.tvNoBookmarks.visibility = if (bookmarks.isEmpty()) View.VISIBLE else View.GONE
                updateBookmarkIcon()
            }
        }
    }

    private fun toggleBookmarkPanel(show: Boolean) {
        val panel = binding.panelBookmarks
        val w = panel.width.takeIf { it > 0 }?.toFloat() ?: (280 * resources.displayMetrics.density)
        if (show) {
            panel.translationX = w
            panel.visibility = View.VISIBLE
            panel.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        } else if (panel.visibility == View.VISIBLE) {
            panel.animate().translationX(w).setDuration(180)
                .withEndAction { panel.visibility = View.GONE }.start()
        }
    }

    private fun updateProgressUi() {
        if (totalPages == 0) return
        val pct = ((currentPage + 1f) / totalPages * 100).toInt()
        val eta = computeEta()
        val left = (totalPages - 1 - currentPage).coerceAtLeast(0)
        binding.tvPageInfo.text = when (extras.readerInfoMode) {
            1 -> {
                val t = if (eta != null) "~${fmtMin(eta)} left" else "time left: reading a bit more to estimate…"
                "$left ${if (left == 1) "page" else "pages"} left  •  $t"
            }
            2 -> {
                val goal = extras.dailyMinuteGoal
                val today = todayMs() / 60_000
                val goalPart = if (goal > 0) "$today / $goal min" + (if (today >= goal) " ✓" else "") else "$today min"
                "⏱ ${fmtClock(activeSessionMs())}  •  Today $goalPart"
            }
            else -> {
                val etaStr = if (eta != null) "  •  ~${fmtMin(eta)} left" else ""
                "Page ${currentPage + 1} / $totalPages  •  $pct%$etaStr"
            }
        }
        isUpdatingSeekbar = true
        binding.seekbarPages.max      = totalPages - 1
        binding.seekbarPages.progress = currentPage
        isUpdatingSeekbar = false
    }

    /** Tap the info line under the seekbar: page → time left → timer & goal. */
    private fun cycleInfoMode() {
        extras.readerInfoMode = (extras.readerInfoMode + 1) % 3
        updateProgressUi()
        scheduleAutoHide()
    }

    private fun fmtMin(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "$m min"

    private fun fmtClock(ms: Long): String {
        val sec = ms / 1000
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    // ── Reading timer & daily time goal ───────────────────────────────────────

    private var sessionActiveMs = 0L           // reading time of this visit, background excluded
    private var timeGoalToastShown = false
    private var tickJob: Job? = null

    private fun activeSessionMs() = sessionActiveMs + (System.currentTimeMillis() - lastCheckpointMs).coerceAtLeast(0)
    private fun todayMs() = extras.msToday + (System.currentTimeMillis() - lastCheckpointMs).coerceAtLeast(0)

    private fun checkTimeGoal() {
        val g = extras.dailyMinuteGoal
        if (g <= 0 || timeGoalToastShown) return
        if (todayMs() >= g * 60_000L) {
            timeGoalToastShown = true
            Toast.makeText(this, "🎯 Daily reading goal reached — $g min!", Toast.LENGTH_LONG).show()
        }
    }

    private fun showTimeGoalDialog() {
        val opts = intArrayOf(0, 10, 15, 20, 30, 45, 60, 90)
        val labels = opts.map { if (it == 0) "No time goal" else "$it minutes a day" }.toTypedArray()
        val cur = opts.indexOf(extras.dailyMinuteGoal).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("🎯 Daily goal · ${todayMs() / 60_000} min today")
            .setSingleChoiceItems(labels, cur) { d, i ->
                extras.dailyMinuteGoal = opts[i]
                timeGoalToastShown = opts[i] > 0 && todayMs() >= opts[i] * 60_000L
                if (opts[i] > 0) extras.readerInfoMode = 2
                updateProgressUi()
                d.dismiss()
                Toast.makeText(this,
                    if (opts[i] > 0) "Goal set — tap the line under the seekbar to switch what it shows"
                    else "Time goal off", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ── Text style (font + line spacing, whole app) ───────────────────────────

    private fun showTextStyleDialog() {
        val fonts = com.pdfreader.app.ui.base.TextStyle.fonts
        val cur = fonts.indexOfFirst { it.key == extras.fontFamily }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("🔤 Font")
            .setSingleChoiceItems(fonts.map { it.label }.toTypedArray(), cur) { d, i ->
                extras.fontFamily = fonts[i].key
                d.dismiss()
                showSpacingDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSpacingDialog() {
        val sp = com.pdfreader.app.ui.base.TextStyle.spacings
        val cur = sp.indexOfFirst { kotlin.math.abs(it.value - extras.lineSpacing) < 0.01f }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("↕ Line spacing")
            .setSingleChoiceItems(sp.map { it.label }.toTypedArray(), cur) { d, i ->
                extras.lineSpacing = sp[i].value
                d.dismiss()
                recreate()
            }
            .setNegativeButton("Keep") { _, _ -> recreate() }
            .show()
    }

    // ── ETA to finish ─────────────────────────────────────────────────────────

    private fun computeEta(): Int? {
        if (currentPage <= 1 || totalPages <= 0) return null
        val sessionMs = activeSessionMs()
        // Earlier reading of this book is enough to estimate straight away
        if (sessionMs < 60_000 && allTimeMsAtOpen < 60_000) return null
        val totalMs   = (allTimeMsAtOpen + sessionMs).toFloat()
        val msPerPage = totalMs / currentPage.toFloat().coerceAtLeast(1f)
        if (msPerPage < 3_000 || msPerPage > 600_000) return null
        val remaining = (totalPages - 1 - currentPage).coerceAtLeast(0)
        val etaMin    = ((msPerPage * remaining) / 60_000).roundToInt()
        return if (etaMin > 0) etaMin else null
    }

    /** Slide bars in/out instead of popping them (smoother, no layout jump). */
    private fun toggleUi() {
        isUiVisible = !isUiVisible
        val top = binding.toolbar
        val bottom = binding.bottomBar
        if (isUiVisible) {
            insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            scheduleAutoHide()
            top.visibility = View.VISIBLE; bottom.visibility = View.VISIBLE
            top.animate().translationY(0f).alpha(1f).setDuration(200).start()
            bottom.animate().translationY(0f).alpha(1f).setDuration(200).start()
        } else {
            cancelAutoHide()
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            top.animate().translationY(-top.height.toFloat()).alpha(0f).setDuration(200)
                .withEndAction { if (!isUiVisible) top.visibility = View.INVISIBLE }.start()
            bottom.animate().translationY(bottom.height.toFloat()).alpha(0f).setDuration(200)
                .withEndAction { if (!isUiVisible) bottom.visibility = View.INVISIBLE }.start()
            toggleBookmarkPanel(false)
        }
    }

    private fun updateBookmarkIcon() {
        lifecycleScope.launch {
            val bm = progressRepo.isPageBookmarked(bookId, ownerName, currentPage)
            binding.toolbar.menu?.findItem(R.id.action_bookmark)?.setIcon(
                if (bm) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
            )
        }
    }

    // ── Finished ──────────────────────────────────────────────────────────────

    private fun maybePromptFinished() {
        if (isFinished || endPromptShown || totalPages == 0 || currentPage < totalPages - 1) return
        endPromptShown = true
        Snackbar.make(binding.root, "🎉 You reached the end of this book", Snackbar.LENGTH_INDEFINITE)
            .setAction("Move to Finished") { markFinished() }
            .setActionTextColor(themeColor(R.attr.kAccent))
            .show()
    }

    private fun markFinished() {
        isFinished = true
        checkpoint(push = false)
        lifecycleScope.launch {
            progressRepo.setFinished(bookId, ownerName, bookName, finished = true, totalPages = totalPages)
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle("✅ Moved to Finished shelf")
                .setMessage("Your reading record is kept.\n\nDelete the PDF from this device to free space? (The Drive copy is not touched.)")
                .setPositiveButton("Delete PDF") { _, _ ->
                    DriveRepository(this@ReaderActivity).deleteLocalPdf(bookId)
                    Toast.makeText(this@ReaderActivity, "PDF deleted — record kept", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("Keep") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showJumpToPageDialog() {
        val input = EditText(this).apply {
            hint = "Enter page number (1–$totalPages)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(48, 16, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Jump to Page")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val page = input.text.toString().toIntOrNull()
                if (page != null && page in 1..totalPages) {
                    goTo(page - 1, true)
                } else {
                    Toast.makeText(this, "Invalid page number", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showThemePicker() {
        val themes  = ReadingTheme.values()
        val labels  = themes.map { it.label }.toTypedArray()
        val current = themes.indexOfFirst { it.name == prefs.readingTheme }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Reading Theme")
            .setSingleChoiceItems(labels, current) { d, i ->
                prefs.readingTheme = themes[i].name; applyReadingTheme(); d.dismiss()
            }.show()
    }

    private fun showBookmarkDialog() {
        lifecycleScope.launch {
            val page = currentPage
            if (progressRepo.isPageBookmarked(bookId, ownerName, page)) {
                progressRepo.removeBookmarksOnPage(bookId, ownerName, page)
                Toast.makeText(this@ReaderActivity, "Bookmark removed", Toast.LENGTH_SHORT).show()
                updateBookmarkIcon()
            } else {
                val input = EditText(this@ReaderActivity).apply { hint = "Label (optional)"; setPadding(48, 16, 48, 16) }
                AlertDialog.Builder(this@ReaderActivity)
                    .setTitle("Bookmark Page ${page + 1}").setView(input)
                    .setPositiveButton("Save") { _, _ ->
                        val label = input.text.toString().ifBlank { "Page ${page + 1}" }
                        lifecycleScope.launch {
                            progressRepo.addBookmark(bookId, ownerName, page, label)
                            Toast.makeText(this@ReaderActivity, "Bookmarked: $label", Toast.LENGTH_SHORT).show()
                            updateBookmarkIcon()
                        }
                    }.setNegativeButton("Cancel", null).show()
            }
        }
    }

    // ── Sleep timer ───────────────────────────────────────────────────────────

    private fun showSleepTimerDialog() {
        val options = arrayOf("15 minutes", "30 minutes", "45 minutes", "60 minutes", "Cancel timer")
        val minutes = arrayOf(15, 30, 45, 60, 0)
        AlertDialog.Builder(this)
            .setTitle("⏱ Sleep Timer")
            .setItems(options) { _, which ->
                sleepTimer?.cancel()
                sleepTimerMinutes = minutes[which]
                if (sleepTimerMinutes == 0) {
                    Toast.makeText(this, "Sleep timer cancelled", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                val ms = sleepTimerMinutes * 60 * 1000L
                sleepTimer = object : CountDownTimer(ms, 60_000) {
                    override fun onTick(remaining: Long) {
                        val minsLeft = (remaining / 60_000).toInt() + 1
                        if (minsLeft <= 5) Toast.makeText(this@ReaderActivity,
                            "⏱ $minsLeft min until auto-close", Toast.LENGTH_SHORT).show()
                    }
                    override fun onFinish() {
                        Toast.makeText(this@ReaderActivity, "Sleep timer — closing book", Toast.LENGTH_LONG).show()
                        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
                    }
                }.start()
                Toast.makeText(this, "⏱ Sleep timer set: $sleepTimerMinutes min", Toast.LENGTH_SHORT).show()
            }.show()
    }

    // ── Checkpointing (replaces the old one-shot saveOnClose) ─────────────────

    /**
     * Save the current position. Called on EVERY pause (screen off, app switch, back) and
     * every 45 s while reading. The old version saved only once per session (hasSaved flag),
     * so after the screen turned off once, everything read afterwards was lost and the
     * older page was synced — that's what looked like "sync restored an old state".
     */
    private fun checkpoint(push: Boolean) {
        if (bookId.isEmpty() || totalPages == 0) return
        val now   = System.currentTimeMillis()
        val delta = now - lastCheckpointMs
        lastCheckpointMs = now
        sessionActiveMs += delta
        extras.addMsToday(delta)
        val page  = currentPage
        val total = totalPages
        ioScope.launch(saveDispatcher) {   // single-threaded → saves apply in call order
            progressRepo.saveAndSync(
                bookId = bookId, ownerName = ownerName, bookName = bookName,
                currentPage = page, totalPages = total,
                scrollOffset = 0f, sessionDeltaMs = delta, pushNow = push
            )
            if (push) progressRepo.recordWeeklySnapshot(prefs)
        }
    }

    private fun startPeriodicCheckpoint() {
        tickJob?.cancel()
        tickJob = lifecycleScope.launch {
            while (isActive) {
                delay(20_000)
                if (extras.readerInfoMode != 0) updateProgressUi()
                checkTimeGoal()
            }
        }
        checkpointJob?.cancel()
        checkpointJob = lifecycleScope.launch {
            while (isActive) {
                delay(45_000)
                checkpoint(push = false)   // local only — crash safety without Drive traffic
            }
        }
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

    // ── Toolbar: bookmark + options sheet ─────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_reader, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        lifecycleScope.launch {
            val bm = progressRepo.isPageBookmarked(bookId, ownerName, currentPage)
            menu.findItem(R.id.action_bookmark)?.setIcon(
                if (bm) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
            )
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home    -> { onBackPressedDispatcher.onBackPressed(); true }
        R.id.action_bookmark -> { showBookmarkDialog(); true }
        R.id.action_menu     -> { showReaderMenu(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ── Reader options sheet ──────────────────────────────────────────────────

    override fun finish() {
        super.finish()
        // Page shrinks back toward the library (pairs with the cover scale-up on open)
        overridePendingTransition(R.anim.hold_home, R.anim.reader_exit)
    }

    private fun showReaderMenu() {
        cancelAutoHide()
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheet  = com.pdfreader.app.databinding.SheetReaderMenuBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)
        val inflater = layoutInflater

        // Header
        sheet.tvMenuTitle.text = com.pdfreader.app.data.model.prettyTitle(bookName)
        val pct = if (totalPages > 0) ((currentPage + 1f) / totalPages * 100).toInt() else 0
        val eta = computeEta()?.let { "  •  ~${fmtMin(it)} left" } ?: ""
        sheet.tvMenuSub.text = "Page ${currentPage + 1} of $totalPages  •  $pct%$eta"
        sheet.menuProgress.setProgressCompat(pct, false)

        // Theme swatches
        val d = resources.displayMetrics.density
        ReadingTheme.values().forEach { theme ->
            val selected = theme.name == prefs.readingTheme
            val swatch = View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt())
                    .apply { marginEnd = (12 * d).toInt() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (theme.nightFilterColor != android.graphics.Color.TRANSPARENT)
                        blend(theme.backgroundColor, theme.nightFilterColor) else theme.backgroundColor)
                    setStroke(((if (selected) 3 else 1) * d).toInt(),
                        if (selected) themeColor(R.attr.kAccent) else themeColor(R.attr.kDivider))
                }
                contentDescription = theme.label
                setOnClickListener {
                    prefs.readingTheme = theme.name
                    applyReadingTheme()
                    dialog.dismiss()
                    Toast.makeText(this@ReaderActivity, theme.label, Toast.LENGTH_SHORT).show()
                }
            }
            sheet.layoutThemes.addView(swatch)
        }

        // Quick toggles (tiles)
        fun tile(icon: String, label: String, on: Boolean,
                 into: android.widget.LinearLayout = sheet.layoutToggles, click: () -> Unit) {
            val t = com.pdfreader.app.databinding.ItemMenuTileBinding.inflate(inflater, into, false)
            t.tvTileIcon.text = icon
            t.tvTileLabel.text = label
            val card = t.root
            card.setCardBackgroundColor(if (on) blendAlpha(themeColor(R.attr.kBrand), 0.18f) else themeColor(R.attr.kSurfaceVariant))
            card.strokeColor = if (on) themeColor(R.attr.kBrand) else android.graphics.Color.TRANSPARENT
            t.tvTileLabel.setTextColor(if (on) themeColor(R.attr.kBrand) else themeColor(R.attr.kTextSecondary))
            card.setOnClickListener { dialog.dismiss(); click() }
            into.addView(card)
        }
        tile("📖", "Book\nmode", prefs.pageTurnAnimation) { toggleBookMode() }
        tile("✂️", "Crop\nmargins", prefs.cropMargins && isBookMode) {
            if (!isBookMode) Toast.makeText(this, "Turn on Book mode first", Toast.LENGTH_SHORT).show()
            else toggleCrop()
        }
        tile("↔️", "Arabic\norder", prefs.rtlPageOrder) { toggleRtl() }
        tile(if (isRotationLocked) "🔒" else "🔓", "Lock\nrotation", isRotationLocked) { toggleRotation() }

        // Scans & footnotes
        val level = profile?.cleanLevel ?: 0
        tile("📎", "Footnote\nfold", foldActive(), sheet.layoutToggles2) { toggleFold() }
        tile("🧼", "Clean scan\n" + listOf("off", "light", "strong")[level], level > 0, sheet.layoutToggles2) { cycleClean() }
        tile("📐", "Deskew", isBookMode && profile?.deskew == true, sheet.layoutToggles2) { toggleDeskew() }
        tile("🌓", "Dark\npages", prefs.invertPages, sheet.layoutToggles2) { toggleInvertPages() }

        // Marker style (segmented)
        listOf("RIBBON" to "🎗 Ribbon", "LINE" to "➖ Line", "HIGHLIGHT" to "🖍 Band").forEach { (key, label) ->
            val on = prefs.pinStyle == key
            val chip = com.google.android.material.button.MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                isAllCaps = false
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (6 * d).toInt() }
                setTextColor(if (on) themeColor(R.attr.kOnBrand) else themeColor(R.attr.kTextPrimary))
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (on) themeColor(R.attr.kBrand) else android.graphics.Color.TRANSPARENT)
                strokeColor = android.content.res.ColorStateList.valueOf(themeColor(R.attr.kDivider))
                setOnClickListener {
                    prefs.pinStyle = key
                    applyPinStyle()
                    dialog.dismiss()
                    if (pinPage == currentPage) drawPinOverlay("drop")
                    else Toast.makeText(this@ReaderActivity, "Long-press a line to place the marker", Toast.LENGTH_SHORT).show()
                }
            }
            sheet.layoutPinStyles.addView(chip)
        }

        // Actions
        fun row(icon: String, label: String, value: String = "", click: () -> Unit) {
            val r = com.pdfreader.app.databinding.ItemMenuRowBinding.inflate(inflater, sheet.layoutActions, false)
            r.tvRowIcon.text = icon; r.tvRowLabel.text = label; r.tvRowValue.text = value
            r.root.setOnClickListener { dialog.dismiss(); click() }
            sheet.layoutActions.addView(r.root)
        }
        if (pinPage >= 0) row("📍", "Go to my marker", "p. ${pinPage + 1}") { jumpToPin() }
        row("🖍", "Highlight") { setHighlightMode(true) }
        if (highlights.isNotEmpty()) row("📝", "Highlights & notes", "${highlights.size}") { showHighlightsList() }
        if (foldNow(currentPage) > 0f) row("📎", "Footnotes of this page") { showFootnotes() }
        row("📏", "Adjust footnote line", "or long-press the footer") { startFootnoteEdit() }
        if (profile?.overrides?.containsKey(currentPage) == true) row("↺", "Auto footnote line", "this page") { resetFootnoteLine() }
        if (cropOn) row("🔄", "Re-detect crop", "whole book") { redetectCrop() }
        row("🔢", "Go to page") { showJumpToPageDialog() }
        row("📑", "Bookmarks") { toggleBookmarkPanel(true) }
        row("🎯", "Reading goal & timer",
            if (extras.dailyMinuteGoal > 0) "${todayMs() / 60_000} / ${extras.dailyMinuteGoal} min" else "Off") { showTimeGoalDialog() }
        row("🔤", "Text style", com.pdfreader.app.ui.base.TextStyle.fontLabel(extras.fontFamily)) { showTextStyleDialog() }
        row("⏱", "Sleep timer", if (sleepTimerMinutes > 0) "$sleepTimerMinutes min" else "Off") { showSleepTimerDialog() }
        if (prefs.screenBrightness >= 0f || prefs.readerDim > 0f)
            row("☀️", "Brightness: follow phone") {
                brightness.followSystem()
                Toast.makeText(this, "Brightness follows the phone again", Toast.LENGTH_SHORT).show()
            }
        row(if (isFinished) "↩️" else "✅", if (isFinished) "Read again" else "Mark as finished") { toggleFinished() }
        row("⚙️", "Settings") {
            startActivity(android.content.Intent(this, com.pdfreader.app.ui.settings.SettingsActivity::class.java))
        }

        com.pdfreader.app.ui.base.TextStyle.apply(sheet.root, this)
        dialog.show()
    }

    private fun blend(base: Int, overlay: Int): Int {
        val a = android.graphics.Color.alpha(overlay) / 255f
        fun ch(b: Int, o: Int) = (b * (1 - a) + o * a).toInt()
        return android.graphics.Color.rgb(
            ch(android.graphics.Color.red(base), android.graphics.Color.red(overlay)),
            ch(android.graphics.Color.green(base), android.graphics.Color.green(overlay)),
            ch(android.graphics.Color.blue(base), android.graphics.Color.blue(overlay)))
    }

    private fun blendAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

    // ── Menu actions ──────────────────────────────────────────────────────────

    private fun toggleBookMode() {
        prefs.pageTurnAnimation = !prefs.pageTurnAnimation
        Toast.makeText(this,
            if (prefs.pageTurnAnimation) "📖 Book mode on — drag the page to turn it" else "Book mode off",
            Toast.LENGTH_SHORT).show()
        recreate()   // PDFView scroll mode can only be set at load time
    }

    private fun toggleCrop() {
        prefs.cropMargins = !prefs.cropMargins
        if (prefs.cropMargins) enableCrop() else disableCrop()
    }

    private fun toggleRtl() {
        prefs.rtlPageOrder = !prefs.rtlPageOrder
        Toast.makeText(this,
            if (prefs.rtlPageOrder) "Arabic order: swipe → for next page" else "Left-to-right: swipe ← for next page",
            Toast.LENGTH_SHORT).show()
        recreate()   // page order is fixed at load time
    }

    private fun toggleRotation() {
        isRotationLocked = !isRotationLocked
        prefs.lockRotation = isRotationLocked
        applyRotationLock()
        Toast.makeText(this, if (isRotationLocked) "Rotation locked" else "Rotation unlocked", Toast.LENGTH_SHORT).show()
    }

    private fun toggleFinished() {
        if (!isFinished) { markFinished(); return }
        AlertDialog.Builder(this).setTitle("Read again?")
            .setMessage("The book returns to your library and restarts from page 1.")
            .setPositiveButton("Read again") { _, _ ->
                isFinished = false; endPromptShown = false
                lifecycleScope.launch {
                    progressRepo.setFinished(bookId, ownerName, bookName, false, totalPages)
                    goTo(0, false)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    override fun onResume() {
        super.onResume()
        lastCheckpointMs = System.currentTimeMillis()   // don't count time spent outside the reader
        startPeriodicCheckpoint()
    }

    override fun onPause() {
        super.onPause()
        checkpointJob?.cancel()
        tickJob?.cancel()
        saveZoom()
        prefs.updateStreak()
        checkpoint(push = true)
        com.pdfreader.app.widget.ReadingWidget.refresh(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        sleepTimer?.cancel()
        pageCache?.release()
        analyzer?.close()
        seekPreview?.release()
    }

    companion object {
        private const val STATE_PAGE = "state_page"
        private const val FN_THIS = 0
        private const val FN_ALL  = 1
        private const val FN_NONE = 2
        @OptIn(ExperimentalCoroutinesApi::class)
        private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)
    }
}
