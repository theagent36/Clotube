package com.clotube.app.data

import android.net.Uri
import android.util.Log
import com.clotube.app.auth.GoogleAuthRepository
import com.clotube.app.model.Episode
import com.clotube.app.model.ExternalSubtitleTrack
import com.clotube.app.model.GenreResolver
import com.clotube.app.model.Season
import com.clotube.app.model.TvSeries
import com.clotube.app.player.DriveMediaSourceFactory
import com.clotube.app.viewmodel.formatTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 100% Folder-driven Google Drive Series Repository.
 *
 * Enforces a STRICT Google Drive 2-level folder hierarchy:
 * 1. Show Folder (e.g., 'The Mentalist') contains Season Folders ('Season 1', 'Season 2').
 * 2. Season Folders contain the actual episode video files.
 */
class SeriesRepository(
    private val authRepository: GoogleAuthRepository,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "SeriesRepository"
        private const val BASE_DRIVE_API = "https://www.googleapis.com/drive/v3/files"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

        private val EPISODE_REGEX_PRIMARY = """(?i)(?:s|season\s*)(\d{1,2})[\s._-]*(?:e|ep|x)?(\d{1,2})""".toRegex()
        private val EPISODE_REGEX_ALT = """(?i)(?:e|ep|episode|\b)(\d{1,3})""".toRegex()
    }

    /**
     * 1. Dynamic Season Discovery (Strict Child Folders):
     * Query ONLY subfolders directly inside the show's folder.
     */
    suspend fun getSeasonsForShow(showFolderId: String): List<Season> = withContext(Dispatchers.IO) {
        if (showFolderId.isBlank() || showFolderId == "root") {
            Log.w(TAG, "Fail-Closed: showFolderId is blank or 'root'. Returning emptyList().")
            return@withContext emptyList()
        }

        val token = authRepository.getAccessToken().getOrNull()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "No valid access token available for getSeasonsForShow")
            return@withContext emptyList()
        }

        val query = "'$showFolderId' in parents and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
        val fields = "files(id, name)"

        val url = Uri.parse(BASE_DRIVE_API).buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("fields", fields)
            .appendQueryParameter("pageSize", "100")
            .appendQueryParameter("supportsAllDrives", "true")
            .appendQueryParameter("includeItemsFromAllDrives", "true")
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Clotube/1.0")
            .get()
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (!res.isSuccessful) {
                    Log.e(TAG, "Failed to list seasons for show $showFolderId: ${res.code}")
                    return@withContext emptyList()
                }

                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val files = json.optJSONArray("files") ?: return@withContext emptyList()

                val seasons = mutableListOf<Season>()
                for (i in 0 until files.length()) {
                    val folder = files.getJSONObject(i)
                    val folderId = folder.getString("id")
                    val folderName = folder.getString("name")

                    // Extract season number from folder name (e.g., "Season 1" -> 1, "Season 02" -> 2)
                    val seasonNum = Regex("""\d+""").find(folderName)?.value?.toIntOrNull() ?: (i + 1)
                    seasons.add(
                        Season(
                            id = folderId,
                            seasonNumber = seasonNum,
                            seasonTitle = folderName
                        )
                    )
                }

                val sortedSeasons = seasons.sortedBy { it.seasonNumber }
                if (sortedSeasons.isEmpty()) {
                    // Check if this show folder contains direct episode video files in its root
                    val directEpisodes = getEpisodesForSeason(showFolderId, 1)
                    if (directEpisodes.isNotEmpty()) {
                        Log.d(TAG, "Show folder $showFolderId contains ${directEpisodes.size} flat episodes in root; creating virtual Season 1")
                        return@withContext listOf(
                            Season(
                                id = showFolderId,
                                seasonNumber = 1,
                                seasonTitle = "Season 1",
                                episodes = directEpisodes
                            )
                        )
                    }
                }
                Log.d(TAG, "Discovered ${sortedSeasons.size} seasons for show folder $showFolderId: ${sortedSeasons.map { it.seasonTitle }}")
                sortedSeasons
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting seasons for show $showFolderId", e)
            emptyList()
        }
    }

    /**
     * 2. Dynamic Episode Discovery (Strict Child Files of that Season Folder):
     * Query ONLY video files directly inside that specific season folder.
     * Episodes from Season 2 must NEVER bleed into Season 1.
     */
    suspend fun getEpisodesForSeason(
        seasonFolderId: String,
        seasonNumber: Int,
        seriesTitle: String? = null
    ): List<Episode> = withContext(Dispatchers.IO) {
        if (seasonFolderId.isBlank() || seasonFolderId == "root") {
            return@withContext emptyList()
        }

        val token = authRepository.getAccessToken().getOrNull()
        if (token.isNullOrBlank()) {
            return@withContext emptyList()
        }

        val query = "'$seasonFolderId' in parents and trashed = false"
        val fields = "files(id, name, size, mimeType, thumbnailLink, videoMediaMetadata)"

        val url = Uri.parse(BASE_DRIVE_API).buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("fields", fields)
            .appendQueryParameter("pageSize", "100")
            .appendQueryParameter("supportsAllDrives", "true")
            .appendQueryParameter("includeItemsFromAllDrives", "true")
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Clotube/1.0")
            .get()
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (!res.isSuccessful) {
                    Log.e(TAG, "Failed to list episodes for season $seasonFolderId: ${res.code}")
                    return@withContext emptyList()
                }

                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val files = json.optJSONArray("files") ?: return@withContext emptyList()

                val videoFiles = mutableListOf<JSONObject>()
                val subtitleFiles = mutableListOf<JSONObject>()
                var subsFolderId: String? = null

                for (i in 0 until files.length()) {
                    val fileObj = files.getJSONObject(i)
                    val mime = fileObj.optString("mimeType", "")
                    val name = fileObj.optString("name", "")
                    when {
                        mime.startsWith("video/") -> videoFiles.add(fileObj)
                        DriveFolderManager.isSubtitleFile(name, mime) -> subtitleFiles.add(fileObj)
                        mime == DriveFolderManager.FOLDER_MIME_TYPE && (name.equals("subs", ignoreCase = true) || name.equals("subtitles", ignoreCase = true)) -> {
                            subsFolderId = fileObj.getString("id")
                        }
                    }
                }

                if (!subsFolderId.isNullOrBlank()) {
                    subtitleFiles.addAll(fetchSubtitlesFromFolder(subsFolderId, token))
                }

                val episodes = mutableListOf<Episode>()
                for ((i, fileObj) in videoFiles.withIndex()) {
                    val rawName = fileObj.optString("name", "")
                    val videoStem = rawName.substringBeforeLast('.')

                    // Extract episode number
                    val primaryMatch = EPISODE_REGEX_PRIMARY.find(rawName)
                    val altMatch = EPISODE_REGEX_ALT.find(rawName)
                    val epNum = primaryMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                        ?: altMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: (i + 1)

                    val matchedSubs = subtitleFiles.filter { subObj ->
                        val subName = subObj.optString("name", "")
                        val epTagDouble = String.format(Locale.US, "E%02d", epNum)
                        val epTagSingle = "E$epNum"
                        subName.contains(epTagDouble, ignoreCase = true) ||
                                subName.contains(epTagSingle, ignoreCase = true) ||
                                subName.startsWith(videoStem, ignoreCase = true)
                    }.map { subObj ->
                        val subId = subObj.getString("id")
                        val subName = subObj.optString("name", "subtitles.srt")
                        val (langCode, label) = DriveFolderManager.resolveSubtitleLanguage(subName)
                        val subMime = DriveFolderManager.resolveSubtitleMimeType(subName)
                        val streamUri = DriveMediaSourceFactory.buildDriveStreamUri(subId).toString()
                        ExternalSubtitleTrack(
                            fileId = subId,
                            fileName = subName,
                            languageCode = langCode,
                            label = label,
                            mimeType = subMime,
                            streamUri = streamUri
                        )
                    }

                    episodes.add(parseEpisodeFromFile(fileObj, seasonNumber, epNum, matchedSubs, seriesTitle))
                }

                val sortedEpisodes = episodes.sortedBy { it.episodeNumber }
                Log.d(TAG, "Discovered ${sortedEpisodes.size} episodes for Season $seasonNumber in folder $seasonFolderId")
                sortedEpisodes
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception getting episodes for season $seasonFolderId", e)
            emptyList()
        }
    }

    /**
     * Resolves the complete TV series structure from Google Drive folders.
     */
    suspend fun getShowDetails(showFolderId: String, showTitle: String? = null): TvSeries = withContext(Dispatchers.IO) {
        val rawSeriesTitle = showTitle?.ifBlank { null } ?: fetchFolderName(showFolderId) ?: "TV Series"
        val seriesMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveSeries(rawSeriesTitle)
        val resolvedTitle = seriesMeta.officialTitle
        val seasons = getSeasonsForShow(showFolderId)

        // Populate episodes for each discovered season folder
        val populatedSeasons = seasons.map { season ->
            if (season.episodes.isNotEmpty()) {
                season
            } else {
                val episodes = getEpisodesForSeason(season.id, season.seasonNumber, resolvedTitle)
                season.copy(episodes = episodes)
            }
        }

        val firstEp = populatedSeasons.firstOrNull()?.episodes?.firstOrNull()
        val totalEps = populatedSeasons.sumOf { it.episodes.size }
        val allEpisodes = populatedSeasons.flatMap { it.episodes }
        val maxResolutionBadge = allEpisodes.map { it.resolutionBadge }.maxByOrNull { badge ->
            when {
                badge.contains("4K", ignoreCase = true) -> 5
                badge.contains("1440", ignoreCase = true) -> 4
                badge.contains("1080", ignoreCase = true) -> 3
                badge.contains("720", ignoreCase = true) -> 2
                badge.contains("SD", ignoreCase = true) -> 1
                else -> 0
            }
        } ?: "HD"

        val resolvedGenre = seriesMeta.genres
        val finalPoster = seriesMeta.posterUrl.ifBlank { firstEp?.thumbnailUrl ?: "" }
        val finalBackdrop = seriesMeta.posterUrl.ifBlank { firstEp?.thumbnailUrl ?: "" }

        val totalSeasonsFormatted = if (populatedSeasons.size == 1) "1 Season" else "${populatedSeasons.size} Seasons"

        TvSeries(
            id = showFolderId,
            title = resolvedTitle,
            genreFormatted = resolvedGenre,
            totalSeasonsFormatted = totalSeasonsFormatted,
            seasons = populatedSeasons,
            thumbnailUrl = finalPoster.ifBlank { finalBackdrop },
            backdropUrl = finalBackdrop,
            posterUrl = finalPoster,
            synopsis = "Collection of $totalEps episodes across ${populatedSeasons.size} season(s) streamed directly from Google Drive in uncompressed high bitrate.",
            qualityBadge = "$maxResolutionBadge • Direct Stream",
            ratingBadge = "TV-14"
        )
    }

    private suspend fun fetchFolderName(folderId: String): String? = withContext(Dispatchers.IO) {
        val token = authRepository.getAccessToken().getOrNull() ?: return@withContext null
        val url = Uri.parse("$BASE_DRIVE_API/$folderId").buildUpon()
            .appendQueryParameter("fields", "name")
            .appendQueryParameter("supportsAllDrives", "true")
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Clotube/1.0")
            .get()
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (!res.isSuccessful) return@withContext null
                val json = JSONObject(res.body?.string().orEmpty())
                json.optString("name").ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchSubtitlesFromFolder(folderId: String, token: String): List<JSONObject> {
        val query = "'$folderId' in parents and trashed = false"
        val fields = "files(id, name, mimeType)"

        val url = Uri.parse(BASE_DRIVE_API).buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("fields", fields)
            .appendQueryParameter("pageSize", "50")
            .appendQueryParameter("supportsAllDrives", "true")
            .appendQueryParameter("includeItemsFromAllDrives", "true")
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Clotube/1.0")
            .get()
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (!res.isSuccessful) return emptyList()
                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val files = json.optJSONArray("files") ?: return emptyList()

                val subs = mutableListOf<JSONObject>()
                for (i in 0 until files.length()) {
                    val obj = files.getJSONObject(i)
                    val name = obj.optString("name", "")
                    val mime = obj.optString("mimeType", "")
                    if (DriveFolderManager.isSubtitleFile(name, mime)) {
                        subs.add(obj)
                    }
                }
                subs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching nested subtitles from folder $folderId", e)
            emptyList()
        }
    }

    private suspend fun parseEpisodeFromFile(
        obj: JSONObject,
        seasonNumber: Int,
        defaultEpisodeNum: Int,
        externalSubtitles: List<ExternalSubtitleTrack> = emptyList(),
        seriesTitle: String? = null
    ): Episode {
        val id = obj.getString("id")
        val rawName = obj.optString("name", "Episode $defaultEpisodeNum")
        val sizeBytes = obj.optLong("size", 0L)
        val rawThumb = if (obj.has("thumbnailLink")) obj.optString("thumbnailLink") else null
        val thumb = rawThumb?.replace("=s220", "=s800")

        val videoMeta = obj.optJSONObject("videoMediaMetadata")
        val width = videoMeta?.optInt("width", 0) ?: 0
        val height = videoMeta?.optInt("height", 0) ?: 0
        val durationMillis = videoMeta?.optLong("durationMillis", 0L) ?: 0L
        val rawResolution = DriveFolderManager.getResolutionLabel(width.toLong(), height.toLong())
        val resolutionBadge = when {
            height >= 2160 || width >= 3840 || rawResolution.contains("4K", ignoreCase = true) || rawResolution.contains("2160", ignoreCase = true) -> "4K UHD"
            height >= 1440 || width >= 2560 || rawResolution.contains("1440", ignoreCase = true) -> "1440p QHD"
            height >= 1080 || width >= 1920 || rawResolution.contains("1080", ignoreCase = true) || rawResolution.contains("FHD", ignoreCase = true) -> "1080p FHD"
            height >= 720 || width >= 1280 || rawResolution.contains("720", ignoreCase = true) -> "720p HD"
            height > 0 -> "${height}p"
            rawResolution.isNotBlank() && rawResolution != "HD" -> rawResolution
            else -> "1080p FHD"
        }

        // Extract episode number (e.g. S01E04 -> 4, or Ep 04 -> 4, or 1x04 -> 4)
        val primaryMatch = EPISODE_REGEX_PRIMARY.find(rawName)
        val altMatch = EPISODE_REGEX_ALT.find(rawName)
        val episodeNum = primaryMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
            ?: altMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: defaultEpisodeNum

        val sTitle = seriesTitle.orEmpty()
        val epMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveEpisode(
            seriesName = sTitle,
            tvMazeSeriesId = 0L,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNum,
            rawFileName = rawName
        )
        val displayTitle = epMeta.officialTitle
        val episodeThumbnail = epMeta.thumbnailUrl.ifBlank { thumb ?: "" }
        val episodeTagFormatted = String.format(Locale.US, "S%02d:E%02d", seasonNumber, episodeNum)
        val durationFormatted = if (durationMillis > 0L) {
            formatTimestamp(durationMillis)
        } else if (epMeta.durationMinutes > 0L) {
            "${epMeta.durationMinutes}:00"
        } else {
            "45:00"
        }
        val fileSizeFormatted = formatFileSize(sizeBytes)
        val streamUri = DriveMediaSourceFactory.buildDriveStreamUri(id).toString()

        return Episode(
            id = id,
            seasonNumber = seasonNumber, // Bound strictly to parent season folder!
            episodeNumber = episodeNum,
            episodeTagFormatted = episodeTagFormatted,
            displayTitle = displayTitle,
            durationFormatted = durationFormatted,
            fileSizeFormatted = fileSizeFormatted,
            resolutionBadge = resolutionBadge,
            streamUri = streamUri,
            thumbnailUrl = episodeThumbnail,
            watchProgressFraction = 0f,
            overview = "Direct uncompressed stream from Google Drive Season $seasonNumber directory.",
            externalSubtitles = externalSubtitles,
            width = width,
            height = height,
            seriesTitle = sTitle,
            sizeBytes = sizeBytes
        )
    }

    private fun cleanEpisodeTitle(fileName: String): String {
        return fileName
            .replace(Regex("""\.(mkv|mp4|webm|mov|avi)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""(?i)[._-](1080p|720p|2160p|4k|60fps|bluray|web-dl|hevc|x265|x264|aac|dts).*"""), "")
            .replace(".", " ")
            .replace("_", " ")
            .trim()
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.1f KB", kb)
        }
    }
}
