package com.pdfreader.app.ui.settings

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pdfreader.app.KitaabApplication
import com.pdfreader.app.data.backup.BackupManager
import com.pdfreader.app.utils.PreferenceManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The "Backup & restore" dialogs (they used to be in the Home menu). */
object BackupUi {

    fun show(activity: AppCompatActivity, prefs: PreferenceManager) {
        val backup = BackupManager(activity)
        val last = prefs.lastBackupAt
        val lastText = if (last > 0) "Last backup: " + SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(last))
                       else "No backup yet"
        AlertDialog.Builder(activity)
            .setTitle("Backup & restore")
            .setMessage("$lastText\n\nBacks up progress, markers, finished books, bookmarks, highlights & notes to your Google Drive. " +
                "A backup is also made automatically every week.\n\nRestoring merges - it never overwrites newer reading.")
            .setPositiveButton("Back up now") { _, _ ->
                Toast.makeText(activity, "Backing up...", Toast.LENGTH_SHORT).show()
                activity.lifecycleScope.launch {
                    backup.backupNow().fold(
                        onSuccess = { Toast.makeText(activity, "Backup saved to Drive", Toast.LENGTH_SHORT).show() },
                        onFailure = { Toast.makeText(activity, "Backup failed: ${it.message}", Toast.LENGTH_LONG).show() }
                    )
                }
            }
            .setNeutralButton("Restore...") { _, _ -> showRestoreList(activity, backup) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showRestoreList(activity: AppCompatActivity, backup: BackupManager) {
        activity.lifecycleScope.launch {
            val items = try { backup.list() } catch (e: Exception) { emptyList() }
            if (items.isEmpty()) {
                Toast.makeText(activity, "No backups found on Drive", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sdf = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
            val labels = items.map { "${sdf.format(Date(it.time))}   -   ${it.sizeBytes / 1024} KB" }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle("Restore from backup")
                .setItems(labels) { _, i ->
                    AlertDialog.Builder(activity)
                        .setTitle("Restore ${labels[i].substringBefore("   ")}?")
                        .setMessage("Anything newer on this phone is kept. Restored items will sync to your other device.")
                        .setPositiveButton("Restore") { _, _ ->
                            activity.lifecycleScope.launch {
                                backup.restore(items[i].fileId).fold(
                                    onSuccess = { r ->
                                        (activity.application as KitaabApplication).requestSync("restore")
                                        AlertDialog.Builder(activity).setTitle("Restore complete")
                                            .setMessage("Books: ${r.progress}\nBookmarks: ${r.bookmarks}\n" +
                                                "Highlights & notes: ${r.highlights}\nCategories: ${r.categories}\n\n" +
                                                "(Items already up to date were left as they are.)")
                                            .setPositiveButton("OK", null).show()
                                    },
                                    onFailure = { Toast.makeText(activity, "Restore failed: ${it.message}", Toast.LENGTH_LONG).show() }
                                )
                            }
                        }
                        .setNegativeButton("Cancel", null).show()
                }
                .setNegativeButton("Close", null).show()
        }
    }
}
