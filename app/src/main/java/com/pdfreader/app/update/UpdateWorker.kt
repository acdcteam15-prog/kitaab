package com.pdfreader.app.update

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Every 12 hours: look for a new release, pre-download it over Wi-Fi, and notify once per version. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = UpdatePrefs(applicationContext)
        if (!prefs.autoCheck) return Result.success()
        val repo = UpdateRepository(applicationContext)
        val result = repo.check()
        if (result is CheckResult.Available) {
            val info = result.info
            if (UpdateParser.shouldOffer(info, repo.installedCode(), prefs.skippedCode)) {
                var ready = repo.hasDownloaded(info)
                if (!ready && isUnmetered()) {
                    ready = try {
                        repo.download(info) { _, _ -> }
                        prefs.predownloadedCode = info.versionCode
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                if (prefs.notifiedCode != info.versionCode) {
                    UpdateNotifier.show(applicationContext, info, ready)
                    prefs.notifiedCode = info.versionCode
                }
            }
        }
        return Result.success()
    }

    private fun isUnmetered(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return !cm.isActiveNetworkMetered
    }

    companion object {
        private const val UNIQUE_NAME = "kitaab_update_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
