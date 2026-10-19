package com.clotube.app.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.clotube.app.ui.components.ClotubeVideoPlayer
import com.clotube.app.viewmodel.VideoViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NativeVideoPlayerScreen(
    fileId: String,
    viewModel: VideoViewModel,
    onNavigateBack: () -> Unit
) {
    val movie = remember(fileId) { viewModel.getMovieById(fileId) }
    val movieTitle = remember(movie) {
        movie?.title?.ifBlank { null } ?: movie?.displayTitle?.ifBlank { null } ?: "Untitled Movie"
    }
    val mediaPlayer = viewModel.mediaPlayer

    LaunchedEffect(fileId) {
        viewModel.prepareAndPlayMovie(fileId)
    }

    LaunchedEffect(mediaPlayer.isPlaying) {
        if (!mediaPlayer.isPlaying) {
            viewModel.playbackHistoryManager.backupToCloudImmediately()
            return@LaunchedEffect
        }
        while (mediaPlayer.isPlaying) {
            val current = mediaPlayer.time
            val total = mediaPlayer.length
            if (total > 0L && current >= 3000L) {
                viewModel.playbackHistoryManager.saveProgress(
                    fileId = fileId,
                    title = movieTitle,
                    seriesTitle = null,
                    seasonNumber = 0,
                    episodeNumber = 0,
                    isSeries = false,
                    positionMs = current,
                    durationMs = total,
                    thumbnailUrl = movie?.thumbnailUrl
                )
            }
            delay(4000L)
        }
        viewModel.playbackHistoryManager.backupToCloudImmediately()
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, mediaPlayer) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    val currentPos = mediaPlayer.time
                    val duration = mediaPlayer.length
                    if (currentPos > 1000L && duration > 0L) {
                        mediaPlayer.pause()
                        viewModel.savePlaybackPositionImmediate(
                            fileId = fileId,
                            positionMs = currentPos,
                            durationMs = duration,
                            title = movieTitle,
                            thumbnailUrl = movie?.thumbnailUrl
                        )
                    }
                    viewModel.playbackHistoryManager.backupToCloudImmediately()
                }
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(fileId) {
        onDispose {
            val current = mediaPlayer.time
            val total = mediaPlayer.length
            if (total > 0L && current >= 1000L) {
                viewModel.savePlaybackPositionImmediate(
                    fileId = fileId,
                    positionMs = current,
                    durationMs = total,
                    title = movieTitle,
                    thumbnailUrl = movie?.thumbnailUrl
                )
            }
            viewModel.playbackHistoryManager.backupToCloudImmediately()
            mediaPlayer.pause()
            try {
                mediaPlayer.detachViews()
            } catch (_: Exception) {}
        }
    }

    val coroutineScope = rememberCoroutineScope()

    ClotubeVideoPlayer(
        mediaPlayer = mediaPlayer,
        mediaId = fileId,
        title = movieTitle,
        thumbnailUrl = movie?.thumbnailUrl,
        activeStreamUrl = "http://127.0.0.1:${com.clotube.app.data.LocalStreamProxy.PORT}/stream?fileId=$fileId",
        tokenRefreshProvider = viewModel.tokenRefreshProvider,
        modifier = Modifier.fillMaxSize(),
        isInitiallyFullscreen = true, // Movies start directly in landscape fullscreen
        onPlaybackEnded = {
            val total = mediaPlayer.length.coerceAtLeast(1000L)
            coroutineScope.launch(NonCancellable + Dispatchers.IO) {
                viewModel.playbackHistoryManager.markCompleted(
                    fileId = fileId,
                    title = movieTitle,
                    durationMs = total,
                    isSeries = false,
                    thumbnailUrl = movie?.thumbnailUrl
                )
            }
        },
        onReloadMedia = { startMs ->
            viewModel.prepareAndPlayMovie(fileId, startMs)
        },
        onNavigateBack = {
            mediaPlayer.pause()
            try {
                mediaPlayer.detachViews()
            } catch (_: Exception) {}
            viewModel.playbackHistoryManager.backupToCloudImmediately()
            onNavigateBack()
        }
    )
}
