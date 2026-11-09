package com.clotube.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val DarkCinemaColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = TextPrimary,
    primaryContainer = SurfaceElevated,
    onPrimaryContainer = AccentPrimary,
    secondary = AccentBlue,
    onSecondary = CanvasDeep,
    secondaryContainer = SurfaceElevated,
    onSecondaryContainer = AccentPrimary,
    tertiary = AccentPrimary,
    onTertiary = CanvasDeep,
    background = CanvasDeep,
    onBackground = TextPrimary,
    surface = SurfaceDefault,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceDefault,
    surfaceContainerHigh = SurfaceElevated,
    surfaceContainerHighest = SurfaceElevated,
    surfaceContainerLow = CanvasDeep,
    surfaceContainerLowest = CanvasDeep,
    outline = SurfaceBorder,
    outlineVariant = SurfaceBorder
)

@Composable
fun ClotubeTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = DarkCinemaColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = CanvasDeep.toArgb()
                window.navigationBarColor = CanvasDeep.toArgb()
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = false
                insetsController.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}
