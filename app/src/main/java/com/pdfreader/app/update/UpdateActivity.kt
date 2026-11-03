package com.pdfreader.app.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pdfreader.app.R
import com.pdfreader.app.databinding.ActivityUpdateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Updates screen: version, release notes, check, skip, download and one-tap install. */
class UpdateActivity : com.pdfreader.app.ui.base.BaseActivity() {

    private lateinit var b: ActivityUpdateBinding
    private lateinit var repo: UpdateRepository
    private lateinit var prefs: UpdatePrefs

    /** The newest release we know about (may be the one that is already installed). */
    private var info: UpdateInfo? = null
    private var busy = false
    private var errorText = ""
    private var waitingForPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(b.root)
        repo = UpdateRepository(this)
        prefs = UpdatePrefs(this)

        setSupportActionBar(b.toolbar)
        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = getString(R.string.upd_title) }

        b.switchAutoCheck.isChecked = prefs.autoCheck
        b.switchAutoCheck.setOnCheckedChangeListener { _, on -> prefs.autoCheck = on }
        b.editToken.setText(prefs.token)
        b.btnSaveToken.setOnClickListener {
            prefs.token = b.editToken.text?.toString().orEmpty()
            Toast.makeText(this, R.string.upd_token_saved, Toast.LENGTH_SHORT).show()
            checkNow()
        }
        b.btnCheck.setOnClickListener { checkNow() }
        b.btnInstall.setOnClickListener { startInstall() }
        b.btnSkip.setOnClickListener {
            info?.let { prefs.skippedCode = it.versionCode }
            render()
        }
        b.btnAllow.setOnClickListener {
            waitingForPermission = true
            try {
                startActivity(UpdateInstaller.allowInstallIntent(this))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.upd_no_settings, Toast.LENGTH_LONG).show()
            }
        }

        info = repo.cachedUpdate()
        render()
        if (repo.isConfigured) checkNow()
    }

    override fun onResume() {
        super.onResume()
        val saved = prefs.lastInstallError
        if (saved.isNotEmpty()) {
            errorText = saved
            prefs.lastInstallError = ""
            render()
        }
        // Back from Android's "allow installing" page: carry on where the user stopped.
        if (waitingForPermission && UpdateInstaller.canInstall(this)) {
            waitingForPermission = false
            b.cardAllow.visibility = View.GONE
            startInstall()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun checkNow() {
        if (busy) return
        busy = true
        errorText = ""
        b.progress.isIndeterminate = true
        render()
        lifecycleScope.launch {
            when (val r = repo.check()) {
                is CheckResult.Available -> info = r.info
                is CheckResult.UpToDate -> info = repo.cachedUpdate()
                is CheckResult.Failed -> errorText = r.message
                is CheckResult.NotConfigured -> {}
            }
            busy = false
            render()
        }
    }

    private fun startInstall() {
        val i = info ?: return
        if (busy) return
        if (!UpdateInstaller.canInstall(this)) {
            // The one-time "Allow installing from Kitaab" step.
            waitingForPermission = true
            b.cardAllow.visibility = View.VISIBLE
            return
        }
        busy = true
        errorText = ""
        render()
        b.progress.isIndeterminate = false
        b.progress.progress = 0
        lifecycleScope.launch {
            try {
                var lastPercent = -1
                val file = repo.download(i) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != lastPercent) {
                        lastPercent = pct
                        runOnUiThread {
                            b.progress.progress = pct
                            b.tvProgress.text = getString(R.string.upd_downloading, pct)
                        }
                    }
                }
                b.tvProgress.text = getString(R.string.upd_installing)
                b.progress.isIndeterminate = true
                prefs.lastInstallError = ""
                UpdateNotifier.cancel(this@UpdateActivity)
                val started = withContext(Dispatchers.IO) { UpdateInstaller.install(this@UpdateActivity, file) }
                if (!started) {
                    // The session install could not start: use the plain system installer screen.
                    startActivity(UpdateInstaller.fallbackIntent(this@UpdateActivity, file))
                }
            } catch (e: ActivityNotFoundException) {
                errorText = getString(R.string.upd_no_installer)
            } catch (e: Exception) {
                errorText = e.message ?: getString(R.string.upd_failed)
            }
            busy = false
            render()
        }
    }

    private fun render() {
        val installed = repo.installedCode()
        val i = info
        val newer = i != null && UpdateParser.isNewer(i, installed)
        val skipped = i != null && !i.required && i.versionCode == prefs.skippedCode

        b.tvInstalled.text = getString(R.string.upd_installed, repo.installedName(), installed)

        // Headline
        b.tvHeadline.text = when {
            !repo.isConfigured -> getString(R.string.upd_not_configured)
            busy && i == null -> getString(R.string.upd_checking)
            newer -> getString(R.string.upd_available, i!!.versionName)
            else -> getString(R.string.upd_up_to_date)
        }
        b.tvRequired.visibility = if (newer && i!!.required) View.VISIBLE else View.GONE
        b.tvSkipped.visibility = if (newer && skipped) View.VISIBLE else View.GONE

        // Release notes: of the new version, or of the version that was just installed
        val showNotes = i != null && (newer || i.versionCode == installed) && i.notes.isNotEmpty()
        b.cardNotes.visibility = if (showNotes) View.VISIBLE else View.GONE
        if (showNotes && i != null) {
            b.tvNotesTitle.text = getString(if (newer) R.string.upd_whats_new else R.string.upd_whats_in_this)
            b.tvNotesMeta.text = listOf(
                i.versionName,
                i.publishedAt.take(10),
                UpdateParser.formatSize(i.apk.size)
            ).filter { it.isNotEmpty() }.joinToString("  \u00B7  ")
            b.tvNotes.text = i.notes
        }

        // Buttons
        b.btnInstall.visibility = if (newer) View.VISIBLE else View.GONE
        b.btnInstall.isEnabled = !busy
        b.btnSkip.visibility = if (newer && !i!!.required && !skipped) View.VISIBLE else View.GONE
        b.btnSkip.isEnabled = !busy
        b.btnCheck.visibility = if (repo.isConfigured) View.VISIBLE else View.GONE
        b.btnCheck.isEnabled = !busy

        // Progress
        b.progress.visibility = if (busy) View.VISIBLE else View.GONE
        b.tvProgress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy && b.progress.isIndeterminate) b.tvProgress.text = getString(R.string.upd_checking)

        // Error
        b.tvError.visibility = if (errorText.isNotEmpty()) View.VISIBLE else View.GONE
        b.tvError.text = errorText
    }
}
