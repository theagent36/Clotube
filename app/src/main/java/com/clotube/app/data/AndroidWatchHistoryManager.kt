package com.clotube.app.data

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class AndroidTypedEntry(val type: String, val value: Any)

object AndroidWatchHistoryManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val memoryCache = ConcurrentHashMap<String, MutableMap<String, AndroidTypedEntry>>()

    private fun getFile(context: Context, email: String): File {
        val dir = File(context.filesDir, "profiles/${email.replace("[^a-zA-Z0-9._-]".toRegex(), "_")}")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "clotube_watch_history.json")
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
