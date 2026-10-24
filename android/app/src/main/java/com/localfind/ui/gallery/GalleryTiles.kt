package com.localfind.ui.gallery

import com.localfind.core.library.GalleryCategory
import com.localfind.core.library.LibraryFilters
import com.localfind.core.model.FileTypes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.localfind.R
import com.localfind.core.model.ImportedFile
import com.localfind.core.model.IndexingStatus

/**
 * Individual square visual tile in the 2-column gallery grid.
 *
 * Renders a rich visual thumbnail tailored to the file's modality:
 * - **Photos**: Full-bleed Coil [AsyncImage].
 * - **Videos**: Extracted video keyframe + duration pill.
 * - **PDFs**: Rendered Page 1 bitmap on white paper + page count pill.
 * - **Text / Markdown**: Miniature document sheet showing the actual first lines of text.
 * - **Audio**: Embedded cover art or stylized waveform bars + duration pill.
 * - **Washed / Grayed Card**: Tiles with Pending, Failed, or Indexing status are washed out with a dimmed overlay.
 * - **Center Status & Actions**: Pending/Failed/Indexing status displayed in the center with contextual buttons (Cancel, Retry, Pause, Resume).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGalleryTile(
    file: ImportedFile,
    isActivelyIndexing: Boolean = false,
    activeProgressFraction: Float = 0f,
    isQueuePaused: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMoreOptionsClick: () -> Unit,
    onCancelIndex: () -> Unit,
    onRetryIndex: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val category = remember(file.displayName, file.mimeType) {
        LibraryFilters.categorizeFile(file.displayName, file.mimeType)
    }
    val isGif = remember(file.displayName, file.mimeType) {
        FileTypes.isGifNameOrMime(file.displayName, file.mimeType)
    }
    val isPdf = remember(file.displayName, file.mimeType) {
        FileTypes.isPdfNameOrMime(file.displayName, file.mimeType)
    }

    val previewData = rememberFilePreview(
        uri = file.uri,
        fileName = file.displayName,
        mimeType = file.mimeType,
        skip = category == GalleryCategory.PHOTOS
    )

    val isPending = file.indexingStatus == IndexingStatus.PENDING
    val isFailed = file.indexingStatus == IndexingStatus.FAILED
    val isIndexing = isActivelyIndexing || file.indexingStatus == IndexingStatus.INDEXING
    val isWashed = isPending || isFailed || isIndexing

    val selectionBorder = if (isSelectionMode && isSelected) {
        Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(selectionBorder)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = if (isWashed) 0.dp else 2.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // -----------------------------------------------------------------
            // 1. BACKGROUND VISUAL PREVIEW BY FILE TYPE (washed/grayed if pending or failed)
            // -----------------------------------------------------------------
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isWashed) Modifier.alpha(0.40f) else Modifier)
            ) {
                when (category) {
                    GalleryCategory.PHOTOS -> {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(file.uri.toUri())
                                .crossfade(true)
                                .build(),
                            contentDescription = file.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    GalleryCategory.VIDEOS -> {
                        val bitmap = previewData?.bitmap
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = file.displayName,
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

                    GalleryCategory.PDFS, GalleryCategory.TEXT -> {
                        if (isPdf) {
                            // PDF Page 1 Thumbnail framed as a document sheet with top alignment
                            val bitmap = previewData?.bitmap
                            if (bitmap != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.linearGradient(
                                                listOf(Color(0xFF263238), Color(0xFF37474F))
                                            )
                                        )
                                        .padding(top = 36.dp, start = 10.dp, end = 10.dp, bottom = 10.dp),
                                    contentAlignment = Alignment.TopCenter
                                ) {
                                    Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
                                        color = Color.White,
                                        shadowElevation = 2.dp
                                    ) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = file.displayName,
                                            contentScale = ContentScale.Crop,
                                            alignment = Alignment.TopCenter,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(top = 4.dp, start = 4.dp, end = 4.dp)
                                        )
                                    }
                                }
                            } else {
                                DocumentPlaceholderSheet(
                                    snippet = "PDF Document\nTap to view pages",
                                    isPdf = true
                                )
                            }
                        } else {
                            // Text / Markdown First Lines Miniature Document Sheet
                            DocumentPlaceholderSheet(
                                snippet = previewData?.textSnippet ?: "Loading preview…",
                                isPdf = false
                            )
                        }
                    }

                    GalleryCategory.AUDIO -> {
                        val bitmap = previewData?.bitmap
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = file.displayName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            AudioWaveformPlaceholder()
                        }
                    }

                    GalleryCategory.ALL -> {}
                }
            }

            // Dark wash overlay scrim when pending, failed, or indexing
            if (isWashed) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.40f))
                )
            }

            // -----------------------------------------------------------------
            // 2. CENTER CIRCULAR PROGRESS & CONTEXTUAL ACTIONS
            // -----------------------------------------------------------------
            if (isIndexing) {
                TileStatusOverlay {
                    // Running animated circular progress (determinate with % or indeterminate)
                    if (activeProgressFraction > 0f) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { activeProgressFraction },
                                modifier = Modifier.size(38.dp),
                                color = Color.White.copy(alpha = 0.85f),
                                trackColor = Color.White.copy(alpha = 0.25f),
                                strokeWidth = 3.5.dp
                            )
                            Text(
                                text = "${(activeProgressFraction * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = Color.White.copy(alpha = 0.85f),
                            trackColor = Color.White.copy(alpha = 0.25f),
                            strokeWidth = 3.5.dp
                        )
                    }
                    // Actively indexing: show Cancel only (pausing is queue-wide, from the status banner)
                    CancelIndexButton(onCancelIndex)
                }
            } else if (isPending) {
                TileStatusOverlay {
                    // Paused or queued: muted ring with a pause / hourglass icon inside.
                    // Pending files only offer Cancel (resuming is queue-wide, from the status banner).
                    StatusRing(if (isQueuePaused) R.drawable.ic_pause else R.drawable.ic_hourglass_empty)
                    CancelIndexButton(onCancelIndex)
                }
            } else if (isFailed) {
                TileStatusOverlay {
                    // Failed: only a red "Try again" pill (no status icon), matching the error banner
                    StatusActionButton(
                        text = "Try again",
                        iconRes = R.drawable.ic_refresh,
                        contentColor = MaterialTheme.colorScheme.onError,
                        backgroundColor = MaterialTheme.colorScheme.error,
                        onClick = onRetryIndex
                    )
                }
            }

            // -----------------------------------------------------------------
            // 3. TOP-LEFT & TOP-RIGHT BADGES (Modality Badge + Menu)
            // -----------------------------------------------------------------

            // Duration text specifically for video and audio (e.g. "02:45"), null for other file types
            val durationText = if (category == GalleryCategory.VIDEOS || category == GalleryCategory.AUDIO) {
                previewData?.badgeText?.takeIf { it != "VIDEO" && it != "AUDIO" && it.isNotBlank() }
            } else {
                null
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (isSelectionMode) {
                    // Checkbox indicator for multi-select
                    Surface(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape),
                        shape = CircleShape,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
                        border = if (!isSelected) BorderStroke(1.5.dp, Color.White) else null
                    ) {
                        if (isSelected) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                } else if (category != GalleryCategory.PHOTOS) {
                    // Modality badge: neutral scrim + white icon (same icon set as search/details),
                    // with the duration next to it for video/audio.
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.55f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            FileTypeIcon(category, size = 13.dp, tint = Color.White)
                            if (durationText != null) {
                                Text(
                                    text = durationText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                } else if (isGif) {
                    // Modality badge for GIF images (original text "GIF" badge on dark background)
                    GifBadge(shape = RoundedCornerShape(8.dp))
                }

                Spacer(modifier = Modifier.weight(1f))

                // Quick actions menu button (hidden during selection mode)
                if (!isSelectionMode) {
                    Surface(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onMoreOptionsClick),
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.45f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_vert),
                                contentDescription = "Options",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Subtle highlight tint over tile when selected
            if (isSelectionMode && isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                )
            }

            // -----------------------------------------------------------------
            // 4. BOTTOM GRADIENT SCRIM WITH FILE NAME
            // -----------------------------------------------------------------
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.70f),
                                Color.Black.copy(alpha = 0.88f)
                            )
                        )
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
