package com.clotube.app.workers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.clotube.app.auth.GoogleAuthRepository
import com.clotube.app.data.DownloadRepository
import com.clotube.app.data.DownloadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

@androidx.annotation.Keep
class VideoDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val repository = DownloadRepository(context)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val TAG = "VideoDownloadWorker"
        const val CHANNEL_ID = "clotube_downloads_channel"
        const val KEY_FILE_ID = "KEY_FILE_ID"
        const val KEY_TITLE = "KEY_TITLE"
        const val KEY_AUTH_TOKEN = "KEY_AUTH_TOKEN"
        private const val MIN_REQUIRED_FREE_BYTES = 2147483648L // 2.0 GB
        private const val MIN_VALID_DOWNLOAD_BYTES = 1024L
        private const val MAX_IN_WORKER_RETRIES = 12
        private const val NETWORK_WAIT_TIMEOUT_MS = 60_000L
        private const val WAKELOCK_TIMEOUT_MS = 4L * 60L * 60L * 1000L // 4 hours max safety timeout
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val fileId = inputData.getString(KEY_FILE_ID) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: "Video Download"
        val initialToken = inputData.getString(KEY_AUTH_TOKEN) ?: ""

        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: return@withContext Result.failure()
        if (!moviesDir.exists()) moviesDir.mkdirs()

        val targetFile = File(moviesDir, "$fileId.mp4")
        val tempFile = File(moviesDir, "$fileId.tmp")

        // If already fully downloaded on disk, mark complete immediately
        if (targetFile.exists() && targetFile.length() > MIN_VALID_DOWNLOAD_BYTES && !tempFile.exists()) {
            val size = targetFile.length()
            repository.setStatus(fileId, title, targetFile.absolutePath, DownloadStatus.COMPLETED, size)
            return@withContext Result.success()
        }

        val existingBytesOnStart = if (tempFile.exists()) tempFile.length() else 0L
        val requiredFreeBytes = (MIN_REQUIRED_FREE_BYTES - existingBytesOnStart).coerceAtLeast(256L * 1024L * 1024L)
        val stat = StatFs(moviesDir.path)
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        if (availableBytes < requiredFreeBytes) {
            repository.setStatus(fileId, title, "", DownloadStatus.FAILED)
            return@withContext Result.failure(workDataOf("error" to "Low storage space"))
        }

        createNotificationChannel()
        val notificationId = fileId.hashCode()
        try {
            setForeground(createForegroundInfo(title, 0, notificationId))
        } catch (e: Exception) {
            Log.w(TAG, "Background FGS promotion skipped (continuing via WorkManager): ${e.message}")
        }

        val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Clotube:VideoDownloadWakeLock_$fileId"
        )?.apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        val wifiLock = wifiManager?.createWifiLock(wifiLockMode, "Clotube:VideoDownloadWifiLock_$fileId")?.apply {
            setReferenceCounted(false)
            acquire()
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val downloadUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true&acknowledgeAbuse=true"
        var activeToken = initialToken
        var knownTotalBytes = 0L
        var attempt = 0

        try {
            while (attempt <= MAX_IN_WORKER_RETRIES) {
                if (isStopped) {
                    // Worker paused/preempted by OS: preserve tempFile for automatic resume
                    val currentBytes = if (tempFile.exists()) tempFile.length() else 0L
                    if (currentBytes > 0L) {
                        repository.updateProgress(fileId, currentBytes, knownTotalBytes)
                    }
                    return@withContext Result.retry()
                }

                if (!isNetworkConnected()) {
                    val currentBytes = if (tempFile.exists()) tempFile.length() else 0L
                    val pct = if (knownTotalBytes > 0L) ((currentBytes * 100) / knownTotalBytes).toInt() else 0
                    repository.updateProgress(fileId, currentBytes, knownTotalBytes)
                    notificationManager.notify(
                        notificationId,
                        buildNotification(title, pct, statusOverride = "Waiting for internet... ($pct%)")
                    )

                    val reconnected = waitForNetworkReconnect(NETWORK_WAIT_TIMEOUT_MS)
                    if (!reconnected) {
                        // Hand back to WorkManager's NetworkType.CONNECTED constraint; tempFile remains intact
                        Log.i(TAG, "Network still offline after ${NETWORK_WAIT_TIMEOUT_MS}ms; scheduling OS retry for $fileId at $currentBytes bytes")
                        return@withContext Result.retry()
                    }
                }

                if (activeToken.isBlank()) {
                    activeToken = resolveFreshToken(forceRefresh = false)
                }

                val resumeOffset = if (tempFile.exists() && tempFile.length() > 0L) tempFile.length() else 0L
                repository.updateProgress(fileId, resumeOffset, knownTotalBytes)

                fun buildRequest(token: String, byteOffset: Long): Request {
                    return Request.Builder()
                        .url(downloadUrl)
                        .header("User-Agent", "ClotubeDownloader/1.0")
                        .apply {
                            if (token.isNotBlank()) {
                                header("Authorization", "Bearer $token")
                            }
                            if (byteOffset > 0L) {
                                header("Range", "bytes=$byteOffset-")
                            }
                        }
                        .build()
                }

                try {
                    Log.i(TAG, "Starting download attempt #${attempt + 1} for $fileId from offset=$resumeOffset bytes")
                    var response = client.newCall(buildRequest(activeToken, resumeOffset)).execute()

                    if (response.code == 401) {
                        response.close()
                        val refreshed = resolveFreshToken(forceRefresh = true)
                        if (refreshed.isNotBlank()) {
                            activeToken = refreshed
                            response = client.newCall(buildRequest(activeToken, resumeOffset)).execute()
                        }
                    }

                    response.use { res ->
                        // HTTP 416 means resumeOffset is already >= remote file size
                        if (res.code == 416 && tempFile.exists() && tempFile.length() > MIN_VALID_DOWNLOAD_BYTES) {
                            return@withContext finalizeDownload(fileId, title, tempFile, targetFile, notificationId)
                        }

                        if (!res.isSuccessful && res.code != 206) {
                            if (res.code in listOf(408, 429, 500, 502, 503, 504)) {
                                Log.w(TAG, "Transient HTTP ${res.code} for $fileId at offset=$resumeOffset; retrying...")
                                attempt++
                                delay((2000L * attempt).coerceAtMost(15_000L))
                                return@use
                            }
                            Log.e(TAG, "Permanent HTTP failure (${res.code}) for $fileId")
                            if (tempFile.exists()) tempFile.delete()
                            repository.setStatus(fileId, title, "", DownloadStatus.FAILED)
                            return@withContext Result.failure(workDataOf("error" to "HTTP error ${res.code}"))
                        }

                        val contentType = res.header("Content-Type")?.lowercase().orEmpty()
                        if (contentType.startsWith("text/html") || contentType.startsWith("application/json")) {
                            Log.e(TAG, "Invalid non-media Content-Type ($contentType) for $fileId")
                            if (tempFile.exists()) tempFile.delete()
                            repository.setStatus(fileId, title, "", DownloadStatus.FAILED)
                            return@withContext Result.failure(workDataOf("error" to "Invalid media response: $contentType"))
                        }

                        val body = res.body ?: throw IOException("Empty response body")

                        val isPartialAppend = (res.code == 206 && resumeOffset > 0L)
                        var downloadedBytes = if (isPartialAppend) resumeOffset else 0L

                        knownTotalBytes = if (isPartialAppend) {
                            val contentRange = res.header("Content-Range").orEmpty()
                            val parsedTotal = contentRange.substringAfterLast("/", "").trim().toLongOrNull()
                            parsedTotal ?: if (body.contentLength() > 0L) resumeOffset + body.contentLength() else knownTotalBytes
                        } else {
                            if (body.contentLength() > 0L) body.contentLength() else knownTotalBytes
                        }

                        body.byteStream().use { inputStream ->
                            FileOutputStream(tempFile, isPartialAppend).use { outputStream ->
                                val buffer = ByteArray(64 * 1024)
                                var bytesRead: Int
                                var lastUpdateMs = System.currentTimeMillis()

                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                    if (isStopped) {
                                        outputStream.flush()
                                        repository.updateProgress(fileId, downloadedBytes, knownTotalBytes)
                                        return@withContext Result.retry()
                                    }

                                    outputStream.write(buffer, 0, bytesRead)
                                    downloadedBytes += bytesRead

                                    // Reset retry counter once healthy data is flowing
                                    if (attempt > 0) attempt = 0

                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdateMs > 800L) {
                                        lastUpdateMs = now
                                        val progressPercent = if (knownTotalBytes > 0L) {
                                            ((downloadedBytes * 100) / knownTotalBytes).toInt().coerceIn(0, 100)
                                        } else 0
                                        repository.updateProgress(fileId, downloadedBytes, knownTotalBytes)
                                        notificationManager.notify(
                                            notificationId,
                                            buildNotification(title, progressPercent)
                                        )
                                    }
                                }
                                outputStream.flush()
                            }
                        }

                        // Stream reached EOF cleanly
                        return@withContext finalizeDownload(fileId, title, tempFile, targetFile, notificationId)
                    }
                } catch (ioe: IOException) {
                    // Connection dropped mid-stream: preserve tempFile and resume from exact offset
                    attempt++
                    val savedSoFar = if (tempFile.exists()) tempFile.length() else 0L
                    val pct = if (knownTotalBytes > 0L) ((savedSoFar * 100) / knownTotalBytes).toInt().coerceIn(0, 100) else 0
                    Log.w(TAG, "Connection dropped for $fileId at $savedSoFar bytes (${ioe.message}). Preserving .tmp and resuming (attempt $attempt/$MAX_IN_WORKER_RETRIES)...")

                    repository.updateProgress(fileId, savedSoFar, knownTotalBytes)
                    notificationManager.notify(
                        notificationId,
                        buildNotification(title, pct, statusOverride = "Connection lost, resuming... ($pct%)")
                    )

                    if (attempt > MAX_IN_WORKER_RETRIES) {
                        Log.i(TAG, "Max in-worker retries reached for $fileId at $savedSoFar bytes; delegating to WorkManager retry")
                        return@withContext Result.retry()
                    }

                    val backoffMs = (2000L * attempt).coerceAtMost(10_000L)
                    delay(backoffMs)
                }
            }

            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error downloading for $fileId: ${e.message}", e)
            val savedSoFar = if (tempFile.exists()) tempFile.length() else 0L
            if (savedSoFar > 0L) {
                repository.updateProgress(fileId, savedSoFar, knownTotalBytes)
                Result.retry()
            } else {
                repository.setStatus(fileId, title, "", DownloadStatus.FAILED)
                Result.failure()
            }
        } finally {
            try {
                if (wifiLock?.isHeld == true) wifiLock.release()
            } catch (_: Exception) {}
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } catch (_: Exception) {}
        }
    }

    private suspend fun finalizeDownload(
        fileId: String,
        title: String,
        tempFile: File,
        targetFile: File,
        notificationId: Int
    ): Result {
        if (!tempFile.exists() || tempFile.length() <= MIN_VALID_DOWNLOAD_BYTES) {
            Log.e(TAG, "Temp file invalid on finalize for $fileId (${tempFile.length()} bytes)")
            if (tempFile.exists()) tempFile.delete()
            repository.setStatus(fileId, title, "", DownloadStatus.FAILED)
            return Result.failure()
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }

        return if (tempFile.renameTo(targetFile) && targetFile.exists() && targetFile.length() > MIN_VALID_DOWNLOAD_BYTES) {
            val finalSize = targetFile.length()
            Log.i(TAG, "Download completed for $fileId: ${targetFile.absolutePath} ($finalSize bytes)")
            repository.setStatus(fileId, title, targetFile.absolutePath, DownloadStatus.COMPLETED, finalSize)
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
            Log.e(TAG, "Failed to rename temp file for $fileId")
            repository.setStatus(fileId, title, "", DownloadStatus.FAILED)
            Result.failure()
        }
    }

    private suspend fun resolveFreshToken(forceRefresh: Boolean): String {
        return try {
            val authRepo = GoogleAuthRepository(context)
            if (forceRefresh) {
                authRepo.refreshAccessToken().getOrNull().orEmpty()
            } else {
                authRepo.getAccessToken().getOrNull().orEmpty()
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun isNetworkConnected(): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun waitForNetworkReconnect(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !isStopped) {
            if (isNetworkConnected()) {
                // Give DNS/routing 1.5s to stabilize after interface comes up
                delay(1500L)
                if (isNetworkConnected()) return true
            }
            delay(2000L)
        }
        return false
    }

    private fun createForegroundInfo(title: String, progress: Int, notificationId: Int): ForegroundInfo {
        val notification = buildNotification(title, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun buildNotification(
        title: String,
        progress: Int,
        statusOverride: String? = null
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading: $title")
            .setContentText(statusOverride ?: "$progress%")
            .setProgress(100, progress, progress == 0 && statusOverride == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
