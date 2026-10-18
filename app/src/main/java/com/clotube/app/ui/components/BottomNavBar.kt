package com.clotube.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.app.viewmodel.NavigationTab

/**
 * Navigation item specification for the floating bottom navigation capsule.
 */
data class BottomNavTabItem(
    val tab: NavigationTab,
    val label: String,
    val icon: ImageVector
)

/**
 * Default tab definitions matching Clotube's core navigation destinations.
 */
val DefaultNavTabs = listOf(
    BottomNavTabItem(
        tab = NavigationTab.LIBRARY,
        label = "Home",
        icon = Icons.Default.VideoLibrary
    ),
    BottomNavTabItem(
        tab = NavigationTab.RECENT,
        label = "Recent",
        icon = Icons.Default.History
    ),
    BottomNavTabItem(
        tab = NavigationTab.SETTINGS,
        label = "Settings",
        icon = Icons.Default.Settings
    )
)

/**
 * Modern floating navigation capsule (pill bar) with rounded corners, subtle border,
 * shadow elevation, and system navigation bar insets.
 *
 * Designed to float above scrolling media feeds without clipping or layout obstruction.
 */
@Composable
fun BottomNavBar(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<BottomNavTabItem> = DefaultNavTabs
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(32.dp),
            color = Color(0xFF161A26), // Cinema dark surface
            border = BorderStroke(1.dp, Color(0xFF2A3142)), // Subtle outline
            shadowElevation = 12.dp,
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { navItem ->
                    val isSelected = selectedTab == navItem.tab
                    BottomNavItem(
                        item = navItem,
                        isSelected = isSelected,
                        onClick = { onTabSelected(navItem.tab) }
                    )
                }
            }
        }
    }
}

/**
 * Compact item layout inside the floating capsule.
 * Displays an animated pill indicator on the active state with Electric Blue highlight,
 * white icon, and bold typography, transitioning to subtle slate gray on inactive tabs.
 */
@Composable
private fun BottomNavItem(
    item: BottomNavTabItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val indicatorColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF2F80ED) else Color.Transparent,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "tab_indicator_color"
    )
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) Color.White else Color(0xFF94A3B8),
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "tab_icon_tint"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Color.White else Color(0xFF64748B),
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "tab_text_color"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tab_icon_scale"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, color = Color(0xFF2F80ED).copy(alpha = 0.25f))
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(indicatorColor)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = iconTint,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer(scaleX = iconScale, scaleY = iconScale)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = item.label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

/**
 * ClotubeBottomNavBar alias for seamless interoperability across screen architectures.
 */
@Composable
fun ClotubeBottomNavBar(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier,
    tabs: List<BottomNavTabItem> = DefaultNavTabs
) {
    BottomNavBar(
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
        modifier = modifier,
        tabs = tabs
    )
}
