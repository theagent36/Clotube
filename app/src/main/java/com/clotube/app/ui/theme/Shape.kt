package com.clotube.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Dark Cinema Corner Radii
val VideoCardCornerRadius = 12.dp
val BottomSheetCornerRadius = 24.dp
val PillCornerRadius = 999.dp

val VideoCardShape = RoundedCornerShape(VideoCardCornerRadius)
val BottomSheetShape = RoundedCornerShape(topStart = BottomSheetCornerRadius, topEnd = BottomSheetCornerRadius)
val PillShape = RoundedCornerShape(PillCornerRadius)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = VideoCardShape,
    large = RoundedCornerShape(BottomSheetCornerRadius),
    extraLarge = RoundedCornerShape(BottomSheetCornerRadius)
)
