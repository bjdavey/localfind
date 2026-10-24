package com.localfind.ui.gallery

import com.localfind.core.library.GalleryCategory
import com.localfind.core.library.LibraryFilters
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.localfind.data.EngineManager
import com.localfind.data.FileOpener
import com.localfind.core.model.Folder
import com.localfind.core.model.ImportedFile
import com.localfind.core.model.IndexingStatus
import com.localfind.core.model.ModelStatus
import com.localfind.core.model.IndexingProgress

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
    availableCategories: List<GalleryCategory>,
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
    onMoveMultipleFiles: (List<ImportedFile>, String?) -> Unit = { _, _ -> },
    onDeleteMultipleFiles: (List<ImportedFile>) -> Unit = {},
    onRetryLoadModel: () -> Unit = {},
    onDownloadCompatibleModel: () -> Unit = {},
    onOpenSearch: () -> Unit,
    onSearchInFile: (ImportedFile) -> Unit,
    onImportFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onFileClick: (ImportedFile) -> Unit,
    onRequestDeleteFile: (ImportedFile) -> Unit,
    onRetryIndexFile: (ImportedFile) -> Unit,
    onRetryAllFailed: () -> Unit,
    currentEmbeddingSpaceId: String? = null,
    onReindexOutdated: (String) -> Unit = {},
    onPauseIndexing: () -> Unit,
    onResumeIndexing: () -> Unit,
    onCancelIndexing: () -> Unit,
    onCancelIndexFile: (ImportedFile) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(GalleryCategory.ALL) }
    var inspectedFile by remember { mutableStateOf<ImportedFile?>(null) }
    val context = LocalContext.current
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var inspectedFolder by remember { mutableStateOf<Folder?>(null) }
    var folderToRename by remember { mutableStateOf<Folder?>(null) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }
    var fileToMove by remember { mutableStateOf<ImportedFile?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedFileUris by remember { mutableStateOf(emptySet<String>()) }
    var showBatchMoveDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    /** Leaves multi-select mode and forgets the current selection. */
    fun exitSelectionMode() {
        isSelectionMode = false
        selectedFileUris = emptySet()
    }

    /** Adds [uri] to the selection, or removes it if it is already selected. */
    fun toggleSelection(uri: String) {
        selectedFileUris = if (uri in selectedFileUris) selectedFileUris - uri else selectedFileUris + uri
    }

    /** Files currently ticked in multi-select mode (used by the batch move and delete dialogs). */
    val selectedFiles = remember(importedFiles, selectedFileUris) {
        importedFiles.filter { it.uri in selectedFileUris }
    }

    LaunchedEffect(currentFolderId) {
        if (isSelectionMode) {
            exitSelectionMode()
        }
    }

    BackHandler(enabled = isSelectionMode) {
        exitSelectionMode()
    }

    // Folder and navigation state
    val currentFolder = remember(folders, currentFolderId) {
        folders.firstOrNull { it.id == currentFolderId }
    }
    val currentSubfolders = remember(folders, currentFolderId) {
        folders.filter { it.parentId == currentFolderId }.distinctBy { it.id }
    }
    val currentFolderFiles = remember(importedFiles, currentFolderId, selectedCategory) {
        LibraryFilters.filterFilesByFolder(importedFiles, currentFolderId, selectedCategory).distinctBy { it.uri }
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
            onOpenExternal = {
                val target = file
                inspectedFile = null
                FileOpener.openInExternalApp(context, target.uri, target.displayName, target.mimeType)
            },
            onSearchInFile = {
                val target = file
                inspectedFile = null
                onSearchInFile(target)
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
            onRetry = {
                val target = file
                inspectedFile = null
                onRetryIndexFile(target)
            },
            onCancelSync = {
                val target = file
                inspectedFile = null
                onCancelIndexFile(target)
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

    // Batch Move dialog for multiple selected files
    if (showBatchMoveDialog) {
        MoveMultipleFilesDialog(
            filesCount = selectedFiles.size,
            folders = folders,
            onMove = { targetFolderId ->
                val targets = selectedFiles
                showBatchMoveDialog = false
                exitSelectionMode()
                onMoveMultipleFiles(targets, targetFolderId)
            },
            onDismiss = { showBatchMoveDialog = false }
        )
    }

    // Batch Delete confirmation dialog for multiple selected files
    if (showBatchDeleteDialog) {
        DeleteMultipleFilesDialog(
            filesCount = selectedFiles.size,
            onConfirm = {
                val targets = selectedFiles
                showBatchDeleteDialog = false
                exitSelectionMode()
                onDeleteMultipleFiles(targets)
            },
            onDismiss = { showBatchDeleteDialog = false }
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
            // 1. TOP HEADER BAR (Contextual Multi-Select Bar or App Title + Stats + Settings)
            // =====================================================================
            GalleryHeaderBar(
                isSelectionMode = isSelectionMode,
                fileCount = importedFiles.size,
                currentFolderFiles = currentFolderFiles,
                selectedFileUris = selectedFileUris,
                enableFolderImport = enableFolderImport,
                onExitSelection = { exitSelectionMode() },
                onSelectionChange = { selectedFileUris = it },
                onMoveSelected = { showBatchMoveDialog = true },
                onDeleteSelected = { showBatchDeleteDialog = true },
                onStartSelection = { isSelectionMode = true },
                onNewFolder = { showCreateFolderDialog = true },
                onOpenSettings = onOpenSettings,
                onImportFolder = onImportFolder
            )
            // =====================================================================
            // 1.5. MODEL STATUS & ERROR BANNER
            // =====================================================================
            ModelErrorBanner(
                modelStatus = modelStatus,
                onRetryLoadModel = onRetryLoadModel,
                onDownloadCompatibleModel = onDownloadCompatibleModel
            )
            ModelDownloadBanner(modelStatus = modelStatus)
            // =====================================================================
            // 2. LIVE INDEXING PROGRESS BANNER
            // =====================================================================
            val hasPending = importedFiles.any {
                it.indexingStatus == IndexingStatus.PENDING || it.indexingStatus == IndexingStatus.INDEXING
            }
            val isCancelledMessage = statusMessage.contains("cancelled", ignoreCase = true)
            val isQueuePaused = indexingProgress.isPaused || (!isIndexing && !indexingProgress.isIndexing && hasPending)
            val showIndexingBanner = !isCancelledMessage && (
                ((isIndexing || indexingProgress.isIndexing || isQueuePaused) && hasPending) ||
                statusMessage.startsWith("Importing")
            )
            IndexingProgressBanner(
                visible = showIndexingBanner,
                isQueuePaused = isQueuePaused,
                importedFiles = importedFiles,
                statusMessage = statusMessage,
                indexingProgress = indexingProgress,
                onResumeIndexing = onResumeIndexing,
                onPauseIndexing = onPauseIndexing,
                onCancelIndexing = onCancelIndexing
            )
            // =====================================================================
            // 2.5. FAILED INDEXING BANNER (With Retry All button)
            // =====================================================================
            FailedIndexingBanner(importedFiles = importedFiles, onRetryAllFailed = onRetryAllFailed)
            OutdatedIndexBanner(
                importedFiles = importedFiles,
                currentSpaceId = currentEmbeddingSpaceId,
                onReindexOutdated = onReindexOutdated
            )
            // =====================================================================
            // 3. CATEGORY FILTER CHIPS ROW (Hidden if library has no files or only 1 category)
            // =====================================================================
            CategoryFilterChips(
                importedFiles = importedFiles,
                availableCategories = availableCategories,
                selectedCategory = selectedCategory,
                onCategorySelected = { selectedCategory = it }
            )
            // =====================================================================
            // 4. BREADCRUMBS & FOLDER NAVIGATION BAR (Visible when inside a folder)
            // =====================================================================
            FolderBreadcrumbBar(
                currentFolder = currentFolder,
                breadcrumbs = breadcrumbs,
                onNavigateFolder = onNavigateFolder,
                onFolderOptions = { inspectedFolder = it }
            )
            // =====================================================================
            // 5. GALLERY GRID OR EMPTY STATE
            // =====================================================================
            val isRootEmpty = currentFolderId == null && importedFiles.isEmpty() && folders.isEmpty()
            val isCurrentFolderEmpty = currentSubfolders.isEmpty() && currentFolderFiles.isEmpty()

            if (isRootEmpty) {
                val npuActive by EngineManager.npuActive.collectAsState()
                EmptyGalleryState(
                    hasAiHardware = npuActive,
                    onImportFiles = onImportFiles,
                    onImportFolder = onImportFolder,
                    onCreateFolder = { showCreateFolderDialog = true },
                    enableFolderImport = enableFolderImport,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else if (isCurrentFolderEmpty && selectedCategory == GalleryCategory.ALL) {
                EmptyFolderState(modifier = Modifier.weight(1f).fillMaxWidth())
            } else if (currentFolderFiles.isEmpty() && selectedCategory != GalleryCategory.ALL) {
                EmptyCategoryState(category = selectedCategory, modifier = Modifier.weight(1f).fillMaxWidth())
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
                        // Extra bottom padding so the floating dock never obscures the bottom row (reduced when dock is hidden)
                        bottom = (if (isSelectionMode) 24.dp else 104.dp) + navBarPadding.calculateBottomPadding()
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
                                LibraryFilters.getFolderItemCount(importedFiles, folders, folder.id)
                            }
                            FolderGalleryTile(
                                folder = folder,
                                itemCount = itemCount,
                                onClick = {
                                    if (!isSelectionMode) {
                                        onNavigateFolder(folder.id)
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionMode) {
                                        inspectedFolder = folder
                                    }
                                },
                                modifier = if (isSelectionMode) Modifier.alpha(0.45f) else Modifier
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

                        val isSelected = file.uri in selectedFileUris

                        MediaGalleryTile(
                            file = file,
                            isActivelyIndexing = isActivelyIndexing,
                            activeProgressFraction = activeFraction,
                            isQueuePaused = isQueuePaused,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            onClick = {
                                if (isSelectionMode) {
                                    toggleSelection(file.uri)
                                } else {
                                    onFileClick(file)
                                }
                            },
                            onLongClick = {
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                    selectedFileUris = setOf(file.uri)
                                } else {
                                    toggleSelection(file.uri)
                                }
                            },
                            onMoreOptionsClick = {
                                inspectedFile = file
                            },
                            onCancelIndex = { onCancelIndexFile(file) },
                            onRetryIndex = { onRetryIndexFile(file) }
                        )
                    }
                }
            }
        }

        // =====================================================================
        // 6. OPTION C UNIFIED BOTTOM FLOATING DOCK (Search Pill + Direct Import Files)
        // =====================================================================
        AnimatedVisibility(
            visible = !isSelectionMode,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 16.dp + navBarPadding.calculateBottomPadding()
                )
        ) {
            FloatingSearchAndImportDock(
                onSearchClick = onOpenSearch,
                onImportClick = onImportFiles
            )
        }
    }
}
