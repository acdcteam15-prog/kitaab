package com.pdfreader.app.ui.home

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.pdfreader.app.KitaabApplication
import com.pdfreader.app.R
import com.pdfreader.app.data.model.Book
import com.pdfreader.app.data.model.ReadingProgress
import com.pdfreader.app.data.model.prettyTitle
import com.pdfreader.app.data.pdf.cleanBookName
import com.pdfreader.app.data.repository.DriveRepository
import com.pdfreader.app.data.repository.ProgressRepository
import com.pdfreader.app.databinding.ActivityHomeBinding
import com.pdfreader.app.databinding.BottomSheetBookDetailsBinding
import com.pdfreader.app.databinding.DialogAddPdfBinding
import com.pdfreader.app.sync.NetworkMonitor
import com.pdfreader.app.ui.auth.LoginActivity
import com.pdfreader.app.ui.finished.FinishedShelfActivity
import com.pdfreader.app.ui.reader.ReaderActivity
import com.pdfreader.app.ui.settings.SettingsActivity
import com.pdfreader.app.ui.upload.PdfEditActivity
import com.pdfreader.app.ui.stats.StatsActivity
import com.pdfreader.app.utils.PreferenceManager
import com.pdfreader.app.worker.DownloadWorker
import com.pdfreader.app.worker.SyncPullWorker
import com.pdfreader.app.worker.UploadWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.abs

class HomeActivity : AppCompatActivity() {

    private val TAG = "HomeActivity"

    private lateinit var binding: ActivityHomeBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var driveRepo: DriveRepository
    private lateinit var progressRepo: ProgressRepository
    private lateinit var bookAdapter: BookAdapter
    private lateinit var readingAdapter: CoverRowAdapter
    private lateinit var finishedRowAdapter: CoverRowAdapter
    private var finishedList: List<ReadingProgress> = emptyList()
    private var skeletonAnim: ObjectAnimator? = null

    private var allBooks: List<Pair<Book, ReadingProgress?>> = emptyList()
    private var isOpeningBook = false   // prevents double-launch on rapid taps
    private var currentSort   = SortMode.LAST_READ
    private var currentSearch = ""
    private var finishedCount = 0
    private var hasLoadedOnce = false

    enum class SortMode { NAME, LAST_READ, PROGRESS }

    /** Smart shelves (#10): filters that fill themselves. */
    enum class Smart(val label: String) { ALL("All"), ALMOST("🏁 Almost done"), FORGOTTEN("💤 Forgotten"), NOT_STARTED("✨ Not started") }
    private var smart = Smart.ALL
    private val smartChips = mutableMapOf<Smart, com.google.android.material.chip.Chip>()

    companion object {
        /** Widget / reminder tap: open the last book straight away. */
        const val EXTRA_OPEN_LAST = "open_last"
        private const val THIRTY_DAYS = 30L * 24 * 60 * 60 * 1000
    }
    private var pendingOpenLast = false

    private val app get() = application as KitaabApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs        = PreferenceManager(this)
        driveRepo    = DriveRepository(this)
        progressRepo = ProgressRepository(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = ""
        setupHeader()
        startSkeleton()

        setupRecycler()
        setupRows()
        setupSmartShelves()
        setupGoalRing()
        pendingOpenLast = intent.getBooleanExtra(EXTRA_OPEN_LAST, false)
        setupSwipeRefresh()
        setupContinueReading()
        setupSearch()
        updateStreakBanner()
        applyAutoNightMode()
        loadBooks()
        loadWeeklySummary()
        observeProgressUpdates()
        observeSyncState()
        observeDownloads()
        setupAddButton()
        observeUploads()
        SyncPullWorker.schedule(this)   // 15-min safety net; real-time sync is app-level
    }

    // ── Live Room updates (from sync on this or the other device) ─────────────
    private fun observeProgressUpdates() {
        lifecycleScope.launch {
            progressRepo.getAllProgressFlow(prefs.currentUser).collectLatest { progressList ->
                finishedList  = progressList.filter { it.isFinished }.sortedByDescending { it.finishedAt }
                finishedCount = finishedList.size
                updateFinishedEntry()
                updateHeaderSubtitle()
                if (allBooks.isEmpty()) return@collectLatest
                val progressMap = progressList.associateBy { it.bookId }
                val updated = allBooks.map { (book, _) -> Pair(book, progressMap[book.id]) }
                if (updated != allBooks) {
                    allBooks = updated
                    applyFilters()
                }
            }
        }
    }

    // ── Sync status + offline banner (reactive, no leaked callbacks) ─────────
    private fun observeSyncState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(NetworkMonitor.online, progressRepo.pendingCountFlow(prefs.currentUser), uploadStatus) { online, pending, up ->
                    Triple(online, pending, up)
                }.collectLatest { (online, pending, up) ->
                    animateVisibility(binding.tvOfflineBanner, !online)
                    binding.tvSyncStatus.text = when {
                        up.count > 0 && !online -> "⏸ ${up.count} upload(s) waiting for internet"
                        up.count > 0            -> uploadingLabel(up)
                        pending > 0 && !online  -> "⏸ $pending change(s) waiting for internet"
                        pending > 0             -> "⬆ Syncing $pending change(s)…"
                        else                    -> "✓ All changes synced"
                    }
                    binding.tvSyncStatus.setTextColor(
                        if (pending > 0 || up.count > 0) getColor(R.color.accent_dark) else getColor(android.R.color.holo_green_dark)
                    )
                }
            }
        }
    }

    private fun animateVisibility(v: View, show: Boolean) {
        if (show && v.visibility != View.VISIBLE) {
            v.alpha = 0f; v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(200).start()
        } else if (!show && v.visibility == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(200).withEndAction { v.visibility = View.GONE }.start()
        }
    }

    // ── Weekly summary ────────────────────────────────────────────────────────
    private fun loadWeeklySummary() {
        lifecycleScope.launch {
            val summary = progressRepo.getWeeklySummary(prefs)
            if (summary.pagesThisWeek > 0 || summary.minutesThisWeek > 0) {
                binding.cardWeeklySummary.visibility = View.VISIBLE
                binding.tvWeekPages.text   = summary.pagesThisWeek.toString()
                binding.tvWeekMinutes.text = summary.minutesThisWeek.toString()
                binding.tvWeekDays.text    = summary.daysActive.toString()
                weekPages = summary.pagesThisWeek
                updateHeaderSubtitle()
            } else {
                binding.cardWeeklySummary.visibility = View.GONE
            }
        }
    }

    // ── Streak banner ─────────────────────────────────────────────────────────
    private fun updateStreakBanner() {
        val streak = prefs.readingStreakDays
        if (streak >= 2) {
            binding.tvStreakBanner.visibility = View.VISIBLE
            binding.tvStreakBanner.text = "🔥 $streak-day streak"
        } else {
            binding.tvStreakBanner.visibility = View.GONE
        }
    }

    // ── Auto Night Mode ───────────────────────────────────────────────────────
    private fun applyAutoNightMode() {
        if (!prefs.autoNightMode) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val nightStart = prefs.nightModeStartHour
        val nightEnd   = prefs.nightModeEndHour
        val isNight = if (nightStart > nightEnd) hour >= nightStart || hour < nightEnd
                      else hour in nightStart until nightEnd
        if (isNight && prefs.readingTheme != "NIGHT" && prefs.readingTheme != "DARK") {
            prefs.readingTheme = "DARK"
        }
    }

    // ── Continue Reading ──────────────────────────────────────────────────────
    private fun setupContinueReading() {
        binding.cardContinueReading.visibility = View.GONE
        val open = View.OnClickListener {
            val pair = allBooks.find { it.first.id == prefs.lastOpenedBookId } ?: return@OnClickListener
            openBook(pair.first, pair.second)
        }
        binding.btnContinueReading.setOnClickListener(open)
        binding.cardContinueReading.setOnClickListener(open)
    }

    private fun updateContinueReading() {
        val bookId = prefs.lastOpenedBookId
        val pair = allBooks.find { it.first.id == bookId }
        if (bookId.isEmpty() || pair == null || pair.first.localPath == null || pair.second?.isFinished == true) {
            binding.cardContinueReading.visibility = View.GONE; return
        }
        val (book, progress) = pair
        if (binding.cardContinueReading.visibility != View.VISIBLE) {
            binding.cardContinueReading.visibility = View.VISIBLE
            binding.cardContinueReading.startAnimation(AnimationUtils.loadAnimation(this, R.anim.item_fade_up))
        }
        binding.tvContinueTitle.text = book.displayName
        Glide.with(this).load(coverOf(book) ?: R.drawable.ic_pdf_placeholder)
            .placeholder(R.drawable.ic_pdf_placeholder).centerCrop().into(binding.ivContinueCover)
        if (progress != null && progress.totalPages > 0) {
            binding.tvContinuePage.text = "Page ${progress.currentPage + 1} / ${progress.totalPages} • ${progress.percent}%"
            binding.progressContinue.setProgress(progress.percent, true)
        } else {
            binding.tvContinuePage.text = "Start reading"
            binding.progressContinue.progress = 0
        }
    }

    // ── Smart shelves (#10) ──────────────────────────────────────────────────

    private fun matches(s: Smart, pair: Pair<Book, ReadingProgress?>): Boolean {
        val (book, p) = pair
        return when (s) {
            Smart.ALL         -> true
            Smart.ALMOST      -> p != null && p.totalPages > 0 && p.percent >= 80
            Smart.FORGOTTEN   -> p != null && p.currentPage > 0 &&
                                 p.lastReadTimestamp < System.currentTimeMillis() - THIRTY_DAYS
            Smart.NOT_STARTED -> book.localPath != null && (p == null || p.currentPage == 0)
        }
    }

    private fun setupSmartShelves() {
        Smart.values().forEach { s ->
            val chip = com.google.android.material.chip.Chip(this, null,
                com.google.android.material.R.attr.chipStyle).apply {
                text = s.label
                isCheckable = true
                isChecked = s == smart
                setOnClickListener {
                    if (smart != s) {
                        smart = s
                        applyFilters()
                        binding.rvBooks.scheduleLayoutAnimation()
                    }
                    isChecked = true
                }
            }
            smartChips[s] = chip
            binding.chipsSmart.addView(chip)
        }
    }

    private fun updateSmartCounts(active: List<Pair<Book, ReadingProgress?>>) {
        smartChips.forEach { (s, chip) ->
            val n = active.count { matches(s, it) }
            chip.text = if (s == Smart.ALL) "${s.label} ($n)" else "${s.label}  $n"
            // Hide empty smart shelves (except the one currently selected)
            chip.visibility = if (s == Smart.ALL || n > 0 || s == smart) View.VISIBLE else View.GONE
        }
    }

    // ── Daily goal ring + reminder (#11) ─────────────────────────────────────

    private val notifPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            prefs.goalReminderEnabled = false
            com.pdfreader.app.worker.GoalReminderWorker.schedule(this)
            Toast.makeText(this, "Reminder needs notification permission", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupGoalRing() {
        binding.goalRing.set(prefs.pagesToday, prefs.dailyPageGoal, animate = false)
        binding.goalRing.setOnClickListener { showGoalDialog() }
    }

    private fun showGoalDialog() {
        val goals = intArrayOf(5, 10, 20, 30, 50, 100)
        val labels = goals.map { "$it pages a day" }.toTypedArray()
        val current = goals.indexOf(prefs.dailyPageGoal).coerceAtLeast(-1)
        val remindLabel = if (prefs.goalReminderEnabled)
            "🔔 Reminder at %02d:%02d (tap to change)".format(prefs.goalReminderMinutes / 60, prefs.goalReminderMinutes % 60)
        else "🔕 Daily reminder: off"
        AlertDialog.Builder(this)
            .setTitle("🎯 Daily goal — ${prefs.pagesToday} / ${prefs.dailyPageGoal} today")
            .setSingleChoiceItems(labels, current) { d, i ->
                prefs.dailyPageGoal = goals[i]
                binding.goalRing.set(prefs.pagesToday, prefs.dailyPageGoal)
                com.pdfreader.app.widget.ReadingWidget.refresh(this)
                d.dismiss()
            }
            .setNeutralButton(remindLabel) { _, _ -> showReminderDialog() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showReminderDialog() {
        val opts = arrayOf("Off", "Pick a time…")
        AlertDialog.Builder(this).setTitle("Daily reading reminder")
            .setItems(opts) { _, i ->
                if (i == 0) {
                    prefs.goalReminderEnabled = false
                    com.pdfreader.app.worker.GoalReminderWorker.schedule(this)
                    Toast.makeText(this, "Reminder off", Toast.LENGTH_SHORT).show()
                } else {
                    val m = prefs.goalReminderMinutes
                    android.app.TimePickerDialog(this, { _, h, min ->
                        prefs.goalReminderMinutes = h * 60 + min
                        prefs.goalReminderEnabled = true
                        com.pdfreader.app.worker.GoalReminderWorker.schedule(this)
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        Toast.makeText(this, "🔔 I'll remind you at %02d:%02d if the goal isn't met".format(h, min),
                            Toast.LENGTH_LONG).show()
                    }, m / 60, m % 60, true).show()
                }
            }.show()
    }

    // ── Backup & restore (#13) ───────────────────────────────────────────────

    private fun showBackupDialog() {
        val backup = com.pdfreader.app.data.backup.BackupManager(this)
        val last = prefs.lastBackupAt
        val lastText = if (last > 0) "Last backup: " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(last))
                       else "No backup yet"
        AlertDialog.Builder(this)
            .setTitle("💾 Backup & restore")
            .setMessage("$lastText\n\nBacks up progress, markers, finished books, bookmarks, highlights & notes to your Google Drive. " +
                "A backup is also made automatically every week.\n\nRestoring merges — it never overwrites newer reading.")
            .setPositiveButton("Back up now") { _, _ ->
                Toast.makeText(this, "Backing up…", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    backup.backupNow().fold(
                        onSuccess = { Toast.makeText(this@HomeActivity, "✓ Backup saved to Drive", Toast.LENGTH_SHORT).show() },
                        onFailure = { Toast.makeText(this@HomeActivity, "Backup failed: ${it.message}", Toast.LENGTH_LONG).show() }
                    )
                }
            }
            .setNeutralButton("Restore…") { _, _ -> showRestoreList(backup) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRestoreList(backup: com.pdfreader.app.data.backup.BackupManager) {
        lifecycleScope.launch {
            val items = try { backup.list() } catch (e: Exception) { emptyList() }
            if (items.isEmpty()) {
                Toast.makeText(this@HomeActivity, "No backups found on Drive", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sdf = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
            val labels = items.map { "${sdf.format(Date(it.time))}   •   ${it.sizeBytes / 1024} KB" }.toTypedArray()
            AlertDialog.Builder(this@HomeActivity)
                .setTitle("Restore from backup")
                .setItems(labels) { _, i ->
                    AlertDialog.Builder(this@HomeActivity)
                        .setTitle("Restore ${labels[i].substringBefore("   ")}?")
                        .setMessage("Anything newer on this phone is kept. Restored items will sync to your other device.")
                        .setPositiveButton("Restore") { _, _ ->
                            lifecycleScope.launch {
                                backup.restore(items[i].fileId).fold(
                                    onSuccess = { r ->
                                        app.requestSync("restore")
                                        AlertDialog.Builder(this@HomeActivity).setTitle("✓ Restore complete")
                                            .setMessage("Books: ${r.progress}\nBookmarks: ${r.bookmarks}\n" +
                                                "Highlights & notes: ${r.highlights}\nCategories: ${r.categories}\n\n" +
                                                "(Items already up to date were left as they are.)")
                                            .setPositiveButton("OK", null).show()
                                        loadBooks()
                                    },
                                    onFailure = { Toast.makeText(this@HomeActivity, "Restore failed: ${it.message}", Toast.LENGTH_LONG).show() }
                                )
                            }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
                .setNegativeButton("Close", null).show()
        }
    }

    // ── Add a PDF: pick → (preview & edit | upload now) → Drive ──────────────

    private data class UploadStatus(val count: Int = 0, val progress: Int = -1, val name: String = "")

    private val uploadStatus = MutableStateFlow(UploadStatus())
    private val finishedUploads = mutableSetOf<String>()
    private var uploadsPrimed = false   // the first WorkManager emission only records old results silently

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) stagePickedPdf(uri)
    }

    /** The editor queues the upload itself and returns RESULT_OK; cancel returns RESULT_CANCELED. */
    private val editPdf = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) toastUploadQueued()
    }

    private fun setupAddButton() {
        binding.fabAdd.setOnClickListener {
            Log.d(TAG, "+ tapped → opening PDF picker")
            try {
                pickPdf.launch(arrayOf("application/pdf"))
            } catch (e: Exception) {
                Log.e(TAG, "Picker failed: ${e.message}")
                Toast.makeText(this, "Couldn't open the file picker: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Slide away while scrolling down the library, come back on scroll up
        binding.scrollContent.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, y, _, oldY ->
            if (y > oldY + 4) binding.fabAdd.hide() else if (y < oldY - 4) binding.fabAdd.show()
        })

        // Keep the button clear of the navigation bar (edge-to-edge on Android 15)
        val base = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.fabAdd) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = base + nav }
            insets
        }
    }

    /** Copies the picked PDF into private storage (picker URIs can't be seeked or read later). */
    private fun stagePickedPdf(uri: Uri) {
        lifecycleScope.launch {
            val busy = showBusyDialog("Reading PDF…")
            val staged = withContext(Dispatchers.IO) { runCatching { copyToStaging(uri) } }
            busy.dismiss()
            staged.fold(
                onSuccess = { (file, name) -> showAddChoiceDialog(file, name) },
                onFailure = { Toast.makeText(this@HomeActivity, it.message ?: "Couldn't read that file", Toast.LENGTH_LONG).show() }
            )
        }
    }

    private fun copyToStaging(uri: Uri): Pair<File, String> {
        val dir  = File(filesDir, "pending_uploads").apply { mkdirs() }
        val file = File(dir, UUID.randomUUID().toString() + ".pdf")
        try {
            val input = contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open that file")
            input.use { src -> FileOutputStream(file).use { out -> src.copyTo(out) } }
            // Must really be a PDF — the header sits within the first KB
            val head = ByteArray(1024)
            val n = file.inputStream().use { it.read(head) }
            if (n <= 0 || !String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF-"))
                throw IOException("That isn't a PDF file")
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        val raw = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Untitled"
        return file to cleanBookName(raw)
    }

    /** "Preview before uploading?" — with the book name editable right here. */
    private fun showAddChoiceDialog(file: File, name: String) {
        val view = DialogAddPdfBinding.inflate(layoutInflater)
        view.etName.setText(name)
        view.etName.setSelection(view.etName.length())
        view.tvMeta.text = "💾 %.1f MB".format(file.length() / 1_048_576f)
        fun chosenName() = cleanBookName(view.etName.text?.toString().orEmpty())

        var handedOff = false   // the editor / upload worker now owns the staged file
        AlertDialog.Builder(this)
            .setTitle("Add to your library")
            .setView(view.root)
            .setPositiveButton("Preview & edit") { _, _ ->
                handedOff = true
                editPdf.launch(Intent(this, PdfEditActivity::class.java)
                    .putExtra(PdfEditActivity.EXTRA_PATH, file.absolutePath)
                    .putExtra(PdfEditActivity.EXTRA_NAME, chosenName()))
            }
            .setNegativeButton("Upload now") { _, _ ->
                handedOff = true
                UploadWorker.enqueue(this, file, chosenName(), prefs.currentUser)
                toastUploadQueued()
            }
            .setNeutralButton("Cancel", null)
            .setOnDismissListener { if (!handedOff) file.delete() }
            .show()
    }

    private fun toastUploadQueued() {
        Toast.makeText(this,
            if (NetworkMonitor.online.value) "⬆ Uploading to your Drive…" else "Queued — will upload when online",
            Toast.LENGTH_SHORT).show()
    }

    private fun showBusyDialog(message: String): AlertDialog {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(android.widget.ProgressBar(this@HomeActivity))
            addView(android.widget.TextView(this@HomeActivity).apply {
                text = message; textSize = 15f; setPadding(pad, 0, 0, 0)
            })
        }
        return AlertDialog.Builder(this).setView(row).setCancelable(false).show()
    }

    private fun uploadingLabel(up: UploadStatus): String {
        val title = if (up.name.length > 22) up.name.take(21) + "…" else up.name
        val pct   = if (up.progress >= 0) " ${up.progress}%" else "…"
        val more  = if (up.count > 1) " (+${up.count - 1})" else ""
        return if (title.isEmpty()) "⬆ Uploading…$more" else "⬆ Uploading $title$pct$more"
    }

    /** Upload progress → status pill; refresh the library the moment a book lands on Drive. */
    private fun observeUploads() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkManager.getInstance(this@HomeActivity)
                    .getWorkInfosByTagFlow(UploadWorker.TAG_ALL)
                    .collectLatest { infos ->
                        var active = 0
                        var progress = -1
                        var name = ""
                        val done = mutableListOf<String>()
                        for (info in infos) {
                            val id = info.tags.firstOrNull { it.startsWith(UploadWorker.ID_PREFIX) }
                                ?.removePrefix(UploadWorker.ID_PREFIX) ?: continue
                            val title = info.tags.firstOrNull { it.startsWith(UploadWorker.NAME_PREFIX) }
                                ?.removePrefix(UploadWorker.NAME_PREFIX).orEmpty()
                            when (info.state) {
                                WorkInfo.State.RUNNING -> {
                                    active++
                                    progress = info.progress.getInt(UploadWorker.K_PROGRESS, 0)
                                    name = title
                                }
                                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                                    active++
                                    if (name.isEmpty()) name = title
                                }
                                WorkInfo.State.SUCCEEDED ->
                                    if (finishedUploads.add(id) && uploadsPrimed)
                                        done += info.outputData.getString(UploadWorker.K_NAME) ?: title
                                WorkInfo.State.FAILED ->
                                    if (finishedUploads.add(id) && uploadsPrimed) Toast.makeText(
                                        this@HomeActivity,
                                        "Upload failed: ${info.outputData.getString(UploadWorker.K_ERROR) ?: "unknown error"}",
                                        Toast.LENGTH_LONG).show()
                                else -> {}
                            }
                        }
                        uploadStatus.value = UploadStatus(active, progress, name)
                        uploadsPrimed = true
                        if (done.isNotEmpty()) {
                            Toast.makeText(this@HomeActivity,
                                "✓ ${done.joinToString { "“" + prettyTitle(it) + "”" }} added to your library",
                                Toast.LENGTH_LONG).show()
                            loadBooks()
                        }
                    }
            }
        }
    }

    // ── Header (greeting, collapse behaviour) ────────────────────────────────
    private var weekPages = 0

    private fun setupHeader() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
        binding.tvGreeting.text = "$part, ${prefs.currentUser}"
        updateHeaderSubtitle()

        // Show the compact title only once the header has collapsed
        binding.appBar.addOnOffsetChangedListener { bar, offset ->
            val collapsed = abs(offset) >= bar.totalScrollRange * 0.85f
            val title = if (collapsed) "${prefs.currentUser}'s Library" else ""
            if (supportActionBar?.title != title) supportActionBar?.title = title
            binding.headerContent.alpha = (1f - (abs(offset).toFloat() / bar.totalScrollRange.coerceAtLeast(1)) * 1.4f).coerceIn(0f, 1f)
        }
    }

    private fun updateHeaderSubtitle() {
        val parts = mutableListOf<String>()
        val reading = allBooks.count { (_, p) -> p != null && p.currentPage > 0 && !p.isFinished }
        if (reading > 0) parts += "$reading in progress"
        if (weekPages > 0) parts += "$weekPages pages this week"
        if (finishedCount > 0) parts += "$finishedCount finished"
        binding.tvHeaderSubtitle.text = if (parts.isEmpty()) "What are we reading today?" else parts.joinToString("  •  ")
    }

    // ── Skeleton while the first load runs ──────────────────────────────────
    private fun startSkeleton() {
        binding.layoutSkeleton.visibility = View.VISIBLE
        skeletonAnim = ObjectAnimator.ofFloat(binding.layoutSkeleton, View.ALPHA, 1f, 0.45f).apply {
            duration = 750; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
        }
    }

    private fun stopSkeleton() {
        if (binding.layoutSkeleton.visibility != View.VISIBLE) return
        skeletonAnim?.cancel(); skeletonAnim = null
        binding.layoutSkeleton.animate().alpha(0f).setDuration(180)
            .withEndAction { binding.layoutSkeleton.visibility = View.GONE }.start()
    }

    // ── Horizontal shelf rows ─────────────────────────────────────────────────
    private fun setupRows() {
        readingAdapter = CoverRowAdapter(
            onClick = { item -> allBooks.find { it.first.id == item.id }?.let { openBook(it.first, it.second) } },
            onLongClick = { item -> allBooks.find { it.first.id == item.id }?.let { showBookOptions(it.first) } }
        )
        binding.rvReading.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvReading.adapter = readingAdapter

        finishedRowAdapter = CoverRowAdapter(
            onClick = { startActivity(Intent(this, FinishedShelfActivity::class.java)) }
        )
        binding.rvFinishedBooks.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvFinishedBooks.adapter = finishedRowAdapter
        binding.tvFinishedHeader.setOnClickListener {
            startActivity(Intent(this, FinishedShelfActivity::class.java))
        }
    }

    private fun coverOf(book: Book): Any? = when {
        book.localThumbPath != null && File(book.localThumbPath).exists() -> File(book.localThumbPath)
        book.thumbnailUrl != null -> book.thumbnailUrl
        else -> null
    }

    private fun finishedCover(bookId: String): File? =
        progressRepo.archivedCoverFile(bookId).takeIf { it.exists() }
            ?: File(File(getExternalFilesDir(null), "thumbs"), "$bookId.png").takeIf { it.exists() }

    private fun updateReadingRow(active: List<Pair<Book, ReadingProgress?>>) {
        val heroId = prefs.lastOpenedBookId
        val items = active
            .filter { (b, p) -> p != null && p.currentPage > 0 && b.localPath != null && b.id != heroId }
            .sortedByDescending { it.second?.lastReadTimestamp ?: 0L }
            .take(12)
            .map { (b, p) -> CoverItem(b.id, b.displayName, "${p!!.percent}%", coverOf(b), p.percent) }
        readingAdapter.submitList(items)
        binding.sectionReading.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateFinishedEntry() {
        val sdf = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        finishedRowAdapter.submitList(finishedList.take(12).map { p ->
            CoverItem(p.bookId, com.pdfreader.app.data.model.prettyTitle(p.bookName.ifEmpty { "Book" }),
                sdf.format(Date(p.finishedAt)), finishedCover(p.bookId))
        })
        binding.sectionFinished.visibility = if (finishedCount > 0) View.VISIBLE else View.GONE
        binding.tvFinishedHeader.text = "See all ($finishedCount) ›"
    }

    // ── Search ────────────────────────────────────────────────────────────────
    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = false
            override fun onQueryTextChange(q: String?): Boolean {
                currentSearch = q?.trim() ?: ""
                applyFilters()
                return true
            }
        })
    }

    // ── Filters ───────────────────────────────────────────────────────────────
    private fun applyFilters() {
        // Finished books leave the library — they live on the Finished shelf
        var list = allBooks.filter { it.second?.isFinished != true }
        updateSmartCounts(list)
        list = list.filter { matches(smart, it) }
        if (currentSearch.isNotEmpty()) {
            list = list.filter { it.first.displayName.contains(currentSearch, ignoreCase = true) }
        }
        list = when (currentSort) {
            SortMode.NAME      -> list.sortedBy { it.first.displayName }
            SortMode.LAST_READ -> list.sortedByDescending { it.second?.lastReadTimestamp ?: 0L }
            SortMode.PROGRESS  -> list.sortedByDescending { it.second?.percent ?: 0 }
        }
        bookAdapter.submitList(list)
        binding.tvBookCount.text = "${list.size} book${if (list.size == 1) "" else "s"}"
        updateReadingRow(allBooks.filter { it.second?.isFinished != true })
        updateHeaderSubtitle()
        stopSkeleton()
        if (pendingOpenLast) {
            allBooks.find { it.first.id == prefs.lastOpenedBookId && it.first.localPath != null }?.let {
                pendingOpenLast = false
                openBook(it.first, it.second)
            }
        }
        if (list.isEmpty() && allBooks.isNotEmpty() && currentSearch.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.tvEmptySubtitle.text   = "All caught up — every book is on your Finished shelf 🏆"
        } else if (allBooks.isNotEmpty()) {
            binding.layoutEmpty.visibility = View.GONE
        }
        updateContinueReading()
    }

    private fun showSortDialog() {
        val options = arrayOf("📝 By Name", "🕐 Last Read", "📊 By Progress")
        AlertDialog.Builder(this)
            .setTitle("Sort Books")
            .setSingleChoiceItems(options, currentSort.ordinal) { d, which ->
                currentSort = SortMode.values()[which]; applyFilters(); d.dismiss()
            }.show()
    }

    // ── Recycler ──────────────────────────────────────────────────────────────
    private fun setupRecycler() {
        bookAdapter = BookAdapter(
            onReadClick        = { book, progress ->
                if (book.localPath == null) downloadBook(book) else openBook(book, progress)
            },
            onDownloadClick    = { book -> downloadBook(book) },
            onDeleteLocalClick = { book -> confirmDeleteLocal(book) },
            onCategoryClick    = { book -> showBookOptions(book) }
        )
        binding.rvBooks.apply {
            layoutManager = GridLayoutManager(this@HomeActivity, 2)
            adapter = bookAdapter
            setHasFixedSize(false)
            itemAnimator?.changeDuration = 150
            layoutAnimation = AnimationUtils.loadLayoutAnimation(this@HomeActivity, R.anim.layout_fade_up)
        }
    }

    private fun confirmDeleteLocal(book: Book) {
        AlertDialog.Builder(this)
            .setTitle("Delete local copy?")
            .setMessage("\"${book.name}\" will be removed from this device. Your progress, pin and bookmarks are kept.")
            .setPositiveButton("Delete") { _, _ ->
                driveRepo.deleteLocalPdf(book.id)
                Toast.makeText(this, "Local copy deleted", Toast.LENGTH_SHORT).show()
                loadBooks()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Long-press menu on a book card. */
    private fun showBookOptions(book: Book) {
        val progress = allBooks.find { it.first.id == book.id }?.second
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "ℹ Details" to { showBookDetailsSheet(book, progress) }
        actions += "🏷 Set category" to { showSetCategoryDialog(book) }
        actions += "✅ Move to Finished shelf" to {
            lifecycleScope.launch {
                progressRepo.setFinished(book.id, prefs.currentUser, book.name, true, progress?.totalPages ?: 0)
                Toast.makeText(this@HomeActivity, "Moved to Finished shelf", Toast.LENGTH_SHORT).show()
                if (book.localPath != null) {
                    AlertDialog.Builder(this@HomeActivity)
                        .setTitle("Delete the PDF too?")
                        .setMessage("The reading record stays on the Finished shelf.")
                        .setPositiveButton("Delete PDF") { _, _ -> driveRepo.deleteLocalPdf(book.id); loadBooks() }
                        .setNegativeButton("Keep", null).show()
                }
            }
            Unit
        }
        if (book.localPath != null) actions += "🗑 Delete local copy" to { confirmDeleteLocal(book) }

        AlertDialog.Builder(this)
            .setTitle(book.displayName)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    // ── Download queue (#10) ─────────────────────────────────────────────────

    /** Tap = queue (or cancel if already queued). Runs in WorkManager with live progress. */
    private fun downloadBook(book: Book) {
        if (bookAdapter.downloads.containsKey(book.id)) {
            DownloadWorker.cancel(this, book.id)
            Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show()
            return
        }
        DownloadWorker.enqueue(this, book.driveFileId, book.name, book.owner, book.thumbnailUrl)
        val msg = when {
            prefs.downloadWifiOnly && !isOnWifi() -> "Queued — will download on Wi-Fi"
            !NetworkMonitor.online.value          -> "Queued — will download when online"
            else                                  -> "Downloading ${book.displayName}…"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private val finishedDownloads = mutableSetOf<String>()

    private fun observeDownloads() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkManager.getInstance(this@HomeActivity)
                    .getWorkInfosByTagFlow(DownloadWorker.TAG_ALL)
                    .collectLatest { infos ->
                        val map = mutableMapOf<String, Int>()
                        var newlyDone = false
                        for (info in infos) {
                            val id = info.tags.firstOrNull { it.startsWith("dl:") }?.removePrefix("dl:") ?: continue
                            when (info.state) {
                                WorkInfo.State.RUNNING  -> map[id] = info.progress.getInt(DownloadWorker.K_PROGRESS, 0)
                                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> map[id] = -1
                                WorkInfo.State.SUCCEEDED -> if (finishedDownloads.add(id)) newlyDone = true
                                WorkInfo.State.FAILED -> if (finishedDownloads.add(id)) Toast.makeText(
                                    this@HomeActivity, "A download failed — tap ⬇ to retry", Toast.LENGTH_LONG).show()
                                else -> {}
                            }
                        }
                        bookAdapter.downloads = map
                        if (newlyDone) loadBooks()
                    }
            }
        }
        // Old finished work records would re-trigger; start from a clean slate
        WorkManager.getInstance(this).pruneWork()
    }

    private fun isOnWifi(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun openBook(book: Book, progress: ReadingProgress?) {
        if (book.localPath == null) { Toast.makeText(this, "Download the book first", Toast.LENGTH_SHORT).show(); return }
        if (isOpeningBook) return
        isOpeningBook = true
        prefs.lastOpenedBookId = book.id
        lifecycleScope.launch {
            try {
                // What THIS device had, before syncing
                val before = progressRepo.getProgressLocal(book.id, prefs.currentUser)
                // Pushes any pending local change first, then merges cloud (max ~3.5 s)
                val latest = progressRepo.getProgress(book.id, prefs.currentUser)

                // #12: if the other device moved the book, don't jump silently —
                // open where this device was and let the reader offer the jump.
                val movedRemotely = before != null && latest != null && before.currentPage > 0 &&
                        latest.currentPage != before.currentPage &&
                        latest.lastReadTimestamp > before.lastReadTimestamp
                val startPage = if (movedRemotely) before!!.currentPage
                                else latest?.currentPage ?: progress?.currentPage ?: 0

                val intent = (Intent(this@HomeActivity, ReaderActivity::class.java).apply {
                    putExtra("BOOK_ID",     book.id)
                    putExtra("BOOK_NAME",   book.name)
                    putExtra("OWNER",       book.owner)
                    putExtra("LOCAL_PATH",  book.localPath)
                    putExtra("START_PAGE",  startPage)
                    putExtra("TOTAL_PAGES", latest?.totalPages ?: progress?.totalPages ?: 0)
                    if (movedRemotely) putExtra("REMOTE_PAGE", latest!!.currentPage)
                })
                // UI 7: the page grows out of the cover you tapped
                val w = (96 * resources.displayMetrics.density).toInt()
                val h = (140 * resources.displayMetrics.density).toInt()
                val opts = androidx.core.app.ActivityOptionsCompat.makeScaleUpAnimation(
                    window.decorView, (lastTapX - w / 2).toInt(), (lastTapY - h / 2).toInt(), w, h)
                startActivity(intent, opts.toBundle())
            } finally {
                isOpeningBook = false
            }
        }
    }

    private fun showSetCategoryDialog(book: Book) {
        val categories = arrayOf("📖 Religion", "🔬 Science", "📚 Literature",
            "💼 Business", "🏥 Health", "🎨 Arts", "Other", "Uncategorized")
        AlertDialog.Builder(this)
            .setTitle("Set Category for\n${book.name}")
            .setItems(categories) { _, which ->
                lifecycleScope.launch {
                    progressRepo.setCategory(book.id, prefs.currentUser, categories[which])
                    Toast.makeText(this@HomeActivity, "Category: ${categories[which]}", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    // ── Load ──────────────────────────────────────────────────────────────────
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            app.requestSync("pull-to-refresh")
            loadBooks()
        }
        binding.swipeRefresh.setColorSchemeResources(R.color.accent)
    }

    private fun loadBooks() {
        binding.swipeRefresh.isRefreshing = binding.layoutSkeleton.visibility != View.VISIBLE
        binding.layoutEmpty.visibility    = View.GONE
        val user = prefs.currentUser

        lifecycleScope.launch {
            // Step 1: downloaded books — file IO + cover rendering OFF the main thread (was a jank source)
            val localBooks = withContext(Dispatchers.IO) {
                driveRepo.getAllDownloadedBooks(user).map { it to progressRepo.getProgressLocal(it.id, user) }
            }
            Log.d(TAG, "Local books found: ${localBooks.size} for user=$user")
            val firstShow = allBooks.isEmpty()
            if (localBooks.isNotEmpty()) {
                allBooks = localBooks
                applyFilters()
                if (firstShow) binding.rvBooks.scheduleLayoutAnimation()
            }

            // Step 2: offline path
            if (!NetworkMonitor.online.value) {
                binding.swipeRefresh.isRefreshing = false
                stopSkeleton()
                if (localBooks.isEmpty()) {
                    binding.layoutEmpty.visibility = View.VISIBLE
                    binding.tvEmptySubtitle.text   = "No internet. Connect to load your library."
                }
                return@launch
            }

            // Step 3: Drive book list
            driveRepo.getBooksForUser(user).fold(
                onSuccess = { driveBooks ->
                    binding.swipeRefresh.isRefreshing = false
                    stopSkeleton()
                    if (driveBooks.isEmpty()) {
                        if (localBooks.isEmpty()) {
                            binding.layoutEmpty.visibility = View.VISIBLE
                            binding.tvEmptySubtitle.text   =
                                "No books found in Drive.\nMake sure your Drive has:\nMy Drive › Books › $user › *.pdf"
                        }
                        return@fold
                    }
                    // Keep downloaded books even if they were removed from Drive
                    val driveIds = driveBooks.map { it.id }.toSet()
                    val merged = withContext(Dispatchers.IO) {
                        driveBooks.map { it to progressRepo.getProgressLocal(it.id, user) } +
                            localBooks.filter { it.first.id !in driveIds }
                    }
                    val wasEmpty = allBooks.isEmpty()
                    allBooks = merged
                    applyFilters()
                    if (wasEmpty) binding.rvBooks.scheduleLayoutAnimation()
                },
                onFailure = { e ->
                    Log.e(TAG, "Drive fetch failed: ${e.javaClass.simpleName}: ${e.message}")
                    binding.swipeRefresh.isRefreshing = false
                    stopSkeleton()
                    val msg = when {
                        e.message?.contains("Unable to resolve host") == true ||
                        e.message?.contains("failed to connect") == true       -> "Network error — check your connection"
                        e.message?.contains("401") == true ||
                        e.message?.contains("403") == true                     -> "Sign-in expired — please sign out and back in"
                        e.message?.contains("Books' folder not found") == true ->
                            "Drive folder not found.\nExpected: My Drive › Books › $user"
                        e.message?.contains("not found inside") == true        ->
                            "User folder not found.\nExpected: My Drive › Books › $user"
                        else -> "Could not load library: ${e.message}"
                    }
                    if (localBooks.isEmpty()) {
                        binding.layoutEmpty.visibility = View.VISIBLE
                        binding.tvEmptySubtitle.text   = msg
                    } else {
                        Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    // ── Book details bottom sheet ────────────────────────────────────────────
    private fun showBookDetailsSheet(book: Book, progress: ReadingProgress?) {
        val dialog = BottomSheetDialog(this)
        val sheet  = BottomSheetBookDetailsBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)

        val sdf   = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val hours = if (progress != null) progress.totalReadingTimeMs / 3_600_000f else 0f

        sheet.tvDetailTitle.text      = book.name
        sheet.tvDetailPages.text      = (progress?.currentPage ?: 0).toString()
        sheet.tvDetailHours.text      = "%.1f".format(hours)
        sheet.tvDetailProgress.text   = "${progress?.percent ?: 0}%"
        sheet.tvDetailTotalPages.text = "📄 Total pages: ${progress?.totalPages ?: "—"}"
        sheet.tvDetailFileSize.text   = "💾 File size: ${if (book.fileSize > 0) "%.1f MB".format(book.fileSize / 1_048_576f) else "Not downloaded"}"
        sheet.tvDetailLastRead.text   = "🕐 Last read: ${if (progress != null) sdf.format(Date(progress.lastReadTimestamp)) else "Never"}"
        sheet.tvDetailSyncStatus.text = "☁ Sync: ${if (progress?.isSynced == true) "✓ Synced" else if (progress != null) "⬆ Pending upload" else "—"}"
        sheet.tvDetailTitle.setOnLongClickListener { dialog.dismiss(); showSetCategoryDialog(book); true }
        dialog.show()
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────
    // Where the user last touched: the reader opens from that point (cover → page)
    private var lastTapX = 0f
    private var lastTapY = 0f
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) { lastTapX = ev.x; lastTapY = ev.y }
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_LAST, false)) {
            pendingOpenLast = true
            if (allBooks.isNotEmpty()) applyFilters()
        }
    }

    override fun onDestroy() {
        skeletonAnim?.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        isOpeningBook = false
        applyAutoNightMode()
        updateStreakBanner()
        refreshUpdateBanner()
        if (hasLoadedOnce) {
            loadBooks()
            loadWeeklySummary()
        }
        hasLoadedOnce = true
        binding.goalRing.set(prefs.pagesToday, prefs.dailyPageGoal)
        com.pdfreader.app.widget.ReadingWidget.refresh(this)
        // Push-then-pull (debounced). Replaces the old immediate pull that could race the reader's upload.
        app.requestSync("home-resume")
    }

    /** Shows the "update available" banner from the saved check, and the one-time "updated to X" message. */
    private fun refreshUpdateBanner() {
        val updates = com.pdfreader.app.update.UpdateRepository(this)
        val offer = updates.pendingOffer()
        if (offer == null) {
            binding.cardUpdateBanner.visibility = View.GONE
        } else {
            binding.tvUpdateBanner.text = getString(
                if (offer.required) R.string.upd_banner_required else R.string.upd_banner, offer.versionName
            )
            binding.cardUpdateBanner.setOnClickListener {
                startActivity(android.content.Intent(this, com.pdfreader.app.update.UpdateActivity::class.java))
            }
            binding.cardUpdateBanner.visibility = View.VISIBLE
        }
        updates.consumeUpdatedVersionName()?.let { name ->
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, getString(R.string.upd_updated_to, name), com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .show()
        }
    }

    // NOTE: no onDestroy → stopListening(). The Firebase listener belongs to the Application;
    // stopping it here silently killed real-time sync for the rest of the process.

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_home, menu)
        menu.findItem(R.id.action_wifi_only)?.isChecked = prefs.downloadWifiOnly
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh     -> { app.requestSync("menu-refresh"); loadBooks(); true }
        R.id.action_sort        -> { showSortDialog(); true }
        R.id.action_wifi_only   -> {
            prefs.downloadWifiOnly = !prefs.downloadWifiOnly
            item.isChecked = prefs.downloadWifiOnly
            Toast.makeText(this, if (prefs.downloadWifiOnly) "📶 New downloads wait for Wi-Fi" else "Downloads use any connection", Toast.LENGTH_SHORT).show()
            true
        }
        R.id.action_backup      -> { showBackupDialog(); true }
        R.id.action_finished    -> { startActivity(Intent(this, FinishedShelfActivity::class.java)); true }
        R.id.action_shelves     -> { startActivity(Intent(this, com.pdfreader.app.ui.shelves.ShelvesActivity::class.java)); true }
        R.id.action_stats       -> { startActivity(Intent(this, StatsActivity::class.java)); true }
        R.id.action_heatmap     -> { startActivity(Intent(this, com.pdfreader.app.ui.stats.HeatmapActivity::class.java)); true }
        R.id.action_sync_log    -> { startActivity(Intent(this, com.pdfreader.app.ui.stats.SyncLogActivity::class.java)); true }
        R.id.action_settings    -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_switch_user -> {
            prefs.currentUser = ""
            startActivity(Intent(this, LoginActivity::class.java)); finish(); true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
