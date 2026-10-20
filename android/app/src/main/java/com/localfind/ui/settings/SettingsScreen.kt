package com.localfind.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localfind.data.AppSettings
import com.localfind.data.ModelStatus
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Clean, categorized Settings Screen styled after modern Android gallery settings:
 * - Uppercase accent section headers (`APPEARANCE`, `SEARCH & RETRIEVAL`, `DOCUMENTS & IMAGES`, `AUDIO & VIDEO`, `LIBRARY & REINDEXING`, `PRIVACY`).
 * - Two-line setting items with title, active value subtitle, descriptive caption, switch, button, and slider controls.
 * - Full reset-to-defaults confirmation modal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onResetDefaults: () -> Unit,
    onReindexAll: () -> Unit = {},
    isIndexing: Boolean = false,
    fileCount: Int = 0,
    modelStatus: ModelStatus = ModelStatus.Loading,
    onRetryLoadModel: () -> Unit = {},
    backHandlerEnabled: Boolean = true,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = backHandlerEnabled) {
        onClose()
    }

    var showResetDialog by remember { mutableStateOf(false) }
    var showReindexDialog by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    if (showReindexDialog) {
        AlertDialog(
            onDismissRequest = { showReindexDialog = false },
            title = { Text("Reindex library files?") },
            text = { Text("Clear and regenerate embeddings for all $fileCount imported files using current settings?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showReindexDialog = false
                        onReindexAll()
                    }
                ) {
                    Text("Reindex", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReindexDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset settings?") },
            text = { Text("Restore all search and indexing variables to their default values?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onResetDefaults()
                    }
                ) {
                    Text("Reset to Defaults", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    TextButton(onClick = { showResetDialog = true }) {
                        Text("Reset")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            // =================================================================
            // SECTION 1: APPEARANCE (FIRST THING IN SETTINGS)
            // =================================================================
            SettingsCategoryHeader("APPEARANCE")

            SettingSwitchRow(
                title = "Dark mode",
                subtitle = if (settings.darkMode) "On" else "Off",
                description = "Switch between light and dark visual themes.",
                checked = settings.darkMode,
                onCheckedChange = { isChecked ->
                    onSettingsChange(settings.copy(darkMode = isChecked))
                }
            )

            SettingsSectionDivider()

            // =================================================================
            // SECTION 2: SEARCH & RETRIEVAL
            // =================================================================
            SettingsCategoryHeader("SEARCH & RETRIEVAL")

            SettingSliderRow(
                title = "Similarity threshold",
                subtitle = "${(settings.similarityThreshold * 100).roundToInt()}%",
                description = "Minimum match score required for search results"
            ) {
                Slider(
                    value = settings.similarityThreshold,
                    onValueChange = { newValue ->
                        val rounded = (newValue * 100).roundToInt() / 100f
                        onSettingsChange(settings.copy(similarityThreshold = rounded))
                    },
                    valueRange = 0.0f..1.0f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingSliderRow(
                title = "Maximum search results (Top-K)",
                subtitle = "${settings.topK} results",
                description = "Maximum number of ranked results returned"
            ) {
                Slider(
                    value = settings.topK.toFloat(),
                    onValueChange = { newValue ->
                        onSettingsChange(settings.copy(topK = newValue.roundToInt()))
                    },
                    valueRange = 5f..50f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingsSectionDivider()

            // =================================================================
            // ADVANCED SETTINGS TOGGLE BUTTON
            // =================================================================
            // Streamlines the settings UI for non-technical users by showing only
            // the primary 3 settings by default (Dark mode, Similarity threshold,
            // Maximum search results). All deeper indexing parameters and diagnostics
            // are revealed when this toggle button is pressed.
            OutlinedButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (showAdvanced) "Hide Advanced Settings ▲" else "Show Advanced Settings ▼",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            AnimatedVisibility(visible = showAdvanced) {
                Column {
                    // =================================================================
                    // SECTION 3: DOCUMENTS & IMAGES
                    // =================================================================
                    SettingsCategoryHeader("DOCUMENTS & IMAGES")

                    SettingSliderRow(
                        title = "Text chunk size",
                        subtitle = "${settings.textChunkSize} characters",
                        description = "Target passage length when splitting text, Markdown, and PDF pages."
                    ) {
                        Slider(
                            value = settings.textChunkSize.toFloat(),
                            onValueChange = { newValue ->
                                val rounded = (newValue / 50).roundToInt() * 50
                                onSettingsChange(settings.copy(textChunkSize = rounded))
                            },
                            valueRange = 200f..2000f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    SettingSliderRow(
                        title = "Text chunk overlap",
                        subtitle = "${settings.textChunkOverlap} characters",
                        description = "Overlap between consecutive text passages to preserve boundary context."
                    ) {
                        Slider(
                            value = settings.textChunkOverlap.toFloat(),
                            onValueChange = { newValue ->
                                val rounded = (newValue / 25).roundToInt() * 25
                                onSettingsChange(settings.copy(textChunkOverlap = rounded))
                            },
                            valueRange = 0f..400f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    SettingSliderRow(
                        title = "Max image dimension",
                        subtitle = "${settings.maxImageDimension} px",
                        description = "Maximum width or height when decoding images and video keyframes for embedding."
                    ) {
                        Slider(
                            value = settings.maxImageDimension.toFloat(),
                            onValueChange = { newValue ->
                                val rounded = (newValue / 64).roundToInt() * 64
                                onSettingsChange(settings.copy(maxImageDimension = rounded))
                            },
                            valueRange = 256f..1024f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    SettingsSectionDivider()

                    // =================================================================
                    // SECTION 4: AUDIO & VIDEO INDEXING
                    // =================================================================
                    SettingsCategoryHeader("AUDIO & VIDEO")

                    SettingSliderRow(
                        title = "Audio window duration",
                        subtitle = "${settings.audioWindowSeconds} seconds",
                        description = "Length of each fixed-time audio window embedded by the multimodal model."
                    ) {
                        Slider(
                            value = settings.audioWindowSeconds.toFloat(),
                            onValueChange = { newValue ->
                                onSettingsChange(settings.copy(audioWindowSeconds = newValue.roundToInt()))
                            },
                            valueRange = 3f..30f,
                            steps = 26,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    SettingSliderRow(
                        title = "Video window duration",
                        subtitle = "${settings.videoWindowSeconds} seconds",
                        description = "Length of each sliding time window when indexing video files."
                    ) {
                        Slider(
                            value = settings.videoWindowSeconds.toFloat(),
                            onValueChange = { newValue ->
                                onSettingsChange(settings.copy(videoWindowSeconds = newValue.roundToInt()))
                            },
                            valueRange = 2f..20f,
                            steps = 17,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    SettingSliderRow(
                        title = "Sampled frames per video window",
                        subtitle = "${settings.videoFramesPerWindow} frames",
                        description = "Number of visual keyframes sampled alongside audio for each video window."
                    ) {
                        Slider(
                            value = settings.videoFramesPerWindow.toFloat(),
                            onValueChange = { newValue ->
                                onSettingsChange(settings.copy(videoFramesPerWindow = newValue.roundToInt()))
                            },
                            valueRange = 1f..5f,
                            steps = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    SettingSwitchRow(
                        title = "Video audio track",
                        subtitle = if (settings.videoIncludeAudio) "Audio + Vision (overlapped)" else "Vision-only (fastest)",
                        description = "Decode and embed audio tracks concurrently with video frames. Vision-only is significantly faster and saves battery.",
                        checked = settings.videoIncludeAudio,
                        onCheckedChange = { isChecked ->
                            onSettingsChange(settings.copy(videoIncludeAudio = isChecked))
                        }
                    )

                    SettingSwitchRow(
                        title = "Skip similar video scenes",
                        subtitle = if (settings.videoSkipSimilarChunks) "Enabled (≥85%)" else "Disabled",
                        description = "Skip redundant consecutive video windows with identical visual content to accelerate indexing and prevent duplicate search results.",
                        checked = settings.videoSkipSimilarChunks,
                        onCheckedChange = { isChecked ->
                            onSettingsChange(settings.copy(videoSkipSimilarChunks = isChecked))
                        }
                    )

                    SettingsSectionDivider()

                    // =================================================================
                    // SECTION 5: LIBRARY & REINDEXING
                    // =================================================================
                    SettingsCategoryHeader("LIBRARY & REINDEXING")

                    SettingActionRow(
                        title = "Reindex library",
                        subtitle = if (isIndexing) "Indexing in progress..." else "$fileCount files in library",
                        description = "Clear current vector embeddings and regenerate new embeddings for all imported files using current settings.",
                        buttonText = "Reindex",
                        enabled = !isIndexing && fileCount > 0,
                        onClick = { showReindexDialog = true }
                    )

                    SettingsSectionDivider()

                    // =================================================================
                    // SECTION 6: PRIVACY & OFFLINE ENGINE
                    // =================================================================
                    SettingsCategoryHeader("PRIVACY & OFFLINE ENGINE")

                    SettingActionRow(
                        title = "On-device multimodal embedding model",
                        subtitle = when (modelStatus) {
                            is ModelStatus.Loaded -> "Loaded (${modelStatus.sizeBytes / 1_048_576} MB)"
                            is ModelStatus.Loading -> "Loading..."
                            is ModelStatus.Error -> "⚠️ Not Loaded"
                        },
                        description = when (modelStatus) {
                            is ModelStatus.Loaded -> "EmbeddingGemma 2 (740M, 768-dim shared space) in app internal storage."
                            is ModelStatus.Loading -> "Warming up on-device LiteRT embedding model..."
                            is ModelStatus.Error -> modelStatus.message
                        },
                        buttonText = when (modelStatus) {
                            is ModelStatus.Loaded -> "Reload"
                            is ModelStatus.Loading -> "Loading"
                            is ModelStatus.Error -> "Load"
                        },
                        enabled = modelStatus !is ModelStatus.Loading,
                        onClick = onRetryLoadModel
                    )
                    SettingsInfoRow(
                        title = "Network & telemetry",
                        subtitle = "100% offline • Zero network permissions"
                    )
                    SettingsInfoRow(
                        title = "Storage architecture",
                        subtitle = "Link, don't copy (SAF persistable URI grants)"
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsCategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(
            letterSpacing = 0.8.sp,
            fontWeight = FontWeight.Bold
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun SettingSliderRow(
    title: String,
    subtitle: String,
    description: String,
    control: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        control()
    }
}

@Composable
private fun SettingsInfoRow(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SettingActionRow(
    title: String,
    subtitle: String,
    description: String,
    buttonText: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = onClick,
                enabled = enabled
            ) {
                Text(buttonText)
            }
        }
    }
}
