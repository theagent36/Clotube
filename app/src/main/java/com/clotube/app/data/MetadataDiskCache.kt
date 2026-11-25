package com.clotube.app.data

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Snapshot record for resolved movie, TV series, or episode metadata.
 */
@Keep
@Immutable
data class CachedMetadataRecord(
    val cacheKey: String,
    val title: String,
    val overview: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val stillUrl: String = "",
    val year: String = "",
    val rating: String = "",
    val genre: String = "",
    val language: String = "",
    val originalLanguage: String = "",
    val tvMazeId: Long = -1L,
    val cachedAt: Long = System.currentTimeMillis()
) : Serializable {

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("cacheKey", cacheKey)
        put("title", title)
        put("overview", overview)
        put("posterUrl", posterUrl)
        put("backdropUrl", backdropUrl)
        put("stillUrl", stillUrl)
        put("year", year)
        put("rating", rating)
        put("genre", genre)
        val resolvedLang = language.ifBlank { originalLanguage }
        put("language", resolvedLang)
        put("originalLanguage", resolvedLang)
        put("tvMazeId", tvMazeId)
        put("cachedAt", cachedAt)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): CachedMetadataRecord? {
            val key = json.optString("cacheKey", "").trim()
            val title = json.optString("title", "").trim()
            if (key.isBlank() || title.isBlank()) return null
            val parsedLang = json.optString("language", json.optString("originalLanguage", "")).trim()
            return CachedMetadataRecord(
                cacheKey = key,
                title = title,
                overview = json.optString("overview", ""),
                posterUrl = json.optString("posterUrl", ""),
                backdropUrl = json.optString("backdropUrl", ""),
                stillUrl = json.optString("stillUrl", ""),
                year = json.optString("year", ""),
                rating = json.optString("rating", ""),
                genre = json.optString("genre", ""),
                language = parsedLang,
                originalLanguage = parsedLang,
                tvMazeId = json.optLong("tvMazeId", -1L),
                cachedAt = json.optLong("cachedAt", System.currentTimeMillis())
            )
        }
    }
}

/**
 * Thread-safe persistent disk cache for resolved media artwork and metadata.
 * Serializes snapshots to `context.cacheDir/clotube_metadata_cache.json` using atomic
 * file writes and a 7-day TTL so cold boots never re-query external APIs.
 */
object MetadataDiskCache {

    private const val TAG = "MetadataDiskCache"
    private const val FILE_NAME = "clotube_metadata_cache.json"
    private const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000L // 7 days

    private val mutex = Mutex()
    private val memoryMap = ConcurrentHashMap<String, CachedMetadataRecord>()
    private val diskScope = CoroutineScope(Dispatchers.IO)
    private var pendingFlushJob: Job? = null

    @Volatile
    private var isLoaded = false

    private fun getCacheFile(context: Context): File = File(context.cacheDir, FILE_NAME)

    /**
     * Loads persisted records from disk into memory.
     */
    suspend fun ensureLoaded(context: Context) = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext

        mutex.withLock {
            if (isLoaded) return@withLock
            val cacheFiles = listOf(getCacheFile(context), File(context.cacheDir, "movie_metadata_cache.json"))
            for (file in cacheFiles) {
                if (file.exists() && file.length() > 0) {
                    try {
                        val raw = file.readText(Charsets.UTF_8)
                        val json = JSONObject(raw)
                        val entriesObj = json.optJSONObject("entries")
                        if (entriesObj != null) {
                            val now = System.currentTimeMillis()
                            val keys = entriesObj.keys()
                            var count = 0
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val itemObj = entriesObj.optJSONObject(key) ?: continue
                                val record = CachedMetadataRecord.fromJsonObject(itemObj) ?: continue
                                if (record.cacheKey.startsWith("movie") && record.posterUrl.isBlank()) continue
                                // Exclude expired records
                                if (now - record.cachedAt < TTL_MILLIS) {
                                    memoryMap[record.cacheKey] = record
                                    count++
                                }
                            }
                            Log.d(TAG, "Loaded $count valid metadata records from ${file.name}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed reading metadata cache from ${file.name}: ${e.message}")
                    }
                }
            }
            isLoaded = true
        }
    }

    /**
     * Retrieves a cached metadata record by key if present and not expired.
     */
    fun get(key: String): CachedMetadataRecord? {
        val cleanKey = key.trim().lowercase()
        val record = memoryMap[cleanKey] ?: return null
        if (record.cacheKey.startsWith("movie") && record.posterUrl.isBlank()) {
            memoryMap.remove(cleanKey)
            return null
        }
        if (System.currentTimeMillis() - record.cachedAt >= TTL_MILLIS) {
            memoryMap.remove(cleanKey)
            return null
        }
        return record
    }

    /**
     * Stores a record in memory and schedules a debounced write to disk.
     */
    suspend fun put(context: Context, record: CachedMetadataRecord) {
        // Never persist a movie metadata record with an empty posterUrl as a permanent hit
        if (record.cacheKey.startsWith("movie") && record.posterUrl.isBlank()) {
            return
        }
        val cleanKey = record.cacheKey.trim().lowercase()
        memoryMap[cleanKey] = record.copy(cacheKey = cleanKey)

        scheduleDebouncedFlush(context)
    }

    private fun scheduleDebouncedFlush(context: Context) {
        synchronized(this) {
            pendingFlushJob?.cancel()
            pendingFlushJob = diskScope.launch {
                delay(1500L) // 1.5s debounce to batch scan flushes
                flushToDisk(context)
            }
        }
    }

    /**
     * Immediately flushes in-memory cache to disk via atomic temp file write.
     */
    suspend fun flushNow(context: Context) = withContext(Dispatchers.IO) {
        synchronized(this@MetadataDiskCache) {
            pendingFlushJob?.cancel()
            pendingFlushJob = null
        }
        flushToDisk(context)
    }

    private suspend fun flushToDisk(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val root = JSONObject()
                root.put("version", 1)
                val entriesObj = JSONObject()
                for ((k, v) in memoryMap) {
                    entriesObj.put(k, v.toJsonObject())
                }
                root.put("entries", entriesObj)

                val targetFile = getCacheFile(context)
                val tempFile = File(context.cacheDir, "$FILE_NAME.tmp")
                tempFile.writeText(root.toString(), Charsets.UTF_8)
                if (tempFile.exists()) {
                    if (targetFile.exists()) targetFile.delete()
                    tempFile.renameTo(targetFile)
                }
                Log.d(TAG, "Flushed ${memoryMap.size} metadata records to disk (${targetFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Error flushing metadata cache: ${e.message}", e)
            }
        }
    }

    /**
     * Wipes all cached records from memory and deletes the disk cache file.
     */
    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            memoryMap.clear()
            val file = getCacheFile(context)
            if (file.exists()) file.delete()
            val tmp = File(context.cacheDir, "$FILE_NAME.tmp")
            if (tmp.exists()) tmp.delete()
            Log.d(TAG, "Cleared all metadata cache entries")
        }
    }

    /**
     * Returns total disk usage in bytes for the metadata cache file.
     */
    fun getCacheSizeBytes(context: Context): Long {
        val file = getCacheFile(context)
        return if (file.exists()) file.length() else 0L
    }
}
