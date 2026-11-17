package com.clotube.app.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.clotube.app.data.DriveWatchHistorySync
import com.clotube.app.data.DriveWatchlistSync
import com.clotube.app.data.WatchlistManager
import com.clotube.app.data.playbackDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Android WorkManager CoroutineWorker ensuring cloud synchronization resilience across
 * process termination, swipe-to-kill, and background lifecycle transitions.
 *
 * Synchronizes both watch history (clotube_watch_history.json) and watchlist (clotube_watchlist.json)
 * on Google Drive inside an uninterrupted NonCancellable coroutine scope.
 */
class DriveSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(NonCancellable + Dispatchers.IO) {
        Log.i(TAG, "[DriveSyncWorker] Background sync worker started by Android OS...")

        try {
            // 1. Ensure WatchlistManager and local DataStore are initialized
            WatchlistManager.ensureLoaded(applicationContext)
            val dataStore = applicationContext.playbackDataStore

            // 2. Execute bidirectional Drive sync for watch history (clotube_watch_history.json)
            val historySync = DriveWatchHistorySync.getInstance(applicationContext)
            val historySuccess = try {
                historySync.executeDirectUpload(dataStore)
            } catch (e: Exception) {
                Log.w(TAG, "[DriveSyncWorker] Watch history sync execution error: ${e.message}")
                false
            }

            // 3. Execute bidirectional Drive sync for watchlist (clotube_watchlist.json)
            val watchlistSync = DriveWatchlistSync.getInstance(applicationContext)
            val watchlistSuccess = try {
                watchlistSync.syncBidirectional(force = true)
            } catch (e: Exception) {
                Log.w(TAG, "[DriveSyncWorker] Watchlist sync execution error: ${e.message}")
                false
            }

            Log.i(
                TAG,
                "[DriveSyncWorker] Background sync completed successfully! history=$historySuccess, watchlist=$watchlistSuccess"
            )
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "[DriveSyncWorker] Transient network error during background sync: ${e.message}", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "[DriveSyncWorker] Fatal error executing background worker: ${e.message}", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "ClotubeSync"
        const val KEY_ACCOUNT_NAME = "account_name"
        const val UNIQUE_WORK_NAME = "clotube_bg_cloud_sync"

        /**
         * Enqueues an expedited, persistent one-time work request to synchronize
         * watch history and watchlist changes to Google Drive even after app kill.
         */
        fun enqueueImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.i(TAG, "ClotubeSync: Enqueued WorkManager persistent background sync")
        }

        /**
         * Legacy backward-compatible invocation alias.
         */
        fun enqueue(context: Context) {
            enqueueImmediateSync(context)
        }
    }
}
