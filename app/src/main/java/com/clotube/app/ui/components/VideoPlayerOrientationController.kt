package com.clotube.app.ui.components

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Dedicated controller that binds screen orientation and system bar insets
 * strictly to explicit fullscreen state.
 *
 * Uses LaunchedEffect instead of DisposableEffect with a portrait-forcing onDispose
 * so recomposing or switching episodes in landscape NEVER resets orientation to portrait.
 */
@Composable
fun VideoPlayerOrientationController(
    isFullscreen: Boolean,
    onFullscreenToggle: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = remember(context) { context as? Activity }

    // Use LaunchedEffect instead of DisposableEffect with a portrait-forcing onDispose
    // so recomposing or switching episodes in landscape NEVER resets orientation to portrait.
    LaunchedEffect(isFullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val insets = WindowCompat.getInsetsController(window, window.decorView)

        if (isFullscreen) {
            if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            if (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
