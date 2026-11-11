package com.clotube.app.ui.components

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.app.model.Episode
import com.clotube.app.ui.theme.*
import com.clotube.app.util.triggerClickHaptic
import com.clotube.app.util.triggerNavigationHaptic
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.MediaPlayer

// Defensive LibVLC accessors to prevent "can't get VLCObject instance" crashes
val MediaPlayer?.safeTime: Long
    get() = try {
        if (this != null && !isReleased) time else 0L
    } catch (_: Throwable) {
        0L
    }

val MediaPlayer?.safeLength: Long
    get() = try {
        if (this != null && !isReleased) length else 0L
    } catch (_: Throwable) {
        0L
    }

val MediaPlayer?.safeIsPlaying: Boolean
    get() = try {
        if (this != null && !isReleased) isPlaying else false
    } catch (_: Throwable) {
        false
    }

val MediaPlayer?.safeRate: Float
    get() = try {
        if (this != null && !isReleased) rate else 1.0f
    } catch (_: Throwable) {
        1.0f
    }

fun MediaPlayer?.safePlay() {
    if (this == null || isReleased) return
    try {
        play()
    } catch (_: Throwable) {}
}

fun MediaPlayer?.safePause() {
    if (this == null || isReleased) return
    try {
        pause()
    } catch (_: Throwable) {}
}

fun MediaPlayer?.safeSetTime(targetMs: Long) {
    if (this == null || isReleased) return
    try {
        time = targetMs
    } catch (_: Throwable) {}
}

fun MediaPlayer?.safeSetRate(targetRate: Float) {
    if (this == null || isReleased) return
    try {
        rate = targetRate
    } catch (_: Throwable) {}
}

fun MediaPlayer?.safeSetAspectRatio(targetRatio: String?) {
    if (this == null || isReleased) return
    try {
        aspectRatio = targetRatio
    } catch (_: Throwable) {}
}

fun safeSetVideoScale(player: MediaPlayer?, scale: MediaPlayer.ScaleType) {
    if (player == null || player.isReleased) return
    try {
        player.videoScale = scale
    } catch (_: Throwable) {}
}

enum class VideoScaleMode(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    STRETCH("Stretch")
}

enum class SeekRippleDirection {
    FORWARD,
    BACKWARD
}

/**
 * Minimalist Cinema Player HUD matching Apple TV+ and Netflix.
 * Features:
 * - Edge-to-edge Top Action Bar with Episode Title and Quick Settings gear.
 * - Center Console with frosted glass Play/Pause spring circle and ±10s skip buttons.
 * - Floating Bottom Scrubber with cobalt/white track and monospace elapsed/countdown timestamps.
 * - 3.5s Inactivity Auto-Fade, single-tap HUD toggle, and double-tap ±10s seek ripple.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerControlsOverlay(
    mediaPlayer: MediaPlayer,
    durationMs: Long,
    currentPositionMs: Long,
    episodeTitle: String,
    onSeekTo: (Long) -> Unit,
    onClosePlayer: () -> Unit,
    onBackClick: () -> Unit = onClosePlayer,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    nextEpisode: Episode? = null,
    onPlayNext: ((Episode) -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val coroutineScope = rememberCoroutineScope()

    fun getSafeCurrentTime(): Long = try {
        if (!mediaPlayer.isReleased) mediaPlayer.time else currentPositionMs
    } catch (_: Throwable) {
        currentPositionMs
    }

    val defaultToggleFullscreen: () -> Unit = {
        activity?.let { act ->
            act.requestedOrientation = if (isLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }
    val handleToggleFullscreen = onToggleFullscreen ?: defaultToggleFullscreen

    var isVisible by remember { mutableStateOf(true) }
    var internalBuffering by remember { mutableStateOf(isBuffering) }
    LaunchedEffect(isBuffering) {
        internalBuffering = isBuffering
    }
    val effectiveBuffering = isBuffering || internalBuffering

    var isPlaying by remember { mutableStateOf(mediaPlayer.safeIsPlaying) }
    var isPlaybackEnded by remember { mutableStateOf(false) }
    var hasStartedOnce by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    var showQuickSettingsSheet by remember { mutableStateOf(false) }
    var currentScaleMode by remember { mutableStateOf(VideoScaleMode.FIT) }
    var lastAppliedScaleMode by remember { mutableStateOf<VideoScaleMode?>(null) }

    LaunchedEffect(currentScaleMode) {
        if (lastAppliedScaleMode == null) {
            lastAppliedScaleMode = currentScaleMode
            return@LaunchedEffect
        }
        if (lastAppliedScaleMode == currentScaleMode) return@LaunchedEffect
        lastAppliedScaleMode = currentScaleMode
        when (currentScaleMode) {
            VideoScaleMode.FIT -> {
                safeSetVideoScale(mediaPlayer, MediaPlayer.ScaleType.SURFACE_BEST_FIT)
                mediaPlayer.safeSetAspectRatio(null)
            }
            VideoScaleMode.FILL -> {
                safeSetVideoScale(mediaPlayer, MediaPlayer.ScaleType.SURFACE_FIT_SCREEN)
                mediaPlayer.safeSetAspectRatio(null)
            }
            VideoScaleMode.STRETCH -> {
                safeSetVideoScale(mediaPlayer, MediaPlayer.ScaleType.SURFACE_FILL)
                mediaPlayer.safeSetAspectRatio(null)
            }
        }
        try {
            if (!mediaPlayer.isReleased) mediaPlayer.updateVideoSurfaces()
        } catch (_: Throwable) {}
    }

    // Quick Double-Tap Seek Ripple Animation
    var seekRippleDirection by remember { mutableStateOf<SeekRippleDirection?>(null) }
    var seekRippleTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(seekRippleTrigger) {
        if (seekRippleTrigger > 0) {
            delay(650)
            seekRippleDirection = null
        }
    }

    // Local dragging state isolation: decouples slider thumb & timestamps from LibVLC events
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var playbackPositionMs by remember(currentPositionMs) { mutableLongStateOf(currentPositionMs) }

    val totalDurationMs = if (durationMs > 0L) durationMs else mediaPlayer.safeLength.coerceAtLeast(1L)
    val currentTimeMs = playbackPositionMs

    val actualFraction = if (totalDurationMs > 0L) {
        (currentTimeMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val displayFraction = if (isDragging) dragFraction else actualFraction
    val displayedCurrentTimeMs = if (isDragging) {
        (dragFraction * totalDurationMs).toLong()
    } else currentTimeMs

    fun resetTimer() {
        hideJob?.cancel()
        if (!effectiveBuffering && isPlaying && !isDragging && !showQuickSettingsSheet) {
            hideJob = coroutineScope.launch {
                delay(3500)
                isVisible = false
            }
        }
    }

    LaunchedEffect(isVisible, isPlaying, effectiveBuffering, isDragging, showQuickSettingsSheet) {
        if (isVisible && !effectiveBuffering) {
            resetTimer()
        } else {
            hideJob?.cancel()
        }
    }

    BackHandler(enabled = true) {
        if (isLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            onClosePlayer()
        }
    }

    // LibVLC Event Synchronization with Dragging Isolation
    DisposableEffect(mediaPlayer) {
        val listener = MediaPlayer.EventListener { event ->
            if (mediaPlayer.isReleased) return@EventListener
            try {
                when (event.type) {
                    MediaPlayer.Event.Buffering -> {
                        internalBuffering = event.buffering < 100f
                    }
                    MediaPlayer.Event.Playing -> {
                        isPlaying = true
                        isPlaybackEnded = false
                        hasStartedOnce = true
                        internalBuffering = false
                    }
                    MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> isPlaying = false
                    MediaPlayer.Event.EndReached -> {
                        isPlaying = false
                        isPlaybackEnded = true
                        internalBuffering = false
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        internalBuffering = false
                    }
                    MediaPlayer.Event.TimeChanged -> {
                        if (event.timeChanged > 0L) {
                            isPlaying = true
                            hasStartedOnce = true
                            internalBuffering = false
                        }
                        if (!isDragging && !mediaPlayer.isReleased) {
                            playbackPositionMs = event.timeChanged.coerceAtLeast(0L)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        if (!mediaPlayer.isReleased) {
            try {
                mediaPlayer.setEventListener(listener)
            } catch (_: Throwable) {}
        }
        onDispose {
            try {
                mediaPlayer.setEventListener(null)
            } catch (_: Throwable) {}
        }
    }

    fun seekDelta(deltaMs: Long) {
        view.triggerClickHaptic()
        val current = playbackPositionMs
        val target = (current + deltaMs).coerceIn(0L, totalDurationMs)
        playbackPositionMs = target
        onSeekTo(target)
        resetTimer()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        isVisible = !isVisible
                        if (isVisible && !effectiveBuffering) resetTimer() else hideJob?.cancel()
                    },
                    onDoubleTap = { offset ->
                        val isLeft = offset.x < size.width / 2f
                        if (isLeft) {
                            seekDelta(-10_000L)
                            seekRippleDirection = SeekRippleDirection.BACKWARD
                            seekRippleTrigger++
                        } else {
                            seekDelta(10_000L)
                            seekRippleDirection = SeekRippleDirection.FORWARD
                            seekRippleTrigger++
                        }
                    }
                )
            }
    ) {
        // Double-Tap Seek Ripple Animation
        AnimatedVisibility(
            visible = seekRippleDirection != null,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (seekRippleDirection == SeekRippleDirection.BACKWARD) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.42f)
                            .align(Alignment.CenterStart)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0x55FFFFFF), Color.Transparent)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "Rewind 10 seconds",
                                tint = Color.White,
                                modifier = Modifier.size(46.dp)
                            )
                            Text(
                                text = "10 seconds",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else if (seekRippleDirection == SeekRippleDirection.FORWARD) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.42f)
                            .align(Alignment.CenterEnd)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color(0x55FFFFFF))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "Forward 10 seconds",
                                tint = Color.White,
                                modifier = Modifier.size(46.dp)
                            )
                            Text(
                                text = "10 seconds",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Centered Buffering Indicator when HUD is hidden
        if (effectiveBuffering && !isVisible) {
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

        // HUD Controls Container
        AnimatedVisibility(
            visible = isVisible || isDragging,
            enter = fadeIn(animationSpec = tween(240, easing = AppleTvMotion.SmoothOutEasing)),
            exit = fadeOut(animationSpec = tween(320, easing = AppleTvMotion.FastExitEasing)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xD9000000),
                                Color(0x40000000),
                                Color(0xD9000000)
                            )
                        )
                    )
            ) {
                // ========================================================
                // 1. TOP ACTION BAR
                // ========================================================
                val topBarInsetModifier = if (isLandscape) {
                    Modifier
                } else {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .then(topBarInsetModifier)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { resetTimer() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Back button + Title
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val backInteractionSource = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = {
                                view.triggerNavigationHaptic()
                                resetTimer()
                                if (isLandscape) {
                                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                } else {
                                    onClosePlayer()
                                }
                            },
                            interactionSource = backInteractionSource,
                            modifier = Modifier
                                .appleTvPressable(backInteractionSource, pressedScale = 0.88f)
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = episodeTitle,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Right: Quick Settings + Fullscreen toggle
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val settingsInteractionSource = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = {
                                view.triggerClickHaptic()
                                resetTimer()
                                showQuickSettingsSheet = true
                            },
                            interactionSource = settingsInteractionSource,
                            modifier = Modifier
                                .appleTvPressable(settingsInteractionSource, pressedScale = 0.88f)
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Playback Settings",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        val fullscreenInteractionSource = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = {
                                view.triggerClickHaptic()
                                resetTimer()
                                handleToggleFullscreen()
                            },
                            interactionSource = fullscreenInteractionSource,
                            modifier = Modifier
                                .appleTvPressable(fullscreenInteractionSource, pressedScale = 0.88f)
                                .size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isLandscape || isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                contentDescription = if (isLandscape || isFullscreen) "Exit Fullscreen" else "Enter Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // ========================================================
                // 2. CENTER CONSOLE CONTROLS
                // ========================================================
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { resetTimer() },
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Rewind 10s
                    val rewindInteractionSource = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = { seekDelta(-10_000L) },
                        interactionSource = rewindInteractionSource,
                        modifier = Modifier
                            .appleTvPressable(rewindInteractionSource, pressedScale = 0.88f)
                            .size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "Rewind 10s",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Center Play/Pause Floating Icon
                    val playPauseInteractionSource = remember { MutableInteractionSource() }

                    Box(
                        modifier = Modifier
                            .appleTvPressable(playPauseInteractionSource, pressedScale = 0.88f)
                            .size(64.dp)
                            .clickable(
                                interactionSource = playPauseInteractionSource,
                                indication = null,
                                enabled = !effectiveBuffering
                            ) {
                                view.triggerClickHaptic()
                                resetTimer()
                                if (mediaPlayer.safeIsPlaying) {
                                    isPlaying = false
                                    mediaPlayer.safePause()
                                } else {
                                    val totalDur = if (totalDurationMs > 0L) totalDurationMs else mediaPlayer.safeLength
                                    val isEofOrEnded = isPlaybackEnded ||
                                            (!mediaPlayer.safeIsPlaying && mediaPlayer.safeTime <= 0L && hasStartedOnce) ||
                                            (totalDur > 0L && currentTimeMs >= totalDur - 1500L)
                                    if (isEofOrEnded) {
                                        val startMs = if (totalDur > 0L && currentTimeMs >= totalDur - 5000L) 0L else currentTimeMs.coerceAtLeast(0L)
                                        playbackPositionMs = startMs
                                        isPlaybackEnded = false
                                        isPlaying = true
                                        onSeekTo(startMs)
                                    } else {
                                        isPlaying = true
                                        mediaPlayer.safePlay()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (effectiveBuffering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(44.dp),
                                color = Color.White,
                                strokeWidth = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(44.dp)
                            )
                        }
                    }

                    // Forward 10s
                    val forwardInteractionSource = remember { MutableInteractionSource() }
                    IconButton(
                        onClick = { seekDelta(10_000L) },
                        interactionSource = forwardInteractionSource,
                        modifier = Modifier
                            .appleTvPressable(forwardInteractionSource, pressedScale = 0.88f)
                            .size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "Forward 10s",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // ========================================================
                // 3. FLOATING BOTTOM SCRUBBER HUD
                // ========================================================
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { resetTimer() }
                ) {
                    Slider(
                        value = displayFraction,
                        onValueChange = { frac ->
                            resetTimer()
                            isDragging = true
                            dragFraction = frac.coerceIn(0f, 1f)
                        },
                        onValueChangeFinished = {
                            val targetMs = (dragFraction * totalDurationMs).toLong()
                            onSeekTo(targetMs)
                            playbackPositionMs = targetMs
                            isDragging = false
                            resetTimer()
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = AccentBlue,
                            inactiveTrackColor = Color(0x33FFFFFF)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                    )

                    val remainingMs = (totalDurationMs - displayedCurrentTimeMs).coerceAtLeast(0L)
                    val remainingFormatted = if (totalDurationMs > 0L) "-${formatTime(remainingMs)}" else "--:--"

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(displayedCurrentTimeMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = remainingFormatted,
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Next Episode Auto-Play Card
                if (nextEpisode != null) {
                    NextEpisodeCountdownCard(
                        currentPositionMs = displayedCurrentTimeMs,
                        durationMs = totalDurationMs,
                        isPlaying = isPlaying,
                        nextEpisode = nextEpisode,
                        onPlayNext = { ep -> onPlayNext?.invoke(ep) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                            .padding(end = 16.dp, bottom = 80.dp),
                        isScrubbing = isDragging
                    )
                }
            }
        }
    }

    // ========================================================
    // QUICK SETTINGS MODAL BOTTOM SHEET
    // ========================================================
    if (showQuickSettingsSheet) {
        PlayerQuickSettingsSheet(
            mediaPlayer = mediaPlayer,
            currentSpeed = mediaPlayer.safeRate.takeIf { it > 0f } ?: 1.0f,
            onSpeedChange = { newRate ->
                mediaPlayer.safeSetRate(newRate)
            },
            currentScaleMode = currentScaleMode,
            onScaleModeChange = { newMode ->
                currentScaleMode = newMode
            },
            onDismiss = {
                showQuickSettingsSheet = false
                resetTimer()
            }
        )
    }
}

/**
 * Sleek glass modal bottom sheet containing audio, subtitles, speed, and aspect ratio controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerQuickSettingsSheet(
    mediaPlayer: MediaPlayer,
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    currentScaleMode: VideoScaleMode,
    onScaleModeChange: (VideoScaleMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedAudioTrackId by remember { mutableIntStateOf(mediaPlayer.safeAudioTrack) }
    var selectedSubtitleTrackId by remember { mutableIntStateOf(mediaPlayer.safeSpuTrack) }
    val audioTracks = remember { mediaPlayer.safeAudioTracks?.toList() ?: emptyList() }
    val spuTracks = remember { mediaPlayer.safeSpuTracks?.toList() ?: emptyList() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF210131B),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playback Settings",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFF94A3B8)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 1. Audio Stream Selector
            Text(
                text = "AUDIO STREAM",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = Color(0xFF64748B)
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (audioTracks.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(audioTracks) { track ->
                        val isSelected = (track.id == selectedAudioTrackId)
                        QuickSettingChip(
                            label = track.name.ifBlank { "Track ${track.id}" },
                            isSelected = isSelected,
                            onClick = {
                                selectedAudioTrackId = track.id
                                mediaPlayer.safeSetAudioTrack(track.id)
                            }
                        )
                    }
                }
            } else {
                Text(
                    text = "Default Audio Stream",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Subtitle Selector
            Text(
                text = "SUBTITLES",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = Color(0xFF64748B)
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    QuickSettingChip(
                        label = "Off",
                        isSelected = (selectedSubtitleTrackId == -1),
                        onClick = {
                            selectedSubtitleTrackId = -1
                            mediaPlayer.safeSetSpuTrack(-1)
                        }
                    )
                }
                items(spuTracks) { track ->
                    if (track.id != -1) {
                        val isSelected = (track.id == selectedSubtitleTrackId)
                        QuickSettingChip(
                            label = track.name.ifBlank { "Subtitle ${track.id}" },
                            isSelected = isSelected,
                            onClick = {
                                selectedSubtitleTrackId = track.id
                                mediaPlayer.safeSetSpuTrack(track.id)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3. Playback Speed
            Text(
                text = "PLAYBACK SPEED",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = Color(0xFF64748B)
            )
            Spacer(modifier = Modifier.height(8.dp))
            val speedOptions = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                speedOptions.forEach { speed ->
                    val isSelected = kotlin.math.abs(currentSpeed - speed) < 0.05f
                    val label = if (speed == 1.0f) "1.0x" else "${speed}x"
                    QuickSettingChip(
                        label = label,
                        isSelected = isSelected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSpeedChange(speed)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. Aspect Ratio Toggle (Fit / Fill / Stretch)
            Text(
                text = "ASPECT RATIO",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = Color(0xFF64748B)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                VideoScaleMode.entries.forEach { mode ->
                    val isSelected = (mode == currentScaleMode)
                    QuickSettingChip(
                        label = mode.label,
                        isSelected = isSelected,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onScaleModeChange(mode)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun QuickSettingChip(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) AccentBlue else Color(0xFF181C26),
        border = if (isSelected) null else BorderStroke(1.dp, Color(0x14FFFFFF)),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isSelected) Color.White else Color(0xFF94A3B8),
                maxLines = 1
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    val m = sec / 60
    val s = sec % 60
    return String.format(java.util.Locale.US, "%02d:%02d", m, s)
}
