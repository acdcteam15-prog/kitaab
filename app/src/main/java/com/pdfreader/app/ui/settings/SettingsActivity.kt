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

class SettingsActivity : com.pdfreader.app.ui.base.BaseActivity() {

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

        // Moved here from the Home menu
        binding.switchWifiOnly.isChecked = prefs.downloadWifiOnly
        binding.switchWifiOnly.setOnCheckedChangeListener { _, v -> prefs.downloadWifiOnly = v }
        binding.rowBackup.setOnClickListener { BackupUi.show(this, prefs) }
        binding.rowSyncHistory.setOnClickListener {
            startActivity(android.content.Intent(this, com.pdfreader.app.ui.stats.SyncLogActivity::class.java))
        }
        binding.rowSwitchUser.setOnClickListener {
            prefs.currentUser = ""
            startActivity(
                android.content.Intent(this, com.pdfreader.app.ui.auth.LoginActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        buildThemePicker()
        setupAppearance()
    }

    /** System / Light / Dark. Themes.applyAppearance recreates every open screen in the new mode. */
    private fun setupAppearance() {
        binding.rgAppearance.check(
            when (prefs.appearance) { "light" -> R.id.rbAppLight; "dark" -> R.id.rbAppDark; else -> R.id.rbAppSystem }
        )
        binding.rgAppearance.setOnCheckedChangeListener { _, id ->
            val v = when (id) { R.id.rbAppLight -> "light"; R.id.rbAppDark -> "dark"; else -> "system" }
            if (prefs.appearance != v) {
                prefs.appearance = v
                com.pdfreader.app.ui.base.Themes.applyAppearance(this)
            }
        }
    }

    /** One tappable swatch per colour theme; the preview colours come from the theme itself. */
    private fun buildThemePicker() {
        val row = binding.themeRow
        row.removeAllViews()
        val selected = com.pdfreader.app.ui.base.Themes.currentKey(this)
        val d = resources.displayMetrics.density
        for (e in com.pdfreader.app.ui.base.Themes.all) {
            val preview = android.view.ContextThemeWrapper(this, R.style.Theme_PDFReader)
            if (e.overlay != 0) preview.theme.applyStyle(e.overlay, true)
            fun c(attr: Int) = com.pdfreader.app.ui.base.ThemeColors.get(preview, attr)

            val card = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(c(R.attr.kBackground))
                    setStroke(
                        ((if (e.key == selected) 3 else 1) * d).toInt(),
                        if (e.key == selected) c(R.attr.kAccent) else c(R.attr.kDivider)
                    )
                }
                isClickable = true
                isFocusable = true
                contentDescription = e.name
            }
            val dots = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            for (attr in intArrayOf(R.attr.kPrimary, R.attr.kBrand, R.attr.kAccent)) {
                dots.addView(android.view.View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(c(attr))
                        setStroke((1 * d).toInt(), c(R.attr.kDivider))
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()).apply {
                        marginStart = (2 * d).toInt(); marginEnd = (2 * d).toInt()
                    }
                })
            }
            card.addView(dots)
            card.addView(android.widget.TextView(this).apply {
                text = e.name
                textSize = 12f
                setTextColor(c(R.attr.kTextPrimary))
                setPadding(0, (6 * d).toInt(), 0, 0)
            })
            card.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (10 * d).toInt() }
            card.setOnClickListener {
                if (prefs.colorTheme != e.key) {
                    prefs.colorTheme = e.key
                    recreate()           // this screen comes back in the new colours; the others follow when shown
                }
            }
            row.addView(card)
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
