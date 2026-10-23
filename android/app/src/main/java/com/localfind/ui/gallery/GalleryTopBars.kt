package com.localfind.ui.gallery

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfind.R
import com.localfind.core.library.GalleryCategory
import com.localfind.core.library.LibraryFilters
import com.localfind.core.model.Folder
import com.localfind.core.model.ImportedFile



/**
 * Top header of the gallery: the contextual multi-select bar (count, select all, move, delete)
 * while [isSelectionMode] is on, otherwise the app title with file count and the select / new folder /
 * settings / folder-menu buttons.
 */
@Composable
internal fun GalleryHeaderBar(
    isSelectionMode: Boolean,
    fileCount: Int,
    currentFolderFiles: List<ImportedFile>,
    selectedFileUris: Set<String>,
    enableFolderImport: Boolean,
    onExitSelection: () -> Unit,
    onSelectionChange: (Set<String>) -> Unit,
    onMoveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onStartSelection: () -> Unit,
    onNewFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onImportFolder: () -> Unit
) {
    if (isSelectionMode) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        onExitSelection()
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Exit selection",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                val countText = if (selectedFileUris.size == 1) "1 selected" else "${selectedFileUris.size} selected"
                Text(
                    text = countText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Select All button (toggles all in current view)
                val allCurrentUris = remember(currentFolderFiles) { currentFolderFiles.map { it.uri }.toSet() }
                val isAllSelected = allCurrentUris.isNotEmpty() && selectedFileUris.containsAll(allCurrentUris)
                IconButton(
                    onClick = {
                        onSelectionChange(
                            if (isAllSelected) selectedFileUris - allCurrentUris else selectedFileUris + allCurrentUris
                        )
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_select_all),
                        contentDescription = if (isAllSelected) "Deselect all" else "Select all",
                        tint = if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Move button
                IconButton(
                    onClick = { onMoveSelected() },
                    enabled = selectedFileUris.isNotEmpty()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_drive_file_move),
                        contentDescription = "Move selected",
                        tint = if (selectedFileUris.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }

                // Delete button
                IconButton(
                    onClick = { onDeleteSelected() },
                    enabled = selectedFileUris.isNotEmpty()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = "Delete selected",
                        tint = if (selectedFileUris.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "LocalFind",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                val fileCountLabel = if (fileCount == 1) "1 file" else "${fileCount} files"
                Text(
                    text = fileCountLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Multi-selection button (visible when current folder has files)
                if (currentFolderFiles.isNotEmpty()) {
                    IconButton(onClick = { onStartSelection() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_checklist),
                            contentDescription = "Select files",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // When folder import is disabled: dedicated "New Folder" icon button
                if (!enableFolderImport) {
                    IconButton(onClick = { onNewFolder() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_create_new_folder),
                            contentDescription = "New folder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Settings button
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // When folder import is enabled in settings: top-right overflow menu ⋮ containing both "New folder" and "Import folder"
                if (enableFolderImport) {
                    var showFolderMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showFolderMenu = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_vert),
                                contentDescription = "Folder options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = showFolderMenu,
                            onDismissRequest = { showFolderMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("New folder") },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_create_new_folder),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showFolderMenu = false
                                    onNewFolder()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import folder") },
                                leadingIcon = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_folder_open),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    showFolderMenu = false
                                    onImportFolder()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Horizontally scrolling modality chips with per-category counts (hidden unless there are 2+ categories). */
@Composable
internal fun CategoryFilterChips(
    importedFiles: List<ImportedFile>,
    availableCategories: List<GalleryCategory>,
    selectedCategory: GalleryCategory,
    onCategorySelected: (GalleryCategory) -> Unit
) {
    if (importedFiles.isNotEmpty() && availableCategories.size > 1) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            availableCategories.forEach { category ->
                val count = remember(importedFiles, category) {
                    LibraryFilters.filterFiles(importedFiles, category).size
                }
                val isSelected = selectedCategory == category
                FilterChip(
                    selected = isSelected,
                    onClick = { onCategorySelected(category) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(category.iconResId),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = {
                        Text(
                            text = if (count > 0) "${category.label} ($count)" else category.label,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
    }
}

/** Back button, folder name and "Library > ..." path shown while browsing inside a folder. */
@Composable
internal fun FolderBreadcrumbBar(
    currentFolder: Folder?,
    breadcrumbs: List<Folder>,
    onNavigateFolder: (String?) -> Unit,
    onFolderOptions: (Folder) -> Unit
) {
    if (currentFolder != null) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onNavigateFolder(currentFolder.parentId) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "Back",
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentFolder.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val breadcrumbString = "Library > " + breadcrumbs.joinToString(" > ") { it.name }
                    Text(
                        text = breadcrumbString,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { onFolderOptions(currentFolder) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = "Folder options",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
