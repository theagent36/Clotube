package com.clotube.app.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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

    var hasAutoScrolled by remember(series.id) { mutableStateOf(false) }

    val activeSeason = series.seasons.firstOrNull { it.seasonNumber == currentSeasonNumber }
        ?: series.seasons.firstOrNull()

    // 3. One-time check for last-watched episode without blocking initial render
    LaunchedEffect(series.id) {
        if (!hasAutoScrolled) {
            val lastWatched = viewModel.getLastWatchedEpisode(series)
            if (lastWatched != null) {
                if (currentSeasonNumber != lastWatched.seasonNumber) {
                    currentSeasonNumber = lastWatched.seasonNumber
                }

                snapshotFlow { activeSeason?.seasonNumber }
                    .collect { seasonNum ->
                        if (seasonNum == lastWatched.seasonNumber && activeSeason != null) {
                            val epIndex = activeSeason.episodes.indexOfFirst { it.id == lastWatched.id }
                            if (epIndex != -1) {
                                val targetIndex = if (epIndex <= 0) 1 else 1 + epIndex
                                listState.animateScrollToItem(
                                    index = targetIndex,
                                    scrollOffset = 0
                                )
                                hasAutoScrolled = true
                            }
                        }
                    }
            } else {
                hasAutoScrolled = true
            }
        }
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
            modifier = if (isLandscape && activePlayingEpisode != null) {
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            } else {
                Modifier
                    .fillMaxWidth()
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
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBackClick,
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

            // ITEM 0: Series Metadata & "Up Next" Card
            item(key = "series_metadata_section") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = series.title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${series.genre} • ${series.seasons.size} Seasons",
                        color = Color(0xFF8899A6),
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // "Up Next" billboard card
                    val nextEpisode = activeSeason?.episodes?.firstOrNull()
                    if (nextEpisode != null) {
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
                                                text = "S${nextEpisode.seasonNumber.toString().padStart(2, '0')}:E${nextEpisode.episodeNumber.toString().padStart(2, '0')}",
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
                                        text = nextEpisode.displayTitle,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = "${nextEpisode.formattedDuration} • ${nextEpisode.fileSize}",
                                        color = Color(0xFF6B7A90),
                                        fontSize = 11.sp
                                    )
                                }

                                IconButton(
                                    onClick = { onEpisodeSelected(nextEpisode) },
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
                    }
                }
            }

            // STICKY HEADER: Season Selector
            stickyHeader(key = "sticky_season_selector_bar") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF07090E),
                    shadowElevation = 6.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
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
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(index = 1, scrollOffset = 0)
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

                        HorizontalDivider(color = Color(0xFF1A2230), thickness = 1.dp)
                    }
                }
            }

            // EPISODES LIST
            if (activeSeason == null || activeSeason.episodes.isEmpty()) {
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
                items(activeSeason.episodes, key = { it.id }) { episode ->
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

    LaunchedEffect(seriesId) {
        if (currentSeries == null || currentSeries?.seasons.isNullOrEmpty() || currentSeries?.seasons?.all { it.episodes.isEmpty() } == true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val authRepo = com.clotube.app.auth.GoogleAuthRepository(context)
                    val seriesRepo = com.clotube.app.data.SeriesRepository(authRepo)
                    val freshShow = seriesRepo.getShowDetails(seriesId, currentSeries?.title)
                    if (freshShow.seasons.any { it.episodes.isNotEmpty() }) {
                        currentSeries = freshShow
                        viewModel.updateSeriesInCatalog(freshShow)
                        com.clotube.app.viewmodel.saveShowToDisk(context, freshShow)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SeriesDetailScreen", "Failed on-demand episode fetch for $seriesId: ${e.message}")
                }
            }
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
