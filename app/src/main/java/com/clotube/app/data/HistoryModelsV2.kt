package com.clotube.app.data

import org.json.JSONArray
import org.json.JSONObject

data class HistoryItemEntityV2(
    val id: String,
    val title: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val timestamp: Long = 0L,
    val isWatched: Boolean = false,
    val seriesId: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

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
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): HistoryItemEntityV2 {
            return HistoryItemEntityV2(
                id = obj.getString("id"),
                title = obj.optString("t", ""),
                positionMs = obj.optLong("p", 0L),
                durationMs = obj.optLong("d", 0L),
                timestamp = obj.optLong("ts", 0L),
                isWatched = obj.optBoolean("w", false),
                seriesId = if (obj.has("sid")) obj.optString("sid").ifBlank { null } else null,
                seasonNumber = if (obj.has("s")) obj.optInt("s") else null,
                episodeNumber = if (obj.has("e")) obj.optInt("e") else null
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
    val timestamp: Long = 0L
) {
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
                timestamp = obj.optLong("ts", 0L)
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

    fun prunedToMaxBytes(maxBytes: Int = 28 * 1024): WatchHistoryPayloadV2 {
        val distinctWatched = watchedIds.filter { it.isNotBlank() }.distinct()
        var currentActive = active
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .sortedByDescending { it.timestamp }
            .take(60)
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

        // First prune oldest completed active items whose watched status is preserved in watchedIds
        val resumeEpisodeIds = currentResume.values.map { it.episodeId }.toSet()
        val removableCompleted = currentActive
            .filter { item ->
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

        // Next trim oldest active scrub entries down to budget
        while (currentActive.size > 1 && candidate.toJsonString().toByteArray(Charsets.UTF_8).size > maxBytes) {
            currentActive = currentActive.dropLast(1)
            candidate = build()
        }

        // Finally trim oldest resume pointers only if still above maxBytes
        if (candidate.toJsonString().toByteArray(Charsets.UTF_8).size > maxBytes && currentResume.size > 1) {
            val resumeList = currentResume.entries.sortedByDescending { it.value.timestamp }.toMutableList()
            while (resumeList.size > 1 && candidate.toJsonString().toByteArray(Charsets.UTF_8).size > maxBytes) {
                resumeList.removeAt(resumeList.lastIndex)
                currentResume = resumeList.associate { it.key to it.value }
                candidate = build()
            }
        }

        return candidate
    }

    companion object {
        fun fromJsonString(json: String): WatchHistoryPayloadV2 {
            val root = JSONObject(json)
            val v = root.optInt("v", 2)
            val updatedAt = root.optLong("updatedAt", System.currentTimeMillis())

            val activeList = mutableListOf<HistoryItemEntityV2>()
            val activeArr = root.optJSONArray("active")
            if (activeArr != null) {
                for (i in 0 until activeArr.length()) {
                    activeList.add(HistoryItemEntityV2.fromJsonObject(activeArr.getJSONObject(i)))
                }
            }

            val resumeMap = mutableMapOf<String, SeriesResumeEntityV2>()
            val resumeObj = root.optJSONObject("resume")
            if (resumeObj != null) {
                val keys = resumeObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    resumeMap[k] = SeriesResumeEntityV2.fromJsonObject(resumeObj.getJSONObject(k))
                }
            }

            val watchedList = mutableListOf<String>()
            val watchedArr = root.optJSONArray("watchedIds")
            if (watchedArr != null) {
                for (i in 0 until watchedArr.length()) {
                    watchedList.add(watchedArr.getString(i))
                }
            }

            return WatchHistoryPayloadV2(v, updatedAt, activeList, resumeMap, watchedList)
        }
    }
}

