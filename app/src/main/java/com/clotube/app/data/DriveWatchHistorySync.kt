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
    private val uploadMutex = Mutex()
    private val lastUploadTime = AtomicLong(0L)

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

    fun pushHistoryAsync(dataStore: DataStore<Preferences>? = null, force: Boolean = false) {
        val target = dataStore ?: appContext.playbackDataStore
        enqueueImmediateWatchState(target)
    }

    suspend fun executeDirectUpload(dataStore: DataStore<Preferences>? = null): Boolean {
        val target = dataStore ?: appContext.playbackDataStore
        return executeDirectUploadInternal(target, SyncPriority.CRITICAL_WATCH_STATE)
    }

    private suspend fun executeDirectUploadInternal(
        dataStore: DataStore<Preferences>,
        priority: SyncPriority
    ): Boolean = uploadMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val driveService = getDriveService()
                if (driveService == null) {
                    Log.e(TAG, "Sync aborted: Google Drive service unavailable")
                    return@withContext false
                }

                // For background sync, perform remote pull and merge first
                if (priority == SyncPriority.BACKGROUND_SYNC) {
                    val targetFileId = cachedFileId.get() ?: findHistoryFileId(driveService)
                    if (targetFileId != null) {
                        try {
                            val outputStream = ByteArrayOutputStream()
                            driveService.files().get(targetFileId)
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
                }

                val prefs = dataStore.data.first()
                val allEntries = prefs.asMap()
                Log.d(TAG, "[${priority.name}] Preparing payload with ${allEntries.size} keys from DataStore...")

                // Exact JSON schema required by both Android and Desktop
                val rootJson = JSONObject().apply {
                    put("version", 1)
                    put("updatedAt", System.currentTimeMillis())
                    val entriesJson = JSONObject()
                    allEntries.forEach { (key, value) ->
                        val entryObj = JSONObject()
                        when (value) {
                            is Long -> { entryObj.put("type", "long"); entryObj.put("value", value) }
                            is Int -> { entryObj.put("type", "int"); entryObj.put("value", value) }
                            is Float -> { entryObj.put("type", "float"); entryObj.put("value", value.toDouble()) }
                            is Boolean -> { entryObj.put("type", "boolean"); entryObj.put("value", value) }
                            is String -> { entryObj.put("type", "string"); entryObj.put("value", value) }
                        }
                        entriesJson.put(key.name, entryObj)
                    }
                    put("entries", entriesJson)
                }

                val mediaContent = ByteArrayContent.fromString("application/json", rootJson.toString())
                var targetFileId = cachedFileId.get() ?: findHistoryFileId(driveService)

                if (targetFileId != null) {
                    try {
                        driveService.files().update(targetFileId, null, mediaContent)
                            .setSupportsAllDrives(true)
                            .execute()
                        cachedFileId.set(targetFileId)
                        lastUploadTime.set(System.currentTimeMillis())
                        Log.i(TAG, "SUCCESS! [${priority.name}] Watch history updated on Google Drive ($targetFileId, ${mediaContent.length} bytes)")
                        return@withContext true
                    } catch (e: GoogleJsonResponseException) {
                        if (e.statusCode == 404 || (e.statusCode == 403 && e.details?.errors?.any { it.reason == "appNotAuthorizedToFile" } == true)) {
                            targetFileId = null
                        } else {
                            throw e
                        }
                    }
                }

                // File does not exist: create file
                val metadata = com.google.api.services.drive.model.File().apply {
                    name = "clotube_watch_history.json"
                    mimeType = "application/json"
                }
                val created = driveService.files().create(metadata, mediaContent)
                    .setSupportsAllDrives(true)
                    .execute()

                cachedFileId.set(created.id)
                lastUploadTime.set(System.currentTimeMillis())
                Log.i(TAG, "SUCCESS! Created clotube_watch_history.json on Google Drive (fileId: ${created.id})")
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

    suspend fun mergeRemotePayload(dataStore: DataStore<Preferences>, remoteJson: String): Boolean {
        if (remoteJson.isBlank()) return false
        return try {
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
            Log.i(TAG, "Successfully merged remote watch history from Google Drive")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error merging remote history payload: ${e.message}", e)
            false
        }
    }

    private fun findHistoryFileId(driveService: Drive): String? {
        val query = "name = 'clotube_watch_history.json' and trashed = false"
        val response = driveService.files().list()
            .setQ(query)
            .setFields("files(id, name)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(5)
            .execute()
        val foundId = response.files?.firstOrNull()?.id
        if (foundId != null) {
            cachedFileId.set(foundId)
        }
        return foundId
    }

    suspend fun syncFromCloud(dataStore: DataStore<Preferences>): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        try {
            val driveService = getDriveService() ?: return@withContext false
            val fileId = findHistoryFileId(driveService) ?: run {
                Log.d(TAG, "No remote history file on Drive to pull")
                return@withContext false
            }

            val outputStream = ByteArrayOutputStream()
            driveService.files().get(fileId)
                .setSupportsAllDrives(true)
                .executeMediaAndDownloadTo(outputStream)

            val rawJson = outputStream.toString("UTF-8")
            if (rawJson.isBlank()) return@withContext false

            mergeRemotePayload(dataStore, rawJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling watch history from cloud: ${e.message}", e)
            false
        }
    }

    suspend fun syncFromCloud(): Boolean = syncFromCloud(appContext.playbackDataStore)

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

                val targetFileId = cachedFileId.get() ?: findHistoryFileId(driveService)
                if (targetFileId == null) {
                    Log.d(TAG, "No remote watch history file found on Drive to delete")
                    cachedFileId.set(null)
                    return@withLock true
                }

                try {
                    driveService.files().delete(targetFileId)
                        .setSupportsAllDrives(true)
                        .execute()
                    cachedFileId.set(null)
                    lastUploadTime.set(0L)
                    Log.i(TAG, "SUCCESS! Deleted clotube_watch_history.json from Google Drive (fileId: $targetFileId)")
                    true
                } catch (e: GoogleJsonResponseException) {
                    if (e.statusCode == 404) {
                        Log.d(TAG, "Remote file was already removed from Drive")
                        cachedFileId.set(null)
                        lastUploadTime.set(0L)
                        true
                    } else {
                        throw e
                    }
                }
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

        fun pushHistoryAsync(context: Context, force: Boolean = false) {
            val inst = getInstance(context)
            inst.backupImmediately()
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
