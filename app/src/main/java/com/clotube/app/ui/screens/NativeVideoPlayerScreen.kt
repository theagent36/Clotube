package com.clotube.app.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewModelScope
import com.clotube.app.model.VideoItem
import com.clotube.app.ui.components.ClotubeVideoPlayer
import com.clotube.app.ui.components.safeCall
import com.clotube.app.viewmodel.VideoViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun NativeVideoPlayerScreen(
    fileId: String,
    viewModel: VideoViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val movie = remember(fileId) {
        viewModel.getMovieById(fileId) ?: if (fileId.isNotBlank()) {
            VideoItem(id = fileId, title = "Movie")
        } else null
    }
    val movieTitle = remember(movie) {
        movie?.title?.ifBlank { null } ?: movie?.displayTitle?.ifBlank { null } ?: "Movie"
    }
    var resumePositionMs by remember(fileId) { mutableLongStateOf(0L) }
    var isReadyToPlay by remember(fileId) { mutableStateOf(false) }

    val toggleFullscreen: () -> Unit = {
        activity?.let { act ->
            act.requestedOrientation = if (isLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    // 1. Resolve resume position from DataStore before mounting player
    LaunchedEffect(fileId) {
        if (fileId.isBlank()) {
            onNavigateBack()
            return@LaunchedEffect
        }
        try {
            viewModel.stopAndReleaseCurrentPlayer()
        } catch (_: Throwable) {}

        resumePositionMs = try {
            viewModel.playbackHistoryManager.getResumePosition(fileId)
        } catch (_: Throwable) {
            0L
        }

        val movieThumb = movie?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: movie?.fallbackThumbnailUrl

        try {
            viewModel.playbackHistoryManager.saveMoviePlaybackState(
                fileId = fileId,
                title = movieTitle,
                positionMs = resumePositionMs.coerceAtLeast(1000L),
                durationMs = movie?.durationMs?.coerceAtLeast(1000L) ?: 1000L,
                thumbnailUrl = movieThumb
            )
        } catch (_: Throwable) {}

        viewModel.prepareAndPlayMovie(fileId, resumePositionMs)
        isReadyToPlay = true
    }

    // 2. Periodic progress ticker while playing (strictly guarded with safeCall)
    LaunchedEffect(isReadyToPlay, fileId) {
        if (!isReadyToPlay) return@LaunchedEffect
        while (isActive) {
            delay(4000L)
            val player = viewModel.playerOrNull
            val playing = player.safeCall(false) { isPlaying }
            if (playing) {
                val pos = player.safeCall(0L) { time }
                val nativeLen = player.safeCall(0L) { length }
                val dur = if (nativeLen > 0L) nativeLen else (movie?.durationMs ?: 0L)
                if (pos > 1000L && dur > 0L) {
                    val movieThumb = movie?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: movie?.fallbackThumbnailUrl
                    try {
                        viewModel.playbackHistoryManager.saveMoviePlaybackState(
                            fileId = fileId,
                            title = movieTitle,
                            positionMs = pos,
                            durationMs = dur,
                            thumbnailUrl = movieThumb
                        )
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    fun persistProgressAndStop() {
        val player = viewModel.playerOrNull
        val pos = player.safeCall(0L) { time }
        val nativeLen = player.safeCall(0L) { length }
        val dur = if (nativeLen > 0L) nativeLen else (movie?.durationMs ?: 0L)
        if (pos > 1000L && dur > 0L) {
            val movieThumb = movie?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: movie?.fallbackThumbnailUrl
            viewModel.viewModelScope.launch {
                try {
                    viewModel.playbackHistoryManager.saveMoviePlaybackState(
                        fileId = fileId,
                        title = movieTitle,
                        positionMs = pos,
                        durationMs = dur,
                        thumbnailUrl = movieThumb
                    )
                } catch (_: Throwable) {}
            }
        }
        try {
            viewModel.stopAndReleaseCurrentPlayer()
        } catch (_: Throwable) {}
    }

    val handleExit = {
        persistProgressAndStop()
        onNavigateBack()
    }

    // 3. Save progress on back navigation / disposal safely
    DisposableEffect(fileId) {
        onDispose {
            persistProgressAndStop()
        }
    }

    // Restore portrait orientation and system bars only when leaving NativeVideoPlayerScreen
    DisposableEffect(Unit) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler {
        if (isLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            handleExit()
        }
    }

    if (isReadyToPlay && movie != null) {
        ClotubeVideoPlayer(
            episode = null,
            movie = movie,
            viewModel = viewModel,
            startPositionMs = resumePositionMs,
            onClosePlayer = {
                if (isLandscape) {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    handleExit()
                }
            },
            onToggleFullscreen = toggleFullscreen,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        )
    }
}
