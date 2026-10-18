package com.clotube.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.roundToInt

/**
 * Floating 16:9 interactive video scrubber preview tooltip.
 *
 * Uses unconstrained layout measurement and negative Y-offset so that it hovers strictly
 * above the seeker thumb with a crisp 16:9 aspect ratio without being clamped by parent
 * container constraints or causing layout shifts.
 */
@Composable
fun ScrubberPreviewTooltip(
    isVisible: Boolean,
    scrubFraction: Float,
    targetPositionFormatted: String,
    totalDurationFormatted: String,
    previewBitmap: Bitmap?,
    fallbackThumbnailUrl: String?,
    trackWidthPx: Float,
    modifier: Modifier = Modifier,
    bubbleWidth: Dp = 192.dp,
    bubbleHeight: Dp = 108.dp
) {
    val density = LocalDensity.current
    val bubbleWidthPx = with(density) { bubbleWidth.toPx() }
    val bubbleHeightPx = with(density) { bubbleHeight.toPx() }
    val clearanceGapPx = with(density) { 12.dp.toPx() } // Gap between bottom of preview card and slider track
    val sideMarginPx = with(density) { 16.dp.toPx() }

    // Center tooltip over thumb and clamp horizontally within screen margins
    val thumbXPx = (scrubFraction.coerceIn(0f, 1f) * trackWidthPx)
    val maxTargetXPx = (trackWidthPx - bubbleWidthPx - sideMarginPx).coerceAtLeast(sideMarginPx)
    val targetXPx = (thumbXPx - (bubbleWidthPx / 2f)).coerceIn(sideMarginPx, maxTargetXPx)

    // Negative Y places the tooltip strictly ABOVE the slider track
    val targetYPx = - (bubbleHeightPx + clearanceGapPx)

    var activeBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(previewBitmap) {
        if (previewBitmap != null) {
            activeBitmap = previewBitmap
        }
    }
    val bitmapToRender = if (isVisible) (previewBitmap ?: activeBitmap) else activeBitmap

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(durationMillis = 100)),
        exit = fadeOut(animationSpec = tween(durationMillis = 60)),
        modifier = modifier
            .layout { measurable, _ ->
                // Measure with unconstrained size so parent max bounds (e.g. 36dp or 28dp)
                // never clamp the 16:9 preview card height.
                val placeable = measurable.measure(Constraints())
                // Report 0x0 size to the parent layout so it NEVER contributes to parent measurement
                // and causes zero layout shift.
                layout(0, 0) {
                    placeable.place(0, 0)
                }
            }
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(targetXPx.roundToInt(), targetYPx.roundToInt()) }
                .requiredSize(width = bubbleWidth, height = bubbleHeight)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161A26)),
                border = BorderStroke(1.5.dp, Color(0xFF2F80ED)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 1. Decoded Frame or Thumbnail Fallback
                    if (bitmapToRender != null) {
                        Image(
                            bitmap = bitmapToRender.asImageBitmap(),
                            contentDescription = "Seek Frame",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (!fallbackThumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = fallbackThumbnailUrl,
                            contentDescription = "Thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF0C0E14)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color(0xFF2F80ED),
                                strokeWidth = 2.5.dp
                            )
                        }
                    }

                    // 2. Subtle Bottom Gradient Scrim
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.85f)
                                    )
                                )
                            )
                    )

                    // 3. Centered Timecode Pill
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.70f),
                        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.2f)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = "$targetPositionFormatted / $totalDurationFormatted",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                        )
                    }
                }
            }
        }
    }
}
