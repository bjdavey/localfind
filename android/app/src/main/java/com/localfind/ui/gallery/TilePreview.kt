package com.localfind.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localfind.ui.theme.BadgePdf
import com.localfind.ui.theme.BadgeText

/**
 * Miniature document page preview card displaying the first lines of a `.txt` or `.md` file
 * on a realistic paper-like surface so documents are visually recognizable in the gallery grid.
 */
@Composable
internal fun DocumentPlaceholderSheet(
    snippet: String,
    isPdf: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF263238), Color(0xFF37474F))
                )
            )
            .padding(top = 36.dp, start = 10.dp, end = 10.dp, bottom = 10.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
            color = Color(0xFFFAFAFA),
            shadowElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                // Decorative header accent line
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isPdf) BadgePdf else BadgeText)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    ),
                    color = Color(0xFF212121),
                    maxLines = 7,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Stylized audio card for audio files without embedded album art.
 */
@Composable
internal fun AudioWaveformPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1E293B), Color(0xFF2A3B4F), Color(0xFF1E293B))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            val heights = listOf(18.dp, 34.dp, 24.dp, 46.dp, 30.dp, 40.dp, 20.dp, 28.dp, 16.dp)
            heights.forEach { barHeight ->
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .height(barHeight)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.75f))
                )
            }
        }
    }
}
