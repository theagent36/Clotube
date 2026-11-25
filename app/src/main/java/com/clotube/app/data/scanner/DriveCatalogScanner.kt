package com.clotube.app.data.scanner

import android.util.Log
import com.clotube.app.data.DriveDirectoryManager
import com.clotube.app.data.FreshBootScanResult
import com.clotube.app.data.MediaArtworkResolver
import com.clotube.app.data.MetadataDiskCache
import com.clotube.app.data.MovieIndustryResolver
import com.clotube.app.data.WatchlistManager
import com.clotube.app.data.metadata.OnlineMetadataResolver
import com.clotube.app.model.Episode
import com.clotube.app.model.Movie
import com.clotube.app.model.Season
import com.clotube.app.model.Series
import com.clotube.app.model.TvSeries
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.Locale
import java.util.regex.Pattern

object DriveCatalogScanner {

    private const val TAG = "DriveCatalogScanner"
    private const val TEST_TAG = "ClotubeScannerTest"
    const val INDEX_PACKET_SIZE = 4
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
    // COLD-BOOT RESCAN (BYPASSES ALL IN-MEMORY CACHES)
    // ========================================================

    suspend fun performFreshBootScan(): FreshBootScanResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Initiating Fresh-Boot Drive scan (bypassing in-memory cache)...")
        try {
            val driveService = getDriveService() ?: return@withContext FreshBootScanResult(emptyList(), emptyList())
            val dirs = DriveDirectoryManager.resolveHierarchy(driveService)

            // 1. Rescan Movies Folder (Packet-of-4 progressive stream)
            val freshMovies = scanMoviesRecursively(driveService, dirs.moviesFolderId ?: "")

            // 2. Rescan Series Folders (Packet-of-4 progressive stream)
            val freshSeries = scanSeriesRecursively(driveService, dirs.seriesFolderId ?: "")

            Log.d(TAG, "Fresh-Boot scan complete: ${freshMovies.size} movies, ${freshSeries.size} series.")
            FreshBootScanResult(movies = freshMovies, series = freshSeries)
        } catch (e: Exception) {
            Log.e(TAG, "Fresh-Boot scan failed: ${e.message}", e)
            FreshBootScanResult(emptyList(), emptyList())
        }
    }

    suspend fun enrichCatalogMetadata(
        rawResult: FreshBootScanResult,
        maxConcurrency: Int = 5
    ): FreshBootScanResult = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(maxConcurrency)

        val enrichedMovies = rawResult.movies.map { movie ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val meta = OnlineMetadataResolver.resolveMovie(movie.title.ifBlank { movie.fileName })
                        val validPoster = meta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                            ?: movie.thumbnailUrl?.takeIf { !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
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
                            enrichedLang = movie.language
                            enrichedIndustry = movie.industry
                        }

                        if (meta.officialTitle.isNotBlank() || !validPoster.isNullOrBlank() || enrichedIndustry != movie.industry) {
                            movie.copy(
                                title = meta.officialTitle.ifBlank { movie.title },
                                thumbnailUrl = validPoster,
                                fallbackThumbnailUrl = movie.fallbackThumbnailUrl.ifBlank {
                                    if (OnlineMetadataResolver.isDriveVideoFrameUrl(movie.thumbnailUrl)) movie.thumbnailUrl.orEmpty() else ""
                                },
                                genre = meta.genre.ifBlank { movie.genre },
                                language = enrichedLang,
                                industry = enrichedIndustry,
                                sourceFolder = movie.sourceFolder,
                                isFolderClassified = movie.isFolderClassified
                            )
                        } else movie
                    } catch (e: Exception) {
                        movie
                    }
                }
            }
        }.awaitAll()

        val enrichedSeries = rawResult.series.map { show ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val meta = OnlineMetadataResolver.resolveSeries(show.title)
                        if (meta.officialTitle.isNotBlank() || meta.posterUrl.isNotBlank()) {
                            show.copy(
                                title = meta.officialTitle.ifBlank { show.title },
                                posterUrl = meta.posterUrl.ifBlank { show.posterUrl },
                                genreFormatted = meta.genres.ifBlank { show.genreFormatted }
                            )
                        } else show
                    } catch (e: Exception) {
                        show
                    }
                }
            }
        }.awaitAll()

        FreshBootScanResult(movies = enrichedMovies, series = enrichedSeries)
    }

    private suspend fun scanMoviesDirect(
        driveService: Drive,
        resolvedMoviesFolderId: String? = null
    ): List<Movie> = withContext(Dispatchers.IO) {
        try {
            val moviesFolderId = resolvedMoviesFolderId
                ?: DriveDirectoryManager.resolveHierarchy(driveService).moviesFolderId
                ?: return@withContext emptyList()

            val fileQuery = "'$moviesFolderId' in parents and mimeType contains 'video/' and trashed = false"
            val fileRes = driveService.files().list()
                .setQ(fileQuery)
                .setFields("files(id, name, size, videoMediaMetadata, thumbnailLink)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setPageSize(100)
                .execute()

            val directMovies = fileRes.files?.mapNotNull { file ->
                val fId = file.id ?: return@mapNotNull null
                val durationMs = file.videoMediaMetadata?.durationMillis ?: 0L
                val cleanTitle = cleanMovieTitle(file.name.orEmpty())
                val driveThumb = file.thumbnailLink?.replace("=s220", "=s800").orEmpty()

                val cachedMeta = OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = fId)
                    ?: OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = cleanTitle)
                val hasValidPoster = cachedMeta != null && cachedMeta.posterUrl.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(cachedMeta.posterUrl)
                val finalTitle = if (hasValidPoster && cachedMeta?.officialTitle?.isNotBlank() == true) cachedMeta.officialTitle else cleanTitle
                val finalThumb = if (hasValidPoster) cachedMeta!!.posterUrl else driveThumb

                val fileLangHint = MovieIndustryResolver.extractLanguageFromFileName(file.name.orEmpty())
                val effectiveLang = fileLangHint
                    ?: cachedMeta?.originalLanguage?.ifBlank { null }
                    ?: MovieIndustryResolver.deduceLanguageFromScript(finalTitle)
                val industryInfo = MovieIndustryResolver.toIndustryInfo(effectiveLang)

                Movie(
                    id = fId,
                    title = finalTitle,
                    fileName = file.name.orEmpty(),
                    durationMs = durationMs,
                    thumbnailUrl = finalThumb,
                    fallbackThumbnailUrl = driveThumb,
                    language = industryInfo.language,
                    industry = industryInfo.sectionLabel,
                    sourceFolder = "",
                    isFolderClassified = false
                )
            } ?: emptyList()

            val recursiveMovies = scanMoviesRecursively(driveService, moviesFolderId)
            val allMovies = (directMovies + recursiveMovies).distinctBy { it.id }.sortedBy { it.title }
            allMovies
        } catch (e: Exception) {
            Log.e(TAG, "Error in scanMoviesDirect: ${e.message}", e)
            emptyList()
        }
    }

    private suspend fun scanSeriesDirect(
        driveService: Drive,
        resolvedSeriesFolderId: String? = null
    ): List<Series> = withContext(Dispatchers.IO) {
        try {
            val seriesRootId = resolvedSeriesFolderId
                ?: DriveDirectoryManager.resolveHierarchy(driveService).seriesFolderId
                ?: return@withContext emptyList()

            val showFoldersQuery = "'$seriesRootId' in parents and mimeType = '$FOLDER_MIME' and trashed = false"
            val showFolders = driveService.files().list()
                .setQ(showFoldersQuery)
                .setFields("files(id, name, thumbnailLink)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)
                .setPageSize(50)
                .execute()
                .files ?: emptyList()

            val scannedSeries = showFolders.mapNotNull { showFolder ->
                val showId = showFolder.id ?: return@mapNotNull null
                val showName = showFolder.name ?: "Unknown Series"
                val cleaned = cleanSeriesTitle(showName)
                val cached = MetadataDiskCache.get("series:${cleaned.trim().lowercase()}")
                val cachedArt = cached?.posterUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                    ?: cached?.backdropUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                    ?: ""
                Series(
                    id = showId,
                    title = cleaned,
                    posterUrl = cachedArt,
                    thumbnailUrl = cachedArt,
                    backdropUrl = cachedArt,
                    genre = cached?.genre?.ifBlank { "TV Series" } ?: "TV Series",
                    seasons = emptyList(),
                    totalEpisodes = 0
                )
            }

            if (scannedSeries.isNotEmpty()) {
                scannedSeries.sortedBy { it.title }
            } else {
                scanSeriesRecursively(driveService, seriesRootId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in scanSeriesDirect: ${e.message}", e)
            emptyList()
        }
    }

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
                val fileLangHint = MovieIndustryResolver.extractLanguageFromFileName(rawName)
                val effectiveLang = fileLangHint
                    ?: movieMeta.originalLanguage.ifBlank { null }
                    ?: MovieIndustryResolver.deduceLanguageFromScript(movieMeta.officialTitle)
                val industryInfo = MovieIndustryResolver.toIndustryInfo(effectiveLang)

                Movie(
                    id = fileId,
                    title = movieMeta.officialTitle,
                    durationMs = rawDurationMs,
                    formattedDuration = formatDuration(rawDurationMs),
                    resolution = "1080p FHD",
                    thumbnailUrl = movieMeta.posterUrl.ifBlank { driveThumb },
                    language = industryInfo.language,
                    industry = industryInfo.sectionLabel,
                    sourceFolder = "",
                    isFolderClassified = false
                )
            }.sortedBy { it.title }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning movies: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun enrichSingleMovie(movie: Movie): Movie = withContext(Dispatchers.IO) {
        try {
            val meta = OnlineMetadataResolver.resolveMovie(movie.title.ifBlank { movie.fileName })
            val validPoster = meta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                ?: movie.thumbnailUrl?.takeIf { !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
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
                enrichedLang = movie.language
                enrichedIndustry = movie.industry
            }

            movie.copy(
                title = meta.officialTitle.ifBlank { movie.title },
                thumbnailUrl = validPoster,
                fallbackThumbnailUrl = movie.fallbackThumbnailUrl.ifBlank {
                    if (OnlineMetadataResolver.isDriveVideoFrameUrl(movie.thumbnailUrl)) movie.thumbnailUrl.orEmpty() else ""
                },
                genre = meta.genre.ifBlank { movie.genre },
                language = enrichedLang,
                industry = enrichedIndustry,
                sourceFolder = movie.sourceFolder,
                isFolderClassified = movie.isFolderClassified
            )
        } catch (e: Exception) {
            movie
        }
    }

    suspend fun enrichSingleSeries(show: TvSeries): TvSeries = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = cleanSeriesTitle(show.title)
            val meta = OnlineMetadataResolver.resolveSeries(cleanTitle)
            val validPoster = meta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                ?: show.posterUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                ?: show.thumbnailUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                ?: ""

            show.copy(
                title = meta.officialTitle.ifBlank { show.title },
                posterUrl = validPoster,
                thumbnailUrl = validPoster,
                backdropUrl = validPoster.ifBlank { show.backdropUrl },
                genreFormatted = meta.genres.ifBlank { show.genreFormatted }
            )
        } catch (e: Exception) {
            show
        }
    }

    fun streamMoviesChunked(
        driveService: Drive,
        rootMoviesFolderId: String
    ): Flow<List<Movie>> = flow {
        if (rootMoviesFolderId.isBlank() || rootMoviesFolderId == "root") return@flow

        val seenMovieIds = mutableSetOf<String>()
        val moviePacket = mutableListOf<Movie>()

        suspend fun emitMoviePacketIfFull() {
            if (moviePacket.size >= INDEX_PACKET_SIZE) {
                val batch = moviePacket.toList()
                moviePacket.clear()
                val enrichedPacket = coroutineScope {
                    batch.map { movie ->
                        async(Dispatchers.IO) {
                            enrichSingleMovie(movie)
                        }
                    }.awaitAll()
                }
                emit(enrichedPacket)
            }
        }

        suspend fun addMovie(movie: Movie) {
            if (movie.id.isBlank() || !seenMovieIds.add(movie.id)) return
            moviePacket.add(movie)
            if (moviePacket.size == INDEX_PACKET_SIZE) {
                emitMoviePacketIfFull()
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

        folderQueue.add(MovieFolderScanTask(rootMoviesFolderId, "Movies", isMoviesRoot = true))
        visitedFolderIds.add(rootMoviesFolderId)

        Log.d(TAG, "Starting progressive Packet-of-4 Movies scan from root: $rootMoviesFolderId")

        while (folderQueue.isNotEmpty()) {
            val task = folderQueue.poll() ?: break
            val currentFolderId = task.folderId
            val currentFolderName = task.folderName
            val items = fetchFolderContents(driveService, currentFolderId)

            for (item in items) {
                val resolvedId = resolveTargetId(item)
                val isFolder = item.mimeType == FOLDER_MIME || 
                               (item.mimeType == SHORTCUT_MIME && item.shortcutDetails?.targetMimeType == FOLDER_MIME)

                if (isFolder) {
                    if (visitedFolderIds.add(resolvedId)) {
                        val childName = item.name ?: "Untitled Folder"
                        val folderClassification = MovieIndustryResolver.classifyDriveFolder(
                            folderName = childName,
                            isDirectChildOfMoviesRoot = task.isMoviesRoot
                        )
                        val nextLang = folderClassification?.language ?: task.inheritedLanguage
                        val nextIndustry = folderClassification?.industry ?: task.inheritedIndustry
                        val isExplicitCategory = folderClassification?.isExplicitCategoryFolder == true
                        val nextSourceFolder = if (isExplicitCategory) {
                            childName
                        } else {
                            task.inheritedSourceFolder.ifBlank { if (task.isCategoryFolder) currentFolderName else "" }
                        }
                        val nextIsCategory = isExplicitCategory || task.isCategoryFolder
                        folderQueue.add(
                            MovieFolderScanTask(
                                folderId = resolvedId,
                                folderName = childName,
                                isMoviesRoot = false,
                                depth = task.depth + 1,
                                inheritedLanguage = nextLang,
                                inheritedIndustry = nextIndustry,
                                inheritedSourceFolder = nextSourceFolder,
                                isCategoryFolder = nextIsCategory
                            )
                        )
                    }
                } else if (isVideoFile(item)) {
                    val rawBytes = item.getSize()?.toLong() ?: 0L
                    val rawDurationMs = item.videoMediaMetadata?.durationMillis ?: 0L
                    val driveThumb = item.thumbnailLink?.replace("=s220", "=s800") ?: ""
                    val rawName = item.name.orEmpty()
                    val targetName = if (!task.isMoviesRoot && !task.isCategoryFolder && MovieIndustryResolver.isGenericVideoFileName(rawName)) {
                        currentFolderName
                    } else {
                        rawName
                    }
                    val cleanedTitle = cleanMovieTitle(targetName, currentFolderName)

                    val cachedMeta = OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = resolvedId)
                        ?: OnlineMetadataResolver.getCachedMovieMetadata(idOrTitle = cleanedTitle)
                    val hasValidPoster = cachedMeta != null && cachedMeta.posterUrl.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(cachedMeta.posterUrl)
                    val finalTitle = if (hasValidPoster && cachedMeta?.officialTitle?.isNotBlank() == true) cachedMeta.officialTitle else cleanedTitle
                    val finalThumb = if (hasValidPoster) cachedMeta!!.posterUrl else driveThumb

                    val hasFolderClassification = !task.inheritedLanguage.isNullOrBlank() || task.isCategoryFolder
                    val fileLangHint = MovieIndustryResolver.extractLanguageFromFileName(rawName)
                    val effectiveLang = task.inheritedIndustry
                        ?: task.inheritedLanguage
                        ?: fileLangHint
                        ?: cachedMeta?.originalLanguage?.ifBlank { null }
                        ?: MovieIndustryResolver.deduceLanguageFromScript(cleanedTitle)
                    val industryInfo = MovieIndustryResolver.toIndustryInfo(effectiveLang)

                    val effectiveSourceFolder = task.inheritedSourceFolder.ifBlank {
                        if (task.isMoviesRoot) "" else currentFolderName
                    }

                    val movie = Movie(
                        id = resolvedId,
                        title = finalTitle,
                        fileName = rawName,
                        fileSize = formatFileSize(rawBytes),
                        mimeType = item.mimeType ?: "video/mp4",
                        thumbnailUrl = finalThumb,
                        fallbackThumbnailUrl = driveThumb,
                        parentFolder = currentFolderName,
                        durationFormatted = if (rawDurationMs > 0L) formatDuration(rawDurationMs) else "00:00",
                        resolutionBadge = "1080p FHD",
                        genre = cachedMeta?.genre?.takeIf { it.isNotBlank() } ?: "Movie",
                        sizeBytes = rawBytes,
                        language = industryInfo.language,
                        industry = industryInfo.sectionLabel,
                        sourceFolder = effectiveSourceFolder,
                        isFolderClassified = hasFolderClassification
                    )
                    addMovie(movie)
                }
            }
        }

        // Flush remaining partial packet (1..3 movies)
        if (moviePacket.isNotEmpty()) {
            val batch = moviePacket.toList()
            moviePacket.clear()
            val enrichedPacket = coroutineScope {
                batch.map { movie ->
                    async(Dispatchers.IO) {
                        enrichSingleMovie(movie)
                    }
                }.awaitAll()
            }
            emit(enrichedPacket)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun scanMoviesRecursively(
        driveService: Drive,
        rootMoviesFolderId: String
    ): List<Movie> = withContext(Dispatchers.IO) {
        val movies = mutableListOf<Movie>()
        streamMoviesChunked(driveService, rootMoviesFolderId).collect { packet ->
            movies.addAll(packet)
        }
        movies.distinctBy { it.id }.sortedBy { it.title }
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

    fun streamSeriesChunked(
        driveService: Drive,
        rootSeriesFolderId: String
    ): Flow<List<TvSeries>> = flow {
        if (rootSeriesFolderId.isBlank() || rootSeriesFolderId == "root") return@flow

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

        val topSeriesItems = fetchFolderContents(driveService, rootSeriesFolderId)

        for (seriesFolder in topSeriesItems) {
            val isFolder = seriesFolder.mimeType == FOLDER_MIME || 
                           (seriesFolder.mimeType == SHORTCUT_MIME && seriesFolder.shortcutDetails?.targetMimeType == FOLDER_MIME)
            if (!isFolder) continue

            val seriesId = resolveTargetId(seriesFolder)
            if (seriesId.isBlank() || !seenSeriesIds.add(seriesId)) continue

            val rawShowName = seriesFolder.name ?: "Untitled Series"

            val seriesMeta = OnlineMetadataResolver.resolveSeries(rawShowName)

            val episodesFound = mutableListOf<Episode>()
            val discoveredSeasons = mutableMapOf<Int, Season>()

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

                val cleanTitle = seriesMeta.officialTitle.ifBlank { cleanSeriesTitle(rawShowName) }
                val cachedRecord = MetadataDiskCache.get("series:${cleanTitle.trim().lowercase()}")
                val officialPoster = listOfNotNull(
                    seriesMeta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) },
                    cachedRecord?.posterUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) },
                    cachedRecord?.backdropUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                ).firstOrNull().orEmpty()

                val show = TvSeries(
                    id = seriesId,
                    title = cleanTitle,
                    posterUrl = officialPoster,
                    backdropUrl = officialPoster.ifBlank { null },
                    thumbnailUrl = officialPoster,
                    genre = seriesMeta.genres.ifBlank { cachedRecord?.genre ?: "TV Series" },
                    seasons = finalSeasons,
                    totalEpisodes = episodesFound.size
                )
                seriesPacket.add(show)
                Log.d(TAG, "Cataloged Series: ${seriesMeta.officialTitle} (${finalSeasons.size} seasons, ${episodesFound.size} episodes)")
                if (seriesPacket.size == INDEX_PACKET_SIZE) {
                    emitSeriesPacketIfFull()
                }
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

    suspend fun scanSeriesRecursively(
        driveService: Drive,
        rootSeriesFolderId: String
    ): List<TvSeries> = withContext(Dispatchers.IO) {
        val series = mutableListOf<TvSeries>()
        streamSeriesChunked(driveService, rootSeriesFolderId).collect { packet ->
            series.addAll(packet)
        }
        series.distinctBy { it.id }.sortedBy { it.title }
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

    internal fun cleanMovieTitle(fileName: String, parentFolderName: String = ""): String {
        val baseName = fileName.substringBeforeLast(".")
        val genericNames = setOf("movie", "video", "film", "cd1", "cd2", "stream", "play")
        val raw = if (genericNames.contains(baseName.lowercase(Locale.ROOT)) && parentFolderName.isNotBlank() && parentFolderName != "Movies") {
            parentFolderName
        } else {
            baseName
        }
        var cleaned = raw.replace(".", " ").replace("_", " ").trim()
        // Strip trailing parenthesized or bracketed release year e.g. (2015), [2015], (2026), [2026]
        cleaned = cleaned.replace(Regex("""(?i)\s*[\(\[]\s*(19\d{2}|20\d{2})\s*[\)\]]\s*$"""), "")
        // Strip bare trailing 4-digit release year e.g. "The Visit 2015" -> "The Visit", "Crime 101 2026" -> "Crime 101"
        cleaned = cleaned.replace(Regex("""(?i)\s+\b(19\d{2}|20\d{2})\b\s*$"""), "")
        cleaned = cleaned.replace(Regex("""\s+"""), " ").trim(' ', '-', '•', ':')
        return if (cleaned.isBlank()) baseName else cleaned
    }

    internal fun cleanSeriesTitle(folderName: String): String {
        return folderName
            .replace(Regex("""(?i)[\[\(](19\d{2}|20\d{2})[\]\)]"""), "")
            .replace(Regex("""(?i)[\[\(].*?[\]\)]"""), "")
            .replace(Regex("""(?i)[._ -]*(1080p|720p|2160p|4k|complete|hevc|x265|x264).*"""), "")
            .replace('.', ' ')
            .replace('_', ' ')
            .trim()
            .ifBlank { folderName }
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
     * Stage-1 Priority Fast-Start Fetcher:
     * Fetches only the top [limit] (default 2) latest movies from Google Drive, resolving all
     * metadata (official poster, backdrop, genres, and clean filtered title) in parallel BEFORE returning.
     */
    suspend fun fetchTopLatestMoviesEnriched(
        driveService: Drive,
        moviesFolderId: String,
        limit: Int = 2
    ): List<Movie> = withContext(Dispatchers.IO) {
        if (moviesFolderId.isBlank() || moviesFolderId == "root") return@withContext emptyList()
        try {
            val query = "'$moviesFolderId' in parents and (mimeType contains 'video/' or mimeType = '$FOLDER_MIME') and trashed = false"
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
                val isFolder = mime == FOLDER_MIME ||
                        (mime == SHORTCUT_MIME && file.shortcutDetails?.targetMimeType == FOLDER_MIME)

                if (isFolder) {
                    val targetId = resolveTargetId(file)
                    val folderMovies = scanMoviesFolder(driveService, targetId)
                    for (m in folderMovies) {
                        if (candidateMovies.size >= limit) break
                        if (m.id.isNotBlank() && candidateMovies.none { it.id == m.id }) {
                            candidateMovies.add(m)
                        }
                    }
                } else if (isVideoFile(file)) {
                    val fileId = file.id ?: continue
                    if (candidateMovies.none { it.id == fileId }) {
                        val rawName = file.name.orEmpty()
                        val cleanTitle = cleanMovieTitle(rawName)
                        val rawDurationMs = file.videoMediaMetadata?.durationMillis ?: 0L
                        val fileLangHint = MovieIndustryResolver.extractLanguageFromFileName(rawName)
                        val industryInfo = MovieIndustryResolver.toIndustryInfo(fileLangHint)
                        candidateMovies.add(
                            Movie(
                                id = fileId,
                                title = cleanTitle,
                                durationMs = rawDurationMs,
                                formattedDuration = formatDuration(rawDurationMs),
                                resolution = "1080p FHD",
                                thumbnailUrl = file.thumbnailLink.orEmpty(),
                                language = industryInfo.language,
                                industry = industryInfo.sectionLabel,
                                sourceFolder = "",
                                isFolderClassified = false
                            )
                        )
                    }
                }
            }

            candidateMovies.take(limit).map { movie ->
                async(Dispatchers.IO) {
                    try {
                        val cleanTitle = cleanMovieTitle(movie.title.ifBlank { movie.fileName })
                        val meta = OnlineMetadataResolver.resolveMovie(cleanTitle)
                        val artwork = MediaArtworkResolver.resolveMovieArtwork(cleanTitle, movie.thumbnailUrl)
                        val finalTitle = meta.officialTitle.ifBlank { cleanTitle }
                        val finalPoster = meta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                            ?: artwork.posterUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                            ?: movie.thumbnailUrl?.takeIf { !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                            ?: movie.fallbackThumbnailUrl
                        val finalBackdrop = artwork.backdropUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                            ?: finalPoster
                        val industryInfo = MovieIndustryResolver.toIndustryInfo(
                            movie.language.ifBlank { meta.originalLanguage }
                        )
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
                        movie
                    }
                }
            }.awaitAll()
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
     */
    suspend fun fetchTopLatestSeriesEnriched(
        driveService: Drive,
        seriesFolderId: String,
        limit: Int = 2
    ): List<TvSeries> = withContext(Dispatchers.IO) {
        if (seriesFolderId.isBlank() || seriesFolderId == "root") return@withContext emptyList()
        try {
            val query = "'$seriesFolderId' in parents and mimeType = '$FOLDER_MIME' and trashed = false"
            val request = driveService.files().list()
                .setQ(query)
                .setOrderBy("createdTime desc")
                .setPageSize(limit)
                .setFields("files(id, name, thumbnailLink)")
                .setSupportsAllDrives(true)
                .setIncludeItemsFromAllDrives(true)

            val result = request.execute()
            val showFolders = result.files.orEmpty().take(limit)

            showFolders.map { folder ->
                async(Dispatchers.IO) {
                    try {
                        val folderId = folder.id.orEmpty()
                        val folderName = folder.name.orEmpty()
                        val cleanTitle = cleanSeriesTitle(folderName)

                        val seriesMeta = OnlineMetadataResolver.resolveSeries(cleanTitle)
                        val officialTitle = seriesMeta.officialTitle.ifBlank { cleanTitle }
                        val posterUrl = seriesMeta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) } ?: ""
                        val genre = seriesMeta.genres.ifBlank { "TV Series • Drama" }

                        TvSeries(
                            id = folderId,
                            title = officialTitle,
                            genreFormatted = genre,
                            totalSeasonsFormatted = "TV Series",
                            seasons = emptyList(),
                            thumbnailUrl = posterUrl,
                            backdropUrl = posterUrl.ifBlank { null },
                            posterUrl = posterUrl
                        )
                    } catch (e: Exception) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Error in fetchTopLatestSeriesEnriched: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Refreshes a single series by its folder ID, retrieving all subfolders, episodes,
     * and resolving metadata via OnlineMetadataResolver.
     */
    suspend fun refreshSingleSeries(
        seriesFolderId: String,
        seriesTitleHint: String? = null
    ): TvSeries? {
        val driveService = getDriveService() ?: return null
        return refreshSingleSeries(driveService, seriesFolderId, seriesTitleHint)
    }

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
