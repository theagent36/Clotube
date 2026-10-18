package com.clotube.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import androidx.compose.ui.text.font.FontFamily
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
import com.clotube.app.model.Episode
import com.clotube.app.viewmodel.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.roundToInt

data class TrackInfoItem(val id: Int, val name: String, val isSelected: Boolean)

fun calculateSafeSeekTargetMs(fraction: Float, totalDuration: Long): Long {
    if (totalDuration <= 0L) return 0L
    val rawTargetMs = (fraction * totalDuration).toLong()
    val maxSafeBoundaryMs = (totalDuration - 3000L).coerceAtLeast(0L)
    return rawTargetMs.coerceIn(0L, maxSafeBoundaryMs)
}

enum class VideoScaleMode(val label: String) {
    BEST_FIT("Fit to Screen"),
    ZOOM_CROP("Zoom (Fill Width)"),
    STRETCH("Stretch")
}

@Composable
fun ClotubeVideoPlayer(
    episode: Episode,
    viewModel: VideoViewModel,
    modifier: Modifier = Modifier,
    nextEpisode: Episode? = null,
    onPreviousEpisode: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    onNavigateBack: () -> Unit = {}
) {
    ClotubeVideoPlayer(
        episode = episode,
        viewModel = viewModel,
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
    nextEpisode: Episode? = null,
    onPreviousEpisode: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    onClosePlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val coroutineScope = rememberCoroutineScope()

    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var libVLCInstance by remember { mutableStateOf<LibVLC?>(null) }
    var isBuffering by remember { mutableStateOf(true) }
    var hasEnded by remember { mutableStateOf(false) }

    var currentScaleMode by remember { mutableStateOf(VideoScaleMode.BEST_FIT) }
    var currentScale by remember(episode.id) { mutableFloatStateOf(1.0f) }
    var scaleIndicatorText by remember { mutableStateOf<String?>(null) }

    var currentPositionMs by remember(episode.id) { mutableLongStateOf(0L) }
    var durationMs by remember(episode.id) { mutableLongStateOf(episode.durationMs) }

    // Intercept back button: In landscape, switch to portrait; in portrait, close player
    BackHandler {
        if (isLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            onClosePlayer()
        }
    }

    fun triggerIndicator(text: String) {
        scaleIndicatorText = text
        coroutineScope.launch {
            delay(1800)
            if (scaleIndicatorText == text) {
                scaleIndicatorText = null
            }
        }
    }

    fun applyScaleMode(mode: VideoScaleMode) {
        currentScaleMode = mode
        val player = mediaPlayer
        when (mode) {
            VideoScaleMode.BEST_FIT -> {
                currentScale = 1.0f
                player?.aspectRatio = null
                triggerIndicator("Original (Fit)")
            }
            VideoScaleMode.ZOOM_CROP -> {
                currentScale = 1.34f
                player?.aspectRatio = null
                triggerIndicator("Zoomed (Crop Bars)")
            }
            VideoScaleMode.STRETCH -> {
                currentScale = 1.0f
                player?.aspectRatio = "20:9"
                triggerIndicator("Stretch to Screen")
            }
        }
    }

    fun loadAndPlayStream(startPositionMs: Long = 0L) {
        val player = mediaPlayer ?: return
        val vlc = libVLCInstance ?: return

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val streamUrl = viewModel.getStreamUrlForEpisode(episode.id)
                val token = viewModel.getFreshGoogleDriveAccessToken()

                if (streamUrl.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Stream link resolution failed", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val downloadRepo = com.clotube.app.data.DownloadRepository(context)
                val localFile = downloadRepo.getLocalFileIfDownloaded(episode.id)
                val media = if (localFile != null && localFile.exists()) {
                    Media(vlc, Uri.fromFile(localFile))
                } else {
                    Media(vlc, Uri.parse(streamUrl)).apply {
                        if (!token.isNullOrBlank()) {
                            addOption(":http-header=Authorization: Bearer $token")
                        }
                    }
                }.apply {
                    addOption(":network-caching=3000")
                    addOption(":http-reconnect")
                    if (startPositionMs > 0L) {
                        addOption(":start-time=${startPositionMs / 1000}")
                    }
                    setHWDecoderEnabled(true, true)
                }

                withContext(Dispatchers.Main) {
                    hasEnded = false
                    player.media = media
                    media.release()
                    player.play()
                }
            } catch (e: Exception) {
                Log.e("ClotubePlayer", "Playback launch error: ${e.message}", e)
            }
        }
    }

    DisposableEffect(episode.id) {
        onDispose {
            try {
                mediaPlayer?.let { player ->
                    player.setEventListener(null)
                    player.detachViews()
                    player.stop()
                    player.release()
                }
                libVLCInstance?.release()
                mediaPlayer = null
                libVLCInstance = null
            } catch (t: Throwable) {
                Log.e("ClotubePlayer", "Player disposal error: ${t.message}")
            }
        }
    }

    LaunchedEffect(mediaPlayer) {
        val player = mediaPlayer ?: return@LaunchedEffect
        while (true) {
            if (player.isPlaying) {
                isBuffering = false
                hasEnded = false
            } else if (player.length > 0L && player.time >= player.length - 1500L) {
                hasEnded = true
                isBuffering = false
            }
            val t = player.time
            if (t >= 0L) {
                currentPositionMs = t
            }
            val len = player.length
            if (len > 0L) {
                durationMs = len
            }
            delay(250L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1.0f) {
                        val newScale = (currentScale * zoom).coerceIn(1.0f, 2.0f)
                        currentScale = newScale
                        currentScaleMode = if (newScale > 1.05f) VideoScaleMode.ZOOM_CROP else VideoScaleMode.BEST_FIT
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (currentScale > 1.05f) {
                            applyScaleMode(VideoScaleMode.BEST_FIT)
                        } else {
                            applyScaleMode(VideoScaleMode.ZOOM_CROP)
                        }
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                VLCVideoLayout(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    val vlc = LibVLC(
                        ctx,
                        arrayListOf(
                            "--network-caching=3000",
                            "--http-reconnect",
                            "--no-drop-late-frames",
                            "--no-skip-frames",
                            "--rtsp-tcp"
                        )
                    )
                    libVLCInstance = vlc

                    val player = MediaPlayer(vlc)
                    mediaPlayer = player

                    var isAttached = false
                    val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                        val w = right - left
                        val h = bottom - top
                        if (w > 0 && h > 0) {
                            if (!isAttached) {
                                player.attachViews(this, null, false, false)
                                isAttached = true
                            } else if (w != (oldRight - oldLeft) || h != (oldBottom - oldTop)) {
                                player.updateVideoSurfaces()
                            }
                        }
                    }
                    addOnLayoutChangeListener(layoutListener)

                    post {
                        if (!isAttached && width > 0 && height > 0) {
                            player.attachViews(this, null, false, false)
                            isAttached = true
                        }
                    }

                    player.setEventListener { event ->
                        when (event.type) {
                            MediaPlayer.Event.Buffering -> isBuffering = (event.buffering < 100f)
                            MediaPlayer.Event.Playing -> {
                                isBuffering = false
                                hasEnded = false
                            }
                            MediaPlayer.Event.EndReached -> {
                                isBuffering = false
                                hasEnded = true
                            }
                            MediaPlayer.Event.EncounteredError -> isBuffering = false
                            MediaPlayer.Event.TimeChanged -> {
                                currentPositionMs = event.timeChanged.coerceAtLeast(0L)
                            }
                            MediaPlayer.Event.LengthChanged -> {
                                if (event.lengthChanged > 0L) {
                                    durationMs = event.lengthChanged
                                }
                            }
                        }
                    }

                    loadAndPlayStream(0L)
                }
            },
            update = { /* State driven */ },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = currentScale
                    scaleY = currentScale
                }
        )

        if (isBuffering) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF00E5FF),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = scaleIndicatorText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            scaleIndicatorText?.let { text ->
                Box(
                    modifier = Modifier
                        .background(Color(0xCC0E1A2B), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = text,
                        color = Color(0xFF00E5FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        mediaPlayer?.let { player ->
            PlayerControlsOverlay(
                mediaPlayer = player,
                durationMs = if (durationMs > 0L) durationMs else episode.durationMs,
                currentPositionMs = currentPositionMs,
                episodeTitle = "S${episode.seasonNumber.toString().padStart(2, '0')}:E${episode.episodeNumber.toString().padStart(2, '0')} • ${episode.displayTitle}",
                onSeekTo = { targetMs ->
                    if (hasEnded || (player.length > 0 && targetMs >= player.length - 1500L)) {
                        loadAndPlayStream(targetMs)
                    } else {
                        player.time = targetMs
                        currentPositionMs = targetMs
                        if (!player.isPlaying) player.play()
                    }
                },
                onClosePlayer = onClosePlayer
            )
        }
    }
}

@Composable
fun ClotubeVideoPlayer(
    mediaPlayer: MediaPlayer,
    mediaId: String = "",
    title: String,
    thumbnailUrl: String? = null,
    fallbackDurationMs: Long = 0L,
    initialPositionMs: Long = 0L,
    modifier: Modifier = Modifier,
    isInitiallyFullscreen: Boolean = false,
    isFullscreen: Boolean = isInitiallyFullscreen,
    onPreviousEpisode: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    nextEpisode: Episode? = null,
    onPlayNextEpisode: ((Episode) -> Unit)? = null,
    nextEpisodeTitle: String? = null,
    onFullscreenChanged: ((Boolean) -> Unit)? = null,
    onIsPlayingChanged: ((Boolean) -> Unit)? = null,
    onPlaybackEnded: (() -> Unit)? = null,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val density = LocalDensity.current

    var isAppForegrounded by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
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
    var isFirstFrameRendered by remember(mediaId) { mutableStateOf(false) }
    var lastAttachedLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    var autoHideJob by remember { mutableStateOf<Job?>(null) }

    // 1. LIFECYCLE CONTROLLER: Intercept backgrounding and stop playback leaks
    DisposableEffect(lifecycleOwner, mediaPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isAppForegrounded = true
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    isAppForegrounded = false
                    if (mediaPlayer.isPlaying) {
                        try {
                            mediaPlayer.pause()
                        } catch (_: Exception) {}
                    }
                    isPlaying = false
                }
                Lifecycle.Event.ON_DESTROY -> {
                    isAppForegrounded = false
                    try {
                        mediaPlayer.detachViews()
                    } catch (_: Exception) {}
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
            try {
                mediaPlayer.detachViews()
            } catch (_: Exception) {}
            try {
                mediaPlayer.stop()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(isPlaying) {
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

    // Reset loading state whenever mediaId changes
    LaunchedEffect(mediaId) {
        isBuffering = true
        isSeeking = false
        isFirstFrameRendered = false
        val len = mediaPlayer.length
        durationMs = if (len > 0L) len else fallbackDurationMs
        if (currentPositionMs == 0L && initialPositionMs > 0L) {
            currentPositionMs = initialPositionMs
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

    // Scrubbing States
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

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

    val coroutineScope = rememberCoroutineScope()
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
    var currentSpeed by remember { mutableFloatStateOf(mediaPlayer.rate) }

    // Synchronize LibVLC Events (Handling Buffering & Opening)
    DisposableEffect(mediaPlayer, mediaId) {
        val listener = MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    isBuffering = true
                    val isCurrentlyResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    if (!isAppForegrounded || !isCurrentlyResumed) {
                        try {
                            mediaPlayer.pause()
                        } catch (_: Exception) {}
                        isPlaying = false
                    }
                }
                MediaPlayer.Event.Buffering -> {
                    isBuffering = event.buffering < 100f
                    if (event.buffering >= 100f) {
                        isSeeking = false
                        val isCurrentlyResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        if (!isAppForegrounded || !isCurrentlyResumed) {
                            try {
                                mediaPlayer.pause()
                            } catch (_: Exception) {}
                            isPlaying = false
                        } else if (mediaPlayer.isPlaying) {
                            isFirstFrameRendered = true
                        }
                    }
                }
                MediaPlayer.Event.Playing -> {
                    // Safety enforcement: If VLC started playing while backgrounded, pause immediately
                    val isCurrentlyResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    if (!isAppForegrounded || !isCurrentlyResumed) {
                        try {
                            mediaPlayer.pause()
                        } catch (_: Exception) {}
                        isPlaying = false
                    } else {
                        isPlaying = true
                        isBuffering = false
                        isSeeking = false
                        if (mediaPlayer.time > 0L) {
                            isFirstFrameRendered = true
                        }
                    }
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                    isPlaying = false
                }
                MediaPlayer.Event.TimeChanged -> {
                    isBuffering = false
                    isSeeking = false
                    isFirstFrameRendered = true
                    if (!isScrubbing) {
                        currentPositionMs = event.timeChanged.coerceAtLeast(0L)
                    }
                }
                MediaPlayer.Event.LengthChanged -> {
                    if (event.lengthChanged > 0L) {
                        durationMs = event.lengthChanged
                    }
                }
                MediaPlayer.Event.EndReached -> {
                    isPlaying = false
                    isBuffering = false
                    isSeeking = false
                    onPlaybackEnded?.invoke()
                }
            }
        }
        mediaPlayer.setEventListener(listener)
        onDispose {
            try {
                mediaPlayer.setEventListener(null)
                mediaPlayer.detachViews()
                mediaPlayer.stop()
            } catch (t: Throwable) {
                android.util.Log.e("ClotubePlayer", "Safe LibVLC release error: ${t.message}")
            }
            lastAttachedLayout = null
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
    LaunchedEffect(mediaPlayer, isPlaying, isScrubbing) {
        while (true) {
            if (!isScrubbing) {
                val currentTime = mediaPlayer.time.coerceAtLeast(0L)
                currentPositionMs = currentTime
                val len = mediaPlayer.length
                if (len > 0L) durationMs = len
                val currentlyPlaying = mediaPlayer.isPlaying
                isPlaying = currentlyPlaying
                if (currentlyPlaying && currentTime > 0L && !isSeeking) {
                    // Failsafe: if video is actively playing frames, dismiss loading spinner & placeholder immediately
                    isFirstFrameRendered = true
                    isBuffering = false
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

    BackHandler(enabled = currentFullscreen) {
        internalFullscreen = false
        onFullscreenChanged?.invoke(false)
    }

    val activeFraction = if (isScrubbing) {
        scrubFraction
    } else if (durationMs > 0L) {
        (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val displayPositionMs = if (isScrubbing) (scrubFraction * durationMs).toLong() else currentPositionMs
    val remainingMs = (durationMs - currentPositionMs).coerceAtLeast(0L)
    val showFloatingNext = false

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

                    var isViewsAttached = false

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
                                mediaPlayer.attachViews(this, null, false, false)
                                mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                                mediaPlayer.aspectRatio = "16:9"
                                isViewsAttached = true
                                lastAttachedLayout = this
                            } else if (newWidth != oldWidth || newHeight != oldHeight) {
                                mediaPlayer.updateVideoSurfaces()
                            }
                        }
                    }
                    addOnLayoutChangeListener(layoutListener)

                    post {
                        if (!isViewsAttached && width > 0 && height > 0) {
                            try {
                                mediaPlayer.detachViews()
                            } catch (_: Exception) {}
                            mediaPlayer.attachViews(this, null, false, false)
                            mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                            mediaPlayer.aspectRatio = "16:9"
                            isViewsAttached = true
                            lastAttachedLayout = this
                        }
                    }
                }
            },
            update = { layout ->
                if (lastAttachedLayout != layout && layout.width > 0 && layout.height > 0) {
                    try {
                        mediaPlayer.detachViews()
                        mediaPlayer.attachViews(layout, null, false, false)
                        lastAttachedLayout = layout
                    } catch (_: Exception) {}
                }
                mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                mediaPlayer.aspectRatio = "16:9"
            },
            onReset = { layout ->
                try {
                    mediaPlayer.detachViews()
                } catch (_: Exception) {}
                lastAttachedLayout = null
            },
            onRelease = { layout ->
                try {
                    mediaPlayer.detachViews()
                } catch (_: Exception) {}
                lastAttachedLayout = null
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
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                nextEpisode = nextEpisode,
                onPlayNext = { ep ->
                    onPlayNextEpisode?.invoke(ep) ?: onNextEpisode?.invoke()
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .then(if (currentFullscreen) Modifier.navigationBarsPadding() else Modifier)
                    .padding(end = 16.dp, bottom = if (currentFullscreen) 80.dp else 16.dp)
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
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
                            resetInactivityTimer()
                            if (currentFullscreen) {
                                internalFullscreen = false
                                onFullscreenChanged?.invoke(false)
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

                        val hasSubtitlesEnabled = remember(mediaPlayer.spuTrack) { mediaPlayer.spuTrack != -1 }
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
                            mediaPlayer.time = newTime
                            currentPositionMs = newTime
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
                                        mediaPlayer.pause()
                                        isPlaying = false
                                        autoHideJob?.cancel() // Keep controls visible while paused
                                    } else {
                                        mediaPlayer.play()
                                        isPlaying = true
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
                            mediaPlayer.time = newTime
                            currentPositionMs = newTime
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
                            .pointerInput(durationMs) {
                                detectTapGestures(
                                    onPress = { offset ->
                                        if (durationMs > 0L && size.width > 0) {
                                            resetInactivityTimer()
                                            isScrubbing = true
                                            isSeeking = true
                                            val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                            scrubFraction = fraction

                                            val released = tryAwaitRelease()
                                            if (released) {
                                                isBuffering = true
                                                isSeeking = true
                                                val targetMs = calculateSafeSeekTargetMs(scrubFraction, durationMs)
                                                mediaPlayer.time = targetMs
                                                currentPositionMs = targetMs
                                                isScrubbing = false
                                                resetInactivityTimer()
                                            }
                                        }
                                    }
                                )
                            }
                            .pointerInput(durationMs) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        if (durationMs > 0L && size.width > 0) {
                                            resetInactivityTimer()
                                            isScrubbing = true
                                            isSeeking = true
                                            scrubFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        if (durationMs > 0L && size.width > 0) {
                                            resetInactivityTimer()
                                            isSeeking = true
                                            scrubFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        }
                                    },
                                    onDragEnd = {
                                        if (durationMs > 0L) {
                                            isBuffering = true
                                            isSeeking = true
                                            val targetMs = calculateSafeSeekTargetMs(scrubFraction, durationMs)
                                            mediaPlayer.time = targetMs
                                            currentPositionMs = targetMs
                                        }
                                        isScrubbing = false
                                        resetInactivityTimer()
                                    },
                                    onDragCancel = {
                                        isScrubbing = false
                                        isSeeking = false
                                        resetInactivityTimer()
                                    }
                                )
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
        val spuTracks = remember(mediaPlayer) {
            val tracks = mediaPlayer.spuTracks ?: emptyArray()
            val currentId = mediaPlayer.spuTrack
            tracks.filter {
                it.id != -1 && !it.name.equals("Off", ignoreCase = true) && !it.name.equals("Disable", ignoreCase = true)
            }.map { TrackInfoItem(it.id, it.name, it.id == currentId) }
        }
        val isAnySubtitleSelected = mediaPlayer.spuTrack != -1

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
        val audioTracks = remember(mediaPlayer) {
            val tracks = mediaPlayer.audioTracks ?: emptyArray()
            val currentId = mediaPlayer.audioTrack
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
