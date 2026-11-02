package com.pdfreader.app.ui.stats

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pdfreader.app.data.local.AppDatabase
import com.pdfreader.app.data.repository.DriveRepository
import com.pdfreader.app.databinding.ActivityStatsBinding
import com.pdfreader.app.utils.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding
    private lateinit var prefs: PreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferenceManager(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = "Reading Stats" }

        loadStats()
    }

    private fun loadStats() {
        val dao       = AppDatabase.getInstance(this).progressDao()
        val driveRepo = DriveRepository(this)

        lifecycleScope.launch {
            // Build a name map from local metadata (off the main thread — renders covers)
            val bookNameMap = withContext(Dispatchers.IO) {
                driveRepo.getAllDownloadedBooks(prefs.currentUser).associate { it.id to it.name }
            }
            dao.getAllProgressForOwner(prefs.currentUser).collectLatest { list ->
                if (list.isEmpty()) {
                    binding.tvStatsContent.text =
                        "No reading data yet.\nStart reading to see your stats!"
                    return@collectLatest
                }

                val totalPages    = list.sumOf { it.currentPage }
                val totalMs       = list.sumOf { it.totalReadingTimeMs }
                val totalMins     = TimeUnit.MILLISECONDS.toMinutes(totalMs)
                val booksFinished = list.count { it.isFinished }
                val avgPct = list.filter { it.totalPages > 0 }
                    .map { (it.currentPage.toFloat() / it.totalPages * 100).toInt() }
                    .average().let { if (it.isNaN()) 0 else it.toInt() }

                val sb = StringBuilder()
                sb.appendLine("👤  ${prefs.currentUser}'s Reading Stats")
                sb.appendLine()
                sb.appendLine("📚  Books tracked:        ${list.size}")
                sb.appendLine("✅  Books finished:       $booksFinished")
                sb.appendLine("📄  Total pages read:     $totalPages")
                sb.appendLine("⏱️  Total reading time:   $totalMins min")
                sb.appendLine("📊  Avg completion:       $avgPct%")
                sb.appendLine()
                sb.appendLine("─────────────────────────────")

                list.sortedByDescending { it.lastReadTimestamp }.forEach { p ->
                    // Use real name from metadata, fall back to shortened ID only if unknown
                    val name = bookNameMap[p.bookId]
                        ?: p.bookName.ifEmpty { p.bookId.take(12) + "…" }
                    val pct  = if (p.totalPages > 0)
                        (p.currentPage.toFloat() / p.totalPages * 100).toInt() else 0
                    val mins = TimeUnit.MILLISECONDS.toMinutes(p.totalReadingTimeMs)

                    sb.appendLine()
                    sb.appendLine(if (p.isFinished) "✅  $name" else "📖  $name")
                    sb.appendLine("    Page ${p.currentPage + 1} / ${p.totalPages}  ($pct%)")
                    sb.appendLine("    Time: $mins min")
                }

                binding.tvStatsContent.text = sb.toString()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) onBackPressedDispatcher.onBackPressed()
        return super.onOptionsItemSelected(item)
    }
}
