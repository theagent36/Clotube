package com.clotube.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Apple TV+ physics motion utility providing critically damped springs
 * and GPU-accelerated graphicsLayer transformations with zero recomposition overhead.
 */
object AppleTvMotion {
    val SmoothOutEasing = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)
    val FastExitEasing = CubicBezierEasing(0.4f, 0.0f, 1.0f, 1.0f)

    fun <T> fluidSpring() = spring<T>(
        dampingRatio = 0.82f,
        stiffness = 380f
    )

    fun <T> tactileSpring() = spring<T>(
        dampingRatio = 0.75f,
        stiffness = 500f
    )
}

/**
 * Convenience alias for [AppleTvMotion].
 */
typealias AppleTvSpring = AppleTvMotion

/**
 * Applies GPU graphicsLayer spring scaling in reaction to an existing [InteractionSource]
 * without overriding or duplicating click handlers.
 */
fun Modifier.appleTvPressable(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.95f
): Modifier = composed {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1.0f,
        animationSpec = AppleTvMotion.tactileSpring(),
        label = "appleTvPressScale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Standalone clickable modifier that applies GPU-accelerated tactile press scaling
 * alongside optional ripple indication.
 */
fun Modifier.appleTvClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.95f,
    showIndication: Boolean = false,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1.0f,
        animationSpec = AppleTvMotion.tactileSpring(),
        label = "appleTvClickScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = if (showIndication) LocalIndication.current else null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * Staggered cascade entrance animation applying GPU-driven fade and subtle vertical slide.
 */
fun Modifier.cascadeEntrance(index: Int): Modifier = composed {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        appeared = true
    }
    val delayMs = (index.coerceIn(0, 10)) * 30
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(
            durationMillis = 360,
            delayMillis = delayMs,
            easing = AppleTvMotion.SmoothOutEasing
        ),
        label = "cascadeAlpha"
    )
    val translationY by animateFloatAsState(
        targetValue = if (appeared) 0f else 22f,
        animationSpec = tween(
            durationMillis = 400,
            delayMillis = delayMs,
            easing = AppleTvMotion.SmoothOutEasing
        ),
        label = "cascadeSlideY"
    )
    this.graphicsLayer {
        this.alpha = alpha
        this.translationY = translationY
    }
}

