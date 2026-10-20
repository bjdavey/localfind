package com.localfind.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localfind.R
import com.localfind.data.Folder
import com.localfind.data.ImportedFile
import com.localfind.data.IndexingStatus
import com.localfind.data.ModelStatus
import com.localfind.index.IndexingProgress

/**
 * Google Photos-inspired Gallery Home Screen supporting Images, Videos, PDFs, Text Documents, and Audio.
 *
 * Key UX Features:
 * 1. **Top Header**: Displays app title, indexed library statistics, and top-right Settings icon.
 * 2. **Per-File Progress**: Displays live progress indicator per file and queue status for background WorkManager indexing.
 * 3. **Category Filter Chips**: Instant filtering across `All`, `Photos`, `Videos`, `Docs`, and `Audio`.
 * 4. **2-Column Visual Grid**: Rich thumbnails for every modality via [MediaGalleryTile] and [FolderGalleryTile].
 * 5. **Option C Unified Bottom Floating Dock**: Single floating pill combining search and import.
 */
@Composable
fun GalleryScreen(
    importedFiles: List<ImportedFile>,
    indexedChunksCount: Int,
    isIndexing: Boolean,
    statusMessage: String,
    indexingProgress: IndexingProgress = IndexingProgress(),
    modelStatus: ModelStatus = ModelStatus.Loading,
    folders: List<Folder> = emptyList(),
    currentFolderId: String? = null,
    onNavigateFolder: (String?) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onRenameFolder: (Folder, String) -> Unit = { _, _ -> },
    onDeleteFolder: (Folder) -> Unit = {},
    onImportFolder: () -> Unit = {},
    enableFolderImport: Boolean = false,
    onMoveFileToFolder: (ImportedFile, String?) -> Unit = { _, _ -> },
    onRetryLoadModel: () -> Unit = {},
    onOpenSearch: () -> Unit,
    onSearchInFile: ((ImportedFile) -> Unit)? = null,
    onImportFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onFileClick: (ImportedFile) -> Unit,
    onRequestDeleteFile: (ImportedFile) -> Unit,
    onRetryIndexFile: ((ImportedFile) -> Unit)? = null,
    onPauseIndexing: (() -> Unit)? = null,
    onResumeIndexing: (() -> Unit)? = null,
    onCancelIndexing: (() -> Unit)? = null,
    onCancelIndexFile: ((ImportedFile) -> Unit)? = null,
    gridState: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(GalleryCategory.ALL) }
    var inspectedFile by remember { mutableStateOf<ImportedFile?>(null) }
    var showImportMenu by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var inspectedFolder by remember { mutableStateOf<Folder?>(null) }
    var folderToRename by remember { mutableStateOf<Folder?>(null) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }
    var fileToMove by remember { mutableStateOf<ImportedFile?>(null) }

    // Folder and navigation state
    val currentFolder = remember(folders, currentFolderId) {
        folders.firstOrNull { it.id == currentFolderId }
    }
    val currentSubfolders = remember(folders, currentFolderId) {
        folders.filter { it.parentId == currentFolderId }
    }
    val currentFolderFiles = remember(importedFiles, currentFolderId, selectedCategory) {
        ThumbnailHelper.filterFilesByFolder(importedFiles, currentFolderId, selectedCategory)
    }

    // Breadcrumb path resolution (Root -> Subfolder -> Current)
    val breadcrumbs = remember(folders, currentFolderId) {
        val chain = mutableListOf<Folder>()
        var curr = folders.firstOrNull { it.id == currentFolderId }
        while (curr != null) {
            chain.add(0, curr)
            curr = folders.firstOrNull { it.id == curr.parentId }
        }
        chain
    }

    // Only show categories that have imported files in the library (plus ALL).
    // Hides filter chips for categories with 0 files in the app.
    val availableCategories = remember(importedFiles) {
        ThumbnailHelper.getAvailableCategories(importedFiles)
    }

    LaunchedEffect(availableCategories) {
        if (selectedCategory !in availableCategories) {
            selectedCategory = GalleryCategory.ALL
        }
    }

    // File Details & Quick Actions Modal when long-pressing or tapping info on a tile
    inspectedFile?.let { file ->
        FileDetailsDialog(
            file = file,
            isQueuePaused = indexingProgress.isPaused,
            onDismiss = { inspectedFile = null },
            onOpen = {
                val target = file
                inspectedFile = null
                onFileClick(target)
            },
            onSearchInFile = onSearchInFile?.let { searchFn ->
                {
                    val target = file
                    inspectedFile = null
                    searchFn(target)
                }
            },
            onMoveToFolder = {
                val target = file
                inspectedFile = null
                fileToMove = target
            },
            onDelete = {
                val target = file
                inspectedFile = null
                onRequestDeleteFile(target)
            },
            onRetry = onRetryIndexFile?.let { retryFn ->
                {
                    val target = file
                    inspectedFile = null
                    retryFn(target)
                }
            },
            onCancelSync = onCancelIndexFile?.let { cancelFn ->
                {
                    val target = file
                    inspectedFile = null
                    cancelFn(target)
                }
            }
        )
    }

    // Move file between folders dialog
    fileToMove?.let { file ->
        MoveFileDialog(
            file = file,
            folders = folders,
            onMove = { targetFolderId ->
                val target = file
                fileToMove = null
                onMoveFileToFolder(target, targetFolderId)
            },
            onDismiss = { fileToMove = null }
        )
    }

    // Folder actions menu (Rename / Delete)
    inspectedFolder?.let { folder ->
        FolderActionsDialog(
            folder = folder,
            onDismiss = { inspectedFolder = null },
            onRename = {
                val target = folder
                inspectedFolder = null
                folderToRename = target
            },
            onDelete = {
                val target = folder
                inspectedFolder = null
                folderToDelete = target
            }
        )
    }

    // Rename folder dialog
    folderToRename?.let { folder ->
        RenameFolderDialog(
            folder = folder,
            onDismiss = { folderToRename = null },
            onConfirm = { newName ->
                val target = folder
                folderToRename = null
                onRenameFolder(target, newName)
            }
        )
    }

    // Delete folder confirmation dialog
    folderToDelete?.let { folder ->
        DeleteFolderDialog(
            folder = folder,
            onDismiss = { folderToDelete = null },
            onConfirm = {
                val target = folder
                folderToDelete = null
                onDeleteFolder(target)
            }
        )
    }

    // Create new folder dialog
    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onConfirm = { folderName ->
                showCreateFolderDialog = false
                onCreateFolder(folderName)
            }
        )
    }

    // Import options modal (Import Files / Import Folder / New Folder)
    if (showImportMenu) {
        ImportOptionsMenu(
            onDismiss = { showImportMenu = false },
            onImportFiles = {
                showImportMenu = false
                onImportFiles()
            },
            onImportFolder = {
                showImportMenu = false
                onImportFolder()
            },
            onCreateFolder = {
                showImportMenu = false
                showCreateFolderDialog = true
            },
            enableFolderImport = enableFolderImport
        )
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding())
        ) {
            // =====================================================================
            // 1. TOP HEADER BAR (App Title + Stats + Top-Right Settings)
            // =====================================================================
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
                    val fileCountLabel = if (importedFiles.size == 1) "1 file" else "${importedFiles.size} files"
                    Text(
                        text = "$fileCountLabel • 100% offline",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onOpenSettings) {
                    Text(
                        text = "⚙",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // =====================================================================
            // 1.5. MODEL STATUS & ERROR BANNER
            // =====================================================================
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
                            Text(
                                text = "⚠️",
                                style = MaterialTheme.typography.titleMedium
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

            // =====================================================================
            // 2. LIVE INDEXING PROGRESS BANNER
            // =====================================================================
            val hasPending = importedFiles.any {
                it.indexingStatus == IndexingStatus.PENDING || it.indexingStatus == IndexingStatus.INDEXING
            }
            val isCancelledMessage = statusMessage.contains("cancelled", ignoreCase = true)
            val showIndexingBanner = !isCancelledMessage && (
                ((isIndexing || indexingProgress.isIndexing || indexingProgress.isPaused) && hasPending) ||
                statusMessage.startsWith("Importing")
            )
            AnimatedVisibility(
                visible = showIndexingBanner,
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val isPaused = indexingProgress.isPaused
                            val bannerText = if (isPaused) {
                                if (indexingProgress.message.isNotBlank()) indexingProgress.message else "Indexing paused"
                            } else if (indexingProgress.message.isNotBlank()) {
                                indexingProgress.message
                            } else {
                                "Indexing files..."
                            }
                            Text(
                                text = bannerText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            if (isPaused) {
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (indexingProgress.isPaused) {
                                if (onResumeIndexing != null) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    TextButton(
                                        onClick = onResumeIndexing,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text(
                                            text = "Resume",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            } else {
                                if (onPauseIndexing != null) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    TextButton(
                                        onClick = onPauseIndexing,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text(
                                            text = "Pause",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            }
                            if (onCancelIndexing != null) {
                                Spacer(modifier = Modifier.width(4.dp))
                                TextButton(
                                    onClick = onCancelIndexing,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(
                                        text = "Cancel",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =====================================================================
            // 3. CATEGORY FILTER CHIPS ROW (Hidden if library has no files or only 1 category)
            // =====================================================================
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
                            ThumbnailHelper.filterFiles(importedFiles, category).size
                        }
                        val isSelected = selectedCategory == category
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedCategory = category },
                            label = {
                                Text(
                                    text = if (count > 0) "${category.icon} ${category.label} ($count)" else "${category.icon} ${category.label}",
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

            // =====================================================================
            // 4. BREADCRUMBS & FOLDER NAVIGATION BAR (Visible when inside a folder)
            // =====================================================================
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
                            Text(
                                text = "←",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
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
                            onClick = { inspectedFolder = currentFolder },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Text(
                                text = "⋮",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // =====================================================================
            // 5. GALLERY GRID OR EMPTY STATE
            // =====================================================================
            val isRootEmpty = currentFolderId == null && importedFiles.isEmpty() && folders.isEmpty()
            val isCurrentFolderEmpty = currentSubfolders.isEmpty() && currentFolderFiles.isEmpty()

            if (isRootEmpty) {
                EmptyGalleryState(
                    onImportFiles = onImportFiles,
                    onImportFolder = onImportFolder,
                    onCreateFolder = { showCreateFolderDialog = true },
                    enableFolderImport = enableFolderImport,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else if (isCurrentFolderEmpty && selectedCategory == GalleryCategory.ALL) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "📁",
                            fontSize = 40.sp
                        )
                        Text(
                            text = "This folder is empty",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Tap the + button in the bottom dock to import files, import a folder, or create a subfolder.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else if (currentFolderFiles.isEmpty() && selectedCategory != GalleryCategory.ALL) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = selectedCategory.icon,
                            fontSize = 40.sp
                        )
                        Text(
                            text = "No ${selectedCategory.label.lowercase()} here",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "No matching files found in this category.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 6.dp,
                        // Extra bottom padding so the floating dock never obscures the bottom row
                        bottom = 104.dp + navBarPadding.calculateBottomPadding()
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Render Subfolders (shown only when category is ALL)
                    if (selectedCategory == GalleryCategory.ALL && currentSubfolders.isNotEmpty()) {
                        items(
                            items = currentSubfolders,
                            key = { "folder_${it.id}" }
                        ) { folder ->
                            val itemCount = remember(importedFiles, folders, folder.id) {
                                ThumbnailHelper.getFolderItemCount(importedFiles, folders, folder.id)
                            }
                            FolderGalleryTile(
                                folder = folder,
                                itemCount = itemCount,
                                onClick = { onNavigateFolder(folder.id) },
                                onLongClick = { inspectedFolder = folder }
                            )
                        }
                    }

                    // 2. Render Media Files
                    items(
                        items = currentFolderFiles,
                        key = { it.uri }
                    ) { file ->
                        val isActivelyIndexing = file.indexingStatus == IndexingStatus.INDEXING ||
                                (indexingProgress.isIndexing && indexingProgress.currentUri == file.uri)
                        val activeFraction = if (indexingProgress.currentUri == file.uri) {
                            indexingProgress.progressFraction
                        } else 0f

                        MediaGalleryTile(
                            file = file,
                            isActivelyIndexing = isActivelyIndexing,
                            activeProgressFraction = activeFraction,
                            isQueuePaused = indexingProgress.isPaused,
                            onClick = { onFileClick(file) },
                            onLongClick = { inspectedFile = file },
                            onCancelIndex = onCancelIndexFile?.let { { it(file) } }
                        )
                    }
                }
            }
        }

        // =====================================================================
        // 6. OPTION C UNIFIED BOTTOM FLOATING DOCK (Search Pill + Import Action Menu)
        // =====================================================================
        FloatingSearchAndImportDock(
            onSearchClick = onOpenSearch,
            onImportClick = { showImportMenu = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 16.dp + navBarPadding.calculateBottomPadding()
                )
        )
    }
}

/**
 * Option C Unified Bottom Floating Dock:
 * Combines the `[ 🔍 Search files by meaning... ]` trigger on the left with a dedicated
 * `[ + ]` Import button on the right inside one sleek floating pill container.
 */
@Composable
fun FloatingSearchAndImportDock(
    onSearchClick: () -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(30.dp),
                clip = false
            ),
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(30.dp)
                )
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Dedicated Search Area that opens the Full-Screen Search View
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(onClick = onSearchClick)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Search files by meaning...",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Subtle vertical divider separating Search from Import
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(26.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            )

            Spacer(modifier = Modifier.width(6.dp))

            // Right: Dedicated Import Button (+)
            Surface(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onImportClick),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "+",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

/**
 * Welcoming Empty Gallery State when no files have been imported yet.
 */
@Composable
private fun EmptyGalleryState(
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onCreateFolder: () -> Unit,
    enableFolderImport: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "🖼  🎬  📄  🎵",
                    fontSize = 32.sp
                )
                Text(
                    text = "Your Library is Empty",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Import videos, photos, PDFs, text documents, or audio clips to browse and search inside them by meaning — 100% offline on your device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = onImportFiles,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "+ Import Files",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (enableFolderImport) {
                        FilledTonalButton(
                            onClick = onImportFolder,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(
                                text = "📁 Folder",
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = onCreateFolder,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "+ New Folder",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
