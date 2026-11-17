package com.clotube.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.clotube.app.data.DriveCatalogMatcher
import com.clotube.app.data.DriveWatchlistSync
import com.clotube.app.data.WatchlistManager
import com.clotube.app.data.metadata.GlobalCatalogSearchEngine
import com.clotube.app.data.metadata.OnlineMetadataResolver
import com.clotube.app.model.Movie
import com.clotube.app.model.TvSeries
import com.clotube.app.model.WatchlistEntry
import com.clotube.app.model.WatchlistMediaType
import com.clotube.app.model.WatchlistStatus
import com.clotube.app.ui.components.ExternalTitleActionBottomSheet
import com.clotube.app.ui.components.WatchlistBookmarkButton
import com.clotube.app.ui.components.WatchlistStatusBottomSheet
import com.clotube.app.ui.components.WatchlistStatusPill
import com.clotube.app.ui.theme.AccentBlue
import com.clotube.app.ui.theme.AccentPrimary
import com.clotube.app.ui.theme.CanvasDeep
import com.clotube.app.ui.theme.SurfaceBorder
import com.clotube.app.ui.theme.SurfaceDefault
import com.clotube.app.ui.theme.SurfaceElevated
import com.clotube.app.ui.theme.TextPrimary
import com.clotube.app.ui.theme.TextSecondary
import com.clotube.app.ui.theme.TextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class WatchlistFilter(val displayName: String, val status: WatchlistStatus?) {
    ALL("All", null),
    WATCH_LATER("Watch Later", WatchlistStatus.WATCH_LATER),
    WATCHED("Watched", WatchlistStatus.WATCHED),
    REWATCH("Rewatch", WatchlistStatus.REWATCH)
}

/**
 * Resolved presentation model for a deduplicated Watchlist card.
 */
private data class ResolvedWatchlistCardItem(
    val dedupKey: String,
    val entry: WatchlistEntry,
    val matchedDriveMovie: Movie?,
    val matchedDriveSeries: TvSeries?,
    val isInDrive: Boolean,
    val liveDrivePoster: String?
)

/**
 * Obsidian Cinema Watchlist screen displaying cross-platform saved movies and TV series
 * with category filtering, real-time Drive sync, and interactive card actions.
 * Safely differentiates titles present in Google Drive vs external titles added from Global Search.
 */
@Composable
fun WatchlistScreen(
    movies: List<Movie> = emptyList(),
    seriesList: List<TvSeries> = emptyList(),
    onMovieSelected: (WatchlistEntry) -> Unit,
    onSeriesSelected: (WatchlistEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // Observe reactive revision state: instantaneous recomposition upon any mutation
    val revision = WatchlistManager.watchlistRevision

    var selectedFilter by remember { mutableStateOf(WatchlistFilter.ALL) }
    var isSyncing by remember { mutableStateOf(false) }

    // Bottom Sheet targets
    var itemForBottomSheet by remember { mutableStateOf<WatchlistEntry?>(null) }
    var itemForExternalSheet by remember { mutableStateOf<WatchlistEntry?>(null) }

    val resolvedItems = remember(revision, selectedFilter, movies, seriesList) {
        val rawActive = WatchlistManager.getActiveItems(selectedFilter.status)
        val map = LinkedHashMap<String, ResolvedWatchlistCardItem>()

        for (entry in rawActive) {
            val matchedDriveMovie = DriveCatalogMatcher.findMatchingDriveMovie(movies, entry.id, entry.title)
            val matchedDriveSeries = if (matchedDriveMovie == null) {
                DriveCatalogMatcher.findMatchingDriveSeries(seriesList, entry.id, entry.title)
            } else null
            val isInDrive = matchedDriveMovie != null || matchedDriveSeries != null
            val liveDrivePoster = matchedDriveMovie?.thumbnailUrl?.ifBlank { null }
                ?: matchedDriveSeries?.thumbnailUrl?.ifBlank { null }
                ?: matchedDriveSeries?.posterUrl?.ifBlank { null }

            val dedupKey = when {
                matchedDriveMovie != null -> "drive_movie_${matchedDriveMovie.id}"
                matchedDriveSeries != null -> "drive_series_${matchedDriveSeries.id}"
                else -> "norm_${WatchlistManager.normalizeTitle(entry.title).ifBlank { entry.id }}"
            }

            val existing = map[dedupKey]
            if (existing == null) {
                map[dedupKey] = ResolvedWatchlistCardItem(
                    dedupKey = dedupKey,
                    entry = entry,
                    matchedDriveMovie = matchedDriveMovie,
                    matchedDriveSeries = matchedDriveSeries,
                    isInDrive = isInDrive,
                    liveDrivePoster = liveDrivePoster
                )
            } else {
                val winnerEntry = WatchlistManager.mergeDuplicateEntries(existing.entry, entry)
                val bestPoster = liveDrivePoster ?: existing.liveDrivePoster
                val bestMovie = matchedDriveMovie ?: existing.matchedDriveMovie
                val bestSeries = matchedDriveSeries ?: existing.matchedDriveSeries
                map[dedupKey] = ResolvedWatchlistCardItem(
                    dedupKey = dedupKey,
                    entry = winnerEntry,
                    matchedDriveMovie = bestMovie,
                    matchedDriveSeries = bestSeries,
                    isInDrive = isInDrive || existing.isInDrive,
                    liveDrivePoster = bestPoster
                )
            }
        }

        map.values.toList()
    }

    val totalSavedCount = remember(revision, movies, seriesList) {
        val rawAll = WatchlistManager.getActiveItems(null)
        val seen = HashSet<String>()
        for (entry in rawAll) {
            val m = DriveCatalogMatcher.findMatchingDriveMovie(movies, entry.id, entry.title)
            val s = if (m == null) DriveCatalogMatcher.findMatchingDriveSeries(seriesList, entry.id, entry.title) else null
            val key = when {
                m != null -> "drive_movie_${m.id}"
                s != null -> "drive_series_${s.id}"
                else -> "norm_${WatchlistManager.normalizeTitle(entry.title).ifBlank { entry.id }}"
            }
            seen.add(key)
        }
        seen.size
    }

    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
    val syncRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sync_angle"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasDeep)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ========================================================
            // 1. TOP HEADER (Title, Item Count, Manual Sync Button)
            // ========================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Watchlist",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$totalSavedCount ${if (totalSavedCount == 1) "title" else "titles"} saved",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = TextSecondary
                    )
                }

                IconButton(
                    onClick = {
                        if (!isSyncing) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            coroutineScope.launch {
                                isSyncing = true
                                DriveWatchlistSync.getInstance(context).syncBidirectional(force = true)
                                isSyncing = false
                            }
                        }
                    },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(SurfaceElevated)
                        .border(BorderStroke(0.5.dp, SurfaceBorder), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = "Sync Watchlist",
                        tint = if (isSyncing) AccentBlue else TextSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .then(if (isSyncing) Modifier.rotate(syncRotation) else Modifier)
                    )
                }
            }

            // ========================================================
            // 2. FILTER PILLS (All, Watch Later, Watched, Rewatch)
            // ========================================================
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(WatchlistFilter.values()) { filter ->
                    val isSelected = selectedFilter == filter
                    val containerColor by animateColorAsState(
                        targetValue = if (isSelected) Color.White else SurfaceDefault,
                        animationSpec = tween(durationMillis = 180),
                        label = "filter_bg"
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (isSelected) CanvasDeep else TextSecondary,
                        animationSpec = tween(durationMillis = 180),
                        label = "filter_text"
                    )

                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedFilter = filter
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = containerColor,
                        border = if (isSelected) null else BorderStroke(0.5.dp, SurfaceBorder),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        ) {
                            Text(
                                text = filter.displayName,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = contentColor
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ========================================================
            // 3. CONTENT GRID OR EMPTY STATE
            // ========================================================
            if (resolvedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(SurfaceElevated)
                                .border(BorderStroke(1.dp, SurfaceBorder), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Bookmarks,
                                contentDescription = null,
                                tint = AccentBlue.copy(alpha = 0.8f),
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = if (selectedFilter == WatchlistFilter.ALL) "Your Watchlist is Empty" else "No ${selectedFilter.displayName} Titles",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Tap the bookmark icon on any movie or series to save it across your Android and Desktop devices.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(resolvedItems, key = { it.dedupKey }) { item ->
                        WatchlistGridCard(
                            entry = item.entry,
                            isInDrive = item.isInDrive,
                            matchingDrivePoster = item.liveDrivePoster,
                            onCardClick = {
                                if (item.isInDrive) {
                                    if (item.entry.mediaType == WatchlistMediaType.SERIES || item.matchedDriveSeries != null) {
                                        val effectiveId = item.matchedDriveSeries?.id ?: item.entry.id
                                        val effectiveTitle = item.matchedDriveSeries?.title ?: item.entry.title
                                        onSeriesSelected(item.entry.copy(id = effectiveId, title = effectiveTitle))
                                    } else {
                                        val effectiveId = item.matchedDriveMovie?.id ?: item.entry.id
                                        val effectiveTitle = item.matchedDriveMovie?.title ?: item.entry.title
                                        onMovieSelected(item.entry.copy(id = effectiveId, title = effectiveTitle))
                                    }
                                } else {
                                    // External non-Drive item: open safe action bottom sheet
                                    itemForExternalSheet = item.entry
                                }
                            },
                            onBookmarkClick = {
                                itemForBottomSheet = item.entry
                            }
                        )
                    }
                }
            }
        }

        // ========================================================
        // 4. WATCHLIST STATUS BOTTOM SHEETS
        // ========================================================

        // A. Standard Watchlist Status Bottom Sheet (bookmark action)
        itemForBottomSheet?.let { targetEntry ->
            WatchlistStatusBottomSheet(
                title = targetEntry.title,
                currentStatus = targetEntry.status,
                onSelectStatus = { newStatus ->
                    val matchedMovie = if (targetEntry.mediaType == WatchlistMediaType.MOVIE) {
                        DriveCatalogMatcher.findMatchingDriveMovie(movies, targetEntry.id, targetEntry.title)
                    } else null
                    val matchedSeries = if (targetEntry.mediaType == WatchlistMediaType.SERIES) {
                        DriveCatalogMatcher.findMatchingDriveSeries(seriesList, targetEntry.id, targetEntry.title)
                    } else null
                    val canonicalId = matchedMovie?.id ?: matchedSeries?.id ?: targetEntry.id

                    WatchlistManager.setStatus(
                        context = context,
                        id = canonicalId,
                        title = targetEntry.title,
                        mediaType = targetEntry.mediaType,
                        status = newStatus,
                        posterUrl = targetEntry.posterUrl,
                        backdropUrl = targetEntry.backdropUrl,
                        year = targetEntry.year,
                        genre = targetEntry.genre
                    )
                },
                onDismiss = { itemForBottomSheet = null }
            )
        }

        // B. External Title Action Bottom Sheet (clicking non-Drive item)
        itemForExternalSheet?.let { targetEntry ->
            ExternalTitleActionBottomSheet(
                entry = targetEntry,
                onSelectStatus = { newStatus ->
                    val matchedMovie = if (targetEntry.mediaType == WatchlistMediaType.MOVIE) {
                        DriveCatalogMatcher.findMatchingDriveMovie(movies, targetEntry.id, targetEntry.title)
                    } else null
                    val matchedSeries = if (targetEntry.mediaType == WatchlistMediaType.SERIES) {
                        DriveCatalogMatcher.findMatchingDriveSeries(seriesList, targetEntry.id, targetEntry.title)
                    } else null
                    val canonicalId = matchedMovie?.id ?: matchedSeries?.id ?: targetEntry.id

                    WatchlistManager.setStatus(
                        context = context,
                        id = canonicalId,
                        title = targetEntry.title,
                        mediaType = targetEntry.mediaType,
                        status = newStatus,
                        posterUrl = targetEntry.posterUrl,
                        backdropUrl = targetEntry.backdropUrl,
                        year = targetEntry.year,
                        genre = targetEntry.genre
                    )
                },
                onDismiss = { itemForExternalSheet = null }
            )
        }
    }
}

/**
 * Backward-compatible overload for callers that do not directly provide Drive catalog lists.
 */
@Composable
fun WatchlistScreen(
    onMovieSelected: (WatchlistEntry) -> Unit,
    onSeriesSelected: (WatchlistEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    WatchlistScreen(
        movies = emptyList(),
        seriesList = emptyList(),
        onMovieSelected = onMovieSelected,
        onSeriesSelected = onSeriesSelected,
        modifier = modifier
    )
}

/**
 * Individual 2:3 vertical poster card displayed in WatchlistScreen.
 */
@Composable
private fun WatchlistGridCard(
    entry: WatchlistEntry,
    isInDrive: Boolean,
    matchingDrivePoster: String? = null,
    onCardClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current

    val initialArtwork = matchingDrivePoster?.ifBlank { null }
        ?: entry.posterUrl.ifBlank { entry.backdropUrl }

    var resolvedFallbackPoster by remember(entry.id) { mutableStateOf("") }
    var imageLoadFailed by remember(initialArtwork) { mutableStateOf(false) }

    val effectivePosterUrl = when {
        resolvedFallbackPoster.isNotBlank() -> resolvedFallbackPoster
        !imageLoadFailed && !initialArtwork.isNullOrBlank() -> initialArtwork
        else -> ""
    }

    // Auto-resolve fallback artwork via GlobalCatalogSearchEngine / OnlineMetadataResolver
    LaunchedEffect(entry.id, entry.title, imageLoadFailed, initialArtwork) {
        if ((initialArtwork.isNullOrBlank() || imageLoadFailed) && resolvedFallbackPoster.isBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val searchResults = GlobalCatalogSearchEngine.searchOnlineCatalog(entry.title)
                    val best = searchResults.firstOrNull { it.posterUrl.isNotBlank() }?.posterUrl
                        ?: searchResults.firstOrNull { it.backdropUrl.isNotBlank() }?.backdropUrl

                    if (!best.isNullOrBlank()) {
                        resolvedFallbackPoster = best
                        WatchlistManager.setStatus(
                            context = context,
                            id = entry.id,
                            title = entry.title,
                            mediaType = entry.mediaType,
                            status = entry.status,
                            posterUrl = best,
                            backdropUrl = entry.backdropUrl,
                            year = entry.year,
                            genre = entry.genre
                        )
                    } else {
                        val resolved = if (entry.mediaType == WatchlistMediaType.SERIES) {
                            OnlineMetadataResolver.resolveSeries(entry.title).posterUrl
                        } else {
                            OnlineMetadataResolver.resolveMovie(entry.title).posterUrl
                        }
                        if (!resolved.isNullOrBlank()) {
                            resolvedFallbackPoster = resolved
                            WatchlistManager.setStatus(
                                context = context,
                                id = entry.id,
                                title = entry.title,
                                mediaType = entry.mediaType,
                                status = entry.status,
                                posterUrl = resolved,
                                backdropUrl = entry.backdropUrl,
                                year = entry.year,
                                genre = entry.genre
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onCardClick
            )
    ) {
        // Poster Box (2:3 aspect ratio)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceElevated)
                .border(BorderStroke(0.5.dp, SurfaceBorder), RoundedCornerShape(10.dp))
        ) {
            if (effectivePosterUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(effectivePosterUrl)
                        .crossfade(180)
                        .build(),
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    onError = { imageLoadFailed = true },
                    onSuccess = { imageLoadFailed = false },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Fallback Obsidian Cinema Monogram & Icon
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF1E2638), Color(0xFF0F1420))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Icon(
                            imageVector = if (entry.mediaType == WatchlistMediaType.SERIES) Icons.Rounded.Tv else Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = entry.title,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Top-Start Overlay Stack: Status Pill + Not in Drive Pill
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                WatchlistStatusPill(status = entry.status)

                if (!isInDrive) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xD908090D),
                        border = BorderStroke(0.5.dp, Color(0x5594A3B8))
                    ) {
                        Text(
                            text = "Not in Drive",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Top-End Bookmark Button
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                WatchlistBookmarkButton(
                    status = entry.status,
                    onClick = onBookmarkClick,
                    size = 28.dp
                )
            }

            // Bottom subtle gradient scrim
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xCC08090D))
                        )
                    )
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Media Type Tag (Movie / Series) + Year
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (entry.mediaType == WatchlistMediaType.SERIES) "Series" else "Movie",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = AccentBlue
            )
            if (entry.year.isNotBlank()) {
                Text(
                    text = "•",
                    fontSize = 9.sp,
                    color = TextTertiary
                )
                Text(
                    text = entry.year,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextTertiary
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Title text
        Text(
            text = entry.title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
