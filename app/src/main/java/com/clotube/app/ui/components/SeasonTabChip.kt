package com.clotube.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.app.util.triggerClickHaptic
import com.clotube.app.model.Season

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeasonTabChip(
    season: Season,
    isSelected: Boolean,
    isCompleted: Boolean,
    onSelect: () -> Unit,
    onMarkComplete: () -> Unit,
    onMarkIncomplete: () -> Unit
) {
    val view = LocalView.current
    var isMenuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    // Smooth color transitions
    val chipBgColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF00E5FF) else Color(0xFF131722),
        label = "chipBgColor"
    )
    val titleTextColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF07090E) else Color(0xFFF1F5F9),
        label = "titleTextColor"
    )
    val subtitleTextColor by animateColorAsState(
        targetValue = if (isSelected) Color(0xFF0F172A).copy(alpha = 0.8f) else Color(0xFF78859E),
        label = "subtitleTextColor"
    )

    Box {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = chipBgColor,
            border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF232A3B)),
            modifier = Modifier
                .padding(end = 10.dp)
                .clip(RoundedCornerShape(24.dp))
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isMenuOpen = true
                    }
                )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = season.seasonTitle.ifBlank { "Season ${season.seasonNumber}" },
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    color = titleTextColor
                )

                Spacer(Modifier.width(6.dp))

                Text(
                    text = "(${season.episodes.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = subtitleTextColor
                )

                if (isCompleted) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "Season Completed",
                        tint = if (isSelected) Color(0xFF07090E) else Color(0xFF00E5FF),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        // LUXURY FLOATING CONTEXT MENU
        MaterialTheme(
            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(18.dp))
        ) {
            DropdownMenu(
                expanded = isMenuOpen,
                onDismissRequest = { isMenuOpen = false },
                offset = DpOffset(x = 0.dp, y = 8.dp),
                modifier = Modifier
                    .width(260.dp)
                    .shadow(elevation = 16.dp, shape = RoundedCornerShape(18.dp))
                    .background(Color(0xFF161B26))
                    .border(BorderStroke(1.dp, Color(0xFF2A344A)), RoundedCornerShape(18.dp))
                    .padding(vertical = 6.dp)
            ) {
                if (isCompleted) {
                    DropdownMenuItem(
                        text = {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = "Mark Season as Incomplete",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Resets watch history for ${season.episodes.size} episodes",
                                    fontSize = 11.sp,
                                    color = Color(0xFF7E8B9F)
                                )
                            }
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFFEF4444).copy(alpha = 0.12f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.RestartAlt,
                                    contentDescription = null,
                                    tint = Color(0xFFFF6B6B),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        onClick = {
                            view.triggerClickHaptic()
                            isMenuOpen = false
                            onMarkIncomplete()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    )
                } else {
                    DropdownMenuItem(
                        text = {
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = "Mark Season as Complete",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Sets all ${season.episodes.size} episodes to 100%",
                                    fontSize = 11.sp,
                                    color = Color(0xFF7E8B9F)
                                )
                            }
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFF00E5FF).copy(alpha = 0.12f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DoneAll,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        onClick = {
                            view.triggerClickHaptic()
                            isMenuOpen = false
                            onMarkComplete()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
