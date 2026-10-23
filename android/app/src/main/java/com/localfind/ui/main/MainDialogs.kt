package com.localfind.ui.main

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.localfind.core.model.ImportedFile
import com.localfind.ui.main.ConfirmRemoveFileDialog
import com.localfind.ui.main.MissingFileDialog

/** Confirms removing [file] from the library; the original file on the device is never deleted. */
@Composable
internal fun ConfirmRemoveFileDialog(file: ImportedFile, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove file?") },
        text = {
            Text(
                "Remove \"${file.displayName}\" from LocalFind library?\n\n" +
                        "The original file on your device will NOT be deleted."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** Offers to clean up a library entry whose original file was moved or deleted. */
@Composable
internal fun MissingFileDialog(file: ImportedFile, onRemove: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("File Not Found") },
        text = {
            Text(
                "\"${file.displayName}\" could not be found on your device. The original file may have been moved or deleted.\n\n" +
                        "Would you like to remove it from LocalFind and clean up its search index?"
            )
        },
        confirmButton = {
            TextButton(onClick = onRemove) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text("Keep")
            }
        }
    )
}
