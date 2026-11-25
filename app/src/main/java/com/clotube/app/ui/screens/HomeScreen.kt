package com.clotube.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileDownloadDone
import com.clotube.app.util.triggerClickHaptic
import androidx.compose.ui.unit.Dp
import com.clotube.app.data.DownloadStatus
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.graphics.Bitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.CachePolicy
import com.clotube.app.model.DriveVideoFile
import com.clotube.app.model.TvSeries
import com.clotube.app.model.VideoItem
import com.clotube.app.player.DriveMediaSourceFactory
import com.clotube.app.ui.components.BottomNavBar
import com.clotube.app.ui.components.ClassyBottomBar
import com.clotube.app.ui.components.DriveFolderPickerDialog
import com.clotube.app.ui.components.GoogleAccountDialog
import kotlinx.coroutines.launch
import com.clotube.app.ui.components.HomeSkeletonScreen
import com.clotube.app.ui.components.ShimmerCategoryRow
import com.clotube.app.ui.components.ShimmerHeroBillboard
import com.clotube.app.ui.components.ShimmerMediaSection
import androidx.compose.material3.ripple
import com.clotube.app.ui.theme.AppleTvMotion
import com.clotube.app.ui.theme.appleTvClickable
import com.clotube.app.ui.theme.appleTvPressable
import com.clotube.app.ui.theme.BackgroundDeep
import com.clotube.app.ui.theme.BadgeBackground
import com.clotube.app.ui.theme.BadgeBorder
import com.clotube.app.ui.theme.BorderOutline
import com.clotube.app.ui.theme.CardContainer
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Tv
import com.clotube.app.data.MetadataDiskCache
import com.clotube.app.data.MovieIndustryResolver
import com.clotube.app.data.WatchlistManager
import com.clotube.app.data.metadata.GlobalCatalogSearchEngine
import com.clotube.app.data.metadata.OnlineMetadataResolver
import com.clotube.app.model.WatchlistMediaType
import com.clotube.app.model.WatchlistStatus
import com.clotube.app.model.toMovie
import com.clotube.app.ui.components.WatchlistBookmarkButton
import com.clotube.app.ui.components.WatchlistStatusBottomSheet
import com.clotube.app.ui.components.WatchlistStatusPill
import com.clotube.app.ui.screens.WatchlistScreen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.vector.ImageVector
import com.clotube.app.viewmodel.ContinueWatchingDisplayData
import com.clotube.app.viewmodel.HeroSlotState
import com.clotube.app.model.Episode
import com.clotube.app.model.Movie
import com.clotube.app.model.Series
import com.clotube.app.ui.theme.CanvasDeep
import com.clotube.app.ui.theme.SurfaceBorder
import com.clotube.app.ui.theme.AccentPrimary
import com.clotube.app.ui.theme.AccentBlue
import com.clotube.app.ui.theme.AccentGlow
import com.clotube.app.ui.theme.TextPrimary
import com.clotube.app.ui.theme.TextSecondary
import com.clotube.app.ui.theme.TextTertiary
import com.clotube.app.ui.theme.PillShape
import com.clotube.app.ui.theme.PrimaryAccent
import com.clotube.app.ui.theme.SecondaryAccent
import com.clotube.app.ui.theme.SurfaceDefault
import com.clotube.app.ui.theme.SurfaceElevated
import com.clotube.app.ui.theme.TextHighEmphasis
import com.clotube.app.ui.theme.TextMuted
import com.clotube.app.ui.theme.VideoCardShape
import androidx.annotation.Keep
import com.clotube.app.viewmodel.NavigationTab
import com.clotube.app.viewmodel.VideoViewModel
import com.clotube.app.viewmodel.formatTimestamp

@Keep
enum class HomeCatalogFilter(val title: String) {
    ALL("All"),
    MOVIES("Movies"),
    TV_SERIES("Series")
}

@Keep
enum class HomeNavigationTab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    SEARCH("Search", Icons.Rounded.Search),
    WATCHLIST("Watchlist", Icons.Rounded.Bookmarks),
    RECENT("Recent", Icons.Rounded.History),
    SETTINGS("Settings", Icons.Rounded.Settings)
}

@Composable
fun HomeFilterChipsRow(
    selectedFilter: HomeCatalogFilter,
    onFilterSelected: (HomeCatalogFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HomeCatalogFilter.values().forEach { filter ->
            val isSelected = selectedFilter == filter
            val backgroundColor by animateColorAsState(
                targetValue = if (isSelected) Color.White else SurfaceDefault,
                animationSpec = AppleTvMotion.fluidSpring(),
                label = "filterChipBackground_${filter.name}"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) Color(0xFF08090D) else TextSecondary,
                animationSpec = AppleTvMotion.fluidSpring(),
                label = "filterChipText_${filter.name}"
            )
            val interactionSource = remember { MutableInteractionSource() }
            Surface(
                modifier = Modifier
                    .appleTvPressable(interactionSource, pressedScale = 0.94f)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = ripple(bounded = true)
                    ) { onFilterSelected(filter) },
                shape = RoundedCornerShape(20.dp),
                color = backgroundColor,
                border = if (isSelected) null else BorderStroke(1.dp, SurfaceBorder)
            ) {
                Text(
                    text = filter.title,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

/**
 * Full OTT-style Home Screen (Netflix / Prime Video style):
 * - Hero Billboard (16:9 featured title banner + Watch Now button)
 * - Row 1: "Movies" horizontal LazyRow (16:9 cards)
 * - Row 2: "TV Series" horizontal LazyRow (16:9 cards)
 * - Drive folder onboarding dialog with automated setup
 * - Storage & Sync Banner with live Google avatar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: VideoViewModel,
    onVideoSelected: (DriveVideoFile) -> Unit,
    onSeriesSelected: (TvSeries) -> Unit,
    onResumePlayback: ((fileId: String, title: String, seriesId: String?, positionMs: Long) -> Unit)? = null,
    onNavigateToSettings: () -> Unit = { viewModel.selectTab(NavigationTab.SETTINGS) },
    onNavigateToSeriesDetail: ((seriesId: String, autoPlayEpisodeId: String?) -> Unit)? = null,
    onPlayMovie: ((fileId: String) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val heroState by viewModel.heroSlotState.collectAsState()
    val driveMovies by viewModel.moviesList.collectAsState()
    val driveSeries by viewModel.seriesList.collectAsState()
    var showAccountDialog by remember { mutableStateOf(false) }
    val currentUser by viewModel.currentUserAccount.collectAsState()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var selectedMovieLanguage by remember { mutableStateOf("All") }

    val allDownloadRecords by viewModel.downloadRepository
        .getAllDownloadRecords()
        .collectAsState(initial = emptyMap())

    var movieToDeleteOffline by remember { mutableStateOf<VideoItem?>(null) }

    fun handleMovieDownloadAction(movie: VideoItem) {
        val record = allDownloadRecords[movie.id]
        when (record?.status) {
            com.clotube.app.data.DownloadStatus.COMPLETED -> {
                movieToDeleteOffline = movie
            }
            com.clotube.app.data.DownloadStatus.DOWNLOADING,
            com.clotube.app.data.DownloadStatus.QUEUED -> {
                coroutineScope.launch {
                    viewModel.downloadRepository.cancelDownload(movie.id)
                }
            }
            else -> {
                coroutineScope.launch {
                    val token = viewModel.getValidAccessToken()
                    viewModel.downloadRepository.enqueueDownload(
                        fileId = movie.id,
                        title = movie.displayTitle,
                        authToken = token
                    )
                    viewModel.downloadRepository.requestUnrestrictedBatteryIfNeeded()
                }
            }
        }
    }

    movieToDeleteOffline?.let { targetMovie ->
        AlertDialog(
            onDismissRequest = { movieToDeleteOffline = null },
            containerColor = Color(0xFF131722),
            title = {
                Text(
                    text = "Remove Offline Movie?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = "Delete \"${targetMovie.displayTitle}\" from device storage? You can download it again anytime while online.",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val idToDelete = targetMovie.id
                        movieToDeleteOffline = null
                        coroutineScope.launch {
                            viewModel.downloadRepository.deleteDownload(idToDelete)
                        }
                    }
                ) {
                    Text("Delete", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { movieToDeleteOffline = null }) {
                    Text("Cancel", color = Color(0xFF94A3B8))
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    var showFolderPickerType by remember { mutableStateOf<String?>(null) }
    var availableDriveFolders by remember { mutableStateOf<List<com.google.api.services.drive.model.File>>(emptyList()) }
    var isFetchingFolders by remember { mutableStateOf(false) }

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

    // Onboarding Dialog for Google Drive 'Movies' and 'Series' folders
    if (uiState.showFolderOnboardingDialog) {
        DriveFolderOnboardingDialog(
            isCreating = uiState.isCreatingFolders,
            onConfirm = { viewModel.confirmCreateFolders(createMovies = true, createSeries = true) },
            onDismiss = { viewModel.dismissOnboardingDialog() }
        )
    }

    val savedProfiles by viewModel.savedProfiles.collectAsState()

    // Manual Folder Picker Dialog
    if (showFolderPickerType != null) {
        DriveFolderPickerDialog(
            folderType = showFolderPickerType!!,
            folders = availableDriveFolders,
            isLoading = isFetchingFolders,
            onFolderSelected = { selectedFolder ->
                viewModel.selectCustomFolder(showFolderPickerType!!, selectedFolder)
                showFolderPickerType = null
            },
            onDismiss = {
                showFolderPickerType = null
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        containerColor = CanvasDeep,
        topBar = {
            if (uiState.currentTab == NavigationTab.LIBRARY) {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = CanvasDeep,
                        titleContentColor = Color.White
                    ),
                    title = {
                        Text(
                            text = "Clotube",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    },
                    actions = {
                        // Profile / Sign-in Avatar on far right
                        Box(modifier = Modifier.padding(start = 4.dp, end = 12.dp)) {
                            IconButton(
                                onClick = {
                                    if (uiState.isSignedIn || currentUser != null) {
                                        showAccountDialog = true
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
                                    .border(1.dp, SurfaceBorder, CircleShape)
                            ) {
                                val photo = currentUser?.photoUrl ?: uiState.userAvatarUrl
                                if ((uiState.isSignedIn || currentUser != null) && photo != null) {
                                    AsyncImage(
                                        model = photo,
                                        contentDescription = "Profile",
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = if (uiState.isSignedIn || currentUser != null) "Profile" else "Sign In",
                                        tint = if (uiState.isSignedIn || currentUser != null) AccentBlue else Color(0xFF94A3B8),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            ClassyBottomBar(
                selectedTab = uiState.currentTab,
                onTabSelected = { tab ->
                    if (tab == NavigationTab.SETTINGS) {
                        onNavigateToSettings()
                    } else {
                        viewModel.selectTab(tab)
                    }
                },
                modifier = Modifier.zIndex(10f)
            )
        }
    ) { paddingValues ->
        BackHandler(enabled = uiState.currentTab != NavigationTab.LIBRARY) {
            viewModel.selectTab(NavigationTab.LIBRARY)
        }

        val isHomeVisible = uiState.currentTab == NavigationTab.LIBRARY

        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Persistently Mounted Home Feed
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (isHomeVisible) 1f else 0f
                    }
                    .then(if (!isHomeVisible) Modifier.zIndex(-1f) else Modifier.zIndex(0f))
            ) {
                Crossfade(
                    targetState = uiState.isInitialLoading,
                    animationSpec = tween(durationMillis = 280),
                    label = "HomeSkeletonCrossfade"
                ) { initialLoading ->
                    if (initialLoading) {
                        HomeSkeletonScreen(contentPadding = paddingValues)
                    } else {
            val searchQuery = uiState.searchQuery.trim()
            val rawMovies = uiState.filteredVideos
            val displayMovies = remember(rawMovies) {
                val seenIds = HashSet<String>()
                rawMovies.filter { movie ->
                    movie.id.isNotBlank() && seenIds.add(movie.id)
                }
            }
            val moviesByLanguage = remember(displayMovies) {
                displayMovies.groupBy { movie ->
                    val section = MovieIndustryResolver.resolveVideoSection(movie)
                    section.ifBlank { movie.language.ifBlank { "English" } }
                }
            }
            val orderedLanguages = remember(moviesByLanguage) {
                MovieIndustryResolver.sortIndustrySections(moviesByLanguage.keys)
            }

            LaunchedEffect(orderedLanguages) {
                if (selectedMovieLanguage != "All" && selectedMovieLanguage !in orderedLanguages) {
                    selectedMovieLanguage = "All"
                }
            }
            val rawSeries = uiState.tvSeriesList
            val displaySeries = remember(rawSeries, searchQuery) {
                val filtered = if (searchQuery.isBlank()) {
                    rawSeries
                } else {
                    rawSeries.filter {
                        it.title.contains(searchQuery, ignoreCase = true) ||
                        it.genre.contains(searchQuery, ignoreCase = true)
                    }
                }
                val seenIds = HashSet<String>()
                val seenNorms = HashSet<String>()
                filtered.filter { series ->
                    val norm = WatchlistManager.normalizeTitle(series.title)
                    val idAdded = seenIds.add(series.id)
                    val normAdded = norm.isBlank() || seenNorms.add(norm)
                    idAdded && normAdded
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
            ) {
                // Top Filter Chips pinned below App Bar
                HomeFilterChipsRow(
                    selectedFilter = when (uiState.selectedFilter) {
                        HomeCatalogFilter.MOVIES.title -> HomeCatalogFilter.MOVIES
                        HomeCatalogFilter.TV_SERIES.title, "TV Series" -> HomeCatalogFilter.TV_SERIES
                        else -> HomeCatalogFilter.ALL
                    },
                    onFilterSelected = { filter ->
                        viewModel.selectFilter(filter.title)
                    }
                )

                val isRefreshing by viewModel.isRefreshing.collectAsState()
                val pullRefreshState = rememberPullToRefreshState()
                val haptic = LocalHapticFeedback.current

                // STABLE HAPTIC THRESHOLD SNAP: Fires when the user pulls past the activation point
                LaunchedEffect(pullRefreshState) {
                    var hasTriggeredThreshold = false
                    snapshotFlow { pullRefreshState.distanceFraction }
                        .map { it >= 1.0f }
                        .distinctUntilChanged()
                        .collect { isPastThreshold ->
                            if (isPastThreshold && !hasTriggeredThreshold) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                hasTriggeredThreshold = true
                            } else if (!isPastThreshold) {
                                hasTriggeredThreshold = false
                            }
                        }
                }

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    state = pullRefreshState,
                    onRefresh = {
                        // Release confirmation pulse
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.refreshAppFreshBoot()
                    },
                    indicator = {
                        if (isRefreshing || pullRefreshState.distanceFraction > 0.05f) {
                            PullToRefreshDefaults.Indicator(
                                state = pullRefreshState,
                                isRefreshing = isRefreshing,
                                color = AccentBlue,
                                containerColor = SurfaceElevated,
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    AnimatedContent(
                        targetState = uiState.selectedFilter,
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(280, easing = AppleTvMotion.SmoothOutEasing)) +
                                scaleIn(initialScale = 0.97f, animationSpec = tween(320, easing = AppleTvMotion.SmoothOutEasing)))
                                .togetherWith(
                                    fadeOut(animationSpec = tween(150))
                                )
                        },
                        label = "homeFilterContentSwitch"
                    ) { currentFilter ->
                        when (currentFilter) {
                        HomeCatalogFilter.MOVIES.title -> {
                            // Full-screen 3-column Grid of All Movies (Continue Watching hidden)
                            if (displayMovies.isEmpty() && !uiState.isInitialLoading && !uiState.isLoading) {
                                CatalogEmptyView(
                                    title = if (searchQuery.isNotBlank()) "No Matching Movies Found" else "No Movies Found",
                                    subtitle = if (searchQuery.isNotBlank()) {
                                        "No movies found matching \"$searchQuery\". Try searching by title or genre tag."
                                    } else {
                                        "Place movie files inside Google Drive > 'Movies' folder."
                                    },
                                    isSearchActive = searchQuery.isNotBlank(),
                                    onClearSearch = { viewModel.onSearchQueryChanged("") },
                                    onRefresh = { viewModel.refreshCatalog(forceSync = true) },
                                    onChooseMovies = {
                                        coroutineScope.launch {
                                            isFetchingFolders = true
                                            showFolderPickerType = "Movies"
                                            availableDriveFolders = viewModel.fetchAllDriveFolders()
                                            isFetchingFolders = false
                                        }
                                    },
                                    onChooseSeries = {
                                        coroutineScope.launch {
                                            isFetchingFolders = true
                                            showFolderPickerType = "Series"
                                            availableDriveFolders = viewModel.fetchAllDriveFolders()
                                            isFetchingFolders = false
                                        }
                                    }
                                )
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    if (orderedLanguages.size > 1) {
                                        LazyRow(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            val pillOptions = listOf("All") + orderedLanguages
                                            items(pillOptions, key = { it }) { languageOption ->
                                                val isSelected = selectedMovieLanguage == languageOption
                                                val backgroundColor by animateColorAsState(
                                                    targetValue = if (isSelected) Color.White else SurfaceDefault,
                                                    animationSpec = AppleTvMotion.fluidSpring(),
                                                    label = "movieLangFilterChipBg_$languageOption"
                                                )
                                                val textColor by animateColorAsState(
                                                    targetValue = if (isSelected) Color(0xFF08090D) else TextSecondary,
                                                    animationSpec = AppleTvMotion.fluidSpring(),
                                                    label = "movieLangFilterChipText_$languageOption"
                                                )
                                                val interactionSource = remember { MutableInteractionSource() }
                                                Surface(
                                                    modifier = Modifier
                                                        .appleTvPressable(interactionSource, pressedScale = 0.94f)
                                                        .clip(RoundedCornerShape(20.dp))
                                                        .clickable(
                                                            interactionSource = interactionSource,
                                                            indication = ripple(bounded = true)
                                                        ) { selectedMovieLanguage = languageOption },
                                                    shape = RoundedCornerShape(20.dp),
                                                    color = backgroundColor,
                                                    border = if (isSelected) null else BorderStroke(1.dp, SurfaceBorder)
                                                ) {
                                                    Text(
                                                        text = languageOption,
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = textColor,
                                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(3),
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 110.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        if (selectedMovieLanguage == "All" && orderedLanguages.size > 1) {
                                            orderedLanguages.forEach { languageSection ->
                                                val sectionMovies = moviesByLanguage[languageSection].orEmpty()
                                                if (sectionMovies.isNotEmpty()) {
                                                    item(
                                                        key = "lang_header_$languageSection",
                                                        span = { GridItemSpan(maxLineSpan) }
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(top = if (languageSection == orderedLanguages.first()) 2.dp else 10.dp, bottom = 2.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text(
                                                                text = languageSection,
                                                                color = Color.White,
                                                                fontSize = 16.sp,
                                                                fontWeight = FontWeight.SemiBold
                                                            )
                                                            Text(
                                                                text = "${sectionMovies.size} ${if (sectionMovies.size == 1) "movie" else "movies"}",
                                                                color = Color(0xFF94A3B8),
                                                                fontSize = 12.sp
                                                            )
                                                        }
                                                    }
                                                    items(
                                                        items = sectionMovies,
                                                        key = { it.id },
                                                        contentType = { "movie_poster_card" }
                                                    ) { movie ->
                                                        MoviePosterCard(
                                                            movie = movie,
                                                            onClick = {
                                                                if (onPlayMovie != null) {
                                                                    onPlayMovie(movie.id)
                                                                } else {
                                                                    onVideoSelected(movie)
                                                                }
                                                            },
                                                            modifier = Modifier.fillMaxWidth(),
                                                            downloadStatus = allDownloadRecords[movie.id]?.status,
                                                            downloadProgress = allDownloadRecords[movie.id]?.progressFraction ?: 0f,
                                                            onDownloadClick = { handleMovieDownloadAction(movie) }
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            val targetMovies = if (selectedMovieLanguage != "All") {
                                                moviesByLanguage[selectedMovieLanguage].orEmpty()
                                            } else {
                                                displayMovies
                                            }
                                            items(
                                                items = targetMovies,
                                                key = { it.id },
                                                contentType = { "movie_poster_card" }
                                            ) { movie ->
                                                MoviePosterCard(
                                                    movie = movie,
                                                    onClick = {
                                                        if (onPlayMovie != null) {
                                                            onPlayMovie(movie.id)
                                                        } else {
                                                            onVideoSelected(movie)
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    downloadStatus = allDownloadRecords[movie.id]?.status,
                                                    downloadProgress = allDownloadRecords[movie.id]?.progressFraction ?: 0f,
                                                    onDownloadClick = { handleMovieDownloadAction(movie) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        HomeCatalogFilter.TV_SERIES.title, "TV Series" -> {
                            // Full-screen Cinematic List of All TV Series (Continue Watching hidden)
                            if (displaySeries.isEmpty() && !uiState.isInitialLoading && !uiState.isLoading) {
                                CatalogEmptyView(
                                    title = if (searchQuery.isNotBlank()) "No Matching Series Found" else "No TV Series Found",
                                    subtitle = if (searchQuery.isNotBlank()) {
                                        "No TV series found matching \"$searchQuery\". Try searching by title or genre tag."
                                    } else {
                                        "Place TV series folders inside Google Drive > 'Series' folder."
                                    },
                                    isSearchActive = searchQuery.isNotBlank(),
                                    onClearSearch = { viewModel.onSearchQueryChanged("") },
                                    onRefresh = { viewModel.refreshCatalog(forceSync = true) },
                                    onChooseMovies = {
                                        coroutineScope.launch {
                                            isFetchingFolders = true
                                            showFolderPickerType = "Movies"
                                            availableDriveFolders = viewModel.fetchAllDriveFolders()
                                            isFetchingFolders = false
                                        }
                                    },
                                    onChooseSeries = {
                                        coroutineScope.launch {
                                            isFetchingFolders = true
                                            showFolderPickerType = "Series"
                                            availableDriveFolders = viewModel.fetchAllDriveFolders()
                                            isFetchingFolders = false
                                        }
                                    }
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(
                                        items = displaySeries,
                                        key = { it.id },
                                        contentType = { "series_row_card" }
                                    ) { series ->
                                        SeriesRowCard(
                                            series = series,
                                            onClick = {
                                                onNavigateToSeriesDetail?.invoke(series.id, null) ?: onSeriesSelected(series)
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            // "All": Displays the "Continue Watching" card (if in progress) followed by horizontal preview carousels for Movies and TV Series
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 110.dp)
                            ) {
                                item(key = "home_hero_billboard") {
                                    when (val state = heroState) {
                                        is HeroSlotState.Resolving -> {
                                            ShimmerHeroBillboard()
                                        }
                                        is HeroSlotState.ContinueWatching -> {
                                            val item = state.item
                                            ContinueWatchingCard(
                                                data = item,
                                                onResumeClick = {
                                                    android.util.Log.i("ClotubePlayer", "--> Home ContinueWatching clicked: isSeries=${item.isSeries}, seriesId=${item.seriesId}, mediaId=${item.mediaId}")
                                                    if (item.isSeries || !item.seriesId.isNullOrBlank()) {
                                                        val candidateSeriesId = item.seriesId?.trim()?.ifBlank { null }
                                                        val resolvedFromCatalog = if (candidateSeriesId != null && (uiState.tvSeriesList.isEmpty() || uiState.tvSeriesList.any { it.id == candidateSeriesId })) {
                                                            candidateSeriesId
                                                        } else {
                                                            uiState.tvSeriesList.firstOrNull { s ->
                                                                s.seasons.any { season -> season.episodes.any { ep -> ep.id == item.mediaId } }
                                                            }?.id ?: candidateSeriesId
                                                        }
                                                        val targetSeriesId = resolvedFromCatalog
                                                            ?: viewModel.getSeriesIdForEpisode(item.mediaId, item.seriesTitle.ifBlank { item.displayHeader })
                                                            ?: viewModel.getDefaultSeriesId()
                                                            ?: ""

                                                        if (targetSeriesId.isNotBlank()) {
                                                            if (onNavigateToSeriesDetail != null) {
                                                                onNavigateToSeriesDetail(targetSeriesId, item.mediaId)
                                                            } else if (onResumePlayback != null) {
                                                                onResumePlayback(item.mediaId, item.displayTitle, targetSeriesId, item.currentPositionMs)
                                                            } else {
                                                                val matchedSeries = uiState.tvSeriesList.find { it.id == targetSeriesId }
                                                                if (matchedSeries != null) {
                                                                    onSeriesSelected(matchedSeries)
                                                                }
                                                            }
                                                        }
                                                    } else if (item.mediaId.isNotBlank()) {
                                                        if (onPlayMovie != null) {
                                                            onPlayMovie(item.mediaId)
                                                        } else if (onResumePlayback != null) {
                                                            onResumePlayback(item.mediaId, item.displayHeader, null, item.currentPositionMs)
                                                        } else {
                                                            val movie = viewModel.getMovieById(item.mediaId)
                                                            if (movie != null) {
                                                                onVideoSelected(movie)
                                                            }
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                        is HeroSlotState.FeaturedFallback -> {
                                            val featuredMovie = state.movie
                                            val videoItem = remember(featuredMovie, uiState.moviesList) {
                                                uiState.moviesList.firstOrNull { it.id == featuredMovie.id } ?: featuredMovie.toVideoItem()
                                            }
                                            FeaturedHeroBanner(
                                                movie = featuredMovie,
                                                onWatchMovie = {
                                                    if (onPlayMovie != null) onPlayMovie(featuredMovie.id) else onVideoSelected(videoItem)
                                                },
                                                onInspect = {
                                                    if (onPlayMovie != null) onPlayMovie(featuredMovie.id) else onVideoSelected(videoItem)
                                                },
                                                downloadStatus = allDownloadRecords[featuredMovie.id]?.status,
                                                downloadProgress = allDownloadRecords[featuredMovie.id]?.progressFraction ?: 0f,
                                                onDownloadClick = { handleMovieDownloadAction(videoItem) }
                                            )
                                        }
                                    }
                                }

                                if (displayMovies.isNotEmpty()) {
                                    item(key = "home_movies_preview") {
                                        SectionHeader(
                                            title = "Movies",
                                            onSeeAllClick = {
                                                selectedMovieLanguage = "All"
                                                viewModel.selectFilter(HomeCatalogFilter.MOVIES.title)
                                            }
                                        )
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                                        ) {
                                            items(
                                                items = displayMovies,
                                                key = { item -> item.id },
                                                contentType = { "movie_card" }
                                            ) { movie ->
                                                MovieCard(
                                                    movie = movie,
                                                    onClick = {
                                                        if (onPlayMovie != null) {
                                                            onPlayMovie(movie.id)
                                                        } else {
                                                            onVideoSelected(movie)
                                                        }
                                                    },
                                                    modifier = Modifier.width(220.dp),
                                                    downloadStatus = allDownloadRecords[movie.id]?.status,
                                                    downloadProgress = allDownloadRecords[movie.id]?.progressFraction ?: 0f,
                                                    onDownloadClick = { handleMovieDownloadAction(movie) }
                                                )
                                            }
                                        }
                                    }
                                }

                                if (displaySeries.isNotEmpty()) {
                                    item(key = "home_series_preview") {
                                        if (displayMovies.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(14.dp))
                                        }
                                        SectionHeader(
                                            title = "Series",
                                            onSeeAllClick = { viewModel.selectFilter(HomeCatalogFilter.TV_SERIES.title) }
                                        )
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                                        ) {
                                            items(
                                                items = displaySeries,
                                                key = { item -> item.id },
                                                contentType = { "series_card" }
                                            ) { series ->
                                                SeriesCard(
                                                    series = series,
                                                    onClick = {
                                                        onNavigateToSeriesDetail?.invoke(series.id, null) ?: onSeriesSelected(series)
                                                    },
                                                    modifier = Modifier.width(220.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                if (displayMovies.isEmpty() && displaySeries.isEmpty() && !uiState.isInitialLoading && !uiState.isLoading) {
                                    item(key = "home_empty_view") {
                                        CatalogEmptyView(
                                            title = if (searchQuery.isNotBlank()) "No Matching Media Found" else "Dedicated Folders Empty",
                                            subtitle = if (searchQuery.isNotBlank()) {
                                                "No movies or TV shows found matching \"$searchQuery\". Try searching by title or genre tag."
                                            } else {
                                                "Clotube enforces strict folder isolation. Place movie files inside Google Drive > 'Movies', and TV show folders inside Google Drive > 'Series'. Personal videos elsewhere in Drive are never accessed."
                                            },
                                            isSearchActive = searchQuery.isNotBlank(),
                                            onClearSearch = { viewModel.onSearchQueryChanged("") },
                                            onRefresh = { viewModel.refreshCatalog(forceSync = true) },
                                            onChooseMovies = {
                                                coroutineScope.launch {
                                                    isFetchingFolders = true
                                                    showFolderPickerType = "Movies"
                                                    availableDriveFolders = viewModel.fetchAllDriveFolders()
                                                    isFetchingFolders = false
                                                }
                                            },
                                            onChooseSeries = {
                                                coroutineScope.launch {
                                                    isFetchingFolders = true
                                                    showFolderPickerType = "Series"
                                                    availableDriveFolders = viewModel.fetchAllDriveFolders()
                                                    isFetchingFolders = false
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

        // 2. Overlaid Tab Destinations
        if (uiState.currentTab == NavigationTab.RECENT) {
            RecentScreen(
                playbackHistoryManager = viewModel.playbackHistoryManager,
                viewModel = viewModel,
                moviesList = uiState.movies.ifEmpty { uiState.moviesList.map { it.toMovie() } },
                tvSeriesList = uiState.tvSeriesList,
                onResumeItem = { historyItem ->
                    val sId = if (historyItem.isSeries) {
                        if (!historyItem.seriesId.isNullOrBlank()) historyItem.seriesId
                        else uiState.tvSeriesList.find { s -> s.seasons.any { season -> season.episodes.any { ep -> ep.id == historyItem.fileId } } }?.id
                    } else null

                    if (onResumePlayback != null) {
                        onResumePlayback(historyItem.fileId, historyItem.title, sId, historyItem.positionMs)
                    } else {
                        if (!sId.isNullOrBlank()) {
                            val matchedSeries = uiState.tvSeriesList.find { it.id == sId }
                            if (matchedSeries != null) {
                                onSeriesSelected(matchedSeries)
                                return@RecentScreen
                            }
                        }
                        val matchedMovie = uiState.moviesList.find { it.id == historyItem.fileId }
                            ?: uiState.videos.find { it.id == historyItem.fileId }
                        if (matchedMovie != null) {
                            onVideoSelected(matchedMovie)
                        } else {
                            onVideoSelected(
                                VideoItem(
                                    id = historyItem.fileId,
                                    displayTitle = historyItem.title,
                                    durationFormatted = formatTimestamp(historyItem.durationMs),
                                    fileSizeFormatted = "",
                                    genreFormatted = if (historyItem.isSeries) "Series" else "Movie",
                                    resolutionBadge = "HD",
                                    streamUri = DriveMediaSourceFactory.buildDriveStreamUri(historyItem.fileId).toString(),
                                    thumbnailUrl = historyItem.thumbnailUrl.orEmpty()
                                )
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = paddingValues.calculateBottomPadding())
            )
        } else if (uiState.currentTab == NavigationTab.SEARCH) {
            val effectiveDriveMovies = remember(uiState.movies, uiState.videos, driveMovies) {
                uiState.movies
                    .ifEmpty { uiState.videos.map { it.toMovie() } }
                    .ifEmpty { driveMovies }
            }
            val effectiveDriveSeries = remember(uiState.tvSeriesList, driveSeries) {
                uiState.tvSeriesList.ifEmpty { driveSeries }
            }
            SearchScreen(
                viewModel = viewModel,
                moviesList = effectiveDriveMovies,
                seriesList = effectiveDriveSeries,
                onMovieSelected = { movie ->
                    if (onPlayMovie != null) {
                        onPlayMovie(movie.id)
                    } else {
                        val matchedMovie = uiState.moviesList.find { it.id == movie.id }
                            ?: uiState.videos.find { it.id == movie.id }
                        if (matchedMovie != null) {
                            onVideoSelected(matchedMovie)
                        } else {
                            onVideoSelected(
                                VideoItem(
                                    id = movie.id,
                                    displayTitle = movie.title,
                                    durationFormatted = movie.formattedDuration,
                                    fileSizeFormatted = movie.fileSize,
                                    genreFormatted = movie.genre,
                                    resolutionBadge = movie.resolution,
                                    streamUri = movie.streamUri,
                                    thumbnailUrl = movie.thumbnailUrl.orEmpty()
                                )
                            )
                        }
                    }
                },
                onSeriesSelected = { series ->
                    if (onNavigateToSeriesDetail != null) {
                        onNavigateToSeriesDetail(series.id, null)
                    } else {
                        onSeriesSelected(series)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = paddingValues.calculateBottomPadding())
            )
        } else if (uiState.currentTab == NavigationTab.WATCHLIST) {
            val effectiveDriveMovies = remember(uiState.movies, uiState.videos, driveMovies) {
                uiState.movies
                    .ifEmpty { uiState.videos.map { it.toMovie() } }
                    .ifEmpty { driveMovies }
            }
            val effectiveDriveSeries = remember(uiState.tvSeriesList, driveSeries) {
                uiState.tvSeriesList.ifEmpty { driveSeries }
            }
            WatchlistScreen(
                moviesList = effectiveDriveMovies,
                seriesList = effectiveDriveSeries,
                onMovieSelected = { entry ->
                    val matchedMovie = uiState.moviesList.find { it.id == entry.id }
                        ?: uiState.videos.find { it.id == entry.id }
                    if (matchedMovie != null) {
                        onVideoSelected(matchedMovie)
                    } else if (onPlayMovie != null) {
                        onPlayMovie(entry.id)
                    } else {
                        onVideoSelected(
                            VideoItem(
                                id = entry.id,
                                displayTitle = entry.title,
                                durationFormatted = "",
                                fileSizeFormatted = "",
                                genreFormatted = entry.genre.ifBlank { "Movie" },
                                resolutionBadge = "HD",
                                streamUri = DriveMediaSourceFactory.buildDriveStreamUri(entry.id).toString(),
                                thumbnailUrl = entry.posterUrl.ifBlank { entry.backdropUrl }
                            )
                        )
                    }
                },
                onSeriesSelected = { entry ->
                    val matchedSeries = uiState.tvSeriesList.find { it.id == entry.id }
                    if (matchedSeries != null) {
                        onSeriesSelected(matchedSeries)
                    } else if (onNavigateToSeriesDetail != null) {
                        onNavigateToSeriesDetail(entry.id, null)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = paddingValues.calculateBottomPadding())
            )
        }
    }
    }



    // Google Account Profile Dialog Overlay
    GoogleAccountDialog(
        isOpen = showAccountDialog,
        onDismissRequest = { showAccountDialog = false },
        userDisplayName = currentUser?.displayName ?: uiState.userName ?: "User",
        userEmail = currentUser?.email ?: uiState.userEmail ?: "",
        userPhotoUrl = currentUser?.photoUrl?.toString() ?: uiState.userAvatarUrl,
        savedProfiles = savedProfiles,
        onSwitchProfile = { email ->
            viewModel.switchProfile(email)
        },
        onAddAccountClick = {
            if (activity != null) {
                com.clotube.app.auth.GoogleAuthManager.launchGoogleSignIn(activity, signInLauncher)
            } else {
                com.clotube.app.auth.GoogleAuthManager.launchGoogleSignIn(context, signInLauncher)
            }
        },
        onRefreshClick = {
            viewModel.refreshCatalog(forceSync = true)
        },
        onSettingsClick = {
            onNavigateToSettings()
        },
        onSignOutClick = {
            viewModel.signOut()
        }
    )
    }
}

/**
 * Onboarding Dialog prompting user to create dedicated 'Movies' and 'Series' folders on Drive.
 */
@Composable
private fun DriveFolderOnboardingDialog(
    isCreating: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CreateNewFolder,
                    contentDescription = null,
                    tint = SecondaryAccent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Set up your Clotube Library",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextHighEmphasis
                )
            }
        },
        text = {
            Column {
                Text(
                    text = "Would you like Clotube to create dedicated 'Movies' and 'Series' folders in your Google Drive?",
                    fontSize = 14.sp,
                    color = TextHighEmphasis
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "• Movies folder: Store uncompressed 4K feature films.\n• Series folder: Store TV shows with Season 1, Season 2 subfolders.\n\nThis keeps your library neatly organized and enables high-bitrate streaming.",
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 17.sp
                )
                if (isCreating) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = SecondaryAccent,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Creating Drive folders...",
                            fontSize = 12.sp,
                            color = SecondaryAccent
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isCreating,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                shape = PillShape
            ) {
                Text("Set Up Folders", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isCreating
            ) {
                Text("Skip / Use Catalog", color = TextMuted)
            }
        }
    )
}

/**
 * Compact circular glass download button matching WatchlistBookmarkButton and EpisodeItemRow.
 */
@Composable
fun MovieDownloadButton(
    status: com.clotube.app.data.DownloadStatus?,
    progressFraction: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val interactionSource = remember { MutableInteractionSource() }

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
                view.triggerClickHaptic()
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            com.clotube.app.data.DownloadStatus.COMPLETED -> {
                Icon(
                    imageVector = Icons.Outlined.FileDownloadDone,
                    contentDescription = "Saved Offline",
                    tint = AccentBlue,
                    modifier = Modifier.size(size * 0.58f)
                )
            }
            com.clotube.app.data.DownloadStatus.DOWNLOADING -> {
                CircularProgressIndicator(
                    progress = { progressFraction.coerceIn(0.02f, 1f) },
                    modifier = Modifier.size(size * 0.62f),
                    color = AccentBlue,
                    strokeWidth = 2.dp
                )
            }
            com.clotube.app.data.DownloadStatus.QUEUED -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(size * 0.62f),
                    color = Color(0xFF94A3B8),
                    strokeWidth = 2.dp
                )
            }
            else -> {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = "Save Offline",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(size * 0.58f)
                )
            }
        }
    }
}

/**
 * Builds an anti-poison Coil [ImageRequest] using stable canonical cache keys.
 * Official/TMDB/Fanart artwork writes to `"${prefix}_${mediaId}"`.
 * If a Drive video-frame URL is supplied for movies, it isolates under `"${drivePrefix}_${mediaId}"`
 * while still reading `"${prefix}_${mediaId}"` as its in-place placeholder bitmap so upgrades
 * swap cleanly without cache poisoning or crossfade flicker.
 */
private fun buildAntiPoisonImageRequest(
    context: android.content.Context,
    url: String,
    mediaId: String,
    canonicalPrefix: String,
    drivePrefix: String = "drive_frame",
    widthPx: Int? = null,
    heightPx: Int? = null
): ImageRequest {
    val cleanId = mediaId.trim()
    val cleanUrl = url.trim()
    val isDriveFrame = cleanUrl.isNotBlank() && OnlineMetadataResolver.isDriveVideoFrameUrl(cleanUrl)
    val builder = ImageRequest.Builder(context)
        .data(cleanUrl)
        .crossfade(false)
        .bitmapConfig(Bitmap.Config.HARDWARE)

    if (widthPx != null && heightPx != null) {
        builder.size(width = widthPx, height = heightPx)
    }

    if (cleanId.isNotBlank()) {
        val officialKey = "${canonicalPrefix}_${cleanId}"
        val activeKey = if (isDriveFrame) "${drivePrefix}_${cleanId}" else officialKey
        builder
            .memoryCacheKey(activeKey)
            .diskCacheKey(activeKey)
            .placeholderMemoryCacheKey(officialKey)
    }

    return builder.build()
}

/**
 * Top 16:9 OTT Featured Fallback Banner shown only when Continue Watching is confirmed empty.
 */
@Composable
private fun FeaturedHeroBanner(
    movie: Movie,
    onWatchMovie: () -> Unit,
    onInspect: () -> Unit,
    downloadStatus: com.clotube.app.data.DownloadStatus? = null,
    downloadProgress: Float = 0f,
    onDownloadClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val backdropUrl = movie.thumbnailUrl?.trim().orEmpty()
    val title = movie.title.ifBlank { "Clotube Featured" }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = CardContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderOutline)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            if (backdropUrl.isNotBlank()) {
                AsyncImage(
                    model = buildAntiPoisonImageRequest(
                        context = context,
                        url = backdropUrl,
                        mediaId = movie.id,
                        canonicalPrefix = "movie_poster",
                        drivePrefix = "movie_drive"
                    ),
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // Cinematic Gradient Overlay (Dark bottom to transparent top)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.45f),
                                Color.Black.copy(alpha = 0.95f)
                            )
                        )
                    )
            )

            // Content Overlay
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onWatchMovie,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        shape = PillShape,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Watch Now", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }

                    OutlinedButton(
                        onClick = onInspect,
                        shape = PillShape,
                        border = BorderStroke(1.dp, SurfaceBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Details", fontSize = 12.sp, color = Color.White)
                    }

                    if (onDownloadClick != null) {
                        MovieDownloadButton(
                            status = downloadStatus,
                            progressFraction = downloadProgress,
                            onClick = onDownloadClick,
                            size = 38.dp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Section Header for Horizontal Carousel Rows.
 */
@Composable
private fun SectionHeader(
    title: String,
    subtitle: String? = null,
    onSeeAllClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }
        if (onSeeAllClick != null) {
            Text(
                text = "See All",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = AccentBlue,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onSeeAllClick() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * 16:9 Cinematic Movie Card with internal scrim gradient overlay.
 * Title and metadata are rendered directly inside the thumbnail canvas on a bottom scrim.
 */
@Composable
fun MovieCard(
    movie: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(220.dp),
    downloadStatus: com.clotube.app.data.DownloadStatus? = null,
    downloadProgress: Float = 0f,
    onDownloadClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val revision = WatchlistManager.watchlistRevision
    val currentStatus = WatchlistManager.getStatus(movie.id, movie.displayTitle)
    var showBottomSheet by remember { mutableStateOf(false) }

    var fallbackPoster by remember(movie.id) { mutableStateOf("") }
    var imageLoadFailed by remember(movie.thumbnailUrl) { mutableStateOf(false) }

    val rawMovieThumb = movie.thumbnailUrl?.trim().orEmpty()
    val movieHasDriveFrame = rawMovieThumb.isNotBlank() && OnlineMetadataResolver.isDriveVideoFrameUrl(rawMovieThumb)

    val effectiveThumb = when {
        fallbackPoster.isNotBlank() -> fallbackPoster
        !imageLoadFailed && rawMovieThumb.isNotBlank() -> rawMovieThumb
        else -> ""
    }

    LaunchedEffect(movie.id, movie.displayTitle, imageLoadFailed, rawMovieThumb) {
        if ((rawMovieThumb.isBlank() || movieHasDriveFrame || imageLoadFailed) && fallbackPoster.isBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val searchResults = GlobalCatalogSearchEngine.searchOnlineCatalog(movie.displayTitle)
                    val best = searchResults.firstOrNull { it.posterUrl.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it.posterUrl) }?.posterUrl
                        ?: searchResults.firstOrNull { it.backdropUrl.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it.backdropUrl) }?.backdropUrl
                    if (!best.isNullOrBlank()) {
                        fallbackPoster = best
                    } else {
                        val resolved = OnlineMetadataResolver.resolveMovie(movie.displayTitle).posterUrl
                        if (!resolved.isNullOrBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(resolved)) {
                            fallbackPoster = resolved
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .appleTvPressable(interactionSource, pressedScale = 0.96f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDefault),
        border = BorderStroke(1.dp, SurfaceBorder)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (effectiveThumb.isNotBlank()) {
                // Layer 1 (Base): Coil AsyncImage with downsampling & HARDWARE bitmap
                AsyncImage(
                    model = buildAntiPoisonImageRequest(
                        context = LocalContext.current,
                        url = effectiveThumb,
                        mediaId = movie.id,
                        canonicalPrefix = "movie_poster",
                        drivePrefix = "movie_drive",
                        widthPx = 440,
                        heightPx = 248
                    ),
                    contentDescription = movie.displayTitle,
                    contentScale = ContentScale.Crop,
                    onError = { imageLoadFailed = true },
                    onSuccess = { imageLoadFailed = false },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
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
                    Icon(
                        imageVector = Icons.Rounded.Movie,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Top-Start Status Pill
            if (currentStatus != WatchlistStatus.UNWATCHED) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    WatchlistStatusPill(status = currentStatus)
                }
            }

            // Top-End: Duration tag and Bookmark Button
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (movie.durationFormatted.isNotBlank() && movie.durationFormatted != "00:00") {
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                            .border(0.5.dp, SurfaceBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = movie.durationFormatted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }

                if (onDownloadClick != null) {
                    MovieDownloadButton(
                        status = downloadStatus,
                        progressFraction = downloadProgress,
                        onClick = onDownloadClick,
                        size = 28.dp
                    )
                }

                WatchlistBookmarkButton(
                    status = currentStatus,
                    onClick = { showBottomSheet = true },
                    size = 28.dp
                )
            }

            // Layer 3 (Bottom Scrim Gradient): covers lower 50% of card height
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.50f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.4f),
                                Color.Black.copy(alpha = 0.92f)
                            )
                        )
                    )
            )

            // Layer 4 (Bottom Metadata Content)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                // 1. Title Text
                Text(
                    text = movie.displayTitle,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // 2. Secondary Line (Display runtime / genre, no "Movie • Cinema")
                val durationText = movie.durationFormatted.takeIf { it.isNotBlank() && it != "00:00" }
                val genreClean = movie.genreFormatted.replace("Movie • Cinema", "").trim().trim('•').trim()
                val metaText = when {
                    durationText != null && genreClean.isNotBlank() -> "$durationText • $genreClean"
                    durationText != null -> durationText
                    genreClean.isNotBlank() -> genreClean
                    else -> "Movie"
                }

                Text(
                    text = metaText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Layer 5 (Watch Progress Bar flush against bottom edge):
            if (movie.watchProgressFraction > 0.02f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color(0x26FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(movie.watchProgressFraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(AccentBlue)
                    )
                }
            }
        }
    }

    if (showBottomSheet) {
        WatchlistStatusBottomSheet(
            title = movie.displayTitle,
            currentStatus = currentStatus,
            onSelectStatus = { newStatus ->
                WatchlistManager.setStatus(
                    context = context,
                    id = movie.id,
                    title = movie.displayTitle,
                    mediaType = WatchlistMediaType.MOVIE,
                    status = newStatus,
                    posterUrl = movie.thumbnailUrl,
                    genre = movie.genreFormatted
                )
            },
            onDismiss = { showBottomSheet = false }
        )
    }
}

@Composable
private fun MovieCarouselCard(
    video: VideoItem,
    onClick: () -> Unit
) = MovieCard(movie = video, onClick = onClick)

private fun extractYearFromTitle(title: String): String? {
    val match = Regex("""\b(19\d\d|20\d\d)\b""").find(title)
    return match?.value
}

/**
 * 2:3 Vertical Portrait Poster Movie Card for the 3-column Movies Catalog Grid.
 * Displays a full-bleed poster canvas, 30% bottom subtle shadow scrim, optional 4K HDR tag,
 * and clean title/year typography directly below the poster card.
 */
@Composable
fun MoviePosterCard(
    movie: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    downloadStatus: com.clotube.app.data.DownloadStatus? = null,
    downloadProgress: Float = 0f,
    onDownloadClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val revision = WatchlistManager.watchlistRevision
    val currentStatus = WatchlistManager.getStatus(movie.id, movie.displayTitle)
    var showBottomSheet by remember { mutableStateOf(false) }

    val is4k = movie.resolutionBadge.contains("4K", ignoreCase = true) ||
               movie.resolutionBadge.contains("2160", ignoreCase = true) ||
               movie.width >= 3840 ||
               (movie.hdrBadge != null && movie.resolutionBadge.contains("UHD", ignoreCase = true))

    val yearText = extractYearFromTitle(movie.displayTitle)
        ?: movie.durationFormatted.takeIf { it.isNotBlank() && it != "00:00" }
        ?: ""

    val interactionSource = remember { MutableInteractionSource() }
    var fallbackPoster by remember(movie.id) { mutableStateOf("") }
    var imageLoadFailed by remember(movie.thumbnailUrl) { mutableStateOf(false) }

    val rawMovieThumb = movie.thumbnailUrl?.trim().orEmpty()
    val movieHasDriveFrame = rawMovieThumb.isNotBlank() && OnlineMetadataResolver.isDriveVideoFrameUrl(rawMovieThumb)

    val effectiveThumb = when {
        fallbackPoster.isNotBlank() -> fallbackPoster
        !imageLoadFailed && rawMovieThumb.isNotBlank() -> rawMovieThumb
        else -> ""
    }

    LaunchedEffect(movie.id, movie.displayTitle, imageLoadFailed, rawMovieThumb) {
        if ((rawMovieThumb.isBlank() || movieHasDriveFrame || imageLoadFailed) && fallbackPoster.isBlank()) {
            withContext(Dispatchers.IO) {
                try {
                    val searchResults = GlobalCatalogSearchEngine.searchOnlineCatalog(movie.displayTitle)
                    val best = searchResults.firstOrNull { it.posterUrl.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it.posterUrl) }?.posterUrl
                        ?: searchResults.firstOrNull { it.backdropUrl.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it.backdropUrl) }?.backdropUrl
                    if (!best.isNullOrBlank()) {
                        fallbackPoster = best
                    } else {
                        val resolved = OnlineMetadataResolver.resolveMovie(movie.displayTitle).posterUrl
                        if (!resolved.isNullOrBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(resolved)) {
                            fallbackPoster = resolved
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .appleTvPressable(interactionSource, pressedScale = 0.96f)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false),
                onClick = onClick
            )
    ) {
        // 2:3 Portrait Poster Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDefault),
            border = BorderStroke(0.5.dp, SurfaceBorder)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (effectiveThumb.isNotBlank()) {
                    // Full bleed AsyncImage with ContentScale.Crop
                    AsyncImage(
                        model = buildAntiPoisonImageRequest(
                            context = LocalContext.current,
                            url = effectiveThumb,
                            mediaId = movie.id,
                            canonicalPrefix = "movie_poster",
                            drivePrefix = "movie_drive",
                            widthPx = 300,
                            heightPx = 450
                        ),
                        contentDescription = movie.displayTitle,
                        contentScale = ContentScale.Crop,
                        onError = { imageLoadFailed = true },
                        onSuccess = { imageLoadFailed = false },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
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
                            modifier = Modifier.padding(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Movie,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = movie.displayTitle,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextSecondary,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Subtle vertical shadow on bottom 30% of image
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.30f)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.60f)
                                )
                            )
                        )
                )

                // Top-Start Status Pill
                if (currentStatus != WatchlistStatus.UNWATCHED) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    ) {
                        WatchlistStatusPill(status = currentStatus)
                    }
                }

                // Top-End: 4K Tag & Bookmark Button
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (is4k) {
                        Box(
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                                .border(0.5.dp, SurfaceBorder, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "4K HDR",
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (onDownloadClick != null) {
                        MovieDownloadButton(
                            status = downloadStatus,
                            progressFraction = downloadProgress,
                            onClick = onDownloadClick,
                            size = 26.dp
                        )
                    }

                    WatchlistBookmarkButton(
                        status = currentStatus,
                        onClick = { showBottomSheet = true },
                        size = 26.dp
                    )
                }

                // Bottom Watch Progress Bar flush against bottom edge
                if (movie.watchProgressFraction > 0.02f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .align(Alignment.BottomCenter)
                            .background(Color(0x26FFFFFF))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(movie.watchProgressFraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(AccentBlue)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Title: 12.sp, Medium, Color.White, maxLines = 1, ellipsis
        Text(
            text = movie.displayTitle,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Year: 10.sp, Normal, Color(#64748B)
        if (yearText.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = yearText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF64748B),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (showBottomSheet) {
        WatchlistStatusBottomSheet(
            title = movie.displayTitle,
            currentStatus = currentStatus,
            onSelectStatus = { newStatus ->
                WatchlistManager.setStatus(
                    context = context,
                    id = movie.id,
                    title = movie.displayTitle,
                    mediaType = WatchlistMediaType.MOVIE,
                    status = newStatus,
                    posterUrl = movie.thumbnailUrl,
                    genre = movie.genreFormatted
                )
            },
            onDismiss = { showBottomSheet = false }
        )
    }
}

/**
 * 16:9 Cinematic TV Series Card with internal scrim gradient overlay.
 * Title and metadata are rendered directly inside the thumbnail canvas on a bottom scrim.
 */
@Composable
fun SeriesCard(
    series: TvSeries,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(220.dp)
) {
    val context = LocalContext.current
    val revision = WatchlistManager.watchlistRevision
    val currentStatus = WatchlistManager.getStatus(series.id, series.title)
    var showBottomSheet by remember { mutableStateOf(false) }

    var fallbackPoster by remember(series.id) { mutableStateOf("") }
    var imageLoadFailed by remember(series.id) { mutableStateOf(false) }

    val officialSeriesArt = remember(series.id, series.posterUrl, series.backdropUrl, series.thumbnailUrl) {
        listOfNotNull(series.posterUrl, series.backdropUrl, series.thumbnailUrl)
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
            .orEmpty()
    }

    val effectiveThumb = when {
        fallbackPoster.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(fallbackPoster) -> fallbackPoster
        !imageLoadFailed && officialSeriesArt.isNotBlank() -> officialSeriesArt
        else -> ""
    }

    LaunchedEffect(series.id, series.title, officialSeriesArt, imageLoadFailed) {
        if ((officialSeriesArt.isBlank() || imageLoadFailed) && (fallbackPoster.isBlank() || OnlineMetadataResolver.isDriveVideoFrameUrl(fallbackPoster))) {
            withContext(Dispatchers.IO) {
                try {
                    val normTitle = WatchlistManager.normalizeTitle(series.title).lowercase()
                    val cachedRecord = MetadataDiskCache.get("series_$normTitle")
                        ?: MetadataDiskCache.get("series:${series.title.trim().lowercase()}")
                    val cachedArt = cachedRecord?.posterUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                        ?: cachedRecord?.backdropUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                    if (!cachedArt.isNullOrBlank()) {
                        fallbackPoster = cachedArt
                        return@withContext
                    }

                    val searchResults = GlobalCatalogSearchEngine.searchOnlineCatalog(series.title)
                    val best = searchResults.firstOrNull { it.posterUrl.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it.posterUrl) }?.posterUrl
                        ?: searchResults.firstOrNull { it.backdropUrl.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it.backdropUrl) }?.backdropUrl
                    if (!best.isNullOrBlank()) {
                        fallbackPoster = best
                    } else {
                        val resolved = OnlineMetadataResolver.resolveSeries(series.title).posterUrl
                        if (!resolved.isNullOrBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(resolved)) {
                            fallbackPoster = resolved
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .appleTvPressable(interactionSource, pressedScale = 0.96f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            ),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDefault),
        border = BorderStroke(1.dp, SurfaceBorder)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (effectiveThumb.isNotBlank()) {
                val thumbUrl = effectiveThumb
                AsyncImage(
                    model = buildAntiPoisonImageRequest(
                        context = LocalContext.current,
                        url = thumbUrl,
                        mediaId = series.id,
                        canonicalPrefix = "series_poster",
                        widthPx = 440,
                        heightPx = 248
                    ),
                    contentDescription = series.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = { imageLoadFailed = true },
                    onSuccess = { imageLoadFailed = false }
                )
            } else {
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
                    Icon(
                        imageVector = Icons.Rounded.Tv,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            // Season count tag anchored to TopEnd (renders ONLY when series.seasons.isNotEmpty())
            val seasonDisplay = when {
                series.seasons.isNotEmpty() -> if (series.seasons.size == 1) "1 Season" else "${series.seasons.size} Seasons"
                else -> null
            }

            // Top-Start Status Pill
            if (currentStatus != WatchlistStatus.UNWATCHED) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    WatchlistStatusPill(status = currentStatus)
                }
            }

            // Top-End: Season count tag and Bookmark Button
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (seasonDisplay != null) {
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                            .border(0.5.dp, SurfaceBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = seasonDisplay,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }

                WatchlistBookmarkButton(
                    status = currentStatus,
                    onClick = { showBottomSheet = true },
                    size = 28.dp
                )
            }

            // Layer 3 (Bottom Scrim Gradient): covers lower 50% of card height
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.50f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.4f),
                                Color.Black.copy(alpha = 0.92f)
                            )
                        )
                    )
            )

            // Layer 4 (Bottom Metadata Content)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = series.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Secondary Line (Total Episodes & Genre)
                val totalEps = series.totalEpisodes
                val epText = if (totalEps > 0) (if (totalEps == 1) "1 Episode" else "$totalEps Episodes") else ""
                val genreClean = series.genreFormatted
                    .replace("OTT SERIES", "")
                    .replace("Tap to explore", "")
                    .trim()
                    .trim('•')
                    .trim()
                    .let { if (it.isBlank() || it.equals("TV Series", ignoreCase = true)) "Series" else it }

                val metaParts = if (totalEps > 0) {
                    listOfNotNull(
                        epText,
                        genreClean.takeIf { it != "Series" }
                    )
                } else {
                    listOf(genreClean)
                }

                Text(
                    text = metaParts.joinToString(" • "),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Layer 5 (Watch Progress Bar flush against bottom edge):
            if (series.watchProgressFraction > 0.02f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color(0x26FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(series.watchProgressFraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(AccentBlue)
                    )
                }
            }
        }
    }

    if (showBottomSheet) {
        val thumbUrl = series.thumbnailUrl.ifBlank { series.backdropUrl ?: series.posterUrl ?: "" }
        WatchlistStatusBottomSheet(
            title = series.title,
            currentStatus = currentStatus,
            onSelectStatus = { newStatus ->
                WatchlistManager.setStatus(
                    context = context,
                    id = series.id,
                    title = series.title,
                    mediaType = WatchlistMediaType.SERIES,
                    status = newStatus,
                    posterUrl = series.posterUrl ?: thumbUrl,
                    backdropUrl = series.backdropUrl ?: "",
                    genre = series.genre
                )
            },
            onDismiss = { showBottomSheet = false }
        )
    }
}

@Composable
private fun SeriesCarouselCard(
    series: TvSeries,
    onClick: () -> Unit
) = SeriesCard(series = series, onClick = onClick)

@Composable
fun MovieOverlayCard(
    movie: Movie,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .appleTvPressable(interactionSource, pressedScale = 0.96f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true)
            ) { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = SurfaceDefault,
        border = BorderStroke(1.dp, SurfaceBorder)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Artwork
            if (!movie.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = buildAntiPoisonImageRequest(
                        context = LocalContext.current,
                        url = movie.thumbnailUrl,
                        mediaId = movie.id,
                        canonicalPrefix = "movie_poster",
                        drivePrefix = "movie_drive",
                        widthPx = 440,
                        heightPx = 248
                    ),
                    contentDescription = movie.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top Badges (1080p FHD badge removed)
            if (movie.formattedDuration.isNotBlank() && movie.formattedDuration != "00:00") {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .border(0.5.dp, SurfaceBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = movie.formattedDuration,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Bottom Gradient Scrim with Internal Title
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xB3000000),
                                Color(0xF2070A0F)
                            )
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = movie.title,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    val durationText = movie.formattedDuration.takeIf { it.isNotBlank() && it != "00:00" }
                    val genreClean = movie.genre.replace("Movie • Cinema", "").trim().trim('•').trim()
                    val metaText = when {
                        durationText != null && genreClean.isNotBlank() -> "$durationText • $genreClean"
                        durationText != null -> durationText
                        genreClean.isNotBlank() -> genreClean
                        else -> "Movie"
                    }
                    Text(
                        text = metaText,
                        color = TextSecondary,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun MovieOverlayCard(
    movie: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) = MovieCard(movie = movie, onClick = onClick, modifier = modifier)

@Composable
fun SeriesOverlayCard(
    series: Series,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .appleTvPressable(interactionSource, pressedScale = 0.96f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true)
            ) { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = SurfaceDefault,
        border = BorderStroke(1.dp, SurfaceBorder)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Artwork (reject Drive video frame URLs to prevent cache poisoning)
            val poster = listOfNotNull(series.posterUrl, series.thumbnailUrl)
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                .orEmpty()
            if (poster.isNotBlank()) {
                AsyncImage(
                    model = buildAntiPoisonImageRequest(
                        context = LocalContext.current,
                        url = poster,
                        mediaId = series.id,
                        canonicalPrefix = "series_poster",
                        widthPx = 440,
                        heightPx = 248
                    ),
                    contentDescription = series.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top Badges ("OTT SERIES" and "Tap to explore" removed)
            val seasonDisplay = when {
                series.seasons.isNotEmpty() -> if (series.seasons.size == 1) "1 Season" else "${series.seasons.size} Seasons"
                else -> null
            }
            if (seasonDisplay != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .border(0.5.dp, SurfaceBorder, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = seasonDisplay,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Bottom Gradient Scrim with Internal Title
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xB3000000),
                                Color(0xF2070A0F)
                            )
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = series.title,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    val totalEps = series.totalEpisodes
                    val epText = if (totalEps > 0) (if (totalEps == 1) "1 Episode" else "$totalEps Episodes") else ""
                    val genreClean = series.genre
                        .replace("OTT SERIES", "")
                        .replace("Tap to explore", "")
                        .trim()
                        .trim('•')
                        .trim()
                        .let { if (it.isBlank() || it.equals("TV Series", ignoreCase = true)) "Series" else it }
                    val metaParts = if (totalEps > 0) {
                        listOfNotNull(
                            epText,
                            genreClean.takeIf { it != "Series" }
                        )
                    } else {
                        listOf(genreClean)
                    }

                    Text(
                        text = metaParts.joinToString(" • "),
                        color = TextSecondary,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 2:3 Vertical Portrait Poster Movie Card for Domain Movie model.
 */
@Composable
fun MoviePosterCard(
    movie: Movie,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val is4k = movie.resolution.contains("4K", ignoreCase = true) ||
               movie.resolution.contains("2160", ignoreCase = true)

    val yearText = extractYearFromTitle(movie.title)
        ?: movie.formattedDuration.takeIf { it.isNotBlank() && it != "00:00" }
        ?: ""

    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .appleTvPressable(interactionSource, pressedScale = 0.96f)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false),
                onClick = onClick
            )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDefault),
            border = BorderStroke(0.5.dp, SurfaceBorder)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!movie.thumbnailUrl.isNullOrBlank()) {
                    val effectiveThumb = movie.thumbnailUrl
                    AsyncImage(
                        model = buildAntiPoisonImageRequest(
                            context = LocalContext.current,
                            url = effectiveThumb,
                            mediaId = movie.id,
                            canonicalPrefix = "movie_poster",
                            drivePrefix = "movie_drive",
                            widthPx = 300,
                            heightPx = 450
                        ),
                        contentDescription = movie.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.30f)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.60f)
                                )
                            )
                        )
                )

                if (is4k) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                            .border(0.5.dp, SurfaceBorder, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "4K HDR",
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = movie.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (yearText.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = yearText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF64748B),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Full-width Cinematic Horizontal Series Card for TV Series Catalog.
 * Height: 120.dp
 * Left 40%: Series Backdrop with right-edge blending gradient
 * Right 60%: Title in 16.sp, seasons count & total episodes, and genre in #94A3B8.
 */
@Composable
fun SeriesRowCard(
    series: TvSeries,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val revision = WatchlistManager.watchlistRevision
    val currentStatus = WatchlistManager.getStatus(series.id, series.title)
    var showBottomSheet by remember { mutableStateOf(false) }

    var fallbackPoster by remember(series.id) { mutableStateOf("") }
    var imageLoadFailed by remember(series.id) { mutableStateOf(false) }

    val officialSeriesArt = remember(series.id, series.posterUrl, series.backdropUrl, series.thumbnailUrl) {
        listOfNotNull(series.posterUrl, series.backdropUrl, series.thumbnailUrl)
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
            .orEmpty()
    }

    val effectiveThumb = when {
        fallbackPoster.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(fallbackPoster) -> fallbackPoster
        !imageLoadFailed && officialSeriesArt.isNotBlank() -> officialSeriesArt
        else -> ""
    }

    LaunchedEffect(series.id, series.title, officialSeriesArt, imageLoadFailed) {
        if ((officialSeriesArt.isBlank() || imageLoadFailed) && (fallbackPoster.isBlank() || OnlineMetadataResolver.isDriveVideoFrameUrl(fallbackPoster))) {
            withContext(Dispatchers.IO) {
                try {
                    val normTitle = WatchlistManager.normalizeTitle(series.title).lowercase()
                    val cachedRecord = MetadataDiskCache.get("series_$normTitle")
                        ?: MetadataDiskCache.get("series:${series.title.trim().lowercase()}")
                    val cachedArt = cachedRecord?.posterUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                        ?: cachedRecord?.backdropUrl?.takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                    if (!cachedArt.isNullOrBlank()) {
                        fallbackPoster = cachedArt
                        return@withContext
                    }

                    val searchResults = GlobalCatalogSearchEngine.searchOnlineCatalog(series.title)
                    val best = searchResults.firstOrNull { it.posterUrl.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it.posterUrl) }?.posterUrl
                        ?: searchResults.firstOrNull { it.backdropUrl.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it.backdropUrl) }?.backdropUrl
                    if (!best.isNullOrBlank()) {
                        fallbackPoster = best
                    } else {
                        val resolved = OnlineMetadataResolver.resolveSeries(series.title).posterUrl
                        if (!resolved.isNullOrBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(resolved)) {
                            fallbackPoster = resolved
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .appleTvPressable(interactionSource, pressedScale = 0.96f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDefault),
        border = BorderStroke(0.5.dp, SurfaceBorder)
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left 40%: Series Backdrop
            Box(
                modifier = Modifier
                    .weight(0.40f)
                    .fillMaxHeight()
            ) {
                if (effectiveThumb.isNotBlank()) {
                    AsyncImage(
                        model = buildAntiPoisonImageRequest(
                            context = LocalContext.current,
                            url = effectiveThumb,
                            mediaId = series.id,
                            canonicalPrefix = "series_poster",
                            widthPx = 440,
                            heightPx = 248
                        ),
                        contentDescription = series.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onError = { imageLoadFailed = true },
                        onSuccess = { imageLoadFailed = false }
                    )
                } else {
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
                        Icon(
                            imageVector = Icons.Rounded.Tv,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Top-Start Status Pill
                if (currentStatus != WatchlistStatus.UNWATCHED) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    ) {
                        WatchlistStatusPill(status = currentStatus)
                    }
                }

                // Right edge gradient to blend seamlessly into card surface
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(28.dp)
                        .align(Alignment.CenterEnd)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, SurfaceDefault)
                            )
                        )
                )

                if (series.watchProgressFraction > 0.02f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .align(Alignment.BottomCenter)
                            .background(Color(0x26FFFFFF))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(series.watchProgressFraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(AccentBlue)
                        )
                    }
                }
            }

            // Right 60%: Series Details
            Column(
                modifier = Modifier
                    .weight(0.60f)
                    .fillMaxHeight()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Center
            ) {
                // Title and Quick Bookmark Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = series.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    WatchlistBookmarkButton(
                        status = currentStatus,
                        onClick = { showBottomSheet = true },
                        size = 26.dp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Seasons count & total episodes count in #94A3B8
                val totalEps = series.totalEpisodes
                val epText = if (totalEps > 0) (if (totalEps == 1) "1 Episode" else "$totalEps Episodes") else ""
                val seasonDisplay = when {
                    series.seasons.isNotEmpty() -> if (series.seasons.size == 1) "1 Season" else "${series.seasons.size} Seasons"
                    else -> null
                }

                val genreClean = series.genreFormatted
                    .replace("OTT SERIES", "")
                    .replace("Tap to explore", "")
                    .trim()
                    .trim('•')
                    .trim()
                    .let { if (it.isBlank() || it.equals("TV Series", ignoreCase = true)) "Series" else it }

                if (seasonDisplay != null) {
                    val countParts = listOfNotNull(seasonDisplay, epText.ifBlank { null })
                    Text(
                        text = countParts.joinToString(" • "),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = TextSecondary, // #94A3B8
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (genreClean != "Series") {
                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = genreClean,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = TextSecondary, // #94A3B8
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        text = genreClean,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = TextSecondary, // #94A3B8
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    if (showBottomSheet) {
        val thumbUrl = series.thumbnailUrl.ifBlank { series.backdropUrl ?: series.posterUrl ?: "" }
        WatchlistStatusBottomSheet(
            title = series.title,
            currentStatus = currentStatus,
            onSelectStatus = { newStatus ->
                WatchlistManager.setStatus(
                    context = context,
                    id = series.id,
                    title = series.title,
                    mediaType = WatchlistMediaType.SERIES,
                    status = newStatus,
                    posterUrl = series.posterUrl ?: thumbUrl,
                    backdropUrl = series.backdropUrl ?: "",
                    genre = series.genre
                )
            },
            onDismiss = { showBottomSheet = false }
        )
    }
}

@Composable
private fun CatalogEmptyView(
    title: String,
    subtitle: String,
    isSearchActive: Boolean,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onChooseMovies: () -> Unit,
    onChooseSeries: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = VideoCardShape,
        color = SurfaceElevated,
        border = BorderStroke(1.dp, BorderOutline)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = if (isSearchActive) Icons.Default.Search else Icons.Default.CreateNewFolder,
                contentDescription = null,
                tint = SecondaryAccent,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextHighEmphasis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextMuted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 17.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (isSearchActive) {
                Button(
                    onClick = onClearSearch,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                    shape = PillShape
                ) {
                    Text("Clear Search")
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onRefresh,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                        shape = PillShape
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Refresh Folders")
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onChooseMovies,
                            shape = PillShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue),
                            border = BorderStroke(1.dp, AccentBlue)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Choose Movies")
                        }
                        OutlinedButton(
                            onClick = onChooseSeries,
                            shape = PillShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue),
                            border = BorderStroke(1.dp, AccentBlue)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Choose Series")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CatalogEmptyView(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = Color(0xFF6B7A90),
            fontSize = 13.sp
        )
    }
}

@Composable
fun ContinueWatchingCard(
    data: ContinueWatchingDisplayData,
    onResumeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .appleTvPressable(interactionSource, pressedScale = 0.96f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true)
            ) { onResumeClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F141E)),
        border = BorderStroke(1.dp, SurfaceBorder)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val safeArtworkUrl = remember(data.mediaId, data.displayThumbnailUrl) {
                data.displayThumbnailUrl.trim()
                    .takeIf { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                    .orEmpty()
            }
            if (safeArtworkUrl.isNotBlank()) {
                // 1. High-Resolution Official Episode / Movie Artwork with canonical anti-poison cache key
                AsyncImage(
                    model = buildAntiPoisonImageRequest(
                        context = LocalContext.current,
                        url = safeArtworkUrl,
                        mediaId = data.mediaId,
                        canonicalPrefix = "cw_hero"
                    ),
                    contentDescription = data.episodeTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFF1A2233), Color(0xFF0B0F17))
                            )
                        )
                )
            }

            // 2. High-Contrast Gradient Scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color(0x33000000),
                            0.45f to Color(0x66000000),
                            0.80f to Color(0xF2070A0F),
                            1.0f to Color(0xFA070A0F)
                        )
                    )
            )

            // 3. Content Overlay
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Row: Cyan-bordered "CONTINUE WATCHING" pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xCC0E1A2B),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "CONTINUE WATCHING",
                            color = Color(0xFF00E5FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Bottom Row: Title, Episode Details, Watched Stats & Resume Action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        val header = data.displayHeader.ifBlank { data.seriesTitle }
                        if (header.isNotBlank()) {
                            Text(
                                text = header,
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                        }

                        val displaySubtitle = when {
                            data.displayTitle.isNotBlank() -> data.displayTitle
                            data.seasonNumber > 0 -> {
                                val sFormatted = data.seasonNumber.toString().padStart(2, '0')
                                val eFormatted = data.episodeNumber.toString().padStart(2, '0')
                                val epCode = "S${sFormatted}:E${eFormatted}"
                                if (data.episodeTitle.isNotBlank()) "$epCode • ${data.episodeTitle}" else epCode
                            }
                            else -> data.episodeTitle
                        }

                        Text(
                            text = displaySubtitle,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(3.dp))

                        val remainingMs = (data.durationMs - data.currentPositionMs).coerceAtLeast(0L)
                        val remainingMinutes = remainingMs / 60000L
                        val remainingText = if (remainingMinutes > 0) {
                            "${remainingMinutes}m remaining"
                        } else {
                            val posFormatted = formatMsToTime(data.currentPositionMs)
                            val durFormatted = formatMsToTime(data.durationMs)
                            "${(data.progressFraction * 100).toInt()}% watched • $posFormatted / $durFormatted"
                        }

                        Text(
                            text = remainingText,
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }

                    // Frosted-glass play circle with white play icon
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .clickable { onResumeClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Resume",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // 4. Sleek 2.5.dp progress track using AccentBlue over track
            if (data.progressFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color(0x26FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(data.progressFraction.coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .background(AccentBlue)
                    )
                }
            }
        }
    }
}

@Composable
fun ContinueWatchingCard(
    headerTitle: String,
    subTitle: String,
    thumbnailUrl: String,
    progressFraction: Float,
    currentPositionMs: Long,
    durationMs: Long,
    isSeries: Boolean,
    onResumeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayData = ContinueWatchingDisplayData(
        mediaId = "",
        seriesId = null,
        isSeries = isSeries,
        displayHeader = headerTitle,
        displayTitle = subTitle,
        displayThumbnailUrl = thumbnailUrl,
        progressFraction = progressFraction,
        currentPositionMs = currentPositionMs,
        durationMs = durationMs
    )
    ContinueWatchingCard(
        data = displayData,
        onResumeClick = onResumeClick,
        modifier = modifier
    )
}

@Composable
fun ContinueWatchingCard(
    episode: Episode,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdropUrl: String = ""
) {
    val seriesTitle = episode.seriesTitle.ifBlank { "Series" }
    val thumb = episode.thumbnailUrl.ifBlank { backdropUrl }
    val displayData = ContinueWatchingDisplayData(
        mediaId = episode.id,
        seriesId = "",
        isSeries = true,
        displayHeader = seriesTitle,
        displayTitle = episode.displayTitle,
        displayThumbnailUrl = thumb,
        progressFraction = episode.watchProgressFraction,
        currentPositionMs = (episode.watchProgressFraction * episode.durationMs).toLong(),
        durationMs = episode.durationMs,
        seriesTitle = seriesTitle,
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        episodeTitle = episode.displayTitle
    )
    ContinueWatchingCard(
        data = displayData,
        onResumeClick = onPlayClick,
        modifier = modifier
    )
}

private fun formatMsToTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
}

@Composable
fun HomeScreen(
    viewModel: VideoViewModel,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit,
    onContinueWatchingClick: (Episode) -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf(HomeCatalogFilter.ALL) }
    var currentNavTab by remember { mutableStateOf(HomeNavigationTab.HOME) }

    val movies by viewModel.moviesList.collectAsState()
    val series by viewModel.seriesList.collectAsState()
    val heroState by viewModel.heroSlotState.collectAsState()

    var selectedMovieLanguage by remember { mutableStateOf("All") }
    val displayMovies = remember(movies) {
        val seenIds = HashSet<String>()
        movies.filter { movie ->
            movie.id.isNotBlank() && seenIds.add(movie.id)
        }
    }
    val moviesByLanguage = remember(displayMovies) {
        displayMovies.groupBy { movie ->
            val section = MovieIndustryResolver.resolveMovieSection(movie)
            section.ifBlank { movie.language.ifBlank { "English" } }
        }
    }
    val orderedLanguages = remember(moviesByLanguage) {
        MovieIndustryResolver.sortIndustrySections(moviesByLanguage.keys)
    }

    LaunchedEffect(orderedLanguages) {
        if (selectedMovieLanguage != "All" && selectedMovieLanguage !in orderedLanguages) {
            selectedMovieLanguage = "All"
        }
    }

    val isHomeVisible = currentNavTab == HomeNavigationTab.HOME

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090E))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .graphicsLayer {
                    alpha = if (isHomeVisible) 1f else 0f
                }
                .then(if (!isHomeVisible) Modifier.zIndex(-1f) else Modifier.zIndex(0f))
        ) {
            // ========================================================
            // 1. TOP APP BAR (Branding on Left, Account Avatar on Right)
            // ========================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Clotube",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .border(1.dp, SurfaceBorder, CircleShape)
                        .background(SurfaceElevated)
                        .clickable { onProfileClick() },
                    contentAlignment = Alignment.Center
                ) {
                    val userPhotoUrl = viewModel.userProfilePhotoUrl.collectAsState().value
                    if (!userPhotoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = userPhotoUrl,
                            contentDescription = "Account",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = "C",
                            color = AccentBlue,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // ========================================================
            // 2. TOP FILTER CHIPS (All, Movies, TV Series)
            // ========================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HomeCatalogFilter.values().forEach { filter ->
                    val isSelected = selectedFilter == filter
                    val backgroundColor by animateColorAsState(
                        targetValue = if (isSelected) Color.White else SurfaceDefault,
                        animationSpec = AppleTvMotion.fluidSpring(),
                        label = "secondFilterChipBackground_${filter.name}"
                    )
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) Color(0xFF08090D) else TextSecondary,
                        animationSpec = AppleTvMotion.fluidSpring(),
                        label = "secondFilterChipText_${filter.name}"
                    )
                    val chipInteractionSource = remember { MutableInteractionSource() }
                    Surface(
                        modifier = Modifier
                            .appleTvPressable(chipInteractionSource, pressedScale = 0.94f)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(
                                interactionSource = chipInteractionSource,
                                indication = ripple(bounded = true)
                            ) { selectedFilter = filter },
                        shape = RoundedCornerShape(20.dp),
                        color = backgroundColor,
                        border = if (isSelected) null else BorderStroke(1.dp, SurfaceBorder)
                    ) {
                        Text(
                            text = filter.title,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // ========================================================
            // 3. CATALOG CONTENT SWITCHER
            // ========================================================
            val isSecondOverloadRefreshing by viewModel.isRefreshing.collectAsState()
            val secondPullRefreshState = rememberPullToRefreshState()
            val secondHaptic = LocalHapticFeedback.current

            LaunchedEffect(secondPullRefreshState) {
                var hasTriggeredThreshold = false
                snapshotFlow { secondPullRefreshState.distanceFraction }
                    .map { it >= 1.0f }
                    .distinctUntilChanged()
                    .collect { isPastThreshold ->
                        if (isPastThreshold && !hasTriggeredThreshold) {
                            secondHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            hasTriggeredThreshold = true
                        } else if (!isPastThreshold) {
                            hasTriggeredThreshold = false
                        }
                    }
            }

            PullToRefreshBox(
                isRefreshing = isSecondOverloadRefreshing,
                state = secondPullRefreshState,
                onRefresh = {
                    secondHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.refreshAppFreshBoot()
                },
                indicator = {
                    if (isSecondOverloadRefreshing || secondPullRefreshState.distanceFraction > 0.05f) {
                        PullToRefreshDefaults.Indicator(
                            state = secondPullRefreshState,
                            isRefreshing = isSecondOverloadRefreshing,
                            color = AccentBlue,
                            containerColor = SurfaceElevated,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AnimatedContent(
                    targetState = selectedFilter,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(280, easing = AppleTvMotion.SmoothOutEasing)) +
                            scaleIn(initialScale = 0.97f, animationSpec = tween(320, easing = AppleTvMotion.SmoothOutEasing)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(150))
                            )
                    },
                    label = "secondHomeCatalogFilterTransition"
                ) { currentFilter ->
                    when (currentFilter) {
                    HomeCatalogFilter.ALL -> {
                        // Home Overview: Continue Watching + Horizontal Carousels
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 110.dp)
                        ) {
                            item(key = "home_hero_billboard") {
                                when (val state = heroState) {
                                    is HeroSlotState.Resolving -> {
                                        ShimmerHeroBillboard()
                                    }
                                    is HeroSlotState.ContinueWatching -> {
                                        val item = state.item
                                        val safeCwId = item.mediaId.trim()
                                        ContinueWatchingCard(
                                            data = item,
                                            onResumeClick = {
                                                android.util.Log.i("ClotubePlayer", "--> Home ContinueWatching clicked: isSeries=${item.isSeries}, seriesId=${item.seriesId}, mediaId=${item.mediaId}")
                                                if (item.isSeries || !item.seriesId.isNullOrBlank()) {
                                                    val ep = series.flatMap { it.seasons }.flatMap { it.episodes }.find { it.id == safeCwId }
                                                    if (ep != null) {
                                                        onContinueWatchingClick(ep)
                                                    } else {
                                                        val candidateSeriesId = item.seriesId?.trim()?.ifBlank { null }
                                                        val sId = if (candidateSeriesId != null && (series.isEmpty() || series.any { it.id == candidateSeriesId })) {
                                                            candidateSeriesId
                                                        } else {
                                                            series.firstOrNull { s -> s.seasons.any { season -> season.episodes.any { e -> e.id == safeCwId } } }?.id ?: candidateSeriesId
                                                        }
                                                        val matchedSeries = series.find { it.id == sId }
                                                        if (matchedSeries != null) onSeriesClick(matchedSeries)
                                                    }
                                                } else if (safeCwId.isNotBlank()) {
                                                    val matched = movies.find { it.id == safeCwId }
                                                    if (matched != null) {
                                                        onMovieClick(matched)
                                                    } else {
                                                        val videoItem = viewModel.getMovieById(safeCwId)
                                                        if (videoItem != null) {
                                                            onMovieClick(
                                                                com.clotube.app.model.Movie(
                                                                    id = videoItem.id,
                                                                    title = videoItem.title.ifBlank { videoItem.displayTitle },
                                                                    durationMs = videoItem.durationMs,
                                                                    thumbnailUrl = videoItem.thumbnailUrl
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    is HeroSlotState.FeaturedFallback -> {
                                        FeaturedHeroBanner(
                                            movie = state.movie,
                                            onWatchMovie = { onMovieClick(state.movie) },
                                            onInspect = { onMovieClick(state.movie) }
                                        )
                                    }
                                }
                            }

                            // Movies Preview Carousel
                            if (movies.isNotEmpty()) {
                                item(key = "home_movies_preview") {
                                    SectionHeader(
                                        title = "Movies",
                                        onSeeAllClick = {
                                            selectedMovieLanguage = "All"
                                            selectedFilter = HomeCatalogFilter.MOVIES
                                        }
                                    )
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        items(displayMovies, key = { it.id }) { movie ->
                                            MovieOverlayCard(
                                                movie = movie,
                                                onClick = { onMovieClick(movie) },
                                                modifier = Modifier.width(220.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // TV Series Preview Carousel
                            item(key = "home_series_preview") {
                                Spacer(modifier = Modifier.height(14.dp))
                                SectionHeader(
                                    title = "Series",
                                    onSeeAllClick = { selectedFilter = HomeCatalogFilter.TV_SERIES }
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(series, key = { it.id }) { show ->
                                        SeriesOverlayCard(
                                            series = show,
                                            onClick = { onSeriesClick(show) },
                                            modifier = Modifier.width(220.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HomeCatalogFilter.MOVIES -> {
                        // Full-screen 3-column Portrait Poster Grid of All Movies
                        if (movies.isEmpty()) {
                            CatalogEmptyView("No movies available in your Drive folder")
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (orderedLanguages.size > 1) {
                                    LazyRow(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val pillOptions = listOf("All") + orderedLanguages
                                        items(pillOptions, key = { it }) { languageOption ->
                                            val isSelected = selectedMovieLanguage == languageOption
                                            val backgroundColor by animateColorAsState(
                                                targetValue = if (isSelected) Color.White else SurfaceDefault,
                                                animationSpec = AppleTvMotion.fluidSpring(),
                                                label = "secMovieLangFilterChipBg_$languageOption"
                                            )
                                            val textColor by animateColorAsState(
                                                targetValue = if (isSelected) Color(0xFF08090D) else TextSecondary,
                                                animationSpec = AppleTvMotion.fluidSpring(),
                                                label = "secMovieLangFilterChipText_$languageOption"
                                            )
                                            val interactionSource = remember { MutableInteractionSource() }
                                            Surface(
                                                modifier = Modifier
                                                    .appleTvPressable(interactionSource, pressedScale = 0.94f)
                                                    .clip(RoundedCornerShape(20.dp))
                                                    .clickable(
                                                        interactionSource = interactionSource,
                                                        indication = ripple(bounded = true)
                                                    ) { selectedMovieLanguage = languageOption },
                                                shape = RoundedCornerShape(20.dp),
                                                color = backgroundColor,
                                                border = if (isSelected) null else BorderStroke(1.dp, SurfaceBorder)
                                            ) {
                                                Text(
                                                    text = languageOption,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = textColor,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 110.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    if (selectedMovieLanguage == "All" && orderedLanguages.size > 1) {
                                        orderedLanguages.forEach { languageSection ->
                                            val sectionMovies = moviesByLanguage[languageSection].orEmpty()
                                            if (sectionMovies.isNotEmpty()) {
                                                item(
                                                    key = "sec_lang_header_$languageSection",
                                                    span = { GridItemSpan(maxLineSpan) }
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(top = if (languageSection == orderedLanguages.first()) 2.dp else 10.dp, bottom = 2.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            text = languageSection,
                                                            color = Color.White,
                                                            fontSize = 16.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                        Text(
                                                            text = "${sectionMovies.size} ${if (sectionMovies.size == 1) "movie" else "movies"}",
                                                            color = Color(0xFF94A3B8),
                                                            fontSize = 12.sp
                                                        )
                                                    }
                                                }
                                                items(
                                                    items = sectionMovies,
                                                    key = { it.id }
                                                ) { movie ->
                                                    MoviePosterCard(
                                                        movie = movie,
                                                        onClick = { onMovieClick(movie) },
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        val targetMovies = if (selectedMovieLanguage != "All") {
                                            moviesByLanguage[selectedMovieLanguage].orEmpty()
                                        } else {
                                            displayMovies
                                        }
                                        items(targetMovies, key = { it.id }) { movie ->
                                            MoviePosterCard(
                                                movie = movie,
                                                onClick = { onMovieClick(movie) },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HomeCatalogFilter.TV_SERIES -> {
                        // Full-screen Cinematic List of All TV Series
                        if (series.isEmpty()) {
                            CatalogEmptyView("No series available in your Drive folder")
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(series, key = { it.id }) { show ->
                                    SeriesRowCard(
                                        series = show,
                                        onClick = { onSeriesClick(show) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }

        if (currentNavTab == HomeNavigationTab.SEARCH) {
            SearchScreen(
                viewModel = viewModel,
                moviesList = movies,
                seriesList = series,
                onMovieSelected = onMovieClick,
                onSeriesSelected = onSeriesClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 70.dp)
            )
        } else if (currentNavTab == HomeNavigationTab.WATCHLIST) {
            WatchlistScreen(
                moviesList = movies,
                seriesList = series,
                onMovieSelected = { entry ->
                    val matched = movies.find { it.id == entry.id }
                    if (matched != null) onMovieClick(matched)
                },
                onSeriesSelected = { entry ->
                    val matched = series.find { it.id == entry.id }
                    if (matched != null) onSeriesClick(matched)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 70.dp)
            )
        }

        // ========================================================
        // 4. FLOATING BOTTOM NAVIGATION BAR
        // ========================================================
        ClassyBottomBar(
            selectedTab = currentNavTab,
            onTabSelected = { currentNavTab = it },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
