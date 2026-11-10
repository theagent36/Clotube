package com.clotube.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileDownloadDone
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clotube.app.data.DownloadRepository
import com.clotube.app.data.DownloadStatus
import com.clotube.app.data.OfflineMediaRecord
import com.clotube.app.data.PlaybackHistoryManager
import com.clotube.app.model.Episode
import com.clotube.app.ui.theme.AccentBlue
import com.clotube.app.ui.theme.AppleTvMotion
import com.clotube.app.ui.theme.SurfaceBorder
import com.clotube.app.ui.theme.SurfaceDefault
import com.clotube.app.ui.theme.SurfaceElevated
import com.clotube.app.ui.theme.appleTvPressable
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
    isWatched: Boolean = false,
    isDownloading: Boolean = false,
    isDownloaded: Boolean = false,
    downloadRecord: OfflineMediaRecord? = null,
    playbackHistoryManager: PlaybackHistoryManager? = null,
    onToggleWatched: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val userEmail = remember { com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(context)?.email.orEmpty() }

    // Reactive watch state with optimistic override for zero-latency tap response
    var optimisticWatched by remember(episode.id) { mutableStateOf<Boolean?>(null) }
    val isWatchedFromFlow by (playbackHistoryManager?.isEpisodeWatched(episode.id)
        ?: remember(episode.id) { kotlinx.coroutines.flow.flowOf(false) })
        .collectAsState(initial = isWatched)

    LaunchedEffect(isWatchedFromFlow) {
        optimisticWatched = null
    }

    val effectiveWatched = optimisticWatched ?: isWatchedFromFlow

    val downloadRepo = remember(context) { DownloadRepository(context) }
    val recordState by downloadRepo.getDownloadRecord(episode.id).collectAsState(initial = null)
    val effectiveRecord = downloadRecord ?: recordState

    val status = effectiveRecord?.status ?: when {
        isDownloaded -> DownloadStatus.COMPLETED
        isDownloading -> DownloadStatus.DOWNLOADING
        else -> DownloadStatus.NOT_DOWNLOADED
    }

    val isActionCancelOrDelete = status == DownloadStatus.COMPLETED ||
            status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.QUEUED

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

    val rowBgColor by animateColorAsState(
        targetValue = if (isPlayingThis) SurfaceElevated else SurfaceDefault,
        animationSpec = AppleTvMotion.fluidSpring(),
        label = "episode_row_bg"
    )
    val rowBorderColor by animateColorAsState(
        targetValue = if (isPlayingThis) AccentBlue else SurfaceBorder,
        animationSpec = AppleTvMotion.fluidSpring(),
        label = "episode_row_border"
    )
    val rowInteractionSource = remember { MutableInteractionSource() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .appleTvPressable(rowInteractionSource, pressedScale = 0.97f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = rowInteractionSource,
                indication = ripple(bounded = true)
            ) { onEpisodeClick() },
        shape = RoundedCornerShape(12.dp),
        color = rowBgColor,
        border = BorderStroke(1.dp, rowBorderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Thumbnail with bottom progress line
            Box(
                modifier = Modifier
                    .width(118.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceElevated),
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

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0x66000000))
                            )
                        )
                )

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
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                if (effectiveWatched) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .align(Alignment.BottomCenter)
                            .background(AccentBlue)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 2. Center Metadata
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                val epIndex = "E${episode.episodeNumber.toString().padStart(2, '0')}"
                Text(
                    text = epIndex,
                    color = if (isPlayingThis) AccentBlue else Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = episode.displayTitle,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                val durationText = remember(episode.durationFormatted, episode.durationMs) {
                    if (episode.durationMs > 0L) {
                        val mins = (episode.durationMs / 1000L) / 60L
                        if (mins > 0L) "${mins}m" else episode.durationFormatted
                    } else {
                        val parts = episode.durationFormatted.split(":")
                        if (parts.size >= 2) {
                            val mins = parts[parts.size - 2].toIntOrNull()
                            if (mins != null && mins > 0) "${mins}m" else episode.durationFormatted
                        } else {
                            episode.durationFormatted.ifBlank { "42m" }
                        }
                    }
                }

                Text(
                    text = durationText,
                    color = Color(0xFF64748B),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal
                )
            }

            // 3. Right Rail Actions: [Mark as Watched Checkbox] + [Download Button]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // THEMED MARK AS WATCHED CHECKBOX BUTTON
                val checkInteractionSource = remember { MutableInteractionSource() }
                val checkBgColor by animateColorAsState(
                    targetValue = if (effectiveWatched) AccentBlue.copy(alpha = 0.16f) else Color.Transparent,
                    animationSpec = AppleTvMotion.tactileSpring(),
                    label = "check_bg"
                )
                val checkBorderColor by animateColorAsState(
                    targetValue = if (effectiveWatched) AccentBlue else SurfaceBorder,
                    animationSpec = AppleTvMotion.tactileSpring(),
                    label = "check_border"
                )
                val checkIconTint by animateColorAsState(
                    targetValue = if (effectiveWatched) AccentBlue else Color(0xFF64748B),
                    animationSpec = AppleTvMotion.tactileSpring(),
                    label = "check_tint"
                )

                Surface(
                    modifier = Modifier
                        .size(32.dp)
                        .appleTvPressable(checkInteractionSource, pressedScale = 0.88f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = checkInteractionSource,
                            indication = ripple(bounded = true)
                        ) {
                            val nextState = !effectiveWatched
                            optimisticWatched = nextState
                            view.triggerClickHaptic()

                            if (onToggleWatched != null) {
                                onToggleWatched()
                            } else {
                                coroutineScope.launch {
                                    playbackHistoryManager?.setEpisodeWatched(
                                        seriesId = seriesId,
                                        seriesTitle = seriesTitle,
                                        episode = episode,
                                        isWatched = nextState,
                                        userEmail = userEmail
                                    ) ?: run {
                                        if (userEmail.isNotBlank()) {
                                            com.clotube.app.data.AndroidWatchHistoryManager.setEpisodeWatched(
                                                context = context,
                                                email = userEmail,
                                                fileId = episode.id,
                                                isWatched = nextState
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    shape = RoundedCornerShape(8.dp),
                    color = checkBgColor,
                    border = BorderStroke(1.dp, checkBorderColor)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = if (effectiveWatched) "Mark as unwatched" else "Mark as watched",
                            tint = checkIconTint,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                // DOWNLOAD BUTTON
                IconButton(
                    onClick = {
                        view.triggerClickHaptic()
                        if (isActionCancelOrDelete) onCancelDownload() else onStartDownload()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    when (status) {
                        DownloadStatus.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Outlined.FileDownloadDone,
                                contentDescription = "Downloaded",
                                tint = AccentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DownloadStatus.DOWNLOADING -> {
                            CircularProgressIndicator(
                                progress = { effectiveRecord?.progressFraction ?: 0f },
                                modifier = Modifier.size(18.dp),
                                color = AccentBlue,
                                strokeWidth = 2.dp
                            )
                        }
                        DownloadStatus.QUEUED -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color(0xFF64748B),
                                strokeWidth = 2.dp
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Outlined.FileDownload,
                                contentDescription = "Download",
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
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
    isDownloading: Boolean = false,
    isDownloaded: Boolean = false,
    downloadRecord: OfflineMediaRecord? = null,
    onToggleWatched: (() -> Unit)? = null,
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
    isWatched = false,
    isDownloading = isDownloading,
    isDownloaded = isDownloaded,
    downloadRecord = downloadRecord,
    playbackHistoryManager = viewModel.playbackHistoryManager,
    onToggleWatched = onToggleWatched,
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
            .background(AccentBlue)
    )
}
