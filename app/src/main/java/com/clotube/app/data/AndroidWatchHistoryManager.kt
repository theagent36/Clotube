package com.clotube.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class AndroidTypedEntry(
    val type: String,
    val value: Any
)

/**
 * Pure volatile RAM-only watch history state holder.
 * Zero local disk persistence: never reads from or writes to filesDir, cacheDir, or DataStore.
 */
object AndroidWatchHistoryManager {
    private const val TAG = "ClotubeSync"

    private val volatileEntries = ConcurrentHashMap<String, AndroidTypedEntry>()
    private val archivedWatchedIds = ConcurrentHashMap.newKeySet<String>()

    private val _stateRevision = MutableStateFlow(0L)
    val stateRevision: StateFlow<Long> = _stateRevision.asStateFlow()

    /**
     * One-time cleanup of all legacy local watch history JSON files and DataStore protobufs
     * so stale cached history can never resurrect or conflict with Google Drive.
     */
    fun purgeAllLocalDiskCaches(context: Context) {
        try {
            val appCtx = context.applicationContext
            val profilesDir = File(appCtx.filesDir, "profiles")
            if (profilesDir.exists() && profilesDir.isDirectory) {
                profilesDir.walkTopDown().forEach { file ->
                    if (file.isFile && (
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
            Log.i(TAG, "Purged all legacy local watch history disk caches")
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
        archiveIds: Set<String>
    ) {
        volatileEntries.clear()
        volatileEntries.putAll(activeEntries)

        archivedWatchedIds.clear()
        archivedWatchedIds.addAll(archiveIds)

        _stateRevision.value = System.currentTimeMillis()
    }

    fun updateEntries(mutations: MutableMap<String, AndroidTypedEntry?>.() -> Unit) {
        val working = HashMap(volatileEntries)
        working.mutations()
        volatileEntries.clear()
        working.forEach { (k, v) ->
            if (v != null) volatileEntries[k] = v
        }
        _stateRevision.value = System.currentTimeMillis()
    }

    fun clearInMemory() {
        volatileEntries.clear()
        archivedWatchedIds.clear()
        _stateRevision.value = System.currentTimeMillis()
    }

    // Backwards-compatible RAM-only overloads for existing callers
    fun loadLocalSync(
        @Suppress("UNUSED_PARAMETER") context: Context? = null,
        @Suppress("UNUSED_PARAMETER") email: String = ""
    ): MutableMap<String, AndroidTypedEntry> = HashMap(volatileEntries)

    fun clearLocal(
        @Suppress("UNUSED_PARAMETER") context: Context? = null,
        @Suppress("UNUSED_PARAMETER") email: String = ""
    ) {
        clearInMemory()
    }

    fun isEpisodeWatched(fileId: String): Boolean {
        if (fileId.isBlank()) return false
        val explicit = volatileEntries["${fileId}_is_watched"]?.value as? Boolean
        if (explicit != null) return explicit
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
        val wasArchived = archivedWatchedIds.remove(fileId)

        updateEntries {
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

        DriveWatchHistorySync.pushHistoryAsync(
            context = context,
            force = true,
            updateArchive = wasArchived
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

    fun removeEntriesForMedia(mediaId: String, seriesId: String?): Boolean {
        val removedFromArchive = archivedWatchedIds.remove(mediaId)
        updateEntries {
            val toDelete = keys.filter { key ->
                key.startsWith("${mediaId}_") ||
                    (!seriesId.isNullOrBlank() && key.startsWith("series_resume_${seriesId}_"))
            }
            toDelete.forEach { remove(it) }
        }
        return removedFromArchive
    }

    fun removeEntriesForMedia(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") email: String,
        mediaId: String,
        seriesId: String?
    ): Boolean = removeEntriesForMedia(mediaId, seriesId)

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
