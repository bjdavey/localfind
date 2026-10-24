package com.localfind.ui.gallery

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localfind.core.library.GalleryCategory

/**
 * Standard badge displayed for animated GIF images across views.
 */
@Composable
fun GifBadge(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    color: Color = Color.Black.copy(alpha = 0.65f)
) {
    Surface(
        shape = shape,
        color = color,
        modifier = modifier
    ) {
        Text(
            text = "GIF",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * The unified file-type indicator used across gallery tiles, search result cards and
 * details dialog: icon-only without colors. GIFs use [GifBadge], and other file types
 * (including plain images in search & file details) display their respective category icon.
 */
@Composable
fun FileTypeIcon(
    category: GalleryCategory,
    isGif: Boolean = false,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    if (isGif) {
        GifBadge(modifier = modifier)
        return
    }
    Icon(
        painter = painterResource(category.iconResId),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size)
    )
}
