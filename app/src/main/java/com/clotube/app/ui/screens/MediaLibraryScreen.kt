package com.clotube.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.graphics.Bitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.clotube.app.model.DriveVideoFile
import com.clotube.app.model.VideoItem
import com.clotube.app.model.TvSeries
import com.clotube.app.ui.components.BottomNavBar
import com.clotube.app.ui.theme.BackgroundDeep
import com.clotube.app.ui.theme.BadgeBackground
import com.clotube.app.ui.theme.BadgeBorder
import com.clotube.app.ui.theme.BorderOutline
import com.clotube.app.ui.theme.CardContainer
import com.clotube.app.ui.theme.PillShape
import com.clotube.app.ui.theme.PrimaryAccent
import com.clotube.app.ui.theme.SecondaryAccent
import com.clotube.app.ui.theme.SurfaceDefault
import com.clotube.app.ui.theme.SurfaceElevated
import com.clotube.app.ui.theme.TextHighEmphasis
import com.clotube.app.ui.theme.TextMuted
import com.clotube.app.ui.theme.VideoCardShape
import com.clotube.app.viewmodel.NavigationTab
import com.clotube.app.viewmodel.VideoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaLibraryScreen(
    viewModel: VideoViewModel,
    onVideoSelected: (DriveVideoFile) -> Unit,
    onSeriesSelected: (TvSeries) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var isProfileMenuExpanded by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is com.clotube.app.MainActivity) return@remember ctx
            ctx = ctx.baseContext
        }
        ctx as? com.clotube.app.MainActivity
    }

    // Google Sign-In Activity Launcher
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (activity != null) {
            activity.handleGoogleSignInResult(result.data)
        } else {
            viewModel.handleSignInResult(result.data)
        }
    }

    Scaffold(
        containerColor = BackgroundDeep,
        topBar = {
            if (uiState.currentTab == NavigationTab.LIBRARY) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDefault,
                    titleContentColor = TextHighEmphasis
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Clotube",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextHighEmphasis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(PrimaryAccent.copy(alpha = 0.2f), PillShape)
                                .border(1.dp, PrimaryAccent, PillShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "4K RAW",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SecondaryAccent
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleSearchActive(!uiState.isSearchActive) }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = TextHighEmphasis
                        )
                    }
                    IconButton(onClick = { viewModel.fetchDriveVideos() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Drive Library",
                            tint = TextHighEmphasis
                        )
                    }

                    // Profile / Sign-in button with dropdown
                    Box(modifier = Modifier.padding(end = 12.dp)) {
                        IconButton(
                            onClick = {
                                if (uiState.isSignedIn) {
                                    isProfileMenuExpanded = true
                                } else {
                                    if (activity != null) {
                                        com.clotube.app.auth.GoogleAuthManager.launchGoogleSignIn(activity, signInLauncher)
                                    } else {
                                        com.clotube.app.auth.GoogleAuthManager.launchGoogleSignIn(context, signInLauncher)
                                    }
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, if (uiState.isSignedIn) PrimaryAccent else BorderOutline, CircleShape)
                        ) {
                            if (uiState.isSignedIn && !uiState.userAvatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = uiState.userAvatarUrl,
                                    contentDescription = "Google Profile Avatar",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = "Sign In",
                                    tint = if (uiState.isSignedIn) SecondaryAccent else TextMuted,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = isProfileMenuExpanded,
                            onDismissRequest = { isProfileMenuExpanded = false },
                            modifier = Modifier.background(SurfaceElevated)
                        ) {
                            if (!uiState.userEmail.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = uiState.userName ?: "Connected Account",
                                                fontWeight = FontWeight.Bold,
                                                color = TextHighEmphasis,
                                                fontSize = 13.sp
                                            )
                                            Text(
                                                text = uiState.userEmail ?: "",
                                                color = TextMuted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    },
                                    onClick = {}
                                )
                            }
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = SecondaryAccent)
                                },
                                text = { Text("Refresh Drive", color = TextHighEmphasis) },
                                onClick = {
                                    isProfileMenuExpanded = false
                                    viewModel.fetchDriveVideos()
                                }
                            )
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = Color(0xFFFF5252))
                                },
                                text = { Text("Sign Out", color = Color(0xFFFF5252)) },
                                onClick = {
                                    isProfileMenuExpanded = false
                                    viewModel.signOut()
                                }
                            )
                        }
                    }
                }
            )
            }
        },
        bottomBar = {
            BottomNavBar(
                selectedTab = uiState.currentTab,
                onTabSelected = { viewModel.selectTab(it) }
            )
        }
    ) { paddingValues ->
        BackHandler(enabled = uiState.currentTab != NavigationTab.LIBRARY) {
            viewModel.selectTab(NavigationTab.LIBRARY)
        }

        if (uiState.currentTab == NavigationTab.RECENT) {
            RecentScreen(
                playbackHistoryManager = viewModel.playbackHistoryManager,
                viewModel = viewModel,
                onResumeItem = { historyItem ->
                    if (historyItem.isSeries && !historyItem.seriesId.isNullOrBlank()) {
                        val s = viewModel.getSeriesById(historyItem.seriesId)
                        if (s != null) onSeriesSelected(s)
                    } else {
                        onVideoSelected(
                            VideoItem(
                                id = historyItem.fileId,
                                displayTitle = historyItem.title,
                                durationFormatted = "",
                                fileSizeFormatted = "",
                                genreFormatted = if (historyItem.isSeries) "Series" else "Movie",
                                resolutionBadge = "HD",
                                streamUri = com.clotube.app.player.DriveMediaSourceFactory.buildDriveStreamUri(historyItem.fileId).toString(),
                                thumbnailUrl = historyItem.thumbnailUrl.orEmpty()
                            )
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = paddingValues.calculateBottomPadding())
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Search Bar if expanded
                if (uiState.isSearchActive) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Search 4K videos in Drive...", color = TextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryAccent,
                        unfocusedBorderColor = BorderOutline,
                        focusedTextColor = TextHighEmphasis,
                        unfocusedTextColor = TextHighEmphasis,
                        focusedContainerColor = SurfaceElevated,
                        unfocusedContainerColor = SurfaceElevated
                    ),
                    shape = PillShape
                )
            }


            // Optional Error Message Banner
            uiState.errorMessage?.let { errorText ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = VideoCardShape,
                    color = Color(0xFF2A1515),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8B2525))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = errorText,
                            fontSize = 11.sp,
                            color = Color(0xFFFF8A80),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.fetchDriveVideos() },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                            shape = PillShape,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text("Retry", fontSize = 11.sp)
                        }
                    }
                }
            }

            // Horizontal Filter Chips
            HomeFilterChipsRow(
                selectedFilter = when (uiState.selectedFilter) {
                    HomeCatalogFilter.MOVIES.title -> HomeCatalogFilter.MOVIES
                    HomeCatalogFilter.TV_SERIES.title -> HomeCatalogFilter.TV_SERIES
                    else -> HomeCatalogFilter.ALL
                },
                onFilterSelected = { filter ->
                    viewModel.selectFilter(filter.title)
                }
            )

            // Video Cards Grid or Shimmer Loading State
            if (uiState.isLoading) {
                // Loading Shimmer Skeletons
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(6) {
                        VideoCardSkeleton()
                    }
                }
            } else if (uiState.filteredVideos.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (uiState.selectedFilter == "Movies") "No movies found" else if (uiState.selectedFilter == "TV Series") "No TV series found" else "No videos found",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextHighEmphasis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (uiState.isSignedIn) "Make sure you have video files in your Google Drive." else "Sign in to access your Google Drive video library.",
                            fontSize = 12.sp,
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                if (uiState.isSignedIn) viewModel.fetchDriveVideos()
                                else {
                                    if (activity != null) {
                                        com.clotube.app.auth.GoogleAuthManager.launchGoogleSignIn(activity, signInLauncher)
                                    } else {
                                        com.clotube.app.auth.GoogleAuthManager.launchGoogleSignIn(context, signInLauncher)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                            shape = PillShape
                        ) {
                            Text(if (uiState.isSignedIn) "Refresh Library" else "Connect Google Drive")
                        }
                    }
                }
            } else if (uiState.selectedFilter == "TV Series") {
                // TV Series OTT Cards Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = uiState.tvSeriesList,
                        key = { it.id },
                        contentType = { "series_grid_card" }
                    ) { series ->
                        SeriesCardItem(
                            series = series,
                            onClick = { onSeriesSelected(series) }
                        )
                    }
                }
            } else {
                // Featured OTT TV Series Spotlight
                if (uiState.tvSeriesList.isNotEmpty() && !uiState.isSearchActive && uiState.selectedFilter != "Movies") {
                    FeaturedSeriesBanner(
                        series = uiState.tvSeriesList.first(),
                        onWatchClick = { onSeriesSelected(uiState.tvSeriesList.first()) }
                    )
                }

                // 2-Column LazyVerticalGrid of Video Cards
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = uiState.filteredVideos,
                        key = { it.id },
                        contentType = { "video_grid_card" }
                    ) { video ->
                        VideoCardItem(
                            video = video,
                            onClick = { viewModel.openInspector(video) }
                        )
                    }
                }
            }
        }
    }
    }

    // Modal Bottom Sheet inspector when a video is selected
    uiState.selectedVideoForInspection?.let { selectedVideo ->
        StreamInspectorBottomSheet(
            video = selectedVideo,
            isHardwareAccelerationEnabled = uiState.isHardwareAccelerationEnabled,
            onHardwareAccelerationChanged = { viewModel.setHardwareAcceleration(it) },
            onDismiss = { viewModel.closeInspector() },
            onPlayFullQuality = {
                viewModel.closeInspector()
                onVideoSelected(selectedVideo)
            }
        )
    }
}

/**
 * Loading skeleton with cinematic shimmer effect
 */
@Composable
private fun VideoCardSkeleton() {
    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            SurfaceElevated.copy(alpha = 0.6f),
            SurfaceDefault.copy(alpha = 0.2f),
            SurfaceElevated.copy(alpha = 0.6f)
        ),
        start = Offset.Zero,
        end = Offset(x = translateAnim, y = translateAnim)
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VideoCardShape),
        shape = VideoCardShape,
        colors = CardDefaults.cardColors(containerColor = CardContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderOutline)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(shimmerBrush),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = SecondaryAccent.copy(alpha = 0.6f),
                    strokeWidth = 2.dp
                )
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(14.dp)
                        .background(shimmerBrush, PillShape)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .width(50.dp)
                            .height(10.dp)
                            .background(shimmerBrush, PillShape)
                    )
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(10.dp)
                            .background(shimmerBrush, PillShape)
                    )
                }
            }
        }
    }
}

/**
 * 2-Column Video Card with 16:9 thumbnail, badges, duration, title, and size
 */
@Composable
private fun VideoCardItem(
    video: DriveVideoFile,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VideoCardShape)
            .clickable(onClick = onClick),
        shape = VideoCardShape,
        colors = CardDefaults.cardColors(containerColor = CardContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderOutline)
    ) {
        Column {
            // 16:9 Thumbnail Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(SurfaceDefault)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(video.thumbnailUrl)
                        .crossfade(150)
                        .size(width = 360, height = 202)
                        .bitmapConfig(Bitmap.Config.HARDWARE)
                        .memoryCacheKey("thumb_${video.id}_${video.thumbnailUrl.hashCode()}")
                        .diskCacheKey("thumb_${video.id}_${video.thumbnailUrl.hashCode()}")
                        .build(),
                    contentDescription = video.displayTitle,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // Top-Left Badge ("4K 60FPS" / "HDR")
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(BadgeBackground, PillShape)
                            .border(1.dp, BadgeBorder, PillShape)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${video.resolutionBadge} ${video.fpsBadge}",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = SecondaryAccent
                        )
                    }

                    video.hdrBadge?.let { hdr ->
                        Box(
                            modifier = Modifier
                                .background(BadgeBackground, PillShape)
                                .border(1.dp, PrimaryAccent.copy(alpha = 0.6f), PillShape)
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = hdr,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryAccent
                            )
                        }
                    }
                }

                // Bottom-Right Duration Overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(BadgeBackground, PillShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = video.durationFormatted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextHighEmphasis
                    )
                }

                // Bottom-edge Watch Progress Bar
                if (video.watchProgressFraction > 0.02f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter)
                            .background(Color.White.copy(alpha = 0.25f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(video.watchProgressFraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(Color(0xFF2F80ED))
                        )
                    }
                }
            }

            // Title and File Size
            Column(
                modifier = Modifier.padding(10.dp)
            ) {
                Text(
                    text = video.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextHighEmphasis,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = video.sizeFormatted,
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                    Text(
                        text = video.bitrateFormatted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SecondaryAccent
                    )
                }
            }
        }
    }
}

/**
 * Featured OTT TV Series Banner for Spotlight display on top of library
 */
@Composable
private fun FeaturedSeriesBanner(
    series: TvSeries,
    onWatchClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onWatchClick),
        shape = VideoCardShape,
        color = SurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryAccent.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(78.dp)
                    .aspectRatio(16f / 9f)
                    .clip(VideoCardShape)
                    .background(SurfaceDefault)
            ) {
                AsyncImage(
                    model = series.backdropUrl ?: series.posterUrl,
                    contentDescription = series.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "FEATURED OTT SERIES",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = SecondaryAccent
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• ${series.totalSeasonsFormatted}",
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
                Text(
                    text = series.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextHighEmphasis
                )
                Text(
                    text = series.genreBadge,
                    fontSize = 10.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onWatchClick,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                shape = PillShape,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text("Seasons", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * 2-Column TV Series Card with 16:9 thumbnail, OTT Series Badge, and Season Count
 */
@Composable
private fun SeriesCardItem(
    series: TvSeries,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(VideoCardShape)
            .clickable(onClick = onClick),
        shape = VideoCardShape,
        colors = CardDefaults.cardColors(containerColor = CardContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderOutline)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(SurfaceDefault)
            ) {
                val thumbUrl = series.thumbnailUrl.ifBlank { series.backdropUrl ?: series.posterUrl ?: "" }
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(thumbUrl)
                        .crossfade(150)
                        .size(width = 360, height = 202)
                        .bitmapConfig(Bitmap.Config.HARDWARE)
                        .memoryCacheKey("thumb_${series.id}_${thumbUrl.hashCode()}")
                        .diskCacheKey("thumb_${series.id}_${thumbUrl.hashCode()}")
                        .build(),
                    contentDescription = series.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(BadgeBackground, PillShape)
                        .border(1.dp, BadgeBorder, PillShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "OTT SERIES",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = SecondaryAccent
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(BadgeBackground, PillShape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = series.totalSeasonsFormatted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextHighEmphasis
                    )
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = series.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextHighEmphasis,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = series.genreBadge,
                    fontSize = 10.sp,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

