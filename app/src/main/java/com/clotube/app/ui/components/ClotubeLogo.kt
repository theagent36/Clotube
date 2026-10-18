package com.clotube.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.clotube.app.R

/**
 * Original Clotube logo component with borders and aesthetic framing intact.
 */
@Composable
fun ClotubeGlyph(
    modifier: Modifier = Modifier,
    iconResId: Int = R.drawable.ic_clotube,
    contentDescription: String? = "Clotube Icon"
) {
    Image(
        painter = painterResource(id = iconResId),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}
