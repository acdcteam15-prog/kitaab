package com.pdfreader.app.ui.finished

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.pdfreader.app.R
import com.pdfreader.app.data.model.ReadingProgress
import com.pdfreader.app.data.model.prettyTitle
import com.pdfreader.app.data.repository.DriveRepository
import com.pdfreader.app.data.repository.ProgressRepository
import com.pdfreader.app.databinding.ActivityFinishedShelfBinding
import com.pdfreader.app.databinding.ItemShelfBookBinding
import com.pdfreader.app.utils.PreferenceManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The Finished bookcase: a "Year in books" summary and your finished books standing on
 * shelves. Records live in Room + Drive, not in the PDF folder, so they survive deleting
 * the PDF on either device.
 */
class FinishedShelfActivity : com.pdfreader.app.ui.base.BaseActivity() {

    private lateinit var binding: ActivityFinishedShelfBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var repo: ProgressRepository
    private lateinit var driveRepo: DriveRepository
    private lateinit var adapter: ShelfAdapter

    private var all: List<ReadingProgress> = emptyList()
    private var year = Calendar.getInstance().get(Calendar.YEAR)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFinishedShelfBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs     = PreferenceManager(this)
        repo      = ProgressRepository(this)
        driveRepo = DriveRepository(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply { title = "🏆 Finished"; setDisplayHomeAsUpEnabled(true) }

        // Remember the best streak (the streak counter itself resets)
        prefs.bestStreak = maxOf(prefs.bestStreak, prefs.readingStreakDays)

        adapter = ShelfAdapter(
            coverFor = { id ->
                repo.archivedCoverFile(id).takeIf { it.exists() }
                    ?: File(File(getExternalFilesDir(null), "thumbs"), "$id.png").takeIf { it.exists() }
            },
            hasPdf  = { id -> driveRepo.isDownloaded(id) },
            onClick = { showOptions(it) }
        )
        binding.rvFinished.layoutManager = GridLayoutManager(this, 3)
        binding.rvFinished.adapter = adapter
        binding.rvFinished.addItemDecoration(ShelfDecoration(resources.displayMetrics.density))

        binding.tvYearPrev.setOnClickListener { changeYear(-1) }
        binding.tvYearNext.setOnClickListener { changeYear(+1) }

        lifecycleScope.launch {
            repo.getFinishedFlow(prefs.currentUser).collectLatest { list ->
                val first = all.isEmpty()
                all = list
                if (first && list.isNotEmpty() && yearOf(list.first().finishedAt) != year && booksIn(year).isEmpty())
                    year = yearOf(list.first().finishedAt)   // no books this year yet → show the latest year
                adapter.submitList(list)
                if (first) binding.rvFinished.scheduleLayoutAnimation()
                binding.layoutEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                renderYear()
            }
        }
    }

    // ── Year in books ────────────────────────────────────────────────────────

    private fun yearOf(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.YEAR)
    private fun monthOf(ts: Long) = Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.MONTH)
    private fun booksIn(y: Int) = all.filter { yearOf(it.finishedAt) == y }

    private fun changeYear(delta: Int) {
        val years = all.map { yearOf(it.finishedAt) }.toSet() + Calendar.getInstance().get(Calendar.YEAR)
        val target = year + delta
        if (target < (years.minOrNull() ?: year) || target > (years.maxOrNull() ?: year)) return
        year = target
        binding.cardYear.animate().alpha(0.4f).setDuration(90).withEndAction {
            renderYear()
            binding.cardYear.animate().alpha(1f).setDuration(160).start()
        }.start()
    }

    private fun renderYear() {
        val books = booksIn(year)
        binding.tvYearTitle.text = "$year in books"
        countUp(binding.tvYBooks, books.size)
        countUp(binding.tvYPages, books.sumOf { it.totalPages })
        countUp(binding.tvYHours, (books.sumOf { it.totalReadingTimeMs } / 3_600_000L).toInt())
        countUp(binding.tvYStreak, maxOf(prefs.bestStreak, prefs.readingStreakDays))

        // Books per month bars
        val perMonth = IntArray(12).also { m -> books.forEach { m[monthOf(it.finishedAt)]++ } }
        val maxCount = perMonth.maxOrNull()?.coerceAtLeast(1) ?: 1
        val d = resources.displayMetrics.density
        binding.layoutMonths.removeAllViews()
        binding.layoutMonthLabels.removeAllViews()
        val names = arrayOf("J","F","M","A","M","J","J","A","S","O","N","D")
        for (i in 0 until 12) {
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ((72 * d) * perMonth[i] / maxCount).toInt()
                    .coerceAtLeast((3 * d).toInt()), 1f).apply { marginStart = (3 * d).toInt(); marginEnd = (3 * d).toInt() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 4 * d
                    setColor(if (perMonth[i] > 0) themeColor(R.attr.kAccent) else 0x33FFFFFF)
                }
                pivotY = 72 * d
                scaleY = 0f
                animate().scaleY(1f).setStartDelay(i * 30L).setDuration(350).start()
            }
            binding.layoutMonths.addView(bar)
            binding.layoutMonthLabels.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER; text = names[i]; textSize = 10f
                setTextColor(0xB3FFFFFF.toInt())
            })
        }

        // Highlight line
        binding.tvYearHighlight.text = if (books.isEmpty()) "No books finished in $year yet — you've got this."
        else {
            val busiest = perMonth.indices.maxByOrNull { perMonth[it] } ?: 0
            val monthName = SimpleDateFormat("MMMM", Locale.getDefault())
                .format(Calendar.getInstance().apply { set(Calendar.MONTH, busiest); set(Calendar.DAY_OF_MONTH, 1) }.time)
            val longest = books.maxByOrNull { it.totalPages }
            buildString {
                append("📅 Busiest month: $monthName (${perMonth[busiest]})")
                if (longest != null) append("\n📚 Longest: ${prettyTitle(longest.bookName)} — ${longest.totalPages} pages")
            }
        }
    }

    private fun countUp(tv: TextView, target: Int) {
        android.animation.ValueAnimator.ofInt(0, target).apply {
            duration = 600
            addUpdateListener { tv.text = it.animatedValue.toString() }
            start()
        }
    }

    // ── Book options ─────────────────────────────────────────────────────────

    private fun showOptions(p: ReadingProgress) {
        val hasPdf = driveRepo.isDownloaded(p.bookId)
        val sdf = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val hours = p.totalReadingTimeMs / 3_600_000f
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "↩ Read again (back to library)" to {
            lifecycleScope.launch {
                repo.setFinished(p.bookId, p.ownerName, p.bookName, false, p.totalPages)
                Toast.makeText(this@FinishedShelfActivity, "Moved back to library", Toast.LENGTH_SHORT).show()
            }
            Unit
        }
        if (hasPdf) actions += "🗑 Delete PDF from this device (keep record)" to {
            driveRepo.deleteLocalPdf(p.bookId)
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "PDF deleted — record kept", Toast.LENGTH_SHORT).show()
        }
        actions += "✖ Delete this record permanently" to { confirmDeleteRecord(p) }

        AlertDialog.Builder(this)
            .setTitle(prettyTitle(p.bookName.ifEmpty { "Book" }))
            .setMessage("Finished ${sdf.format(Date(p.finishedAt))}  •  ${p.totalPages} pages  •  ${"%.1f".format(hours)} h")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun confirmDeleteRecord(p: ReadingProgress) {
        AlertDialog.Builder(this)
            .setTitle("Delete record?")
            .setMessage("Removes the reading history of \"${prettyTitle(p.bookName)}\" from both devices. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val ok = repo.deleteFinishedRecord(p.bookId, p.ownerName)
                    Toast.makeText(this@FinishedShelfActivity,
                        if (ok) "Record deleted" else "Needs internet to delete from both devices",
                        Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressedDispatcher.onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }
}

/** Book standing on the shelf. */
class ShelfAdapter(
    private val coverFor: (String) -> File?,
    private val hasPdf: (String) -> Boolean,
    private val onClick: (ReadingProgress) -> Unit
) : ListAdapter<ReadingProgress, ShelfAdapter.VH>(Diff()) {

    private val sdf = SimpleDateFormat("MMM yyyy", Locale.getDefault())

    inner class VH(val b: ItemShelfBookBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(p: ReadingProgress) {
            b.tvTitle.text = prettyTitle(p.bookName.ifEmpty { "Book" })
            b.tvFinishedDate.text = sdf.format(Date(p.finishedAt))
            b.tvRecordOnly.visibility = if (hasPdf(p.bookId)) View.GONE else View.VISIBLE
            Glide.with(b.root.context)
                .load(coverFor(p.bookId) ?: R.drawable.ic_pdf_placeholder)
                .placeholder(R.drawable.ic_pdf_placeholder)
                .centerCrop()
                .into(b.ivCover)
            b.root.setOnClickListener { onClick(p) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemShelfBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class Diff : DiffUtil.ItemCallback<ReadingProgress>() {
        override fun areItemsTheSame(a: ReadingProgress, b: ReadingProgress) = a.bookId == b.bookId
        override fun areContentsTheSame(a: ReadingProgress, b: ReadingProgress) = a == b
    }
}

/** Draws a wooden plank under each row of books. */
class ShelfDecoration(private val d: Float) : RecyclerView.ItemDecoration() {
    private val plank  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val span = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: 3
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION || pos % span != 0) continue
            // the plank sits just under the covers (card is 140dp, starts after 12dp padding)
            val top = child.top + child.translationY + (12 + 140) * d
            rect.set(4 * d, top, parent.width - 4 * d, top + 10 * d)
            plank.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                intArrayOf(0xFFB58152.toInt(), 0xFF8A5A33.toInt(), 0xFF6B4226.toInt()), null, Shader.TileMode.CLAMP)
            shadow.shader = LinearGradient(0f, rect.bottom, 0f, rect.bottom + 8 * d,
                0x55000000, 0x00000000, Shader.TileMode.CLAMP)
            c.drawRect(rect.left, rect.bottom, rect.right, rect.bottom + 8 * d, shadow)
            c.drawRoundRect(rect, 3 * d, 3 * d, plank)
        }
    }
}
