package com.clotube.app.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.app.util.triggerClickHaptic
import com.clotube.app.util.triggerNavigationHaptic
import com.clotube.app.model.Episode
import com.clotube.app.model.Series
import com.clotube.app.model.TvSeries
import com.clotube.app.data.DownloadStatus
import com.clotube.app.ui.components.ClotubeVideoPlayer
import org.videolan.libvlc.MediaPlayer
import com.clotube.app.ui.components.EpisodeItemRow
import com.clotube.app.ui.components.SeasonTabChip
import com.clotube.app.ui.components.shimmerEffect
import com.clotube.app.ui.theme.*
import com.clotube.app.ui.theme.AppleTvMotion
import com.clotube.app.ui.theme.appleTvPressable
import com.clotube.app.viewmodel.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    series: Series,
    viewModel: VideoViewModel,
    activePlayingEpisode: Episode? = null,
    initialPositionMs: Long = 0L,
    autoPlayEpisodeId: String? = null,
    onEpisodeSelected: (Episode) -> Unit = {},
    onEpisodeSelectedWithPosition: (Episode, Long) -> Unit = { ep, _ -> onEpisodeSelected(ep) },
    onBackClick: () -> Unit,
    onClosePlayer: () -> Unit = onBackClick,
    isLoadingEpisodes: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val toggleFullscreen: () -> Unit = {
        activity?.let { act ->
            act.requestedOrientation = if (isLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    val playbackHistoryManager = viewModel.playbackHistoryManager

    val allEpisodes = remember(series.id) {
        series.seasons.flatMap { it.episodes }
    }

    // Match autoPlayEpisodeId by ID, or fallback to SxxExx match if ID format differs
    val initialAutoPlayEpisode = remember(series.id) {
        if (!autoPlayEpisodeId.isNullOrBlank()) {
            series.seasons.flatMap { it.episodes }.firstOrNull { it.id == autoPlayEpisodeId }
                ?: series.seasons.flatMap { it.episodes }.firstOrNull { it.displayTitle.contains(autoPlayEpisodeId, ignoreCase = true) }
                ?: activePlayingEpisode
        } else activePlayingEpisode
    }

    // 1. Immediately determine the best initial season containing episodes (prevents opening on Season 2 if empty)
    val defaultSeasonNumber = remember(series.id) {
        initialAutoPlayEpisode?.seasonNumber
            ?: series.seasons.firstOrNull { it.episodes.isNotEmpty() }?.seasonNumber
            ?: series.seasons.firstOrNull()?.seasonNumber
            ?: 1
    }

    // 2. Localize selected season state to this series instance
    var currentSeasonNumber by rememberSaveable(series.id) {
        mutableIntStateOf(defaultSeasonNumber)
    }

    // PREVENT RECOMPOSITION RESET: Key strictly to seriesId
    var activeEpisode by remember(series.id) {
        val initialEp = if (!autoPlayEpisodeId.isNullOrBlank()) {
            series.seasons.flatMap { it.episodes }.firstOrNull { it.id == autoPlayEpisodeId }
        } else activePlayingEpisode
        mutableStateOf<Episode?>(initialEp)
    }

    // Also handle late catalog hydration if allEpisodes finishes loading after screen entry
    LaunchedEffect(autoPlayEpisodeId, allEpisodes.size) {
        if (activeEpisode == null && !autoPlayEpisodeId.isNullOrBlank() && allEpisodes.isNotEmpty()) {
            val matched = allEpisodes.firstOrNull { it.id == autoPlayEpisodeId }
                ?: allEpisodes.firstOrNull { it.displayTitle.contains(autoPlayEpisodeId, ignoreCase = true) }
            if (matched != null) {
                currentSeasonNumber = matched.seasonNumber
                activeEpisode = matched
            }
        }
    }
    var initialResumePositionMs by remember(series.id) {
        mutableLongStateOf(initialPositionMs)
    }
    var targetResumeEpisode by remember(series.id) { mutableStateOf<Episode?>(null) }
    var targetResumePosMs by remember(series.id) { mutableLongStateOf(initialPositionMs) }
    var targetDurationMs by remember(series.id) { mutableLongStateOf(0L) }

    DisposableEffect(series.id) {
        onDispose {
            activeEpisode = null
            viewModel.stopAndReleaseCurrentPlayer()
        }
    }

    LaunchedEffect(series.id) {
        val autoEp = initialAutoPlayEpisode
        if (autoEp != null) {
            currentSeasonNumber = autoEp.seasonNumber
        }
    }

    LaunchedEffect(activePlayingEpisode) {
        if (activePlayingEpisode != null && activePlayingEpisode != activeEpisode) {
            currentSeasonNumber = activePlayingEpisode.seasonNumber
            activeEpisode = activePlayingEpisode
        }
    }

    fun startEpisodePlayback(targetEpisode: Episode, startPosMs: Long = 0L) {
        if (activePlayingEpisode?.id == targetEpisode.id || activeEpisode?.id == targetEpisode.id) {
            // Already active; do nothing to avoid teardown cycle
            return
        }
        currentSeasonNumber = targetEpisode.seasonNumber
        activeEpisode = targetEpisode
        initialResumePositionMs = startPosMs
        onEpisodeSelectedWithPosition(targetEpisode, startPosMs)
    }

    // Hardware BackHandler: If in landscape, revert to portrait without closing player; else navigate back
    BackHandler(enabled = true) {
        if (isLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            onBackClick() // Pops backstack to previous screen (HomeScreen)
        }
    }

    val allSeriesEpisodes = allEpisodes

    var lastScrolledEpisodeId by rememberSaveable(series.id) { mutableStateOf<String?>(null) }
    val seasonRowState = rememberLazyListState()

    LaunchedEffect(currentSeasonNumber) {
        val seasonIdx = series.seasons.indexOfFirst { it.seasonNumber == currentSeasonNumber }
        if (seasonIdx != -1) {
            seasonRowState.animateScrollToItem(seasonIdx)
        }
    }

    val ongoingEpisode = remember(activeEpisode, activePlayingEpisode, autoPlayEpisodeId, series) {
        activeEpisode
            ?: activePlayingEpisode
            ?: allSeriesEpisodes.firstOrNull { it.id == autoPlayEpisodeId }
    }

    LaunchedEffect(series.id, ongoingEpisode?.id) {
        val targetEpisode = ongoingEpisode 
            ?: viewModel.getLastWatchedEpisode(series) 
            ?: allSeriesEpisodes.firstOrNull()
            
        if (targetEpisode == null) return@LaunchedEffect

        // 1. Align active season tab with the target episode
        if (currentSeasonNumber != targetEpisode.seasonNumber) {
            currentSeasonNumber = targetEpisode.seasonNumber
        }

        // 2. Prevent redundant auto-scrolls for the same episode
        if (lastScrolledEpisodeId == targetEpisode.id) return@LaunchedEffect

        // 3. Wait for the season episodes to compose in layout, scroll once
        snapshotFlow {
            val season = series.seasons.firstOrNull { it.seasonNumber == currentSeasonNumber }
            val episodes = season?.episodes ?: emptyList()
            val epIdx = episodes.indexOfFirst { it.id == targetEpisode.id }
            episodes.isNotEmpty() to epIdx
        }.firstOrNull { (hasEpisodes, epIndex) -> hasEpisodes && epIndex != -1 }?.let { (_, epIndex) ->
            // Layout indices:
            // Index 0: series_metadata_section (Title & Up Next card)
            // Index 1: sticky_season_selector_bar (Sticky season tabs)
            // Index 2 + epIndex: Target episode card

            // Small delay to allow Compose layout measurement to complete
            kotlinx.coroutines.delay(120L)

            if (epIndex <= 0) {
                // For Episode 1, scroll to the sticky header so Episode 1 docks directly beneath it
                listState.animateScrollToItem(
                    index = 1,
                    scrollOffset = 0
                )
            } else {
                // For Episode 2+, target the preceding item with a positive offset.
                // This scrolls the previous card up behind the sticky bar, placing the target episode
                // completely in view right below the season bar with zero clipping.
                val precedingCardOffsetPx = with(density) { 34.dp.roundToPx() }
                listState.animateScrollToItem(
                    index = 1 + epIndex,
                    scrollOffset = precedingCardOffsetPx
                )
            }

            lastScrolledEpisodeId = targetEpisode.id
        }
    }

    LaunchedEffect(series.id, series.seasons) {
        if (series.seasons.firstOrNull { it.seasonNumber == currentSeasonNumber }?.episodes.isNullOrEmpty()) {
            val seasonWithEpisodes = series.seasons.firstOrNull { it.episodes.isNotEmpty() }?.seasonNumber
            if (seasonWithEpisodes != null && seasonWithEpisodes != currentSeasonNumber) {
                currentSeasonNumber = seasonWithEpisodes
            }
        }
    }

    LaunchedEffect(activeEpisode?.seasonNumber) {
        val epSeason = activeEpisode?.seasonNumber
        if (epSeason != null && epSeason != currentSeasonNumber) {
            currentSeasonNumber = epSeason
        }
    }



    var lastWatchedEpisode by remember(series.id) { mutableStateOf<Episode?>(null) }
    var hasCheckedLastWatched by remember(series.id) { mutableStateOf(false) }

    val activeSeason = series.seasons.firstOrNull { it.seasonNumber == currentSeasonNumber }
        ?: series.seasons.firstOrNull()

    // 3. One-time check for last-watched episode without blocking initial render or scrolling past headers
    LaunchedEffect(series.id) {
        if (!hasCheckedLastWatched) {
            val lastWatched = viewModel.getLastWatchedEpisode(series)
            if (lastWatched != null) {
                lastWatchedEpisode = lastWatched
                targetResumeEpisode = lastWatched
                val pos = viewModel.playbackHistoryManager.getResumePosition(lastWatched.id)
                targetResumePosMs = if (initialPositionMs > 0L && lastWatched.id == autoPlayEpisodeId) initialPositionMs else pos
                val savedProgress = viewModel.playbackHistoryManager.getProgressSync(lastWatched.id)
                targetDurationMs = when {
                    lastWatched.durationMs > 0L -> lastWatched.durationMs
                    savedProgress != null && savedProgress.durationMs > 0L -> savedProgress.durationMs
                    else -> 0L
                }
                if (currentSeasonNumber != lastWatched.seasonNumber) {
                    currentSeasonNumber = lastWatched.seasonNumber
                }
            }
            hasCheckedLastWatched = true
        }
    }

    val initialFallbackEpisode = remember(series.id, series.seasons) {
        series.seasons.firstOrNull { it.episodes.isNotEmpty() }?.episodes?.firstOrNull()
    }
    var cachedUpNextEpisode by remember(series.id) {
        mutableStateOf<Episode?>(initialFallbackEpisode)
    }

    val upNextEpisode = remember(activeSeason, lastWatchedEpisode, series.seasons, cachedUpNextEpisode) {
        val candidate = lastWatchedEpisode
            ?: activeSeason?.episodes?.firstOrNull()
            ?: initialFallbackEpisode
            ?: cachedUpNextEpisode

        if (candidate != null && candidate != cachedUpNextEpisode) {
            cachedUpNextEpisode = candidate
        }
        candidate ?: cachedUpNextEpisode
    }

    val currentEpisodeList = activeSeason?.episodes ?: emptyList()
    val currentIndex = currentEpisodeList.indexOfFirst { it.id == activeEpisode?.id }

    val prevEpisode = if (currentIndex > 0) currentEpisodeList[currentIndex - 1] else null
    val nextEpisode = if (currentIndex != -1 && currentIndex < currentEpisodeList.lastIndex) currentEpisodeList[currentIndex + 1] else null

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasDeep)
    ) {

        // ========================================================
        // 1. TOP VIDEO PLAYER / BACKDROP BANNER
        // ========================================================
        Box(
            modifier = if (isLandscape) {
                if (activeEpisode != null || activePlayingEpisode != null) {
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black)
                }
            } else {
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            }
        ) {
            val currentPlaying = activeEpisode ?: activePlayingEpisode
            Crossfade(
                targetState = currentPlaying,
                animationSpec = tween(280, easing = AppleTvMotion.SmoothOutEasing),
                label = "topViewportCrossfade"
            ) { targetPlaying ->
                if (targetPlaying != null) {
                    key(targetPlaying.id) {
                        val currentEpisodeList = activeSeason?.episodes ?: emptyList()
                        val currentIndex = currentEpisodeList.indexOfFirst { it.id == targetPlaying.id }

                        val prevEpisode = if (currentIndex > 0) currentEpisodeList[currentIndex - 1] else null
                        val nextEpisode = if (currentIndex != -1 && currentIndex < currentEpisodeList.lastIndex) {
                            currentEpisodeList[currentIndex + 1]
                        } else if (currentIndex != -1 && currentIndex == currentEpisodeList.lastIndex) {
                            val currentSeasonIdx = series.seasons.indexOfFirst { it.seasonNumber == currentSeasonNumber }
                            if (currentSeasonIdx != -1 && currentSeasonIdx < series.seasons.lastIndex) {
                                series.seasons[currentSeasonIdx + 1].episodes.firstOrNull()
                            } else null
                        } else null

                        ClotubeVideoPlayer(
                            episode = targetPlaying,
                            viewModel = viewModel,
                            seriesId = series.id,
                            initialPositionMs = initialResumePositionMs,
                            startPositionMs = initialResumePositionMs,
                            nextEpisode = nextEpisode,
                            onPlayerCreated = { epPlayer: MediaPlayer ->
                                viewModel.registerActivePlayer(epPlayer)
                            },
                            onPreviousEpisode = prevEpisode?.let { ep ->
                                { startEpisodePlayback(ep) }
                            },
                            onNextEpisode = nextEpisode?.let { ep ->
                                { startEpisodePlayback(ep) }
                            },
                            onClosePlayer = {
                                if (isLandscape) {
                                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                } else {
                                    onBackClick() // Navigates back to the previous screen
                                }
                            },
                            onToggleFullscreen = toggleFullscreen,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    val fallbackEp = targetResumeEpisode
                        ?: upNextEpisode
                        ?: lastWatchedEpisode
                        ?: series.seasons.firstOrNull { it.episodes.isNotEmpty() }?.episodes?.firstOrNull()
                        ?: allEpisodes.firstOrNull()

                    if (fallbackEp != null) {
                        SeriesResumeHeaderCard(
                            series = series,
                            episode = fallbackEp,
                            currentPositionMs = targetResumePosMs,
                            durationMs = targetDurationMs,
                            onBackClick = {
                                view.triggerNavigationHaptic()
                                if (isLandscape) {
                                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                } else {
                                    onBackClick()
                                }
                            },
                            onResumeClick = { ep, pos ->
                                startEpisodePlayback(ep, pos)
                            }
                        )
                    } else {
                        StaticSeriesBackdropHeader(
                            series = series,
                            onBackClick = {
                                view.triggerNavigationHaptic()
                                if (isLandscape) {
                                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                } else {
                                    onBackClick()
                                }
                            },
                            onStartWatchingClick = {
                                view.triggerClickHaptic()
                                val firstEp = allEpisodes.firstOrNull()
                                    ?: series.seasons.firstOrNull { it.episodes.isNotEmpty() }?.episodes?.firstOrNull()
                                if (firstEp != null) {
                                    startEpisodePlayback(firstEp)
                                }
                            }
                        )
                    }
                }
            }
        }

        // ========================================================
        // 2. SCROLLABLE CONTENT WITH PINNED SEASON HEADER
        // ========================================================
        if (!isLandscape || (activeEpisode == null && activePlayingEpisode == null)) {
            var isSeriesRefreshing by remember { mutableStateOf(false) }
            val seriesPullRefreshState = rememberPullToRefreshState()
            val seriesHaptic = LocalHapticFeedback.current

            // Dual-stage haptics: trigger on threshold snap + confirmation on pull release
            LaunchedEffect(seriesPullRefreshState) {
                var hasTriggeredThreshold = false
                snapshotFlow { seriesPullRefreshState.distanceFraction }
                    .map { it >= 1.0f }
                    .distinctUntilChanged()
                    .collect { isPastThreshold ->
                        if (isPastThreshold && !hasTriggeredThreshold) {
                            seriesHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            hasTriggeredThreshold = true
                        } else if (!isPastThreshold) {
                            hasTriggeredThreshold = false
                        }
                    }
            }

            PullToRefreshBox(
                isRefreshing = isSeriesRefreshing,
                state = seriesPullRefreshState,
                onRefresh = {
                    seriesHaptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    coroutineScope.launch {
                        isSeriesRefreshing = true
                        viewModel.refreshAppFreshBoot()
                        viewModel.refreshSingleSeries(series.id, series.title)
                        isSeriesRefreshing = false
                    }
                },
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = seriesPullRefreshState,
                        isRefreshing = isSeriesRefreshing,
                        color = AccentBlue,
                        containerColor = SurfaceElevated,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val currentUserEmail = remember(series.id) {
                    viewModel.getSelectedGoogleAccountName().orEmpty().ifBlank {
                        viewModel.uiState.value.userEmail.orEmpty()
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {

            // ITEM 0: series_metadata_section (Title, genre, watch card)
            item(key = "series_metadata_section") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)
                ) {
                    Text(
                        text = series.title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (series.genre.isNotBlank()) {
                            Text(
                                text = series.genre,
                                color = Color(0xFF8899A6),
                                fontSize = 12.sp
                            )
                            Text(
                                text = "•",
                                color = Color(0xFF8899A6),
                                fontSize = 12.sp
                            )
                        }

                        if (isLoadingEpisodes) {
                            Box(
                                modifier = Modifier
                                    .width(60.dp)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .shimmerEffect()
                            )
                        } else {
                            val seasonCount = series.seasons.size
                            Text(
                                text = "$seasonCount ${if (seasonCount == 1) "Season" else "Seasons"}",
                                color = Color(0xFF8899A6),
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                ) {
                    val upNextToDisplay = upNextEpisode
                    if (upNextToDisplay != null) {
                        val upNextInteractionSource = remember { MutableInteractionSource() }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SurfaceDefault,
                            border = BorderStroke(1.dp, SurfaceBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .appleTvPressable(upNextInteractionSource, pressedScale = 0.97f)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(
                                    interactionSource = upNextInteractionSource,
                                    indication = ripple(bounded = true)
                                ) {
                                    startEpisodePlayback(upNextToDisplay)
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "UP NEXT",
                                        color = Color(0xFF64748B),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.8.sp
                                    )

                                    Spacer(modifier = Modifier.height(3.dp))

                                    val epIndex = "E${upNextToDisplay.episodeNumber.toString().padStart(2, '0')}"
                                    val fullTitle = "$epIndex • ${upNextToDisplay.displayTitle}"

                                    Text(
                                        text = fullTitle,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(AccentBlue)
                                        .clickable {
                                            startEpisodePlayback(upNextToDisplay)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = "Play Next",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    } else if (isLoadingEpisodes) {
                        UpNextCardSkeleton()
                    }
                }
            }

            // ITEM 1: sticky_season_selector_bar (Season tabs)
            stickyHeader(key = "sticky_season_selector_bar") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = CanvasDeep,
                    shadowElevation = 4.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (isLoadingEpisodes && (series.seasons.isEmpty() || series.seasons.all { it.episodes.isEmpty() })) {
                            SeasonSelectorRowSkeleton()
                        } else {
                            LazyRow(
                                state = seasonRowState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                items(series.seasons, key = { it.seasonNumber }) { season ->
                                    val isCompleted by viewModel.playbackHistoryManager
                                        .isSeasonFullyWatched(season.episodes)
                                        .collectAsState(initial = false)

                                    SeasonTabChip(
                                        season = season,
                                        isSelected = (activeSeason?.seasonNumber == season.seasonNumber),
                                        isCompleted = isCompleted,
                                        onSelect = {
                                            currentSeasonNumber = season.seasonNumber
                                        },
                                        onMarkComplete = {
                                            coroutineScope.launch {
                                                viewModel.playbackHistoryManager.markSeasonAsComplete(
                                                    seriesId = series.id,
                                                    seriesTitle = series.title,
                                                    seasonNumber = season.seasonNumber,
                                                    episodes = season.episodes
                                                )
                                            }
                                        },
                                        onMarkIncomplete = {
                                            coroutineScope.launch {
                                                viewModel.playbackHistoryManager.markSeasonAsIncomplete(
                                                    seriesId = series.id,
                                                    seasonNumber = season.seasonNumber,
                                                    episodes = season.episodes
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = SurfaceBorder, thickness = 0.5.dp)
                    }
                }
            }

            // EPISODES LIST
            if (isLoadingEpisodes) {
                item(key = "series_episodes_skeleton") {
                    SeriesEpisodesSkeleton()
                }
            } else if (activeSeason == null || activeSeason.episodes.isEmpty()) {
                item(key = "empty_season_message") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp, bottom = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No episodes available in Season ${activeSeason?.seasonNumber ?: currentSeasonNumber}",
                            color = Color(0xFF6B7A90),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                items(activeSeason.episodes, key = { episode -> "${currentSeasonNumber}_${episode.id}" }) { episode ->
                    val downloadRecord by viewModel.downloadRepository.getDownloadRecord(episode.id).collectAsState(initial = null)
                    val isWatchedLocal = remember(currentUserEmail, episode.id) {
                        if (currentUserEmail.isNotBlank()) {
                            com.clotube.app.data.AndroidWatchHistoryManager.isEpisodeWatched(context, currentUserEmail, episode.id)
                        } else false
                    }
                    val isWatchedFlow by viewModel.playbackHistoryManager.isEpisodeWatched(episode.id).collectAsState(initial = false)
                    val isWatched = isWatchedLocal || isWatchedFlow
                    val isOngoing = (activeEpisode?.id == episode.id)

                    EpisodeItemRow(
                        episode = episode,
                        seriesId = series.id,
                        seriesTitle = series.title,
                        isPlayingThis = isOngoing,
                        onEpisodeClick = {
                            startEpisodePlayback(episode)
                        },
                        onStartDownload = {
                            coroutineScope.launch {
                                val token = viewModel.getValidAccessToken()
                                viewModel.downloadRepository.enqueueDownload(
                                    fileId = episode.id,
                                    title = "${series.title} - ${episode.displayTitle}",
                                    authToken = token
                                )
                            }
                        },
                        onCancelDownload = {
                            coroutineScope.launch {
                                viewModel.downloadRepository.cancelDownload(episode.id)
                            }
                        },
                        onToggleWatched = {
                            if (isWatched) {
                                viewModel.markEpisodeUnwatched(series, episode)
                            } else {
                                viewModel.markEpisodeWatched(series, episode)
                            }
                        },
                        downloadRecord = downloadRecord,
                        isDownloading = downloadRecord?.status == DownloadStatus.DOWNLOADING || downloadRecord?.status == DownloadStatus.QUEUED,
                        isDownloaded = downloadRecord?.status == DownloadStatus.COMPLETED,
                        isWatched = isWatched,
                        playbackHistoryManager = viewModel.playbackHistoryManager,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
            }
        }
    }
}
}

/**
 * Convenience overload for direct series + autoPlayEpisodeId invocation.
 */
@Composable
fun SeriesDetailScreen(
    series: Series,
    viewModel: VideoViewModel,
    autoPlayEpisodeId: String? = null,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) = SeriesDetailScreen(
    series = series,
    viewModel = viewModel,
    activePlayingEpisode = null,
    initialPositionMs = 0L,
    autoPlayEpisodeId = autoPlayEpisodeId,
    onBackClick = onBackClick,
    modifier = modifier
)

/**
 * Backward-compatible navigation overload for NavHost entry.
 */
@Composable
fun SeriesDetailScreen(
    seriesId: String,
    initialEpisodeId: String? = null,
    initialPositionMs: Long = 0L,
    autoPlayEpisodeId: String? = null,
    initialSeries: TvSeries? = null,
    accessToken: String? = null,
    onNavigateBack: () -> Unit,
    onOpenFullscreen: (fileId: String, title: String, seriesId: String, episodeQueue: List<Episode>) -> Unit = { _, _, _, _ -> },
    onRegisterActivePlayer: ((org.videolan.libvlc.MediaPlayer?) -> Unit)? = null,
    viewModel: VideoViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val effectiveAutoPlayId = autoPlayEpisodeId ?: initialEpisodeId
    val context = androidx.compose.ui.platform.LocalContext.current
    val seriesList by viewModel.seriesList.collectAsState()

    val resolvedSeries = remember(seriesId, seriesList, initialSeries, effectiveAutoPlayId) {
        seriesList.firstOrNull { it.id == seriesId && seriesId.isNotEmpty() }
            ?: (if (!effectiveAutoPlayId.isNullOrBlank()) {
                seriesList.find { s -> s.seasons.any { it.episodes.any { ep -> ep.id == effectiveAutoPlayId } } }
            } else null)
            ?: initialSeries
    }

    val fallbackSeries = remember(seriesId, effectiveAutoPlayId) {
        val sTitle = seriesList.find { it.id == seriesId }?.title ?: "TV Series"
        val epId = effectiveAutoPlayId.orEmpty().ifBlank { "ep_fallback" }
        val fallbackEp = Episode(
            id = epId,
            seriesTitle = sTitle,
            displayTitle = "Episode 1",
            seasonNumber = 1,
            episodeNumber = 1,
            thumbnailUrl = "",
            durationFormatted = ""
        )
        TvSeries(
            id = seriesId.ifBlank { "series_fallback" },
            title = sTitle,
            posterUrl = "",
            backdropUrl = "",
            seasons = listOf(
                com.clotube.app.model.Season(
                    id = "season_1",
                    seasonNumber = 1,
                    episodes = if (!effectiveAutoPlayId.isNullOrBlank()) listOf(fallbackEp) else emptyList()
                )
            )
        )
    }

    val targetSeries = resolvedSeries ?: fallbackSeries

    val hasLoadedEpisodes = remember(targetSeries.seasons) {
        targetSeries.seasons.any { it.episodes.isNotEmpty() }
    }

    var isLoadingEpisodes by remember(seriesId) {
        mutableStateOf(!hasLoadedEpisodes)
    }

    LaunchedEffect(hasLoadedEpisodes) {
        if (hasLoadedEpisodes) {
            isLoadingEpisodes = false
        }
    }

    LaunchedEffect(seriesId, effectiveAutoPlayId) {
        val needsFetch = targetSeries.seasons.isEmpty() || targetSeries.seasons.all { it.episodes.isEmpty() }

        if (needsFetch) {
            isLoadingEpisodes = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val authRepo = com.clotube.app.auth.GoogleAuthRepository(context)
                    val seriesRepo = com.clotube.app.data.SeriesRepository(authRepo)
                    val effectiveId = if (seriesId.isNotBlank()) seriesId else {
                        viewModel.getSeriesIdForEpisode(effectiveAutoPlayId.orEmpty())
                            ?: viewModel.getDefaultSeriesId()
                            ?: viewModel.resolveSeriesFolderId()
                            ?: ""
                    }
                    if (effectiveId.isNotBlank()) {
                        val freshShow = seriesRepo.getShowDetails(effectiveId, targetSeries.title)
                        if (freshShow.seasons.any { it.episodes.isNotEmpty() }) {
                            viewModel.updateSeriesInCatalog(freshShow)
                            com.clotube.app.viewmodel.saveShowToDisk(context, freshShow)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SeriesDetailScreen", "Failed on-demand episode fetch for $seriesId: ${e.message}")
                } finally {
                    isLoadingEpisodes = false
                }
            }
        } else {
            isLoadingEpisodes = false
        }
    }

    // PREVENT RECOMPOSITION RESET: Key strictly to seriesId
    var activePlayingEpisode by remember(seriesId) {
        val initialEp = if (!effectiveAutoPlayId.isNullOrBlank()) {
            targetSeries.seasons.flatMap { it.episodes }.firstOrNull { it.id == effectiveAutoPlayId }
        } else null
        mutableStateOf<Episode?>(initialEp)
    }

    LaunchedEffect(seriesId, targetSeries.seasons.size) {
        if (activePlayingEpisode == null && !effectiveAutoPlayId.isNullOrBlank()) {
            val found = targetSeries.seasons.flatMap { it.episodes }.find { it.id == effectiveAutoPlayId }
            if (found != null) {
                activePlayingEpisode = found
            }
        }
    }

    var currentInitialPositionMs by rememberSaveable(seriesId) {
        mutableLongStateOf(if (effectiveAutoPlayId != null) initialPositionMs else 0L)
    }

    SeriesDetailScreen(
        series = targetSeries,
        viewModel = viewModel,
        activePlayingEpisode = activePlayingEpisode,
        initialPositionMs = currentInitialPositionMs,
        autoPlayEpisodeId = effectiveAutoPlayId,
        onEpisodeSelected = { ep ->
            if (activePlayingEpisode?.id == ep.id) return@SeriesDetailScreen
            currentInitialPositionMs = 0L
            activePlayingEpisode = ep
        },
        onEpisodeSelectedWithPosition = { ep, seekPos ->
            if (activePlayingEpisode?.id == ep.id) return@SeriesDetailScreen
            currentInitialPositionMs = seekPos
            activePlayingEpisode = ep
        },
        onClosePlayer = {
            activePlayingEpisode = null
            viewModel.clearActiveEpisode()
            viewModel.stopAndReleaseCurrentPlayer()
            onNavigateBack()
        },
        onBackClick = {
            activePlayingEpisode = null
            viewModel.clearActiveEpisode()
            viewModel.stopAndReleaseCurrentPlayer()
            onNavigateBack()
        },
        isLoadingEpisodes = isLoadingEpisodes,
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Reusable shimmer brush modifier that creates a smooth, dark-theme-friendly
 * infinite shimmer animation using [rememberInfiniteTransition] and [Brush.linearGradient].
 *
 * Base dark surface color: Color(0xFF161B22)
 * Highlight shimmer sweep color: Color(0xFF262F3D)
 * Cycle duration: 1100ms with LinearEasing
 */
@Composable
fun Modifier.shimmerEffect(): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer_transition")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    val shimmerColors = listOf(
        Color(0xFF161B22),
        Color(0xFF262F3D),
        Color(0xFF161B22)
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 600f, translateAnim - 600f),
        end = Offset(translateAnim, translateAnim)
    )

    return this.background(brush)
}

/**
 * Up Next banner shimmer skeleton matching the compact loaded Up Next card geometry.
 */
@Composable
fun UpNextCardSkeleton(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceDefault,
        border = BorderStroke(1.dp, SurfaceBorder),
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(11.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .shimmerEffect()
            )
        }
    }
}

/**
 * Season selector row shimmer skeleton displaying 4 rounded pill boxes
 * (width = 86.dp, height = 34.dp, RoundedCornerShape(20.dp)) with 8.dp horizontal spacing.
 */
@Composable
fun SeasonSelectorRowSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(4) {
            Box(
                modifier = Modifier
                    .width(86.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .shimmerEffect()
            )
        }
    }
}

/**
 * Single episode item shimmer card matching the exact dimensions of EpisodeItemRow:
 * fillMaxWidth, RoundedCornerShape(12.dp), padding(horizontal = 16.dp, vertical = 4.dp),
 * 16:9 thumbnail box (118.dp width), middle info bars, and right 34.dp action button.
 */
@Composable
fun EpisodeItemSkeleton(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceDefault,
        border = BorderStroke(1.dp, SurfaceBorder),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: 16:9 thumbnail box
            Box(
                modifier = Modifier
                    .width(118.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .shimmerEffect()
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Middle (weight(1f))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Small bar for E01 index
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(11.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .shimmerEffect()
                )

                // Medium bar for Episode Title
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )

                // Small bar for duration
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(11.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .shimmerEffect()
                )
            }

            // Right: Single 34.dp download circle placeholder
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .shimmerEffect()
            )
        }
    }
}

/**
 * Series episodes skeleton container rendering 5 placeholder cards matching
 * the episode list geometry during asynchronous loading.
 */
@Composable
fun SeriesEpisodesSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(5) {
            EpisodeItemSkeleton()
        }
    }
}

@Composable
fun StaticSeriesBackdropHeader(
    series: Series,
    onBackClick: () -> Unit,
    onStartWatchingClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayArtworkUrl = series.backdropUrl?.ifBlank { null }
        ?: series.posterUrl?.ifBlank { null }
        ?: ""

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable { onStartWatchingClick() }
    ) {
        // 1. Full-Bleed Artwork
        if (displayArtworkUrl.isNotBlank()) {
            coil.compose.AsyncImage(
                model = displayArtworkUrl,
                contentDescription = series.title,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. High-Contrast Gradient Scrim
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

        // 3. Top Action Bar (Back Button)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // 4. Center / Bottom Content: Title + "Start Watching" Button
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
        ) {
            Text(
                text = series.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = onStartWatchingClick,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Start Watching",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Start Watching",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                )
            }
        }
    }
}
