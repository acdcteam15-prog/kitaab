package com.pdfreader.app.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.pdfreader.app.data.repository.ProgressRepository
import com.pdfreader.app.utils.PreferenceManager
import java.util.concurrent.TimeUnit

/**
 * Upload retry worker. Runs only when the network is connected and performs a full
 * push-then-pull (so it can never download old data over un-pushed local changes).
 * Scheduled whenever an immediate push fails or the device is offline.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val owner = PreferenceManager(applicationContext).currentUser
        if (owner.isEmpty()) return Result.success()
        return try {
            Log.d(TAG, "Worker sync (push → pull)…")
            ProgressRepository(applicationContext).syncNow(owner, "SyncWorker")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker sync failed: ${e.message}")
            if (runAttemptCount < 10) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            // KEEP: data lives in Room, so one pending worker is enough; REPLACE would keep
            // cancelling a worker that is mid-upload.
            WorkManager.getInstance(context).enqueueUniqueWork("progress_push", ExistingWorkPolicy.KEEP, request)
            Log.d(TAG, "Push worker scheduled")
        }
    }
}
