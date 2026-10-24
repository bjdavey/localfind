package com.localfind.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfind.R
import com.localfind.core.model.EmbeddingSpace
import com.localfind.core.model.ImportedFile
import com.localfind.core.model.IndexingProgress
import com.localfind.core.model.IndexingStatus
import com.localfind.core.model.ModelStatus
import com.localfind.embed.RecoveryModel



/**
 * Red "Model not loaded" banner (with a help dialog explaining how to install the model) shown on model errors.
 * When no GPU/CPU model is installed, the dialog also offers downloading it from Play ([onDownloadCompatibleModel]).
 */
@Composable
internal fun ColumnScope.ModelErrorBanner(
    modelStatus: ModelStatus,
    onRetryLoadModel: () -> Unit,
    onDownloadCompatibleModel: () -> Unit
) {
    var showModelHelpDialog by remember { mutableStateOf(false) }

    if (showModelHelpDialog && modelStatus is ModelStatus.Error) {
        AlertDialog(
            onDismissRequest = { showModelHelpDialog = false },
            title = { Text("Embedding Model Not Loaded") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(modelStatus.message)
                    if (modelStatus.instructions.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = modelStatus.instructions,
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    if (modelStatus.canDownloadCompatibleModel) {
                        FilledTonalButton(
                            onClick = {
                                showModelHelpDialog = false
                                onDownloadCompatibleModel()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Download compatible model (${RecoveryModel.DOWNLOAD_SIZE_MB} MB)")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showModelHelpDialog = false
                        onRetryLoadModel()
                    }
                ) {
                    Text("Retry Loading")
                }
            },
            dismissButton = {
                TextButton(onClick = { showModelHelpDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    AnimatedVisibility(
        visible = modelStatus is ModelStatus.Error,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        if (modelStatus is ModelStatus.Error) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clickable { showModelHelpDialog = true },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Model not loaded",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = modelStatus.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    FilledTonalButton(
                        onClick = onRetryLoadModel,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Retry", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Live "Analyzing n/m" banner with percent, file name, pause / resume and cancel controls. */
@Composable
internal fun ColumnScope.IndexingProgressBanner(
    visible: Boolean,
    isQueuePaused: Boolean,
    importedFiles: List<ImportedFile>,
    statusMessage: String,
    indexingProgress: IndexingProgress,
    onResumeIndexing: () -> Unit,
    onPauseIndexing: () -> Unit,
    onCancelIndexing: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                val statusHeader = if (isQueuePaused) {
                    val pendingCount = importedFiles.count {
                        it.indexingStatus == IndexingStatus.PENDING || it.indexingStatus == IndexingStatus.INDEXING
                    }
                    if (indexingProgress.message.isNotBlank() && indexingProgress.isPaused) indexingProgress.message
                    else "Paused ($pendingCount pending)"
                } else if (indexingProgress.totalFiles > 0) {
                    "Analyzing ${indexingProgress.fileIndex}/${indexingProgress.totalFiles}"
                } else if (statusMessage.startsWith("Importing")) {
                    statusMessage
                } else if (indexingProgress.message.isNotBlank() && !indexingProgress.message.startsWith("Indexing ") && !indexingProgress.message.startsWith("Analyzing ")) {
                    indexingProgress.message
                } else {
                    "Analyzing files..."
                }

                val showPercent = !isQueuePaused && (indexingProgress.progressFraction > 0f || indexingProgress.currentChunk > 0)
                val percentText = if (showPercent) "${(indexingProgress.progressFraction * 100).toInt()}%" else ""

                val activeFileName = indexingProgress.currentFileName
                    ?: importedFiles.firstOrNull { it.uri == indexingProgress.currentUri }?.displayName
                    ?: if ((indexingProgress.message.startsWith("Indexing ") || indexingProgress.message.startsWith("Analyzing ")) && indexingProgress.message.contains(": ")) {
                        indexingProgress.message.substringAfter(": ").substringBeforeLast(" (").trim().takeIf { it.isNotBlank() }
                    } else null

                // Row 1: Status header on left, action controls on right
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = statusHeader,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (percentText.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = percentText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isQueuePaused) {
                            TextButton(
                                onClick = onResumeIndexing,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_play_arrow),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Resume",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            TextButton(
                                onClick = onPauseIndexing,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_pause),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Pause",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        TextButton(
                            onClick = onCancelIndexing,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Cancel",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // Row 2: File name on its own row (spans full width, never overflows or collides with buttons)
                if (!activeFileName.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = activeFileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Row 3: Full-width progress bar
                Spacer(modifier = Modifier.height(6.dp))
                if (isQueuePaused) {
                    LinearProgressIndicator(
                        progress = { indexingProgress.progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.outline
                    )
                } else if (indexingProgress.isIndexing && indexingProgress.progressFraction > 0f) {
                    LinearProgressIndicator(
                        progress = { indexingProgress.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

/** Red banner listing how many files failed to index, with a "Try again" button for all of them. */
@Composable
internal fun ColumnScope.FailedIndexingBanner(importedFiles: List<ImportedFile>, onRetryAllFailed: () -> Unit) {
    val failedFiles = remember(importedFiles) {
        importedFiles.filter { it.indexingStatus == IndexingStatus.FAILED }
    }
    val failedCount = failedFiles.size
    AnimatedVisibility(
        visible = failedCount > 0,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (failedCount == 1) "1 file failed analysis" else "$failedCount files failed analysis",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Tap Try again to retry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = onRetryAllFailed,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Try again",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Banner shown when some indexed files were analyzed in another embedding space than the one now loaded
 * (e.g. after switching the hardware accelerator between NPU and GPU/CPU), with a button to re-analyze them.
 * Files with an unknown space (indexed before ids were recorded) are not counted, see [EmbeddingSpace.isStale].
 */
@Composable
internal fun ColumnScope.OutdatedIndexBanner(
    importedFiles: List<ImportedFile>,
    currentSpaceId: String?,
    onReindexOutdated: (String) -> Unit
) {
    val outdatedCount = remember(importedFiles, currentSpaceId) {
        importedFiles.count {
            it.indexingStatus == IndexingStatus.INDEXED && EmbeddingSpace.isStale(it.embeddingSpace, currentSpaceId)
        }
    }
    AnimatedVisibility(
        visible = outdatedCount > 0 && currentSpaceId != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (outdatedCount == 1) "1 file needs re-analysis" else "$outdatedCount files need re-analysis",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = "Analyzed with another AI setting, so search may miss them",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { currentSpaceId?.let(onReindexOutdated) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Re-analyze",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
