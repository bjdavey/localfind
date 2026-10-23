package com.localfind.ui.search

import com.localfind.core.library.LibraryFilters
import com.localfind.core.format.SearchResultFormatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.localfind.R
import com.localfind.core.model.SearchResult
import com.localfind.core.library.GalleryCategory
import com.localfind.ui.gallery.ThumbnailHelper
import com.localfind.ui.gallery.badgeColor
import com.localfind.ui.gallery.rememberFilePreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scrollable list displaying ranked [SearchResult] cards.
 */
@Composable
fun SearchResultsList(
    results: List<SearchResult>,
    onResultClick: (SearchResult) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    // Defensively ensure chunk keys are unique so LazyColumn never throws an IllegalArgumentException
    val uniqueResults = remember(results) {
        results.distinctBy { it.chunkId }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = uniqueResults,
            key = { it.chunkId }
        ) { result ->
            SearchResultCard(
                result = result,
                onClick = { onResultClick(result) }
            )
        }
    }
}

/**
 * Rich visual result card showing:
 * - Left: 78dp square visual thumbnail (Image, Video frame, PDF page 1, Text preview, or Audio cover).
 * - Right: File name, similarity score badge (`88% match`), file type + location badge (`Page 4`, `01:20`),
 *   and matched snippet text.
 */
@Composable
fun SearchResultCard(
    result: SearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val category = remember(result.fileType, result.fileName) {
        LibraryFilters.categorizeSearchResult(result)
    }

    // For video results, locate the thumbnail at the matched chunk's timestamp offset (+ 2s offset)
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

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Left: Square Thumbnail Preview
            Box(
                modifier = Modifier
                    .size(78.dp)
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

            // Right: Metadata & Snippet
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Header: File Name + Match Score Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = result.fileName.ifEmpty { "Unknown Document" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = SearchResultFormatter.formatScore(result.score),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                // Sub-header: Type Badge + Location Offset
                val typeBadgeColor = category.badgeColor

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = typeBadgeColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = result.fileType.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = typeBadgeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }

                    Text(
                        text = SearchResultFormatter.formatLocation(result.fileType, result.location),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Snippet Box
                if (result.snippet.isNotBlank()) {
                    Text(
                        text = SearchResultFormatter.formatSnippet(result.snippet, maxLength = 180),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
