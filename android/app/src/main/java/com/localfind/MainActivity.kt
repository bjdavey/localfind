package com.localfind

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.localfind.data.AppSettings
import com.localfind.data.EngineManager
import com.localfind.data.FileAvailability
import com.localfind.data.FileImporter
import com.localfind.data.FileStore
import com.localfind.data.Folder
import com.localfind.data.ImportedFile
import com.localfind.data.IndexingStatus
import com.localfind.data.ModelStatus
import com.localfind.data.SettingsStore
import com.localfind.index.IndexingProgressState
import com.localfind.index.IndexingResumeManager
import com.localfind.index.IndexingWorker
import com.localfind.search.SearchResult
import com.localfind.ui.gallery.GalleryCategory
import com.localfind.ui.gallery.GalleryScreen
import com.localfind.ui.gallery.ThumbnailHelper
import com.localfind.ui.search.SearchScreen
import com.localfind.ui.settings.SettingsScreen
import com.localfind.ui.theme.LocalFindTheme
import com.localfind.ui.viewer.ViewerOverlayHost
import com.localfind.ui.viewer.rememberViewerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main application activity for LocalFind.
 *
 * Implements a single-activity architecture with Jetpack Compose.
 * All operations run completely offline with no network permissions or broad storage permissions.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge drawing for modern Android system bars
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val systemDark = isSystemInDarkTheme()
            val settingsStore = remember { SettingsStore(context) }
            var currentSettings by remember { mutableStateOf(settingsStore.load(defaultDarkMode = systemDark)) }

            // If user hasn't explicitly set a preference, automatically follow the phone's system theme
            LaunchedEffect(systemDark) {
                if (!settingsStore.hasCustomTheme()) {
                    currentSettings = currentSettings.copy(darkMode = systemDark)
                }
            }

            LocalFindTheme(darkTheme = currentSettings.darkMode) {
                MainScreen(
                    settings = currentSettings,
                    onSettingsChange = { updated ->
                        currentSettings = updated
                        settingsStore.save(updated)
                    },
                    onResetDefaults = {
                        val defaults = settingsStore.reset(defaultDarkMode = systemDark)
                        currentSettings = defaults
                    }
                )
            }
        }
    }
}

/**
 * Primary UI coordinator for LocalFind:
 * - Default view is the Google Photos-inspired [GalleryScreen] with 2-column visual tiles
 *   and the Option C unified bottom floating dock (`[ 🔍 Search... | + ]`).
 * - Tapping the bottom search pill transitions smoothly into the dedicated [SearchScreen].
 * - Tapping any media tile or search result transitions smoothly into its builtin viewer
 *   via [ViewerOverlayHost].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: AppSettings = AppSettings(),
    onSettingsChange: (AppSettings) -> Unit = {},
    onResetDefaults: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Observable on-device multimodal model lifecycle status
    val modelStatus by EngineManager.modelStatus.collectAsState()

    // Observable background indexing state from WorkManager
    val indexingProgress by IndexingProgressState.progress.collectAsState()
    val workManager = remember { WorkManager.getInstance(context) }
    val workInfos by workManager.getWorkInfosForUniqueWorkFlow(IndexingWorker.WORK_NAME)
        .collectAsState(initial = emptyList())
    val activeWorkInfo = workInfos.firstOrNull()
    val isWorkActive = activeWorkInfo?.state == WorkInfo.State.RUNNING ||
            activeWorkInfo?.state == WorkInfo.State.ENQUEUED
    val isIndexing = indexingProgress.isIndexing || isWorkActive

    // UI state
    var status by remember { mutableStateOf("Ready") }
    var importedFiles by remember { mutableStateOf<List<ImportedFile>>(emptyList()) }
    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var indexedChunksCount by remember { mutableIntStateOf(0) }

    // Navigation & Search state
    var isSearchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchCategory by remember { mutableStateOf(GalleryCategory.ALL) }
    var activeSearchFile by remember { mutableStateOf<ImportedFile?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }

    // Modality categories with at least one imported file in the app (plus ALL).
    // Hides filter chips for categories with 0 files in the app.
    val availableCategories = remember(importedFiles) {
        ThumbnailHelper.getAvailableCategories(importedFiles)
    }

    LaunchedEffect(availableCategories) {
        if (searchCategory !in availableCategories) {
            searchCategory = GalleryCategory.ALL
        }
    }

    // Active full-screen builtin viewers managed by ViewerState
    val viewerState = rememberViewerState()

    var filePendingDeletion by remember { mutableStateOf<ImportedFile?>(null) }
    var missingFileToDelete by remember { mutableStateOf<ImportedFile?>(null) }
    var isSettingsOpen by remember { mutableStateOf(false) }

    // Folder back navigation support
    val currentFolder = remember(folders, currentFolderId) {
        folders.firstOrNull { it.id == currentFolderId }
    }
    BackHandler(
        enabled = currentFolderId != null && !isSearchOpen && !isSettingsOpen &&
                !viewerState.isAnyViewerOpen
    ) {
        currentFolderId = currentFolder?.parentId
    }

    // Hoisted scroll states to guarantee scroll position is never lost when opening/closing files or search
    val galleryGridState = rememberLazyGridState()
    val searchListState = rememberLazyListState()

    /**
     * Retries loading the model and resumes background indexing if files are pending.
     */
    fun retryLoadModel() {
        scope.launch(Dispatchers.IO) {
            status = "Loading model..."
            val result = EngineManager.ensureModelLoaded(context)
            withContext(Dispatchers.Main) {
                if (result is ModelStatus.Loaded) {
                    status = "Model loaded successfully"
                    Toast.makeText(context, "Model loaded (${result.sizeBytes / 1_048_576} MB)", Toast.LENGTH_SHORT).show()
                } else if (result is ModelStatus.Error) {
                    status = "Model load failed"
                }
            }
            if (result is ModelStatus.Loaded) {
                val store = FileStore.getInstance(context)
                if (store.getPendingFiles().isNotEmpty()) {
                    IndexingWorker.enqueue(context, replace = true)
                }
            }
        }
    }

    /**
     * Queries [EmbeddingStore] via [EngineManager] to count total chunks in the vector database.
     */
    suspend fun refreshIndexCount() {
        withContext(Dispatchers.IO) {
            EngineManager.withStore(context) { store ->
                try {
                    indexedChunksCount = store.count()
                } catch (_: Exception) {
                    // Ignore if database is temporarily busy
                }
            }
        }
    }

    suspend fun reloadFiles() {
        withContext(Dispatchers.IO) {
            val store = FileStore.getInstance(context)
            importedFiles = store.getAll()
            folders = store.getAllFolders()
        }
        refreshIndexCount()
    }

    // Load model and persisted files on startup, automatically resuming background indexing if ready
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            EngineManager.ensureModelLoaded(context)
            val store = FileStore.getInstance(context)
            // Revert any files left in INDEXING status from an interrupted session back to PENDING
            val pending = store.getPendingFiles()
            for (f in pending) {
                if (f.indexingStatus == IndexingStatus.INDEXING) {
                    val resumeChunk = IndexingResumeManager.getResumeChunk(context, f.uri, f.size)
                    store.updateStatus(f.uri, IndexingStatus.PENDING, resumeChunk)
                }
            }
            importedFiles = store.getAll()
            folders = store.getAllFolders()
            // If the app was closed/killed while indexing was in-flight, resume immediately via WorkManager unless paused
            val pendingCount = store.getPendingFiles().size
            if (pendingCount > 0) {
                if (IndexingWorker.isPaused(context)) {
                    IndexingProgressState.setPaused(true, "Indexing paused ($pendingCount pending)")
                } else {
                    IndexingWorker.enqueue(context, replace = true)
                }
            }
        }
        refreshIndexCount()
    }

    // Refresh file statuses and index counts as files transition lifecycle states
    LaunchedEffect(
        activeWorkInfo?.state,
        indexingProgress.currentUri,
        indexingProgress.isIndexing,
        indexingProgress.fileIndex
    ) {
        reloadFiles()
        if (!isIndexing && !indexingProgress.isIndexing && !indexingProgress.isPaused) {
            status = "Ready"
        }
    }

    /**
     * Removes an imported file from the local library:
     * 1. Releases persistable SAF URI read grant (if held) and evicts cached thumbnail preview.
     * 2. Deletes the file metadata record from [FileStore].
     * 3. Deletes all associated embedding vectors from [EmbeddingStore].
     * 4. Updates library UI state and refreshes the total indexed chunks count.
     */
    fun removeFile(file: ImportedFile) {
        scope.launch {
            status = "Removing ${file.displayName}..."
            withContext(Dispatchers.IO) {
                // If file is actively indexing or queued, abort it immediately
                IndexingWorker.cancelFile(context, file.uri)
                ThumbnailHelper.evictPreview(file.uri)

                try {
                    context.contentResolver.releasePersistableUriPermission(
                        file.uri.toUri(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Non-SAF URIs or revoked grants don't have persistable grants
                }

                val fileStore = FileStore.getInstance(context)
                fileStore.delete(file.uri)
                val updatedFiles = fileStore.getAll()

                EngineManager.withStore(context) { store ->
                    try {
                        store.deleteByFileUri(file.uri)
                    } catch (_: Exception) {
                        // Ignore if store is temporarily inaccessible
                    }
                }

                withContext(Dispatchers.Main) {
                    importedFiles = updatedFiles
                    searchResults = searchResults.filter { it.fileUri != file.uri }
                }
            }

            refreshIndexCount()
            status = "Removed ${file.displayName}"
            Toast.makeText(
                context,
                "Removed ${file.displayName} from library",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Prepares and navigates to the search view scoped to a specific [target] file.
     */
    fun startSearchInFile(target: ImportedFile) {
        if (!FileAvailability.isFileAvailable(context, target.uri)) {
            missingFileToDelete = target
            return
        }
        activeSearchFile = target
        searchCategory = GalleryCategory.ALL
        searchQuery = ""
        hasSearched = false
        searchResults = emptyList()
        searchError = null
        // Close any active viewers first so the search screen becomes active
        viewerState.closeAll()
        isSearchOpen = true
    }

    /**
     * Executes a semantic search query against the local vector database.
     * Searches after filtering by modality [category] or scoped [activeSearchFile]
     * ("Search after filter, not filter search results"), preventing unrelated modalities
     * from consuming the top-K budget.
     */
    fun performSearch(queryText: String, category: GalleryCategory = searchCategory) {
        val trimmed = queryText.trim()
        if (trimmed.isBlank() || isSearching) return
        if (modelStatus is ModelStatus.Error) {
            searchError = "Search unavailable: Embedding model is not loaded."
            hasSearched = true
            return
        }
        val fileFilter = activeSearchFile?.let { mapOf("fileId" to it.uri) } ?: emptyMap()
        val categoryTypes = if (activeSearchFile != null) null else category.types
        scope.launch {
            isSearching = true
            searchError = null
            try {
                val results = withContext(Dispatchers.Default) {
                    EngineManager.withSearcher(context) { searcher ->
                        searcher.search(
                            query = trimmed,
                            topK = settings.topK,
                            threshold = settings.similarityThreshold,
                            metadataFilter = fileFilter,
                            fileTypes = categoryTypes
                        )
                    }
                }
                searchResults = results
                hasSearched = true
                scope.launch {
                    try {
                        searchListState.scrollToItem(0)
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                searchError = e.message ?: "An error occurred during search"
                searchResults = emptyList()
                hasSearched = true
            } finally {
                isSearching = false
            }
        }
    }

    // System file picker launcher using Storage Access Framework (SAF)
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val (supportedFiles, unsupportedCount) = withContext(Dispatchers.IO) {
                    val store = FileStore.getInstance(context)
                    val supported = mutableListOf<ImportedFile>()
                    var unsupported = 0

                    for (uri in uris) {
                        val imported = FileImporter.importUri(context.contentResolver, uri, folderId = currentFolderId)
                        if (imported != null) {
                            supported.add(imported.copy(indexingStatus = IndexingStatus.PENDING))
                        } else {
                            unsupported++
                        }
                    }

                    if (supported.isNotEmpty()) {
                        store.insertAll(supported)
                        importedFiles = store.getAll()
                    }

                    Pair(supported, unsupported)
                }

                if (supportedFiles.isNotEmpty()) {
                    if (IndexingWorker.isPaused(context)) {
                        val pendingCount = withContext(Dispatchers.IO) { FileStore.getInstance(context).getPendingFiles().size }
                        IndexingProgressState.setPaused(true, "Indexing paused ($pendingCount pending)")
                    } else {
                        IndexingWorker.enqueue(context)
                    }
                    if (unsupportedCount > 0) {
                        val msg = "Imported ${supportedFiles.size} file(s). Skipped $unsupportedCount unsupported file(s)."
                        status = msg
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    } else {
                        val msg = "Imported ${supportedFiles.size} file(s)"
                        status = msg
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                } else if (unsupportedCount > 0) {
                    val msg = "Cannot import unsupported file(s). Only files with builtin viewers (text, PDF, image, audio, video) are supported."
                    status = msg
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // System folder picker launcher using Storage Access Framework (SAF OpenDocumentTree)
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            scope.launch {
                status = "Importing folder..."
                val result = withContext(Dispatchers.IO) {
                    FileImporter.importTreeUri(context, treeUri, parentFolderId = currentFolderId)
                }
                reloadFiles()
                if (result.importedFiles.isNotEmpty()) {
                    if (IndexingWorker.isPaused(context)) {
                        val pendingCount = withContext(Dispatchers.IO) { FileStore.getInstance(context).getPendingFiles().size }
                        IndexingProgressState.setPaused(true, "Indexing paused ($pendingCount pending)")
                    } else {
                        IndexingWorker.enqueue(context)
                    }
                    val folderName = result.createdFolders.firstOrNull()?.name ?: "folder"
                    val msg = "Imported ${result.importedFiles.size} file(s) into \"$folderName\"" +
                            if (result.skippedUnsupportedCount > 0) " (${result.skippedUnsupportedCount} unsupported skipped)" else ""
                    status = msg
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                } else if (result.createdFolders.isNotEmpty()) {
                    val folderName = result.createdFolders.first().name
                    val msg = if (result.skippedUnsupportedCount > 0)
                        "Created folder \"$folderName\" (${result.skippedUnsupportedCount} unsupported file(s) skipped)"
                    else "Created empty folder \"$folderName\""
                    status = msg
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun createFolder(name: String) {
        scope.launch(Dispatchers.IO) {
            val store = FileStore.getInstance(context)
            store.createFolder(name, currentFolderId)
            reloadFiles()
        }
    }

    fun renameFolder(folder: Folder, newName: String) {
        scope.launch(Dispatchers.IO) {
            val store = FileStore.getInstance(context)
            store.renameFolder(folder.id, newName)
            reloadFiles()
        }
    }

    fun deleteFolder(folder: Folder) {
        scope.launch {
            status = "Deleting folder ${folder.name}..."
            val deletedUris = withContext(Dispatchers.IO) {
                val store = FileStore.getInstance(context)
                val uris = store.deleteFolder(folder.id)
                if (uris.isNotEmpty()) {
                    for (uri in uris) {
                        // Cancel any in-flight indexing and clear resume checkpoints
                        IndexingWorker.cancelFile(context, uri)
                        ThumbnailHelper.evictPreview(uri)
                        try {
                            context.contentResolver.releasePersistableUriPermission(
                                uri.toUri(),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (_: Exception) {
                            // Non-SAF URIs or folder-tree child URIs may not hold individual grants
                        }
                    }
                    EngineManager.withStore(context) { vecStore ->
                        for (uri in uris) {
                            try {
                                vecStore.deleteByFileUri(uri)
                            } catch (_: Exception) {}
                        }
                    }
                }
                uris
            }
            if (deletedUris.isNotEmpty()) {
                val deletedSet = deletedUris.toSet()
                searchResults = searchResults.filter { it.fileUri !in deletedSet }
            }
            if (currentFolderId == folder.id) {
                currentFolderId = folder.parentId
            }
            reloadFiles()
            val msg = "Removed folder and ${deletedUris.size} file(s)"
            status = msg
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun moveFileToFolder(file: ImportedFile, targetFolderId: String?) {
        scope.launch(Dispatchers.IO) {
            val store = FileStore.getInstance(context)
            store.updateFileFolder(file.uri, targetFolderId)
            reloadFiles()
        }
    }

    // Confirmation dialog before removing a file and deleting its embeddings
    filePendingDeletion?.let { file ->
        AlertDialog(
            onDismissRequest = { filePendingDeletion = null },
            title = { Text("Remove file?") },
            text = {
                Text(
                    "Remove \"${file.displayName}\" from LocalFind library and delete all its indexed search embeddings?\n\n" +
                            "The original file on your device will NOT be deleted."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = file
                        filePendingDeletion = null
                        removeFile(toDelete)
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { filePendingDeletion = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Prompt when an original file is missing/deleted from storage
    missingFileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { missingFileToDelete = null },
            title = { Text("File Not Found") },
            text = {
                Text(
                    "\"${file.displayName}\" could not be found on your device. The original file may have been moved or deleted.\n\n" +
                            "Would you like to remove it from LocalFind and clean up its search index?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = file
                        missingFileToDelete = null
                        removeFile(toDelete)
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { missingFileToDelete = null }) {
                    Text("Keep")
                }
            }
        )
    }

    // Layered Composition Architecture:
    // 1. BASE SCREEN: GalleryScreen is always composed at the base of the Box. Its grid state
    //    and cached tiles are never destroyed, guaranteeing zero scroll reset and zero reload flicker.
    // 2. SEARCH & SETTINGS: Smooth slide/fade overlay screens on top of the gallery.
    // 3. BUILTIN VIEWERS: Hosted via ViewerOverlayHost with scale + fade / slide + fade animations.
    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Gallery Base Screen
        GalleryScreen(
            importedFiles = importedFiles,
            indexedChunksCount = indexedChunksCount,
            isIndexing = isIndexing,
            statusMessage = if (indexingProgress.isIndexing && indexingProgress.message.isNotBlank()) {
                indexingProgress.message
            } else {
                status
            },
            indexingProgress = indexingProgress,
            modelStatus = modelStatus,
            folders = folders,
            currentFolderId = currentFolderId,
            onNavigateFolder = { currentFolderId = it },
            onCreateFolder = { createFolder(it) },
            onRenameFolder = { folder, newName -> renameFolder(folder, newName) },
            onDeleteFolder = { folder -> deleteFolder(folder) },
            onImportFolder = { folderPickerLauncher.launch(null) },
            enableFolderImport = settings.enableFolderImport,
            onMoveFileToFolder = { file, targetFolderId -> moveFileToFolder(file, targetFolderId) },
            gridState = galleryGridState,
            onRetryLoadModel = { retryLoadModel() },
            onOpenSearch = {
                activeSearchFile = null
                searchCategory = GalleryCategory.ALL
                isSearchOpen = true
            },
            onSearchInFile = { file -> startSearchInFile(file) },
            onImportFiles = { pickerLauncher.launch(FileImporter.SUPPORTED_MIME_TYPES) },
            onOpenSettings = { isSettingsOpen = true },
            onFileClick = { file ->
                viewerState.openLibraryFile(context, file) { missingFileToDelete = it }
            },
            onRequestDeleteFile = { file -> filePendingDeletion = file },
            onRetryIndexFile = { file ->
                if (!FileAvailability.isFileAvailable(context, file.uri)) {
                    missingFileToDelete = file
                } else {
                    scope.launch(Dispatchers.IO) {
                        val store = FileStore.getInstance(context)
                        store.updateStatus(file.uri, IndexingStatus.PENDING)
                        importedFiles = store.getAll()
                        IndexingWorker.enqueue(context, replace = true)
                    }
                }
            },
            onPauseIndexing = {
                scope.launch(Dispatchers.IO) {
                    IndexingWorker.pause(context)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Indexing paused", Toast.LENGTH_SHORT).show()
                    }
                    reloadFiles()
                }
            },
            onResumeIndexing = {
                scope.launch(Dispatchers.IO) {
                    IndexingWorker.resume(context)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Indexing resumed", Toast.LENGTH_SHORT).show()
                    }
                    reloadFiles()
                }
            },
            onCancelIndexing = {
                scope.launch(Dispatchers.IO) {
                    IndexingWorker.cancel(context)
                    withContext(Dispatchers.Main) {
                        status = "Ready"
                        Toast.makeText(context, "Indexing cancelled", Toast.LENGTH_SHORT).show()
                    }
                    reloadFiles()
                }
            },
            onCancelIndexFile = { file ->
                scope.launch(Dispatchers.IO) {
                    IndexingWorker.cancelFile(context, file.uri)
                    withContext(Dispatchers.Main) {
                        status = "Ready"
                        Toast.makeText(context, "Cancelled sync for ${file.displayName}", Toast.LENGTH_SHORT).show()
                    }
                    reloadFiles()
                }
            }
        )

        // 2. Dedicated Search Screen (Overlay)
        AnimatedVisibility(
            visible = isSearchOpen,
            enter = fadeIn(animationSpec = tween(200)) + slideInVertically(
                initialOffsetY = { (it * 0.06f).toInt() },
                animationSpec = tween(220, easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(
                targetOffsetY = { (it * 0.06f).toInt() },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            )
        ) {
            SearchScreen(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = { performSearch(searchQuery, searchCategory) },
                onClear = {
                    searchQuery = ""
                    hasSearched = false
                    searchResults = emptyList()
                    searchError = null
                    searchCategory = GalleryCategory.ALL
                    scope.launch {
                        try {
                            searchListState.scrollToItem(0)
                        } catch (_: Exception) {
                        }
                    }
                },
                isSearching = isSearching,
                hasSearched = hasSearched,
                searchResults = searchResults,
                selectedCategory = searchCategory,
                onCategoryChange = { newCategory ->
                    searchCategory = newCategory
                    if (searchQuery.isNotBlank() && hasSearched) {
                        performSearch(searchQuery, newCategory)
                    }
                },
                availableCategories = availableCategories,
                listState = searchListState,
                backHandlerEnabled = isSearchOpen && !viewerState.isAnyViewerOpen,
                onResultClick = { result ->
                    viewerState.openSearchResult(context, result, importedFiles) {
                        missingFileToDelete = it
                    }
                },
                targetFile = activeSearchFile,
                onClearTargetFile = {
                    activeSearchFile = null
                    searchCategory = GalleryCategory.ALL
                    if (searchQuery.isNotBlank()) {
                        performSearch(searchQuery, GalleryCategory.ALL)
                    }
                },
                errorMessage = searchError ?: if (modelStatus is ModelStatus.Error) (modelStatus as ModelStatus.Error).message else null,
                indexedChunksCount = indexedChunksCount,
                onClose = {
                    isSearchOpen = false
                    activeSearchFile = null
                    searchCategory = GalleryCategory.ALL
                }
            )
        }

        // 3. Settings Screen (Overlay)
        AnimatedVisibility(
            visible = isSettingsOpen,
            enter = fadeIn(animationSpec = tween(200)) + slideInHorizontally(
                initialOffsetX = { (it * 0.15f).toInt() },
                animationSpec = tween(240, easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(animationSpec = tween(180)) + slideOutHorizontally(
                targetOffsetX = { (it * 0.15f).toInt() },
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            )
        ) {
            SettingsScreen(
                settings = settings,
                onSettingsChange = onSettingsChange,
                onResetDefaults = onResetDefaults,
                onReindexAll = {
                    scope.launch(Dispatchers.IO) {
                        IndexingResumeManager.clearAll(context)
                        val store = FileStore.getInstance(context)
                        store.markAllPending()
                        importedFiles = store.getAll()
                        IndexingWorker.enqueue(context, replace = true)
                    }
                    isSettingsOpen = false
                },
                isIndexing = isIndexing,
                fileCount = importedFiles.size,
                modelStatus = modelStatus,
                onRetryLoadModel = { retryLoadModel() },
                backHandlerEnabled = isSettingsOpen,
                onClose = { isSettingsOpen = false }
            )
        }

        // 4. Builtin Viewers (Image, Media Player, PDF, Text)
        ViewerOverlayHost(
            viewerState = viewerState,
            importedFiles = importedFiles,
            onSearchInFile = { file -> startSearchInFile(file) }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    LocalFindTheme {
        MainScreen()
    }
}
