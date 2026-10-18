package com.clotube.app.data

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private val Context.downloadsDataStore by preferencesDataStore(name = "clotube_offline_downloads")

enum class DownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED
}

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

class DownloadRepository(private val context: Context) {

    fun getDownloadRecord(fileId: String): Flow<OfflineMediaRecord?> {
        return context.downloadsDataStore.data.map { prefs ->
            val statusStr = prefs[stringPreferencesKey("${fileId}_status")] ?: return@map null
            val path = prefs[stringPreferencesKey("${fileId}_path")] ?: ""
            val total = prefs[longPreferencesKey("${fileId}_total")] ?: 0L
            val downloaded = prefs[longPreferencesKey("${fileId}_downloaded")] ?: 0L
            val title = prefs[stringPreferencesKey("${fileId}_title")] ?: ""

            OfflineMediaRecord(
                fileId = fileId,
                title = title,
                localFilePath = path,
                totalBytes = total,
                downloadedBytes = downloaded,
                status = try { DownloadStatus.valueOf(statusStr) } catch (e: Exception) { DownloadStatus.FAILED }
            )
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
                val status = try { DownloadStatus.valueOf(statusStr) } catch (e: Exception) { DownloadStatus.FAILED }
                if (status != DownloadStatus.COMPLETED) return@mapNotNull null

                val path = prefs[stringPreferencesKey("${fileId}_path")] ?: ""
                val file = File(path)
                if (!file.exists()) return@mapNotNull null

                OfflineMediaRecord(
                    fileId = fileId,
                    title = prefs[stringPreferencesKey("${fileId}_title")] ?: "",
                    localFilePath = path,
                    totalBytes = prefs[longPreferencesKey("${fileId}_total")] ?: 0L,
                    downloadedBytes = prefs[longPreferencesKey("${fileId}_downloaded")] ?: 0L,
                    status = status
                )
            }
        }
    }

    suspend fun updateProgress(fileId: String, downloaded: Long, total: Long) = withContext(Dispatchers.IO) {
        context.downloadsDataStore.edit { prefs ->
            prefs[longPreferencesKey("${fileId}_downloaded")] = downloaded
            prefs[longPreferencesKey("${fileId}_total")] = total
            prefs[stringPreferencesKey("${fileId}_status")] = DownloadStatus.DOWNLOADING.name
        }
    }

    suspend fun setStatus(fileId: String, title: String, path: String, status: DownloadStatus, total: Long = 0L) = withContext(Dispatchers.IO) {
        context.downloadsDataStore.edit { prefs ->
            prefs[stringPreferencesKey("${fileId}_status")] = status.name
            prefs[stringPreferencesKey("${fileId}_title")] = title
            prefs[stringPreferencesKey("${fileId}_path")] = path
            if (total > 0L) prefs[longPreferencesKey("${fileId}_total")] = total
            if (status == DownloadStatus.COMPLETED) {
                prefs[longPreferencesKey("${fileId}_downloaded")] = total
            }
        }
    }

    suspend fun deleteDownload(fileId: String) = withContext(Dispatchers.IO) {
        var pathToDelete: String? = null
        context.downloadsDataStore.edit { prefs ->
            pathToDelete = prefs[stringPreferencesKey("${fileId}_path")]
            prefs.remove(stringPreferencesKey("${fileId}_status"))
            prefs.remove(stringPreferencesKey("${fileId}_title"))
            prefs.remove(stringPreferencesKey("${fileId}_path"))
            prefs.remove(longPreferencesKey("${fileId}_total"))
            prefs.remove(longPreferencesKey("${fileId}_downloaded"))
        }
        pathToDelete?.let {
            val file = File(it)
            if (file.exists()) file.delete()
        }
    }

    fun getLocalFileIfDownloaded(fileId: String): File? {
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return null
        val targetFile = File(moviesDir, "$fileId.mp4")
        return if (targetFile.exists() && targetFile.length() > 1024 * 1024L) targetFile else null
    }
}
