package com.pdfreader.app

import android.app.Application
import android.util.Log
import com.pdfreader.app.data.repository.ProgressRepository
import com.pdfreader.app.sync.FirebaseSyncManager
import com.pdfreader.app.sync.NetworkMonitor
import com.pdfreader.app.utils.PreferenceManager
import com.pdfreader.app.worker.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class KitaabApplication : Application() {

    val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingSync: Job? = null

    override fun onCreate() {
        super.onCreate()
        // App-wide connectivity: every offline→online transition pushes local work FIRST
        NetworkMonitor.start(this) { requestSync("back-online") }
        startFirebaseListener()
        requestSync("startup")
        com.pdfreader.app.worker.GoalReminderWorker.schedule(this)
        maybeAutoBackup()
        com.pdfreader.app.update.UpdateWorker.schedule(this)
        maybeQuietUpdateCheck()
    }

    /** Looks for a new release at most every 6 hours when the app starts (no message when nothing is new). */
    private fun maybeQuietUpdateCheck() {
        val prefs = com.pdfreader.app.update.UpdatePrefs(this)
        if (!prefs.autoCheck) return
        if (System.currentTimeMillis() - prefs.lastCheckAt < 6 * 60 * 60 * 1000L) return
        appScope.launch {
            delay(8_000)
            com.pdfreader.app.update.UpdateRepository(this@KitaabApplication).check()
        }
    }

    /**
     * Debounced full sync (push → pull). Multiple triggers within 800 ms collapse into one.
     * All syncs are serialized by the repository's mutex, so this can never race.
     */
    fun requestSync(reason: String) {
        val owner = PreferenceManager(this).currentUser
        if (owner.isEmpty()) return
        pendingSync?.cancel()   // only cancels the waiting phase, never a running sync
        pendingSync = appScope.launch {
            delay(800)
            // Separate job: a later requestSync() must not cancel an upload in progress
            appScope.launch {
                try {
                    ProgressRepository(this@KitaabApplication).syncNow(owner, reason)
                } catch (e: Exception) {
                    Log.e(TAG, "Sync ($reason) failed: ${e.message} — scheduling worker")
                    SyncWorker.schedule(this@KitaabApplication)
                }
            }
        }
    }

    /** Weekly automatic backup to Drive (runs after the startup sync, only when online). */
    private fun maybeAutoBackup() {
        val prefs = PreferenceManager(this)
        if (prefs.currentUser.isEmpty()) return
        if (System.currentTimeMillis() - prefs.lastBackupAt < com.pdfreader.app.data.backup.BackupManager.AUTO_INTERVAL_MS) return
        appScope.launch {
            delay(20_000)   // let the startup sync finish first
            com.pdfreader.app.data.backup.BackupManager(this@KitaabApplication).backupNow()
                .onFailure { Log.d(TAG, "Auto backup skipped: ${it.message}") }
        }
    }

    /**
     * Firebase listener lives at application level and is NEVER stopped by an Activity.
     * When another device pushes, this device syncs within ~1-2 s.
     */
    private fun startFirebaseListener() {
        val ownerName = PreferenceManager(this).currentUser
        if (ownerName.isEmpty()) {
            Log.d(TAG, "No user yet — Firebase listener skipped")
            return
        }
        FirebaseSyncManager.startListeningWithDirectPull(
            context       = this,
            ownerName     = ownerName,
            lastKnownPush = FirebaseSyncManager.getLastKnownPush(this, ownerName),
            onPullNeeded  = { requestSync("firebase") }
        )
    }

    /** Called from LoginActivity after sign-in to (re)start the listener with the new user. */
    fun restartFirebaseListener() {
        FirebaseSyncManager.stopListening()
        startFirebaseListener()
        requestSync("login")
    }

    companion object { private const val TAG = "KitaabApp" }
}
