package com.clotube.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import com.clotube.app.util.triggerNavigationHaptic
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.clotube.app.auth.TokenRefreshProvider
import com.clotube.app.media.BluetoothAudioKeepAlive
import com.clotube.app.media.ClotubeAudioFocusManager
import com.clotube.app.media.ClotubeMediaSessionManager
import com.clotube.app.media.NetworkMonitor
import com.clotube.app.media.applyClotubeMediaOptions
import com.clotube.app.model.Episode
import com.clotube.app.viewmodel.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.roundToInt

@androidx.annotation.Keep
data class TrackInfoItem(val id: Int, val name: String, val isSelected: Boolean)

fun calculateSafeSeekTargetMs(fraction: Float, totalDuration: Long): Long {
    if (totalDuration <= 0L) return 0L
    val rawTargetMs = (fraction * totalDuration).toLong()
    val maxSafeBoundaryMs = (totalDuration - 3000L).coerceAtLeast(0L)
    return rawTargetMs.coerceIn(0L, maxSafeBoundaryMs)
}

@Composable
fun ClotubeVideoPlayer(
    episode: Episode,
    viewModel: VideoViewModel,
    modifier: Modifier = Modifier,
    seriesId: String? = null,
    initialPositionMs: Long = 0L,
    nextEpisode: Episode? = null,
    onPreviousEpisode: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    onNavigateBack: () -> Unit = {}
) {
    ClotubeVideoPlayer(
        episode = episode,
        viewModel = viewModel,
        seriesId = seriesId,
        initialPositionMs = initialPositionMs,
        nextEpisode = nextEpisode,
        onPreviousEpisode = onPreviousEpisode,
        onNextEpisode = onNextEpisode,
        onClosePlayer = onNavigateBack,
        modifier = modifier
    )
}

@Composable
fun ClotubeVideoPlayer(
    episode: Episode,
    viewModel: VideoViewModel,
    seriesId: String? = null,
    initialPositionMs: Long = 0L,
    nextEpisode: Episode? = null,
    onPreviousEpisode: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    onClosePlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    fun isScreenVisible(): Boolean {
        return lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    var isLoading by remember(episode.id) { mutableStateOf(false) }

    val resolvedSeriesId = remember(seriesId, episode.id) {
        seriesId?.trim()?.ifBlank { null }
            ?: viewModel.getSeriesIdForEpisode(episode.id, episode.seriesTitle)
            ?: viewModel.getDefaultSeriesId()
            ?: ""
    }

    val libVLCInstance = remember(episode.id) {
        LibVLC(
            context.applicationContext,
            arrayListOf(
                "--aout=audiotrack",
                "--audio-time-stretch",
                "--network-caching=1500",
                "--file-caching=1500",
                "--live-caching=1500",
                "--no-sub-autodetect-file",
                "--no-drop-late-frames",
                "--no-skip-frames",
                "--rtsp-tcp"
            )
        )
    }

    val mediaPlayer = remember(libVLCInstance) {
        MediaPlayer(libVLCInstance)
    }

    val audioFocusManager = remember(context) {
        ClotubeAudioFocusManager(
            context = context,
            onPausePlayback = {
                if (mediaPlayer.isPlaying) {
                    try { mediaPlayer.pause() } catch (_: Exception) {}
                }
            },
            onResumePlayback = {
                if (isScreenVisible() && !mediaPlayer.isPlaying) {
                    try { mediaPlayer.play() } catch (_: Exception) {}
                }
            }
        )
    }

    var activeStreamUrl by remember(episode.id) { mutableStateOf("") }

    fun loadAndPlayStream(startPositionMs: Long = 0L, forceRefreshToken: Boolean = false) {
        coroutineScope.launch(Dispatchers.IO) {
            isLoading = true
            try {
                val downloadRepo = com.clotube.app.data.DownloadRepository(context)
                val localFile = downloadRepo.getLocalFileIfDownloaded(episode.id)

                val media = if (localFile != null && localFile.exists()) {
                    Media(libVLCInstance, Uri.fromFile(localFile)).apply {
                        addOption(":file-caching=1500")
                    }
                } else {
                    val streamUrl = viewModel.getStreamUrlForEpisode(episode.id)
                    activeStreamUrl = streamUrl
                    val token = if (forceRefreshToken) {
                        viewModel.getFreshGoogleDriveAccessToken(forceRefresh = true)
                    } else {
                        viewModel.uiState.value.accessToken?.takeIf { it.isNotBlank() }
                            ?: viewModel.getFreshGoogleDriveAccessToken(forceRefresh = false)
                    }

                    if (streamUrl.isBlank()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Stream link resolution failed", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    Media(libVLCInstance, Uri.parse(streamUrl)).apply {
                        addOption(":network-caching=1500")
                        if (!token.isNullOrBlank()) {
                            addOption(":http-header=Authorization: Bearer $token")
                        }
                    }
                }.apply {
                    val totalDuration = episode.durationMs
                    val startSec = if (totalDuration > 0L && startPositionMs >= totalDuration - 5_000L) {
                        0L
                    } else {
                        (startPositionMs / 1000L).coerceAtLeast(0L)
                    }
                    applyClotubeMediaOptions(startSec)
                    setHWDecoderEnabled(true, true)
                }

                withContext(Dispatchers.Main) {
                    mediaPlayer.stop()
                    mediaPlayer.media = media
                    media.release()

                    if (isScreenVisible()) {
                        audioFocusManager.requestAudioFocus()
                        mediaPlayer.play()
                    } else {
                        mediaPlayer.pause()
                    }
                }
            } catch (e: Exception) {
                Log.e("ClotubePlayer", "Playback launch error: ${e.message}", e)
            } finally {
                isLoading = false
            }
        }
    }

    // MediaTek Hardware Decoder & Crash Prevention (Strict Order: detachViews -> stop -> release)
    DisposableEffect(episode.id) {
        onDispose {
            audioFocusManager.abandonAudioFocus()
            try {
                val current = mediaPlayer.time
                val total = mediaPlayer.length
                if (total > 0L && current >= 1000L) {
                    viewModel.savePlaybackPositionImmediate(
                        fileId = episode.id,
                        positionMs = current,
                        durationMs = total,
                        title = episode.displayTitle,
                        seriesTitle = episode.seriesTitle.ifBlank { null },
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                        isSeries = true,
                        seriesId = resolvedSeriesId,
                        thumbnailUrl = episode.thumbnailUrl.ifBlank { null }
                    )
                }
                viewModel.playbackHistoryManager.backupToCloudImmediately()
            } catch (_: Exception) {}

            try {
                mediaPlayer.setEventListener(null)
                mediaPlayer.detachViews()
                mediaPlayer.stop()
                mediaPlayer.release()
            } catch (t: Throwable) {
                Log.e("ClotubePlayer", "Safe LibVLC release error: ${t.message}")
            }
            try {
                libVLCInstance.release()
            } catch (t: Throwable) {
                Log.e("ClotubePlayer", "Safe LibVLC release error: ${t.message}")
            }
        }
    }

    // Initial launch with progress recovery (Resets completed / already-watched episodes to 0L)
    LaunchedEffect(episode.id) {
        val initialSeek = withContext(Dispatchers.IO) {
            val savedProgress = viewModel.playbackHistoryManager.getProgressSync(episode.id)
            val isWatched = viewModel.playbackHistoryManager.isEpisodeWatched(episode.id).firstOrNull() ?: false
            val totalDurationMs = if (savedProgress != null && savedProgress.durationMs > 0L) {
                savedProgress.durationMs
            } else {
                episode.durationMs
            }
            val isNearEof = (totalDurationMs > 0L && savedProgress != null && savedProgress.positionMs >= totalDurationMs - 30_000L) ||
                    (savedProgress != null && savedProgress.progressFraction >= 0.95f)
            if (initialPositionMs > 0L) {
                initialPositionMs
            } else if (isWatched || isNearEof) {
                0L
            } else if (savedProgress != null && savedProgress.positionMs > 1000L) {
                savedProgress.positionMs
            } else {
                0L
            }
        }
        loadAndPlayStream(initialSeek)
    }

    // Periodic progress saving loop
    LaunchedEffect(episode.id, mediaPlayer) {
        while (true) {
            delay(4000L)
            if (mediaPlayer.isPlaying) {
                val current = mediaPlayer.time
                val total = mediaPlayer.length
                if (total > 0L && current >= 3000L) {
                    viewModel.playbackHistoryManager.saveProgress(
                        fileId = episode.id,
                        title = episode.displayTitle,
                        seriesTitle = episode.seriesTitle.ifBlank { null },
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                        isSeries = true,
                        seriesId = resolvedSeriesId,
                        positionMs = current,
                        durationMs = total,
                        thumbnailUrl = episode.thumbnailUrl.ifBlank { null }
                    )
                }
            }
        }
    }

    val sNum = episode.seasonNumber.toString().padStart(2, '0')
    val eNum = episode.episodeNumber.toString().padStart(2, '0')
    val formattedEpisodeTitle = "S${sNum}:E${eNum} • ${episode.displayTitle}"

    ClotubeVideoPlayer(
        mediaPlayer = mediaPlayer,
        mediaId = episode.id,
        title = formattedEpisodeTitle,
        thumbnailUrl = episode.thumbnailUrl,
        fallbackDurationMs = episode.durationMs,
        initialPositionMs = initialPositionMs,
        activeStreamUrl = activeStreamUrl,
        tokenRefreshProvider = viewModel.tokenRefreshProvider,
        modifier = modifier,
        isInitiallyFullscreen = false,
        audioFocusManager = audioFocusManager,
        onPreviousEpisode = onPreviousEpisode,
        onNextEpisode = onNextEpisode,
        nextEpisode = nextEpisode,
        onPlayNextEpisode = { nextEp ->
            onNextEpisode?.invoke()
        },
        onReloadMedia = { startPositionMs ->
            loadAndPlayStream(startPositionMs, forceRefreshToken = false)
        },
        onPlaybackEnded = {
            val total = mediaPlayer.length.coerceAtLeast(1000L)
            coroutineScope.launch(NonCancellable + Dispatchers.IO) {
                viewModel.playbackHistoryManager.markCompleted(
                    fileId = episode.id,
                    title = episode.displayTitle,
                    durationMs = total,
                    isSeries = true,
                    seriesId = resolvedSeriesId,
                    thumbnailUrl = episode.thumbnailUrl
                )
            }
        },
        onNavigateBack = onClosePlayer
    )
}

@Composable
fun ClotubeVideoPlayer(
    mediaPlayer: MediaPlayer,
    mediaId: String = "",
    title: String,
    thumbnailUrl: String? = null,
    fallbackDurationMs: Long = 0L,
    initialPositionMs: Long = 0L,
    activeStreamUrl: String = "",
    tokenRefreshProvider: TokenRefreshProvider? = null,
    modifier: Modifier = Modifier,
    isInitiallyFullscreen: Boolean = false,
    isFullscreen: Boolean = isInitiallyFullscreen,
    audioFocusManager: ClotubeAudioFocusManager? = null,
    onPreviousEpisode: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    nextEpisode: Episode? = null,
    onPlayNextEpisode: ((Episode) -> Unit)? = null,
    nextEpisodeTitle: String? = null,
    onFullscreenChanged: ((Boolean) -> Unit)? = null,
    onIsPlayingChanged: ((Boolean) -> Unit)? = null,
    onPlaybackEnded: (() -> Unit)? = null,
    onReloadMedia: ((startPositionMs: Long) -> Unit)? = null,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val effectiveTokenRefreshProvider = remember(tokenRefreshProvider, context) {
        tokenRefreshProvider ?: TokenRefreshProvider(context.applicationContext)
    }
    val view = LocalView.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val bluetoothAudioKeepAlive = remember {
        BluetoothAudioKeepAlive(coroutineScope)
    }

    DisposableEffect(bluetoothAudioKeepAlive) {
        bluetoothAudioKeepAlive.start()
        onDispose {
            bluetoothAudioKeepAlive.release()
        }
    }

    var sessionManagerRef by remember { mutableStateOf<ClotubeMediaSessionManager?>(null) }

    val effectiveAudioFocusManager = audioFocusManager ?: remember(context) {
        ClotubeAudioFocusManager(
            context = context,
            onPausePlayback = {
                bluetoothAudioKeepAlive.stop()
                mediaPlayer.let { player ->
                    if (player.isPlaying) {
                        try {
                            player.pause()
                        } catch (_: Exception) {}
                        sessionManagerRef?.updatePlaybackState(isPlaying = false, currentPositionMs = player.time)
                    }
                }
            },
            onResumePlayback = {
                // Only resume if app is still foregrounded and visible on screen
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    mediaPlayer.let { player ->
                        if (!player.isPlaying) {
                            bluetoothAudioKeepAlive.start()
                            try {
                                player.play()
                            } catch (_: Exception) {}
                            sessionManagerRef?.updatePlaybackState(isPlaying = true, currentPositionMs = player.time)
                        }
                    }
                }
            }
        )
    }

    DisposableEffect(effectiveAudioFocusManager, mediaPlayer, bluetoothAudioKeepAlive, lifecycleOwner) {
        effectiveAudioFocusManager.setPlaybackCallbacks(
            onPause = {
                bluetoothAudioKeepAlive.stop()
                mediaPlayer.let { player ->
                    if (player.isPlaying) {
                        try {
                            player.pause()
                        } catch (_: Exception) {}
                        sessionManagerRef?.updatePlaybackState(isPlaying = false, currentPositionMs = player.time)
                    }
                }
            },
            onResume = {
                // Only resume if app is still foregrounded and visible on screen
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    mediaPlayer.let { player ->
                        if (!player.isPlaying) {
                            bluetoothAudioKeepAlive.start()
                            try {
                                player.play()
                            } catch (_: Exception) {}
                            sessionManagerRef?.updatePlaybackState(isPlaying = true, currentPositionMs = player.time)
                        }
                    }
                }
            }
        )
        onDispose {}
    }

    fun requestPlaybackAudioFocus(): Boolean {
        return effectiveAudioFocusManager.requestAudioFocus()
    }

    fun abandonPlaybackAudioFocus() {
        effectiveAudioFocusManager.abandonAudioFocus()
    }

    DisposableEffect(mediaId) {
        onDispose {
            effectiveAudioFocusManager.cancelPendingResume()
            abandonPlaybackAudioFocus()
        }
    }

    var userIntendedToPlay by remember(mediaId) { mutableStateOf(true) }

    val sessionManager = remember(mediaId, onNextEpisode, onPreviousEpisode, mediaPlayer) {
        ClotubeMediaSessionManager(
            context = context,
            actionPlayPauseToggle = {
                mediaPlayer.let { player ->
                    if (player.isPlaying) {
                        userIntendedToPlay = false
                        try { player.pause() } catch (_: Exception) {}
                    } else {
                        userIntendedToPlay = true
                        requestPlaybackAudioFocus()
                        try { player.play() } catch (_: Exception) {}
                    }
                }
            },
            actionPlay = {
                mediaPlayer.let { player ->
                    userIntendedToPlay = true
                    if (!player.isPlaying) {
                        requestPlaybackAudioFocus()
                        try { player.play() } catch (_: Exception) {}
                    }
                }
            },
            actionPause = {
                mediaPlayer.let { player ->
                    userIntendedToPlay = false
                    if (player.isPlaying) {
                        try { player.pause() } catch (_: Exception) {}
                    }
                }
            },
            actionNext = onNextEpisode,
            actionPrevious = onPreviousEpisode,
            actionFastForward = {
                mediaPlayer.let { player ->
                    val maxLen = if (player.length > 0) player.length else Long.MAX_VALUE
                    player.time = (player.time + 10_000L).coerceAtMost(maxLen)
                }
            },
            actionRewind = {
                mediaPlayer.let { player ->
                    player.time = (player.time - 10_000L).coerceAtLeast(0L)
                }
            }
        )
    }

    DisposableEffect(sessionManager) {
        sessionManagerRef = sessionManager
        sessionManager.initialize()
        onDispose {
            if (sessionManagerRef == sessionManager) {
                sessionManagerRef = null
            }
            sessionManager.release()
        }
    }

    fun isScreenVisible(): Boolean {
        return lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    var isAppForegrounded by remember {
        mutableStateOf(isScreenVisible())
    }

    var internalFullscreen by remember(isInitiallyFullscreen) { mutableStateOf(isInitiallyFullscreen) }
    LaunchedEffect(isFullscreen) {
        internalFullscreen = isFullscreen
    }
    val currentFullscreen = if (onFullscreenChanged != null) isFullscreen else internalFullscreen
    var areControlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(mediaPlayer.isPlaying) }
    var isBuffering by remember { mutableStateOf(true) }
    var isSeeking by remember { mutableStateOf(false) }
    var hasEnded by remember(mediaId) { mutableStateOf(false) }
    var isFirstFrameRendered by remember(mediaId) { mutableStateOf(false) }
    var vlcVideoLayoutRef by remember { mutableStateOf<VLCVideoLayout?>(null) }
    var isViewsAttached by remember { mutableStateOf(false) }
    var autoHideJob by remember { mutableStateOf<Job?>(null) }

    // Dynamic Network Handover, 8s Buffer Underrun Stall Watchdog & State
    var lastValidPositionMs by remember(mediaId) {
        val curTime = mediaPlayer.time
        mutableLongStateOf(if (curTime > 0L) curTime else initialPositionMs)
    }
    var isReconnecting by remember { mutableStateOf(false) }
    var pendingReloadOnReconnect by remember { mutableStateOf(false) }
    var stallWatchdogJob by remember { mutableStateOf<Job?>(null) }
    var stallRetryCount by remember(mediaId) { mutableIntStateOf(0) }

    var currentActiveStreamUrl by remember(mediaId, activeStreamUrl) {
        mutableStateOf(activeStreamUrl)
    }
    var currentAccessToken by remember(mediaId) { mutableStateOf("") }
    var lastTokenRefreshTimeMs by remember(mediaId) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    var proactiveTokenRotationJob by remember(mediaId) {
        mutableStateOf<Job?>(null)
    }

    var userSelectedAudioTrackId by remember(mediaId) { mutableStateOf<Int?>(null) }
    var userSelectedSpuTrackId by remember(mediaId) { mutableStateOf<Int?>(null) }
    var userSelectedPlaybackSpeed by remember(mediaId) { mutableFloatStateOf(mediaPlayer.rate.takeIf { it > 0f } ?: 1.0f) }
    var currentSpeed by remember(mediaId) { mutableFloatStateOf(mediaPlayer.rate.takeIf { it > 0f } ?: 1.0f) }
    var pendingTrackRestore by remember(mediaId) { mutableStateOf(false) }

    fun restoreTracksIfNeeded(player: MediaPlayer) {
        if (!pendingTrackRestore) return

        // Ensure LibVLC has parsed the elementary stream tables
        val availableAudio = player.audioTracks
        if (availableAudio.isNullOrEmpty()) return

        userSelectedAudioTrackId?.let { targetAudioId ->
            if (availableAudio.any { it.id == targetAudioId } && player.audioTrack != targetAudioId) {
                player.audioTrack = targetAudioId
                Log.d("ClotubePlayer", "Restored user audio track: $targetAudioId")
            }
        }

        userSelectedSpuTrackId?.let { targetSpuId ->
            val availableSpu = player.spuTracks
            if (targetSpuId == -1 || availableSpu?.any { it.id == targetSpuId } == true) {
                if (player.spuTrack != targetSpuId) {
                    player.spuTrack = targetSpuId
                    Log.d("ClotubePlayer", "Restored user subtitle track: $targetSpuId")
                }
            }
        }

        if (player.rate != userSelectedPlaybackSpeed) {
            player.rate = userSelectedPlaybackSpeed
            currentSpeed = userSelectedPlaybackSpeed
            Log.d("ClotubePlayer", "Restored user playback speed: ${userSelectedPlaybackSpeed}x")
        }

        pendingTrackRestore = false
    }

    // Fast initial in-memory access token priming without blocking launch
    LaunchedEffect(mediaId) {
        if (currentAccessToken.isBlank()) {
            val cached = withContext(Dispatchers.IO) {
                effectiveTokenRefreshProvider.getValidAccessToken(forceRefresh = false)
            }
            if (!cached.isNullOrBlank()) {
                currentAccessToken = cached
                lastTokenRefreshTimeMs = System.currentTimeMillis()
                if (currentActiveStreamUrl.isBlank()) {
                    currentActiveStreamUrl = effectiveTokenRefreshProvider.buildAuthenticatedStreamUrl(mediaId, cached)
                }
            }
        }
    }

    // Proactive 45-Minute Background Token Rotation
    LaunchedEffect(mediaId) {
        proactiveTokenRotationJob?.cancel()
        proactiveTokenRotationJob = launch {
            while (isActive) {
                delay(45 * 60 * 1000L) // 45 minutes
                Log.d("ClotubePlayer", "Proactive 45-minute token rotation triggered for $mediaId")
                val freshToken = effectiveTokenRefreshProvider.refreshAccessToken()
                if (!freshToken.isNullOrBlank()) {
                    currentAccessToken = freshToken
                    lastTokenRefreshTimeMs = System.currentTimeMillis()
                    val newUrl = effectiveTokenRefreshProvider.buildAuthenticatedStreamUrl(mediaId, freshToken)
                    currentActiveStreamUrl = newUrl
                    Log.i("ClotubePlayer", "Proactively rotated token and updated currentActiveStreamUrl at 45m mark")
                }
            }
        }
    }

    fun softReloadStream(forceTokenRefresh: Boolean = false) {
        if (isReconnecting) return
        isReconnecting = true
        isBuffering = true
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null

        coroutineScope.launch {
            try {
                val now = System.currentTimeMillis()
                val elapsedSinceRefresh = now - lastTokenRefreshTimeMs
                val needsTokenRefresh = currentAccessToken.isBlank() || elapsedSinceRefresh > 50 * 60 * 1000L

                if (needsTokenRefresh) {
                    Log.d("ClotubePlayer", "Executing token refresh in softReloadStream (elapsed=${elapsedSinceRefresh}ms, force=$forceTokenRefresh)")
                    val freshToken = withContext(Dispatchers.IO) {
                        if (forceTokenRefresh || elapsedSinceRefresh > 50 * 60 * 1000L) {
                            effectiveTokenRefreshProvider.refreshAccessToken()
                        } else {
                            effectiveTokenRefreshProvider.getValidAccessToken(forceRefresh = false)
                        }
                    }
                    if (!freshToken.isNullOrBlank()) {
                        currentAccessToken = freshToken
                        lastTokenRefreshTimeMs = System.currentTimeMillis()
                        val newUrl = effectiveTokenRefreshProvider.buildAuthenticatedStreamUrl(mediaId, freshToken)
                        currentActiveStreamUrl = newUrl
                        Log.i("ClotubePlayer", "Successfully refreshed token reactive in softReloadStream")
                    }
                }

                val resumeMs = lastValidPositionMs.coerceAtLeast(0L)
                val resumeSec = (resumeMs / 1000L).coerceAtLeast(0L)
                Log.d("ClotubePlayer", "softReloadStream() at resumeMs=$resumeMs, resumeSec=$resumeSec, userIntendedToPlay=$userIntendedToPlay")

                // Capture active tracks before any stream reload as fallback
                if (userSelectedAudioTrackId == null && mediaPlayer.audioTrack != -1) {
                    userSelectedAudioTrackId = mediaPlayer.audioTrack
                }
                if (userSelectedSpuTrackId == null && mediaPlayer.spuTrack != -1) {
                    userSelectedSpuTrackId = mediaPlayer.spuTrack
                }
                pendingTrackRestore = true

                if (onReloadMedia != null) {
                    onReloadMedia.invoke(resumeMs)
                    if (!userIntendedToPlay) {
                        try { mediaPlayer.pause() } catch (_: Exception) {}
                    }
                    isReconnecting = false
                } else {
                    val downloadRepo = com.clotube.app.data.DownloadRepository(context)
                    val localFile = downloadRepo.getLocalFileIfDownloaded(mediaId)
                    if (localFile != null && localFile.exists()) {
                        mediaPlayer.stop()
                        val media = Media(mediaPlayer.libVLC, Uri.fromFile(localFile)).apply {
                            addOption(":file-caching=1500")
                            applyClotubeMediaOptions(resumeSec)
                            setHWDecoderEnabled(true, false)
                        }
                        mediaPlayer.media = media
                        media.release()

                        if (userIntendedToPlay && isScreenVisible()) {
                            requestPlaybackAudioFocus()
                            mediaPlayer.play()
                        } else {
                            mediaPlayer.pause()
                        }
                    } else {
                        val streamUrl = currentActiveStreamUrl.ifBlank {
                            activeStreamUrl.ifBlank {
                                try { mediaPlayer.media?.uri?.toString().orEmpty() } catch (_: Exception) { "" }
                            }
                        }
                        if (streamUrl.isNotBlank()) {
                            mediaPlayer.stop()
                            val media = Media(mediaPlayer.libVLC, Uri.parse(streamUrl)).apply {
                                addOption(":network-caching=1500")
                                if (currentAccessToken.isNotBlank()) {
                                    addOption(":http-header=Authorization: Bearer $currentAccessToken")
                                }
                                applyClotubeMediaOptions(resumeSec)
                                setHWDecoderEnabled(true, false)
                            }
                            mediaPlayer.media = media
                            media.release()

                            if (userIntendedToPlay && isScreenVisible()) {
                                requestPlaybackAudioFocus()
                                mediaPlayer.play()
                            } else {
                                mediaPlayer.pause()
                            }
                        }
                    }
                    isReconnecting = false
                }
            } catch (e: Exception) {
                Log.e("ClotubePlayer", "Error during softReloadStream: ${e.message}", e)
                isReconnecting = false
            }
        }
    }

    val networkMonitor = remember {
        NetworkMonitor(
            context = context,
            hasRenderedFirstFrame = { isFirstFrameRendered }
        ) {
            Log.d("ClotubePlayer", "onNetworkHandover callback: pendingReload=$pendingReloadOnReconnect, userIntendedToPlay=$userIntendedToPlay, isEnded=$hasEnded")
            if (isFirstFrameRendered && (pendingReloadOnReconnect || (userIntendedToPlay && !hasEnded))) {
                pendingReloadOnReconnect = false
                softReloadStream(forceTokenRefresh = false)
            }
        }
    }

    // 1. LIFECYCLE CONTROLLER: Intercept backgrounding and stop playback leaks
    DisposableEffect(lifecycleOwner, mediaPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    isAppForegrounded = true
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isAppForegrounded = false
                    stallWatchdogJob?.cancel()
                    stallWatchdogJob = null
                    isBuffering = false
                    if (mediaPlayer.isPlaying) {
                        try {
                            mediaPlayer.pause()
                        } catch (_: Exception) {}
                    }
                    lastValidPositionMs = mediaPlayer.time.coerceAtLeast(0L)
                    isPlaying = false
                    bluetoothAudioKeepAlive.stop()
                }
                Lifecycle.Event.ON_STOP -> {
                    isAppForegrounded = false
                    stallWatchdogJob?.cancel()
                    stallWatchdogJob = null
                    isBuffering = false
                    if (mediaPlayer.isPlaying) {
                        try {
                            mediaPlayer.pause()
                        } catch (_: Exception) {}
                    }
                    lastValidPositionMs = mediaPlayer.time.coerceAtLeast(0L)
                    isPlaying = false
                    sessionManager.updatePlaybackState(isPlaying = false, currentPositionMs = mediaPlayer.time)
                    effectiveAudioFocusManager.cancelPendingResume()
                    abandonPlaybackAudioFocus()
                    bluetoothAudioKeepAlive.stop()
                    if (isViewsAttached) {
                        try {
                            mediaPlayer.detachViews()
                        } catch (_: Exception) {}
                        isViewsAttached = false
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    isAppForegrounded = true
                    bluetoothAudioKeepAlive.start()
                    sessionManager.updatePlaybackState(isPlaying = mediaPlayer.isPlaying, currentPositionMs = mediaPlayer.time)
                    vlcVideoLayoutRef?.let { layout ->
                        layout.post {
                            if (!isViewsAttached && layout.width > 0 && layout.height > 0) {
                                try {
                                    mediaPlayer.attachViews(layout, null, false, false)
                                    mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                                    mediaPlayer.aspectRatio = "16:9"
                                    mediaPlayer.updateVideoSurfaces()
                                    isViewsAttached = true
                                    // Nudge decoder to refresh the current video frame on the newly attached surface
                                    val currentPos = mediaPlayer.time
                                    if (currentPos > 0L) {
                                        mediaPlayer.time = currentPos
                                    }
                                } catch (_: Exception) {}
                            } else if (isViewsAttached && layout.width > 0 && layout.height > 0) {
                                try {
                                    mediaPlayer.updateVideoSurfaces()
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    isAppForegrounded = false
                    stallWatchdogJob?.cancel()
                    stallWatchdogJob = null
                    effectiveAudioFocusManager.cancelPendingResume()
                    abandonPlaybackAudioFocus()
                    bluetoothAudioKeepAlive.release()
                    if (isViewsAttached) {
                        try {
                            mediaPlayer.detachViews()
                        } catch (_: Exception) {}
                        isViewsAttached = false
                    }
                    try {
                        mediaPlayer.stop()
                    } catch (_: Exception) {}
                    isPlaying = false
                }
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            effectiveAudioFocusManager.cancelPendingResume()
            abandonPlaybackAudioFocus()
            bluetoothAudioKeepAlive.release()
            if (isViewsAttached) {
                try {
                    mediaPlayer.detachViews()
                } catch (_: Exception) {}
                isViewsAttached = false
            }
            try {
                mediaPlayer.stop()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            requestPlaybackAudioFocus()
        }
        sessionManager.updatePlaybackState(isPlaying = isPlaying, currentPositionMs = mediaPlayer.time)
        onIsPlayingChanged?.invoke(isPlaying)
    }

    var currentPositionMs by remember(mediaId) {
        val curTime = mediaPlayer.time
        mutableLongStateOf(if (curTime > 0L) curTime else initialPositionMs)
    }
    var durationMs by remember(mediaId) {
        val len = mediaPlayer.length
        mutableLongStateOf(if (len > 0L) len else fallbackDurationMs)
    }

    var isPlaybackEnded by remember(mediaId) { mutableStateOf(false) }
    var hasStartedOnce by remember(mediaId) { mutableStateOf(false) }

    // Reset loading state whenever mediaId changes
    LaunchedEffect(mediaId) {
        isBuffering = true
        isSeeking = false
        isPlaybackEnded = false
        hasStartedOnce = false
        isFirstFrameRendered = false
        userIntendedToPlay = true
        pendingReloadOnReconnect = false
        lastTokenRefreshTimeMs = System.currentTimeMillis()
        userSelectedAudioTrackId = null
        userSelectedSpuTrackId = null
        pendingTrackRestore = false
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
        stallRetryCount = 0
        val len = mediaPlayer.length
        durationMs = if (len > 0L) len else fallbackDurationMs
        if (currentPositionMs == 0L && initialPositionMs > 0L) {
            currentPositionMs = initialPositionMs
            lastValidPositionMs = initialPositionMs
        } else if (currentPositionMs > 0L) {
            lastValidPositionMs = currentPositionMs
        }
    }

    // Safety fallback to prevent UI lockup if stream stalls
    LaunchedEffect(isSeeking) {
        if (isSeeking) {
            delay(3500L)
            isSeeking = false
            isBuffering = false
        }
    }

    // Measured track width for pixel-perfect thumb and preview alignment
    var trackWidthPx by remember { mutableFloatStateOf(0f) }

    // 1. Local dragging state isolation: decouples slider thumb & timestamps from LibVLC TimeChanged events
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val actualFraction = if (durationMs > 0L) {
        (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val displayFraction = if (isDragging) dragFraction else actualFraction
    val displayedCurrentTimeMs = if (isDragging) {
        (dragFraction * durationMs).toLong()
    } else currentPositionMs

    val isScrubbing = isDragging
    val scrubFraction = dragFraction
    val activeFraction = displayFraction
    val displayPositionMs = displayedCurrentTimeMs

    // Floating Next Episode Dismissal
    var isFloatingNextDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(title) { isFloatingNextDismissed = false }

    // Volume & Brightness Gesture States with Continuous Float Accumulators
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var currentVolumeInt by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var volumeAccumulator by remember { mutableFloatStateOf(currentVolumeInt.toFloat()) }

    val initialBrightness = remember {
        val wb = activity?.window?.attributes?.screenBrightness ?: -1f
        if (wb >= 0f) wb else {
            val sys = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: Exception) { 128 }
            (sys / 255f).coerceIn(0.1f, 1.0f)
        }
    }
    var currentBrightness by remember { mutableFloatStateOf(initialBrightness) }
    var brightnessAccumulator by remember { mutableFloatStateOf(initialBrightness) }

    var showGestureOverlay by remember { mutableStateOf(false) }
    var activeGestureMode by remember { mutableStateOf<PlayerGestureMode?>(null) }
    var lastLockedGestureMode by remember { mutableStateOf(PlayerGestureMode.BRIGHTNESS) }
    var gestureIndicatorFraction by remember { mutableFloatStateOf(0f) }
    var dismissGestureJob by remember { mutableStateOf<Job?>(null) }

    fun scheduleGestureDismiss() {
        dismissGestureJob?.cancel()
        dismissGestureJob = coroutineScope.launch {
            delay(1200)
            showGestureOverlay = false
            delay(350) // Wait for AnimatedVisibility exit transition to completely finish
            activeGestureMode = null // Safe to reset identity only after overlay is completely invisible
        }
    }

    // Dialogs & Overlays
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showAudioTrackDialog by remember { mutableStateOf(false) }
    var showStatsOverlay by remember { mutableStateOf(false) }

    // Synchronize LibVLC Events (Handling Buffering & Opening)
    DisposableEffect(mediaPlayer, mediaId) {
        networkMonitor.register()

        val listener = MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    isBuffering = true
                    if (!isScreenVisible()) {
                        try {
                            mediaPlayer.pause()
                        } catch (_: Exception) {}
                        isPlaying = false
                    }
                }
                MediaPlayer.Event.Buffering -> {
                    val percent = event.buffering
                    // Buffer Underrun Stall Watchdog (Strictly guarded for mid-stream drops only)
                    if (percent < 100f) {
                        isBuffering = true
                        if (isFirstFrameRendered && lastValidPositionMs > 0L && userIntendedToPlay && stallWatchdogJob == null && stallRetryCount < 1) {
                            stallWatchdogJob = coroutineScope.launch {
                                delay(25_000L)
                                if (isBuffering && networkMonitor.isConnected && isFirstFrameRendered && lastValidPositionMs > 0L) {
                                    stallRetryCount++
                                    Log.w("ClotubePlayer", "Buffer underrun stall watchdog triggered (25s freeze). Soft reloading stream...")
                                    softReloadStream(forceTokenRefresh = false)
                                }
                            }
                        }
                    } else {
                        stallWatchdogJob?.cancel()
                        stallWatchdogJob = null
                        isBuffering = false
                        isSeeking = false
                        if (!isScreenVisible()) {
                            try {
                                mediaPlayer.pause()
                            } catch (_: Exception) {}
                            isPlaying = false
                        } else {
                            // If the player stalled or paused during initial buffering, ensure it starts playing
                            if (userIntendedToPlay && !mediaPlayer.isPlaying) {
                                requestPlaybackAudioFocus()
                                mediaPlayer.play()
                            } else if (mediaPlayer.isPlaying) {
                                hasStartedOnce = true
                                isFirstFrameRendered = true
                            }
                        }
                    }
                }
                MediaPlayer.Event.Playing -> {
                    stallWatchdogJob?.cancel()
                    stallWatchdogJob = null
                    // Safety enforcement: If VLC started playing while backgrounded, pause immediately
                    if (!isScreenVisible()) {
                        try {
                            mediaPlayer.pause()
                        } catch (_: Exception) {}
                        isPlaying = false
                        sessionManager.updatePlaybackState(isPlaying = false, currentPositionMs = mediaPlayer.time)
                    } else {
                        requestPlaybackAudioFocus()
                        isPlaying = true
                        isBuffering = false
                        isSeeking = false
                        isPlaybackEnded = false
                        hasStartedOnce = true
                        if (mediaPlayer.time > 0L) {
                            isFirstFrameRendered = true
                            lastValidPositionMs = mediaPlayer.time
                        }
                        sessionManager.updatePlaybackState(isPlaying = true, currentPositionMs = mediaPlayer.time)
                    }
                }
                MediaPlayer.Event.Paused -> {
                    isPlaying = false
                    isBuffering = false
                    stallWatchdogJob?.cancel()
                    stallWatchdogJob = null
                    sessionManager.updatePlaybackState(isPlaying = false, currentPositionMs = mediaPlayer.time)
                }
                MediaPlayer.Event.Stopped -> {
                    isPlaying = false
                    isBuffering = false
                    stallWatchdogJob?.cancel()
                    stallWatchdogJob = null
                    sessionManager.updatePlaybackState(isPlaying = false, currentPositionMs = mediaPlayer.time)
                }
                MediaPlayer.Event.TimeChanged -> {
                    stallWatchdogJob?.cancel()
                    stallWatchdogJob = null
                    stallRetryCount = 0
                    isBuffering = false
                    if (event.timeChanged > 0L) {
                        lastValidPositionMs = event.timeChanged
                        isPlaying = true
                        hasStartedOnce = true
                        isFirstFrameRendered = true
                        if (pendingTrackRestore) {
                            restoreTracksIfNeeded(mediaPlayer)
                        }
                    }
                    // Strictly ignore TimeChanged events while user is dragging the slider to eliminate jitter
                    if (!isDragging) {
                        isSeeking = false
                        isFirstFrameRendered = true
                        currentPositionMs = event.timeChanged.coerceAtLeast(0L)
                    }
                }
                MediaPlayer.Event.PositionChanged -> {
                    // Strictly ignore PositionChanged while isDragging to prevent slider snap-backs
                }
                MediaPlayer.Event.LengthChanged -> {
                    if (event.lengthChanged > 0L) {
                        durationMs = event.lengthChanged
                    }
                }
                MediaPlayer.Event.EndReached,
                MediaPlayer.Event.EncounteredError -> {
                    val totalDur = if (durationMs > 0L) durationMs else (if (mediaPlayer.length > 0L) mediaPlayer.length else fallbackDurationMs)
                    val isPrematureEof = totalDur > 0L && lastValidPositionMs > 0L && lastValidPositionMs < (totalDur - 15_000L)
                    Log.d("ClotubePlayer", "Event ${if (event.type == MediaPlayer.Event.EndReached) "EndReached" else "EncounteredError"}, totalDur=$totalDur, lastValidPositionMs=$lastValidPositionMs, isPrematureEof=$isPrematureEof")

                    if (isPrematureEof) {
                        // Premature EOF guard: stream dropped prematurely due to socket reset
                        stallWatchdogJob?.cancel()
                        stallWatchdogJob = null
                        isPlaying = false
                        isBuffering = true
                        isPlaybackEnded = false

                        if (networkMonitor.isConnected) {
                            softReloadStream(forceTokenRefresh = false)
                        } else {
                            pendingReloadOnReconnect = true
                            Log.d("ClotubePlayer", "Network lost during premature EOF. Pending reload on reconnect.")
                        }
                    } else {
                        // Genuine playback completion
                        stallWatchdogJob?.cancel()
                        stallWatchdogJob = null
                        isPlaying = false
                        isBuffering = false
                        isSeeking = false
                        isPlaybackEnded = true
                        sessionManager.updatePlaybackState(isPlaying = false, currentPositionMs = mediaPlayer.time)
                        onPlaybackEnded?.invoke()
                    }
                }
            }
        }
        mediaPlayer.setEventListener(listener)
        onDispose {
            proactiveTokenRotationJob?.cancel()
            proactiveTokenRotationJob = null
            networkMonitor.unregister()
            stallWatchdogJob?.cancel()
            stallWatchdogJob = null
            try {
                mediaPlayer.setEventListener(null)
                if (isViewsAttached) {
                    try {
                        mediaPlayer.detachViews()
                    } catch (_: Exception) {}
                    isViewsAttached = false
                } else {
                    try {
                        mediaPlayer.detachViews()
                    } catch (_: Exception) {}
                }
                mediaPlayer.stop()
            } catch (t: Throwable) {
                android.util.Log.e("ClotubePlayer", "Safe LibVLC release error: ${t.message}")
            }
            vlcVideoLayoutRef = null
        }
    }

    // Dynamic Screen-On WakeLock
    DisposableEffect(isPlaying) {
        val window = activity?.window
        if (isPlaying) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Auto-Reset Window Brightness on Exit
    DisposableEffect(Unit) {
        onDispose {
            activity?.let { act ->
                val lp = act.window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                act.window.attributes = lp
            }
        }
    }

    // Polling Timeline Loop
    LaunchedEffect(mediaPlayer, isDragging) {
        while (true) {
            if (!isDragging) {
                val currentTime = mediaPlayer.time.coerceAtLeast(0L)
                if (currentTime > 0L) {
                    currentPositionMs = currentTime
                    lastValidPositionMs = maxOf(lastValidPositionMs, currentTime)
                }
                val len = mediaPlayer.length
                if (len > 0L) durationMs = len
                if (mediaPlayer.isPlaying && currentTime > 0L && !isSeeking) {
                    // Failsafe: if video is actively playing frames, dismiss loading spinner & placeholder immediately
                    isFirstFrameRendered = true
                    isBuffering = false
                    stallWatchdogJob?.cancel()
                    stallWatchdogJob = null
                    stallRetryCount = 0
                }
            }
            delay(250)
        }
    }

    // Centralized Inactivity Timer: Restarts countdown on every interaction
    val isAnyModalOpen = showSpeedDialog || showSubtitleDialog || showAudioTrackDialog

    fun resetInactivityTimer() {
        autoHideJob?.cancel()
        if (isPlaying && !isScrubbing && !isAnyModalOpen) {
            autoHideJob = coroutineScope.launch {
                delay(3500)
                areControlsVisible = false
            }
        }
    }

    // Automatically trigger timer whenever controls become visible or play state toggles
    LaunchedEffect(areControlsVisible, isPlaying, isScrubbing, isAnyModalOpen) {
        if (areControlsVisible) {
            resetInactivityTimer()
        } else {
            autoHideJob?.cancel()
        }
    }



    // Dedicated orientation and insets controller bound strictly to explicit fullscreen state
    VideoPlayerOrientationController(
        isFullscreen = currentFullscreen,
        onFullscreenToggle = { newFs ->
            internalFullscreen = newFs
            onFullscreenChanged?.invoke(newFs)
        }
    )

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    BackHandler(enabled = isLandscape || currentFullscreen) {
        internalFullscreen = false
        onFullscreenChanged?.invoke(false)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    val remainingMs = (durationMs - currentPositionMs).coerceAtLeast(0L)
    val showFloatingNext = false

    fun seekTo(targetTimeMs: Long) {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
        stallRetryCount = 0
        lastValidPositionMs = targetTimeMs
        val totalDur = if (durationMs > 0L) durationMs else mediaPlayer.length

        // Reactive token refresh if scrubbing after 50+ minutes of playback
        val elapsedSinceTokenRefresh = System.currentTimeMillis() - lastTokenRefreshTimeMs
        val isTokenLikelyExpired = elapsedSinceTokenRefresh > 50 * 60 * 1000L

        if (isTokenLikelyExpired) {
            Log.w("ClotubePlayer", "seekTo: More than 50 minutes elapsed since token refresh ($elapsedSinceTokenRefresh ms). Refreshing token and reloading at ${targetTimeMs}ms")
            val safeTarget = if (totalDur > 0L && targetTimeMs >= totalDur - 5000L) 0L else targetTimeMs.coerceAtLeast(0L)
            currentPositionMs = safeTarget
            lastValidPositionMs = safeTarget
            softReloadStream(forceTokenRefresh = true)
            return
        }

        val isEofOrEnded = isPlaybackEnded ||
                (!mediaPlayer.isPlaying && mediaPlayer.time <= 0L && (hasStartedOnce || isFirstFrameRendered)) ||
                (!mediaPlayer.isPlaying && mediaPlayer.time == -1L) ||
                (totalDur > 0L && currentPositionMs >= totalDur - 1500L)
        if (isEofOrEnded) {
            val safeTarget = if (totalDur > 0L && targetTimeMs >= totalDur - 5000L) 0L else targetTimeMs.coerceAtLeast(0L)
            isPlaybackEnded = false
            isBuffering = true
            isSeeking = true
            currentPositionMs = safeTarget
            lastValidPositionMs = safeTarget

            // Capture active tracks before EOF reload as fallback
            if (userSelectedAudioTrackId == null && mediaPlayer.audioTrack != -1) {
                userSelectedAudioTrackId = mediaPlayer.audioTrack
            }
            if (userSelectedSpuTrackId == null && mediaPlayer.spuTrack != -1) {
                userSelectedSpuTrackId = mediaPlayer.spuTrack
            }
            pendingTrackRestore = true

            if (onReloadMedia != null) {
                onReloadMedia.invoke(safeTarget)
            } else {
                mediaPlayer.time = safeTarget
                requestPlaybackAudioFocus()
                mediaPlayer.play()
            }
        } else {
            isBuffering = true
            isSeeking = true
            mediaPlayer.time = targetTimeMs
            currentPositionMs = targetTimeMs
            if (!mediaPlayer.isPlaying && userIntendedToPlay) {
                requestPlaybackAudioFocus()
                mediaPlayer.play()
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(maxVolume) {
                var isLeftSide = false
                var isDragAllowed = false
                val scrubberDeadZonePx = with(density) { 56.dp.toPx() }

                detectDragGestures(
                    onDragStart = { offset ->
                        if (offset.y < size.height - scrubberDeadZonePx) {
                            isDragAllowed = true
                            dismissGestureJob?.cancel()
                            val isLeftHalf = offset.x < (size.width / 2f)
                            if (isLeftHalf) {
                                brightnessAccumulator = currentBrightness
                                activeGestureMode = PlayerGestureMode.BRIGHTNESS
                                lastLockedGestureMode = PlayerGestureMode.BRIGHTNESS
                                gestureIndicatorFraction = currentBrightness
                            } else {
                                volumeAccumulator = currentVolumeInt.toFloat()
                                activeGestureMode = PlayerGestureMode.VOLUME
                                lastLockedGestureMode = PlayerGestureMode.VOLUME
                                gestureIndicatorFraction = volumeAccumulator / maxVolume.toFloat()
                            }
                            showGestureOverlay = true
                        } else {
                            isDragAllowed = false
                        }
                    },
                    onDrag = { change, dragAmount ->
                        if (!isDragAllowed) return@detectDragGestures
                        change.consume()
                        dismissGestureJob?.cancel()
                        val deltaPercent = -dragAmount.y / (size.height * 0.65f)

                        when (activeGestureMode) {
                            PlayerGestureMode.BRIGHTNESS -> {
                                brightnessAccumulator = (brightnessAccumulator + deltaPercent).coerceIn(0.01f, 1.0f)
                                currentBrightness = brightnessAccumulator
                                activity?.let { act ->
                                    val lp = act.window.attributes
                                    lp.screenBrightness = currentBrightness
                                    act.window.attributes = lp
                                }
                                gestureIndicatorFraction = currentBrightness
                                showGestureOverlay = true
                            }
                            PlayerGestureMode.VOLUME -> {
                                val volumeDelta = deltaPercent * maxVolume
                                volumeAccumulator = (volumeAccumulator + volumeDelta).coerceIn(0f, maxVolume.toFloat())
                                val newVol = volumeAccumulator.roundToInt().coerceIn(0, maxVolume)
                                if (newVol != currentVolumeInt) {
                                    currentVolumeInt = newVol
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                }
                                gestureIndicatorFraction = volumeAccumulator / maxVolume.toFloat()
                                showGestureOverlay = true
                            }
                            null -> Unit
                        }
                    },
                    onDragEnd = {
                        scheduleGestureDismiss()
                    },
                    onDragCancel = {
                        scheduleGestureDismiss()
                    }
                )
            }
    ) {
        // 1. LibVLC Native Viewport with TextureView for 100% Fill
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    vlcVideoLayoutRef = this

                    // Attach LibVLC strictly when the AndroidView has finished measuring non-zero dimensions
                    val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                        val newWidth = right - left
                        val newHeight = bottom - top
                        val oldWidth = oldRight - oldLeft
                        val oldHeight = oldBottom - oldTop

                        if (newWidth > 0 && newHeight > 0) {
                            if (!isViewsAttached) {
                                try {
                                    mediaPlayer.detachViews()
                                } catch (_: Exception) {}
                                try {
                                    mediaPlayer.attachViews(this, null, false, false)
                                    mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                                    mediaPlayer.aspectRatio = "16:9"
                                    mediaPlayer.updateVideoSurfaces()
                                    isViewsAttached = true
                                    val currentPos = mediaPlayer.time
                                    if (currentPos > 0L) {
                                        mediaPlayer.time = currentPos
                                    }
                                } catch (_: Exception) {}
                            } else if (newWidth != oldWidth || newHeight != oldHeight) {
                                try {
                                    mediaPlayer.updateVideoSurfaces()
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    addOnLayoutChangeListener(layoutListener)

                    post {
                        if (!isViewsAttached && width > 0 && height > 0) {
                            try {
                                mediaPlayer.detachViews()
                            } catch (_: Exception) {}
                            try {
                                mediaPlayer.attachViews(this, null, false, false)
                                mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                                mediaPlayer.aspectRatio = "16:9"
                                mediaPlayer.updateVideoSurfaces()
                                isViewsAttached = true
                                val currentPos = mediaPlayer.time
                                if (currentPos > 0L) {
                                    mediaPlayer.time = currentPos
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            },
            update = { layout ->
                vlcVideoLayoutRef = layout
                if (!isViewsAttached && layout.width > 0 && layout.height > 0) {
                    try {
                        mediaPlayer.detachViews()
                    } catch (_: Exception) {}
                    try {
                        mediaPlayer.attachViews(layout, null, false, false)
                        mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                        mediaPlayer.aspectRatio = "16:9"
                        mediaPlayer.updateVideoSurfaces()
                        isViewsAttached = true
                        val currentPos = mediaPlayer.time
                        if (currentPos > 0L) {
                            mediaPlayer.time = currentPos
                        }
                    } catch (_: Exception) {}
                } else if (isViewsAttached && layout.width > 0 && layout.height > 0) {
                    try {
                        mediaPlayer.updateVideoSurfaces()
                    } catch (_: Exception) {}
                }
                mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                mediaPlayer.aspectRatio = "16:9"
            },
            onReset = { layout ->
                if (isViewsAttached) {
                    try {
                        mediaPlayer.detachViews()
                    } catch (_: Exception) {}
                    isViewsAttached = false
                }
                vlcVideoLayoutRef = null
            },
            onRelease = { layout ->
                if (isViewsAttached) {
                    try {
                        mediaPlayer.detachViews()
                    } catch (_: Exception) {}
                    isViewsAttached = false
                }
                vlcVideoLayoutRef = null
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Poster placeholder while waiting for the first video frame
        if (!isFirstFrameRendered && !(isPlaying && currentPositionMs > 0L) && !thumbnailUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = "Poster",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.50f))
                )
            }
        }

        // 3. Centered Vertical Brightness / Volume Capsule HUD
        AnimatedVisibility(
            visible = showGestureOverlay && activeGestureMode != null,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val modeToDisplay = activeGestureMode ?: lastLockedGestureMode
            CenteredGesturePill(
                valueFraction = gestureIndicatorFraction,
                mode = modeToDisplay
            )
        }

        // 4. Center Loading / Buffering Indicator
        val shouldShowSpinner = !isFirstFrameRendered || isBuffering || isSeeking

        if (shouldShowSpinner && !isScrubbing && !areControlsVisible) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(44.dp),
                    color = Color.White,
                    strokeWidth = 3.dp
                )
            }
        }

        // 5. Stats for Nerds Overlay
        if (showStatsOverlay) {
            StatsForNerdsOverlay(
                mediaPlayer = mediaPlayer,
                isBuffering = isBuffering,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 54.dp, start = 16.dp)
            )
        }

        // 6. Floating Next Episode Auto-Play Card
        if (nextEpisode != null) {
            NextEpisodeCountdownCard(
                currentPositionMs = if (isScrubbing) displayPositionMs else currentPositionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                nextEpisode = nextEpisode,
                onPlayNext = { ep ->
                    onPlayNextEpisode?.invoke(ep) ?: onNextEpisode?.invoke()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .then(if (currentFullscreen) Modifier.navigationBarsPadding() else Modifier)
                    .padding(end = 16.dp, bottom = if (currentFullscreen) 80.dp else 16.dp),
                isScrubbing = isScrubbing
            )
        }

        // 7. HUD Controls Tap Revealer when hidden
        if (!areControlsVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        areControlsVisible = true
                        resetInactivityTimer()
                    }
            )
        }

        // 7. HUD Controls
        AnimatedVisibility(
            visible = areControlsVisible || isScrubbing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        areControlsVisible = false
                        autoHideJob?.cancel()
                    }
            ) {
                // Top Action Bar
                val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                val insetModifier = if (isPortrait) {
                    Modifier.statusBarsPadding()
                } else {
                    Modifier.windowInsetsPadding(WindowInsets.displayCutout)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(insetModifier)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            resetInactivityTimer()
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            view.triggerNavigationHaptic()
                            resetInactivityTimer()
                            val isLandscapeOrientation = activity?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
                            if (isLandscapeOrientation || currentFullscreen) {
                                internalFullscreen = false
                                onFullscreenChanged?.invoke(false)
                                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            } else {
                                onNavigateBack()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }

                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp, end = 8.dp)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            resetInactivityTimer()
                            showStatsOverlay = !showStatsOverlay
                        }) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Stats",
                                tint = if (showStatsOverlay) Color(0xFF00E5FF) else Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        IconButton(onClick = {
                            resetInactivityTimer()
                            showAudioTrackDialog = true
                        }) {
                            Icon(Icons.Default.Audiotrack, contentDescription = "Audio", tint = Color.White, modifier = Modifier.size(22.dp))
                        }

                        val hasSubtitlesEnabled = remember(mediaPlayer.spuTrack, userSelectedSpuTrackId) {
                            (userSelectedSpuTrackId ?: mediaPlayer.spuTrack) != -1
                        }
                        IconButton(onClick = {
                            resetInactivityTimer()
                            showSubtitleDialog = true
                        }) {
                            Icon(
                                imageVector = if (hasSubtitlesEnabled) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionOff,
                                contentDescription = "Subtitles",
                                tint = if (hasSubtitlesEnabled) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Surface(
                            color = Color(0x33FFFFFF),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .clickable {
                                    resetInactivityTimer()
                                    showSpeedDialog = true
                                }
                        ) {
                            Text(
                                text = "${currentSpeed}x",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }

                        IconButton(onClick = {
                            resetInactivityTimer()
                            val newFs = !currentFullscreen
                            internalFullscreen = newFs
                            onFullscreenChanged?.invoke(newFs)
                        }) {
                            Icon(
                                imageVector = if (currentFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = "Toggle Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Center Controls Row (Skip / Play / Pause)
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        // Prevent row clicks from triggering the root background toggle
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            resetInactivityTimer()
                        },
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            resetInactivityTimer()
                            onPreviousEpisode?.invoke()
                        },
                        enabled = onPreviousEpisode != null,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous Episode",
                            tint = if (onPreviousEpisode != null) Color.White else Color(0x44FFFFFF),
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            resetInactivityTimer()
                            val newTime = (mediaPlayer.time - 10000L).coerceAtLeast(0L)
                            seekTo(newTime)
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "Rewind 10s",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    // Primary Play/Pause Action Button / White Loading Spinner
                    Box(
                        modifier = Modifier.size(64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBuffering && !isFirstFrameRendered) {
                            // PURE WHITE LOADING SPINNER
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(44.dp)
                            )
                        } else {
                            IconButton(
                                onClick = {
                                    if (mediaPlayer.isPlaying) {
                                        userIntendedToPlay = false
                                        isPlaying = false
                                        stallWatchdogJob?.cancel()
                                        stallWatchdogJob = null
                                        isBuffering = false
                                        mediaPlayer.pause()
                                        autoHideJob?.cancel() // Keep controls visible while paused
                                    } else {
                                        userIntendedToPlay = true
                                        val totalDur = if (durationMs > 0L) durationMs else mediaPlayer.length
                                        val isEofOrEnded = isPlaybackEnded ||
                                                (!mediaPlayer.isPlaying && mediaPlayer.time <= 0L && (hasStartedOnce || isFirstFrameRendered)) ||
                                                (totalDur > 0L && currentPositionMs >= totalDur - 1500L)
                                        if (isEofOrEnded) {
                                            val startMs = if (totalDur > 0L && currentPositionMs >= totalDur - 5000L) 0L else currentPositionMs.coerceAtLeast(0L)
                                            currentPositionMs = startMs
                                            lastValidPositionMs = startMs
                                            isPlaybackEnded = false
                                            isBuffering = true
                                            isPlaying = true
                                            if (onReloadMedia != null) {
                                                onReloadMedia.invoke(startMs)
                                            } else {
                                                seekTo(startMs)
                                            }
                                        } else {
                                            isPlaying = true
                                            requestPlaybackAudioFocus()
                                            mediaPlayer.play()
                                        }
                                        resetInactivityTimer()
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = {
                            resetInactivityTimer()
                            val totalDuration = if (durationMs > 0) durationMs else mediaPlayer.length
                            val newTime = (mediaPlayer.time + 10000L).coerceAtMost(totalDuration)
                            seekTo(newTime)
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "Forward 10s",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            resetInactivityTimer()
                            if (nextEpisode != null && onPlayNextEpisode != null) {
                                onPlayNextEpisode.invoke(nextEpisode)
                            } else {
                                onNextEpisode?.invoke()
                            }
                        },
                        enabled = onNextEpisode != null || (nextEpisode != null && onPlayNextEpisode != null),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Episode",
                            tint = if (onNextEpisode != null || (nextEpisode != null && onPlayNextEpisode != null)) Color.White else Color(0x44FFFFFF),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Bottom Timeline Scrubber Container
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .then(if (currentFullscreen) Modifier.navigationBarsPadding() else Modifier)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            resetInactivityTimer()
                        }
                ) {
                    val previewWidthDp = 130.dp
                    val previewHeightDp = 74.dp
                    val previewWidthPx = with(density) { previewWidthDp.toPx() }
                    val previewHeightPx = with(density) { previewHeightDp.toPx() }

                    // Floating 16:9 Thumbnail Preview
                    if (isScrubbing && trackWidthPx > 0f) {
                        val thumbCenterXPx = activeFraction * trackWidthPx
                        val previewLeftXPx = (thumbCenterXPx - (previewWidthPx / 2f)).coerceIn(
                            0f,
                            (trackWidthPx - previewWidthPx).coerceAtLeast(0f)
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(0.dp)
                        ) {
                            Card(
                                modifier = Modifier
                                    .size(previewWidthDp, previewHeightDp)
                                    .offset {
                                        IntOffset(
                                            x = previewLeftXPx.roundToInt(),
                                            y = -(previewHeightPx + with(density) { 14.dp.toPx() }).roundToInt()
                                        )
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF131620)),
                                border = BorderStroke(1.5.dp, Color(0xFF00E5FF)),
                                elevation = CardDefaults.cardElevation(8.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    if (!thumbnailUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = thumbnailUrl,
                                            contentDescription = "Seek Preview",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color(0xFF0B0E17)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = Color(0x66FFFFFF),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(24.dp)
                                            .align(Alignment.BottomCenter)
                                            .background(Color.Black.copy(alpha = 0.70f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (durationMs > 0L) "${formatTimecode(displayPositionMs)} / ${formatTimecode(durationMs)}" else formatTimecode(displayPositionMs),
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Scrubber Interactive Track
                    val thumbDiameterDp = if (isScrubbing) 15.dp else 12.dp
                    val thumbDiameterPx = with(density) { thumbDiameterDp.toPx() }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .onSizeChanged { trackWidthPx = it.width.toFloat() }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val currentWidth = size.width.toFloat()
                                    if (currentWidth > 0f) {
                                        resetInactivityTimer()
                                        isDragging = true
                                        dragFraction = (down.position.x / currentWidth).coerceIn(0f, 1f)
                                    }

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pointerChange = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!pointerChange.pressed) {
                                            pointerChange.consume()
                                            break
                                        }
                                        pointerChange.consume()
                                        resetInactivityTimer()
                                        val width = size.width.toFloat()
                                        if (width > 0f) {
                                            dragFraction = (pointerChange.position.x / width).coerceIn(0f, 1f)
                                        }
                                    }

                                    if (durationMs > 0L) {
                                        val targetMs = calculateSafeSeekTargetMs(dragFraction, durationMs)
                                        seekTo(targetMs)
                                    }
                                    isDragging = false
                                    resetInactivityTimer()
                                }
                            },
                        contentAlignment = Alignment.CenterStart
                    ) {
                        // 1. Inactive Track
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0x33FFFFFF))
                        )

                        // 2. Active Played Track
                        val activeWidthPx = (activeFraction * trackWidthPx).coerceIn(0f, trackWidthPx)
                        Box(
                            modifier = Modifier
                                .width(with(density) { activeWidthPx.toDp() })
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF00E5FF))
                        )

                        // 3. Thumb Handle
                        val clampedThumbXPx = (activeWidthPx - (thumbDiameterPx / 2f)).coerceIn(
                            0f,
                            (trackWidthPx - thumbDiameterPx).coerceAtLeast(0f)
                        )

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(clampedThumbXPx.roundToInt(), 0) }
                                .size(thumbDiameterDp)
                                .shadow(4.dp, CircleShape)
                                .clip(CircleShape)
                                .background(Color(0xFF00E5FF))
                        )
                    }

                    // Timecode Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTimecode(displayPositionMs),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = formatTimecode(durationMs),
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    // Modal: Playback Speed
    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            containerColor = Color(0xFF161A26),
            title = { Text("Playback Speed", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    speeds.forEach { speed ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    mediaPlayer.rate = speed
                                    currentSpeed = speed
                                    userSelectedPlaybackSpeed = speed
                                    showSpeedDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${speed}x" + if (speed == 1.0f) " (Normal)" else "",
                                color = if (currentSpeed == speed) Color(0xFF00E5FF) else Color.White,
                                fontSize = 14.sp
                            )
                            if (currentSpeed == speed) {
                                Text("✓", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("Close", color = Color(0xFF94A3B8)) } },
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Modal: Subtitles / Captions
    if (showSubtitleDialog) {
        val spuTracks = remember(mediaPlayer, userSelectedSpuTrackId) {
            val tracks = mediaPlayer.spuTracks ?: emptyArray()
            val currentId = userSelectedSpuTrackId ?: mediaPlayer.spuTrack
            tracks.filter {
                it.id != -1 && !it.name.equals("Off", ignoreCase = true) && !it.name.equals("Disable", ignoreCase = true)
            }.map { TrackInfoItem(it.id, it.name, it.id == currentId) }
        }
        val isAnySubtitleSelected = (userSelectedSpuTrackId ?: mediaPlayer.spuTrack) != -1

        AlertDialog(
            onDismissRequest = { showSubtitleDialog = false },
            containerColor = Color(0xFF161A26),
            title = { Text("Captions / Subtitles", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                mediaPlayer.spuTrack = -1
                                userSelectedSpuTrackId = -1
                                showSubtitleDialog = false
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Disable",
                            color = if (!isAnySubtitleSelected) Color(0xFF00E5FF) else Color.White,
                            fontSize = 14.sp,
                            fontWeight = if (!isAnySubtitleSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        if (!isAnySubtitleSelected) {
                            Text("✓", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                        }
                    }

                    spuTracks.forEach { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    mediaPlayer.spuTrack = track.id
                                    userSelectedSpuTrackId = track.id
                                    showSubtitleDialog = false
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = track.name,
                                color = if (track.isSelected) Color(0xFF00E5FF) else Color.White,
                                fontSize = 14.sp
                            )
                            if (track.isSelected) {
                                Text("✓", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSubtitleDialog = false }) { Text("Close", color = Color(0xFF94A3B8)) } },
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Modal: Audio Tracks
    if (showAudioTrackDialog) {
        val audioTracks = remember(mediaPlayer, userSelectedAudioTrackId) {
            val tracks = mediaPlayer.audioTracks ?: emptyArray()
            val currentId = userSelectedAudioTrackId ?: mediaPlayer.audioTrack
            tracks.map { TrackInfoItem(it.id, it.name, it.id == currentId) }
        }

        AlertDialog(
            onDismissRequest = { showAudioTrackDialog = false },
            containerColor = Color(0xFF161A26),
            title = { Text("Audio Track", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (audioTracks.isEmpty()) {
                        Text("No alternate audio tracks available.", color = Color(0xFF94A3B8), fontSize = 13.sp)
                    } else {
                        audioTracks.forEach { track ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        mediaPlayer.audioTrack = track.id
                                        userSelectedAudioTrackId = track.id
                                        showAudioTrackDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = track.name,
                                    color = if (track.isSelected) Color(0xFF00E5FF) else Color.White,
                                    fontSize = 14.sp
                                )
                                if (track.isSelected) {
                                    Text("✓", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAudioTrackDialog = false }) { Text("Close", color = Color(0xFF94A3B8)) } },
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun StatsForNerdsOverlay(
    mediaPlayer: MediaPlayer,
    isBuffering: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.widthIn(max = 280.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFF2A3142))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("VIDEO STATS (LibVLC)", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            StatText("Engine", "LibVLC (Native FFmpeg)")
            StatText("Speed", "${mediaPlayer.rate}x")
            StatText("Timecode", "${mediaPlayer.time / 1000}s / ${mediaPlayer.length / 1000}s")
            StatText("Audio Track", "${mediaPlayer.audioTrack}")
            StatText("Subtitle Track", "${mediaPlayer.spuTrack}")
            StatText("Player State", if (isBuffering) "Buffering/Loading" else if (mediaPlayer.isPlaying) "Playing" else "Paused/Stopped")
        }
    }
}

@Composable
private fun StatText(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFF94A3B8), fontSize = 10.sp)
        Text(value, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun formatTimecode(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
