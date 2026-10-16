package com.localfind.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localfind.data.AppSettings
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings Screen allowing the user to configure:
 * - Search parameters: Similarity Threshold (default 0.60), Top-K results (default 10).
 * - Indexing parameters: Text chunk size & overlap, Audio window length, Video window length & frame count, Max image dimension.
 *
 * Implements Milestone 2: "Settings page for threshold and all other variables"
 *
 * @param settings Current active settings.
 * @param onSettingsChange Callback triggered whenever a setting is adjusted by the user.
 * @param onResetDefaults Callback triggered when the user resets settings to default values.
 * @param onClose Callback triggered to exit Settings and return to the main application screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onResetDefaults: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Intercept hardware and gesture back navigation
    BackHandler {
        onClose()
    }

    var showResetDialog by remember { mutableStateOf(false) }

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
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ==========================================
            // SEARCH SETTINGS SECTION
            // ==========================================
            Text(
                text = "Search Variables",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Similarity Threshold Setting
            SettingItem(
                title = "Similarity Threshold",
                valueText = String.format(Locale.US, "%.2f", settings.similarityThreshold),
                description = "Minimum cosine similarity score (0.0 to 1.0) required for results. Higher values ensure only high-confidence matches; lower values return broader results. (Default: 0.60)"
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

            // Top-K Search Results
            SettingItem(
                title = "Max Results (Top-K)",
                valueText = "${settings.topK} results",
                description = "Maximum number of nearest-neighbor results retrieved per search query. (Default: 10)"
            ) {
                Slider(
                    value = settings.topK.toFloat(),
                    onValueChange = { newValue ->
                        onSettingsChange(settings.copy(topK = newValue.roundToInt()))
                    },
                    valueRange = 5f..50f,
                    steps = 8, // 5, 10, 15, 20, 25, 30, 35, 40, 45, 50
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()

            // ==========================================
            // INDEXING SETTINGS SECTION
            // ==========================================
            Text(
                text = "Indexing Variables",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Explanatory Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                Text(
                    text = "Indexing variables apply to newly imported files. To apply updated chunking to existing documents, tap \"Re-index all\" in the Library tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Text Chunk Size
            SettingItem(
                title = "Text Chunk Size",
                valueText = "${settings.textChunkSize} chars",
                description = "Target passage character length when splitting text, Markdown, and PDF documents. (Default: 500 chars)"
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

            // Text Chunk Overlap
            SettingItem(
                title = "Text Chunk Overlap",
                valueText = "${settings.textChunkOverlap} chars",
                description = "Character overlap between consecutive chunks when sliding across passages. (Default: 100 chars)"
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

            // Audio Window Duration
            SettingItem(
                title = "Audio Window Duration",
                valueText = "${settings.audioWindowSeconds} seconds",
                description = "Duration of each fixed-time audio slice embedded by the multimodal model. (Default: 10s)"
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

            // Video Window Duration
            SettingItem(
                title = "Video Window Duration",
                valueText = "${settings.videoWindowSeconds} seconds",
                description = "Duration of each sliding time window when chunking video files. (Default: 5s)"
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

            // Video Frames Per Window
            SettingItem(
                title = "Frames Per Video Window",
                valueText = "${settings.videoFramesPerWindow} frames",
                description = "Number of visual JPEG frames sampled and embedded alongside audio per video window. (Default: 2)"
            ) {
                Slider(
                    value = settings.videoFramesPerWindow.toFloat(),
                    onValueChange = { newValue ->
                        onSettingsChange(settings.copy(videoFramesPerWindow = newValue.roundToInt()))
                    },
                    valueRange = 1f..5f,
                    steps = 3, // 1, 2, 3, 4, 5
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Max Image Dimension
            SettingItem(
                title = "Max Image Dimension",
                valueText = "${settings.maxImageDimension} px",
                description = "Maximum width or height when decoding images and video frames for embedding. (Default: 512 px)"
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

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Reusable layout item for presenting a setting with a title, current value indicator,
 * descriptive context, and custom control (e.g. [Slider]).
 */
@Composable
private fun SettingItem(
    title: String,
    valueText: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

