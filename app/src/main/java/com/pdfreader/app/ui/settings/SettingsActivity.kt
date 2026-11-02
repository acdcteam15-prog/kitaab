package com.pdfreader.app.ui.settings

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.MenuItem
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pdfreader.app.data.repository.ProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pdfreader.app.R
import com.pdfreader.app.databinding.ActivitySettingsBinding
import com.pdfreader.app.utils.PreferenceManager
import com.pdfreader.app.utils.WakeLockManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var wakeLock: WakeLockManager
    private lateinit var progressRepo: ProgressRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs        = PreferenceManager(this)
        wakeLock     = WakeLockManager(this)
        progressRepo = ProgressRepository(this)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = "Settings" }
        loadCurrentValues()
        setupListeners()
        binding.tvUpdatesSummary.text =
            getString(R.string.upd_menu_summary, com.pdfreader.app.update.UpdateRepository(this).installedName())
        binding.rowUpdates.setOnClickListener {
            startActivity(android.content.Intent(this, com.pdfreader.app.update.UpdateActivity::class.java))
        }
    }

    private fun loadCurrentValues() {
        binding.switchKeepScreenOn.isChecked   = prefs.keepScreenOn
        binding.switchLockRotation.isChecked   = prefs.lockRotation
        binding.switchAutoSync.isChecked       = prefs.autoSyncEnabled
        binding.switchShowPageNumber.isChecked = prefs.showPageNumber
        binding.switchReadingStats.isChecked   = prefs.enableReadingStats
        binding.switchAutoNightMode.isChecked  = prefs.autoNightMode
        binding.tvCurrentUser.text = "Profile: ${prefs.currentUser}"
        if (prefs.pageScrollMode == "HORIZONTAL") binding.rgScrollMode.check(R.id.rbHorizontal)
        else binding.rgScrollMode.check(R.id.rbVertical)
        val b = prefs.screenBrightness
        if (b < 0) { binding.seekbarBrightness.progress = 50; binding.tvBrightness.text = "Brightness: System Default" }
        else { val pct = (b * 100).toInt(); binding.seekbarBrightness.progress = pct; binding.tvBrightness.text = "Brightness: $pct%" }
        updateNightModeTimeDisplay()
    }

    private fun updateNightModeTimeDisplay() {
        val start = "%02d:00".format(prefs.nightModeStartHour)
        val end   = "%02d:00".format(prefs.nightModeEndHour)
        binding.tvNightModeTime.text   = "Dark theme between $start and $end — tap to change"
        binding.btnSetNightStart.text  = "🌙 Start $start"
        binding.btnSetNightEnd.text    = "☀️ End $end"
    }

    private fun setupListeners() {
        binding.switchKeepScreenOn.setOnCheckedChangeListener  { _, v -> prefs.keepScreenOn  = v }
        binding.switchLockRotation.setOnCheckedChangeListener  { _, v -> prefs.lockRotation  = v }
        binding.switchAutoSync.setOnCheckedChangeListener      { _, v -> prefs.autoSyncEnabled = v }
        binding.switchShowPageNumber.setOnCheckedChangeListener { _, v -> prefs.showPageNumber = v }
        binding.switchReadingStats.setOnCheckedChangeListener  { _, v -> prefs.enableReadingStats = v }
        binding.switchAutoNightMode.setOnCheckedChangeListener { _, v ->
            prefs.autoNightMode = v
            binding.layoutNightModeTime.visibility = if (v) android.view.View.VISIBLE else android.view.View.GONE
        }
        binding.layoutNightModeTime.visibility =
            if (prefs.autoNightMode) android.view.View.VISIBLE else android.view.View.GONE

        binding.btnSetNightStart.setOnClickListener {
            TimePickerDialog(this, { _, h, _ ->
                prefs.nightModeStartHour = h
                updateNightModeTimeDisplay()
            }, prefs.nightModeStartHour, 0, true).show()
        }

        binding.btnSetNightEnd.setOnClickListener {
            TimePickerDialog(this, { _, h, _ ->
                prefs.nightModeEndHour = h
                updateNightModeTimeDisplay()
            }, prefs.nightModeEndHour, 0, true).show()
        }

        binding.rgScrollMode.setOnCheckedChangeListener { _, id ->
            prefs.pageScrollMode = if (id == R.id.rbHorizontal) "HORIZONTAL" else "VERTICAL"
        }

        binding.seekbarBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) prefs.readerDim = 0f   // the slider replaces any extra night dimming
                if (progress == 0) { prefs.screenBrightness = -1f; binding.tvBrightness.text = "Brightness: System Default" }
                else { val v = progress / 100f; prefs.screenBrightness = v; binding.tvBrightness.text = "Brightness: $progress%"; wakeLock.applyBrightness(v) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.btnResetProgress.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Clear Local Progress?")
                .setMessage("Deletes local data for ${prefs.currentUser}. Cloud data stays.")
                .setPositiveButton("Clear") { _, _ ->
                    Toast.makeText(this, "Local progress cleared", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Cancel", null).show()
        }

        binding.btnClearWeekly.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Clear Weekly Summary?")
                .setMessage("Resets this week's reading stats for ${prefs.currentUser} on this device and Drive.")
                .setPositiveButton("Clear") { _, _ ->
                    prefs.clearWeeklySnapshot()
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            progressRepo.deleteWeeklySnapshotFromDrive(prefs.currentUser)
                        }
                    }
                    Toast.makeText(this, "Weekly summary cleared", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Cancel", null).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) onBackPressedDispatcher.onBackPressed()
        return super.onOptionsItemSelected(item)
    }
}
