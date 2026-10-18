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
    primary = PrimaryAccent,
    onPrimary = TextHighEmphasis,
    primaryContainer = SurfaceElevated,
    onPrimaryContainer = PrimaryAccent,
    secondary = SecondaryAccent,
    onSecondary = BackgroundDeep,
    secondaryContainer = SurfaceElevated,
    onSecondaryContainer = SecondaryAccent,
    background = BackgroundDeep,
    onBackground = TextHighEmphasis,
    surface = SurfaceDefault,
    onSurface = TextHighEmphasis,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextMuted,
    outline = BorderOutline,
    outlineVariant = BorderOutline
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
                window.statusBarColor = BackgroundDeep.toArgb()
                window.navigationBarColor = BackgroundDeep.toArgb()
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
