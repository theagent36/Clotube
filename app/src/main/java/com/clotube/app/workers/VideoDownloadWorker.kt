package com.clotube.app.workers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.clotube.app.data.DownloadRepository
import com.clotube.app.data.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

@androidx.annotation.Keep
class VideoDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val repository = DownloadRepository(context)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "clotube_downloads_channel"
        const val KEY_FILE_ID = "KEY_FILE_ID"
        const val KEY_TITLE = "KEY_TITLE"
        const val KEY_AUTH_TOKEN = "KEY_AUTH_TOKEN"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val fileId = inputData.getString(KEY_FILE_ID) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Video Download"
        val authToken = inputData.getString(KEY_AUTH_TOKEN) ?: ""

        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: return@withContext Result.failure()
        val targetFile = File(moviesDir, "$fileId.mp4")
        val tempFile = File(moviesDir, "$fileId.tmp")

        // Pre-flight check: ensure at least 2.0 GB free (2147483648L)
        val stat = StatFs(moviesDir.path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        if (availableBytes < 2147483648L) {
            repository.setStatus(fileId, title, "", DownloadStatus.FAILED)
            return@withContext Result.failure(workDataOf("error" to "Low storage space"))
        }

        repository.setStatus(fileId, title, targetFile.absolutePath, DownloadStatus.DOWNLOADING)
        createNotificationChannel()
        val notificationId = fileId.hashCode()
        setForeground(createForegroundInfo(title, 0, notificationId))

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val downloadUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true&acknowledgeAbuse=true"
        val requestBuilder = Request.Builder().url(downloadUrl)

        val activeToken = if (authToken.isNotBlank()) {
            authToken
        } else {
            try {
                com.clotube.app.auth.GoogleAuthRepository(context).getAccessToken().getOrNull() ?: ""
            } catch (_: Exception) { "" }
        }

        if (activeToken.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $activeToken")
        }

        try {
            var response = client.newCall(requestBuilder.build()).execute()
            if (response.code == 401) {
                // Token may have expired, refresh once
                response.close()
                val freshToken = try {
                    com.clotube.app.auth.GoogleAuthRepository(context).refreshAccessToken().getOrNull()
                } catch (_: Exception) { null }

                if (!freshToken.isNullOrBlank()) {
                    val retryRequest = Request.Builder()
                        .url(downloadUrl)
                        .addHeader("Authorization", "Bearer $freshToken")
                        .build()
                    response = client.newCall(retryRequest).execute()
                }
            }

            if (!response.isSuccessful) {
                repository.setStatus(fileId, title, "", DownloadStatus.FAILED)
                return@withContext Result.failure(workDataOf("error" to "HTTP error ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure()
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            val inputStream: InputStream = body.byteStream()
            val outputStream = FileOutputStream(tempFile)
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var lastUpdateMs = System.currentTimeMillis()

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastUpdateMs > 800L) { // Throttle updates to UI and notification every 800ms
                    lastUpdateMs = now
                    val progressPercent = if (totalBytes > 0L) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                    repository.updateProgress(fileId, downloadedBytes, totalBytes)
                    notificationManager.notify(notificationId, buildNotification(title, progressPercent))
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            if (tempFile.renameTo(targetFile)) {
                repository.setStatus(fileId, title, targetFile.absolutePath, DownloadStatus.COMPLETED, totalBytes)
                notificationManager.notify(
                    notificationId,
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("Download Complete")
                        .setContentText(title)
                        .setAutoCancel(true)
                        .build()
                )
                Result.success()
            } else {
                repository.setStatus(fileId, title, "", DownloadStatus.FAILED)
                Result.failure()
            }
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            repository.setStatus(fileId, title, "", DownloadStatus.FAILED)
            Result.failure()
        }
    }

    private fun createForegroundInfo(title: String, progress: Int, notificationId: Int): ForegroundInfo {
        val notification = buildNotification(title, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun buildNotification(title: String, progress: Int): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading: $title")
            .setContentText("$progress%")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clotube Media Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Offline media downloads" }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
