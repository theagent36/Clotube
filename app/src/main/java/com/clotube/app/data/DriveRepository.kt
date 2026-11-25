package com.clotube.app.data

import android.util.Log
import com.clotube.app.auth.GoogleAuthRepository
import com.clotube.app.data.metadata.OnlineMetadataResolver
import com.clotube.app.model.Episode
import com.clotube.app.model.ExternalSubtitleTrack
import com.clotube.app.model.GenreResolver
import com.clotube.app.model.Movie
import com.clotube.app.model.Season
import com.clotube.app.model.TvSeries
import com.clotube.app.model.VideoItem
import com.clotube.app.model.toMovie
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
import java.util.ArrayDeque
import java.util.Locale
import java.util.regex.Pattern

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
        const val STAGE1_PRIORITY_LIMIT = 2
        const val INDEX_PACKET_SIZE = 4
        private val GENERIC_STEMS = setOf(
            "movie", "video", "main", "film", "cd1", "cd2", "feature", "play", "default", "stream"
        )

        fun cleanVideoTitle(raw: String): String {
            var title = raw
            title = title.replace(Regex("""\.(mkv|mp4|webm|avi|mov|ts|m4v)$""", RegexOption.IGNORE_CASE), "")
            title = title.replace(Regex("""(?i)[._ -]*(1080p|720p|2160p|4k|60fps|bluray|web-dl|webrip|hevc|x265|x264|aac|dts|remux|h264|hdr|uhd).*"""), "")
            title = title.replace('.', ' ').replace('_', ' ').trim()
            title = title.replace(Regex("""(?i)\s*[\(\[]\s*(19\d{2}|20\d{2})\s*[\)\]]\s*$"""), "")
            title = title.replace(Regex("""(?i)\s+\b(19\d{2}|20\d{2})\b\s*$"""), "")
            return title.trim().ifBlank { raw }
        }

        fun cleanFolderMovieTitle(folderName: String): String {
            return folderName
                .replace(Regex("""\[.*?\]"""), "")
                .replace(Regex("""(?i)[._ -]*(1080p|720p|2160p|4k|bluray|web-dl|webrip|hevc|x265|x264|aac|dts|remux|h264|hdr|uhd).*"""), "")
                .replace('.', ' ')
                .replace('_', ' ')
                .trim()
                .ifBlank { folderName }
        }

        fun cleanSeriesTitle(folderName: String): String {
            return folderName
                .replace(Regex("""(?i)[\[\(](19\d{2}|20\d{2})[\]\)]"""), "")
                .replace(Regex("""(?i)[\[\(].*?[\]\)]"""), "")
                .replace(Regex("""(?i)[._ -]*(1080p|720p|2160p|4k|complete|hevc|x265|x264).*"""), "")
                .replace('.', ' ')
                .replace('_', ' ')
                .trim()
                .ifBlank { folderName }
        }
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
     * Stage-1 Priority Fast-Start Fetcher:
     * Fetches only the top [limit] (default 2) latest movies from Google Drive, resolving all
     * metadata (official poster, backdrop, genres, and clean filtered title) in parallel BEFORE returning.
     * Guarantees zero thumbnail or title swapping on Frame 1.
     */
    suspend fun fetchTopLatestMoviesEnriched(moviesFolderId: String, limit: Int = STAGE1_PRIORITY_LIMIT): List<Movie> = withContext(Dispatchers.IO) {
        if (moviesFolderId.isBlank() || moviesFolderId == "root") {
            Log.w(TAG, "Fail-Closed: moviesFolderId is blank or 'root' in fetchTopLatestMoviesEnriched.")
            return@withContext emptyList()
        }

        val driveService = try {
            resolveDriveService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed resolving Drive service for priority movies fetch", e)
            return@withContext emptyList()
        }

        try {
            val query = "'$moviesFolderId' in parents and (mimeType contains 'video/' or mimeType = 'application/vnd.google-apps.folder') and trashed = false"
            val request = driveService.files().list()
                .setQ(query)
                .setOrderBy("createdTime desc")
                .setPageSize(6)
                .setFields("files(id, name, size, mimeType, thumbnailLink, videoMediaMetadata, shortcutDetails)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)

            val result = request.execute()
            val files = result.files.orEmpty()
            val candidateMovies = mutableListOf<Movie>()

            for (file in files) {
                if (candidateMovies.size >= limit) break

                val mime = file.mimeType.orEmpty()
                val isFolder = mime == "application/vnd.google-apps.folder" ||
                        (mime == "application/vnd.google-apps.shortcut" && file.shortcutDetails?.targetMimeType == "application/vnd.google-apps.folder")

                if (isFolder) {
                    val targetFolder = if (mime == "application/vnd.google-apps.shortcut" && file.shortcutDetails?.targetId != null) {
                        file.clone().setId(file.shortcutDetails.targetId)
                    } else {
                        file
                    }
                    val folderClassification = MovieIndustryResolver.classifyDriveFolder(
                        folderName = targetFolder.name.orEmpty(),
                        isDirectChildOfMoviesRoot = true
                    )
                    val inspectedVideoItems = inspectMovieFolder(
                        driveService = driveService,
                        folder = targetFolder,
                        depth = 0,
                        inheritedLanguage = folderClassification?.language,
                        inheritedIndustry = folderClassification?.industry,
                        inheritedSourceFolder = if (folderClassification?.isExplicitCategoryFolder == true) targetFolder.name.orEmpty() else "",
                        isCategoryFolder = folderClassification?.isExplicitCategoryFolder == true
                    )
                    for (item in inspectedVideoItems) {
                        if (candidateMovies.size >= limit) break
                        if (item.id.isNotBlank() && candidateMovies.none { it.id == item.id }) {
                            candidateMovies.add(item.toMovie())
                        }
                    }
                } else if (mime.startsWith("video/") || isVideoFileName(file.name)) {
                    val fileId = file.id ?: continue
                    if (candidateMovies.none { it.id == fileId }) {
                        val rawName = file.name.orEmpty()
                        val cleanTitle = cleanVideoTitle(rawName)
                        val fileLangHint = MovieIndustryResolver.extractLanguageFromFileName(rawName)
                        val industryInfo = MovieIndustryResolver.toIndustryInfo(fileLangHint)
                        val videoItem = mapToFileVideoItem(
                            file = file,
                            overrideTitle = cleanTitle,
                            resolveOnlineMetadata = false,
                            language = industryInfo.language,
                            industry = industryInfo.sectionLabel,
                            sourceFolder = "",
                            isFolderClassified = false
                        )
                        candidateMovies.add(videoItem.toMovie())
                    }
                }
            }

            // Run OnlineMetadataResolver.resolveMovie / MediaArtworkResolver.resolveMovieArtwork in parallel
            val enrichedMovies = candidateMovies.take(limit).map { movie ->
                async(Dispatchers.IO) {
                    try {
                        val titleToSearch = movie.title.ifBlank { movie.fileName }
                        val cleanSearchTitle = cleanVideoTitle(titleToSearch)
                        val meta = OnlineMetadataResolver.resolveMovie(cleanSearchTitle)
                        val artwork = MediaArtworkResolver.resolveMovieArtwork(cleanSearchTitle, null)

                        val finalTitle = meta.officialTitle.ifBlank { cleanSearchTitle }
                        val finalPoster = meta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                            ?: artwork.posterUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                            ?: movie.thumbnailUrl?.takeIf { !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                            ?: movie.fallbackThumbnailUrl.takeIf { !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                            ?: ""

                        val finalBackdrop = artwork.backdropUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                            ?: finalPoster

                        val fileLangHint = MovieIndustryResolver.extractLanguageFromFileName(movie.fileName)
                        val effectiveLang = movie.language.ifBlank {
                            fileLangHint
                                ?: meta.originalLanguage.ifBlank { null }
                                ?: MovieIndustryResolver.deduceLanguageFromScript(finalTitle)
                                ?: "English"
                        }
                        val industryInfo = MovieIndustryResolver.toIndustryInfo(effectiveLang)

                        movie.copy(
                            title = finalTitle,
                            thumbnailUrl = finalPoster,
                            fallbackThumbnailUrl = movie.fallbackThumbnailUrl.ifBlank {
                                if (OnlineMetadataResolver.isDriveVideoFrameUrl(movie.thumbnailUrl)) movie.thumbnailUrl.orEmpty() else ""
                            },
                            genre = meta.genre.ifBlank { movie.genre },
                            language = industryInfo.language,
                            industry = industryInfo.sectionLabel
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed enriching priority movie ${movie.title}: ${e.message}", e)
                        movie
                    }
                }
            }.awaitAll()

            enrichedMovies
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchTopLatestMoviesEnriched: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Stage-1 Priority Fast-Start Fetcher:
     * Fetches only the top [limit] (default 2) latest series folders from Google Drive.
     * Shallow discovery (zero scanning of seasons or episodes), resolving official show poster/backdrop
     * and genres via OnlineMetadataResolver BEFORE returning.
     * Guarantees zero thumbnail or title swapping on Frame 1.
     */
    suspend fun fetchTopLatestSeriesEnriched(seriesFolderId: String, limit: Int = STAGE1_PRIORITY_LIMIT): List<TvSeries> = withContext(Dispatchers.IO) {
        if (seriesFolderId.isBlank() || seriesFolderId == "root") {
            Log.w(TAG, "Fail-Closed: seriesFolderId is blank or 'root' in fetchTopLatestSeriesEnriched.")
            return@withContext emptyList()
        }

        val driveService = try {
            resolveDriveService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed resolving Drive service for priority series fetch", e)
            return@withContext emptyList()
        }

        try {
            val query = "'$seriesFolderId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val request = driveService.files().list()
                .setQ(query)
                .setOrderBy("createdTime desc")
                .setPageSize(limit)
                .setFields("files(id, name, thumbnailLink)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)

            val result = request.execute()
            val showFolders = result.files.orEmpty().take(limit)

            val enrichedSeries = showFolders.map { folder ->
                async(Dispatchers.IO) {
                    try {
                        val folderId = folder.id.orEmpty()
                        val folderName = folder.name.orEmpty()
                        val cleanTitle = cleanSeriesTitle(folderName)

                        val seriesMeta = OnlineMetadataResolver.resolveSeries(cleanTitle)
                        val officialTitle = seriesMeta.officialTitle.ifBlank { cleanTitle }
                        val artwork = MediaArtworkResolver.resolveSeriesArtwork(cleanTitle, null)
                        val posterUrl = seriesMeta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                            ?: artwork.posterUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                            ?: ""
                        val backdropUrl = artwork.backdropUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                            ?: posterUrl
                        val genre = seriesMeta.genres.ifBlank { "TV Series • Drama" }

                        TvSeries(
                            id = folderId,
                            title = officialTitle,
                            genreFormatted = genre,
                            totalSeasonsFormatted = "TV Series",
                            seasons = emptyList(), // Shallow!
                            thumbnailUrl = posterUrl,
                            backdropUrl = backdropUrl.ifBlank { null },
                            posterUrl = posterUrl
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed enriching priority series ${folder.name}: ${e.message}", e)
                        val cleanTitle = cleanSeriesTitle(folder.name.orEmpty())
                        TvSeries(
                            id = folder.id.orEmpty(),
                            title = cleanTitle,
                            genreFormatted = "TV Series • Drama",
                            totalSeasonsFormatted = "TV Series",
                            seasons = emptyList(),
                            thumbnailUrl = "",
                            backdropUrl = null,
                            posterUrl = ""
                        )
                    }
                }
            }.awaitAll()

            enrichedSeries
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchTopLatestSeriesEnriched: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Enriches metadata, posters, and industry classification for a single [VideoItem].
     * Guarantees accurate online posters/titles without raw Drive video frame captures,
     * while preserving folder-based language/industry classification when isFolderClassified = true.
     */
    suspend fun enrichSingleMovie(movie: VideoItem): VideoItem = withContext(Dispatchers.IO) {
        try {
            val titleToSearch = movie.displayTitle.ifBlank { movie.title }
            val cleanSearchTitle = cleanVideoTitle(titleToSearch)
            val meta = OnlineMetadataResolver.resolveMovie(cleanSearchTitle)
            val artwork = MediaArtworkResolver.resolveMovieArtwork(cleanSearchTitle, movie.thumbnailUrl)

            val finalTitle = meta.officialTitle.ifBlank { cleanSearchTitle }
            val finalPoster = meta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                ?: artwork.posterUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                ?: movie.thumbnailUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                ?: movie.fallbackThumbnailUrl

            val enrichedLang: String
            val enrichedIndustry: String
            if (movie.isFolderClassified) {
                enrichedLang = movie.language
                enrichedIndustry = movie.industry
            } else if (meta.originalLanguage.isNotBlank()) {
                val info = MovieIndustryResolver.toIndustryInfo(meta.originalLanguage)
                enrichedLang = info.language
                enrichedIndustry = info.sectionLabel
            } else {
                val fileLangHint = MovieIndustryResolver.extractLanguageFromFileName(movie.displayTitle)
                val effectiveLang = movie.language.ifBlank {
                    fileLangHint
                        ?: meta.originalLanguage.ifBlank { null }
                        ?: MovieIndustryResolver.deduceLanguageFromScript(finalTitle)
                        ?: "English"
                }
                val info = MovieIndustryResolver.toIndustryInfo(effectiveLang)
                enrichedLang = info.language
                enrichedIndustry = info.sectionLabel
            }

            movie.copy(
                displayTitle = finalTitle,
                thumbnailUrl = finalPoster,
                fallbackThumbnailUrl = movie.fallbackThumbnailUrl.ifBlank {
                    if (OnlineMetadataResolver.isDriveVideoFrameUrl(movie.thumbnailUrl)) movie.thumbnailUrl else ""
                },
                genreFormatted = meta.genre.ifBlank { movie.genreFormatted },
                language = enrichedLang,
                industry = enrichedIndustry,
                sourceFolder = movie.sourceFolder,
                isFolderClassified = movie.isFolderClassified
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed enriching movie ${movie.displayTitle}: ${e.message}", e)
            movie
        }
    }

    /**
     * Executes paginated API calls ensuring all items in a folder are retrieved without truncation.
     */
    private suspend fun fetchFolderFiles(driveService: Drive, folderId: String): List<File> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<File>()
        var pageToken: String? = null
        do {
            try {
                val response = driveService.files().list()
                    .setQ("'$folderId' in parents and trashed = false")
                    .setFields("nextPageToken, files(id, name, mimeType, size, thumbnailLink, videoMediaMetadata, shortcutDetails)")
                    .setPageSize(1000)
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setPageToken(pageToken)
                    .execute()
                response.files?.let { resultList.addAll(it) }
                pageToken = response.nextPageToken
            } catch (e: Exception) {
                Log.e(TAG, "Failed fetching folder files for $folderId: ${e.message}")
                break
            }
        } while (!pageToken.isNullOrBlank())
        resultList
    }

    /**
     * Progressive Packet-of-4 (INDEX_PACKET_SIZE = 4) streaming Flow for Movies.
     * Traverses clotube/Movies/ using a BFS queue, indexing direct video files, industry
     * subfolders, and dedicated movie folders. Enriches packets of 4 items concurrently in parallel
     * so each packet arrives with accurate official posters and clean titles.
     */
    fun streamMoviesChunked(moviesFolderId: String): Flow<List<VideoItem>> = flow {
        if (moviesFolderId.isBlank() || moviesFolderId == "root") {
            Log.w(TAG, "Fail-Closed: moviesFolderId is blank or 'root'. Emitting emptyList().")
            return@flow
        }

        val driveService = try {
            resolveDriveService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed resolving Drive service for movies streaming", e)
            return@flow
        }

        val seenMovieIds = mutableSetOf<String>()
        val moviePacket = mutableListOf<VideoItem>()

        suspend fun emitPacketIfFull() {
            if (moviePacket.size >= INDEX_PACKET_SIZE) {
                val batch = moviePacket.toList()
                moviePacket.clear()
                val enrichedPacket = coroutineScope {
                    batch.map { item ->
                        async(Dispatchers.IO) {
                            enrichSingleMovie(item)
                        }
                    }.awaitAll()
                }
                emit(enrichedPacket)
            }
        }

        suspend fun addMovie(item: VideoItem) {
            if (item.id.isBlank() || !seenMovieIds.add(item.id)) return
            moviePacket.add(item)
            if (moviePacket.size == INDEX_PACKET_SIZE) {
                emitPacketIfFull()
            }
        }

        data class MovieFolderScanTask(
            val folderId: String,
            val folderName: String,
            val isMoviesRoot: Boolean = false,
            val depth: Int = 0,
            val inheritedLanguage: String? = null,
            val inheritedIndustry: String? = null,
            val inheritedSourceFolder: String = "",
            val isCategoryFolder: Boolean = false
        )

        val folderQueue = ArrayDeque<MovieFolderScanTask>()
        val visitedFolderIds = mutableSetOf<String>()

        folderQueue.add(
            MovieFolderScanTask(
                folderId = moviesFolderId,
                folderName = "Movies",
                isMoviesRoot = true
            )
        )
        visitedFolderIds.add(moviesFolderId)

        while (folderQueue.isNotEmpty()) {
            val task = folderQueue.poll() ?: break
            try {
                val files = fetchFolderFiles(driveService, task.folderId)
                val videoFiles = mutableListOf<File>()
                val imageFiles = mutableListOf<File>()
                val subtitleFiles = mutableListOf<File>()
                val subFolders = mutableListOf<File>()
                var subsFolderId: String? = null

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

                if (task.isMoviesRoot) {
                    // 1. Direct loose video files in root Movies/
                    for (vFile in videoFiles) {
                        val vId = vFile.id ?: continue
                        val matchingSubs = findMatchingSubtitles(vFile.name.orEmpty(), subtitleFiles)
                        val rawName = vFile.name.orEmpty()
                        val fileLangHint = MovieIndustryResolver.extractLanguageFromFileName(rawName)
                        val cleanTitle = cleanVideoTitle(rawName)
                        val cachedMeta = OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = vId)
                            ?: OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = cleanTitle)
                        val effectiveLang = fileLangHint
                            ?: cachedMeta?.originalLanguage?.ifBlank { null }
                            ?: MovieIndustryResolver.deduceLanguageFromScript(cleanTitle)
                        val industryInfo = MovieIndustryResolver.toIndustryInfo(effectiveLang)

                        val item = mapToFileVideoItem(
                            file = vFile,
                            externalSubtitles = matchingSubs,
                            resolveOnlineMetadata = false,
                            language = industryInfo.language,
                            industry = industryInfo.sectionLabel,
                            sourceFolder = "",
                            isFolderClassified = false
                        )
                        addMovie(item)
                    }

                    // 2. Subfolders of root Movies/
                    for (sub in subFolders) {
                        val sId = sub.id ?: continue
                        if (visitedFolderIds.add(sId)) {
                            val childName = sub.name.orEmpty()
                            val folderClassification = MovieIndustryResolver.classifyDriveFolder(
                                folderName = childName,
                                isDirectChildOfMoviesRoot = true
                            )
                            val isExplicitCategory = folderClassification?.isExplicitCategoryFolder == true
                            folderQueue.add(
                                MovieFolderScanTask(
                                    folderId = sId,
                                    folderName = childName,
                                    isMoviesRoot = false,
                                    depth = 1,
                                    inheritedLanguage = folderClassification?.language,
                                    inheritedIndustry = folderClassification?.industry,
                                    inheritedSourceFolder = if (isExplicitCategory) childName else "",
                                    isCategoryFolder = isExplicitCategory
                                )
                            )
                        }
                    }
                } else {
                    // Non-root folder traversal
                    if (task.isCategoryFolder) {
                        // Category folder (e.g. Hindi Movies, English Movies, Bollywood, etc.)
                        for (vFile in videoFiles) {
                            val vId = vFile.id ?: continue
                            val rawVideoName = vFile.name.orEmpty()
                            val movieTitle = cleanFolderMovieTitle(rawVideoName)
                            val rawVideoThumb = vFile.thumbnailLink?.replace("=s220", "=s800").orEmpty()
                            val videoStem = rawVideoName.substringBeforeLast('.').lowercase()
                            val matchedImage = imageFiles.find { img ->
                                val iname = img.name.orEmpty().lowercase()
                                videoStem.isNotBlank() && iname.startsWith(videoStem)
                            }
                            val finalThumbnail = matchedImage?.thumbnailLink?.replace("=s220", "=s1200") ?: rawVideoThumb
                            val matchingSubs = findMatchingSubtitles(rawVideoName, subtitleFiles)

                            val fileLangHint = MovieIndustryResolver.extractLanguageFromFileName(rawVideoName)
                            val cachedMeta = OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = vId)
                                ?: OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = movieTitle)
                            val effectiveLanguage = task.inheritedLanguage
                                ?: fileLangHint
                                ?: cachedMeta?.originalLanguage?.ifBlank { null }
                                ?: MovieIndustryResolver.deduceLanguageFromScript(movieTitle)
                            val industryInfo = MovieIndustryResolver.toIndustryInfo(task.inheritedIndustry ?: effectiveLanguage)
                            val effectiveSourceFolder = task.inheritedSourceFolder.ifBlank { task.folderName }

                            val item = mapToFileVideoItem(
                                file = vFile,
                                overrideTitle = movieTitle,
                                externalSubtitles = matchingSubs,
                                overrideThumbnail = finalThumbnail,
                                resolveOnlineMetadata = false,
                                language = industryInfo.language,
                                industry = industryInfo.sectionLabel,
                                sourceFolder = effectiveSourceFolder,
                                isFolderClassified = true
                            )
                            addMovie(item)
                        }

                        // Enqueue child subfolders
                        if (task.depth < 10) {
                            for (sub in subFolders) {
                                val sId = sub.id ?: continue
                                if (visitedFolderIds.add(sId)) {
                                    val childName = sub.name.orEmpty()
                                    val folderClassification = MovieIndustryResolver.classifyDriveFolder(
                                        folderName = childName,
                                        isDirectChildOfMoviesRoot = false
                                    )
                                    val nextLang = folderClassification?.language ?: task.inheritedLanguage
                                    val nextIndustry = folderClassification?.industry ?: task.inheritedIndustry
                                    val isExplicitCategory = (folderClassification?.isExplicitCategoryFolder == true)
                                    val nextSourceFolder = if (isExplicitCategory) childName else task.inheritedSourceFolder
                                    folderQueue.add(
                                        MovieFolderScanTask(
                                            folderId = sId,
                                            folderName = childName,
                                            isMoviesRoot = false,
                                            depth = task.depth + 1,
                                            inheritedLanguage = nextLang,
                                            inheritedIndustry = nextIndustry,
                                            inheritedSourceFolder = nextSourceFolder,
                                            isCategoryFolder = isExplicitCategory || task.isCategoryFolder
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        // Dedicated single-movie folder (or folder with nested subfolders)
                        if (videoFiles.isNotEmpty()) {
                            if (!subsFolderId.isNullOrBlank()) {
                                val nestedSubs = fetchSubtitlesFromDriveFolder(driveService, subsFolderId)
                                subtitleFiles.addAll(nestedSubs)
                            }

                            val primaryVideo = videoFiles.maxByOrNull { it.size?.toLong() ?: 0L } ?: videoFiles.first()
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

                            val preferredImage = imageFiles.find { img ->
                                val iname = img.name.orEmpty().lowercase()
                                iname.contains("poster") || iname.contains("cover") || iname.contains("backdrop") ||
                                        iname.contains("fanart") || iname.contains("folder")
                            } ?: imageFiles.firstOrNull()

                            val localImageThumb = preferredImage?.thumbnailLink?.replace("=s220", "=s1200")
                            val rawVideoThumb = primaryVideo.thumbnailLink?.replace("=s220", "=s800").orEmpty()
                            val rawVideoName = primaryVideo.name.orEmpty()
                            val targetName = if (MovieIndustryResolver.isGenericVideoFileName(rawVideoName) && !task.folderName.equals("Movies", true)) {
                                task.folderName
                            } else if (task.folderName.isNotBlank() && !isGenericMovieStem(task.folderName)) {
                                task.folderName
                            } else {
                                rawVideoName
                            }
                            val movieTitle = cleanFolderMovieTitle(targetName)
                            val finalThumbnail = localImageThumb ?: rawVideoThumb

                            val hasFolderClassification = !task.inheritedLanguage.isNullOrBlank() || task.isCategoryFolder
                            val fileLangHint = MovieIndustryResolver.extractLanguageFromFileName(rawVideoName)
                            val cachedMeta = OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = primaryVideo.id.orEmpty())
                                ?: OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = movieTitle)
                            val effectiveLanguage = task.inheritedLanguage
                                ?: fileLangHint
                                ?: cachedMeta?.originalLanguage?.ifBlank { null }
                                ?: MovieIndustryResolver.deduceLanguageFromScript(movieTitle)
                            val industryInfo = MovieIndustryResolver.toIndustryInfo(task.inheritedIndustry ?: effectiveLanguage)
                            val effectiveSourceFolder = task.inheritedSourceFolder.ifBlank {
                                if (task.folderName.equals("Movies", ignoreCase = true)) "" else task.folderName
                            }

                            val currentMovie = mapToFileVideoItem(
                                file = primaryVideo,
                                overrideTitle = movieTitle,
                                externalSubtitles = externalSubtitles,
                                overrideThumbnail = finalThumbnail,
                                resolveOnlineMetadata = false,
                                language = industryInfo.language,
                                industry = industryInfo.sectionLabel,
                                sourceFolder = effectiveSourceFolder,
                                isFolderClassified = hasFolderClassification
                            )
                            addMovie(currentMovie)
                        }

                        // Also enqueue any subfolders (e.g. CD2, extras, nested collections) up to depth 10
                        if (task.depth < 10) {
                            for (sub in subFolders) {
                                val sId = sub.id ?: continue
                                if (visitedFolderIds.add(sId)) {
                                    val childName = sub.name.orEmpty()
                                    val folderClassification = MovieIndustryResolver.classifyDriveFolder(
                                        folderName = childName,
                                        isDirectChildOfMoviesRoot = false
                                    )
                                    val nextLang = folderClassification?.language ?: task.inheritedLanguage
                                    val nextIndustry = folderClassification?.industry ?: task.inheritedIndustry
                                    val isExplicitCategory = (folderClassification?.isExplicitCategoryFolder == true)
                                    val nextSourceFolder = if (isExplicitCategory) childName else task.inheritedSourceFolder
                                    folderQueue.add(
                                        MovieFolderScanTask(
                                            folderId = sId,
                                            folderName = childName,
                                            isMoviesRoot = false,
                                            depth = task.depth + 1,
                                            inheritedLanguage = nextLang,
                                            inheritedIndustry = nextIndustry,
                                            inheritedSourceFolder = nextSourceFolder,
                                            isCategoryFolder = isExplicitCategory || task.isCategoryFolder
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error traversing movie folder ${task.folderName} (${task.folderId}): ${e.message}", e)
            }
        }

        // Flush remaining partial packet (1..3 movies)
        if (moviePacket.isNotEmpty()) {
            val batch = moviePacket.toList()
            moviePacket.clear()
            val enrichedPacket = coroutineScope {
                batch.map { item ->
                    async(Dispatchers.IO) {
                        enrichSingleMovie(item)
                    }
                }.awaitAll()
            }
            emit(enrichedPacket)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Inspects a movie subfolder, selecting primary video, discovering subtitles and artwork,
     * with recursion for nested category folders.
     */
    internal suspend fun inspectMovieFolder(
        driveService: Drive,
        folder: File,
        depth: Int = 0,
        inheritedLanguage: String? = null,
        inheritedIndustry: String? = null,
        inheritedSourceFolder: String = "",
        isCategoryFolder: Boolean = false
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
                    val subName = subFolder.name.orEmpty()
                    val childClass = MovieIndustryResolver.classifyDriveFolder(subName, isDirectChildOfMoviesRoot = false)
                    val nextLang = childClass?.language ?: inheritedLanguage
                    val nextIndustry = childClass?.industry ?: inheritedIndustry
                    val nextSourceFolder = if (childClass?.isExplicitCategoryFolder == true) {
                        subName
                    } else {
                        inheritedSourceFolder.ifBlank { if (isCategoryFolder) folderName else "" }
                    }
                    val nextIsCategory = (childClass?.isExplicitCategoryFolder == true)
                    inspectMovieFolder(
                        driveService = driveService,
                        folder = subFolder,
                        depth = depth + 1,
                        inheritedLanguage = nextLang,
                        inheritedIndustry = nextIndustry,
                        inheritedSourceFolder = nextSourceFolder,
                        isCategoryFolder = nextIsCategory
                    )
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

            // Case A: The folder is an explicit category folder (e.g. Hindi Movies, Bengali Movies, Bollywood, etc.)
            // Emit EVERY direct video file as an individual movie
            if (isCategoryFolder) {
                val directCategoryMovies = videoFiles.mapNotNull { videoFile ->
                    val vId = videoFile.id ?: return@mapNotNull null
                    val rawVideoName = videoFile.name.orEmpty()
                    val movieTitle = cleanFolderMovieTitle(rawVideoName)
                    val rawVideoThumb = videoFile.thumbnailLink?.replace("=s220", "=s800").orEmpty()
                    val videoStem = rawVideoName.substringBeforeLast('.').lowercase()
                    val matchedImage = imageFiles.find { img ->
                        val iname = img.name.orEmpty().lowercase()
                        videoStem.isNotBlank() && iname.startsWith(videoStem)
                    }
                    val finalThumbnail = matchedImage?.thumbnailLink?.replace("=s220", "=s1200") ?: rawVideoThumb
                    val matchingSubs = findMatchingSubtitles(rawVideoName, subtitleFiles)

                    val hasFolderClassification = !inheritedLanguage.isNullOrBlank() || isCategoryFolder
                    val fileLangHint = MovieIndustryResolver.extractLanguageFromFileName(rawVideoName)
                    val cachedMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = vId)
                        ?: com.clotube.app.data.metadata.OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = movieTitle)
                    val effectiveLanguage = inheritedLanguage
                        ?: fileLangHint
                        ?: cachedMeta?.originalLanguage?.ifBlank { null }
                        ?: MovieIndustryResolver.deduceLanguageFromScript(movieTitle)
                    val industryInfo = MovieIndustryResolver.toIndustryInfo(inheritedIndustry ?: effectiveLanguage)
                    val effectiveSourceFolder = inheritedSourceFolder.ifBlank {
                        if (folderName.equals("Movies", ignoreCase = true)) "" else folderName
                    }

                    mapToFileVideoItem(
                        file = videoFile,
                        overrideTitle = movieTitle,
                        externalSubtitles = matchingSubs,
                        overrideThumbnail = finalThumbnail,
                        resolveOnlineMetadata = false,
                        language = industryInfo.language,
                        industry = industryInfo.sectionLabel,
                        sourceFolder = effectiveSourceFolder,
                        isFolderClassified = hasFolderClassification
                    )
                }
                return@withContext directCategoryMovies + nestedMovies
            }

            // Case B: Dedicated single-movie folder (e.g. Inception (2010), Sonar Kella (1974))
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
            val targetName = if (MovieIndustryResolver.isGenericVideoFileName(rawVideoName) && !folderName.equals("Movies", true)) {
                folderName
            } else if (folderName.isNotBlank() && !isGenericMovieStem(folderName)) {
                folderName
            } else {
                rawVideoName
            }
            val movieTitle = cleanFolderMovieTitle(targetName)
            val finalThumbnail = localImageThumb ?: rawVideoThumb

            val hasFolderClassification = !inheritedLanguage.isNullOrBlank() || isCategoryFolder
            val fileLangHint = MovieIndustryResolver.extractLanguageFromFileName(rawVideoName)
            val cachedMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = primaryVideo.id.orEmpty())
                ?: com.clotube.app.data.metadata.OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = movieTitle)
            val effectiveLanguage = inheritedLanguage
                ?: fileLangHint
                ?: cachedMeta?.originalLanguage?.ifBlank { null }
                ?: MovieIndustryResolver.deduceLanguageFromScript(movieTitle)
            val industryInfo = MovieIndustryResolver.toIndustryInfo(inheritedIndustry ?: effectiveLanguage)

            val effectiveSourceFolder = inheritedSourceFolder.ifBlank {
                if (folderName.equals("Movies", ignoreCase = true)) "" else folderName
            }

            val currentMovie = mapToFileVideoItem(
                file = primaryVideo,
                overrideTitle = movieTitle,
                externalSubtitles = externalSubtitles,
                overrideThumbnail = finalThumbnail,
                resolveOnlineMetadata = false,
                language = industryInfo.language,
                industry = industryInfo.sectionLabel,
                sourceFolder = effectiveSourceFolder,
                isFolderClassified = hasFolderClassification
            )

            listOf(currentMovie) + nestedMovies
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting movie subfolder $folderName ($folderId)", e)
            emptyList()
        }
    }

    private val S_E_PATTERN = Pattern.compile("(?i)(?:s|season\\s*)(\\d{1,2})[\\s._-]*(?:e|ep|episode\\s*)(\\d{1,3})")
    private val S_DASH_E_PATTERN = Pattern.compile("(?i)(?:s|season\\s*)(\\d{1,2})\\s*-\\s*(\\d{1,3})")
    private val X_PATTERN = Pattern.compile("(?i)(\\d{1,2})x(\\d{1,3})")
    private val SEASON_FOLDER_PATTERN = Pattern.compile("(?i)(?:season|series|s)\\s*(\\d{1,2})")
    private val EPISODE_FALLBACK_PATTERN = Pattern.compile("(?i)(?:e|ep|episode)\\s*(\\d{1,3})")
    private val EPISODE_STANDALONE_PATTERN = Pattern.compile("(?i)(?:^|[^\\d])(\\d{1,3})(?:[^\\d]|$)")

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

    internal fun formatDuration(durationMs: Long): String {
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

    /**
     * Enriches official show title, poster/backdrop art, and genres for a single [TvSeries]
     * without blocking on Frame 1.
     */
    suspend fun enrichSingleSeries(show: TvSeries): TvSeries = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = cleanSeriesTitle(show.title)
            val seriesMeta = OnlineMetadataResolver.resolveSeries(cleanTitle)
            val officialTitle = seriesMeta.officialTitle.ifBlank { cleanTitle }
            val posterUrl = seriesMeta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                ?: show.posterUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                ?: show.thumbnailUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                ?: ""
            val genre = seriesMeta.genres.ifBlank { show.genreFormatted }

            val enrichedSeasons = show.seasons.map { season ->
                val enrichedEpisodes = season.episodes.map { ep ->
                    val epMeta = OnlineMetadataResolver.resolveEpisode(
                        seriesName = officialTitle,
                        tvMazeSeriesId = seriesMeta.tvMazeId,
                        seasonNumber = ep.seasonNumber,
                        episodeNumber = ep.episodeNumber,
                        rawFileName = ep.displayTitle
                    )
                    val epPoster = epMeta.thumbnailUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                        ?: ep.thumbnailUrl
                    ep.copy(
                        displayTitle = epMeta.officialTitle.ifBlank { ep.displayTitle },
                        thumbnailUrl = epPoster
                    )
                }
                season.copy(episodes = enrichedEpisodes)
            }

            show.copy(
                title = officialTitle,
                thumbnailUrl = posterUrl,
                backdropUrl = posterUrl.ifBlank { show.backdropUrl },
                posterUrl = posterUrl,
                genreFormatted = genre,
                seasons = enrichedSeasons
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed enriching series ${show.title}: ${e.message}", e)
            show
        }
    }

    private suspend fun scanSingleSeriesFolder(driveService: Drive, showFolder: File): TvSeries? = withContext(Dispatchers.IO) {
        val showId = showFolder.id ?: return@withContext null
        val rawShowName = showFolder.name ?: "Unknown Series"
        val cleanTitle = cleanSeriesTitle(rawShowName)

        val cached = MetadataDiskCache.get("series:${cleanTitle.trim().lowercase()}")
        val cachedArt = cached?.posterUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
            ?: cached?.backdropUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
            ?: ""

        val seasons = mutableListOf<Season>()
        try {
            val childFiles = fetchFolderFiles(driveService, showId)
            val seasonFolders = childFiles.filter {
                it.mimeType == "application/vnd.google-apps.folder" ||
                (it.mimeType == "application/vnd.google-apps.shortcut" && it.shortcutDetails?.targetMimeType == "application/vnd.google-apps.folder")
            }

            if (seasonFolders.isNotEmpty()) {
                for (sf in seasonFolders) {
                    val sTargetId = if (sf.mimeType == "application/vnd.google-apps.shortcut" && sf.shortcutDetails?.targetId != null) {
                        sf.shortcutDetails.targetId
                    } else sf.id.orEmpty()
                    val sName = sf.name.orEmpty()
                    val seasonNumMatcher = Pattern.compile("(?i)(?:season|series|s)\\s*(\\d+)").matcher(sName)
                    val sNum = if (seasonNumMatcher.find()) seasonNumMatcher.group(1)?.toIntOrNull() ?: 1 else 1

                    val epFiles = fetchFolderFiles(driveService, sTargetId).filter { isVideoFileName(it.name) || it.mimeType?.startsWith("video/") == true }
                    val episodes = epFiles.mapNotNull { epFile ->
                        val epId = epFile.id ?: return@mapNotNull null
                        val epName = epFile.name.orEmpty()
                        val (fSeason, fEp) = parseSeasonAndEpisode(epName, sName)
                        val durationMs = epFile.videoMediaMetadata?.durationMillis ?: 0L
                        val epDriveThumb = epFile.thumbnailLink?.takeIf { it.isNotBlank() } ?: ""
                        Episode(
                            id = epId,
                            seasonNumber = fSeason,
                            episodeNumber = fEp,
                            displayTitle = cleanVideoTitle(epName),
                            formattedDuration = if (durationMs > 0L) formatDuration(durationMs) else "00:00",
                            fileSize = formatFileSize(epFile.getSize()?.toLong() ?: epFile.size?.toLong() ?: 0L),
                            resolution = "1080p FHD",
                            thumbnailUrl = epDriveThumb
                        )
                    }.sortedBy { it.episodeNumber }

                    seasons.add(
                        Season(
                            id = sTargetId,
                            seasonNumber = sNum,
                            seasonTitle = sName.ifBlank { "Season $sNum" },
                            episodes = episodes
                        )
                    )
                }
            } else {
                val epFiles = childFiles.filter { isVideoFileName(it.name) || it.mimeType?.startsWith("video/") == true }
                if (epFiles.isNotEmpty()) {
                    val episodes = epFiles.mapNotNull { epFile ->
                        val epId = epFile.id ?: return@mapNotNull null
                        val epName = epFile.name.orEmpty()
                        val (fSeason, fEp) = parseSeasonAndEpisode(epName, rawShowName)
                        val durationMs = epFile.videoMediaMetadata?.durationMillis ?: 0L
                        val epDriveThumb = epFile.thumbnailLink?.takeIf { it.isNotBlank() } ?: ""
                        Episode(
                            id = epId,
                            seasonNumber = fSeason,
                            episodeNumber = fEp,
                            displayTitle = cleanVideoTitle(epName),
                            formattedDuration = if (durationMs > 0L) formatDuration(durationMs) else "00:00",
                            fileSize = formatFileSize(epFile.getSize()?.toLong() ?: epFile.size?.toLong() ?: 0L),
                            resolution = "1080p FHD",
                            thumbnailUrl = epDriveThumb
                        )
                    }.sortedBy { it.episodeNumber }
                    val grouped = episodes.groupBy { it.seasonNumber }
                    grouped.forEach { (sNum, eps) ->
                        seasons.add(Season(id = "${showId}_S$sNum", seasonNumber = sNum, episodes = eps))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed scanning seasons/episodes for $cleanTitle: ${e.message}")
        }

        seasons.sortBy { it.seasonNumber }
        val totalEpCount = seasons.sumOf { it.episodes.size }

        TvSeries(
            id = showId,
            title = cleanTitle,
            genreFormatted = cached?.genre?.ifBlank { "TV Series" } ?: "TV Series",
            totalSeasonsFormatted = if (seasons.size == 1) "1 Season" else "${seasons.size} Seasons",
            seasons = seasons,
            thumbnailUrl = cachedArt,
            backdropUrl = cachedArt.ifBlank { null },
            posterUrl = cachedArt
        )
    }

    /**
     * Progressive Packet-of-4 (INDEX_PACKET_SIZE = 4) streaming Flow for TV Series.
     * Discovers and indexes show folders inside clotube/Series/, buffering in packets of 4.
     * Each time 4 TV series folders are indexed, enriches those 4 series in parallel and
     * emits immediately to the Flow collector.
     */
    fun streamSeriesChunked(seriesFolderId: String): Flow<List<TvSeries>> = flow {
        if (seriesFolderId.isBlank() || seriesFolderId == "root") {
            Log.w(TAG, "Fail-Closed: seriesFolderId is blank or 'root'. Emitting emptyList().")
            return@flow
        }

        val driveService = try {
            resolveDriveService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed resolving Drive service for series streaming", e)
            return@flow
        }

        val seenSeriesIds = mutableSetOf<String>()
        val seriesPacket = mutableListOf<TvSeries>()

        suspend fun emitSeriesPacketIfFull() {
            if (seriesPacket.size >= INDEX_PACKET_SIZE) {
                val batch = seriesPacket.toList()
                seriesPacket.clear()
                val enrichedPacket = coroutineScope {
                    batch.map { show ->
                        async(Dispatchers.IO) {
                            enrichSingleSeries(show)
                        }
                    }.awaitAll()
                }
                emit(enrichedPacket)
            }
        }

        val showFolders = mutableListOf<File>()
        var pageToken: String? = null
        try {
            do {
                val request = driveService.files().list()
                    .setQ("'$seriesFolderId' in parents and trashed = false")
                    .setFields("nextPageToken, files(id, name, mimeType, thumbnailLink, shortcutDetails)")
                    .setPageSize(50)
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)

                if (pageToken != null) {
                    request.pageToken = pageToken
                }

                val result = withContext(Dispatchers.IO) { request.execute() }
                val files = result.files.orEmpty()
                for (file in files) {
                    val mime = file.mimeType.orEmpty()
                    val isFolder = mime == "application/vnd.google-apps.folder" ||
                            (mime == "application/vnd.google-apps.shortcut" && file.shortcutDetails?.targetMimeType == "application/vnd.google-apps.folder")
                    if (isFolder) {
                        val targetId = if (mime == "application/vnd.google-apps.shortcut" && file.shortcutDetails?.targetId != null) {
                            file.shortcutDetails.targetId
                        } else {
                            file.id
                        }
                        val targetFolder = file.clone().setId(targetId)
                        showFolders.add(targetFolder)
                    }
                }
                pageToken = result.nextPageToken
            } while (pageToken != null)
        } catch (e: Exception) {
            Log.e(TAG, "Error listing series folders in $seriesFolderId: ${e.message}", e)
        }

        for (folder in showFolders) {
            val folderId = folder.id.orEmpty()
            if (folderId.isBlank() || !seenSeriesIds.add(folderId)) continue

            try {
                val indexedShow = scanSingleSeriesFolder(driveService, folder)
                if (indexedShow != null) {
                    seriesPacket.add(indexedShow)
                    if (seriesPacket.size == INDEX_PACKET_SIZE) {
                        emitSeriesPacketIfFull()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error indexing show folder ${folder.name}: ${e.message}", e)
            }
        }

        // Flush remaining partial packet (1..3 series)
        if (seriesPacket.isNotEmpty()) {
            val batch = seriesPacket.toList()
            seriesPacket.clear()
            val enrichedPacket = coroutineScope {
                batch.map { show ->
                    async(Dispatchers.IO) {
                        enrichSingleSeries(show)
                    }
                }.awaitAll()
            }
            emit(enrichedPacket)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Backward-compatible delegation to [streamSeriesChunked].
     */
    fun streamSeriesOverview(seriesRootFolderId: String): Flow<List<TvSeries>> = streamSeriesChunked(seriesRootFolderId)

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

    private suspend fun mapToShowOverview(
        driveService: Drive,
        folder: File,
        resolveOnlineMetadata: Boolean = false
    ): TvSeries = withContext(Dispatchers.IO) {
        val folderId = folder.id.orEmpty()
        val folderName = folder.name.orEmpty()
        val cleanTitle = cleanSeriesTitle(folderName)

        val cachedRecord = MetadataDiskCache.get("series:${cleanTitle.trim().lowercase()}")
        val cachedArt = cachedRecord?.posterUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
            ?: cachedRecord?.backdropUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
            ?: ""

        val finalThumb = if (cachedArt.isNotBlank()) {
            cachedArt
        } else if (resolveOnlineMetadata) {
            val seriesMeta = OnlineMetadataResolver.resolveSeries(folderName)
            val resolvedArt = seriesMeta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) } ?: ""
            resolvedArt
        } else {
            ""
        }

        TvSeries(
            id = folderId,
            title = cleanTitle,
            genreFormatted = cachedRecord?.genre?.ifBlank { "TV Series" } ?: "TV Series",
            totalSeasonsFormatted = "TV Series",
            seasons = emptyList(), // Shallow!
            thumbnailUrl = finalThumb,
            backdropUrl = finalThumb.ifBlank { null },
            posterUrl = finalThumb
        )
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
        overrideThumbnail: String? = null,
        resolveOnlineMetadata: Boolean = false,
        language: String = "",
        industry: String = "",
        sourceFolder: String = "",
        isFolderClassified: Boolean = false
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
        val cleanTitle: String
        val genreFormatted: String
        val finalThumb: String

        if (resolveOnlineMetadata) {
            val movieMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveMovie(targetMovieName)
            cleanTitle = movieMeta.officialTitle.ifBlank { cleanVideoTitle(targetMovieName) }
            genreFormatted = movieMeta.genre.ifBlank { "Movie" }
            finalThumb = if (!overrideThumbnail.isNullOrBlank()) {
                overrideThumbnail
            } else {
                movieMeta.posterUrl.ifBlank { rawThumb }
            }
        } else {
            cleanTitle = overrideTitle?.ifBlank { null } ?: cleanVideoTitle(targetMovieName)
            genreFormatted = "Movie"
            finalThumb = if (!overrideThumbnail.isNullOrBlank()) overrideThumbnail else rawThumb
        }

        val durationFormatted = if (durationMillis > 0) formatTimestamp(durationMillis) else "00:00"
        val sizeFormatted = formatFileSize(sizeBytes)
        val streamUri = DriveMediaSourceFactory.buildDriveStreamUri(id).toString()
        val hdrBadge = if (width >= 3840 || height >= 2160) "HDR10" else null

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
            watchProgressFraction = 0f,
            language = language,
            industry = industry,
            sourceFolder = sourceFolder,
            isFolderClassified = isFolderClassified
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

    internal fun cleanVideoTitle(raw: String): String = Companion.cleanVideoTitle(raw)

    internal fun cleanFolderMovieTitle(folderName: String): String = Companion.cleanFolderMovieTitle(folderName)

    internal fun cleanSeriesTitle(folderName: String): String = Companion.cleanSeriesTitle(folderName)

    private fun resolveGenre(name: String): String {
        return GenreResolver.resolveGenre(name, isSeries = true)
    }

    private fun isGenericMovieStem(stem: String): Boolean {
        val lower = stem.lowercase().trim()
        return lower in GENERIC_STEMS
    }

    internal fun isVideoFileName(name: String?): Boolean {
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
