package com.clotube.app.data

import android.content.Context
import androidx.annotation.Keep
import com.clotube.app.model.Movie
import com.clotube.app.model.TvSeries
import com.clotube.app.model.VideoItem
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Serializable

/**
 * Serialized data envelope for cached media library.
 * Persisted on disk to enable instant 0ms cold-start rendering.
 */
@Keep
data class CachedCatalog(
    @SerializedName("movies") val movies: List<VideoItem> = emptyList(),
    @SerializedName("series") val series: List<TvSeries> = emptyList(),
    @SerializedName("lastSyncTimestamp") val lastSyncTimestamp: Long = 0L
) : Serializable

/**
 * Manages isolated local disk caching of the Google Drive catalog per user profile.
 * Implements in-memory read-through caching and atomic temp-file writes to eliminate
 * duplicate JSON serialization and prevent read corruption.
 */
class CatalogCacheManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()

    private val cacheFile: File
        get() {
            val dir = ProfileManager.getProfileDir(appContext)
            return File(dir, "cached_catalog.json")
        }

    private val legacySeriesCacheFile: File
        get() = File(appContext.filesDir, "cached_series_catalog.json")

    private val legacyRootCacheFile: File
        get() = File(appContext.filesDir, "cached_catalog.json")

    companion object {
        private val cacheMutex = Mutex()
        private val diskLock = Any()

        @Volatile
        private var cachedInMemory: CachedCatalog? = null

        /**
         * Clears in-memory catalog cache when switching profiles.
         */
        fun clearInMemoryCache() {
            synchronized(diskLock) {
                cachedInMemory = null
            }
        }
    }

    private fun readCatalogFromDiskSync(): CachedCatalog {
        cachedInMemory?.let { return it }
        synchronized(diskLock) {
            cachedInMemory?.let { return it }
            val loaded = try {
                val targetFile = cacheFile
                if (targetFile.exists()) {
                    val jsonString = targetFile.readText(Charsets.UTF_8)
                    if (jsonString.isNotBlank()) {
                        val type = object : TypeToken<CachedCatalog>() {}.type
                        gson.fromJson<CachedCatalog>(jsonString, type) ?: CachedCatalog()
                    } else {
                        CachedCatalog()
                    }
                } else if (legacyRootCacheFile.exists()) {
                    // One-time migration from legacy root directory to profile directory
                    val legacyJson = legacyRootCacheFile.readText(Charsets.UTF_8)
                    if (legacyJson.isNotBlank()) {
                        val type = object : TypeToken<CachedCatalog>() {}.type
                        val migrated = gson.fromJson<CachedCatalog>(legacyJson, type) ?: CachedCatalog()
                        try {
                            val targetDir = targetFile.parentFile ?: appContext.filesDir
                            if (!targetDir.exists()) targetDir.mkdirs()
                            targetFile.writeText(legacyJson, Charsets.UTF_8)
                        } catch (_: Exception) {}
                        migrated
                    } else {
                        CachedCatalog()
                    }
                } else if (legacySeriesCacheFile.exists()) {
                    val legacyJson = legacySeriesCacheFile.readText(Charsets.UTF_8)
                    if (legacyJson.isNotBlank()) {
                        val type = object : TypeToken<List<TvSeries>>() {}.type
                        val legacySeries = gson.fromJson<List<TvSeries>>(legacyJson, type) ?: emptyList()
                        CachedCatalog(series = legacySeries)
                    } else {
                        CachedCatalog()
                    }
                } else {
                    CachedCatalog()
                }
            } catch (_: Exception) {
                CachedCatalog()
            }
            cachedInMemory = loaded
            return loaded
        }
    }

    private fun writeCatalogToDiskSync(catalog: CachedCatalog) {
        synchronized(diskLock) {
            try {
                cachedInMemory = catalog
                val targetFile = cacheFile
                val targetDir = targetFile.parentFile ?: appContext.filesDir
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val jsonString = gson.toJson(catalog)
                val tempFile = File(targetDir, "cached_catalog.json.tmp")
                tempFile.writeText(jsonString, Charsets.UTF_8)
                if (!tempFile.renameTo(targetFile)) {
                    targetFile.writeText(jsonString, Charsets.UTF_8)
                    tempFile.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getCachedSeriesSync(): List<TvSeries> = readCatalogFromDiskSync().series

    fun saveSeriesOnlySync(series: List<TvSeries>) {
        val current = readCatalogFromDiskSync()
        writeCatalogToDiskSync(
            current.copy(
                series = series,
                lastSyncTimestamp = System.currentTimeMillis()
            )
        )
    }

    fun upsertSeriesSync(updatedShow: TvSeries) {
        synchronized(diskLock) {
            val current = readCatalogFromDiskSync()
            val updatedList = current.series.toMutableList()
            val index = updatedList.indexOfFirst { it.id == updatedShow.id }
            if (index >= 0) {
                updatedList[index] = updatedShow
            } else {
                updatedList.add(updatedShow)
            }
            writeCatalogToDiskSync(
                current.copy(
                    series = updatedList,
                    lastSyncTimestamp = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Reads the cached catalog from internal storage (or warm memory cache).
     */
    suspend fun getCachedCatalog(): CachedCatalog = withContext(Dispatchers.IO) {
        cachedInMemory?.let { return@withContext it }
        cacheMutex.withLock {
            readCatalogFromDiskSync()
        }
    }

    /**
     * Atomically writes the updated movies and TV series catalog to internal storage in a single pass.
     */
    suspend fun saveCatalog(movies: List<VideoItem>, series: List<TvSeries>) = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            writeCatalogToDiskSync(
                CachedCatalog(
                    movies = movies,
                    series = series,
                    lastSyncTimestamp = System.currentTimeMillis()
                )
            )
        }
    }

    @JvmName("saveCatalogDomainMovies")
    suspend fun saveCatalog(movies: List<Movie>, series: List<TvSeries>) = withContext(Dispatchers.IO) {
        saveCatalog(movies.map { it.toVideoItem() }, series)
    }

    /**
     * Purges the local disk and memory caches upon sign-out or manual user cache clear.
     */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        cacheMutex.withLock {
            synchronized(diskLock) {
                cachedInMemory = null
                try {
                    val targetFile = cacheFile
                    if (targetFile.exists()) targetFile.delete()
                    if (legacySeriesCacheFile.exists()) legacySeriesCacheFile.delete()
                    if (legacyRootCacheFile.exists()) legacyRootCacheFile.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
