package com.clotube.app.data

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.clotube.app.model.ContinueWatchingItem
import com.clotube.app.model.Episode
import com.clotube.app.model.Movie
import com.clotube.app.model.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Serializable

@Keep
data class WatchProgress(
    val fileId: String = "",
    val title: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val progressFraction: Float = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f,
    val lastWatchedTimestamp: Long = System.currentTimeMillis(),
    val isSeries: Boolean = false,
    val seriesId: String? = null,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val seriesTitle: String? = null,
    val thumbnailUrl: String? = null,
    val isWatched: Boolean = false,
    val backdropUrl: String? = thumbnailUrl,
    val genre: String? = null
) : Serializable {
    val lastUpdatedEpochMs: Long get() = lastWatchedTimestamp
    val episodeId: String get() = fileId
    val mediaId: String get() = fileId

    val formattedDisplayTitle: String
        get() {
            val safeTitle = title ?: ""
            val safeSeriesTitle = seriesTitle ?: ""
            val sNum = seasonNumber ?: 0
            val eNum = episodeNumber ?: 0
            return when {
                isSeries && safeSeriesTitle.isNotBlank() -> {
                    val epCode = "S${String.format(java.util.Locale.US, "%02d", sNum)}:E${String.format(java.util.Locale.US, "%02d", eNum)}"
                    if (safeTitle.isNotBlank() && !safeTitle.equals(safeSeriesTitle, ignoreCase = true)) {
                        "$safeSeriesTitle • $epCode • $safeTitle"
                    } else {
                        "$safeSeriesTitle • $epCode"
                    }
                }
                isSeries -> {
                    val epCode = "S${String.format(java.util.Locale.US, "%02d", sNum)}:E${String.format(java.util.Locale.US, "%02d", eNum)}"
                    if (safeTitle.isNotBlank()) "$safeTitle • $epCode" else "Series • $epCode"
                }
                else -> safeTitle.ifBlank { "Untitled Video" }
            }
        }

    fun toJson(): String {
        val json = JSONObject()
        json.put("fileId", fileId)
        json.put("title", title)
        seriesTitle?.let { json.put("seriesTitle", it) }
        json.put("seasonNumber", seasonNumber)
        json.put("episodeNumber", episodeNumber)
        json.put("isSeries", isSeries)
        json.put("positionMs", positionMs)
        json.put("durationMs", durationMs)
        json.put("progressFraction", progressFraction.toDouble())
        json.put("lastWatchedTimestamp", lastWatchedTimestamp)
        val thumb = thumbnailUrl ?: backdropUrl
        thumb?.let {
            json.put("thumbnailUrl", it)
            json.put("backdropUrl", it)
        }
        seriesId?.let { json.put("seriesId", it) }
        genre?.let { json.put("genre", it) }
        json.put("isWatched", isWatched)
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
                val ts = json.optLong("lastWatchedTimestamp", 0L)
                val pos = json.optLong("positionMs", 0L)
                val dur = json.optLong("durationMs", 0L)
                val frac = if (json.has("progressFraction")) {
                    json.optDouble("progressFraction", 0.0).toFloat()
                } else if (dur > 0L) {
                    (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                } else 0f

                WatchProgress(
                    fileId = rawFileId,
                    title = json.optString("title", "Unknown").trim().ifBlank { "Unknown" },
                    seriesTitle = if (json.has("seriesTitle")) json.optString("seriesTitle", "").ifBlank { null } else null,
                    seasonNumber = json.optInt("seasonNumber", 0),
                    episodeNumber = json.optInt("episodeNumber", 0),
                    isSeries = json.optBoolean("isSeries", false),
                    positionMs = pos,
                    durationMs = dur,
                    progressFraction = frac,
                    lastWatchedTimestamp = ts,
                    thumbnailUrl = thumb,
                    backdropUrl = thumb,
                    seriesId = if (json.has("seriesId")) json.optString("seriesId", "").ifBlank { null } else null,
                    genre = if (json.has("genre")) json.optString("genre", "").ifBlank { null } else null,
                    isWatched = json.optBoolean("isWatched", frac >= 0.90f)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

@Keep
data class SeriesResumeState(
    val seriesId: String = "",
    val lastEpisodeId: String = "",
    val seasonNumber: Int = 1,
    val episodeNumber: Int = 1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastWatchedTimestamp: Long = 0L
) : Serializable {
    fun toJson(): String {
        val json = JSONObject()
        json.put("seriesId", seriesId)
        json.put("lastEpisodeId", lastEpisodeId)
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
    val fileId: String = "",
    val title: String = "",
    val seriesTitle: String? = null,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val isSeries: Boolean = false,
    val seriesId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastWatchedTimestamp: Long = System.currentTimeMillis(),
    val thumbnailUrl: String = ""
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
    private val context: Context,
    private val watchHistorySync: DriveWatchHistorySync = DriveWatchHistorySync.getInstance(context)
) {
    private val appContext = context.applicationContext
    var nextEpisodeResolver: ((seriesId: String, currentEpisodeId: String) -> EpisodeResumeInfo?)? = null

    init {
        AndroidWatchHistoryManager.purgeAllLocalDiskCaches(appContext)
    }

    fun setTokenProvider(provider: suspend () -> String) {
        DriveWatchHistorySync.setTokenProvider(provider)
        DriveWatchHistorySync.init(appContext, provider)
    }

    suspend fun syncFromCloud(force: Boolean = true): Boolean {
        return watchHistorySync.syncFromCloud(force = force)
    }

    fun backupImmediately() {
        watchHistorySync.enqueueImmediateWatchState(updateArchive = false)
    }

    fun backupToCloudImmediately() {
        backupImmediately()
    }

    fun backupToCloudThrottled() {
        watchHistorySync.enqueueProgressUpdate()
    }

    val allHistory: Flow<List<WatchHistoryItem>> = AndroidWatchHistoryManager.stateRevision.map {
        val entries = AndroidWatchHistoryManager.snapshotEntries()
        val resultMap = mutableMapOf<String, WatchHistoryItem>()

        for ((keyName, entry) in entries) {
            if (keyName.endsWith("_timestamp") && !keyName.startsWith("series_resume_") && !keyName.startsWith("global_")) {
                val fileId = keyName.removeSuffix("_timestamp")
                val timestamp = (entry.value as? Number)?.toLong() ?: 0L
                val pos = (entries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
                val dur = (entries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
                val frac = (entries["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f
                val title = (entries["${fileId}_title"]?.value as? String)?.ifBlank { "Untitled Video" } ?: "Untitled Video"
                val isSeries = (entries["${fileId}_is_series"]?.value as? Boolean) ?: false
                val sId = entries["${fileId}_series_id"]?.value as? String
                val sNum = (entries["${fileId}_season_num"]?.value as? Number)?.toInt() ?: 0
                val eNum = (entries["${fileId}_episode_num"]?.value as? Number)?.toInt() ?: 0
                val sTitle = entries["${fileId}_series_title"]?.value as? String
                val thumb = (entries["${fileId}_thumbnail_url"]?.value as? String)?.takeIf { it.isNotBlank() }
                    ?: (entries["${fileId}_thumb"]?.value as? String)?.takeIf { it.isNotBlank() }
                    ?: ""

                if (dur > 0L && (frac >= 0.01f || pos > 3000L || frac >= 0.95f)) {
                    val isFinished = frac >= 0.95f || pos >= (dur * 0.95f)
                    val finalPos = if (isFinished) dur else pos
                    resultMap[fileId] = WatchHistoryItem(
                        fileId = fileId,
                        title = title,
                        seriesTitle = sTitle,
                        seasonNumber = sNum,
                        episodeNumber = eNum,
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
        resultMap.values.sortedByDescending { it.lastWatchedTimestamp }
    }

    val lastWatchedFlow: Flow<WatchProgress?> get() = getLastWatchedMedia()

    fun getLastWatchedMedia(): Flow<WatchProgress?> = AndroidWatchHistoryManager.stateRevision.map {
        val entries = AndroidWatchHistoryManager.snapshotEntries()
        val candidates = mutableListOf<WatchProgress>()
        val resumeEpisodeIds = mutableSetOf<String>()

        // 1. Evaluate series_resume_${seriesId}_* pointers
        entries.keys
            .filter { it.startsWith("series_resume_") && it.endsWith("_episode_id") }
            .forEach { epKey ->
                val sId = epKey.removePrefix("series_resume_").removeSuffix("_episode_id")
                val p = "series_resume_${sId}"
                val epId = (entries["${p}_episode_id"]?.value as? String).orEmpty()
                if (epId.isNotBlank()) {
                    resumeEpisodeIds.add(epId)
                    val explicitWatched = entries["${epId}_is_watched"]?.value as? Boolean
                    val resumeTs = (entries["${p}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                    val epTs = (entries["${epId}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                    val ts = maxOf(resumeTs, epTs)
                    val pos = (entries["${p}_pos_ms"]?.value as? Number)?.toLong()
                        ?: (entries["${epId}_pos"]?.value as? Number)?.toLong() ?: 0L
                    val dur = (entries["${p}_dur_ms"]?.value as? Number)?.toLong()
                        ?: (entries["${epId}_dur"]?.value as? Number)?.toLong() ?: 0L
                    val fraction = (entries["${p}_fraction"]?.value as? Number)?.toFloat()
                        ?: (entries["${epId}_fraction"]?.value as? Number)?.toFloat()
                        ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
                    val isWatched = explicitWatched ?: (fraction >= 0.90f)

                    if (!isWatched && fraction < 0.95f && ts > 0L) {
                        val sNum = (entries["${p}_season_num"]?.value as? Number)?.toInt()
                            ?: (entries["${epId}_season_num"]?.value as? Number)?.toInt() ?: 1
                        val eNum = (entries["${p}_episode_num"]?.value as? Number)?.toInt()
                            ?: (entries["${epId}_episode_num"]?.value as? Number)?.toInt() ?: 1
                        val epTitle = (entries["${p}_episode_title"]?.value as? String)
                            ?: (entries["${epId}_title"]?.value as? String) ?: "Episode $eNum"
                        val sTitle = (entries["${p}_series_title"]?.value as? String)
                            ?: (entries["${epId}_series_title"]?.value as? String)
                        val thumb = (entries["${epId}_thumbnail_url"]?.value as? String)?.takeIf { it.isNotBlank() }
                            ?: (entries["${epId}_thumb"]?.value as? String)?.takeIf { it.isNotBlank() }
                            ?: (entries["${p}_thumbnail_url"]?.value as? String)?.takeIf { it.isNotBlank() }
                            ?: (entries["${p}_thumb"]?.value as? String)?.takeIf { it.isNotBlank() }

                        candidates.add(
                            WatchProgress(
                                fileId = epId,
                                title = epTitle,
                                positionMs = pos,
                                durationMs = dur,
                                progressFraction = fraction,
                                lastWatchedTimestamp = ts,
                                isSeries = true,
                                seriesId = sId,
                                seasonNumber = sNum,
                                episodeNumber = eNum,
                                seriesTitle = sTitle,
                                thumbnailUrl = thumb,
                                backdropUrl = thumb,
                                isWatched = false
                            )
                        )
                    }
                }
            }

        // 2. Evaluate discrete movies and standalone episodes
        entries.keys
            .filter { it.endsWith("_timestamp") && !it.startsWith("series_resume_") && !it.startsWith("global_") }
            .forEach { tsKey ->
                val fileId = tsKey.removeSuffix("_timestamp")
                if (fileId !in resumeEpisodeIds) {
                    val ts = (entries[tsKey]?.value as? Number)?.toLong() ?: 0L
                    val pos = (entries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
                    val dur = (entries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
                    val fraction = (entries["${fileId}_fraction"]?.value as? Number)?.toFloat()
                        ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
                    val explicitWatched = entries["${fileId}_is_watched"]?.value as? Boolean
                    val isWatched = explicitWatched ?: (fraction >= 0.90f)
                    val isSeries = (entries["${fileId}_is_series"]?.value as? Boolean) ?: false
                    val sId = entries["${fileId}_series_id"]?.value as? String
                    val sNum = (entries["${fileId}_season_num"]?.value as? Number)?.toInt() ?: 0
                    val eNum = (entries["${fileId}_episode_num"]?.value as? Number)?.toInt() ?: 0
                    val title = (entries["${fileId}_title"]?.value as? String) ?: ""
                    val sTitle = entries["${fileId}_series_title"]?.value as? String
                    val thumb = (entries["${fileId}_thumbnail_url"]?.value as? String)?.takeIf { it.isNotBlank() }
                        ?: (entries["${fileId}_thumb"]?.value as? String)?.takeIf { it.isNotBlank() }

                    if (!isWatched && pos >= 1000L && fraction < 0.95f && ts > 0L) {
                        candidates.add(
                            WatchProgress(
                                fileId = fileId,
                                title = title,
                                positionMs = pos,
                                durationMs = dur,
                                progressFraction = fraction,
                                lastWatchedTimestamp = ts,
                                isSeries = isSeries,
                                seriesId = sId,
                                seasonNumber = sNum,
                                episodeNumber = eNum,
                                seriesTitle = sTitle,
                                thumbnailUrl = thumb,
                                backdropUrl = thumb,
                                isWatched = false
                            )
                        )
                    }
                }
            }

        candidates.maxByOrNull { it.lastWatchedTimestamp }
    }

    fun getContinueWatchingMedia(): Flow<ContinueWatchingItem?> = lastWatchedFlow.map { progress ->
        if (progress == null) null
        else {
            val sNum = if (progress.seasonNumber > 0) progress.seasonNumber else 1
            val eNum = if (progress.episodeNumber > 0) progress.episodeNumber else 1
            val epCode = if (progress.isSeries) {
                "S${sNum.toString().padStart(2, '0')}:E${eNum.toString().padStart(2, '0')}"
            } else ""
            val subtitle = if (progress.isSeries) "$epCode • ${progress.title}" else "Movie • Cinema"
            ContinueWatchingItem(
                mediaId = progress.fileId,
                seriesId = progress.seriesId,
                isSeries = progress.isSeries,
                seriesTitle = progress.seriesTitle ?: progress.title,
                seasonNumber = progress.seasonNumber ?: 0,
                episodeNumber = progress.episodeNumber ?: 0,
                episodeTitle = progress.title,
                displaySubtitle = subtitle,
                thumbnailUrl = progress.thumbnailUrl ?: "",
                progressFraction = progress.progressFraction,
                currentPositionMs = progress.positionMs,
                durationMs = progress.durationMs,
                lastWatchedTimestamp = progress.lastWatchedTimestamp,
                title = progress.seriesTitle ?: progress.title,
                subtitle = subtitle,
                positionMs = progress.positionMs,
                timestamp = progress.lastWatchedTimestamp,
                backdropUrl = progress.thumbnailUrl ?: ""
            )
        }
    }

    suspend fun getAllContinueWatchingItems(
        movies: List<Movie>,
        seriesList: List<Series>
    ): List<ContinueWatchingItem> = withContext(Dispatchers.IO) {
        val entries = AndroidWatchHistoryManager.snapshotEntries()
        val result = mutableListOf<ContinueWatchingItem>()
        val seenSeriesIds = mutableSetOf<String>()
        val seenMediaIds = mutableSetOf<String>()

        // 1. Scan series_resume pointers
        entries.keys
            .filter { it.startsWith("series_resume_") && it.endsWith("_episode_id") }
            .forEach { epKey ->
                val sId = epKey.removePrefix("series_resume_").removeSuffix("_episode_id")
                val p = "series_resume_${sId}"
                val epId = (entries["${p}_episode_id"]?.value as? String).orEmpty()
                if (epId.isNotBlank()) {
                    val resumeTs = (entries["${p}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                    val epTs = (entries["${epId}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                    val ts = maxOf(resumeTs, epTs)
                    val pos = (entries["${p}_pos_ms"]?.value as? Number)?.toLong()
                        ?: (entries["${epId}_pos"]?.value as? Number)?.toLong() ?: 0L
                    val dur = (entries["${p}_dur_ms"]?.value as? Number)?.toLong()
                        ?: (entries["${epId}_dur"]?.value as? Number)?.toLong() ?: 0L
                    val frac = (entries["${p}_fraction"]?.value as? Number)?.toFloat()
                        ?: (entries["${epId}_fraction"]?.value as? Number)?.toFloat()
                        ?: (if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f)
                    val isWatched = AndroidWatchHistoryManager.isEpisodeWatched(epId)

                    if (!isWatched && frac < 0.90f && (pos > 5000L || ts > 0L)) {
                        val matchedSeries = seriesList.firstOrNull { it.id == sId }
                        val allEpisodes = matchedSeries?.seasons?.flatMap { it.episodes } ?: emptyList()
                        val matchedEp = allEpisodes.firstOrNull { it.id == epId }

                        val sNumStored = (entries["${p}_season_num"]?.value as? Number)?.toInt() ?: 0
                        val eNumStored = (entries["${p}_episode_num"]?.value as? Number)?.toInt() ?: 0
                        val seasonNum = when {
                            matchedEp?.seasonNumber != null && matchedEp.seasonNumber > 0 -> matchedEp.seasonNumber
                            sNumStored > 0 -> sNumStored
                            else -> 1
                        }
                        val episodeNum = when {
                            matchedEp?.episodeNumber != null && matchedEp.episodeNumber > 0 -> matchedEp.episodeNumber
                            eNumStored > 0 -> eNumStored
                            else -> 1
                        }

                        val rawSeriesTitle = matchedSeries?.title?.ifBlank { null }
                            ?: (entries["${p}_series_title"]?.value as? String) ?: "TV Series"
                        val seriesTitle = rawSeriesTitle.trim()

                        val rawEpTitle = matchedEp?.displayTitle?.ifBlank { null }
                            ?: (entries["${p}_episode_title"]?.value as? String) ?: ""
                        val cleanEp = rawEpTitle
                            .replace(Regex("(?i)\\bcompleted\\b"), "")
                            .replace(Regex("(?i)\\bcomplete\\b"), "")
                            .replace(Regex("(?i)^.*?S0*\\d+[:\\s._-]*E0*\\d+[:\\s-]*"), "")
                            .replace(Regex("(?i)^${Regex.escape(seriesTitle)}[:\\s-]*"), "")
                            .trim(' ', '-', '•', ':')
                            .ifBlank { "Episode $episodeNum" }

                        val epCode = "S${seasonNum.toString().padStart(2, '0')}:E${episodeNum.toString().padStart(2, '0')}"
                        val displaySubtitle = "$epCode • $cleanEp"

                        val thumb = matchedEp?.thumbnailUrl?.takeIf { it.isNotBlank() }
                            ?: (entries["${epId}_thumb"]?.value as? String)?.takeIf { it.isNotBlank() }
                            ?: (entries["${p}_thumb"]?.value as? String)?.takeIf { it.isNotBlank() }
                            ?: matchedSeries?.backdropUrl?.takeIf { it.isNotBlank() }
                            ?: matchedSeries?.posterUrl?.takeIf { it.isNotBlank() }
                            ?: ""

                        result.add(
                            ContinueWatchingItem(
                                mediaId = epId,
                                seriesId = sId,
                                isSeries = true,
                                seriesTitle = seriesTitle,
                                seasonNumber = seasonNum,
                                episodeNumber = episodeNum,
                                episodeTitle = cleanEp,
                                displaySubtitle = displaySubtitle,
                                thumbnailUrl = thumb,
                                progressFraction = frac,
                                currentPositionMs = pos,
                                durationMs = dur,
                                lastWatchedTimestamp = ts,
                                title = seriesTitle,
                                subtitle = displaySubtitle,
                                positionMs = pos,
                                timestamp = ts,
                                backdropUrl = thumb
                            )
                        )
                        seenSeriesIds.add(sId)
                        seenMediaIds.add(epId)
                    }
                }
            }

        // 2. Scan discrete entries
        entries.keys
            .filter {
                it.endsWith("_timestamp") &&
                !it.startsWith("series_resume_") &&
                !it.startsWith("global_")
            }
            .forEach { key ->
                val fileId = key.removeSuffix("_timestamp")
                if (!seenMediaIds.contains(fileId)) {
                    val ts = (entries[key]?.value as? Number)?.toLong() ?: 0L
                    val pos = (entries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
                    val dur = (entries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
                    val frac = (entries["${fileId}_fraction"]?.value as? Number)?.toFloat()
                        ?: (if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f)
                    val isWatched = AndroidWatchHistoryManager.isEpisodeWatched(fileId)

                    if (pos > 5000L && !isWatched && frac < 0.90f && ts > 0L) {
                        val sId = entries["${fileId}_series_id"]?.value as? String
                        val isSeriesExplicit = (entries["${fileId}_is_series"]?.value as? Boolean) ?: false
                        val matchedSeries = if (!sId.isNullOrBlank()) seriesList.firstOrNull { it.id == sId }
                        else seriesList.firstOrNull { s -> s.seasons.any { season -> season.episodes.any { ep -> ep.id == fileId } } }
                        val matchedMovie = movies.firstOrNull { it.id == fileId }

                        val isSeries = isSeriesExplicit || sId != null || matchedSeries != null

                        if (isSeries) {
                            val effectiveSeriesId = sId ?: matchedSeries?.id ?: ""
                            if (effectiveSeriesId.isNotBlank() && seenSeriesIds.contains(effectiveSeriesId)) {
                                return@forEach
                            }
                            val allEpisodes = matchedSeries?.seasons?.flatMap { it.episodes } ?: emptyList()
                            val matchedEp = allEpisodes.firstOrNull { it.id == fileId }
                            val seasonNum = matchedEp?.seasonNumber ?: (entries["${fileId}_season_num"]?.value as? Number)?.toInt() ?: 1
                            val episodeNum = matchedEp?.episodeNumber ?: (entries["${fileId}_episode_num"]?.value as? Number)?.toInt() ?: 1

                            val rawSeriesTitle = matchedSeries?.title?.ifBlank { null }
                                ?: (entries["${fileId}_series_title"]?.value as? String) ?: "TV Series"
                            val seriesTitle = rawSeriesTitle.trim()

                            val rawEpTitle = matchedEp?.displayTitle?.ifBlank { null }
                                ?: (entries["${fileId}_title"]?.value as? String) ?: ""
                            val cleanEp = rawEpTitle
                                .replace(Regex("(?i)\\bcompleted\\b"), "")
                                .replace(Regex("(?i)\\bcomplete\\b"), "")
                                .replace(Regex("(?i)^.*?S0*\\d+[:\\s._-]*E0*\\d+[:\\s-]*"), "")
                                .replace(Regex("(?i)^${Regex.escape(seriesTitle)}[:\\s-]*"), "")
                                .trim(' ', '-', '•', ':')
                                .ifBlank { "Episode $episodeNum" }

                            val epCode = "S${seasonNum.toString().padStart(2, '0')}:E${episodeNum.toString().padStart(2, '0')}"
                            val displaySubtitle = "$epCode • $cleanEp"

                            val thumb = matchedEp?.thumbnailUrl?.takeIf { it.isNotBlank() }
                                ?: (entries["${fileId}_thumb"]?.value as? String)?.takeIf { it.isNotBlank() }
                                ?: matchedSeries?.backdropUrl?.takeIf { it.isNotBlank() }
                                ?: matchedSeries?.posterUrl?.takeIf { it.isNotBlank() }
                                ?: ""

                            result.add(
                                ContinueWatchingItem(
                                    mediaId = fileId,
                                    seriesId = effectiveSeriesId.ifBlank { null },
                                    isSeries = true,
                                    seriesTitle = seriesTitle,
                                    seasonNumber = seasonNum,
                                    episodeNumber = episodeNum,
                                    episodeTitle = cleanEp,
                                    displaySubtitle = displaySubtitle,
                                    thumbnailUrl = thumb,
                                    progressFraction = frac,
                                    currentPositionMs = pos,
                                    durationMs = dur,
                                    lastWatchedTimestamp = ts,
                                    title = seriesTitle,
                                    subtitle = displaySubtitle,
                                    positionMs = pos,
                                    timestamp = ts,
                                    backdropUrl = thumb
                                )
                            )
                            if (effectiveSeriesId.isNotBlank()) {
                                seenSeriesIds.add(effectiveSeriesId)
                            }
                            seenMediaIds.add(fileId)
                        } else {
                            val title = matchedMovie?.title?.takeIf { it.isNotBlank() }
                                ?: (entries["${fileId}_title"]?.value as? String) ?: "Movie"
                            val thumb = matchedMovie?.thumbnailUrl?.takeIf { it.isNotBlank() }
                                ?: (entries["${fileId}_thumb"]?.value as? String) ?: ""

                            result.add(
                                ContinueWatchingItem(
                                    mediaId = fileId,
                                    seriesId = null,
                                    isSeries = false,
                                    seriesTitle = title,
                                    seasonNumber = 0,
                                    episodeNumber = 0,
                                    episodeTitle = title,
                                    displaySubtitle = "Movie • Cinema",
                                    thumbnailUrl = thumb,
                                    progressFraction = frac,
                                    currentPositionMs = pos,
                                    durationMs = if (dur > 0L) dur else matchedMovie?.durationMs ?: 0L,
                                    lastWatchedTimestamp = ts,
                                    title = title,
                                    subtitle = "Movie • Cinema",
                                    positionMs = pos,
                                    timestamp = ts,
                                    backdropUrl = thumb
                                )
                            )
                            seenMediaIds.add(fileId)
                        }
                    }
                }
            }

        val finalItems = mutableListOf<ContinueWatchingItem>()
        val groupedBySeries = result.filter { it.isSeries && !it.seriesId.isNullOrBlank() }.groupBy { it.seriesId }
        for ((_, items) in groupedBySeries) {
            items.maxByOrNull { it.lastWatchedTimestamp }?.let { finalItems.add(it) }
        }
        finalItems.addAll(result.filter { !it.isSeries || it.seriesId.isNullOrBlank() })
        finalItems.sortedByDescending { it.lastWatchedTimestamp }
    }

    suspend fun removeContinueWatchingItem(
        @Suppress("UNUSED_PARAMETER") context: Context,
        mediaId: String,
        seriesId: String?
    ) = deleteItemHistory(mediaId, seriesId)

    suspend fun removeContinueWatchingItem(
        context: Context,
        item: ContinueWatchingItem
    ) = removeContinueWatchingItem(context, item.mediaId, item.seriesId)

    fun getSeriesResumeState(seriesId: String): Flow<SeriesResumeState?> = AndroidWatchHistoryManager.stateRevision.map {
        if (seriesId.isBlank()) return@map null
        val entries = AndroidWatchHistoryManager.snapshotEntries()
        val p = "series_resume_${seriesId}"
        val epId = (entries["${p}_episode_id"]?.value as? String).orEmpty()
        if (epId.isBlank()) return@map null

        val sNum = (entries["${p}_season_num"]?.value as? Number)?.toInt() ?: 1
        val eNum = (entries["${p}_episode_num"]?.value as? Number)?.toInt() ?: 1
        val pos = (entries["${p}_pos_ms"]?.value as? Number)?.toLong() ?: 0L
        val dur = (entries["${p}_dur_ms"]?.value as? Number)?.toLong() ?: 0L
        val ts = (entries["${p}_timestamp"]?.value as? Number)?.toLong() ?: 0L

        SeriesResumeState(
            seriesId = seriesId,
            lastEpisodeId = epId,
            seasonNumber = sNum,
            episodeNumber = eNum,
            positionMs = pos,
            durationMs = dur,
            lastWatchedTimestamp = ts
        )
    }

    suspend fun getSeriesResumeStateOnce(seriesId: String): SeriesResumeState? {
        return getSeriesResumeState(seriesId).firstOrNull()
    }

    fun getEpisodeProgressFraction(episodeId: String): Flow<Float> = AndroidWatchHistoryManager.stateRevision.map {
        val entries = AndroidWatchHistoryManager.snapshotEntries()
        (entries["${episodeId}_fraction"]?.value as? Number)?.toFloat()
            ?: if (AndroidWatchHistoryManager.isEpisodeWatched(episodeId)) 1.0f else 0f
    }

    fun getResumePosition(fileId: String, durationMs: Long = 0L): Long {
        if (fileId.isBlank()) return 0L
        val entries = AndroidWatchHistoryManager.snapshotEntries()
        val pos = (entries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
        val dur = if (durationMs > 0L) durationMs else (entries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
        val fraction = (entries["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f
        val isWatched = AndroidWatchHistoryManager.isEpisodeWatched(fileId) || fraction >= 0.90f

        return when {
            pos > 5000L && (dur == 0L || pos < dur - 15000L) -> pos
            isWatched -> 0L
            pos > 3000L -> pos
            else -> 0L
        }
    }

    fun getResumePosition(
        @Suppress("UNUSED_PARAMETER") userEmail: String,
        fileId: String,
        durationMs: Long
    ): Long = getResumePosition(fileId, durationMs)

    fun getProgressSync(fileId: String): WatchProgress? {
        if (fileId.isBlank()) return null
        val entries = AndroidWatchHistoryManager.snapshotEntries()
        val timestamp = (entries["${fileId}_timestamp"]?.value as? Number)?.toLong()
        if (timestamp != null && timestamp > 0L) {
            val pos = (entries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = (entries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
            val frac = (entries["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f
            val title = (entries["${fileId}_title"]?.value as? String) ?: "Video"
            val isSeries = (entries["${fileId}_is_series"]?.value as? Boolean) ?: false
            val sId = entries["${fileId}_series_id"]?.value as? String
            val sNum = (entries["${fileId}_season_num"]?.value as? Number)?.toInt()
            val eNum = (entries["${fileId}_episode_num"]?.value as? Number)?.toInt()
            val sTitle = entries["${fileId}_series_title"]?.value as? String
            val thumb = entries["${fileId}_thumb"]?.value as? String
            val isWatched = AndroidWatchHistoryManager.isEpisodeWatched(fileId)
            return WatchProgress(
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
        return null
    }

    fun getProgress(fileId: String): Flow<WatchProgress?> = AndroidWatchHistoryManager.stateRevision.map {
        getProgressSync(fileId)
    }

    fun getAllProgress(): Flow<Map<String, WatchProgress>> = AndroidWatchHistoryManager.stateRevision.map {
        val entries = AndroidWatchHistoryManager.snapshotEntries()
        val resultMap = mutableMapOf<String, WatchProgress>()
        for ((keyName, entry) in entries) {
            if (keyName.endsWith("_timestamp") && !keyName.startsWith("series_resume_") && !keyName.startsWith("global_")) {
                val fileId = keyName.removeSuffix("_timestamp")
                val timestamp = (entry.value as? Number)?.toLong() ?: 0L
                val pos = (entries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
                val dur = (entries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
                val frac = (entries["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f
                val title = (entries["${fileId}_title"]?.value as? String) ?: "Video"
                val isSeries = (entries["${fileId}_is_series"]?.value as? Boolean) ?: false
                val sId = entries["${fileId}_series_id"]?.value as? String
                val sNum = (entries["${fileId}_season_num"]?.value as? Number)?.toInt()
                val eNum = (entries["${fileId}_episode_num"]?.value as? Number)?.toInt()
                val sTitle = entries["${fileId}_series_title"]?.value as? String
                val thumb = entries["${fileId}_thumb"]?.value as? String
                val isWatched = AndroidWatchHistoryManager.isEpisodeWatched(fileId)

                if (dur > 0L || isWatched || timestamp > 0L) {
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
        resultMap
    }

    fun getAllProgressList(): Flow<List<WatchProgress>> = getAllProgress().map { it.values.toList() }

    fun isEpisodeWatched(episodeId: String): Flow<Boolean> = AndroidWatchHistoryManager.stateRevision.map {
        AndroidWatchHistoryManager.isEpisodeWatched(episodeId)
    }

    fun isSeasonFullyWatched(episodes: List<Episode>): Flow<Boolean> = AndroidWatchHistoryManager.stateRevision.map {
        if (episodes.isEmpty()) false
        else episodes.all { AndroidWatchHistoryManager.isEpisodeWatched(it.id) }
    }

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
        @Suppress("UNUSED_PARAMETER") genre: String? = null,
        forceCompleted: Boolean = false,
        immediateCloudSync: Boolean = false
    ) = withContext(NonCancellable + Dispatchers.IO) {
        if (fileId.isBlank() || durationMs <= 0L) return@withContext

        val now = System.currentTimeMillis()
        val isFinished = forceCompleted || (positionMs >= (durationMs * 0.95f))
        val finalPos = if (isFinished) durationMs else positionMs
        val finalFraction = if (isFinished) 1.0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        val wasAlreadyWatched = AndroidWatchHistoryManager.isEpisodeWatched(fileId)
        val isCompletedNow = isFinished || wasAlreadyWatched
        val thumb = thumbnailUrl ?: backdropUrl

        AndroidWatchHistoryManager.updateEntries {
            this["${fileId}_title"] = AndroidTypedEntry("string", title)
            this["${fileId}_pos"] = AndroidTypedEntry("long", finalPos)
            this["${fileId}_dur"] = AndroidTypedEntry("long", durationMs)
            this["${fileId}_fraction"] = AndroidTypedEntry("float", finalFraction)
            this["${fileId}_timestamp"] = AndroidTypedEntry("long", now)
            this["${fileId}_is_series"] = AndroidTypedEntry("boolean", isSeries)
            if (isCompletedNow) {
                this["${fileId}_is_watched"] = AndroidTypedEntry("boolean", true)
            }
            if (!thumb.isNullOrBlank()) {
                val existingThumb = (this["${fileId}_thumbnail_url"]?.value as? String)
                    ?: (this["${fileId}_thumb"]?.value as? String)
                val shouldUpdateThumb = when {
                    existingThumb.isNullOrBlank() -> true
                    com.clotube.app.data.metadata.OnlineMetadataResolver.isDriveVideoFrameUrl(thumb) && !com.clotube.app.data.metadata.OnlineMetadataResolver.isDriveVideoFrameUrl(existingThumb) -> false
                    else -> true
                }
                if (shouldUpdateThumb) {
                    this["${fileId}_thumbnail_url"] = AndroidTypedEntry("string", thumb)
                    this["${fileId}_thumb"] = AndroidTypedEntry("string", thumb)
                }
            }
            if (!seriesId.isNullOrBlank()) {
                this["${fileId}_series_id"] = AndroidTypedEntry("string", seriesId)
            }
            if (seasonNumber != null && seasonNumber > 0) {
                this["${fileId}_season_num"] = AndroidTypedEntry("int", seasonNumber)
            }
            if (episodeNumber != null && episodeNumber > 0) {
                this["${fileId}_episode_num"] = AndroidTypedEntry("int", episodeNumber)
            }
            if (!seriesTitle.isNullOrBlank()) {
                this["${fileId}_series_title"] = AndroidTypedEntry("string", seriesTitle)
            }

            if (isSeries && !seriesId.isNullOrBlank()) {
                val p = "series_resume_${seriesId}"
                this["${p}_episode_id"] = AndroidTypedEntry("string", fileId)
                this["${p}_episode_title"] = AndroidTypedEntry("string", title)
                if (seasonNumber != null && seasonNumber > 0) {
                    this["${p}_season_num"] = AndroidTypedEntry("int", seasonNumber)
                }
                if (episodeNumber != null && episodeNumber > 0) {
                    this["${p}_episode_num"] = AndroidTypedEntry("int", episodeNumber)
                }
                this["${p}_pos_ms"] = AndroidTypedEntry("long", finalPos)
                this["${p}_dur_ms"] = AndroidTypedEntry("long", durationMs)
                this["${p}_fraction"] = AndroidTypedEntry("float", finalFraction)
                this["${p}_timestamp"] = AndroidTypedEntry("long", now)
                if (!seriesTitle.isNullOrBlank()) {
                    this["${p}_series_title"] = AndroidTypedEntry("string", seriesTitle)
                }
                if (!thumb.isNullOrBlank()) {
                    val existingResumeThumb = (this["${p}_thumbnail_url"]?.value as? String)
                        ?: (this["${p}_thumb"]?.value as? String)
                    val shouldUpdateResumeThumb = when {
                        existingResumeThumb.isNullOrBlank() -> true
                        com.clotube.app.data.metadata.OnlineMetadataResolver.isDriveVideoFrameUrl(thumb) && !com.clotube.app.data.metadata.OnlineMetadataResolver.isDriveVideoFrameUrl(existingResumeThumb) -> false
                        else -> true
                    }
                    if (shouldUpdateResumeThumb) {
                        this["${p}_thumbnail_url"] = AndroidTypedEntry("string", thumb)
                        this["${p}_thumb"] = AndroidTypedEntry("string", thumb)
                    }
                }
            }
        }

        if (immediateCloudSync || isFinished) {
            watchHistorySync.executeDirectUpload(SyncPriority.CRITICAL_WATCH_STATE)
        } else {
            watchHistorySync.enqueueProgressUpdate()
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

    suspend fun saveMoviePlaybackState(
        fileId: String,
        title: String,
        positionMs: Long,
        durationMs: Long,
        thumbnailUrl: String? = null
    ) {
        saveProgress(
            fileId = fileId,
            title = title,
            positionMs = positionMs,
            durationMs = durationMs,
            isSeries = false,
            thumbnailUrl = thumbnailUrl,
            immediateCloudSync = false
        )
    }

    suspend fun saveSeriesPlaybackState(
        seriesId: String,
        seriesTitle: String = "",
        episodeId: String,
        episodeTitle: String = "",
        seasonNumber: Int,
        episodeNumber: Int,
        positionMs: Long,
        durationMs: Long,
        thumbnailUrl: String? = null
    ) {
        saveProgress(
            fileId = episodeId,
            title = episodeTitle.ifBlank { "Episode $episodeNumber" },
            positionMs = positionMs,
            durationMs = durationMs,
            isSeries = true,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            seriesTitle = seriesTitle.ifBlank { null },
            thumbnailUrl = thumbnailUrl,
            immediateCloudSync = false
        )
    }

    suspend fun savePlaybackState(
        fileId: String,
        title: String,
        positionMs: Long,
        durationMs: Long,
        isSeries: Boolean = false,
        seriesId: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        seriesTitle: String? = null,
        thumbnailUrl: String? = null
    ) {
        saveProgress(
            fileId = fileId,
            title = title,
            positionMs = positionMs,
            durationMs = durationMs,
            isSeries = isSeries,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            seriesTitle = seriesTitle,
            thumbnailUrl = thumbnailUrl,
            immediateCloudSync = false
        )
    }

    suspend fun recordPlayback(
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
    ) {
        saveProgress(
            fileId = fileId,
            title = title,
            positionMs = positionMs,
            durationMs = durationMs,
            isSeries = isSeries,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            seriesTitle = seriesTitle,
            thumbnailUrl = thumbnailUrl,
            immediateCloudSync = false
        )
    }

    suspend fun persistThumbnailUrl(fileId: String, thumbnailUrl: String) = withContext(NonCancellable + Dispatchers.IO) {
        if (fileId.isBlank() || thumbnailUrl.isBlank()) return@withContext
        AndroidWatchHistoryManager.saveThumbnailUrl(fileId, thumbnailUrl)
    }

    fun getThumbnailUrl(fileId: String): String? {
        return AndroidWatchHistoryManager.getThumbnailUrl(fileId)
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
    ) {
        saveProgress(
            fileId = fileId,
            title = title,
            positionMs = positionMs,
            durationMs = durationMs,
            isSeries = isSeries,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            seriesTitle = seriesTitle,
            thumbnailUrl = thumbnailUrl,
            immediateCloudSync = false
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
        if (fileId.isBlank()) return@withContext
        val now = System.currentTimeMillis()
        val safeDur = if (durationMs > 0L) durationMs else 1000L
        val effectiveTitle = title.ifBlank { nextEpisodeTitle ?: "" }
        val effectiveThumb = thumbnailUrl ?: nextThumbnailUrl ?: backdropUrl

        AndroidWatchHistoryManager.updateEntries {
            this["${fileId}_title"] = AndroidTypedEntry("string", effectiveTitle)
            this["${fileId}_pos"] = AndroidTypedEntry("long", safeDur)
            this["${fileId}_dur"] = AndroidTypedEntry("long", safeDur)
            this["${fileId}_fraction"] = AndroidTypedEntry("float", 1.0f)
            this["${fileId}_timestamp"] = AndroidTypedEntry("long", now)
            this["${fileId}_is_watched"] = AndroidTypedEntry("boolean", true)
            this["${fileId}_is_series"] = AndroidTypedEntry("boolean", isSeries)
            if (!effectiveThumb.isNullOrBlank()) {
                this["${fileId}_thumb"] = AndroidTypedEntry("string", effectiveThumb)
            }

            if (isSeries && !seriesId.isNullOrBlank()) {
                this["${fileId}_series_id"] = AndroidTypedEntry("string", seriesId)
                this["${fileId}_season_num"] = AndroidTypedEntry("int", seasonNumber ?: 1)
                this["${fileId}_episode_num"] = AndroidTypedEntry("int", episodeNumber ?: 1)
                if (!seriesTitle.isNullOrBlank()) {
                    this["${fileId}_series_title"] = AndroidTypedEntry("string", seriesTitle)
                }

                val p = "series_resume_${seriesId}"
                if (!nextEpisodeId.isNullOrBlank()) {
                    this["${p}_episode_id"] = AndroidTypedEntry("string", nextEpisodeId)
                    this["${p}_season_num"] = AndroidTypedEntry("int", nextSeasonNumber ?: (seasonNumber ?: 1))
                    this["${p}_episode_num"] = AndroidTypedEntry("int", nextEpisodeNumber ?: ((episodeNumber ?: 1) + 1))
                    this["${p}_pos_ms"] = AndroidTypedEntry("long", 0L)
                    this["${p}_dur_ms"] = AndroidTypedEntry("long", 0L)
                    this["${p}_fraction"] = AndroidTypedEntry("float", 0.0f)
                    this["${p}_timestamp"] = AndroidTypedEntry("long", now)
                    if (!seriesTitle.isNullOrBlank()) {
                        this["${p}_series_title"] = AndroidTypedEntry("string", seriesTitle)
                    }
                }
            }
        }

        watchHistorySync.executeDirectUpload(SyncPriority.CRITICAL_WATCH_STATE)
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
        val resolvedNext = if (nextEpisodeId.isNullOrBlank()) {
            nextEpisodeResolver?.invoke(seriesId, completedEpisodeId)
        } else null

        val targetNextId = nextEpisodeId ?: resolvedNext?.episodeId
        val targetNextTitle = nextEpisodeTitle ?: resolvedNext?.title
        val targetNextSNum = nextSeasonNumber ?: resolvedNext?.seasonNumber ?: seasonNumber
        val targetNextENum = nextEpisodeNumber ?: resolvedNext?.episodeNumber ?: (episodeNumber + 1)
        val targetNextThumb = nextThumbnailUrl ?: resolvedNext?.thumbnailUrl

        markCompleted(
            fileId = completedEpisodeId,
            title = targetNextTitle ?: "",
            durationMs = durationMs,
            isSeries = true,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            nextEpisodeId = targetNextId,
            nextSeasonNumber = targetNextSNum,
            nextEpisodeNumber = targetNextENum,
            seriesTitle = seriesTitle,
            thumbnailUrl = targetNextThumb
        )
    }

    suspend fun setEpisodeWatched(
        seriesId: String,
        seriesTitle: String,
        episode: Episode,
        isWatched: Boolean,
        @Suppress("UNUSED_PARAMETER") userEmail: String = ""
    ) = withContext(NonCancellable + Dispatchers.IO) {
        AndroidWatchHistoryManager.setEpisodeWatched(
            context = appContext,
            fileId = episode.id,
            isWatched = isWatched,
            title = episode.displayTitle,
            durationMs = episode.durationMs,
            isSeries = true,
            seriesId = seriesId,
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber
        )
    }

    suspend fun toggleEpisodeWatched(
        seriesId: String,
        seriesTitle: String,
        episode: Episode,
        userEmail: String = ""
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val currentlyWatched = AndroidWatchHistoryManager.isEpisodeWatched(episode.id)
        setEpisodeWatched(
            seriesId = seriesId,
            seriesTitle = seriesTitle,
            episode = episode,
            isWatched = !currentlyWatched,
            userEmail = userEmail
        )
    }

    suspend fun markSingleEpisodeComplete(
        seriesId: String,
        seriesTitle: String,
        episode: Episode
    ) = withContext(NonCancellable + Dispatchers.IO) {
        markCompleted(
            fileId = episode.id,
            title = episode.displayTitle,
            durationMs = episode.durationMs,
            isSeries = true,
            seriesId = seriesId,
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            seriesTitle = seriesTitle,
            thumbnailUrl = episode.thumbnailUrl
        )
    }

    suspend fun markSeasonAsComplete(
        seriesId: String,
        seriesTitle: String,
        seasonNumber: Int,
        episodes: List<Episode>
    ) = withContext(NonCancellable + Dispatchers.IO) {
        if (episodes.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        AndroidWatchHistoryManager.updateEntries {
            episodes.forEach { ep ->
                val dur = ep.durationMs.coerceAtLeast(1000L)
                this["${ep.id}_is_watched"] = AndroidTypedEntry("boolean", true)
                this["${ep.id}_title"] = AndroidTypedEntry("string", ep.displayTitle)
                this["${ep.id}_pos"] = AndroidTypedEntry("long", dur)
                this["${ep.id}_dur"] = AndroidTypedEntry("long", dur)
                this["${ep.id}_fraction"] = AndroidTypedEntry("float", 1.0f)
                this["${ep.id}_timestamp"] = AndroidTypedEntry("long", now)
                this["${ep.id}_is_series"] = AndroidTypedEntry("boolean", true)
                this["${ep.id}_series_id"] = AndroidTypedEntry("string", seriesId)
                this["${ep.id}_season_num"] = AndroidTypedEntry("int", seasonNumber)
                this["${ep.id}_episode_num"] = AndroidTypedEntry("int", ep.episodeNumber)
                if (ep.thumbnailUrl.isNotBlank()) {
                    this["${ep.id}_thumb"] = AndroidTypedEntry("string", ep.thumbnailUrl)
                }
            }
            val lastEp = episodes.lastOrNull()
            if (lastEp != null) {
                val p = "series_resume_${seriesId}"
                this["${p}_series_title"] = AndroidTypedEntry("string", seriesTitle)
                this["${p}_episode_id"] = AndroidTypedEntry("string", lastEp.id)
                this["${p}_episode_title"] = AndroidTypedEntry("string", lastEp.displayTitle)
                this["${p}_season_num"] = AndroidTypedEntry("int", seasonNumber)
                this["${p}_episode_num"] = AndroidTypedEntry("int", lastEp.episodeNumber)
                this["${p}_pos_ms"] = AndroidTypedEntry("long", lastEp.durationMs.coerceAtLeast(1000L))
                this["${p}_dur_ms"] = AndroidTypedEntry("long", lastEp.durationMs.coerceAtLeast(1000L))
                this["${p}_fraction"] = AndroidTypedEntry("float", 1.0f)
                this["${p}_timestamp"] = AndroidTypedEntry("long", now)
                if (lastEp.thumbnailUrl.isNotBlank()) {
                    this["${p}_thumb"] = AndroidTypedEntry("string", lastEp.thumbnailUrl)
                }
            }
        }
        watchHistorySync.executeDirectUpload(SyncPriority.CRITICAL_WATCH_STATE)
    }

    suspend fun markSeasonAsIncomplete(
        seriesId: String,
        seasonNumber: Int,
        episodes: List<Episode>
    ) = withContext(NonCancellable + Dispatchers.IO) {
        if (episodes.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val epIdSet = episodes.map { it.id }.toSet()
        AndroidWatchHistoryManager.updateEntries {
            episodes.forEach { ep ->
                this["${ep.id}_is_watched"] = AndroidTypedEntry("boolean", false)
                this["${ep.id}_pos"] = AndroidTypedEntry("long", 0L)
                this["${ep.id}_fraction"] = AndroidTypedEntry("float", 0.0f)
                this["${ep.id}_timestamp"] = AndroidTypedEntry("long", now)
            }
            val p = "series_resume_${seriesId}"
            val resumeEpId = this["${p}_episode_id"]?.value as? String
            if (resumeEpId != null && epIdSet.contains(resumeEpId)) {
                this["${p}_pos_ms"] = AndroidTypedEntry("long", 0L)
                this["${p}_fraction"] = AndroidTypedEntry("float", 0.0f)
                this["${p}_timestamp"] = AndroidTypedEntry("long", now)
            }
        }
        watchHistorySync.executeDirectUpload(SyncPriority.CRITICAL_WATCH_STATE)
    }

    suspend fun markEpisodeUnwatched(
        seriesId: String,
        seriesTitle: String,
        episodeId: String,
        episodeTitle: String,
        seasonNumber: Int,
        episodeNumber: Int
    ) = withContext(NonCancellable + Dispatchers.IO) {
        if (episodeId.isBlank()) return@withContext
        val now = System.currentTimeMillis()
        val wasArchived = AndroidWatchHistoryManager.snapshotArchivedIds().contains(episodeId)
        if (wasArchived) {
            AndroidWatchHistoryManager.removeEntriesForMedia(episodeId, null)
        }

        AndroidWatchHistoryManager.updateEntries {
            this["${episodeId}_is_watched"] = AndroidTypedEntry("boolean", false)
            this["${episodeId}_fraction"] = AndroidTypedEntry("float", 0.0f)
            this["${episodeId}_pos"] = AndroidTypedEntry("long", 0L)
            this["${episodeId}_timestamp"] = AndroidTypedEntry("long", now)

            if (seriesId.isNotBlank()) {
                val p = "series_resume_${seriesId}"
                this["${p}_series_title"] = AndroidTypedEntry("string", seriesTitle)
                this["${p}_episode_id"] = AndroidTypedEntry("string", episodeId)
                this["${p}_episode_title"] = AndroidTypedEntry("string", episodeTitle)
                this["${p}_season_num"] = AndroidTypedEntry("int", seasonNumber)
                this["${p}_episode_num"] = AndroidTypedEntry("int", episodeNumber)
                this["${p}_pos_ms"] = AndroidTypedEntry("long", 0L)
                this["${p}_fraction"] = AndroidTypedEntry("float", 0.0f)
                this["${p}_timestamp"] = AndroidTypedEntry("long", now)
            }
        }

        watchHistorySync.executeDirectUpload(
            priority = SyncPriority.CRITICAL_WATCH_STATE,
            updateArchive = wasArchived
        )
    }

    suspend fun markEpisodeUnwatched(
        seriesId: String,
        episodeId: String
    ) = withContext(NonCancellable + Dispatchers.IO) {
        val entries = AndroidWatchHistoryManager.snapshotEntries()
        val sTitle = (entries["${episodeId}_series_title"]?.value as? String)
            ?: (entries["series_resume_${seriesId}_series_title"]?.value as? String)
            ?: "Series"
        val epTitle = (entries["${episodeId}_title"]?.value as? String)
            ?: (entries["series_resume_${seriesId}_episode_title"]?.value as? String)
            ?: "Episode"
        val sNum = (entries["${episodeId}_season_num"]?.value as? Number)?.toInt()
            ?: (entries["series_resume_${seriesId}_season_num"]?.value as? Number)?.toInt()
            ?: 1
        val eNum = (entries["${episodeId}_episode_num"]?.value as? Number)?.toInt()
            ?: (entries["series_resume_${seriesId}_episode_num"]?.value as? Number)?.toInt()
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

    suspend fun markSingleEpisodeIncomplete(episodeId: String) = withContext(NonCancellable + Dispatchers.IO) {
        if (episodeId.isBlank()) return@withContext
        val now = System.currentTimeMillis()
        val wasArchived = AndroidWatchHistoryManager.snapshotArchivedIds().contains(episodeId)
        if (wasArchived) {
            AndroidWatchHistoryManager.removeEntriesForMedia(episodeId, null)
        }

        AndroidWatchHistoryManager.updateEntries {
            this["${episodeId}_is_watched"] = AndroidTypedEntry("boolean", false)
            this["${episodeId}_pos"] = AndroidTypedEntry("long", 0L)
            this["${episodeId}_fraction"] = AndroidTypedEntry("float", 0.0f)
            this["${episodeId}_timestamp"] = AndroidTypedEntry("long", now)
        }

        watchHistorySync.executeDirectUpload(
            priority = SyncPriority.CRITICAL_WATCH_STATE,
            updateArchive = wasArchived
        )
    }

    suspend fun getLastWatchedEpisode(series: Series): Episode? = withContext(Dispatchers.IO) {
        val entries = AndroidWatchHistoryManager.snapshotEntries()
        val allEpisodes = series.seasons.flatMap { it.episodes }
        if (allEpisodes.isEmpty()) return@withContext null

        val p = "series_resume_${series.id}"
        val resumeEpId = entries["${p}_episode_id"]?.value as? String
        if (!resumeEpId.isNullOrBlank()) {
            val matched = allEpisodes.firstOrNull { it.id == resumeEpId }
            if (matched != null) return@withContext matched
        }

        var latestEp: Episode? = null
        var latestTs = 0L
        for (ep in allEpisodes) {
            val ts = (entries["${ep.id}_timestamp"]?.value as? Number)?.toLong() ?: 0L
            if (ts > latestTs) {
                latestTs = ts
                latestEp = ep
            }
        }
        latestEp
    }

    suspend fun deleteItemHistory(contentId: String, seriesId: String? = null) =
        withContext(NonCancellable + Dispatchers.IO) {
            val removedFromArchive = AndroidWatchHistoryManager.removeEntriesForMedia(contentId, seriesId)
            watchHistorySync.executeDirectUpload(
                priority = SyncPriority.CRITICAL_WATCH_STATE,
                updateArchive = removedFromArchive
            )
        }

    suspend fun clearProgress(fileId: String) = deleteItemHistory(fileId)

    suspend fun deleteHistoryItem(fileId: String) = deleteItemHistory(fileId)

    suspend fun clearEntireWatchHistory() = withContext(NonCancellable + Dispatchers.IO) {
        AndroidWatchHistoryManager.clearInMemory()
        val deletedFromDrive = watchHistorySync.deleteRemoteHistoryFiles()
        if (deletedFromDrive) {
            Log.i("ClotubeSync", "All watch history wiped in RAM and removed from Drive")
        } else {
            Log.w("ClotubeSync", "RAM watch history wiped, but failed to delete files from Drive")
        }
    }

    suspend fun clearAllHistory() = clearEntireWatchHistory()

    companion object {
        @JvmStatic
        fun getResumePosition(
            @Suppress("UNUSED_PARAMETER") context: Context,
            @Suppress("UNUSED_PARAMETER") userEmail: String,
            fileId: String,
            durationMs: Long
        ): Long {
            if (fileId.isBlank()) return 0L
            val entries = AndroidWatchHistoryManager.snapshotEntries()
            val pos = (entries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
            val dur = if (durationMs > 0L) durationMs else (entries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
            val fraction = (entries["${fileId}_fraction"]?.value as? Number)?.toFloat() ?: 0f
            val isWatched = AndroidWatchHistoryManager.isEpisodeWatched(fileId) || fraction >= 0.90f

            return when {
                pos > 5000L && (dur == 0L || pos < dur - 15000L) -> pos
                isWatched -> 0L
                pos > 3000L -> pos
                else -> 0L
            }
        }
    }
}
