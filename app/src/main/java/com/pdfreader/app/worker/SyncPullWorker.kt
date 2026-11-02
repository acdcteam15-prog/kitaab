package com.pdfreader.app.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.pdfreader.app.data.repository.ProgressRepository
import com.pdfreader.app.sync.FirebaseSyncManager
import com.pdfreader.app.utils.PreferenceManager
import java.util.concurrent.TimeUnit

/** Periodic safety-net sync (push → pull). Real-time sync is handled by Firebase + NetworkMonitor. */
class SyncPullWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs     = PreferenceManager(applicationContext)
        val ownerName = prefs.currentUser
        if (ownerName.isEmpty()) return Result.success()

        return try {
            val updated = ProgressRepository(applicationContext).syncNow(ownerName, "SyncPullWorker")

            FirebaseSyncManager.fetchWeeklyData(ownerName) { weeklyJson, streakDays, lastReadDay ->
                if (weeklyJson != null) prefs.mergeWeeklySnapshot(weeklyJson)
                if (streakDays > prefs.readingStreakDays) {
                    prefs.readingStreakDays = streakDays
                    prefs.lastReadEpochDay  = lastReadDay
                }
            }
            Log.d(TAG, "Sync done ✓ — $updated book(s) updated")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}")
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "SyncPullWorker"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncPullWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "periodic_progress_pull", ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun scheduleImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncPullWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "immediate_progress_pull", ExistingWorkPolicy.KEEP, request
            )
        }
    }
}
