package com.clotube.app.data

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class AndroidTypedEntry(val type: String, val value: Any)

object AndroidWatchHistoryManager {
    private const val MAX_V2_PAYLOAD_BYTES = 28 * 1024
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val memoryCache = ConcurrentHashMap<String, MutableMap<String, AndroidTypedEntry>>()

    private val ITEM_SUFFIXES = listOf(
        "_is_watched",
        "_is_series",
        "_series_id",
        "_season_num",
        "_episode_num",
        "_timestamp",
        "_fraction",
        "_title",
        "_pos",
        "_dur"
    )

    private val NON_ITEM_PREFIXES = listOf(
        "series_resume_",
        "global_",
        "last_global_",
        "snapshot_",
        "progress_",
        "playback_pos_",
        "playback_dur_",
        "completed_",
        "timestamp_",
        "watch_progress_",
        "episode_title_"
    )

    private fun getFile(context: Context, email: String): File {
        val dir = File(context.filesDir, "profiles/${email.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "clotube_watch_history.json")
    }

    private fun getV2File(context: Context, email: String): File {
        val dir = File(context.filesDir, "profiles/${email.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "clotube_active_sync.json")
    }

    @Synchronized
    fun loadLocalSync(context: Context, email: String): MutableMap<String, AndroidTypedEntry> {
        if (email.isBlank()) return mutableMapOf()
        memoryCache[email]?.let { return it }

        val file = getFile(context, email)
        val entries = mutableMapOf<String, AndroidTypedEntry>()

        if (file.exists() && file.length() > 0) {
            try {
                val json = file.readText(Charsets.UTF_8)
                val root = JsonParser.parseString(json).asJsonObject
                val entriesObj = root.getAsJsonObject("entries")
                if (entriesObj != null) {
                    for ((k, v) in entriesObj.entrySet()) {
                        val entryObj = v.asJsonObject
                        val type = entryObj.get("type")?.asString ?: "string"
                        val valueElem = entryObj.get("value")
                        val value: Any = when (type) {
                            "long" -> valueElem?.asLong ?: 0L
                            "int" -> valueElem?.asInt ?: 0
                            "float" -> valueElem?.asFloat ?: 0f
                            "boolean" -> valueElem?.asBoolean ?: false
                            else -> valueElem?.asString ?: ""
                        }
                        entries[k] = AndroidTypedEntry(type, value)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        memoryCache[email] = entries
        return entries
    }

    @Synchronized
    fun saveLocal(context: Context, email: String, entries: Map<String, AndroidTypedEntry>) {
        if (email.isBlank()) return
        try {
            val root = JsonObject().apply {
                addProperty("version", 1)
                addProperty("updatedAt", System.currentTimeMillis())
                val entriesObj = JsonObject()
                for ((k, v) in entries) {
                    val entryObj = JsonObject()
                    entryObj.addProperty("type", v.type)
                    when (v.value) {
                        is Number -> entryObj.addProperty("value", v.value)
                        is Boolean -> entryObj.addProperty("value", v.value)
                        else -> entryObj.addProperty("value", v.value.toString())
                    }
                    entriesObj.add(k, entryObj)
                }
                add("entries", entriesObj)
            }

            val file = getFile(context, email)
            file.writeText(gson.toJson(root), Charsets.UTF_8)

            val v2Json = buildV2PayloadFromEntries(entries).toJsonString()
            getV2File(context, email).writeText(v2Json, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun clearLocal(context: Context, email: String) {
        if (email.isBlank()) return
        memoryCache.remove(email)
        try {
            getFile(context, email).delete()
            getV2File(context, email).delete()
        } catch (_: Exception) {}
    }

    fun isV2Payload(json: String): Boolean {
        if (json.isBlank()) return false
        return try {
            val root = JSONObject(json)
            root.optInt("v", 0) >= 2 ||
                (!root.has("entries") && (root.has("active") || root.has("watchedIds") || root.has("resume")))
        } catch (_: Exception) {
            false
        }
    }

    fun buildV2PayloadFromEntries(
        entries: Map<String, AndroidTypedEntry>,
        updatedAt: Long = System.currentTimeMillis()
    ): WatchHistoryPayloadV2 {
        val itemIds = linkedSetOf<String>()
        val seriesResumeIds = linkedSetOf<String>()

        for (key in entries.keys) {
            if (key == "watch_history_list_json") continue
            if (key.startsWith("series_resume_") && key.endsWith("_episode_id")) {
                val sid = key.removePrefix("series_resume_").removeSuffix("_episode_id")
                if (sid.isNotBlank()) seriesResumeIds.add(sid)
                continue
            }
            if (NON_ITEM_PREFIXES.any { key.startsWith(it) }) continue
            val matchedSuffix = ITEM_SUFFIXES.firstOrNull { key.endsWith(it) } ?: continue
            val id = key.removeSuffix(matchedSuffix)
            if (id.isNotBlank()) {
                itemIds.add(id)
            }
        }

        // Also migrate legacy DataStore keys (playback_pos_<id>, completed_<id>) if present without modern keys
        for (key in entries.keys) {
            when {
                key.startsWith("playback_pos_") -> {
                    val id = key.removePrefix("playback_pos_")
                    if (id.isNotBlank()) itemIds.add(id)
                }
                key.startsWith("completed_") -> {
                    val id = key.removePrefix("completed_")
                    if (id.isNotBlank()) itemIds.add(id)
                }
            }
        }

        val watchedIds = mutableListOf<String>()
        val activeItems = mutableListOf<HistoryItemEntityV2>()

        for (id in itemIds) {
            val title = (entries["${id}_title"]?.value as? String)
                ?: (entries["episode_title_${id}"]?.value as? String)
                ?: ""
            val pos = (entries["${id}_pos"]?.value as? Number)?.toLong()
                ?: (entries["playback_pos_${id}"]?.value as? Number)?.toLong()
                ?: 0L
            val dur = (entries["${id}_dur"]?.value as? Number)?.toLong()
                ?: (entries["playback_dur_${id}"]?.value as? Number)?.toLong()
                ?: 0L
            val frac = (entries["${id}_fraction"]?.value as? Number)?.toFloat()
                ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
            val ts = (entries["${id}_timestamp"]?.value as? Number)?.toLong()
                ?: (entries["timestamp_${id}"]?.value as? Number)?.toLong()
                ?: 0L
            val explicitWatched = (entries["${id}_is_watched"]?.value as? Boolean)
                ?: (entries["${id}_is_watched"]?.value as? String)?.toBooleanStrictOrNull()
                ?: (entries["completed_${id}"]?.value as? Boolean)
            val isWatched = explicitWatched ?: (frac >= 0.90f)

            val seriesId = (entries["${id}_series_id"]?.value as? String)?.ifBlank { null }
            val seasonNum = (entries["${id}_season_num"]?.value as? Number)?.toInt()
            val episodeNum = (entries["${id}_episode_num"]?.value as? Number)?.toInt()

            if (isWatched) {
                watchedIds.add(id)
            }
            if (ts > 0L || pos > 0L || dur > 0L || isWatched) {
                activeItems.add(
                    HistoryItemEntityV2(
                        id = id,
                        title = title,
                        positionMs = pos,
                        durationMs = dur,
                        timestamp = ts,
                        isWatched = isWatched,
                        seriesId = seriesId,
                        seasonNumber = seasonNum,
                        episodeNumber = episodeNum
                    )
                )
            }
        }

        val resumeMap = linkedMapOf<String, SeriesResumeEntityV2>()
        for (sid in seriesResumeIds) {
            val prefix = "series_resume_$sid"
            val epId = (entries["${prefix}_episode_id"]?.value as? String)?.trim().orEmpty()
            if (epId.isBlank()) continue
            val sNum = (entries["${prefix}_season_num"]?.value as? Number)?.toInt() ?: 1
            val eNum = (entries["${prefix}_episode_num"]?.value as? Number)?.toInt() ?: 1
            val posMs = (entries["${prefix}_pos_ms"]?.value as? Number)?.toLong() ?: 0L
            val durMs = (entries["${prefix}_dur_ms"]?.value as? Number)?.toLong() ?: 0L
            val ts = (entries["${prefix}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            resumeMap[sid] = SeriesResumeEntityV2(
                episodeId = epId,
                seasonNumber = sNum,
                episodeNumber = eNum,
                positionMs = posMs,
                durationMs = durMs,
                timestamp = ts
            )
        }

        return WatchHistoryPayloadV2(
            version = 2,
            updatedAt = updatedAt,
            active = activeItems,
            resume = resumeMap,
            watchedIds = watchedIds
        ).prunedToMaxBytes(MAX_V2_PAYLOAD_BYTES)
    }

    /**
     * Returns the V2-format JSON payload for the given user's local history,
     * built from the in-memory cache (which is authoritative after setEpisodeWatched mutations).
     */
    fun getV2PayloadJson(context: Context, email: String): String {
        val entries = loadLocalSync(context, email)
        val snapshot = synchronized(entries) { entries.toMap() }
        return buildV2PayloadFromEntries(snapshot).toJsonString()
    }

    /**
     * Merges a remote V2 payload into the local profile-scoped file-based cache.
     *
     * Reconciliation strategy (timestamp-wins with explicit unwatch support):
     * - For each item in remote `active`: if remote timestamp > local, adopt the remote item's
     *   isWatched flag exactly. If the remote says isWatched=false, the local `_is_watched` key
     *   is written as false, so subsequent buildV2PayloadFromEntries excludes it from watchedIds.
     * - For IDs in remote `watchedIds` that have no corresponding `active` entry: mark watched
     *   only if local timestamp is not newer with an explicit unwatched state.
     * - Resume pointers merge by timestamp-wins as before.
     */
    fun mergeRemoteV2Payload(context: Context, email: String, remoteJson: String): Boolean {
        if (email.isBlank() || remoteJson.isBlank()) return false
        if (!isV2Payload(remoteJson)) {
            return mergeRemotePayload(context, email, remoteJson)
        }

        val local = loadLocalSync(context, email)
        var changed = false

        try {
            val payload = WatchHistoryPayloadV2.fromJsonString(remoteJson)
            synchronized(local) {
                val remoteWatchedSet = payload.watchedIds.toSet()

                // 1. Reconcile active items: adopt remote isWatched flag when remote is newer.
                for (item in payload.active) {
                    if (item.id.isBlank()) continue
                    val localTs = (local["${item.id}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                    val isMissing = !local.containsKey("${item.id}_pos") &&
                        !local.containsKey("${item.id}_is_watched")
                    if (item.timestamp > localTs || isMissing) {
                        if (item.title.isNotBlank()) {
                            local["${item.id}_title"] = AndroidTypedEntry("string", item.title)
                        }
                        // When unmarked (isWatched=false), write pos=0 and fraction=0
                        val effectivePos = if (item.isWatched) item.positionMs else 0L
                        val effectiveFrac = if (item.isWatched) {
                            if (item.durationMs > 0L)
                                (item.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
                            else 1.0f
                        } else {
                            0.0f
                        }
                        local["${item.id}_pos"] = AndroidTypedEntry("long", effectivePos)
                        local["${item.id}_dur"] = AndroidTypedEntry("long", item.durationMs)
                        local["${item.id}_fraction"] = AndroidTypedEntry("float", effectiveFrac)
                        local["${item.id}_timestamp"] = AndroidTypedEntry("long", item.timestamp)
                        // Authoritative: use the item's explicit isWatched from remote,
                        // also checking remoteWatchedSet for IDs that are only in watchedIds.
                        val watchedValue = item.isWatched || item.id in remoteWatchedSet
                        local["${item.id}_is_watched"] = AndroidTypedEntry("boolean", watchedValue)
                        local["${item.id}_is_series"] = AndroidTypedEntry("boolean", !item.seriesId.isNullOrBlank())
                        if (!item.seriesId.isNullOrBlank()) {
                            local["${item.id}_series_id"] = AndroidTypedEntry("string", item.seriesId)
                        }
                        if (item.seasonNumber != null) {
                            local["${item.id}_season_num"] = AndroidTypedEntry("int", item.seasonNumber)
                        }
                        if (item.episodeNumber != null) {
                            local["${item.id}_episode_num"] = AndroidTypedEntry("int", item.episodeNumber)
                        }
                        changed = true
                    }
                }

                // 2. For IDs in remoteWatchedSet that have no active entry:
                //    mark watched only when remote payload is newer than local timestamp.
                val coveredByActive = payload.active.map { it.id }.toSet()
                for (id in remoteWatchedSet) {
                    if (id.isBlank() || id in coveredByActive) continue
                    val localTs = (local["${id}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                    val localExplicit = local["${id}_is_watched"]?.value as? Boolean
                    // Only override if the remote payload is authoritative (newer) or not set locally
                    if (localExplicit != false || payload.updatedAt > localTs) {
                        if (localExplicit != true) {
                            local["${id}_is_watched"] = AndroidTypedEntry("boolean", true)
                            changed = true
                        }
                        if (!local.containsKey("${id}_fraction")) {
                            local["${id}_fraction"] = AndroidTypedEntry("float", 1.0f)
                            changed = true
                        }
                        if (!local.containsKey("${id}_timestamp")) {
                            local["${id}_timestamp"] = AndroidTypedEntry("long", payload.updatedAt)
                            changed = true
                        }
                    }
                }

                // 3. Merge series resume pointers by timestamp-wins.
                for ((sid, res) in payload.resume) {
                    if (sid.isBlank() || res.episodeId.isBlank()) continue
                    val prefix = "series_resume_$sid"
                    val localTs = (local["${prefix}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                    val isMissing = !local.containsKey("${prefix}_episode_id")
                    if (res.timestamp > localTs || isMissing) {
                        local["${prefix}_episode_id"] = AndroidTypedEntry("string", res.episodeId)
                        local["${prefix}_season_num"] = AndroidTypedEntry("int", res.seasonNumber)
                        local["${prefix}_episode_num"] = AndroidTypedEntry("int", res.episodeNumber)
                        local["${prefix}_pos_ms"] = AndroidTypedEntry("long", res.positionMs)
                        local["${prefix}_dur_ms"] = AndroidTypedEntry("long", res.durationMs)
                        local["${prefix}_timestamp"] = AndroidTypedEntry("long", res.timestamp)
                        changed = true
                    }
                }
            }

            if (changed) {
                saveLocal(context, email, local)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return changed
    }

    fun getLocalPayloadJson(context: Context, email: String): String {
        val entries = loadLocalSync(context, email)
        val root = JsonObject().apply {
            addProperty("version", 1)
            addProperty("updatedAt", System.currentTimeMillis())
            val entriesObj = JsonObject()
            for ((k, v) in entries) {
                val entryObj = JsonObject()
                entryObj.addProperty("type", v.type)
                when (v.value) {
                    is Number -> entryObj.addProperty("value", v.value)
                    is Boolean -> entryObj.addProperty("value", v.value)
                    else -> entryObj.addProperty("value", v.value.toString())
                }
                entriesObj.add(k, entryObj)
            }
            add("entries", entriesObj)
        }
        return gson.toJson(root)
    }

    private fun resolveTimestampKey(key: String): String {
        return if (key.startsWith("series_resume_")) {
            val suffix = when {
                key.endsWith("_episode_id") -> "_episode_id"
                key.endsWith("_season_num") -> "_season_num"
                key.endsWith("_episode_num") -> "_episode_num"
                key.endsWith("_pos_ms") -> "_pos_ms"
                key.endsWith("_dur_ms") -> "_dur_ms"
                key.endsWith("_timestamp") -> "_timestamp"
                else -> ""
            }
            if (suffix.isNotEmpty()) {
                val seriesId = key.removePrefix("series_resume_").removeSuffix(suffix)
                "series_resume_${seriesId}_timestamp"
            } else {
                "${key.substringBeforeLast('_')}_timestamp"
            }
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
                key.endsWith("_season_num") -> "_season_num"
                key.endsWith("_episode_num") -> "_episode_num"
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

    fun mergeRemotePayload(context: Context, email: String, remoteJson: String): Boolean {
        if (email.isBlank() || remoteJson.isBlank()) return false
        val local = loadLocalSync(context, email)
        var changed = false

        try {
            val root = JsonParser.parseString(remoteJson).asJsonObject
            val entriesObj = root.getAsJsonObject("entries") ?: return false

            for ((key, valueElem) in entriesObj.entrySet()) {
                val entryObj = valueElem.asJsonObject
                val type = entryObj.get("type")?.asString ?: "string"
                val v = entryObj.get("value")
                val remoteValue: Any = when (type) {
                    "long" -> v?.asLong ?: 0L
                    "int" -> v?.asInt ?: 0
                    "float" -> v?.asFloat ?: 0f
                    "boolean" -> v?.asBoolean ?: false
                    else -> v?.asString ?: ""
                }

                val localEntry = local[key]
                if (localEntry == null) {
                    local[key] = AndroidTypedEntry(type, remoteValue)
                    changed = true
                } else {
                    val tsKey = resolveTimestampKey(key)
                    val remoteTs = entriesObj.getAsJsonObject(tsKey)?.get("value")?.asLong ?: 0L
                    val localTs = (local[tsKey]?.value as? Long) ?: 0L

                    if (remoteTs > localTs) {
                        local[key] = AndroidTypedEntry(type, remoteValue)
                        changed = true
                    }
                }
            }

            if (changed) {
                saveLocal(context, email, local)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return changed
    }

    fun recordPlayback(
        context: Context,
        email: String,
        fileId: String,
        title: String,
        posMs: Long,
        durMs: Long,
        isSeries: Boolean,
        seriesId: String = "",
        seasonNumber: Int = 1,
        episodeNumber: Int = 1
    ) {
        val local = loadLocalSync(context, email)
        val now = System.currentTimeMillis()
        val fraction = if (durMs > 0L) (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f) else 0f

        synchronized(local) {
            // Check if episode is already watched so replaying from 00:00 preserves watched status
            val alreadyWatched = isEpisodeWatched(context, email, fileId)
            val isCompletedNow = alreadyWatched || fraction >= 0.90f || (durMs > 0L && posMs >= (durMs * 0.90f))

            local["${fileId}_title"] = AndroidTypedEntry("string", title)
            local["${fileId}_pos"] = AndroidTypedEntry("long", posMs)
            local["${fileId}_dur"] = AndroidTypedEntry("long", durMs)
            local["${fileId}_fraction"] = AndroidTypedEntry("float", fraction)
            local["${fileId}_timestamp"] = AndroidTypedEntry("long", now)
            local["${fileId}_is_series"] = AndroidTypedEntry("boolean", isSeries)

            if (isCompletedNow) {
                local["${fileId}_is_watched"] = AndroidTypedEntry("boolean", true)
            }

            if (isSeries && seriesId.isNotBlank()) {
                local["${fileId}_series_id"] = AndroidTypedEntry("string", seriesId)
                local["${fileId}_season_num"] = AndroidTypedEntry("int", seasonNumber)
                local["${fileId}_episode_num"] = AndroidTypedEntry("int", episodeNumber)

                local["series_resume_${seriesId}_episode_id"] = AndroidTypedEntry("string", fileId)
                local["series_resume_${seriesId}_season_num"] = AndroidTypedEntry("int", seasonNumber)
                local["series_resume_${seriesId}_episode_num"] = AndroidTypedEntry("int", episodeNumber)
                local["series_resume_${seriesId}_pos_ms"] = AndroidTypedEntry("long", posMs)
                local["series_resume_${seriesId}_dur_ms"] = AndroidTypedEntry("long", durMs)
                local["series_resume_${seriesId}_timestamp"] = AndroidTypedEntry("long", now)
            }
        }
        saveLocal(context, email, local)
    }

    fun isEpisodeWatched(context: Context, email: String, fileId: String): Boolean {
        if (email.isBlank() || fileId.isBlank()) return false
        val local = loadLocalSync(context, email)
        val explicit = local["${fileId}_is_watched"]?.value
        if (explicit is Boolean) return explicit
        if (explicit is String) return explicit.toBoolean()

        val fraction = (local["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f
        return fraction >= 0.90f
    }

    fun getWatchedEpisodeIds(context: Context, email: String): Set<String> {
        val local = loadLocalSync(context, email)
        val watched = mutableSetOf<String>()
        for ((k, v) in local) {
            if (k.endsWith("_is_watched")) {
                if (v.value == true || v.value == "true") {
                    watched.add(k.removeSuffix("_is_watched"))
                }
            }
        }
        for ((k, v) in local) {
            if (k.endsWith("_fraction")) {
                val id = k.removeSuffix("_fraction")
                if (!local.containsKey("${id}_is_watched")) {
                    val frac = (v.value as? Number)?.toFloat() ?: 0f
                    if (frac >= 0.90f) {
                        watched.add(id)
                    }
                }
            }
        }
        return watched
    }

    /**
     * Marks or unmarks an episode as watched.
     *
     * When isWatched=false:
     * - Sets _is_watched=false, _fraction=0, _pos=0 in the local cache.
     * - Updates the timestamp so that subsequent remote merges recognise this as the
     *   authoritative unwatch event (timestamp-wins).
     * - If the series resume pointer points at this episode, resets its positionMs to 0.
     * - Persists to disk and immediately triggers a force-upload to Drive via
     *   DriveWatchHistorySync.pushHistoryAsync(context, force=true), bypassing all debounce
     *   and hash-equality guards in executeDirectUploadInternal.
     */
    fun setEpisodeWatched(
        context: Context,
        email: String,
        fileId: String,
        isWatched: Boolean,
        seriesId: String = "",
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        title: String = "",
        durationMs: Long = 0L
    ) {
        val local = loadLocalSync(context, email)
        val now = System.currentTimeMillis()
        val fraction = if (isWatched) 1.0f else 0.0f
        val pos = if (isWatched) durationMs else 0L

        synchronized(local) {
            local["${fileId}_is_watched"] = AndroidTypedEntry("boolean", isWatched)
            local["${fileId}_title"] = AndroidTypedEntry("string", title)
            local["${fileId}_pos"] = AndroidTypedEntry("long", pos)
            local["${fileId}_dur"] = AndroidTypedEntry("long", durationMs)
            local["${fileId}_fraction"] = AndroidTypedEntry("float", fraction)
            local["${fileId}_timestamp"] = AndroidTypedEntry("long", now)
            local["${fileId}_is_series"] = AndroidTypedEntry("boolean", seriesId.isNotBlank())

            if (seriesId.isNotBlank()) {
                local["${fileId}_series_id"] = AndroidTypedEntry("string", seriesId)
                local["${fileId}_season_num"] = AndroidTypedEntry("int", seasonNumber)
                local["${fileId}_episode_num"] = AndroidTypedEntry("int", episodeNumber)

                if (!isWatched) {
                    val currentResumeEpId = local["series_resume_${seriesId}_episode_id"]?.value as? String
                    if (currentResumeEpId == fileId) {
                        local["series_resume_${seriesId}_pos_ms"] = AndroidTypedEntry("long", 0L)
                        local["series_resume_${seriesId}_timestamp"] = AndroidTypedEntry("long", now)
                    }
                }
            }
        }
        saveLocal(context, email, local)
        // force=true bypasses the 15-second debounce AND the hash-equality guard in
        // executeDirectUploadInternal, ensuring the unwatch propagates to Drive immediately.
        DriveWatchHistorySync.pushHistoryAsync(context.applicationContext, force = true)
    }

    fun advanceToNextEpisode(
        context: Context,
        email: String,
        seriesId: String,
        nextEpId: String,
        nextSeasonNum: Int,
        nextEpNum: Int,
        nextTitle: String,
        nextDurationMs: Long
    ) {
        val local = loadLocalSync(context, email)
        val now = System.currentTimeMillis()

        synchronized(local) {
            local["series_resume_${seriesId}_episode_id"] = AndroidTypedEntry("string", nextEpId)
            local["series_resume_${seriesId}_season_num"] = AndroidTypedEntry("int", nextSeasonNum)
            local["series_resume_${seriesId}_episode_num"] = AndroidTypedEntry("int", nextEpNum)
            local["series_resume_${seriesId}_pos_ms"] = AndroidTypedEntry("long", 0L)
            local["series_resume_${seriesId}_dur_ms"] = AndroidTypedEntry("long", nextDurationMs)
            local["series_resume_${seriesId}_timestamp"] = AndroidTypedEntry("long", now)

            local["${nextEpId}_title"] = AndroidTypedEntry("string", nextTitle)
            local["${nextEpId}_pos"] = AndroidTypedEntry("long", 0L)
            local["${nextEpId}_dur"] = AndroidTypedEntry("long", nextDurationMs)
            local["${nextEpId}_fraction"] = AndroidTypedEntry("float", 0.0f)
            local["${nextEpId}_timestamp"] = AndroidTypedEntry("long", now)
            local["${nextEpId}_is_series"] = AndroidTypedEntry("boolean", true)
            local["${nextEpId}_series_id"] = AndroidTypedEntry("string", seriesId)
            local["${nextEpId}_season_num"] = AndroidTypedEntry("int", nextSeasonNum)
            local["${nextEpId}_episode_num"] = AndroidTypedEntry("int", nextEpNum)
        }
        saveLocal(context, email, local)
    }
}
