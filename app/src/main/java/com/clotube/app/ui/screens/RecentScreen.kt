package com.clotube.app.ui.screens

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.clotube.app.data.PlaybackHistoryManager
import com.clotube.app.data.WatchHistoryItem
import com.clotube.app.ui.theme.AccentBlue
import com.clotube.app.ui.theme.CanvasDeep
import com.clotube.app.ui.theme.SurfaceBorder
import com.clotube.app.ui.theme.SurfaceElevated
import com.clotube.app.ui.theme.TextSecondary
import com.clotube.app.viewmodel.VideoViewModel
import kotlinx.coroutines.launch

/**
 * Sleek, editorial Watch History feed screen with refined list rows,
 * compact 16:9 thumbnails, docked progress lines, and contextual overflow actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    playbackHistoryManager: PlaybackHistoryManager,
    onResumeItem: (WatchHistoryItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoViewModel? = null
) {
    val historyList by playbackHistoryManager.allHistory.collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()
    var showClearDialog by remember { mutableStateOf(false) }

    // Clear All Confirmation Dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text(
                    text = "Clear Watch History?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "This will remove all watched items from your playback history.",
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (viewModel != null) {
                            viewModel.clearAllHistory {
                                showClearDialog = false
                            }
                        } else {
                            coroutineScope.launch {
                                playbackHistoryManager.clearAllHistory()
                                showClearDialog = false
                            }
                        }
                    }
                ) {
                    Text("Clear All", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            containerColor = SurfaceElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Watch History",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    if (historyList.isNotEmpty()) {
                        TextButton(
                            onClick = { showClearDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Clear All",
                                color = Color(0xFF94A3B8),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CanvasDeep
                )
            )
        },
        containerColor = CanvasDeep,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(SurfaceElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No watch history yet",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Movies and series you watch will appear here.",
                        color = Color(0xFF64748B),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(
                    items = historyList,
                    key = { it.fileId }
                ) { item ->
                    RecentHistoryRow(
                        item = item,
                        onClick = { onResumeItem(item) },
                        onDelete = {
                            if (viewModel != null) {
                                viewModel.deleteHistoryItem(item.fileId)
                            } else {
                                coroutineScope.launch {
                                    playbackHistoryManager.deleteHistoryItem(item.fileId)
                                }
                            }
                        },
                        onMarkUnwatched = {
                            coroutineScope.launch {
                                if (item.isSeries && !item.seriesId.isNullOrBlank()) {
                                    playbackHistoryManager.markEpisodeUnwatched(item.seriesId, item.fileId)
                                } else {
                                    playbackHistoryManager.clearProgress(item.fileId)
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Editorial History List Row.
 * Replaces chunky utility cards with an edge-to-edge row featuring a 114.dp 16:9 thumbnail,
 * docked 2.dp AccentBlue progress line, refined metadata typography, and 3-dot overflow options.
 */
@Composable
fun RecentHistoryRow(
    item: WatchHistoryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onMarkUnwatched: () -> Unit,
    modifier: Modifier = Modifier
) {
    val relativeTime = remember(item.lastWatchedEpochMs) {
        DateUtils.getRelativeTimeSpanString(
            item.lastWatchedEpochMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    val isCompleted = item.progressFraction >= 0.95f
    val remainingMs = (item.durationMs - item.positionMs).coerceAtLeast(0L)
    val remainingMinutes = remainingMs / 60000L

    val isSeries = item.isSeries || !item.seriesId.isNullOrBlank() || item.seasonNumber > 0
    val primaryTitle = if (isSeries && !item.seriesTitle.isNullOrBlank()) {
        item.seriesTitle!!
    } else {
        item.title
    }

    val episodeInfo = if (isSeries) {
        val sNum = item.seasonNumber
        val eNum = item.episodeNumber
        val epCode = if (sNum > 0 || eNum > 0) {
            "S${String.format(java.util.Locale.US, "%02d", sNum)}:E${String.format(java.util.Locale.US, "%02d", eNum)}"
        } else null

        val epTitle = item.title.takeIf { it != primaryTitle && it.isNotBlank() }

        when {
            epCode != null && epTitle != null -> "$epCode • $epTitle"
            epCode != null -> epCode
            epTitle != null -> epTitle
            else -> null
        }
    } else null

    val contextText = when {
        isCompleted -> "Completed • $relativeTime"
        remainingMinutes > 0 -> "${remainingMinutes}m remaining • $relativeTime"
        item.progressFraction > 0.02f -> "${(item.progressFraction * 100).toInt()}% watched • $relativeTime"
        else -> "Watched $relativeTime"
    }

    var menuExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Compact 16:9 Thumbnail (width = 114.dp, RoundedCornerShape(8.dp))
            Box(
                modifier = Modifier
                    .width(114.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceElevated)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.thumbnailUrl)
                        .crossfade(150)
                        .size(width = 228, height = 128)
                        .bitmapConfig(Bitmap.Config.HARDWARE)
                        .build(),
                    contentDescription = primaryTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Center play icon overlay (only if not completed)
                if (!isCompleted) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.50f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Resume",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // If completed, small checkmark badge in corner
                if (isCompleted) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(5.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(AccentBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Completed",
                            tint = Color.White,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }

                // Dock a 2.dp progress bar directly at the bottom edge of the thumbnail image (AccentBlue #3877EE)
                if (item.progressFraction > 0.02f || isCompleted) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.BottomCenter)
                            .background(Color(0x33FFFFFF))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(if (isCompleted) 1f else item.progressFraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(AccentBlue)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Center Metadata: weight(1f)
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Line 1: Primary Title in 14.sp, SemiBold, White
                Text(
                    text = primaryTitle,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Line 2: Episode info in 12.sp, #94A3B8
                if (!episodeInfo.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = episodeInfo,
                        color = TextSecondary, // #94A3B8
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Line 3: Watch context in 11.sp, #64748B
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = contextText,
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Right Action: 3-dot overflow menu
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Options",
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(20.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier
                        .background(SurfaceElevated)
                        .border(BorderStroke(1.dp, SurfaceBorder), RoundedCornerShape(8.dp))
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Mark as Unwatched",
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.VisibilityOff,
                                contentDescription = null,
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onMarkUnwatched()
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Remove from History",
                                fontSize = 13.sp,
                                color = Color(0xFFFF6B6B)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = Color(0xFFFF6B6B),
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }

        // Subtle divider line (0.5.dp, Color(0x0FFFFFFF))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(0.5.dp)
                .background(Color(0x0FFFFFFF))
        )
    }
}

/**
 * Backward compatibility alias for any existing callers.
 */
@Composable
fun RecentHistoryCard(
    item: WatchHistoryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    RecentHistoryRow(
        item = item,
        onClick = onClick,
        onDelete = onDelete,
        onMarkUnwatched = onDelete
    )
}
