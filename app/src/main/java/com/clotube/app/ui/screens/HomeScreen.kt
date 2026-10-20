package com.clotube.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
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
import com.clotube.app.model.ContinueWatchingItem
import com.clotube.app.player.DriveMediaSourceFactory
import com.clotube.app.ui.components.BottomNavBar
import com.clotube.app.ui.components.DriveFolderPickerDialog
import com.clotube.app.ui.components.GoogleAccountDialog
import kotlinx.coroutines.launch
import com.clotube.app.ui.components.ShimmerCategoryRow
import com.clotube.app.ui.components.ShimmerHeroBillboard
import com.clotube.app.ui.components.ShimmerMediaSection
import com.clotube.app.ui.theme.BackgroundDeep
import com.clotube.app.ui.theme.BadgeBackground
import com.clotube.app.ui.theme.BadgeBorder
import com.clotube.app.ui.theme.BorderOutline
import com.clotube.app.ui.theme.CardContainer
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.vector.ImageVector
import com.clotube.app.viewmodel.ContinueWatchingData
import com.clotube.app.model.Episode
import com.clotube.app.model.Movie
import com.clotube.app.model.Series
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
    TV_SERIES("TV Series")
}

@Keep
enum class HomeNavigationTab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
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
            FilterChip(
                selected = isSelected,
                onClick = { onFilterSelected(filter) },
                label = {
                    Text(
                        text = filter.title,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                shape = RoundedCornerShape(20.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(0xFF131822),
                    labelColor = Color(0xFF90A4AE),
                    selectedContainerColor = Color(0xFF1976D2),
                    selectedLabelColor = Color.White
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = Color(0xFF222C3D),
                    selectedBorderColor = Color(0xFF1976D2),
                    borderWidth = 1.dp,
                    selectedBorderWidth = 1.dp
                )
            )
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
    onNavigateToSettings: () -> Unit = { viewModel.selectTab(NavigationTab.SETTINGS) }
) {
    val uiState by viewModel.uiState.collectAsState()
    val continueWatchingData by viewModel.continueWatchingState.collectAsState()
    val continueWatchingItem by viewModel.continueWatchingItem.collectAsState()
    val lastWatchedDirect by viewModel.playbackHistoryManager.getLastWatchedMedia().collectAsState(initial = null)
    var showAccountDialog by remember { mutableStateOf(false) }
    val currentUser by viewModel.currentUserAccount.collectAsState()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

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
        containerColor = BackgroundDeep,
        topBar = {
            if (uiState.currentTab == NavigationTab.LIBRARY) {
                TopAppBar(
                    modifier = Modifier.statusBarsPadding(),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceDefault,
                    titleContentColor = TextHighEmphasis
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 20.dp)
                    ) {
                        Text(
                            text = "Clotube",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextHighEmphasis
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        // Modern Compact Pill Search Bar
                        BasicTextField(
                            value = uiState.searchQuery,
                            onValueChange = { viewModel.onSearchQueryChanged(it) },
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .background(Color(0xFF1E2230), RoundedCornerShape(20.dp))
                                .padding(horizontal = 12.dp),
                            singleLine = true,
                            textStyle = TextStyle(
                                color = TextHighEmphasis,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            cursorBrush = SolidColor(PrimaryAccent),
                            decorationBox = { innerTextField ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = Color(0xFF94A3B8),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (uiState.searchQuery.isEmpty()) {
                                            Text(
                                                text = "Search movies, series...",
                                                fontSize = 13.sp,
                                                color = Color(0xFF64748B)
                                            )
                                        }
                                        innerTextField()
                                    }
                                    if (uiState.searchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { viewModel.onSearchQueryChanged("") },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Clear search",
                                                tint = Color(0xFF94A3B8),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                },
                actions = {
                    // Profile / Sign-in Avatar on far right
                    Box(modifier = Modifier.padding(start = 8.dp, end = 12.dp)) {
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
                                .size(38.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, if (uiState.isSignedIn || currentUser != null) PrimaryAccent else BorderOutline, CircleShape)
                        ) {
                            val photo = currentUser?.photoUrl ?: uiState.userAvatarUrl
                            if ((uiState.isSignedIn || currentUser != null) && photo != null) {
                                AsyncImage(
                                    model = photo,
                                    contentDescription = "Profile",
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = if (uiState.isSignedIn || currentUser != null) "Profile" else "Sign In",
                                    tint = if (uiState.isSignedIn || currentUser != null) SecondaryAccent else TextMuted,
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
            BottomNavBar(
                selectedTab = uiState.currentTab,
                onTabSelected = { tab ->
                    if (tab == NavigationTab.SETTINGS) {
                        onNavigateToSettings()
                    } else {
                        viewModel.selectTab(tab)
                    }
                }
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
        } else {
            val isInitialLoading = uiState.isInitialLoading
            val isCatalogEmpty = uiState.moviesList.isEmpty() && uiState.tvSeriesList.isEmpty()

        if (isCatalogEmpty && isInitialLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding())
            ) {
                ShimmerCategoryRow()
                ShimmerHeroBillboard()
                ShimmerMediaSection(sectionTitle = "Movies")
                ShimmerMediaSection(sectionTitle = "TV Series & Shows")
            }
        } else {
            val searchQuery = uiState.searchQuery.trim()
            val displayMovies = uiState.filteredVideos
            val displaySeries = if (searchQuery.isBlank()) {
                uiState.tvSeriesList
            } else {
                uiState.tvSeriesList.filter {
                    it.title.contains(searchQuery, ignoreCase = true) ||
                    it.genre.contains(searchQuery, ignoreCase = true)
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
                        HomeCatalogFilter.TV_SERIES.title -> HomeCatalogFilter.TV_SERIES
                        else -> HomeCatalogFilter.ALL
                    },
                    onFilterSelected = { filter ->
                        viewModel.selectFilter(filter.title)
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (uiState.selectedFilter) {
                        HomeCatalogFilter.MOVIES.title -> {
                            // Full-screen 2-column Grid of All Movies (Continue Watching hidden)
                            if (displayMovies.isEmpty()) {
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
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(
                                        items = displayMovies,
                                        key = { it.id },
                                        contentType = { "movie_grid_card" }
                                    ) { movie ->
                                        MovieCard(
                                            movie = movie,
                                            onClick = { viewModel.openInspector(movie) },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        HomeCatalogFilter.TV_SERIES.title -> {
                            // Full-screen 2-column Grid of All TV Series (Continue Watching hidden)
                            if (displaySeries.isEmpty()) {
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
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(
                                        items = displaySeries,
                                        key = { it.id },
                                        contentType = { "series_grid_card" }
                                    ) { series ->
                                        SeriesCard(
                                            series = series,
                                            onClick = { onSeriesSelected(series) },
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
                                    val heroMovie = uiState.heroItem
                                    val heroSeries = uiState.heroSeries
                                    val lastWatched = lastWatchedDirect ?: uiState.lastWatchedMedia

                                    val activeContinueWatching = continueWatchingData
                                        ?: remember(continueWatchingItem, lastWatched) {
                                            continueWatchingItem?.let { item ->
                                                val safeMediaId = (item.mediaId ?: "").trim()
                                                if (safeMediaId.isEmpty()) null
                                                else {
                                                    val safeTitle = (item.title ?: "").trim().ifBlank { "Untitled Video" }
                                                    val safeSubtitle = (item.subtitle ?: "").trim()
                                                    val safeThumb = (item.backdropUrl ?: item.thumbnailUrl ?: "").trim()
                                                    val isSeriesItem = item.isSeries ||
                                                        viewModel.isEpisode(safeMediaId, safeTitle) ||
                                                        safeSubtitle.contains(Regex("""S\d+E\d+|Season\s*\d+|Episode\s*\d+""", RegexOption.IGNORE_CASE)) ||
                                                        safeTitle.contains(Regex("""S\d+E\d+|Season\s*\d+|Episode\s*\d+""", RegexOption.IGNORE_CASE)) ||
                                                        (item.seriesId ?: "").isNotBlank()
                                                    val resolvedSeriesId = if (!item.seriesId.isNullOrBlank()) {
                                                        item.seriesId?.trim().orEmpty()
                                                    } else if (isSeriesItem) {
                                                        viewModel.getSeriesIdForEpisode(safeMediaId, null) ?: viewModel.getDefaultSeriesId() ?: ""
                                                    } else ""
                                                    ContinueWatchingData(
                                                        mediaId = safeMediaId,
                                                        seriesId = resolvedSeriesId,
                                                        title = safeTitle,
                                                        subtitle = safeSubtitle,
                                                        episodeTitle = safeSubtitle,
                                                        thumbnailUrl = safeThumb,
                                                        isMovie = !isSeriesItem,
                                                        progressPercentage = (item.progressFraction * 100).toInt().coerceIn(0, 100),
                                                        currentPositionMs = item.positionMs,
                                                        durationMs = item.durationMs,
                                                        rawEpisode = null,
                                                        rawMovie = null
                                                    )
                                                }
                                            } ?: lastWatched?.takeIf { it.positionMs >= 1000L || it.progressFraction > 0f }?.let { wp ->
                                                val safeFileId = (wp.fileId ?: "").trim()
                                                if (safeFileId.isEmpty()) null
                                                else {
                                                    val watchedPercent = (wp.progressFraction * 100).toInt().coerceIn(0, 100)
                                                    val safeTitle = (wp.title ?: "").trim()
                                                    val isSeriesWp = wp.isSeries ||
                                                        viewModel.isEpisode(safeFileId, safeTitle) ||
                                                        wp.seasonNumber > 0 ||
                                                        wp.episodeNumber > 0 ||
                                                        (wp.seriesId ?: "").isNotBlank() ||
                                                        safeTitle.contains(Regex("""S\d+E\d+|Season\s*\d+|Episode\s*\d+""", RegexOption.IGNORE_CASE))
                                                    val resolvedSeriesId = if (!wp.seriesId.isNullOrBlank()) {
                                                        wp.seriesId?.trim().orEmpty()
                                                    } else if (isSeriesWp) {
                                                        viewModel.getSeriesIdForEpisode(safeFileId, wp.seriesTitle) ?: viewModel.getDefaultSeriesId() ?: ""
                                                    } else ""
                                                    val subtitle = if (isSeriesWp) {
                                                        safeTitle.ifBlank {
                                                            val sNum = wp.seasonNumber.coerceAtLeast(1)
                                                            val eNum = wp.episodeNumber.coerceAtLeast(1)
                                                            "S${String.format(java.util.Locale.US, "%02d", sNum)}:E${String.format(java.util.Locale.US, "%02d", eNum)}"
                                                        }
                                                    } else {
                                                        "Movie"
                                                    }
                                                    val safeThumb = (wp.backdropUrl ?: wp.thumbnailUrl ?: "").trim()
                                                    ContinueWatchingData(
                                                        mediaId = safeFileId,
                                                        seriesId = resolvedSeriesId,
                                                        title = wp.formattedDisplayTitle,
                                                        subtitle = subtitle,
                                                        episodeTitle = safeTitle,
                                                        thumbnailUrl = safeThumb,
                                                        isMovie = !isSeriesWp,
                                                        progressPercentage = watchedPercent,
                                                        currentPositionMs = wp.positionMs,
                                                        durationMs = wp.durationMs,
                                                        rawEpisode = null,
                                                        rawMovie = null
                                                    )
                                                }
                                            }
                                        }

                                    if (activeContinueWatching != null) {
                                        val cw = activeContinueWatching
                                        ContinueWatchingCard(
                                            data = cw,
                                            onResumeClick = {
                                                val safeMediaId = (cw.mediaId ?: "").trim()
                                                if (safeMediaId.isNotEmpty()) {
                                                    val safeTitle = (cw.title ?: "").trim().ifBlank { "Video" }
                                                    val pos = cw.currentPositionMs
                                                    val isEpisode = !cw.isMovie ||
                                                        viewModel.isEpisode(safeMediaId, safeTitle) ||
                                                        (cw.seriesId ?: "").isNotBlank() ||
                                                        safeTitle.contains(Regex("""S\d+E\d+|Season\s*\d+|Episode\s*\d+""", RegexOption.IGNORE_CASE))

                                                    if (!isEpisode) {
                                                        if (onResumePlayback != null) {
                                                            onResumePlayback(safeMediaId, safeTitle, null, pos)
                                                        } else {
                                                            val movie = viewModel.getMovieById(safeMediaId) ?: cw.rawMovie?.toVideoItem()
                                                            if (movie != null) {
                                                                onVideoSelected(movie)
                                                            }
                                                        }
                                                    } else {
                                                        val sId = if (!cw.seriesId.isNullOrBlank()) {
                                                            cw.seriesId.trim()
                                                        } else {
                                                            viewModel.getSeriesIdForEpisode(safeMediaId, cw.seriesTitle)
                                                                ?: viewModel.getDefaultSeriesId()
                                                                ?: uiState.tvSeriesList.find { s ->
                                                                    s.seasons.any { season -> season.episodes.any { ep -> ep.id == safeMediaId } }
                                                                }?.id?.trim() ?: ""
                                                        }
                                                        val epTitle = (cw.episodeTitle ?: "").trim().ifBlank { safeTitle }
                                                        if (onResumePlayback != null) {
                                                            onResumePlayback(safeMediaId, epTitle, sId.ifBlank { null }, pos)
                                                        } else {
                                                            val matchedSeries = uiState.tvSeriesList.find { it.id == sId }
                                                            if (matchedSeries != null) {
                                                                onSeriesSelected(matchedSeries)
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            onDetailsClick = {
                                                val safeMediaId = (cw.mediaId ?: "").trim()
                                                val safeTitle = (cw.title ?: "").trim().ifBlank { "Video" }
                                                val isEpisode = !cw.isMovie ||
                                                    viewModel.isEpisode(safeMediaId, safeTitle) ||
                                                    (cw.seriesId ?: "").isNotBlank() ||
                                                    safeTitle.contains(Regex("""S\d+E\d+|Season\s*\d+|Episode\s*\d+""", RegexOption.IGNORE_CASE))

                                                if (isEpisode) {
                                                    val sId = if (!cw.seriesId.isNullOrBlank()) {
                                                        cw.seriesId.trim()
                                                    } else {
                                                        viewModel.getSeriesIdForEpisode(safeMediaId, cw.seriesTitle)
                                                            ?: viewModel.getDefaultSeriesId()
                                                            ?: uiState.tvSeriesList.find { s ->
                                                                s.seasons.any { season -> season.episodes.any { ep -> ep.id == safeMediaId } }
                                                            }?.id?.trim() ?: ""
                                                    }
                                                    val matchedSeries = uiState.tvSeriesList.find { it.id == sId }
                                                        ?: TvSeries(
                                                            id = sId,
                                                            title = (cw.title ?: "").substringBefore(" • ").trim().ifBlank { "TV Series" },
                                                            posterUrl = cw.thumbnailUrl
                                                        )
                                                    onSeriesSelected(matchedSeries)
                                                }
                                            },
                                            onDismiss = {
                                                viewModel.clearContinueWatching()
                                            },
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                    } else if (heroMovie != null || heroSeries != null) {
                                        HeroBillboardSection(
                                            movie = heroMovie,
                                            series = heroSeries,
                                            continueWatching = null,
                                            lastWatched = null,
                                            onWatchMovie = { heroMovie?.let { onVideoSelected(it) } },
                                            onWatchSeries = { heroSeries?.let { onSeriesSelected(it) } },
                                            onResume = { },
                                            onResumeContinueWatching = null,
                                            onInspect = {
                                                heroMovie?.let { viewModel.openInspector(it) }
                                                    ?: heroSeries?.let { onSeriesSelected(it) }
                                            },
                                            onRemoveResume = null
                                        )
                                    }
                                }

                                if (displayMovies.isNotEmpty()) {
                                    item(key = "home_movies_preview") {
                                        SectionHeader(
                                            title = "Movies",
                                            subtitle = "${displayMovies.size} raw videos in 'Movies' folder"
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
                                                    onClick = { viewModel.openInspector(movie) },
                                                    modifier = Modifier.width(220.dp)
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
                                            title = "TV Series & Shows",
                                            subtitle = "${displaySeries.size} multi-season shows in 'Series' folder"
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
                                                    onClick = { onSeriesSelected(series) },
                                                    modifier = Modifier.width(220.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                if (displayMovies.isEmpty() && displaySeries.isEmpty() && !uiState.isLoading) {
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

    // Modal Bottom Sheet inspector when a video is clicked
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

    // Google Account Profile Dialog Overlay
    GoogleAccountDialog(
        isOpen = showAccountDialog,
        onDismissRequest = { showAccountDialog = false },
        userDisplayName = currentUser?.displayName ?: uiState.userName ?: "User",
        userEmail = currentUser?.email ?: uiState.userEmail ?: "",
        userPhotoUrl = currentUser?.photoUrl?.toString() ?: uiState.userAvatarUrl,
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
 * Top 16:9 OTT Hero Billboard Banner with high-res backdrop, dynamic Continue Watching status,
 * progress bar, and Resume or Watch Now actions.
 */
@Composable
private fun HeroBillboardSection(
    movie: VideoItem?,
    series: TvSeries?,
    continueWatching: ContinueWatchingItem? = null,
    lastWatched: com.clotube.app.data.WatchProgress? = null,
    onWatchMovie: () -> Unit,
    onWatchSeries: () -> Unit,
    onResume: (com.clotube.app.data.WatchProgress) -> Unit = {},
    onResumeContinueWatching: ((ContinueWatchingItem) -> Unit)? = null,
    onInspect: () -> Unit,
    onRemoveResume: (() -> Unit)? = null
) {
    val isResumeMode = continueWatching != null || (lastWatched != null && (lastWatched.positionMs >= 1000L || lastWatched.progressFraction > 0f))

    val title = when {
        continueWatching != null -> continueWatching.title
        isResumeMode && lastWatched != null -> lastWatched.formattedDisplayTitle
        else -> movie?.title ?: series?.title ?: "Clotube Featured"
    }

    val subtitle = when {
        continueWatching != null -> continueWatching.subtitle
        isResumeMode && lastWatched != null -> {
            val watchedPercent = (lastWatched.progressFraction * 100).toInt()
            val current = formatTimestamp(lastWatched.positionMs)
            val total = formatTimestamp(lastWatched.durationMs)
            if (lastWatched.isSeries) {
                val sNum = lastWatched.seasonNumber
                val eNum = lastWatched.episodeNumber
                val epCode = "S${String.format(java.util.Locale.US, "%02d", sNum)}:E${String.format(java.util.Locale.US, "%02d", eNum)}"
                "$epCode • $watchedPercent% watched • $current / $total"
            } else {
                "$watchedPercent% watched • $current / $total"
            }
        }
        else -> null
    }

    val progressFraction = when {
        continueWatching != null -> continueWatching.progressFraction
        lastWatched != null -> lastWatched.progressFraction
        else -> 0f
    }

    val backdrop = when {
        continueWatching != null -> continueWatching.backdropUrl ?: continueWatching.thumbnailUrl ?: movie?.thumbnailUrl ?: series?.backdropUrl ?: series?.posterUrl
        isResumeMode && lastWatched != null -> lastWatched.backdropUrl ?: lastWatched.thumbnailUrl ?: movie?.thumbnailUrl ?: series?.backdropUrl ?: series?.posterUrl
        else -> movie?.thumbnailUrl ?: series?.backdropUrl ?: series?.posterUrl
    }

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
            // Backdrop Image
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(backdrop)
                    .crossfade(150)
                    .bitmapConfig(Bitmap.Config.HARDWARE)
                    .build(),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Top Badge
            if (isResumeMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF2F80ED), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = Color(0xFF2F80ED),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "CONTINUE WATCHING",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF2F80ED)
                        )
                    }
                }
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
                // Title
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isResumeMode && subtitle != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    // 3.dp Electric Blue progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(1.5.dp))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(Color(0xFF2F80ED), RoundedCornerShape(1.5.dp))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isResumeMode) {
                        Button(
                            onClick = {
                                if (continueWatching != null && onResumeContinueWatching != null) {
                                    onResumeContinueWatching(continueWatching)
                                } else if (lastWatched != null) {
                                    onResume(lastWatched)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F80ED)),
                            shape = PillShape,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = TextHighEmphasis,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val buttonText = if (progressFraction >= 0.95f) "Watch Again" else if (progressFraction <= 0.01f) "Watch Now" else "Resume"
                            Text(buttonText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                if (movie != null) onWatchMovie() else onWatchSeries()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                            shape = PillShape,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = TextHighEmphasis,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Watch Now", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            if (isResumeMode) {
                                val isSeries = continueWatching?.isSeries ?: lastWatched?.isSeries ?: false
                                if (isSeries) onWatchSeries() else onInspect()
                            } else {
                                if (movie != null) onInspect() else onWatchSeries()
                            }
                        },
                        shape = PillShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = SecondaryAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Details", fontSize = 12.sp, color = TextHighEmphasis)
                    }

                    if (isResumeMode && onRemoveResume != null) {
                        IconButton(
                            onClick = onRemoveResume,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove from history",
                                tint = Color(0xFF6B7A90)
                            )
                        }
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
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextHighEmphasis
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = TextMuted
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
    modifier: Modifier = Modifier.width(220.dp)
) {
    Card(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderOutline)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Layer 1 (Base): Coil AsyncImage with downsampling & HARDWARE bitmap
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(movie.thumbnailUrl)
                    .crossfade(150)
                    .size(width = 440, height = 248) // Exact 2x pixel density for 220dp x 124dp card
                    .bitmapConfig(Bitmap.Config.HARDWARE) // Direct GPU texture
                    .memoryCacheKey("thumb_${movie.id}_${movie.thumbnailUrl.hashCode()}")
                    .diskCacheKey("thumb_${movie.id}_${movie.thumbnailUrl.hashCode()}")
                    .build(),
                contentDescription = movie.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Layer 2 (Top Chips):
            // Anchored to Alignment.TopStart: Clean quality badge
            val badgeText = movie.resolutionBadge.ifBlank { "HD" }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                    .border(0.5.dp, BorderOutline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = SecondaryAccent
                )
            }

            // Anchored to Alignment.TopEnd: Duration tag
            if (movie.durationFormatted.isNotBlank() && movie.durationFormatted != "00:00") {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                        .border(0.5.dp, BorderOutline, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = movie.durationFormatted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextHighEmphasis
                    )
                }
            }

            // Layer 3 (Bottom Scrim Gradient): covers lower 45–50% of card height
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

                // 2. Secondary Line (Genre)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val genreText = movie.genreFormatted.ifBlank { "Movie" }

                    Text(
                        text = genreText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF94A3B8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Layer 5 (Watch Progress Bar flush against bottom edge):
            if (movie.watchProgressFraction > 0.02f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(movie.watchProgressFraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color(0xFF2F80ED))
                    )
                }
            }
        }
    }
}

@Composable
private fun MovieCarouselCard(
    video: VideoItem,
    onClick: () -> Unit
) = MovieCard(movie = video, onClick = onClick)

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
    Card(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderOutline)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Layer 1 (Base): Coil AsyncImage with downsampling & HARDWARE bitmap
            val thumbUrl = series.thumbnailUrl.ifBlank { series.backdropUrl ?: series.posterUrl ?: "" }
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbUrl)
                    .crossfade(150)
                    .size(width = 440, height = 248) // Exact 2x pixel density for 220dp x 124dp card
                    .bitmapConfig(Bitmap.Config.HARDWARE) // Direct GPU texture
                    .memoryCacheKey("thumb_${series.id}_${thumbUrl.hashCode()}")
                    .diskCacheKey("thumb_${series.id}_${thumbUrl.hashCode()}")
                    .build(),
                contentDescription = series.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )

            // Layer 2 (Top Chips):
            // Anchored to Alignment.TopStart: "OTT SERIES" badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                    .border(0.5.dp, BorderOutline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "OTT SERIES",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = SecondaryAccent
                )
            }

            // Anchored to Alignment.TopEnd: Season count tag (pre-calculated totalSeasonsFormatted)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                    .border(0.5.dp, BorderOutline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = series.totalSeasonsFormatted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextHighEmphasis
                )
            }

            // Layer 3 (Bottom Scrim Gradient): covers lower 45–50% of card height
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
                    text = series.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // 2. Secondary Line (Total Episodes & Genre)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val genreText = series.genreFormatted.ifBlank { "TV Series" }
                    val totalEps = series.seasons.sumOf { it.episodes.size }
                    val secondaryText = if (totalEps > 0) "$genreText • $totalEps Episodes" else genreText

                    Text(
                        text = secondaryText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF94A3B8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Layer 5 (Watch Progress Bar flush against bottom edge):
            if (series.watchProgressFraction > 0.02f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(series.watchProgressFraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color(0xFF2F80ED))
                    )
                }
            }
        }
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
    Surface(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF111722),
        border = BorderStroke(1.dp, Color(0xFF1E2838))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Artwork
            if (!movie.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = movie.thumbnailUrl,
                    contentDescription = movie.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top Badges
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xCC00838F), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "1080p FHD",
                        color = Color(0xFFE0F7FA),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (movie.formattedDuration.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = movie.formattedDuration,
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
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
                    Text(
                        text = "Movie • Cinema",
                        color = Color(0xFF8B9BB4),
                        fontSize = 9.sp,
                        maxLines = 1
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
    Surface(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF111722),
        border = BorderStroke(1.dp, Color(0xFF1E2838))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Artwork
            val poster = series.posterUrl ?: series.thumbnailUrl
            if (poster.isNotBlank()) {
                AsyncImage(
                    model = poster,
                    contentDescription = series.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top Badges
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xCC00838F), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "OTT SERIES",
                        color = Color(0xFFE0F7FA),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${series.seasons.size} Seasons",
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
                    Text(
                        text = "${series.genre} • ${series.totalEpisodes} Episodes",
                        color = Color(0xFF8B9BB4),
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
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF))
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Choose Movies")
                        }
                        OutlinedButton(
                            onClick = onChooseSeries,
                            shape = PillShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                            border = BorderStroke(1.dp, Color(0xFF00E5FF))
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
    data: ContinueWatchingData,
    onResumeClick: () -> Unit,
    onDetailsClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onResumeClick() },
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF101622),
        border = BorderStroke(1.dp, Color(0xFF1E2838))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // 1. Full-Bleed Artwork (Movie Poster or TV Episode Still)
            val thumbUrl = (data.thumbnailUrl ?: "").trim()
            if (thumbUrl.isNotBlank()) {
                val context = LocalContext.current
                val safeKey = (data.mediaId ?: "").trim()
                val imageRequest = remember(safeKey, thumbUrl) {
                    ImageRequest.Builder(context)
                        .data(thumbUrl)
                        .memoryCacheKey(safeKey)
                        .diskCacheKey(safeKey)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = imageRequest,
                    contentDescription = (data.title ?: "").ifBlank { "Artwork" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 2. Balanced Scrim: Allows artwork to shine at the top, ensures text legibility at bottom
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

            // 3. Top Badges: "CONTINUE WATCHING" Pill + Dismiss 'X'
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xCC0E1A2B),
                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (data.isMovie) "RESUME MOVIE" else "CONTINUE WATCHING",
                            color = Color(0xFF00E5FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0x80000000))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Dismiss",
                        tint = Color(0xFFB0BEC5),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // 4. Bottom Info: Title, Subtitle, Progress Bar & Action Buttons
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // Title
                Text(
                    text = (data.title ?: "").ifBlank { "Untitled Video" },
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Progress Info & Subtitle
                val posFormatted = formatMsToTime(data.currentPositionMs)
                val durFormatted = formatMsToTime(data.durationMs)
                val safeSub = (data.subtitle ?: "").trim()
                val subText = if (safeSub.contains("% watched")) {
                    safeSub
                } else if (safeSub.isNotBlank()) {
                    "$safeSub • ${data.progressPercentage}% watched • $posFormatted / $durFormatted"
                } else {
                    "${data.progressPercentage}% watched • $posFormatted / $durFormatted"
                }

                Text(
                    text = subText,
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Cyan Progress Bar
                LinearProgressIndicator(
                    progress = { (data.progressPercentage / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.5.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF00E5FF),
                    trackColor = Color(0xFF222E3D)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Actions: Resume & Details
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onResumeClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Resume",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val buttonText = if (data.progressPercentage >= 95) "Watch Again" else "Resume"
                        Text(
                            text = buttonText,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!data.isMovie) {
                        OutlinedButton(
                            onClick = onDetailsClick,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, Color(0xFF2A384C)),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0x66131A26)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = "Details",
                                color = Color(0xFFCFD8DC),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContinueWatchingCard(
    episode: Episode,
    onPlayClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    backdropUrl: String = ""
) {
    val sNum = episode.seasonNumber
    val eNum = episode.episodeNumber
    val sTag = "S" + sNum.toString().padStart(2, '0')
    val eTag = "E" + eNum.toString().padStart(2, '0')
    val seriesTitle = (episode.seriesTitle ?: "").ifBlank { "Series" }
    val epTitle = (episode.displayTitle ?: "").ifBlank { "Episode" }
    val thumb = (episode.thumbnailUrl ?: "").ifBlank { backdropUrl.orEmpty() }
    val data = ContinueWatchingData(
        mediaId = (episode.id ?: "").trim(),
        title = "$seriesTitle • $sTag:$eTag",
        subtitle = epTitle,
        episodeTitle = epTitle,
        thumbnailUrl = thumb,
        isMovie = false,
        progressPercentage = (episode.watchProgressFraction * 100).toInt().coerceIn(0, 100),
        currentPositionMs = (episode.watchProgressFraction * episode.durationMs).toLong(),
        durationMs = episode.durationMs,
        rawEpisode = episode,
        rawMovie = null
    )
    ContinueWatchingCard(
        data = data,
        onResumeClick = onPlayClick,
        onDetailsClick = onPlayClick,
        onDismiss = onDismiss,
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
    onSearchClick: () -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf(HomeCatalogFilter.ALL) }
    var currentNavTab by remember { mutableStateOf(HomeNavigationTab.HOME) }

    val movies by viewModel.moviesList.collectAsState()
    val series by viewModel.seriesList.collectAsState()
    val continueWatchingEpisode by viewModel.lastWatchedEpisodeState.collectAsState()
    val continueWatchingData by viewModel.continueWatchingState.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090E))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ========================================================
            // 1. TOP APP BAR (Branding, Search Pill, Account Avatar)
            // ========================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Clotube",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onSearchClick() },
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF131A26),
                    border = BorderStroke(1.dp, Color(0xFF1E2838))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = Color(0xFF6B7A90),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Search movies, series...",
                            color = Color(0xFF6B7A90),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, Color(0xFF1976D2), CircleShape)
                        .background(Color(0xFF111824))
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
                            color = Color(0xFF00E5FF),
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
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = filter },
                        label = {
                            Text(
                                text = filter.title,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xFF131822),
                            labelColor = Color(0xFF90A4AE),
                            selectedContainerColor = Color(0xFF1976D2),
                            selectedLabelColor = Color.White
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = Color(0xFF222C3D),
                            selectedBorderColor = Color(0xFF1976D2),
                            borderWidth = 1.dp,
                            selectedBorderWidth = 1.dp
                        )
                    )
                }
            }

            // ========================================================
            // 3. CATALOG CONTENT SWITCHER
            // ========================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (selectedFilter) {
                    HomeCatalogFilter.ALL -> {
                        // Home Overview: Continue Watching + Horizontal Carousels
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 110.dp)
                        ) {
                            if (continueWatchingData != null) {
                                val cw = continueWatchingData!!
                                val safeCwId = (cw.mediaId ?: "").trim()
                                if (safeCwId.isNotEmpty()) {
                                    item(key = "continue_watching_card") {
                                        ContinueWatchingCard(
                                            data = cw,
                                            onResumeClick = {
                                                if (cw.isMovie) {
                                                    val matched = movies.find { it.id == safeCwId } ?: cw.rawMovie
                                                    if (matched != null) onMovieClick(matched)
                                                } else {
                                                    val ep = cw.rawEpisode
                                                        ?: series.flatMap { it.seasons }.flatMap { it.episodes }.find { it.id == safeCwId }
                                                    if (ep != null) onContinueWatchingClick(ep)
                                                }
                                            },
                                            onDetailsClick = {
                                                if (!cw.isMovie) {
                                                    val sId = (cw.seriesId ?: "").trim()
                                                    val matched = series.find { it.id == sId }
                                                        ?: series.find { s -> s.seasons.any { it.episodes.any { ep -> ep.id == safeCwId } } }
                                                    if (matched != null) onSeriesClick(matched)
                                                }
                                            },
                                            onDismiss = { viewModel.clearContinueWatching() },
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            } else if (continueWatchingEpisode != null) {
                                val ep = continueWatchingEpisode!!
                                val safeEpId = (ep.id ?: "").trim()
                                if (safeEpId.isNotEmpty()) {
                                    item(key = "continue_watching_card") {
                                        ContinueWatchingCard(
                                            episode = ep,
                                            onPlayClick = { onContinueWatchingClick(ep) },
                                            onDismiss = { viewModel.clearContinueWatching() },
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                            }

                            // Movies Preview Carousel
                            item(key = "home_movies_preview") {
                                SectionHeader(
                                    title = "Movies",
                                    subtitle = "${movies.size} videos available"
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(movies, key = { it.id }) { movie ->
                                        MovieOverlayCard(
                                            movie = movie,
                                            onClick = { onMovieClick(movie) },
                                            modifier = Modifier.width(220.dp)
                                        )
                                    }
                                }
                            }

                            // TV Series Preview Carousel
                            item(key = "home_series_preview") {
                                Spacer(modifier = Modifier.height(14.dp))
                                SectionHeader(
                                    title = "TV Series & Shows",
                                    subtitle = "${series.size} multi-season shows"
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
                        // Full-screen 2-column Grid of All Movies (No Continue Watching)
                        if (movies.isEmpty()) {
                            CatalogEmptyView("No movies available in your Drive folder")
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(movies, key = { it.id }) { movie ->
                                    MovieOverlayCard(
                                        movie = movie,
                                        onClick = { onMovieClick(movie) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    HomeCatalogFilter.TV_SERIES -> {
                        // Full-screen 2-column Grid of All TV Series (No Continue Watching)
                        if (series.isEmpty()) {
                            CatalogEmptyView("No series available in your Drive folder")
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(series, key = { it.id }) { show ->
                                    SeriesOverlayCard(
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

        // ========================================================
        // 4. FLOATING BOTTOM NAVIGATION BAR
        // ========================================================
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(32.dp)),
            shape = RoundedCornerShape(32.dp),
            color = Color(0xF20D1424),
            border = BorderStroke(1.dp, Color(0xFF1E2A3E)),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeNavigationTab.values().forEach { tab ->
                    val isSelected = currentNavTab == tab

                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { currentNavTab = tab }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isSelected) Color(0xFF1976D2) else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = if (isSelected) Color.White else Color(0xFF6B7A90),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = tab.title,
                            color = if (isSelected) Color.White else Color(0xFF6B7A90),
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
