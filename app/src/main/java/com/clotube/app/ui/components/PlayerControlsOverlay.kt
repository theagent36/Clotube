package com.clotube.app.ui.components

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.app.util.triggerNavigationHaptic
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.clotube.app.model.Episode
import org.videolan.libvlc.MediaPlayer

@Composable
fun PlayerControlsOverlay(
    mediaPlayer: MediaPlayer,
    durationMs: Long,
    currentPositionMs: Long,
    episodeTitle: String,
    onSeekTo: (Long) -> Unit,
    onClosePlayer: () -> Unit,
    modifier: Modifier = Modifier,
    nextEpisode: Episode? = null,
    onPlayNext: ((Episode) -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val coroutineScope = rememberCoroutineScope()

    var isVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(mediaPlayer.isPlaying) }
    var isPlaybackEnded by remember { mutableStateOf(false) }
    var hasStartedOnce by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<Job?>(null) }

    // 1. Local dragging state isolation: decouples slider thumb & timestamps from LibVLC TimeChanged events
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var playbackPositionMs by remember(currentPositionMs) { mutableLongStateOf(currentPositionMs) }

    val totalDurationMs = if (durationMs > 0L) durationMs else mediaPlayer.length.coerceAtLeast(1L)
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
        isVisible = true
        hideJob = coroutineScope.launch {
            delay(3500)
            isVisible = false
        }
    }

    LaunchedEffect(Unit) {
        resetTimer()
    }

    BackHandler {
        if (isLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            onClosePlayer()
        }
    }

    // 2. LibVLC Event Synchronization with Dragging Isolation
    DisposableEffect(mediaPlayer) {
        val listener = MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    isPlaying = true
                    isPlaybackEnded = false
                    hasStartedOnce = true
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> isPlaying = false
                MediaPlayer.Event.EndReached -> {
                    isPlaying = false
                    isPlaybackEnded = true
                }
                MediaPlayer.Event.TimeChanged -> {
                    if (event.timeChanged > 0L) {
                        isPlaying = true
                        hasStartedOnce = true
                    }
                    // Strictly ignore TimeChanged events while user is dragging the slider to eliminate jitter
                    if (!isDragging) {
                        playbackPositionMs = event.timeChanged.coerceAtLeast(0L)
                    }
                }
            }
        }
        mediaPlayer.setEventListener(listener)
        onDispose {
            mediaPlayer.setEventListener(null)
        }
    }

    val insetModifier = if (isPortrait) {
        Modifier.statusBarsPadding()
    } else {
        Modifier.windowInsetsPadding(WindowInsets.displayCutout)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (isVisible) {
                    isVisible = false
                    hideJob?.cancel()
                } else {
                    resetTimer()
                }
            }
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xCC000000),
                                Color.Transparent,
                                Color(0xE6000000)
                            )
                        )
                    )
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .then(insetModifier)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = episodeTitle,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                // Center Console Play/Pause
                Box(
                    modifier = Modifier.align(Alignment.Center),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = {
                            resetTimer()
                            if (mediaPlayer.isPlaying) {
                                isPlaying = false
                                mediaPlayer.pause()
                            } else {
                                val totalDur = if (totalDurationMs > 0L) totalDurationMs else mediaPlayer.length
                                val isEofOrEnded = isPlaybackEnded ||
                                        (!mediaPlayer.isPlaying && mediaPlayer.time <= 0L && hasStartedOnce) ||
                                        (totalDur > 0L && currentTimeMs >= totalDur - 1500L)
                                if (isEofOrEnded) {
                                    val startMs = if (totalDur > 0L && currentTimeMs >= totalDur - 5000L) 0L else currentTimeMs.coerceAtLeast(0L)
                                    playbackPositionMs = startMs
                                    isPlaybackEnded = false
                                    isPlaying = true
                                    onSeekTo(startMs)
                                } else {
                                    isPlaying = true
                                    mediaPlayer.play()
                                }
                            }
                        },
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E5FF))
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color(0xFF07090E),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Bottom Scrubber with Isolated Slider Drag State
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
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
                            thumbColor = Color(0xFF00E5FF),
                            activeTrackColor = Color(0xFF00E5FF),
                            inactiveTrackColor = Color(0x66455A64)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTime(displayedCurrentTimeMs),
                            color = Color(0xFFB0BEC5),
                            fontSize = 12.sp
                        )
                        Text(
                            text = formatTime(totalDurationMs),
                            color = Color(0xFFB0BEC5),
                            fontSize = 12.sp
                        )
                    }
                }

                // Floating Next Episode Auto-Play Card at 30 seconds
                if (nextEpisode != null) {
                    NextEpisodeCountdownCard(
                        currentPositionMs = displayedCurrentTimeMs,
                        durationMs = totalDurationMs,
                        isPlaying = isPlaying,
                        nextEpisode = nextEpisode,
                        onPlayNext = { ep ->
                            onPlayNext?.invoke(ep)
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 16.dp, bottom = 80.dp),
                        isScrubbing = isDragging
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    val m = sec / 60
    val s = sec % 60
    return String.format(java.util.Locale.US, "%02d:%02d", m, s)
}
