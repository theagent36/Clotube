package com.clotube.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.clotube.app.ui.theme.AppleTvMotion
import com.clotube.app.ui.theme.appleTvPressable

/**
 * Square action checkbox styled specifically for Clotube's Episode Rows.
 * Adheres strictly to the Obsidian Cinema palette:
 * - Card Surfaces: #101622
 * - Card Borders / Inactive: #222C3E
 * - Active Accent: #00E5FF (Electric Cyan)
 * - Muted Text / Inactive Icon: #6B7A90
 */
@Composable
fun ClotubeEpisodeCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    val backgroundColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF00E5FF).copy(alpha = 0.16f) else Color(0xFF101622),
        animationSpec = AppleTvMotion.fluidSpring(),
        label = "cbBackground"
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF00E5FF) else Color(0xFF222C3E),
        animationSpec = AppleTvMotion.fluidSpring(),
        label = "cbBorder"
    )
    val iconTint by animateColorAsState(
        targetValue = if (checked) Color(0xFF00E5FF) else Color(0xFF6B7A90),
        animationSpec = AppleTvMotion.fluidSpring(),
        label = "cbIconTint"
    )
    val bounceScale by animateFloatAsState(
        targetValue = if (checked) 1.0f else 0.94f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cbScale"
    )

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = bounceScale
                scaleY = bounceScale
            }
            .appleTvPressable(interactionSource, pressedScale = 0.88f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true)
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(!checked)
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = if (checked) "Mark as Unwatched" else "Mark as Watched",
                tint = iconTint,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

/**
 * Minimalist circular checkbox for list views or selection dialogs.
 * Styled with Obsidian Cinema palette and tactile Apple TV+ spring physics.
 */
@Composable
fun ClotubeCircularCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }

    val bgColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF00E5FF) else Color.Transparent,
        animationSpec = AppleTvMotion.fluidSpring(),
        label = "circBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF00E5FF) else Color(0xFF222C3E),
        animationSpec = AppleTvMotion.fluidSpring(),
        label = "circBorder"
    )
    val bounceScale by animateFloatAsState(
        targetValue = if (checked) 1.0f else 0.92f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "circScale"
    )

    Surface(
        shape = CircleShape,
        color = bgColor,
        border = BorderStroke(1.5.dp, borderColor),
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = bounceScale
                scaleY = bounceScale
            }
            .appleTvPressable(interactionSource, pressedScale = 0.88f)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true)
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheckedChange(!checked)
            }
    ) {
        if (checked) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color(0xFF07090E),
                    modifier = Modifier.size((size.value * 0.65f).dp)
                )
            }
        }
    }
}
