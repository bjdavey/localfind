package com.localfind.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localfind.R
import com.localfind.core.settings.AppSettings
import com.localfind.core.settings.HardwareAccelerator
import com.localfind.core.model.ModelStatus
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
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAcceleratorDialog by remember { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val isSystemDark = isSystemInDarkTheme()
    val context = androidx.compose.ui.platform.LocalContext.current
    val detectedSoc = remember { com.localfind.embed.SocDetector.detectSoc() }
    val isNpuReady = remember(detectedSoc) { com.localfind.embed.SocDetector.isNpuRuntimeAvailable(context) }
    val activeAcceleratorOption = if (!isNpuReady && settings.accelerator == HardwareAccelerator.NPU) {
        HardwareAccelerator.SMART
    } else {
        settings.accelerator
    }

    if (showThemeDialog) {
        ThemeModeDialog(
            settings = settings,
            isSystemDark = isSystemDark,
            onSettingsChange = onSettingsChange,
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showAcceleratorDialog) {
        AcceleratorDialog(
            settings = settings,
            activeOption = activeAcceleratorOption,
            isNpuReady = isNpuReady,
            soc = detectedSoc,
            onSettingsChange = onSettingsChange,
            onDismiss = { showAcceleratorDialog = false }
        )
    }

    if (showReindexDialog) {
        ReindexDialog(
            fileCount = fileCount,
            onConfirm = onReindexAll,
            onDismiss = { showReindexDialog = false }
        )
    }

    if (showResetDialog) {
        ResetSettingsDialog(
            onConfirm = onResetDefaults,
            onDismiss = { showResetDialog = false }
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                // Prevent click pass-through to underlying screens
            },
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Medium) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showResetDialog = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_restart_alt),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
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

            SettingClickableRow(
                title = "Dark mode",
                subtitle = settings.themeMode.title,
                description = "Choose between system default, light, or dark visual themes.",
                onClick = { showThemeDialog = true }
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
                title = "Maximum search results",
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (showAdvanced) "Hide Advanced Settings" else "Show Advanced Settings",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Icon(
                        painter = painterResource(
                            if (showAdvanced) R.drawable.ic_keyboard_arrow_up else R.drawable.ic_keyboard_arrow_down
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(visible = showAdvanced) {
                Column {
                    // =================================================================
                    // SECTION 3: HARDWARE ACCELERATION
                    // =================================================================
                    SettingsCategoryHeader("HARDWARE ACCELERATION")

                    SettingClickableRow(
                        title = "Vision indexing accelerator",
                        subtitle = activeAcceleratorOption.title,
                        description = when (activeAcceleratorOption) {
                            HardwareAccelerator.NPU -> "NPU (${detectedSoc.marketingName}): Dedicated AI tensor cores for maximum indexing speed and consistent vectors."
                            HardwareAccelerator.SMART -> "Smart: Uses GPU when screen is off, switches to CPU when screen is on or Battery Saver is on."
                            HardwareAccelerator.GPU -> "GPU: Always uses mobile GPU compute shaders for maximum indexing speed."
                            HardwareAccelerator.CPU -> "CPU: Always uses CPU (XNNPack) to eliminate UI lag and reduce heat."
                        },
                        onClick = { showAcceleratorDialog = true }
                    )

                    SettingsSectionDivider()

                    // =================================================================
                    // SECTION 4: DOCUMENTS & IMAGES
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
                    // SECTION 5: AUDIO & VIDEO INDEXING
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
                    // SECTION 6: LIBRARY & REINDEXING
                    // =================================================================
                    SettingsCategoryHeader("LIBRARY & REINDEXING")

                    SettingSwitchRow(
                        title = "Enable folder import",
                        subtitle = "Not Recommended",
                        description = "Show folder import options in the gallery and action sheets.",
                        checked = settings.enableFolderImport,
                        onCheckedChange = { checked ->
                            onSettingsChange(settings.copy(enableFolderImport = checked))
                        }
                    )

                    SettingsSectionDivider()

                    SettingActionRow(
                        title = "Reindex library",
                        subtitle = if (isIndexing) "Analysis in progress..." else "$fileCount files in library",
                        description = "Clear current vector embeddings and regenerate new embeddings for all imported files using current settings.",
                        buttonText = "Reindex",
                        enabled = !isIndexing && fileCount > 0,
                        onClick = { showReindexDialog = true }
                    )

                    SettingsSectionDivider()

                    // =================================================================
                    // SECTION 7: PRIVACY & OFFLINE ENGINE
                    // =================================================================
                    SettingsCategoryHeader("PRIVACY & OFFLINE ENGINE")

                    SettingActionRow(
                        title = "On-device multimodal embedding model",
                        subtitle = when (modelStatus) {
                            is ModelStatus.Loaded -> "Loaded (${modelStatus.sizeBytes / 1_048_576} MB)"
                            is ModelStatus.Loading -> "Loading..."
                            is ModelStatus.Error -> "Not Loaded"
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
