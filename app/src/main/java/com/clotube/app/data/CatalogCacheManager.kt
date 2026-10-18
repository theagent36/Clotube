package com.clotube.app.data

import android.content.Context
import com.clotube.app.model.TvSeries
import com.clotube.app.model.VideoItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Serialized data envelope for cached media library.
 * Persisted on disk to enable instant 0ms cold-start rendering.
 */
data class CachedCatalog(
    val movies: List<VideoItem> = emptyList(),
    val series: List<TvSeries> = emptyList(),
    val lastSyncTimestamp: Long = 0L
)

/**
 * Manages local disk caching of the Google Drive catalog using internal app storage.
 * Implements atomic writes to prevent read corruption during unexpected process termination.
 */
class CatalogCacheManager(private val context: Context) {

    private val gson = Gson()
    private val cacheFile: File
        get() = File(context.filesDir, "cached_catalog.json")

    /**
     * Reads the cached catalog from internal storage.
     * Returns an empty [CachedCatalog] if the cache file does not exist or fails to parse.
     */
    suspend fun getCachedCatalog(): CachedCatalog = withContext(Dispatchers.IO) {
        try {
            if (!cacheFile.exists()) {
                return@withContext CachedCatalog()
            }
            val jsonString = cacheFile.readText(Charsets.UTF_8)
            if (jsonString.isBlank()) {
                return@withContext CachedCatalog()
            }
            val type = object : TypeToken<CachedCatalog>() {}.type
            gson.fromJson<CachedCatalog>(jsonString, type) ?: CachedCatalog()
        } catch (e: Exception) {
            CachedCatalog()
        }
    }

    /**
     * Atomically writes the updated movies and TV series catalog to internal storage.
     */
    suspend fun saveCatalog(movies: List<VideoItem>, series: List<TvSeries>) = withContext(Dispatchers.IO) {
        try {
            val catalog = CachedCatalog(
                movies = movies,
                series = series,
                lastSyncTimestamp = System.currentTimeMillis()
            )
            val jsonString = gson.toJson(catalog)
            val tempFile = File(context.filesDir, "cached_catalog.json.tmp")
            tempFile.writeText(jsonString, Charsets.UTF_8)
            if (!tempFile.renameTo(cacheFile)) {
                cacheFile.writeText(jsonString, Charsets.UTF_8)
                tempFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Purges the local disk cache upon sign-out or manual user cache clear.
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        try {
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
