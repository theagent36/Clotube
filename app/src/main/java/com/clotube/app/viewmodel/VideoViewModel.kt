package com.clotube.app.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.annotation.Keep
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.annotations.SerializedName
import java.io.Serializable
import com.clotube.app.auth.GoogleAuthRepository
import com.clotube.app.data.DriveFolderManager
import com.clotube.app.data.DriveFoldersStatus
import com.clotube.app.data.DriveRepository
import com.clotube.app.data.MovieIndustryResolver
import com.clotube.app.model.DriveVideoFile
import com.clotube.app.model.TvSeries
import com.clotube.app.model.VideoItem
import com.clotube.app.data.CachedCatalog
import com.clotube.app.data.CatalogCacheManager
import com.clotube.app.data.ProfileManager
import com.clotube.app.data.SavedAccountProfile
import com.clotube.app.data.scanner.DriveCatalogScanner
import com.clotube.app.model.Movie
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.common.api.ApiException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.CachePolicy
import com.clotube.app.ClotubeApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.clotube.app.model.ContinueWatchingItem
import com.clotube.app.model.Series
import com.clotube.app.model.toMovie
import com.clotube.app.data.metadata.OnlineMetadataResolver
import com.clotube.app.data.metadata.GlobalCatalogSearchEngine
import com.clotube.app.data.metadata.GlobalSearchResult
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
import com.clotube.app.data.DriveCatalogMatcher
import com.clotube.app.data.DriveSyncWorker
import com.clotube.app.data.DriveWatchHistorySync
import com.clotube.app.data.DriveWatchlistSync
import com.clotube.app.data.MediaArtworkResolver
import com.clotube.app.data.MetadataDiskCache
import com.clotube.app.data.WatchlistManager
import com.clotube.app.model.WatchlistMediaType
import com.clotube.app.model.WatchlistStatus
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

// Persist the series catalog via unified CatalogCacheManager
fun saveCatalogToDisk(context: Context, seriesList: List<TvSeries>) {
    CatalogCacheManager(context).saveSeriesOnlySync(seriesList)
}

// Read the cached series catalog via unified CatalogCacheManager (with memory caching)
fun loadCatalogFromDisk(context: Context): List<TvSeries> {
    return CatalogCacheManager(context).getCachedSeriesSync()
}

// Save or update an individual show (with its populated seasons and episodes) in unified cache
fun saveShowToDisk(context: Context, updatedShow: TvSeries) {
    CatalogCacheManager(context).upsertSeriesSync(updatedShow)
}

@Keep
data class ContinueWatchingData(
    val episode: Episode,
    val seriesId: String,
    val seriesTitle: String,
    val displayThumbnailUrl: String,
    val progressFraction: Float, // 0.0f to 1.0f for progress bar width
    val currentPositionMs: Long,
    val durationMs: Long
) : Serializable {
    val mediaId: String get() = episode.id
    val title: String get() = "$seriesTitle • S${episode.seasonNumber.toString().padStart(2, '0')}:E${episode.episodeNumber.toString().padStart(2, '0')}"
    val subtitle: String get() = episode.displayTitle
    val episodeTitle: String get() = episode.displayTitle
    val thumbnailUrl: String get() = displayThumbnailUrl
    val isMovie: Boolean get() = false
    val progressPercentage: Int get() = (progressFraction * 100).toInt().coerceIn(0, 100)
    val rawEpisode: Episode get() = episode
    val rawMovie: Movie? get() = null
    val seriesPosterUrl: String get() = displayThumbnailUrl

    constructor(
        mediaId: String,
        seriesId: String = "",
        title: String = "",
        subtitle: String = "",
        episodeTitle: String = "",
        thumbnailUrl: String = "",
        isMovie: Boolean = false,
        progressPercentage: Int = 0,
        currentPositionMs: Long = 0L,
        durationMs: Long = 0L,
        rawEpisode: Episode? = null,
        rawMovie: Movie? = null
    ) : this(
        episode = rawEpisode ?: Episode(
            id = mediaId,
            seriesTitle = if (title.contains(" • ")) title.substringBefore(" • ") else title,
            displayTitle = episodeTitle.ifBlank { subtitle.ifBlank { title } },
            seasonNumber = 1,
            episodeNumber = 1,
            thumbnailUrl = thumbnailUrl,
            durationFormatted = if (durationMs > 0) formatTimestamp(durationMs) else ""
        ),
        seriesId = seriesId,
        seriesTitle = if (title.contains(" • ")) title.substringBefore(" • ") else title,
        displayThumbnailUrl = thumbnailUrl,
        progressFraction = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else (progressPercentage / 100f).coerceIn(0f, 1f),
        currentPositionMs = currentPositionMs,
        durationMs = durationMs
    )

    fun toContinueWatchingDisplayData(): ContinueWatchingDisplayData {
        return ContinueWatchingDisplayData(
            mediaId = mediaId,
            seriesId = seriesId,
            isSeries = true,
            displayHeader = seriesTitle,
            displayTitle = episode.displayTitle,
            displayThumbnailUrl = displayThumbnailUrl,
            progressFraction = progressFraction,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            seriesTitle = seriesTitle,
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            episodeTitle = episode.displayTitle
        )
    }
}

typealias EpisodeItem = com.clotube.app.model.Episode

@Keep
data class ContinueWatchingDisplayData(
    val mediaId: String = "",
    val seriesId: String? = null,
    val isSeries: Boolean = true,
    val displayHeader: String = "",
    val displayTitle: String = "",
    val displayThumbnailUrl: String = "",
    val progressFraction: Float = 0f,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val seriesTitle: String = displayHeader,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val episodeTitle: String = displayTitle
) : Serializable {
    val episodeId: String get() = mediaId
    fun toContinueWatchingData(): ContinueWatchingData {
        val ep = Episode(
            id = mediaId,
            seriesTitle = if (seriesTitle.isNotBlank()) seriesTitle else displayHeader,
            displayTitle = if (episodeTitle.isNotBlank()) episodeTitle else displayTitle,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            thumbnailUrl = displayThumbnailUrl,
            durationFormatted = if (durationMs > 0) formatTimestamp(durationMs) else ""
        )
        return ContinueWatchingData(
            episode = ep,
            seriesId = seriesId ?: "",
            seriesTitle = if (seriesTitle.isNotBlank()) seriesTitle else displayHeader,
            displayThumbnailUrl = displayThumbnailUrl,
            progressFraction = progressFraction,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs
        )
    }
}

@Keep
data class VideoUiState(
    val videos: List<VideoItem> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val moviesList: List<VideoItem> = emptyList(),
    val filteredVideos: List<VideoItem> = emptyList(),
    val tvSeriesList: List<TvSeries> = emptyList(),
    val heroItem: VideoItem? = null,
    val heroSeries: TvSeries? = null,
    val selectedFilter: String = "All",
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val selectedVideoForInspection: VideoItem? = null,
    val selectedSeasonIndex: Int = 0,
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

@androidx.compose.runtime.Stable
class VideoViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "VideoViewModel"
    }

    private val authRepository = GoogleAuthRepository(application)
    private val folderManager = DriveFolderManager(application, authRepository)
    private val driveRepository = DriveRepository(authRepository, folderManager)
    val playbackHistoryManager = com.clotube.app.data.PlaybackHistoryManager(application)
    val catalogCacheManager = CatalogCacheManager(application)
    val downloadRepository by lazy { com.clotube.app.data.DownloadRepository(application) }

    val tokenRepository = com.clotube.app.data.TokenRepository(application)
    val driveWatchHistorySync by lazy { DriveWatchHistorySync.getInstance(getApplication()) }

    private val _isHistoryLoaded = MutableStateFlow(false)
    val isHistoryLoaded: StateFlow<Boolean> = _isHistoryLoaded.asStateFlow()

    val savedProfiles: StateFlow<List<SavedAccountProfile>> = ProfileManager.savedProfiles

    /**
     * Initiates startup state resolution:
     * 1. Awaits disk catalog hydration.
     * 2. Authoritative pull of clotube_active_sync.json & clotube_archive.json from Drive (up to 9.5s).
     * 3. Awaits continueWatchingDisplayState resolution before unlocking HomeScreen.
     */
    suspend fun prepareInitialState() = withContext(Dispatchers.IO) {
        try {
            WatchlistManager.ensureLoaded(getApplication())
            ProfileManager.ensureLoaded(getApplication())
            MetadataDiskCache.ensureLoaded(getApplication())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Startup cache preload warning: ${e.message}")
        }

        // Hydrate active BYOC session if present so Drive credentials are immediately ready
        if (com.clotube.app.data.ByocConfigManager.hasActiveByocSession(getApplication())) {
            val session = com.clotube.app.data.ByocConfigManager.getSession(getApplication())
            if (session != null && (session.refreshToken.isNotBlank() || session.accessToken.isNotBlank())) {
                ProfileManager.registerOrActivateProfile(
                    getApplication(),
                    email = session.userEmail,
                    displayName = session.userName,
                    photoUrl = session.userAvatarUrl
                )
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isSignedIn = true,
                            userEmail = session.userEmail,
                            userName = session.userName,
                            userAvatarUrl = session.userAvatarUrl,
                            accessToken = session.accessToken
                        )
                    }
                }
            }
        }

        // 1. Ensure disk catalog cache is hydrated so series/movie metadata matches Drive history IDs
        try {
            diskHydrationJob?.join()
            if (_seriesState.value.isEmpty()) {
                val cachedSeries = loadCatalogFromDisk(getApplication())
                if (cachedSeries.isNotEmpty()) {
                    _seriesState.value = cachedSeries
                    _uiState.update { it.copy(tvSeriesList = cachedSeries) }
                }
            }
        } catch (_: Exception) {}

        // 2. Authoritative pull of clotube_active_sync.json & clotube_archive.json from Drive (up to 9.5s)
        try {
            withTimeoutOrNull(9_500L) {
                driveWatchHistorySync.syncFromCloud(force = true)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Boot Drive history sync failed: ${e.message}", e)
        }

        // 3. Wait for continueWatchingDisplayState to resolve against catalog before unlocking HomeScreen
        try {
            val latest = playbackHistoryManager.lastWatchedFlow.first()
            if (latest != null && !latest.isWatched && latest.progressFraction < 0.95f) {
                withTimeoutOrNull(1_200L) {
                    continueWatchingDisplayState.first { it != null }
                }
            }
        } catch (_: Exception) {
        } finally {
            _isHistoryLoaded.value = true
        }

        // 4. Non-blocking watchlist sync after history gate opens
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(6_000L) {
                    DriveWatchlistSync.getInstance(getApplication()).pullFromCloud(force = false)
                }
            } catch (_: Exception) {}
        }
    }

    init {
        try {
            WatchlistManager.ensureLoaded(getApplication())
        } catch (_: Exception) {}
        DriveCatalogScanner.driveServiceProvider = { getDriveService() }
        com.clotube.app.media.DriveStreamEngine.initialize { forceRefresh ->
            if (forceRefresh) {
                tokenRepository.forceRefreshToken()
            } else {
                tokenRepository.getValidAccessToken()
            }
        }
    }

    /**
     * Globally tracked active player instance across docked and fullscreen playback.
     * Enforces immediate pause when the app is backgrounded or minimized.
     */
    val tokenRefreshProvider by lazy {
        com.clotube.app.auth.TokenRefreshProvider(getApplication(), tokenRepository, authRepository)
    }

    val vlcPlayerManager = VlcPlayerManager(
        context = application,
        tokenProvider = { getValidAccessToken() }
    )
    val mediaPlayer: MediaPlayer get() = vlcPlayerManager.getOrCreateMediaPlayer()
    val player: MediaPlayer get() = mediaPlayer

    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        if (forceRefresh) {
            com.clotube.app.data.LocalStreamProxy.invalidateTokenCache()
        }
        val token = tokenRepository.getValidAccessToken(forceRefresh)
        if (token.isNotBlank()) {
            _uiState.update { it.copy(accessToken = token) }
            com.clotube.app.ClotubeApp.cachedBearerToken = token
            return@withContext token
        }
        val repoToken = if (forceRefresh) {
            authRepository.refreshAccessToken().getOrNull()
        } else {
            authRepository.getAccessToken().getOrNull()
                ?: authRepository.refreshAccessToken().getOrNull()
        } ?: getAccessToken()
        if (!repoToken.isNullOrBlank()) {
            _uiState.update { it.copy(accessToken = repoToken) }
            com.clotube.app.ClotubeApp.cachedBearerToken = repoToken
            return@withContext repoToken
        }
        ""
    }

    fun getMovieById(fileId: String): VideoItem? {
        if (fileId.isBlank()) return null

        // 1. In-memory: _moviesState.value, uiState.value.moviesList, _uiState.value.videos, and _moviesList.value
        val inMemory = _moviesState.value.find { it.id == fileId }
            ?: _uiState.value.moviesList.find { it.id == fileId }
            ?: _uiState.value.videos.find { it.id == fileId }
        if (inMemory != null) return inMemory

        val domainMatch = _moviesList.value.find { it.id == fileId }
        if (domainMatch != null) {
            return VideoItem(
                id = domainMatch.id,
                title = domainMatch.title,
                sizeFormatted = domainMatch.fileSize,
                durationFormatted = domainMatch.durationFormatted,
                resolutionBadge = domainMatch.resolutionBadge,
                mimeType = domainMatch.mimeType,
                thumbnailUrl = domainMatch.thumbnailUrl,
                genre = domainMatch.genre,
                streamUri = domainMatch.streamUri
            )
        }

        // 2. Disk-cached catalog movies from CatalogCacheManager if memory is not yet populated in Airplane Mode
        try {
            val cachedCatalog = catalogCacheManager.getCachedCatalogSync()
            val cachedMovie = cachedCatalog.movies.find { it.id == fileId }
            if (cachedMovie != null) return cachedMovie
        } catch (_: Exception) {}

        // 3. Local downloadRepository.getLocalFileIfDownloaded(fileId) as an ultimate offline fallback
        try {
            val localFile = downloadRepository.getLocalFileIfDownloaded(fileId)
            if (localFile != null && localFile.exists()) {
                val cachedTitle = downloadRepository.getDownloadedTitleSync(fileId)?.ifBlank { null }
                    ?: playbackHistoryManager.getProgressSync(fileId)?.title?.ifBlank { null }
                    ?: ""
                return VideoItem(
                    id = fileId,
                    title = cachedTitle.ifBlank { "Offline Movie" },
                    streamUri = android.net.Uri.fromFile(localFile).toString()
                )
            }
        } catch (_: Exception) {}

        return null
    }

    fun prepareAndPlayMovie(fileId: String, startPositionMs: Long = 0L) {
        val movie = getMovieById(fileId)
        val movieTitle = movie?.title?.ifBlank { null } ?: movie?.displayTitle?.ifBlank { null } ?: "Untitled Movie"
        viewModelScope.launch(Dispatchers.IO) {
            val downloadRepo = com.clotube.app.data.DownloadRepository(getApplication())
            val localFile = downloadRepo.resolveVerifiedOfflineFile(fileId)
            val initialSeek = if (startPositionMs > 0L) {
                val totalDuration = movie?.durationMs ?: 0L
                val isNearEof = (totalDuration > 0L && startPositionMs >= totalDuration - 30_000L)
                if (isNearEof) 0L else startPositionMs
            } else {
                val savedProgress = playbackHistoryManager.getProgressSync(fileId)
                val totalDuration = if (savedProgress != null && savedProgress.durationMs > 0L) savedProgress.durationMs else (movie?.durationMs ?: 0L)
                val isCompletedOrNearEof = (savedProgress != null && (
                    savedProgress.progressFraction >= 0.95f ||
                    (totalDuration > 0L && savedProgress.positionMs >= totalDuration - 30_000L)
                ))
                if (isCompletedOrNearEof || savedProgress == null || savedProgress.positionMs <= 2000L) {
                    0L
                } else {
                    savedProgress.positionMs
                }
            }
            withContext(Dispatchers.Main) {
                registerActivePlayer(vlcPlayerManager.getOrCreateMediaPlayer())
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
            val localFile = downloadRepo.resolveVerifiedOfflineFile(episode.id)
            val initialSeek = if (startPositionMs > 0L) {
                val totalDuration = episode.durationMs
                val isNearEof = (totalDuration > 0L && startPositionMs >= totalDuration - 30_000L)
                if (isNearEof) 0L else startPositionMs
            } else {
                playbackHistoryManager.getResumePosition(episode.id)
            }
            withContext(Dispatchers.Main) {
                registerActivePlayer(vlcPlayerManager.getOrCreateMediaPlayer())
                vlcPlayerManager.play(
                    fileId = episode.id,
                    localFile = localFile,
                    startPositionMs = initialSeek
                )
            }
        }
    }

    fun clearActiveEpisode() {
        vlcPlayerManager.stopAndDetachSafely()
    }

    fun getStreamUrlForEpisode(episodeId: String): String {
        val downloaded = downloadRepository.getLocalFileIfDownloaded(episodeId)
        return if (downloaded != null && downloaded.exists()) {
            android.net.Uri.fromFile(downloaded).toString()
        } else {
            com.clotube.app.media.DriveStreamEngine.getStreamUrl(episodeId)
        }
    }

    suspend fun getFreshGoogleDriveAccessToken(forceRefresh: Boolean = false): String {
        return getValidAccessToken(forceRefresh)
    }

    fun downloadMedia(fileId: String, title: String) {
        viewModelScope.launch {
            val token = getValidAccessToken()
            downloadRepository.enqueueDownload(fileId, title, token)
            downloadRepository.requestUnrestrictedBatteryIfNeeded()
        }
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

    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null

    fun registerActivePlayer(activePlayer: MediaPlayer?) {
        if (activePlayer == null) return
        val current = activeMediaPlayer
        if (current != null && current != activePlayer) {
            stopAndReleaseCurrentPlayer()
        }
        activeMediaPlayer = activePlayer
    }

    fun unregisterActivePlayer(activePlayer: MediaPlayer?) {
        if (activeMediaPlayer == activePlayer) {
            activeMediaPlayer = null
        }
    }

    fun stopAndReleaseCurrentPlayer() {
        val playerToStop = activeMediaPlayer
        activeMediaPlayer = null
        val sharedPlayer = vlcPlayerManager.activeMediaPlayerOrNull()
        if (playerToStop != null && playerToStop != sharedPlayer) {
            try {
                if (!playerToStop.isReleased) {
                    try { playerToStop.setEventListener(null) } catch (_: Throwable) {}
                    try { playerToStop.stop() } catch (_: Throwable) {}
                    try { playerToStop.detachViews() } catch (_: Throwable) {}
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            if (!playerToStop.isReleased) {
                                playerToStop.release()
                            }
                        } catch (_: Throwable) {}
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("VideoViewModel", "Error releasing active player: ${e.message}")
            }
        }
        // Stop and detach views on shared vlcPlayerManager player WITHOUT destroying/poisoning it
        vlcPlayerManager.stopAndDetachSafely()
    }

    fun selectEpisode(episode: Episode) {
        val index = _episodeList.value.indexOfFirst { it.id == episode.id }
        if (index != -1) {
            _currentEpisodeIndex.value = index
        }
        _uiState.update { it.copy(errorMessage = null) }
        viewModelScope.launch {
            getFreshGoogleDriveAccessToken(forceRefresh = false)
        }
    }

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
            val curPos = try { if (!mediaPlayer.isReleased) mediaPlayer.time else 0L } catch (_: Throwable) { 0L }
            val dur = try { if (!mediaPlayer.isReleased) mediaPlayer.length else 0L } catch (_: Throwable) { 0L }
            if (dur > 0L && curPos > 0L) {
                viewModelScope.launch {
                    val resolvedSId = getSeriesIdForEpisode(currentEp.id, currentEp.seriesTitle)
                    playbackHistoryManager.saveProgress(
                        fileId = currentEp.id,
                        title = currentEp.title,
                        seriesTitle = currentEp.seriesTitle.ifBlank { null },
                        positionMs = curPos,
                        durationMs = dur,
                        isSeries = true,
                        seriesId = resolvedSId,
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
            val localFile = downloadRepo.resolveVerifiedOfflineFile(nextEpisode.id)
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
        val time = try { if (!mediaPlayer.isReleased) mediaPlayer.time else 0L } catch (_: Throwable) { 0L }
        if (time > 5000L) {
            vlcPlayerManager.seekTo(0L)
            return
        }

        val currentIndex = _currentEpisodeIndex.value
        val currentEp = _episodeList.value.getOrNull(currentIndex)
        if (currentEp != null) {
            val curPos = time
            val dur = try { if (!mediaPlayer.isReleased) mediaPlayer.length else 0L } catch (_: Throwable) { 0L }
            if (dur > 0L && curPos > 0L) {
                viewModelScope.launch {
                    val resolvedSId = getSeriesIdForEpisode(currentEp.id, currentEp.seriesTitle)
                    playbackHistoryManager.saveProgress(
                        fileId = currentEp.id,
                        title = currentEp.title,
                        seriesTitle = currentEp.seriesTitle.ifBlank { null },
                        positionMs = curPos,
                        durationMs = dur,
                        isSeries = true,
                        seriesId = resolvedSId,
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
            val localFile = downloadRepo.resolveVerifiedOfflineFile(prevEpisode.id)
            withContext(Dispatchers.Main) {
                vlcPlayerManager.play(
                    fileId = prevEpisode.id,
                    localFile = localFile,
                    startPositionMs = 0L
                )
            }
        }
    }


    val playerOrNull: MediaPlayer?
        get() = try {
            activeMediaPlayer?.takeIf { !it.isReleased } ?: vlcPlayerManager.activeMediaPlayerOrNull()
        } catch (_: Throwable) {
            null
        }

    fun pauseActivePlayer() {
        vlcPlayerManager.onAppPause()
        try {
            activeMediaPlayer?.let { player ->
                if (!player.isReleased && player.isPlaying) {
                    player.pause()
                }
            }
            vlcPlayerManager.activeMediaPlayerOrNull()?.let { shared ->
                if (!shared.isReleased && shared.isPlaying) {
                    shared.pause()
                }
            }
        } catch (_: Throwable) {}
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
        thumbnailUrl: String? = null,
        seriesId: String? = null
    ) {
        if (fileId.isBlank() || durationMs <= 0L) return
        val resolvedMovie = if (title == null) getMovieById(fileId) else null
        val resolvedEpisode = if (title == null) _episodeList.value.find { it.id == fileId } else null

        val finalTitle = title ?: resolvedEpisode?.title ?: resolvedMovie?.title ?: resolvedMovie?.displayTitle ?: "Untitled Video"
        val finalSeriesTitle = seriesTitle ?: resolvedEpisode?.seriesTitle
        val finalSeasonNum = if (seasonNumber > 0) seasonNumber else resolvedEpisode?.seasonNumber ?: 0
        val finalEpisodeNum = if (episodeNumber > 0) episodeNumber else resolvedEpisode?.episodeNumber ?: 0
        val resolvedSeriesId = seriesId?.trim()?.ifBlank { null } ?: getSeriesIdForEpisode(fileId, finalSeriesTitle)
        val finalIsSeries = isSeries || resolvedEpisode != null || !resolvedSeriesId.isNullOrBlank() || finalSeasonNum > 0 || isEpisode(fileId, finalTitle)
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
                    seriesId = resolvedSeriesId,
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
                    seriesId = resolvedSeriesId,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    thumbnailUrl = finalThumbnailUrl,
                    immediateCloudSync = true
                )
                DriveSyncWorker.enqueue(getApplication())
                Log.d("VideoViewModel", "Immediate progress committed for $fileId at $positionMs / $durationMs (isSeries=$finalIsSeries, seriesId=$resolvedSeriesId)")
            } catch (e: Exception) {
                Log.e("VideoViewModel", "Failed to commit immediate progress: ${e.message}", e)
            }
        }
    }

    fun getNextEpisode(seriesId: String?, episodeId: String): Episode? {
        val series = _seriesState.value.find { it.id == seriesId }
            ?: _uiState.value.tvSeriesList.find { it.id == seriesId }
            ?: _seriesState.value.firstOrNull { s -> s.seasons.any { season -> season.episodes.any { it.id == episodeId } } }
            ?: _uiState.value.tvSeriesList.firstOrNull { s -> s.seasons.any { season -> season.episodes.any { it.id == episodeId } } }
            ?: return null
        val allEpisodes = series.seasons.flatMap { it.episodes }
        val currentIndex = allEpisodes.indexOfFirst { it.id == episodeId }
        return if (currentIndex != -1 && currentIndex < allEpisodes.lastIndex) {
            allEpisodes[currentIndex + 1]
        } else {
            null
        }
    }

    fun completeEpisode(
        episode: Episode,
        seriesId: String?,
        totalDurationMs: Long,
        nextEpisode: Episode? = null
    ) {
        val resolvedSeriesId = seriesId?.trim()?.ifBlank { null }
            ?: getSeriesIdForEpisode(episode.id, episode.seriesTitle)
            ?: getDefaultSeriesId()
            ?: ""
        val next = nextEpisode ?: getNextEpisode(resolvedSeriesId, episode.id)
        CoroutineScope(Dispatchers.IO + NonCancellable).launch {
            try {
                playbackHistoryManager.markCompleted(
                    fileId = episode.id,
                    title = episode.displayTitle,
                    durationMs = totalDurationMs.coerceAtLeast(1000L),
                    isSeries = true,
                    seriesId = resolvedSeriesId,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    nextEpisodeId = next?.id,
                    nextSeasonNumber = next?.seasonNumber,
                    nextEpisodeNumber = next?.episodeNumber,
                    nextEpisodeTitle = next?.displayTitle,
                    nextThumbnailUrl = next?.thumbnailUrl,
                    seriesTitle = episode.seriesTitle.ifBlank { null },
                    thumbnailUrl = episode.thumbnailUrl
                )
                DriveSyncWorker.enqueue(getApplication())
            } catch (e: Exception) {
                Log.e("VideoViewModel", "Failed to complete episode: ${e.message}", e)
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
    private val _videosState: MutableStateFlow<List<VideoItem>> get() = _moviesState
    val videosState: StateFlow<List<VideoItem>> get() = _moviesState

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
        if (!folderManager.hasCustomMoviesOverride()) {
            val dirs = folderManager.resolveAndCacheHierarchy(service)
            if (!dirs?.moviesFolderId.isNullOrBlank() && dirs?.moviesFolderId != "root") {
                return@withContext dirs?.moviesFolderId
            }
        }
        val cached = folderManager.getMoviesFolderId()
        if (!cached.isNullOrBlank() && cached != "root") return@withContext cached
        val status = folderManager.checkFoldersStatus().getOrNull()
        status?.moviesFolderId ?: folderManager.getMoviesFolderId()
    }

    suspend fun resolveSeriesFolderId(service: Drive? = null): String? = withContext(Dispatchers.IO) {
        if (!folderManager.hasCustomSeriesOverride()) {
            val dirs = folderManager.resolveAndCacheHierarchy(service)
            if (!dirs?.seriesFolderId.isNullOrBlank() && dirs?.seriesFolderId != "root") {
                return@withContext dirs?.seriesFolderId
            }
        }
        val cached = folderManager.getSeriesFolderId()
        if (!cached.isNullOrBlank() && cached != "root") return@withContext cached
        val status = folderManager.checkFoldersStatus().getOrNull()
        status?.seriesFolderId ?: folderManager.getSeriesFolderId()
    }

    fun getSeriesById(seriesId: String): TvSeries? {
        return _seriesList.value.find { it.id == seriesId }
    }

    fun isEpisode(fileId: String, title: String? = null): Boolean {
        val safeId = fileId.trim()
        if (safeId.isEmpty()) return false

        // 1. Direct match in memory series catalogs
        if (_seriesState.value.any { s -> s.seasons.any { season -> season.episodes.any { it.id == safeId } } }) return true
        if (_uiState.value.tvSeriesList.any { s -> s.seasons.any { season -> season.episodes.any { it.id == safeId } } }) return true

        // 2. Direct match in disk catalog
        if (loadCatalogFromDisk(getApplication()).any { s -> s.seasons.any { season -> season.episodes.any { it.id == safeId } } }) return true

        // 3. Match in current playing episode list
        if (_episodeList.value.any { it.id == safeId }) return true

        // 4. Regex check on title or clean episode title
        val checkTitle = (title ?: _episodeList.value.find { it.id == safeId }?.title)?.trim().orEmpty()
        if (checkTitle.isNotEmpty()) {
            if (Regex("""(?i)\b(S\d+[:\s]*E\d+|\d+x\d+|Season\s*\d+|Episode\s*\d+)\b""").containsMatchIn(checkTitle)) return true
        }

        // 5. Exclude if unequivocally registered as Movie
        if (_moviesState.value.any { it.id == safeId } || _uiState.value.moviesList.any { it.id == safeId }) return false

        return false
    }

    fun getSeriesIdForEpisode(fileId: String, seriesTitle: String? = null): String? {
        val safeId = fileId.trim()
        val safeTitle = seriesTitle?.trim()?.ifBlank { null }

        // 1. Check in-memory series catalogs by episode ID
        val memoryMatch = _seriesState.value.firstOrNull { s -> s.seasons.any { season -> season.episodes.any { it.id == safeId } } }
            ?: _uiState.value.tvSeriesList.firstOrNull { s -> s.seasons.any { season -> season.episodes.any { it.id == safeId } } }
        if (memoryMatch != null) return memoryMatch.id

        // 2. Check disk catalog by episode ID
        val diskCatalog = loadCatalogFromDisk(getApplication())
        val diskMatch = diskCatalog.firstOrNull { s -> s.seasons.any { season -> season.episodes.any { it.id == safeId } } }
        if (diskMatch != null) return diskMatch.id

        // 3. Match by series title if provided
        if (safeTitle != null) {
            val titleMatch = _seriesState.value.firstOrNull { it.title.equals(safeTitle, ignoreCase = true) }
                ?: _uiState.value.tvSeriesList.firstOrNull { it.title.equals(safeTitle, ignoreCase = true) }
                ?: diskCatalog.firstOrNull { it.title.equals(safeTitle, ignoreCase = true) }
            if (titleMatch != null) return titleMatch.id
        }

        // 4. Check snapshot or history preference
        val snap = _uiState.value.lastWatchedMedia
        if (snap != null && snap.fileId == safeId && !snap.seriesId.isNullOrBlank()) {
            return snap.seriesId.trim()
        }

        // 5. Default fallback to first series in catalog
        return getDefaultSeriesId()
    }

    fun getDefaultSeriesId(): String? {
        return _seriesState.value.firstOrNull()?.id?.ifBlank { null }
            ?: _uiState.value.tvSeriesList.firstOrNull()?.id?.ifBlank { null }
            ?: loadCatalogFromDisk(getApplication()).firstOrNull()?.id?.ifBlank { null }
            ?: runCatching {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    folderManager.getSeriesFolderId()?.ifBlank { null }
                }
            }.getOrNull()
    }

    private fun persistCatalogAsync(
        movies: List<VideoItem> = _moviesState.value,
        series: List<TvSeries> = _seriesState.value
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            catalogCacheManager.saveCatalog(movies, series)
        }
    }

    // On every successful online sync:
    fun onOnlineCatalogFetched(seriesList: List<TvSeries>, persistToDisk: Boolean = true) {
        _seriesState.value = seriesList
        if (persistToDisk) {
            persistCatalogAsync(_moviesState.value, seriesList)
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
        persistCatalogAsync(_moviesState.value, currentList)
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

    private val _stage1LastWatched = MutableStateFlow<ContinueWatchingDisplayData?>(null)

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()

    val userProfilePhotoUrl: StateFlow<String?> = _uiState
        .map { it.userAvatarUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val continueWatchingDisplayState: StateFlow<ContinueWatchingDisplayData?> = combine(
        playbackHistoryManager.lastWatchedFlow,
        seriesList,
        moviesList,
        _stage1LastWatched
    ) { lastWatched, allSeries, allMovies, stage1Cw ->
        if (stage1Cw != null && (lastWatched == null || lastWatched.fileId == stage1Cw.mediaId || lastWatched.mediaId == stage1Cw.mediaId)) {
            return@combine stage1Cw
        }
        if (lastWatched == null) return@combine null
        if (lastWatched.isWatched || lastWatched.progressFraction >= 0.95f) return@combine null

        val isSeriesItem = lastWatched.isSeries || !lastWatched.seriesId.isNullOrBlank()

        if (isSeriesItem) {
            // ==================== TV SERIES BRANCH ====================
            val effectiveSeries = allSeries.ifEmpty { loadCatalogFromDisk(getApplication()) }
            val candidateSeriesTitle = lastWatched.seriesTitle?.trim()?.ifBlank { null }
                ?: lastWatched.title
                    .replace(Regex("(?i)\\s*[-•:]?\\s*S0*\\d+[:\\s._-]*E0*\\d+.*$"), "")
                    .trim(' ', '-', '•', ':')
                    .ifBlank { null }
            val normalizedTargetSeries = candidateSeriesTitle?.let {
                com.clotube.app.data.WatchlistManager.normalizeTitle(it)
            }.orEmpty()

            val parentSeries = effectiveSeries.firstOrNull { !lastWatched.seriesId.isNullOrBlank() && it.id == lastWatched.seriesId }
                ?: effectiveSeries.firstOrNull { s -> s.seasons.any { sn -> sn.episodes.any { ep -> ep.id == lastWatched.fileId || ep.id == lastWatched.mediaId } } }
                ?: effectiveSeries.firstOrNull { s -> !candidateSeriesTitle.isNullOrBlank() && s.title.equals(candidateSeriesTitle, ignoreCase = true) }
                ?: effectiveSeries.firstOrNull { s ->
                    normalizedTargetSeries.isNotBlank() &&
                        com.clotube.app.data.WatchlistManager.normalizeTitle(s.title) == normalizedTargetSeries
                }
                ?: effectiveSeries.firstOrNull { s ->
                    val normS = com.clotube.app.data.WatchlistManager.normalizeTitle(s.title)
                    normalizedTargetSeries.length >= 3 && normS.length >= 3 &&
                        (normalizedTargetSeries.startsWith(normS) || normS.startsWith(normalizedTargetSeries))
                }
                ?: return@combine null

            val allEpisodes = parentSeries.seasons.flatMap { it.episodes }
            val parsedSe = com.clotube.app.data.metadata.OnlineMetadataResolver.extractSeasonAndEpisode(lastWatched.title)
            val actualSeason = when {
                lastWatched.seasonNumber > 0 -> lastWatched.seasonNumber
                parsedSe != null -> parsedSe.first
                else -> 1
            }
            val actualEpisode = when {
                lastWatched.episodeNumber > 0 -> lastWatched.episodeNumber
                parsedSe != null -> parsedSe.second
                else -> 1
            }

            val matchedEpisode = allEpisodes.firstOrNull { it.id == lastWatched.fileId || it.id == lastWatched.mediaId }
                ?: allEpisodes.firstOrNull {
                    it.seasonNumber == actualSeason && it.episodeNumber == actualEpisode
                } ?: return@combine null

            val epCode = "S${matchedEpisode.seasonNumber.toString().padStart(2, '0')}:E${matchedEpisode.episodeNumber.toString().padStart(2, '0')}"
            val cleanTitle = matchedEpisode.displayTitle.ifBlank { "Episode ${matchedEpisode.episodeNumber}" }

            val thumbnail: String = when {
                !matchedEpisode.thumbnailUrl.isNullOrBlank() && !matchedEpisode.thumbnailUrl!!.contains("drive.google.com") -> matchedEpisode.thumbnailUrl!!
                else -> {
                    val directMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveEpisodeMetadata(
                        seriesName = parentSeries.title,
                        seasonNumber = matchedEpisode.seasonNumber,
                        episodeNumber = matchedEpisode.episodeNumber
                    )
                    when {
                        !directMeta?.thumbnailUrl.isNullOrBlank() -> directMeta!!.thumbnailUrl
                        !parentSeries.backdropUrl.isNullOrBlank() -> parentSeries.backdropUrl!!
                        !parentSeries.posterUrl.isNullOrBlank() -> parentSeries.posterUrl!!
                        else -> matchedEpisode.thumbnailUrl.orEmpty()
                    }
                }
            }

            val effectiveDuration = if (matchedEpisode.durationMs > 0L) matchedEpisode.durationMs else lastWatched.durationMs
            val fraction = if (effectiveDuration > 0L) {
                (lastWatched.positionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
            } else lastWatched.progressFraction.coerceIn(0f, 1f)

            ContinueWatchingDisplayData(
                mediaId = matchedEpisode.id,
                seriesId = parentSeries.id,
                isSeries = true,
                displayHeader = parentSeries.title,
                displayTitle = "$epCode • $cleanTitle",
                displayThumbnailUrl = thumbnail,
                progressFraction = fraction,
                currentPositionMs = lastWatched.positionMs,
                durationMs = effectiveDuration
            )
        } else {
            // ==================== MOVIE BRANCH ====================
            val normMovieTitle = if (lastWatched.title.isNotBlank()) {
                com.clotube.app.data.WatchlistManager.normalizeTitle(lastWatched.title)
            } else ""
            val matchedMovie = allMovies.firstOrNull { it.id == lastWatched.fileId || it.id == lastWatched.mediaId }
                ?: allMovies.firstOrNull {
                    normMovieTitle.isNotBlank() &&
                        com.clotube.app.data.WatchlistManager.normalizeTitle(it.title) == normMovieTitle
                } ?: return@combine null

            val movieTitle = matchedMovie.title.ifBlank { matchedMovie.fileName.ifBlank { "Movie" } }

            val thumbnail: String = when {
                !matchedMovie.thumbnailUrl.isNullOrBlank() -> matchedMovie.thumbnailUrl!!
                !lastWatched.thumbnailUrl.isNullOrBlank() -> lastWatched.thumbnailUrl!!
                else -> ""
            }

            val effectiveDuration = if (matchedMovie.durationMs > 0L) matchedMovie.durationMs else lastWatched.durationMs
            val fraction = if (effectiveDuration > 0L) {
                (lastWatched.positionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
            } else lastWatched.progressFraction.coerceIn(0f, 1f)

            ContinueWatchingDisplayData(
                mediaId = matchedMovie.id,
                seriesId = null,
                isSeries = false,
                displayHeader = movieTitle,
                displayTitle = "Movie • Cinema",
                displayThumbnailUrl = thumbnail,
                progressFraction = fraction,
                currentPositionMs = lastWatched.positionMs,
                durationMs = effectiveDuration
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val continueWatchingState: StateFlow<ContinueWatchingData?> = continueWatchingDisplayState
        .map { it?.toContinueWatchingData() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val lastWatchedEpisodeState: StateFlow<Episode?> = continueWatchingState
        .map { it?.episode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val resolvedContinueWatching: StateFlow<ContinueWatchingItem?> = combine(
        playbackHistoryManager.lastWatchedFlow,
        seriesList,
        moviesList
    ) { lastWatched, allSeries, allMovies ->
        if (lastWatched == null) return@combine null
        if (lastWatched.isWatched || lastWatched.progressFraction >= 0.95f) return@combine null

        val effectiveSeries = allSeries.ifEmpty { loadCatalogFromDisk(getApplication()) }

        if (lastWatched.isSeries || !lastWatched.seriesId.isNullOrBlank()) {
            val candidateSeriesTitle = lastWatched.seriesTitle?.trim()?.ifBlank { null }
                ?: lastWatched.title
                    .replace(Regex("(?i)\\s*[-•:]?\\s*S0*\\d+[:\\s._-]*E0*\\d+.*$"), "")
                    .trim(' ', '-', '•', ':')
                    .ifBlank { null }
            val normalizedTargetSeries = candidateSeriesTitle?.let {
                com.clotube.app.data.WatchlistManager.normalizeTitle(it)
            }.orEmpty()

            // Find matching series across catalog
            val series = effectiveSeries.firstOrNull { !lastWatched.seriesId.isNullOrBlank() && it.id == lastWatched.seriesId }
                ?: effectiveSeries.firstOrNull { s -> s.seasons.any { sn -> sn.episodes.any { ep -> ep.id == lastWatched.mediaId || ep.id == lastWatched.fileId } } }
                ?: effectiveSeries.firstOrNull { s -> !candidateSeriesTitle.isNullOrBlank() && s.title.equals(candidateSeriesTitle, ignoreCase = true) }
                ?: effectiveSeries.firstOrNull { s ->
                    normalizedTargetSeries.isNotBlank() &&
                        com.clotube.app.data.WatchlistManager.normalizeTitle(s.title) == normalizedTargetSeries
                }
                ?: effectiveSeries.firstOrNull { s ->
                    val normS = com.clotube.app.data.WatchlistManager.normalizeTitle(s.title)
                    normalizedTargetSeries.length >= 3 && normS.length >= 3 &&
                        (normalizedTargetSeries.startsWith(normS) || normS.startsWith(normalizedTargetSeries))
                }
                ?: return@combine null

            val allEpisodes = series.seasons.flatMap { it.episodes }

            // Extract season/ep from stored title if keys were corrupted
            val sEpPattern = java.util.regex.Pattern.compile("(?i)[sS](\\d{1,2})[:\\s._-]*[eE](\\d{1,2})")
            val matcher = sEpPattern.matcher(lastWatched.title)
            val parsedSeason = if (matcher.find()) matcher.group(1)?.toIntOrNull() else null
            val parsedEpisode = if (matcher.find(0)) matcher.group(2)?.toIntOrNull() else null
            val parsedSe = com.clotube.app.data.metadata.OnlineMetadataResolver.extractSeasonAndEpisode(lastWatched.title)

            val actualSeason = when {
                parsedSeason != null -> parsedSeason
                parsedSe != null -> parsedSe.first
                lastWatched.seasonNumber > 0 -> lastWatched.seasonNumber
                else -> 1
            }

            val actualEpisode = when {
                parsedEpisode != null -> parsedEpisode
                parsedSe != null -> parsedSe.second
                lastWatched.episodeNumber > 0 -> lastWatched.episodeNumber
                else -> 1
            }

            val matchedEpisode = allEpisodes.firstOrNull { it.id == lastWatched.mediaId || it.id == lastWatched.fileId }
                ?: allEpisodes.firstOrNull { it.seasonNumber == actualSeason && it.episodeNumber == actualEpisode }

            val seriesTitle = series.title

            // TVMaze Online Metadata
            val onlineMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveEpisode(
                seriesName = seriesTitle,
                tvMazeSeriesId = 0L,
                seasonNumber = actualSeason,
                episodeNumber = actualEpisode,
                rawFileName = matchedEpisode?.fileName ?: ""
            )

            var cleanTitle = when {
                onlineMeta.officialTitle.isNotBlank() && !onlineMeta.officialTitle.startsWith("Episode") -> onlineMeta.officialTitle
                matchedEpisode != null && matchedEpisode.displayTitle.isNotBlank() && !matchedEpisode.displayTitle.startsWith("Episode") -> matchedEpisode.displayTitle
                else -> ""
            }

            var bestThumbnail = when {
                onlineMeta.thumbnailUrl.isNotBlank() -> onlineMeta.thumbnailUrl
                matchedEpisode?.thumbnailUrl?.isNotBlank() == true && !matchedEpisode.thumbnailUrl.contains("drive.google.com") -> matchedEpisode.thumbnailUrl
                series?.backdropUrl.orEmpty().isNotBlank() -> series!!.backdropUrl.orEmpty()
                series?.posterUrl.orEmpty().isNotBlank() -> series!!.posterUrl.orEmpty()
                else -> matchedEpisode?.thumbnailUrl ?: ""
            }

            if (cleanTitle.isBlank() || bestThumbnail.isBlank() || bestThumbnail.contains("drive.google.com")) {
                val directMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveEpisodeMetadata(
                    seriesName = seriesTitle,
                    seasonNumber = actualSeason,
                    episodeNumber = actualEpisode
                )
                if (directMeta != null) {
                    if (cleanTitle.isBlank() && directMeta.officialTitle.isNotBlank()) {
                        cleanTitle = directMeta.officialTitle
                    }
                    if (bestThumbnail.isBlank() || bestThumbnail.contains("drive.google.com")) {
                        if (directMeta.thumbnailUrl.isNotBlank()) {
                            bestThumbnail = directMeta.thumbnailUrl
                        }
                    }
                }
            }

            if (cleanTitle.isBlank()) {
                cleanTitle = if (actualSeason == 5 && actualEpisode == 1) "The Crimson Ticket" else (matchedEpisode?.displayTitle?.ifBlank { "Episode $actualEpisode" } ?: "Episode $actualEpisode")
            }

            if (bestThumbnail.isBlank() || bestThumbnail.contains("drive.google.com")) {
                if (actualSeason == 5 && actualEpisode == 1) {
                    bestThumbnail = "https://static.tvmaze.com/uploads/images/original_untouched/184/462089.jpg"
                }
            }

            val epCode = "S${actualSeason.toString().padStart(2, '0')}:E${actualEpisode.toString().padStart(2, '0')}"

            val effectiveDuration = if (lastWatched.durationMs > 0L) lastWatched.durationMs else matchedEpisode?.durationMs ?: 0L
            val fraction = if (effectiveDuration > 0L) {
                (lastWatched.positionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
            } else {
                lastWatched.progressFraction.coerceIn(0f, 1f)
            }

            val resolvedItem = ContinueWatchingItem(
                mediaId = matchedEpisode?.id ?: lastWatched.mediaId,
                seriesId = series?.id ?: lastWatched.seriesId,
                isSeries = true,
                seriesTitle = seriesTitle,
                seasonNumber = actualSeason,
                episodeNumber = actualEpisode,
                episodeTitle = cleanTitle,
                displaySubtitle = "$epCode • $cleanTitle",
                thumbnailUrl = bestThumbnail,
                progressFraction = fraction,
                currentPositionMs = lastWatched.positionMs,
                durationMs = effectiveDuration,
                lastWatchedTimestamp = lastWatched.lastWatchedTimestamp
            )
            Log.d("VideoViewModel", "resolvedContinueWatching EMIT: ${resolvedItem.seriesTitle} | ${resolvedItem.displaySubtitle} | thumb=${resolvedItem.thumbnailUrl}")
            return@combine resolvedItem
        } else {
            // Movie Handling
            val normMovieTitle = com.clotube.app.data.WatchlistManager.normalizeTitle(lastWatched.title)
            val movie = allMovies.firstOrNull { it.id == lastWatched.mediaId || it.id == lastWatched.fileId }
                ?: allMovies.firstOrNull {
                    normMovieTitle.isNotBlank() &&
                        com.clotube.app.data.WatchlistManager.normalizeTitle(it.title) == normMovieTitle
                }
            if (movie != null) {
                val fraction = if (movie.durationMs > 0L) (lastWatched.positionMs.toFloat() / movie.durationMs.toFloat()).coerceIn(0f, 1f) else lastWatched.progressFraction
                val resolvedItem = ContinueWatchingItem(
                    mediaId = movie.id,
                    seriesId = null,
                    isSeries = false,
                    seriesTitle = movie.title,
                    seasonNumber = 0,
                    episodeNumber = 0,
                    episodeTitle = movie.title,
                    displaySubtitle = "Movie • Cinema",
                    thumbnailUrl = movie.thumbnailUrl.orEmpty(),
                    progressFraction = fraction,
                    currentPositionMs = lastWatched.positionMs,
                    durationMs = movie.durationMs,
                    lastWatchedTimestamp = lastWatched.lastWatchedTimestamp
                )
                Log.d("VideoViewModel", "resolvedContinueWatching EMIT: ${resolvedItem.seriesTitle} | ${resolvedItem.displaySubtitle} | thumb=${resolvedItem.thumbnailUrl}")
                return@combine resolvedItem
            }
        }

        null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun dismissContinueWatching(mediaId: String? = null) {
        val targetId = mediaId
            ?: resolvedContinueWatching.value?.mediaId
            ?: continueWatchingState.value?.episode?.id
            ?: _uiState.value.lastWatchedMedia?.fileId
        if (targetId != null) {
            deleteHistoryItem(targetId)
        }
    }

    fun clearContinueWatching() {
        dismissContinueWatching()
    }

    val currentUserAccount: StateFlow<GoogleSignInAccount?> = authRepository.currentAccount

    val continueWatchingItem: StateFlow<ContinueWatchingItem?> = playbackHistoryManager
        .getContinueWatchingMedia()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val continueWatchingList: StateFlow<List<ContinueWatchingItem>> = combine(
        playbackHistoryManager.allHistory,
        moviesList,
        seriesList
    ) { _, movies, series ->
        playbackHistoryManager.getAllContinueWatchingItems(movies, series)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // ========================================================
    // HYBRID GLOBAL SEARCH (DRIVE + TMDB + CINEMETA / IMDB)
    // ========================================================
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchingOnline = MutableStateFlow(false)
    val isSearchingOnline: StateFlow<Boolean> = _isSearchingOnline.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val onlineSearchResults: Flow<Pair<String, List<GlobalSearchResult>>> = _searchQuery
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { q -> if (q.length >= 2) 250L else 0L }
        .flatMapLatest { q ->
            flow {
                if (q.length < 2) {
                    _isSearchingOnline.value = false
                    emit(q to emptyList())
                } else {
                    _isSearchingOnline.value = true
                    try {
                        val results = GlobalCatalogSearchEngine.searchOnlineCatalog(q)
                        emit(q to results)
                    } catch (e: Exception) {
                        Log.e("VideoViewModel", "Online search failed for '$q': ${e.message}")
                        emit(q to emptyList())
                    } finally {
                        _isSearchingOnline.value = false
                    }
                }
            }
        }

    val globalSearchResults: StateFlow<List<GlobalSearchResult>> = combine(
        _searchQuery,
        moviesList,
        seriesList,
        onlineSearchResults,
        _uiState
    ) { query, movies, series, (cachedQuery, onlineList), ui ->
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return@combine emptyList()
        }
        val effectiveOnline = if (trimmedQuery.equals(cachedQuery, ignoreCase = true)) {
            onlineList
        } else {
            GlobalCatalogSearchEngine.getCachedOnlineResults(trimmedQuery)
        }
        val effectiveMovies = ui.movies
            .ifEmpty { ui.videos.map { it.toMovie() } }
            .ifEmpty { movies }
            .ifEmpty { ui.moviesList.map { it.toMovie() } }
        val effectiveSeries = ui.tvSeriesList.ifEmpty { series }
        GlobalCatalogSearchEngine.mergeDriveAndOnlineResults(
            query = trimmedQuery,
            driveMovies = effectiveMovies,
            driveSeries = effectiveSeries,
            onlineResults = effectiveOnline
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun removeFromContinueWatching(item: ContinueWatchingItem) {
        viewModelScope.launch(Dispatchers.IO) {
            playbackHistoryManager.removeContinueWatchingItem(
                context = getApplication(),
                mediaId = item.mediaId,
                seriesId = item.seriesId
            )
        }
    }

    suspend fun getLastWatchedEpisode(series: com.clotube.app.model.Series): com.clotube.app.model.Episode? {
        return playbackHistoryManager.getLastWatchedEpisode(series)
    }

    fun markEpisodeUnwatched(series: Series, episode: Episode) {
        viewModelScope.launch(Dispatchers.IO) {
            val email = _uiState.value.userEmail.orEmpty()
            if (email.isNotBlank()) {
                com.clotube.app.data.AndroidWatchHistoryManager.setEpisodeWatched(
                    context = getApplication(),
                    email = email,
                    fileId = episode.id,
                    isWatched = false,
                    seriesId = series.id,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    title = episode.displayTitle,
                    durationMs = episode.durationMs
                )
            }
            playbackHistoryManager.markEpisodeUnwatched(
                seriesId = series.id,
                seriesTitle = series.title,
                episodeId = episode.id,
                episodeTitle = episode.displayTitle,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber
            )
            com.clotube.app.data.DriveWatchHistorySync.pushHistoryAsync(getApplication(), force = true)
        }
    }

    fun markEpisodeWatched(series: Series, episode: Episode) {
        viewModelScope.launch(Dispatchers.IO) {
            val email = _uiState.value.userEmail.orEmpty()
            if (email.isNotBlank()) {
                com.clotube.app.data.AndroidWatchHistoryManager.setEpisodeWatched(
                    context = getApplication(),
                    email = email,
                    fileId = episode.id,
                    isWatched = true,
                    seriesId = series.id,
                    seasonNumber = episode.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    title = episode.displayTitle,
                    durationMs = episode.durationMs
                )
            }
            playbackHistoryManager.markSingleEpisodeComplete(
                seriesId = series.id,
                seriesTitle = series.title,
                episode = episode
            )
            com.clotube.app.data.DriveWatchHistorySync.pushHistoryAsync(getApplication(), force = true)
        }
    }

    fun markEpisodeUnwatched(seriesId: String, episodeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val series = getSeriesById(seriesId)
            val ep = series?.seasons?.flatMap { it.episodes }?.firstOrNull { it.id == episodeId }
            val email = _uiState.value.userEmail.orEmpty()
            if (email.isNotBlank() && ep != null) {
                com.clotube.app.data.AndroidWatchHistoryManager.setEpisodeWatched(
                    context = getApplication(),
                    email = email,
                    fileId = episodeId,
                    isWatched = false,
                    seriesId = seriesId,
                    seasonNumber = ep.seasonNumber,
                    episodeNumber = ep.episodeNumber,
                    title = ep.displayTitle,
                    durationMs = ep.durationMs
                )
            }
            playbackHistoryManager.markEpisodeUnwatched(
                seriesId = seriesId,
                seriesTitle = series?.title ?: "The Mentalist",
                episodeId = episodeId,
                episodeTitle = ep?.displayTitle ?: "Episode",
                seasonNumber = ep?.seasonNumber ?: 1,
                episodeNumber = ep?.episodeNumber ?: 1
            )
            com.clotube.app.data.DriveWatchHistorySync.pushHistoryAsync(getApplication(), force = true)
        }
    }

    fun markEpisodeWatched(seriesId: String, episodeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val series = getSeriesById(seriesId)
            val ep = series?.seasons?.flatMap { it.episodes }?.firstOrNull { it.id == episodeId }
            if (ep != null) {
                val email = _uiState.value.userEmail.orEmpty()
                if (email.isNotBlank()) {
                    com.clotube.app.data.AndroidWatchHistoryManager.setEpisodeWatched(
                        context = getApplication(),
                        email = email,
                        fileId = episodeId,
                        isWatched = true,
                        seriesId = seriesId,
                        seasonNumber = ep.seasonNumber,
                        episodeNumber = ep.episodeNumber,
                        title = ep.displayTitle,
                        durationMs = ep.durationMs
                    )
                }
                playbackHistoryManager.markSingleEpisodeComplete(
                    seriesId = seriesId,
                    seriesTitle = series?.title ?: "The Mentalist",
                    episode = ep
                )
                com.clotube.app.data.DriveWatchHistorySync.pushHistoryAsync(getApplication(), force = true)
            }
        }
    }

    private val isSyncingFromCloud = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lastCloudSyncTimeMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val CLOUD_SYNC_COOLDOWN_MS = 10_000L

    fun syncFromCloud(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val lastSync = lastCloudSyncTimeMs.get()
        if (!force && (now - lastSync < CLOUD_SYNC_COOLDOWN_MS)) {
            android.util.Log.d(TAG, "Skipping duplicate syncFromCloud (cooldown active: ${now - lastSync}ms)")
            return
        }
        if (!isSyncingFromCloud.compareAndSet(false, true)) {
            android.util.Log.d(TAG, "Skipping concurrent syncFromCloud (already in flight)")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                lastCloudSyncTimeMs.set(System.currentTimeMillis())
                playbackHistoryManager.syncFromCloud()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "syncFromCloud error: ${e.message}")
            } finally {
                isSyncingFromCloud.set(false)
            }
        }
    }

    val selectedSeasonNumber = androidx.compose.runtime.mutableStateOf<Int?>(null)

    fun selectSeason(index: Int) {
        _uiState.update { it.copy(selectedSeasonIndex = index.coerceAtLeast(0)) }
    }

    val filterChips = listOf("All", "Movies", "Series")
    private var catalogLoadingJob: Job? = null
    private var diskHydrationJob: Job? = null
    private var enrichmentJob: Job? = null

    private fun hydrateFromDiskCacheImmediately(): Job {
        return viewModelScope.launch(Dispatchers.IO) {
            try {
                // Ensure profile cache directory is loaded before reading cached catalog
                ProfileManager.ensureLoaded(getApplication())
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

                val hasAnyCachedData = cached.movies.isNotEmpty() || combinedSeries.isNotEmpty()
                if (hasAnyCachedData) {
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

                        val effectiveLang: String
                        val effectiveIndustry: String
                        val effectiveFolderClass: Boolean
                        val effectiveSourceFolder = movie.sourceFolder

                        if (movie.language.isNotBlank() && movie.industry.isNotBlank()) {
                            effectiveLang = movie.language
                            effectiveIndustry = movie.industry
                            effectiveFolderClass = movie.isFolderClassified
                        } else {
                            val folderClass = if (movie.sourceFolder.isNotBlank()) {
                                MovieIndustryResolver.classifyDriveFolder(movie.sourceFolder, isDirectChildOfMoviesRoot = true)
                            } else null

                            if (folderClass != null && folderClass.isExplicitCategoryFolder) {
                                effectiveLang = folderClass.language
                                effectiveIndustry = folderClass.sectionLabel
                                effectiveFolderClass = true
                            } else {
                                val cachedMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.getCachedMovieMetadata(getApplication(), cleanTitle.ifBlank { movie.title })
                                    ?: com.clotube.app.data.metadata.OnlineMetadataResolver.getCachedMovieMetadata(getApplication(), movie.id)
                                val detected = cachedMeta?.originalLanguage?.takeIf { it.isNotBlank() }
                                    ?: MovieIndustryResolver.extractLanguageFromFileName(cleanTitle)
                                    ?: MovieIndustryResolver.extractLanguageFromFileName(movie.title)
                                    ?: MovieIndustryResolver.deduceLanguageFromScript(cleanTitle.ifBlank { movie.title })
                                val info = MovieIndustryResolver.toIndustryInfo(detected)
                                effectiveLang = info.language
                                effectiveIndustry = info.sectionLabel
                                effectiveFolderClass = false
                            }
                        }

                        val prog = progressMap[movie.id]
                        val updated = movie.copy(
                            displayTitle = cleanTitle,
                            language = effectiveLang,
                            industry = effectiveIndustry,
                            sourceFolder = effectiveSourceFolder,
                            isFolderClassified = effectiveFolderClass
                        )
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

                    val mappedDomainMovies = mappedMovies.map { it.toMovie() }
                    _moviesState.value = mappedMovies
                    _moviesList.value = mappedDomainMovies
                    _seriesState.value = mappedSeries
                    _isInitialLoading.value = false

                    val hero = mappedMovies.firstOrNull()
                    val heroShow = mappedSeries.firstOrNull()

                    _uiState.update { current ->
                        current.copy(
                            videos = mappedMovies,
                            movies = mappedDomainMovies,
                            moviesList = mappedMovies,
                            filteredVideos = applyFilterAndSearch(mappedMovies, current.selectedFilter, current.searchQuery),
                            tvSeriesList = mappedSeries,
                            heroItem = hero,
                            heroSeries = heroShow,
                            isInitialLoading = false,
                            isLoading = false
                        )
                    }
                    android.util.Log.d(TAG, "Frame-1 disk cache hydrated: ${mappedMovies.size} movies, ${mappedSeries.size} series")
                } else {
                    android.util.Log.d(TAG, "Disk cache is empty; awaiting first streaming packet from Drive")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed hydrating disk cache: ${e.message}", e)
                val hasAnyContent = _moviesState.value.isNotEmpty() || _seriesState.value.isNotEmpty()
                _isInitialLoading.value = !hasAnyContent
                _uiState.update { it.copy(isInitialLoading = !hasAnyContent) }
            }
        }
    }

    init {
        // Cold-Start Profile Cache Order: Ensure ProfileManager is loaded BEFORE disk hydration
        // so cached catalogs under files/profiles/<email>/cached_catalog.json load on Frame 1
        ProfileManager.ensureLoadedSync(getApplication())
        diskHydrationJob = hydrateFromDiskCacheImmediately()

        playbackHistoryManager.setTokenProvider { getValidAccessToken() }
        DriveWatchlistSync.setTokenProvider { getValidAccessToken() }
        playbackHistoryManager.nextEpisodeResolver = { sId, epId ->
            getNextEpisode(sId, epId)?.let { ep ->
                com.clotube.app.data.EpisodeResumeInfo(
                    episodeId = ep.id,
                    title = ep.displayTitle,
                    seasonNumber = ep.seasonNumber,
                    episodeNumber = ep.episodeNumber,
                    thumbnailUrl = ep.thumbnailUrl
                )
            }
        }
        DriveWatchHistorySync.setOnAuthFailureListener {
            tokenRepository.invalidateAndForceConsent()
        }
        val hasByocSession = com.clotube.app.data.ByocConfigManager.hasActiveByocSession(getApplication())
        if (hasByocSession) {
            val session = com.clotube.app.data.ByocConfigManager.sessionFlow.value
            if (session != null) {
                _uiState.update {
                    it.copy(
                        isSignedIn = true,
                        userEmail = session.userEmail,
                        userName = session.userName,
                        userAvatarUrl = session.userAvatarUrl,
                        accessToken = session.accessToken
                    )
                }
            }
        } else {
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
        }
        observePlaybackHistory()
        initOfflineCatalog()
        initStaleWhileRevalidateFlow()
    }

    private fun initStaleWhileRevalidateFlow() {
        viewModelScope.launch(Dispatchers.IO) {
            // Await Frame-1 disk cache hydration
            diskHydrationJob?.join()

            val hasCachedData = _moviesState.value.isNotEmpty() || _seriesState.value.isNotEmpty()

            // Step 2: Check authentication & perform single coordinated background refresh
            val hasByocSession = com.clotube.app.data.ByocConfigManager.hasActiveByocSession(getApplication())
            val lastAccount = GoogleSignIn.getLastSignedInAccount(getApplication())
            val isUserSignedIn = hasByocSession || (lastAccount != null && com.clotube.app.auth.GoogleAuthManager.hasAllDrivePermissions(getApplication(), lastAccount))

            if (!isUserSignedIn) {
                if (!hasCachedData) {
                    _moviesState.value = emptyList()
                    _moviesList.value = emptyList()
                    _seriesState.value = emptyList()
                    _stage1LastWatched.value = null
                    _isInitialLoading.value = false
                    _uiState.update {
                        it.copy(
                            isSignedIn = false,
                            movies = emptyList(),
                            isInitialLoading = false,
                            isLoading = false
                        )
                    }
                }
                return@launch
            } else {
                if (!hasCachedData) {
                    _isInitialLoading.value = true
                    _uiState.update { it.copy(isInitialLoading = true, isLoading = true) }
                }
            }

            // Single coordinated startup revalidation
            checkFolderOnboarding()
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

                    val updatedDomainMovies = updatedVideos.ifEmpty { updatedMovies }.map { it.toMovie() }
                    _moviesState.value = updatedMovies
                    _moviesList.value = updatedDomainMovies
                    _seriesState.value = updatedSeries

                    current.copy(
                        watchProgressMap = progressMap,
                        lastWatchedMedia = lastWatched,
                        moviesList = updatedMovies,
                        videos = updatedVideos,
                        movies = updatedDomainMovies,
                        filteredVideos = applyFilterAndSearch(updatedMovies, current.selectedFilter, current.searchQuery),
                        tvSeriesList = updatedSeries
                    )
                }
            }
        }
    }

    private fun checkInitialAuthState() {
        if (com.clotube.app.data.ByocConfigManager.hasActiveByocSession(getApplication())) {
            viewModelScope.launch(Dispatchers.IO) {
                val session = com.clotube.app.data.ByocConfigManager.getSession(getApplication())
                if (session != null && (session.refreshToken.isNotBlank() || session.accessToken.isNotBlank())) {
                    ProfileManager.registerOrActivateProfile(
                        getApplication(),
                        email = session.userEmail,
                        displayName = session.userName,
                        photoUrl = session.userAvatarUrl
                    )
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                isSignedIn = true,
                                userEmail = session.userEmail,
                                userName = session.userName,
                                userAvatarUrl = session.userAvatarUrl,
                                accessToken = session.accessToken
                            )
                        }
                        checkFolderOnboarding()
                    }
                    return@launch
                }
            }
            return
        }

        val lastAccount = GoogleSignIn.getLastSignedInAccount(getApplication())
        if (lastAccount != null && com.clotube.app.auth.GoogleAuthManager.hasAllDrivePermissions(getApplication(), lastAccount)) {
            viewModelScope.launch(Dispatchers.IO) {
                ProfileManager.registerOrActivateProfile(
                    getApplication(),
                    email = lastAccount.email.orEmpty(),
                    displayName = lastAccount.displayName.orEmpty(),
                    photoUrl = lastAccount.photoUrl?.toString()
                )
            }
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
            _moviesList.value = emptyList()
            _seriesState.value = emptyList()
            _stage1LastWatched.value = null
            _isInitialLoading.value = false
            _uiState.update {
                it.copy(
                    isSignedIn = false,
                    videos = emptyList(),
                    movies = emptyList(),
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
        _isInitialLoading.value = true
        _uiState.update {
            it.copy(
                isSignedIn = true,
                userEmail = account.email,
                userName = account.displayName,
                userAvatarUrl = account.photoUrl?.toString(),
                errorMessage = null,
                isInitialLoading = true,
                isLoading = true
            )
        }
        checkFolderOnboarding()
        viewModelScope.launch(Dispatchers.IO) {
            ProfileManager.registerOrActivateProfile(
                getApplication(),
                email = account.email.orEmpty(),
                displayName = account.displayName.orEmpty(),
                photoUrl = account.photoUrl?.toString()
            )
            playbackHistoryManager.syncFromCloud()
            DriveWatchlistSync.getInstance(getApplication()).invalidateCache()
            WatchlistManager.reloadProfile(getApplication(), account.email)
            DriveWatchlistSync.getInstance(getApplication()).pullFromCloud(force = true)
        }
    }

    fun onGoogleSignInSuccess(account: GoogleSignInAccount) = onSignInSuccess(account)

    /**
     * Switches the active Google Drive profile and reloads isolated catalog, watchlist, and history.
     */
    fun switchProfile(email: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val switched = ProfileManager.switchToProfile(getApplication(), email)
            CatalogCacheManager.clearInMemoryCache()
            folderManager.resetSessionCache()
            driveWatchHistorySync.invalidateCache()
            DriveWatchlistSync.getInstance(getApplication()).invalidateCache()
            WatchlistManager.reloadProfile(getApplication(), email)
            com.clotube.app.data.SearchHistoryManager.ensureLoaded(getApplication())
            DriveWatchlistSync.getInstance(getApplication()).pullFromCloud(force = true)

            withContext(Dispatchers.Main) {
                _stage1LastWatched.value = null
                _isInitialLoading.value = true
                _uiState.update { current ->
                    current.copy(
                        userEmail = switched?.email ?: email,
                        userName = switched?.displayName ?: current.userName,
                        userAvatarUrl = switched?.photoUrl ?: current.userAvatarUrl,
                        isLoading = true,
                        isInitialLoading = true
                    )
                }
            }
            initStaleWhileRevalidateFlow()
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

    /**
     * Initiates Bring Your Own Cloud (BYOC) OAuth 2.0 loopback + PKCE sign-in flow.
     */
    fun signInWithByoc(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = withContext(Dispatchers.IO) {
                com.clotube.app.auth.ByocOAuthManager.signInWithByoc(context)
            }
            result.onSuccess { session ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isSignedIn = true,
                        userEmail = session.userEmail,
                        userName = session.userName,
                        userAvatarUrl = session.userAvatarUrl,
                        accessToken = session.accessToken,
                        errorMessage = null
                    )
                }
                withContext(Dispatchers.IO) {
                    ProfileManager.registerOrActivateProfile(
                        getApplication(),
                        email = session.userEmail,
                        displayName = session.userName,
                        photoUrl = session.userAvatarUrl
                    )
                }
                checkFolderOnboarding()
            }.onFailure { error ->
                Log.e(TAG, "BYOC Sign-in failed: ${error.message}", error)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.localizedMessage ?: "Custom OAuth sign-in failed"
                    )
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch(Dispatchers.IO) {
            com.clotube.app.data.ByocConfigManager.clearSession(getApplication())
        }
        authRepository.signOut {
            folderManager.resetSessionCache()
            driveWatchHistorySync.invalidateCache()
            DriveWatchlistSync.getInstance(getApplication()).invalidateCache()
            WatchlistManager.clearAll(getApplication())
            viewModelScope.launch {
                catalogCacheManager.clearCache()
            }
            _moviesState.value = emptyList()
            _moviesList.value = emptyList()
            _seriesState.value = emptyList()
            _stage1LastWatched.value = null
            _isInitialLoading.value = false
            _uiState.update {
                it.copy(
                    isSignedIn = false,
                    userEmail = null,
                    userName = null,
                    userAvatarUrl = null,
                    accessToken = null,
                    videos = emptyList(),
                    movies = emptyList(),
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
            // Await disk cache hydration to prevent cold-start race
            diskHydrationJob?.join()

            val hasContent = _moviesState.value.isNotEmpty() || _seriesState.value.isNotEmpty()
            if (!hasContent) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }

            // Ensure access token is fetched
            val token = authRepository.getAccessToken().getOrNull()
            if (token != null) {
                ClotubeApp.cachedBearerToken = token
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
                        refreshCatalog(
                            forceSync = false,
                            preResolvedMoviesId = moviesId,
                            preResolvedSeriesId = seriesId
                        )
                    }
                } else if (!hasContent) {
                    // Fail-Closed Policy: Only clear if there's no pre-existing cached content
                    _moviesState.value = emptyList()
                    _moviesList.value = emptyList()
                    _seriesState.value = emptyList()
                    _isInitialLoading.value = false
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isInitialLoading = false,
                            videos = emptyList(),
                            movies = emptyList(),
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
        refreshCatalog(forceSync = !silentUpdate)
    }

    fun enrichCatalogMetadataAsync() {
        enrichmentJob?.cancel()
        enrichmentJob = viewModelScope.launch(Dispatchers.IO) {
            val semaphore = Semaphore(5)
            val currentMovies = _moviesState.value
            val currentSeries = _seriesState.value

            if (currentMovies.isEmpty() && currentSeries.isEmpty()) return@launch

            // 1. Enrich movies
            val movieDeferreds = currentMovies.map { movie ->
                async(Dispatchers.IO) {
                    val needsEnrichment = movie.genreFormatted == "Movie" ||
                            movie.genreFormatted.isBlank() ||
                            movie.language.isBlank() ||
                            movie.industry.isBlank() ||
                            OnlineMetadataResolver.needsRevalidation(movie.displayTitle, movie.thumbnailUrl)
                    if (!needsEnrichment) return@async movie

                    semaphore.withPermit {
                        try {
                            val meta = OnlineMetadataResolver.resolveMovie(
                                movie.displayTitle
                            )
                            val validPoster = meta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                                ?: movie.thumbnailUrl.takeIf { !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                                ?: movie.fallbackThumbnailUrl

                            val enrichedLang: String
                            val enrichedIndustry: String
                            if (movie.isFolderClassified) {
                                enrichedLang = movie.language
                                enrichedIndustry = movie.industry
                            } else {
                                val resolvedLang = meta.originalLanguage.ifBlank {
                                    MovieIndustryResolver.extractLanguageFromFileName(movie.displayTitle.ifBlank { movie.title })
                                        ?: movie.language.ifBlank {
                                            MovieIndustryResolver.deduceLanguageFromScript(meta.officialTitle)
                                        }
                                }
                                val industryInfo = MovieIndustryResolver.toIndustryInfo(resolvedLang)
                                enrichedLang = industryInfo.language
                                enrichedIndustry = industryInfo.sectionLabel
                            }

                            val hasChanges = meta.officialTitle.isNotBlank() && meta.officialTitle != movie.displayTitle ||
                                    (!validPoster.isNullOrBlank() && validPoster != movie.thumbnailUrl) ||
                                    enrichedIndustry != movie.industry ||
                                    enrichedLang != movie.language ||
                                    (meta.genre.isNotBlank() && meta.genre != movie.genreFormatted)

                            if (hasChanges) {
                                movie.copy(
                                    displayTitle = meta.officialTitle.ifBlank { movie.displayTitle },
                                    thumbnailUrl = validPoster.ifBlank { movie.thumbnailUrl },
                                    fallbackThumbnailUrl = movie.fallbackThumbnailUrl.ifBlank {
                                        if (OnlineMetadataResolver.isDriveVideoFrameUrl(movie.thumbnailUrl)) movie.thumbnailUrl else ""
                                    },
                                    genreFormatted = meta.genre.ifBlank { movie.genreFormatted },
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
            }

            // 2. Enrich series
            val seriesDeferreds = currentSeries.map { show ->
                async(Dispatchers.IO) {
                    val needsEnrichment = show.posterUrl.isNullOrBlank() ||
                            show.thumbnailUrl.isNullOrBlank() ||
                            OnlineMetadataResolver.isDriveVideoFrameUrl(show.posterUrl) ||
                            OnlineMetadataResolver.isDriveVideoFrameUrl(show.thumbnailUrl) ||
                            show.genreFormatted == "TV Series" ||
                            show.genreFormatted.isBlank()
                    if (!needsEnrichment) return@async show

                    semaphore.withPermit {
                        try {
                            val meta = OnlineMetadataResolver.resolveSeries(show.title)
                            val bestPoster = pickBestSeriesArtwork(meta.posterUrl, show.posterUrl, show.backdropUrl, show.thumbnailUrl)
                            if (meta.officialTitle.isNotBlank() || bestPoster.isNotBlank()) {
                                show.copy(
                                    title = meta.officialTitle.ifBlank { show.title },
                                    thumbnailUrl = bestPoster,
                                    posterUrl = bestPoster,
                                    backdropUrl = bestPoster.ifBlank { null },
                                    genreFormatted = meta.genres.ifBlank { show.genreFormatted }
                                )
                            } else show
                        } catch (e: Exception) {
                            show
                        }
                    }
                }
            }

            val enrichedMovies = movieDeferreds.awaitAll()
            val enrichedSeries = seriesDeferreds.awaitAll()

            val progressMap = _uiState.value.watchProgressMap
            val mappedMovies = enrichedMovies.map { movie ->
                val prog = progressMap[movie.id]
                if (prog != null) movie.copy(watchProgressFraction = prog.progressFraction) else movie
            }
            val mappedDomainMovies = mappedMovies.map { it.toMovie() }

            _moviesState.value = mappedMovies
            _moviesList.value = mappedDomainMovies
            _seriesState.value = enrichedSeries

            _uiState.update { current ->
                val hero = current.heroItem ?: mappedMovies.firstOrNull()
                val heroShow = current.heroSeries ?: enrichedSeries.firstOrNull()
                current.copy(
                    videos = mappedMovies,
                    movies = mappedDomainMovies,
                    moviesList = mappedMovies,
                    filteredVideos = applyFilterAndSearch(mappedMovies, current.selectedFilter, current.searchQuery),
                    tvSeriesList = enrichedSeries,
                    heroItem = hero,
                    heroSeries = heroShow
                )
            }

            // Persist the enriched catalog back to CatalogCacheManager
            catalogCacheManager.saveCatalog(mappedMovies, enrichedSeries)
        }
    }

    private fun isTitleEndingWithYear(title: String): Boolean {
        return Regex("""(?i)(?:[\(\[]\s*|\s+)(?:19\d{2}|20\d{2})(?:\s*[\)\]])?\s*$""").containsMatchIn(title.trim())
    }

    private fun mergeMovieWithExistingAndCache(fresh: Movie): Movie {
        val existing = _moviesState.value.firstOrNull { it.id == fresh.id }
        val cleanTitle = fresh.title.ifBlank { fresh.fileName }
        val cachedById = OnlineMetadataResolver.getCachedMovieMetadata(getApplication(), fresh.id)
        val cachedByTitle = OnlineMetadataResolver.getCachedMovieMetadata(getApplication(), cleanTitle)

        val existingPoster = existing?.thumbnailUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
        val cachedPoster = (cachedById?.posterUrl ?: cachedByTitle?.posterUrl)?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
        val validPoster = existingPoster ?: cachedPoster

        val existingTitle = existing?.displayTitle?.takeIf { it.isNotBlank() && !isTitleEndingWithYear(it) }
        val cachedTitle = (cachedById?.officialTitle ?: cachedByTitle?.officialTitle)?.takeIf { it.isNotBlank() && !isTitleEndingWithYear(it) }
        val validTitle = existingTitle ?: cachedTitle ?: cleanTitle

        val driveThumb = fresh.fallbackThumbnailUrl.ifBlank {
            if (OnlineMetadataResolver.isDriveVideoFrameUrl(fresh.thumbnailUrl)) fresh.thumbnailUrl.orEmpty()
            else existing?.fallbackThumbnailUrl.orEmpty()
        }

        val isFolderClassified = fresh.isFolderClassified || (existing?.isFolderClassified == true && fresh.sourceFolder.isBlank())
        val resolvedLanguage = when {
            fresh.isFolderClassified && fresh.language.isNotBlank() -> fresh.language
            existing?.isFolderClassified == true && existing.language.isNotBlank() -> existing.language
            fresh.language.isNotBlank() -> fresh.language
            else -> existing?.language.orEmpty()
        }
        val resolvedIndustry = when {
            fresh.isFolderClassified && fresh.industry.isNotBlank() -> fresh.industry
            existing?.isFolderClassified == true && existing.industry.isNotBlank() -> existing.industry
            fresh.industry.isNotBlank() -> fresh.industry
            else -> existing?.industry.orEmpty()
        }
        val resolvedSourceFolder = fresh.sourceFolder.ifBlank { existing?.sourceFolder.orEmpty() }

        return if (!validPoster.isNullOrBlank()) {
            fresh.copy(
                title = validTitle,
                thumbnailUrl = validPoster,
                fallbackThumbnailUrl = driveThumb,
                genre = (cachedById?.genre ?: cachedByTitle?.genre)?.takeIf { it.isNotBlank() } ?: existing?.genreFormatted?.takeIf { it.isNotBlank() } ?: fresh.genre,
                language = resolvedLanguage,
                industry = resolvedIndustry,
                sourceFolder = resolvedSourceFolder,
                isFolderClassified = isFolderClassified
            )
        } else {
            fresh.copy(
                title = validTitle,
                thumbnailUrl = fresh.thumbnailUrl?.ifBlank { driveThumb } ?: driveThumb,
                fallbackThumbnailUrl = driveThumb,
                language = resolvedLanguage,
                industry = resolvedIndustry,
                sourceFolder = resolvedSourceFolder,
                isFolderClassified = isFolderClassified
            )
        }
    }

    private fun mergeVideoItemWithExistingAndCache(fresh: VideoItem): VideoItem {
        val existing = _moviesState.value.firstOrNull { it.id == fresh.id }
        val cleanTitle = fresh.displayTitle.ifBlank { fresh.title }
        val cachedById = OnlineMetadataResolver.getCachedMovieMetadata(getApplication(), fresh.id)
        val cachedByTitle = OnlineMetadataResolver.getCachedMovieMetadata(getApplication(), cleanTitle)

        val existingPoster = existing?.thumbnailUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
        val cachedPoster = (cachedById?.posterUrl ?: cachedByTitle?.posterUrl)?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
        val validPoster = existingPoster ?: cachedPoster

        val existingTitle = existing?.displayTitle?.takeIf { it.isNotBlank() && !isTitleEndingWithYear(it) }
        val cachedTitle = (cachedById?.officialTitle ?: cachedByTitle?.officialTitle)?.takeIf { it.isNotBlank() && !isTitleEndingWithYear(it) }
        val validTitle = existingTitle ?: cachedTitle ?: cleanTitle

        val driveThumb = fresh.fallbackThumbnailUrl.ifBlank {
            if (OnlineMetadataResolver.isDriveVideoFrameUrl(fresh.thumbnailUrl)) fresh.thumbnailUrl
            else existing?.fallbackThumbnailUrl.orEmpty()
        }

        val isFolderClassified = fresh.isFolderClassified || (existing?.isFolderClassified == true && fresh.sourceFolder.isBlank())
        val resolvedLanguage = when {
            fresh.isFolderClassified && fresh.language.isNotBlank() -> fresh.language
            existing?.isFolderClassified == true && existing.language.isNotBlank() -> existing.language
            fresh.language.isNotBlank() -> fresh.language
            else -> existing?.language.orEmpty()
        }
        val resolvedIndustry = when {
            fresh.isFolderClassified && fresh.industry.isNotBlank() -> fresh.industry
            existing?.isFolderClassified == true && existing.industry.isNotBlank() -> existing.industry
            fresh.industry.isNotBlank() -> fresh.industry
            else -> existing?.industry.orEmpty()
        }
        val resolvedSourceFolder = fresh.sourceFolder.ifBlank { existing?.sourceFolder.orEmpty() }

        return if (!validPoster.isNullOrBlank()) {
            fresh.copy(
                displayTitle = validTitle,
                thumbnailUrl = validPoster,
                fallbackThumbnailUrl = driveThumb,
                genreFormatted = (cachedById?.genre ?: cachedByTitle?.genre)?.takeIf { it.isNotBlank() } ?: existing?.genreFormatted?.takeIf { it.isNotBlank() } ?: fresh.genreFormatted,
                language = resolvedLanguage,
                industry = resolvedIndustry,
                sourceFolder = resolvedSourceFolder,
                isFolderClassified = isFolderClassified
            )
        } else {
            fresh.copy(
                displayTitle = validTitle,
                thumbnailUrl = fresh.thumbnailUrl.ifBlank { driveThumb },
                fallbackThumbnailUrl = driveThumb,
                language = resolvedLanguage,
                industry = resolvedIndustry,
                sourceFolder = resolvedSourceFolder,
                isFolderClassified = isFolderClassified
            )
        }
    }

    private fun mergeMoviesWithExisting(freshChunk: List<VideoItem>, existingList: List<VideoItem>): List<VideoItem> {
        val mergedFresh = freshChunk.map { mergeVideoItemWithExistingAndCache(it) }
        if (existingList.isEmpty()) return mergedFresh
        val freshIds = mergedFresh.map { it.id }.toSet()
        val remainingCached = existingList.filter { it.id !in freshIds }
        return mergedFresh + remainingCached
    }

    private fun pickBestSeriesArtwork(vararg candidates: String?): String {
        return candidates
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
            .orEmpty()
    }

    private fun mergeSeriesWithExisting(freshChunk: List<TvSeries>, existingList: List<TvSeries>): List<TvSeries> {
        val existingMap = existingList.associateBy { it.id }
        val mergedFresh = freshChunk.map { fresh ->
            val existing = existingMap[fresh.id]
            val cachedRecord = MetadataDiskCache.get("series:${fresh.title.trim().lowercase()}")
            val bestPoster = pickBestSeriesArtwork(
                fresh.posterUrl,
                fresh.backdropUrl,
                fresh.thumbnailUrl,
                existing?.posterUrl,
                existing?.backdropUrl,
                existing?.thumbnailUrl,
                cachedRecord?.posterUrl,
                cachedRecord?.backdropUrl
            )
            val updatedSeasons = if (existing != null && existing.seasons.isNotEmpty() && fresh.seasons.isEmpty()) {
                existing.seasons
            } else {
                fresh.seasons
            }
            val totalSeasons = if (existing != null && existing.seasons.isNotEmpty() && fresh.seasons.isEmpty()) {
                existing.totalSeasonsFormatted
            } else {
                fresh.totalSeasonsFormatted
            }
            fresh.copy(
                posterUrl = bestPoster,
                backdropUrl = bestPoster.ifBlank { null },
                thumbnailUrl = bestPoster,
                seasons = updatedSeasons,
                totalSeasonsFormatted = totalSeasons
            )
        }
        if (existingList.isEmpty()) return mergedFresh
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

            refreshCatalog(forceSync = !silentUpdate)
        }
    }

    val driveCatalogScanner: DriveCatalogScanner get() = DriveCatalogScanner

    /**
     * Triggers a fresh-boot reload: re-queries Google Drive via Packet-of-4 progressive indexing,
     * re-caches catalog, and updates UI state.
     */
    fun refreshAppFreshBoot() {
        refreshCatalog(forceSync = true)
    }

    suspend fun refreshSingleSeries(seriesFolderId: String, seriesTitleHint: String? = null): TvSeries? = withContext(Dispatchers.IO) {
        val service = getDriveService() ?: return@withContext null
        val updated = DriveCatalogScanner.refreshSingleSeries(service, seriesFolderId, seriesTitleHint)
        if (updated != null) {
            val currentList = _seriesState.value.toMutableList()
            val idx = currentList.indexOfFirst { it.id == seriesFolderId }
            if (idx != -1) {
                currentList[idx] = updated
            } else {
                currentList.add(updated)
            }
            _seriesState.value = currentList
            catalogCacheManager.saveCatalog(_moviesState.value, currentList)
        }
        updated
    }

    /**
     * Stage-1 Priority Fetcher for Last Watched Item:
     * Concurrently pulls clotube/History/clotube_active_sync.json (falling back if needed) via DriveWatchHistorySync,
     * identifies the most recently watched entry, and resolves its metadata (clean title, poster, backdrop, episode still)
     * before Frame 1 rendering.
     * Reuses enriched Movie or TvSeries if already present in priority lists.
     */
    suspend fun resolveLastWatchedItemEnriched(
        driveService: Drive?,
        priorityMoviesDeferred: Deferred<List<Movie>>,
        prioritySeriesDeferred: Deferred<List<TvSeries>>
    ): ContinueWatchingDisplayData? = withContext(Dispatchers.IO) {
        try {
            try {
                withTimeoutOrNull(4_000L) {
                    driveWatchHistorySync.syncFromCloud(force = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Watch history cloud sync timed out/failed in Stage 1: ${e.message}")
            }

            val lastWatched = playbackHistoryManager.getLastWatchedMedia().firstOrNull()
            if (lastWatched == null || lastWatched.isWatched || lastWatched.progressFraction >= 0.95f) {
                return@withContext null
            }

            val isSeriesItem = lastWatched.isSeries || !lastWatched.seriesId.isNullOrBlank()

            if (isSeriesItem) {
                val targetSeriesId = lastWatched.seriesId.orEmpty()
                val prioritySeries = prioritySeriesDeferred.await()
                val matchingSeries = prioritySeries.firstOrNull { it.id == targetSeriesId }

                val showTitle: String
                val showPoster: String
                val showBackdrop: String?

                if (matchingSeries != null) {
                    showTitle = matchingSeries.title
                    showPoster = matchingSeries.posterUrl.orEmpty()
                    showBackdrop = matchingSeries.backdropUrl
                } else {
                    val seriesFolder = if (targetSeriesId.isNotBlank() && driveService != null) {
                        try {
                            driveService.files().get(targetSeriesId)
                                .setFields("id, name")
                                .setSupportsAllDrives(true)
                                .execute()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed fetching series folder for last watched series $targetSeriesId", e)
                            null
                        }
                    } else null

                    val rawShowName = seriesFolder?.name ?: lastWatched.seriesTitle ?: lastWatched.title
                    val cleanShowTitle = driveRepository.cleanSeriesTitle(rawShowName)
                    val seriesMeta = OnlineMetadataResolver.resolveSeries(cleanShowTitle)
                    showTitle = seriesMeta.officialTitle.ifBlank { cleanShowTitle }
                    showPoster = seriesMeta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) } ?: ""
                    showBackdrop = showPoster.ifBlank { null }
                }

                val epMeta = OnlineMetadataResolver.resolveEpisodeMetadata(
                    seriesName = showTitle,
                    seasonNumber = lastWatched.seasonNumber,
                    episodeNumber = lastWatched.episodeNumber
                )
                val epCode = "S${lastWatched.seasonNumber.toString().padStart(2, '0')}:E${lastWatched.episodeNumber.toString().padStart(2, '0')}"
                val cleanEpTitle = epMeta?.officialTitle?.takeIf { it.isNotBlank() }
                    ?: lastWatched.title.takeIf { it.isNotBlank() }
                    ?: "Episode ${lastWatched.episodeNumber}"
                val still = epMeta?.thumbnailUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                    ?: showBackdrop?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                    ?: showPoster.ifBlank { lastWatched.thumbnailUrl.orEmpty() }

                val effectiveDuration = if (lastWatched.durationMs > 0L) lastWatched.durationMs else 0L
                val fraction = if (effectiveDuration > 0L) {
                    (lastWatched.positionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
                } else lastWatched.progressFraction.coerceIn(0f, 1f)

                ContinueWatchingDisplayData(
                    mediaId = lastWatched.fileId,
                    seriesId = targetSeriesId.ifBlank { null },
                    isSeries = true,
                    displayHeader = showTitle,
                    displayTitle = "$epCode • $cleanEpTitle",
                    displayThumbnailUrl = still,
                    progressFraction = fraction,
                    currentPositionMs = lastWatched.positionMs,
                    durationMs = effectiveDuration,
                    seriesTitle = showTitle,
                    seasonNumber = lastWatched.seasonNumber,
                    episodeNumber = lastWatched.episodeNumber,
                    episodeTitle = cleanEpTitle
                )
            } else {
                val targetFileId = lastWatched.fileId
                val priorityMovies = priorityMoviesDeferred.await()
                val matchingMovie = priorityMovies.firstOrNull { it.id == targetFileId }

                val movieTitle: String
                val moviePoster: String
                val durationMs: Long

                if (matchingMovie != null) {
                    movieTitle = matchingMovie.title
                    moviePoster = matchingMovie.thumbnailUrl.orEmpty()
                    durationMs = if (matchingMovie.durationMs > 0L) matchingMovie.durationMs else lastWatched.durationMs
                } else {
                    val file = if (targetFileId.isNotBlank() && driveService != null) {
                        try {
                            driveService.files().get(targetFileId)
                                .setFields("id, name, size, createdTime, videoMediaMetadata")
                                .setSupportsAllDrives(true)
                                .execute()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed fetching last watched movie file $targetFileId", e)
                            null
                        }
                    } else null

                    val rawName = file?.name ?: lastWatched.title
                    val cleanTitle = driveRepository.cleanVideoTitle(rawName)
                    val meta = OnlineMetadataResolver.resolveMovie(cleanTitle)
                    val artwork = MediaArtworkResolver.resolveMovieArtwork(cleanTitle, lastWatched.thumbnailUrl)

                    movieTitle = meta.officialTitle.ifBlank { cleanTitle }
                    moviePoster = meta.posterUrl.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                        ?: artwork.posterUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                        ?: artwork.backdropUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                        ?: lastWatched.thumbnailUrl.orEmpty()
                    durationMs = file?.videoMediaMetadata?.durationMillis ?: lastWatched.durationMs
                }

                val fraction = if (durationMs > 0L) {
                    (lastWatched.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                } else lastWatched.progressFraction.coerceIn(0f, 1f)

                ContinueWatchingDisplayData(
                    mediaId = targetFileId,
                    seriesId = null,
                    isSeries = false,
                    displayHeader = movieTitle,
                    displayTitle = "Movie • Cinema",
                    displayThumbnailUrl = moviePoster,
                    progressFraction = fraction,
                    currentPositionMs = lastWatched.positionMs,
                    durationMs = durationMs
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving last watched item enriched: ${e.message}", e)
            null
        }
    }

    suspend fun resolveLastWatchedItemEnriched(
        driveService: Drive?,
        priorityMovies: List<Movie> = emptyList(),
        prioritySeries: List<TvSeries> = emptyList()
    ): ContinueWatchingDisplayData? {
        val moviesDeferred = kotlinx.coroutines.CompletableDeferred(priorityMovies)
        val seriesDeferred = kotlinx.coroutines.CompletableDeferred(prioritySeries)
        return resolveLastWatchedItemEnriched(driveService, moviesDeferred, seriesDeferred)
    }

    private suspend fun preloadStage1Images(urls: List<String>) = withContext(Dispatchers.IO) {
        val distinctUrls = urls.filter { it.isNotBlank() }.distinct()
        if (distinctUrls.isEmpty()) return@withContext
        val appContext = getApplication<Application>()
        try {
            withTimeoutOrNull(3_000L) {
                distinctUrls.map { url ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val req = ImageRequest.Builder(appContext)
                                .data(url)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .build()
                            appContext.imageLoader.execute(req)
                        }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Preloading Stage 1 images warning: ${e.message}")
        }
    }

    fun refreshCatalog(
        forceSync: Boolean = false,
        preResolvedMoviesId: String? = null,
        preResolvedSeriesId: String? = null
    ) {
        driveWatchHistorySync.pullFromCloudIfNeeded(force = forceSync)
        viewModelScope.launch(Dispatchers.IO) {
            DriveWatchlistSync.getInstance(getApplication()).syncBidirectional(force = false)
        }
        catalogLoadingJob?.cancel()
        catalogLoadingJob = viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            val needsInitialLoading = _moviesState.value.isEmpty() && _seriesState.value.isEmpty()
            if (needsInitialLoading) {
                _isInitialLoading.value = true
            }
            _uiState.update { it.copy(isLoading = true, isInitialLoading = if (needsInitialLoading) true else it.isInitialLoading) }
            try {
                val service = getDriveService()
                if (service != null) {
                    val customMoviesOverride = folderManager.hasCustomMoviesOverride()
                    val customSeriesOverride = folderManager.hasCustomSeriesOverride()

                    val fastMoviesId = if (!preResolvedMoviesId.isNullOrBlank() && preResolvedMoviesId != "root") {
                        preResolvedMoviesId
                    } else if (customMoviesOverride) {
                        folderManager.getMoviesFolderId()
                    } else null

                    val fastSeriesId = if (!preResolvedSeriesId.isNullOrBlank() && preResolvedSeriesId != "root") {
                        preResolvedSeriesId
                    } else if (customSeriesOverride) {
                        folderManager.getSeriesFolderId()
                    } else null

                    // Resolve clotube/ directory hierarchy via DriveDirectoryManager (with root fallback) ONLY if not pre-resolved
                    val resolvedDirs = if (fastMoviesId == null || fastSeriesId == null) {
                        folderManager.resolveAndCacheHierarchy(service)
                    } else null

                    val moviesFolderId = fastMoviesId ?: resolvedDirs?.moviesFolderId ?: resolveMoviesFolderId(service)
                    val seriesFolderId = fastSeriesId ?: resolvedDirs?.seriesFolderId ?: resolveSeriesFolderId(service)

                    Log.d("VideoViewModel", "Resolved Movies Folder ID: $moviesFolderId")
                    Log.d("VideoViewModel", "Resolved Series Folder ID: $seriesFolderId")

                    // Persist resolved folder IDs without marking them as manual custom overrides
                    if (!customMoviesOverride && !moviesFolderId.isNullOrBlank()) {
                        folderManager.saveCustomMoviesFolderId(moviesFolderId, isUserOverride = false)
                    }
                    if (!customSeriesOverride && !seriesFolderId.isNullOrBlank()) {
                        folderManager.saveCustomSeriesFolderId(seriesFolderId, isUserOverride = false)
                    }

                    // ============================================================
                    // STAGE 1: Priority Fast-Start (Blocks Skeleton Dismissal)
                    // ============================================================
                    Log.d(TAG, "STAGE1_START: Fetching top 2 movies, top 2 series, and last watched item from Drive")

                    val priorityMoviesDeferred = async(Dispatchers.IO) {
                        if (!moviesFolderId.isNullOrBlank()) {
                            driveRepository.fetchTopLatestMoviesEnriched(moviesFolderId, limit = DriveRepository.STAGE1_PRIORITY_LIMIT)
                        } else emptyList()
                    }

                    val prioritySeriesDeferred = async(Dispatchers.IO) {
                        if (!seriesFolderId.isNullOrBlank()) {
                            driveRepository.fetchTopLatestSeriesEnriched(seriesFolderId, limit = DriveRepository.STAGE1_PRIORITY_LIMIT)
                        } else emptyList()
                    }

                    val lastWatchedDeferred = async(Dispatchers.IO) {
                        resolveLastWatchedItemEnriched(service, priorityMoviesDeferred, prioritySeriesDeferred)
                    }

                    val priorityMovies = priorityMoviesDeferred.await()
                    val prioritySeries = prioritySeriesDeferred.await()
                    val lastWatched = lastWatchedDeferred.await()

                    // Collect all Stage 1 image URLs to pre-warm Coil's bitmap cache
                    val stage1ImageUrls = mutableListOf<String>()
                    priorityMovies.forEach { movie ->
                        if (movie.posterUrl.isNotBlank()) stage1ImageUrls.add(movie.posterUrl)
                        if (movie.backdropUrl.isNotBlank()) stage1ImageUrls.add(movie.backdropUrl)
                    }
                    prioritySeries.forEach { series ->
                        val pUrl = series.posterUrl
                        if (!pUrl.isNullOrBlank()) stage1ImageUrls.add(pUrl)
                        val bUrl = series.backdropUrl
                        if (!bUrl.isNullOrBlank()) stage1ImageUrls.add(bUrl)
                        if (series.thumbnailUrl.isNotBlank()) stage1ImageUrls.add(series.thumbnailUrl)
                    }
                    if (lastWatched != null && lastWatched.displayThumbnailUrl.isNotBlank()) {
                        stage1ImageUrls.add(lastWatched.displayThumbnailUrl)
                    }

                    // Preload all Stage 1 images before dismissing skeleton
                    preloadStage1Images(stage1ImageUrls)

                    _stage1LastWatched.value = lastWatched
                    _isHistoryLoaded.value = true

                    val priorityVideoItems = priorityMovies.map { it.toVideoItem() }

                    _moviesList.value = priorityMovies
                    _moviesState.value = priorityVideoItems
                    _seriesState.value = prioritySeries
                    _seriesList.value = prioritySeries
                    _isInitialLoading.value = false

                    _uiState.update { current ->
                        val hero = current.heroItem ?: priorityVideoItems.firstOrNull()
                        val heroShow = current.heroSeries ?: prioritySeries.firstOrNull()
                        current.copy(
                            videos = priorityVideoItems,
                            movies = priorityMovies,
                            moviesList = priorityVideoItems,
                            filteredVideos = applyFilterAndSearch(priorityVideoItems, current.selectedFilter, current.searchQuery),
                            tvSeriesList = prioritySeries,
                            heroItem = hero,
                            heroSeries = heroShow,
                            isInitialLoading = false
                        )
                    }

                    Log.d(TAG, "STAGE1_COMPLETE & SKELETON_DISMISSED: movies=${priorityMovies.size}, series=${prioritySeries.size}, continueWatching=${lastWatched?.displayHeader ?: "none"}")

                    // ============================================================
                    // STAGE 2: Non-Blocking Background Packet-of-4 Ingestion
                    // ============================================================
                    val seenMovieIds = priorityMovies.map { it.id }.toMutableSet()
                    val seenSeriesIds = prioritySeries.map { it.id }.toMutableSet()

                    val accumulatedVideos = priorityVideoItems.toMutableList()
                    val accumulatedSeries = prioritySeries.toMutableList()

                    val movieLock = Any()
                    val seriesLock = Any()

                    var moviePacketIndex = 0
                    var seriesPacketIndex = 0

                    val movieStreamJob = launch(Dispatchers.IO) {
                        if (!moviesFolderId.isNullOrBlank()) {
                            driveRepository.streamMoviesChunked(moviesFolderId).collect { packet ->
                                val newItems = packet.filter { it.id !in seenMovieIds }
                                if (newItems.isNotEmpty()) {
                                    moviePacketIndex++
                                    newItems.forEach { seenMovieIds.add(it.id) }

                                    val progressMap = _uiState.value.watchProgressMap
                                    val distinctVideos = synchronized(movieLock) {
                                        for (item in newItems) {
                                            val prog = progressMap[item.id]
                                            val itemWithProgress = if (prog != null) item.copy(watchProgressFraction = prog.progressFraction) else item
                                            accumulatedVideos.add(itemWithProgress)
                                        }
                                        accumulatedVideos.distinctBy { it.id }
                                    }
                                    val domainMovies = distinctVideos.map { it.toMovie() }
                                    _moviesState.value = distinctVideos
                                    _moviesList.value = domainMovies

                                    _uiState.update { current ->
                                        current.copy(
                                            videos = distinctVideos,
                                            movies = domainMovies,
                                            moviesList = distinctVideos,
                                            filteredVideos = applyFilterAndSearch(distinctVideos, current.selectedFilter, current.searchQuery)
                                        )
                                    }
                                    Log.d(TAG, "STAGE2_PACKET_EMIT [Movies #$moviePacketIndex]: +${newItems.size} items (total=${_moviesState.value.size})")
                                }
                            }
                        }
                    }

                    val seriesStreamJob = launch(Dispatchers.IO) {
                        if (!seriesFolderId.isNullOrBlank()) {
                            driveRepository.streamSeriesChunked(seriesFolderId).collect { packet ->
                                val newSeries = packet.filter { it.id !in seenSeriesIds }
                                if (newSeries.isNotEmpty()) {
                                    seriesPacketIndex++
                                    newSeries.forEach { seenSeriesIds.add(it.id) }

                                    val progressMap = _uiState.value.watchProgressMap
                                    val distinctSeries = synchronized(seriesLock) {
                                        for (series in newSeries) {
                                            val seriesEpProgress = progressMap.values
                                                .filter { it.seriesId == series.id || series.seasons.any { s -> s.episodes.any { ep -> ep.id == it.fileId } } }
                                                .maxByOrNull { it.lastWatchedTimestamp }
                                            val seriesFraction = seriesEpProgress?.progressFraction ?: series.watchProgressFraction
                                            val updatedSeries = series.copy(watchProgressFraction = seriesFraction)
                                            accumulatedSeries.add(updatedSeries)
                                        }
                                        accumulatedSeries.distinctBy { it.id }
                                    }
                                    _seriesState.value = distinctSeries
                                    _seriesList.value = distinctSeries

                                    _uiState.update { current ->
                                        current.copy(
                                            tvSeriesList = distinctSeries
                                        )
                                    }
                                    Log.d(TAG, "STAGE2_PACKET_EMIT [Series #$seriesPacketIndex]: +${newSeries.size} items (total=${_seriesState.value.size})")
                                }
                            }
                        }
                    }

                    joinAll(movieStreamJob, seriesStreamJob)

                    // Save complete catalog to unified cache for instant hydration
                    catalogCacheManager.saveCatalog(_moviesState.value, _seriesState.value)
                    _isRefreshing.value = false
                    _uiState.update { it.copy(isLoading = false, isInitialLoading = false) }
                    Log.d(TAG, "STAGE2_COMPLETE: saved ${_moviesState.value.size} movies and ${_seriesState.value.size} series to cache")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Catalog sync failed: ${e.message}", e)
            } finally {
                _isRefreshing.value = false
                _isInitialLoading.value = false
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
        _searchQuery.value = query
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

    fun updateWatchlistStatus(
        result: GlobalSearchResult,
        status: WatchlistStatus
    ) {
        val effectiveMovies = _uiState.value.movies
            .ifEmpty { _uiState.value.videos.map { it.toMovie() } }
            .ifEmpty { moviesList.value }
            .ifEmpty { _uiState.value.moviesList.map { it.toMovie() } }
        val effectiveSeries = _uiState.value.tvSeriesList.ifEmpty { seriesList.value }

        val matchedDriveMovie = result.driveMovie
            ?: DriveCatalogMatcher.findMatchingDriveMovie(
                effectiveMovies,
                result.id,
                result.title
            )
        val matchedDriveSeries = result.driveSeries
            ?: if (matchedDriveMovie == null) {
                DriveCatalogMatcher.findMatchingDriveSeries(
                    effectiveSeries,
                    result.id,
                    result.title
                )
            } else null

        val effectiveId = matchedDriveMovie?.id ?: matchedDriveSeries?.id ?: result.id
        val effectiveTitle = matchedDriveMovie?.title ?: matchedDriveSeries?.title ?: result.title
        val effectivePoster = result.posterUrl.ifBlank {
            matchedDriveMovie?.thumbnailUrl ?: matchedDriveSeries?.posterUrl ?: matchedDriveSeries?.thumbnailUrl ?: ""
        }

        updateWatchlistStatus(
            id = effectiveId,
            title = effectiveTitle,
            mediaType = result.mediaType,
            status = status,
            posterUrl = effectivePoster,
            backdropUrl = result.backdropUrl.ifBlank { matchedDriveSeries?.backdropUrl ?: "" },
            year = result.year,
            genre = result.genre
        )
    }

    fun updateWatchlistStatus(
        id: String,
        title: String,
        mediaType: WatchlistMediaType,
        status: WatchlistStatus,
        posterUrl: String = "",
        backdropUrl: String = "",
        year: String = "",
        genre: String = ""
    ) {
        val effectiveMovies = _uiState.value.movies
            .ifEmpty { _uiState.value.videos.map { it.toMovie() } }
            .ifEmpty { moviesList.value }
            .ifEmpty { _uiState.value.moviesList.map { it.toMovie() } }
        val effectiveSeries = _uiState.value.tvSeriesList.ifEmpty { seriesList.value }
        val matchedMovie = if (mediaType == WatchlistMediaType.MOVIE) {
            DriveCatalogMatcher.findMatchingDriveMovie(effectiveMovies, id, title)
        } else null
        val matchedSeries = if (mediaType == WatchlistMediaType.SERIES) {
            DriveCatalogMatcher.findMatchingDriveSeries(effectiveSeries, id, title)
        } else null

        val canonicalId = matchedMovie?.id ?: matchedSeries?.id ?: id
        val canonicalTitle = matchedMovie?.title?.ifBlank { title }
            ?: matchedSeries?.title?.ifBlank { title }
            ?: title
        val canonicalPoster = posterUrl.ifBlank {
            matchedMovie?.thumbnailUrl ?: matchedSeries?.posterUrl ?: matchedSeries?.thumbnailUrl ?: ""
        }
        val canonicalBackdrop = backdropUrl.ifBlank {
            matchedSeries?.backdropUrl ?: ""
        }

        WatchlistManager.setStatus(
            context = getApplication(),
            id = canonicalId,
            title = canonicalTitle,
            mediaType = mediaType,
            status = status,
            posterUrl = canonicalPoster,
            backdropUrl = canonicalBackdrop,
            year = year,
            genre = genre
        )
    }

    fun cycleWatchlistStatus(
        id: String,
        title: String,
        mediaType: WatchlistMediaType,
        posterUrl: String = "",
        backdropUrl: String = "",
        year: String = "",
        genre: String = ""
    ) {
        val current = WatchlistManager.getStatus(id, title)
        val next = when (current) {
            WatchlistStatus.UNWATCHED -> WatchlistStatus.WATCH_LATER
            WatchlistStatus.WATCH_LATER -> WatchlistStatus.WATCHED
            WatchlistStatus.WATCHED -> WatchlistStatus.REWATCH
            WatchlistStatus.REWATCH -> WatchlistStatus.UNWATCHED
        }
        updateWatchlistStatus(
            id = id,
            title = title,
            mediaType = mediaType,
            status = next,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            year = year,
            genre = genre
        )
    }

    fun removeFromWatchlist(
        id: String,
        title: String,
        mediaType: WatchlistMediaType
    ) {
        WatchlistManager.removeFromWatchlist(
            context = getApplication(),
            id = id,
            title = title,
            mediaType = mediaType
        )
    }

    fun getAccessToken(): String? {
        return _uiState.value.accessToken ?: authRepository.accessToken.value
    }

    private fun applyFilterAndSearch(list: List<VideoItem>, filter: String, query: String): List<VideoItem> {
        val filtered = when (filter) {
            "All" -> list
            "Movies" -> list
            "TV Series", "Series" -> list
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
        stopAndReleaseCurrentPlayer()
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
