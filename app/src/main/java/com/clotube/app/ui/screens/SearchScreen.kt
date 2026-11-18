package com.clotube.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.clotube.app.data.DriveCatalogMatcher
import com.clotube.app.data.RecentSearchEntry
import com.clotube.app.data.SearchHistoryManager
import com.clotube.app.data.WatchlistManager
import com.clotube.app.data.metadata.GlobalSearchResult
import com.clotube.app.model.Movie
import com.clotube.app.model.Series
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
import com.clotube.app.viewmodel.VideoViewModel
import com.clotube.app.model.toMovie
import kotlinx.coroutines.launch

enum class SearchAvailabilityFilter(val label: String) {
    ALL("All"),
    IN_DRIVE("In Drive"),
    NOT_IN_DRIVE("Not in Drive")
}

enum class SearchMediaTypeFilter(val label: String) {
    ALL("All Types"),
    MOVIES("Movies"),
    TV_SERIES("TV Series")
}

enum class SearchStatusFilter(val label: String, val status: WatchlistStatus?) {
    ANY("Any Status", null),
    WATCH_LATER("Watch Later", WatchlistStatus.WATCH_LATER),
    WATCHED("Watched", WatchlistStatus.WATCHED),
    REWATCH("Rewatch", WatchlistStatus.REWATCH)
}

enum class SearchSortOption(val label: String) {
    RELEVANCE("Relevance"),
    TITLE_ASC("Title (A–Z)"),
    YEAR_DESC("Year (Newest)")
}

/**
 * Hybrid Global Search Screen combining Google Drive video library with global online
 * entertainment databases (TMDB v3 + Cinemeta + IMDb suggestions).
 * Supports "In Drive" / "Not in Drive" badging, and full Watchlist bookmarking.
 */
@Composable
fun SearchScreen(
    viewModel: VideoViewModel,
    moviesList: List<Movie>,
    seriesList: List<Series>,
    onMovieSelected: (Movie) -> Unit,
    onSeriesSelected: (Series) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current

    // Local-only Recent Searches (never synced to Google Drive)
    val recentSearches by SearchHistoryManager.recentSearches.collectAsState()
    LaunchedEffect(Unit) {
        SearchHistoryManager.ensureLoaded(context)
    }

    // Reactive streams from ViewModel
    val rawQuery by viewModel.searchQuery.collectAsState()
    val isSearchingOnline by viewModel.isSearchingOnline.collectAsState()
    val searchResults by viewModel.globalSearchResults.collectAsState()
    val vmMovies by viewModel.moviesList.collectAsState()
    val vmSeries by viewModel.seriesList.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val effectiveMovies = remember(moviesList, vmMovies, uiState.movies, uiState.videos, uiState.moviesList) {
        moviesList
            .ifEmpty { uiState.movies }
            .ifEmpty { vmMovies }
            .ifEmpty { uiState.videos.map { it.toMovie() } }
            .ifEmpty { uiState.moviesList.map { it.toMovie() } }
    }
    val effectiveSeries = remember(seriesList, vmSeries, uiState.tvSeriesList) {
        seriesList
            .ifEmpty { uiState.tvSeriesList }
            .ifEmpty { vmSeries }
    }

    // Observe watchlist revision for immediate pill/badge updates
    val revision = WatchlistManager.watchlistRevision

    // UI Filter & Sort states
    var selectedAvailability by rememberSaveable { mutableStateOf(SearchAvailabilityFilter.ALL) }
    var selectedTypeFilter by rememberSaveable { mutableStateOf(SearchMediaTypeFilter.ALL) }
    var selectedStatusFilter by rememberSaveable { mutableStateOf(SearchStatusFilter.ANY) }
    var selectedSortOption by rememberSaveable { mutableStateOf(SearchSortOption.RELEVANCE) }
    var isSortMenuOpen by remember { mutableStateOf(false) }

    // Bottom Sheet targets
    var targetForBookmarkSheet by remember { mutableStateOf<GlobalSearchResult?>(null) }
    var targetForExternalSheet by remember { mutableStateOf<GlobalSearchResult?>(null) }

    // Compute filtered and sorted result list
    val filteredResults = remember(
        searchResults,
        selectedAvailability,
        selectedTypeFilter,
        selectedStatusFilter,
        selectedSortOption,
        effectiveMovies,
        effectiveSeries,
        revision
    ) {
        var stream = searchResults.asSequence()

        // 1. Availability filter (All, In Drive, Not in Drive)
        when (selectedAvailability) {
            SearchAvailabilityFilter.ALL -> Unit
            SearchAvailabilityFilter.IN_DRIVE -> stream = stream.filter {
                it.isInDrive ||
                it.driveMovie != null ||
                it.driveSeries != null ||
                DriveCatalogMatcher.findMatchingDriveMovie(effectiveMovies, it.id, it.title) != null ||
                DriveCatalogMatcher.findMatchingDriveSeries(effectiveSeries, it.id, it.title) != null
            }
            SearchAvailabilityFilter.NOT_IN_DRIVE -> stream = stream.filter {
                !it.isInDrive &&
                it.driveMovie == null &&
                it.driveSeries == null &&
                DriveCatalogMatcher.findMatchingDriveMovie(effectiveMovies, it.id, it.title) == null &&
                DriveCatalogMatcher.findMatchingDriveSeries(effectiveSeries, it.id, it.title) == null
            }
        }

        // 2. Media Type filter (All, Movies, TV Series)
        when (selectedTypeFilter) {
            SearchMediaTypeFilter.ALL -> Unit
            SearchMediaTypeFilter.MOVIES -> stream = stream.filter { it.mediaType == WatchlistMediaType.MOVIE }
            SearchMediaTypeFilter.TV_SERIES -> stream = stream.filter { it.mediaType == WatchlistMediaType.SERIES }
        }

        // 3. Watchlist Status filter
        if (selectedStatusFilter.status != null) {
            val targetStatus = selectedStatusFilter.status
            stream = stream.filter { item ->
                val currentStatus = WatchlistManager.getStatus(item.id, item.title)
                currentStatus == targetStatus
            }
        }

        // 4. Sort ordering
        val sortedList = when (selectedSortOption) {
            SearchSortOption.RELEVANCE -> stream.toList()
            SearchSortOption.TITLE_ASC -> stream.sortedBy { it.title.lowercase() }.toList()
            SearchSortOption.YEAR_DESC -> stream.sortedWith(
                compareByDescending<GlobalSearchResult> { it.year.toIntOrNull() ?: 0 }
                    .thenBy { it.title.lowercase() }
            ).toList()
        }

        // 5. Canonical deduplication across Drive and online items
        val dedupedMap = LinkedHashMap<String, GlobalSearchResult>()
        for (item in sortedList) {
            val matchedM = item.driveMovie ?: DriveCatalogMatcher.findMatchingDriveMovie(effectiveMovies, item.id, item.title)
            val matchedS = item.driveSeries ?: if (matchedM == null) DriveCatalogMatcher.findMatchingDriveSeries(effectiveSeries, item.id, item.title) else null
            val key = when {
                matchedM != null -> "drive_movie_${matchedM.id}"
                matchedS != null -> "drive_series_${matchedS.id}"
                else -> "norm_${item.mediaType.name}_${WatchlistManager.normalizeTitle(item.title).ifBlank { item.id }}"
            }
            val existing = dedupedMap[key]
            if (existing == null) {
                dedupedMap[key] = item
            } else {
                val winner = if (!existing.isInDrive && (item.isInDrive || matchedM != null || matchedS != null)) item
                else if (existing.posterUrl.isBlank() && item.posterUrl.isNotBlank()) item
                else existing
                dedupedMap[key] = winner
            }
        }
        dedupedMap.values.toList()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasDeep)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ========================================================
            // 1. SEARCH INPUT HEADER
            // ========================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SurfaceElevated,
                    border = BorderStroke(1.dp, SurfaceBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = if (rawQuery.isNotEmpty()) AccentBlue else TextTertiary,
                            modifier = Modifier.size(20.dp)
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Box(modifier = Modifier.weight(1f)) {
                            if (rawQuery.isEmpty()) {
                                Text(
                                    text = "Search movies, series, or any title...",
                                    color = TextTertiary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = rawQuery,
                                onValueChange = { viewModel.onSearchQueryChanged(it) },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                cursorBrush = SolidColor(AccentBlue),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = {
                                        focusManager.clearFocus()
                                        if (rawQuery.trim().length >= 2) {
                                            coroutineScope.launch {
                                                SearchHistoryManager.addSearchQuery(context, rawQuery)
                                            }
                                        }
                                    }
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        if (rawQuery.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    viewModel.onSearchQueryChanged("")
                                    focusManager.clearFocus()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear search",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Slim linear progress indicator beneath the search input when online search is querying
            AnimatedVisibility(
                visible = isSearchingOnline && rawQuery.isNotBlank(),
                enter = fadeIn(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(200))
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .padding(horizontal = 20.dp),
                    color = AccentBlue,
                    trackColor = Color.Transparent
                )
            }

            if (rawQuery.isBlank()) {
                // ========================================================
                // LOCAL-ONLY RECENT SEARCHES PANEL
                // ========================================================
                RecentSearchesSection(
                    recentSearches = recentSearches,
                    onSelectQuery = { selectedQuery ->
                        viewModel.onSearchQueryChanged(selectedQuery)
                        focusManager.clearFocus()
                        coroutineScope.launch {
                            SearchHistoryManager.addSearchQuery(context, selectedQuery)
                        }
                    },
                    onFillQuery = { fillQuery ->
                        viewModel.onSearchQueryChanged(fillQuery)
                    },
                    onRemoveQuery = { queryToRemove ->
                        coroutineScope.launch {
                            SearchHistoryManager.removeSearchQuery(context, queryToRemove)
                        }
                    },
                    onClearAll = {
                        coroutineScope.launch {
                            SearchHistoryManager.clearAll(context)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 96.dp)
                )
            } else {
                // ========================================================
                // 2. MULTI-DIMENSIONAL FILTER CHIPS STRIP
                // ========================================================
                LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Availability Group (All, In Drive, Not in Drive)
                items(SearchAvailabilityFilter.values()) { filter ->
                    val isSelected = selectedAvailability == filter
                    val bg by animateColorAsState(
                        targetValue = if (isSelected) Color.White else SurfaceDefault,
                        animationSpec = tween(180),
                        label = "avail_chip_bg_${filter.name}"
                    )
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) CanvasDeep else TextSecondary,
                        animationSpec = tween(180),
                        label = "avail_chip_fg_${filter.name}"
                    )
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedAvailability = filter
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = bg,
                        border = if (isSelected) null else BorderStroke(0.5.dp, SurfaceBorder)
                    ) {
                        Text(
                            text = filter.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // Vertical divider
                item {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(18.dp)
                            .background(SurfaceBorder)
                    )
                }

                // Media Type Group (All Types, Movies, TV Series)
                items(SearchMediaTypeFilter.values()) { filter ->
                    val isSelected = selectedTypeFilter == filter
                    val bg by animateColorAsState(
                        targetValue = if (isSelected) Color.White else SurfaceDefault,
                        animationSpec = tween(180),
                        label = "type_chip_bg_${filter.name}"
                    )
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) CanvasDeep else TextSecondary,
                        animationSpec = tween(180),
                        label = "type_chip_fg_${filter.name}"
                    )
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedTypeFilter = filter
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = bg,
                        border = if (isSelected) null else BorderStroke(0.5.dp, SurfaceBorder)
                    ) {
                        Text(
                            text = filter.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // Vertical divider
                item {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(18.dp)
                            .background(SurfaceBorder)
                    )
                }

                // Watchlist Status Group (Any Status, Watch Later, Watched, Rewatch)
                items(SearchStatusFilter.values()) { filter ->
                    val isSelected = selectedStatusFilter == filter
                    val bg by animateColorAsState(
                        targetValue = if (isSelected) Color.White else SurfaceDefault,
                        animationSpec = tween(180),
                        label = "status_chip_bg_${filter.name}"
                    )
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) CanvasDeep else TextSecondary,
                        animationSpec = tween(180),
                        label = "status_chip_fg_${filter.name}"
                    )
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedStatusFilter = filter
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = bg,
                        border = if (isSelected) null else BorderStroke(0.5.dp, SurfaceBorder)
                    ) {
                        Text(
                            text = filter.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // Vertical divider
                item {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(18.dp)
                            .background(SurfaceBorder)
                    )
                }

                // Sort Dropdown Button
                item {
                    Box {
                        Surface(
                            onClick = { isSortMenuOpen = true },
                            shape = RoundedCornerShape(18.dp),
                            color = SurfaceDefault,
                            border = BorderStroke(0.5.dp, SurfaceBorder)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Sort,
                                    contentDescription = "Sort",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = selectedSortOption.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextSecondary
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = isSortMenuOpen,
                            onDismissRequest = { isSortMenuOpen = false },
                            modifier = Modifier.background(SurfaceElevated)
                        ) {
                            SearchSortOption.values().forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.label,
                                            fontSize = 12.sp,
                                            color = if (selectedSortOption == option) AccentBlue else TextPrimary,
                                            fontWeight = if (selectedSortOption == option) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        selectedSortOption = option
                                        isSortMenuOpen = false
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = TextPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ========================================================
            // 3. RESULTS GRID OR EMPTY STATE
            // ========================================================
            if (filteredResults.isEmpty()) {
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
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(SurfaceElevated)
                                .border(BorderStroke(1.dp, SurfaceBorder), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (rawQuery.isNotEmpty()) Icons.Rounded.SearchOff else Icons.Rounded.Search,
                                contentDescription = null,
                                tint = AccentBlue.copy(alpha = 0.8f),
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = if (rawQuery.isNotEmpty()) "No Matching Titles" else "Explore Global Entertainment",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (rawQuery.isNotEmpty()) {
                                "No titles matched \"$rawQuery\". Try checking the spelling or relaxing active filters."
                            } else {
                                "Search for any movie or TV series worldwide (e.g. \"Inception\", \"Interstellar\"), even if not currently in your Google Drive."
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp
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
                    // Results count subheader
                    item(span = { GridItemSpan(3) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SEARCH RESULTS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextTertiary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "${filteredResults.size} titles",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                color = TextTertiary
                            )
                        }
                    }

                    items(filteredResults, key = { it.driveMovie?.id ?: it.driveSeries?.id ?: (it.id + "_" + it.normalizedTitle) }) { item ->
                        val itemStatus = WatchlistManager.getStatus(item.id, item.title)
                        val matchedDriveMovie = item.driveMovie
                            ?: remember(item.id, item.title, effectiveMovies) {
                                DriveCatalogMatcher.findMatchingDriveMovie(effectiveMovies, item.id, item.title)
                            }
                        val matchedDriveSeries = item.driveSeries
                            ?: remember(item.id, item.title, effectiveSeries) {
                                if (matchedDriveMovie == null) {
                                    DriveCatalogMatcher.findMatchingDriveSeries(effectiveSeries, item.id, item.title)
                                } else null
                            }
                        val isItemInDrive = item.isInDrive || matchedDriveMovie != null || matchedDriveSeries != null

                        SearchGridCard(
                            result = item,
                            watchlistStatus = itemStatus,
                            isInDrive = isItemInDrive,
                            onCardClick = {
                                if (rawQuery.trim().length >= 2) {
                                    coroutineScope.launch {
                                        SearchHistoryManager.addSearchQuery(context, rawQuery)
                                    }
                                }
                                if (isItemInDrive) {
                                    if (item.mediaType == WatchlistMediaType.SERIES || matchedDriveSeries != null) {
                                        val s = matchedDriveSeries
                                            ?: effectiveSeries.find { it.id == item.id }
                                            ?: Series(id = item.id, title = item.title, posterUrl = item.posterUrl)
                                        onSeriesSelected(s)
                                    } else {
                                        val m = matchedDriveMovie
                                            ?: effectiveMovies.find { it.id == item.id }
                                            ?: Movie(id = item.id, title = item.title, thumbnailUrl = item.posterUrl)
                                        onMovieSelected(m)
                                    }
                                } else {
                                    // Non-Drive title: launch action bottom sheet
                                    targetForExternalSheet = item
                                }
                            },
                            onBookmarkClick = {
                                if (rawQuery.trim().length >= 2) {
                                    coroutineScope.launch {
                                        SearchHistoryManager.addSearchQuery(context, rawQuery)
                                    }
                                }
                                targetForBookmarkSheet = item
                            }
                        )
                    }
                }
            }
            }
        }

        // ========================================================
        // 4. BOTTOM SHEETS
        // ========================================================

        // A. Standard Watchlist Status Bottom Sheet (bookmark action)
        targetForBookmarkSheet?.let { target ->
            val status = WatchlistManager.getStatus(target.id, target.title)
            WatchlistStatusBottomSheet(
                title = target.title,
                currentStatus = status,
                onSelectStatus = { newStatus ->
                    viewModel.updateWatchlistStatus(target, newStatus)
                },
                onDismiss = { targetForBookmarkSheet = null }
            )
        }

        // B. External Title Action Bottom Sheet (clicking non-Drive item)
        targetForExternalSheet?.let { target ->
            val status = WatchlistManager.getStatus(target.id, target.title)
            ExternalTitleActionBottomSheet(
                result = target,
                currentStatus = status,
                onSelectStatus = { newStatus ->
                    viewModel.updateWatchlistStatus(target, newStatus)
                },
                onDismiss = { targetForExternalSheet = null }
            )
        }
    }
}

/**
 * Individual 2:3 vertical poster card displayed in the hybrid Global Search grid.
 */
@Composable
private fun SearchGridCard(
    result: GlobalSearchResult,
    watchlistStatus: WatchlistStatus,
    isInDrive: Boolean = result.isInDrive,
    onCardClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val artworkUrl = result.posterUrl.ifBlank { result.backdropUrl }

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
            if (artworkUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(artworkUrl)
                        .crossfade(180)
                        .build(),
                    contentDescription = result.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Monogram placeholder
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF1E2638), Color(0xFF0F1420))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = result.title.take(1).uppercase(),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.25f)
                    )
                }
            }

            // Top-Start Overlay Stack: Watchlist status pill + In Drive / Not in Drive pill
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (watchlistStatus != WatchlistStatus.UNWATCHED) {
                    WatchlistStatusPill(status = watchlistStatus)
                }

                if (!isInDrive) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xD908090D),
                        border = BorderStroke(0.5.dp, Color(0x5594A3B8))
                    ) {
                        Text(
                            text = "Not in Drive",
                            color = TextSecondary,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xCC08090D),
                        border = BorderStroke(0.5.dp, AccentBlue.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = "In Drive",
                            color = AccentPrimary,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Top-End Quick Action Bookmark Button
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                WatchlistBookmarkButton(
                    status = watchlistStatus,
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

        // Media Type Tag + Year + Rating
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (result.mediaType == WatchlistMediaType.SERIES) "Series" else "Movie",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = AccentBlue
            )
            if (result.year.isNotBlank()) {
                Text(text = "•", fontSize = 9.sp, color = TextTertiary)
                Text(
                    text = result.year,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextTertiary
                )
            }
            if (result.rating.isNotBlank()) {
                Text(text = "•", fontSize = 9.sp, color = TextTertiary)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = result.rating,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF59E0B)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Title text
        Text(
            text = result.title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Obsidian Cinema local-only Recent Searches panel rendered when the Global Search query is blank.
 */
@Composable
private fun RecentSearchesSection(
    recentSearches: List<RecentSearchEntry>,
    onSelectQuery: (String) -> Unit,
    onFillQuery: (String) -> Unit,
    onRemoveQuery: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (recentSearches.isEmpty()) {
        Box(
            modifier = modifier.padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(SurfaceElevated)
                        .border(BorderStroke(1.dp, SurfaceBorder), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                        tint = AccentBlue.copy(alpha = 0.85f),
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "No Recent Searches",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Search for any movie or TV series in your Google Drive or worldwide.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                )
            }
        }
    } else {
        Column(modifier = modifier) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RECENT SEARCHES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextTertiary,
                    letterSpacing = 1.sp
                )

                Text(
                    text = "Clear All",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AccentBlue,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClearAll)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(
                    items = recentSearches,
                    key = { it.query.lowercase() }
                ) { entry ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectQuery(entry.query) }
                                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = null,
                                tint = TextTertiary,
                                modifier = Modifier.size(18.dp)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = entry.query,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = { onFillQuery(entry.query) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.NorthWest,
                                    contentDescription = "Fill search",
                                    tint = TextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            IconButton(
                                onClick = { onRemoveQuery(entry.query) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Remove search",
                                    tint = TextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = Color(0x0FFFFFFF),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        }
    }
}
