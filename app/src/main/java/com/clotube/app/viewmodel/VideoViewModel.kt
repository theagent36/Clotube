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
import com.clotube.app.data.ContinueWatchingSnapshotStore
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
import com.clotube.app.data.AndroidWatchHistoryManager
import com.clotube.app.data.DriveCatalogMatcher
import com.clotube.app.data.DriveSyncWorker
import com.clotube.app.data.DriveWatchHistorySync
import com.clotube.app.data.DriveWatchlistSync
import com.clotube.app.data.MediaArtworkResolver
import com.clotube.app.data.MetadataDiskCache
import com.clotube.app.data.WatchHistoryPayloadV2
import com.clotube.app.data.WatchlistManager
import com.clotube.app.model.WatchlistMediaType
import com.clotube.app.model.WatchlistStatus
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.clotube.app.media.VlcPlayerManager
import com.clotube.app.model.Episode
import org.videolan.libvlc.MediaPlayer
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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

typealias EpisodeItem = com.clotube.app.model.Episode

@Keep
@androidx.compose.runtime.Immutable
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
}

@androidx.compose.runtime.Immutable
sealed interface HeroSlotState {
    data object Resolving : HeroSlotState
    data class ContinueWatching(val item: ContinueWatchingDisplayData) : HeroSlotState
    data class FeaturedFallback(val movie: Movie) : HeroSlotState
}

@Keep
data class VideoUiState(
    val videos: List<VideoItem> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val moviesList: List<VideoItem> = emptyList(),
    val filteredVideos: List<VideoItem> = emptyList(),
    val tvSeriesList: List<TvSeries> = emptyList(),
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

    private val _heroSlotState = MutableStateFlow<HeroSlotState>(HeroSlotState.Resolving)
    val heroSlotState: StateFlow<HeroSlotState> = _heroSlotState.asStateFlow()

    private val hasResolvedContinueWatchingLatch = AtomicBoolean(false)
    private val hasConfirmedEmptyHistoryLatch = AtomicBoolean(false)
    private val metadataRevision = MutableStateFlow(0L)
    private val heroReconcileMutex = Mutex()
    private var cwEnrichmentJob: Job? = null
    private var cwEnrichmentMediaId: String? = null

    val savedProfiles: StateFlow<List<SavedAccountProfile>> = ProfileManager.savedProfiles

    /**
     * Initiates startup state resolution:
     * 1. Awaits Frame-1 local disk hydration (snapshot + catalog).
     * 2. Authoritative timestamp-fenced pull of clotube_active_sync.json & clotube_archive.json from Drive.
     * 3. Reconciles HeroSlotState and pre-warms Hero artwork before unlocking HomeScreen.
     */
    suspend fun prepareInitialState() = withContext(Dispatchers.IO) {
        try {
            WatchlistManager.ensureLoaded(getApplication())
            ProfileManager.ensureLoaded(getApplication())
            MetadataDiskCache.ensureLoaded(getApplication())
        } catch (e: Exception) {
            Log.w(TAG, "Startup cache preload warning: ${e.message}")
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

        // 1. Ensure disk catalog cache + snapshot are hydrated
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

        // 2. Authoritative timestamp-fenced pull & self-healing merge from Drive (up to 9.5s)
        try {
            withTimeoutOrNull(9_500L) {
                driveWatchHistorySync.pullAndMerge(getApplication(), force = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Boot Drive history sync failed: ${e.message}", e)
        }

        // 3. Reconcile HeroSlotState & pre-warm hero artwork if available
        try {
            reconcileHeroSlotState()
            val currentHero = _heroSlotState.value
            if (currentHero is HeroSlotState.ContinueWatching && currentHero.item.displayThumbnailUrl.isBlank()) {
                withTimeoutOrNull(1_200L) {
                    cwEnrichmentJob?.join()
                }
            }
            val finalHero = _heroSlotState.value
            if (finalHero is HeroSlotState.ContinueWatching && finalHero.item.displayThumbnailUrl.isNotBlank()) {
                preloadStage1ImageWithKey(
                    url = finalHero.item.displayThumbnailUrl,
                    cacheKey = "cw_hero_${finalHero.item.mediaId}"
                )
            }
        } catch (_: Exception) {}

        // 4. Non-blocking watchlist sync after boot gate opens
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

        // 4. Check HeroSlotState or history preference
        val heroCw = (_heroSlotState.value as? HeroSlotState.ContinueWatching)?.item
        if (heroCw != null && heroCw.mediaId == safeId && !heroCw.seriesId.isNullOrBlank()) {
            return heroCw.seriesId.trim()
        }
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

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()

    val userProfilePhotoUrl: StateFlow<String?> = _uiState
        .map { it.userAvatarUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentUserAccount: StateFlow<GoogleSignInAccount?> = authRepository.currentAccount

    private data class RamInProgressCandidate(
        val mediaId: String,
        val seriesId: String?,
        val isSeries: Boolean,
        val title: String,
        val seriesTitle: String?,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val positionMs: Long,
        val durationMs: Long,
        val progressFraction: Float,
        val timestamp: Long,
        val thumbnailUrl: String
    )

    private fun selectTopInProgressFromRam(
        entries: Map<String, com.clotube.app.data.AndroidTypedEntry>,
        archivedIds: Set<String>,
        archivedItems: Map<String, com.clotube.app.data.HistoryItemEntityV2>,
        activeHeroMediaId: String?
    ): RamInProgressCandidate? {
        if (entries.isEmpty() && archivedItems.isEmpty()) return null
        val candidates = mutableListOf<RamInProgressCandidate>()
        val resumeEpisodeIds = mutableSetOf<String>()
        val activeSeenIds = mutableSetOf<String>()

        entries.keys
            .filter { it.startsWith("series_resume_") && it.endsWith("_episode_id") }
            .forEach { epKey ->
                val sId = epKey.removePrefix("series_resume_").removeSuffix("_episode_id")
                val p = "series_resume_$sId"
                val epId = (entries["${p}_episode_id"]?.value as? String)?.trim().orEmpty()
                if (epId.isNotBlank()) {
                    resumeEpisodeIds.add(epId)
                    val resumeTs = WatchHistoryPayloadV2.normalizeTimestampMs(
                        (entries["${p}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                    )
                    val epTs = WatchHistoryPayloadV2.normalizeTimestampMs(
                        (entries["${epId}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                    )
                    val ts = maxOf(resumeTs, epTs)
                    val pos = (entries["${p}_pos_ms"]?.value as? Number)?.toLong()
                        ?: (entries["${epId}_pos"]?.value as? Number)?.toLong() ?: 0L
                    val dur = (entries["${p}_dur_ms"]?.value as? Number)?.toLong()
                        ?: (entries["${epId}_dur"]?.value as? Number)?.toLong() ?: 0L
                    val frac = (entries["${p}_fraction"]?.value as? Number)?.toFloat()
                        ?: (entries["${epId}_fraction"]?.value as? Number)?.toFloat()
                        ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f

                    val explicitWatched = entries["${epId}_is_watched"]?.value as? Boolean
                    val isWatched = explicitWatched ?: (epId in archivedIds || frac >= 0.90f)
                    val minPos = if (epId == activeHeroMediaId) 0L else 3000L

                    if (!isWatched && epId !in archivedIds && frac < 0.95f && pos > minPos && ts > 0L) {
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
                            ?: ""

                        candidates.add(
                            RamInProgressCandidate(
                                mediaId = epId,
                                seriesId = sId.ifBlank { null },
                                isSeries = true,
                                title = epTitle,
                                seriesTitle = sTitle,
                                seasonNumber = sNum,
                                episodeNumber = eNum,
                                positionMs = pos,
                                durationMs = dur,
                                progressFraction = frac,
                                timestamp = ts,
                                thumbnailUrl = thumb
                            )
                        )
                    }
                }
            }

        entries.keys
            .filter { it.endsWith("_timestamp") && !it.startsWith("series_resume_") && !it.startsWith("global_") }
            .forEach { tsKey ->
                val fileId = tsKey.removeSuffix("_timestamp")
                if (fileId.isNotBlank() && fileId !in resumeEpisodeIds) {
                    activeSeenIds.add(fileId)
                    val ts = WatchHistoryPayloadV2.normalizeTimestampMs(
                        (entries[tsKey]?.value as? Number)?.toLong() ?: 0L
                    )
                    val pos = (entries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
                    val dur = (entries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
                    val frac = (entries["${fileId}_fraction"]?.value as? Number)?.toFloat()
                        ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
                    val explicitWatched = entries["${fileId}_is_watched"]?.value as? Boolean
                    val isWatched = explicitWatched ?: (fileId in archivedIds || frac >= 0.90f)
                    val isSeries = (entries["${fileId}_is_series"]?.value as? Boolean) ?: false
                    val sId = (entries["${fileId}_series_id"]?.value as? String)?.ifBlank { null }
                    val sNum = (entries["${fileId}_season_num"]?.value as? Number)?.toInt() ?: 0
                    val eNum = (entries["${fileId}_episode_num"]?.value as? Number)?.toInt() ?: 0
                    val title = (entries["${fileId}_title"]?.value as? String) ?: ""
                    val sTitle = (entries["${fileId}_series_title"]?.value as? String)?.ifBlank { null }
                    val thumb = (entries["${fileId}_thumbnail_url"]?.value as? String)?.takeIf { it.isNotBlank() }
                        ?: (entries["${fileId}_thumb"]?.value as? String)?.takeIf { it.isNotBlank() }
                        ?: ""
                    val minPos = if (fileId == activeHeroMediaId) 0L else 3000L

                    if (!isWatched && fileId !in archivedIds && frac < 0.95f && pos > minPos && ts > 0L) {
                        candidates.add(
                            RamInProgressCandidate(
                                mediaId = fileId,
                                seriesId = sId,
                                isSeries = isSeries || sId != null || sNum > 0 || eNum > 0,
                                title = title,
                                seriesTitle = sTitle,
                                seasonNumber = sNum,
                                episodeNumber = eNum,
                                positionMs = pos,
                                durationMs = dur,
                                progressFraction = frac,
                                timestamp = ts,
                                thumbnailUrl = thumb
                            )
                        )
                    }
                }
            }

        // Archive fallback: include any uncompleted archived items so Continue Watching & catalog enrichment cover evicted progress
        archivedItems.values.forEach { archived ->
            val aid = archived.id.trim()
            if (aid.isNotBlank() && aid !in resumeEpisodeIds && aid !in activeSeenIds) {
                val minPos = if (aid == activeHeroMediaId) 0L else 3000L
                val eligible = !archived.isWatched &&
                    (aid !in archivedIds || archived.isInProgressForProtection) &&
                    archived.progressFraction < 0.90f &&
                    archived.positionMs >= minPos &&
                    archived.timestamp > 0L
                if (eligible) {
                    candidates.add(
                        RamInProgressCandidate(
                            mediaId = aid,
                            seriesId = archived.seriesId,
                            isSeries = archived.isSeries,
                            title = archived.title,
                            seriesTitle = archived.seriesTitle,
                            seasonNumber = archived.seasonNumber ?: 0,
                            episodeNumber = archived.episodeNumber ?: 0,
                            positionMs = archived.positionMs,
                            durationMs = archived.durationMs,
                            progressFraction = archived.progressFraction,
                            timestamp = archived.timestamp,
                            thumbnailUrl = archived.thumbnailUrl.orEmpty()
                        )
                    )
                }
            }
        }

        return candidates.maxByOrNull { it.timestamp }
    }

    private fun pickNonDriveArtwork(vararg candidates: String?): String {
        return candidates
            .asSequence()
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
            .orEmpty()
    }

    private fun enrichDisplayDataLocally(
        base: ContinueWatchingDisplayData,
        allSeries: List<TvSeries>,
        allMovies: List<Movie>,
        existingHeroArtwork: String?
    ): ContinueWatchingDisplayData {
        val app = getApplication<Application>()
        if (base.isSeries) {
            val candidateSeriesTitle = base.seriesTitle.ifBlank { base.displayHeader }.trim().ifBlank { null }
            val normTarget = candidateSeriesTitle?.let { WatchlistManager.normalizeTitle(it) }.orEmpty()

            val parentSeries = allSeries.firstOrNull { !base.seriesId.isNullOrBlank() && it.id == base.seriesId }
                ?: allSeries.firstOrNull { s -> s.seasons.any { sn -> sn.episodes.any { ep -> ep.id == base.mediaId } } }
                ?: allSeries.firstOrNull { s -> !candidateSeriesTitle.isNullOrBlank() && s.title.equals(candidateSeriesTitle, ignoreCase = true) }
                ?: allSeries.firstOrNull { s ->
                    normTarget.isNotBlank() && WatchlistManager.normalizeTitle(s.title) == normTarget
                }

            val allEpisodes = parentSeries?.seasons?.flatMap { it.episodes }.orEmpty()
            val parsedSe = OnlineMetadataResolver.extractSeasonAndEpisode(base.episodeTitle.ifBlank { base.displayTitle })
            val actualSeason = when {
                base.seasonNumber > 0 -> base.seasonNumber
                parsedSe != null -> parsedSe.first
                else -> 1
            }
            val actualEpisode = when {
                base.episodeNumber > 0 -> base.episodeNumber
                parsedSe != null -> parsedSe.second
                else -> 1
            }

            val matchedEpisode = allEpisodes.firstOrNull { it.id == base.mediaId }
                ?: allEpisodes.firstOrNull { it.seasonNumber == actualSeason && it.episodeNumber == actualEpisode }

            val resolvedSeriesTitle = parentSeries?.title?.takeIf { it.isNotBlank() }
                ?: candidateSeriesTitle
                ?: "TV Series"
            val normSeries = WatchlistManager.normalizeTitle(resolvedSeriesTitle)
            val epDisk = MetadataDiskCache.get("ep_${normSeries}_s${actualSeason}e${actualEpisode}")
            val seriesDisk = MetadataDiskCache.get("series_$normSeries")
                ?: MetadataDiskCache.get("series:${resolvedSeriesTitle.trim().lowercase()}")

            val rawEpTitle = base.episodeTitle.ifBlank { base.displayTitle }
            val sanitizedRawEp = OnlineMetadataResolver.sanitizeEpisodeTitle(rawEpTitle, resolvedSeriesTitle, actualSeason, actualEpisode)
            val cleanEpTitle = epDisk?.title?.takeIf { it.isNotBlank() && !it.startsWith("Episode", ignoreCase = true) }
                ?: matchedEpisode?.displayTitle?.takeIf { it.isNotBlank() && !it.startsWith("Episode", ignoreCase = true) }
                ?: sanitizedRawEp.takeIf { it.isNotBlank() && !it.startsWith("Episode", ignoreCase = true) }
                ?: matchedEpisode?.displayTitle?.ifBlank { null }
                ?: "Episode $actualEpisode"

            val officialArt = pickNonDriveArtwork(
                epDisk?.stillUrl,
                epDisk?.posterUrl,
                matchedEpisode?.thumbnailUrl,
                parentSeries?.backdropUrl,
                parentSeries?.posterUrl,
                parentSeries?.thumbnailUrl,
                seriesDisk?.backdropUrl,
                seriesDisk?.posterUrl,
                base.displayThumbnailUrl,
                existingHeroArtwork
            )

            val epCode = "S${actualSeason.toString().padStart(2, '0')}:E${actualEpisode.toString().padStart(2, '0')}"
            val effectiveDuration = when {
                matchedEpisode != null && matchedEpisode.durationMs > 0L -> matchedEpisode.durationMs
                else -> base.durationMs
            }
            val fraction = if (effectiveDuration > 0L) {
                (base.currentPositionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
            } else {
                base.progressFraction.coerceIn(0f, 1f)
            }

            return ContinueWatchingDisplayData(
                mediaId = base.mediaId,
                seriesId = parentSeries?.id ?: base.seriesId,
                isSeries = true,
                displayHeader = resolvedSeriesTitle,
                displayTitle = "$epCode • $cleanEpTitle",
                displayThumbnailUrl = officialArt,
                progressFraction = fraction,
                currentPositionMs = base.currentPositionMs,
                durationMs = effectiveDuration,
                seriesTitle = resolvedSeriesTitle,
                seasonNumber = actualSeason,
                episodeNumber = actualEpisode,
                episodeTitle = cleanEpTitle
            )
        } else {
            val rawMovieTitle = base.displayHeader.ifBlank { base.episodeTitle }.ifBlank { "Movie" }
            val normMovieTitle = WatchlistManager.normalizeTitle(rawMovieTitle)
            val matchedMovie = allMovies.firstOrNull { it.id == base.mediaId }
                ?: allMovies.firstOrNull {
                    normMovieTitle.isNotBlank() && WatchlistManager.normalizeTitle(it.title) == normMovieTitle
                }

            val cleanTitle = matchedMovie?.title?.takeIf { it.isNotBlank() }
                ?: DriveRepository.cleanVideoTitle(rawMovieTitle)
            val cachedMeta = OnlineMetadataResolver.getCachedMovieMetadata(app, base.mediaId)
                ?: OnlineMetadataResolver.getCachedMovieMetadata(app, cleanTitle)
            val officialTitle = cachedMeta?.officialTitle?.takeIf { it.isNotBlank() } ?: cleanTitle

            val officialArt = pickNonDriveArtwork(
                cachedMeta?.posterUrl,
                matchedMovie?.thumbnailUrl,
                base.displayThumbnailUrl,
                existingHeroArtwork
            )
            val effectiveDuration = when {
                matchedMovie != null && matchedMovie.durationMs > 0L -> matchedMovie.durationMs
                else -> base.durationMs
            }
            val fraction = if (effectiveDuration > 0L) {
                (base.currentPositionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
            } else {
                base.progressFraction.coerceIn(0f, 1f)
            }

            return ContinueWatchingDisplayData(
                mediaId = base.mediaId,
                seriesId = null,
                isSeries = false,
                displayHeader = officialTitle,
                displayTitle = "Movie • Cinema",
                displayThumbnailUrl = officialArt,
                progressFraction = fraction,
                currentPositionMs = base.currentPositionMs,
                durationMs = effectiveDuration,
                seriesTitle = officialTitle,
                seasonNumber = 0,
                episodeNumber = 0,
                episodeTitle = officialTitle
            )
        }
    }

    private fun triggerCwBackgroundEnrichment(item: ContinueWatchingDisplayData) {
        val needsSeriesEnrichment = item.isSeries && (
            item.displayThumbnailUrl.isBlank() ||
                OnlineMetadataResolver.isDriveVideoFrameUrl(item.displayThumbnailUrl) ||
                item.episodeTitle.startsWith("Episode", ignoreCase = true)
            )
        val needsMovieEnrichment = !item.isSeries && (
            item.displayThumbnailUrl.isBlank() ||
                OnlineMetadataResolver.needsRevalidation(item.displayHeader, item.displayThumbnailUrl)
            )
        if (!needsSeriesEnrichment && !needsMovieEnrichment) return

        if (cwEnrichmentJob?.isActive == true && cwEnrichmentMediaId == item.mediaId) {
            return
        }
        cwEnrichmentJob?.cancel()
        cwEnrichmentMediaId = item.mediaId
        cwEnrichmentJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                if (item.isSeries) {
                    val seriesName = item.seriesTitle.ifBlank { item.displayHeader }
                    val cleanShowTitle = DriveRepository.cleanSeriesTitle(seriesName)
                    val sNum = item.seasonNumber.coerceAtLeast(1)
                    val eNum = item.episodeNumber.coerceAtLeast(1)
                    val seriesMeta = OnlineMetadataResolver.resolveSeries(cleanShowTitle)
                    val officialShowTitle = seriesMeta.officialTitle.ifBlank { cleanShowTitle }
                    val epMeta = OnlineMetadataResolver.resolveEpisode(
                        seriesName = officialShowTitle,
                        tvMazeSeriesId = seriesMeta.tvMazeId,
                        seasonNumber = sNum,
                        episodeNumber = eNum,
                        rawFileName = item.episodeTitle
                    )
                    val directEpMeta = if (epMeta.thumbnailUrl.isBlank() || OnlineMetadataResolver.isDriveVideoFrameUrl(epMeta.thumbnailUrl)) {
                        OnlineMetadataResolver.resolveEpisodeMetadata(
                            seriesName = officialShowTitle,
                            seasonNumber = sNum,
                            episodeNumber = eNum
                        )
                    } else null

                    val normSeries = WatchlistManager.normalizeTitle(officialShowTitle)
                    val resolvedEpTitle = epMeta.officialTitle.takeIf { it.isNotBlank() && !it.startsWith("Episode", ignoreCase = true) }
                        ?: directEpMeta?.officialTitle?.takeIf { it.isNotBlank() }
                        ?: item.episodeTitle
                    val resolvedArt = pickNonDriveArtwork(
                        epMeta.thumbnailUrl,
                        directEpMeta?.thumbnailUrl,
                        seriesMeta.posterUrl,
                        item.displayThumbnailUrl
                    )

                    if (resolvedArt.isNotBlank()) {
                        MetadataDiskCache.put(
                            app,
                            com.clotube.app.data.CachedMetadataRecord(
                                cacheKey = "ep_${normSeries}_s${sNum}e${eNum}",
                                title = resolvedEpTitle,
                                stillUrl = resolvedArt,
                                posterUrl = resolvedArt
                            )
                        )
                    }
                    metadataRevision.value = maxOf(metadataRevision.value + 1L, System.nanoTime())
                } else {
                    val cleanTitle = DriveRepository.cleanVideoTitle(item.displayHeader.ifBlank { item.episodeTitle })
                    val meta = OnlineMetadataResolver.resolveMovie(cleanTitle)
                    val artwork = MediaArtworkResolver.resolveMovieArtwork(cleanTitle, null)
                    val resolvedArt = pickNonDriveArtwork(
                        meta.posterUrl,
                        artwork.posterUrl,
                        artwork.backdropUrl,
                        item.displayThumbnailUrl
                    )
                    if (resolvedArt.isNotBlank()) {
                        MetadataDiskCache.put(
                            app,
                            com.clotube.app.data.CachedMetadataRecord(
                                cacheKey = "movie_${WatchlistManager.normalizeTitle(cleanTitle)}",
                                title = meta.officialTitle.ifBlank { cleanTitle },
                                posterUrl = resolvedArt,
                                backdropUrl = artwork.backdropUrl.orEmpty(),
                                year = meta.releaseYear,
                                genre = meta.genre,
                                language = meta.originalLanguage,
                                originalLanguage = meta.originalLanguage
                            )
                        )
                    }
                    metadataRevision.value = maxOf(metadataRevision.value + 1L, System.nanoTime())
                }
            } catch (e: Exception) {
                Log.w(TAG, "CW background enrichment skipped for ${item.mediaId}: ${e.message}")
            }
        }
    }

    private suspend fun reconcileHeroSlotState() = heroReconcileMutex.withLock {
        val app = getApplication<Application>()
        val entries = AndroidWatchHistoryManager.snapshotEntries()
        val archivedIds = AndroidWatchHistoryManager.snapshotArchivedIds()
        val archivedItems = AndroidWatchHistoryManager.snapshotArchivedItems()
        val allSeries = _seriesState.value
        val allMovies = _moviesList.value.ifEmpty { _moviesState.value.map { it.toMovie() } }

        val currentHero = _heroSlotState.value
        val currentHeroCw = (currentHero as? HeroSlotState.ContinueWatching)?.item

        val ramCandidate = selectTopInProgressFromRam(
            entries = entries,
            archivedIds = archivedIds,
            archivedItems = archivedItems,
            activeHeroMediaId = currentHeroCw?.mediaId
        )

        if (ramCandidate != null) {
            val baseItem = if (ramCandidate.isSeries) {
                val sNum = ramCandidate.seasonNumber.coerceAtLeast(1)
                val eNum = ramCandidate.episodeNumber.coerceAtLeast(1)
                val sTitle = ramCandidate.seriesTitle?.ifBlank { null } ?: "TV Series"
                val epCode = "S${sNum.toString().padStart(2, '0')}:E${eNum.toString().padStart(2, '0')}"
                ContinueWatchingDisplayData(
                    mediaId = ramCandidate.mediaId,
                    seriesId = ramCandidate.seriesId,
                    isSeries = true,
                    displayHeader = sTitle,
                    displayTitle = "$epCode • ${ramCandidate.title}",
                    displayThumbnailUrl = pickNonDriveArtwork(ramCandidate.thumbnailUrl),
                    progressFraction = ramCandidate.progressFraction,
                    currentPositionMs = ramCandidate.positionMs,
                    durationMs = ramCandidate.durationMs,
                    seriesTitle = sTitle,
                    seasonNumber = sNum,
                    episodeNumber = eNum,
                    episodeTitle = ramCandidate.title
                )
            } else {
                val mTitle = ramCandidate.title.ifBlank { "Movie" }
                ContinueWatchingDisplayData(
                    mediaId = ramCandidate.mediaId,
                    seriesId = null,
                    isSeries = false,
                    displayHeader = mTitle,
                    displayTitle = "Movie • Cinema",
                    displayThumbnailUrl = pickNonDriveArtwork(ramCandidate.thumbnailUrl),
                    progressFraction = ramCandidate.progressFraction,
                    currentPositionMs = ramCandidate.positionMs,
                    durationMs = ramCandidate.durationMs,
                    seriesTitle = mTitle,
                    seasonNumber = 0,
                    episodeNumber = 0,
                    episodeTitle = mTitle
                )
            }

            val existingSameIdArt = currentHeroCw?.takeIf { it.mediaId == baseItem.mediaId }?.displayThumbnailUrl
            val enrichedItem = enrichDisplayDataLocally(
                base = baseItem,
                allSeries = allSeries,
                allMovies = allMovies,
                existingHeroArtwork = existingSameIdArt
            )

            hasConfirmedEmptyHistoryLatch.set(false)
            hasResolvedContinueWatchingLatch.set(true)
            _heroSlotState.value = HeroSlotState.ContinueWatching(enrichedItem)

            val activeEmail = ContinueWatchingSnapshotStore.resolveActiveProfileEmail(app)
            val prevSnap = ContinueWatchingSnapshotStore.loadSnapshot(app, activeEmail)
            val prevItem = prevSnap.item
            val metadataOrProgressImproved = prevItem == null ||
                prevItem.mediaId != enrichedItem.mediaId ||
                prevItem.currentPositionMs != enrichedItem.currentPositionMs ||
                prevItem.durationMs != enrichedItem.durationMs ||
                prevItem.displayHeader != enrichedItem.displayHeader ||
                prevItem.displayTitle != enrichedItem.displayTitle ||
                (enrichedItem.displayThumbnailUrl.isNotBlank() && enrichedItem.displayThumbnailUrl != prevItem.displayThumbnailUrl)

            if (metadataOrProgressImproved) {
                ContinueWatchingSnapshotStore.saveSnapshot(
                    context = app,
                    profileEmail = activeEmail,
                    item = enrichedItem,
                    updatedAt = maxOf(ramCandidate.timestamp, prevSnap.updatedAt)
                )
            }

            triggerCwBackgroundEnrichment(enrichedItem)
            return@withLock
        }

        // No in-progress candidate in RAM: enforce monotonic latch rules
        if (hasResolvedContinueWatchingLatch.get()) {
            // Keep existing ContinueWatching snapshot hydrated in-place; enrich if catalog improved
            if (currentHeroCw != null) {
                val enrichedSnapshot = enrichDisplayDataLocally(
                    base = currentHeroCw,
                    allSeries = allSeries,
                    allMovies = allMovies,
                    existingHeroArtwork = currentHeroCw.displayThumbnailUrl
                )
                if (enrichedSnapshot != currentHeroCw) {
                    _heroSlotState.value = HeroSlotState.ContinueWatching(enrichedSnapshot)
                    val activeEmail = ContinueWatchingSnapshotStore.resolveActiveProfileEmail(app)
                    val prevSnap = ContinueWatchingSnapshotStore.loadSnapshot(app, activeEmail)
                    if (prevSnap.item != null && prevSnap.item.mediaId == enrichedSnapshot.mediaId) {
                        ContinueWatchingSnapshotStore.saveSnapshot(
                            context = app,
                            profileEmail = activeEmail,
                            item = enrichedSnapshot,
                            updatedAt = prevSnap.updatedAt
                        )
                    }
                }
                triggerCwBackgroundEnrichment(enrichedSnapshot)
            }
            return@withLock
        }

        if (hasConfirmedEmptyHistoryLatch.get()) {
            val firstMovie = allMovies.firstOrNull()
            if (firstMovie != null) {
                val currentFallback = (currentHero as? HeroSlotState.FeaturedFallback)?.movie
                val targetMovie = if (currentFallback != null) {
                    val refreshed = allMovies.firstOrNull { it.id == currentFallback.id } ?: firstMovie
                    if (pickNonDriveArtwork(refreshed.thumbnailUrl).isBlank() && pickNonDriveArtwork(currentFallback.thumbnailUrl).isNotBlank()) {
                        refreshed.copy(thumbnailUrl = currentFallback.thumbnailUrl)
                    } else {
                        refreshed
                    }
                } else {
                    firstMovie
                }
                _heroSlotState.value = HeroSlotState.FeaturedFallback(targetMovie)
            } else {
                _heroSlotState.value = HeroSlotState.Resolving
            }
        } else {
            _heroSlotState.value = HeroSlotState.Resolving
        }
    }

    private fun observeHeroSlotReconciliation() {
        viewModelScope.launch(Dispatchers.IO) {
            driveWatchHistorySync.lastSyncOutcome.collect { outcome ->
                if (outcome.succeeded) {
                    if (outcome.confirmedEmpty) {
                        if (outcome.remoteWasNewer) {
                            hasResolvedContinueWatchingLatch.set(false)
                        }
                        hasConfirmedEmptyHistoryLatch.set(true)
                    } else {
                        hasConfirmedEmptyHistoryLatch.set(false)
                    }
                    reconcileHeroSlotState()
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            AndroidWatchHistoryManager.localMutationClearedInProgressRevision.collect { rev ->
                if (rev > 0L) {
                    hasResolvedContinueWatchingLatch.set(false)
                    hasConfirmedEmptyHistoryLatch.set(true)
                    reconcileHeroSlotState()
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            diskHydrationJob?.join()
            combine(
                AndroidWatchHistoryManager.stateRevision,
                _moviesState,
                _seriesState,
                metadataRevision
            ) { rev, movies, series, metaRev ->
                arrayOf(rev, movies.size, series.size, metaRev)
            }.collect {
                reconcileHeroSlotState()
            }
        }
    }

    fun dismissContinueWatching(mediaId: String? = null) {
        val targetId = mediaId
            ?: (_heroSlotState.value as? HeroSlotState.ContinueWatching)?.item?.mediaId
            ?: _uiState.value.lastWatchedMedia?.fileId
        if (targetId != null) {
            deleteHistoryItem(targetId)
        }
    }

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
                val app = getApplication<Application>()
                // Cold-Start Frame-1 Order: load profile, metadata disk cache, local archive store, and local CW snapshot first
                ProfileManager.ensureLoaded(app)
                MetadataDiskCache.ensureLoaded(app)
                WatchlistManager.ensureLoaded(app)
                AndroidWatchHistoryManager.ensureArchiveLoaded(app)

                val activeEmail = ContinueWatchingSnapshotStore.resolveActiveProfileEmail(app)
                val snapshot = ContinueWatchingSnapshotStore.loadSnapshot(app, activeEmail)

                if (snapshot.item != null) {
                    hasConfirmedEmptyHistoryLatch.set(false)
                    hasResolvedContinueWatchingLatch.set(true)
                    _heroSlotState.value = HeroSlotState.ContinueWatching(snapshot.item)
                }

                val cached = catalogCacheManager.getCachedCatalog()
                val diskSeries = loadCatalogFromDisk(app)
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
                                val (q, _) = OnlineMetadataResolver.extractMovieQueryAndYear(movie.displayTitle)
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
                                val cachedMeta = OnlineMetadataResolver.getCachedMovieMetadata(app, cleanTitle.ifBlank { movie.title })
                                    ?: OnlineMetadataResolver.getCachedMovieMetadata(app, movie.id)
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

                    if (snapshot.item == null && snapshot.confirmedEmpty) {
                        hasConfirmedEmptyHistoryLatch.set(true)
                        val firstMovie = mappedDomainMovies.firstOrNull()
                        if (firstMovie != null) {
                            _heroSlotState.value = HeroSlotState.FeaturedFallback(firstMovie)
                        }
                    }

                    _uiState.update { current ->
                        current.copy(
                            videos = mappedMovies,
                            movies = mappedDomainMovies,
                            moviesList = mappedMovies,
                            filteredVideos = applyFilterAndSearch(mappedMovies, current.selectedFilter, current.searchQuery),
                            tvSeriesList = mappedSeries,
                            isInitialLoading = false,
                            isLoading = false
                        )
                    }
                    Log.d(TAG, "Frame-1 disk cache hydrated: ${mappedMovies.size} movies, ${mappedSeries.size} series, snapshot=${snapshot.item?.mediaId ?: if (snapshot.confirmedEmpty) "confirmedEmpty" else "none"}")
                } else {
                    if (snapshot.item == null && snapshot.confirmedEmpty) {
                        hasConfirmedEmptyHistoryLatch.set(true)
                    }
                    Log.d(TAG, "Disk cache is empty; awaiting first streaming packet from Drive")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed hydrating disk cache: ${e.message}", e)
                val hasAnyContent = _moviesState.value.isNotEmpty() || _seriesState.value.isNotEmpty()
                _isInitialLoading.value = !hasAnyContent
                _uiState.update { it.copy(isInitialLoading = !hasAnyContent) }
            }
        }
    }

    init {
        // Cold-Start Profile Cache Order: Ensure ProfileManager is loaded BEFORE disk hydration
        // so cached catalogs and cw_hero_snapshot.json under files/profiles/<email>/ load on Frame 1
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
        observeHeroSlotReconciliation()
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
                    hasResolvedContinueWatchingLatch.set(false)
                    hasConfirmedEmptyHistoryLatch.set(false)
                    _heroSlotState.value = HeroSlotState.Resolving
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
            hasResolvedContinueWatchingLatch.set(false)
            hasConfirmedEmptyHistoryLatch.set(false)
            _heroSlotState.value = HeroSlotState.Resolving
            _isInitialLoading.value = false
            _uiState.update {
                it.copy(
                    isSignedIn = false,
                    videos = emptyList(),
                    movies = emptyList(),
                    moviesList = emptyList(),
                    filteredVideos = emptyList(),
                    tvSeriesList = emptyList(),
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
        hasResolvedContinueWatchingLatch.set(false)
        hasConfirmedEmptyHistoryLatch.set(false)
        _heroSlotState.value = HeroSlotState.Resolving
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
            val snap = ContinueWatchingSnapshotStore.loadSnapshot(getApplication(), account.email)
            if (snap.item != null) {
                hasResolvedContinueWatchingLatch.set(true)
                _heroSlotState.value = HeroSlotState.ContinueWatching(snap.item)
            } else if (snap.confirmedEmpty) {
                hasConfirmedEmptyHistoryLatch.set(true)
            }
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
            AndroidWatchHistoryManager.clearInMemory(getApplication())
            DriveWatchlistSync.getInstance(getApplication()).invalidateCache()
            WatchlistManager.reloadProfile(getApplication(), email)
            com.clotube.app.data.SearchHistoryManager.ensureLoaded(getApplication())
            DriveWatchlistSync.getInstance(getApplication()).pullFromCloud(force = true)

            hasResolvedContinueWatchingLatch.set(false)
            hasConfirmedEmptyHistoryLatch.set(false)
            _heroSlotState.value = HeroSlotState.Resolving

            withContext(Dispatchers.Main) {
                _moviesState.value = emptyList()
                _moviesList.value = emptyList()
                _seriesState.value = emptyList()
                _isInitialLoading.value = true
                _uiState.update { current ->
                    current.copy(
                        userEmail = switched?.email ?: email,
                        userName = switched?.displayName ?: current.userName,
                        userAvatarUrl = switched?.photoUrl ?: current.userAvatarUrl,
                        videos = emptyList(),
                        movies = emptyList(),
                        moviesList = emptyList(),
                        filteredVideos = emptyList(),
                        tvSeriesList = emptyList(),
                        isLoading = true,
                        isInitialLoading = true
                    )
                }
            }
            diskHydrationJob = hydrateFromDiskCacheImmediately()
            driveWatchHistorySync.pullAndMerge(getApplication(), force = true)
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
                    val snap = ContinueWatchingSnapshotStore.loadSnapshot(getApplication(), session.userEmail)
                    if (snap.item != null) {
                        hasResolvedContinueWatchingLatch.set(true)
                        _heroSlotState.value = HeroSlotState.ContinueWatching(snap.item)
                    } else if (snap.confirmedEmpty) {
                        hasConfirmedEmptyHistoryLatch.set(true)
                    }
                    playbackHistoryManager.syncFromCloud()
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
            AndroidWatchHistoryManager.clearInMemory(getApplication())
            DriveWatchlistSync.getInstance(getApplication()).invalidateCache()
            WatchlistManager.clearAll(getApplication())
            viewModelScope.launch {
                catalogCacheManager.clearCache()
            }
            _moviesState.value = emptyList()
            _moviesList.value = emptyList()
            _seriesState.value = emptyList()
            hasResolvedContinueWatchingLatch.set(false)
            hasConfirmedEmptyHistoryLatch.set(false)
            _heroSlotState.value = HeroSlotState.Resolving
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
                            tvSeriesList = emptyList()
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
                current.copy(
                    videos = mappedMovies,
                    movies = mappedDomainMovies,
                    moviesList = mappedMovies,
                    filteredVideos = applyFilterAndSearch(mappedMovies, current.selectedFilter, current.searchQuery),
                    tvSeriesList = enrichedSeries
                )
            }
            metadataRevision.value = maxOf(metadataRevision.value + 1L, System.nanoTime())

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
            val normTitle = WatchlistManager.normalizeTitle(fresh.title)
            val cachedRecord = MetadataDiskCache.get("series_$normTitle")
                ?: MetadataDiskCache.get("series:${fresh.title.trim().lowercase()}")
            val bestPoster = pickBestSeriesArtwork(
                existing?.posterUrl,
                existing?.thumbnailUrl,
                fresh.posterUrl,
                fresh.thumbnailUrl,
                cachedRecord?.posterUrl
            )
            val bestBackdrop = pickBestSeriesArtwork(
                existing?.backdropUrl,
                fresh.backdropUrl,
                cachedRecord?.backdropUrl,
                bestPoster
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
                title = existing?.title?.takeIf { it.isNotBlank() } ?: fresh.title,
                genreFormatted = existing?.genreFormatted?.takeIf { it.isNotBlank() && it != "TV Series" } ?: fresh.genreFormatted,
                posterUrl = bestPoster,
                backdropUrl = bestBackdrop.ifBlank { null },
                thumbnailUrl = bestPoster.ifBlank { bestBackdrop },
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
        _isRefreshing.value = true
        refreshCatalog(forceSync = true)
        val activeJob = catalogLoadingJob
        if (activeJob != null) {
            activeJob.invokeOnCompletion {
                _isRefreshing.value = false
            }
        } else {
            _isRefreshing.value = false
        }
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

    private suspend fun preloadStage1ImageWithKey(url: String, cacheKey: String) = withContext(Dispatchers.IO) {
        if (url.isBlank() || OnlineMetadataResolver.isDriveVideoFrameUrl(url)) return@withContext
        val appContext = getApplication<Application>()
        runCatching {
            val req = ImageRequest.Builder(appContext)
                .data(url)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
            appContext.imageLoader.execute(req)
        }
    }

    private suspend fun preloadStage1Images(keyedUrls: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        val distinctPairs = keyedUrls
            .filter { (_, url) -> url.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(url) }
            .distinctBy { (key, url) -> "$key::$url" }
        if (distinctPairs.isEmpty()) return@withContext
        val appContext = getApplication<Application>()
        try {
            withTimeoutOrNull(3_000L) {
                distinctPairs.map { (cacheKey, url) ->
                    async(Dispatchers.IO) {
                        runCatching {
                            val req = ImageRequest.Builder(appContext)
                                .data(url)
                                .memoryCacheKey(cacheKey)
                                .diskCacheKey(cacheKey)
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

                    val existingMoviesBaseline = _moviesState.value
                    val existingSeriesBaseline = _seriesState.value

                    // ============================================================
                    // STAGE 1: Priority Fast-Start (Blocks Skeleton Dismissal)
                    // ============================================================
                    Log.d(TAG, "STAGE1_START: Fetching top 2 movies & top 2 series from Drive")

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

                    val priorityMovies = priorityMoviesDeferred.await()
                    val prioritySeries = prioritySeriesDeferred.await()

                    val priorityVideoItems = priorityMovies.map { it.toVideoItem() }
                    val mergedPriorityVideos = mergeMoviesWithExisting(priorityVideoItems, existingMoviesBaseline)
                    val mergedPrioritySeries = mergeSeriesWithExisting(prioritySeries, existingSeriesBaseline)
                    val mergedPriorityDomainMovies = mergedPriorityVideos.map { it.toMovie() }

                    // Collect canonical-keyed Stage 1 image URLs to pre-warm Coil's bitmap cache
                    val stage1ImageUrls = mutableListOf<Pair<String, String>>()
                    mergedPriorityDomainMovies.take(DriveRepository.STAGE1_PRIORITY_LIMIT).forEach { movie ->
                        val poster = movie.posterUrl
                        if (poster.isNotBlank()) stage1ImageUrls.add("movie_poster_${movie.id}" to poster)
                    }
                    mergedPrioritySeries.take(DriveRepository.STAGE1_PRIORITY_LIMIT).forEach { series ->
                        val art = pickBestSeriesArtwork(series.posterUrl, series.backdropUrl, series.thumbnailUrl)
                        if (art.isNotBlank()) stage1ImageUrls.add("series_poster_${series.id}" to art)
                    }
                    val currentHero = _heroSlotState.value
                    if (currentHero is HeroSlotState.ContinueWatching && currentHero.item.displayThumbnailUrl.isNotBlank()) {
                        stage1ImageUrls.add("cw_hero_${currentHero.item.mediaId}" to currentHero.item.displayThumbnailUrl)
                    }

                    preloadStage1Images(stage1ImageUrls)

                    _moviesList.value = mergedPriorityDomainMovies
                    _moviesState.value = mergedPriorityVideos
                    _seriesState.value = mergedPrioritySeries
                    _isInitialLoading.value = false

                    _uiState.update { current ->
                        current.copy(
                            videos = mergedPriorityVideos,
                            movies = mergedPriorityDomainMovies,
                            moviesList = mergedPriorityVideos,
                            filteredVideos = applyFilterAndSearch(mergedPriorityVideos, current.selectedFilter, current.searchQuery),
                            tvSeriesList = mergedPrioritySeries,
                            isInitialLoading = false
                        )
                    }

                    Log.d(TAG, "STAGE1_COMPLETE & SKELETON_DISMISSED: movies=${mergedPriorityVideos.size}, series=${mergedPrioritySeries.size}")

                    // ============================================================
                    // STAGE 2: Non-Blocking Background Packet-of-4 Ingestion
                    // HeroSlotState MUST NEVER flash back to Resolving or FeaturedFallback.
                    // ============================================================
                    val seenMovieIds = priorityMovies.map { it.id }.toMutableSet()
                    val seenSeriesIds = prioritySeries.map { it.id }.toMutableSet()

                    val accumulatedVideos = priorityVideoItems.map { mergeVideoItemWithExistingAndCache(it) }.toMutableList()
                    val accumulatedSeries = mergeSeriesWithExisting(prioritySeries, emptyList()).toMutableList()

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
                                            val merged = mergeVideoItemWithExistingAndCache(item)
                                            val prog = progressMap[merged.id]
                                            val itemWithProgress = if (prog != null) merged.copy(watchProgressFraction = prog.progressFraction) else merged
                                            accumulatedVideos.add(itemWithProgress)
                                        }
                                        mergeMoviesWithExisting(accumulatedVideos.distinctBy { it.id }, _moviesState.value)
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
                                        val existingNow = _seriesState.value
                                        val mergedPacket = mergeSeriesWithExisting(newSeries, existingNow)
                                            .filter { it.id in newSeries.map { ns -> ns.id }.toSet() }
                                        for (series in mergedPacket) {
                                            val seriesEpProgress = progressMap.values
                                                .filter { it.seriesId == series.id || series.seasons.any { s -> s.episodes.any { ep -> ep.id == it.fileId } } }
                                                .maxByOrNull { it.lastWatchedTimestamp }
                                            val seriesFraction = seriesEpProgress?.progressFraction ?: series.watchProgressFraction
                                            val updatedSeries = series.copy(watchProgressFraction = seriesFraction)
                                            accumulatedSeries.add(updatedSeries)
                                        }
                                        mergeSeriesWithExisting(accumulatedSeries.distinctBy { it.id }, existingNow)
                                    }
                                    _seriesState.value = distinctSeries

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

                    // Finalize complete Drive catalog while preserving official artwork & seasons from existing baseline
                    val finalMovies = if (accumulatedVideos.isNotEmpty()) {
                        val deduped = accumulatedVideos.distinctBy { it.id }
                        val finalDomain = deduped.map { it.toMovie() }
                        _moviesState.value = deduped
                        _moviesList.value = finalDomain
                        _uiState.update { current ->
                            current.copy(
                                videos = deduped,
                                movies = finalDomain,
                                moviesList = deduped,
                                filteredVideos = applyFilterAndSearch(deduped, current.selectedFilter, current.searchQuery)
                            )
                        }
                        deduped
                    } else {
                        _moviesState.value
                    }

                    val finalSeries = if (accumulatedSeries.isNotEmpty()) {
                        val dedupedSeries = mergeSeriesWithExisting(
                            accumulatedSeries.distinctBy { it.id },
                            _seriesState.value
                        ).filter { s -> accumulatedSeries.any { it.id == s.id } }
                        _seriesState.value = dedupedSeries
                        _uiState.update { current ->
                            current.copy(tvSeriesList = dedupedSeries)
                        }
                        dedupedSeries
                    } else {
                        _seriesState.value
                    }

                    // Save complete catalog to unified cache for instant hydration
                    catalogCacheManager.saveCatalog(finalMovies, finalSeries)
                    _uiState.update { it.copy(isLoading = false, isInitialLoading = false) }
                    Log.d(TAG, "STAGE2_COMPLETE: saved ${finalMovies.size} movies and ${finalSeries.size} series to cache")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Catalog sync failed: ${e.message}", e)
            } finally {
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
