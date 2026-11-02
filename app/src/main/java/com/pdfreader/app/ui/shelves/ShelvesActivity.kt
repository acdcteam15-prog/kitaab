package com.pdfreader.app.ui.shelves

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pdfreader.app.R
import com.pdfreader.app.data.model.Book
import com.pdfreader.app.data.model.ReadingProgress
import com.pdfreader.app.data.repository.DriveRepository
import com.pdfreader.app.data.repository.ProgressRepository
import com.pdfreader.app.databinding.ActivityShelvesBinding
import com.pdfreader.app.ui.reader.ReaderActivity
import com.pdfreader.app.utils.PreferenceManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ShelvesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShelvesBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var progressRepo: ProgressRepository
    private lateinit var driveRepo: DriveRepository

    // shelf name → list of (book, progress) pairs
    private var shelfMap: Map<String, List<Pair<Book, ReadingProgress?>>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShelvesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs        = PreferenceManager(this)
        progressRepo = ProgressRepository(this)
        driveRepo    = DriveRepository(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply { title = "My Shelves"; setDisplayHomeAsUpEnabled(true) }

        binding.fabAddShelf.setOnClickListener { showCreateShelfDialog() }

        setupShelfList()
    }

    private fun setupShelfList() {
        lifecycleScope.launch {
            progressRepo.getCategoriesFlow(prefs.currentUser).collectLatest { categories ->
                // Build shelf → books map
                val localBooks = driveRepo.getAllDownloadedBooks(prefs.currentUser)
                val bookMap    = localBooks.associateBy { it.id }

                val grouped = mutableMapOf<String, MutableList<Pair<Book, ReadingProgress?>>>()

                categories.forEach { cat ->
                    val book     = bookMap[cat.bookId] ?: return@forEach
                    val progress = progressRepo.getProgressLocal(cat.bookId, prefs.currentUser)
                    grouped.getOrPut(cat.category) { mutableListOf() }
                        .add(Pair(book, progress))
                }

                // Also add an "Uncategorized" shelf for books with no category
                val categorizedIds = categories.map { it.bookId }.toSet()
                val uncategorized  = localBooks
                    .filter { it.id !in categorizedIds }
                    .map { book ->
                        Pair(book, progressRepo.getProgressLocal(book.id, prefs.currentUser))
                    }
                if (uncategorized.isNotEmpty()) {
                    grouped["Uncategorized"] = uncategorized.toMutableList()
                }

                shelfMap = grouped

                val adapter = ShelfListAdapter(
                    shelves  = grouped.keys.toList().sorted(),
                    shelfMap = grouped,
                    onShelfClick  = { shelfName -> openShelf(shelfName, grouped[shelfName] ?: emptyList()) },
                    onShelfRename = { shelfName -> showRenameShelfDialog(shelfName) },
                    onBookClick   = { book, progress -> openBook(book, progress) }
                )
                binding.rvShelves.layoutManager = LinearLayoutManager(this@ShelvesActivity)
                binding.rvShelves.adapter = adapter

                binding.tvEmptyShelves.visibility =
                    if (grouped.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun openShelf(shelfName: String, books: List<Pair<Book, ReadingProgress?>>) {
        val intent = Intent(this, ShelfDetailActivity::class.java).apply {
            putExtra("SHELF_NAME", shelfName)
        }
        startActivity(intent)
    }

    private fun openBook(book: Book, progress: ReadingProgress?) {
        if (book.localPath == null) {
            android.widget.Toast.makeText(this, "Download the book first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        prefs.lastOpenedBookId = book.id
        startActivity(Intent(this, ReaderActivity::class.java).apply {
            putExtra("BOOK_ID",     book.id)
            putExtra("BOOK_NAME",   book.name)
            putExtra("OWNER",       book.owner)
            putExtra("LOCAL_PATH",  book.localPath)
            putExtra("START_PAGE",  progress?.currentPage ?: 0)
            putExtra("TOTAL_PAGES", progress?.totalPages  ?: 0)
        })
    }

    private fun showCreateShelfDialog() {
        val input = EditText(this).apply {
            hint = "Shelf name (e.g. Religion, Science…)"
            setPadding(48, 16, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("New Shelf")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    android.widget.Toast.makeText(this,
                        "Shelf \"$name\" created. Long-press a book to assign it.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameShelfDialog(oldName: String) {
        if (oldName == "Uncategorized") {
            android.widget.Toast.makeText(this, "Cannot rename Uncategorized", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            setText(oldName)
            setPadding(48, 16, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Shelf")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != oldName) {
                    lifecycleScope.launch {
                        // Re-assign all books in old shelf to new name
                        val books = shelfMap[oldName] ?: return@launch
                        books.forEach { (book, _) ->
                            progressRepo.setCategory(book.id, prefs.currentUser, newName)
                        }
                        android.widget.Toast.makeText(this@ShelvesActivity,
                            "Renamed to \"$newName\"", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) onBackPressedDispatcher.onBackPressed()
        return super.onOptionsItemSelected(item)
    }
}

// ── Shelf list adapter ────────────────────────────────────────────────────────

class ShelfListAdapter(
    private val shelves: List<String>,
    private val shelfMap: Map<String, List<Pair<Book, ReadingProgress?>>>,
    private val onShelfClick: (String) -> Unit,
    private val onShelfRename: (String) -> Unit,
    private val onBookClick: (Book, ReadingProgress?) -> Unit
) : RecyclerView.Adapter<ShelfListAdapter.VH>() {

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName:    TextView     = itemView.findViewById(R.id.tvShelfName)
        val tvCount:   TextView     = itemView.findViewById(R.id.tvShelfCount)
        val tvPreview: TextView     = itemView.findViewById(R.id.tvShelfPreview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shelf, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val name  = shelves[position]
        val books = shelfMap[name] ?: emptyList()

        holder.tvName.text  = name
        holder.tvCount.text = "${books.size} book${if (books.size != 1) "s" else ""}"
        holder.tvPreview.text = books.take(3).joinToString(" • ") { it.first.name }
            .ifEmpty { "No books yet" }

        holder.itemView.setOnClickListener { onShelfClick(name) }
        holder.itemView.setOnLongClickListener { onShelfRename(name); true }
    }

    override fun getItemCount() = shelves.size
}
