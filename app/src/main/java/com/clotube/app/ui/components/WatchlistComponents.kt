package com.clotube.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.clotube.app.data.metadata.GlobalSearchResult
import com.clotube.app.model.WatchlistEntry
import com.clotube.app.model.WatchlistMediaType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.app.model.WatchlistStatus
import com.clotube.app.ui.theme.AccentBlue
import com.clotube.app.ui.theme.AccentPrimary
import com.clotube.app.ui.theme.CanvasDeep
import com.clotube.app.ui.theme.SurfaceBorder
import com.clotube.app.ui.theme.SurfaceDefault
import com.clotube.app.ui.theme.SurfaceElevated
import com.clotube.app.ui.theme.TextPrimary
import com.clotube.app.ui.theme.TextSecondary
import com.clotube.app.ui.theme.TextTertiary

/**
 * Compact obsidian glass status badge displayed over media posters.
 * Renders nothing if [status] == [WatchlistStatus.UNWATCHED].
 */
@Composable
fun WatchlistStatusPill(
    status: WatchlistStatus,
    modifier: Modifier = Modifier
) {
    if (status == WatchlistStatus.UNWATCHED) return

    val (borderColor, textColor, label) = when (status) {
        WatchlistStatus.WATCH_LATER -> Triple(
            AccentBlue.copy(alpha = 0.6f),
            AccentPrimary,
            "Watch Later"
        )
        WatchlistStatus.WATCHED -> Triple(
            Color(0xFF10B981).copy(alpha = 0.55f),
            Color(0xFF6EE7B7),
            "Watched"
        )
        WatchlistStatus.REWATCH -> Triple(
            Color(0xFFF59E0B).copy(alpha = 0.55f),
            Color(0xFFFCD34D),
            "Rewatch"
        )
        WatchlistStatus.UNWATCHED -> return
    }

    Box(
        modifier = modifier
            .background(Color(0xCC08090D), RoundedCornerShape(6.dp))
            .border(BorderStroke(0.5.dp, borderColor), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            maxLines = 1
        )
    }
}

/**
 * Circular frosted glass action button triggering watchlist status bottom sheet or toggle.
 */
@Composable
fun WatchlistBookmarkButton(
    status: WatchlistStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    size: Dp = 30.dp
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    val icon: ImageVector = when (status) {
        WatchlistStatus.UNWATCHED -> Icons.Rounded.BookmarkBorder
        WatchlistStatus.WATCH_LATER -> Icons.Rounded.Bookmark
        WatchlistStatus.WATCHED -> Icons.Rounded.CheckCircle
        WatchlistStatus.REWATCH -> Icons.Rounded.Replay
    }

    val tint: Color = when (status) {
        WatchlistStatus.UNWATCHED -> Color.White.copy(alpha = 0.85f)
        WatchlistStatus.WATCH_LATER -> AccentBlue
        WatchlistStatus.WATCHED -> Color(0xFF10B981)
        WatchlistStatus.REWATCH -> Color(0xFFF59E0B)
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xB308090D))
            .border(BorderStroke(0.5.dp, Color(0x29FFFFFF)), CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = Color.White.copy(alpha = 0.2f))
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Watchlist: ${status.displayName}",
            tint = tint,
            modifier = Modifier.size(size * 0.58f)
        )
    }
}

/**
 * Obsidian Cinema modal bottom sheet letting the user choose between Watch Later,
 * Watched, Rewatch, or Remove from Watchlist with detailed descriptions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistStatusBottomSheet(
    title: String,
    currentStatus: WatchlistStatus,
    onSelectStatus: (WatchlistStatus) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDefault,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))
            )
        },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            // Header with title and close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Watchlist Status",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    if (title.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Option 1: Watch Later
            WatchlistStatusOptionRow(
                title = "Watch Later",
                subtitle = "Save to watch when you have time",
                icon = Icons.Rounded.Bookmark,
                iconTint = AccentBlue,
                isSelected = currentStatus == WatchlistStatus.WATCH_LATER,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectStatus(WatchlistStatus.WATCH_LATER)
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Option 2: Watched
            WatchlistStatusOptionRow(
                title = "Watched",
                subtitle = "Mark as completed across all your devices",
                icon = Icons.Rounded.CheckCircle,
                iconTint = Color(0xFF10B981),
                isSelected = currentStatus == WatchlistStatus.WATCHED,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectStatus(WatchlistStatus.WATCHED)
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Option 3: Rewatch
            WatchlistStatusOptionRow(
                title = "Rewatch",
                subtitle = "Queue up for another viewing experience",
                icon = Icons.Rounded.Replay,
                iconTint = Color(0xFFF59E0B),
                isSelected = currentStatus == WatchlistStatus.REWATCH,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectStatus(WatchlistStatus.REWATCH)
                    onDismiss()
                }
            )

            if (currentStatus != WatchlistStatus.UNWATCHED) {
                Spacer(modifier = Modifier.height(10.dp))

                // Option 4: Remove from Watchlist
                WatchlistStatusOptionRow(
                    title = "Remove from Watchlist",
                    subtitle = "Delete and sync removal to Google Drive",
                    icon = Icons.Rounded.BookmarkRemove,
                    iconTint = Color(0xFFEF4444),
                    isSelected = false,
                    isDestructive = true,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectStatus(WatchlistStatus.UNWATCHED)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun WatchlistStatusOptionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDestructive: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val containerBg by animateColorAsState(
        targetValue = if (isSelected) Color(0x1F3877EE) else SurfaceElevated,
        animationSpec = tween(durationMillis = 180),
        label = "option_row_bg"
    )
    val borderStroke = if (isSelected) {
        BorderStroke(1.dp, AccentBlue.copy(alpha = 0.8f))
    } else {
        BorderStroke(0.5.dp, SurfaceBorder)
    }

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = containerBg,
        border = borderStroke,
        interactionSource = interactionSource
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDestructive) Color(0xFFFCA5A5) else TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * Obsidian Cinema modal bottom sheet displayed when interacting with external titles
 * not currently stored in the user's Google Drive. Shows rich synopsis, rating,
 * "Not present in your Google Drive" banner, and full Watchlist action triggers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalTitleActionBottomSheet(
    title: String,
    mediaType: WatchlistMediaType,
    posterUrl: String = "",
    backdropUrl: String = "",
    year: String = "",
    genre: String = "",
    overview: String = "",
    rating: String = "",
    currentStatus: WatchlistStatus,
    onSelectStatus: (WatchlistStatus) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current
    val artwork = posterUrl.ifBlank { backdropUrl }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDefault,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))
            )
        },
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 1. Header with Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Poster Thumbnail
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(108.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceElevated)
                            .border(BorderStroke(0.5.dp, SurfaceBorder), RoundedCornerShape(8.dp))
                    ) {
                        if (artwork.isNotBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(artwork)
                                    .crossfade(200)
                                    .build(),
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = title.take(1).uppercase(),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }

                    // Metadata details
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (mediaType == WatchlistMediaType.SERIES) "TV Series" else "Movie",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AccentBlue
                            )
                            if (year.isNotBlank()) {
                                Text(text = "•", fontSize = 10.sp, color = TextTertiary)
                                Text(
                                    text = year,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = TextSecondary
                                )
                            }
                            if (rating.isNotBlank()) {
                                Text(text = "•", fontSize = 10.sp, color = TextTertiary)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFF59E0B),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = rating,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFF59E0B)
                                    )
                                }
                            }
                        }

                        // "Not present in your Google Drive" banner
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(Color(0x26000000), RoundedCornerShape(6.dp))
                                .border(BorderStroke(0.5.dp, Color(0x3394A3B8)), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudOff,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Not present in your Google Drive",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = TextTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 2. Overview / Synopsis
            if (overview.isNotBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = overview,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 3. Watchlist Action Section Title
            Text(
                text = "Add to Watchlist",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Save this title now. When uploaded to Drive later, it will link automatically.",
                fontSize = 11.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Option 1: Watch Later
            WatchlistStatusOptionRow(
                title = "Watch Later",
                subtitle = "Save to watch when you have time",
                icon = Icons.Rounded.Bookmark,
                iconTint = AccentBlue,
                isSelected = currentStatus == WatchlistStatus.WATCH_LATER,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectStatus(WatchlistStatus.WATCH_LATER)
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Option 2: Watched
            WatchlistStatusOptionRow(
                title = "Watched",
                subtitle = "Mark as completed across all your devices",
                icon = Icons.Rounded.CheckCircle,
                iconTint = Color(0xFF10B981),
                isSelected = currentStatus == WatchlistStatus.WATCHED,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectStatus(WatchlistStatus.WATCHED)
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Option 3: Rewatch
            WatchlistStatusOptionRow(
                title = "Rewatch",
                subtitle = "Queue up for another viewing experience",
                icon = Icons.Rounded.Replay,
                iconTint = Color(0xFFF59E0B),
                isSelected = currentStatus == WatchlistStatus.REWATCH,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelectStatus(WatchlistStatus.REWATCH)
                    onDismiss()
                }
            )

            if (currentStatus != WatchlistStatus.UNWATCHED) {
                Spacer(modifier = Modifier.height(10.dp))

                // Option 4: Remove from Watchlist
                WatchlistStatusOptionRow(
                    title = "Remove from Watchlist",
                    subtitle = "Delete and sync removal to Google Drive",
                    icon = Icons.Rounded.BookmarkRemove,
                    iconTint = Color(0xFFEF4444),
                    isSelected = false,
                    isDestructive = true,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectStatus(WatchlistStatus.UNWATCHED)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalTitleActionBottomSheet(
    result: GlobalSearchResult,
    currentStatus: WatchlistStatus,
    onSelectStatus: (WatchlistStatus) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExternalTitleActionBottomSheet(
        title = result.title,
        mediaType = result.mediaType,
        posterUrl = result.posterUrl,
        backdropUrl = result.backdropUrl,
        year = result.year,
        genre = result.genre,
        overview = result.overview,
        rating = result.rating,
        currentStatus = currentStatus,
        onSelectStatus = onSelectStatus,
        onDismiss = onDismiss,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalTitleActionBottomSheet(
    entry: WatchlistEntry,
    onSelectStatus: (WatchlistStatus) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ExternalTitleActionBottomSheet(
        title = entry.title,
        mediaType = entry.mediaType,
        posterUrl = entry.posterUrl,
        backdropUrl = entry.backdropUrl,
        year = entry.year,
        genre = entry.genre,
        overview = "",
        rating = "",
        currentStatus = entry.status,
        onSelectStatus = onSelectStatus,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
