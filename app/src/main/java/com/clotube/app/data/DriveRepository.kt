package com.clotube.app.data

import android.util.Log
import com.clotube.app.auth.GoogleAuthRepository
import com.clotube.app.model.Episode
import com.clotube.app.model.ExternalSubtitleTrack
import com.clotube.app.model.GenreResolver
import com.clotube.app.model.Season
import com.clotube.app.model.TvSeries
import com.clotube.app.model.VideoItem
import com.clotube.app.player.DriveMediaSourceFactory
import com.clotube.app.viewmodel.formatTimestamp
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * High-performance, Progressive Streaming Google Drive Repository.
 *
 * Implements:
 * 1. Hybrid Progressive Movie Streaming:
 *    - Immediately discovers and emits direct loose video files in ~300ms (Fast First Paint).
 *    - Concurrently inspects movie subfolders and category folders in progressive chunks of 3.
 *    - Discovers primary video, local artwork, Cinemeta backdrops, and sidecar subtitles.
 *    - Progressively emits growing catalog without blocking or resetting scroll positions.
 * 2. Progressive Shallow Series Discovery:
 *    - Streams top-level show folders inside the "Series" directory in progressive chunks of 3.
 *    - Shallow metadata fetching for Home Screen without deep season/episode recursion.
 * 3. On-Demand Episode Fetcher:
 *    - Isolated deep-fetch for SeriesDetailScreen when a show is selected.
 */
class DriveRepository(
    private val authRepository: GoogleAuthRepository,
    private val driveFolderManager: DriveFolderManager,
    private val injectedDriveService: Drive? = null
) {
    companion object {
        private const val TAG = "DriveRepository"
        private val GENERIC_STEMS = setOf(
            "movie", "video", "main", "film", "cd1", "cd2", "feature", "play", "default", "stream"
        )
    }

    private suspend fun getDriveService(): Drive? {
        val token = authRepository.getAccessToken().getOrNull() ?: return null
        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance()
        ) { request ->
            request.headers.authorization = "Bearer $token"
        }.setApplicationName("Clotube/1.0").build()
    }

    private suspend fun resolveDriveService(): Drive {
        return injectedDriveService ?: getDriveService()
            ?: throw IllegalStateException("Drive service unavailable: user not authenticated")
    }

    /**
     * Progressive streaming Flow for movies.
     * Emits direct loose video files immediately in ~300ms, then progressively inspects
     * and streams movies from dedicated subfolders in small concurrent batches.
     */
    fun streamMoviesChunked(moviesFolderId: String): Flow<List<VideoItem>> = flow {
        if (moviesFolderId.isBlank() || moviesFolderId == "root") {
            Log.w(TAG, "Fail-Closed: moviesFolderId is blank or 'root'. Emitting emptyList().")
            emit(emptyList())
            return@flow
        }

        val driveService = try {
            resolveDriveService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed resolving Drive service for movies streaming", e)
            emit(emptyList())
            return@flow
        }

        val accumulatedMovies = mutableListOf<VideoItem>()
        val seenMovieIds = mutableSetOf<String>()

        // 1. Query direct children of the Movies folder (both files and folders)
        val directChildren = mutableListOf<File>()
        var pageToken: String? = null

        try {
            do {
                val request = driveService.files().list()
                    .setQ("'$moviesFolderId' in parents and trashed = false")
                    .setFields("nextPageToken, files(id, name, size, mimeType, thumbnailLink, videoMediaMetadata)")
                    .setPageSize(50)
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)

                if (pageToken != null) {
                    request.pageToken = pageToken
                }

                val result = withContext(Dispatchers.IO) { request.execute() }
                val files = result.files.orEmpty()
                directChildren.addAll(files)

                // Separate direct loose video files vs root subtitles
                val directVideoFiles = files.filter { it.mimeType?.startsWith("video/") == true || isVideoFileName(it.name) }
                val rootSubtitles = files.filter { DriveFolderManager.isSubtitleFile(it.name.orEmpty(), it.mimeType) }

                // Map direct videos immediately for ~300ms Fast First Paint
                val newDirectItems = directVideoFiles.mapNotNull { file ->
                    val fileId = file.id ?: return@mapNotNull null
                    if (seenMovieIds.add(fileId)) {
                        val matchingSubs = findMatchingSubtitles(file.name, rootSubtitles)
                        mapToFileVideoItem(file, externalSubtitles = matchingSubs)
                    } else null
                }

                if (newDirectItems.isNotEmpty()) {
                    accumulatedMovies.addAll(newDirectItems)
                    emit(accumulatedMovies.toList())
                }

                pageToken = result.nextPageToken
            } while (pageToken != null)
        } catch (e: Exception) {
            Log.e(TAG, "Error listing direct children of movies root folder $moviesFolderId", e)
        }

        // 2. Discover and inspect dedicated movie subfolders
        val movieFolders = directChildren.filter { it.mimeType == "application/vnd.google-apps.folder" }
        Log.d(TAG, "Found ${accumulatedMovies.size} loose movies and ${movieFolders.size} movie folders in '$moviesFolderId'")

        // Process movie subfolders in small concurrent batches (e.g. 3 at a time) for progressive ingestion
        val folderBatches = movieFolders.chunked(3)
        for (batch in folderBatches) {
            val batchMovies = coroutineScope {
                batch.map { folder ->
                    async(Dispatchers.IO) {
                        try {
                            inspectMovieFolder(driveService, folder)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error inspecting movie subfolder ${folder.name}", e)
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }

            val newlyDiscovered = batchMovies.filter { movie ->
                movie.id.isNotBlank() && seenMovieIds.add(movie.id)
            }

            if (newlyDiscovered.isNotEmpty()) {
                accumulatedMovies.addAll(newlyDiscovered)
                emit(accumulatedMovies.toList())
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Inspects a movie subfolder, selecting primary video, discovering subtitles and artwork,
     * with recursion for nested category folders.
     */
    private suspend fun inspectMovieFolder(
        driveService: Drive,
        folder: File,
        depth: Int = 0
    ): List<VideoItem> = withContext(Dispatchers.IO) {
        val folderId = folder.id ?: return@withContext emptyList()
        val folderName = folder.name ?: "Untitled Movie"

        try {
            val files = mutableListOf<File>()
            var pageToken: String? = null
            do {
                val req = driveService.files().list()
                    .setQ("'$folderId' in parents and trashed = false")
                    .setFields("nextPageToken, files(id, name, size, mimeType, thumbnailLink, videoMediaMetadata, shortcutDetails)")
                    .setPageSize(1000)
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                if (pageToken != null) {
                    req.pageToken = pageToken
                }
                val res = withContext(Dispatchers.IO) { req.execute() }
                res.files?.let { files.addAll(it) }
                pageToken = res.nextPageToken
            } while (!pageToken.isNullOrBlank())

            val videoFiles = mutableListOf<File>()
            val imageFiles = mutableListOf<File>()
            val subtitleFiles = mutableListOf<File>()
            var subsFolderId: String? = null
            val subFolders = mutableListOf<File>()

            for (file in files) {
                val mime = file.mimeType.orEmpty()
                val name = file.name.orEmpty()
                val isFolder = mime == "application/vnd.google-apps.folder" || 
                               (mime == "application/vnd.google-apps.shortcut" && file.shortcutDetails?.targetMimeType == "application/vnd.google-apps.folder")

                when {
                    mime.startsWith("video/") || isVideoFileName(name) -> videoFiles.add(file)
                    mime.startsWith("image/") || isImageFileName(name) -> imageFiles.add(file)
                    DriveFolderManager.isSubtitleFile(name, mime) -> subtitleFiles.add(file)
                    isFolder -> {
                        val resolvedTargetId = if (mime == "application/vnd.google-apps.shortcut" && file.shortcutDetails?.targetId != null) {
                            file.shortcutDetails.targetId
                        } else {
                            file.id
                        }
                        if (name.equals("subs", ignoreCase = true) || name.equals("subtitles", ignoreCase = true)) {
                            subsFolderId = resolvedTargetId
                        } else {
                            val resolvedFile = file.clone().setId(resolvedTargetId)
                            subFolders.add(resolvedFile)
                        }
                    }
                }
            }

            // Recurse into nested subfolders (up to depth 10) for multi-level collections/franchises
            val nestedMovies = if (depth < 10 && subFolders.isNotEmpty()) {
                subFolders.flatMap { subFolder ->
                    inspectMovieFolder(driveService, subFolder, depth + 1)
                }
            } else {
                emptyList()
            }

            // If no video files exist directly inside this folder, return any nested movies discovered
            if (videoFiles.isEmpty()) {
                return@withContext nestedMovies
            }

            // Query nested 'subs' or 'subtitles' directory if present
            if (!subsFolderId.isNullOrBlank()) {
                val nestedSubs = fetchSubtitlesFromDriveFolder(driveService, subsFolderId)
                subtitleFiles.addAll(nestedSubs)
            }

            // Pick primary video file (largest video file by size to avoid sample clips)
            val primaryVideo = videoFiles.maxByOrNull { it.size?.toLong() ?: 0L } ?: videoFiles.first()

            // Map subtitles to external subtitle tracks
            val externalSubtitles = subtitleFiles.map { subFile ->
                val subId = subFile.id.orEmpty()
                val subName = subFile.name ?: "subtitles.srt"
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

            // Preferred local image inside folder (poster / backdrop)
            val preferredImage = imageFiles.find { img ->
                val iname = img.name.orEmpty().lowercase()
                iname.contains("poster") || iname.contains("cover") || iname.contains("backdrop") ||
                        iname.contains("fanart") || iname.contains("folder")
            } ?: imageFiles.firstOrNull()

            val localImageThumb = preferredImage?.thumbnailLink?.replace("=s220", "=s1200")
            val rawVideoThumb = primaryVideo.thumbnailLink?.replace("=s220", "=s800").orEmpty()

            // Resolve title: folder name is typically "Inception (2010)" or clean title
            val rawVideoName = primaryVideo.name.orEmpty()
            val targetName = if (folderName.isNotBlank() && !isGenericMovieStem(folderName)) folderName else rawVideoName
            val movieMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveMovie(targetName)
            val movieTitle = movieMeta.officialTitle
            val finalThumbnail = movieMeta.posterUrl.ifBlank { localImageThumb ?: rawVideoThumb }

            val currentMovie = mapToFileVideoItem(
                file = primaryVideo,
                overrideTitle = movieTitle,
                externalSubtitles = externalSubtitles,
                overrideThumbnail = finalThumbnail
            )

            listOf(currentMovie) + nestedMovies
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting movie subfolder $folderName ($folderId)", e)
            emptyList()
        }
    }

    /**
     * Progressive streaming Flow for TV series overview.
     * Emits show folders in small batches of 2-3 shows as each chunk resolves.
     */
    fun streamSeriesOverview(seriesRootFolderId: String): Flow<List<TvSeries>> = flow {
        if (seriesRootFolderId.isBlank() || seriesRootFolderId == "root") {
            Log.w(TAG, "Fail-Closed: seriesRootFolderId is blank or 'root'. Emitting emptyList().")
            emit(emptyList())
            return@flow
        }

        val driveService = try {
            resolveDriveService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed resolving Drive service for series overview", e)
            emit(emptyList())
            return@flow
        }

        val accumulatedSeries = mutableListOf<TvSeries>()
        val seenSeriesIds = mutableSetOf<String>()
        val allShowFolders = mutableListOf<File>()
        var pageToken: String? = null

        try {
            do {
                val request = driveService.files().list()
                    .setQ("'$seriesRootFolderId' in parents and trashed = false")
                    .setFields("nextPageToken, files(id, name, mimeType, thumbnailLink)")
                    .setPageSize(50)
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)

                if (pageToken != null) {
                    request.pageToken = pageToken
                }

                val result = withContext(Dispatchers.IO) { request.execute() }
                val files = result.files.orEmpty()

                for (file in files) {
                    if (file.mimeType == "application/vnd.google-apps.folder") {
                        allShowFolders.add(file)
                    }
                }

                pageToken = result.nextPageToken
            } while (pageToken != null)
        } catch (e: Exception) {
            Log.e(TAG, "Error listing series folders in $seriesRootFolderId", e)
        }

        Log.d(TAG, "Discovered ${allShowFolders.size} show folders in '$seriesRootFolderId'")

        // Process show folders in small concurrent batches of 3
        val folderBatches = allShowFolders.chunked(3)
        for (batch in folderBatches) {
            val batchSeries = coroutineScope {
                batch.map { folder ->
                    async(Dispatchers.IO) {
                        try {
                            mapToShowOverview(driveService, folder)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error mapping series ${folder.name}", e)
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            val newlyDiscovered = batchSeries.filter { show ->
                show.id.isNotBlank() && seenSeriesIds.add(show.id)
            }

            if (newlyDiscovered.isNotEmpty()) {
                accumulatedSeries.addAll(newlyDiscovered)
                emit(accumulatedSeries.toList())
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Shallow Series Discovery (No deep scan on Home Screen):
     * Collects and returns the full series overview.
     */
    suspend fun getSeriesOverview(seriesRootFolderId: String): List<TvSeries> = withContext(Dispatchers.IO) {
        var lastList: List<TvSeries> = emptyList()
        streamSeriesOverview(seriesRootFolderId).collect {
            lastList = it
        }
        lastList
    }

    private suspend fun mapToShowOverview(driveService: Drive, folder: File): TvSeries = withContext(Dispatchers.IO) {
        val folderId = folder.id.orEmpty()
        val folderName = folder.name.orEmpty()
        val seriesMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveSeries(folderName)
        val cleanTitle = seriesMeta.officialTitle
        val genre = seriesMeta.genres
        val rawThumb = folder.thumbnailLink?.replace("=s220", "=s800").orEmpty()

        var finalThumb = seriesMeta.posterUrl.ifBlank { rawThumb }

        // If poster and folder thumbnail both returned empty, peek at first season or episode for artwork fallback
        if (finalThumb.isBlank()) {
            finalThumb = peekSeriesArtwork(driveService, folderId)
        }

        TvSeries(
            id = folderId,
            title = cleanTitle,
            genreFormatted = genre,
            totalSeasonsFormatted = "Tap to explore",
            seasons = emptyList(), // Shallow!
            thumbnailUrl = finalThumb,
            backdropUrl = seriesMeta.posterUrl.ifBlank { finalThumb },
            posterUrl = seriesMeta.posterUrl.ifBlank { finalThumb }
        )
    }

    private suspend fun peekSeriesArtwork(driveService: Drive, folderId: String): String = withContext(Dispatchers.IO) {
        try {
            val result = driveService.files().list()
                .setQ("'$folderId' in parents and trashed = false")
                .setFields("files(id, name, mimeType, thumbnailLink)")
                .setPageSize(5)
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .execute()

            val files = result.files.orEmpty()
            // Look for an image file first
            val img = files.find { it.mimeType?.startsWith("image/") == true }
            if (img?.thumbnailLink != null) {
                return@withContext img.thumbnailLink.replace("=s220", "=s1200")
            }
            // Look for a video file or season subfolder
            val vid = files.find { it.mimeType?.startsWith("video/") == true }
            if (vid?.thumbnailLink != null) {
                return@withContext vid.thumbnailLink.replace("=s220", "=s800")
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * On-Demand Episode Fetcher (For SeriesDetailScreen only):
     * Deeply scans the season folders and video episodes for a single selected show.
     */
    suspend fun fetchSeriesDetails(seriesFolderId: String): List<Season> = withContext(Dispatchers.IO) {
        if (seriesFolderId.isBlank() || seriesFolderId == "root") {
            Log.w(TAG, "Fail-Closed: seriesFolderId is blank or 'root'.")
            return@withContext emptyList()
        }

        try {
            val seriesRepo = SeriesRepository(authRepository)
            val show = seriesRepo.getShowDetails(seriesFolderId)
            show.seasons
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching series details on-demand for $seriesFolderId", e)
            emptyList()
        }
    }

    /**
     * Backwards-compatible convenience methods.
     */
    suspend fun getMovies(): List<VideoItem> {
        val folderId = driveFolderManager.getMoviesFolderId()
        if (folderId.isNullOrBlank() || folderId == "root") return emptyList()
        var lastList: List<VideoItem> = emptyList()
        streamMoviesChunked(folderId).collect {
            lastList = it
        }
        return lastList
    }

    suspend fun getSeries(): List<TvSeries> {
        val folderId = driveFolderManager.getSeriesFolderId()
        if (folderId.isNullOrBlank() || folderId == "root") return emptyList()
        return getSeriesOverview(folderId)
    }

    private suspend fun mapToFileVideoItem(
        file: File,
        overrideTitle: String? = null,
        externalSubtitles: List<ExternalSubtitleTrack> = emptyList(),
        overrideThumbnail: String? = null
    ): VideoItem {
        val id = file.id ?: ""
        val name = file.name ?: "Untitled Video"
        val sizeBytes: Long = file.size?.toLong() ?: 0L
        val rawThumb = file.thumbnailLink?.replace("=s220", "=s800").orEmpty()
        val videoMeta = file.videoMediaMetadata
        val width: Int = videoMeta?.width?.toInt() ?: 0
        val height: Int = videoMeta?.height?.toInt() ?: 0
        val durationMillis: Long = videoMeta?.durationMillis?.toLong() ?: 0L

        val rawResolution = DriveFolderManager.getResolutionLabel(width.toLong(), height.toLong())
        val resolutionBadge = when {
            height >= 2160 || width >= 3840 || rawResolution.contains("4K", ignoreCase = true) -> "4K UHD"
            height >= 1440 || width >= 2560 || rawResolution.contains("1440", ignoreCase = true) -> "1440p QHD"
            height >= 1080 || width >= 1920 || rawResolution.contains("1080", ignoreCase = true) -> "1080p FHD"
            height >= 720 || width >= 1280 || rawResolution.contains("720", ignoreCase = true) -> "720p HD"
            height > 0 -> "${height}p"
            rawResolution.isNotBlank() && rawResolution != "HD" -> rawResolution
            else -> "1080p FHD"
        }

        val targetMovieName = overrideTitle?.ifBlank { null } ?: name
        val movieMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveMovie(targetMovieName)
        val cleanTitle = movieMeta.officialTitle
        val durationFormatted = if (durationMillis > 0) formatTimestamp(durationMillis) else "00:00"
        val sizeFormatted = formatFileSize(sizeBytes)
        val genreFormatted = movieMeta.genre
        val streamUri = DriveMediaSourceFactory.buildDriveStreamUri(id).toString()
        val hdrBadge = if (width >= 3840 || height >= 2160) "HDR10" else null

        val finalThumb = if (!overrideThumbnail.isNullOrBlank()) {
            overrideThumbnail
        } else {
            movieMeta.posterUrl.ifBlank { rawThumb }
        }

        val bitrateFormatted = if (durationMillis > 0 && sizeBytes > 0) {
            val durationSec = durationMillis / 1000.0
            val mbps = (sizeBytes * 8.0) / (durationSec * 1_000_000.0)
            String.format(Locale.US, "%.1f Mbps", mbps)
        } else ""

        val codec = when {
            name.endsWith(".mkv", ignoreCase = true) -> "MKV Container"
            name.endsWith(".mp4", ignoreCase = true) -> "MP4 Container"
            else -> "Direct Stream"
        }

        return VideoItem(
            id = id,
            displayTitle = cleanTitle,
            durationFormatted = durationFormatted,
            fileSizeFormatted = sizeFormatted,
            genreFormatted = genreFormatted,
            resolutionBadge = resolutionBadge,
            streamUri = streamUri,
            thumbnailUrl = finalThumb,
            fpsBadge = "",
            mimeType = file.mimeType ?: "video/mp4",
            bitrateFormatted = bitrateFormatted,
            codec = codec,
            audioProfile = "",
            hdrBadge = hdrBadge,
            width = width,
            height = height,
            durationMs = durationMillis,
            sizeBytes = sizeBytes,
            externalSubtitles = externalSubtitles,
            watchProgressFraction = 0f
        )
    }

    private fun findMatchingSubtitles(videoFileName: String?, subtitleFiles: List<File>): List<ExternalSubtitleTrack> {
        if (videoFileName == null || subtitleFiles.isEmpty()) return emptyList()
        val videoStem = videoFileName.substringBeforeLast('.')
        return subtitleFiles.filter { subFile ->
            val subName = subFile.name.orEmpty()
            subName.startsWith(videoStem, ignoreCase = true)
        }.map { subFile ->
            val subId = subFile.id.orEmpty()
            val subName = subFile.name ?: "subtitles.srt"
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
    }

    private suspend fun fetchSubtitlesFromDriveFolder(driveService: Drive, folderId: String): List<File> = withContext(Dispatchers.IO) {
        try {
            val result = driveService.files().list()
                .setQ("'$folderId' in parents and trashed = false")
                .setFields("files(id, name, mimeType)")
                .setPageSize(50)
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .execute()

            result.files.orEmpty().filter { DriveFolderManager.isSubtitleFile(it.name.orEmpty(), it.mimeType) }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching nested subtitles from folder $folderId", e)
            emptyList()
        }
    }

    private fun cleanVideoTitle(raw: String): String {
        var title = raw
        title = title.replace(Regex("""\.(mkv|mp4|webm|avi|mov|ts|m4v)$""", RegexOption.IGNORE_CASE), "")
        title = title.replace(Regex("""(?i)[._ -]*(1080p|720p|2160p|4k|60fps|bluray|web-dl|webrip|hevc|x265|x264|aac|dts|remux|h264|hdr|uhd).*"""), "")
        title = title.replace('.', ' ').replace('_', ' ').trim()
        return title.ifBlank { raw }
    }

    private fun cleanFolderMovieTitle(folderName: String): String {
        return folderName
            .replace(Regex("""\[.*?\]"""), "")
            .replace(Regex("""(?i)[._ -]*(1080p|720p|2160p|4k|bluray|web-dl|webrip|hevc|x265|x264|aac|dts|remux|h264|hdr|uhd).*"""), "")
            .replace('.', ' ')
            .replace('_', ' ')
            .trim()
            .ifBlank { folderName }
    }

    private fun cleanSeriesTitle(folderName: String): String {
        return folderName
            .replace(Regex("""(?i)[\[\(](19\d{2}|20\d{2})[\]\)]"""), "")
            .replace(Regex("""(?i)[\[\(].*?[\]\)]"""), "")
            .replace(Regex("""(?i)[._ -]*(1080p|720p|2160p|4k|complete|hevc|x265|x264).*"""), "")
            .replace('.', ' ')
            .replace('_', ' ')
            .trim()
            .ifBlank { folderName }
    }

    private fun resolveGenre(name: String): String {
        return GenreResolver.resolveGenre(name, isSeries = true)
    }

    private fun isGenericMovieStem(stem: String): Boolean {
        val lower = stem.lowercase().trim()
        return lower in GENERIC_STEMS
    }

    private fun isVideoFileName(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase()
        return lower.endsWith(".mkv") || lower.endsWith(".mp4") || lower.endsWith(".webm") ||
                lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".ts") || lower.endsWith(".m4v")
    }

    private fun isImageFileName(name: String?): Boolean {
        if (name == null) return false
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.1f GB", gb)
        } else {
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            String.format(Locale.US, "%.0f MB", mb)
        }
    }
}
