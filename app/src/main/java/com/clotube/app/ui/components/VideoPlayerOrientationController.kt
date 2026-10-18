package com.clotube.app.ui.components

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Dedicated controller that binds screen orientation and system bar insets
 * strictly to explicit fullscreen toggles.
 *
 * Orientation and window insets are NEVER modified or reset during episode switching.
 */
@Composable
fun VideoPlayerOrientationController(
    isFullscreen: Boolean,
    onFullscreenToggle: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = remember(context) { context as? Activity }

    // ONLY change orientation and insets when isFullscreen changes
    DisposableEffect(isFullscreen) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }

        if (isFullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            // Restore portrait orientation and system bars when leaving the player
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
