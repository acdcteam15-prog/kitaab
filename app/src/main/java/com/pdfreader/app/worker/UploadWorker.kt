package com.pdfreader.app.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.pdfreader.app.data.repository.DriveRepository
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Background PDF upload to My Drive › Books › <user>. Survives leaving the screen, waits for the
 * network and retries. The staged file lives in filesDir/pending_uploads until it's safely on Drive.
 * Progress is published as "p" (0–100).
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val path  = inputData.getString(K_PATH) ?: return Result.failure()
        val name  = inputData.getString(K_NAME) ?: "Untitled"
        val owner = inputData.getString(K_OWNER) ?: return Result.failure()
        val file  = File(path)
        if (!file.exists() || file.length() == 0L) {
            return Result.failure(workDataOf(K_ERROR to "The file to upload is missing"))
        }

        setProgress(workDataOf(K_PROGRESS to 0))
        var last = -1
        val result = DriveRepository(applicationContext).uploadPdf(file, name, owner) { pct ->
            if (pct != last) {
                last = pct
                setProgressAsync(workDataOf(K_PROGRESS to pct))
            }
        }
        return result.fold(
            onSuccess = {
                file.delete()
                Result.success(workDataOf(K_ID to it.id, K_NAME to it.name))
            },
            onFailure = { e ->
                Log.e(TAG, "Upload '$name' failed: ${e.javaClass.simpleName}: ${e.message}")
                // "Not signed in" will not fix itself by retrying
                if (e is IllegalStateException || runAttemptCount >= 4) {
                    file.delete()
                    Result.failure(workDataOf(K_ERROR to (e.message ?: "Upload failed")))
                } else Result.retry()
            }
        )
    }

    companion object {
        private const val TAG = "UploadWorker"
        const val TAG_ALL = "upload"
        const val NAME_PREFIX = "ulname:"
        const val ID_PREFIX = "ul:"
        const val K_PATH = "path"; const val K_NAME = "name"; const val K_OWNER = "owner"
        const val K_ID = "id"; const val K_PROGRESS = "p"; const val K_ERROR = "err"

        /** [file] must already be a private copy (the worker deletes it when done). */
        fun enqueue(context: Context, file: File, name: String, owner: String) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(K_PATH to file.absolutePath, K_NAME to name, K_OWNER to owner))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .addTag(TAG_ALL)
                .addTag(ID_PREFIX + file.nameWithoutExtension)
                .addTag(NAME_PREFIX + name)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("ul_${file.nameWithoutExtension}", ExistingWorkPolicy.KEEP, req)
        }
    }
}
