package com.clotube.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.videolan.libvlc.MediaPlayer

/**
 * Obsolete series player controls overlay.
 * Scrapped and replaced by the production movie player UI layout in [ClotubeVideoPlayer].
 */
@Deprecated(
    message = "Obsolete series player controls overlay scrapped. Use ClotubeVideoPlayer instead.",
    level = DeprecationLevel.WARNING
)
@Composable
fun PlayerControlsOverlay(
    mediaPlayer: MediaPlayer,
    durationMs: Long,
    currentPositionMs: Long,
    episodeTitle: String,
    onSeekTo: (Long) -> Unit,
    onClosePlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Obsolete controls overlay scrapped. Video player UI is unified in ClotubeVideoPlayer.
}
