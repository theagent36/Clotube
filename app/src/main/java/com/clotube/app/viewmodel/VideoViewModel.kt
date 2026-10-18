package com.clotube.app.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clotube.app.auth.GoogleAuthRepository
import com.clotube.app.data.DriveFolderManager
import com.clotube.app.data.DriveFoldersStatus
import com.clotube.app.data.DriveRepository
import com.clotube.app.model.DriveVideoFile
import com.clotube.app.model.TvSeries
import com.clotube.app.model.VideoItem
import com.clotube.app.data.CachedCatalog
import com.clotube.app.data.CatalogCacheManager
import com.clotube.app.data.scanner.DriveCatalogScanner
import com.clotube.app.data.scanner.SmartFolderResolver
import com.clotube.app.model.Movie
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.clotube.app.model.ContinueWatchingItem
import com.clotube.app.model.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.clotube.app.data.DriveSyncWorker
import com.clotube.app.data.DriveWatchHistorySync
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.clotube.app.media.VlcPlayerManager
import com.clotube.app.model.Episode
import org.videolan.libvlc.MediaPlayer
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

// Persist the catalog to app-private cache whenever fetched successfully online
fun saveCatalogToDisk(context: Context, seriesList: List<TvSeries>) {
    try {
        val cacheFile = File(context.filesDir, "cached_series_catalog.json")
        val json = Gson().toJson(seriesList)
        cacheFile.writeText(json)
    } catch (ignored: Exception) {}
}

// Read the cached catalog when offline or in Airplane Mode
fun loadCatalogFromDisk(context: Context): List<TvSeries> {
    return try {
        val cacheFile = File(context.filesDir, "cached_series_catalog.json")
        if (cacheFile.exists()) {
            val json = cacheFile.readText()
            val type = object : TypeToken<List<TvSeries>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } else {
            emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }
}

// Save or update an individual show (with its populated seasons and episodes)
fun saveShowToDisk(context: Context, updatedShow: TvSeries) {
    try {
        val existing = loadCatalogFromDisk(context).toMutableList()
        val index = existing.indexOfFirst { it.id == updatedShow.id }
        if (index >= 0) {
            existing[index] = updatedShow
        } else {
            existing.add(updatedShow)
        }
        saveCatalogToDisk(context, existing)
    } catch (ignored: Exception) {}
}

fun VideoItem.toMovie(): Movie = Movie(
    id = id,
    title = displayTitle,
    fileName = displayTitle,
    fileSize = fileSizeFormatted,
    mimeType = mimeType,
    thumbnailUrl = thumbnailUrl,
    parentFolder = "",
    durationFormatted = durationFormatted,
    resolutionBadge = resolutionBadge,
    genre = genreFormatted,
    streamUri = streamUri,
    sizeBytes = sizeBytes
)

data class ContinueWatchingData(
    val mediaId: String,
    val title: String,
    val subtitle: String,
    val episodeTitle: String = "",
    val thumbnailUrl: String,
    val isMovie: Boolean,
    val progressPercentage: Int,
    val currentPositionMs: Long,
    val durationMs: Long,
    val rawEpisode: Episode? = null,
    val rawMovie: Movie? = null
) {
    val episode: Episode? get() = rawEpisode
    val seriesId: String get() = if (!isMovie) (rawEpisode?.id?.let { mediaId } ?: "") else ""
    val seriesTitle: String get() = if (!isMovie) title.substringBefore(" • ") else ""
    val seriesPosterUrl: String get() = thumbnailUrl
}

data class VideoUiState(
    val videos: List<VideoItem> = emptyList(),
    val moviesList: List<VideoItem> = emptyList(),
    val filteredVideos: List<VideoItem> = emptyList(),
    val tvSeriesList: List<TvSeries> = emptyList(),
    val heroItem: VideoItem? = null,
    val heroSeries: TvSeries? = null,
    val selectedFilter: String = "All",
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val selectedVideoForInspection: VideoItem? = null,
    val currentTab: NavigationTab = NavigationTab.LIBRARY,
    val isHardwareAccelerationEnabled: Boolean = true,
    val userEmail: String? = null,
    val userName: String? = null,
    val userAvatarUrl: String? = null,
    val isSignedIn: Boolean = false,
    val isLoading: Boolean = false,
    val isInitialLoading: Boolean = true,
    val errorMessage: String? = null,
    val accessToken: String? = null,
    val showFolderOnboardingDialog: Boolean = false,
    val isCreatingFolders: Boolean = false,
    val folderStatus: DriveFoldersStatus? = null,
    val lastWatchedMedia: com.clotube.app.data.WatchProgress? = null,
    val watchProgressMap: Map<String, com.clotube.app.data.WatchProgress> = emptyMap()
)

class VideoViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VideoViewModel"
    }

    private val authRepository = GoogleAuthRepository(application)
    private val folderManager = DriveFolderManager(application, authRepository)
    private val driveRepository = DriveRepository(authRepository, folderManager)
    val playbackHistoryManager = com.clotube.app.data.PlaybackHistoryManager(application)
    val catalogCacheManager = CatalogCacheManager(application)

    val tokenRepository = com.clotube.app.data.TokenRepository(application)

    init {
        DriveCatalogScanner.driveServiceProvider = { getDriveService() }
    }

    /**
     * Globally tracked active player instance across docked and fullscreen playback.
     * Enforces immediate pause when the app is backgrounded or minimized.
     */
    val vlcPlayerManager = VlcPlayerManager(
        context = application,
        tokenProvider = { getValidAccessToken() }
    )
    val mediaPlayer: MediaPlayer get() = vlcPlayerManager.mediaPlayer

    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val token = tokenRepository.getValidAccessToken(forceRefresh)
        if (token.isNotBlank()) {
            _uiState.update { it.copy(accessToken = token) }
            return@withContext token
        }
        val repoToken = authRepository.getAccessToken().getOrNull()
            ?: authRepository.refreshAccessToken().getOrNull()
            ?: getAccessToken()
        if (!repoToken.isNullOrBlank()) return@withContext repoToken
        ""
    }

    fun getMovieById(fileId: String): VideoItem? {
        return _moviesState.value.find { it.id == fileId }
            ?: _uiState.value.moviesList.find { it.id == fileId }
            ?: _uiState.value.videos.find { it.id == fileId }
    }

    fun prepareAndPlayMovie(fileId: String, startPositionMs: Long = 0L) {
        val movie = getMovieById(fileId)
        val movieTitle = movie?.title?.ifBlank { null } ?: movie?.displayTitle?.ifBlank { null } ?: "Untitled Movie"
        viewModelScope.launch(Dispatchers.IO) {
            val downloadRepo = com.clotube.app.data.DownloadRepository(getApplication())
            val localFile = downloadRepo.getLocalFileIfDownloaded(fileId)
            val initialSeek = if (startPositionMs > 0L) {
                startPositionMs
            } else {
                val savedProgress = playbackHistoryManager.getProgressSync(fileId)
                if (savedProgress != null && savedProgress.positionMs > 2000L) savedProgress.positionMs else 0L
            }
            withContext(Dispatchers.Main) {
                vlcPlayerManager.play(
                    fileId = fileId,
                    localFile = localFile,
                    startPositionMs = initialSeek
                )
            }
        }
    }

    fun prepareAndPlayEpisode(episode: Episode, startPositionMs: Long = 0L) {
        viewModelScope.launch(Dispatchers.IO) {
            val downloadRepo = com.clotube.app.data.DownloadRepository(getApplication())
            val localFile = downloadRepo.getLocalFileIfDownloaded(episode.id)
            val initialSeek = if (startPositionMs > 0L) {
                startPositionMs
            } else {
                val savedProgress = playbackHistoryManager.getProgressSync(episode.id)
                if (savedProgress != null && savedProgress.positionMs > 2000L) savedProgress.positionMs else 0L
            }
            withContext(Dispatchers.Main) {
                vlcPlayerManager.play(
                    fileId = episode.id,
                    localFile = localFile,
                    startPositionMs = initialSeek
                )
            }
        }
    }

    fun clearActiveEpisode() {
        try {
            mediaPlayer.setEventListener(null)
            mediaPlayer.detachViews()
            mediaPlayer.stop()
        } catch (_: Exception) {}
    }

    fun getStreamUrlForEpisode(episodeId: String): String {
        return "http://127.0.0.1:${com.clotube.app.data.LocalStreamProxy.PORT}/stream?fileId=$episodeId"
    }

    suspend fun getFreshGoogleDriveAccessToken(): String {
        return getValidAccessToken()
    }

    private val _episodeList = MutableStateFlow<List<Episode>>(emptyList())
    val episodeList: StateFlow<List<Episode>> = _episodeList.asStateFlow()

    private val _currentEpisodeIndex = MutableStateFlow<Int>(-1)
    val currentEpisodeIndex: StateFlow<Int> = _currentEpisodeIndex.asStateFlow()

    private val _isSeriesMode = MutableStateFlow<Boolean>(false)
    val isSeriesMode: StateFlow<Boolean> = _isSeriesMode.asStateFlow()

    val hasPrevious: Boolean
        get() = isSeriesMode.value && currentEpisodeIndex.value > 0

    val hasNext: Boolean
        get() = isSeriesMode.value && currentEpisodeIndex.value >= 0 && currentEpisodeIndex.value < (_episodeList.value.size - 1)

    fun registerActivePlayer(activePlayer: MediaPlayer?) {}

    fun unregisterActivePlayer(activePlayer: MediaPlayer?) {}

    fun setSeriesQueue(episodes: List<Episode>, initialIndex: Int = 0) {
        _episodeList.value = episodes
        _isSeriesMode.value = episodes.isNotEmpty()
        _currentEpisodeIndex.value = if (episodes.isNotEmpty()) initialIndex.coerceIn(0, episodes.lastIndex) else -1
    }

    fun clearSeriesQueue() {
        _episodeList.value = emptyList()
        _isSeriesMode.value = false
        _currentEpisodeIndex.value = -1
    }

    fun playNextEpisode() {
        if (!hasNext) return
        val currentIndex = _currentEpisodeIndex.value
        val currentEp = _episodeList.value.getOrNull(currentIndex)
        if (currentEp != null) {
            val curPos = mediaPlayer.time
            val dur = mediaPlayer.length
            if (dur > 0L && curPos > 0L) {
                viewModelScope.launch {
                    playbackHistoryManager.saveProgress(
                        fileId = currentEp.id,
                        title = currentEp.title,
                        seriesTitle = currentEp.seriesTitle.ifBlank { null },
                        positionMs = curPos,
                        durationMs = dur,
                        isSeries = true,
                        seasonNumber = currentEp.seasonNumber,
                        episodeNumber = currentEp.episodeNumber,
                        thumbnailUrl = currentEp.thumbnailUrl.ifBlank { null }
                    )
                }
            }
        }

        val nextIndex = currentIndex + 1
        if (nextIndex !in 0 until _episodeList.value.size) return
        _currentEpisodeIndex.value = nextIndex
        val nextEpisode = _episodeList.value[nextIndex]

        viewModelScope.launch(Dispatchers.IO) {
            val downloadRepo = com.clotube.app.data.DownloadRepository(getApplication())
            val localFile = downloadRepo.getLocalFileIfDownloaded(nextEpisode.id)
            withContext(Dispatchers.Main) {
                vlcPlayerManager.play(
                    fileId = nextEpisode.id,
                    localFile = localFile,
                    startPositionMs = 0L
                )
            }
        }
    }

    fun playPreviousEpisode() {
        if (!hasPrevious) return
        if (mediaPlayer.time > 5000L) {
            vlcPlayerManager.seekTo(0L)
            return
        }

        val currentIndex = _currentEpisodeIndex.value
        val currentEp = _episodeList.value.getOrNull(currentIndex)
        if (currentEp != null) {
            val curPos = mediaPlayer.time
            val dur = mediaPlayer.length
            if (dur > 0L && curPos > 0L) {
                viewModelScope.launch {
                    playbackHistoryManager.saveProgress(
                        fileId = currentEp.id,
                        title = currentEp.title,
                        seriesTitle = currentEp.seriesTitle.ifBlank { null },
                        positionMs = curPos,
                        durationMs = dur,
                        isSeries = true,
                        seasonNumber = currentEp.seasonNumber,
                        episodeNumber = currentEp.episodeNumber,
                        thumbnailUrl = currentEp.thumbnailUrl.ifBlank { null }
                    )
                }
            }
        }

        val prevIndex = currentIndex - 1
        if (prevIndex < 0) return
        _currentEpisodeIndex.value = prevIndex
        val prevEpisode = _episodeList.value[prevIndex]

        viewModelScope.launch(Dispatchers.IO) {
            val downloadRepo = com.clotube.app.data.DownloadRepository(getApplication())
            val localFile = downloadRepo.getLocalFileIfDownloaded(prevEpisode.id)
            withContext(Dispatchers.Main) {
                vlcPlayerManager.play(
                    fileId = prevEpisode.id,
                    localFile = localFile,
                    startPositionMs = 0L
                )
            }
        }
    }


    val playerOrNull: MediaPlayer? get() = mediaPlayer

    fun pauseActivePlayer() {
        vlcPlayerManager.onAppPause()
        try {
            mediaPlayer.pause()
        } catch (_: Exception) {}
    }

    fun getSelectedGoogleAccountName(): String? {
        return authRepository.currentAccount.value?.account?.name
            ?: authRepository.currentAccount.value?.email
            ?: GoogleSignIn.getLastSignedInAccount(getApplication())?.account?.name
            ?: GoogleSignIn.getLastSignedInAccount(getApplication())?.email
    }

    /**
     * Guarantees that playback position (timestamp, duration, episode ID) is committed
     * to AndroidX DataStore instantly when onStop() / onPause() fires, using NonCancellable + Dispatchers.IO.
     * This survives app minimization and swiping away from Android Recents.
     */
    fun savePlaybackPositionImmediate(
        fileId: String,
        positionMs: Long,
        durationMs: Long,
        title: String? = null,
        seriesTitle: String? = null,
        seasonNumber: Int = 0,
        episodeNumber: Int = 0,
        isSeries: Boolean = false,
        thumbnailUrl: String? = null
    ) {
        if (fileId.isBlank() || durationMs <= 0L) return
        val resolvedMovie = if (title == null) getMovieById(fileId) else null
        val resolvedEpisode = if (title == null) _episodeList.value.find { it.id == fileId } else null

        val finalTitle = title ?: resolvedEpisode?.title ?: resolvedMovie?.title ?: resolvedMovie?.displayTitle ?: "Untitled Video"
        val finalSeriesTitle = seriesTitle ?: resolvedEpisode?.seriesTitle
        val finalSeasonNum = if (seasonNumber > 0) seasonNumber else resolvedEpisode?.seasonNumber ?: 0
        val finalEpisodeNum = if (episodeNumber > 0) episodeNumber else resolvedEpisode?.episodeNumber ?: 0
        val finalIsSeries = isSeries || resolvedEpisode != null
        val finalThumbnailUrl = thumbnailUrl ?: resolvedEpisode?.thumbnailUrl ?: resolvedMovie?.thumbnailUrl

        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            try {
                playbackHistoryManager.recordProgress(
                    fileId = fileId,
                    title = finalTitle,
                    seriesTitle = finalSeriesTitle,
                    seasonNumber = finalSeasonNum,
                    episodeNumber = finalEpisodeNum,
                    isSeries = finalIsSeries,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    thumbnailUrl = finalThumbnailUrl
                )
                playbackHistoryManager.saveProgress(
                    fileId = fileId,
                    title = finalTitle,
                    seriesTitle = finalSeriesTitle,
                    seasonNumber = finalSeasonNum,
                    episodeNumber = finalEpisodeNum,
                    isSeries = finalIsSeries,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    thumbnailUrl = finalThumbnailUrl,
                    immediateCloudSync = true
                )
                Log.d("VideoViewModel", "Immediate progress committed for $fileId at $positionMs / $durationMs")
            } catch (e: Exception) {
                Log.e("VideoViewModel", "Failed to commit immediate progress: ${e.message}", e)
            }
        }
    }

    /**
     * Enqueues an AndroidX WorkManager task to upload clotube_history.json to Google Drive silently,
     * guaranteeing cloud backup even across process death (SIGKILL / swiped from Recents).
     */
    fun scheduleBackgroundDriveSync(context: Context) {
        DriveSyncWorker.enqueue(context)
    }

    /**
     * Deletes watch history for a single episode or movie and immediately syncs changes to Drive.
     */
    fun deleteHistoryItem(contentId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            playbackHistoryManager.deleteItemHistory(contentId)
        }
    }

    /**
     * Completely deletes all watch history locally and removes the JSON file from Google Drive.
     */
    fun clearAllHistory(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            playbackHistoryManager.clearEntireWatchHistory()
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    private val _isRefreshing = MutableStateFlow<Boolean>(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _moviesList = MutableStateFlow<List<Movie>>(emptyList())
    val moviesList: StateFlow<List<Movie>> = _moviesList.asStateFlow()

    private val _moviesState = MutableStateFlow<List<VideoItem>>(emptyList())
    val moviesState: StateFlow<List<VideoItem>> = _moviesState.asStateFlow()

    private val _seriesState = MutableStateFlow<List<TvSeries>>(emptyList())
    val seriesState: StateFlow<List<TvSeries>> = _seriesState.asStateFlow()
    val _seriesList: MutableStateFlow<List<TvSeries>> get() = _seriesState
    val seriesList: StateFlow<List<Series>> get() = _seriesState

    suspend fun getDriveService(): Drive? = withContext(Dispatchers.IO) {
        val token = authRepository.getAccessToken().getOrNull() ?: return@withContext null
        Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance()
        ) { request ->
            request.headers.authorization = "Bearer $token"
        }.setApplicationName("Clotube/1.0").build()
    }

    suspend fun resolveMoviesFolderId(service: Drive? = null): String? = withContext(Dispatchers.IO) {
        val cached = folderManager.getMoviesFolderId()
        if (!cached.isNullOrBlank() && cached != "root") return@withContext cached
        val status = folderManager.checkFoldersStatus().getOrNull()
        status?.moviesFolderId ?: folderManager.getMoviesFolderId()
    }

    suspend fun resolveSeriesFolderId(service: Drive? = null): String? = withContext(Dispatchers.IO) {
        val cached = folderManager.getSeriesFolderId()
        if (!cached.isNullOrBlank() && cached != "root") return@withContext cached
        val status = folderManager.checkFoldersStatus().getOrNull()
        status?.seriesFolderId ?: folderManager.getSeriesFolderId()
    }

    fun getSeriesById(seriesId: String): TvSeries? {
        return _seriesList.value.find { it.id == seriesId }
    }

    // On every successful online sync:
    fun onOnlineCatalogFetched(seriesList: List<TvSeries>) {
        _seriesState.value = seriesList
        viewModelScope.launch(Dispatchers.IO) {
            saveCatalogToDisk(getApplication(), seriesList)
        }
    }

    fun updateSeriesInCatalog(updatedSeries: TvSeries) {
        val currentList = _seriesState.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == updatedSeries.id }
        if (index >= 0) {
            currentList[index] = updatedSeries
        } else {
            currentList.add(updatedSeries)
        }
        _seriesState.value = currentList
        _uiState.update { current ->
            current.copy(tvSeriesList = currentList)
        }
        viewModelScope.launch(Dispatchers.IO) {
            saveCatalogToDisk(getApplication(), currentList)
        }
    }

    fun initOfflineCatalog() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_seriesList.value.isEmpty()) {
                val cached = loadCatalogFromDisk(getApplication())
                if (cached.isNotEmpty()) {
                    _seriesList.value = cached
                    _uiState.update { current ->
                        current.copy(tvSeriesList = cached)
                    }
                }
            }
        }
    }

    private val _isInitialLoading = MutableStateFlow<Boolean>(true)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()

    val userProfilePhotoUrl: StateFlow<String?> = _uiState
        .map { it.userAvatarUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val continueWatchingState: StateFlow<ContinueWatchingData?> = combine(
        playbackHistoryManager.lastWatchedFlow,
        seriesList,
        moviesList
    ) { lastWatched, allSeries, allMovies ->
        if (lastWatched == null) return@combine null

        // 1. Check if the last watched item is a Series Episode
        val parentSeries = allSeries.firstOrNull { it.id == lastWatched.seriesId }
            ?: loadCatalogFromDisk(getApplication()).firstOrNull { it.id == lastWatched.seriesId }

        var matchedEpisode = parentSeries?.seasons
            ?.flatMap { it.episodes }
            ?.firstOrNull { it.id == lastWatched.episodeId }

        if (matchedEpisode == null) {
            val diskCatalog = loadCatalogFromDisk(getApplication())
            val diskShow = diskCatalog.firstOrNull { it.id == lastWatched.seriesId }
            matchedEpisode = diskShow?.seasons
                ?.flatMap { it.episodes }
                ?.firstOrNull { it.id == lastWatched.episodeId }
        }

        if (matchedEpisode == null && parentSeries != null && lastWatched.isSeries) {
            val sNum = lastWatched.seasonNumber.coerceAtLeast(1)
            val eNum = lastWatched.episodeNumber.coerceAtLeast(1)
            val epMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveEpisode(
                seriesName = parentSeries.title,
                tvMazeSeriesId = 0L,
                seasonNumber = sNum,
                episodeNumber = eNum,
                rawFileName = lastWatched.title
            )
            matchedEpisode = Episode(
                id = lastWatched.episodeId,
                seriesTitle = parentSeries.title,
                displayTitle = epMeta.officialTitle.ifBlank { lastWatched.title },
                seasonNumber = sNum,
                episodeNumber = eNum,
                thumbnailUrl = epMeta.thumbnailUrl.ifBlank { parentSeries.posterUrl.orEmpty() },
                durationFormatted = if (lastWatched.durationMs > 0) formatTimestamp(lastWatched.durationMs) else "${epMeta.durationMinutes}:00"
            )
        }

        if (parentSeries != null && matchedEpisode != null) {
            val totalDuration = if (matchedEpisode.durationMs > 0) matchedEpisode.durationMs else lastWatched.durationMs
            val progressPct = if (totalDuration > 0) {
                ((lastWatched.positionMs.toFloat() / totalDuration.toFloat()) * 100).toInt().coerceIn(1, 99)
            } else {
                0
            }

            var artwork = matchedEpisode.thumbnailUrl.ifBlank { parentSeries.posterUrl.orEmpty() }
            if (artwork.isBlank() || artwork.contains("googleusercontent.com") || artwork.contains("drive.google.com")) {
                val sNum = matchedEpisode.seasonNumber.coerceAtLeast(1)
                val eNum = matchedEpisode.episodeNumber.coerceAtLeast(1)
                val epMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveEpisode(
                    seriesName = parentSeries.title,
                    tvMazeSeriesId = 0L,
                    seasonNumber = sNum,
                    episodeNumber = eNum,
                    rawFileName = matchedEpisode.displayTitle
                )
                if (epMeta.thumbnailUrl.isNotBlank()) {
                    artwork = epMeta.thumbnailUrl
                }
            }
            if (artwork.isBlank()) {
                artwork = parentSeries.posterUrl.orEmpty()
            }

            val sTag = "S" + matchedEpisode.seasonNumber.toString().padStart(2, '0')
            val eTag = "E" + matchedEpisode.episodeNumber.toString().padStart(2, '0')

            return@combine ContinueWatchingData(
                mediaId = matchedEpisode.id,
                title = "${parentSeries.title} • $sTag:$eTag",
                subtitle = matchedEpisode.displayTitle,
                episodeTitle = matchedEpisode.displayTitle,
                thumbnailUrl = artwork,
                isMovie = false,
                progressPercentage = progressPct,
                currentPositionMs = lastWatched.positionMs,
                durationMs = totalDuration,
                rawEpisode = matchedEpisode.copy(thumbnailUrl = artwork),
                rawMovie = null
            )
        }

        // 2. Check if the last watched item is a Movie
        val matchedMovie = allMovies.firstOrNull {
            it.id == lastWatched.episodeId || it.id == lastWatched.seriesId
        } ?: _moviesState.value.firstOrNull {
            it.id == lastWatched.episodeId || it.id == lastWatched.seriesId
        }?.toMovie()

        if (matchedMovie != null) {
            val totalDuration = if (matchedMovie.durationMs > 0) matchedMovie.durationMs else lastWatched.durationMs
            val progressPct = if (totalDuration > 0) {
                ((lastWatched.positionMs.toFloat() / totalDuration.toFloat()) * 100).toInt().coerceIn(1, 99)
            } else {
                0
            }

            return@combine ContinueWatchingData(
                mediaId = matchedMovie.id,
                title = matchedMovie.title,
                subtitle = "Movie • Cinema",
                episodeTitle = "",
                thumbnailUrl = matchedMovie.thumbnailUrl.orEmpty(),
                isMovie = true,
                progressPercentage = progressPct,
                currentPositionMs = lastWatched.positionMs,
                durationMs = totalDuration,
                rawEpisode = null,
                rawMovie = matchedMovie
            )
        }

        null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val lastWatchedEpisodeState: StateFlow<Episode?> = continueWatchingState
        .map { it?.rawEpisode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun clearContinueWatching() {
        val targetId = continueWatchingState.value?.episode?.id
            ?: _uiState.value.lastWatchedMedia?.fileId
        if (targetId != null) {
            deleteHistoryItem(targetId)
        }
    }

    val currentUserAccount: StateFlow<GoogleSignInAccount?> = authRepository.currentAccount

    val continueWatchingItem: StateFlow<ContinueWatchingItem?> = playbackHistoryManager
        .getContinueWatchingMedia()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    suspend fun getLastWatchedEpisode(series: com.clotube.app.model.Series): com.clotube.app.model.Episode? {
        return playbackHistoryManager.getLastWatchedEpisode(series)
    }

    val selectedSeasonNumber = androidx.compose.runtime.mutableStateOf<Int?>(null)

    val filterChips = listOf("All", "Movies", "TV Series")
    private var catalogLoadingJob: Job? = null

    init {
        playbackHistoryManager.setTokenProvider { getValidAccessToken() }
        DriveWatchHistorySync.setOnAuthFailureListener {
            tokenRepository.invalidateAndForceConsent()
        }
        viewModelScope.launch(Dispatchers.IO) {
            playbackHistoryManager.syncFromCloud()
        }
        val lastAccount = GoogleSignIn.getLastSignedInAccount(getApplication())
        if (lastAccount != null && com.clotube.app.auth.GoogleAuthManager.hasAllDrivePermissions(getApplication(), lastAccount)) {
            _uiState.update {
                it.copy(
                    isSignedIn = true,
                    userEmail = lastAccount.email,
                    userName = lastAccount.displayName,
                    userAvatarUrl = lastAccount.photoUrl?.toString()
                )
            }
        }
        com.clotube.app.data.metadata.OnlineMetadataResolver.clearCache()
        observePlaybackHistory()
        initOfflineCatalog()
        initStaleWhileRevalidateFlow()
        if (com.clotube.app.BuildConfig.DEBUG) {
            runScannerDiagnosticTest()
        }
    }

    private fun initStaleWhileRevalidateFlow() {
        viewModelScope.launch(Dispatchers.IO) {
            // Step 1: Immediately emit cached catalog from disk (0ms delay)
            val cached = catalogCacheManager.getCachedCatalog()
            val diskSeries = loadCatalogFromDisk(getApplication())
            val combinedSeries = if (diskSeries.isNotEmpty()) {
                val diskMap = diskSeries.associateBy { it.id }
                val mergedFromCache = cached.series.map { s ->
                    val d = diskMap[s.id]
                    if (d != null && d.seasons.isNotEmpty() && s.seasons.isEmpty()) d else s
                }
                val freshIds = cached.series.map { it.id }.toSet()
                mergedFromCache + diskSeries.filter { it.id !in freshIds }
            } else {
                cached.series
            }

            val hasCachedData = cached.movies.isNotEmpty() || combinedSeries.isNotEmpty()
            if (hasCachedData) {
                val progressMap = _uiState.value.watchProgressMap
                val mappedMovies = cached.movies.map { movie ->
                    val cleanTitle = when {
                        movie.displayTitle.equals("My People, My Homeland", ignoreCase = true) -> "The Vast of Night"
                        movie.displayTitle.contains("uindex", ignoreCase = true) || movie.displayTitle.startsWith("www", ignoreCase = true) -> {
                            val (q, _) = com.clotube.app.data.metadata.OnlineMetadataResolver.extractMovieQueryAndYear(movie.displayTitle)
                            if (q.isNotBlank()) q else movie.displayTitle
                        }
                        else -> movie.displayTitle
                    }
                    val prog = progressMap[movie.id]
                    val updated = movie.copy(displayTitle = cleanTitle)
                    if (prog != null) updated.copy(watchProgressFraction = prog.progressFraction) else updated
                }
                val mappedSeries = combinedSeries.map { series ->
                    val seriesEpProgress = progressMap.values
                        .filter { it.seriesId == series.id || series.seasons.any { s -> s.episodes.any { ep -> ep.id == it.fileId } } }
                        .maxByOrNull { it.lastWatchedTimestamp }
                    val seriesFraction = seriesEpProgress?.progressFraction ?: 0f

                    val updatedSeasons = series.seasons.map { season ->
                        season.copy(
                            episodes = season.episodes.map { ep ->
                                val epProg = progressMap[ep.id]
                                if (epProg != null) ep.copy(watchProgressFraction = epProg.progressFraction) else ep
                            }
                        )
                    }
                    series.copy(watchProgressFraction = seriesFraction, seasons = updatedSeasons)
                }

                _moviesState.value = mappedMovies
                _moviesList.value = mappedMovies.map { it.toMovie() }
                _seriesState.value = mappedSeries
                _isInitialLoading.value = false

                val hero = mappedMovies.firstOrNull()
                val heroShow = mappedSeries.firstOrNull()

                _uiState.update { current ->
                    current.copy(
                        videos = mappedMovies,
                        moviesList = mappedMovies,
                        filteredVideos = applyFilterAndSearch(mappedMovies, current.selectedFilter, current.searchQuery),
                        tvSeriesList = mappedSeries,
                        heroItem = hero,
                        heroSeries = heroShow,
                        isInitialLoading = false
                    )
                }
            } else {
                _isInitialLoading.value = true // Triggers shimmer only if cache is completely empty
                _uiState.update { it.copy(isInitialLoading = true) }
            }

            // Step 2: Check authentication & perform single coordinated background refresh
            val lastAccount = GoogleSignIn.getLastSignedInAccount(getApplication())
            if (lastAccount == null || !com.clotube.app.auth.GoogleAuthManager.hasAllDrivePermissions(getApplication(), lastAccount)) {
                if (!hasCachedData) {
                    _moviesState.value = emptyList()
                    _seriesState.value = emptyList()
                    _isInitialLoading.value = false
                    _uiState.update {
                        it.copy(
                            isSignedIn = false,
                            isInitialLoading = false,
                            isLoading = false
                        )
                    }
                }
                return@launch
            }

            // Single coordinated startup revalidation
            checkFolderOnboarding()
            viewModelScope.launch(Dispatchers.IO) {
                playbackHistoryManager.syncFromCloud()
            }
        }
    }

    private fun observePlaybackHistory() {
        viewModelScope.launch {
            playbackHistoryManager.getAllProgress().collect { progressMap ->
                val lastWatched = progressMap.values
                    .maxByOrNull { it.lastWatchedTimestamp }

                _uiState.update { current ->
                    val updatedMovies = current.moviesList.map { movie ->
                        val prog = progressMap[movie.id]
                        movie.copy(watchProgressFraction = prog?.progressFraction ?: 0f)
                    }
                    val updatedVideos = current.videos.map { vid ->
                        val prog = progressMap[vid.id]
                        vid.copy(watchProgressFraction = prog?.progressFraction ?: 0f)
                    }
                    val updatedSeries = current.tvSeriesList.map { series ->
                        val seriesEpProgress = progressMap.values
                            .filter { it.seriesId == series.id || series.seasons.any { s -> s.episodes.any { ep -> ep.id == it.fileId } } }
                            .maxByOrNull { it.lastWatchedTimestamp }
                        val seriesFraction = seriesEpProgress?.progressFraction ?: 0f

                        val updatedSeasons = series.seasons.map { season ->
                            season.copy(
                                episodes = season.episodes.map { ep ->
                                    val epProg = progressMap[ep.id]
                                    ep.copy(watchProgressFraction = epProg?.progressFraction ?: 0f)
                                }
                            )
                        }
                        series.copy(watchProgressFraction = seriesFraction, seasons = updatedSeasons)
                    }

                    _moviesState.value = updatedMovies
                    _seriesState.value = updatedSeries

                    current.copy(
                        watchProgressMap = progressMap,
                        lastWatchedMedia = lastWatched,
                        moviesList = updatedMovies,
                        videos = updatedVideos,
                        filteredVideos = applyFilterAndSearch(updatedMovies, current.selectedFilter, current.searchQuery),
                        tvSeriesList = updatedSeries
                    )
                }
            }
        }
    }

    private fun checkInitialAuthState() {
        val lastAccount = GoogleSignIn.getLastSignedInAccount(getApplication())
        if (lastAccount != null && com.clotube.app.auth.GoogleAuthManager.hasAllDrivePermissions(getApplication(), lastAccount)) {
            _uiState.update {
                it.copy(
                    isSignedIn = true,
                    userEmail = lastAccount.email,
                    userName = lastAccount.displayName,
                    userAvatarUrl = lastAccount.photoUrl?.toString()
                )
            }
            checkFolderOnboarding()
        } else {
            _moviesState.value = emptyList()
            _seriesState.value = emptyList()
            _isInitialLoading.value = false
            _uiState.update {
                it.copy(
                    isSignedIn = false,
                    videos = emptyList(),
                    moviesList = emptyList(),
                    filteredVideos = emptyList(),
                    tvSeriesList = emptyList(),
                    heroItem = null,
                    heroSeries = null,
                    isInitialLoading = false,
                    isLoading = false
                )
            }
        }
    }

    fun getSignInIntent(): Intent {
        return authRepository.getSignInIntent()
    }

    fun onSignInSuccess(account: GoogleSignInAccount) {
        authRepository.setCurrentAccount(account)
        _uiState.update {
            it.copy(
                isSignedIn = true,
                userEmail = account.email,
                userName = account.displayName,
                userAvatarUrl = account.photoUrl?.toString(),
                errorMessage = null
            )
        }
        checkFolderOnboarding()
        viewModelScope.launch(Dispatchers.IO) {
            playbackHistoryManager.syncFromCloud()
        }
    }

    fun onPermissionsDenied() {
        _uiState.update {
            it.copy(
                isSignedIn = false,
                errorMessage = "Drive permissions are required to use Clotube"
            )
        }
    }

    fun processSignInResult(activity: android.app.Activity, data: Intent?) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            if (account != null) {
                val hasRead = GoogleSignIn.hasPermissions(
                    account,
                    com.clotube.app.auth.GoogleAuthManager.DRIVE_READONLY_SCOPE
                )
                val hasFile = GoogleSignIn.hasPermissions(
                    account,
                    com.clotube.app.auth.GoogleAuthManager.DRIVE_FILE_SCOPE
                )

                if (!hasRead || !hasFile) {
                    GoogleSignIn.requestPermissions(
                        activity,
                        com.clotube.app.auth.GoogleAuthManager.REQUEST_CODE_DRIVE_PERMISSIONS,
                        account,
                        com.clotube.app.auth.GoogleAuthManager.DRIVE_READONLY_SCOPE,
                        com.clotube.app.auth.GoogleAuthManager.DRIVE_FILE_SCOPE
                    )
                } else {
                    onSignInSuccess(account)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processSignInResult failed", e)
            handleSignInResult(data)
        }
    }

    fun handleSignInResult(data: Intent?) {
        val result = authRepository.handleSignInResult(data)
        result.onSuccess { account ->
            val hasRead = GoogleSignIn.hasPermissions(
                account,
                com.clotube.app.auth.GoogleAuthManager.DRIVE_READONLY_SCOPE
            )
            val hasFile = GoogleSignIn.hasPermissions(
                account,
                com.clotube.app.auth.GoogleAuthManager.DRIVE_FILE_SCOPE
            )
            if (!hasRead || !hasFile) {
                Log.w(TAG, "Sign-in succeeded but Drive permissions are missing (hasRead=$hasRead, hasFile=$hasFile)")
                onPermissionsDenied()
                return
            }
            onSignInSuccess(account)
        }.onFailure { error ->
            Log.e(TAG, "Sign in failed in ViewModel", error)
            _uiState.update {
                it.copy(errorMessage = "Sign-in failed: ${error.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    fun signOut() {
        authRepository.signOut {
            viewModelScope.launch {
                catalogCacheManager.clearCache()
            }
            _moviesState.value = emptyList()
            _seriesState.value = emptyList()
            _isInitialLoading.value = false
            _uiState.update {
                it.copy(
                    isSignedIn = false,
                    userEmail = null,
                    userName = null,
                    userAvatarUrl = null,
                    accessToken = null,
                    videos = emptyList(),
                    moviesList = emptyList(),
                    filteredVideos = emptyList(),
                    tvSeriesList = emptyList(),
                    heroItem = null,
                    heroSeries = null,
                    selectedVideoForInspection = null,
                    errorMessage = null,
                    showFolderOnboardingDialog = false,
                    isLoading = false,
                    isInitialLoading = false
                )
            }
        }
    }

    /**
     * Inspects Google Drive root and DataStore for 'Movies' and 'Series' folders.
     * Enforces Fail-Closed Policy: Does NOT perform unconstrained queries.
     */
    fun checkFolderOnboarding() {
        viewModelScope.launch {
            val hasContent = _moviesState.value.isNotEmpty() || _seriesState.value.isNotEmpty()
            if (!hasContent) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }

            // Ensure access token is fetched
            val token = authRepository.getAccessToken().getOrNull()
            if (token != null) {
                _uiState.update { it.copy(accessToken = token) }
            }

            val statusResult = folderManager.checkFoldersStatus()
            statusResult.onSuccess { status ->
                Log.d(TAG, "Drive folders status: needsOnboarding=${status.needsOnboarding}")
                _uiState.update {
                    it.copy(
                        folderStatus = status,
                        showFolderOnboardingDialog = status.needsOnboarding
                    )
                }

                // If no onboarding is needed, fetch scoped feeds directly
                if (!status.needsOnboarding) {
                    val moviesId = status.moviesFolderId ?: folderManager.getMoviesFolderId()
                    val seriesId = status.seriesFolderId ?: folderManager.getSeriesFolderId()
                    if (!moviesId.isNullOrBlank() && !seriesId.isNullOrBlank()) {
                        loadCatalogProgressively(moviesId, seriesId, silentUpdate = hasContent)
                    }
                } else if (!hasContent) {
                    // Fail-Closed Policy: Only clear if there's no pre-existing cached content
                    _moviesState.value = emptyList()
                    _seriesState.value = emptyList()
                    _isInitialLoading.value = false
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isInitialLoading = false,
                            moviesList = emptyList(),
                            filteredVideos = emptyList(),
                            tvSeriesList = emptyList(),
                            heroItem = null,
                            heroSeries = null
                        )
                    }
                }
            }.onFailure { err ->
                Log.w(TAG, "Could not check folders status: ${err.message}. Preserving cached catalog.")
                // NEVER wipe movies/series state on failure! Keep whatever is already displayed!
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Confirms automated folder creation for Movies and Series in Drive.
     */
    fun confirmCreateFolders(createMovies: Boolean = true, createSeries: Boolean = true) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingFolders = true) }
            val createResult = folderManager.createMissingFolders(createMovies, createSeries)
            createResult.onSuccess {
                Log.d(TAG, "Successfully created missing folders on Drive")
                _uiState.update {
                    it.copy(
                        isCreatingFolders = false,
                        showFolderOnboardingDialog = false
                    )
                }
                refreshScopedFeeds()
            }.onFailure { error ->
                Log.e(TAG, "Failed creating folders on Drive", error)
                _uiState.update {
                    it.copy(
                        isCreatingFolders = false,
                        showFolderOnboardingDialog = false,
                        errorMessage = "Folder setup: ${error.localizedMessage}"
                    )
                }
                refreshScopedFeeds()
            }
        }
    }

    fun dismissOnboardingDialog() {
        _uiState.update { it.copy(showFolderOnboardingDialog = false) }
    }

    /**
     * Queries Google Drive strictly scoped to Movies and Series root folders.
     * Enforces Fail-Closed Privacy:
     * - Only files inside 'Movies' folder appear under Movies.
     * - Only show folders inside 'Series' folder appear under Series.
     * - Global unconstrained searches across Drive are strictly prohibited.
     */
    /**
     * Parallel Non-Blocking Media Ingestion:
     * 1. Streams movies progressively (first 6 items emitted in ~400ms, then chunks of 12).
     * 2. Fetches top-level show folders in parallel in ~200ms (shallow, no season/episode recursion).
     * 3. Dismisses shimmer skeletons as soon as the first chunk arrives without resetting scroll position.
     * 4. Persists the final accumulated catalog to local disk cache.
     */
    fun loadCatalogProgressively(
        moviesFolderId: String,
        seriesFolderId: String,
        silentUpdate: Boolean = false
    ) {
        catalogLoadingJob?.cancel()
        catalogLoadingJob = viewModelScope.launch {
            val hasExistingMovies = _moviesState.value.isNotEmpty()
            val hasExistingSeries = _seriesState.value.isNotEmpty()

            if (!silentUpdate && !hasExistingMovies && !hasExistingSeries) {
                _isInitialLoading.value = true
                _uiState.update { it.copy(isInitialLoading = true, isLoading = true, errorMessage = null) }
            }

            val finalFreshMovies = mutableListOf<VideoItem>()
            val finalFreshSeries = mutableListOf<TvSeries>()

            // Step 1: Start progressive movie stream with smart cache merging
            val movieJob = launch {
                driveRepository.streamMoviesChunked(moviesFolderId).collect { freshChunk ->
                    finalFreshMovies.clear()
                    finalFreshMovies.addAll(freshChunk)

                    val progressMap = _uiState.value.watchProgressMap

                    // Merge fresh chunk with existing cached items so remaining cached movies never disappear
                    val mergedList = mergeMoviesWithExisting(freshChunk, _moviesState.value)

                    val mappedMovies = mergedList.map { movie ->
                        val prog = progressMap[movie.id]
                        if (prog != null) movie.copy(watchProgressFraction = prog.progressFraction) else movie
                    }

                    _moviesState.value = mappedMovies

                    // Dismiss skeleton as soon as the first chunk of items arrives
                    if (mappedMovies.isNotEmpty()) {
                        _isInitialLoading.value = false
                    }

                    _uiState.update { current ->
                        val hero = current.heroItem ?: mappedMovies.firstOrNull()
                        current.copy(
                            videos = mappedMovies,
                            moviesList = mappedMovies,
                            filteredVideos = applyFilterAndSearch(mappedMovies, current.selectedFilter, current.searchQuery),
                            heroItem = hero,
                            isInitialLoading = if (mappedMovies.isNotEmpty() || current.tvSeriesList.isNotEmpty()) false else current.isInitialLoading,
                            isLoading = false
                        )
                    }
                }
            }

            // Step 2: Stream Series Overview progressively in parallel with smart cache merging
            val seriesJob = launch {
                driveRepository.streamSeriesOverview(seriesFolderId).collect { freshSeriesChunk ->
                    finalFreshSeries.clear()
                    finalFreshSeries.addAll(freshSeriesChunk)

                    val progressMap = _uiState.value.watchProgressMap

                    val mergedSeriesList = mergeSeriesWithExisting(freshSeriesChunk, _seriesState.value)

                    val mappedSeries = mergedSeriesList.map { series ->
                        val seriesEpProgress = progressMap.values
                            .filter { it.seriesId == series.id || series.seasons.any { s -> s.episodes.any { ep -> ep.id == it.fileId } } }
                            .maxByOrNull { it.lastWatchedTimestamp }
                        val seriesFraction = seriesEpProgress?.progressFraction ?: 0f
                        series.copy(watchProgressFraction = seriesFraction)
                    }

                    _seriesState.value = mappedSeries

                    // Dismiss skeleton as soon as the first series arrives
                    if (mappedSeries.isNotEmpty()) {
                        _isInitialLoading.value = false
                    }

                    _uiState.update { current ->
                        val heroShow = current.heroSeries ?: mappedSeries.firstOrNull()
                        current.copy(
                            tvSeriesList = mappedSeries,
                            heroSeries = heroShow,
                            isInitialLoading = if (mappedSeries.isNotEmpty() || current.moviesList.isNotEmpty()) false else current.isInitialLoading
                        )
                    }
                }
            }

            joinAll(movieJob, seriesJob)

            // Once the entire revalidation stream finishes, set the final verified catalogs
            if (finalFreshMovies.isNotEmpty()) {
                val progressMap = _uiState.value.watchProgressMap
                val finalMapped = finalFreshMovies.map { movie ->
                    val prog = progressMap[movie.id]
                    if (prog != null) movie.copy(watchProgressFraction = prog.progressFraction) else movie
                }
                _moviesState.value = finalMapped
                _uiState.update { current ->
                    current.copy(
                        videos = finalMapped,
                        moviesList = finalMapped,
                        filteredVideos = applyFilterAndSearch(finalMapped, current.selectedFilter, current.searchQuery),
                        heroItem = current.heroItem ?: finalMapped.firstOrNull()
                    )
                }
            }

            if (finalFreshSeries.isNotEmpty()) {
                val progressMap = _uiState.value.watchProgressMap
                val mergedSeries = mergeSeriesWithExisting(finalFreshSeries, _seriesState.value)
                val finalMappedSeries = mergedSeries.map { series ->
                    val seriesEpProgress = progressMap.values
                        .filter { it.seriesId == series.id || series.seasons.any { s -> s.episodes.any { ep -> ep.id == it.fileId } } }
                        .maxByOrNull { it.lastWatchedTimestamp }
                    val seriesFraction = seriesEpProgress?.progressFraction ?: 0f
                    series.copy(watchProgressFraction = seriesFraction)
                }
                onOnlineCatalogFetched(finalMappedSeries)
                _uiState.update { current ->
                    current.copy(
                        tvSeriesList = finalMappedSeries,
                        heroSeries = current.heroSeries ?: finalMappedSeries.firstOrNull()
                    )
                }
            }

            _isInitialLoading.value = false
            _uiState.update { it.copy(isLoading = false, isInitialLoading = false) }

            // Save accumulated results to local catalog cache for instant subsequent boots
            onOnlineCatalogFetched(_seriesState.value)
            catalogCacheManager.saveCatalog(_moviesState.value, _seriesState.value)
        }
    }

    private fun mergeMoviesWithExisting(freshChunk: List<VideoItem>, existingList: List<VideoItem>): List<VideoItem> {
        if (existingList.isEmpty()) return freshChunk
        val freshIds = freshChunk.map { it.id }.toSet()
        val remainingCached = existingList.filter { it.id !in freshIds }
        return freshChunk + remainingCached
    }

    private fun mergeSeriesWithExisting(freshChunk: List<TvSeries>, existingList: List<TvSeries>): List<TvSeries> {
        if (existingList.isEmpty()) return freshChunk
        val existingMap = existingList.associateBy { it.id }
        val mergedFresh = freshChunk.map { fresh ->
            val existing = existingMap[fresh.id]
            if (existing != null && existing.seasons.isNotEmpty() && fresh.seasons.isEmpty()) {
                fresh.copy(seasons = existing.seasons, totalSeasonsFormatted = existing.totalSeasonsFormatted)
            } else {
                fresh
            }
        }
        val freshIds = freshChunk.map { it.id }.toSet()
        val remainingCached = existingList.filter { it.id !in freshIds }
        return mergedFresh + remainingCached
    }

    /**
     * Queries Google Drive strictly scoped to Movies and Series root folders.
     * Enforces Fail-Closed Privacy:
     * - Only files inside 'Movies' folder appear under Movies.
     * - Only show folders inside 'Series' folder appear under Series.
     * - Global unconstrained searches across Drive are strictly prohibited.
     */
    fun refreshScopedFeeds(silentUpdate: Boolean = false) {
        viewModelScope.launch {
            val hasContent = _moviesState.value.isNotEmpty() || _seriesState.value.isNotEmpty()
            if (!silentUpdate && !hasContent) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }

            val token = authRepository.getAccessToken().getOrNull()
            if (token != null) {
                _uiState.update { it.copy(accessToken = token) }
            }

            val moviesId = folderManager.getMoviesFolderId()
            val seriesId = folderManager.getSeriesFolderId()

            if (moviesId.isNullOrBlank() || moviesId == "root" || seriesId.isNullOrBlank() || seriesId == "root") {
                checkFolderOnboarding()
                return@launch
            }

            loadCatalogProgressively(moviesId, seriesId, silentUpdate = silentUpdate || hasContent)
        }
    }

    fun refreshCatalog(forceSync: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            playbackHistoryManager.syncFromCloud()
            _isRefreshing.value = true
            _uiState.update { it.copy(isLoading = true) }
            try {
                val service = getDriveService()
                if (service != null) {
                    // Discover the best populated folder dynamically
                    val moviesFolderId = SmartFolderResolver.findPopulatedMoviesFolder(service)
                        ?: resolveMoviesFolderId(service)
                    val seriesFolderId = SmartFolderResolver.findPopulatedSeriesFolder(service)
                        ?: resolveSeriesFolderId(service)

                    Log.d("VideoViewModel", "Discovered Movies Folder ID: $moviesFolderId")
                    Log.d("VideoViewModel", "Discovered Series Folder ID: $seriesFolderId")

                    // Persist discovered folder IDs to prevent sticking to empty onboarding folders
                    if (!moviesFolderId.isNullOrBlank()) {
                        folderManager.saveCustomMoviesFolderId(moviesFolderId)
                    }
                    if (!seriesFolderId.isNullOrBlank()) {
                        folderManager.saveCustomSeriesFolderId(seriesFolderId)
                    }

                    // 1. Recursive Scan for Movies
                    if (!moviesFolderId.isNullOrBlank()) {
                        val movies = DriveCatalogScanner.scanMoviesRecursively(service, moviesFolderId)
                        _moviesList.value = movies
                        val videoItems = movies.map { it.toVideoItem() }
                        _moviesState.value = videoItems
                        _uiState.update { current ->
                            val hero = current.heroItem ?: videoItems.firstOrNull()
                            current.copy(
                                videos = videoItems,
                                moviesList = videoItems,
                                filteredVideos = applyFilterAndSearch(videoItems, current.selectedFilter, current.searchQuery),
                                heroItem = hero,
                                isInitialLoading = false,
                                isLoading = false
                            )
                        }
                    } else {
                        _moviesList.value = emptyList()
                        _moviesState.value = emptyList()
                    }

                    // 2. Recursive Scan for TV Series
                    if (!seriesFolderId.isNullOrBlank()) {
                        val series = DriveCatalogScanner.scanSeriesRecursively(service, seriesFolderId)
                        _seriesList.value = series
                        saveCatalogToDisk(getApplication(), series)
                        _uiState.update { current ->
                            val heroShow = current.heroSeries ?: series.firstOrNull()
                            current.copy(
                                tvSeriesList = series,
                                heroSeries = heroShow,
                                isInitialLoading = false,
                                isLoading = false
                            )
                        }
                    } else {
                        _seriesList.value = emptyList()
                    }

                    // Persist accumulated catalog to local cache
                    catalogCacheManager.saveCatalog(_moviesState.value, _seriesState.value)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Catalog sync failed: ${e.message}", e)
            } finally {
                _isRefreshing.value = false
                _uiState.update { it.copy(isLoading = false, isInitialLoading = false) }
            }
        }
    }

    suspend fun fetchAllDriveFolders(): List<com.google.api.services.drive.model.File> = withContext(Dispatchers.IO) {
        val service = getDriveService() ?: return@withContext emptyList()
        val folderList = mutableListOf<com.google.api.services.drive.model.File>()
        val query = "(mimeType = 'application/vnd.google-apps.folder' or mimeType = 'application/vnd.google-apps.shortcut') and trashed = false"
        try {
            var pageToken: String? = null
            do {
                val response = service.files().list()
                    .setQ(query)
                    .setFields("nextPageToken, files(id, name, mimeType, shortcutDetails)")
                    .setSupportsAllDrives(true)
                    .setIncludeItemsFromAllDrives(true)
                    .setPageSize(100)
                    .setPageToken(pageToken)
                    .execute()

                response.files?.let { folderList.addAll(it) }
                pageToken = response.nextPageToken
            } while (!pageToken.isNullOrBlank() && folderList.size < 300)
            folderList.sortBy { it.name?.lowercase() ?: "" }
        } catch (e: Exception) {
            Log.e(TAG, "Failed fetching drive folders: ${e.message}", e)
        }
        folderList
    }

    fun selectCustomFolder(folderType: String, folder: com.google.api.services.drive.model.File) {
        viewModelScope.launch(Dispatchers.IO) {
            val targetId = if (folder.mimeType == "application/vnd.google-apps.shortcut" && folder.shortcutDetails?.targetId != null) {
                folder.shortcutDetails.targetId
            } else {
                folder.id.orEmpty()
            }
            if (targetId.isBlank()) return@launch

            if (folderType.equals("Movies", ignoreCase = true)) {
                folderManager.saveCustomMoviesFolderId(targetId)
            } else if (folderType.equals("Series", ignoreCase = true)) {
                folderManager.saveCustomSeriesFolderId(targetId)
            }
            refreshCatalog(forceSync = true)
        }
    }

    fun clearCachedFoldersAndRescan() {
        viewModelScope.launch(Dispatchers.IO) {
            folderManager.clearSavedFolderIds()
            refreshCatalog(forceSync = true)
        }
    }

    fun runScannerDiagnosticTest() {
        viewModelScope.launch(Dispatchers.IO) {
            val service = getDriveService() ?: return@launch
            Log.i("ClotubeScannerTest", "========== STARTING SCANNER SELF-TEST ==========")
            val moviesFolderId = resolveMoviesFolderId(service) ?: ""
            val movies = DriveCatalogScanner.scanMoviesRecursively(service, moviesFolderId)
            Log.i("ClotubeScannerTest", "Diagnostic Movies Found: ${movies.size}")
            movies.forEach { m ->
                Log.i("ClotubeScannerTest", " - Movie: '${m.title}', File: '${m.fileName}', Parent: '${m.parentFolder}'")
            }
            val seriesFolderId = resolveSeriesFolderId(service)
            if (!seriesFolderId.isNullOrBlank()) {
                val seriesList = DriveCatalogScanner.scanSeriesRecursively(service, seriesFolderId)
                Log.i("ClotubeScannerTest", "Diagnostic Series Found: ${seriesList.size}")
                seriesList.forEach { s ->
                    val epCount = s.seasons.sumOf { it.episodes.size }
                    Log.i("ClotubeScannerTest", " - Series: '${s.title}', Seasons: ${s.seasons.size}, Total Episodes: $epCount")
                    s.seasons.forEach { sea ->
                        sea.episodes.forEach { ep ->
                            Log.i("ClotubeScannerTest", "    * S${ep.seasonNumber}E${ep.episodeNumber}: '${ep.title}' (${ep.fileName})")
                        }
                    }
                }
            }
            Log.i("ClotubeScannerTest", "========== SCANNER SELF-TEST COMPLETE ==========")
        }
    }

    fun fetchDriveMedia(silentUpdate: Boolean = false) {
        refreshCatalog(forceSync = !silentUpdate)
    }

    fun fetchDriveVideos() {
        refreshCatalog(forceSync = true)
    }

    fun selectFilter(filter: String) {
        val currentState = _uiState.value
        _uiState.update {
            it.copy(
                selectedFilter = filter,
                filteredVideos = applyFilterAndSearch(currentState.moviesList, filter, currentState.searchQuery)
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        val currentState = _uiState.value
        _uiState.update {
            it.copy(
                searchQuery = query,
                filteredVideos = applyFilterAndSearch(currentState.moviesList, currentState.selectedFilter, query)
            )
        }
    }

    fun toggleSearchActive(active: Boolean) {
        _uiState.update { it.copy(isSearchActive = active) }
    }

    fun openInspector(video: VideoItem) {
        _uiState.update { it.copy(selectedVideoForInspection = video) }
    }

    fun closeInspector() {
        _uiState.update { it.copy(selectedVideoForInspection = null) }
    }

    fun setHardwareAcceleration(enabled: Boolean) {
        _uiState.update { it.copy(isHardwareAccelerationEnabled = enabled) }
    }

    fun selectTab(tab: NavigationTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun getAccessToken(): String? {
        return _uiState.value.accessToken ?: authRepository.accessToken.value
    }

    private fun applyFilterAndSearch(list: List<VideoItem>, filter: String, query: String): List<VideoItem> {
        val filtered = when (filter) {
            "All" -> list
            "Movies" -> list
            "TV Series" -> list
            else -> list
        }

        if (query.isBlank()) return filtered
        return filtered.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.genre.contains(query, ignoreCase = true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        vlcPlayerManager.release()
    }
}

fun formatTimestamp(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
}
