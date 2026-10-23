package com.localfind

import com.localfind.index.IndexingQueue
import com.localfind.core.library.LibraryFilters
import com.localfind.core.model.FileTypes
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.localfind.core.settings.AppSettings
import com.localfind.data.EngineManager
import com.localfind.data.FileAvailability
import com.localfind.data.FileStore
import com.localfind.core.model.Folder
import com.localfind.core.model.ImportedFile
import com.localfind.core.model.ModelStatus
import com.localfind.data.SettingsStore
import com.localfind.data.ShareImporter
import com.localfind.core.settings.ThemeMode
import com.localfind.index.IndexingProgressState
import com.localfind.index.IndexingResumeManager
import com.localfind.ui.ModelLoadingOverlay
import com.localfind.core.library.GalleryCategory
import com.localfind.ui.main.ConfirmRemoveFileDialog
import com.localfind.ui.main.MissingFileDialog
import com.localfind.ui.main.rememberLibraryState
import com.localfind.ui.main.rememberSearchState
import com.localfind.ui.gallery.GalleryScreen
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
class MainActivity : FragmentActivity() {
    /** Observable pending share intent received from an external application */
    private var pendingShareIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If launched via ACTION_SEND or ACTION_SEND_MULTIPLE, stage intent for processing
        if (ShareImporter.isShareIntent(intent)) {
            pendingShareIntent = intent
            // Consume action on activity's main intent so configuration changes (e.g. rotation) don't re-trigger import
            intent = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        }

        // Enable edge-to-edge drawing for modern Android system bars
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val systemDark = isSystemInDarkTheme()
            val settingsStore = remember { SettingsStore(context) }
            var currentSettings by remember { mutableStateOf(settingsStore.load(defaultDarkMode = systemDark)) }

            // If theme mode is SYSTEM, automatically follow the phone's system theme
            LaunchedEffect(systemDark, currentSettings.themeMode) {
                if (currentSettings.themeMode == ThemeMode.SYSTEM) {
                    currentSettings = currentSettings.copy(darkMode = systemDark)
                }
            }

            LocalFindTheme(darkTheme = currentSettings.darkMode) {
                MainScreen(
                    settings = currentSettings,
                    pendingShareIntent = pendingShareIntent,
                    onShareIntentHandled = { pendingShareIntent = null },
                    onSettingsChange = { updated ->
                        val acceleratorChanged = updated.accelerator != currentSettings.accelerator
                        currentSettings = updated
                        settingsStore.save(updated)
                        if (acceleratorChanged) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                EngineManager.updateAccelerator(applicationContext, updated.accelerator)
                            }
                        }
                    },
                    onResetDefaults = {
                        val previousAccelerator = currentSettings.accelerator
                        val defaults = settingsStore.reset(defaultDarkMode = systemDark)
                        currentSettings = defaults
                        if (defaults.accelerator != previousAccelerator) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                EngineManager.updateAccelerator(applicationContext, defaults.accelerator)
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle share intents delivered while MainActivity was already alive
        if (ShareImporter.isShareIntent(intent)) {
            pendingShareIntent = intent
            setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
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
@Composable
fun MainScreen(
    settings: AppSettings,
    pendingShareIntent: Intent?,
    onShareIntentHandled: () -> Unit,
    onSettingsChange: (AppSettings) -> Unit,
    onResetDefaults: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Observable on-device multimodal model lifecycle status
    val modelStatus by EngineManager.modelStatus.collectAsState()
    // Embedding space of the loaded model / accelerator; files indexed in another one are flagged for re-analysis
    val embeddingSpaceId by EngineManager.embeddingSpaceId.collectAsState()

    // Observable background indexing state from WorkManager
    val indexingProgress by IndexingProgressState.progress.collectAsState()
    val workManager = remember { WorkManager.getInstance(context) }
    val workInfos by workManager.getWorkInfosForUniqueWorkFlow(IndexingQueue.WORK_NAME)
        .collectAsState(initial = emptyList())
    val activeWorkInfo = workInfos.firstOrNull()
    val isWorkActive = activeWorkInfo?.state == WorkInfo.State.RUNNING ||
            activeWorkInfo?.state == WorkInfo.State.ENQUEUED
    val isIndexing = indexingProgress.isIndexing || isWorkActive

    // UI state
    // Library data and the operations on it live in LibraryState; these aliases keep reads and
    // writes below going through the same observable state.
    val library = rememberLibraryState()
    var status by library::status
    var importedFiles by library::importedFiles
    var folders by library::folders
    var currentFolderId by library::currentFolderId
    var indexedChunksCount by library::indexedChunksCount

    // Modality categories with at least one imported file in the app (plus ALL).
    // Hides filter chips for categories with 0 files in the app.
    val availableCategories = remember(importedFiles) {
        LibraryFilters.getAvailableCategories(importedFiles)
    }

    // Active full-screen builtin viewers managed by ViewerState
    val viewerState = rememberViewerState()

    var filePendingDeletion by remember { mutableStateOf<ImportedFile?>(null) }
    var missingFileToDelete by remember { mutableStateOf<ImportedFile?>(null) }
    var isSettingsOpen by remember { mutableStateOf(false) }

    // Hoisted scroll states to guarantee scroll position is never lost when opening/closing files or search
    val galleryGridState = rememberLazyGridState()
    val searchListState = rememberLazyListState()

    // Search state and logic live in SearchState; aliases keep the gating logic below readable.
    val search = rememberSearchState(
        listState = searchListState,
        settings = settings,
        availableCategories = availableCategories,
        onMissingFile = { missingFileToDelete = it },
        closeViewers = { viewerState.closeAll() }
    )
    var isSearchOpen by search::isSearchOpen
    var searchCategory by search::searchCategory
    var activeSearchFile by search::activeSearchFile

    // Library operations and the library's own indexing/missing-file callbacks into the UI
    SideEffect {
        library.onFilesRemoved = search::removeResultsFor
        library.onMissingFile = { missingFileToDelete = it }
    }

    LaunchedEffect(availableCategories) {
        if (searchCategory !in availableCategories) {
            searchCategory = GalleryCategory.ALL
        }
    }

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

    // Load model and persisted files on startup, automatically resuming background indexing if ready
    LaunchedEffect(Unit) { library.loadInitial() }

    // Refresh file statuses and index counts as files transition lifecycle states
    LaunchedEffect(
        activeWorkInfo?.state,
        indexingProgress.currentUri,
        indexingProgress.isIndexing,
        indexingProgress.fileIndex
    ) {
        library.reloadFiles()
        if (!isIndexing && !indexingProgress.isIndexing && !indexingProgress.isPaused) {
            status = "Ready"
        }
    }

    // Process incoming shared files or notes from other apps (ACTION_SEND / ACTION_SEND_MULTIPLE)
    LaunchedEffect(pendingShareIntent) {
        val shareIntent = pendingShareIntent ?: return@LaunchedEffect
        library.importShared(shareIntent)
        onShareIntentHandled()
    }


    // System file picker launcher using Storage Access Framework (SAF)
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> library.importPicked(uris) }

    // System folder picker launcher using Storage Access Framework (SAF OpenDocumentTree)
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri -> library.importTree(treeUri) }

    // Confirmation dialogs: removing a file from the library, and a library file whose original is gone
    filePendingDeletion?.let { file ->
        ConfirmRemoveFileDialog(
            file = file,
            onConfirm = {
                filePendingDeletion = null
                library.removeFile(file)
            },
            onDismiss = { filePendingDeletion = null }
        )
    }
    missingFileToDelete?.let { file ->
        MissingFileDialog(
            file = file,
            onRemove = {
                missingFileToDelete = null
                library.removeFile(file)
            },
            onKeep = { missingFileToDelete = null }
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
            availableCategories = availableCategories,
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
            onNavigateFolder = {
                if (!isSearchOpen && !isSettingsOpen && !viewerState.isAnyViewerOpen) currentFolderId = it
            },
            onCreateFolder = { library.createFolder(it) },
            onRenameFolder = { folder, newName -> library.renameFolder(folder, newName) },
            onDeleteFolder = { folder -> library.deleteFolder(folder) },
            onImportFolder = {
                if (!isSearchOpen && !isSettingsOpen && !viewerState.isAnyViewerOpen) folderPickerLauncher.launch(null)
            },
            enableFolderImport = settings.enableFolderImport,
            onMoveFileToFolder = { file, targetFolderId -> library.moveFileToFolder(file, targetFolderId) },
            onMoveMultipleFiles = { files, targetFolderId -> library.moveFilesToFolder(files, targetFolderId) },
            onDeleteMultipleFiles = { files -> library.removeFiles(files) },
            gridState = galleryGridState,
            onRetryLoadModel = { library.retryLoadModel() },
            onOpenSearch = {
                if (!isSearchOpen && !isSettingsOpen && !viewerState.isAnyViewerOpen) {
                    activeSearchFile = null
                    searchCategory = GalleryCategory.ALL
                    isSearchOpen = true
                }
            },
            onSearchInFile = { file ->
                if (!isSearchOpen && !isSettingsOpen && !viewerState.isAnyViewerOpen) {
                    search.startSearchInFile(file)
                }
            },
            onImportFiles = {
                if (!isSearchOpen && !isSettingsOpen && !viewerState.isAnyViewerOpen) {
                    pickerLauncher.launch(FileTypes.SUPPORTED_MIME_TYPES)
                }
            },
            onOpenSettings = {
                if (!isSearchOpen && !isSettingsOpen && !viewerState.isAnyViewerOpen) {
                    isSettingsOpen = true
                }
            },
            onFileClick = { file ->
                if (!isSearchOpen && !isSettingsOpen && !viewerState.isAnyViewerOpen) {
                    scope.launch { viewerState.openLibraryFile(context, file) { missingFileToDelete = it } }
                }
            },
            onRequestDeleteFile = { file ->
                if (!isSearchOpen && !isSettingsOpen && !viewerState.isAnyViewerOpen) {
                    filePendingDeletion = file
                }
            },
            onRetryIndexFile = library::retryFile,
            onRetryAllFailed = library::retryAllFailed,
            currentEmbeddingSpaceId = embeddingSpaceId,
            onReindexOutdated = library::reindexOutdated,
            onPauseIndexing = library::pauseIndexing,
            onResumeIndexing = library::resumeIndexing,
            onCancelIndexing = library::cancelIndexing,
            onCancelIndexFile = library::cancelFile
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
                modifier = Modifier.fillMaxSize(),
                query = search.searchQuery,
                onQueryChange = search::onQueryChange,
                onSearch = { search.performSearch(search.searchQuery, search.searchCategory) },
                onClear = search::clearSearch,
                isSearching = search.isSearching,
                hasSearched = search.hasSearched,
                searchResults = search.searchResults,
                selectedCategory = search.searchCategory,
                onCategoryChange = search::changeCategory,
                availableCategories = availableCategories,
                listState = searchListState,
                backHandlerEnabled = isSearchOpen && !viewerState.isAnyViewerOpen,
                onResultClick = { result ->
                    scope.launch {
                        viewerState.openSearchResult(context, result, importedFiles) { missingFileToDelete = it }
                    }
                },
                targetFile = search.activeSearchFile,
                onClearTargetFile = search::clearTargetFile,
                errorMessage = search.searchError ?: if (modelStatus is ModelStatus.Error) (modelStatus as ModelStatus.Error).message else null,
                indexedChunksCount = indexedChunksCount,
                onClose = search::close,
                queryImageUri = search.searchImageUri,
                queryImageName = search.searchImageName,
                onSelectQueryImage = { uri -> search.performImageSearch(uri) },
                onClearQueryImage = search::clearQueryImage
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
                        IndexingQueue.enqueue(context, replace = true)
                    }
                    isSettingsOpen = false
                },
                isIndexing = isIndexing,
                fileCount = importedFiles.size,
                modelStatus = modelStatus,
                onRetryLoadModel = { library.retryLoadModel() },
                backHandlerEnabled = isSettingsOpen,
                onClose = { isSettingsOpen = false }
            )
        }

        // 4. Builtin Viewers (Image, Media Player, PDF, Text)
        ViewerOverlayHost(
            viewerState = viewerState,
            importedFiles = importedFiles,
            onSearchInFile = { file -> search.startSearchInFile(file) },
            onSearchSimilarImage = { uriStr, name ->
                scope.launch {
                    // Content provider access is IPC, so check the original off the main thread
                    val available = withContext(Dispatchers.IO) { FileAvailability.isFileAvailable(context, uriStr) }
                    if (!available) {
                        val matchedFile = importedFiles.firstOrNull { it.uri == uriStr }
                        if (matchedFile != null) missingFileToDelete = matchedFile
                    } else {
                        activeSearchFile = null
                        searchCategory = GalleryCategory.PHOTOS
                        isSearchOpen = true
                        search.performImageSearch(
                            imageUri = uriStr.toUri(),
                            imageName = name,
                            category = GalleryCategory.PHOTOS,
                            excludeUri = uriStr
                        )
                    }
                }
            }
        )

        // 5. Model Loading Splash / Modal Progress Overlay (Blocks user interactions while LiteRT engine loads)
        ModelLoadingOverlay(
            isLoading = modelStatus is ModelStatus.Loading
        )
    }
}

