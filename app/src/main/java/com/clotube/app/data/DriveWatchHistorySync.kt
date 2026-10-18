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
            debouncedJob = syncScope.launch {
                executeDirectUpload(dataStore)
            }
        } else if (debouncedJob == null || !debouncedJob!!.isActive) {
            debouncedJob = syncScope.launch {
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

    /**
     * Permanently deletes clotube_watch_history.json from Google Drive.
     */
    suspend fun deleteRemoteHistoryFile(): Boolean = withContext(Dispatchers.IO) {
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
     * Direct suspend upload method used by both the UI app and DriveSyncWorker.
     * Returns true if the upload was successful.
     */
    suspend fun executeDirectUpload(dataStoreOverride: DataStore<Preferences>? = null): Boolean = withContext(Dispatchers.IO) {
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

                // Access the app DataStore instance
                val dataStore = dataStoreOverride ?: appContext.playbackDataStore
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
                var targetFileId = cachedFileId.get() ?: findHistoryFileId(driveService)

                if (targetFileId != null) {
                    try {
                        driveService.files().update(targetFileId, null, mediaContent)
                            .setSupportsAllDrives(true)
                            .execute()
                        cachedFileId.set(targetFileId)
                        lastUploadTime.set(System.currentTimeMillis())
                        val payloadSize = mediaContent.length
                        Log.i(TAG, "SUCCESS! Watch history updated on Google Drive (fileId: $targetFileId, size: $payloadSize bytes)")
                        return@withLock true
                    } catch (e: GoogleJsonResponseException) {
                        if (e.statusCode == 404) {
                            Log.w(TAG, "File $targetFileId was deleted or not found. Creating fresh file...")
                            cachedFileId.set(null)
                            targetFileId = null
                        } else if (e.statusCode == 403 && (e.details?.errors?.any { it.reason == "appNotAuthorizedToFile" } == true || e.message?.contains("appNotAuthorizedToFile") == true)) {
                            Log.w(TAG, "File $targetFileId not writable under current scope. Creating fresh file...")
                            try {
                                driveService.files().delete(targetFileId).setSupportsAllDrives(true).execute()
                            } catch (ignored: Exception) {}
                            cachedFileId.set(null)
                            targetFileId = null
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

    suspend fun pullAndMergeHistoryFromDrive(dataStore: DataStore<Preferences>) = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService() ?: return@withContext
            Log.d(TAG, "Checking for remote watch history file to restore...")
            val fileId = findHistoryFileId(driveService) ?: return@withContext
            Log.d(TAG, "Downloading remote watch history fileId=$fileId...")
            val outputStream = ByteArrayOutputStream()
            driveService.files().get(fileId)
                .setSupportsAllDrives(true)
                .executeMediaAndDownloadTo(outputStream)

            val jsonString = outputStream.toString("UTF-8")
            if (jsonString.isBlank()) return@withContext

            val rootJson = JSONObject(jsonString)
            val entriesJson = rootJson.optJSONObject("entries") ?: return@withContext

            var restoredCount = 0
            dataStore.edit { mutablePrefs ->
                val keysIterator = entriesJson.keys()
                while (keysIterator.hasNext()) {
                    val keyName = keysIterator.next()
                    val itemObj = entriesJson.optJSONObject(keyName) ?: continue
                    val type = itemObj.optString("type")

                    when (type) {
                        "long" -> {
                            mutablePrefs[longPreferencesKey(keyName)] = itemObj.optLong("value", 0L)
                            restoredCount++
                        }
                        "int" -> {
                            mutablePrefs[intPreferencesKey(keyName)] = itemObj.optInt("value", 1)
                            restoredCount++
                        }
                        "float" -> {
                            mutablePrefs[floatPreferencesKey(keyName)] = itemObj.optDouble("value", 0.0).toFloat()
                            restoredCount++
                        }
                        "boolean" -> {
                            mutablePrefs[booleanPreferencesKey(keyName)] = itemObj.optBoolean("value", false)
                            restoredCount++
                        }
                        "string" -> {
                            val remoteStr = itemObj.optString("value", "")
                            if (remoteStr.isNotBlank()) {
                                mutablePrefs[stringPreferencesKey(keyName)] = remoteStr
                                restoredCount++
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "SUCCESS! Restored $restoredCount watch history keys from Google Drive!")
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling watch history from Drive: ${e.message}", e)
        }
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

        fun backupToCloudImmediately(dataStore: DataStore<Preferences>? = null) {
            defaultInstance?.backupImmediately(dataStore)
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
