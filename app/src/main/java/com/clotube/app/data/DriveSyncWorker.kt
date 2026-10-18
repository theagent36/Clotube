package com.clotube.app.data

import android.content.Context
import android.util.Log
import androidx.work.*

class DriveSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d("ClotubeSync", "[DriveSyncWorker] Background sync worker started by Android OS...")
        return try {
            val sync = DriveWatchHistorySync(applicationContext)
            val success = sync.executeDirectUpload()
            if (success) {
                Log.i("ClotubeSync", "[DriveSyncWorker] Background sync completed successfully!")
                Result.success()
            } else {
                Log.w("ClotubeSync", "[DriveSyncWorker] Background sync failed, scheduling retry...")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("ClotubeSync", "[DriveSyncWorker] Error executing background worker: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        const val KEY_ACCOUNT_NAME = "account_name"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "ClotubeDriveBackgroundSync",
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
            Log.d("ClotubeSync", "Enqueued WorkManager persistent background sync")
        }
    }
}
