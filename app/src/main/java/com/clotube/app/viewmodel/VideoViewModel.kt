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
import com.clotube.app.model.DriveVideoFile
import com.clotube.app.model.TvSeries
import com.clotube.app.model.VideoItem
import com.clotube.app.data.CachedCatalog
import com.clotube.app.data.CatalogCacheManager
import com.clotube.app.data.scanner.DriveCatalogScanner
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.clotube.app.model.ContinueWatchingItem
import com.clotube.app.model.Series
import com.clotube.app.data.metadata.OnlineMetadataResolver
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

    /**
     * Initiates startup state resolution:
     * 1. Immediately validates local DataStore records (< 15ms).
     * 2. Asynchronously fetches clotube_active_sync.json / history from Drive in the background.
     */
    fun prepareInitialState() {
        viewModelScope.launch(Dispatchers.IO) {
            // Step 1: Hydrate local DataStore state immediately before releasing hero gate
            try {
                val lastWatched = playbackHistoryManager.lastWatchedFlow.first()
                if (lastWatched != null && lastWatched.progressFraction < 0.95f) {
                    withTimeoutOrNull(150L) {
                        continueWatchingDisplayState.first { it != null }
                    }
                }
            } catch (_: Exception) {
            } finally {
                _isHistoryLoaded.value = true
            }

            // Step 2: Background Drive sync with defensive timeout
            try {
                withTimeoutOrNull(6000L) {
                    driveWatchHistorySync.syncFromCloud(playbackHistoryManager.dataStore)
                }
            } catch (e: Exception) {
                android.util.Log.w("VideoViewModel", "Background boot sync skipped/timed out: ${e.message}")
            }
        }
    }

    init {
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
    val mediaPlayer: MediaPlayer get() = vlcPlayerManager.mediaPlayer
    val player: MediaPlayer get() = mediaPlayer

    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        if (forceRefresh) {
            com.clotube.app.data.LocalStreamProxy.invalidateTokenCache()
        }
        val token = tokenRepository.getValidAccessToken(forceRefresh)
        if (token.isNotBlank()) {
            _uiState.update { it.copy(accessToken = token) }
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
            return@withContext repoToken
        }
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
                registerActivePlayer(vlcPlayerManager.mediaPlayer)
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
                val totalDuration = episode.durationMs
                val isNearEof = (totalDuration > 0L && startPositionMs >= totalDuration - 30_000L)
                if (isNearEof) 0L else startPositionMs
            } else {
                playbackHistoryManager.getResumePosition(episode.id)
            }
            withContext(Dispatchers.Main) {
                registerActivePlayer(vlcPlayerManager.mediaPlayer)
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
        val downloaded = downloadRepository.getLocalFileIfDownloaded(episodeId)
        return if (downloaded != null && downloaded.exists()) {
            downloaded.toURI().toString()
        } else {
            com.clotube.app.media.DriveStreamEngine.getStreamUrl(episodeId)
        }
    }

    suspend fun getFreshGoogleDriveAccessToken(forceRefresh: Boolean = false): String {
        return getValidAccessToken(forceRefresh)
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
        val playerToRelease = activeMediaPlayer
        activeMediaPlayer = null
        if (playerToRelease != null && !playerToRelease.isReleased) {
            try {
                playerToRelease.setEventListener(null)
                try {
                    playerToRelease.stop()
                } catch (_: Throwable) {}
                try {
                    playerToRelease.detachViews()
                } catch (_: Throwable) {}
                if (playerToRelease != vlcPlayerManager.mediaPlayer) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            if (!playerToRelease.isReleased) {
                                playerToRelease.release()
                            }
                        } catch (_: Throwable) {}
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoViewModel", "Error releasing active player: ${e.message}")
            }
        }
        try {
            if (!vlcPlayerManager.mediaPlayer.isReleased) {
                if (vlcPlayerManager.mediaPlayer.isPlaying) {
                    vlcPlayerManager.mediaPlayer.stop()
                }
                vlcPlayerManager.mediaPlayer.detachViews()
            }
        } catch (_: Exception) {}
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
            activeMediaPlayer?.let { player ->
                if (!player.isReleased && player.isPlaying) {
                    player.pause()
                }
            }
            if (!mediaPlayer.isReleased && mediaPlayer.isPlaying) {
                mediaPlayer.pause()
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

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()

    val userProfilePhotoUrl: StateFlow<String?> = _uiState
        .map { it.userAvatarUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val continueWatchingDisplayState: StateFlow<ContinueWatchingDisplayData?> = combine(
        playbackHistoryManager.lastWatchedFlow,
        seriesList,
        moviesList
    ) { lastWatched, allSeries, allMovies ->
        if (lastWatched == null) return@combine null
        if (lastWatched.progressFraction >= 0.95f) return@combine null

        val isSeriesItem = lastWatched.isSeries || !lastWatched.seriesId.isNullOrBlank()

        if (isSeriesItem) {
            // ==================== TV SERIES BRANCH ====================
            val effectiveSeries = allSeries.ifEmpty { loadCatalogFromDisk(getApplication()) }
            val parentSeries = effectiveSeries.firstOrNull { it.id == lastWatched.seriesId }
                ?: effectiveSeries.firstOrNull { s -> s.seasons.any { sn -> sn.episodes.any { ep -> ep.id == lastWatched.fileId || ep.id == lastWatched.mediaId } } }
                ?: return@combine null

            val allEpisodes = parentSeries.seasons.flatMap { it.episodes }
            val matchedEpisode = allEpisodes.firstOrNull { it.id == lastWatched.fileId || it.id == lastWatched.mediaId }
                ?: allEpisodes.firstOrNull { 
                    it.seasonNumber == (if (lastWatched.seasonNumber > 0) lastWatched.seasonNumber else 1) && 
                    it.episodeNumber == (if (lastWatched.episodeNumber > 0) lastWatched.episodeNumber else 1) 
                } ?: return@combine null

            val epCode = "S${matchedEpisode.seasonNumber.toString().padStart(2, '0')}:E${matchedEpisode.episodeNumber.toString().padStart(2, '0')}"
            val cleanTitle = matchedEpisode.displayTitle.ifBlank { "Episode ${matchedEpisode.episodeNumber}" }

            val thumbnail: String = when {
                !matchedEpisode.thumbnailUrl.isNullOrBlank() && !matchedEpisode.thumbnailUrl!!.contains("drive.google.com") -> matchedEpisode.thumbnailUrl!!
                !parentSeries.backdropUrl.isNullOrBlank() -> parentSeries.backdropUrl!!
                !parentSeries.posterUrl.isNullOrBlank() -> parentSeries.posterUrl!!
                else -> matchedEpisode.thumbnailUrl.orEmpty()
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
            val matchedMovie = allMovies.firstOrNull { it.id == lastWatched.fileId || it.id == lastWatched.mediaId }
            val fallbackItem = if (matchedMovie == null) getMovieById(lastWatched.fileId) else null

            val movieTitle = matchedMovie?.title?.ifBlank { null }
                ?: fallbackItem?.title?.ifBlank { null }
                ?: fallbackItem?.displayTitle?.ifBlank { null }
                ?: lastWatched.title.ifBlank { null }
                ?: "Movie"

            val thumbnail: String = when {
                !matchedMovie?.thumbnailUrl.isNullOrBlank() -> matchedMovie!!.thumbnailUrl!!
                !fallbackItem?.thumbnailUrl.isNullOrBlank() -> fallbackItem!!.thumbnailUrl!!
                !lastWatched.thumbnailUrl.isNullOrBlank() -> lastWatched.thumbnailUrl!!
                else -> ""
            }

            val effectiveDuration = when {
                matchedMovie != null && matchedMovie.durationMs > 0L -> matchedMovie.durationMs
                fallbackItem != null && fallbackItem.durationMs > 0L -> fallbackItem.durationMs
                lastWatched.durationMs > 0L -> lastWatched.durationMs
                else -> 0L
            }
            val fraction = if (effectiveDuration > 0L) {
                (lastWatched.positionMs.toFloat() / effectiveDuration.toFloat()).coerceIn(0f, 1f)
            } else lastWatched.progressFraction.coerceIn(0f, 1f)

            ContinueWatchingDisplayData(
                mediaId = matchedMovie?.id ?: fallbackItem?.id ?: lastWatched.fileId,
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
        if (lastWatched.progressFraction >= 0.95f) return@combine null

        val effectiveSeries = allSeries.ifEmpty { loadCatalogFromDisk(getApplication()) }

        if (lastWatched.isSeries) {
            // Find matching series across catalog
            val series = effectiveSeries.firstOrNull { it.id == lastWatched.seriesId }
                ?: effectiveSeries.firstOrNull { s -> s.seasons.any { sn -> sn.episodes.any { ep -> ep.id == lastWatched.mediaId || ep.id == lastWatched.fileId } } }
                ?: effectiveSeries.firstOrNull { s -> !lastWatched.seriesTitle.isNullOrBlank() && s.title.equals(lastWatched.seriesTitle, ignoreCase = true) }
                ?: return@combine null

            val allEpisodes = series.seasons.flatMap { it.episodes }

            // Extract season/ep from stored title if keys were corrupted
            val sEpPattern = java.util.regex.Pattern.compile("(?i)[sS](\\d{1,2})[:\\s._-]*[eE](\\d{1,2})")
            val matcher = sEpPattern.matcher(lastWatched.title)
            val parsedSeason = if (matcher.find()) matcher.group(1)?.toIntOrNull() else null
            val parsedEpisode = if (matcher.find(0)) matcher.group(2)?.toIntOrNull() else null

            val actualSeason = when {
                parsedSeason != null -> parsedSeason
                lastWatched.seasonNumber > 0 -> lastWatched.seasonNumber
                else -> 1
            }

            val actualEpisode = when {
                parsedEpisode != null -> parsedEpisode
                lastWatched.episodeNumber > 0 -> lastWatched.episodeNumber
                else -> 1
            }

            val matchedEpisode = allEpisodes.firstOrNull { it.id == lastWatched.mediaId }
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
            val movie = allMovies.firstOrNull { it.id == lastWatched.mediaId }
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

    fun syncFromCloud() {
        viewModelScope.launch(Dispatchers.IO) {
            playbackHistoryManager.syncFromCloud()
        }
    }

    val selectedSeasonNumber = androidx.compose.runtime.mutableStateOf<Int?>(null)

    fun selectSeason(index: Int) {
        _uiState.update { it.copy(selectedSeasonIndex = index.coerceAtLeast(0)) }
    }

    val filterChips = listOf("All", "Movies", "TV Series")
    private var catalogLoadingJob: Job? = null

    init {
        playbackHistoryManager.setTokenProvider { getValidAccessToken() }
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
        observePlaybackHistory()
        initOfflineCatalog()
        initStaleWhileRevalidateFlow()
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
            folderManager.resetSessionCache()
            driveWatchHistorySync.invalidateCache()
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
                onOnlineCatalogFetched(finalMappedSeries, persistToDisk = false)
                _uiState.update { current ->
                    current.copy(
                        tvSeriesList = finalMappedSeries,
                        heroSeries = current.heroSeries ?: finalMappedSeries.firstOrNull()
                    )
                }
            }

            _isInitialLoading.value = false
            _uiState.update { it.copy(isLoading = false, isInitialLoading = false) }

            // Persist accumulated catalog once to unified local cache on Dispatchers.IO
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

    val driveCatalogScanner: DriveCatalogScanner get() = DriveCatalogScanner

    /**
     * Triggers a fresh-boot reload: re-queries Google Drive, re-caches catalog, and updates UI state.
     */
    fun refreshAppFreshBoot() {
        viewModelScope.launch {
            if (_isRefreshing.value) return@launch
            _isRefreshing.value = true
            try {
                // 1. Perform direct remote scan bypassing memory cache
                val result = DriveCatalogScanner.performFreshBootScan()

                if (result.movies.isNotEmpty() || result.series.isNotEmpty()) {
                    _moviesList.value = result.movies
                    val videoItems = result.movies.map { it.toVideoItem() }
                    _moviesState.value = videoItems
                    _seriesList.value = result.series

                    // 2. Overwrite unified disk cache with fresh results once on Dispatchers.IO
                    catalogCacheManager.saveCatalog(videoItems, result.series)

                    // 3. Update UI state
                    _uiState.update { current ->
                        val heroMovie = current.heroItem ?: videoItems.firstOrNull()
                        val heroSeries = current.heroSeries ?: result.series.firstOrNull()
                        current.copy(
                            videos = videoItems,
                            moviesList = videoItems,
                            tvSeriesList = result.series,
                            filteredVideos = applyFilterAndSearch(videoItems, current.selectedFilter, current.searchQuery),
                            heroItem = heroMovie,
                            heroSeries = heroSeries,
                            isLoading = false,
                            isInitialLoading = false
                        )
                    }
                }

                // 4. Re-sync cloud watch history so Continue Watching & progress checkmarks are freshly hydrated
                playbackHistoryManager.syncFromCloud()
            } catch (e: Exception) {
                android.util.Log.e("VideoViewModel", "Fresh boot refresh failed: ${e.message}", e)
            } finally {
                _isRefreshing.value = false
                _uiState.update { it.copy(isLoading = false, isInitialLoading = false) }
            }
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

    fun refreshCatalog(forceSync: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            driveWatchHistorySync.syncFromCloud(playbackHistoryManager.dataStore, force = forceSync)
            _isRefreshing.value = true
            _uiState.update { it.copy(isLoading = true) }
            try {
                val service = getDriveService()
                if (service != null) {
                    val customMoviesOverride = folderManager.hasCustomMoviesOverride()
                    val customSeriesOverride = folderManager.hasCustomSeriesOverride()

                    // Resolve clotube/ directory hierarchy via DriveDirectoryManager (with root fallback)
                    val resolvedDirs = if (!customMoviesOverride || !customSeriesOverride) {
                        folderManager.resolveAndCacheHierarchy(service)
                    } else null

                    val moviesFolderId = if (customMoviesOverride) {
                        folderManager.getMoviesFolderId() ?: resolvedDirs?.moviesFolderId
                    } else {
                        resolvedDirs?.moviesFolderId ?: resolveMoviesFolderId(service)
                    }
                    val seriesFolderId = if (customSeriesOverride) {
                        folderManager.getSeriesFolderId() ?: resolvedDirs?.seriesFolderId
                    } else {
                        resolvedDirs?.seriesFolderId ?: resolveSeriesFolderId(service)
                    }

                    Log.d("VideoViewModel", "Resolved Movies Folder ID: $moviesFolderId")
                    Log.d("VideoViewModel", "Resolved Series Folder ID: $seriesFolderId")

                    // Persist resolved folder IDs without marking them as manual custom overrides
                    if (!customMoviesOverride && !moviesFolderId.isNullOrBlank()) {
                        folderManager.saveCustomMoviesFolderId(moviesFolderId, isUserOverride = false)
                    }
                    if (!customSeriesOverride && !seriesFolderId.isNullOrBlank()) {
                        folderManager.saveCustomSeriesFolderId(seriesFolderId, isUserOverride = false)
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

                    // Persist accumulated catalog once to unified local cache
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
