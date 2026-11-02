package com.pdfreader.app.ui.shelves

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.pdfreader.app.databinding.ActivityShelfDetailBinding
import com.pdfreader.app.data.repository.DriveRepository
import com.pdfreader.app.data.repository.ProgressRepository
import com.pdfreader.app.ui.home.BookAdapter
import com.pdfreader.app.ui.reader.ReaderActivity
import com.pdfreader.app.utils.PreferenceManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ShelfDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShelfDetailBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var progressRepo: ProgressRepository
    private lateinit var driveRepo: DriveRepository
    private lateinit var bookAdapter: BookAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShelfDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val shelfName = intent.getStringExtra("SHELF_NAME") ?: "Shelf"
        prefs         = PreferenceManager(this)
        progressRepo  = ProgressRepository(this)
        driveRepo     = DriveRepository(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply { title = shelfName; setDisplayHomeAsUpEnabled(true) }

        bookAdapter = BookAdapter(
            onReadClick = { book, progress ->
                if (book.localPath == null) {
                    android.widget.Toast.makeText(this, "Download the book first", android.widget.Toast.LENGTH_SHORT).show()
                    return@BookAdapter
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
            },
            onDownloadClick    = { },
            onDeleteLocalClick = { }
        )
        binding.rvShelfBooks.apply {
            layoutManager = GridLayoutManager(this@ShelfDetailActivity, 2)
            adapter = bookAdapter
        }

        lifecycleScope.launch {
            progressRepo.getCategoriesFlow(prefs.currentUser).collectLatest { categories ->
                val localBooks  = driveRepo.getAllDownloadedBooks(prefs.currentUser)
                val bookMap     = localBooks.associateBy { it.id }
                val shelfBookIds = categories
                    .filter { it.category == shelfName }
                    .map { it.bookId }
                    .toSet()

                val books = if (shelfName == "Uncategorized") {
                    val categorized = categories.map { it.bookId }.toSet()
                    localBooks.filter { it.id !in categorized }
                } else {
                    localBooks.filter { it.id in shelfBookIds }
                }

                val pairs = books.map { book ->
                    Pair(book, progressRepo.getProgressLocal(book.id, prefs.currentUser))
                }

                bookAdapter.submitList(pairs)
                binding.tvEmptyShelf.visibility =
                    if (pairs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) onBackPressedDispatcher.onBackPressed()
        return super.onOptionsItemSelected(item)
    }
}
