package com.clotube.app.data

import android.content.Context
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Keep
@Immutable
data class RecentSearchEntry(
    val query: String,
    val searchedAt: Long = System.currentTimeMillis()
)

/**
 * Local-device-only manager for Recent Searches in Global Search.
 * Persists strictly to local app storage (never synced or uploaded to Google Drive).
 */
object SearchHistoryManager {

    private const val FILE_NAME = "local_recent_searches.json"
    private const val MAX_RECENT_SEARCHES = 20

    private val mutex = Mutex()
    private val _recentSearches = MutableStateFlow<List<RecentSearchEntry>>(emptyList())
    val recentSearches: StateFlow<List<RecentSearchEntry>> = _recentSearches.asStateFlow()

    @Volatile
    private var loadedProfileKey: String? = null

    private fun resolveLocalFile(context: Context): File {
        val dir = try {
            ProfileManager.getProfileDir(context.applicationContext)
        } catch (_: Exception) {
            context.applicationContext.filesDir
        }
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    suspend fun ensureLoaded(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = resolveLocalFile(context)
            val profileKey = file.absolutePath
            if (loadedProfileKey == profileKey) return@withLock

            val parsed = if (file.exists() && file.length() > 0L) {
                parseJson(file.readText(Charsets.UTF_8))
            } else {
                emptyList()
            }
            _recentSearches.value = parsed
            loadedProfileKey = profileKey
        }
    }

    suspend fun addSearchQuery(context: Context, rawQuery: String) = withContext(Dispatchers.IO) {
        val cleaned = rawQuery.trim().replace(Regex("\\s+"), " ")
        if (cleaned.length < 2) return@withContext

        mutex.withLock {
            val file = resolveLocalFile(context)
            if (loadedProfileKey != file.absolutePath && file.exists() && file.length() > 0L) {
                _recentSearches.value = parseJson(file.readText(Charsets.UTF_8))
            }

            val updated = _recentSearches.value
                .filterNot { it.query.equals(cleaned, ignoreCase = true) }
                .toMutableList()
            updated.add(0, RecentSearchEntry(query = cleaned, searchedAt = System.currentTimeMillis()))
            if (updated.size > MAX_RECENT_SEARCHES) {
                updated.subList(MAX_RECENT_SEARCHES, updated.size).clear()
            }

            _recentSearches.value = updated
            loadedProfileKey = file.absolutePath
            writeAtomic(file, updated)
        }
    }

    suspend fun removeSearchQuery(context: Context, query: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = resolveLocalFile(context)
            val updated = _recentSearches.value.filterNot { it.query.equals(query, ignoreCase = true) }
            _recentSearches.value = updated
            loadedProfileKey = file.absolutePath
            writeAtomic(file, updated)
        }
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = resolveLocalFile(context)
            _recentSearches.value = emptyList()
            loadedProfileKey = file.absolutePath
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun parseJson(raw: String): List<RecentSearchEntry> {
        return try {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("items") ?: return emptyList()
            val list = ArrayList<RecentSearchEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val q = obj.optString("query").trim()
                if (q.isNotBlank()) {
                    list.add(
                        RecentSearchEntry(
                            query = q,
                            searchedAt = obj.optLong("searchedAt", System.currentTimeMillis())
                        )
                    )
                }
            }
            list.take(MAX_RECENT_SEARCHES)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeAtomic(file: File, items: List<RecentSearchEntry>) {
        try {
            val arr = JSONArray()
            for (item in items) {
                arr.put(
                    JSONObject().apply {
                        put("query", item.query)
                        put("searchedAt", item.searchedAt)
                    }
                )
            }
            val root = JSONObject().apply {
                put("version", 1)
                put("items", arr)
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }
}
