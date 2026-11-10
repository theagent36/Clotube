package com.clotube.app.data

import android.accounts.Account
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class SyncPriority(val level: Int) {
    CRITICAL_WATCH_STATE(1), // Mark / Unmark watched
    TIMESTAMPS_AND_SEEK(2),  // Progress & resume timestamps
    BACKGROUND_SYNC(3)       // Periodic remote pull
}

class DriveWatchHistorySync(private val context: Context) {

    private val appContext: Context = context.applicationContext
    private val TAG = "ClotubeSync"
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cachedFileId = AtomicReference<String?>(null)
    private val cachedHistoryFolderId = AtomicReference<String?>(null)
    private val lastUploadedHash = AtomicReference<String>("")
    private val uploadMutex = Mutex()
    private val lastUploadTime = AtomicLong(0L)

    private data class SyncTarget(
        val fileId: String?,
        val parentFolderId: String,
        val isV2: Boolean
    )

    fun invalidateCache() {
        cachedFileId.set(null)
        cachedHistoryFolderId.set(null)
        lastUploadedHash.set("")
    }

    private suspend fun resolveHistoryFolderId(driveService: Drive): String = withContext(Dispatchers.IO) {
        cachedHistoryFolderId.get()?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val dirs = DriveDirectoryManager.resolveHierarchy(driveService)
        cachedHistoryFolderId.set(dirs.historyFolderId)
        dirs.historyFolderId
    }

    private suspend fun resolveTarget(driveService: Drive): SyncTarget = withContext(Dispatchers.IO) {
        val historyFolderId = resolveHistoryFolderId(driveService)

        cachedFileId.get()?.takeIf { it.isNotBlank() }?.let { cachedId ->
            return@withContext SyncTarget(cachedId, historyFolderId, isV2 = true)
        }

        val v2Res = driveService.files().list()
            .setQ("'$historyFolderId' in parents and name = '$ACTIVE_SYNC_FILE' and trashed = false")
            .setFields("files(id)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(1)
            .execute()

        val v2Id = v2Res.files?.firstOrNull()?.id
        if (v2Id != null) {
            cachedFileId.set(v2Id)
            return@withContext SyncTarget(v2Id, historyFolderId, isV2 = true)
        }

        val scopedLegacy = driveService.files().list()
            .setQ("'$historyFolderId' in parents and name = '$LEGACY_SYNC_FILE' and trashed = false")
            .setFields("files(id)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(1)
            .execute()

        val scopedLegacyId = scopedLegacy.files?.firstOrNull()?.id
        if (scopedLegacyId != null) {
            return@withContext SyncTarget(scopedLegacyId, historyFolderId, isV2 = false)
        }

        val rootLegacyId = findLegacyRootHistoryFileId(driveService)
        SyncTarget(rootLegacyId, historyFolderId, isV2 = false)
    }

    // Priority-based task request holder
    private class SyncTask(
        val priority: SyncPriority,
        val dataStore: DataStore<Preferences>,
        val onComplete: CompletableDeferred<Boolean>? = null
    ) : Comparable<SyncTask> {
        override fun compareTo(other: SyncTask): Int = this.priority.level.compareTo(other.priority.level)
    }

    private val taskChannel = Channel<SyncTask>(Channel.UNLIMITED)

    init {
        // Background Priority Queue Consumer
        syncScope.launch {
            while (isActive) {
                val firstTask = taskChannel.receive()
                val pending = mutableListOf<SyncTask>()
                pending.add(firstTask)

                // Drain any immediately available tasks to sort by priority
                var next = taskChannel.tryReceive().getOrNull()
                while (next != null) {
                    pending.add(next)
                    next = taskChannel.tryReceive().getOrNull()
                }

                // Sort: CRITICAL_WATCH_STATE (1) first, TIMESTAMPS_AND_SEEK (2), BACKGROUND_SYNC (3)
                pending.sort()

                val criticalTasks = pending.filter { it.priority == SyncPriority.CRITICAL_WATCH_STATE }
                val progressTasks = pending.filter { it.priority == SyncPriority.TIMESTAMPS_AND_SEEK }
                val backgroundTasks = pending.filter { it.priority == SyncPriority.BACKGROUND_SYNC }

                // 1. Process all Critical tasks first (mark/unmark watched)
                for (task in criticalTasks) {
                    try {
                        val success = executeDirectUploadInternal(task.dataStore, task.priority)
                        task.onComplete?.complete(success)
                    } catch (e: Exception) {
                        Log.e(TAG, "SyncTask failure: ${e.message}", e)
                        task.onComplete?.complete(false)
                    }
                }

                // 2. Process latest progress task (deduplicating redundant progress snapshots)
                if (progressTasks.isNotEmpty()) {
                    val latestProgress = progressTasks.last()
                    progressTasks.dropLast(1).forEach { it.onComplete?.complete(true) }
                    try {
                        val success = executeDirectUploadInternal(latestProgress.dataStore, latestProgress.priority)
                        latestProgress.onComplete?.complete(success)
                    } catch (e: Exception) {
                        Log.e(TAG, "Progress task failure: ${e.message}", e)
                        latestProgress.onComplete?.complete(false)
                    }
                }

                // 3. Process background reconciliation tasks
                for (task in backgroundTasks) {
                    try {
                        val success = executeDirectUploadInternal(task.dataStore, task.priority)
                        task.onComplete?.complete(success)
                    } catch (e: Exception) {
                        Log.e(TAG, "Background task failure: ${e.message}", e)
                        task.onComplete?.complete(false)
                    }
                }
            }
        }
    }

    /**
     * Priority 1: High-priority immediate dispatch for marking watched/unwatched.
     * Bypasses debouncing and pulls; uploads the local DataStore snapshot directly within 100ms.
     * Runs under NonCancellable so app backgrounding cannot cancel an in-flight HTTP request.
     */
    fun enqueueImmediateWatchState(dataStore: DataStore<Preferences>) {
        syncScope.launch(NonCancellable) {
            Log.d(TAG, "[P1 - CRITICAL] Enqueuing immediate watch status push to Drive...")
            val deferred = CompletableDeferred<Boolean>()
            taskChannel.send(SyncTask(SyncPriority.CRITICAL_WATCH_STATE, dataStore, deferred))
            deferred.await()
        }
    }

    /**
     * Priority 2: Periodic timestamp update (debounced/queued every 8-10 seconds).
     */
    fun enqueueProgressUpdate(dataStore: DataStore<Preferences>) {
        val now = System.currentTimeMillis()
        if (now - lastUploadTime.get() < 8000L) {
            return // Throttled: progress updates wait at least 8 seconds between uploads
        }
        syncScope.launch(NonCancellable) {
            taskChannel.send(SyncTask(SyncPriority.TIMESTAMPS_AND_SEEK, dataStore))
        }
    }

    /**
     * Flushes progress immediately on pause or player exit.
     */
    fun flushProgressImmediately(dataStore: DataStore<Preferences>) {
        syncScope.launch(NonCancellable) {
            taskChannel.send(SyncTask(SyncPriority.TIMESTAMPS_AND_SEEK, dataStore))
        }
    }

    /**
     * Priority 3: Low-priority background sync and remote pull.
     */
    fun enqueueBackgroundSync(dataStore: DataStore<Preferences>) {
        syncScope.launch(NonCancellable) {
            taskChannel.send(SyncTask(SyncPriority.BACKGROUND_SYNC, dataStore))
        }
    }

    /**
     * Legacy compatibility bridge for existing callers.
     */
    fun backupImmediately(dataStore: DataStore<Preferences>? = null) {
        val target = dataStore ?: appContext.playbackDataStore
        enqueueImmediateWatchState(target)
    }

    fun triggerThrottledBackup(dataStore: DataStore<Preferences>) {
        enqueueProgressUpdate(dataStore)
    }

    /**
     * Triggers an immediate force-upload to Drive.
     *
     * When force=true the enqueued task is processed as CRITICAL_WATCH_STATE priority,
     * which causes executeDirectUploadInternal to skip both the 15-second debounce window
     * AND the hash-equality guard, uploading the current payload unconditionally.
     */
    fun pushHistoryAsync(dataStore: DataStore<Preferences>? = null, force: Boolean = false) {
        val target = dataStore ?: appContext.playbackDataStore
        enqueueImmediateWatchState(target)
    }

    suspend fun executeDirectUpload(dataStore: DataStore<Preferences>? = null): Boolean {
        val target = dataStore ?: appContext.playbackDataStore
        return executeDirectUploadInternal(target, SyncPriority.CRITICAL_WATCH_STATE)
    }

    private suspend fun buildV2PayloadJson(dataStore: DataStore<Preferences>): String = withContext(Dispatchers.IO) {
        val allEntries = dataStore.data.first().asMap()
        val combined = mutableMapOf<String, AndroidTypedEntry>()

        allEntries.forEach { (key, value) ->
            val typed = when (value) {
                is Long -> AndroidTypedEntry("long", value)
                is Int -> AndroidTypedEntry("int", value)
                is Float -> AndroidTypedEntry("float", value)
                is Boolean -> AndroidTypedEntry("boolean", value)
                is String -> AndroidTypedEntry("string", value)
                else -> null
            }
            if (typed != null) {
                combined[key.name] = typed
            }
        }

        val email = GoogleSignIn.getLastSignedInAccount(appContext)?.email.orEmpty()
        if (email.isNotBlank()) {
            val localProfileEntries = AndroidWatchHistoryManager.loadLocalSync(appContext, email)
            val snapshot = synchronized(localProfileEntries) { localProfileEntries.toMap() }
            snapshot.forEach { (keyName, entry) ->
                val tsKey = resolveTimestampKey(keyName)
                val profileTs = (snapshot[tsKey]?.value as? Number)?.toLong() ?: 0L
                val dsTs = (combined[tsKey]?.value as? Number)?.toLong() ?: 0L
                if (!combined.containsKey(keyName) || profileTs > dsTs) {
                    combined[keyName] = entry
                }
            }
        }

        AndroidWatchHistoryManager.buildV2PayloadFromEntries(combined).toJsonString()
    }

    /**
     * Core upload worker. Runs under [NonCancellable] + [Dispatchers.IO] to guarantee
     * that in-flight Drive HTTP calls are never interrupted by coroutine cancellation
     * (e.g., user navigating away while an unwatch is being uploaded).
     *
     * Debounce / hash-guard bypass:
     * - When [priority] == [SyncPriority.CRITICAL_WATCH_STATE], both guards are skipped
     *   unconditionally, so the payload is always uploaded regardless of timing or content.
     * - For [SyncPriority.TIMESTAMPS_AND_SEEK] and [SyncPriority.BACKGROUND_SYNC], the
     *   hash equality check is applied, but the 15-second wall-clock gate is NOT applied
     *   here (the caller-side [enqueueProgressUpdate] already enforces the 8-second gate).
     */
    private suspend fun executeDirectUploadInternal(
        dataStore: DataStore<Preferences>,
        priority: SyncPriority
    ): Boolean = uploadMutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val driveService = getDriveService()
                if (driveService == null) {
                    Log.e(TAG, "Sync aborted: Google Drive service unavailable")
                    return@withContext false
                }

                var target = resolveTarget(driveService)

                // For background sync, perform remote pull and merge first
                if (priority == SyncPriority.BACKGROUND_SYNC && target.fileId != null) {
                    try {
                        val outputStream = ByteArrayOutputStream()
                        driveService.files().get(target.fileId)
                            .setSupportsAllDrives(true)
                            .executeMediaAndDownloadTo(outputStream)
                        val remoteJson = outputStream.toString("UTF-8")
                        if (remoteJson.isNotBlank()) {
                            mergeRemotePayload(dataStore, remoteJson)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Background pre-pull failed: ${e.message}")
                    }
                }

                val payloadJson = buildV2PayloadJson(dataStore)
                val payloadHash = payloadJson.hashCode().toString()

                // CRITICAL_WATCH_STATE bypasses BOTH the debounce timer AND the hash-equality check,
                // ensuring that mark/unmark operations always reach Drive regardless of recent uploads
                // or identical-looking payloads from an immediately preceding background sync.
                if (priority != SyncPriority.CRITICAL_WATCH_STATE &&
                    target.isV2 &&
                    target.fileId != null &&
                    payloadHash == lastUploadedHash.get()
                ) {
                    return@withContext true
                }

                val mediaContent = ByteArrayContent.fromString("application/json", payloadJson)
                Log.d(TAG, "[${priority.name}] Uploading v2 payload (${mediaContent.length} bytes) to clotube/History/$ACTIVE_SYNC_FILE...")

                if (target.fileId != null && target.isV2) {
                    try {
                        driveService.files().update(target.fileId, null, mediaContent)
                            .setSupportsAllDrives(true)
                            .execute()
                        cachedFileId.set(target.fileId)
                        lastUploadedHash.set(payloadHash)
                        lastUploadTime.set(System.currentTimeMillis())
                        Log.i(TAG, "SUCCESS! [${priority.name}] Updated $ACTIVE_SYNC_FILE in clotube/History/ (${target.fileId}, ${mediaContent.length} bytes)")
                        return@withContext true
                    } catch (e: GoogleJsonResponseException) {
                        if (e.statusCode == 404 || (e.statusCode == 403 && e.details?.errors?.any { it.reason == "appNotAuthorizedToFile" } == true)) {
                            invalidateCache()
                            target = resolveTarget(driveService)
                        } else {
                            throw e
                        }
                    }
                }

                if (target.fileId != null && target.isV2) {
                    driveService.files().update(target.fileId, null, mediaContent)
                        .setSupportsAllDrives(true)
                        .execute()
                    cachedFileId.set(target.fileId)
                    lastUploadedHash.set(payloadHash)
                    lastUploadTime.set(System.currentTimeMillis())
                    Log.i(TAG, "SUCCESS! [${priority.name}] Updated $ACTIVE_SYNC_FILE in clotube/History/ (${target.fileId}, ${mediaContent.length} bytes)")
                    return@withContext true
                }

                // File does not exist in clotube/History/: create clotube_active_sync.json
                val metadata = com.google.api.services.drive.model.File().apply {
                    name = ACTIVE_SYNC_FILE
                    mimeType = "application/json"
                    parents = listOf(target.parentFolderId)
                }
                val created = driveService.files().create(metadata, mediaContent)
                    .setSupportsAllDrives(true)
                    .execute()

                cachedFileId.set(created.id)
                lastUploadedHash.set(payloadHash)
                lastUploadTime.set(System.currentTimeMillis())
                Log.i(TAG, "SUCCESS! Created $ACTIVE_SYNC_FILE in clotube/History/ (fileId: ${created.id}, folderId: ${target.parentFolderId}, ${mediaContent.length} bytes)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Direct cloud sync failed: ${e.message}", e)
                false
            }
        }
    }

    fun resolveTimestampKey(key: String): String {
        return if (key.startsWith("series_resume_")) {
            val suffix = when {
                key.endsWith("_series_title") -> "_series_title"
                key.endsWith("_episode_id") -> "_episode_id"
                key.endsWith("_episode_title") -> "_episode_title"
                key.endsWith("_season_num") -> "_season_num"
                key.endsWith("_episode_num") -> "_episode_num"
                key.endsWith("_pos_ms") -> "_pos_ms"
                key.endsWith("_dur_ms") -> "_dur_ms"
                key.endsWith("_fraction") -> "_fraction"
                key.endsWith("_thumb") -> "_thumb"
                key.endsWith("_timestamp") -> "_timestamp"
                else -> ""
            }
            if (suffix.isNotEmpty()) {
                val seriesId = key.removePrefix("series_resume_").removeSuffix(suffix)
                "series_resume_${seriesId}_timestamp"
            } else {
                "${key.substringBeforeLast('_')}_timestamp"
            }
        } else if (key.startsWith("snapshot_")) {
            "snapshot_timestamp"
        } else if (key.startsWith("global_") || key.startsWith("last_global_")) {
            "global_latest_timestamp"
        } else {
            val suffix = when {
                key.endsWith("_title") -> "_title"
                key.endsWith("_pos") -> "_pos"
                key.endsWith("_dur") -> "_dur"
                key.endsWith("_fraction") -> "_fraction"
                key.endsWith("_timestamp") -> "_timestamp"
                key.endsWith("_is_series") -> "_is_series"
                key.endsWith("_is_watched") -> "_is_watched"
                key.endsWith("_series_id") -> "_series_id"
                key.endsWith("_series_title") -> "_series_title"
                key.endsWith("_season_num") -> "_season_num"
                key.endsWith("_episode_num") -> "_episode_num"
                key.endsWith("_thumb") -> "_thumb"
                else -> ""
            }
            if (suffix.isNotEmpty()) {
                val fileId = key.removeSuffix(suffix)
                "${fileId}_timestamp"
            } else {
                "${key.substringBeforeLast('_')}_timestamp"
            }
        }
    }

    /**
     * Merges a remote payload into the local DataStore and the profile-scoped file cache.
     *
     * For V2 payloads, the merge now faithfully propagates unwatch events originating from
     * Android or Desktop:
     * - Active items whose remote timestamp wins are written with their exact `isWatched` flag,
     *   so a remote isWatched=false clears the local `_is_watched` DataStore key.
     * - `watchedIds`-only entries (no corresponding active item) are merged additively with the
     *   existing timestamp-wins guard.
     */
    suspend fun mergeRemotePayload(dataStore: DataStore<Preferences>, remoteJson: String): Boolean {
        if (remoteJson.isBlank()) return false
        return try {
            val email = GoogleSignIn.getLastSignedInAccount(appContext)?.email.orEmpty()

            if (AndroidWatchHistoryManager.isV2Payload(remoteJson)) {
                val payload = WatchHistoryPayloadV2.fromJsonString(remoteJson)
                val activeById = payload.active.associateBy { it.id }
                val remoteWatchedSet = payload.watchedIds.toSet()

                dataStore.edit { prefs ->
                    val existingKeyNames = prefs.asMap().keys.map { it.name }.toSet()

                    // 1. Reconcile active items: when remote timestamp wins, write the exact
                    //    isWatched state from remote (supports unwatch propagation).
                    for (item in payload.active) {
                        if (item.id.isBlank()) continue
                        val localTs = prefs[longPreferencesKey("${item.id}_timestamp")] ?: 0L
                        val isMissing = !existingKeyNames.contains("${item.id}_pos") &&
                            !existingKeyNames.contains("${item.id}_is_watched")
                        if (item.timestamp > localTs || isMissing) {
                            if (item.title.isNotBlank()) {
                                prefs[stringPreferencesKey("${item.id}_title")] = item.title
                            }
                            // For unwatched items reset pos/fraction to 0 so progress bars clear
                            val effectivePos = if (item.isWatched) item.positionMs else 0L
                            val effectiveFrac = if (item.isWatched) {
                                if (item.durationMs > 0L)
                                    (item.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
                                else 1.0f
                            } else {
                                0.0f
                            }
                            prefs[longPreferencesKey("${item.id}_pos")] = effectivePos
                            prefs[longPreferencesKey("${item.id}_dur")] = item.durationMs
                            prefs[floatPreferencesKey("${item.id}_fraction")] = effectiveFrac
                            prefs[longPreferencesKey("${item.id}_timestamp")] = item.timestamp
                            // Authoritative watched state: item flag OR in remoteWatchedSet
                            prefs[booleanPreferencesKey("${item.id}_is_watched")] =
                                item.isWatched || item.id in remoteWatchedSet
                            val isSeries = !item.seriesId.isNullOrBlank() ||
                                item.seasonNumber != null || item.episodeNumber != null
                            prefs[booleanPreferencesKey("${item.id}_is_series")] = isSeries
                            if (!item.seriesId.isNullOrBlank()) {
                                prefs[stringPreferencesKey("${item.id}_series_id")] = item.seriesId
                            }
                            if (item.seasonNumber != null) {
                                prefs[intPreferencesKey("${item.id}_season_num")] = item.seasonNumber
                            }
                            if (item.episodeNumber != null) {
                                prefs[intPreferencesKey("${item.id}_episode_num")] = item.episodeNumber
                            }
                        }
                    }

                    // 2. For IDs in remoteWatchedSet that have no active entry, merge additively
                    //    with timestamp-wins guard (these are historical, never-played-again IDs).
                    val coveredByActive = payload.active.map { it.id }.toSet()
                    for (id in remoteWatchedSet) {
                        if (id.isBlank() || id in coveredByActive) continue
                        val localTs = prefs[longPreferencesKey("${id}_timestamp")] ?: 0L
                        val localExplicit = prefs[booleanPreferencesKey("${id}_is_watched")]
                        if (localExplicit != false || payload.updatedAt > localTs) {
                            prefs[booleanPreferencesKey("${id}_is_watched")] = true
                            if (!existingKeyNames.contains("${id}_fraction")) {
                                prefs[floatPreferencesKey("${id}_fraction")] = 1.0f
                            }
                            if (!existingKeyNames.contains("${id}_timestamp")) {
                                prefs[longPreferencesKey("${id}_timestamp")] = payload.updatedAt
                            }
                        }
                    }

                    // 3. Merge series resume pointers (timestamp-wins, unchanged logic)
                    for ((sid, res) in payload.resume) {
                        if (sid.isBlank() || res.episodeId.isBlank()) continue
                        val prefix = "series_resume_$sid"
                        val localTs = prefs[longPreferencesKey("${prefix}_timestamp")] ?: 0L
                        val isMissing = !existingKeyNames.contains("${prefix}_episode_id")
                        if (res.timestamp > localTs || isMissing) {
                            prefs[stringPreferencesKey("${prefix}_episode_id")] = res.episodeId
                            prefs[intPreferencesKey("${prefix}_season_num")] = res.seasonNumber
                            prefs[intPreferencesKey("${prefix}_episode_num")] = res.episodeNumber
                            prefs[longPreferencesKey("${prefix}_pos_ms")] = res.positionMs
                            prefs[longPreferencesKey("${prefix}_dur_ms")] = res.durationMs
                            val frac = if (res.durationMs > 0L) (res.positionMs.toFloat() / res.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                            prefs[floatPreferencesKey("${prefix}_fraction")] = frac
                            prefs[longPreferencesKey("${prefix}_timestamp")] = res.timestamp
                            activeById[res.episodeId]?.title?.takeIf { it.isNotBlank() }?.let { epTitle ->
                                prefs[stringPreferencesKey("${prefix}_episode_title")] = epTitle
                            }
                        }
                    }

                    // 4. Update global latest timestamp & snapshot if remote is newer
                    val newestActive = payload.active.maxByOrNull { it.timestamp }
                    val remoteGlobalTs = maxOf(
                        newestActive?.timestamp ?: 0L,
                        payload.resume.values.maxOfOrNull { it.timestamp } ?: 0L,
                        payload.updatedAt
                    )
                    val localGlobalTs = prefs[longPreferencesKey("global_latest_timestamp")] ?: 0L
                    if (remoteGlobalTs > localGlobalTs) {
                        prefs[longPreferencesKey("last_global_watch_timestamp")] = remoteGlobalTs
                        prefs[longPreferencesKey("global_last_watched_timestamp")] = remoteGlobalTs
                        prefs[longPreferencesKey("global_latest_timestamp")] = remoteGlobalTs

                        if (newestActive != null && newestActive.timestamp > localGlobalTs) {
                            prefs[PlaybackHistoryManager.SNAPSHOT_FILE_ID] = newestActive.id
                            if (newestActive.title.isNotBlank()) {
                                prefs[PlaybackHistoryManager.SNAPSHOT_TITLE] = newestActive.title
                            }
                            if (!newestActive.seriesId.isNullOrBlank()) {
                                prefs[PlaybackHistoryManager.SNAPSHOT_SERIES_ID] = newestActive.seriesId
                                prefs[PlaybackHistoryManager.SNAPSHOT_IS_SERIES] = true
                            } else {
                                prefs[PlaybackHistoryManager.SNAPSHOT_IS_SERIES] = false
                            }
                            newestActive.seasonNumber?.let { prefs[PlaybackHistoryManager.SNAPSHOT_SEASON_NUMBER] = it }
                            newestActive.episodeNumber?.let { prefs[PlaybackHistoryManager.SNAPSHOT_EPISODE_NUMBER] = it }
                            prefs[PlaybackHistoryManager.SNAPSHOT_POSITION_MS] = newestActive.positionMs
                            prefs[PlaybackHistoryManager.SNAPSHOT_DURATION_MS] = newestActive.durationMs
                            prefs[PlaybackHistoryManager.SNAPSHOT_FRACTION] = newestActive.progressFraction
                            prefs[PlaybackHistoryManager.SNAPSHOT_TIMESTAMP] = newestActive.timestamp
                        }
                    }
                }

                if (email.isNotBlank()) {
                    AndroidWatchHistoryManager.mergeRemoteV2Payload(appContext, email, remoteJson)
                }
                Log.i(TAG, "Successfully merged remote v2 watch history ($ACTIVE_SYNC_FILE) from Google Drive")
                return true
            }

            val rootJson = JSONObject(remoteJson)
            val entriesJson = rootJson.optJSONObject("entries") ?: return false
            val remoteUpdatedAt = rootJson.optLong("updatedAt", 0L)

            dataStore.edit { prefs ->
                val currentPrefs = prefs.asMap()
                val existingKeyNames = currentPrefs.keys.map { it.name }.toSet()
                val keysIterator = entriesJson.keys()

                while (keysIterator.hasNext()) {
                    val keyName = keysIterator.next()
                    val entryObj = entriesJson.optJSONObject(keyName)
                    val tsKey = resolveTimestampKey(keyName)
                    val remoteTsEntry = entriesJson.optJSONObject(tsKey)
                    val remoteTs = remoteTsEntry?.optLong("value", 0L)
                        ?: if (keyName == tsKey) (entryObj?.optLong("value", 0L) ?: 0L) else remoteUpdatedAt

                    val localTs = (currentPrefs[longPreferencesKey(tsKey)] as? Long)
                        ?: if (keyName == tsKey) (currentPrefs[longPreferencesKey(keyName)] as? Long ?: 0L) else 0L

                    val isNewer = remoteTs > localTs
                    val isMissingLocally = !existingKeyNames.contains(keyName)

                    if (isNewer || isMissingLocally) {
                        if (entryObj != null) {
                            val type = entryObj.optString("type")
                            when (type) {
                                "long" -> prefs[longPreferencesKey(keyName)] = entryObj.optLong("value", 0L)
                                "int" -> prefs[intPreferencesKey(keyName)] = entryObj.optInt("value", 0)
                                "float" -> prefs[floatPreferencesKey(keyName)] = entryObj.optDouble("value", 0.0).toFloat()
                                "boolean" -> prefs[booleanPreferencesKey(keyName)] = entryObj.optBoolean("value", false)
                                else -> prefs[stringPreferencesKey(keyName)] = entryObj.optString("value", "")
                            }
                        } else {
                            when (val rawVal = entriesJson.opt(keyName)) {
                                is Long -> prefs[longPreferencesKey(keyName)] = rawVal
                                is Int -> prefs[intPreferencesKey(keyName)] = rawVal
                                is Double -> prefs[floatPreferencesKey(keyName)] = rawVal.toFloat()
                                is Float -> prefs[floatPreferencesKey(keyName)] = rawVal
                                is Boolean -> prefs[booleanPreferencesKey(keyName)] = rawVal
                                is String -> prefs[stringPreferencesKey(keyName)] = rawVal
                            }
                        }
                    }
                }

                val remoteGlobalTs = (entriesJson.optJSONObject("global_latest_timestamp")?.optLong("value", 0L) ?: 0L)
                    .takeIf { it > 0L }
                    ?: (entriesJson.optJSONObject("last_global_watch_timestamp")?.optLong("value", 0L) ?: 0L)
                    .takeIf { it > 0L }
                    ?: remoteUpdatedAt

                val localGlobalTs = prefs[longPreferencesKey("global_latest_timestamp")] ?: 0L
                if (remoteGlobalTs > localGlobalTs) {
                    prefs[longPreferencesKey("last_global_watch_timestamp")] = remoteGlobalTs
                    prefs[longPreferencesKey("global_last_watched_timestamp")] = remoteGlobalTs
                    prefs[longPreferencesKey("global_latest_timestamp")] = remoteGlobalTs
                }
            }
            if (email.isNotBlank()) {
                AndroidWatchHistoryManager.mergeRemotePayload(appContext, email, remoteJson)
            }
            Log.i(TAG, "Successfully merged remote legacy v1 watch history from Google Drive")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error merging remote history payload: ${e.message}", e)
            false
        }
    }

    private fun findScopedHistoryFileId(driveService: Drive, historyFolderId: String, fileName: String = ACTIVE_SYNC_FILE): String? {
        val query = "'$historyFolderId' in parents and name = '$fileName' and trashed = false"
        val response = driveService.files().list()
            .setQ(query)
            .setFields("files(id, name)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(5)
            .execute()
        val foundId = response.files?.firstOrNull()?.id
        if (foundId != null && fileName == ACTIVE_SYNC_FILE) {
            cachedFileId.set(foundId)
        }
        return foundId
    }

    private fun findLegacyRootHistoryFileId(driveService: Drive): String? {
        val query = "name = '$LEGACY_SYNC_FILE' and trashed = false"
        val response = driveService.files().list()
            .setQ(query)
            .setFields("files(id, name, parents)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(10)
            .execute()
        val files = response.files.orEmpty()
        val rootMatch = files.firstOrNull { it.parents?.contains("root") == true }
        return (rootMatch ?: files.firstOrNull())?.id
    }

    suspend fun syncFromCloud(dataStore: DataStore<Preferences>): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try {
            val driveService = getDriveService() ?: return@withContext false
            var target = resolveTarget(driveService)
            val fileId = target.fileId ?: run {
                Log.d(TAG, "No remote history file in clotube/History/ or root to pull")
                return@withContext false
            }

            val outputStream = ByteArrayOutputStream()
            try {
                driveService.files().get(fileId)
                    .setSupportsAllDrives(true)
                    .executeMediaAndDownloadTo(outputStream)
            } catch (e: GoogleJsonResponseException) {
                if (e.statusCode == 404) {
                    outputStream.reset()
                    invalidateCache()
                    target = resolveTarget(driveService)
                    val refreshedId = target.fileId ?: return@withContext false
                    driveService.files().get(refreshedId)
                        .setSupportsAllDrives(true)
                        .executeMediaAndDownloadTo(outputStream)
                } else {
                    throw e
                }
            }

            val rawJson = outputStream.toString("UTF-8")
            if (rawJson.isBlank()) return@withContext false

            val merged = mergeRemotePayload(dataStore, rawJson)
            if (!target.isV2) {
                Log.i(TAG, "Migrating remote v1 history ($LEGACY_SYNC_FILE) to v2 ($ACTIVE_SYNC_FILE) in clotube/History/...")
                enqueueImmediateWatchState(dataStore)
            }
            merged
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling watch history from cloud: ${e.message}", e)
            false
        }
    }

    suspend fun syncFromCloud(): Boolean = syncFromCloud(appContext.playbackDataStore)

    suspend fun pullAndMerge(): Boolean = syncFromCloud(appContext.playbackDataStore)

    suspend fun pullAndMergeHistoryFromDrive(dataStore: DataStore<Preferences>) = withContext(Dispatchers.IO) {
        syncFromCloud(dataStore)
    }

    suspend fun deleteRemoteHistoryFile(): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        uploadMutex.withLock {
            try {
                val driveService = getDriveService()
                if (driveService == null) {
                    Log.e(TAG, "Cannot delete remote file: Drive service unavailable")
                    return@withLock false
                }

                val email = GoogleSignIn.getLastSignedInAccount(appContext)?.email.orEmpty()
                if (email.isNotBlank()) {
                    AndroidWatchHistoryManager.clearLocal(appContext, email)
                }

                val historyFolderId = resolveHistoryFolderId(driveService)
                val v2FileId = cachedFileId.get() ?: findScopedHistoryFileId(driveService, historyFolderId, ACTIVE_SYNC_FILE)
                val scopedLegacyId = findScopedHistoryFileId(driveService, historyFolderId, LEGACY_SYNC_FILE)
                val rootLegacyId = findLegacyRootHistoryFileId(driveService)

                if (v2FileId == null && scopedLegacyId == null && rootLegacyId == null) {
                    Log.d(TAG, "No remote watch history file found on Drive to delete")
                    invalidateCache()
                    return@withLock true
                }

                var primaryDeleted = v2FileId == null
                if (v2FileId != null) {
                    try {
                        driveService.files().delete(v2FileId)
                            .setSupportsAllDrives(true)
                            .execute()
                        primaryDeleted = true
                        Log.i(TAG, "SUCCESS! Deleted $ACTIVE_SYNC_FILE from clotube/History/ (fileId: $v2FileId)")
                    } catch (e: GoogleJsonResponseException) {
                        if (e.statusCode == 404) {
                            Log.d(TAG, "$ACTIVE_SYNC_FILE was already removed from Drive")
                            primaryDeleted = true
                        } else {
                            throw e
                        }
                    }
                }

                listOfNotNull(scopedLegacyId, rootLegacyId)
                    .distinct()
                    .filter { it != v2FileId }
                    .forEach { legacyId ->
                        try {
                            driveService.files().delete(legacyId)
                                .setSupportsAllDrives(true)
                                .execute()
                            Log.i(TAG, "Deleted legacy $LEGACY_SYNC_FILE (fileId: $legacyId)")
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not delete legacy history file ($legacyId): ${e.message}")
                        }
                    }

                invalidateCache()
                lastUploadTime.set(0L)
                primaryDeleted
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete remote watch history file from Drive: ${e.message}", e)
                false
            }
        }
    }

    private fun getDriveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(appContext)
        if (account == null) {
            val token = runBlocking {
                tokenProviderRef?.invoke() ?: com.clotube.app.auth.GoogleAuthRepository(appContext).getAccessToken().getOrNull()
            }
            if (!token.isNullOrBlank()) {
                Log.d(TAG, "Using Bearer token provider for Drive service")
                return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance()) { request ->
                    request.headers.authorization = "Bearer $token"
                }.setApplicationName("Clotube").build()
            }
            Log.e(TAG, "Cannot sync: GoogleSignIn account is null and no token available")
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
        private const val LEGACY_SYNC_FILE = "clotube_watch_history.json"

        @Volatile
        private var defaultInstance: DriveWatchHistorySync? = null

        @Volatile
        private var onAuthFailure: (suspend () -> Unit)? = null

        @Volatile
        private var tokenProviderRef: (suspend () -> String)? = null

        fun getInstance(context: Context): DriveWatchHistorySync {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: DriveWatchHistorySync(context.applicationContext).also { defaultInstance = it }
            }
        }

        fun init(
            context: Context,
            dataStore: DataStore<Preferences>? = null,
            tokenProvider: (suspend () -> String)? = null
        ) {
            val instance = getInstance(context)
            if (tokenProvider != null) {
                this.tokenProviderRef = tokenProvider
            }
            if (dataStore != null) {
                instance.syncScope.launch {
                    instance.syncFromCloud(dataStore)
                }
            }
        }

        fun setOnAuthFailureListener(listener: suspend () -> Unit) {
            this.onAuthFailure = listener
        }

        fun setTokenProvider(provider: suspend () -> String) {
            this.tokenProviderRef = provider
        }

        fun triggerThrottledBackup(dataStore: DataStore<Preferences>) {
            defaultInstance?.enqueueProgressUpdate(dataStore)
        }

        fun triggerThrottledBackup(
            dataStore: DataStore<Preferences>,
            tokenProvider: suspend () -> String,
            context: Context? = null
        ) {
            val inst = context?.let { getInstance(it) } ?: defaultInstance
            inst?.enqueueProgressUpdate(dataStore)
        }

        fun triggerImmediateBackup(
            dataStore: DataStore<Preferences>,
            tokenProvider: suspend () -> String,
            context: Context? = null
        ) {
            val inst = context?.let { getInstance(it) } ?: defaultInstance
            inst?.enqueueImmediateWatchState(dataStore)
        }

        fun backupImmediately(dataStore: DataStore<Preferences>? = null) {
            defaultInstance?.backupImmediately(dataStore)
        }

        fun pushHistoryAsync(dataStore: DataStore<Preferences>? = null) {
            defaultInstance?.pushHistoryAsync(dataStore)
        }

        /**
         * Static companion entry-point for push triggered by [AndroidWatchHistoryManager.setEpisodeWatched].
         * When force=true, the enqueued task runs as CRITICAL_WATCH_STATE priority, bypassing
         * both the 15-second debounce window and the hash-equality guard in
         * [executeDirectUploadInternal], guaranteeing the unwatch reaches Drive immediately.
         */
        fun pushHistoryAsync(context: Context, force: Boolean = false) {
            val inst = getInstance(context)
            val dataStore = context.applicationContext.playbackDataStore
            if (force) {
                inst.enqueueImmediateWatchState(dataStore)
            } else {
                inst.enqueueProgressUpdate(dataStore)
            }
        }

        suspend fun pullAndMerge(context: Context): Boolean {
            return getInstance(context).pullAndMerge()
        }

        fun backupToCloudImmediately(dataStore: DataStore<Preferences>? = null) {
            defaultInstance?.backupImmediately(dataStore)
        }

        suspend fun syncFromCloud(
            dataStore: DataStore<Preferences>,
            context: Context? = null
        ): Boolean {
            val inst = context?.let { getInstance(it) } ?: defaultInstance
            return inst?.syncFromCloud(dataStore) ?: false
        }

        suspend fun pullAndMergeHistoryFromDrive(
            dataStore: DataStore<Preferences>,
            tokenProvider: suspend () -> String,
            context: Context? = null
        ) {
            val inst = context?.let { getInstance(it) } ?: defaultInstance
            inst?.pullAndMergeHistoryFromDrive(dataStore)
        }

        suspend fun pushLocalHistoryToDrive(
            dataStore: DataStore<Preferences>,
            tokenProvider: suspend () -> String,
            context: Context? = null
        ) {
            val inst = context?.let { getInstance(it) } ?: defaultInstance
            inst?.executeDirectUpload(dataStore)
        }

        suspend fun deleteRemoteHistoryFile(context: Context? = null): Boolean {
            val inst = context?.let { getInstance(it) } ?: defaultInstance
            return inst?.deleteRemoteHistoryFile() ?: false
        }
    }
}
