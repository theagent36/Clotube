package com.clotube.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shimmer skeleton placeholder for [DedicatedWatchBar].
 * Replicates the exact dimensions:
 * - 16.dp rounded container card
 * - Pill badge placeholder (e.g. S01 : E08)
 * - Status & title text lines
 * - Circular 48.dp play action button
 * - Ultra-thin bottom timeline progress bar
 */
@Composable
fun ShimmerWatchBar(
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161A26)),
        border = BorderStroke(1.dp, Color(0xFF2A3142)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: Pill badge, status text, title, duration & size
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Pill badge skeleton (S01 : E01)
                        Box(
                            modifier = Modifier
                                .width(68.dp)
                                .height(20.dp)
                                .clip(RoundedCornerShape(100.dp))
                                .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.25f), RoundedCornerShape(100.dp))
                                .shimmerEffect()
                        )

                        // Status text placeholder ("Resume Watching • 45%")
                        Box(
                            modifier = Modifier
                                .width(115.dp)
                                .height(13.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .shimmerEffect()
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Episode title placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.78f)
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .shimmerEffect()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Duration and file size metadata line placeholder
                    Box(
                        modifier = Modifier
                            .width(135.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .shimmerEffect()
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Circular Play button skeleton (48.dp)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E2436))
                        .border(1.dp, Color(0xFF2F80ED).copy(alpha = 0.4f), CircleShape)
                        .shimmerEffect()
                )
            }

            // Bottom progress bar placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(1.5.dp))
                            .shimmerEffect()
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * Shimmer skeleton placeholder for [ParticularSeasonTabs].
 * Renders a horizontal row of pill-shaped season selector tabs.
 */
@Composable
fun ShimmerSeasonTabs(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val tabWidths = listOf(105.dp, 96.dp, 96.dp, 96.dp)
        tabWidths.forEach { width ->
            Box(
                modifier = Modifier
                    .width(width)
                    .height(34.dp)
                    .clip(RoundedCornerShape(50.dp))
                    .border(1.dp, Color(0xFF2A3042), RoundedCornerShape(50.dp))
                    .shimmerEffect()
            )
        }
    }
}

/**
 * Shimmer skeleton placeholder for [EpisodeListItem].
 * Matches:
 * - 16:9 thumbnail preview box (120dp x 68dp)
 * - Episode index tag ("E01" / "S01:E01")
 * - Title text placeholder
 * - File size and resolution badge placeholder
 */
@Composable
fun ShimmerEpisodeItem(
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1E2230),
        border = BorderStroke(1.dp, Color(0xFF2A3042)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 16:9 Thumbnail skeleton
            Box(
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF12141C))
                    .shimmerEffect()
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Right Episode Info Column
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Top row: Episode index tag placeholder
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(13.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Episode title placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(15.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom row: File size and resolution badge placeholder
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(55.dp)
                            .height(11.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .shimmerEffect()
                    )

                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0xFF2A3042), RoundedCornerShape(4.dp))
                            .shimmerEffect()
                    )
                }
            }
        }
    }
}

/**
 * Composite helper rendering multiple [ShimmerEpisodeItem] cards.
 */
@Composable
fun ShimmerEpisodesSection(
    count: Int = 5,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(count) {
            ShimmerEpisodeItem()
        }
    }
}
