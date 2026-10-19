package com.clotube.app.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.app.util.triggerNavigationHaptic
import com.clotube.app.model.Episode
import com.clotube.app.model.Series
import com.clotube.app.model.TvSeries
import com.clotube.app.ui.components.ClotubeVideoPlayer
import com.clotube.app.ui.components.EpisodeItemRow
import com.clotube.app.ui.components.SeasonTabChip
import com.clotube.app.viewmodel.VideoViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesDetailScreen(
    series: Series,
    viewModel: VideoViewModel,
    activePlayingEpisode: Episode?,
    onEpisodeSelected: (Episode) -> Unit,
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
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Hardware BackHandler: If in landscape, revert to portrait without closing player; else close player or navigate back
    BackHandler {
        if (isLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else if (activePlayingEpisode != null) {
            viewModel.clearActiveEpisode()
            onClosePlayer()
        } else {
            onBackClick()
        }
    }

    // 1. Immediately determine the best initial season containing episodes (prevents opening on Season 2 if empty)
    val defaultSeasonNumber = remember(series.id, series.seasons) {
        series.seasons.firstOrNull { it.episodes.isNotEmpty() }?.seasonNumber
            ?: series.seasons.firstOrNull()?.seasonNumber
            ?: 1
    }

    // 2. Localize selected season state to this series instance
    var currentSeasonNumber by rememberSaveable(series.id) {
        mutableIntStateOf(defaultSeasonNumber)
    }

    LaunchedEffect(series.id, series.seasons) {
        if (series.seasons.firstOrNull { it.seasonNumber == currentSeasonNumber }?.episodes.isNullOrEmpty()) {
            val seasonWithEpisodes = series.seasons.firstOrNull { it.episodes.isNotEmpty() }?.seasonNumber
            if (seasonWithEpisodes != null && seasonWithEpisodes != currentSeasonNumber) {
                currentSeasonNumber = seasonWithEpisodes
            }
        }
    }

    LaunchedEffect(activePlayingEpisode?.seasonNumber) {
        val epSeason = activePlayingEpisode?.seasonNumber
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
        val candidate = (if (lastWatchedEpisode != null && lastWatchedEpisode?.seasonNumber == activeSeason?.seasonNumber) {
            lastWatchedEpisode
        } else {
            activeSeason?.episodes?.firstOrNull()
        }) ?: lastWatchedEpisode
           ?: initialFallbackEpisode
           ?: cachedUpNextEpisode

        if (candidate != null && candidate != cachedUpNextEpisode) {
            cachedUpNextEpisode = candidate
        }
        candidate ?: cachedUpNextEpisode
    }

    val currentEpisodeList = activeSeason?.episodes ?: emptyList()
    val currentIndex = currentEpisodeList.indexOfFirst { it.id == activePlayingEpisode?.id }

    val prevEpisode = if (currentIndex > 0) currentEpisodeList[currentIndex - 1] else null
    val nextEpisode = if (currentIndex != -1 && currentIndex < currentEpisodeList.lastIndex) currentEpisodeList[currentIndex + 1] else null

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090E))
    ) {

        // ========================================================
        // 1. TOP VIDEO PLAYER / BACKDROP BANNER
        // ========================================================
        Box(
            modifier = if (isLandscape) {
                if (activePlayingEpisode != null) {
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
            if (activePlayingEpisode != null) {
                val currentEpisodeList = activeSeason?.episodes ?: emptyList()
                val currentIndex = currentEpisodeList.indexOfFirst { it.id == activePlayingEpisode.id }

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
                    episode = activePlayingEpisode,
                    viewModel = viewModel,
                    nextEpisode = nextEpisode,
                    onPreviousEpisode = prevEpisode?.let { ep -> { onEpisodeSelected(ep) } },
                    onNextEpisode = nextEpisode?.let { ep -> { onEpisodeSelected(ep) } },
                    onClosePlayer = {
                        viewModel.clearActiveEpisode()
                        onClosePlayer()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (!series.posterUrl.isNullOrBlank()) {
                        coil.compose.AsyncImage(
                            model = series.posterUrl,
                            contentDescription = series.title,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x66000000))
                    )

                    IconButton(
                        onClick = {
                            val targetEpisode = activeSeason?.episodes?.firstOrNull()
                            if (targetEpisode != null) {
                                onEpisodeSelected(targetEpisode)
                            }
                        },
                        modifier = Modifier
                            .size(60.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(0xCC0E131E))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "Play Series",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                view.triggerNavigationHaptic()
                                if (isLandscape) {
                                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                } else {
                                    onBackClick()
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0x80000000))
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .background(Color(0xCC111D2D), RoundedCornerShape(16.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "• OTT DIRECT STREAM",
                                color = Color(0xFF00E5FF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // ========================================================
        // 2. SCROLLABLE CONTENT WITH PINNED SEASON HEADER
        // ========================================================
        if (!isLandscape || activePlayingEpisode == null) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {

            // ITEM 0: Series Title & Genre Header
            item(key = "series_title_header") {
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
            }

            // ITEM 1: "Up Next" Watch Card
            item(key = "up_next_watch_card") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                ) {
                    val upNextToDisplay = upNextEpisode
                    if (upNextToDisplay != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF111722),
                            border = BorderStroke(1.dp, Color(0xFF1B2433)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFF162536), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "S${upNextToDisplay.seasonNumber.toString().padStart(2, '0')}:E${upNextToDisplay.episodeNumber.toString().padStart(2, '0')}",
                                                color = Color(0xFF00E5FF),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Text(
                                            text = "Up Next",
                                            color = Color(0xFF8899A6),
                                            fontSize = 11.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = upNextToDisplay.displayTitle,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = "${upNextToDisplay.formattedDuration} • ${upNextToDisplay.fileSize}",
                                        color = Color(0xFF6B7A90),
                                        fontSize = 11.sp
                                    )
                                }

                                IconButton(
                                    onClick = { onEpisodeSelected(upNextToDisplay) },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color(0xFF00E5FF))
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = "Play Next",
                                        tint = Color(0xFF07090E),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    } else if (isLoadingEpisodes) {
                        UpNextCardSkeleton()
                    }
                }
            }

            // ITEM 2: Season Selector Row
            item(key = "season_selector_row") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF07090E),
                    shadowElevation = 6.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (isLoadingEpisodes && (series.seasons.isEmpty() || series.seasons.all { it.episodes.isEmpty() })) {
                            SeasonSelectorRowSkeleton()
                        } else {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
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

                        HorizontalDivider(color = Color(0xFF1A2230), thickness = 1.dp)
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
                    EpisodeItemRow(
                        episode = episode,
                        seriesId = series.id,
                        seriesTitle = series.title,
                        isPlayingThis = (activePlayingEpisode?.id == episode.id),
                        onEpisodeClick = { onEpisodeSelected(episode) },
                        onStartDownload = { /* handle download */ },
                        onCancelDownload = { /* handle cancel */ },
                        isDownloading = false,
                        isDownloaded = false,
                        viewModel = viewModel,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
        }
    }
}

/**
 * Backward-compatible navigation overload for NavHost entry.
 */
@Composable
fun SeriesDetailScreen(
    seriesId: String,
    initialEpisodeId: String? = null,
    initialPositionMs: Long = 0L,
    initialSeries: TvSeries? = null,
    accessToken: String? = null,
    onNavigateBack: () -> Unit,
    onOpenFullscreen: (fileId: String, title: String, seriesId: String, episodeQueue: List<Episode>) -> Unit = { _, _, _, _ -> },
    onRegisterActivePlayer: ((org.videolan.libvlc.MediaPlayer?) -> Unit)? = null,
    viewModel: VideoViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val seriesList by viewModel.seriesList.collectAsState()

    val cachedSeries = remember(seriesId, seriesList, uiState.tvSeriesList) {
        val diskShow = com.clotube.app.viewmodel.loadCatalogFromDisk(context).find { it.id == seriesId && it.seasons.any { s -> s.episodes.isNotEmpty() } }
        (initialSeries?.takeIf { it.seasons.any { s -> s.episodes.isNotEmpty() } })
            ?: seriesList.find { it.id == seriesId && it.seasons.any { s -> s.episodes.isNotEmpty() } }
            ?: uiState.tvSeriesList.find { it.id == seriesId && it.seasons.any { s -> s.episodes.isNotEmpty() } }
            ?: viewModel.getSeriesById(seriesId)?.takeIf { it.seasons.any { s -> s.episodes.isNotEmpty() } }
            ?: diskShow
            ?: initialSeries
            ?: seriesList.find { it.id == seriesId }
            ?: uiState.tvSeriesList.find { it.id == seriesId }
            ?: viewModel.getSeriesById(seriesId)
            ?: com.clotube.app.viewmodel.loadCatalogFromDisk(context).find { it.id == seriesId }
    }

    var currentSeries by remember(seriesId) { mutableStateOf(cachedSeries) }

    val hasLoadedEpisodes = remember(cachedSeries) {
        cachedSeries?.seasons?.any { it.episodes.isNotEmpty() } == true
    }

    var isLoadingEpisodes by remember(seriesId) {
        mutableStateOf(!hasLoadedEpisodes)
    }

    LaunchedEffect(currentSeries) {
        if (currentSeries?.seasons?.any { it.episodes.isNotEmpty() } == true) {
            isLoadingEpisodes = false
        }
    }

    LaunchedEffect(seriesId) {
        val needsFetch = currentSeries == null ||
            currentSeries?.seasons.isNullOrEmpty() ||
            currentSeries?.seasons?.all { it.episodes.isEmpty() } == true

        if (needsFetch) {
            isLoadingEpisodes = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val authRepo = com.clotube.app.auth.GoogleAuthRepository(context)
                    val seriesRepo = com.clotube.app.data.SeriesRepository(authRepo)
                    val freshShow = seriesRepo.getShowDetails(seriesId, currentSeries?.title)
                    if (freshShow.seasons.any { it.episodes.isNotEmpty() }) {
                        currentSeries = freshShow
                        viewModel.updateSeriesInCatalog(freshShow)
                        com.clotube.app.viewmodel.saveShowToDisk(context, freshShow)
                    } else if (freshShow.seasons.isNotEmpty()) {
                        currentSeries = freshShow
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

    val targetSeries = currentSeries ?: cachedSeries

    var activePlayingEpisode by rememberSaveable(seriesId, initialEpisodeId) {
        mutableStateOf<Episode?>(
            if (!initialEpisodeId.isNullOrBlank() && targetSeries != null) {
                targetSeries.seasons.flatMap { it.episodes }.find { it.id == initialEpisodeId }
            } else null
        )
    }

    if (targetSeries != null) {
        SeriesDetailScreen(
            series = targetSeries,
            viewModel = viewModel,
            activePlayingEpisode = activePlayingEpisode,
            onEpisodeSelected = { ep ->
                activePlayingEpisode = ep
                onRegisterActivePlayer?.invoke(viewModel.mediaPlayer)
            },
            onClosePlayer = {
                viewModel.clearActiveEpisode()
                activePlayingEpisode = null
            },
            onBackClick = {
                if (activePlayingEpisode != null) {
                    viewModel.clearActiveEpisode()
                    activePlayingEpisode = null
                } else {
                    onNavigateBack()
                }
            },
            isLoadingEpisodes = isLoadingEpisodes,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF07090E)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color(0xFF00E5FF))
        }
    }
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
 * Up Next banner shimmer skeleton matching the loaded Up Next card geometry.
 * Height ~88.dp, RoundedCornerShape(16.dp), left placeholder bars for badge/title/metadata,
 * and a 48.dp circular shimmer placeholder on the right for the cyan Play button.
 */
@Composable
fun UpNextCardSkeleton(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF111722),
        border = BorderStroke(1.dp, Color(0xFF1B2433)),
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .shimmerEffect()
                    )
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .shimmerEffect()
                    )
                }
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .shimmerEffect()
            )
        }
    }
}

/**
 * Season selector row shimmer skeleton displaying 4 rounded pill boxes
 * (width = 105.dp, height = 38.dp, RoundedCornerShape(10.dp)) with 8.dp horizontal spacing.
 */
@Composable
fun SeasonSelectorRowSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(4) {
            Box(
                modifier = Modifier
                    .width(105.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .shimmerEffect()
            )
        }
    }
}

/**
 * Single episode item shimmer card matching the exact dimensions of EpisodeItemRow:
 * fillMaxWidth, RoundedCornerShape(14.dp), padding(horizontal = 16.dp, vertical = 6.dp),
 * 16:9 thumbnail box (130.dp x 76.dp), middle info bars, and two 32.dp action squares.
 */
@Composable
fun EpisodeItemSkeleton(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF131822),
        border = BorderStroke(1.dp, Color(0xFF1E2638)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: 16:9 thumbnail box
            Box(
                modifier = Modifier
                    .width(130.dp)
                    .height(76.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .shimmerEffect()
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Middle (weight(1f))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Small bar for S01:E01 code
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(12.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )

                // Medium bar for Episode Title
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )

                // Two small inline bars for file size and 1080p FHD badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .shimmerEffect()
                    )
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .shimmerEffect()
                    )
                }
            }

            // Right: Two 32.dp rounded square shimmer placeholders (RoundedCornerShape(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .shimmerEffect()
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .shimmerEffect()
                )
            }
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
