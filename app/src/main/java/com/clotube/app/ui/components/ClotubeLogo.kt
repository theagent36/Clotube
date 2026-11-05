package com.clotube.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.clotube.app.R

/**
 * Seamless Clotube glyph component with soft radial blending to remove square border artifacts.
 */
@Composable
fun ClotubeGlyph(
    modifier: Modifier = Modifier,
    iconResId: Int = R.drawable.ic_clotube,
    contentDescription: String? = "Clotube Icon",
    seamless: Boolean = true
) {
    if (seamless) {
        Box(
            modifier = modifier
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // Apply a gentle radial blend to naturally fade the dark image canvas into #07090E
                    drawRect(
                        brush = Brush.radialGradient(
                            0.0f to Color.White,
                            0.75f to Color.White,
                            1.0f to Color.Transparent,
                            center = center,
                            radius = size.minDimension / 2f
                        ),
                        blendMode = BlendMode.DstIn
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = iconResId),
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        Image(
            painter = painterResource(id = iconResId),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = modifier
        )
    }
}
