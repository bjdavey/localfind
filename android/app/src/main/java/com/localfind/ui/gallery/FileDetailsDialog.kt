package com.localfind.ui.gallery

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfind.R
import com.localfind.core.format.formatFileSize
import com.localfind.core.library.GalleryCategory
import com.localfind.core.library.LibraryFilters
import com.localfind.core.model.FileTypes
import com.localfind.core.model.Folder
import com.localfind.core.model.ImportedFile
import com.localfind.core.model.IndexingStatus
import com.localfind.data.FilePathHelper

/**
 * Dialog displaying file metadata and providing quick actions ("Open", "Open with/Gallery", "Search in File", "Move", "Remove from Library").
 */
@Composable
internal fun FileDetailsDialog(
    file: ImportedFile,
    isQueuePaused: Boolean = false,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onSearchInFile: () -> Unit,
    onMoveToFolder: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onCancelSync: () -> Unit
) {
    val context = LocalContext.current
    val originalPath = remember(file.uri, file.displayName) {
        FilePathHelper.resolveDisplayPath(context, file.uri, file.displayName)
    }
    val friendlyPath = remember(originalPath) {
        FilePathHelper.formatUserFriendlyPath(originalPath)
    }
    val category = remember(file.displayName, file.mimeType) {
        LibraryFilters.categorizeFile(file.displayName, file.mimeType)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val iconRes = category.iconResId
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Text(
                    text = file.displayName,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Metadata Card
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Size",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatFileSize(file.size),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        val friendlyType = when {
                            FileTypes.isPdfNameOrMime(file.displayName, file.mimeType) -> "PDF Document"
                            file.displayName.endsWith(".txt", ignoreCase = true) -> "Text Document (.txt)"
                            file.displayName.endsWith(".md", ignoreCase = true) -> "Markdown Document (.md)"
                            category == GalleryCategory.PHOTOS -> "Photo"
                            category == GalleryCategory.VIDEOS -> "Video"
                            category == GalleryCategory.AUDIO -> "Audio"
                            else -> file.mimeType ?: "File"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Type",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = friendlyType,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        val statusText = when (file.indexingStatus) {
                            IndexingStatus.INDEXED -> "Ready"
                            IndexingStatus.INDEXING -> "Analyzing..."
                            IndexingStatus.PENDING -> if (isQueuePaused) "Paused" else "Pending"
                            IndexingStatus.FAILED -> "Analysis failed"
                        }
                        val statusColor = when (file.indexingStatus) {
                            IndexingStatus.FAILED -> MaterialTheme.colorScheme.error
                            IndexingStatus.INDEXED -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val statusIcon = when (file.indexingStatus) {
                            IndexingStatus.INDEXED -> R.drawable.ic_check
                            IndexingStatus.FAILED -> R.drawable.ic_warning
                            IndexingStatus.INDEXING -> R.drawable.ic_auto_awesome
                            IndexingStatus.PENDING -> R.drawable.ic_hourglass_empty
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Status",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    painter = painterResource(statusIcon),
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = statusColor
                                )
                            }
                        }

                        // Original Device File Path Section
                        if (originalPath.isNotBlank()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )

                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Original path",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                FilePathHelper.copyToClipboard(context, originalPath)
                                                Toast.makeText(context, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_content_copy),
                                            contentDescription = "Copy original path",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Copy",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                SelectionContainer {
                                    Text(
                                        text = friendlyPath,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                // Retry Button if indexing failed
                if (file.indexingStatus == IndexingStatus.FAILED) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Try again", fontWeight = FontWeight.SemiBold)
                    }
                }

                // Cancel Button if currently indexing or queued
                if ((file.indexingStatus == IndexingStatus.INDEXING || file.indexingStatus == IndexingStatus.PENDING)) {
                    FilledTonalButton(
                        onClick = onCancelSync,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cancel indexing", fontWeight = FontWeight.SemiBold)
                    }
                }

                // Open in phone gallery or native app ("Open in Gallery" or "Open original file")
                val externalLabel = when (category) {
                    GalleryCategory.PHOTOS -> "Open in Gallery"
                    else -> "Open original file"
                }
                Button(
                    onClick = onOpenExternal,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_open_in_new),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(externalLabel, fontWeight = FontWeight.SemiBold)
                }

                // Secondary actions: Search in File (or Search by image for photos) and Move to Folder
                val isPhoto = category == GalleryCategory.PHOTOS
                val searchActionLabel = if (isPhoto) "Search by image" else "Search in file"
                val searchActionShortLabel = "Search"
                val searchActionIcon = if (isPhoto) R.drawable.ic_image else R.drawable.ic_search

                if (file.indexingStatus == IndexingStatus.INDEXED) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onSearchInFile,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(searchActionIcon),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(searchActionShortLabel, maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = onMoveToFolder,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_drive_file_move),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Move", maxLines = 1)
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = onMoveToFolder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_drive_file_move),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Move to folder")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDelete
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
