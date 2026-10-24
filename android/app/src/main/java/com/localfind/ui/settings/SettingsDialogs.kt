package com.localfind.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localfind.core.settings.AppSettings
import com.localfind.core.settings.Capabilities
import com.localfind.core.settings.HardwareAccelerator
import com.localfind.core.settings.ThemeMode
import com.localfind.embed.SocInfo

/** Radio list for choosing System / Light / Dark theme; applies the choice immediately. */
@Composable
internal fun ThemeModeDialog(
    settings: AppSettings,
    isSystemDark: Boolean,
    onSettingsChange: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Choose theme") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemeMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSettingsChange(
                                    settings.copy(
                                        themeMode = mode,
                                        darkMode = when (mode) {
                                            ThemeMode.SYSTEM -> isSystemDark
                                            ThemeMode.LIGHT -> false
                                            ThemeMode.DARK -> true
                                        }
                                    )
                                )
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(
                            selected = (settings.themeMode == mode),
                            onClick = null // Handled by outer Row clickable
                        )
                        Text(
                            text = mode.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("Cancel")
            }
        }
    )

}

/**
 * Radio list for choosing the vision hardware accelerator. Modes the installed models cannot run ([caps]) are
 * disabled and explained: NPU without NPU runtime or NPU model, Smart/GPU/CPU without the universal model (the
 * NPU model cannot run on GPU/CPU). [activeOption] is the choice in effect.
 */
@Composable
internal fun AcceleratorDialog(
    settings: AppSettings,
    activeOption: HardwareAccelerator,
    caps: Capabilities,
    soc: SocInfo,
    onSettingsChange: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Vision hardware accelerator") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HardwareAccelerator.values().forEach { mode ->
                    val isModeEnabled = if (mode == HardwareAccelerator.NPU) caps.npu else caps.cpuGpu
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = isModeEnabled) {
                                onSettingsChange(settings.copy(accelerator = mode))
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(
                            selected = (activeOption == mode),
                            onClick = null, // Handled by outer Row clickable
                            enabled = isModeEnabled
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = mode.title,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isModeEnabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                }
                            )
                            Text(
                                text = when {
                                    mode == HardwareAccelerator.NPU && caps.npu ->
                                        "NPU (${soc.marketingName}): Default. Dedicated AI tensor cores for maximum indexing speed and consistent vectors."
                                    mode == HardwareAccelerator.NPU ->
                                        "NPU (${soc.marketingName}): Not supported on this device. Automatically falls back to Smart (Auto)."
                                    !caps.cpuGpu ->
                                        "Not available: this device's model is compiled for the NPU only."
                                    mode == HardwareAccelerator.SMART -> "Auto: Uses GPU when screen is off, CPU when screen is on or Battery Saver is on."
                                    mode == HardwareAccelerator.GPU -> "Always use mobile GPU compute shaders for maximum indexing speed."
                                    else -> "Always use CPU (XNNPack) to keep phone cool and eliminate UI lag."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isModeEnabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("Cancel")
            }
        }
    )

}

/** Confirmation before clearing and regenerating the embeddings of all [fileCount] library files. */
@Composable
internal fun ReindexDialog(fileCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Reindex library files?") },
        text = { Text("Clear and regenerate embeddings for all $fileCount imported files using current settings?") },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                }
            ) {
                Text("Reindex", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("Cancel")
            }
        }
    )

}

/** Confirmation before restoring every setting to its default value. */
@Composable
internal fun ResetSettingsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text("Reset settings?") },
        text = { Text("Restore all search and indexing variables to their default values?") },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                }
            ) {
                Text("Reset to Defaults", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("Cancel")
            }
        }
    )

}
