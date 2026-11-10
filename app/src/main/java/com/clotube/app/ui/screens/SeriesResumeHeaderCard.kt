package com.clotube.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clotube.app.model.Episode
import com.clotube.app.model.Series
import com.clotube.app.ui.theme.*

@Composable
fun SeriesResumeHeaderCard(
    series: Series,
    episode: Episode,
    currentPositionMs: Long,
    durationMs: Long,
    onBackClick: () -> Unit,
    onResumeClick: (Episode, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val progressFraction = if (durationMs > 0L) {
        (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val progressPercent = (progressFraction * 100).toInt()
    val isResume = currentPositionMs > 3000L

    // Priority: Episode screenshot -> Series backdrop -> Series poster
    val displayArtworkUrl = when {
        episode.thumbnailUrl.isNotBlank() && !episode.thumbnailUrl.contains("drive.google.com") -> episode.thumbnailUrl
        !series.backdropUrl.isNullOrBlank() -> series.backdropUrl
        !series.posterUrl.isNullOrBlank() -> series.posterUrl
        else -> episode.thumbnailUrl
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable { onResumeClick(episode, currentPositionMs) }
    ) {
        // 1. Full-Bleed Artwork
        if (!displayArtworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = displayArtworkUrl,
                contentDescription = episode.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. High-Contrast Gradient Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0x66000000),
                            0.30f to Color(0x20000000),
                            0.65f to Color(0xD9070A0F),
                            1.0f to Color(0xFC070A0F)
                        )
                    )
                )
        )

        // 3. Top Bar (Back Button + OTT Direct Stream Badge)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            Box(
                modifier = Modifier
                    .background(Color(0xCC111D2D), RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "• OTT DIRECT STREAM",
                    color = AccentBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 4. Bottom Information & Action Row
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                // Tag Pill
                Box(
                    modifier = Modifier
                        .background(SurfaceElevated, RoundedCornerShape(6.dp))
                        .border(BorderStroke(1.dp, SurfaceBorder), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = AccentBlue,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = if (isResume) "CONTINUE WATCHING" else "START WATCHING",
                            color = AccentBlue,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Series Title
                if (series.title.isNotBlank()) {
                    Text(
                        text = series.title,
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                // Episode Number & Name (e.g., S05:E11 • Days of Wine and Roses)
                val epCode = "S${episode.seasonNumber.toString().padStart(2, '0')}:E${episode.episodeNumber.toString().padStart(2, '0')}"
                val epTitle = if (episode.displayTitle.isNotBlank() && !episode.displayTitle.equals(series.title, ignoreCase = true)) {
                    "$epCode • ${episode.displayTitle}"
                } else {
                    epCode
                }

                Text(
                    text = epTitle,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(3.dp))

                // Progress text
                val posFormatted = formatMsToTime(currentPositionMs)
                val durFormatted = formatMsToTime(durationMs)
                val progressText = if (isResume && durationMs > 0L) {
                    "$progressPercent% watched • $posFormatted / $durFormatted"
                } else if (durationMs > 0L) {
                    durFormatted
                } else {
                    "1080p FHD"
                }

                Text(
                    text = progressText,
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Prominent Action Button
            Button(
                onClick = { onResumeClick(episode, currentPositionMs) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = CanvasDeep
                ),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = CanvasDeep,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isResume) "Resume Watching" else "Start Watching",
                    color = CanvasDeep,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 5. Accurate Bottom Progress Indicator Line
        if (isResume && progressFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomCenter)
                    .background(Color(0x33FFFFFF))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction.coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .background(AccentBlue)
                )
            }
        }
    }
}

private fun formatMsToTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
}
