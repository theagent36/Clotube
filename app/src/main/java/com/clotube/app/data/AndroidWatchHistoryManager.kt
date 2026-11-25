package com.clotube.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class AndroidTypedEntry(
    val type: String,
    val value: Any
)

/**
 * Volatile RAM active window (capped at MAX_ACTIVE_WINDOW = 50 with priority protection for
 * in-progress items) paired with a local persistent archive store (`watch_history_archive.json`)
 * and cross-device Google Drive V2 sync.
 */
object AndroidWatchHistoryManager {
    private const val TAG = "ClotubeSync"
    private const val ARCHIVE_DISK_FILE_NAME = "watch_history_archive.json"

    private val volatileEntries = ConcurrentHashMap<String, AndroidTypedEntry>()
    private val archivedWatchedIds = ConcurrentHashMap.newKeySet<String>()
    private val archivedItems = ConcurrentHashMap<String, HistoryItemEntityV2>()
    private val pendingDeletedFromArchiveIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingArchiveSync = AtomicBoolean(false)

    private val entriesLock = Any()
    private val archiveDiskLock = Any()
    @Volatile
    private var archiveLoaded = false

    private val _stateRevision = MutableStateFlow(0L)
    val stateRevision: StateFlow<Long> = _stateRevision.asStateFlow()

    private val _localMutationClearedInProgressRevision = MutableStateFlow(0L)
    val localMutationClearedInProgressRevision: StateFlow<Long> = _localMutationClearedInProgressRevision.asStateFlow()

    private fun bumpRevision() {
        val now = System.currentTimeMillis()
        _stateRevision.value = maxOf(_stateRevision.value + 1L, now)
    }

    fun notifyLocalInProgressCleared() {
        _localMutationClearedInProgressRevision.value = maxOf(_localMutationClearedInProgressRevision.value + 1L, System.nanoTime())
    }

    fun consumePendingArchiveSync(): Boolean = pendingArchiveSync.getAndSet(false)

    fun markPendingArchiveSync() {
        pendingArchiveSync.set(true)
    }

    fun snapshotPendingDeletedFromArchiveIds(): Set<String> = HashSet(pendingDeletedFromArchiveIds)

    fun clearPendingDeletedFromArchiveIds(ids: Collection<String>) {
        if (ids.isNotEmpty()) {
            pendingDeletedFromArchiveIds.removeAll(ids.toSet())
        }
    }

    private fun getArchiveFile(context: Context): File {
        return File(context.applicationContext.filesDir, ARCHIVE_DISK_FILE_NAME)
    }

    fun ensureArchiveLoaded(context: Context) {
        if (archiveLoaded) return
        synchronized(archiveDiskLock) {
            if (archiveLoaded) return
            try {
                val file = getArchiveFile(context)
                if (file.exists() && file.length() > 0L) {
                    val raw = file.readText(Charsets.UTF_8)
                    val parsed = ArchivePayloadV2.fromJsonString(raw)
                    parsed.items.forEach { entity ->
                        if (entity.id.isNotBlank()) {
                            val existing = archivedItems[entity.id]
                            if (existing == null || entity.timestamp >= existing.timestamp) {
                                archivedItems[entity.id] = entity
                            }
                            if (entity.isWatched || entity.progressFraction >= 0.90f) {
                                archivedWatchedIds.add(entity.id)
                            }
                        }
                    }
                    parsed.watchedIds.forEach { id ->
                        if (id.isNotBlank() && archivedItems[id]?.isInProgressForProtection != true) {
                            archivedWatchedIds.add(id)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed loading local watch history archive: ${e.message}")
            } finally {
                archiveLoaded = true
            }
        }
    }

    fun persistArchiveToDisk(context: Context) {
        synchronized(archiveDiskLock) {
            try {
                val targetFile = getArchiveFile(context)
                val parent = targetFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                val payload = ArchivePayloadV2(
                    version = 2,
                    updatedAt = System.currentTimeMillis(),
                    watchedIds = archivedWatchedIds.toList(),
                    items = archivedItems.values.toList()
                ).toJsonString()
                val tempFile = File(parent, "$ARCHIVE_DISK_FILE_NAME.tmp")
                tempFile.writeText(payload, Charsets.UTF_8)
                if (!tempFile.renameTo(targetFile)) {
                    if (targetFile.exists()) targetFile.delete()
                    if (!tempFile.renameTo(targetFile)) {
                        targetFile.writeText(payload, Charsets.UTF_8)
                        tempFile.delete()
                    }
                }
                archiveLoaded = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed persisting watch history archive to disk: ${e.message}", e)
            }
        }
    }

    fun snapshotArchivedItems(): Map<String, HistoryItemEntityV2> = HashMap(archivedItems)

    fun getArchivedItem(fileId: String): HistoryItemEntityV2? {
        if (fileId.isBlank()) return null
        return archivedItems[fileId]
    }

    fun upsertArchivedItems(
        context: Context?,
        items: Collection<HistoryItemEntityV2>,
        extraWatchedIds: Collection<String> = emptyList()
    ) {
        var changed = false
        for (item in items) {
            if (item.id.isBlank()) continue
            val existing = archivedItems[item.id]
            if (existing == null || item.timestamp >= existing.timestamp) {
                archivedItems[item.id] = item
                changed = true
            }
            if (item.isWatched || item.progressFraction >= 0.90f) {
                if (archivedWatchedIds.add(item.id)) changed = true
                pendingDeletedFromArchiveIds.remove(item.id)
            } else if (item.isInProgressForProtection) {
                if (archivedWatchedIds.remove(item.id)) changed = true
            }
        }
        for (wid in extraWatchedIds) {
            if (wid.isNotBlank() && archivedItems[wid]?.isInProgressForProtection != true) {
                if (archivedWatchedIds.add(wid)) changed = true
                pendingDeletedFromArchiveIds.remove(wid)
            }
        }
        if (changed && context != null) {
            persistArchiveToDisk(context)
        }
    }

    fun replaceArchiveFromCloud(
        context: Context?,
        items: Collection<HistoryItemEntityV2>,
        watchedIds: Collection<String>
    ) {
        archivedItems.clear()
        archivedWatchedIds.clear()
        items.forEach { entity ->
            if (entity.id.isNotBlank()) {
                val existing = archivedItems[entity.id]
                if (existing == null || entity.timestamp >= existing.timestamp) {
                    archivedItems[entity.id] = entity
                }
                if (entity.isWatched || entity.progressFraction >= 0.90f) {
                    archivedWatchedIds.add(entity.id)
                }
            }
        }
        watchedIds.forEach { wid ->
            if (wid.isNotBlank() && archivedItems[wid]?.isInProgressForProtection != true) {
                archivedWatchedIds.add(wid)
            }
        }
        if (context != null) {
            persistArchiveToDisk(context)
        }
    }

    private fun writeEntityIntoTypedMap(
        target: MutableMap<String, AndroidTypedEntry?>,
        entity: HistoryItemEntityV2
    ) {
        val id = entity.id.trim()
        if (id.isBlank()) return
        val frac = if (entity.isWatched) 1.0f else entity.progressFraction
        target["${id}_title"] = AndroidTypedEntry("string", entity.title)
        target["${id}_pos"] = AndroidTypedEntry("long", entity.positionMs)
        target["${id}_dur"] = AndroidTypedEntry("long", entity.durationMs)
        target["${id}_fraction"] = AndroidTypedEntry("float", frac)
        target["${id}_timestamp"] = AndroidTypedEntry("long", entity.timestamp)
        target["${id}_is_watched"] = AndroidTypedEntry("boolean", entity.isWatched)
        target["${id}_is_series"] = AndroidTypedEntry("boolean", entity.isSeries)
        if (!entity.seriesId.isNullOrBlank()) {
            target["${id}_series_id"] = AndroidTypedEntry("string", entity.seriesId)
        }
        if (!entity.seriesTitle.isNullOrBlank()) {
            target["${id}_series_title"] = AndroidTypedEntry("string", entity.seriesTitle)
        }
        if (entity.seasonNumber != null && entity.seasonNumber > 0) {
            target["${id}_season_num"] = AndroidTypedEntry("int", entity.seasonNumber)
        }
        if (entity.episodeNumber != null && entity.episodeNumber > 0) {
            target["${id}_episode_num"] = AndroidTypedEntry("int", entity.episodeNumber)
        }
        if (!entity.thumbnailUrl.isNullOrBlank()) {
            target["${id}_thumbnail_url"] = AndroidTypedEntry("string", entity.thumbnailUrl)
            target["${id}_thumb"] = AndroidTypedEntry("string", entity.thumbnailUrl)
        }
    }

    private fun removeSeriesResumeKeys(
        target: MutableMap<String, AndroidTypedEntry?>,
        seriesId: String
    ) {
        val prefix = "series_resume_${seriesId}_"
        target.keys.filter { it.startsWith(prefix) }.forEach { target.remove(it) }
    }

    private fun enforceActiveWindowLimitLocked(context: Context?): Boolean {
        val allActive = WatchHistoryPayloadV2.fromTypedEntries(volatileEntries).active
        val partition = WatchHistoryPayloadV2.partitionActiveWindow(allActive)
        if (partition.overflowItems.isEmpty()) {
            return false
        }

        val overflowIds = partition.overflowItems.map { it.id }.toHashSet()
        val keysToRemove = volatileEntries.keys.filter { key ->
            !key.startsWith("series_resume_") &&
                !key.startsWith("global_") &&
                overflowIds.any { oid -> key.startsWith("${oid}_") }
        }
        keysToRemove.forEach { volatileEntries.remove(it) }

        // If a series_resume pointer still pointed to a completed overflowed episode, clean it up.
        val resumeEpKeys = volatileEntries.keys.filter {
            it.startsWith("series_resume_") && it.endsWith("_episode_id")
        }
        for (epKey in resumeEpKeys) {
            val sid = epKey.removePrefix("series_resume_").removeSuffix("_episode_id")
            val epId = (volatileEntries[epKey]?.value as? String)?.trim().orEmpty()
            if (epId in overflowIds) {
                val overflowEntity = partition.overflowItems.firstOrNull { it.id == epId }
                if (overflowEntity != null && (overflowEntity.isWatched || overflowEntity.progressFraction >= 0.90f)) {
                    volatileEntries.keys.filter { it.startsWith("series_resume_${sid}_") }.forEach {
                        volatileEntries.remove(it)
                    }
                }
            }
        }

        upsertArchivedItems(context, partition.overflowItems)
        pendingArchiveSync.set(true)
        return true
    }

    fun enforceActiveWindowLimit(context: Context? = null): Boolean {
        val overflowed = synchronized(entriesLock) {
            enforceActiveWindowLimitLocked(context)
        }
        if (overflowed) {
            bumpRevision()
        }
        return overflowed
    }

    /**
     * Checks if `series_resume_${seriesId}_episode_id` points to [watchedEpisodeId].
     * If so, re-points to the highest-timestamp unwatched in-progress episode in the same series
     * across active & archived entries, or deletes all `series_resume_${seriesId}_*` keys if none remain.
     */
    fun cleanupSeriesResumePointerIfNeeded(
        seriesId: String?,
        watchedEpisodeId: String,
        context: Context? = null
    ): Boolean {
        if (seriesId.isNullOrBlank() || watchedEpisodeId.isBlank()) return false
        var modified = false
        var archiveTouched = false

        synchronized(entriesLock) {
            val p = "series_resume_${seriesId}"
            val currentResumeEpId = (volatileEntries["${p}_episode_id"]?.value as? String)?.trim()
            if (currentResumeEpId != watchedEpisodeId) {
                return@synchronized
            }

            val activeItems = WatchHistoryPayloadV2.fromTypedEntries(volatileEntries).active
            val activeIds = activeItems.map { it.id }.toSet()
            val candidateFromActive = activeItems
                .filter { item ->
                    item.seriesId == seriesId &&
                        item.id != watchedEpisodeId &&
                        !item.isWatched &&
                        item.positionMs >= 2000L &&
                        item.progressFraction < 0.90f &&
                        item.id !in archivedWatchedIds
                }
                .maxByOrNull { it.timestamp }

            val candidateFromArchive = archivedItems.values
                .filter { item ->
                    item.seriesId == seriesId &&
                        item.id != watchedEpisodeId &&
                        item.id !in activeIds &&
                        !item.isWatched &&
                        item.positionMs >= 2000L &&
                        item.progressFraction < 0.90f &&
                        item.id !in archivedWatchedIds
                }
                .maxByOrNull { it.timestamp }

            val bestCandidate = when {
                candidateFromActive != null && candidateFromArchive != null ->
                    if (candidateFromActive.timestamp >= candidateFromArchive.timestamp) candidateFromActive else candidateFromArchive
                else -> candidateFromActive ?: candidateFromArchive
            }

            if (bestCandidate != null) {
                if (bestCandidate.id !in activeIds) {
                    val tempMap = HashMap<String, AndroidTypedEntry?>(volatileEntries)
                    writeEntityIntoTypedMap(tempMap, bestCandidate)
                    tempMap.forEach { (k, v) -> if (v != null) volatileEntries[k] = v }
                    if (archivedItems.remove(bestCandidate.id) != null) {
                        archiveTouched = true
                    }
                    archivedWatchedIds.remove(bestCandidate.id)
                }

                val existingSeriesTitle = (volatileEntries["${p}_series_title"]?.value as? String)?.ifBlank { null }
                    ?: bestCandidate.seriesTitle
                val existingThumb = bestCandidate.thumbnailUrl
                    ?: (volatileEntries["${bestCandidate.id}_thumbnail_url"]?.value as? String)
                    ?: (volatileEntries["${bestCandidate.id}_thumb"]?.value as? String)
                    ?: (volatileEntries["${p}_thumbnail_url"]?.value as? String)
                    ?: (volatileEntries["${p}_thumb"]?.value as? String)

                volatileEntries["${p}_episode_id"] = AndroidTypedEntry("string", bestCandidate.id)
                if (bestCandidate.title.isNotBlank()) {
                    volatileEntries["${p}_episode_title"] = AndroidTypedEntry("string", bestCandidate.title)
                }
                if (bestCandidate.seasonNumber != null && bestCandidate.seasonNumber > 0) {
                    volatileEntries["${p}_season_num"] = AndroidTypedEntry("int", bestCandidate.seasonNumber)
                }
                if (bestCandidate.episodeNumber != null && bestCandidate.episodeNumber > 0) {
                    volatileEntries["${p}_episode_num"] = AndroidTypedEntry("int", bestCandidate.episodeNumber)
                }
                volatileEntries["${p}_pos_ms"] = AndroidTypedEntry("long", bestCandidate.positionMs)
                volatileEntries["${p}_dur_ms"] = AndroidTypedEntry("long", bestCandidate.durationMs)
                volatileEntries["${p}_fraction"] = AndroidTypedEntry("float", bestCandidate.progressFraction)
                volatileEntries["${p}_timestamp"] = AndroidTypedEntry("long", bestCandidate.timestamp)
                if (!existingSeriesTitle.isNullOrBlank()) {
                    volatileEntries["${p}_series_title"] = AndroidTypedEntry("string", existingSeriesTitle)
                }
                if (!existingThumb.isNullOrBlank()) {
                    volatileEntries["${p}_thumbnail_url"] = AndroidTypedEntry("string", existingThumb)
                    volatileEntries["${p}_thumb"] = AndroidTypedEntry("string", existingThumb)
                }
                modified = true
            } else {
                val keysToDelete = volatileEntries.keys.filter { it.startsWith("${p}_") }
                if (keysToDelete.isNotEmpty()) {
                    keysToDelete.forEach { volatileEntries.remove(it) }
                    modified = true
                }
            }
        }

        if (archiveTouched && context != null) {
            persistArchiveToDisk(context)
            pendingArchiveSync.set(true)
        }
        if (modified) {
            bumpRevision()
        }
        return modified
    }

    fun getLatestTimestampMs(): Long {
        var maxTs = WatchHistoryPayloadV2.normalizeTimestampMs(
            (volatileEntries["global_latest_timestamp"]?.value as? Number)?.toLong() ?: 0L
        )
        for ((key, entry) in volatileEntries) {
            if (key.endsWith("_timestamp")) {
                val ts = WatchHistoryPayloadV2.normalizeTimestampMs((entry.value as? Number)?.toLong() ?: 0L)
                if (ts > maxTs) maxTs = ts
            }
        }
        return maxTs
    }

    /**
     * Re-seeds a newer local Continue Watching snapshot into volatile RAM without wiping
     * historical watchedIds or other series entries. Returns true if an archived ID was removed.
     */
    fun seedSnapshotEntryIfNewer(
        item: com.clotube.app.viewmodel.ContinueWatchingDisplayData,
        updatedAt: Long
    ): Boolean {
        val id = item.mediaId.trim()
        if (id.isBlank()) return false
        val normalizedTs = WatchHistoryPayloadV2.normalizeTimestampMs(updatedAt)
        val existingTs = WatchHistoryPayloadV2.normalizeTimestampMs(
            (volatileEntries["${id}_timestamp"]?.value as? Number)?.toLong() ?: 0L
        )
        val existingWatched = volatileEntries["${id}_is_watched"]?.value as? Boolean
        val needsReseed = !volatileEntries.containsKey("${id}_timestamp") ||
            existingTs < normalizedTs ||
            existingWatched == true
        if (!needsReseed) return false

        val wasArchived = archivedWatchedIds.remove(id) || (archivedItems.remove(id) != null)
        pendingDeletedFromArchiveIds.remove(id)
        val safeTitle = if (item.isSeries) {
            item.episodeTitle.ifBlank { item.displayTitle }
        } else {
            item.displayHeader.ifBlank { item.displayTitle }
        }
        val safeFraction = if (item.durationMs > 0L) {
            (item.currentPositionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            item.progressFraction.coerceIn(0f, 1f)
        }
        val cleanThumb = item.displayThumbnailUrl.trim()
            .takeIf { it.isNotBlank() && !com.clotube.app.data.metadata.OnlineMetadataResolver.isDriveVideoFrameUrl(it) }

        updateEntries {
            this["${id}_title"] = AndroidTypedEntry("string", safeTitle)
            this["${id}_pos"] = AndroidTypedEntry("long", item.currentPositionMs)
            this["${id}_dur"] = AndroidTypedEntry("long", item.durationMs)
            this["${id}_fraction"] = AndroidTypedEntry("float", safeFraction)
            this["${id}_timestamp"] = AndroidTypedEntry("long", normalizedTs)
            this["${id}_is_watched"] = AndroidTypedEntry("boolean", false)
            this["${id}_is_series"] = AndroidTypedEntry("boolean", item.isSeries)

            if (cleanThumb != null) {
                this["${id}_thumbnail_url"] = AndroidTypedEntry("string", cleanThumb)
                this["${id}_thumb"] = AndroidTypedEntry("string", cleanThumb)
            }

            if (item.isSeries) {
                val sId = item.seriesId?.trim()?.ifBlank { null }
                if (sId != null) {
                    this["${id}_series_id"] = AndroidTypedEntry("string", sId)
                }
                val sTitle = item.seriesTitle.ifBlank { item.displayHeader }
                if (sTitle.isNotBlank()) {
                    this["${id}_series_title"] = AndroidTypedEntry("string", sTitle)
                }
                if (item.seasonNumber > 0) {
                    this["${id}_season_num"] = AndroidTypedEntry("int", item.seasonNumber)
                }
                if (item.episodeNumber > 0) {
                    this["${id}_episode_num"] = AndroidTypedEntry("int", item.episodeNumber)
                }
                if (sId != null) {
                    val p = "series_resume_$sId"
                    val existingResumeTs = WatchHistoryPayloadV2.normalizeTimestampMs(
                        (this["${p}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                    )
                    if (normalizedTs >= existingResumeTs) {
                        this["${p}_episode_id"] = AndroidTypedEntry("string", id)
                        this["${p}_episode_title"] = AndroidTypedEntry("string", safeTitle)
                        if (item.seasonNumber > 0) this["${p}_season_num"] = AndroidTypedEntry("int", item.seasonNumber)
                        if (item.episodeNumber > 0) this["${p}_episode_num"] = AndroidTypedEntry("int", item.episodeNumber)
                        this["${p}_pos_ms"] = AndroidTypedEntry("long", item.currentPositionMs)
                        this["${p}_dur_ms"] = AndroidTypedEntry("long", item.durationMs)
                        this["${p}_fraction"] = AndroidTypedEntry("float", safeFraction)
                        this["${p}_timestamp"] = AndroidTypedEntry("long", normalizedTs)
                        if (sTitle.isNotBlank()) this["${p}_series_title"] = AndroidTypedEntry("string", sTitle)
                        if (cleanThumb != null) {
                            this["${p}_thumbnail_url"] = AndroidTypedEntry("string", cleanThumb)
                            this["${p}_thumb"] = AndroidTypedEntry("string", cleanThumb)
                        }
                    }
                }
            }
        }
        return wasArchived
    }

    /**
     * One-time cleanup of all legacy local watch history JSON files and DataStore protobufs
     * so stale cached history can never resurrect or conflict with Google Drive.
     * Intentionally preserves cw_hero_snapshot.json and watch_history_archive.json.
     */
    fun purgeAllLocalDiskCaches(context: Context) {
        try {
            val appCtx = context.applicationContext
            val profilesDir = File(appCtx.filesDir, "profiles")
            if (profilesDir.exists() && profilesDir.isDirectory) {
                profilesDir.walkTopDown().forEach { file ->
                    if (file.isFile &&
                        file.name != "cw_hero_snapshot.json" &&
                        file.name != ARCHIVE_DISK_FILE_NAME &&
                        (
                            file.name == "clotube_watch_history.json" ||
                            file.name == "clotube_active_sync.json" ||
                            file.name == "clotube_archive.json" ||
                            file.name == "clotube_history.json"
                        )
                    ) {
                        file.delete()
                    }
                }
            }

            val datastoreDir = File(appCtx.filesDir, "datastore")
            if (datastoreDir.exists() && datastoreDir.isDirectory) {
                datastoreDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("clotube_playback_history")) {
                        file.delete()
                    }
                }
            }
            Log.i(TAG, "Purged all legacy local watch history disk caches (preserved snapshot & archive)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed purging legacy local history files: ${e.message}")
        }
    }

    const val KEY_SUFFIX_THUMBNAIL_URL = "_thumbnail_url"
    const val KEY_SUFFIX_THUMB = "_thumb"
    const val KEY_SUFFIX_TITLE = "_title"
    const val KEY_SUFFIX_TIMESTAMP = "_timestamp"

    fun thumbnailKey(fileId: String): String = "${fileId}$KEY_SUFFIX_THUMBNAIL_URL"
    fun legacyThumbKey(fileId: String): String = "${fileId}$KEY_SUFFIX_THUMB"
    fun titleKey(fileId: String): String = "${fileId}$KEY_SUFFIX_TITLE"
    fun timestampKey(fileId: String): String = "${fileId}$KEY_SUFFIX_TIMESTAMP"

    fun getThumbnailUrl(fileId: String): String? {
        if (fileId.isBlank()) return null
        return (volatileEntries["${fileId}_thumbnail_url"]?.value as? String)?.takeIf { it.isNotBlank() }
            ?: (volatileEntries["${fileId}_thumb"]?.value as? String)?.takeIf { it.isNotBlank() }
    }

    fun saveThumbnailUrl(fileId: String, thumbnailUrl: String) {
        if (fileId.isBlank() || thumbnailUrl.isBlank()) return
        val existing = getThumbnailUrl(fileId)
        val isNewDriveUrl = com.clotube.app.data.metadata.OnlineMetadataResolver.isDriveVideoFrameUrl(thumbnailUrl)
        val isExistingDriveUrl = existing != null && com.clotube.app.data.metadata.OnlineMetadataResolver.isDriveVideoFrameUrl(existing)
        if (isNewDriveUrl && existing != null && !isExistingDriveUrl) {
            return
        }
        updateEntries {
            this["${fileId}_thumbnail_url"] = AndroidTypedEntry("string", thumbnailUrl)
            this["${fileId}_thumb"] = AndroidTypedEntry("string", thumbnailUrl)
        }
    }

    fun snapshotEntries(): Map<String, AndroidTypedEntry> = HashMap(volatileEntries)

    fun snapshotArchivedIds(): Set<String> = HashSet(archivedWatchedIds)

    fun replaceAllFromDrive(
        activeEntries: Map<String, AndroidTypedEntry>,
        archiveIds: Set<String>,
        archivedItemList: Collection<HistoryItemEntityV2> = emptyList(),
        context: Context? = null
    ) {
        synchronized(entriesLock) {
            volatileEntries.clear()
            volatileEntries.putAll(activeEntries)

            archivedWatchedIds.clear()
            archivedWatchedIds.addAll(archiveIds)

            archivedItems.clear()
            archivedItemList.forEach { entity ->
                if (entity.id.isNotBlank()) {
                    val existing = archivedItems[entity.id]
                    if (existing == null || entity.timestamp >= existing.timestamp) {
                        archivedItems[entity.id] = entity
                    }
                    if (entity.isWatched || entity.progressFraction >= 0.90f) {
                        archivedWatchedIds.add(entity.id)
                    } else if (entity.isInProgressForProtection) {
                        archivedWatchedIds.remove(entity.id)
                    }
                }
            }
            enforceActiveWindowLimitLocked(context)
        }

        if (context != null) {
            persistArchiveToDisk(context)
        }
        bumpRevision()
    }

    fun updateEntries(
        context: Context? = null,
        mutations: MutableMap<String, AndroidTypedEntry?>.() -> Unit
    ): Boolean {
        val overflowed = synchronized(entriesLock) {
            val working = HashMap<String, AndroidTypedEntry?>(volatileEntries)
            working.mutations()
            volatileEntries.clear()
            working.forEach { (k, v) ->
                if (v != null) volatileEntries[k] = v
            }
            enforceActiveWindowLimitLocked(context)
        }
        bumpRevision()
        return overflowed
    }

    fun clearInMemory(context: Context? = null) {
        synchronized(entriesLock) {
            volatileEntries.clear()
            archivedWatchedIds.clear()
            archivedItems.clear()
            pendingDeletedFromArchiveIds.clear()
            pendingArchiveSync.set(false)
        }
        if (context != null) {
            synchronized(archiveDiskLock) {
                try {
                    val f = getArchiveFile(context)
                    val tmp = File(f.parentFile, "$ARCHIVE_DISK_FILE_NAME.tmp")
                    if (tmp.exists()) tmp.delete()
                    if (f.exists()) f.delete()
                } catch (_: Exception) {}
                archiveLoaded = true
            }
        }
        bumpRevision()
    }

    // Backwards-compatible RAM-only overloads for existing callers
    fun loadLocalSync(
        @Suppress("UNUSED_PARAMETER") context: Context? = null,
        @Suppress("UNUSED_PARAMETER") email: String = ""
    ): MutableMap<String, AndroidTypedEntry> = HashMap(volatileEntries)

    fun clearLocal(
        context: Context? = null,
        @Suppress("UNUSED_PARAMETER") email: String = ""
    ) {
        clearInMemory(context)
    }

    fun isEpisodeWatched(fileId: String): Boolean {
        if (fileId.isBlank()) return false
        val explicit = volatileEntries["${fileId}_is_watched"]?.value as? Boolean
        if (explicit != null) return explicit
        val archivedEntity = archivedItems[fileId]
        if (archivedEntity != null) {
            if (archivedEntity.isWatched || archivedEntity.progressFraction >= 0.90f) return true
            if (archivedEntity.isInProgressForProtection) return false
        }
        if (archivedWatchedIds.contains(fileId)) return true
        val fraction = (volatileEntries["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f
        return fraction >= 0.90f
    }

    fun isEpisodeWatched(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") email: String,
        fileId: String
    ): Boolean = isEpisodeWatched(fileId)

    fun getWatchedEpisodeIds(): Set<String> {
        val watched = HashSet(archivedWatchedIds)
        for ((id, item) in archivedItems) {
            if (item.isWatched || item.progressFraction >= 0.90f) {
                watched.add(id)
            } else if (item.isInProgressForProtection) {
                watched.remove(id)
            }
        }
        for ((key, entry) in volatileEntries) {
            if (key.endsWith("_is_watched")) {
                val id = key.removeSuffix("_is_watched")
                if ((entry.value as? Boolean) == true) {
                    watched.add(id)
                } else {
                    watched.remove(id)
                }
            } else if (key.endsWith("_fraction")) {
                val id = key.removeSuffix("_fraction")
                val explicit = volatileEntries["${id}_is_watched"]?.value as? Boolean
                val frac = (entry.value as? Number)?.toFloat() ?: 0f
                if (explicit == null && frac >= 0.90f) {
                    watched.add(id)
                }
            }
        }
        return watched
    }

    fun getWatchedEpisodeIds(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") email: String = ""
    ): Set<String> = getWatchedEpisodeIds()

    fun setEpisodeWatched(
        context: Context,
        fileId: String,
        isWatched: Boolean,
        title: String = "",
        durationMs: Long = 0L,
        isSeries: Boolean = false,
        seriesId: String? = null,
        seasonNumber: Int = 0,
        episodeNumber: Int = 0
    ) {
        if (fileId.isBlank()) return
        val now = System.currentTimeMillis()
        ensureArchiveLoaded(context)
        val removedWatchedId = archivedWatchedIds.remove(fileId)
        val removedArchivedItem = archivedItems.remove(fileId) != null
        val wasArchived = removedWatchedId || removedArchivedItem
        if (!isWatched && wasArchived) {
            pendingDeletedFromArchiveIds.add(fileId)
            persistArchiveToDisk(context)
            pendingArchiveSync.set(true)
        } else if (isWatched) {
            pendingDeletedFromArchiveIds.remove(fileId)
        }

        val overflowFromUpdate = updateEntries(context) {
            this["${fileId}_is_watched"] = AndroidTypedEntry("boolean", isWatched)
            this["${fileId}_timestamp"] = AndroidTypedEntry("long", now)
            if (title.isNotBlank()) {
                this["${fileId}_title"] = AndroidTypedEntry("string", title)
            }
            if (isWatched) {
                if (durationMs > 0L) {
                    this["${fileId}_pos"] = AndroidTypedEntry("long", durationMs)
                    this["${fileId}_dur"] = AndroidTypedEntry("long", durationMs)
                }
                this["${fileId}_fraction"] = AndroidTypedEntry("float", 1.0f)
            } else {
                this["${fileId}_pos"] = AndroidTypedEntry("long", 0L)
                this["${fileId}_fraction"] = AndroidTypedEntry("float", 0.0f)
            }
            if (isSeries && !seriesId.isNullOrBlank()) {
                this["${fileId}_is_series"] = AndroidTypedEntry("boolean", true)
                this["${fileId}_series_id"] = AndroidTypedEntry("string", seriesId)
                if (seasonNumber > 0) this["${fileId}_season_num"] = AndroidTypedEntry("int", seasonNumber)
                if (episodeNumber > 0) this["${fileId}_episode_num"] = AndroidTypedEntry("int", episodeNumber)
            }
        }

        if (isWatched && isSeries && !seriesId.isNullOrBlank()) {
            cleanupSeriesResumePointerIfNeeded(seriesId, fileId, context)
        }

        val overflowed = enforceActiveWindowLimit(context) || overflowFromUpdate

        DriveWatchHistorySync.pushHistoryAsync(
            context = context,
            force = true,
            updateArchive = wasArchived || overflowed
        )
    }

    fun setEpisodeWatched(
        context: Context,
        @Suppress("UNUSED_PARAMETER") email: String,
        fileId: String,
        isWatched: Boolean,
        title: String = "",
        durationMs: Long = 0L,
        isSeries: Boolean = false,
        seriesId: String? = null,
        seasonNumber: Int = 0,
        episodeNumber: Int = 0
    ) {
        setEpisodeWatched(
            context = context,
            fileId = fileId,
            isWatched = isWatched,
            title = title,
            durationMs = durationMs,
            isSeries = isSeries,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber
        )
    }

    fun removeEntriesForMedia(
        mediaId: String,
        seriesId: String?,
        context: Context? = null
    ): Boolean {
        val removedWatchedId = archivedWatchedIds.remove(mediaId)
        val removedArchivedItem = archivedItems.remove(mediaId) != null
        val removedFromArchive = removedWatchedId || removedArchivedItem
        if (removedFromArchive) {
            pendingDeletedFromArchiveIds.add(mediaId)
            pendingArchiveSync.set(true)
            if (context != null) {
                persistArchiveToDisk(context)
            }
        }
        updateEntries(context) {
            val toDelete = keys.filter { key ->
                key.startsWith("${mediaId}_") ||
                    (!seriesId.isNullOrBlank() && key.startsWith("series_resume_${seriesId}_"))
            }
            toDelete.forEach { remove(it) }
        }
        return removedFromArchive
    }

    fun removeEntriesForMedia(
        context: Context,
        @Suppress("UNUSED_PARAMETER") email: String,
        mediaId: String,
        seriesId: String?
    ): Boolean = removeEntriesForMedia(mediaId, seriesId, context)

    fun isV2Payload(json: String): Boolean {
        if (json.isBlank()) return false
        return try {
            val root = JSONObject(json)
            root.optInt("v", 0) >= 2 ||
                (!root.has("entries") && (root.has("active") || root.has("watchedIds")))
        } catch (_: Exception) {
            false
        }
    }
}
