package com.clotube.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.clotube.app.model.WatchlistEntry
import com.clotube.app.model.WatchlistMediaType
import com.clotube.app.model.WatchlistPayload
import com.clotube.app.model.WatchlistStatus
import com.google.android.gms.auth.api.signin.GoogleSignIn
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe singleton managing Watchlist local persistence, in-memory state,
 * title normalization deduplication, tombstones, and reactive Compose updates.
 */
object WatchlistManager {

    private const val TAG = "WatchlistManager"
    private const val FILE_NAME = "clotube_watchlist.json"

    /**
     * Reactive Compose state trigger. Composable functions reading this revision
     * will automatically and immediately recompose upon any local mutation or cloud merge.
     */
    var watchlistRevision by mutableIntStateOf(0)
        private set

    /**
     * In-memory cache mapping canonical keys (normalizedTitle.ifBlank { id }) to WatchlistEntry.
     */
    private val memoryCache = ConcurrentHashMap<String, WatchlistEntry>()

    @Volatile
    private var currentlyLoadedEmail: String? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun notifyRevisionChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            watchlistRevision++
        } else {
            mainHandler.post {
                watchlistRevision++
            }
        }
    }

    /**
     * Normalizes video and series titles by removing file extensions, bracketed tags,
     * resolutions, video codecs, and release suffixes so cross-device catalog matching succeeds.
     */
    fun normalizeTitle(rawTitle: String): String {
        if (rawTitle.isBlank()) return ""

        var title = rawTitle.trim()

        // 1. Strip common video extensions
        title = title.replace(Regex("""\.(mkv|mp4|avi|mov|webm|flv|wmv|m4v|ts)$""", RegexOption.IGNORE_CASE), "")

        // 2. Strip bracketed or parenthesized tags (e.g. [1080p], (2022), [BluRay], <...>, {...}, etc.)
        title = title.replace(Regex("""\[.*?\]|\(.*?\)|<.*?>|\{.*?\}""", RegexOption.IGNORE_CASE), " ")

        // 3. Replace punctuation, quotes, and separator characters with spaces
        title = title.replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(':', ' ')
            .replace('\'', ' ')
            .replace('’', ' ')
            .replace('`', ' ')
            .replace('&', ' ')
            .replace(',', ' ')
            .replace(';', ' ')
            .replace('!', ' ')
            .replace('?', ' ')
            .replace('/', ' ')
            .replace('\\', ' ')

        // 4. Strip noise tokens (resolutions, formats, sources, codecs, audio, languages, years, tags)
        val noiseRegex = Regex(
            """(?i)\b(19\d{2}|20[0-3]\d|2160p|1080p|720p|480p|360p|4k|uhd|fhd|hd|sd|hdr|hdr10|hdr10\+|dv|dolby|vision|imax|web-?dl|web-?rip|blu-?ray|brrip|bdrip|hdrip|dvdrip|hdcam|camrip|predvd|remux|repack|proper|x264|x265|h264|h265|hevc|avc|av1|10bit|8bit|aac|ac3|eac3|ddp?5\s*1|dd5\s*1|dts|truehd|atmos|5\s*1|7\s*1|2\s*0|esub|esubs|hc|hardcoded|subbed|dubbed|hindi|english|tamil|telugu|malayalam|kannada|bengali|punjabi|dual\s*audio|multi\s*audio|full\s*movie|bollywood|hollywood|unrated|extended|remastered|directors?\s*cut)\b"""
        )
        title = title.replace(noiseRegex, " ")

        // 5. Lowercase and normalize common vowel doubling / transliteration variations
        title = title.lowercase()
            .replace("ii", "i")
            .replace("aa", "a")
            .replace("ee", "i")
            .replace("oo", "u")

        // 6. Collapse consecutive whitespace, and trim
        return title.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * Noise tokens for determining cleaner display titles.
     */
    private val TITLE_NOISE_REGEX = Regex(
        """(?i)\b(19\d{2}|20[0-3]\d|2160p|1080p|720p|480p|4k|uhd|fhd|hd|sd|hdr|hdr10|bluray|bdrip|brrip|web-?dl|web-?rip|dvdrip|remux|x264|x265|hevc|aac|esub|esubs|hc|hindi|english|tamil|telugu|dual\s*audio|multi\s*audio|full\s*movie|bollywood|hollywood|mkv|mp4|avi)\b"""
    )

    /**
     * Determines whether [titleA] is a cleaner display title than [titleB].
     * Prefers titles with fewer noise words, or shorter non-blank titles.
     */
    fun pickCleanerTitle(titleA: String, titleB: String): String {
        val a = titleA.trim()
        val b = titleB.trim()
        if (a.isBlank()) return b
        if (b.isBlank()) return a

        val noiseCountA = TITLE_NOISE_REGEX.findAll(a).count()
        val noiseCountB = TITLE_NOISE_REGEX.findAll(b).count()

        return when {
            noiseCountA < noiseCountB -> a
            noiseCountB < noiseCountA -> b
            a.length <= b.length -> a
            else -> b
        }
    }

    /**
     * Computes the canonical dictionary key for an entry using title normalization.
     */
    fun canonicalKey(id: String, title: String): String {
        val norm = normalizeTitle(title)
        return norm.ifBlank { id.trim() }
    }

    /**
     * Returns true when [id] is a temporary external catalog identifier (TMDB/IMDb/Cinemeta)
     * rather than a canonical Google Drive fileId/folderId.
     */
    fun isTemporaryExternalId(id: String): Boolean {
        val trimmed = id.trim().lowercase()
        if (trimmed.isBlank()) return true
        return trimmed.startsWith("tmdb_") ||
            trimmed.startsWith("imdb_") ||
            trimmed.startsWith("cinemeta_") ||
            trimmed.startsWith("ext_") ||
            trimmed.matches(Regex("^tt\\d+$"))
    }

    /**
     * Resolves the winning ID when merging two entries: always prefers a real Google Drive
     * fileId over a temporary external ID (e.g., tmdb_movie_*).
     */
    private fun pickPreferredId(primaryId: String, secondaryId: String): String {
        val p = primaryId.trim()
        val s = secondaryId.trim()
        if (p.isBlank()) return s
        if (s.isBlank()) return p
        val pExt = isTemporaryExternalId(p)
        val sExt = isTemporaryExternalId(s)
        return when {
            !pExt && sExt -> p
            pExt && !sExt -> s
            else -> p
        }
    }

    /**
     * Merges two duplicate entries collapsing to the same canonical identity.
     * Prioritizes real Drive fileIds, cleaner display titles, valid non-blank artwork,
     * newest status, and earliest addedAt.
     */
    fun mergeDuplicateEntries(existing: WatchlistEntry, incoming: WatchlistEntry): WatchlistEntry {
        val newest = if (incoming.updatedAt >= existing.updatedAt) incoming else existing
        val older = if (newest === incoming) existing else incoming
        val preferredId = pickPreferredId(newest.id, older.id)
        val cleanTitle = pickCleanerTitle(existing.title, incoming.title)
        val canonicalNorm = normalizeTitle(cleanTitle)

        val bestPoster = existing.posterUrl.takeIf { it.isNotBlank() }
            ?: incoming.posterUrl.takeIf { it.isNotBlank() }
            ?: ""
        val bestBackdrop = existing.backdropUrl.takeIf { it.isNotBlank() }
            ?: incoming.backdropUrl.takeIf { it.isNotBlank() }
            ?: ""
        val bestYear = existing.year.takeIf { it.isNotBlank() }
            ?: incoming.year.takeIf { it.isNotBlank() }
            ?: ""
        val bestGenre = existing.genre.takeIf { it.isNotBlank() }
            ?: incoming.genre.takeIf { it.isNotBlank() }
            ?: ""

        val effectiveAddedAt = when {
            existing.addedAt > 0L && incoming.addedAt > 0L -> minOf(existing.addedAt, incoming.addedAt)
            existing.addedAt > 0L -> existing.addedAt
            incoming.addedAt > 0L -> incoming.addedAt
            else -> newest.addedAt
        }

        val effectiveIsDeleted = existing.isDeleted && incoming.isDeleted
        val effectiveStatus = if (!effectiveIsDeleted) {
            if (newest.status != WatchlistStatus.UNWATCHED) newest.status
            else if (existing.status != WatchlistStatus.UNWATCHED) existing.status
            else incoming.status
        } else {
            WatchlistStatus.UNWATCHED
        }

        return newest.copy(
            id = preferredId,
            title = cleanTitle,
            normalizedTitle = canonicalNorm,
            mediaType = newest.mediaType,
            status = effectiveStatus,
            posterUrl = bestPoster,
            backdropUrl = bestBackdrop,
            year = bestYear,
            genre = bestGenre,
            addedAt = effectiveAddedAt,
            updatedAt = maxOf(existing.updatedAt, incoming.updatedAt),
            isDeleted = effectiveIsDeleted
        )
    }

    /**
     * Resolves the profile-specific file path on disk.
     */
    private fun getFile(context: Context, email: String?): File {
        val dir = ProfileManager.getProfileDir(context, email)
        val file = File(dir, FILE_NAME)
        if (!file.exists()) {
            val legacyEmail = email?.takeIf { it.isNotBlank() } ?: "default"
            val legacySanitized = legacyEmail.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val legacyDirFile = File(context.filesDir, "profiles/$legacySanitized/$FILE_NAME")
            val legacyRootFile = File(context.filesDir, FILE_NAME)
            if (legacyDirFile.exists()) {
                try {
                    legacyDirFile.copyTo(file, overwrite = true)
                } catch (_: Exception) {}
            } else if (legacyRootFile.exists()) {
                try {
                    legacyRootFile.copyTo(file, overwrite = true)
                } catch (_: Exception) {}
            }
        }
        return file
    }

    /**
     * Reloads watchlist for the target profile email into memory.
     */
    @Synchronized
    fun reloadProfile(context: Context, email: String? = null) {
        currentlyLoadedEmail = null
        memoryCache.clear()
        ensureLoaded(context, email)
    }

    /**
     * Clears in-memory cache when signing out or switching profiles.
     */
    @Synchronized
    fun clearInMemoryCache() {
        memoryCache.clear()
        currentlyLoadedEmail = null
        notifyRevisionChanged()
    }

    private fun resolveActiveEmail(context: Context, email: String? = null): String {
        return email?.takeIf { it.isNotBlank() }
            ?: ProfileManager.activeProfileEmail.value?.takeIf { it.isNotBlank() }
            ?: GoogleSignIn.getLastSignedInAccount(context)?.email.orEmpty()
    }

    /**
     * Ensures that the local profile JSON has been loaded into [memoryCache].
     */
    @Synchronized
    fun ensureLoaded(context: Context, email: String? = null) {
        val resolvedEmail = resolveActiveEmail(context, email)
        if (currentlyLoadedEmail == resolvedEmail && memoryCache.isNotEmpty()) {
            return
        }

        memoryCache.clear()
        currentlyLoadedEmail = resolvedEmail

        var didDeduplicate = false
        val file = getFile(context, resolvedEmail)
        if (file.exists() && file.length() > 0) {
            try {
                val rawJson = file.readText(Charsets.UTF_8)
                val payload = WatchlistPayload.fromJsonString(rawJson)
                for (item in payload.items) {
                    val key = canonicalKey(item.id, item.title)
                    val existing = memoryCache[key]
                    if (existing == null) {
                        // Check if any existing item has the same normalized title under a different key
                        val altEntry = memoryCache.entries.firstOrNull { (_, v) ->
                            canonicalKey(v.id, v.title) == key
                        }
                        if (altEntry != null) {
                            val merged = mergeDuplicateEntries(altEntry.value, item)
                            memoryCache.remove(altEntry.key)
                            memoryCache[key] = merged
                            didDeduplicate = true
                        } else {
                            memoryCache[key] = item.copy(normalizedTitle = key)
                        }
                    } else {
                        // Both exist for this canonical key
                        if (!existing.isDeleted && !item.isDeleted) {
                            memoryCache[key] = mergeDuplicateEntries(existing, item)
                            didDeduplicate = true
                        } else {
                            // Keep newer updatedAt
                            if (item.updatedAt > existing.updatedAt) {
                                memoryCache[key] = item.copy(normalizedTitle = key)
                            }
                            didDeduplicate = true
                        }
                    }
                }
                Log.d(TAG, "Loaded ${memoryCache.size} watchlist entries for profile '$resolvedEmail' (deduplicated=$didDeduplicate)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load watchlist from disk: ${e.message}", e)
            }
        }

        if (didDeduplicate) {
            persistToDisk(context)
            try {
                DriveWatchlistSync.getInstance(context).enqueuePush()
            } catch (_: Exception) {}
        }

        notifyRevisionChanged()
    }

    /**
     * Persists current [memoryCache] to local profile storage.
     */
    @Synchronized
    private fun persistToDisk(context: Context) {
        try {
            val email = currentlyLoadedEmail?.takeIf { it.isNotBlank() } ?: resolveActiveEmail(context)
            val file = getFile(context, email)
            val payload = WatchlistPayload(
                version = 1,
                updatedAt = System.currentTimeMillis(),
                items = memoryCache.values.toList()
            )
            file.writeText(payload.toJsonString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save watchlist to disk: ${e.message}", e)
        }
    }

    /**
     * Retrieves an active entry by ID or normalized title.
     * Returns null if missing or marked as deleted.
     */
    fun getEntry(id: String, title: String): WatchlistEntry? {
        val safeId = id.trim()
        val normTitle = normalizeTitle(title)
        val key = canonicalKey(id, title)

        // 1. Direct match by canonical key
        memoryCache[key]?.let { if (!it.isDeleted) return it }

        // 2. Direct match by id
        for (entry in memoryCache.values) {
            if (entry.id == safeId && !entry.isDeleted) {
                return entry
            }
        }

        // 3. Fallback match by normalized title
        if (normTitle.isNotBlank()) {
            for (entry in memoryCache.values) {
                if (canonicalKey(entry.id, entry.title) == normTitle && !entry.isDeleted) {
                    return entry
                }
            }
        }

        return null
    }

    /**
     * Retrieves the current WatchlistStatus for the given media item.
     */
    fun getStatus(id: String, title: String): WatchlistStatus {
        return getEntry(id, title)?.status ?: WatchlistStatus.UNWATCHED
    }

    /**
     * Updates or sets the status of a watchlist item.
     * If status == UNWATCHED, writes a deletion tombstone so remote devices delete it deterministically.
     */
    @Synchronized
    fun setStatus(
        context: Context,
        id: String,
        title: String,
        mediaType: WatchlistMediaType,
        status: WatchlistStatus,
        posterUrl: String = "",
        backdropUrl: String = "",
        year: String = "",
        genre: String = ""
    ) {
        ensureLoaded(context)

        val safeId = id.trim().ifBlank { title.trim() }
        val safeTitle = title.trim().ifBlank { id.trim() }
        val normTitle = normalizeTitle(safeTitle)
        val key = canonicalKey(safeId, safeTitle)

        // Find existing or any duplicates matching safeId or normTitle
        val matchingEntries = memoryCache.entries.filter { (k, v) ->
            k == key || v.id == safeId || (normTitle.isNotBlank() && canonicalKey(v.id, v.title) == key)
        }

        val existing = matchingEntries.maxByOrNull { it.value.updatedAt }?.value

        // Remove any stale duplicate keys from memoryCache
        for (entry in matchingEntries) {
            if (entry.key != key) {
                memoryCache.remove(entry.key)
            }
        }

        val now = System.currentTimeMillis()
        val effectiveAddedAt = existing?.addedAt?.takeIf { it > 0L } ?: now
        val effectiveId = if (existing != null) pickPreferredId(safeId, existing.id) else safeId

        val effectivePoster = posterUrl.ifBlank { existing?.posterUrl.orEmpty() }
        val effectiveBackdrop = backdropUrl.ifBlank { existing?.backdropUrl.orEmpty() }
        val effectiveYear = year.ifBlank { existing?.year.orEmpty() }
        val effectiveGenre = genre.ifBlank { existing?.genre.orEmpty() }
        val effectiveTitle = if (existing != null) pickCleanerTitle(existing.title, safeTitle) else safeTitle

        val updatedEntry = if (status == WatchlistStatus.UNWATCHED) {
            // Tombstone deletion entry
            WatchlistEntry(
                id = effectiveId,
                normalizedTitle = normTitle.ifBlank { key },
                title = effectiveTitle,
                mediaType = mediaType,
                status = WatchlistStatus.UNWATCHED,
                posterUrl = effectivePoster,
                backdropUrl = effectiveBackdrop,
                year = effectiveYear,
                genre = effectiveGenre,
                addedAt = effectiveAddedAt,
                updatedAt = now,
                isDeleted = true
            )
        } else {
            // Active entry
            WatchlistEntry(
                id = effectiveId,
                normalizedTitle = normTitle.ifBlank { key },
                title = effectiveTitle,
                mediaType = mediaType,
                status = status,
                posterUrl = effectivePoster,
                backdropUrl = effectiveBackdrop,
                year = effectiveYear,
                genre = effectiveGenre,
                addedAt = effectiveAddedAt,
                updatedAt = now,
                isDeleted = false
            )
        }

        memoryCache[key] = updatedEntry
        persistToDisk(context)
        notifyRevisionChanged()

        // Trigger debounced cloud synchronization
        try {
            DriveWatchlistSync.getInstance(context).enqueuePush()
        } catch (e: Exception) {
            Log.w(TAG, "Could not enqueue DriveWatchlistSync push: ${e.message}")
        }
    }

    /**
     * Quick toggle helper: switches between WATCH_LATER and UNWATCHED.
     */
    fun toggleWatchLater(
        context: Context,
        id: String,
        title: String,
        mediaType: WatchlistMediaType,
        posterUrl: String = "",
        backdropUrl: String = "",
        year: String = "",
        genre: String = ""
    ) {
        val currentStatus = getStatus(id, title)
        val newStatus = if (currentStatus == WatchlistStatus.WATCH_LATER) {
            WatchlistStatus.UNWATCHED
        } else {
            WatchlistStatus.WATCH_LATER
        }
        setStatus(
            context = context,
            id = id,
            title = title,
            mediaType = mediaType,
            status = newStatus,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            year = year,
            genre = genre
        )
    }

    /**
     * Removes an item from the watchlist by setting it to UNWATCHED (writing a tombstone).
     */
    fun removeFromWatchlist(
        context: Context,
        id: String,
        title: String,
        mediaType: WatchlistMediaType
    ) {
        setStatus(
            context = context,
            id = id,
            title = title,
            mediaType = mediaType,
            status = WatchlistStatus.UNWATCHED
        )
    }

    /**
     * Returns all active watchlist items, optionally filtered by status, deduplicated by
     * canonical title identity, and sorted descending by updatedAt.
     */
    fun getActiveItems(filter: WatchlistStatus? = null): List<WatchlistEntry> {
        val activeList = memoryCache.values.asSequence()
            .filter { !it.isDeleted && it.status != WatchlistStatus.UNWATCHED }
            .filter { filter == null || it.status == filter }
            .toList()

        // Group by canonical key to guarantee no duplicates in active items
        val dedupedMap = LinkedHashMap<String, WatchlistEntry>()
        for (item in activeList) {
            val key = canonicalKey(item.id, item.title)
            val existing = dedupedMap[key]
            if (existing == null) {
                dedupedMap[key] = item
            } else {
                dedupedMap[key] = mergeDuplicateEntries(existing, item)
            }
        }

        return dedupedMap.values.sortedByDescending { it.updatedAt }
    }

    /**
     * Deterministic Last-Write-Wins (LWW) merge of remote entries into local state,
     * ensuring duplicate entries collapsing to the same canonical key are merged.
     */
    @Synchronized
    fun mergeRemoteEntries(context: Context, remoteItems: List<WatchlistEntry>): Boolean {
        ensureLoaded(context)
        var modified = false

        for (remote in remoteItems) {
            val key = canonicalKey(remote.id, remote.title)

            // Look up existing local entry by canonical key or matching normalized title/id
            val existingEntry = memoryCache.entries.firstOrNull { (k, v) ->
                k == key || v.id == remote.id || canonicalKey(v.id, v.title) == key
            }

            if (existingEntry == null) {
                // Adopt remote entry unconditionally (including tombstones)
                memoryCache[key] = remote.copy(normalizedTitle = key)
                modified = true
            } else {
                val existingKey = existingEntry.key
                val local = existingEntry.value

                if (!local.isDeleted && !remote.isDeleted) {
                    val merged = mergeDuplicateEntries(local, remote)
                    if (existingKey != key) {
                        memoryCache.remove(existingKey)
                        modified = true
                    }
                    if (merged != local) {
                        modified = true
                    }
                    memoryCache[key] = merged
                } else {
                    // Last-Write-Wins: newer timestamp wins unconditionally
                    if (remote.updatedAt > local.updatedAt) {
                        val preferredId = pickPreferredId(remote.id, local.id)
                        if (existingKey != key) {
                            memoryCache.remove(existingKey)
                        }
                        memoryCache[key] = remote.copy(
                            id = preferredId,
                            normalizedTitle = key
                        )
                        modified = true
                    }
                }
            }
        }

        if (modified) {
            persistToDisk(context)
            notifyRevisionChanged()
            Log.i(TAG, "Merged remote watchlist payload: ${remoteItems.size} items processed, local cache updated")
        }

        return modified
    }

    /**
     * Serializes current memory state into a full WatchlistPayload JSON string.
     */
    fun buildPayloadJson(): String {
        return buildPayloadSnapshot().first
    }

    /**
     * Returns a pair of (serializedJson, deterministicItemsHash) where the hash is computed
     * exclusively over the sorted items array (excluding volatile root updatedAt).
     */
    fun buildPayloadSnapshot(): Pair<String, String> {
        val payload = WatchlistPayload(
            version = 1,
            updatedAt = System.currentTimeMillis(),
            items = memoryCache.values.toList()
        )
        return payload.toJsonString() to payload.computeItemsHash()
    }

    /**
     * Clears all local profile data and resets cache.
     */
    @Synchronized
    fun clearAll(context: Context) {
        memoryCache.clear()
        try {
            val email = currentlyLoadedEmail?.takeIf { it.isNotBlank() } ?: resolveActiveEmail(context)
            getFile(context, email).delete()
        } catch (_: Exception) {}
        currentlyLoadedEmail = null
        notifyRevisionChanged()
    }
}
