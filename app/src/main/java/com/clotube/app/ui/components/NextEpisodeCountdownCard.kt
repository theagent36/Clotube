package com.clotube.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.clotube.app.model.Episode
import kotlinx.coroutines.delay

private const val COUNTDOWN_TOTAL_MS = 10_000L

@Composable
fun NextEpisodeCountdownCard(
    currentPositionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    nextEpisode: Episode?,
    onPlayNext: (Episode) -> Unit,
    modifier: Modifier = Modifier,
    isScrubbing: Boolean = false
) {
    if (nextEpisode == null || durationMs <= 0L) return

    val remainingMs = (durationMs - currentPositionMs).coerceAtLeast(0L)
    val shouldTriggerWindow = remainingMs in 1L..30_000L && !isScrubbing

    var isDismissed by remember(nextEpisode.id) { mutableStateOf(false) }
    var countdownRemainingMs by remember(nextEpisode.id) { mutableLongStateOf(COUNTDOWN_TOTAL_MS) }
    var isCountdownActive by remember(nextEpisode.id) { mutableStateOf(false) }

    LaunchedEffect(shouldTriggerWindow, isDismissed) {
        if (shouldTriggerWindow && !isDismissed) {
            isCountdownActive = true
        } else if (!shouldTriggerWindow) {
            countdownRemainingMs = COUNTDOWN_TOTAL_MS
            isCountdownActive = false
        }
    }

    LaunchedEffect(isCountdownActive, isPlaying, isDismissed) {
        if (isCountdownActive && isPlaying && !isDismissed) {
            while (countdownRemainingMs > 0L && isPlaying && !isDismissed) {
                delay(100L)
                countdownRemainingMs = (countdownRemainingMs - 100L).coerceAtLeast(0L)
            }
            if (countdownRemainingMs <= 0L && !isDismissed) {
                isCountdownActive = false
                onPlayNext(nextEpisode)
            }
        }
    }

    val progressFraction = (countdownRemainingMs.toFloat() / COUNTDOWN_TOTAL_MS.toFloat()).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
        label = "countdownBorderProgress"
    )

    val isVisible = shouldTriggerWindow && !isDismissed

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier.zIndex(100f)
    ) {
        val cornerRadius = 16.dp
        val strokeWidth = 1.8.dp
        val activeCyan = Color(0xFF00E5FF)
        val inactiveBorder = Color(0xFF1E283D)

        Box(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(cornerRadius))
                .background(Color(0xFF0C1019).copy(alpha = 0.95f))
                .drawWithCache {
                    val cornerRadiusPx = cornerRadius.toPx()
                    val strokeWidthPx = strokeWidth.toPx()

                    val roundedRectPath = Path().apply {
                        addRoundRect(
                            RoundRect(
                                rect = size.toRect().deflate(strokeWidthPx / 2f),
                                cornerRadius = CornerRadius(cornerRadiusPx)
                            )
                        )
                    }

                    val pathMeasure = PathMeasure()
                    pathMeasure.setPath(roundedRectPath, false)
                    val totalLength = pathMeasure.length
                    val segmentPath = Path()

                    onDrawWithContent {
                        drawContent()

                        drawPath(
                            path = roundedRectPath,
                            color = inactiveBorder,
                            style = Stroke(width = strokeWidthPx)
                        )

                        if (animatedProgress > 0f) {
                            segmentPath.reset()
                            pathMeasure.getSegment(
                                startDistance = 0f,
                                stopDistance = totalLength * animatedProgress,
                                destination = segmentPath,
                                startWithMoveTo = true
                            )
                            drawPath(
                                path = segmentPath,
                                color = activeCyan,
                                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                            )
                        }
                    }
                }
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Main Clickable Area (Triggers Immediate Play Next)
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            isCountdownActive = false
                            onPlayNext(nextEpisode)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play Next",
                        tint = activeCyan,
                        modifier = Modifier.size(20.dp)
                    )

                    Spacer(Modifier.width(8.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Next Episode",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val secondsLeft = ((countdownRemainingMs + 999L) / 1000L).coerceIn(1L, 10L)
                            Text(
                                text = " • in ${secondsLeft}s",
                                color = activeCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(Modifier.height(1.dp))

                        val seasonFormatted = String.format(java.util.Locale.US, "%02d", nextEpisode.seasonNumber)
                        val episodeFormatted = String.format(java.util.Locale.US, "%02d", nextEpisode.episodeNumber)
                        Text(
                            text = "S$seasonFormatted:E$episodeFormatted • ${nextEpisode.displayTitle}",
                            color = Color(0xFF7E8D9F),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                // Independent Dismiss Button with Generous Hit-Target
                IconButton(
                    onClick = {
                        isDismissed = true
                        isCountdownActive = false
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = Color(0xFF7E8D9F),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
