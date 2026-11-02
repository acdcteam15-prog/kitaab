package com.pdfreader.app.ui.reader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pdfreader.app.data.model.Bookmark
import com.pdfreader.app.databinding.ItemBookmarkBinding
import java.text.SimpleDateFormat
import java.util.*

class BookmarkAdapter(
    private val onItemClick: (Bookmark) -> Unit,
    private val onItemLongClick: (Bookmark) -> Unit = {}
) : ListAdapter<Bookmark, BookmarkAdapter.VH>(Diff()) {

    private val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    inner class VH(val binding: ItemBookmarkBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(bookmark: Bookmark) {
            binding.tvBookmarkLabel.text = bookmark.label
            binding.tvBookmarkPage.text  = "Page ${bookmark.pageNumber + 1}"
            binding.tvBookmarkDate.text  = sdf.format(Date(bookmark.createdAt))
            binding.root.setOnClickListener { onItemClick(bookmark) }
            binding.root.setOnLongClickListener { onItemLongClick(bookmark); true }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemBookmarkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class Diff : DiffUtil.ItemCallback<Bookmark>() {
        override fun areItemsTheSame(a: Bookmark, b: Bookmark) = a.uid == b.uid
        override fun areContentsTheSame(a: Bookmark, b: Bookmark) = a == b
    }
}
