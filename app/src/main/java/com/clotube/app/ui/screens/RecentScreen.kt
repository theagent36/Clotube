package com.clotube.app.ui.screens

import android.text.format.DateUtils
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clotube.app.data.PlaybackHistoryManager
import com.clotube.app.data.WatchHistoryItem
import com.clotube.app.viewmodel.VideoViewModel
import kotlinx.coroutines.launch

/**
 * Full OTT-style Watch History feed screen (movies and TV series episodes)
 * with progress bars, relative timestamps, and one-tap resume.
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

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Watch History?") },
            text = { Text("This will remove all watched items from your device and cloud backup.") },
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
                    Text("Clear All", color = Color(0xFFFF5252))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E2433),
            titleContentColor = Color.White,
            textContentColor = Color(0xFF94A3B8)
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
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear History",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F121C)
                )
            )
        },
        containerColor = Color(0xFF0F121C),
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Watch History Yet",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Movies and episodes you watch will appear here.",
                        color = Color(0xFF64748B),
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(
                    items = historyList,
                    key = { it.fileId }
                ) { item ->
                    RecentHistoryCard(
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
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun RecentHistoryCard(
    item: WatchHistoryItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val relativeTime = remember(item.lastWatchedEpochMs) {
        DateUtils.getRelativeTimeSpanString(
            item.lastWatchedEpochMs,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    val isCompleted = item.progressFraction >= 0.95f
    val progressPercent = if (isCompleted) 100 else (item.progressFraction * 100).toInt()

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161A26)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: 16:9 Thumbnail with flush progress bar
            Box(
                modifier = Modifier
                    .width(130.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF23293B))
            ) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = item.formattedDisplayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Play / Replay Icon overlay
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isCompleted) Icons.Default.Replay else Icons.Default.PlayArrow,
                        contentDescription = if (isCompleted) "Replay" else "Resume",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Completed badge on thumbnail
                if (isCompleted) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(Color(0xFF1B5E20).copy(alpha = 0.92f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "COMPLETED",
                            color = Color(0xFF81C784),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Bottom Progress Line (100% full progress line if completed)
                if (isCompleted || item.progressFraction > 0.02f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter)
                            .background(Color.White.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = if (isCompleted) 1.0f else item.progressFraction)
                                .fillMaxHeight()
                                .background(if (isCompleted) Color(0xFF4CAF50) else Color(0xFF2F80ED))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Middle: Title & Meta Info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.formattedDisplayTitle,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isCompleted) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF2E7D32).copy(alpha = 0.25f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = "Watched",
                                color = Color(0xFF81C784),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }

                    Text(
                        text = if (isCompleted) "100% watched • $relativeTime" else "$progressPercent% watched • $relativeTime",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }

            // Right: Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove from history",
                    tint = Color(0xFF64748B),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
