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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class CloudHistorySyncOutcome(
    val revision: Long = 0L,
    val succeeded: Boolean = false,
    val confirmedEmpty: Boolean = false,
    val remoteWasNewer: Boolean = false,
    val remoteLatestTs: Long = 0L
)

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

    private val _lastSyncOutcome = MutableStateFlow(CloudHistorySyncOutcome())
    val lastSyncOutcome: StateFlow<CloudHistorySyncOutcome> = _lastSyncOutcome.asStateFlow()

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

    private data class DownloadResult(
        val content: String?,
        val notFound: Boolean
    )

    private fun downloadJsonFile(driveService: Drive, fileId: String): DownloadResult {
        return try {
            val out = ByteArrayOutputStream()
            driveService.files().get(fileId)
                .setSupportsAllDrives(true)
                .executeMediaAndDownloadTo(out)
            DownloadResult(out.toString("UTF-8").takeIf { it.isNotBlank() }, notFound = false)
        } catch (e: GoogleJsonResponseException) {
            if (e.statusCode == 404) DownloadResult(null, notFound = true) else throw e
        }
    }

    private fun downloadWithStaleIdRetry(
        driveService: Drive,
        historyFolderId: String,
        fileName: String,
        cachedIdRef: AtomicReference<String?>
    ): String? {
        val currentId = cachedIdRef.get()
            ?: findFileInHistoryFolder(driveService, historyFolderId, fileName)?.also { cachedIdRef.set(it) }
            ?: return null

        val firstAttempt = downloadJsonFile(driveService, currentId)
        if (!firstAttempt.notFound) {
            return firstAttempt.content
        }

        // Cached Drive fileId went stale (404); clear and re-discover once
        cachedIdRef.set(null)
        val freshId = findFileInHistoryFolder(driveService, historyFolderId, fileName)
            ?.also { cachedIdRef.set(it) }
            ?: return null
        return downloadJsonFile(driveService, freshId).content
    }

    /**
     * Authoritative timestamp-fenced pull & self-healing merge from `clotube/History/clotube_active_sync.json`
     * and `clotube_archive.json`, reconciled against the local archive store and `ContinueWatchingSnapshotStore`.
     */
    suspend fun syncFromCloud(force: Boolean = false): Boolean = pullAndMerge(appContext, force)

    suspend fun pullAndMerge(
        context: Context = appContext,
        force: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastCloudPullTimeMs.get() < CLOUD_PULL_COOLDOWN_MS)) {
            return@withContext true
        }
        if (!isPullingFromCloud.compareAndSet(false, true)) {
            return@withContext false
        }

        var shouldHealPushToDrive = false
        try {
            val appCtx = context.applicationContext
            AndroidWatchHistoryManager.ensureArchiveLoaded(appCtx)

            val driveService = getDriveService() ?: return@withContext false
            val historyFolderId = resolveHistoryFolderId(driveService)

            val activeJson = downloadWithStaleIdRetry(
                driveService = driveService,
                historyFolderId = historyFolderId,
                fileName = ACTIVE_SYNC_FILE,
                cachedIdRef = cachedActiveFileId
            )
            val archiveJson = downloadWithStaleIdRetry(
                driveService = driveService,
                historyFolderId = historyFolderId,
                fileName = ARCHIVE_SYNC_FILE,
                cachedIdRef = cachedArchiveFileId
            )

            val payload = if (activeJson != null) {
                runCatching { WatchHistoryPayloadV2.fromJsonString(activeJson) }.getOrNull()
            } else {
                null
            }
            val remoteArchive = if (archiveJson != null) {
                runCatching { ArchivePayloadV2.fromJsonString(archiveJson) }.getOrNull()
            } else {
                null
            }

            val pendingDeleted = AndroidWatchHistoryManager.snapshotPendingDeletedFromArchiveIds()
            val mergedArchiveItems = linkedMapOf<String, HistoryItemEntityV2>()
            AndroidWatchHistoryManager.snapshotArchivedItems().values.forEach { localItem ->
                if (localItem.id.isNotBlank() && localItem.id !in pendingDeleted) {
                    val existing = mergedArchiveItems[localItem.id]
                    if (existing == null || localItem.timestamp >= existing.timestamp) {
                        mergedArchiveItems[localItem.id] = localItem
                    }
                }
            }
            remoteArchive?.items.orEmpty().forEach { remoteItem ->
                if (remoteItem.id.isNotBlank() && remoteItem.id !in pendingDeleted) {
                    val existing = mergedArchiveItems[remoteItem.id]
                    if (existing == null || remoteItem.timestamp >= existing.timestamp) {
                        mergedArchiveItems[remoteItem.id] = remoteItem
                    }
                }
            }

            val mergedWatchedIds = linkedSetOf<String>()
            AndroidWatchHistoryManager.snapshotArchivedIds()
                .filter { it.isNotBlank() && it !in pendingDeleted }
                .forEach { mergedWatchedIds.add(it) }
            remoteArchive?.watchedIds.orEmpty()
                .filter { it.isNotBlank() && it !in pendingDeleted }
                .forEach { mergedWatchedIds.add(it) }
            payload?.watchedIds.orEmpty()
                .filter { it.isNotBlank() && it !in pendingDeleted }
                .forEach { mergedWatchedIds.add(it) }
            mergedArchiveItems.values.forEach { entity ->
                if (entity.isWatched || entity.progressFraction >= 0.90f) {
                    mergedWatchedIds.add(entity.id)
                }
            }
            payload?.active.orEmpty().forEach { entity ->
                if ((entity.isWatched || entity.progressFraction >= 0.90f) && entity.id !in pendingDeleted) {
                    mergedWatchedIds.add(entity.id)
                }
            }

            // Self-Healing Promotion: move genuinely in-progress archived items back to active window
            val remoteActiveById = payload?.active.orEmpty().associateBy { it.id }
            val promotedItems = mutableListOf<HistoryItemEntityV2>()
            val payloadWatchedSet = payload?.watchedIds?.filter { it.isNotBlank() }?.toSet().orEmpty()

            mergedArchiveItems.values.toList().forEach { item ->
                val remoteActiveMatch = remoteActiveById[item.id]
                val completedInRemoteActive = remoteActiveMatch != null &&
                    (remoteActiveMatch.isWatched || remoteActiveMatch.progressFraction >= 0.90f) &&
                    remoteActiveMatch.timestamp >= item.timestamp
                if (item.isInProgressForProtection && !completedInRemoteActive && item.id !in payloadWatchedSet) {
                    promotedItems.add(item)
                    mergedArchiveItems.remove(item.id)
                    mergedWatchedIds.remove(item.id)
                } else if (remoteActiveMatch != null && (remoteActiveMatch.isWatched || remoteActiveMatch.progressFraction >= 0.90f)) {
                    if (remoteActiveMatch.timestamp >= item.timestamp) {
                        mergedArchiveItems[item.id] = remoteActiveMatch
                        mergedWatchedIds.add(item.id)
                    }
                }
            }

            // Combine remote active items + promoted archive items using LWW, then priority-partition <= 50
            val combinedActiveMap = linkedMapOf<String, HistoryItemEntityV2>()
            payload?.active.orEmpty().forEach { entity ->
                if (entity.id.isNotBlank()) {
                    val existing = combinedActiveMap[entity.id]
                    if (existing == null || entity.timestamp >= existing.timestamp) {
                        combinedActiveMap[entity.id] = entity
                    }
                }
            }
            promotedItems.forEach { promoted ->
                val existing = combinedActiveMap[promoted.id]
                if (existing == null || promoted.timestamp >= existing.timestamp) {
                    combinedActiveMap[promoted.id] = promoted
                }
            }

            val partition = WatchHistoryPayloadV2.partitionActiveWindow(combinedActiveMap.values.toList())
            partition.overflowItems.forEach { overflow ->
                val existing = mergedArchiveItems[overflow.id]
                if (existing == null || overflow.timestamp >= existing.timestamp) {
                    mergedArchiveItems[overflow.id] = overflow
                }
                if (overflow.isWatched || overflow.progressFraction >= 0.90f) {
                    mergedWatchedIds.add(overflow.id)
                }
            }

            val activeIdsAfterPartition = partition.activeItems.map { it.id }.toSet()
            val promotedIntoActive = promotedItems.any { it.id in activeIdsAfterPartition }
            shouldHealPushToDrive = promotedIntoActive

            partition.activeItems.filter { it.isInProgressForProtection }.forEach { inProg ->
                mergedWatchedIds.remove(inProg.id)
            }

            val effectivePayload = WatchHistoryPayloadV2(
                version = payload?.version ?: 2,
                updatedAt = payload?.updatedAt ?: 0L,
                active = partition.activeItems,
                resume = payload?.resume.orEmpty(),
                watchedIds = mergedWatchedIds.toList()
            )

            val activePayloadTs = WatchHistoryPayloadV2.normalizeTimestampMs(effectivePayload.updatedAt)
            val activeMaxItemTs = partition.activeItems.maxOfOrNull { WatchHistoryPayloadV2.normalizeTimestampMs(it.timestamp) } ?: 0L
            val resumeMaxTs = effectivePayload.resume.values.maxOfOrNull { WatchHistoryPayloadV2.normalizeTimestampMs(it.timestamp) } ?: 0L
            val remoteLatestTs = maxOf(activePayloadTs, activeMaxItemTs, resumeMaxTs, remoteArchive?.updatedAt ?: 0L)

            val activeEntries = parseActiveSyncPayload(effectivePayload, mergedWatchedIds)

            val profileEmail = ContinueWatchingSnapshotStore.resolveActiveProfileEmail(appCtx)
            val localSnapshot = ContinueWatchingSnapshotStore.loadSnapshot(appCtx, profileEmail)
            val ramEntries = AndroidWatchHistoryManager.snapshotEntries()
            val ramLatestTs = AndroidWatchHistoryManager.getLatestTimestampMs()

            val localSnapshotIsNewer = localSnapshot.item != null && localSnapshot.updatedAt > remoteLatestTs
            val localRamIsNewer = ramEntries.isNotEmpty() && ramLatestTs > remoteLatestTs

            if (localSnapshotIsNewer || localRamIsNewer) {
                // Case B: Local snapshot / RAM progress is newer than Drive; preserve local progress and push up to Drive.
                var removedFromArchive = false
                if (ramEntries.isEmpty() && localSnapshot.item != null) {
                    AndroidWatchHistoryManager.replaceAllFromDrive(
                        activeEntries = activeEntries,
                        archiveIds = mergedWatchedIds,
                        archivedItemList = mergedArchiveItems.values,
                        context = appCtx
                    )
                    removedFromArchive = AndroidWatchHistoryManager.seedSnapshotEntryIfNewer(
                        item = localSnapshot.item,
                        updatedAt = localSnapshot.updatedAt
                    )
                } else {
                    AndroidWatchHistoryManager.replaceArchiveFromCloud(
                        context = appCtx,
                        items = mergedArchiveItems.values,
                        watchedIds = mergedWatchedIds
                    )
                    if (localSnapshot.item != null && localSnapshot.updatedAt > ramLatestTs) {
                        removedFromArchive = AndroidWatchHistoryManager.seedSnapshotEntryIfNewer(
                            item = localSnapshot.item,
                            updatedAt = localSnapshot.updatedAt
                        )
                    }
                }

                enqueueImmediateWatchState(updateArchive = removedFromArchive || promotedIntoActive)
                shouldHealPushToDrive = false
                lastCloudPullTimeMs.set(System.currentTimeMillis())
                _lastSyncOutcome.value = CloudHistorySyncOutcome(
                    revision = maxOf(_lastSyncOutcome.value.revision + 1L, System.nanoTime()),
                    succeeded = true,
                    confirmedEmpty = false,
                    remoteWasNewer = false,
                    remoteLatestTs = remoteLatestTs
                )
                Log.i(
                    TAG,
                    "Preserved newer local progress over stale Drive (localTs=${maxOf(localSnapshot.updatedAt, ramLatestTs)}, remoteTs=$remoteLatestTs); enqueued upload"
                )
                return@withContext true
            }

            val watchedSet = effectivePayload.watchedIds.filter { it.isNotBlank() }.toSet()

            if (remoteLatestTs > localSnapshot.updatedAt && remoteLatestTs > 0L) {
                // Case A: Remote Drive V2 state (or promoted archive recovery) is newer than local snapshot
                AndroidWatchHistoryManager.replaceAllFromDrive(
                    activeEntries = activeEntries,
                    archiveIds = mergedWatchedIds,
                    archivedItemList = mergedArchiveItems.values,
                    context = appCtx
                )
                val topPair = ContinueWatchingSnapshotStore.deriveTopInProgressItem(
                    context = appCtx,
                    entries = AndroidWatchHistoryManager.snapshotEntries(),
                    watchedIds = watchedSet,
                    archivedIds = AndroidWatchHistoryManager.snapshotArchivedIds(),
                    archivedItems = AndroidWatchHistoryManager.snapshotArchivedItems(),
                    minPositionMs = 0L
                )

                if (topPair != null) {
                    ContinueWatchingSnapshotStore.saveSnapshot(
                        context = appCtx,
                        profileEmail = profileEmail,
                        item = topPair.first,
                        updatedAt = maxOf(remoteLatestTs, topPair.second)
                    )
                    _lastSyncOutcome.value = CloudHistorySyncOutcome(
                        revision = maxOf(_lastSyncOutcome.value.revision + 1L, System.nanoTime()),
                        succeeded = true,
                        confirmedEmpty = false,
                        remoteWasNewer = true,
                        remoteLatestTs = remoteLatestTs
                    )
                } else {
                    ContinueWatchingSnapshotStore.markConfirmedEmpty(
                        context = appCtx,
                        profileEmail = profileEmail,
                        updatedAt = remoteLatestTs
                    )
                    _lastSyncOutcome.value = CloudHistorySyncOutcome(
                        revision = maxOf(_lastSyncOutcome.value.revision + 1L, System.nanoTime()),
                        succeeded = true,
                        confirmedEmpty = true,
                        remoteWasNewer = true,
                        remoteLatestTs = remoteLatestTs
                    )
                }
            } else if (remoteLatestTs == 0L && mergedArchiveItems.isEmpty() && mergedWatchedIds.isEmpty()) {
                // Case C: Remote confirms empty and local snapshot has no newer progress
                AndroidWatchHistoryManager.replaceAllFromDrive(emptyMap(), emptySet(), emptyList(), appCtx)
                ContinueWatchingSnapshotStore.markConfirmedEmpty(
                    context = appCtx,
                    profileEmail = profileEmail,
                    updatedAt = System.currentTimeMillis()
                )
                _lastSyncOutcome.value = CloudHistorySyncOutcome(
                    revision = maxOf(_lastSyncOutcome.value.revision + 1L, System.nanoTime()),
                    succeeded = true,
                    confirmedEmpty = true,
                    remoteWasNewer = false,
                    remoteLatestTs = 0L
                )
            } else {
                // Timestamps match (remoteLatestTs == localSnapshot.updatedAt): hydrate RAM & archive from Drive
                AndroidWatchHistoryManager.replaceAllFromDrive(
                    activeEntries = activeEntries,
                    archiveIds = mergedWatchedIds,
                    archivedItemList = mergedArchiveItems.values,
                    context = appCtx
                )
                val topPair = ContinueWatchingSnapshotStore.deriveTopInProgressItem(
                    context = appCtx,
                    entries = AndroidWatchHistoryManager.snapshotEntries(),
                    watchedIds = watchedSet,
                    archivedIds = AndroidWatchHistoryManager.snapshotArchivedIds(),
                    archivedItems = AndroidWatchHistoryManager.snapshotArchivedItems(),
                    minPositionMs = 0L
                )
                if (topPair == null) {
                    ContinueWatchingSnapshotStore.markConfirmedEmpty(
                        context = appCtx,
                        profileEmail = profileEmail,
                        updatedAt = remoteLatestTs.takeIf { it > 0L } ?: System.currentTimeMillis()
                    )
                    _lastSyncOutcome.value = CloudHistorySyncOutcome(
                        revision = maxOf(_lastSyncOutcome.value.revision + 1L, System.nanoTime()),
                        succeeded = true,
                        confirmedEmpty = true,
                        remoteWasNewer = false,
                        remoteLatestTs = remoteLatestTs
                    )
                } else {
                    if (localSnapshot.item == null) {
                        ContinueWatchingSnapshotStore.saveSnapshot(
                            context = appCtx,
                            profileEmail = profileEmail,
                            item = topPair.first,
                            updatedAt = maxOf(remoteLatestTs, topPair.second)
                        )
                    }
                    _lastSyncOutcome.value = CloudHistorySyncOutcome(
                        revision = maxOf(_lastSyncOutcome.value.revision + 1L, System.nanoTime()),
                        succeeded = true,
                        confirmedEmpty = false,
                        remoteWasNewer = false,
                        remoteLatestTs = remoteLatestTs
                    )
                }
            }

            if (shouldHealPushToDrive) {
                executeDirectUpload(SyncPriority.CRITICAL_WATCH_STATE, updateArchive = true)
            }

            lastCloudPullTimeMs.set(System.currentTimeMillis())
            Log.i(
                TAG,
                "Pulled from Drive: activeEntries=${activeEntries.size}, archivedItems=${mergedArchiveItems.size}, archivedIds=${mergedWatchedIds.size}, promotedFromArchive=$promotedIntoActive, remoteLatestTs=$remoteLatestTs"
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

    private fun parseActiveSyncPayload(
        payload: WatchHistoryPayloadV2,
        archivedIds: Set<String>
    ): Map<String, AndroidTypedEntry> {
        val result = mutableMapOf<String, AndroidTypedEntry>()
        val updatedAt = WatchHistoryPayloadV2.normalizeTimestampMs(payload.updatedAt)
        if (updatedAt > 0L) {
            result["global_latest_timestamp"] = AndroidTypedEntry("long", updatedAt)
        }

        val activeById = payload.active.associateBy { it.id }
        val watchedSet = payload.watchedIds.filter { it.isNotBlank() }.toSet()

        for (watchedId in watchedSet) {
            result["${watchedId}_is_watched"] = AndroidTypedEntry("boolean", true)
            result["${watchedId}_fraction"] = AndroidTypedEntry("float", 1.0f)
        }

        for (item in payload.active) {
            if (item.id.isBlank()) continue
            val id = item.id
            val itemTs = WatchHistoryPayloadV2.normalizeTimestampMs(item.timestamp)
            val isWatchedEffective = item.isWatched || id in watchedSet || id in archivedIds
            val frac = if (isWatchedEffective) {
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
            result["${id}_is_watched"] = AndroidTypedEntry("boolean", isWatchedEffective)
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
            val alreadyWatched = epId in watchedSet || epId in archivedIds || linkedActive?.isWatched == true
            if (resTs >= existingEpTs) {
                result["${epId}_timestamp"] = AndroidTypedEntry("long", resTs)
                result["${epId}_pos"] = AndroidTypedEntry("long", resume.positionMs)
                result["${epId}_dur"] = AndroidTypedEntry("long", resume.durationMs)
                result["${epId}_fraction"] = AndroidTypedEntry("float", if (alreadyWatched) 1.0f else frac)
                val isCompleted = alreadyWatched || (resume.durationMs > 0L && resume.positionMs >= (resume.durationMs * 0.90f))
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

    /**
     * Pushes current volatile state directly to clotube/History/clotube_active_sync.json
     * (and performs an atomic pull-merge-upsert on clotube_archive.json whenever archive state changed
     * or overflow occurred during priority-protected active window pruning).
     */
    suspend fun executeDirectUpload(
        priority: SyncPriority = SyncPriority.CRITICAL_WATCH_STATE,
        updateArchive: Boolean = false
    ): Boolean = uploadMutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                AndroidWatchHistoryManager.ensureArchiveLoaded(appContext)
                val overflowFromLimit = AndroidWatchHistoryManager.enforceActiveWindowLimit(appContext)
                val pendingSync = AndroidWatchHistoryManager.consumePendingArchiveSync()

                val driveService = getDriveService() ?: return@withContext false
                val historyFolderId = resolveHistoryFolderId(driveService)

                val activeSnapshot = AndroidWatchHistoryManager.snapshotEntries()
                val rawActivePayload = WatchHistoryPayloadV2.fromTypedEntries(activeSnapshot)
                val partition = WatchHistoryPayloadV2.partitionActiveWindow(rawActivePayload.active)
                if (partition.overflowItems.isNotEmpty()) {
                    AndroidWatchHistoryManager.upsertArchivedItems(appContext, partition.overflowItems)
                }
                val activePayload = rawActivePayload
                    .copy(active = partition.activeItems)
                    .prunedToMaxBytes(MAX_ACTIVE_PAYLOAD_BYTES)

                val activePayloadJson = activePayload.toJsonString()
                val activeMedia = ByteArrayContent.fromString("application/json", activePayloadJson)

                val activeOk = upsertHistoryFile(
                    driveService = driveService,
                    historyFolderId = historyFolderId,
                    fileName = ACTIVE_SYNC_FILE,
                    cachedIdRef = cachedActiveFileId,
                    mediaContent = activeMedia,
                    priority = priority
                )

                val shouldUpdateArchive = updateArchive ||
                    overflowFromLimit ||
                    pendingSync ||
                    partition.overflowItems.isNotEmpty()

                if (shouldUpdateArchive) {
                    val remoteArchiveJson = downloadWithStaleIdRetry(
                        driveService = driveService,
                        historyFolderId = historyFolderId,
                        fileName = ARCHIVE_SYNC_FILE,
                        cachedIdRef = cachedArchiveFileId
                    )
                    val remoteArchive = if (remoteArchiveJson != null) {
                        runCatching { ArchivePayloadV2.fromJsonString(remoteArchiveJson) }.getOrNull()
                    } else {
                        null
                    }

                    val activeUnwatchedIds = activePayload.active
                        .filter { !it.isWatched && it.progressFraction < 0.90f }
                        .map { it.id }
                        .toSet()
                    val pendingDeleted = AndroidWatchHistoryManager.snapshotPendingDeletedFromArchiveIds()
                    val excludedIds = activeUnwatchedIds + pendingDeleted

                    val mergedItemsMap = linkedMapOf<String, HistoryItemEntityV2>()
                    remoteArchive?.items.orEmpty().forEach { remoteItem ->
                        if (remoteItem.id.isNotBlank() && remoteItem.id !in excludedIds) {
                            val existing = mergedItemsMap[remoteItem.id]
                            if (existing == null || remoteItem.timestamp >= existing.timestamp) {
                                mergedItemsMap[remoteItem.id] = remoteItem
                            }
                        }
                    }
                    AndroidWatchHistoryManager.snapshotArchivedItems().values.forEach { localItem ->
                        if (localItem.id.isNotBlank() && localItem.id !in excludedIds) {
                            val existing = mergedItemsMap[localItem.id]
                            if (existing == null || localItem.timestamp >= existing.timestamp) {
                                mergedItemsMap[localItem.id] = localItem
                            }
                        }
                    }

                    val mergedWatchedIds = linkedSetOf<String>()
                    remoteArchive?.watchedIds.orEmpty()
                        .filter { it.isNotBlank() && it !in excludedIds }
                        .forEach { mergedWatchedIds.add(it) }
                    AndroidWatchHistoryManager.snapshotArchivedIds()
                        .filter { it.isNotBlank() && it !in excludedIds }
                        .forEach { mergedWatchedIds.add(it) }
                    mergedItemsMap.values.forEach { item ->
                        if (item.isWatched || item.progressFraction >= 0.90f) {
                            mergedWatchedIds.add(item.id)
                        } else if (item.isInProgressForProtection) {
                            mergedWatchedIds.remove(item.id)
                        }
                    }

                    AndroidWatchHistoryManager.replaceArchiveFromCloud(
                        context = appContext,
                        items = mergedItemsMap.values,
                        watchedIds = mergedWatchedIds
                    )

                    val archivePayloadJson = ArchivePayloadV2(
                        version = 2,
                        updatedAt = System.currentTimeMillis(),
                        watchedIds = mergedWatchedIds.toList(),
                        items = mergedItemsMap.values.toList()
                    ).toJsonString()
                    val archiveMedia = ByteArrayContent.fromString("application/json", archivePayloadJson)
                    val archiveOk = upsertHistoryFile(
                        driveService = driveService,
                        historyFolderId = historyFolderId,
                        fileName = ARCHIVE_SYNC_FILE,
                        cachedIdRef = cachedArchiveFileId,
                        mediaContent = archiveMedia,
                        priority = priority
                    )
                    if (archiveOk) {
                        AndroidWatchHistoryManager.clearPendingDeletedFromArchiveIds(pendingDeleted)
                    }
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
