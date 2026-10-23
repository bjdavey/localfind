package com.localfind.ui.gallery

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localfind.R

/** Centered column shared by the tile's indexing / queued / failed status overlays. */
@Composable
internal fun TileStatusOverlay(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp),
            content = content
        )
    }
}

/** Frozen circular ring in a muted outline with a status [iconRes] inside (paused, queued, failed). */
@Composable
internal fun StatusRing(iconRes: Int) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .border(3.5.dp, Color.White.copy(alpha = 0.55f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(16.dp)
        )
    }
}

/** Red "Cancel" pill used on active and queued tiles. */
@Composable
internal fun CancelIndexButton(onCancel: () -> Unit) {
    StatusActionButton(
        text = "Cancel",
        iconRes = R.drawable.ic_close,
        contentColor = Color(0xFFFF8A80),
        onClick = onCancel
    )
}

/**
 * Compact pill button used in gallery tiles for quick indexing actions (Cancel, Retry, Pause, Resume).
 */
@Composable
internal fun StatusActionButton(
    text: String,
    iconRes: Int,
    contentColor: Color,
    backgroundColor: Color = Color.Black.copy(alpha = 0.65f),
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = text,
                tint = contentColor,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}
