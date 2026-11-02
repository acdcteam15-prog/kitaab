package com.pdfreader.app.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pdfreader.app.KitaabApplication
import com.pdfreader.app.R
import com.pdfreader.app.data.model.SyncEvent
import com.pdfreader.app.data.repository.ProgressRepository
import com.pdfreader.app.databinding.ActivitySyncLogBinding
import com.pdfreader.app.databinding.ItemSyncLogBinding
import com.pdfreader.app.utils.PreferenceManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Sync History: every push, pull, conflict and error, newest first, updating live.
 * Lets you see exactly what happened between the two devices.
 */
class SyncLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySyncLogBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var repo: ProgressRepository
    private val adapter = SyncEventAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySyncLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PreferenceManager(this)
        repo  = ProgressRepository(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply { title = "☁ Sync History"; setDisplayHomeAsUpEnabled(true) }

        binding.rvSyncLog.layoutManager = LinearLayoutManager(this)
        binding.rvSyncLog.adapter = adapter

        lifecycleScope.launch {
            repo.syncEventsFlow(prefs.currentUser).collectLatest { events ->
                adapter.submitList(events)
                supportActionBar?.subtitle = if (events.isEmpty()) "No sync activity yet" else "${events.size} events"
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Sync now").setIcon(android.R.drawable.ic_popup_sync)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 2, 1, "Clear history")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> onBackPressedDispatcher.onBackPressed()
            1 -> (application as KitaabApplication).requestSync("manual (history)")
            2 -> AlertDialog.Builder(this).setTitle("Clear sync history?")
                .setPositiveButton("Clear") { _, _ -> lifecycleScope.launch { repo.clearSyncHistory(prefs.currentUser) } }
                .setNegativeButton("Cancel", null).show()
        }
        return true
    }
}

class SyncEventAdapter : ListAdapter<SyncEvent, SyncEventAdapter.VH>(Diff()) {

    private val sdf = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

    inner class VH(val b: ItemSyncLogBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSyncLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = getItem(position)
        val ctx = holder.b.root.context
        val (label, color) = when (e.type) {
            "PUSH"     -> "⬆ Sent"       to ctx.getColor(R.color.brand)
            "PULL"     -> "⬇ Received"   to ctx.getColor(R.color.success)
            "CONFLICT" -> "⚖ Merged"     to ctx.getColor(R.color.accent_dark)
            "ERROR"    -> "⚠ Failed"     to ctx.getColor(android.R.color.holo_red_light)
            else       -> "↻ Sync"       to ctx.getColor(R.color.text_secondary)
        }
        holder.b.tvSyncBookName.text = e.bookName.ifEmpty { if (e.type == "ERROR") "Sync error" else "Library" }
            .removePrefix("Emailing ").replace('_', ' ')
        holder.b.tvSyncPage.text   = e.detail
        holder.b.tvSyncStatus.text = label
        holder.b.tvSyncStatus.setTextColor(color)
        holder.b.tvSyncTime.text   = sdf.format(Date(e.time))
    }

    class Diff : DiffUtil.ItemCallback<SyncEvent>() {
        override fun areItemsTheSame(a: SyncEvent, b: SyncEvent) = a.id == b.id
        override fun areContentsTheSame(a: SyncEvent, b: SyncEvent) = a == b
    }
}
