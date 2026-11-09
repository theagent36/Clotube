package com.clotube.app.ui.screens

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewModelScope
import com.clotube.app.ui.components.ClotubeVideoPlayer
import com.clotube.app.viewmodel.VideoViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NativeVideoPlayerScreen(
    fileId: String,
    viewModel: VideoViewModel,
    onNavigateBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val movie = remember(fileId) { viewModel.getMovieById(fileId) }
    val movieTitle = remember(movie) {
        movie?.title?.ifBlank { null } ?: movie?.displayTitle?.ifBlank { null } ?: "Movie"
    }
    var resumePositionMs by remember { mutableLongStateOf(0L) }
    var isReadyToPlay by remember { mutableStateOf(false) }

    // 1. Resolve resume position from DataStore before mounting player
    LaunchedEffect(fileId) {
        viewModel.stopAndReleaseCurrentPlayer()
        resumePositionMs = viewModel.playbackHistoryManager.getResumePosition(fileId)

        // Immediately record playback start with current timestamp so it becomes the newest history item
        viewModel.playbackHistoryManager.saveMoviePlaybackState(
            fileId = fileId,
            title = movieTitle,
            positionMs = resumePositionMs.coerceAtLeast(1000L),
            durationMs = movie?.durationMs?.coerceAtLeast(1000L) ?: 1000L
        )
        viewModel.prepareAndPlayMovie(fileId, resumePositionMs)
        isReadyToPlay = true
    }

    // 2. Periodic progress ticker while playing
    LaunchedEffect(isReadyToPlay) {
        if (!isReadyToPlay) return@LaunchedEffect
        while (true) {
            delay(4000L)
            viewModel.mediaPlayer.let { player ->
                if (player.isPlaying) {
                    val pos = player.time
                    val dur = if (player.length > 0L) player.length else (movie?.durationMs ?: 0L)
                    if (pos > 1000L && dur > 0L) {
                        viewModel.playbackHistoryManager.saveMoviePlaybackState(
                            fileId = fileId,
                            title = movieTitle,
                            positionMs = pos,
                            durationMs = dur
                        )
                    }
                }
            }
        }
    }

    val handleExit = {
        viewModel.mediaPlayer.let { player ->
            val pos = player.time
            val dur = if (player.length > 0L) player.length else (movie?.durationMs ?: 0L)
            if (pos > 1000L && dur > 0L) {
                viewModel.viewModelScope.launch {
                    viewModel.playbackHistoryManager.saveMoviePlaybackState(
                        fileId = fileId,
                        title = movieTitle,
                        positionMs = pos,
                        durationMs = dur
                    )
                }
            }
        }
        viewModel.stopAndReleaseCurrentPlayer()
        onNavigateBack()
    }

    // 3. Save progress on back navigation / disposal and release native player
    DisposableEffect(fileId) {
        onDispose {
            viewModel.mediaPlayer.let { player ->
                val pos = player.time
                val dur = if (player.length > 0L) player.length else (movie?.durationMs ?: 0L)
                if (pos > 1000L && dur > 0L) {
                    viewModel.viewModelScope.launch {
                        viewModel.playbackHistoryManager.saveMoviePlaybackState(
                            fileId = fileId,
                            title = movieTitle,
                            positionMs = pos,
                            durationMs = dur
                        )
                    }
                }
            }
            viewModel.stopAndReleaseCurrentPlayer()
        }
    }

    BackHandler {
        handleExit()
    }

    if (isReadyToPlay) {
        ClotubeVideoPlayer(
            episode = null,
            movie = movie,
            viewModel = viewModel,
            startPositionMs = resumePositionMs,
            onClosePlayer = handleExit,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        )
    }
}
