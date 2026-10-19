package com.clotube.app.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.BrightnessLow
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.annotation.Keep
enum class PlayerGestureMode {
    BRIGHTNESS,
    VOLUME
}

@Stable
interface SystemVolumeState {
    val volumeFraction: Float
    fun onDragStarted() {}
    fun onDragStopped() {}
    fun setVolumeFraction(fraction: Float)
}

@Composable
fun PlayerDualGestureOverlay(
    volumeState: SystemVolumeState,
    isFullscreen: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context as? Activity }
    val coroutineScope = rememberCoroutineScope()

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var showOverlay by remember { mutableStateOf(false) }
    var activeGestureMode by remember { mutableStateOf<PlayerGestureMode?>(null) }
    var dismissJob by remember { mutableStateOf<Job?>(null) }

    // Brightness fraction (0.0f to 1.0f)
    var brightnessFraction by remember {
        val windowBrightness = activity?.window?.attributes?.screenBrightness ?: -1f
        mutableFloatStateOf(if (windowBrightness in 0f..1f) windowBrightness else 0.5f)
    }

    fun setScreenBrightness(fraction: Float) {
        val clamped = fraction.coerceIn(0.01f, 1.0f)
        brightnessFraction = clamped
        activity?.let { act ->
            val layoutParams = act.window.attributes
            layoutParams.screenBrightness = clamped
            act.window.attributes = layoutParams
        }
    }

    fun scheduleDismiss() {
        dismissJob?.cancel()
        dismissJob = coroutineScope.launch {
            delay(1200)
            showOverlay = false
            delay(300) // Wait for fade-out animation to finish
            activeGestureMode = null // Safe to reset identity only after overlay is completely invisible
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(isFullscreen, containerSize) {
                if (!isFullscreen || containerSize.width == 0 || containerSize.height == 0) return@pointerInput

                var accumulatedVolume = volumeState.volumeFraction
                var accumulatedBrightness = brightnessFraction

                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        dismissJob?.cancel()
                        val isLeftHalf = offset.x < (containerSize.width / 2f)

                        // Lock gesture identity on initial touch
                        activeGestureMode = if (isLeftHalf) {
                            accumulatedBrightness = brightnessFraction
                            PlayerGestureMode.BRIGHTNESS
                        } else {
                            accumulatedVolume = volumeState.volumeFraction
                            volumeState.onDragStarted()
                            PlayerGestureMode.VOLUME
                        }
                        showOverlay = true
                    },
                    onDragEnd = {
                        if (activeGestureMode == PlayerGestureMode.VOLUME) {
                            volumeState.onDragStopped()
                        }
                        // DO NOT reset activeGestureMode here! Keep it locked for the dismissal animation.
                        scheduleDismiss()
                    },
                    onDragCancel = {
                        if (activeGestureMode == PlayerGestureMode.VOLUME) {
                            volumeState.onDragStopped()
                        }
                        scheduleDismiss()
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val delta = -dragAmount / (containerSize.height * 0.75f)

                        when (activeGestureMode) {
                            PlayerGestureMode.BRIGHTNESS -> {
                                accumulatedBrightness = (accumulatedBrightness + delta).coerceIn(0.01f, 1.0f)
                                setScreenBrightness(accumulatedBrightness)
                                showOverlay = true
                            }
                            PlayerGestureMode.VOLUME -> {
                                accumulatedVolume = (accumulatedVolume + delta).coerceIn(0f, 1f)
                                volumeState.setVolumeFraction(accumulatedVolume)
                                showOverlay = true
                            }
                            null -> Unit
                        }
                    }
                )
            }
    ) {
        content()

        // Center HUD Pill
        AnimatedVisibility(
            visible = showOverlay && activeGestureMode != null && isFullscreen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            when (activeGestureMode) {
                PlayerGestureMode.BRIGHTNESS -> {
                    CenteredGesturePill(
                        valueFraction = brightnessFraction,
                        mode = PlayerGestureMode.BRIGHTNESS
                    )
                }
                PlayerGestureMode.VOLUME -> {
                    CenteredGesturePill(
                        valueFraction = volumeState.volumeFraction,
                        mode = PlayerGestureMode.VOLUME
                    )
                }
                null -> Unit
            }
        }
    }
}

@Composable
fun CenteredGesturePill(
    valueFraction: Float,
    mode: PlayerGestureMode,
    modifier: Modifier = Modifier
) {
    val icon = when (mode) {
        PlayerGestureMode.BRIGHTNESS -> when {
            valueFraction < 0.33f -> Icons.Rounded.BrightnessLow
            valueFraction < 0.66f -> Icons.Rounded.BrightnessMedium
            else -> Icons.Rounded.BrightnessHigh
        }
        PlayerGestureMode.VOLUME -> when {
            valueFraction <= 0.01f -> Icons.AutoMirrored.Rounded.VolumeMute
            valueFraction < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
            else -> Icons.AutoMirrored.Rounded.VolumeUp
        }
    }

    Box(
        modifier = modifier
            .width(56.dp)
            .height(190.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xD90B0E14))
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxHeight()
        ) {
            Text(
                text = "${(valueFraction.coerceIn(0f, 1f) * 100).toInt()}%",
                color = Color.White,
                fontSize = 12.sp,
                maxLines = 1
            )

            // Vertical Level Bar
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF1E2638)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(valueFraction.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(0xFF00E5FF))
                )
            }

            Icon(
                imageVector = icon,
                contentDescription = if (mode == PlayerGestureMode.BRIGHTNESS) "Brightness" else "Volume",
                tint = Color(0xFF00E5FF),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
