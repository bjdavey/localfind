package com.localfind.ui.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.localfind.R
import com.localfind.core.library.GalleryCategory
import com.localfind.core.library.LibraryFilters
import com.localfind.core.model.SearchResult
import com.localfind.ui.gallery.rememberFilePreview

/**
 * Square visual preview of one search hit, shared by the main result card and the per-file
 * "more matches" strip. The caller sets the size via [modifier].
 *
 * Image: the image itself. Video: the frame at the matched timestamp. PDF: the matched page.
 * Text: the matched passage. Audio: cover art or a note icon.
 */
@Composable
fun SearchResultThumbnail(result: SearchResult, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val category = remember(result.fileType, result.fileName) {
        LibraryFilters.categorizeSearchResult(result)
    }

    // For video results, locate the thumbnail at the matched chunk's timestamp offset
    val videoTimeOffset = remember(category, result.location) {
        if (category == GalleryCategory.VIDEOS) result.location else null
    }

    // For PDF results, locate the thumbnail at the matched chunk's 1-based page number
    val pdfPageNumber = remember(category, result.location) {
        if (category == GalleryCategory.PDFS && result.location > 0) result.location.toInt() else null
    }

    val previewData = rememberFilePreview(
        uri = result.fileUri,
        fileName = result.fileName,
        mimeType = null,
        skip = category == GalleryCategory.PHOTOS,
        timeOffsetMs = videoTimeOffset,
        pageNumber = pdfPageNumber
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        when (category) {
            GalleryCategory.PHOTOS -> {
                if (result.fileUri.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(result.fileUri.toUri())
                            .crossfade(true)
                            .build(),
                        contentDescription = result.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_image),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            GalleryCategory.VIDEOS -> {
                val bmp = previewData?.bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = result.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF1A1F2C), Color(0xFF2D3748))
                                )
                            )
                    )
                }
            }

            GalleryCategory.PDFS -> {
                val bmp = previewData?.bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = result.fileName,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFFAFAFA))
                            .padding(6.dp)
                    ) {
                        Text(
                            text = result.snippet.ifBlank { result.fileName },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                                lineHeight = 10.sp
                            ),
                            color = Color(0xFF212121),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            GalleryCategory.TEXT -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFFAFAFA))
                        .padding(6.dp)
                ) {
                    Text(
                        text = result.snippet.ifBlank { previewData?.textSnippet ?: result.fileName },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            lineHeight = 10.sp
                        ),
                        color = Color(0xFF212121),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            GalleryCategory.AUDIO -> {
                val bmp = previewData?.bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = result.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF1E293B), Color(0xFF2A3B4F))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_audiotrack),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            GalleryCategory.ALL -> {}
        }
    }
}
