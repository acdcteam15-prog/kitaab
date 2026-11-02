package com.pdfreader.app.ui.stats

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pdfreader.app.R
import com.pdfreader.app.data.local.AppDatabase
import com.pdfreader.app.databinding.ActivityHeatmapBinding
import com.pdfreader.app.utils.PreferenceManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HeatmapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHeatmapBinding
    private lateinit var prefs: PreferenceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHeatmapBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferenceManager(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply { title = "Reading Heatmap"; setDisplayHomeAsUpEnabled(true) }
        loadHeatmap()
    }

    private fun loadHeatmap() {
        lifecycleScope.launch {
            val dao       = AppDatabase.getInstance(this@HeatmapActivity).progressDao()
            val allProgress = dao.getAllProgressOnce(prefs.currentUser)

            // Build a map of epochDay → pages read that day
            // We approximate by counting the timestamp days where there was activity
            val readDays = mutableMapOf<Long, Int>()
            allProgress.forEach { p ->
                val day = p.lastReadTimestamp / 86_400_000L
                readDays[day] = (readDays[day] ?: 0) + 1
            }

            renderHeatmap(readDays)
        }
    }

    private fun renderHeatmap(readDays: Map<Long, Int>) {
        val today     = System.currentTimeMillis() / 86_400_000L
        val weeks     = 26  // show last 26 weeks (~6 months)
        val totalDays = weeks * 7

        // Start from the Monday of the week 26 weeks ago
        val startDay = today - totalDays + 1

        val accentColor = ContextCompat.getColor(this, R.color.accent)
        val bgColor     = ContextCompat.getColor(this, R.color.card_bg)

        binding.heatmapGrid.removeAllViews()
        binding.heatmapGrid.columnCount = weeks
        binding.heatmapGrid.rowCount    = 7

        val cellSize = resources.getDimensionPixelSize(R.dimen.heatmap_cell_size)
        val cellGap  = resources.getDimensionPixelSize(R.dimen.heatmap_cell_gap)

        val maxActivity = (readDays.values.maxOrNull() ?: 1).toFloat()

        for (dayOffset in 0 until totalDays) {
            val epochDay = startDay + dayOffset
            val col      = dayOffset / 7
            val row      = dayOffset % 7
            val activity = readDays[epochDay] ?: 0

            val cell = TextView(this).apply {
                val alpha = if (activity == 0) 0.08f else (0.3f + 0.7f * (activity / maxActivity))
                val color = Color.argb(
                    (alpha * 255).toInt(),
                    Color.red(accentColor),
                    Color.green(accentColor),
                    Color.blue(accentColor)
                )
                setBackgroundColor(color)
                val params = GridLayout.LayoutParams(
                    GridLayout.spec(row), GridLayout.spec(col)
                ).apply {
                    width  = cellSize
                    height = cellSize
                    setMargins(cellGap, cellGap, cellGap, cellGap)
                }
                layoutParams = params

                // Show day number for today
                if (epochDay == today) {
                    text = "·"
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 10f
                }

                // Tooltip on click
                setOnClickListener {
                    if (activity > 0) {
                        val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
                        val date = Date(epochDay * 86_400_000L)
                        android.widget.Toast.makeText(
                            this@HeatmapActivity,
                            "${sdf.format(date)}: $activity book(s) read",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            binding.heatmapGrid.addView(cell)
        }

        // Summary below grid
        val totalReadDays  = readDays.size
        val currentStreak  = prefs.readingStreakDays
        binding.tvHeatmapSummary.text =
            "📚 $totalReadDays days read in the last 6 months  •  🔥 $currentStreak day streak"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) onBackPressedDispatcher.onBackPressed()
        return super.onOptionsItemSelected(item)
    }
}
