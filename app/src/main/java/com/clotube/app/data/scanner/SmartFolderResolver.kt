package com.clotube.app.data.scanner

import android.util.Log
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SmartFolderResolver {

    private const val TAG = "SmartFolderResolver"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"

    private val MOVIE_KEYWORDS = setOf("movies", "movie", "films", "film", "cinema")
    private val SERIES_KEYWORDS = setOf("series", "tv series", "tv shows", "shows", "tv")

    /**
     * Resolves the user's actual Movies folder ID, searching across root, subfolders, and shortcuts.
     * Prioritizes folders with actual video content over newly created empty folders.
     */
    suspend fun findPopulatedMoviesFolder(driveService: Drive): String? = withContext(Dispatchers.IO) {
        findBestFolderWithContent(driveService, MOVIE_KEYWORDS)
    }

    suspend fun findPopulatedSeriesFolder(driveService: Drive): String? = withContext(Dispatchers.IO) {
        findBestFolderWithContent(driveService, SERIES_KEYWORDS)
    }

    private fun findBestFolderWithContent(driveService: Drive, targetKeywords: Set<String>): String? {
        // Query ALL folders across the user's Drive, removing the restrictive "'root' in parents" condition
        val query = "(mimeType = '$FOLDER_MIME' or mimeType = '$SHORTCUT_MIME') and trashed = false"

        val matchingFolders = mutableListOf<File>()

        try {
            var pageToken: String? = null
            do {
                val response = driveService.files().list()
                    .setQ(query)
                    .setFields("nextPageToken, files(id, name, mimeType, shortcutDetails)")
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setPageSize(200)
                    .setPageToken(pageToken)
                    .execute()

                response.files?.forEach { file ->
                    val cleanName = file.name?.trim()?.lowercase() ?: ""
                    if (targetKeywords.any { kw -> cleanName == kw || cleanName.contains(kw) }) {
                        matchingFolders.add(file)
                    }
                }
                pageToken = response.nextPageToken
            } while (!pageToken.isNullOrBlank() && matchingFolders.size < 25)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query folders: ${e.message}", e)
            return null
        }

        if (matchingFolders.isEmpty()) {
            Log.w(TAG, "Zero folders found matching keywords: $targetKeywords")
            return null
        }

        if (matchingFolders.size == 1) {
            return resolveTargetId(matchingFolders.first())
        }

        // If multiple folders exist (e.g. empty created duplicate vs existing collection),
        // pick the one containing video files or subfolders
        Log.i(TAG, "Found ${matchingFolders.size} matching candidate folders. Inspecting contents...")
        var bestFolderId: String? = null
        var maxVideoCount = -1

        for (candidate in matchingFolders) {
            val candidateId = resolveTargetId(candidate)
            val videoCount = countVideoItemsInFolder(driveService, candidateId)
            Log.d(TAG, "Folder '${candidate.name}' (ID: $candidateId) contains $videoCount media items.")

            if (videoCount > maxVideoCount) {
                maxVideoCount = videoCount
                bestFolderId = candidateId
            }
        }

        return bestFolderId ?: resolveTargetId(matchingFolders.first())
    }

    private fun countVideoItemsInFolder(driveService: Drive, folderId: String): Int {
        return try {
            val query = "'$folderId' in parents and trashed = false"
            val response = driveService.files().list()
                .setQ(query)
                .setFields("files(id, mimeType, name)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setPageSize(50)
                .execute()

            val files = response.files ?: emptyList()
            // Count video files or child folders
            files.count { file ->
                file.mimeType == FOLDER_MIME ||
                file.mimeType?.startsWith("video/") == true ||
                file.name?.endsWith(".mp4", ignoreCase = true) == true ||
                file.name?.endsWith(".mkv", ignoreCase = true) == true
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun resolveTargetId(file: File): String {
        return if (file.mimeType == SHORTCUT_MIME && file.shortcutDetails?.targetId != null) {
            file.shortcutDetails.targetId
        } else {
            file.id.orEmpty()
        }
    }
}
