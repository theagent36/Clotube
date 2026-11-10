package com.clotube.app.data

import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AndroidResolvedDirectories(
    val clotubeRootId: String,
    val moviesFolderId: String?,
    val seriesFolderId: String?,
    val historyFolderId: String
)

object DriveDirectoryManager {

    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"
    private val resolveMutex = Mutex()

    suspend fun resolveHierarchy(drive: Drive): AndroidResolvedDirectories = withContext(Dispatchers.IO) {
        resolveMutex.withLock {
            val rootId = getOrCreateFolder(drive, "clotube", parentId = null)
            val historyId = getOrCreateFolder(drive, "History", parentId = rootId)

            val scopedMovies = findChildFolder(drive, "Movies", rootId)
            val moviesId = if (scopedMovies != null && hasFiles(drive, scopedMovies)) {
                scopedMovies
            } else {
                resolveLegacyFolder(drive, setOf("movies", "movie", "films", "cinema"))
                    ?: scopedMovies
                    ?: getOrCreateFolder(drive, "Movies", parentId = rootId)
            }

            val scopedSeries = findChildFolder(drive, "Series", rootId)
            val seriesId = if (scopedSeries != null && hasFiles(drive, scopedSeries)) {
                scopedSeries
            } else {
                resolveLegacyFolder(drive, setOf("series", "tv series", "tv shows", "shows"))
                    ?: scopedSeries
                    ?: getOrCreateFolder(drive, "Series", parentId = rootId)
            }

            AndroidResolvedDirectories(
                clotubeRootId = rootId,
                moviesFolderId = moviesId,
                seriesFolderId = seriesId,
                historyFolderId = historyId
            )
        }
    }

    private fun getOrCreateFolder(drive: Drive, folderName: String, parentId: String?): String {
        val existingId = if (parentId != null) findChildFolder(drive, folderName, parentId) else findRootFolder(drive, folderName)
        if (existingId != null) return existingId

        val meta = File().apply {
            name = folderName
            mimeType = FOLDER_MIME
            if (parentId != null) parents = listOf(parentId)
        }
        val created = drive.files().create(meta)
            .setFields("id, name")
            .setSupportsAllDrives(true)
            .execute()
        return created.id
    }

    private fun findRootFolder(drive: Drive, name: String): String? {
        val query = "name = '$name' and (mimeType = '$FOLDER_MIME' or mimeType = '$SHORTCUT_MIME') and trashed = false"
        val res = drive.files().list()
            .setQ(query)
            .setFields("files(id, name, mimeType, shortcutDetails)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(5)
            .execute()
        return res.files?.firstOrNull()?.let { resolveTargetId(it) }
    }

    private fun findChildFolder(drive: Drive, name: String, parentId: String): String? {
        val query = "'$parentId' in parents and name = '$name' and (mimeType = '$FOLDER_MIME' or mimeType = '$SHORTCUT_MIME') and trashed = false"
        val res = drive.files().list()
            .setQ(query)
            .setFields("files(id, name, mimeType, shortcutDetails)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(5)
            .execute()
        return res.files?.firstOrNull()?.let { resolveTargetId(it) }
    }

    private fun resolveLegacyFolder(drive: Drive, targetNames: Set<String>): String? {
        val query = "(mimeType = '$FOLDER_MIME' or mimeType = '$SHORTCUT_MIME') and trashed = false"
        val candidates = mutableListOf<File>()

        try {
            val res = drive.files().list()
                .setQ(query)
                .setFields("files(id, name, mimeType, shortcutDetails)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setPageSize(200)
                .execute()

            res.files?.forEach { file ->
                if (targetNames.contains(file.name?.trim()?.lowercase() ?: "")) {
                    candidates.add(file)
                }
            }
        } catch (_: Exception) {
            return null
        }

        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return resolveTargetId(candidates.first())

        return candidates.maxByOrNull { countChildren(drive, resolveTargetId(it)) }?.let { resolveTargetId(it) }
    }

    private fun hasFiles(drive: Drive, folderId: String): Boolean = countChildren(drive, folderId) > 0

    private fun countChildren(drive: Drive, folderId: String): Int {
        return try {
            val res = drive.files().list()
                .setQ("'$folderId' in parents and trashed = false")
                .setFields("files(id)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setPageSize(5)
                .execute()
            res.files?.size ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun resolveTargetId(file: File): String {
        return if (file.mimeType == SHORTCUT_MIME && file.shortcutDetails?.targetId != null) {
            file.shortcutDetails.targetId
        } else {
            file.id
        }
    }
}

