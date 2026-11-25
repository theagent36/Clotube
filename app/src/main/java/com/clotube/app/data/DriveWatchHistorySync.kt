package com.clotube.app.data

import android.accounts.Account
import android.content.Context
import android.util.Log
import com.clotube.app.auth.GoogleAuthRepository
import com.clotube.app.data.ByocConfigManager
import com.clotube.app.data.TokenRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class SyncPriority(val level: Int) {
    CRITICAL_WATCH_STATE(1),
    TIMESTAMPS_AND_SEEK(2),
    BACKGROUND_SYNC(3)
}

class DriveWatchHistorySync(private val context: Context) {

    private val appContext: Context = context.applicationContext
    val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cachedActiveFileId = AtomicReference<String?>(null)
    private val cachedArchiveFileId = AtomicReference<String?>(null)
    private val cachedHistoryFolderId = AtomicReference<String?>(null)
    private val uploadMutex = Mutex()
    private val lastUploadTime = AtomicLong(0L)
    private val isPullingFromCloud = AtomicBoolean(false)
    private val lastCloudPullTimeMs = AtomicLong(0L)
    private var debouncedProgressJob: Job? = null

    fun invalidateCache() {
        cachedActiveFileId.set(null)
        cachedArchiveFileId.set(null)
        cachedHistoryFolderId.set(null)
        lastCloudPullTimeMs.set(0L)
    }

    private suspend fun resolveHistoryFolderId(driveService: Drive): String = withContext(Dispatchers.IO) {
        cachedHistoryFolderId.get()?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val dirs = DriveDirectoryManager.resolveHierarchy(driveService)
        cachedHistoryFolderId.set(dirs.historyFolderId)
        dirs.historyFolderId
    }

    private fun findFileInHistoryFolder(
        driveService: Drive,
        historyFolderId: String,
        fileName: String
    ): String? {
        val scopedRes = driveService.files().list()
            .setQ("'$historyFolderId' in parents and name = '$fileName' and trashed = false")
            .setOrderBy("modifiedTime desc")
            .setFields("files(id, parents, modifiedTime)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(5)
            .execute()

        val scopedId = scopedRes.files?.firstOrNull()?.id
        if (!scopedId.isNullOrBlank()) return scopedId

        val globalRes = driveService.files().list()
            .setQ("name = '$fileName' and trashed = false")
            .setOrderBy("modifiedTime desc")
            .setFields("files(id, parents, modifiedTime)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(5)
            .execute()

        val globalMatch = globalRes.files?.firstOrNull()
        if (globalMatch != null && !globalMatch.id.isNullOrBlank()) {
            globalMatch.parents?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                cachedHistoryFolderId.set(it)
            }
            return globalMatch.id
        }
        return null
    }

    private fun downloadJsonFile(driveService: Drive, fileId: String): String? {
        return try {
            val out = ByteArrayOutputStream()
            driveService.files().get(fileId)
                .setSupportsAllDrives(true)
                .executeMediaAndDownloadTo(out)
            out.toString("UTF-8").takeIf { it.isNotBlank() }
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 404) null else throw e
        }
    }

    /**
     * Authoritative pull from clotube/History/clotube_active_sync.json and clotube_archive.json.
     * Replaces volatile RAM state completely—zero local disk caching or merging.
     */
    suspend fun syncFromCloud(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastCloudPullTimeMs.get() < CLOUD_PULL_COOLDOWN_MS)) {
            return@withContext true
        }
        if (!isPullingFromCloud.compareAndSet(false, true)) {
            return@withContext false
        }

        try {
            val driveService = getDriveService() ?: return@withContext false
            val historyFolderId = resolveHistoryFolderId(driveService)

            val activeFileId = cachedActiveFileId.get()
                ?: findFileInHistoryFolder(driveService, historyFolderId, ACTIVE_SYNC_FILE)?.also {
                    cachedActiveFileId.set(it)
                }

            val archiveFileId = cachedArchiveFileId.get()
                ?: findFileInHistoryFolder(driveService, historyFolderId, ARCHIVE_SYNC_FILE)?.also {
                    cachedArchiveFileId.set(it)
                }

            val activeJson = activeFileId?.let { downloadJsonFile(driveService, it) }
            val archiveJson = archiveFileId?.let { downloadJsonFile(driveService, it) }

            val activeEntries = if (activeJson != null) parseActiveSyncJson(activeJson) else emptyMap()
            val archivedIds = if (archiveJson != null) parseArchiveJson(archiveJson) else emptySet()

            AndroidWatchHistoryManager.replaceAllFromDrive(activeEntries, archivedIds)
            lastCloudPullTimeMs.set(System.currentTimeMillis())
            Log.i(
                TAG,
                "Pulled directly from Drive: activeEntries=${activeEntries.size}, archivedIds=${archivedIds.size}"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Direct Drive pull failed: ${e.message}", e)
            if (e is GoogleJsonResponseException && (e.statusCode == 401 || e.statusCode == 403)) {
                onAuthFailureListener?.invoke()
            }
            false
        } finally {
            isPullingFromCloud.set(false)
        }
    }

    private fun parseActiveSyncJson(remoteJson: String): Map<String, AndroidTypedEntry> {
        val result = mutableMapOf<String, AndroidTypedEntry>()
        val payload = WatchHistoryPayloadV2.fromJsonString(remoteJson)
        val updatedAt = WatchHistoryPayloadV2.normalizeTimestampMs(payload.updatedAt)
        if (updatedAt > 0L) {
            result["global_latest_timestamp"] = AndroidTypedEntry("long", updatedAt)
        }

        val activeById = payload.active.associateBy { it.id }

        for (watchedId in payload.watchedIds) {
            if (watchedId.isNotBlank()) {
                result["${watchedId}_is_watched"] = AndroidTypedEntry("boolean", true)
                result["${watchedId}_fraction"] = AndroidTypedEntry("float", 1.0f)
            }
        }

        for (item in payload.active) {
            if (item.id.isBlank()) continue
            val id = item.id
            val itemTs = WatchHistoryPayloadV2.normalizeTimestampMs(item.timestamp)
            val frac = if (item.isWatched) {
                1.0f
            } else if (item.durationMs > 0L) {
                (item.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                item.progressFraction.coerceIn(0f, 1f)
            }
            val isSeries = !item.seriesId.isNullOrBlank() ||
                (item.seasonNumber != null && item.seasonNumber > 0) ||
                (item.episodeNumber != null && item.episodeNumber > 0)

            result["${id}_title"] = AndroidTypedEntry("string", item.title)
            result["${id}_pos"] = AndroidTypedEntry("long", item.positionMs)
            result["${id}_dur"] = AndroidTypedEntry("long", item.durationMs)
            result["${id}_fraction"] = AndroidTypedEntry("float", frac)
            result["${id}_timestamp"] = AndroidTypedEntry("long", itemTs)
            result["${id}_is_watched"] = AndroidTypedEntry("boolean", item.isWatched)
            result["${id}_is_series"] = AndroidTypedEntry("boolean", isSeries)
            if (!item.seriesId.isNullOrBlank()) {
                result["${id}_series_id"] = AndroidTypedEntry("string", item.seriesId)
            }
            if (!item.seriesTitle.isNullOrBlank()) {
                result["${id}_series_title"] = AndroidTypedEntry("string", item.seriesTitle)
            }
            if (item.seasonNumber != null && item.seasonNumber > 0) {
                result["${id}_season_num"] = AndroidTypedEntry("int", item.seasonNumber)
            }
            if (item.episodeNumber != null && item.episodeNumber > 0) {
                result["${id}_episode_num"] = AndroidTypedEntry("int", item.episodeNumber)
            }
            if (!item.thumbnailUrl.isNullOrBlank()) {
                result["${id}_thumb"] = AndroidTypedEntry("string", item.thumbnailUrl)
                result["${id}_thumbnail_url"] = AndroidTypedEntry("string", item.thumbnailUrl)
            }
        }

        // Parse payload.resume (JSON key "resume") and link directly to target episodeId
        for ((seriesId, resume) in payload.resume) {
            if (seriesId.isBlank() || resume.episodeId.isBlank()) continue
            val resTs = WatchHistoryPayloadV2.normalizeTimestampMs(resume.timestamp)
            val frac = if (resume.durationMs > 0L) {
                (resume.positionMs.toFloat() / resume.durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val linkedActive = activeById[resume.episodeId]
            val epTitle = linkedActive?.title?.takeIf { it.isNotBlank() } ?: ""

            val p = "series_resume_${seriesId}"
            result["${p}_episode_id"] = AndroidTypedEntry("string", resume.episodeId)
            result["${p}_season_num"] = AndroidTypedEntry("int", resume.seasonNumber)
            result["${p}_episode_num"] = AndroidTypedEntry("int", resume.episodeNumber)
            result["${p}_pos_ms"] = AndroidTypedEntry("long", resume.positionMs)
            result["${p}_dur_ms"] = AndroidTypedEntry("long", resume.durationMs)
            result["${p}_fraction"] = AndroidTypedEntry("float", frac)
            result["${p}_timestamp"] = AndroidTypedEntry("long", resTs)
            if (epTitle.isNotBlank()) {
                result["${p}_episode_title"] = AndroidTypedEntry("string", epTitle)
            }

            // Ensure the target episodeId entry reflects the latest resume timestamp & series metadata
            val epId = resume.episodeId
            val existingEpTs = (result["${epId}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            if (resTs >= existingEpTs) {
                result["${epId}_timestamp"] = AndroidTypedEntry("long", resTs)
                result["${epId}_pos"] = AndroidTypedEntry("long", resume.positionMs)
                result["${epId}_dur"] = AndroidTypedEntry("long", resume.durationMs)
                result["${epId}_fraction"] = AndroidTypedEntry("float", frac)
                val isCompleted = resume.durationMs > 0L && resume.positionMs >= (resume.durationMs * 0.90f)
                result["${epId}_is_watched"] = AndroidTypedEntry("boolean", isCompleted)
            }
            result["${epId}_is_series"] = AndroidTypedEntry("boolean", true)
            result["${epId}_series_id"] = AndroidTypedEntry("string", seriesId)
            if (resume.seasonNumber > 0) result["${epId}_season_num"] = AndroidTypedEntry("int", resume.seasonNumber)
            if (resume.episodeNumber > 0) result["${epId}_episode_num"] = AndroidTypedEntry("int", resume.episodeNumber)
            if (epTitle.isNotBlank() && !result.containsKey("${epId}_title")) {
                result["${epId}_title"] = AndroidTypedEntry("string", epTitle)
            }
        }

        return result
    }

    private fun parseArchiveJson(archiveJson: String): Set<String> {
        val ids = mutableSetOf<String>()
        try {
            val root = JSONObject(archiveJson)
            val watchedArr = root.optJSONArray("watchedIds")
            if (watchedArr != null) {
                for (i in 0 until watchedArr.length()) {
                    val id = watchedArr.optString(i).trim()
                    if (id.isNotBlank()) ids.add(id)
                }
            }
            val itemsArr = root.optJSONArray("items")
            if (itemsArr != null) {
                for (i in 0 until itemsArr.length()) {
                    val obj = itemsArr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").trim()
                    if (id.isNotBlank()) ids.add(id)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse $ARCHIVE_SYNC_FILE:${e.message}")
        }
        return ids
    }

    private fun buildArchiveJsonString(archiveIds: Set<String>): String {
        val root = JSONObject()
        root.put("v", 2)
        root.put("updatedAt", System.currentTimeMillis())
        val arr = JSONArray()
        archiveIds.sorted().forEach { arr.put(it) }
        root.put("watchedIds", arr)
        return root.toString()
    }

    /**
     * Pushes current volatile state directly to clotube/History/clotube_active_sync.json
     * (and updates clotube_archive.json if an archived item was unwatched or deleted).
     */
    suspend fun executeDirectUpload(
        priority: SyncPriority = SyncPriority.CRITICAL_WATCH_STATE,
        updateArchive: Boolean = false
    ): Boolean = uploadMutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val driveService = getDriveService() ?: return@withContext false
                val historyFolderId = resolveHistoryFolderId(driveService)

                val activeSnapshot = AndroidWatchHistoryManager.snapshotEntries()
                val activePayloadJson = WatchHistoryPayloadV2.fromTypedEntries(activeSnapshot).toJsonString()
                val activeMedia = ByteArrayContent.fromString("application/json", activePayloadJson)

                val activeOk = upsertHistoryFile(
                    driveService = driveService,
                    historyFolderId = historyFolderId,
                    fileName = ACTIVE_SYNC_FILE,
                    cachedIdRef = cachedActiveFileId,
                    mediaContent = activeMedia,
                    priority = priority
                )

                if (updateArchive) {
                    val archiveJson = buildArchiveJsonString(AndroidWatchHistoryManager.snapshotArchivedIds())
                    val archiveMedia = ByteArrayContent.fromString("application/json", archiveJson)
                    upsertHistoryFile(
                        driveService = driveService,
                        historyFolderId = historyFolderId,
                        fileName = ARCHIVE_SYNC_FILE,
                        cachedIdRef = cachedArchiveFileId,
                        mediaContent = archiveMedia,
                        priority = priority
                    )
                }

                lastUploadTime.set(System.currentTimeMillis())
                activeOk
            } catch (e: Exception) {
                Log.e(TAG, "Direct Drive upload failed: ${e.message}", e)
                if (e is GoogleJsonResponseException && (e.statusCode == 401 || e.statusCode == 403)) {
                    onAuthFailureListener?.invoke()
                }
                false
            }
        }
    }

    private fun upsertHistoryFile(
        driveService: Drive,
        historyFolderId: String,
        fileName: String,
        cachedIdRef: AtomicReference<String?>,
        mediaContent: ByteArrayContent,
        priority: SyncPriority
    ): Boolean {
        var fileId = cachedIdRef.get() ?: findFileInHistoryFolder(driveService, historyFolderId, fileName)
        if (fileId != null) {
            try {
                driveService.files().update(fileId, null, mediaContent)
                    .setSupportsAllDrives(true)
                    .execute()
                cachedIdRef.set(fileId)
                Log.i(TAG, "SUCCESS! [${priority.name}] Updated $fileName directly on Drive ($fileId)")
                return true
            } catch (e: GoogleJsonResponseException) {
                if (e.statusCode == 404 || e.statusCode == 403) {
                    cachedIdRef.set(null)
                    fileId = null
                } else {
                    throw e
                }
            }
        }

        val metadata = com.google.api.services.drive.model.File().apply {
            name = fileName
            mimeType = "application/json"
            parents = listOf(historyFolderId)
        }
        val created = driveService.files().create(metadata, mediaContent)
            .setSupportsAllDrives(true)
            .execute()
        cachedIdRef.set(created.id)
        Log.i(TAG, "SUCCESS! [${priority.name}] Created $fileName directly on Drive (${created.id})")
        return true
    }

    fun enqueueImmediateWatchState(updateArchive: Boolean = false) {
        debouncedProgressJob?.cancel()
        syncScope.launch {
            executeDirectUpload(SyncPriority.CRITICAL_WATCH_STATE, updateArchive = updateArchive)
        }
    }

    fun enqueueProgressUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastUploadTime.get() >= PROGRESS_UPLOAD_INTERVAL_MS) {
            debouncedProgressJob?.cancel()
            syncScope.launch {
                executeDirectUpload(SyncPriority.TIMESTAMPS_AND_SEEK, updateArchive = false)
            }
            return
        }
        if (debouncedProgressJob?.isActive == true) return
        debouncedProgressJob = syncScope.launch {
            delay(PROGRESS_UPLOAD_INTERVAL_MS)
            executeDirectUpload(SyncPriority.TIMESTAMPS_AND_SEEK, updateArchive = false)
        }
    }

    fun backupImmediately() {
        enqueueImmediateWatchState(updateArchive = false)
    }

    fun pullFromCloudIfNeeded(force: Boolean = false) {
        syncScope.launch {
            syncFromCloud(force = force)
        }
    }

    suspend fun deleteRemoteHistoryFiles(): Boolean = uploadMutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val driveService = getDriveService() ?: return@withContext false
                val historyFolderId = resolveHistoryFolderId(driveService)

                listOf(
                    ACTIVE_SYNC_FILE to cachedActiveFileId,
                    ARCHIVE_SYNC_FILE to cachedArchiveFileId
                ).forEach { (fileName, ref) ->
                    val id = ref.get() ?: findFileInHistoryFolder(driveService, historyFolderId, fileName)
                    if (id != null) {
                        try {
                            driveService.files().delete(id).setSupportsAllDrives(true).execute()
                            Log.i(TAG, "Deleted $fileName from Drive ($id)")
                        } catch (_: Exception) {}
                    }
                }
                invalidateCache()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed deleting Drive history files: ${e.message}", e)
                false
            }
        }
    }

    private fun getDriveService(): Drive? {
        if (ByocConfigManager.isByocConfigured(appContext)) {
            val token = runBlocking {
                tokenProviderRef?.invoke()
                    ?: TokenRepository(appContext).getValidAccessToken(forceRefresh = false).takeIf { it.isNotBlank() }
                    ?: GoogleAuthRepository(appContext).getAccessToken().getOrNull()
            }
            if (!token.isNullOrBlank()) {
                return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance()) { request ->
                    request.headers.authorization = "Bearer $token"
                }.setApplicationName("Clotube").build()
            }
            return null
        }

        val account = GoogleSignIn.getLastSignedInAccount(appContext)
        if (account == null) {
            val token = runBlocking {
                tokenProviderRef?.invoke() ?: GoogleAuthRepository(appContext).getAccessToken().getOrNull()
            }
            if (!token.isNullOrBlank()) {
                return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance()) { request ->
                    request.headers.authorization = "Bearer $token"
                }.setApplicationName("Clotube").build()
            }
            return null
        }

        val googleAccount: Account? = account.account ?: account.email?.let { Account(it, "com.google") }
        val credential = GoogleAccountCredential.usingOAuth2(
            appContext,
            listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE)
        ).setSelectedAccount(googleAccount)

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Clotube").build()
    }

    companion object {
        private const val TAG = "ClotubeSync"
        private const val ACTIVE_SYNC_FILE = "clotube_active_sync.json"
        private const val ARCHIVE_SYNC_FILE = "clotube_archive.json"
        private const val CLOUD_PULL_COOLDOWN_MS = 3_000L
        private const val PROGRESS_UPLOAD_INTERVAL_MS = 8_000L

        @Volatile
        private var defaultInstance: DriveWatchHistorySync? = null

        @Volatile
        private var tokenProviderRef: (suspend () -> String)? = null

        @Volatile
        private var onAuthFailureListener: (suspend () -> Unit)? = null

        fun getInstance(context: Context): DriveWatchHistorySync {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: DriveWatchHistorySync(context.applicationContext).also { defaultInstance = it }
            }
        }

        fun init(context: Context, tokenProvider: (suspend () -> String)? = null) {
            AndroidWatchHistoryManager.purgeAllLocalDiskCaches(context)
            val instance = getInstance(context)
            if (tokenProvider != null) {
                this.tokenProviderRef = tokenProvider
            }
            instance.syncScope.launch {
                instance.syncFromCloud(force = true)
            }
        }

        fun setTokenProvider(provider: suspend () -> String) {
            this.tokenProviderRef = provider
        }

        fun setOnAuthFailureListener(listener: suspend () -> Unit) {
            this.onAuthFailureListener = listener
        }

        fun pushHistoryAsync(
            context: Context,
            force: Boolean = false,
            updateArchive: Boolean = false
        ) {
            val inst = getInstance(context)
            if (force) {
                inst.enqueueImmediateWatchState(updateArchive = updateArchive)
            } else {
                inst.enqueueProgressUpdate()
            }
        }

        fun pullFromCloudIfNeeded(context: Context, force: Boolean = false) {
            val inst = getInstance(context)
            inst.syncScope.launch {
                inst.syncFromCloud(force = force)
            }
        }

        fun backupImmediately(context: Context) {
            getInstance(context).enqueueImmediateWatchState(updateArchive = false)
        }
    }
}
