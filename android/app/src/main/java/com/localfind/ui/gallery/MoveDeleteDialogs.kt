package com.localfind.ui.gallery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfind.R
import com.localfind.core.model.Folder
import com.localfind.core.model.ImportedFile

/**
 * Dialog enabling users to move an imported file into a different folder or back to root.
 * The file's current folder is excluded from the destinations.
 */
@Composable
internal fun MoveFileDialog(
    file: ImportedFile,
    folders: List<Folder>,
    onMove: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    MoveDestinationDialog(
        title = {
            Text(
                text = "Move \"${file.displayName}\"",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        // Root library option only makes sense when the file currently lives in a folder
        showRoot = file.folderId != null,
        folders = folders.filter { it.id != file.folderId },
        emptyText = "No other folders created yet.",
        onMove = onMove,
        onDismiss = onDismiss
    )
}

/**
 * Dialog enabling users to move multiple selected files into a different folder or back to root.
 */
@Composable
internal fun MoveMultipleFilesDialog(
    filesCount: Int,
    folders: List<Folder>,
    onMove: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    MoveDestinationDialog(
        title = {
            Text(
                text = if (filesCount == 1) "Move 1 file" else "Move $filesCount files",
                fontWeight = FontWeight.Bold
            )
        },
        showRoot = true,
        folders = folders,
        emptyText = "No folders created yet.",
        onMove = onMove,
        onDismiss = onDismiss
    )
}

/**
 * Shared "pick a destination folder" dialog behind [MoveFileDialog] and [MoveMultipleFilesDialog].
 *
 * @param showRoot Whether to offer the "Library Root" destination (`onMove(null)`).
 * @param folders Destination folders to list; [emptyText] is shown when there are none.
 */
@Composable
private fun MoveDestinationDialog(
    title: @Composable () -> Unit,
    showRoot: Boolean,
    folders: List<Folder>,
    emptyText: String,
    onMove: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Select destination folder:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (showRoot) {
                    FolderDestinationRow(R.drawable.ic_home, "Library Root") { onMove(null) }
                }

                if (folders.isEmpty()) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    folders.forEach { folder ->
                        FolderDestinationRow(R.drawable.ic_folder, folder.name) { onMove(folder.id) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/** One tappable destination (root or folder) inside [MoveDestinationDialog]. */
@Composable
private fun FolderDestinationRow(iconRes: Int, label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Dialog to confirm removal of multiple selected files from the library.
 */
@Composable
internal fun DeleteMultipleFilesDialog(
    filesCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (filesCount == 1) "Remove 1 file?" else "Remove $filesCount files?",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = if (filesCount == 1) {
                    "Remove 1 selected file from LocalFind library and delete its indexed search embeddings?\n\nThe original file on your device will NOT be deleted."
                } else {
                    "Remove $filesCount selected files from LocalFind library?\n\nThe original files on your device will NOT be deleted."
                }
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
