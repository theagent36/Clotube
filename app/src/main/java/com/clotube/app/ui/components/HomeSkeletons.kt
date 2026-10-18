package com.clotube.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Animated shimmer skeleton for the Hero Billboard banner.
 * Matches 16:9 banner proportion with embedded action button and title placeholders.
 */
@Composable
fun ShimmerHeroBillboard(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .aspectRatio(16f / 9f)
            .shimmerEffect()
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            // Fake Title Bar Placeholder
            Box(
                modifier = Modifier
                    .width(200.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF0F121C).copy(alpha = 0.65f))
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Fake Button Pills
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF0F121C).copy(alpha = 0.65f))
                )
                Box(
                    modifier = Modifier
                        .width(100.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        ShimmerCategoryRow()
        ShimmerHeroBillboard()
        ShimmerMediaSection(sectionTitle = "Movies")
        ShimmerMediaSection(sectionTitle = "TV Series & Shows")
    }
}
