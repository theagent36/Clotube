package com.clotube.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Ultra-thin, sleek, and minimalist cinema video progress scrubber.
 *
 * Visual specifications:
 * - Idle: 2.5dp ultra-thin track with 8dp crisp white circular thumb.
 * - Active: Smoothly animates to 5dp track height with 14dp thumb, Electric Blue accent ring,
 *   and drop glow.
 * - Invisible touch container: 36dp for comfortable, frustration-free finger interaction.
 */
@Composable
fun SlickVideoScrubber(
    progressFraction: Float, // 0.0f to 1.0f
    isScrubbing: Boolean,
    onScrubStart: () -> Unit,
    onScrubbing: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
    bufferedFraction: Float = 0f,
    activeColor: Color = Color(0xFF2F80ED),
    inactiveColor: Color = Color.White.copy(alpha = 0.20f),
    bufferedColor: Color = Color.White.copy(alpha = 0.35f),
    thumbColor: Color = Color.White
) {
    // Dynamic height and thumb expansion on drag
    val trackHeight by animateDpAsState(
        targetValue = if (isScrubbing) 5.dp else 2.5.dp,
        animationSpec = tween(durationMillis = 150),
        label = "trackHeight"
    )

    val thumbRadius by animateDpAsState(
        targetValue = if (isScrubbing) 7.dp else 4.dp,
        animationSpec = tween(durationMillis = 150),
        label = "thumbRadius"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp) // Generous invisible touch container
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val width = size.width.toFloat()
                    if (width > 0f) {
                        var currentFrac = (down.position.x / width).coerceIn(0f, 1f)
                        onScrubStart()
                        onScrubbing(currentFrac)
                        val pointerId = down.id
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                // If all pointers lifted, finish scrubbing immediately
                                if (event.changes.none { it.pressed }) {
                                    break
                                }
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                if (change == null || !change.pressed) {
                                    change?.consume()
                                    break
                                }
                                change.consume()
                                currentFrac = (change.position.x / width).coerceIn(0f, 1f)
                                onScrubbing(currentFrac)
                            }
                        } finally {
                            // Guaranteed execution: never leaves isScrubbing stuck in true
                            onScrubEnd(currentFrac)
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            val centerY = size.height / 2f
            val currentTrackHeightPx = trackHeight.toPx()
            val currentThumbRadiusPx = thumbRadius.toPx()
            val cornerRadius = CornerRadius(currentTrackHeightPx / 2f, currentTrackHeightPx / 2f)

            // 1. Inactive Background Track (Full Width)
            drawRoundRect(
                color = inactiveColor,
                topLeft = Offset(0f, centerY - (currentTrackHeightPx / 2f)),
                size = Size(size.width, currentTrackHeightPx),
                cornerRadius = cornerRadius
            )

            // 1.5. Buffered Track (if present)
            val bufferedWidth = size.width * bufferedFraction.coerceIn(0f, 1f)
            if (bufferedWidth > 0f) {
                drawRoundRect(
                    color = bufferedColor,
                    topLeft = Offset(0f, centerY - (currentTrackHeightPx / 2f)),
                    size = Size(bufferedWidth, currentTrackHeightPx),
                    cornerRadius = cornerRadius
                )
            }

            // 2. Active Played Track (Electric Blue)
            val activeWidth = size.width * progressFraction.coerceIn(0f, 1f)
            if (activeWidth > 0f) {
                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(0f, centerY - (currentTrackHeightPx / 2f)),
                    size = Size(activeWidth, currentTrackHeightPx),
                    cornerRadius = cornerRadius
                )
            }

            // 3. Minimalist Thumb
            val thumbCenterX = activeWidth.coerceIn(currentThumbRadiusPx, size.width - currentThumbRadiusPx)

            // Outer subtle glow ring and blue accent ring when actively scrubbing
            if (isScrubbing) {
                drawCircle(
                    color = activeColor.copy(alpha = 0.35f),
                    radius = currentThumbRadiusPx + 4.dp.toPx(),
                    center = Offset(thumbCenterX, centerY)
                )
                drawCircle(
                    color = activeColor,
                    radius = currentThumbRadiusPx + 1.5.dp.toPx(),
                    center = Offset(thumbCenterX, centerY)
                )
            }

            // Solid inner thumb (Crisp White)
            drawCircle(
                color = thumbColor,
                radius = currentThumbRadiusPx,
                center = Offset(thumbCenterX, centerY)
            )
        }
    }
}
