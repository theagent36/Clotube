package com.clotube.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.app.ui.theme.AppleTvMotion
import com.clotube.app.ui.theme.appleTvPressable
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
 * "Home", "Recent", and "Settings".
 */
val DefaultClassyNavTabs = listOf(
    ClassyNavTabItem(
        tab = NavigationTab.LIBRARY,
        label = "Home",
        icon = Icons.Rounded.Home
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
                    ClassyBottomBarItem(
                        item = navItem,
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

@Composable
private fun ClassyBottomBarItem(
    item: ClassyNavTabItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) Color.White else Color(0xFF64748B),
        animationSpec = tween(durationMillis = 200),
        label = "classy_tab_icon_tint"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) Color(0x1AFFFFFF) else Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "classy_tab_container"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .appleTvPressable(interactionSource, pressedScale = 0.92f)
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = Color.White.copy(alpha = 0.15f))
            ) { onClick() }
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            .padding(horizontal = if (isSelected) 16.dp else 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )

            AnimatedVisibility(
                visible = isSelected,
                enter = expandHorizontally(animationSpec = AppleTvMotion.fluidSpring()) + fadeIn(),
                exit = shrinkHorizontally(animationSpec = AppleTvMotion.fluidSpring()) + fadeOut()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.label,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
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
                    val iconTint by animateColorAsState(
                        targetValue = if (isSelected) Color.White else Color(0xFF64748B),
                        animationSpec = tween(durationMillis = 200),
                        label = "classy_home_tab_icon_tint"
                    )
                    val containerColor by animateColorAsState(
                        targetValue = if (isSelected) Color(0x1AFFFFFF) else Color.Transparent,
                        animationSpec = tween(durationMillis = 200),
                        label = "classy_home_tab_container"
                    )

                    val interactionSource = remember { MutableInteractionSource() }

                    Box(
                        modifier = Modifier
                            .appleTvPressable(interactionSource, pressedScale = 0.92f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(21.dp))
                            .background(containerColor)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = ripple(bounded = true, color = Color.White.copy(alpha = 0.15f))
                            ) {
                                view.triggerTabTickHaptic()
                                onTabSelected(tab)
                            }
                            .animateContentSize(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                            .padding(horizontal = if (isSelected) 16.dp else 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title,
                                tint = iconTint,
                                modifier = Modifier.size(20.dp)
                            )

                            AnimatedVisibility(
                                visible = isSelected,
                                enter = expandHorizontally(animationSpec = AppleTvMotion.fluidSpring()) + fadeIn(),
                                exit = shrinkHorizontally(animationSpec = AppleTvMotion.fluidSpring()) + fadeOut()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = tab.title,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
