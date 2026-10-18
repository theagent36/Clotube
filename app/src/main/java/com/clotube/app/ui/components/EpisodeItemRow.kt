package com.clotube.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clotube.app.data.PlaybackHistoryManager
import com.clotube.app.model.Episode
import com.clotube.app.util.triggerClickHaptic
import com.clotube.app.viewmodel.VideoViewModel
import kotlinx.coroutines.launch

@Composable
fun EpisodeItemRow(
    episode: Episode,
    seriesId: String,
    seriesTitle: String,
    isPlayingThis: Boolean,
    onEpisodeClick: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    playbackHistoryManager: PlaybackHistoryManager?,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    // Infinite transition for pulsing "NOW PLAYING" indicator
    val infiniteTransition = rememberInfiniteTransition(label = "now_playing_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onEpisodeClick() },
        shape = RoundedCornerShape(12.dp),
        color = if (isPlayingThis) Color(0xFF0E1A2B) else Color(0xFF131822),
        // Active Blue Outline
        border = if (isPlayingThis) {
            BorderStroke(1.5.dp, Color(0xFF00E5FF))
        } else {
            BorderStroke(1.dp, Color(0xFF1E2638))
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Episode Thumbnail with Active Indicator Overlay
            Box(
                modifier = Modifier
                    .width(116.dp)
                    .height(68.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF080B11)),
                contentAlignment = Alignment.Center
            ) {
                if (episode.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = episode.thumbnailUrl,
                        contentDescription = episode.displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Thumbnail dark scrim
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x40000000))
                )

                // Play / Equalizer Icon
                if (isPlayingThis) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.height(18.dp)
                    ) {
                        AnimatedEqualizerBar(pulseAlpha, heightFraction = 0.9f)
                        AnimatedEqualizerBar(1.4f - pulseAlpha, heightFraction = 0.6f)
                        AnimatedEqualizerBar(pulseAlpha, heightFraction = 1.0f)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Duration badge
                if (episode.formattedDuration.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = episode.formattedDuration,
                            color = Color(0xFFECEFF1),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 2. Metadata: Tags, Title, and Technical details
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                // Header Row: Season/Episode Pill + NOW PLAYING Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val sFormatted = episode.seasonNumber.toString().padStart(2, '0')
                    val eFormatted = episode.episodeNumber.toString().padStart(2, '0')

                    Text(
                        text = "S$sFormatted:E$eFormatted",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    if (isPlayingThis) {
                        Text(
                            text = "•",
                            color = Color(0xFF00E5FF).copy(alpha = 0.5f),
                            fontSize = 10.sp
                        )

                        // "NOW PLAYING" Glowing Label
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFF00E5FF).copy(alpha = pulseAlpha))
                            )
                            Text(
                                text = "NOW PLAYING",
                                color = Color(0xFF00E5FF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Episode Title
                Text(
                    text = episode.displayTitle,
                    color = if (isPlayingThis) Color(0xFF00E5FF) else Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (isPlayingThis) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Bottom Specs: File Size & Resolution
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (episode.fileSize.isNotBlank()) {
                        Text(
                            text = episode.fileSize,
                            color = Color(0xFF7E8D9F),
                            fontSize = 11.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1E2838), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = episode.resolution.ifBlank { "1080p FHD" },
                            color = Color(0xFF64B5F6),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 3. Action Buttons: Checkmark / Watched & Download
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Completed indicator
                val isWatched by (playbackHistoryManager?.isEpisodeWatched(episode.id)
                    ?: remember(episode.id) { kotlinx.coroutines.flow.flowOf(false) })
                    .collectAsState(initial = false)

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isWatched) Color(0xFF16322A) else Color(0xFF181F2C))
                        .clickable {
                            view.triggerClickHaptic()
                            coroutineScope.launch {
                                playbackHistoryManager?.toggleEpisodeWatched(
                                    episode = episode,
                                    seriesId = seriesId,
                                    seriesTitle = seriesTitle
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Mark as Watched",
                        tint = if (isWatched) Color(0xFF00E676) else Color(0xFF4C5D75),
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Download action
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF181F2C))
                        .clickable {
                            view.triggerClickHaptic()
                            if (isDownloading) onCancelDownload() else onStartDownload()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FileDownload,
                        contentDescription = "Download",
                        tint = if (isDownloaded) Color(0xFF00E5FF) else Color(0xFF4C5D75),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EpisodeItemRow(
    episode: Episode,
    seriesId: String,
    seriesTitle: String,
    isPlayingThis: Boolean,
    onEpisodeClick: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    viewModel: VideoViewModel,
    modifier: Modifier = Modifier
) = EpisodeItemRow(
    episode = episode,
    seriesId = seriesId,
    seriesTitle = seriesTitle,
    isPlayingThis = isPlayingThis,
    onEpisodeClick = onEpisodeClick,
    onStartDownload = onStartDownload,
    onCancelDownload = onCancelDownload,
    isDownloading = isDownloading,
    isDownloaded = isDownloaded,
    playbackHistoryManager = viewModel.playbackHistoryManager,
    modifier = modifier
)

@Composable
fun AnimatedEqualizerBar(
    progress: Float,
    heightFraction: Float
) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height((16 * heightFraction * progress.coerceIn(0.3f, 1.0f)).dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(Color(0xFF00E5FF))
    )
}
