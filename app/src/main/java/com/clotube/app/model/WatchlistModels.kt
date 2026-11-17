package com.clotube.app.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable

/**
 * Lifecycle and intent states for items saved to Watchlist.
 * Matches Clotube Desktop clotube_watchlist.json specification.
 */
@Keep
enum class WatchlistStatus(val value: String, val displayName: String) {
    UNWATCHED("UNWATCHED", "Unwatched"),
    WATCH_LATER("WATCH_LATER", "Watch Later"),
    WATCHED("WATCHED", "Watched"),
    REWATCH("REWATCH", "Rewatch");

    companion object {
        fun fromRaw(raw: String?): WatchlistStatus {
            if (raw.isNullOrBlank()) return UNWATCHED
            val normalized = raw.trim().lowercase().replace(" ", "_").replace("-", "_")
            return when (normalized) {
                "watch_later", "watchlater" -> WATCH_LATER
                "watched" -> WATCHED
                "rewatch" -> REWATCH
                "unwatched" -> UNWATCHED
                else -> values().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: UNWATCHED
            }
        }
    }
}

/**
 * Distinguishes standalone movies from episodic TV series.
 */
@Keep
enum class WatchlistMediaType(val value: String) {
    MOVIE("movie"),
    SERIES("series");

    companion object {
        fun fromRaw(raw: String?): WatchlistMediaType {
            if (raw.isNullOrBlank()) return MOVIE
            return when (raw.trim().lowercase()) {
                "series", "tv", "show", "tv_series" -> SERIES
                else -> MOVIE
            }
        }
    }
}

/**
 * Cross-platform data model for an individual media item saved to the Watchlist.
 * Supports tombstone deletions (isDeleted = true) for deterministic two-way sync.
 */
@Keep
@Immutable
data class WatchlistEntry(
    val id: String,
    val normalizedTitle: String,
    val title: String,
    val mediaType: WatchlistMediaType,
    val status: WatchlistStatus,
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val year: String = "",
    val genre: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
) : Serializable {

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("normalizedTitle", normalizedTitle)
        put("normalized_title", normalizedTitle)
        put("title", title)
        put("mediaType", mediaType.value)
        put("type", mediaType.value)
        put("status", status.name)
        put("posterUrl", posterUrl)
        put("poster_url", posterUrl)
        put("backdropUrl", backdropUrl)
        put("backdrop_url", backdropUrl)
        put("year", year)
        put("genre", genre)
        put("addedAt", addedAt)
        put("added_at", addedAt)
        put("updatedAt", updatedAt)
        put("updated_at", updatedAt)
        put("isDeleted", isDeleted)
        put("is_deleted", isDeleted)
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): WatchlistEntry? {
            val id = obj.optString("id", "").trim()
            if (id.isEmpty()) return null

            val rawTitle = obj.optString("title", "").trim()
            val rawNormTitle = obj.optString("normalizedTitle").ifBlank {
                obj.optString("normalized_title", "")
            }.trim()

            val effectiveTitle = rawTitle.ifBlank { rawNormTitle }.ifBlank { id }
            val effectiveNormTitle = rawNormTitle.ifBlank {
                effectiveTitle.lowercase()
            }

            val rawMediaType = obj.optString("mediaType").ifBlank {
                obj.optString("type", "movie")
            }
            val mediaType = WatchlistMediaType.fromRaw(rawMediaType)

            val rawStatus = obj.optString("status", "UNWATCHED")
            val status = WatchlistStatus.fromRaw(rawStatus)

            val posterUrl = obj.optString("posterUrl").ifBlank {
                obj.optString("poster_url", "")
            }.trim()

            val backdropUrl = obj.optString("backdropUrl").ifBlank {
                obj.optString("backdrop_url", "")
            }.trim()

            val year = obj.optString("year", "").trim()
            val genre = obj.optString("genre", "").trim()

            val rawAddedAt = when {
                obj.has("addedAt") -> obj.optLong("addedAt", System.currentTimeMillis())
                obj.has("added_at") -> obj.optLong("added_at", System.currentTimeMillis())
                else -> System.currentTimeMillis()
            }
            val addedAt = WatchlistPayload.normalizeTimestampMs(rawAddedAt)

            val rawUpdatedAt = when {
                obj.has("updatedAt") -> obj.optLong("updatedAt", System.currentTimeMillis())
                obj.has("updated_at") -> obj.optLong("updated_at", System.currentTimeMillis())
                else -> System.currentTimeMillis()
            }
            val updatedAt = WatchlistPayload.normalizeTimestampMs(rawUpdatedAt)

            val isDeleted = when {
                obj.has("isDeleted") -> obj.optBoolean("isDeleted", false)
                obj.has("is_deleted") -> obj.optBoolean("is_deleted", false)
                else -> false
            }

            return WatchlistEntry(
                id = id,
                normalizedTitle = effectiveNormTitle,
                title = effectiveTitle,
                mediaType = mediaType,
                status = status,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
                year = year,
                genre = genre,
                addedAt = addedAt,
                updatedAt = updatedAt,
                isDeleted = isDeleted
            )
        }
    }
}

/**
 * Root envelope for clotube_watchlist.json.
 */
@Keep
@Immutable
data class WatchlistPayload(
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val items: List<WatchlistEntry> = emptyList()
) : Serializable {

    fun toJsonString(): String {
        val root = JSONObject()
        root.put("version", version)
        root.put("updatedAt", updatedAt)
        val itemsArray = JSONArray()
        items.sortedWith(compareBy<WatchlistEntry> { it.normalizedTitle }.thenBy { it.id })
            .forEach { itemsArray.put(it.toJsonObject()) }
        root.put("items", itemsArray)
        return root.toString(2)
    }

    /**
     * Computes a deterministic hash strictly over the sorted [items] JSON array,
     * excluding the volatile root [updatedAt] timestamp so identical payloads skip redundant uploads
     * while any item status/tombstone/ID mutation produces a distinct hash.
     */
    fun computeItemsHash(): String {
        val itemsArray = JSONArray()
        items.sortedWith(compareBy<WatchlistEntry> { it.normalizedTitle }.thenBy { it.id })
            .forEach { itemsArray.put(it.toJsonObject()) }
        return itemsArray.toString().hashCode().toString()
    }

    companion object {
        fun normalizeTimestampMs(value: Long): Long {
            return if (value in 1_000_000_000L..9_999_999_999L) {
                value * 1000L
            } else {
                value
            }
        }

        fun fromJsonString(raw: String): WatchlistPayload {
            if (raw.isBlank()) return WatchlistPayload()
            return try {
                val trimmed = raw.trim()
                if (trimmed.startsWith("[")) {
                    // Direct array of entries
                    val array = JSONArray(trimmed)
                    val parsedItems = mutableListOf<WatchlistEntry>()
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        WatchlistEntry.fromJsonObject(obj)?.let { parsedItems.add(it) }
                    }
                    WatchlistPayload(
                        version = 1,
                        updatedAt = System.currentTimeMillis(),
                        items = parsedItems
                    )
                } else {
                    val root = JSONObject(trimmed)
                    val version = root.optInt("version", 1)
                    val rawUpdatedAt = when {
                        root.has("updatedAt") -> root.optLong("updatedAt", System.currentTimeMillis())
                        root.has("updated_at") -> root.optLong("updated_at", System.currentTimeMillis())
                        else -> System.currentTimeMillis()
                    }
                    val updatedAt = normalizeTimestampMs(rawUpdatedAt)

                    val parsedItems = mutableListOf<WatchlistEntry>()
                    val itemsArray = root.optJSONArray("items")
                        ?: root.optJSONArray("entries")

                    if (itemsArray != null) {
                        for (i in 0 until itemsArray.length()) {
                            val obj = itemsArray.optJSONObject(i) ?: continue
                            WatchlistEntry.fromJsonObject(obj)?.let { parsedItems.add(it) }
                        }
                    } else {
                        val itemsObj = root.optJSONObject("items")
                            ?: root.optJSONObject("entries")
                        if (itemsObj != null) {
                            val keys = itemsObj.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val obj = itemsObj.optJSONObject(key) ?: continue
                                WatchlistEntry.fromJsonObject(obj)?.let { parsedItems.add(it) }
                            }
                        }
                    }

                    WatchlistPayload(
                        version = version,
                        updatedAt = updatedAt,
                        items = parsedItems
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("WatchlistModels", "Failed to parse WatchlistPayload: ${e.message}", e)
                WatchlistPayload()
            }
        }
    }
}
