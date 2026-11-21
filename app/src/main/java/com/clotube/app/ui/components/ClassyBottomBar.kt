package com.clotube.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.app.util.triggerTabTickHaptic
import com.clotube.app.viewmodel.NavigationTab

/**
 * Tab item model for the floating glassmorphic navigation capsule.
 */
@androidx.annotation.Keep
data class ClassyNavTabItem(
    val tab: NavigationTab,
    val label: String,
    val icon: ImageVector
)

/**
 * Default tab definitions matching Clotube's core destinations:
 * "Home", "Search", "Watchlist", "Recent", and "Settings".
 */
val DefaultClassyNavTabs = listOf(
    ClassyNavTabItem(
        tab = NavigationTab.LIBRARY,
        label = "Home",
        icon = Icons.Rounded.Home
    ),
    ClassyNavTabItem(
        tab = NavigationTab.SEARCH,
        label = "Search",
        icon = Icons.Rounded.Search
    ),
    ClassyNavTabItem(
        tab = NavigationTab.WATCHLIST,
        label = "Watchlist",
        icon = Icons.Rounded.Bookmarks
    ),
    ClassyNavTabItem(
        tab = NavigationTab.RECENT,
        label = "Recent",
        icon = Icons.Rounded.History
    ),
    ClassyNavTabItem(
        tab = NavigationTab.SETTINGS,
        label = "Settings",
        icon = Icons.Rounded.Settings
    )
)

/**
 * Floating glassmorphic bottom navigation bar with Apple TV+ / HBO style obsidian aesthetics.
 *
 * Container:
 * - Height: 54.dp
 * - Shape: RoundedCornerShape(27.dp)
 * - Placement: Anchored bottom center with navigationBarsPadding().padding(bottom = 12.dp)
 * - Background: Translucent deep obsidian Color(0xE60E1118)
 * - Border: Hairline BorderStroke(0.5.dp, Color(0x24FFFFFF))
 * - Shadow: shadowElevation = 16.dp
 *
 * Selection:
 * - Selected: Soft capsule pill Color(0x1AFFFFFF) wrapping icon and bold white text label (12.sp)
 * - Inactive: Displays only the icon in muted slate Color(0xFF64748B)
 */
@Composable
fun ClassyBottomBar(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<ClassyNavTabItem> = DefaultClassyNavTabs
) {
    val view = LocalView.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .height(54.dp),
            shape = RoundedCornerShape(27.dp),
            color = Color(0xE60E1118),
            border = BorderStroke(0.5.dp, Color(0x24FFFFFF)),
            shadowElevation = 16.dp,
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { navItem ->
                    val isSelected = selectedTab == navItem.tab
                    ClassyBottomBarTab(
                        label = navItem.label,
                        icon = navItem.icon,
                        isSelected = isSelected,
                        onClick = {
                            view.triggerTabTickHaptic()
                            onTabSelected(navItem.tab)
                        }
                    )
                }
            }
        }
    }
}

/**
 * ClassyBottomBar overload for HomeNavigationTab enum destinations.
 */
@Composable
fun ClassyBottomBar(
    selectedTab: com.clotube.app.ui.screens.HomeNavigationTab,
    onTabSelected: (com.clotube.app.ui.screens.HomeNavigationTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val tabs = com.clotube.app.ui.screens.HomeNavigationTab.values()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .height(54.dp),
            shape = RoundedCornerShape(27.dp),
            color = Color(0xE60E1118),
            border = BorderStroke(0.5.dp, Color(0x24FFFFFF)),
            shadowElevation = 16.dp,
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val isSelected = selectedTab == tab
                    ClassyBottomBarTab(
                        label = tab.title,
                        icon = tab.icon,
                        isSelected = isSelected,
                        onClick = {
                            view.triggerTabTickHaptic()
                            onTabSelected(tab)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassyBottomBarItem(
    item: ClassyNavTabItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ClassyBottomBarTab(
        label = item.label,
        icon = item.icon,
        isSelected = isSelected,
        onClick = onClick
    )
}

/**
 * Individual tab item in the floating capsule with spring-based press scaling,
 * ripple-free touch response, and linear spring width/alpha expansion.
 */
@Composable
private fun ClassyBottomBarTab(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = 0.75f,
            stiffness = 600f
        ),
        label = "tab_press_scale"
    )

    val selectionProgress by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.82f,
            stiffness = 520f
        ),
        label = "tab_selection_progress"
    )

    val containerColor = lerp(
        start = Color.Transparent,
        stop = Color(0x1AFFFFFF),
        fraction = selectionProgress.coerceIn(0f, 1f)
    )
    val iconTint = lerp(
        start = Color(0xFF64748B),
        stop = Color.White,
        fraction = selectionProgress.coerceIn(0f, 1f)
    )
    val horizontalPadding = (14f + 2f * selectionProgress.coerceIn(0f, 1f)).dp

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab
            ) { onClick() }
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )

            ExpandableTabLabel(
                label = label,
                selectionProgress = selectionProgress
            )
        }
    }
}

/**
 * Smoothly expanding tab label measured at its unconstrained intrinsic single-line width,
 * avoiding zero-width clips and enter/exit race conditions during rapid tab switching.
 */
@Composable
private fun ExpandableTabLabel(
    label: String,
    selectionProgress: Float,
    modifier: Modifier = Modifier,
    startSpacing: Dp = 8.dp
) {
    val density = LocalDensity.current
    val startSpacingPx = with(density) { startSpacing.roundToPx() }

    Layout(
        content = {
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                modifier = Modifier.graphicsLayer {
                    alpha = ((selectionProgress - 0.15f) / 0.85f).coerceIn(0f, 1f)
                }
            )
        },
        modifier = modifier.clipToBounds()
    ) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(0, 0) {}
        }
        val textMeasurable = measurables.first()
        val textPlaceable = textMeasurable.measure(
            constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
        )

        val fullWidth = startSpacingPx + textPlaceable.width
        val animatedWidth = (fullWidth * selectionProgress.coerceIn(0f, 1f)).toInt()
        val height = textPlaceable.height

        layout(width = animatedWidth, height = height) {
            if (animatedWidth > startSpacingPx) {
                textPlaceable.placeRelative(
                    x = startSpacingPx,
                    y = (height - textPlaceable.height) / 2
                )
            }
        }
    }
}
