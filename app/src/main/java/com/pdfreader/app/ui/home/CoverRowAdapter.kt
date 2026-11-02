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
import com.pdfreader.app.databinding.ItemBookCoverBinding

/** One compact cover in a horizontal shelf row (Currently reading / Finished). */
data class CoverItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val cover: Any?,          // File, URL or null → placeholder
    val progress: Int? = null // 0..100, null = no bar
)

class CoverRowAdapter(
    private val onClick: (CoverItem) -> Unit,
    private val onLongClick: (CoverItem) -> Unit = {}
) : ListAdapter<CoverItem, CoverRowAdapter.VH>(Diff()) {

    inner class VH(val b: ItemBookCoverBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: CoverItem) {
            b.tvTitle.text    = item.title
            b.tvSubtitle.text = item.subtitle
            Glide.with(b.root.context)
                .load(item.cover ?: R.drawable.ic_pdf_placeholder)
                .placeholder(R.drawable.ic_pdf_placeholder)
                .transition(DrawableTransitionOptions.withCrossFade(180))
                .centerCrop()
                .into(b.ivCover)
            if (item.progress != null) {
                b.progress.visibility = View.VISIBLE
                b.progress.setProgressCompat(item.progress, false)
            } else b.progress.visibility = View.GONE

            b.root.setOnClickListener { onClick(item) }
            b.root.setOnLongClickListener { onLongClick(item); true }
            b.root.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
                false
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemBookCoverBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class Diff : DiffUtil.ItemCallback<CoverItem>() {
        override fun areItemsTheSame(a: CoverItem, b: CoverItem) = a.id == b.id
        override fun areContentsTheSame(a: CoverItem, b: CoverItem) = a == b
    }
}
