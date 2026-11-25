package com.clotube.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clotube.app.ui.theme.TextHighEmphasis

/**
 * Animated shimmer skeleton for the Hero Billboard banner / Continue Watching card.
 * Matches ContinueWatchingCard specifications: fillMaxWidth, height 210.dp, 16.dp rounded corners,
 * with placeholders for the top-left badge pill, bottom-left title/subtitle, and bottom-right 46.dp play circle.
 */
@Composable
fun ShimmerHeroBillboard(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .shimmerEffect()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top-left badge pill placeholder
            Box(
                modifier = Modifier
                    .width(130.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0F121C).copy(alpha = 0.65f))
            )

            // Bottom Row: bottom-left title/subtitle lines and bottom-right 46.dp circular play button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(100.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF0F121C).copy(alpha = 0.65f))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(180.dp)
                            .height(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF0F121C).copy(alpha = 0.65f))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF0F121C).copy(alpha = 0.65f))
                    )
                }

                // Bottom-right 46.dp circular play button placeholder
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0F121C).copy(alpha = 0.65f))
                )
            }
        }
    }
}

/**
 * Horizontal row of filter category pill shimmer placeholders.
 */
@Composable
fun ShimmerCategoryRow(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .shimmerEffect()
            )
        }
    }
}

/**
 * Shimmer media section matching Clotube's 16:9 carousel card specifications.
 */
@Composable
fun ShimmerMediaSection(
    sectionTitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = sectionTitle,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = TextHighEmphasis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(220.dp)
                        .height(124.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .shimmerEffect()
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(140.dp)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF0F121C).copy(alpha = 0.70f))
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .width(80.dp)
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF0F121C).copy(alpha = 0.70f))
                        )
                    }
                }
            }
        }
    }
}

/**
 * Composite full-screen skeleton matching Clotube's Home layout during empty-cache initial sync.
 */
@Composable
fun HomeSkeletonScreen(
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 16.dp
            )
    ) {
        ShimmerCategoryRow()
        ShimmerHeroBillboard()
        ShimmerMediaSection(sectionTitle = "Movies")
        ShimmerMediaSection(sectionTitle = "Series")
    }
}
