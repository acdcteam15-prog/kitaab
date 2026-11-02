package com.pdfreader.app.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.pdfreader.app.data.repository.DriveRepository
import com.pdfreader.app.utils.PreferenceManager
import java.util.concurrent.TimeUnit

/**
 * Background PDF download with progress. Survives leaving the screen, retries on network
 * errors, and can wait for Wi-Fi. Progress is published as "p" (0–100).
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val fileId = inputData.getString(K_ID) ?: return Result.failure()
        val name   = inputData.getString(K_NAME) ?: ""
        val owner  = inputData.getString(K_OWNER) ?: ""
        val thumb  = inputData.getString(K_THUMB)
        setProgress(workDataOf(K_PROGRESS to 0))
        var last = -1
        val result = DriveRepository(applicationContext).downloadPdf(fileId, name, owner, thumb) { pct ->
            if (pct != last) {
                last = pct
                setProgressAsync(workDataOf(K_PROGRESS to pct))
            }
        }
        return result.fold(
            onSuccess = { Result.success(workDataOf(K_ID to fileId)) },
            onFailure = {
                Log.e(TAG, "Download $name failed: ${it.message}")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        )
    }

    companion object {
        private const val TAG = "DownloadWorker"
        const val TAG_ALL   = "download"
        const val K_ID = "id"; const val K_NAME = "name"; const val K_OWNER = "owner"
        const val K_THUMB = "thumb"; const val K_PROGRESS = "p"

        fun idTag(fileId: String) = "dl:$fileId"

        fun enqueue(context: Context, fileId: String, name: String, owner: String, thumb: String?) {
            val wifiOnly = PreferenceManager(context).downloadWifiOnly
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(K_ID to fileId, K_NAME to name, K_OWNER to owner, K_THUMB to thumb))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .addTag(TAG_ALL)
                .addTag(idTag(fileId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("dl_$fileId", ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(context: Context, fileId: String) =
            WorkManager.getInstance(context).cancelUniqueWork("dl_$fileId")
    }
}
