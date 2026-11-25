package com.clotube.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.clotube.app.workers.VideoDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

private val Context.downloadsDataStore by preferencesDataStore(name = "clotube_offline_downloads")

@androidx.annotation.Keep
enum class DownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED
}

@androidx.annotation.Keep
@androidx.compose.runtime.Immutable
data class OfflineMediaRecord(
    val fileId: String,
    val title: String,
    val localFilePath: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: DownloadStatus
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

@androidx.annotation.Keep
class DownloadRepository(private val context: Context) {

    companion object {
        private const val MIN_VALID_MEDIA_BYTES = 1024L
        private val titleCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    fun getDownloadRecord(fileId: String): Flow<OfflineMediaRecord?> {
        return context.downloadsDataStore.data.map { prefs ->
            val statusStr = prefs[stringPreferencesKey("${fileId}_status")] ?: return@map null
            val path = prefs[stringPreferencesKey("${fileId}_path")] ?: ""
            val total = prefs[longPreferencesKey("${fileId}_total")] ?: 0L
            val downloaded = prefs[longPreferencesKey("${fileId}_downloaded")] ?: 0L
            val title = prefs[stringPreferencesKey("${fileId}_title")] ?: ""
            if (title.isNotBlank()) titleCache[fileId] = title

            OfflineMediaRecord(
                fileId = fileId,
                title = title,
                localFilePath = path,
                totalBytes = total,
                downloadedBytes = downloaded,
                status = try { DownloadStatus.valueOf(statusStr) } catch (_: Exception) { DownloadStatus.FAILED }
            )
        }
    }

    fun getAllDownloadRecords(): Flow<Map<String, OfflineMediaRecord>> {
        return context.downloadsDataStore.data.map { prefs ->
            val result = HashMap<String, OfflineMediaRecord>()
            for (key in prefs.asMap().keys) {
                if (key.name.endsWith("_status")) {
                    val fileId = key.name.removeSuffix("_status")
                    val statusStr = prefs[stringPreferencesKey("${fileId}_status")] ?: continue
                    val status = try { DownloadStatus.valueOf(statusStr) } catch (_: Exception) { DownloadStatus.FAILED }
                    val recordTitle = prefs[stringPreferencesKey("${fileId}_title")] ?: ""
                    if (recordTitle.isNotBlank()) titleCache[fileId] = recordTitle
                    result[fileId] = OfflineMediaRecord(
                        fileId = fileId,
                        title = recordTitle,
                        localFilePath = prefs[stringPreferencesKey("${fileId}_path")] ?: "",
                        totalBytes = prefs[longPreferencesKey("${fileId}_total")] ?: 0L,
                        downloadedBytes = prefs[longPreferencesKey("${fileId}_downloaded")] ?: 0L,
                        status = status
                    )
                }
            }
            result
        }
    }

    fun getAllCompletedDownloads(): Flow<List<OfflineMediaRecord>> {
        return context.downloadsDataStore.data.map { prefs ->
            val allKeys = prefs.asMap().keys
            val fileIds = allKeys
                .filter { it.name.endsWith("_status") }
                .map { it.name.removeSuffix("_status") }

            fileIds.mapNotNull { fileId ->
                val statusStr = prefs[stringPreferencesKey("${fileId}_status")] ?: return@mapNotNull null
                val status = try { DownloadStatus.valueOf(statusStr) } catch (_: Exception) { DownloadStatus.FAILED }
                if (status != DownloadStatus.COMPLETED) return@mapNotNull null

                val path = prefs[stringPreferencesKey("${fileId}_path")] ?: ""
                val file = resolveValidFile(fileId, path) ?: return@mapNotNull null

                OfflineMediaRecord(
                    fileId = fileId,
                    title = prefs[stringPreferencesKey("${fileId}_title")] ?: "",
                    localFilePath = file.absolutePath,
                    totalBytes = prefs[longPreferencesKey("${fileId}_total")] ?: file.length(),
                    downloadedBytes = prefs[longPreferencesKey("${fileId}_downloaded")] ?: file.length(),
                    status = status
                )
            }
        }
    }

    suspend fun updateProgress(fileId: String, downloaded: Long, total: Long) = withContext(Dispatchers.IO) {
        context.downloadsDataStore.edit { prefs ->
            prefs[longPreferencesKey("${fileId}_downloaded")] = downloaded
            if (total > 0L) {
                prefs[longPreferencesKey("${fileId}_total")] = total
            }
            prefs[stringPreferencesKey("${fileId}_status")] = DownloadStatus.DOWNLOADING.name
        }
    }

    suspend fun setStatus(
        fileId: String,
        title: String,
        path: String,
        status: DownloadStatus,
        total: Long = 0L
    ) = withContext(Dispatchers.IO) {
        context.downloadsDataStore.edit { prefs ->
            prefs[stringPreferencesKey("${fileId}_status")] = status.name
            if (title.isNotBlank()) {
                prefs[stringPreferencesKey("${fileId}_title")] = title
            }
            prefs[stringPreferencesKey("${fileId}_path")] = path
            if (total > 0L) {
                prefs[longPreferencesKey("${fileId}_total")] = total
            }
            if (status == DownloadStatus.COMPLETED && total > 0L) {
                prefs[longPreferencesKey("${fileId}_downloaded")] = total
            }
        }
    }

    suspend fun enqueueDownload(
        fileId: String,
        title: String,
        authToken: String,
        replaceExisting: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val existingTempBytes = moviesDir?.let { File(it, "$fileId.tmp") }
            ?.takeIf { it.exists() }
            ?.length() ?: 0L

        context.downloadsDataStore.edit { prefs ->
            val currentStatus = prefs[stringPreferencesKey("${fileId}_status")]
            if (currentStatus != DownloadStatus.DOWNLOADING.name) {
                prefs[stringPreferencesKey("${fileId}_status")] = DownloadStatus.QUEUED.name
            }
            prefs[stringPreferencesKey("${fileId}_title")] = title
            if (existingTempBytes > 0L) {
                prefs[longPreferencesKey("${fileId}_downloaded")] = existingTempBytes
            }
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<VideoDownloadWorker>()
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10L,
                TimeUnit.SECONDS
            )
            .setInputData(
                workDataOf(
                    VideoDownloadWorker.KEY_FILE_ID to fileId,
                    VideoDownloadWorker.KEY_TITLE to title,
                    VideoDownloadWorker.KEY_AUTH_TOKEN to authToken
                )
            )
            .addTag("download_$fileId")
            .addTag("clotube_active_download")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "download_$fileId",
            if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    /**
     * Automatically resumes any interrupted downloads that still have a `.tmp` file on disk
     * or remain marked QUEUED/DOWNLOADING after process death or network outage.
     */
    suspend fun resumeAllInterruptedDownloads() = withContext(Dispatchers.IO) {
        val prefs = context.downloadsDataStore.data.firstOrNull() ?: return@withContext
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)

        val candidateIds = mutableSetOf<String>()
        for (key in prefs.asMap().keys) {
            if (key.name.endsWith("_status")) {
                val fileId = key.name.removeSuffix("_status")
                val statusStr = prefs[stringPreferencesKey("${fileId}_status")]
                if (statusStr == DownloadStatus.QUEUED.name || statusStr == DownloadStatus.DOWNLOADING.name) {
                    candidateIds.add(fileId)
                }
            }
        }

        moviesDir?.listFiles { file -> file.extension == "tmp" && file.length() > 0L }?.forEach { tmpFile ->
            val fileId = tmpFile.nameWithoutExtension
            if (fileId.isNotBlank()) {
                candidateIds.add(fileId)
            }
        }

        for (fileId in candidateIds) {
            val savedTitle = prefs[stringPreferencesKey("${fileId}_title")].orEmpty().ifBlank { "Video Download" }
            enqueueDownload(fileId = fileId, title = savedTitle, authToken = "", replaceExisting = false)
        }
    }

    suspend fun cancelDownload(fileId: String) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork("download_$fileId")
        deleteDownload(fileId)
    }

    suspend fun deleteDownload(fileId: String) = withContext(Dispatchers.IO) {
        titleCache.remove(fileId)
        var pathToDelete: String? = null
        context.downloadsDataStore.edit { prefs ->
            pathToDelete = prefs[stringPreferencesKey("${fileId}_path")]
            prefs.remove(stringPreferencesKey("${fileId}_status"))
            prefs.remove(stringPreferencesKey("${fileId}_title"))
            prefs.remove(stringPreferencesKey("${fileId}_path"))
            prefs.remove(longPreferencesKey("${fileId}_total"))
            prefs.remove(longPreferencesKey("${fileId}_downloaded"))
        }
        pathToDelete?.takeIf { it.isNotBlank() }?.let {
            val file = File(it)
            if (file.exists()) file.delete()
        }
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (moviesDir != null) {
            val mp4 = File(moviesDir, "$fileId.mp4")
            if (mp4.exists()) mp4.delete()
            val tmp = File(moviesDir, "$fileId.tmp")
            if (tmp.exists()) tmp.delete()
        }
    }

    fun getDownloadedTitleSync(fileId: String): String? {
        if (fileId.isBlank()) return null
        titleCache[fileId]?.let { return it }
        return try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                val title = context.downloadsDataStore.data.firstOrNull()?.get(stringPreferencesKey("${fileId}_title"))
                if (!title.isNullOrBlank()) {
                    titleCache[fileId] = title
                }
                title
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getLocalFileIfDownloaded(fileId: String): File? {
        if (fileId.isBlank()) return null
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return null
        val targetFile = File(moviesDir, "$fileId.mp4")
        return if (targetFile.exists() && targetFile.isFile && targetFile.canRead() && targetFile.length() > MIN_VALID_MEDIA_BYTES) {
            targetFile
        } else {
            null
        }
    }

    suspend fun resolveVerifiedOfflineFile(fileId: String): File? = withContext(Dispatchers.IO) {
        if (fileId.isBlank()) return@withContext null
        val prefs = context.downloadsDataStore.data.firstOrNull()
        val savedPath = prefs?.get(stringPreferencesKey("${fileId}_path")).orEmpty()
        val statusStr = prefs?.get(stringPreferencesKey("${fileId}_status"))

        val validFile = resolveValidFile(fileId, savedPath)
        if (validFile != null) {
            return@withContext validFile
        }

        if (statusStr == DownloadStatus.COMPLETED.name) {
            deleteDownload(fileId)
        }
        null
    }

    private fun resolveValidFile(fileId: String, savedPath: String): File? {
        if (savedPath.isNotBlank()) {
            val savedFile = File(savedPath)
            if (savedFile.exists() && savedFile.isFile && savedFile.canRead() && savedFile.length() > MIN_VALID_MEDIA_BYTES) {
                return savedFile
            }
        }
        return getLocalFileIfDownloaded(fileId)
    }

    @SuppressLint("BatteryLife")
    fun requestUnrestrictedBatteryIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
    }
}
