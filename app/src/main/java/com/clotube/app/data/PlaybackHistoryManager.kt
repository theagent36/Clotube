package com.clotube.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.clotube.app.model.ContinueWatchingItem
import com.clotube.app.model.Episode
import androidx.annotation.Keep
import java.io.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "clotube_playback_history")
val Context.playbackHistoryDataStore: DataStore<Preferences> get() = playbackDataStore

@Keep
@androidx.compose.runtime.Immutable
data class WatchProgress(
    @SerializedName("fileId") val fileId: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("positionMs") val positionMs: Long = 0L,
    @SerializedName("durationMs") val durationMs: Long = 0L,
    @SerializedName("progressFraction") val progressFraction: Float = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
    @SerializedName("lastWatchedTimestamp") val lastWatchedTimestamp: Long = System.currentTimeMillis(),
    @SerializedName("isSeries") val isSeries: Boolean = false,
    @SerializedName("seriesId") val seriesId: String? = null,
    @SerializedName("seasonNumber") val seasonNumber: Int = 0,
    @SerializedName("episodeNumber") val episodeNumber: Int = 0,
    @SerializedName("seriesTitle") val seriesTitle: String? = null,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String? = null,
    @SerializedName("backdropUrl") val backdropUrl: String? = null,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("isWatched") val isWatched: Boolean = progressFraction >= 0.90f
) : Serializable {
    val lastUpdatedEpochMs: Long get() = lastWatchedTimestamp
    val episodeId: String get() = fileId
    val mediaId: String get() = fileId

    val formattedDisplayTitle: String
        get() {
            val safeTitle = title ?: ""
            val safeSeriesTitle = seriesTitle ?: ""
            return when {
                isSeries && safeSeriesTitle.isNotBlank() -> {
                    val epCode = "S${String.format(java.util.Locale.US, "%02d", seasonNumber)}:E${String.format(java.util.Locale.US, "%02d", episodeNumber)}"
                    if (safeTitle.isNotBlank() && !safeTitle.equals(safeSeriesTitle, ignoreCase = true)) {
                        "$safeSeriesTitle • $epCode • $safeTitle"
                    } else {
                        "$safeSeriesTitle • $epCode"
                    }
                }
                isSeries -> {
                    val epCode = "S${String.format(java.util.Locale.US, "%02d", seasonNumber)}:E${String.format(java.util.Locale.US, "%02d", episodeNumber)}"
                    if (safeTitle.isNotBlank()) "$safeTitle • $epCode" else "Series • $epCode"
                }
                else -> safeTitle.ifBlank { "Untitled Video" }
            }
        }

    fun toJson(): String {
        val json = JSONObject()
        json.put("fileId", fileId ?: "")
        json.put("title", title ?: "")
        seriesTitle?.let { json.put("seriesTitle", it) }
        json.put("seasonNumber", seasonNumber)
        json.put("episodeNumber", episodeNumber)
        json.put("isSeries", isSeries)
        json.put("positionMs", positionMs)
        json.put("durationMs", durationMs)
        json.put("progressFraction", progressFraction.toDouble())
        json.put("lastWatchedTimestamp", lastWatchedTimestamp)
        json.put("lastUpdatedEpochMs", lastWatchedTimestamp)
        val thumb = thumbnailUrl ?: backdropUrl
        thumb?.let {
            json.put("thumbnailUrl", it)
            json.put("backdropUrl", it)
        }
        seriesId?.let { json.put("seriesId", it) }
        genre?.let { json.put("genre", it) }
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): WatchProgress? {
            return try {
                val json = JSONObject(jsonStr)
                val rawFileId = json.optString("fileId", "").trim()
                if (rawFileId.isEmpty()) return null

                val thumb = when {
                    json.has("thumbnailUrl") && !json.isNull("thumbnailUrl") -> json.optString("thumbnailUrl", "").ifBlank { null }
                    json.has("backdropUrl") && !json.isNull("backdropUrl") -> json.optString("backdropUrl", "").ifBlank { null }
                    else -> null
                }
                val ts = when {
                    json.has("lastWatchedTimestamp") -> json.optLong("lastWatchedTimestamp", 0L)
                    json.has("lastUpdatedEpochMs") -> json.optLong("lastUpdatedEpochMs", 0L)
                    else -> 0L
                }
                val pos = json.optLong("positionMs", 0L)
                val dur = json.optLong("durationMs", 0L)
                val frac = if (json.has("progressFraction")) {
                    json.optDouble("progressFraction", 0.0).toFloat()
                } else if (dur > 0L) {
                    (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                } else 0f

                val parsedTitle = json.optString("title", "Unknown").trim().ifBlank { "Unknown" }
                val parsedSeriesTitle = if (json.has("seriesTitle") && !json.isNull("seriesTitle")) json.optString("seriesTitle", "").ifBlank { null } else null
                val parsedSNum = json.optInt("seasonNumber", 0)
                val parsedENum = json.optInt("episodeNumber", 0)
                val parsedSId = if (json.has("seriesId") && !json.isNull("seriesId")) json.optString("seriesId", "").ifBlank { null } else null
                val titleHasSeriesPattern = parsedTitle.contains(Regex("""(?i)\b(S\d+[:\s]*E\d+|\d+x\d+|Season\s*\d+|Episode\s*\d+)\b"""))
                val isSeries = json.optBoolean("isSeries", false) || !parsedSeriesTitle.isNullOrBlank() || parsedSNum > 0 || parsedENum > 0 || !parsedSId.isNullOrBlank() || titleHasSeriesPattern

                WatchProgress(
                    fileId = rawFileId,
                    title = parsedTitle,
                    seriesTitle = parsedSeriesTitle,
                    seasonNumber = parsedSNum,
                    episodeNumber = parsedENum,
                    isSeries = isSeries,
                    positionMs = pos,
                    durationMs = dur,
                    progressFraction = frac,
                    lastWatchedTimestamp = ts,
                    thumbnailUrl = thumb,
                    backdropUrl = thumb,
                    seriesId = parsedSId,
                    genre = if (json.has("genre") && !json.isNull("genre")) json.optString("genre", "").ifBlank { null } else null
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

@Keep
data class SeriesResumeState(
    @SerializedName("seriesId") val seriesId: String = "",
    @SerializedName("lastEpisodeId") val lastEpisodeId: String = "",
    @SerializedName("seasonNumber") val seasonNumber: Int = 0,
    @SerializedName("episodeNumber") val episodeNumber: Int = 0,
    @SerializedName("positionMs") val positionMs: Long = 0L,
    @SerializedName("durationMs") val durationMs: Long = 0L,
    @SerializedName("lastWatchedTimestamp") val lastWatchedTimestamp: Long = 0L
) : Serializable {
    fun toJson(): String {
        val json = JSONObject()
        json.put("seriesId", seriesId ?: "")
        json.put("lastEpisodeId", lastEpisodeId ?: "")
        json.put("seasonNumber", seasonNumber)
        json.put("episodeNumber", episodeNumber)
        json.put("positionMs", positionMs)
        json.put("durationMs", durationMs)
        json.put("lastWatchedTimestamp", lastWatchedTimestamp)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): SeriesResumeState? {
            return try {
                val json = JSONObject(jsonStr)
                val sId = json.optString("seriesId", "").trim()
                val epId = json.optString("lastEpisodeId", "").trim()
                if (sId.isEmpty() || epId.isEmpty()) return null
                SeriesResumeState(
                    seriesId = sId,
                    lastEpisodeId = epId,
                    seasonNumber = json.optInt("seasonNumber", 1),
                    episodeNumber = json.optInt("episodeNumber", 1),
                    positionMs = json.optLong("positionMs", 0L),
                    durationMs = json.optLong("durationMs", 0L),
                    lastWatchedTimestamp = json.optLong("lastWatchedTimestamp", 0L)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

@Keep
data class WatchHistoryItem(
    @SerializedName("fileId") val fileId: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("seriesTitle") val seriesTitle: String? = null,
    @SerializedName("seasonNumber") val seasonNumber: Int = 0,
    @SerializedName("episodeNumber") val episodeNumber: Int = 0,
    @SerializedName("isSeries") val isSeries: Boolean = false,
    @SerializedName("seriesId") val seriesId: String? = null,
    @SerializedName("positionMs") val positionMs: Long = 0L,
    @SerializedName("durationMs") val durationMs: Long = 0L,
    @SerializedName("lastWatchedTimestamp") val lastWatchedTimestamp: Long = System.currentTimeMillis(),
    @SerializedName("thumbnailUrl") val thumbnailUrl: String? = null
) : Serializable {
    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val lastWatchedEpochMs: Long get() = lastWatchedTimestamp

    val formattedDisplayTitle: String
        get() {
            val safeTitle = title ?: ""
            val safeSeriesTitle = seriesTitle ?: ""
            return when {
                safeTitle.contains("• S") -> safeTitle
                isSeries && safeSeriesTitle.isNotBlank() -> {
                    val epCode = "S${String.format(java.util.Locale.US, "%02d", seasonNumber)}:E${String.format(java.util.Locale.US, "%02d", episodeNumber)}"
                    if (safeTitle.isNotBlank() && !safeTitle.equals(safeSeriesTitle, ignoreCase = true)) {
                        "$safeSeriesTitle • $epCode • $safeTitle"
                    } else {
                        "$safeSeriesTitle • $epCode"
                    }
                }
                isSeries -> {
                    val epCode = "S${String.format(java.util.Locale.US, "%02d", seasonNumber)}:E${String.format(java.util.Locale.US, "%02d", episodeNumber)}"
                    if (safeTitle.isNotBlank()) "$safeTitle • $epCode" else "Series • $epCode"
                }
                else -> safeTitle.ifBlank { "Untitled Video" }
            }
        }
}

data class EpisodeResumeInfo(
    val episodeId: String,
    val title: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val thumbnailUrl: String? = null
)

class PlaybackHistoryManager(
    val dataStore: DataStore<Preferences>,
    private val watchHistorySync: DriveWatchHistorySync? = null
) {
    private var context: Context? = null
    var nextEpisodeResolver: ((seriesId: String, currentEpisodeId: String) -> EpisodeResumeInfo?)? = null

    constructor(context: Context) : this(
        context.playbackDataStore,
        DriveWatchHistorySync.getInstance(context)
    ) {
        this.context = context
    }

    companion object {
        private const val TAG = "PlaybackHistoryManager"
        private val HISTORY_JSON_KEY = stringPreferencesKey("watch_history_list_json")

        // Dedicated snapshot keys for the single most recently watched Continue Watching item
        val SNAPSHOT_FILE_ID = stringPreferencesKey("global_latest_file_id")
        val SNAPSHOT_TITLE = stringPreferencesKey("global_latest_title")
        val SNAPSHOT_SERIES_NAME = stringPreferencesKey("global_latest_series_name")
        val SNAPSHOT_SERIES_ID = stringPreferencesKey("global_latest_series_id")
        val SNAPSHOT_SEASON_NUMBER = intPreferencesKey("global_latest_season_num")
        val SNAPSHOT_EPISODE_NUMBER = intPreferencesKey("global_latest_episode_num")
        val SNAPSHOT_IS_SERIES = booleanPreferencesKey("global_latest_is_series")
        val SNAPSHOT_THUMBNAIL_URL = stringPreferencesKey("global_latest_thumbnail_url")
        val SNAPSHOT_POSITION_MS = longPreferencesKey("global_latest_pos_ms")
        val SNAPSHOT_DURATION_MS = longPreferencesKey("global_latest_dur_ms")
        val SNAPSHOT_FRACTION = floatPreferencesKey("global_latest_fraction")
        val SNAPSHOT_TIMESTAMP = longPreferencesKey("global_latest_timestamp")

        @JvmStatic
        fun getResumePosition(context: Context, userEmail: String, fileId: String, durationMs: Long): Long {
            if (userEmail.isBlank() || fileId.isBlank()) return 0L

            val local = AndroidWatchHistoryManager.loadLocalSync(context, userEmail)
            val pos = (local["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = if (durationMs > 0L) durationMs else (local["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
            val fraction = (local["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f
            val isWatched = AndroidWatchHistoryManager.isEpisodeWatched(context, userEmail, fileId) || fraction >= 0.90f

            return when {
                // User is actively re-watching past 5 seconds and has not reached the end credits
                pos > 5000L && (dur == 0L || pos < dur - 15000L) -> pos

                // Watched episode with no active re-watch progress: start from beginning
                isWatched -> 0L

                // Standard in-progress playback
                pos > 3000L -> pos

                else -> 0L
            }
        }
    }

    suspend fun getResumePosition(fileId: String): Long = withContext(Dispatchers.IO) {
        val prefs = dataStore.data.first()
        val isWatched = prefs[booleanPreferencesKey("${fileId}_is_watched")]
            ?: ((prefs[floatPreferencesKey("${fileId}_fraction")] ?: 0f) >= 0.90f)

        val savedPos = prefs[longPreferencesKey("${fileId}_pos")] ?: 0L
        val duration = prefs[longPreferencesKey("${fileId}_dur")] ?: 0L

        when {
            // User is actively re-watching past 5 seconds and has not reached the end credits
            savedPos > 5000L && (duration == 0L || savedPos < duration - 15000L) -> savedPos

            // Watched episode with no active re-watch progress: start from beginning
            isWatched -> 0L

            // Standard in-progress playback
            savedPos > 3000L -> savedPos

            else -> 0L
        }
    }

    fun getResumePosition(userEmail: String, fileId: String, durationMs: Long): Long {
        val ctx = context ?: return 0L
        return getResumePosition(ctx, userEmail, fileId, durationMs)
    }

    private val gson = Gson()
    private val historyMutex = Mutex()

    @Volatile
    private var tokenProvider: (suspend () -> String)? = null

    fun setTokenProvider(provider: suspend () -> String) {
        this.tokenProvider = provider
        DriveWatchHistorySync.setTokenProvider(provider)
        context?.let { ctx ->
            DriveWatchHistorySync.init(ctx, dataStore, provider)
        }
    }

    suspend fun syncFromCloud(): Boolean {
        val sync = watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) }
        return sync?.syncFromCloud(dataStore) ?: DriveWatchHistorySync.syncFromCloud(dataStore, context)
    }

    fun backupToCloudThrottled() {
        val sync = watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) }
        sync?.triggerThrottledBackup(dataStore)
    }

    fun backupToCloudImmediately() {
        val sync = watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) }
        sync?.backupImmediately(dataStore)
    }

    fun backupImmediately() {
        backupToCloudImmediately()
    }

    /**
     * Unified progress saver: Updates episode history, series resume state, and triggers cloud sync.
     * Retains fully completed media (fraction = 1.0f, positionMs = durationMs).
     */
    fun cleanTitleFormatting(rawTitle: String): String {
        var text = rawTitle.trim()
        // Strip out repeated prefixes like "The Mentalist • S02:E06 • The Mentalist • S02:E06 • "
        val regex = Regex("(?:.*?•\\s*S\\d+[:E]\\d+\\s*•\\s*)+", RegexOption.IGNORE_CASE)
        val match = regex.find(text)
        if (match != null && match.range.first == 0) {
            val remainder = text.substring(match.range.last + 1).trim()
            if (remainder.isNotBlank()) {
                text = remainder
            }
        }
        text = text.removePrefix("•").trim()
        return text
    }

    fun cleanSeriesTitle(rawSeriesTitle: String): String {
        var title = cleanTitleFormatting(rawSeriesTitle)
        val seasonEpRegex = Regex("\\s*•\\s*S\\d+[:E]\\d+.*$", RegexOption.IGNORE_CASE)
        title = seasonEpRegex.replace(title, "").trim()
        return title.ifBlank { rawSeriesTitle.trim() }
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSec = ms / 1000
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        val hrs = totalSec / 3600
        return if (hrs > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", mins, secs)
        }
    }

    suspend fun recordEpisodePlayback(
        seriesId: String,
        seriesTitle: String,
        episodeId: String,
        episodeTitle: String,
        seasonNumber: Int,
        episodeNumber: Int,
        positionMs: Long,
        durationMs: Long,
        thumbnailUrl: String? = null
    ) = withContext(NonCancellable + Dispatchers.IO) {
        if (episodeId.isBlank()) return@withContext

        val now = System.currentTimeMillis()
        val validDur = durationMs.coerceAtLeast(1000L)
        val isFinished = positionMs >= (validDur * 0.95f)
        val finalPos = if (isFinished) validDur else positionMs
        val finalFraction = if (isFinished) 1.0f else (positionMs.toFloat() / validDur.toFloat()).coerceIn(0f, 1f)
        val cleanEp = cleanTitleFormatting(episodeTitle)
        val cleanSeries = cleanSeriesTitle(seriesTitle).ifBlank { seriesTitle.trim() }

        dataStore.edit { prefs ->
            // Episode entry
            prefs[stringPreferencesKey("${episodeId}_title")] = cleanEp
            prefs[longPreferencesKey("${episodeId}_pos")] = finalPos
            prefs[longPreferencesKey("${episodeId}_dur")] = validDur
            prefs[floatPreferencesKey("${episodeId}_fraction")] = finalFraction
            prefs[longPreferencesKey("${episodeId}_timestamp")] = now
            prefs[booleanPreferencesKey("${episodeId}_is_series")] = true
            prefs[stringPreferencesKey("${episodeId}_series_id")] = seriesId
            prefs[intPreferencesKey("${episodeId}_season_num")] = seasonNumber
            prefs[intPreferencesKey("${episodeId}_episode_num")] = episodeNumber
            thumbnailUrl?.let { prefs[stringPreferencesKey("${episodeId}_thumb")] = it }

            // Series resume pointer
            val prefix = "series_resume_${seriesId}"
            prefs[stringPreferencesKey("${prefix}_series_title")] = cleanSeries
            prefs[stringPreferencesKey("${prefix}_episode_id")] = episodeId
            prefs[stringPreferencesKey("${prefix}_episode_title")] = cleanEp
            prefs[intPreferencesKey("${prefix}_season_num")] = seasonNumber
            prefs[intPreferencesKey("${prefix}_episode_num")] = episodeNumber
            prefs[longPreferencesKey("${prefix}_pos_ms")] = finalPos
            prefs[longPreferencesKey("${prefix}_dur_ms")] = validDur
            prefs[floatPreferencesKey("${prefix}_fraction")] = finalFraction
            prefs[longPreferencesKey("${prefix}_timestamp")] = now
            thumbnailUrl?.let { prefs[stringPreferencesKey("${prefix}_thumb")] = it }

            // Global pointer & complete snapshot
            prefs[stringPreferencesKey("global_latest_type")] = "series"
            prefs[stringPreferencesKey("global_latest_id")] = seriesId
            prefs[longPreferencesKey("global_latest_timestamp")] = now

            prefs[SNAPSHOT_FILE_ID] = episodeId
            prefs[SNAPSHOT_TITLE] = cleanEp
            cleanSeries?.let { prefs[SNAPSHOT_SERIES_NAME] = it } ?: prefs.remove(SNAPSHOT_SERIES_NAME)
            prefs[SNAPSHOT_SERIES_ID] = seriesId
            prefs[SNAPSHOT_SEASON_NUMBER] = seasonNumber
            prefs[SNAPSHOT_EPISODE_NUMBER] = episodeNumber
            prefs[SNAPSHOT_IS_SERIES] = true
            thumbnailUrl?.let { prefs[SNAPSHOT_THUMBNAIL_URL] = it }
            prefs[SNAPSHOT_POSITION_MS] = finalPos
            prefs[SNAPSHOT_DURATION_MS] = validDur
            prefs[SNAPSHOT_FRACTION] = finalFraction
            prefs[SNAPSHOT_TIMESTAMP] = now
        }

        val epCode = "S${String.format(java.util.Locale.US, "%02d", seasonNumber)}:E${String.format(java.util.Locale.US, "%02d", episodeNumber)}"
        val formattedTitle = "$cleanSeries • $epCode • $cleanEp"
        recordToWatchHistoryList(
            WatchHistoryItem(
                fileId = episodeId,
                title = formattedTitle,
                seriesTitle = cleanSeries,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                isSeries = true,
                seriesId = seriesId,
                positionMs = finalPos,
                durationMs = validDur,
                lastWatchedTimestamp = now,
                thumbnailUrl = thumbnailUrl
            )
        )

        (watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) })?.triggerThrottledBackup(dataStore)
    }

    suspend fun completeEpisodeAndAdvance(
        seriesId: String,
        seriesTitle: String,
        completedEpisodeId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        durationMs: Long,
        nextEpisodeId: String?,
        nextEpisodeTitle: String?,
        nextSeasonNumber: Int?,
        nextEpisodeNumber: Int?,
        nextThumbnailUrl: String? = null
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val validDur = durationMs.coerceAtLeast(1000L)
        val cleanSeries = cleanSeriesTitle(seriesTitle).ifBlank { seriesTitle.trim() }

        val resolvedNext = if (nextEpisodeId.isNullOrBlank()) {
            nextEpisodeResolver?.invoke(seriesId, completedEpisodeId)
        } else null

        val targetNextId = nextEpisodeId ?: resolvedNext?.episodeId
        val targetNextTitle = nextEpisodeTitle ?: resolvedNext?.title
        val targetNextSNum = nextSeasonNumber ?: resolvedNext?.seasonNumber ?: seasonNumber
        val targetNextENum = nextEpisodeNumber ?: resolvedNext?.episodeNumber ?: (episodeNumber + 1)
        val targetNextThumb = nextThumbnailUrl ?: resolvedNext?.thumbnailUrl

        dataStore.edit { prefs ->
            // Mark completed episode 100% watched with reset resume position (0L)
            prefs[booleanPreferencesKey("${completedEpisodeId}_is_watched")] = true
            prefs[longPreferencesKey("${completedEpisodeId}_pos")] = 0L
            prefs[longPreferencesKey("${completedEpisodeId}_dur")] = validDur
            prefs[floatPreferencesKey("${completedEpisodeId}_fraction")] = 1.0f
            prefs[longPreferencesKey("${completedEpisodeId}_timestamp")] = now
            prefs[booleanPreferencesKey("${completedEpisodeId}_is_series")] = true
            prefs[stringPreferencesKey("${completedEpisodeId}_series_id")] = seriesId
            prefs[stringPreferencesKey("${completedEpisodeId}_series_title")] = cleanSeries
            prefs[intPreferencesKey("${completedEpisodeId}_season_num")] = seasonNumber
            prefs[intPreferencesKey("${completedEpisodeId}_episode_num")] = episodeNumber

            val prefix = "series_resume_${seriesId}"
            if (!targetNextId.isNullOrBlank()) {
                // Point Continue Watching to the next episode (cued at 0L and 0f with updated timestamp)
                val nextEpTitle = cleanTitleFormatting(targetNextTitle ?: "Episode $targetNextENum")
                prefs[stringPreferencesKey("${prefix}_series_title")] = cleanSeries
                prefs[stringPreferencesKey("${prefix}_episode_id")] = targetNextId
                prefs[stringPreferencesKey("${prefix}_episode_title")] = nextEpTitle
                prefs[intPreferencesKey("${prefix}_season_num")] = targetNextSNum
                prefs[intPreferencesKey("${prefix}_episode_num")] = targetNextENum
                prefs[longPreferencesKey("${prefix}_pos_ms")] = 0L
                prefs[longPreferencesKey("${prefix}_dur_ms")] = 0L
                prefs[floatPreferencesKey("${prefix}_fraction")] = 0.0f
                prefs[longPreferencesKey("${prefix}_timestamp")] = now + 500L
                targetNextThumb?.let { prefs[stringPreferencesKey("${prefix}_thumb")] = it }

                // Ensure next episode records reflect series status and queued progress
                // (never mark targetNextId as watched or write durationMs into its position)
                prefs[booleanPreferencesKey("${targetNextId}_is_series")] = true
                prefs[stringPreferencesKey("${targetNextId}_series_id")] = seriesId
                prefs[stringPreferencesKey("${targetNextId}_series_title")] = cleanSeries
                prefs[intPreferencesKey("${targetNextId}_season_num")] = targetNextSNum
                prefs[intPreferencesKey("${targetNextId}_episode_num")] = targetNextENum
                prefs[stringPreferencesKey("${targetNextId}_title")] = nextEpTitle
                prefs[longPreferencesKey("${targetNextId}_pos")] = 0L
                prefs[longPreferencesKey("${targetNextId}_dur")] = 0L
                prefs[floatPreferencesKey("${targetNextId}_fraction")] = 0.0f
                prefs[longPreferencesKey("${targetNextId}_timestamp")] = now + 500L
                targetNextThumb?.let { prefs[stringPreferencesKey("${targetNextId}_thumb")] = it }
                prefs.remove(stringPreferencesKey("progress_${targetNextId}"))

                // Save snapshot
                prefs[SNAPSHOT_FILE_ID] = targetNextId
                prefs[SNAPSHOT_TITLE] = nextEpTitle
                prefs[SNAPSHOT_SERIES_NAME] = cleanSeries
                prefs[SNAPSHOT_SERIES_ID] = seriesId
                prefs[SNAPSHOT_SEASON_NUMBER] = targetNextSNum
                prefs[SNAPSHOT_EPISODE_NUMBER] = targetNextENum
                prefs[SNAPSHOT_IS_SERIES] = true
                targetNextThumb?.let { prefs[SNAPSHOT_THUMBNAIL_URL] = it }
                prefs[SNAPSHOT_POSITION_MS] = 0L
                prefs[SNAPSHOT_DURATION_MS] = 0L
                prefs[SNAPSHOT_FRACTION] = 0.0f
                prefs[SNAPSHOT_TIMESTAMP] = now + 500L

                prefs[stringPreferencesKey("global_latest_type")] = "series"
                prefs[stringPreferencesKey("global_latest_id")] = seriesId
                prefs[longPreferencesKey("global_latest_timestamp")] = now + 500L
            } else {
                prefs[floatPreferencesKey("${prefix}_fraction")] = 1.0f
                prefs[longPreferencesKey("${prefix}_pos_ms")] = validDur
                prefs[longPreferencesKey("${prefix}_dur_ms")] = validDur
                prefs[longPreferencesKey("${prefix}_timestamp")] = now

                prefs[SNAPSHOT_FILE_ID] = completedEpisodeId
                prefs[SNAPSHOT_TITLE] = cleanTitleFormatting(completedEpisodeId)
                prefs[SNAPSHOT_SERIES_NAME] = cleanSeries
                prefs[SNAPSHOT_SERIES_ID] = seriesId
                prefs[SNAPSHOT_SEASON_NUMBER] = seasonNumber
                prefs[SNAPSHOT_EPISODE_NUMBER] = episodeNumber
                prefs[SNAPSHOT_IS_SERIES] = true
                prefs[SNAPSHOT_POSITION_MS] = validDur
                prefs[SNAPSHOT_DURATION_MS] = validDur
                prefs[SNAPSHOT_FRACTION] = 1.0f
                prefs[SNAPSHOT_TIMESTAMP] = now

                prefs[stringPreferencesKey("global_latest_type")] = "series"
                prefs[stringPreferencesKey("global_latest_id")] = seriesId
                prefs[longPreferencesKey("global_latest_timestamp")] = now
            }
        }

        val epCode = "S${String.format(java.util.Locale.US, "%02d", seasonNumber)}:E${String.format(java.util.Locale.US, "%02d", episodeNumber)}"
        val formattedTitle = "$cleanSeries • $epCode"
        recordToWatchHistoryList(
            WatchHistoryItem(
                fileId = completedEpisodeId,
                title = formattedTitle,
                seriesTitle = cleanSeries,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                isSeries = true,
                seriesId = seriesId,
                positionMs = validDur,
                durationMs = validDur,
                lastWatchedTimestamp = now
            )
        )

        (watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) })?.enqueueImmediateWatchState(dataStore)
    }

    /**
     * Checks reactively if all episodes in a given season list have been completed (fraction >= 0.95f).
     */
    fun isSeasonFullyWatched(episodes: List<Episode>): Flow<Boolean> {
        return dataStore.data.map { prefs ->
            if (episodes.isEmpty()) return@map false
            episodes.all { ep ->
                val explicit = prefs[booleanPreferencesKey("${ep.id}_is_watched")]
                if (explicit != null) {
                    explicit
                } else {
                    val fraction = prefs[floatPreferencesKey("${ep.id}_fraction")] ?: 0f
                    fraction >= 0.95f
                }
            }
        }
    }

    /**
     * Checks reactively if a single episode is marked as watched (either explicit _is_watched flag or fraction >= 0.90f).
     */
    fun isEpisodeWatched(episodeId: String): Flow<Boolean> = dataStore.data.map { prefs ->
        val isWatched = prefs[booleanPreferencesKey("${episodeId}_is_watched")]
        val fraction = prefs[floatPreferencesKey("${episodeId}_fraction")] ?: 0f
        isWatched == true || fraction >= 0.90f
    }

    /**
     * Toggles an episode between watched (100%) and unwatched (0%), syncing immediately to Drive.
     */
    suspend fun toggleEpisodeWatched(
        seriesId: String,
        seriesTitle: String,
        episode: com.clotube.app.model.Episode,
        userEmail: String = ""
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val prefs = dataStore.data.first()
        val currentlyWatched = prefs[booleanPreferencesKey("${episode.id}_is_watched")]
            ?: ((prefs[floatPreferencesKey("${episode.id}_fraction")] ?: 0f) >= 0.90f)
        val targetWatched = !currentlyWatched
        val now = System.currentTimeMillis()
        val dur = episode.durationMs.coerceAtLeast(1000L)

        dataStore.edit { p ->
            p[booleanPreferencesKey("${episode.id}_is_watched")] = targetWatched
            p[floatPreferencesKey("${episode.id}_fraction")] = if (targetWatched) 1.0f else 0.0f
            p[longPreferencesKey("${episode.id}_pos")] = if (targetWatched) dur else 0L
            p[longPreferencesKey("${episode.id}_dur")] = dur
            p[longPreferencesKey("${episode.id}_timestamp")] = now
            p[stringPreferencesKey("${episode.id}_title")] = episode.displayTitle
            p[booleanPreferencesKey("${episode.id}_is_series")] = true
            p[stringPreferencesKey("${episode.id}_series_id")] = seriesId
            p[intPreferencesKey("${episode.id}_season_num")] = episode.seasonNumber
            p[intPreferencesKey("${episode.id}_episode_num")] = episode.episodeNumber
        }

        // Keep legacy AndroidWatchHistoryManager in sync if needed
        try {
            if (userEmail.isNotBlank()) {
                context?.let { ctx ->
                    com.clotube.app.data.AndroidWatchHistoryManager.setEpisodeWatched(
                        ctx,
                        userEmail,
                        episode.id,
                        targetWatched
                    )
                }
            }
        } catch (_: Throwable) {}

        // PRIORITY 1: Fire instant upload without pre-pull delay
        (watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) })?.enqueueImmediateWatchState(dataStore)
    }

    /**
     * Backward-compatible overload for legacy callers.
     */
    suspend fun toggleEpisodeWatched(
        episode: com.clotube.app.model.Episode,
        seriesId: String,
        seriesTitle: String
    ) = toggleEpisodeWatched(seriesId, seriesTitle, episode)

    /**
     * Explicitly sets watched state for an episode and syncs to Google Drive.
     */
    suspend fun setEpisodeWatched(
        seriesId: String,
        seriesTitle: String,
        episode: com.clotube.app.model.Episode,
        isWatched: Boolean,
        userEmail: String = ""
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val dur = episode.durationMs.coerceAtLeast(1000L)

        dataStore.edit { p ->
            p[booleanPreferencesKey("${episode.id}_is_watched")] = isWatched
            p[floatPreferencesKey("${episode.id}_fraction")] = if (isWatched) 1.0f else 0.0f
            p[longPreferencesKey("${episode.id}_pos")] = if (isWatched) dur else 0L
            p[longPreferencesKey("${episode.id}_dur")] = dur
            p[longPreferencesKey("${episode.id}_timestamp")] = now
            p[stringPreferencesKey("${episode.id}_title")] = episode.displayTitle
            p[booleanPreferencesKey("${episode.id}_is_series")] = true
            p[stringPreferencesKey("${episode.id}_series_id")] = seriesId
            p[intPreferencesKey("${episode.id}_season_num")] = episode.seasonNumber
            p[intPreferencesKey("${episode.id}_episode_num")] = episode.episodeNumber
        }

        try {
            if (userEmail.isNotBlank()) {
                context?.let { ctx ->
                    com.clotube.app.data.AndroidWatchHistoryManager.setEpisodeWatched(
                        ctx,
                        userEmail,
                        episode.id,
                        isWatched
                    )
                }
            }
        } catch (_: Throwable) {}

        // PRIORITY 1: Instant upload
        (watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) })?.enqueueImmediateWatchState(dataStore)
    }

    suspend fun markEpisodeUnwatched(
        seriesId: String,
        seriesTitle: String,
        episodeId: String,
        episodeTitle: String,
        seasonNumber: Int,
        episodeNumber: Int
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cleanSeries = cleanSeriesTitle(seriesTitle).ifBlank { seriesTitle.trim() }
        val cleanEp = cleanTitleFormatting(episodeTitle)

        dataStore.edit { prefs ->
            // Reset episode progress
            prefs[booleanPreferencesKey("${episodeId}_is_watched")] = false
            prefs[floatPreferencesKey("${episodeId}_fraction")] = 0.0f
            prefs[longPreferencesKey("${episodeId}_pos")] = 0L
            prefs[longPreferencesKey("${episodeId}_dur")] = 0L
            prefs[longPreferencesKey("${episodeId}_timestamp")] = now
            prefs.remove(stringPreferencesKey("progress_${episodeId}"))

            // Rewind series resume pointer to this unwatched episode if current or earlier
            val prefix = "series_resume_${seriesId}"
            val currentResumeEpId = prefs[stringPreferencesKey("${prefix}_episode_id")]
            val currentResumeSNum = prefs[intPreferencesKey("${prefix}_season_num")] ?: Int.MAX_VALUE
            val currentResumeENum = prefs[intPreferencesKey("${prefix}_episode_num")] ?: Int.MAX_VALUE
            val isCurrentOrEarlier = currentResumeEpId == null ||
                    currentResumeEpId == episodeId ||
                    seasonNumber < currentResumeSNum ||
                    (seasonNumber == currentResumeSNum && episodeNumber <= currentResumeENum)

            if (isCurrentOrEarlier) {
                prefs[stringPreferencesKey("${prefix}_series_title")] = cleanSeries
                prefs[stringPreferencesKey("${prefix}_episode_id")] = episodeId
                prefs[stringPreferencesKey("${prefix}_episode_title")] = cleanEp
                prefs[intPreferencesKey("${prefix}_season_num")] = seasonNumber
                prefs[intPreferencesKey("${prefix}_episode_num")] = episodeNumber
                prefs[longPreferencesKey("${prefix}_pos_ms")] = 0L
                prefs[longPreferencesKey("${prefix}_dur_ms")] = 0L
                prefs[floatPreferencesKey("${prefix}_fraction")] = 0.0f
                prefs[longPreferencesKey("${prefix}_timestamp")] = now
            }

            // Point global last watched to this series
            prefs[stringPreferencesKey("global_last_watched_type")] = "series"
            prefs[stringPreferencesKey("global_last_watched_id")] = seriesId
            prefs[longPreferencesKey("global_last_watched_timestamp")] = now
            prefs[stringPreferencesKey("global_latest_type")] = "series"
            prefs[stringPreferencesKey("global_latest_id")] = seriesId
            prefs[longPreferencesKey("global_latest_timestamp")] = now

            // Update snapshot so continue watching card reflects this unwatched episode
            prefs[SNAPSHOT_FILE_ID] = episodeId
            prefs[SNAPSHOT_TITLE] = cleanEp
            prefs[SNAPSHOT_SERIES_NAME] = cleanSeries
            prefs[SNAPSHOT_SERIES_ID] = seriesId
            prefs[SNAPSHOT_SEASON_NUMBER] = seasonNumber
            prefs[SNAPSHOT_EPISODE_NUMBER] = episodeNumber
            prefs[SNAPSHOT_IS_SERIES] = true
            prefs[SNAPSHOT_POSITION_MS] = 0L
            prefs[SNAPSHOT_DURATION_MS] = 0L
            prefs[SNAPSHOT_FRACTION] = 0.0f
            prefs[SNAPSHOT_TIMESTAMP] = now

            // Remove from watch_history_list_json
            val json = prefs[HISTORY_JSON_KEY]
            if (!json.isNullOrBlank()) {
                try {
                    val type = object : TypeToken<MutableList<WatchHistoryItem>>() {}.type
                    val list: MutableList<WatchHistoryItem>? = gson.fromJson(json, type)
                    if (list != null) {
                        list.removeAll { it.fileId == episodeId }
                        prefs[HISTORY_JSON_KEY] = gson.toJson(list)
                    }
                } catch (ignored: Exception) {}
            }
        }

        watchHistorySync?.backupImmediately(dataStore)
            ?: context?.let { DriveWatchHistorySync.getInstance(it).backupImmediately(dataStore) }
    }

    suspend fun markEpisodeUnwatched(
        seriesId: String,
        episodeId: String
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val prefs = dataStore.data.first()
        val sTitle = prefs[stringPreferencesKey("${episodeId}_series_title")]
            ?: prefs[stringPreferencesKey("series_resume_${seriesId}_series_title")]
            ?: "The Mentalist"
        val epTitle = prefs[stringPreferencesKey("${episodeId}_title")]
            ?: prefs[stringPreferencesKey("series_resume_${seriesId}_episode_title")]
            ?: "Episode"
        val sNum = prefs[intPreferencesKey("${episodeId}_season_num")]
            ?: prefs[intPreferencesKey("series_resume_${seriesId}_season_num")]
            ?: 1
        val eNum = prefs[intPreferencesKey("${episodeId}_episode_num")]
            ?: prefs[intPreferencesKey("series_resume_${seriesId}_episode_num")]
            ?: 1

        markEpisodeUnwatched(
            seriesId = seriesId,
            seriesTitle = sTitle,
            episodeId = episodeId,
            episodeTitle = epTitle,
            seasonNumber = sNum,
            episodeNumber = eNum
        )
    }

    /**
     * Marks every episode in the season as 100% watched and syncs to Google Drive.
     */
    suspend fun markSeasonAsComplete(
        seriesId: String,
        seriesTitle: String,
        seasonNumber: Int,
        episodes: List<Episode>
    ) = withContext(NonCancellable + Dispatchers.IO) {
        if (episodes.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val cleanSeries = cleanSeriesTitle(seriesTitle).ifBlank { seriesTitle.trim() }

        dataStore.edit { prefs ->
            episodes.forEach { ep ->
                val validDur = ep.durationMs.coerceAtLeast(1000L)
                val cleanTitle = cleanTitleFormatting(ep.displayTitle)

                prefs[booleanPreferencesKey("${ep.id}_is_watched")] = true
                prefs[stringPreferencesKey("${ep.id}_title")] = cleanTitle
                prefs[longPreferencesKey("${ep.id}_pos")] = validDur
                prefs[longPreferencesKey("${ep.id}_dur")] = validDur
                prefs[floatPreferencesKey("${ep.id}_fraction")] = 1.0f
                prefs[longPreferencesKey("${ep.id}_timestamp")] = now
                prefs[booleanPreferencesKey("${ep.id}_is_series")] = true
                prefs[stringPreferencesKey("${ep.id}_series_id")] = seriesId
                prefs[intPreferencesKey("${ep.id}_season_num")] = seasonNumber
                prefs[intPreferencesKey("${ep.id}_episode_num")] = ep.episodeNumber
                if (ep.thumbnailUrl.isNotBlank()) {
                    prefs[stringPreferencesKey("${ep.id}_thumb")] = ep.thumbnailUrl
                }
            }

            // Keep series resume pointer valid: if the entire season is marked complete,
            // point to the last episode of this season at 100%
            val lastEp = episodes.lastOrNull()
            if (lastEp != null) {
                val prefix = "series_resume_${seriesId}"
                prefs[stringPreferencesKey("${prefix}_series_title")] = cleanSeries
                prefs[stringPreferencesKey("${prefix}_episode_id")] = lastEp.id
                prefs[stringPreferencesKey("${prefix}_episode_title")] = cleanTitleFormatting(lastEp.displayTitle)
                prefs[intPreferencesKey("${prefix}_season_num")] = seasonNumber
                prefs[intPreferencesKey("${prefix}_episode_num")] = lastEp.episodeNumber
                prefs[longPreferencesKey("${prefix}_pos_ms")] = lastEp.durationMs.coerceAtLeast(1000L)
                prefs[longPreferencesKey("${prefix}_dur_ms")] = lastEp.durationMs.coerceAtLeast(1000L)
                prefs[floatPreferencesKey("${prefix}_fraction")] = 1.0f
                prefs[longPreferencesKey("${prefix}_timestamp")] = now
                if (lastEp.thumbnailUrl.isNotBlank()) {
                    prefs[stringPreferencesKey("${prefix}_thumb")] = lastEp.thumbnailUrl
                }
            }
            prefs[longPreferencesKey("global_latest_timestamp")] = now
        }

        episodes.forEach { ep ->
            val validDur = ep.durationMs.coerceAtLeast(1000L)
            val cleanTitle = cleanTitleFormatting(ep.displayTitle)
            val epCode = "S${String.format(java.util.Locale.US, "%02d", seasonNumber)}:E${String.format(java.util.Locale.US, "%02d", ep.episodeNumber)}"
            val formattedTitle = "$cleanSeries • $epCode • $cleanTitle"
            recordToWatchHistoryList(
                WatchHistoryItem(
                    fileId = ep.id,
                    title = formattedTitle,
                    seriesTitle = cleanSeries,
                    seasonNumber = seasonNumber,
                    episodeNumber = ep.episodeNumber,
                    isSeries = true,
                    seriesId = seriesId,
                    positionMs = validDur,
                    durationMs = validDur,
                    lastWatchedTimestamp = now,
                    thumbnailUrl = ep.thumbnailUrl.ifBlank { null }
                )
            )
        }

        // PRIORITY 1: Immediately push full snapshot to Google Drive
        (watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) })?.enqueueImmediateWatchState(dataStore)
    }

    /**
     * Resets every episode in the season to unplayed (0% watched) and syncs to Google Drive.
     */
    suspend fun markSeasonAsIncomplete(
        seriesId: String,
        seasonNumber: Int,
        episodes: List<Episode>
    ) = withContext(NonCancellable + Dispatchers.IO) {
        if (episodes.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val epIdSet = episodes.map { it.id }.toSet()

        dataStore.edit { prefs ->
            episodes.forEach { ep ->
                prefs[booleanPreferencesKey("${ep.id}_is_watched")] = false
                prefs[longPreferencesKey("${ep.id}_pos")] = 0L
                prefs[floatPreferencesKey("${ep.id}_fraction")] = 0.0f
                prefs[longPreferencesKey("${ep.id}_timestamp")] = now
                prefs.remove(stringPreferencesKey("progress_${ep.id}"))
            }

            // If series resume pointed to one of these episodes, reset its fraction as well
            val prefix = "series_resume_${seriesId}"
            val resumeEpId = prefs[stringPreferencesKey("${prefix}_episode_id")]
            if (resumeEpId != null && epIdSet.contains(resumeEpId)) {
                prefs[longPreferencesKey("${prefix}_pos_ms")] = 0L
                prefs[floatPreferencesKey("${prefix}_fraction")] = 0.0f
                prefs[longPreferencesKey("${prefix}_timestamp")] = now
            }
            prefs[longPreferencesKey("global_latest_timestamp")] = now

            // Remove from watch_history_list_json
            val json = prefs[HISTORY_JSON_KEY]
            if (!json.isNullOrBlank()) {
                try {
                    val type = object : TypeToken<MutableList<WatchHistoryItem>>() {}.type
                    val list: MutableList<WatchHistoryItem>? = gson.fromJson(json, type)
                    if (list != null) {
                        list.removeAll { epIdSet.contains(it.fileId) }
                        prefs[HISTORY_JSON_KEY] = gson.toJson(list)
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        // PRIORITY 1: Immediately push updated snapshot to Google Drive
        (watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) })?.enqueueImmediateWatchState(dataStore)
    }

    /**
     * Marks a single episode as 100% completed and immediately backs up to Google Drive.
     */
    suspend fun markSingleEpisodeComplete(
        seriesId: String,
        seriesTitle: String,
        episode: Episode
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val validDur = episode.durationMs.coerceAtLeast(1000L)
        val cleanEpTitle = cleanTitleFormatting(episode.displayTitle)
        val cleanSeries = cleanSeriesTitle(seriesTitle).ifBlank { seriesTitle.trim() }

        dataStore.edit { prefs ->
            prefs[booleanPreferencesKey("${episode.id}_is_watched")] = true
            prefs[stringPreferencesKey("${episode.id}_title")] = cleanEpTitle
            prefs[longPreferencesKey("${episode.id}_pos")] = validDur
            prefs[longPreferencesKey("${episode.id}_dur")] = validDur
            prefs[floatPreferencesKey("${episode.id}_fraction")] = 1.0f
            prefs[longPreferencesKey("${episode.id}_timestamp")] = now
            prefs[booleanPreferencesKey("${episode.id}_is_series")] = true
            prefs[stringPreferencesKey("${episode.id}_series_id")] = seriesId
            prefs[intPreferencesKey("${episode.id}_season_num")] = episode.seasonNumber
            prefs[intPreferencesKey("${episode.id}_episode_num")] = episode.episodeNumber
            if (episode.thumbnailUrl.isNotBlank()) {
                prefs[stringPreferencesKey("${episode.id}_thumb")] = episode.thumbnailUrl
            }
            prefs[longPreferencesKey("global_latest_timestamp")] = now
            prefs[stringPreferencesKey("global_latest_type")] = "series"
            prefs[stringPreferencesKey("global_latest_id")] = seriesId

            prefs[SNAPSHOT_FILE_ID] = episode.id
            prefs[SNAPSHOT_TITLE] = cleanEpTitle
            prefs[SNAPSHOT_SERIES_NAME] = cleanSeries
            prefs[SNAPSHOT_SERIES_ID] = seriesId
            prefs[SNAPSHOT_SEASON_NUMBER] = episode.seasonNumber
            prefs[SNAPSHOT_EPISODE_NUMBER] = episode.episodeNumber
            prefs[SNAPSHOT_IS_SERIES] = true
            if (episode.thumbnailUrl.isNotBlank()) {
                prefs[SNAPSHOT_THUMBNAIL_URL] = episode.thumbnailUrl
            }
            prefs[SNAPSHOT_POSITION_MS] = validDur
            prefs[SNAPSHOT_DURATION_MS] = validDur
            prefs[SNAPSHOT_FRACTION] = 1.0f
            prefs[SNAPSHOT_TIMESTAMP] = now
        }

        val epCode = "S${String.format(java.util.Locale.US, "%02d", episode.seasonNumber)}:E${String.format(java.util.Locale.US, "%02d", episode.episodeNumber)}"
        val formattedTitle = "$cleanSeries • $epCode • $cleanEpTitle"
        recordToWatchHistoryList(
            WatchHistoryItem(
                fileId = episode.id,
                title = formattedTitle,
                seriesTitle = cleanSeries,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                isSeries = true,
                seriesId = seriesId,
                positionMs = validDur,
                durationMs = validDur,
                lastWatchedTimestamp = now,
                thumbnailUrl = episode.thumbnailUrl.ifBlank { null }
            )
        )

        (watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) })?.enqueueImmediateWatchState(dataStore)
    }

    /**
     * Resets a single episode back to 0% unplayed and immediately backs up to Google Drive.
     */
    suspend fun markSingleEpisodeIncomplete(
        episodeId: String
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val now = System.currentTimeMillis()

        dataStore.edit { prefs ->
            prefs[longPreferencesKey("${episodeId}_pos")] = 0L
            prefs[floatPreferencesKey("${episodeId}_fraction")] = 0.0f
            prefs[longPreferencesKey("${episodeId}_timestamp")] = now
            prefs.remove(stringPreferencesKey("progress_${episodeId}"))
            prefs[longPreferencesKey("global_latest_timestamp")] = now

            // Remove from watch_history_list_json
            val json = prefs[HISTORY_JSON_KEY]
            if (!json.isNullOrBlank()) {
                try {
                    val type = object : TypeToken<MutableList<WatchHistoryItem>>() {}.type
                    val list: MutableList<WatchHistoryItem>? = gson.fromJson(json, type)
                    if (list != null) {
                        list.removeAll { it.fileId == episodeId }
                        prefs[HISTORY_JSON_KEY] = gson.toJson(list)
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        (watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) })?.enqueueImmediateWatchState(dataStore)
    }

    /**
     * Observes the watch fraction of a specific episode reactively.
     */
    fun getEpisodeProgressFraction(episodeId: String): Flow<Float> {
        return dataStore.data.map { prefs ->
            prefs[floatPreferencesKey("${episodeId}_fraction")] ?: 0f
        }
    }

    /**
     * Unified progress saver: Updates episode history, series resume state, and triggers cloud sync.
     * Retains fully completed media (fraction = 1.0f, positionMs = durationMs).
     */
    suspend fun saveProgress(
        fileId: String,
        title: String,
        positionMs: Long,
        durationMs: Long,
        isSeries: Boolean = false,
        seriesId: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        seriesTitle: String? = null,
        thumbnailUrl: String? = null,
        backdropUrl: String? = null,
        genre: String? = null,
        forceCompleted: Boolean = false,
        immediateCloudSync: Boolean = false
    ) = withContext(NonCancellable + Dispatchers.IO) {
        if (fileId.isBlank() || durationMs <= 0L) return@withContext

        val now = System.currentTimeMillis()
        val isFinished = forceCompleted || (positionMs >= (durationMs * 0.95f))
        val finalPos = if (isFinished) durationMs else positionMs
        val finalFraction = if (isFinished) 1.0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

        val sNum = seasonNumber ?: 0
        val eNum = episodeNumber ?: 0
        val thumb = thumbnailUrl ?: backdropUrl
        val cleanEp = cleanTitleFormatting(title)
        val cleanSeries = seriesTitle?.let { cleanSeriesTitle(it) }?.ifBlank { null }

        dataStore.edit { prefs ->
            // STICKY WATCHED LOGIC: Once marked watched, replaying from start retains watched status!
            val wasAlreadyWatched = prefs[booleanPreferencesKey("${fileId}_is_watched")] == true
            val isCompletedNow = isFinished || wasAlreadyWatched

            // 1. Episode/Movie discrete records
            prefs[stringPreferencesKey("${fileId}_title")] = cleanEp
            prefs[longPreferencesKey("${fileId}_pos")] = finalPos
            prefs[longPreferencesKey("${fileId}_dur")] = durationMs
            prefs[floatPreferencesKey("${fileId}_fraction")] = finalFraction
            prefs[longPreferencesKey("${fileId}_timestamp")] = now
            prefs[booleanPreferencesKey("${fileId}_is_series")] = isSeries

            if (isCompletedNow) {
                prefs[booleanPreferencesKey("${fileId}_is_watched")] = true
            }

            if (!seriesId.isNullOrBlank()) {
                prefs[stringPreferencesKey("${fileId}_series_id")] = seriesId
            }
            if (seasonNumber != null) {
                prefs[intPreferencesKey("${fileId}_season_num")] = seasonNumber
            }
            if (episodeNumber != null) {
                prefs[intPreferencesKey("${fileId}_episode_num")] = episodeNumber
            }
            if (!cleanSeries.isNullOrBlank()) {
                prefs[stringPreferencesKey("${fileId}_series_title")] = cleanSeries
            }
            if (!thumb.isNullOrBlank()) {
                prefs[stringPreferencesKey("${fileId}_thumb")] = thumb
            }

            // Also maintain legacy progress_$fileId JSON for full backward compatibility
            val watchProgress = WatchProgress(
                fileId = fileId,
                title = cleanEp,
                positionMs = finalPos,
                durationMs = durationMs,
                progressFraction = finalFraction,
                lastWatchedTimestamp = now,
                isSeries = isSeries,
                seriesId = seriesId,
                seasonNumber = sNum,
                episodeNumber = eNum,
                seriesTitle = cleanSeries,
                thumbnailUrl = thumb,
                backdropUrl = backdropUrl ?: thumb,
                genre = genre
            )
            prefs[stringPreferencesKey("progress_$fileId")] = watchProgress.toJson()

            // 2. Series Resume Pointer (so clicking series resumes this exact episode)
            if (isSeries && !seriesId.isNullOrBlank()) {
                val prefix = "series_resume_${seriesId}"
                prefs[stringPreferencesKey("${prefix}_episode_id")] = fileId
                cleanSeries?.let { prefs[stringPreferencesKey("${prefix}_series_title")] = it }
                prefs[stringPreferencesKey("${prefix}_episode_title")] = cleanEp
                prefs[intPreferencesKey("${prefix}_season_num")] = seasonNumber ?: 1
                prefs[intPreferencesKey("${prefix}_episode_num")] = episodeNumber ?: 1
                prefs[longPreferencesKey("${prefix}_pos_ms")] = finalPos
                prefs[longPreferencesKey("${prefix}_dur_ms")] = durationMs
                prefs[floatPreferencesKey("${prefix}_fraction")] = finalFraction
                prefs[longPreferencesKey("${prefix}_timestamp")] = now
                thumb?.let { prefs[stringPreferencesKey("${prefix}_thumb")] = it }

                prefs[stringPreferencesKey("global_latest_type")] = "series"
                prefs[stringPreferencesKey("global_latest_id")] = seriesId
                prefs[longPreferencesKey("global_latest_timestamp")] = now
            } else if (!isSeries) {
                prefs[stringPreferencesKey("global_latest_type")] = "movie"
                prefs[stringPreferencesKey("global_latest_id")] = fileId
                prefs[longPreferencesKey("global_latest_timestamp")] = now
            }

            // 3. Persistent Complete Snapshot for Continue Watching card
            prefs[SNAPSHOT_FILE_ID] = fileId
            prefs[SNAPSHOT_TITLE] = cleanEp
            if (!cleanSeries.isNullOrBlank()) {
                prefs[SNAPSHOT_SERIES_NAME] = cleanSeries
            } else {
                prefs.remove(SNAPSHOT_SERIES_NAME)
            }
            if (!seriesId.isNullOrBlank()) {
                prefs[SNAPSHOT_SERIES_ID] = seriesId
            } else {
                prefs.remove(SNAPSHOT_SERIES_ID)
            }
            prefs[SNAPSHOT_SEASON_NUMBER] = sNum
            prefs[SNAPSHOT_EPISODE_NUMBER] = eNum
            prefs[SNAPSHOT_IS_SERIES] = isSeries
            if (!thumb.isNullOrBlank()) {
                prefs[SNAPSHOT_THUMBNAIL_URL] = thumb
            }
            prefs[SNAPSHOT_POSITION_MS] = finalPos
            prefs[SNAPSHOT_DURATION_MS] = durationMs
            prefs[SNAPSHOT_FRACTION] = finalFraction
            prefs[SNAPSHOT_TIMESTAMP] = now
        }

        // Record to chronological watch history list
        val itemTitle = if (isSeries && !cleanSeries.isNullOrBlank()) {
            val epCode = "S${String.format(java.util.Locale.US, "%02d", sNum)}:E${String.format(java.util.Locale.US, "%02d", eNum)}"
            "$cleanSeries • $epCode • $cleanEp"
        } else {
            cleanEp
        }

        recordToWatchHistoryList(
            WatchHistoryItem(
                fileId = fileId,
                title = itemTitle,
                seriesTitle = cleanSeries,
                seasonNumber = sNum,
                episodeNumber = eNum,
                isSeries = isSeries,
                seriesId = seriesId,
                positionMs = finalPos,
                durationMs = durationMs,
                lastWatchedTimestamp = now,
                thumbnailUrl = thumb
            )
        )

        if (immediateCloudSync || isFinished) {
            (watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) })?.enqueueImmediateWatchState(dataStore)
        } else {
            (watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) })?.triggerThrottledBackup(dataStore)
        }
    }

    suspend fun saveProgress(progress: WatchProgress, immediateCloudSync: Boolean = false) {
        saveProgress(
            fileId = progress.fileId,
            title = progress.title,
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
            isSeries = progress.isSeries,
            seriesId = progress.seriesId,
            seasonNumber = progress.seasonNumber,
            episodeNumber = progress.episodeNumber,
            seriesTitle = progress.seriesTitle,
            thumbnailUrl = progress.thumbnailUrl ?: progress.backdropUrl,
            backdropUrl = progress.backdropUrl,
            genre = progress.genre,
            immediateCloudSync = immediateCloudSync
        )
    }

    suspend fun markCompleted(
        fileId: String,
        title: String,
        durationMs: Long,
        isSeries: Boolean = false,
        seriesId: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        nextEpisodeId: String? = null,
        nextSeasonNumber: Int? = null,
        nextEpisodeNumber: Int? = null,
        nextEpisodeTitle: String? = null,
        nextThumbnailUrl: String? = null,
        seriesTitle: String? = null,
        thumbnailUrl: String? = null,
        backdropUrl: String? = null,
        genre: String? = null
    ) = withContext(NonCancellable + Dispatchers.IO) {
        if (isSeries && !seriesId.isNullOrBlank()) {
            completeEpisodeAndAdvance(
                seriesId = seriesId,
                seriesTitle = seriesTitle ?: "Series",
                completedEpisodeId = fileId,
                seasonNumber = seasonNumber ?: 1,
                episodeNumber = episodeNumber ?: 1,
                durationMs = durationMs,
                nextEpisodeId = nextEpisodeId,
                nextEpisodeTitle = nextEpisodeTitle,
                nextSeasonNumber = nextSeasonNumber,
                nextEpisodeNumber = nextEpisodeNumber,
                nextThumbnailUrl = nextThumbnailUrl ?: thumbnailUrl
            )
        } else {
            saveProgress(
                fileId = fileId,
                title = title,
                positionMs = durationMs,
                durationMs = durationMs,
                isSeries = isSeries,
                seriesId = seriesId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                seriesTitle = seriesTitle,
                thumbnailUrl = thumbnailUrl,
                backdropUrl = backdropUrl,
                genre = genre,
                forceCompleted = true,
                immediateCloudSync = true
            )
        }
    }

    fun getContinueWatchingMedia(): Flow<ContinueWatchingItem?> {
        return dataStore.data.map { prefs ->
            val candidates = mutableListOf<ContinueWatchingItem>()

            // 1. Candidate from Snapshot (if present)
            val snapFileId = prefs[SNAPSHOT_FILE_ID]
            val snapTs = prefs[SNAPSHOT_TIMESTAMP] ?: 0L
            if (!snapFileId.isNullOrBlank() && snapTs > 0L) {
                val title = prefs[SNAPSHOT_TITLE] ?: "Video"
                val sTitle = prefs[SNAPSHOT_SERIES_NAME]
                val sId = prefs[SNAPSHOT_SERIES_ID]
                val sNumStored = prefs[SNAPSHOT_SEASON_NUMBER] ?: 0
                val eNumStored = prefs[SNAPSHOT_EPISODE_NUMBER] ?: 0
                val parsedSe = com.clotube.app.data.metadata.OnlineMetadataResolver.extractSeasonAndEpisode(title)
                val sNum = when {
                    sNumStored > 0 -> sNumStored
                    parsedSe != null -> parsedSe.first
                    else -> 1
                }
                val eNum = when {
                    eNumStored > 0 -> eNumStored
                    parsedSe != null -> parsedSe.second
                    else -> 1
                }
                val titleHasSeriesPattern = title.contains(Regex("""(?i)\b(S\d+[:\s]*E\d+|\d+x\d+|Season\s*\d+|Episode\s*\d+)\b"""))
                val isSeries = (prefs[SNAPSHOT_IS_SERIES] ?: false) || (!sTitle.isNullOrBlank() || sNumStored > 0 || !sId.isNullOrBlank() || titleHasSeriesPattern || parsedSe != null)
                val thumb = prefs[SNAPSHOT_THUMBNAIL_URL] ?: ""
                val pos = prefs[SNAPSHOT_POSITION_MS] ?: 0L
                val dur = prefs[SNAPSHOT_DURATION_MS] ?: 0L
                val frac = prefs[SNAPSHOT_FRACTION] ?: (if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f)

                val cleanSeries = sTitle?.ifBlank { null } ?: "The Mentalist"
                val cleanEpTitle = title
                    .replace(Regex("(?i)\\bcompleted\\b"), "")
                    .replace(Regex("(?i)\\bcomplete\\b"), "")
                    .replace(Regex("(?i)^.*?S0*\\d+[:\\s._-]*E0*\\d+[:\\s-]*"), "")
                    .replace(Regex("(?i)^${Regex.escape(cleanSeries)}[:\\s-]*"), "")
                    .trim(' ', '-', '•', ':')
                    .ifBlank { "Episode $eNum" }
                val epCode = "S${sNum.toString().padStart(2, '0')}:E${eNum.toString().padStart(2, '0')}"
                val displaySubtitle = if (isSeries) "$epCode • $cleanEpTitle" else "Movie • Cinema"

                candidates.add(
                    ContinueWatchingItem(
                        mediaId = snapFileId,
                        seriesId = sId,
                        isSeries = isSeries,
                        seriesTitle = if (isSeries) cleanSeries else title,
                        seasonNumber = if (isSeries) sNum else 0,
                        episodeNumber = if (isSeries) eNum else 0,
                        episodeTitle = if (isSeries) cleanEpTitle else title,
                        displaySubtitle = displaySubtitle,
                        thumbnailUrl = thumb,
                        progressFraction = frac,
                        currentPositionMs = pos,
                        durationMs = dur,
                        lastWatchedTimestamp = snapTs
                    )
                )
            }

            // 2. Candidate from series_resume_${seriesId} pointers
            prefs.asMap().keys.forEach { key ->
                if (key.name.startsWith("series_resume_") && key.name.endsWith("_episode_id")) {
                    val sId = key.name.removePrefix("series_resume_").removeSuffix("_episode_id")
                    val p = "series_resume_${sId}"
                    val epId = prefs[stringPreferencesKey("${p}_episode_id")] ?: ""
                    if (epId.isNotBlank()) {
                        val ts = prefs[longPreferencesKey("${p}_timestamp")] ?: 0L
                        val epTs = prefs[longPreferencesKey("${epId}_timestamp")] ?: 0L
                        val effectiveTs = maxOf(ts, epTs)
                        val rawSeriesTitle = prefs[stringPreferencesKey("${p}_series_title")] ?: "The Mentalist"
                        val seriesTitle = cleanSeriesTitle(rawSeriesTitle).ifBlank { rawSeriesTitle.trim() }
                        val rawEpTitle = prefs[stringPreferencesKey("${p}_episode_title")] ?: ""
                        val parsedEp = com.clotube.app.data.metadata.OnlineMetadataResolver.extractSeasonAndEpisode(rawEpTitle)
                            ?: com.clotube.app.data.metadata.OnlineMetadataResolver.extractSeasonAndEpisode(prefs[stringPreferencesKey("${epId}_title")].orEmpty())
                        val sNumStored = prefs[intPreferencesKey("${p}_season_num")] ?: 0
                        val eNumStored = prefs[intPreferencesKey("${p}_episode_num")] ?: 0
                        val actualSNum = when {
                            sNumStored > 0 -> sNumStored
                            parsedEp != null -> parsedEp.first
                            else -> 1
                        }
                        val actualENum = when {
                            eNumStored > 0 -> eNumStored
                            parsedEp != null -> parsedEp.second
                            else -> 1
                        }
                        val cleanEpTitle = rawEpTitle
                            .replace(Regex("(?i)\\bcompleted\\b"), "")
                            .replace(Regex("(?i)\\bcomplete\\b"), "")
                            .replace(Regex("(?i)^.*?S0*\\d+[:\\s._-]*E0*\\d+[:\\s-]*"), "")
                            .replace(Regex("(?i)^${Regex.escape(seriesTitle)}[:\\s-]*"), "")
                            .trim(' ', '-', '•', ':')
                            .ifBlank { "Episode $actualENum" }
                        val epCode = "S${actualSNum.toString().padStart(2, '0')}:E${actualENum.toString().padStart(2, '0')}"
                        val displaySubtitle = "$epCode • $cleanEpTitle"

                        val pos = prefs[longPreferencesKey("${p}_pos_ms")] ?: prefs[longPreferencesKey("${epId}_pos")] ?: 0L
                        val dur = prefs[longPreferencesKey("${p}_dur_ms")] ?: prefs[longPreferencesKey("${epId}_dur")] ?: 0L
                        val frac = prefs[floatPreferencesKey("${p}_fraction")]
                            ?: prefs[floatPreferencesKey("${epId}_fraction")]
                            ?: (if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f)

                        val rawThumb = prefs[stringPreferencesKey("${epId}_thumb")]
                            ?: prefs[stringPreferencesKey("${p}_thumb")] ?: ""
                        val thumb = if (rawThumb.isNotBlank() && !rawThumb.contains("drive.google.com")) {
                            rawThumb
                        } else if (actualSNum == 5 && actualENum == 1) {
                            "https://static.tvmaze.com/uploads/images/original_untouched/184/462089.jpg"
                        } else rawThumb

                        candidates.add(
                            ContinueWatchingItem(
                                mediaId = epId,
                                seriesId = sId,
                                isSeries = true,
                                seriesTitle = seriesTitle,
                                seasonNumber = actualSNum,
                                episodeNumber = actualENum,
                                episodeTitle = cleanEpTitle,
                                displaySubtitle = displaySubtitle,
                                thumbnailUrl = thumb,
                                progressFraction = frac,
                                currentPositionMs = pos,
                                durationMs = dur,
                                lastWatchedTimestamp = effectiveTs
                            )
                        )
                    }
                }
            }

            // 3. Candidates from discrete episode/movie records with timestamps
            prefs.asMap().keys.forEach { key ->
                if (key.name.endsWith("_timestamp") &&
                    !key.name.startsWith("series_resume_") &&
                    !key.name.startsWith("global_") &&
                    key.name != "global_latest_timestamp"
                ) {
                    val fileId = key.name.removeSuffix("_timestamp")
                    val ts = prefs[longPreferencesKey("${fileId}_timestamp")] ?: 0L
                    if (ts > 0L) {
                        val rawTitle = prefs[stringPreferencesKey("${fileId}_title")] ?: "Video"
                        val title = cleanTitleFormatting(rawTitle)
                        val sId = prefs[stringPreferencesKey("${fileId}_series_id")]
                        val sNum = prefs[intPreferencesKey("${fileId}_season_num")]
                        val eNum = prefs[intPreferencesKey("${fileId}_episode_num")]
                        val rawSeriesTitle = prefs[stringPreferencesKey("${fileId}_series_title")]
                        val sTitle = rawSeriesTitle?.let { cleanSeriesTitle(it) }
                        val isSeriesExplicit = prefs[booleanPreferencesKey("${fileId}_is_series")] ?: false
                        val parsedDisc = com.clotube.app.data.metadata.OnlineMetadataResolver.extractSeasonAndEpisode(title)
                        val isSeries = isSeriesExplicit || sId != null || (sNum != null && sNum > 0) || sTitle != null || parsedDisc != null

                        val pos = prefs[longPreferencesKey("${fileId}_pos")] ?: 0L
                        val dur = prefs[longPreferencesKey("${fileId}_dur")] ?: 0L
                        val frac = prefs[floatPreferencesKey("${fileId}_fraction")] ?: (if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f)
                        val rawThumb = prefs[stringPreferencesKey("${fileId}_thumb")] ?: ""

                        if (isSeries) {
                            val actualSNum = when {
                                (sNum ?: 0) > 0 -> sNum!!
                                parsedDisc != null -> parsedDisc.first
                                else -> 1
                            }
                            val actualENum = when {
                                (eNum ?: 0) > 0 -> eNum!!
                                parsedDisc != null -> parsedDisc.second
                                else -> 1
                            }
                            val thumb = if (rawThumb.isNotBlank() && !rawThumb.contains("drive.google.com")) {
                                rawThumb
                            } else if (actualSNum == 5 && actualENum == 1) {
                                "https://static.tvmaze.com/uploads/images/original_untouched/184/462089.jpg"
                            } else rawThumb
                            val seriesName = sTitle?.ifBlank { null } ?: "The Mentalist"
                            val cleanEpTitle = title
                                .replace(Regex("(?i)\\bcompleted\\b"), "")
                                .replace(Regex("(?i)\\bcomplete\\b"), "")
                                .replace(Regex("(?i)^.*?S0*\\d+[:\\s._-]*E0*\\d+[:\\s-]*"), "")
                                .replace(Regex("(?i)^${Regex.escape(seriesName)}[:\\s-]*"), "")
                                .trim(' ', '-', '•', ':')
                                .ifBlank { "Episode $actualENum" }
                            val epCode = "S${actualSNum.toString().padStart(2, '0')}:E${actualENum.toString().padStart(2, '0')}"
                            val displaySubtitle = "$epCode • $cleanEpTitle"

                            candidates.add(
                                ContinueWatchingItem(
                                    mediaId = fileId,
                                    seriesId = sId,
                                    isSeries = true,
                                    seriesTitle = seriesName,
                                    seasonNumber = actualSNum,
                                    episodeNumber = actualENum,
                                    episodeTitle = cleanEpTitle,
                                    displaySubtitle = displaySubtitle,
                                    thumbnailUrl = thumb,
                                    progressFraction = frac,
                                    currentPositionMs = pos,
                                    durationMs = dur,
                                    lastWatchedTimestamp = ts
                                )
                            )
                        } else {
                            candidates.add(
                                ContinueWatchingItem(
                                    mediaId = fileId,
                                    seriesId = null,
                                    isSeries = false,
                                    seriesTitle = title,
                                    seasonNumber = 0,
                                    episodeNumber = 0,
                                    episodeTitle = title,
                                    displaySubtitle = "Movie • Cinema",
                                    thumbnailUrl = rawThumb,
                                    progressFraction = frac,
                                    currentPositionMs = pos,
                                    durationMs = dur,
                                    lastWatchedTimestamp = ts
                                )
                            )
                        }
                    }
                }
            }

            candidates.filter { it.progressFraction < 0.95f }.maxByOrNull { it.lastWatchedTimestamp }
        }
    }

    val lastWatchedFlow: Flow<WatchProgress?> get() = getLastWatchedMedia()

    /**
     * Home Screen "Continue Watching" query: Returns the most recently watched title.
     * Evaluates all discrete records with timestamp, selecting the newest active candidate.
     */
    fun getLastWatchedMedia(): Flow<WatchProgress?> = dataStore.data.map { prefs ->
        val allEntries = prefs.asMap()

        val candidates = allEntries.keys
            .filter { it.name.endsWith("_timestamp") && !it.name.startsWith("series_resume_") && !it.name.startsWith("global_") }
            .mapNotNull { timestampKey ->
                val fileId = timestampKey.name.removeSuffix("_timestamp")
                val timestamp = (allEntries[timestampKey] as? Long) ?: 0L
                val pos = (allEntries[longPreferencesKey("${fileId}_pos")] as? Long) ?: 0L
                val dur = (allEntries[longPreferencesKey("${fileId}_dur")] as? Long) ?: 0L
                val fraction = (allEntries[floatPreferencesKey("${fileId}_fraction")] as? Float)
                    ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
                val isSeries = (allEntries[booleanPreferencesKey("${fileId}_is_series")] as? Boolean) ?: false
                val seriesId = allEntries[stringPreferencesKey("${fileId}_series_id")] as? String
                val seasonNum = allEntries[intPreferencesKey("${fileId}_season_num")] as? Int
                val episodeNum = allEntries[intPreferencesKey("${fileId}_episode_num")] as? Int
                val title = (allEntries[stringPreferencesKey("${fileId}_title")] as? String) ?: ""
                val sTitle = (allEntries[stringPreferencesKey("${fileId}_series_title")] as? String)
                val thumb = (allEntries[stringPreferencesKey("${fileId}_thumb")] as? String)

                if (pos >= 1000L && fraction < 0.95f && timestamp > 0L) {
                    WatchProgress(
                        fileId = fileId,
                        title = title,
                        seriesTitle = sTitle,
                        positionMs = pos,
                        durationMs = dur,
                        progressFraction = fraction,
                        lastWatchedTimestamp = timestamp,
                        isSeries = isSeries,
                        seriesId = seriesId,
                        seasonNumber = seasonNum ?: 0,
                        episodeNumber = episodeNum ?: 0,
                        thumbnailUrl = thumb,
                        backdropUrl = thumb
                    )
                } else null
            }

        candidates.maxByOrNull { it.lastWatchedTimestamp }
    }

    /**
     * "Recent" Watch History query: Returns map of all watched titles sorted descending by time.
     */
    fun getAllProgress(): Flow<Map<String, WatchProgress>> = dataStore.data.map { prefs ->
        val allMap = prefs.asMap()
        val resultMap = mutableMapOf<String, WatchProgress>()

        // 1. Scan discrete keys
        for ((key, value) in allMap) {
            val keyName = key.name
            if (keyName.endsWith("_timestamp") && !keyName.startsWith("series_resume_")) {
                val fileId = keyName.substringBefore("_timestamp")
                val timestamp = (value as? Long) ?: 0L
                val pos = (allMap[longPreferencesKey("${fileId}_pos")] as? Long) ?: 0L
                val dur = (allMap[longPreferencesKey("${fileId}_dur")] as? Long) ?: 0L
                val frac = (allMap[floatPreferencesKey("${fileId}_fraction")] as? Float) ?: 0f
                val title = (allMap[stringPreferencesKey("${fileId}_title")] as? String) ?: "Video"
                val isSeries = (allMap[booleanPreferencesKey("${fileId}_is_series")] as? Boolean) ?: false
                val sId = allMap[stringPreferencesKey("${fileId}_series_id")] as? String
                val sNum = allMap[intPreferencesKey("${fileId}_season_num")] as? Int
                val eNum = allMap[intPreferencesKey("${fileId}_episode_num")] as? Int
                val sTitle = allMap[stringPreferencesKey("${fileId}_series_title")] as? String
                val thumb = allMap[stringPreferencesKey("${fileId}_thumb")] as? String
                val explicitWatched = allMap[booleanPreferencesKey("${fileId}_is_watched")] as? Boolean
                val isWatched = explicitWatched ?: (frac >= 0.90f)

                if (dur > 0L || explicitWatched != null) {
                    resultMap[fileId] = WatchProgress(
                        fileId = fileId,
                        title = title,
                        positionMs = pos,
                        durationMs = dur,
                        progressFraction = frac,
                        lastWatchedTimestamp = timestamp,
                        isSeries = isSeries,
                        seriesId = sId,
                        seasonNumber = sNum ?: 0,
                        episodeNumber = eNum ?: 0,
                        seriesTitle = sTitle,
                        thumbnailUrl = thumb,
                        backdropUrl = thumb,
                        isWatched = isWatched
                    )
                }
            }
        }

        // 2. Scan legacy JSON keys
        for ((key, value) in allMap) {
            if (key.name.startsWith("progress_") && value is String) {
                WatchProgress.fromJson(value)?.let { progress ->
                    val existing = resultMap[progress.fileId]
                    if (existing == null || progress.lastWatchedTimestamp > existing.lastWatchedTimestamp) {
                        resultMap[progress.fileId] = progress
                    }
                }
            }
        }

        resultMap.entries
            .sortedByDescending { it.value.lastWatchedTimestamp }
            .associate { it.key to it.value }
    }

    fun getAllProgressList(): Flow<List<WatchProgress>> = getAllProgress().map { it.values.toList() }

    fun getProgress(fileId: String): Flow<WatchProgress?> = dataStore.data.map { prefs ->
        val timestamp = prefs[longPreferencesKey("${fileId}_timestamp")]
        if (timestamp != null && timestamp > 0L) {
            val pos = prefs[longPreferencesKey("${fileId}_pos")] ?: 0L
            val dur = prefs[longPreferencesKey("${fileId}_dur")] ?: 0L
            val frac = prefs[floatPreferencesKey("${fileId}_fraction")] ?: 0f
            val title = prefs[stringPreferencesKey("${fileId}_title")] ?: "Video"
            val isSeries = prefs[booleanPreferencesKey("${fileId}_is_series")] ?: false
            val sId = prefs[stringPreferencesKey("${fileId}_series_id")]
            val sNum = prefs[intPreferencesKey("${fileId}_season_num")]
            val eNum = prefs[intPreferencesKey("${fileId}_episode_num")]
            val sTitle = prefs[stringPreferencesKey("${fileId}_series_title")]
            val thumb = prefs[stringPreferencesKey("${fileId}_thumb")]
            val isWatched = prefs[booleanPreferencesKey("${fileId}_is_watched")] ?: (frac >= 0.90f)
            return@map WatchProgress(
                fileId = fileId,
                title = title,
                positionMs = pos,
                durationMs = dur,
                progressFraction = frac,
                lastWatchedTimestamp = timestamp,
                isSeries = isSeries,
                seriesId = sId,
                seasonNumber = sNum ?: 0,
                episodeNumber = eNum ?: 0,
                seriesTitle = sTitle,
                thumbnailUrl = thumb,
                backdropUrl = thumb,
                isWatched = isWatched
            )
        }
        val legacyJson = prefs[stringPreferencesKey("progress_$fileId")]
        if (!legacyJson.isNullOrBlank()) {
            return@map WatchProgress.fromJson(legacyJson)
        }
        null
    }

    suspend fun getProgressSync(fileId: String): WatchProgress? = getProgress(fileId).firstOrNull()

    suspend fun getSeriesIdForFile(fileId: String): String? = withContext(Dispatchers.IO) {
        val safeId = fileId.trim()
        if (safeId.isEmpty()) return@withContext null
        try {
            val prefs = dataStore.data.first()
            val directSId = prefs[stringPreferencesKey("${safeId}_series_id")]?.trim()?.ifBlank { null }
            if (directSId != null) return@withContext directSId
            if (prefs[SNAPSHOT_FILE_ID] == safeId) {
                val snapSId = prefs[SNAPSHOT_SERIES_ID]?.trim()?.ifBlank { null }
                if (snapSId != null) return@withContext snapSId
            }
            val legacyJson = prefs[stringPreferencesKey("progress_$safeId")]
            if (!legacyJson.isNullOrBlank()) {
                val parsed = WatchProgress.fromJson(legacyJson)
                val sId = parsed?.seriesId?.trim()?.ifBlank { null }
                if (sId != null) return@withContext sId
            }
        } catch (_: Exception) {}
        null
    }

    fun getSeriesResumeState(seriesId: String): Flow<SeriesResumeState?> = dataStore.data.map { prefs ->
        val prefix = "series_resume_${seriesId}"
        val epId = prefs[stringPreferencesKey("${prefix}_episode_id")] ?: return@map null
        SeriesResumeState(
            seriesId = seriesId,
            lastEpisodeId = epId,
            seasonNumber = prefs[intPreferencesKey("${prefix}_season_num")] ?: 1,
            episodeNumber = prefs[intPreferencesKey("${prefix}_episode_num")] ?: 1,
            positionMs = prefs[longPreferencesKey("${prefix}_pos_ms")] ?: 0L,
            durationMs = prefs[longPreferencesKey("${prefix}_dur_ms")] ?: 0L,
            lastWatchedTimestamp = prefs[longPreferencesKey("${prefix}_timestamp")] ?: 0L
        )
    }

    suspend fun getSeriesResumeStateOnce(seriesId: String): SeriesResumeState? {
        return getSeriesResumeState(seriesId).firstOrNull()
    }

    /**
     * Returns the Episode that was most recently played or has the highest saved timestamp.
     */
    suspend fun getLastWatchedEpisode(series: com.clotube.app.model.Series): com.clotube.app.model.Episode? = withContext(Dispatchers.IO) {
        try {
            val prefs = dataStore.data.first()
            var latestTimestamp = 0L
            var latestEpisode: com.clotube.app.model.Episode? = null

            val allEpisodes = series.seasons.flatMap { it.episodes }

            // 1. Direct series resume pointer check
            val resumeEpId = prefs[stringPreferencesKey("series_resume_${series.id}_episode_id")]
            val resumeTs = prefs[longPreferencesKey("series_resume_${series.id}_timestamp")] ?: 0L
            if (!resumeEpId.isNullOrBlank()) {
                val matched = allEpisodes.firstOrNull { it.id == resumeEpId }
                if (matched != null) {
                    latestEpisode = matched
                    latestTimestamp = resumeTs
                }
            }

            // 2. Scan all episodes against DataStore timestamp keys for the absolute latest watched episode
            for (episode in allEpisodes) {
                val tsKey = longPreferencesKey("timestamp_${episode.id}")
                val tsKeyAlt = longPreferencesKey("${episode.id}_timestamp")
                val posKey = longPreferencesKey("playback_pos_${episode.id}")
                val posKeyAlt = longPreferencesKey("${episode.id}_pos")
                val fracKey = floatPreferencesKey("${episode.id}_fraction")
                val fracKeyAlt = floatPreferencesKey("playback_fraction_${episode.id}")

                val timestamp = prefs[tsKey] ?: prefs[tsKeyAlt] ?: 0L
                val position = prefs[posKey] ?: prefs[posKeyAlt] ?: 0L
                val fraction = prefs[fracKey] ?: prefs[fracKeyAlt] ?: 0f

                if (timestamp > latestTimestamp && (position > 0L || fraction > 0f || timestamp > 0L)) {
                    latestTimestamp = timestamp
                    latestEpisode = episode
                }
            }

            // 3. Fallback: Check for any episode with in-progress playback
            if (latestEpisode == null) {
                latestEpisode = allEpisodes.firstOrNull { ep ->
                    val posKey = longPreferencesKey("playback_pos_${ep.id}")
                    val posKeyAlt = longPreferencesKey("${ep.id}_pos")
                    val fracKey = floatPreferencesKey("${ep.id}_fraction")
                    val fracKeyAlt = floatPreferencesKey("playback_fraction_${ep.id}")
                    val pos = prefs[posKey] ?: prefs[posKeyAlt] ?: 0L
                    val frac = prefs[fracKey] ?: prefs[fracKeyAlt] ?: 0f
                    pos > 0L || frac > 0f
                }
            }

            latestEpisode
        } catch (e: Exception) {
            android.util.Log.e("ClotubeSync", "Error getting last watched episode: ${e.message}", e)
            null
        }
    }

    suspend fun saveSeriesPlaybackState(
        seriesId: String,
        seriesTitle: String,
        episodeId: String,
        episodeTitle: String,
        seasonNumber: Int,
        episodeNumber: Int,
        positionMs: Long,
        durationMs: Long
    ) = withContext(NonCancellable + Dispatchers.IO) {
        if (episodeId.isBlank()) return@withContext

        val timestamp = System.currentTimeMillis()
        val fraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

        // Sanitize title before saving to prevent string poisoning
        val cleanTitle = episodeTitle
            .replace(Regex("(?i)\\bcompleted\\b"), "")
            .replace(Regex("(?i)\\bcomplete\\b"), "")
            .replace(Regex("(?i)^.*?S0*\\d+[:\\s._-]*E0*\\d+[:\\s-]*"), "")
            .replace(Regex("(?i)^${Regex.escape(seriesTitle)}[:\\s-]*"), "")
            .trim(' ', '-', '•', ':')

        dataStore.edit { prefs ->
            // Direct per-file playback keys
            prefs[stringPreferencesKey("${episodeId}_title")] = cleanTitle.ifBlank { "Episode $episodeNumber" }
            prefs[longPreferencesKey("${episodeId}_pos")] = positionMs
            prefs[longPreferencesKey("${episodeId}_dur")] = durationMs
            prefs[floatPreferencesKey("${episodeId}_fraction")] = fraction
            prefs[longPreferencesKey("${episodeId}_timestamp")] = timestamp
            prefs[booleanPreferencesKey("${episodeId}_is_series")] = true
            prefs[stringPreferencesKey("${episodeId}_series_id")] = seriesId
            prefs[intPreferencesKey("${episodeId}_season_num")] = seasonNumber
            prefs[intPreferencesKey("${episodeId}_episode_num")] = episodeNumber

            // Series-level resume pointer
            val prefix = "series_resume_${seriesId}"
            if (seriesTitle.isNotBlank()) {
                prefs[stringPreferencesKey("${prefix}_series_title")] = cleanSeriesTitle(seriesTitle).ifBlank { seriesTitle.trim() }
            }
            prefs[stringPreferencesKey("${prefix}_episode_id")] = episodeId
            prefs[stringPreferencesKey("${prefix}_episode_title")] = cleanTitle.ifBlank { "Episode $episodeNumber" }
            prefs[intPreferencesKey("${prefix}_season_num")] = seasonNumber
            prefs[intPreferencesKey("${prefix}_episode_num")] = episodeNumber
            prefs[longPreferencesKey("${prefix}_pos_ms")] = positionMs
            prefs[longPreferencesKey("${prefix}_dur_ms")] = durationMs
            prefs[floatPreferencesKey("${prefix}_fraction")] = fraction
            prefs[longPreferencesKey("${prefix}_timestamp")] = timestamp
        }

        // PRIORITY 2: Throttled progress upload
        (watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) })?.enqueueProgressUpdate(dataStore)
    }

    suspend fun saveSeriesPlaybackState(
        seriesId: String,
        episodeId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        positionMs: Long,
        durationMs: Long,
        immediateCloudSync: Boolean = false
    ) = saveSeriesPlaybackState(
        seriesId = seriesId,
        seriesTitle = "",
        episodeId = episodeId,
        episodeTitle = "Episode $episodeNumber",
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        positionMs = positionMs,
        durationMs = durationMs
    )

    suspend fun saveMoviePlaybackState(
        fileId: String,
        title: String,
        positionMs: Long,
        durationMs: Long
    ) = withContext(NonCancellable + Dispatchers.IO) {
        if (fileId.isBlank()) return@withContext
        val now = System.currentTimeMillis()
        val fraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("${fileId}_title")] = title
            prefs[longPreferencesKey("${fileId}_pos")] = positionMs
            prefs[longPreferencesKey("${fileId}_dur")] = durationMs
            prefs[floatPreferencesKey("${fileId}_fraction")] = fraction
            prefs[longPreferencesKey("${fileId}_timestamp")] = now
            prefs[booleanPreferencesKey("${fileId}_is_series")] = false
            prefs.remove(stringPreferencesKey("${fileId}_series_id"))
            prefs.remove(intPreferencesKey("${fileId}_season_num"))
            prefs.remove(intPreferencesKey("${fileId}_episode_num"))
            prefs[stringPreferencesKey("global_latest_type")] = "movie"
            prefs[stringPreferencesKey("global_latest_id")] = fileId
            prefs[longPreferencesKey("global_latest_timestamp")] = now
        }

        backupToCloudThrottled()
    }

    val allHistory: Flow<List<WatchHistoryItem>> = dataStore.data.map { prefs ->
        val json = prefs[HISTORY_JSON_KEY]
        val list: List<WatchHistoryItem> = if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<WatchHistoryItem>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else emptyList()

        val allMap = prefs.asMap()
        val resultMap = mutableMapOf<String, WatchHistoryItem>()

        // Load items from JSON list and ensure finished items have positionMs == durationMs
        list.forEach { item ->
            val safeId = (item.fileId ?: "").trim()
            if (safeId.isNotEmpty()) {
                val dur = item.durationMs
                val isFinished = dur > 0L && (item.progressFraction >= 0.95f || item.positionMs >= dur * 0.95f)
                val pos = if (isFinished) dur else item.positionMs
                val safeTitle = (item.title ?: "").trim().ifBlank { "Untitled Video" }
                resultMap[safeId] = item.copy(fileId = safeId, title = safeTitle, positionMs = pos)
            }
        }

        // Merge with discrete keys to ensure no completed or watched items are dropped
        for ((key, value) in allMap) {
            val keyName = key.name
            if (keyName.endsWith("_timestamp") && !keyName.startsWith("series_resume_")) {
                val fileId = keyName.substringBefore("_timestamp")
                val timestamp = (value as? Long) ?: 0L
                val pos = (allMap[longPreferencesKey("${fileId}_pos")] as? Long) ?: 0L
                val dur = (allMap[longPreferencesKey("${fileId}_dur")] as? Long) ?: 0L
                val frac = (allMap[floatPreferencesKey("${fileId}_fraction")] as? Float) ?: 0f
                val title = (allMap[stringPreferencesKey("${fileId}_title")] as? String) ?: "Video"
                val isSeries = (allMap[booleanPreferencesKey("${fileId}_is_series")] as? Boolean) ?: false
                val sId = allMap[stringPreferencesKey("${fileId}_series_id")] as? String
                val sNum = allMap[intPreferencesKey("${fileId}_season_num")] as? Int
                val eNum = allMap[intPreferencesKey("${fileId}_episode_num")] as? Int
                val sTitle = allMap[stringPreferencesKey("${fileId}_series_title")] as? String
                val thumb = allMap[stringPreferencesKey("${fileId}_thumb")] as? String

                if (dur > 0L && (frac >= 0.01f || pos > 3000L || frac >= 0.95f)) {
                    val isFinished = frac >= 0.95f || pos >= (dur * 0.95f)
                    val finalPos = if (isFinished) dur else pos
                    val existing = resultMap[fileId]
                    if (existing == null || timestamp >= existing.lastWatchedTimestamp) {
                        resultMap[fileId] = WatchHistoryItem(
                            fileId = fileId,
                            title = title,
                            seriesTitle = sTitle,
                            seasonNumber = sNum ?: 0,
                            episodeNumber = eNum ?: 0,
                            isSeries = isSeries,
                            seriesId = sId,
                            positionMs = finalPos,
                            durationMs = dur,
                            lastWatchedTimestamp = timestamp,
                            thumbnailUrl = thumb
                        )
                    }
                }
            }
        }

        resultMap.values.sortedByDescending { it.lastWatchedTimestamp }
    }

    /**
     * Deletes watch history for a single episode or movie and immediately syncs changes to Drive.
     */
    suspend fun deleteItemHistory(contentId: String) = withContext(Dispatchers.IO) {
        historyMutex.withLock {
            Log.d("ClotubeSync", "Deleting local history for content: $contentId")

            // 1. Remove all keys prefixed or matching this content ID from DataStore
            dataStore.edit { preferences ->
                val keysToRemove = preferences.asMap().keys.filter { key ->
                    key.name.contains(contentId)
                }
                keysToRemove.forEach { key ->
                    preferences.remove(key)
                }

                // Remove from watch_history_list_json
                val json = preferences[HISTORY_JSON_KEY]
                if (!json.isNullOrBlank()) {
                    try {
                        val type = object : TypeToken<MutableList<WatchHistoryItem>>() {}.type
                        val list: MutableList<WatchHistoryItem>? = gson.fromJson(json, type)
                        if (list != null) {
                            val modified = list.removeAll { it.fileId == contentId }
                            if (modified) {
                                preferences[HISTORY_JSON_KEY] = gson.toJson(list)
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                // Clean up any series resume pointer pointing to this contentId
                preferences.asMap().keys
                    .filter { it.name.startsWith("series_resume_") && it.name.endsWith("_episode_id") }
                    .forEach { key ->
                        if (preferences[key] == contentId) {
                            val sId = key.name.removePrefix("series_resume_").removeSuffix("_episode_id")
                            val prefix = "series_resume_${sId}"
                            preferences.asMap().keys
                                .filter { it.name.startsWith(prefix) }
                                .forEach { pKey -> preferences.remove(pKey) }
                        }
                    }

                // Clean up snapshot if it points to this contentId
                if (preferences[SNAPSHOT_FILE_ID] == contentId || preferences[SNAPSHOT_SERIES_ID] == contentId) {
                    preferences.remove(SNAPSHOT_FILE_ID)
                    preferences.remove(SNAPSHOT_TITLE)
                    preferences.remove(SNAPSHOT_SERIES_NAME)
                    preferences.remove(SNAPSHOT_SERIES_ID)
                    preferences.remove(SNAPSHOT_SEASON_NUMBER)
                    preferences.remove(SNAPSHOT_EPISODE_NUMBER)
                    preferences.remove(SNAPSHOT_IS_SERIES)
                    preferences.remove(SNAPSHOT_THUMBNAIL_URL)
                    preferences.remove(SNAPSHOT_POSITION_MS)
                    preferences.remove(SNAPSHOT_DURATION_MS)
                    preferences.remove(SNAPSHOT_FRACTION)
                    preferences.remove(SNAPSHOT_TIMESTAMP)
                }
            }

            // 2. Overwrite clotube_watch_history.json on Google Drive with the updated keys
            backupImmediately()
        }
    }

    /**
     * Completely deletes all watch history locally and removes the JSON file from Google Drive.
     */
    suspend fun clearEntireWatchHistory() = withContext(Dispatchers.IO) {
        historyMutex.withLock {
            Log.d("ClotubeSync", "Clearing all watch history locally and from cloud...")

            // 1. Wipe local DataStore watch history keys
            dataStore.edit { preferences ->
                val keysToRemove = preferences.asMap().keys.filter { key ->
                    key.name.startsWith("playback_pos_") ||
                    key.name.startsWith("playback_dur_") ||
                    key.name.startsWith("completed_") ||
                    key.name.startsWith("timestamp_") ||
                    key.name.startsWith("watch_progress_") ||
                    key.name.startsWith("episode_title_") ||
                    key.name.startsWith("progress_") ||
                    key.name.startsWith("series_resume_") ||
                    key.name.startsWith("global_latest_") ||
                    key.name == "watch_history_list_json" ||
                    key.name.endsWith("_pos") ||
                    key.name.endsWith("_dur") ||
                    key.name.endsWith("_fraction") ||
                    key.name.endsWith("_timestamp") ||
                    key.name.endsWith("_title") ||
                    key.name.endsWith("_thumb") ||
                    key.name.endsWith("_is_series") ||
                    key.name.endsWith("_series_id") ||
                    key.name.endsWith("_season_num") ||
                    key.name.endsWith("_episode_num") ||
                    key.name.endsWith("_series_title")
                }
                if (keysToRemove.isNotEmpty()) {
                    keysToRemove.forEach { key -> preferences.remove(key) }
                } else {
                    preferences.clear()
                }
            }

            // 2. Delete clotube_watch_history.json directly from Google Drive
            val sync = watchHistorySync ?: context?.let { DriveWatchHistorySync.getInstance(it) }
            val deletedFromDrive = sync?.deleteRemoteHistoryFile()
                ?: DriveWatchHistorySync.deleteRemoteHistoryFile(context)
            if (deletedFromDrive) {
                Log.i("ClotubeSync", "All watch history successfully wiped locally and removed from Drive")
            } else {
                Log.w("ClotubeSync", "Local history wiped, but failed to delete file from Google Drive")
            }
        }
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        clearEntireWatchHistory()
    }

    suspend fun clearProgress(fileId: String) = withContext(Dispatchers.IO) {
        deleteItemHistory(fileId)
    }

    suspend fun deleteHistoryItem(fileId: String) = withContext(Dispatchers.IO) {
        deleteItemHistory(fileId)
    }

    suspend fun recordProgress(
        fileId: String,
        title: String,
        seriesTitle: String? = null,
        seasonNumber: Int = 0,
        episodeNumber: Int = 0,
        isSeries: Boolean = false,
        seriesId: String? = null,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
        thumbnailUrl: String? = null
    ) = withContext(Dispatchers.IO) {
        recordToWatchHistoryList(
            WatchHistoryItem(
                fileId = fileId,
                title = title,
                seriesTitle = seriesTitle,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                isSeries = isSeries,
                seriesId = seriesId,
                positionMs = positionMs,
                durationMs = durationMs,
                lastWatchedTimestamp = System.currentTimeMillis(),
                thumbnailUrl = thumbnailUrl
            )
        )
    }

    private suspend fun recordToWatchHistoryList(item: WatchHistoryItem) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs ->
            val json = prefs[HISTORY_JSON_KEY]
            val list: MutableList<WatchHistoryItem> = if (!json.isNullOrBlank()) {
                try {
                    val type = object : TypeToken<MutableList<WatchHistoryItem>>() {}.type
                    gson.fromJson(json, type) ?: mutableListOf()
                } catch (e: Exception) {
                    mutableListOf()
                }
            } else mutableListOf()

            list.removeAll { it.fileId == item.fileId }
            list.add(0, item)
            if (list.size > 100) {
                list.subList(100, list.size).clear()
            }
            prefs[HISTORY_JSON_KEY] = gson.toJson(list)
        }
    }

    suspend fun exportHistoryJson(): String = withContext(Dispatchers.IO) {
        try {
            val currentList = allHistory.first()
            if (currentList.isNotEmpty()) {
                gson.toJson(currentList)
            } else {
                "[]"
            }
        } catch (e: Exception) {
            "[]"
        }
    }

    suspend fun importHistoryJson(json: String) = withContext(Dispatchers.IO) {
        if (json.isBlank() || json == "[]") return@withContext
        try {
            val type = object : TypeToken<List<WatchHistoryItem>>() {}.type
            val parsed: List<WatchHistoryItem> = gson.fromJson(json, type) ?: return@withContext
            if (parsed.isNotEmpty()) {
                dataStore.edit { prefs ->
                    prefs[HISTORY_JSON_KEY] = json
                    parsed.forEach { item ->
                        val safeId = (item.fileId ?: "").trim()
                        if (safeId.isNotEmpty()) {
                            val watchProgress = WatchProgress(
                                fileId = safeId,
                                title = (item.title ?: "").trim().ifBlank { "Untitled Video" },
                                seriesTitle = item.seriesTitle,
                                seasonNumber = item.seasonNumber,
                                episodeNumber = item.episodeNumber,
                                isSeries = item.isSeries,
                                positionMs = item.positionMs,
                                durationMs = item.durationMs,
                                thumbnailUrl = item.thumbnailUrl,
                                lastWatchedTimestamp = item.lastWatchedTimestamp,
                                seriesId = item.seriesId,
                                backdropUrl = item.thumbnailUrl
                            )
                            prefs[stringPreferencesKey("progress_$safeId")] = watchProgress.toJson()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import history JSON", e)
        }
    }
}
