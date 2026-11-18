package com.clotube.app.data

import android.accounts.Account
import android.content.Context
import android.util.Log
import com.clotube.app.model.WatchlistPayload
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Two-way Google Drive synchronization client for Clotube Watchlist (clotube/History/clotube_watchlist.json).
 * - Uses canonical clotube/History/clotube_watchlist.json (ordered by modifiedTime desc)
 * - Merges and cleans up any legacy root clotube_watchlist.json duplicates
 * - Hashes strictly over the deterministic items array (excluding volatile root updatedAt)
 * - Coalesces rapid UI bookmark taps with 350ms debouncing inside NonCancellable IO scope
 * - Enqueues WorkManager DriveSyncWorker safety net on transient IOException failures
 */
class DriveWatchlistSync(private val context: Context) {

    private val appContext: Context = context.applicationContext
    private val TAG = "DriveWatchlistSync"
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cachedFileId = AtomicReference<String?>(null)
    private val cachedHistoryFolderId = AtomicReference<String?>(null)
    private val lastUploadedHash = AtomicReference<String>("")
    private val syncMutex = Mutex()
    private val lastUploadTime = AtomicLong(0L)
    private val lastCloudPullTimeMs = AtomicLong(0L)
    private val isSyncingFromCloud = AtomicBoolean(false)

    private var pushDebounceJob: Job? = null

    private data class TargetFileResult(
        val canonicalFileId: String?,
        val parentFolderId: String,
        val legacyRootFileIds: List<String> = emptyList()
    )

    fun invalidateCache() {
        cachedFileId.set(null)
        cachedHistoryFolderId.set(null)
        lastUploadedHash.set("")
        lastCloudPullTimeMs.set(0L)
    }

    private suspend fun resolveHistoryFolderId(driveService: Drive): String = withContext(Dispatchers.IO) {
        cachedHistoryFolderId.get()?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val dirs = DriveDirectoryManager.resolveHierarchy(driveService)
        cachedHistoryFolderId.set(dirs.historyFolderId)
        dirs.historyFolderId
    }

    private suspend fun resolveTarget(
        driveService: Drive,
        bypassCache: Boolean = false
    ): TargetFileResult = withContext(Dispatchers.IO) {
        val historyFolderId = resolveHistoryFolderId(driveService)

        if (!bypassCache) {
            cachedFileId.get()?.takeIf { it.isNotBlank() }?.let { cachedId ->
                return@withContext TargetFileResult(
                    canonicalFileId = cachedId,
                    parentFolderId = historyFolderId,
                    legacyRootFileIds = emptyList()
                )
            }
        }

        // 1. Query canonical clotube/History/clotube_watchlist.json ordered by modifiedTime desc
        val scopedRes = driveService.files().list()
            .setQ("'$historyFolderId' in parents and name = '$WATCHLIST_FILE' and trashed = false")
            .setOrderBy("modifiedTime desc")
            .setFields("files(id, modifiedTime)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(10)
            .execute()

        val canonicalId = scopedRes.files?.firstOrNull()?.id
        if (canonicalId != null) {
            cachedFileId.set(canonicalId)
        }

        // 2. Inspect any legacy clotube_watchlist.json outside clotube/History/ (e.g., Drive root)
        val allMatchesRes = driveService.files().list()
            .setQ("name = '$WATCHLIST_FILE' and trashed = false")
            .setOrderBy("modifiedTime desc")
            .setFields("files(id, parents, modifiedTime)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .setPageSize(10)
            .execute()

        val legacyOutsideIds = allMatchesRes.files.orEmpty()
            .filter { file ->
                val id = file.id
                !id.isNullOrBlank() &&
                    id != canonicalId &&
                    (file.parents == null || !file.parents.contains(historyFolderId))
            }
            .map { it.id }

        TargetFileResult(
            canonicalFileId = canonicalId,
            parentFolderId = historyFolderId,
            legacyRootFileIds = legacyOutsideIds
        )
    }

    private fun downloadAndMergeFile(driveService: Drive, fileId: String): Boolean {
        val outputStream = ByteArrayOutputStream()
        driveService.files().get(fileId)
            .setSupportsAllDrives(true)
            .executeMediaAndDownloadTo(outputStream)

        val remoteJson = outputStream.toString("UTF-8")
        if (remoteJson.isBlank()) return false

        val remotePayload = WatchlistPayload.fromJsonString(remoteJson)
        if (remotePayload.items.isEmpty()) return false

        return WatchlistManager.mergeRemoteEntries(appContext, remotePayload.items)
    }

    /**
     * Enqueues a fast debounced (350ms) bidirectional push to Google Drive inside NonCancellable IO scope.
     */
    fun enqueuePush() {
        synchronized(this) {
            pushDebounceJob?.cancel()
            pushDebounceJob = syncScope.launch(NonCancellable) {
                delay(DEBOUNCE_DELAY_MS)
                syncBidirectional(force = false)
            }
        }
    }

    /**
     * Immediately triggers a forced bidirectional push to Google Drive without debouncing.
     */
    fun pushNow() {
        synchronized(this) {
            pushDebounceJob?.cancel()
            pushDebounceJob = syncScope.launch(NonCancellable) {
                syncBidirectional(force = true)
            }
        }
    }

    /**
     * Non-blocking foreground pull with a 5-second cooldown when [force] == false.
     */
    fun triggerForegroundPull(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val lastPull = lastCloudPullTimeMs.get()
        if (!force && (now - lastPull < CLOUD_PULL_COOLDOWN_MS)) {
            Log.d(TAG, "Skipping triggerForegroundPull (cooldown active: ${now - lastPull}ms)")
            return
        }
        syncScope.launch(Dispatchers.IO) {
            pullFromCloud(force = force)
        }
    }

    /**
     * Core two-way bidirectional sync:
     * 1. Pulls and merges canonical clotube/History/clotube_watchlist.json AND any legacy root files.
     * 2. Computes deterministic items-only hash (excluding volatile root updatedAt).
     * 3. Uploads merged payload to clotube/History/clotube_watchlist.json when changed or forced.
     * 4. Cleans up any migrated legacy root duplicates.
     * 5. Enqueues WorkManager DriveSyncWorker safety net on transient IOException.
     */
    suspend fun syncBidirectional(force: Boolean = false): Boolean = syncMutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                WatchlistManager.ensureLoaded(appContext)
                val driveService = getDriveService()
                if (driveService == null) {
                    Log.w(TAG, "Watchlist sync skipped: Drive service unavailable")
                    return@withContext false
                }

                var target = resolveTarget(driveService, bypassCache = force)

                // 1a. Download and merge canonical clotube/History/clotube_watchlist.json if present
                if (target.canonicalFileId != null) {
                    try {
                        downloadAndMergeFile(driveService, target.canonicalFileId!!)
                    } catch (e: GoogleJsonResponseException) {
                        if (e.statusCode == 404) {
                            Log.w(TAG, "Canonical watchlist file (${target.canonicalFileId}) returned 404; re-querying Drive")
                            invalidateCache()
                            target = resolveTarget(driveService, bypassCache = true)
                            target.canonicalFileId?.let { refreshedId ->
                                runCatching { downloadAndMergeFile(driveService, refreshedId) }
                            }
                        } else {
                            throw e
                        }
                    }
                }

                // 1b. Merge any legacy root clotube_watchlist.json duplicates before uploading
                var mergedLegacyRoot = false
                for (legacyId in target.legacyRootFileIds) {
                    try {
                        downloadAndMergeFile(driveService, legacyId)
                        mergedLegacyRoot = true
                        Log.i(TAG, "Merged legacy root $WATCHLIST_FILE ($legacyId) into local WatchlistManager")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not read legacy root $WATCHLIST_FILE ($legacyId): ${e.message}")
                    }
                }

                // 2. Prepare deterministic merged local payload & items-only hash
                val (payloadJson, payloadHash) = WatchlistManager.buildPayloadSnapshot()
                val needsLegacyMigration = target.legacyRootFileIds.isNotEmpty() || mergedLegacyRoot

                if (!force &&
                    !needsLegacyMigration &&
                    target.canonicalFileId != null &&
                    payloadHash == lastUploadedHash.get()
                ) {
                    Log.d(TAG, "Watchlist upload skipped: deterministic items hash unchanged ($payloadHash)")
                    return@withContext true
                }

                val mediaContent = ByteArrayContent.fromString("application/json", payloadJson)
                Log.d(TAG, "Uploading watchlist payload (${mediaContent.length} bytes, hash=$payloadHash) to clotube/History/$WATCHLIST_FILE...")

                // 3. Update canonical file inside clotube/History/ if present
                var uploadSucceeded = false
                if (target.canonicalFileId != null) {
                    try {
                        driveService.files().update(target.canonicalFileId, null, mediaContent)
                            .setSupportsAllDrives(true)
                            .execute()

                        cachedFileId.set(target.canonicalFileId)
                        lastUploadedHash.set(payloadHash)
                        lastUploadTime.set(System.currentTimeMillis())
                        uploadSucceeded = true
                        Log.i(TAG, "SUCCESS! Updated $WATCHLIST_FILE in clotube/History/ (${target.canonicalFileId})")
                    } catch (e: GoogleJsonResponseException) {
                        if (e.statusCode == 404) {
                            invalidateCache()
                            target = resolveTarget(driveService, bypassCache = true)
                        } else {
                            throw e
                        }
                    }
                }

                // 4. Create canonical file inside clotube/History/ if it does not yet exist
                if (!uploadSucceeded) {
                    val metadata = com.google.api.services.drive.model.File().apply {
                        name = WATCHLIST_FILE
                        mimeType = "application/json"
                        parents = listOf(target.parentFolderId)
                    }

                    val created = driveService.files().create(metadata, mediaContent)
                        .setSupportsAllDrives(true)
                        .execute()

                    cachedFileId.set(created.id)
                    lastUploadedHash.set(payloadHash)
                    lastUploadTime.set(System.currentTimeMillis())
                    Log.i(TAG, "SUCCESS! Created $WATCHLIST_FILE in clotube/History/ (fileId: ${created.id})")
                }

                // 5. Clean up any legacy root duplicates now that unified payload lives in clotube/History/
                for (legacyId in target.legacyRootFileIds) {
                    try {
                        driveService.files().delete(legacyId)
                            .setSupportsAllDrives(true)
                            .execute()
                        Log.i(TAG, "Cleaned up duplicate legacy root $WATCHLIST_FILE ($legacyId)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not delete legacy root watchlist file ($legacyId): ${e.message}")
                    }
                }

                true
            } catch (e: IOException) {
                Log.w(TAG, "Transient network IOException during watchlist sync; enqueuing DriveSyncWorker safety net: ${e.message}")
                try {
                    com.clotube.app.sync.DriveSyncWorker.enqueueImmediateSync(appContext)
                } catch (workerErr: Exception) {
                    Log.w(TAG, "Failed to enqueue DriveSyncWorker safety net: ${workerErr.message}")
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "Watchlist cloud sync failed: ${e.message}", e)
                false
            }
        }
    }

    /**
     * Pulls latest watchlist from Google Drive without forcing an immediate upload
     * (unless legacy root duplicates require migration into clotube/History/).
     * Enforces a 5-second cooldown when [force] == false.
     */
    suspend fun pullFromCloud(force: Boolean = false): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastPull = lastCloudPullTimeMs.get()
        if (!force && (now - lastPull < CLOUD_PULL_COOLDOWN_MS)) {
            Log.d(TAG, "Skipping duplicate pullFromCloud (cooldown active: ${now - lastPull}ms)")
            return@withContext false
        }

        if (!isSyncingFromCloud.compareAndSet(false, true)) {
            Log.d(TAG, "Skipping concurrent pullFromCloud (already in flight)")
            return@withContext false
        }

        try {
            WatchlistManager.ensureLoaded(appContext)
            val driveService = getDriveService() ?: return@withContext false
            var target = resolveTarget(driveService, bypassCache = force)

            if (target.canonicalFileId == null && target.legacyRootFileIds.isEmpty()) {
                lastCloudPullTimeMs.set(System.currentTimeMillis())
                Log.d(TAG, "No remote watchlist file found in clotube/History/ or root to pull")
                return@withContext false
            }

            var mergedAny = false

            if (target.canonicalFileId != null) {
                try {
                    if (downloadAndMergeFile(driveService, target.canonicalFileId!!)) {
                        mergedAny = true
                    }
                } catch (e: GoogleJsonResponseException) {
                    if (e.statusCode == 404) {
                        invalidateCache()
                        target = resolveTarget(driveService, bypassCache = true)
                        val refreshedId = target.canonicalFileId
                        if (refreshedId != null && downloadAndMergeFile(driveService, refreshedId)) {
                            mergedAny = true
                        }
                    } else {
                        throw e
                    }
                }
            }

            // Merge any legacy root files and trigger migration push to clotube/History/
            if (target.legacyRootFileIds.isNotEmpty()) {
                for (legacyId in target.legacyRootFileIds) {
                    try {
                        if (downloadAndMergeFile(driveService, legacyId)) {
                            mergedAny = true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed pulling legacy root watchlist file ($legacyId): ${e.message}")
                    }
                }
                enqueuePush()
            }

            lastCloudPullTimeMs.set(System.currentTimeMillis())
            mergedAny
        } catch (e: Exception) {
            Log.e(TAG, "Error pulling watchlist from cloud: ${e.message}", e)
            false
        } finally {
            isSyncingFromCloud.set(false)
        }
    }

    private fun getDriveService(): Drive? {
        val byocConfig = ByocConfigManager.configFlow.value
        val byocActive = byocConfig.isEnabled && byocConfig.clientId.isNotBlank()

        // 1. Prefer registered Bearer token provider / TokenRepository / GoogleAuthRepository when BYOC is active
        //    or when a fresh OAuth access token is already available.
        val bearerToken = runBlocking {
            runCatching { tokenProviderRef?.invoke()?.takeIf { it.isNotBlank() } }.getOrNull()
                ?: runCatching { TokenRepository(appContext).getValidAccessToken(forceRefresh = false)?.takeIf { it.isNotBlank() } }.getOrNull()
                ?: runCatching { com.clotube.app.auth.GoogleAuthRepository(appContext).getAccessToken().getOrNull()?.takeIf { it.isNotBlank() } }.getOrNull()
        }

        if (!bearerToken.isNullOrBlank()) {
            Log.d(TAG, "Using OAuth Bearer token for DriveWatchlistSync (byocActive=$byocActive)")
            return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance()) { request ->
                request.headers.authorization = "Bearer $bearerToken"
            }.setApplicationName("Clotube").build()
        }

        if (byocActive) {
            Log.e(TAG, "Cannot sync watchlist: BYOC is active but no valid OAuth access token could be acquired")
            return null
        }

        // 2. Fallback to GoogleSignIn last signed-in account / GoogleAccountCredential
        val account = GoogleSignIn.getLastSignedInAccount(appContext)
        val activeEmail = ProfileManager.activeProfileEmail.value?.takeIf { it.isNotBlank() }
            ?: account?.email

        if (account != null || !activeEmail.isNullOrBlank()) {
            val googleAccount: Account? = account?.account ?: activeEmail?.let { Account(it, "com.google") }
            if (googleAccount != null) {
                Log.d(TAG, "Using GoogleAccountCredential for DriveWatchlistSync (email=${googleAccount.name}, byocActive=$byocActive)")
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
        }

        Log.e(TAG, "Cannot sync watchlist: no Bearer token, GoogleSignIn account, or active BYOC session available (byocActive=$byocActive)")
        return null
    }

    companion object {
        private const val WATCHLIST_FILE = "clotube_watchlist.json"
        private const val CLOUD_PULL_COOLDOWN_MS = 5_000L
        private const val DEBOUNCE_DELAY_MS = 350L

        @Volatile
        private var instance: DriveWatchlistSync? = null

        @Volatile
        private var tokenProviderRef: (suspend () -> String)? = null

        fun getInstance(context: Context): DriveWatchlistSync {
            return instance ?: synchronized(this) {
                instance ?: DriveWatchlistSync(context.applicationContext).also { instance = it }
            }
        }

        fun setTokenProvider(provider: suspend () -> String) {
            this.tokenProviderRef = provider
        }
    }
}
