package com.clotube.app.data

import org.json.JSONArray
import org.json.JSONObject

const val MAX_ACTIVE_WINDOW = 50
const val MAX_PROTECTED_IN_PROGRESS = 20
const val MAX_ACTIVE_PAYLOAD_BYTES = 28 * 1024

data class HistoryItemEntityV2(
    val id: String,
    val title: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val timestamp: Long = 0L,
    val isWatched: Boolean = false,
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val seriesTitle: String? = null,
    val thumbnailUrl: String? = null
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val fraction: Float get() = progressFraction
    val timestampMs: Long get() = timestamp
    val isSeries: Boolean get() = !seriesId.isNullOrBlank() || seasonNumber != null || episodeNumber != null

    val isInProgressForProtection: Boolean
        get() = !isWatched && positionMs >= 3000L && progressFraction < 0.90f

    val isResumeEligibleFromArchive: Boolean
        get() = !isWatched && positionMs > 1500L && progressFraction < 0.90f

    val isContinueWatchingEligible: Boolean
        get() = !isWatched && positionMs >= 3000L && progressFraction in 0.01f..0.90f

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("t", title)
        put("p", positionMs)
        put("d", durationMs)
        put("ts", timestamp)
        put("w", isWatched)
        if (!seriesId.isNullOrBlank()) put("sid", seriesId)
        if (seasonNumber != null) put("s", seasonNumber)
        if (episodeNumber != null) put("e", episodeNumber)
        if (!seriesTitle.isNullOrBlank()) put("st", seriesTitle)
        if (!thumbnailUrl.isNullOrBlank()) put("thumb", thumbnailUrl)
    }

    companion object {
        private fun optIntMulti(obj: JSONObject, vararg keys: String): Int? {
            for (k in keys) {
                if (obj.has(k) && !obj.isNull(k)) {
                    return obj.optInt(k)
                }
            }
            return null
        }

        private fun optStringMulti(obj: JSONObject, vararg keys: String): String {
            for (k in keys) {
                if (obj.has(k) && !obj.isNull(k)) {
                    val v = obj.optString(k, "")
                    if (v.isNotBlank()) return v
                }
            }
            return ""
        }

        private fun optLongMulti(obj: JSONObject, vararg keys: String): Long {
            for (k in keys) {
                if (obj.has(k) && !obj.isNull(k)) {
                    return obj.optLong(k, 0L)
                }
            }
            return 0L
        }

        fun fromJsonObject(obj: JSONObject): HistoryItemEntityV2 {
            val isWatchedVal = when {
                obj.has("w") && !obj.isNull("w") -> obj.optBoolean("w", false)
                obj.has("isWatched") && !obj.isNull("isWatched") -> obj.optBoolean("isWatched", false)
                else -> false
            }
            return HistoryItemEntityV2(
                id = obj.getString("id"),
                title = optStringMulti(obj, "t", "title"),
                positionMs = optLongMulti(obj, "p", "positionMs").coerceAtLeast(0L),
                durationMs = optLongMulti(obj, "d", "durationMs").coerceAtLeast(0L),
                timestamp = WatchHistoryPayloadV2.normalizeTimestampMs(optLongMulti(obj, "ts", "timestamp")),
                isWatched = isWatchedVal,
                seriesId = optStringMulti(obj, "sid", "seriesId").ifBlank { null },
                seasonNumber = optIntMulti(obj, "s", "sn", "seasonNumber"),
                episodeNumber = optIntMulti(obj, "e", "en", "episodeNumber"),
                seriesTitle = optStringMulti(obj, "st", "seriesTitle").ifBlank { null },
                thumbnailUrl = optStringMulti(obj, "thumb", "thumbnailUrl").ifBlank { null }
            )
        }
    }
}

data class ActiveWindowPartitionResult(
    val activeItems: List<HistoryItemEntityV2>,
    val overflowItems: List<HistoryItemEntityV2>
)

data class ArchivePayloadV2(
    val version: Int = 2,
    val updatedAt: Long = System.currentTimeMillis(),
    val watchedIds: List<String> = emptyList(),
    val items: List<HistoryItemEntityV2> = emptyList()
) {
    fun toJsonString(): String {
        val root = JSONObject()
        root.put("v", version)
        root.put("updatedAt", updatedAt)
        val watchedArr = JSONArray()
        watchedIds.filter { it.isNotBlank() }.distinct().sorted().forEach { watchedArr.put(it) }
        root.put("watchedIds", watchedArr)
        val itemsArr = JSONArray()
        items.distinctBy { it.id }.sortedByDescending { it.timestamp }.forEach { itemsArr.put(it.toJsonObject()) }
        root.put("items", itemsArr)
        return root.toString()
    }

    companion object {
        fun fromJsonString(json: String): ArchivePayloadV2 {
            if (json.isBlank()) return ArchivePayloadV2(updatedAt = 0L)
            val root = JSONObject(json)
            val v = root.optInt("v", 2)
            val rawUpdatedAt = if (root.has("updatedAt") && !root.isNull("updatedAt")) {
                WatchHistoryPayloadV2.normalizeTimestampMs(root.optLong("updatedAt", 0L))
            } else {
                0L
            }
            val watchedSet = linkedSetOf<String>()
            val watchedArr = root.optJSONArray("watchedIds")
            if (watchedArr != null) {
                for (i in 0 until watchedArr.length()) {
                    val id = watchedArr.optString(i, "").trim()
                    if (id.isNotBlank()) watchedSet.add(id)
                }
            }
            val itemsMap = linkedMapOf<String, HistoryItemEntityV2>()
            val itemsArr = root.optJSONArray("items") ?: root.optJSONArray("archive")
            if (itemsArr != null) {
                for (i in 0 until itemsArr.length()) {
                    val obj = itemsArr.optJSONObject(i) ?: continue
                    if (obj.optString("id", "").isNotBlank()) {
                        val entity = HistoryItemEntityV2.fromJsonObject(obj)
                        val existing = itemsMap[entity.id]
                        if (existing == null || entity.timestamp >= existing.timestamp) {
                            itemsMap[entity.id] = entity
                        }
                        if (entity.isWatched || entity.progressFraction >= 0.90f) {
                            watchedSet.add(entity.id)
                        } else {
                            // Keep genuinely uncompleted archive items eligible for promotion
                            watchedSet.remove(entity.id)
                        }
                    }
                }
            }
            val maxItemTs = itemsMap.values.maxOfOrNull { it.timestamp } ?: 0L
            return ArchivePayloadV2(
                version = v,
                updatedAt = maxOf(rawUpdatedAt, maxItemTs),
                watchedIds = watchedSet.toList(),
                items = itemsMap.values.sortedByDescending { it.timestamp }
            )
        }
    }
}

data class SeriesResumeEntityV2(
    val episodeId: String,
    val seasonNumber: Int = 1,
    val episodeNumber: Int = 1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val timestamp: Long = 0L,
    val seriesTitle: String? = null,
    val episodeTitle: String? = null
) {
    val fraction: Float get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val timestampMs: Long get() = timestamp

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", episodeId)
        put("s", seasonNumber)
        put("e", episodeNumber)
        put("p", positionMs)
        put("d", durationMs)
        put("ts", timestamp)
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): SeriesResumeEntityV2 {
            return SeriesResumeEntityV2(
                episodeId = obj.getString("id"),
                seasonNumber = obj.optInt("s", 1),
                episodeNumber = obj.optInt("e", 1),
                positionMs = obj.optLong("p", 0L),
                durationMs = obj.optLong("d", 0L),
                timestamp = WatchHistoryPayloadV2.normalizeTimestampMs(obj.optLong("ts", 0L))
            )
        }
    }
}

data class WatchHistoryPayloadV2(
    val version: Int = 2,
    val updatedAt: Long = System.currentTimeMillis(),
    val active: List<HistoryItemEntityV2> = emptyList(),
    val resume: Map<String, SeriesResumeEntityV2> = emptyMap(),
    val watchedIds: List<String> = emptyList()
) {
    val seriesResume: Map<String, SeriesResumeEntityV2> get() = resume
    fun toJsonString(): String {
        val root = JSONObject()
        root.put("v", version)
        root.put("updatedAt", updatedAt)

        val activeArray = JSONArray()
        active.forEach { activeArray.put(it.toJsonObject()) }
        root.put("active", activeArray)

        val resumeObj = JSONObject()
        resume.forEach { (sid, entity) -> resumeObj.put(sid, entity.toJsonObject()) }
        root.put("resume", resumeObj)

        val watchedArray = JSONArray()
        watchedIds.forEach { watchedArray.put(it) }
        root.put("watchedIds", watchedArray)

        return root.toString()
    }

    fun prunedToMaxBytes(maxBytes: Int = MAX_ACTIVE_PAYLOAD_BYTES): WatchHistoryPayloadV2 {
        val partition = partitionActiveWindow(active)
        var currentActive = partition.activeItems
        val pinnedIds = currentActive
            .filter { it.isInProgressForProtection }
            .sortedByDescending { it.timestamp }
            .take(MAX_PROTECTED_IN_PROGRESS)
            .map { it.id }
            .toSet()

        val distinctWatched = (
            watchedIds +
                currentActive.filter { it.isWatched || it.progressFraction >= 0.90f }.map { it.id }
            ).filter { it.isNotBlank() }.distinct()

        var currentResume = resume.entries
            .filter { it.key.isNotBlank() && it.value.episodeId.isNotBlank() }
            .sortedByDescending { it.value.timestamp }
            .associate { it.key to it.value }

        fun build() = copy(
            active = currentActive,
            resume = currentResume,
            watchedIds = distinctWatched
        )

        var candidate = build()
        if (candidate.toJsonString().toByteArray(Charsets.UTF_8).size <= maxBytes) {
            return candidate
        }

        // First prune oldest completed active items (non-pinned) whose watched status is preserved in watchedIds
        val resumeEpisodeIds = currentResume.values.map { it.episodeId }.toSet()
        val removableCompleted = currentActive
            .filter { item ->
                item.id !in pinnedIds &&
                    item.isWatched &&
                    (item.positionMs <= 0L || item.progressFraction >= 0.90f) &&
                    item.id !in resumeEpisodeIds
            }
            .sortedBy { it.timestamp }

        if (removableCompleted.isNotEmpty()) {
            val activeMutable = currentActive.toMutableList()
            for (oldItem in removableCompleted) {
                activeMutable.removeAll { it.id == oldItem.id }
                currentActive = activeMutable.toList()
                candidate = build()
                if (candidate.toJsonString().toByteArray(Charsets.UTF_8).size <= maxBytes) {
                    return candidate
                }
            }
        }

        // Next trim oldest non-pinned active items down to budget while protecting pinned in-progress items
        val nonPinnedIdsOrder = currentActive
            .filter { it.id !in pinnedIds }
            .sortedBy { it.timestamp }
            .map { it.id }
        if (nonPinnedIdsOrder.isNotEmpty()) {
            val activeMutable = currentActive.toMutableList()
            for (oldId in nonPinnedIdsOrder) {
                if (activeMutable.size <= 1 || candidate.toJsonString().toByteArray(Charsets.UTF_8).size <= maxBytes) {
                    break
                }
                activeMutable.removeAll { it.id == oldId }
                currentActive = activeMutable.toList()
                candidate = build()
            }
        }

        // Trim oldest resume pointers if still above maxBytes (pinned in-progress items stay protected longer)
        if (candidate.toJsonString().toByteArray(Charsets.UTF_8).size > maxBytes && currentResume.size > 1) {
            val resumeList = currentResume.entries.sortedByDescending { it.value.timestamp }.toMutableList()
            while (resumeList.size > 1 && candidate.toJsonString().toByteArray(Charsets.UTF_8).size > maxBytes) {
                resumeList.removeAt(resumeList.lastIndex)
                currentResume = resumeList.associate { it.key to it.value }
                candidate = build()
            }
        }

        // Last resort only if even pinned items alone exceed maxBytes
        while (currentActive.size > 1 && candidate.toJsonString().toByteArray(Charsets.UTF_8).size > maxBytes) {
            val oldestPinnedIdx = currentActive.indices.minByOrNull { currentActive[it].timestamp } ?: break
            val mutable = currentActive.toMutableList()
            mutable.removeAt(oldestPinnedIdx)
            currentActive = mutable.toList()
            candidate = build()
        }

        return candidate
    }

    companion object {
        fun partitionActiveWindow(allItems: List<HistoryItemEntityV2>): ActiveWindowPartitionResult {
            val dedupedMap = linkedMapOf<String, HistoryItemEntityV2>()
            for (item in allItems) {
                if (item.id.isBlank()) continue
                val existing = dedupedMap[item.id]
                if (existing == null || item.timestamp >= existing.timestamp) {
                    dedupedMap[item.id] = item
                }
            }
            val deduped = dedupedMap.values.toList()

            val pinnedInProgress = deduped
                .filter { it.isInProgressForProtection }
                .sortedByDescending { it.timestamp }
                .take(MAX_PROTECTED_IN_PROGRESS)
            val pinnedIds = pinnedInProgress.map { it.id }.toHashSet()

            val remainingSlots = (MAX_ACTIVE_WINDOW - pinnedInProgress.size).coerceAtLeast(0)
            val filledNonPinned = deduped
                .filter { it.id !in pinnedIds }
                .sortedByDescending { it.timestamp }
                .take(remainingSlots)

            val activeItems = (pinnedInProgress + filledNonPinned)
                .sortedByDescending { it.timestamp }
            val activeIds = activeItems.map { it.id }.toHashSet()
            val overflowItems = deduped
                .filter { it.id !in activeIds }
                .sortedByDescending { it.timestamp }

            return ActiveWindowPartitionResult(
                activeItems = activeItems,
                overflowItems = overflowItems
            )
        }
        /**
         * Normalizes Unix timestamps to epoch milliseconds.
         * If a remote client (e.g., Desktop/PC) wrote Unix seconds (10-digit epoch),
         * converts it to milliseconds so it compares accurately against Android's System.currentTimeMillis().
         */
        fun normalizeTimestampMs(value: Long): Long {
            return if (value in 1L until 10_000_000_000L) {
                value * 1000L
            } else {
                value
            }
        }

        fun fromJsonString(json: String): WatchHistoryPayloadV2 {
            val root = JSONObject(json)
            val v = root.optInt("v", 2)
            val rawUpdatedAt = if (root.has("updatedAt") && !root.isNull("updatedAt")) {
                normalizeTimestampMs(root.optLong("updatedAt", 0L))
            } else {
                0L
            }

            val activeList = mutableListOf<HistoryItemEntityV2>()
            val activeArr = root.optJSONArray("active")
            if (activeArr != null) {
                for (i in 0 until activeArr.length()) {
                    val obj = activeArr.optJSONObject(i) ?: continue
                    if (obj.optString("id", "").isNotBlank()) {
                        activeList.add(HistoryItemEntityV2.fromJsonObject(obj))
                    }
                }
            }

            val resumeMap = mutableMapOf<String, SeriesResumeEntityV2>()
            val resumeObj = root.optJSONObject("resume")
            if (resumeObj != null) {
                val keys = resumeObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val obj = resumeObj.optJSONObject(k) ?: continue
                    if (obj.optString("id", "").isNotBlank()) {
                        resumeMap[k] = SeriesResumeEntityV2.fromJsonObject(obj)
                    }
                }
            }

            val watchedList = mutableListOf<String>()
            val watchedArr = root.optJSONArray("watchedIds")
            if (watchedArr != null) {
                for (i in 0 until watchedArr.length()) {
                    val wid = watchedArr.optString(i, "")
                    if (wid.isNotBlank()) {
                        watchedList.add(wid)
                    }
                }
            }

            val updatedAt = maxOf(
                rawUpdatedAt,
                activeList.maxOfOrNull { it.timestamp } ?: 0L,
                resumeMap.values.maxOfOrNull { it.timestamp } ?: 0L
            )

            return WatchHistoryPayloadV2(v, updatedAt, activeList, resumeMap, watchedList)
        }

        fun fromTypedEntries(
            entries: Map<String, AndroidTypedEntry>,
            updatedAt: Long = System.currentTimeMillis()
        ): WatchHistoryPayloadV2 {
            val itemIds = linkedSetOf<String>()
            val seriesResumeIds = linkedSetOf<String>()

            for (key in entries.keys) {
                if (key == "watch_history_list_json") continue
                if (key.startsWith("series_resume_")) {
                    val sid = key.removePrefix("series_resume_").substringBefore("_")
                    if (sid.isNotBlank()) seriesResumeIds.add(sid)
                } else {
                    val id = key.substringBeforeLast("_")
                    if (id.isNotBlank() && !id.startsWith("global_") && !id.startsWith("snapshot_") && !id.startsWith("last_global_")) {
                        itemIds.add(id)
                    }
                }
            }

            val activeItems = mutableListOf<HistoryItemEntityV2>()
            val watchedIds = linkedSetOf<String>()

            for (id in itemIds) {
                val title = (entries["${id}_title"]?.value as? String) ?: ""
                val pos = (entries["${id}_pos"]?.value as? Number)?.toLong() ?: 0L
                val dur = (entries["${id}_dur"]?.value as? Number)?.toLong() ?: 0L
                val frac = (entries["${id}_fraction"]?.value as? Number)?.toFloat()
                    ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
                val ts = (entries["${id}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                val explicitWatched = (entries["${id}_is_watched"]?.value as? Boolean)
                val isWatched = explicitWatched ?: (frac >= 0.90f)

                val seriesId = (entries["${id}_series_id"]?.value as? String)?.ifBlank { null }
                val seriesTitle = (entries["${id}_series_title"]?.value as? String)?.ifBlank { null }
                val seasonNum = (entries["${id}_season_num"]?.value as? Number)?.toInt()
                val episodeNum = (entries["${id}_episode_num"]?.value as? Number)?.toInt()
                val thumb = (entries["${id}_thumbnail_url"]?.value as? String)?.ifBlank { null }
                    ?: (entries["${id}_thumb"]?.value as? String)?.ifBlank { null }

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
                            episodeNumber = episodeNum,
                            seriesTitle = seriesTitle,
                            thumbnailUrl = thumb
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
                watchedIds = watchedIds.toList()
            )
        }
    }
}

