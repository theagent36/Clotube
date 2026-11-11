package com.clotube.app.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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
import kotlinx.coroutines.withContext

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
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var isFullscreen by rememberSaveable(series.id) { mutableStateOf(isLandscape) }

    // Restore portrait orientation and system bars ONLY when leaving SeriesDetailScreen entirely—
    // never when switching between episodes.
    DisposableEffect(Unit) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val allSeriesEpisodes = remember(
        series.id,
        series.seasons.size,
        series.seasons.sumOf { it.episodes.size }
    ) {
        series.seasons.sortedBy { it.seasonNumber }.flatMap { it.episodes }
    }

    fun resolveEpisodeInCatalog(ep: Episode?): Episode? {
        if (ep == null || allSeriesEpisodes.isEmpty()) return null
        return allSeriesEpisodes.firstOrNull { it.id == ep.id }
            ?: allSeriesEpisodes.firstOrNull {
                it.seasonNumber == ep.seasonNumber && it.episodeNumber == ep.episodeNumber
            }
    }

    // Match autoPlayEpisodeId by ID, or SxxExx / title hint for initial season selection
    val initialAutoPlayEpisode = remember(series.id, autoPlayEpisodeId, allSeriesEpisodes.size) {
        if (!autoPlayEpisodeId.isNullOrBlank()) {
            allSeriesEpisodes.firstOrNull { it.id == autoPlayEpisodeId }
                ?: allSeriesEpisodes.firstOrNull {
                    autoPlayEpisodeId.contains("S%02dE%02d".format(it.seasonNumber, it.episodeNumber), ignoreCase = true)
                }
                ?: allSeriesEpisodes.firstOrNull { it.displayTitle.contains(autoPlayEpisodeId, ignoreCase = true) }
                ?: activePlayingEpisode
        } else activePlayingEpisode
    }

    // Unified single source of truth for active playback in SeriesDetailScreen
    // PREVENT RECOMPOSITION RESET: Key strictly to seriesId (never to currentSeasonNumber or activeSeason)
    var currentPlayingEpisode by remember(series.id) {
        mutableStateOf<Episode?>(activePlayingEpisode)
    }
    var initialResumePositionMs by remember(series.id) {
        mutableLongStateOf(initialPositionMs)
    }
    var forceExplicitStartPosition by remember(series.id) {
        mutableStateOf(initialPositionMs > 0L)
    }
    var targetResumeEpisode by remember(series.id) { mutableStateOf<Episode?>(null) }
    var targetResumePosMs by remember(series.id) { mutableLongStateOf(initialPositionMs) }
    var targetDurationMs by remember(series.id) { mutableLongStateOf(0L) }
    var lastWatchedEpisode by remember(series.id) { mutableStateOf<Episode?>(null) }
    var lastWatchedSeasonNumber by remember(series.id) { mutableStateOf<Int?>(null) }
    var hasCheckedLastWatched by remember(series.id) { mutableStateOf(false) }
    var hasHandledInitialAutoPlay by remember(series.id, autoPlayEpisodeId) { mutableStateOf(false) }

    DisposableEffect(series.id) {
        onDispose {
            currentPlayingEpisode = null
        }
    }

    LaunchedEffect(activePlayingEpisode?.id) {
        if (activePlayingEpisode != null && currentPlayingEpisode?.id != activePlayingEpisode.id) {
            currentPlayingEpisode = resolveEpisodeInCatalog(activePlayingEpisode) ?: activePlayingEpisode
        }
    }

    fun startEpisodePlayback(
        targetEpisode: Episode,
        startPosMs: Long = 0L,
        forceStartPos: Boolean = false
    ) {
        val resolvedEpisode = resolveEpisodeInCatalog(targetEpisode)?.takeIf { it.id.isNotBlank() } ?: targetEpisode
        android.util.Log.i(
            "ClotubePlayer",
            "--> startEpisodePlayback INVOKED: id=${resolvedEpisode.id}, title=${resolvedEpisode.displayTitle}, startPosMs=$startPosMs"
        )
        if (resolvedEpisode.id.isBlank()) {
            android.util.Log.e("ClotubePlayer", "--> startEpisodePlayback ERROR: episode ID is blank!")
            return
        }
        initialResumePositionMs = startPosMs
        forceExplicitStartPosition = forceStartPos
        currentPlayingEpisode = resolvedEpisode
        onEpisodeSelectedWithPosition(resolvedEpisode, startPosMs)
        onEpisodeSelected(resolvedEpisode)
        viewModel.selectEpisode(resolvedEpisode)
    }

    // Resolve autoPlayEpisodeId once catalog episodes are hydrated (handles frame-0 hydration lag)
    LaunchedEffect(series.id, autoPlayEpisodeId, allSeriesEpisodes.size) {
        if (!hasHandledInitialAutoPlay && !autoPlayEpisodeId.isNullOrBlank() && allSeriesEpisodes.isNotEmpty()) {
            val matchedEp = allSeriesEpisodes.firstOrNull { it.id == autoPlayEpisodeId }
                ?: allSeriesEpisodes.firstOrNull {
                    autoPlayEpisodeId.contains("S%02dE%02d".format(it.seasonNumber, it.episodeNumber), ignoreCase = true)
                }
                ?: allSeriesEpisodes.firstOrNull {
                    it.displayTitle.contains(autoPlayEpisodeId, ignoreCase = true)
                }

            if (matchedEp != null) {
                hasHandledInitialAutoPlay = true
                android.util.Log.i("ClotubePlayer", "--> AutoPlay matched episode: ${matchedEp.id} (${matchedEp.displayTitle})")
                startEpisodePlayback(
                    targetEpisode = matchedEp,
                    startPosMs = initialPositionMs,
                    forceStartPos = initialPositionMs > 0L
                )
            } else {
                android.util.Log.w("ClotubePlayer", "--> AutoPlay failed to match ID=$autoPlayEpisodeId among ${allSeriesEpisodes.size} episodes")
            }
        }
    }

    // One-time check for last-watched episode once episodes exist without overriding browsed season
    LaunchedEffect(series.id, allSeriesEpisodes.size) {
        if (!hasCheckedLastWatched && allSeriesEpisodes.isNotEmpty()) {
            val lastWatched = viewModel.getLastWatchedEpisode(series)
            if (lastWatched != null) {
                val resolvedLastWatched = resolveEpisodeInCatalog(lastWatched) ?: lastWatched
                lastWatchedEpisode = resolvedLastWatched
                targetResumeEpisode = resolvedLastWatched
                lastWatchedSeasonNumber = resolvedLastWatched.seasonNumber
                val pos = viewModel.playbackHistoryManager.getResumePosition(resolvedLastWatched.id)
                targetResumePosMs = if (initialPositionMs > 0L && resolvedLastWatched.id == autoPlayEpisodeId) initialPositionMs else pos
                val savedProgress = viewModel.playbackHistoryManager.getProgressSync(resolvedLastWatched.id)
                targetDurationMs = when {
                    resolvedLastWatched.durationMs > 0L -> resolvedLastWatched.durationMs
                    savedProgress != null && savedProgress.durationMs > 0L -> savedProgress.durationMs
                    else -> 0L
                }
            }
            hasCheckedLastWatched = true
        }
    }

    // Hardware BackHandler: If in landscape, revert to portrait without closing player; else navigate back
    BackHandler(enabled = true) {
        if (isLandscape || isFullscreen) {
            isFullscreen = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            onBackClick() // Pops backstack to previous screen (HomeScreen)
        }
    }

    val initialFallbackEpisode = remember(series.id, allSeriesEpisodes) {
        allSeriesEpisodes.firstOrNull()
    }
    var cachedUpNextEpisode by remember(series.id) {
        mutableStateOf<Episode?>(initialFallbackEpisode)
    }

    val upNextEpisode = remember(lastWatchedEpisode, initialFallbackEpisode, allSeriesEpisodes, cachedUpNextEpisode) {
        val candidate = lastWatchedEpisode
            ?: initialFallbackEpisode
            ?: cachedUpNextEpisode
        val resolved = resolveEpisodeInCatalog(candidate) ?: initialFallbackEpisode
        if (resolved != null && resolved != cachedUpNextEpisode) {
            cachedUpNextEpisode = resolved
        }
        resolved
    }

    val playingEp = currentPlayingEpisode ?: activePlayingEpisode
    val currentPlayingIndex = remember(playingEp?.id, allSeriesEpisodes) {
        allSeriesEpisodes.indexOfFirst { it.id == playingEp?.id }
    }
    val prevEpisode = remember(currentPlayingIndex, allSeriesEpisodes) {
        if (currentPlayingIndex > 0) allSeriesEpisodes[currentPlayingIndex - 1] else null
    }
    val nextEpisode = remember(currentPlayingIndex, allSeriesEpisodes) {
        if (currentPlayingIndex != -1 && currentPlayingIndex < allSeriesEpisodes.lastIndex) {
            allSeriesEpisodes[currentPlayingIndex + 1]
        } else null
    }

    // Memoize all callbacks passed into ClotubeVideoPlayer so season browsing or unrelated recompositions
    // NEVER produce unstable lambda references on the player.
    val currentIsLandscape by rememberUpdatedState(isLandscape)
    val currentIsFullscreen by rememberUpdatedState(isFullscreen)
    val currentOnBackClick by rememberUpdatedState(onBackClick)
    val currentStartPlayback by rememberUpdatedState(::startEpisodePlayback)

    val stableOnPlayerCreated = remember(viewModel) {
        { epPlayer: MediaPlayer -> viewModel.registerActivePlayer(epPlayer) }
    }
    val stableOnFullscreenChanged = remember(series.id) {
        { newFs: Boolean -> isFullscreen = newFs }
    }
    val stableOnToggleFullscreen = remember(series.id) {
        { isFullscreen = !isFullscreen }
    }
    val stableOnClosePlayer = remember(series.id, activity) {
        {
            if (currentIsLandscape || currentIsFullscreen) {
                isFullscreen = false
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                currentOnBackClick()
            }
        }
    }
    val stableOnPreviousEpisode: (() -> Unit)? = remember(prevEpisode?.id) {
        prevEpisode?.let { ep ->
            { currentStartPlayback(ep, 0L, true) }
        }
    }
    val stableOnNextEpisode: (() -> Unit)? = remember(nextEpisode?.id) {
        nextEpisode?.let { nextEp ->
            { currentStartPlayback(nextEp, 0L, true) }
        }
    }
    val stableOnBackdropBackClick = remember(series.id, activity, view) {
        {
            view.triggerNavigationHaptic()
            if (currentIsLandscape || currentIsFullscreen) {
                isFullscreen = false
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                currentOnBackClick()
            }
        }
    }
    val stableOnResumeClick = remember(series.id) {
        { ep: Episode, pos: Long ->
            android.util.Log.i("ClotubePlayer", "--> WatchCard SeriesResumeHeaderCard clicked: ep=${ep.id} (${ep.displayTitle}), pos=$pos")
            currentStartPlayback(ep, pos, pos > 0L)
        }
    }
    val stableOnStartWatchingClick = remember(series.id, allSeriesEpisodes, view) {
        {
            view.triggerClickHaptic()
            val firstEp = allSeriesEpisodes.firstOrNull()
            android.util.Log.i("ClotubePlayer", "--> StaticSeriesBackdropHeader clicked: firstEp=${firstEp?.id}")
            if (firstEp != null) {
                currentStartPlayback(firstEp, 0L, false)
            }
        }
    }
    val stableOnEpisodeClicked = remember(series.id) {
        { ep: Episode ->
            currentStartPlayback(ep, 0L, false)
        }
    }

    val resolvedWatchCardEpisode = remember(
        targetResumeEpisode,
        upNextEpisode,
        lastWatchedEpisode,
        allSeriesEpisodes
    ) {
        resolveEpisodeInCatalog(targetResumeEpisode ?: upNextEpisode ?: lastWatchedEpisode)
            ?: allSeriesEpisodes.firstOrNull()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasDeep)
    ) {
        // ========================================================
        // 1. TOP VIDEO PLAYER / BACKDROP BANNER (Isolated from season browsing)
        // ========================================================
        SeriesTopPlayerViewport(
            series = series,
            viewModel = viewModel,
            currentPlaying = playingEp,
            isLandscape = isLandscape,
            isFullscreen = isFullscreen,
            initialResumePositionMs = initialResumePositionMs,
            forceExplicitStartPosition = forceExplicitStartPosition,
            nextEpisode = nextEpisode,
            fallbackResumeEpisode = resolvedWatchCardEpisode,
            targetResumePosMs = targetResumePosMs,
            targetDurationMs = targetDurationMs,
            onFullscreenChanged = stableOnFullscreenChanged,
            onPlayerCreated = stableOnPlayerCreated,
            onPreviousEpisode = stableOnPreviousEpisode,
            onNextEpisode = stableOnNextEpisode,
            onClosePlayer = stableOnClosePlayer,
            onToggleFullscreen = stableOnToggleFullscreen,
            onBackdropBackClick = stableOnBackdropBackClick,
            onResumeClick = stableOnResumeClick,
            onStartWatchingClick = stableOnStartWatchingClick
        )

        // ========================================================
        // 2. SCROLLABLE CONTENT WITH PINNED SEASON HEADER (Isolated child scope)
        // ========================================================
        if (!isLandscape || playingEp == null) {
            SeriesEpisodesSection(
                series = series,
                viewModel = viewModel,
                activeEpisodeId = playingEp?.id,
                activePlayingEpisodeId = activePlayingEpisode?.id,
                autoPlayEpisodeId = autoPlayEpisodeId,
                initialAutoPlayEpisode = initialAutoPlayEpisode,
                initialLastWatchedSeasonNumber = lastWatchedSeasonNumber,
                allSeriesEpisodes = allSeriesEpisodes,
                upNextEpisode = upNextEpisode,
                isLoadingEpisodes = isLoadingEpisodes,
                onEpisodePlaybackRequested = stableOnEpisodeClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun SeriesTopPlayerViewport(
    series: Series,
    viewModel: VideoViewModel,
    currentPlaying: Episode?,
    isLandscape: Boolean,
    isFullscreen: Boolean,
    initialResumePositionMs: Long,
    forceExplicitStartPosition: Boolean,
    nextEpisode: Episode?,
    fallbackResumeEpisode: Episode?,
    targetResumePosMs: Long,
    targetDurationMs: Long,
    onFullscreenChanged: (Boolean) -> Unit,
    onPlayerCreated: (MediaPlayer) -> Unit,
    onPreviousEpisode: (() -> Unit)?,
    onNextEpisode: (() -> Unit)?,
    onClosePlayer: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onBackdropBackClick: () -> Unit,
    onResumeClick: (Episode, Long) -> Unit,
    onStartWatchingClick: () -> Unit
) {
    Box(
        modifier = if (isLandscape) {
            if (currentPlaying != null) {
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
        val playingEp = currentPlaying
        if (playingEp != null) {
            android.util.Log.i("ClotubePlayer", "--> Top Viewport composing ClotubeVideoPlayer for ep=${playingEp.id} (${playingEp.displayTitle})")
            ClotubeVideoPlayer(
                episode = playingEp,
                viewModel = viewModel,
                seriesId = series.id,
                initialPositionMs = initialResumePositionMs,
                startPositionMs = initialResumePositionMs,
                forceExplicitStartPosition = forceExplicitStartPosition,
                nextEpisode = nextEpisode,
                isFullscreen = isFullscreen,
                onFullscreenChanged = onFullscreenChanged,
                onPlayerCreated = onPlayerCreated,
                onPreviousEpisode = onPreviousEpisode,
                onNextEpisode = onNextEpisode,
                onClosePlayer = onClosePlayer,
                onToggleFullscreen = onToggleFullscreen,
                modifier = Modifier.fillMaxSize()
            )
        } else if (fallbackResumeEpisode != null) {
            SeriesResumeHeaderCard(
                series = series,
                episode = fallbackResumeEpisode,
                currentPositionMs = targetResumePosMs,
                durationMs = targetDurationMs,
                onBackClick = onBackdropBackClick,
                onResumeClick = onResumeClick
            )
        } else {
            StaticSeriesBackdropHeader(
                series = series,
                onBackClick = onBackdropBackClick,
                onStartWatchingClick = onStartWatchingClick
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SeriesEpisodesSection(
    series: Series,
    viewModel: VideoViewModel,
    activeEpisodeId: String?,
    activePlayingEpisodeId: String?,
    autoPlayEpisodeId: String?,
    initialAutoPlayEpisode: Episode?,
    initialLastWatchedSeasonNumber: Int?,
    allSeriesEpisodes: List<Episode>,
    upNextEpisode: Episode?,
    isLoadingEpisodes: Boolean,
    onEpisodePlaybackRequested: (Episode) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val seasonRowState = rememberLazyListState()

    val defaultSeasonNumber = remember(series.id) {
        initialAutoPlayEpisode?.seasonNumber
            ?: series.seasons.firstOrNull { it.episodes.isNotEmpty() }?.seasonNumber
            ?: series.seasons.firstOrNull()?.seasonNumber
            ?: 1
    }

    // Scoped strictly to SeriesEpisodesSection so changing seasons NEVER recomposes the top player.
    var currentSeasonNumber by rememberSaveable(series.id) {
        mutableIntStateOf(defaultSeasonNumber)
    }
    var lastScrolledEpisodeId by rememberSaveable(series.id) { mutableStateOf<String?>(null) }
    var lastSyncedSeasonEpisodeId by rememberSaveable(series.id) { mutableStateOf<String?>(null) }
    var hasAlignedToLastWatched by rememberSaveable(series.id) { mutableStateOf(false) }

    LaunchedEffect(initialLastWatchedSeasonNumber) {
        if (!hasAlignedToLastWatched &&
            initialLastWatchedSeasonNumber != null &&
            activeEpisodeId == null &&
            activePlayingEpisodeId == null &&
            lastSyncedSeasonEpisodeId == null
        ) {
            hasAlignedToLastWatched = true
            if (currentSeasonNumber != initialLastWatchedSeasonNumber) {
                currentSeasonNumber = initialLastWatchedSeasonNumber
            }
        }
    }

    LaunchedEffect(currentSeasonNumber) {
        val seasonIdx = series.seasons.indexOfFirst { it.seasonNumber == currentSeasonNumber }
        if (seasonIdx != -1) {
            val layoutInfo = seasonRowState.layoutInfo
            val targetInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == seasonIdx }
            val isFullyVisible = targetInfo != null &&
                targetInfo.offset >= layoutInfo.viewportStartOffset &&
                (targetInfo.offset + targetInfo.size) <= layoutInfo.viewportEndOffset
            if (!isFullyVisible) {
                seasonRowState.animateScrollToItem(seasonIdx)
            }
        }
    }

    val ongoingEpisodeId = activeEpisodeId ?: activePlayingEpisodeId ?: autoPlayEpisodeId
    val ongoingEpisode = remember(ongoingEpisodeId, allSeriesEpisodes) {
        ongoingEpisodeId?.let { targetId ->
            allSeriesEpisodes.firstOrNull { it.id == targetId }
                ?: allSeriesEpisodes.firstOrNull { it.displayTitle.contains(targetId, ignoreCase = true) }
        }
    }

    LaunchedEffect(series.id, ongoingEpisode?.id) {
        val targetEpisode = ongoingEpisode
            ?: viewModel.getLastWatchedEpisode(series)
            ?: allSeriesEpisodes.firstOrNull()

        if (targetEpisode == null) return@LaunchedEffect

        // Align active season tab ONLY when the target/playing episode ID actually changes
        if (lastSyncedSeasonEpisodeId != targetEpisode.id) {
            lastSyncedSeasonEpisodeId = targetEpisode.id
            if (currentSeasonNumber != targetEpisode.seasonNumber) {
                currentSeasonNumber = targetEpisode.seasonNumber
            }
        }

        // Prevent redundant auto-scrolls for the same episode
        if (lastScrolledEpisodeId == targetEpisode.id) return@LaunchedEffect

        // Wait for the season episodes to compose in layout, scroll once
        snapshotFlow {
            val season = series.seasons.firstOrNull { it.seasonNumber == currentSeasonNumber }
            val episodes = season?.episodes ?: emptyList()
            val epIdx = episodes.indexOfFirst { it.id == targetEpisode.id }
            episodes.isNotEmpty() to epIdx
        }.firstOrNull { (hasEpisodes, epIndex) -> hasEpisodes && epIndex != -1 }?.let { (_, epIndex) ->
            delay(120L)

            if (epIndex <= 0) {
                listState.animateScrollToItem(
                    index = 1,
                    scrollOffset = 0
                )
            } else {
                val precedingCardOffsetPx = with(density) { 34.dp.roundToPx() }
                listState.animateScrollToItem(
                    index = 1 + epIndex,
                    scrollOffset = precedingCardOffsetPx
                )
            }

            lastScrolledEpisodeId = targetEpisode.id
        }
    }

    LaunchedEffect(series.id, series.seasons.size) {
        if (series.seasons.firstOrNull { it.seasonNumber == currentSeasonNumber }?.episodes.isNullOrEmpty()) {
            val seasonWithEpisodes = series.seasons.firstOrNull { it.episodes.isNotEmpty() }?.seasonNumber
            if (seasonWithEpisodes != null && seasonWithEpisodes != currentSeasonNumber) {
                currentSeasonNumber = seasonWithEpisodes
            }
        }
    }

    val activeSeason by remember(series, currentSeasonNumber) {
        derivedStateOf {
            series.seasons.firstOrNull { it.seasonNumber == currentSeasonNumber }
                ?: series.seasons.firstOrNull()
        }
    }

    // Hoist watch progress and download status to ONE screen-level Flow collection each
    val allWatchProgress by viewModel.playbackHistoryManager
        .getAllProgress()
        .collectAsState(initial = emptyMap())
    val allDownloadRecords by viewModel.downloadRepository
        .getAllDownloadRecords()
        .collectAsState(initial = emptyMap())

    val currentUserEmail = remember(series.id) {
        viewModel.getSelectedGoogleAccountName().orEmpty().ifBlank {
            viewModel.uiState.value.userEmail.orEmpty()
        }
    }

    var localWatchedIds by remember(series.id, currentUserEmail) {
        mutableStateOf<Set<String>>(emptySet())
    }
    LaunchedEffect(series.id, currentUserEmail, allSeriesEpisodes.size) {
        if (currentUserEmail.isNotBlank()) {
            localWatchedIds = withContext(Dispatchers.Default) {
                allSeriesEpisodes.asSequence()
                    .filter { com.clotube.app.data.AndroidWatchHistoryManager.isEpisodeWatched(context, currentUserEmail, it.id) }
                    .map { it.id }
                    .toSet()
            }
        }
    }

    val completedSeasonNumbers = remember(series.seasons, allWatchProgress, localWatchedIds) {
        series.seasons.asSequence()
            .filter { season ->
                season.episodes.isNotEmpty() && season.episodes.all { ep ->
                    allWatchProgress[ep.id]?.isWatched == true || localWatchedIds.contains(ep.id)
                }
            }
            .map { it.seasonNumber }
            .toSet()
    }

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
        modifier = modifier
    ) {
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
                    val upNextToDisplay = remember(upNextEpisode, allSeriesEpisodes) {
                        upNextEpisode?.let { cand ->
                            allSeriesEpisodes.firstOrNull { it.id == cand.id }
                                ?: allSeriesEpisodes.firstOrNull {
                                    it.seasonNumber == cand.seasonNumber && it.episodeNumber == cand.episodeNumber
                                }
                        } ?: allSeriesEpisodes.firstOrNull()
                    }
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
                                    android.util.Log.i("ClotubePlayer", "--> UpNext WatchCard row clicked: ${upNextToDisplay.id} (${upNextToDisplay.displayTitle})")
                                    onEpisodePlaybackRequested(upNextToDisplay)
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
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(AccentBlue)
                                        .clickable {
                                            android.util.Log.i("ClotubePlayer", "--> UpNext WatchCard play button clicked: ${upNextToDisplay.id} (${upNextToDisplay.displayTitle})")
                                            onEpisodePlaybackRequested(upNextToDisplay)
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
                                    SeasonTabChip(
                                        season = season,
                                        isSelected = (activeSeason?.seasonNumber == season.seasonNumber),
                                        isCompleted = completedSeasonNumbers.contains(season.seasonNumber),
                                        onSelect = {
                                            if (currentSeasonNumber != season.seasonNumber) {
                                                currentSeasonNumber = season.seasonNumber
                                                if (listState.firstVisibleItemIndex > 2) {
                                                    coroutineScope.launch {
                                                        listState.scrollToItem(index = 1, scrollOffset = 0)
                                                    }
                                                }
                                            }
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
            val currentSeason = activeSeason
            if (isLoadingEpisodes) {
                item(key = "series_episodes_skeleton") {
                    SeriesEpisodesSkeleton()
                }
            } else if (currentSeason == null || currentSeason.episodes.isEmpty()) {
                item(key = "empty_season_message") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp, bottom = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No episodes available in Season ${currentSeason?.seasonNumber ?: currentSeasonNumber}",
                            color = Color(0xFF6B7A90),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                items(
                    items = currentSeason.episodes,
                    key = { episode -> episode.id },
                    contentType = { "episode_row" }
                ) { episode ->
                    val downloadRecord = allDownloadRecords[episode.id]
                    val prog = allWatchProgress[episode.id]
                    val isWatched = if (prog != null) prog.isWatched else localWatchedIds.contains(episode.id)
                    val watchProgressFraction = prog?.progressFraction ?: if (isWatched) 1.0f else 0f
                    val downloadStatus = downloadRecord?.status
                    val downloadProgress = downloadRecord?.progressFraction ?: 0f
                    val isOngoing = (activeEpisodeId == episode.id)

                    EpisodeItemRow(
                        episode = episode,
                        seriesId = series.id,
                        seriesTitle = series.title,
                        isPlayingThis = isOngoing,
                        onEpisodeClick = {
                            android.util.Log.i("ClotubePlayer", "--> EpisodeItemRow clicked: ${episode.id} (${episode.displayTitle})")
                            view.triggerClickHaptic()
                            onEpisodePlaybackRequested(episode)
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
                                localWatchedIds = localWatchedIds - episode.id
                                viewModel.markEpisodeUnwatched(series, episode)
                            } else {
                                localWatchedIds = localWatchedIds + episode.id
                                viewModel.markEpisodeWatched(series, episode)
                            }
                        },
                        isWatched = isWatched,
                        watchProgressFraction = watchProgressFraction,
                        downloadStatus = downloadStatus,
                        downloadProgress = downloadProgress,
                        downloadRecord = downloadRecord,
                        isDownloading = downloadStatus == DownloadStatus.DOWNLOADING || downloadStatus == DownloadStatus.QUEUED,
                        isDownloaded = downloadStatus == DownloadStatus.COMPLETED,
                        playbackHistoryManager = null,
                        useHoistedState = true,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
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
    val seriesSliceFlow = remember(seriesId, effectiveAutoPlayId) {
        viewModel.seriesList
            .map { list ->
                val combined = (list + viewModel.uiState.value.tvSeriesList).distinctBy { it.id }
                combined.firstOrNull { it.id == seriesId && seriesId.isNotEmpty() }
                    ?: if (!effectiveAutoPlayId.isNullOrBlank()) {
                        combined.find { s -> s.seasons.any { it.episodes.any { ep -> ep.id == effectiveAutoPlayId } } }
                    } else null
            }
            .distinctUntilChanged { old, new ->
                if (old === new) return@distinctUntilChanged true
                if (old == null || new == null) return@distinctUntilChanged false
                old.id == new.id &&
                    old.title == new.title &&
                    old.posterUrl == new.posterUrl &&
                    old.backdropUrl == new.backdropUrl &&
                    old.seasons.size == new.seasons.size &&
                    old.seasons.zip(new.seasons).all { (a, b) ->
                        a.seasonNumber == b.seasonNumber &&
                            a.episodes.size == b.episodes.size &&
                            a.episodes.zip(b.episodes).all { (ea, eb) ->
                                ea.id == eb.id && ea.displayTitle == eb.displayTitle
                            }
                    }
            }
    }
    val resolvedSeries by seriesSliceFlow.collectAsState(initial = initialSeries)

    val resolvedFallbackSeriesId = remember(seriesId, effectiveAutoPlayId) {
        val combined = (viewModel.seriesList.value + viewModel.uiState.value.tvSeriesList).distinctBy { it.id }
        val directMatch = if (seriesId.isNotBlank() && combined.any { it.id == seriesId }) seriesId else null
        val byEpisodeMatch = if (!effectiveAutoPlayId.isNullOrBlank()) {
            combined.firstOrNull { s -> s.seasons.any { sn -> sn.episodes.any { it.id == effectiveAutoPlayId } } }?.id
        } else null
        directMatch
            ?: byEpisodeMatch
            ?: seriesId.ifBlank { null }
            ?: effectiveAutoPlayId?.let { viewModel.getSeriesIdForEpisode(it) }
            ?: "series_fallback"
    }

    val fallbackSeries = remember(resolvedFallbackSeriesId) {
        val sTitle = viewModel.getSeriesById(resolvedFallbackSeriesId)?.title ?: "TV Series"
        TvSeries(
            id = resolvedFallbackSeriesId,
            title = sTitle,
            posterUrl = "",
            backdropUrl = "",
            seasons = emptyList()
        )
    }

    val targetSeries by remember(seriesId, initialSeries, fallbackSeries) {
        derivedStateOf { resolvedSeries ?: initialSeries ?: fallbackSeries }
    }

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

    // Strictly synchronized via inner SeriesDetailScreen.startEpisodePlayback (single source of truth)
    var activePlayingEpisode by remember(seriesId) {
        mutableStateOf<Episode?>(null)
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
            currentInitialPositionMs = 0L
            activePlayingEpisode = ep
        },
        onEpisodeSelectedWithPosition = { ep, seekPos ->
            currentInitialPositionMs = seekPos
            activePlayingEpisode = ep
        },
        onClosePlayer = {
            viewModel.pauseActivePlayer()
            activePlayingEpisode = null
            viewModel.clearActiveEpisode()
            onNavigateBack()
        },
        onBackClick = {
            viewModel.pauseActivePlayer()
            activePlayingEpisode = null
            viewModel.clearActiveEpisode()
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
