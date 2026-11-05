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
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DriveWatchHistorySync(private val context: Context) {

    private val appContext: Context = context.applicationContext

    private val TAG = "ClotubeSync"
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var debouncedJob: Job? = null
    private val isUploading = AtomicBoolean(false)
    private val lastUploadTime = AtomicLong(0L)
    private val cachedFileId = AtomicReference<String?>(null)
    private val syncMutex = Mutex()
    private val MIN_INTERVAL_MS = 20_000L

    private fun getDriveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(appContext)
        Log.d(TAG, "Resolving Drive credentials/token for account: ${account?.email ?: "none"}...")
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
            Collections.singleton(DriveScopes.DRIVE)
        ).setSelectedAccount(googleAccount)

        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Clotube")
            .build()
    }

    fun triggerThrottledBackup(dataStore: DataStore<Preferences>) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastUploadTime.get()

        if (elapsed >= MIN_INTERVAL_MS) {
            debouncedJob?.cancel()
            debouncedJob = syncScope.launch(NonCancellable + Dispatchers.IO) {
                executeDirectUpload(dataStore)
            }
        } else if (debouncedJob == null || !debouncedJob!!.isActive) {
            debouncedJob = syncScope.launch(NonCancellable + Dispatchers.IO) {
                delay(MIN_INTERVAL_MS - elapsed)
                executeDirectUpload(dataStore)
            }
        }
    }

    fun backupImmediately(dataStore: DataStore<Preferences>? = null) {
        debouncedJob?.cancel()
        syncScope.launch(NonCancellable + Dispatchers.IO) {
            Log.d(TAG, "Executing immediate un-cancellable cloud sync...")
            executeDirectUpload(dataStore)
        }
    }

    fun pushHistoryAsync(dataStore: DataStore<Preferences>? = null, force: Boolean = false) {
        backupImmediately(dataStore)
    }

    /**
     * Permanently deletes clotube_watch_history.json from Google Drive.
     */
    suspend fun deleteRemoteHistoryFile(): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        debouncedJob?.cancel()
        syncMutex.withLock {
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

    /**
     * Resolves companion timestamp key accurately matching Desktop schema.
     */
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
     * Merges remote Google Drive history payload with local DataStore preferences using item-level
     * Last-Write-Wins based on timestamps. Never drops queued next-up episodes (fraction == 0f).
     */
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

    /**
     * Direct suspend upload method used by both the UI app and DriveSyncWorker.
     * Guarantees atomic pre-pull and merge so no client blindly overwrites remote cross-device history.
     */
    suspend fun executeDirectUpload(dataStoreOverride: DataStore<Preferences>? = null): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        syncMutex.withLock {
            if (!isUploading.compareAndSet(false, true)) {
                Log.w(TAG, "Upload already in flight, skipping duplicate call")
                return@withLock true
            }

            try {
                val driveService = getDriveService()
                if (driveService == null) {
                    Log.e(TAG, "Sync aborted: Drive service unavailable")
                    return@withLock false
                }

                val dataStore = dataStoreOverride ?: appContext.playbackDataStore

                // 1. Pre-pull and merge remote changes from Google Drive first (Atomic Pre-Pull)
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
                        Log.w(TAG, "Pre-upload remote pull failed (continuing with local state): ${e.message}")
                    }
                }

                // 2. Read merged preferences from DataStore
                val prefs = dataStore.data.first()
                val allEntries = prefs.asMap()
                Log.d(TAG, "Preparing payload with ${allEntries.size} keys from DataStore...")

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
                var currentFileId = targetFileId ?: findHistoryFileId(driveService)

                if (currentFileId != null) {
                    try {
                        driveService.files().update(currentFileId, null, mediaContent)
                            .setSupportsAllDrives(true)
                            .execute()
                        cachedFileId.set(currentFileId)
                        lastUploadTime.set(System.currentTimeMillis())
                        val payloadSize = mediaContent.length
                        Log.i(TAG, "SUCCESS! Watch history updated on Google Drive (fileId: $currentFileId, size: $payloadSize bytes)")
                        return@withLock true
                    } catch (e: GoogleJsonResponseException) {
                        if (e.statusCode == 404) {
                            Log.w(TAG, "File $currentFileId was deleted or not found. Creating fresh file...")
                            cachedFileId.set(null)
                            currentFileId = null
                        } else if (e.statusCode == 403 && (e.details?.errors?.any { it.reason == "appNotAuthorizedToFile" } == true || e.message?.contains("appNotAuthorizedToFile") == true)) {
                            Log.w(TAG, "File $currentFileId not writable under current scope. Creating fresh file...")
                            try {
                                driveService.files().delete(currentFileId).setSupportsAllDrives(true).execute()
                            } catch (ignored: Exception) {}
                            cachedFileId.set(null)
                            currentFileId = null
                        } else {
                            throw e
                        }
                    }
                }

                // Create new file if not present or replaced
                val metadata = com.google.api.services.drive.model.File().apply {
                    name = "clotube_watch_history.json"
                    mimeType = "application/json"
                }
                val created = driveService.files().create(metadata, mediaContent)
                    .setSupportsAllDrives(true)
                    .execute()

                cachedFileId.set(created.id)
                lastUploadTime.set(System.currentTimeMillis())
                val payloadSize = mediaContent.length
                Log.i(TAG, "SUCCESS! Created and synced clotube_watch_history.json (fileId: ${created.id}, size: $payloadSize bytes)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Cloud sync failed with exception: ${e.message}", e)
                false
            } finally {
                isUploading.set(false)
            }
        }
    }

    private fun findHistoryFileId(driveService: Drive): String? {
        val query = "name = 'clotube_watch_history.json' and trashed = false"
        val response = driveService.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(5)
            .execute()

        val foundId = response.files?.firstOrNull()?.id
        cachedFileId.set(foundId)
        return foundId
    }

    /**
     * Downloads clotube_watch_history.json from Google Drive and synchronizes
     * local DataStore preferences via Last-Write-Wins item level merge.
     */
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
                    instance.pullAndMergeHistoryFromDrive(dataStore)
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
            defaultInstance?.triggerThrottledBackup(dataStore)
        }

        fun triggerThrottledBackup(
            dataStore: DataStore<Preferences>,
            tokenProvider: suspend () -> String,
            context: Context? = null
        ) {
            val inst = context?.let { getInstance(it) } ?: defaultInstance
            inst?.triggerThrottledBackup(dataStore)
        }

        fun triggerImmediateBackup(
            dataStore: DataStore<Preferences>,
            tokenProvider: suspend () -> String,
            context: Context? = null
        ) {
            val inst = context?.let { getInstance(it) } ?: defaultInstance
            inst?.backupImmediately(dataStore)
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
