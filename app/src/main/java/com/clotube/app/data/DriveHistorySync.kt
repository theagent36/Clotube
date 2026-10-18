package com.clotube.app.data

import android.util.Log
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * DriveHistorySync manages cloud backup and restore of Clotube watch history
 * to Google Drive under the persistent artifact 'clotube_history.json'.
 *
 * Ensures watch progress and history persist seamlessly across app reinstalls
 * and across devices logged into the same Google account.
 */
object DriveHistorySync {
    private const val TAG = "DriveHistorySync"
    private const val SYNC_FILE_NAME = "clotube_history.json"

    /**
     * Factory helper to create a Google Drive client from an OAuth access token.
     */
    fun createDriveService(accessToken: String): Drive {
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance()
        ) { request ->
            request.headers.authorization = "Bearer $accessToken"
        }.setApplicationName("Clotube/1.0").build()
    }

    /**
     * Call on app startup or login. If local history is empty, attempts to restore from Drive.
     */
    suspend fun restoreIfEmpty(
        driveService: Drive,
        historyManager: PlaybackHistoryManager
    ) = withContext(Dispatchers.IO) {
        try {
            val currentLocalJson = historyManager.exportHistoryJson()
            if (currentLocalJson.isNotBlank() && currentLocalJson != "[]") {
                Log.d(TAG, "Local history exists; skipping cloud restore.")
                return@withContext
            }

            val query = "name = '$SYNC_FILE_NAME' and trashed = false"
            val fileList = driveService.files().list()
                .setQ(query)
                .setFields("files(id, name)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .execute()

            val cloudFile = fileList.files?.firstOrNull() ?: return@withContext
            val outputStream = ByteArrayOutputStream()
            driveService.files().get(cloudFile.id)
                .setSupportsAllDrives(true)
                .executeMediaAndDownloadTo(outputStream)

            val downloadedJson = outputStream.toString("UTF-8")
            if (downloadedJson.isNotBlank() && downloadedJson != "[]") {
                historyManager.importHistoryJson(downloadedJson)
                Log.d(TAG, "Successfully restored watch history from Google Drive!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "History restore failed: ${e.message}", e)
        }
    }

    /**
     * Silently backup local history to Google Drive.
     */
    suspend fun syncToDrive(
        driveService: Drive,
        historyManager: PlaybackHistoryManager
    ) = withContext(Dispatchers.IO) {
        try {
            val jsonContent = historyManager.exportHistoryJson()
            if (jsonContent.isBlank() || jsonContent == "[]") return@withContext

            val query = "name = '$SYNC_FILE_NAME' and trashed = false"
            val fileList = driveService.files().list()
                .setQ(query)
                .setFields("files(id, name)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .execute()

            val mediaContent = ByteArrayContent.fromString("application/json", jsonContent)
            val existing = fileList.files?.firstOrNull()

            if (existing != null) {
                driveService.files().update(existing.id, null, mediaContent)
                    .setSupportsAllDrives(true)
                    .execute()
                Log.d(TAG, "Updated existing clotube_history.json on Google Drive.")
            } else {
                val metadata = DriveFile().apply {
                    name = SYNC_FILE_NAME
                    mimeType = "application/json"
                }
                driveService.files().create(metadata, mediaContent)
                    .setSupportsAllDrives(true)
                    .execute()
                Log.d(TAG, "Created fresh clotube_history.json on Google Drive.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "History cloud backup failed: ${e.message}", e)
        }
    }
}
