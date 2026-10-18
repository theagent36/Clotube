package com.clotube.app.navigation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.clotube.app.ui.screens.ConnectDriveScreen
import com.clotube.app.ui.screens.HomeScreen
import com.clotube.app.ui.screens.MediaLibraryScreen
import com.clotube.app.ui.screens.NativeVideoPlayerScreen
import com.clotube.app.ui.screens.RecentScreen
import com.clotube.app.ui.screens.SeriesDetailScreen
import com.clotube.app.ui.screens.SettingsScreen
import com.clotube.app.data.WatchHistoryItem
import com.clotube.app.viewmodel.VideoViewModel

object MaterialSharedZAxisMotion {
    private const val DURATION_MOTION = 300
    private const val DURATION_FADE_IN = 210
    private const val DELAY_FADE_IN = 90
    private const val DURATION_FADE_OUT = 90

    val enterTransition: EnterTransition =
        scaleIn(
            initialScale = 0.8f,
            animationSpec = tween(durationMillis = DURATION_MOTION, easing = FastOutSlowInEasing)
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = DURATION_FADE_IN,
                delayMillis = DELAY_FADE_IN,
                easing = LinearOutSlowInEasing
            )
        )

    val exitTransition: ExitTransition =
        scaleOut(
            targetScale = 1.1f,
            animationSpec = tween(durationMillis = DURATION_MOTION, easing = FastOutSlowInEasing)
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = DURATION_FADE_OUT,
                easing = FastOutLinearInEasing
            )
        )

    val popEnterTransition: EnterTransition =
        scaleIn(
            initialScale = 1.1f,
            animationSpec = tween(durationMillis = DURATION_MOTION, easing = FastOutSlowInEasing)
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = DURATION_FADE_IN,
                delayMillis = DELAY_FADE_IN,
                easing = LinearOutSlowInEasing
            )
        )

    val popExitTransition: ExitTransition =
        scaleOut(
            targetScale = 0.8f,
            animationSpec = tween(durationMillis = DURATION_MOTION, easing = FastOutSlowInEasing)
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = DURATION_FADE_OUT,
                easing = FastOutLinearInEasing
            )
        )
}

object NavRoutes {
    const val HOME = "home"
    const val RECENT = "recent"
    const val MEDIA_LIBRARY = "media_library"
    const val PLAYER = "player/{fileId}/{title}?seriesId={seriesId}"
    const val SERIES_DETAIL = "series/{seriesId}?episodeId={episodeId}&positionMs={positionMs}"
    const val SETTINGS = "settings"

    fun playerRoute(fileId: String, title: String, seriesId: String? = null): String {
        val encodedTitle = Uri.encode(title)
        return if (!seriesId.isNullOrBlank()) {
            val encodedSeriesId = Uri.encode(seriesId)
            "player/$fileId/$encodedTitle?seriesId=$encodedSeriesId"
        } else {
            "player/$fileId/$encodedTitle?seriesId="
        }
    }

    fun seriesDetailRoute(seriesId: String, episodeId: String? = null, positionMs: Long = 0L): String {
        return if (!episodeId.isNullOrBlank()) {
            val encodedEp = Uri.encode(episodeId)
            "series/$seriesId?episodeId=$encodedEp&positionMs=$positionMs"
        } else {
            "series/$seriesId?episodeId=&positionMs=0"
        }
    }
}

sealed class Screen(val route: String) {
    object Home : Screen(NavRoutes.HOME)
    object Recent : Screen(NavRoutes.RECENT)
    object MediaLibrary : Screen(NavRoutes.MEDIA_LIBRARY)
    object SeriesDetail : Screen(NavRoutes.SERIES_DETAIL)
    object Player : Screen(NavRoutes.PLAYER)
    object Settings : Screen(NavRoutes.SETTINGS)
}

@Composable
fun ClotubeNavHost(
    videoViewModel: VideoViewModel = viewModel(),
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val uiState by videoViewModel.uiState.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is com.clotube.app.MainActivity) return@remember ctx
            ctx = ctx.baseContext
        }
        ctx as? com.clotube.app.MainActivity
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (activity != null) {
            activity.handleGoogleSignInResult(result.data)
        } else {
            videoViewModel.handleSignInResult(result.data)
        }
    }

    if (!uiState.isSignedIn) {
        ConnectDriveScreen(
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onConnectClick = {
                val act = activity
                if (act != null) {
                    com.clotube.app.auth.GoogleAuthManager.launchGoogleSignIn(act, signInLauncher)
                } else {
                    com.clotube.app.auth.GoogleAuthManager.launchGoogleSignIn(context, signInLauncher)
                }
            }
        )
    } else {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = modifier,
            // Global Material Shared Z-Axis Defaults
            enterTransition = { MaterialSharedZAxisMotion.enterTransition },
            exitTransition = { MaterialSharedZAxisMotion.exitTransition },
            popEnterTransition = { MaterialSharedZAxisMotion.popEnterTransition },
            popExitTransition = { MaterialSharedZAxisMotion.popExitTransition }
        ) {
            // HOME SCREEN (Root Destination)
            composable(
                route = NavRoutes.HOME,
                enterTransition = { MaterialSharedZAxisMotion.enterTransition },
                exitTransition = { MaterialSharedZAxisMotion.exitTransition },
                popEnterTransition = { MaterialSharedZAxisMotion.popEnterTransition },
                popExitTransition = { MaterialSharedZAxisMotion.popExitTransition }
            ) {
                HomeScreen(
                    viewModel = videoViewModel,
                    onVideoSelected = { video ->
                        navController.navigate(NavRoutes.playerRoute(video.id, video.title))
                    },
                    onSeriesSelected = { series ->
                        navController.navigate(NavRoutes.seriesDetailRoute(series.id))
                    },
                    onResumePlayback = { fileId, title, seriesId, positionMs ->
                        if (!seriesId.isNullOrBlank()) {
                            navController.navigate(NavRoutes.seriesDetailRoute(seriesId, episodeId = fileId, positionMs = positionMs))
                        } else {
                            navController.navigate(NavRoutes.playerRoute(fileId, title))
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigate(NavRoutes.SETTINGS)
                    }
                )
            }

            // RECENT / WATCH HISTORY SCREEN
            composable(
                route = NavRoutes.RECENT,
                enterTransition = { MaterialSharedZAxisMotion.enterTransition },
                exitTransition = { MaterialSharedZAxisMotion.exitTransition },
                popEnterTransition = { MaterialSharedZAxisMotion.popEnterTransition },
                popExitTransition = { MaterialSharedZAxisMotion.popExitTransition }
            ) {
                RecentScreen(
                    playbackHistoryManager = videoViewModel.playbackHistoryManager,
                    viewModel = videoViewModel,
                    onResumeItem = { historyItem ->
                        val sId = if (historyItem.isSeries) {
                            if (!historyItem.seriesId.isNullOrBlank()) historyItem.seriesId
                            else videoViewModel.uiState.value.tvSeriesList.find { s -> s.seasons.any { season -> season.episodes.any { ep -> ep.id == historyItem.fileId } } }?.id
                        } else null

                        val resumePos = if (historyItem.progressFraction >= 0.95f) 0L else historyItem.positionMs
                        if (!sId.isNullOrBlank()) {
                            navController.navigate(NavRoutes.seriesDetailRoute(sId, episodeId = historyItem.fileId, positionMs = resumePos))
                        } else {
                            navController.navigate(NavRoutes.playerRoute(historyItem.fileId, historyItem.title))
                        }
                    }
                )
            }

            // MEDIA LIBRARY SCREEN
            composable(
                route = NavRoutes.MEDIA_LIBRARY,
                enterTransition = { MaterialSharedZAxisMotion.enterTransition },
                exitTransition = { MaterialSharedZAxisMotion.exitTransition },
                popEnterTransition = { MaterialSharedZAxisMotion.popEnterTransition },
                popExitTransition = { MaterialSharedZAxisMotion.popExitTransition }
            ) {
                MediaLibraryScreen(
                    viewModel = videoViewModel,
                    onVideoSelected = { video ->
                        navController.navigate(NavRoutes.playerRoute(video.id, video.title))
                    },
                    onSeriesSelected = { series ->
                        navController.navigate(NavRoutes.seriesDetailRoute(series.id))
                    }
                )
            }

            // SERIES DETAIL SCREEN
            composable(
                route = NavRoutes.SERIES_DETAIL,
                arguments = listOf(
                    navArgument("seriesId") { type = NavType.StringType },
                    navArgument("episodeId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("positionMs") {
                        type = NavType.LongType
                        defaultValue = 0L
                    }
                ),
                enterTransition = { MaterialSharedZAxisMotion.enterTransition },
                exitTransition = { MaterialSharedZAxisMotion.exitTransition },
                popEnterTransition = { MaterialSharedZAxisMotion.popEnterTransition },
                popExitTransition = { MaterialSharedZAxisMotion.popExitTransition }
            ) { backStackEntry ->
                val seriesId = backStackEntry.arguments?.getString("seriesId") ?: ""
                val episodeId = backStackEntry.arguments?.getString("episodeId")
                val positionMs = backStackEntry.arguments?.getLong("positionMs") ?: 0L
                val state = videoViewModel.uiState.collectAsState().value
                val initialSeries = state.tvSeriesList.find { it.id == seriesId } ?: videoViewModel.getSeriesById(seriesId)

                SeriesDetailScreen(
                    seriesId = seriesId,
                    initialEpisodeId = episodeId,
                    initialPositionMs = positionMs,
                    initialSeries = initialSeries,
                    accessToken = state.accessToken ?: videoViewModel.getAccessToken(),
                    onNavigateBack = {
                        navController.popBackStack()
                    },
                    onOpenFullscreen = { fileId, title, sId, queue ->
                        if (queue.isNotEmpty()) {
                            val idx = queue.indexOfFirst { it.id == fileId }.coerceAtLeast(0)
                            videoViewModel.setSeriesQueue(queue, idx)
                        }
                        navController.navigate(NavRoutes.playerRoute(fileId, title, sId))
                    },
                    onRegisterActivePlayer = { videoViewModel.registerActivePlayer(it) },
                    viewModel = videoViewModel
                )
            }

            // FULLSCREEN VIDEO PLAYER
            composable(
                route = NavRoutes.PLAYER,
                arguments = listOf(
                    navArgument("fileId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType },
                    navArgument("seriesId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
                enterTransition = { MaterialSharedZAxisMotion.enterTransition },
                exitTransition = { MaterialSharedZAxisMotion.exitTransition },
                popEnterTransition = { MaterialSharedZAxisMotion.popEnterTransition },
                popExitTransition = { MaterialSharedZAxisMotion.popExitTransition }
            ) { backStackEntry ->
                val fileId = backStackEntry.arguments?.getString("fileId") ?: ""

                NativeVideoPlayerScreen(
                    fileId = fileId,
                    viewModel = videoViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }

            // SETTINGS SCREEN
            composable(
                route = NavRoutes.SETTINGS,
                enterTransition = { MaterialSharedZAxisMotion.enterTransition },
                exitTransition = { MaterialSharedZAxisMotion.exitTransition },
                popEnterTransition = { MaterialSharedZAxisMotion.popEnterTransition },
                popExitTransition = { MaterialSharedZAxisMotion.popExitTransition }
            ) {
                SettingsScreen(
                    viewModel = videoViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
