package com.clotube.app.data.scanner

import android.util.Log
import com.clotube.app.data.metadata.OnlineMetadataResolver
import com.clotube.app.model.Episode
import com.clotube.app.model.Movie
import com.clotube.app.model.Season
import com.clotube.app.model.Series
import com.clotube.app.model.TvSeries
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.Locale
import java.util.regex.Pattern

object DriveCatalogScanner {

    private const val TAG = "DriveCatalogScanner"
    private const val TEST_TAG = "ClotubeScannerTest"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    private const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"

    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts")

    // Flexible regex patterns for detecting season and episode numbers
    private val S_E_PATTERN = Pattern.compile("(?i)(?:s|season\\s*)(\\d{1,2})[\\s._-]*(?:e|ep|episode\\s*)(\\d{1,3})")
    private val S_DASH_E_PATTERN = Pattern.compile("(?i)(?:s|season\\s*)(\\d{1,2})\\s*-\\s*(\\d{1,3})")
    private val X_PATTERN = Pattern.compile("(?i)(\\d{1,2})x(\\d{1,3})")
    private val SEASON_FOLDER_PATTERN = Pattern.compile("(?i)(?:season|series|s)\\s*(\\d{1,2})")
    private val EPISODE_FALLBACK_PATTERN = Pattern.compile("(?i)(?:e|ep|episode)\\s*(\\d{1,3})")
    private val EPISODE_STANDALONE_PATTERN = Pattern.compile("(?i)(?:^|[^\\d])(\\d{1,3})(?:[^\\d]|$)")

    var driveServiceProvider: (suspend () -> Drive?)? = null

    suspend fun getDriveService(): Drive? = driveServiceProvider?.invoke()

    // ========================================================
    // 1. SCAN MOVIES (DIRECT FOLDER & RECURSIVE)
    // ========================================================

    suspend fun scanMoviesFolder(moviesFolderId: String): List<Movie> = withContext(Dispatchers.IO) {
        val driveService = getDriveService() ?: return@withContext emptyList()
        scanMoviesFolder(driveService, moviesFolderId)
    }

    suspend fun scanMoviesFolder(driveService: Drive, moviesFolderId: String): List<Movie> = withContext(Dispatchers.IO) {
        try {
            val files = fetchFolderContents(driveService, moviesFolderId).filter { isVideoFile(it) }

            files.mapNotNull { file ->
                val fileId = file.id ?: return@mapNotNull null
                val rawName = file.name ?: "Unknown Movie"
                val rawDurationMs = file.videoMediaMetadata?.durationMillis ?: 0L
                val driveThumb = file.thumbnailLink?.takeIf { it.isNotBlank() } ?: ""

                // Resolve official movie title, high-res poster, and genre
                val movieMeta = OnlineMetadataResolver.resolveMovie(rawName)

                Movie(
                    id = fileId,
                    title = movieMeta.officialTitle,
                    durationMs = rawDurationMs,
                    formattedDuration = formatDuration(rawDurationMs),
                    resolution = "1080p FHD",
                    thumbnailUrl = movieMeta.posterUrl.ifBlank { driveThumb }
                )
            }.sortedBy { it.title }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning movies: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun scanMoviesRecursively(
        driveService: Drive,
        rootMoviesFolderId: String
    ): List<Movie> = withContext(Dispatchers.IO) {
        val discoveredMovies = mutableListOf<Movie>()
        val folderQueue = ArrayDeque<Pair<String, String>>() // Pair(FolderId, FolderName)
        val visitedFolderIds = mutableSetOf<String>()

        folderQueue.add(Pair(rootMoviesFolderId, "Movies"))
        visitedFolderIds.add(rootMoviesFolderId)

        Log.d(TAG, "Starting recursive Movies scan from root: $rootMoviesFolderId")

        while (folderQueue.isNotEmpty()) {
            val (currentFolderId, currentFolderName) = folderQueue.poll() ?: break
            val items = fetchFolderContents(driveService, currentFolderId)

            for (item in items) {
                val resolvedId = resolveTargetId(item)
                val isFolder = item.mimeType == FOLDER_MIME || 
                               (item.mimeType == SHORTCUT_MIME && item.shortcutDetails?.targetMimeType == FOLDER_MIME)

                if (isFolder) {
                    if (visitedFolderIds.add(resolvedId)) {
                        folderQueue.add(Pair(resolvedId, item.name ?: "Untitled Folder"))
                    }
                } else if (isVideoFile(item)) {
                    val rawBytes = item.getSize()?.toLong() ?: 0L
                    val rawDurationMs = item.videoMediaMetadata?.durationMillis ?: 0L
                    val driveThumb = item.thumbnailLink?.takeIf { it.isNotBlank() } ?: ""

                    // Resolve official movie title, high-res poster, and genre via OnlineMetadataResolver
                    val movieMeta = OnlineMetadataResolver.resolveMovie(item.name.orEmpty())

                    val movie = Movie(
                        id = resolvedId,
                        title = movieMeta.officialTitle,
                        fileName = item.name.orEmpty(),
                        fileSize = formatFileSize(rawBytes),
                        mimeType = item.mimeType ?: "video/mp4",
                        thumbnailUrl = movieMeta.posterUrl.ifBlank { driveThumb },
                        parentFolder = currentFolderName,
                        durationFormatted = if (rawDurationMs > 0L) formatDuration(rawDurationMs) else "00:00",
                        resolutionBadge = "1080p FHD",
                        genre = movieMeta.genre,
                        sizeBytes = rawBytes
                    )
                    discoveredMovies.add(movie)
                    Log.d(TAG, "Discovered Movie: rawName='${item.name}', officialTitle='${movie.title}', poster='${movie.thumbnailUrl}'")
                }
            }
        }

        Log.d(TAG, "Completed Movies scan. Total movies discovered: ${discoveredMovies.size}")
        discoveredMovies.sortedBy { it.title }
    }

    // ========================================================
    // 2. SCAN TV SERIES (DIRECT FOLDER & RECURSIVE)
    // ========================================================

    suspend fun scanSeriesFolder(seriesFolderId: String): List<Series> = withContext(Dispatchers.IO) {
        val driveService = getDriveService() ?: return@withContext emptyList()
        scanSeriesFolder(driveService, seriesFolderId)
    }

    suspend fun scanSeriesFolder(driveService: Drive, seriesFolderId: String): List<Series> = withContext(Dispatchers.IO) {
        try {
            val seriesFoldersQuery = "'$seriesFolderId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"

            val seriesResponse = driveService.files().list()
                .setQ(seriesFoldersQuery)
                .setFields("files(id, name, thumbnailLink)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setPageSize(50)
                .execute()

            val seriesFolders = seriesResponse.files ?: emptyList()

            seriesFolders.mapNotNull { showFolder ->
                val showId = showFolder.id ?: return@mapNotNull null
                val rawShowName = showFolder.name ?: "Unknown Series"
                val drivePoster = showFolder.thumbnailLink?.takeIf { it.isNotBlank() } ?: ""

                // Resolve official Show Title, Poster Art, and TVMaze ID
                val seriesMeta = OnlineMetadataResolver.resolveSeries(rawShowName)

                // Scan Season Folders
                val seasonsQuery = "'$showId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
                val seasonsResponse = driveService.files().list()
                    .setQ(seasonsQuery)
                    .setFields("files(id, name)")
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .execute()

                val seasonFolders = seasonsResponse.files ?: emptyList()
                val parsedSeasons = mutableListOf<Season>()

                seasonFolders.forEach { seasonFolder ->
                    val seasonFolderId = seasonFolder.id ?: return@forEach
                    val seasonFolderName = seasonFolder.name ?: "Season 1"
                    val seasonNumMatcher = Pattern.compile("\\d+").matcher(seasonFolderName)
                    val seasonNumber = if (seasonNumMatcher.find()) seasonNumMatcher.group().toIntOrNull() ?: 1 else 1

                    // Scan Episode Video Files inside Season Folder
                    val epFiles = fetchFolderContents(driveService, seasonFolderId).filter { isVideoFile(it) }

                    val episodes = epFiles.mapNotNull { epFile ->
                        val epFileId = epFile.id ?: return@mapNotNull null
                        val rawEpName = epFile.name ?: "Episode"
                        val epDriveThumb = epFile.thumbnailLink?.takeIf { it.isNotBlank() } ?: ""
                        val durationMs = epFile.videoMediaMetadata?.durationMillis ?: 0L
                        val fileSizeFormatted = formatFileSize(epFile.getSize()?.toLong() ?: epFile.size?.toLong() ?: 0L)

                        // Parse Season & Episode numbers
                        val parsedNumbers = OnlineMetadataResolver.parseSeasonAndEpisodeNumbers(rawEpName)
                        val epNumber = parsedNumbers?.second ?: 1
                        val actualSeasonNumber = parsedNumbers?.first ?: seasonNumber

                        // Resolve official Episode Title and Episode Thumbnail from TVMaze
                        val epMeta = OnlineMetadataResolver.resolveEpisode(
                            seriesName = seriesMeta.officialTitle,
                            tvMazeSeriesId = seriesMeta.tvMazeId,
                            seasonNumber = actualSeasonNumber,
                            episodeNumber = epNumber,
                            rawFileName = rawEpName
                        )

                        Episode(
                            id = epFileId,
                            seasonNumber = actualSeasonNumber,
                            episodeNumber = epNumber,
                            displayTitle = epMeta.officialTitle,
                            formattedDuration = if (durationMs > 0L) formatDuration(durationMs) else if (epMeta.durationMinutes > 0L) "${epMeta.durationMinutes} min" else "42:00",
                            fileSize = fileSizeFormatted,
                            resolution = "1080p FHD",
                            thumbnailUrl = epMeta.thumbnailUrl.ifBlank { epDriveThumb }
                        )
                    }.sortedBy { it.episodeNumber }

                    parsedSeasons.add(
                        Season(
                            id = seasonFolderId,
                            seasonNumber = seasonNumber,
                            seasonTitle = "Season $seasonNumber",
                            episodes = episodes
                        )
                    )
                }

                parsedSeasons.sortBy { it.seasonNumber }
                val totalEpisodesCount = parsedSeasons.sumOf { it.episodes.size }

                Series(
                    id = showId,
                    title = seriesMeta.officialTitle,
                    posterUrl = seriesMeta.posterUrl.ifBlank { drivePoster },
                    genre = seriesMeta.genres,
                    seasons = parsedSeasons,
                    totalEpisodes = totalEpisodesCount
                )
            }.sortedBy { it.title }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning series: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun scanSeriesRecursively(
        driveService: Drive,
        rootSeriesFolderId: String
    ): List<TvSeries> = withContext(Dispatchers.IO) {
        val discoveredSeriesList = mutableListOf<TvSeries>()
        val topSeriesItems = fetchFolderContents(driveService, rootSeriesFolderId)

        for (seriesFolder in topSeriesItems) {
            val isFolder = seriesFolder.mimeType == FOLDER_MIME || 
                           (seriesFolder.mimeType == SHORTCUT_MIME && seriesFolder.shortcutDetails?.targetMimeType == FOLDER_MIME)
            if (!isFolder) continue

            val seriesId = resolveTargetId(seriesFolder)
            val rawShowName = seriesFolder.name ?: "Untitled Series"
            val drivePoster = seriesFolder.thumbnailLink?.takeIf { it.isNotBlank() } ?: ""

            // Resolve official Show Title, Poster Art, and TVMaze ID
            val seriesMeta = OnlineMetadataResolver.resolveSeries(rawShowName)

            val episodesFound = mutableListOf<Episode>()
            val discoveredSeasons = mutableMapOf<Int, Season>()

            // Traverse series subfolders (e.g. Series -> The Mentalist -> Season 1 -> S01E08.mkv)
            val subFolderQueue = ArrayDeque<Pair<String, String>>()
            val visitedSubs = mutableSetOf<String>()
            subFolderQueue.add(Pair(seriesId, seriesMeta.officialTitle))
            visitedSubs.add(seriesId)

            while (subFolderQueue.isNotEmpty()) {
                val (currentSubId, currentSubName) = subFolderQueue.poll() ?: break
                val childItems = fetchFolderContents(driveService, currentSubId)

                for (child in childItems) {
                    val resolvedChildId = resolveTargetId(child)
                    val isChildFolder = child.mimeType == FOLDER_MIME || 
                                       (child.mimeType == SHORTCUT_MIME && child.shortcutDetails?.targetMimeType == FOLDER_MIME)

                    if (isChildFolder) {
                        if (visitedSubs.add(resolvedChildId)) {
                            val folderName = child.name ?: "Untitled Season"
                            val seasonNumMatcher = Pattern.compile("(?i)(?:season|series|s)\\s*(\\d+)").matcher(folderName)
                            val seasonNum = if (seasonNumMatcher.find()) seasonNumMatcher.group(1)?.toIntOrNull() else null
                            if (seasonNum != null && !discoveredSeasons.containsKey(seasonNum)) {
                                discoveredSeasons[seasonNum] = Season(
                                    id = resolvedChildId,
                                    seasonNumber = seasonNum,
                                    seasonTitle = "Season $seasonNum",
                                    episodes = emptyList()
                                )
                            }
                            subFolderQueue.add(Pair(resolvedChildId, folderName))
                        }
                    } else if (isVideoFile(child)) {
                        val rawBytes = child.getSize()?.toLong() ?: 0L
                        val rawDurationMs = child.videoMediaMetadata?.durationMillis ?: 0L
                        val epDriveThumb = child.thumbnailLink?.takeIf { it.isNotBlank() } ?: ""
                        val fileSizeFormatted = formatFileSize(rawBytes)

                        // Parse Season & Episode numbers
                        val parsedNumbers = OnlineMetadataResolver.parseSeasonAndEpisodeNumbers(child.name.orEmpty())
                        val (fallbackSeasonNum, fallbackEpNum) = parseSeasonAndEpisode(child.name.orEmpty(), currentSubName)
                        val actualSeasonNumber = parsedNumbers?.first ?: fallbackSeasonNum
                        val epNumber = parsedNumbers?.second ?: fallbackEpNum

                        // Resolve official Episode Title and Episode Thumbnail from TVMaze
                        val epMeta = OnlineMetadataResolver.resolveEpisode(
                            seriesName = seriesMeta.officialTitle,
                            tvMazeSeriesId = seriesMeta.tvMazeId,
                            seasonNumber = actualSeasonNumber,
                            episodeNumber = epNumber,
                            rawFileName = child.name.orEmpty()
                        )

                        val episode = Episode(
                            id = resolvedChildId,
                            seasonNumber = actualSeasonNumber,
                            episodeNumber = epNumber,
                            displayTitle = epMeta.officialTitle,
                            formattedDuration = if (rawDurationMs > 0L) formatDuration(rawDurationMs) else if (epMeta.durationMinutes > 0L) "${epMeta.durationMinutes} min" else "42:00",
                            fileSize = fileSizeFormatted,
                            resolution = "1080p FHD",
                            thumbnailUrl = epMeta.thumbnailUrl.ifBlank { epDriveThumb }
                        )
                        episodesFound.add(episode)
                        Log.d(TAG, "Discovered Episode: rawName='${child.name}', S${actualSeasonNumber}E${epNumber} title='${episode.displayTitle}', thumb='${episode.thumbnailUrl}'")
                    }
                }
            }

            if (episodesFound.isNotEmpty() || discoveredSeasons.isNotEmpty()) {
                val seasonsGrouped = episodesFound.groupBy { it.seasonNumber }
                val allSeasonNums = (discoveredSeasons.keys + seasonsGrouped.keys).sorted()
                val finalSeasons = allSeasonNums.map { sNum ->
                    val existingSeason = discoveredSeasons[sNum]
                    val eps = seasonsGrouped[sNum]?.sortedBy { it.episodeNumber } ?: emptyList()
                    Season(
                        id = existingSeason?.id ?: "${seriesId}_S$sNum",
                        seasonNumber = sNum,
                        seasonTitle = existingSeason?.seasonTitle ?: "Season $sNum",
                        episodes = eps
                    )
                }

                discoveredSeriesList.add(
                    TvSeries(
                        id = seriesId,
                        title = seriesMeta.officialTitle,
                        posterUrl = seriesMeta.posterUrl.ifBlank { drivePoster },
                        genre = seriesMeta.genres,
                        seasons = finalSeasons,
                        totalEpisodes = episodesFound.size
                    )
                )
                Log.d(TAG, "Cataloged Series: ${seriesMeta.officialTitle} (${finalSeasons.size} seasons, ${episodesFound.size} episodes)")
            }
        }

        discoveredSeriesList.sortedBy { it.title }
    }

    /**
     * Diagnostic test runner for autonomous verification of directory scanner and regex detection.
     */
    suspend fun runDiagnosticTest(
        driveService: Drive,
        moviesFolderId: String,
        seriesFolderId: String? = null
    ) = withContext(Dispatchers.IO) {
        Log.i(TEST_TAG, "========== STARTING SCANNER SELF-TEST ==========")
        if (moviesFolderId.isNotBlank()) {
            Log.i(TEST_TAG, "--- Scanning Movies Root: $moviesFolderId ---")
            val movies = scanMoviesRecursively(driveService, moviesFolderId)
            Log.i(TEST_TAG, "Diagnostic Movies Found: ${movies.size}")
            movies.forEach { m ->
                Log.i(TEST_TAG, " - Movie: '${m.title}', File: '${m.fileName}', Parent: '${m.parentFolder}'")
            }
        }
        if (!seriesFolderId.isNullOrBlank()) {
            Log.i(TEST_TAG, "--- Scanning Series Root: $seriesFolderId ---")
            val seriesList = scanSeriesRecursively(driveService, seriesFolderId)
            Log.i(TEST_TAG, "Diagnostic Series Found: ${seriesList.size}")
            seriesList.forEach { s ->
                val epCount = s.seasons.sumOf { it.episodes.size }
                Log.i(TEST_TAG, " - Series: '${s.title}', Seasons: ${s.seasons.size}, Total Episodes: $epCount")
                s.seasons.forEach { sea ->
                    sea.episodes.forEach { ep ->
                        Log.i(TEST_TAG, "    * S${ep.seasonNumber}E${ep.episodeNumber}: '${ep.title}' (${ep.fileName})")
                    }
                }
            }
        }
        Log.i(TEST_TAG, "========== SCANNER SELF-TEST COMPLETE ==========")
    }

    /**
     * Executes paginated API calls ensuring all items in a folder are retrieved without truncation.
     */
    private fun fetchFolderContents(driveService: Drive, folderId: String): List<File> {
        val resultList = mutableListOf<File>()
        var pageToken: String? = null

        do {
            try {
                val query = "'$folderId' in parents and trashed = false"
                val response = driveService.files().list()
                    .setQ(query)
                    .setFields("nextPageToken, files(id, name, mimeType, size, thumbnailLink, videoMediaMetadata, shortcutDetails)")
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setPageSize(1000)
                    .setPageToken(pageToken)
                    .execute()

                response.files?.let { resultList.addAll(it) }
                pageToken = response.nextPageToken
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch items in folder $folderId: ${e.message}")
                break
            }
        } while (!pageToken.isNullOrBlank())

        return resultList
    }

    internal fun resolveTargetId(file: File): String {
        return if (file.mimeType == SHORTCUT_MIME && file.shortcutDetails?.targetId != null) {
            file.shortcutDetails.targetId
        } else {
            file.id.orEmpty()
        }
    }

    internal fun isVideoFile(file: File): Boolean {
        if (file.mimeType?.startsWith("video/") == true) return true
        val ext = file.name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: ""
        return VIDEO_EXTENSIONS.contains(ext)
    }

    internal fun cleanMovieTitle(fileName: String, parentFolderName: String): String {
        val baseName = fileName.substringBeforeLast(".")
        val genericNames = setOf("movie", "video", "film", "cd1", "cd2", "stream", "play")
        return if (genericNames.contains(baseName.lowercase(Locale.ROOT)) && parentFolderName.isNotBlank() && parentFolderName != "Movies") {
            parentFolderName
        } else {
            baseName.replace(".", " ").replace("_", " ").trim()
        }
    }

    internal fun parseSeasonAndEpisode(fileName: String, folderName: String): Pair<Int, Int> {
        val seMatcher = S_E_PATTERN.matcher(fileName)
        if (seMatcher.find()) {
            return Pair(seMatcher.group(1)?.toIntOrNull() ?: 1, seMatcher.group(2)?.toIntOrNull() ?: 1)
        }

        val xMatcher = X_PATTERN.matcher(fileName)
        if (xMatcher.find()) {
            return Pair(xMatcher.group(1)?.toIntOrNull() ?: 1, xMatcher.group(2)?.toIntOrNull() ?: 1)
        }

        val sDashMatcher = S_DASH_E_PATTERN.matcher(fileName)
        if (sDashMatcher.find()) {
            return Pair(sDashMatcher.group(1)?.toIntOrNull() ?: 1, sDashMatcher.group(2)?.toIntOrNull() ?: 1)
        }

        var seasonNum = 1
        val seasonFolderMatcher = SEASON_FOLDER_PATTERN.matcher(folderName)
        if (seasonFolderMatcher.find()) {
            seasonNum = seasonFolderMatcher.group(1)?.toIntOrNull() ?: 1
        }

        val epMatcher = EPISODE_FALLBACK_PATTERN.matcher(fileName)
        if (epMatcher.find()) {
            val epNum = epMatcher.group(1)?.toIntOrNull() ?: 1
            return Pair(seasonNum, epNum)
        }

        val standaloneMatcher = EPISODE_STANDALONE_PATTERN.matcher(fileName)
        if (standaloneMatcher.find()) {
            val epNum = standaloneMatcher.group(1)?.toIntOrNull() ?: 1
            return Pair(seasonNum, epNum)
        }

        val folderSeMatcher = S_E_PATTERN.matcher(folderName)
        if (folderSeMatcher.find()) {
            return Pair(folderSeMatcher.group(1)?.toIntOrNull() ?: 1, folderSeMatcher.group(2)?.toIntOrNull() ?: 1)
        }

        return Pair(seasonNum, 1)
    }

    internal fun cleanEpisodeTitle(fileName: String): String {
        return fileName
            .substringBeforeLast(".")
            .replace(Regex("""(?i)[._-](1080p|720p|2160p|4k|60fps|bluray|web-dl|webrip|hdtv|hevc|x265|x264|aac|dts).*"""), "")
            .replace(".", " ")
            .replace("_", " ")
            .trim()
    }

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "00:00"
        val totalSecs = durationMs / 1000
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    fun formatFileSize(sizeBytes: Long?): String {
        if (sizeBytes == null || sizeBytes <= 0L) return "750 MB"
        val mb = sizeBytes / (1024 * 1024).toDouble()
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024.0) else String.format(Locale.US, "%.0f MB", mb)
    }

    /**
     * High-level alias for scanning all media in the Series root folder.
     */
    suspend fun scanDriveMedia(
        driveService: Drive,
        rootSeriesFolderId: String
    ): List<TvSeries> = scanSeriesRecursively(driveService, rootSeriesFolderId)

    /**
     * Refreshes a single series by its folder ID, retrieving all subfolders, episodes,
     * and resolving metadata via OnlineMetadataResolver.
     */
    suspend fun refreshSingleSeries(
        driveService: Drive,
        seriesFolderId: String,
        seriesTitleHint: String? = null
    ): TvSeries? = withContext(Dispatchers.IO) {
        val seriesTitle = seriesTitleHint?.ifBlank { null } ?: fetchFolderName(driveService, seriesFolderId) ?: "TV Series"
        val seriesMeta = OnlineMetadataResolver.resolveSeries(seriesTitle)
        val episodesFound = mutableListOf<Episode>()
        val discoveredSeasons = mutableMapOf<Int, Season>()

        val subFolderQueue = ArrayDeque<Pair<String, String>>()
        val visitedSubs = mutableSetOf<String>()
        subFolderQueue.add(Pair(seriesFolderId, seriesMeta.officialTitle))
        visitedSubs.add(seriesFolderId)

        while (subFolderQueue.isNotEmpty()) {
            val (currentSubId, currentSubName) = subFolderQueue.poll() ?: break
            val childItems = fetchFolderContents(driveService, currentSubId)

            for (child in childItems) {
                val resolvedChildId = resolveTargetId(child)
                val isChildFolder = child.mimeType == FOLDER_MIME || 
                                   (child.mimeType == SHORTCUT_MIME && child.shortcutDetails?.targetMimeType == FOLDER_MIME)

                if (isChildFolder) {
                    if (visitedSubs.add(resolvedChildId)) {
                        val folderName = child.name ?: "Untitled Season"
                        val seasonNumMatcher = Pattern.compile("(?i)(?:season|series|s)\\s*(\\d+)").matcher(folderName)
                        val seasonNum = if (seasonNumMatcher.find()) seasonNumMatcher.group(1)?.toIntOrNull() else null
                        if (seasonNum != null && !discoveredSeasons.containsKey(seasonNum)) {
                            discoveredSeasons[seasonNum] = Season(
                                id = resolvedChildId,
                                seasonNumber = seasonNum,
                                seasonTitle = "Season $seasonNum",
                                episodes = emptyList()
                            )
                        }
                        subFolderQueue.add(Pair(resolvedChildId, folderName))
                    }
                } else if (isVideoFile(child)) {
                    val rawBytes = child.getSize()?.toLong() ?: 0L
                    val rawDurationMs = child.videoMediaMetadata?.durationMillis ?: 0L
                    val epDriveThumb = child.thumbnailLink?.takeIf { it.isNotBlank() } ?: ""
                    val fileSizeFormatted = formatFileSize(rawBytes)

                    val parsedNumbers = OnlineMetadataResolver.parseSeasonAndEpisodeNumbers(child.name.orEmpty())
                    val (fallbackSeasonNum, fallbackEpNum) = parseSeasonAndEpisode(child.name.orEmpty(), currentSubName)
                    val actualSeasonNumber = parsedNumbers?.first ?: fallbackSeasonNum
                    val epNumber = parsedNumbers?.second ?: fallbackEpNum

                    val epMeta = OnlineMetadataResolver.resolveEpisode(
                        seriesName = seriesMeta.officialTitle,
                        tvMazeSeriesId = seriesMeta.tvMazeId,
                        seasonNumber = actualSeasonNumber,
                        episodeNumber = epNumber,
                        rawFileName = child.name.orEmpty()
                    )

                    val episode = Episode(
                        id = resolvedChildId,
                        seasonNumber = actualSeasonNumber,
                        episodeNumber = epNumber,
                        displayTitle = epMeta.officialTitle,
                        formattedDuration = if (rawDurationMs > 0L) formatDuration(rawDurationMs) else if (epMeta.durationMinutes > 0L) "${epMeta.durationMinutes} min" else "42:00",
                        fileSize = fileSizeFormatted,
                        resolution = "1080p FHD",
                        thumbnailUrl = epMeta.thumbnailUrl.ifBlank { epDriveThumb }
                    )
                    episodesFound.add(episode)
                }
            }
        }

        if (episodesFound.isEmpty() && discoveredSeasons.isEmpty()) return@withContext null

        val seasonsGrouped = episodesFound.groupBy { it.seasonNumber }
        val allSeasonNums = (discoveredSeasons.keys + seasonsGrouped.keys).sorted()
        val finalSeasons = allSeasonNums.map { sNum ->
            val existingSeason = discoveredSeasons[sNum]
            val eps = seasonsGrouped[sNum]?.sortedBy { it.episodeNumber } ?: emptyList()
            Season(
                id = existingSeason?.id ?: "${seriesFolderId}_S$sNum",
                seasonNumber = sNum,
                seasonTitle = existingSeason?.seasonTitle ?: "Season $sNum",
                episodes = eps
            )
        }

        TvSeries(
            id = seriesFolderId,
            title = seriesMeta.officialTitle,
            posterUrl = seriesMeta.posterUrl,
            genre = seriesMeta.genres,
            seasons = finalSeasons,
            totalEpisodes = episodesFound.size
        )
    }

    private fun fetchFolderName(driveService: Drive, folderId: String): String? {
        return try {
            val file = driveService.files().get(folderId)
                .setFields("name")
                .setSupportsAllDrives(true)
                .execute()
            file?.name
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch folder name for $folderId: ${e.message}")
            null
        }
    }
}
