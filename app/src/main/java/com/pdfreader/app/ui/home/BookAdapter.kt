package com.pdfreader.app.ui.home

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.pdfreader.app.R
import com.pdfreader.app.data.model.Book
import com.pdfreader.app.data.model.ReadingProgress
import com.pdfreader.app.databinding.ItemBookBinding
import java.io.File

class BookAdapter(
    private val onReadClick: (Book, ReadingProgress?) -> Unit,
    private val onDownloadClick: (Book) -> Unit,
    private val onDeleteLocalClick: (Book) -> Unit,
    private val onCategoryClick: (Book) -> Unit = {}
) : ListAdapter<Pair<Book, ReadingProgress?>, BookAdapter.VH>(Diff()) {

    init { setHasStableIds(true) }

    /** fileId → download progress (0–100), or -1 while waiting (e.g. for Wi-Fi). */
    var downloads: Map<String, Int> = emptyMap()
        set(v) {
            if (v == field) return
            val changed = (field.keys + v.keys).filter { field[it] != v[it] }.toSet()
            field = v
            currentList.forEachIndexed { i, item -> if (item.first.id in changed) notifyItemChanged(i) }
        }
    override fun getItemId(position: Int): Long = getItem(position).first.id.hashCode().toLong()

    inner class VH(val binding: ItemBookBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(book: Book, progress: ReadingProgress?) {
            binding.tvBookTitle.text = book.displayName
            val isDownloaded = book.localPath != null

            // Cover thumbnail: prefer locally rendered PNG → Drive thumbnail URL → placeholder
            val coverSource: Any = when {
                book.localThumbPath != null && File(book.localThumbPath).exists() -> File(book.localThumbPath)
                book.thumbnailUrl != null -> book.thumbnailUrl
                else -> R.drawable.ic_pdf_placeholder
            }
            Glide.with(binding.root.context)
                .load(coverSource)
                .placeholder(R.drawable.ic_pdf_placeholder)
                .transition(DrawableTransitionOptions.withCrossFade(180))
                .centerCrop()
                .into(binding.ivCover)

            val pct = progress?.percent ?: 0
            if (progress != null && progress.totalPages > 0) {
                binding.progressBar.setProgressCompat(pct, false)
                binding.tvProgressText.text  = "p. ${progress.currentPage + 1} / ${progress.totalPages}  •  $pct%"
                binding.progressBar.visibility = View.VISIBLE
            } else {
                binding.progressBar.visibility = View.INVISIBLE
                binding.tvProgressText.text = if (isDownloaded) "Not started" else "☁ In Drive"
            }

            val isFinished = progress?.isFinished == true
            binding.tvFinishedBadge.visibility = if (isFinished) View.VISIBLE else View.GONE
            binding.ivDownloaded.visibility    = if (isDownloaded && !isFinished) View.VISIBLE else View.GONE

            binding.btnRead.alpha     = if (isDownloaded) 1f else 0.6f
            binding.btnRead.text      = when {
                !isDownloaded -> "Download"
                progress != null && progress.currentPage > 0 -> "Continue ›"
                else -> "Start reading ›"
            }
            binding.btnRead.setOnClickListener { if (isDownloaded) onReadClick(book, progress) else onDownloadClick(book) }

            // Download in progress / queued
            val dl = downloads[book.id]
            if (!isDownloaded && dl != null) {
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.isIndeterminate = false
                binding.progressBar.setProgressCompat(dl.coerceAtLeast(0), true)
                binding.tvProgressText.text = if (dl < 0) "⏳ Waiting for Wi-Fi…" else "⬇ Downloading $dl%"
                binding.btnRead.text = "Cancel"
            }

            binding.btnDownload.visibility = if (isDownloaded || dl != null) View.GONE else View.VISIBLE
            binding.btnDownload.setOnClickListener { onDownloadClick(book) }

            binding.btnDeleteLocal.visibility = if (isDownloaded) View.VISIBLE else View.GONE
            binding.btnDeleteLocal.setOnClickListener { onDeleteLocalClick(book) }

            // Tap the card to open, long-press for options; subtle press-scale feedback
            binding.root.setOnClickListener { if (isDownloaded) onReadClick(book, progress) else onDownloadClick(book) }
            binding.root.setOnLongClickListener { onCategoryClick(book); true }
            binding.root.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                false
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (book, progress) = getItem(position)
        holder.bind(book, progress)
    }

    class Diff : DiffUtil.ItemCallback<Pair<Book, ReadingProgress?>>() {
        override fun areItemsTheSame(a: Pair<Book, ReadingProgress?>, b: Pair<Book, ReadingProgress?>) =
            a.first.id == b.first.id
        override fun areContentsTheSame(a: Pair<Book, ReadingProgress?>, b: Pair<Book, ReadingProgress?>) =
            a.first == b.first &&
            a.second?.currentPage == b.second?.currentPage &&
            a.second?.totalPages == b.second?.totalPages &&
            a.second?.finishedAt == b.second?.finishedAt
    }
}
