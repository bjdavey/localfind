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
import com.localfind.data.ShareImporter
import com.localfind.data.ThemeMode
import com.localfind.index.IndexingProgressState
import com.localfind.index.IndexingResumeManager
import com.localfind.index.IndexingWorker
import com.localfind.search.SearchResult
import com.localfind.ui.ModelLoadingOverlay
import com.localfind.ui.gallery.GalleryCategory
import com.localfind.ui.gallery.GalleryScreen
import com.localfind.ui.gallery.ThumbnailHelper
import com.localfind.ui.search.SearchScreen
import com.localfind.ui.settings.SettingsScreen
import com.localfind.ui.theme.LocalFindTheme
import com.localfind.ui.viewer.ViewerOverlayHost
import com.localfind.ui.viewer.rememberViewerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main application activity for LocalFind.
 *
 * Implements a single-activity architecture with Jetpack Compose.
 * All operations run completely offline with no network permissions or broad storage permissions.
 */
class MainActivity : ComponentActivity() {
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: AppSettings = AppSettings(),
    pendingShareIntent: Intent? = null,
    onShareIntentHandled: () -> Unit = {},
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
    var searchImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var searchImageName by remember { mutableStateOf<String?>(null) }
    val searchImageEmbeddingCache = remember { arrayOfNulls<FloatArray>(1) }
    var excludeSourceUri by remember { mutableStateOf<String?>(null) }
    var currentSearchJob by remember { mutableStateOf<Job?>(null) }

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
                    IndexingProgressState.setPaused(true, "Paused ($pendingCount pending)")
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

    // Process incoming shared files or notes from other apps (ACTION_SEND / ACTION_SEND_MULTIPLE)
    LaunchedEffect(pendingShareIntent) {
        val shareIntent = pendingShareIntent ?: return@LaunchedEffect
        status = "Importing shared content..."
        val result = withContext(Dispatchers.IO) {
            val res = ShareImporter.processShareIntent(context, shareIntent, folderId = currentFolderId)
            if (res.importedFiles.isNotEmpty()) {
                val store = FileStore.getInstance(context)
                store.insertAll(res.importedFiles)
            }
            res
        }
        reloadFiles()

        if (result.importedFiles.isNotEmpty()) {
            if (IndexingWorker.isPaused(context)) {
                val pendingCount = withContext(Dispatchers.IO) { FileStore.getInstance(context).getPendingFiles().size }
                IndexingProgressState.setPaused(true, "Paused ($pendingCount pending)")
            } else {
                IndexingWorker.enqueue(context)
            }

            val msg = if (result.isTextNote) {
                "Imported shared note"
            } else if (result.unsupportedCount > 0) {
                "Imported ${result.importedFiles.size} file(s) from share. Skipped ${result.unsupportedCount} unsupported file(s)."
            } else {
                "Imported ${result.importedFiles.size} file(s) from share"
            }
            status = msg
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } else if (result.unsupportedCount > 0) {
            val msg = "Cannot import unsupported file(s). Only files with builtin viewers (text, PDF, image, audio, video) are supported."
            status = msg
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }

        onShareIntentHandled()
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
                ShareImporter.deleteInternalFileIfShared(context, file.uri)

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
     * Batch removes multiple imported files from the local library.
     */
    fun removeFiles(files: List<ImportedFile>) {
        if (files.isEmpty()) return
        scope.launch {
            val count = files.size
            status = "Removing $count file(s)..."
            withContext(Dispatchers.IO) {
                val uris = files.map { it.uri }
                for (file in files) {
                    IndexingWorker.cancelFile(context, file.uri)
                    ThumbnailHelper.evictPreview(file.uri)
                    ShareImporter.deleteInternalFileIfShared(context, file.uri)
                    try {
                        context.contentResolver.releasePersistableUriPermission(
                            file.uri.toUri(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}
                }

                val fileStore = FileStore.getInstance(context)
                fileStore.deleteFiles(uris)
                val updatedFiles = fileStore.getAll()

                EngineManager.withStore(context) { store ->
                    for (uri in uris) {
                        try {
                            store.deleteByFileUri(uri)
                        } catch (_: Exception) {}
                    }
                }

                withContext(Dispatchers.Main) {
                    importedFiles = updatedFiles
                    val uriSet = uris.toSet()
                    searchResults = searchResults.filter { it.fileUri !in uriSet }
                }
            }

            refreshIndexCount()
            status = "Removed $count file(s)"
            Toast.makeText(
                context,
                "Removed $count file(s) from library",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Executes a visual similarity search given a query image URI.
     *
     * Decodes the image bitmap, computes its 768-dimensional normalized embedding via [EngineManager],
     * caches the embedding for instant filter chip switching, and searches nearest visual neighbors.
     */
    fun performImageSearch(
        imageUri: android.net.Uri,
        imageName: String? = null,
        category: GalleryCategory? = null,
        excludeUri: String? = null,
        useCachedEmbedding: Boolean = false
    ) {
        if (modelStatus is ModelStatus.Error) {
            searchError = "Search unavailable: Embedding model is not loaded."
            hasSearched = true
            return
        }

        currentSearchJob?.cancel()

        // If searching inside a specific target file (e.g. video or PDF), scope results to that file
        val fileFilter = activeSearchFile?.let { mapOf("fileId" to it.uri) } ?: emptyMap()

        // When scoped to a file, do not filter by category types so all chunks of that file are matched.
        // When searching all files, preserve or default to photos.
        val targetCategory = if (activeSearchFile != null) {
            searchCategory
        } else {
            category ?: when {
                searchCategory == GalleryCategory.PHOTOS || searchCategory == GalleryCategory.ALL -> searchCategory
                GalleryCategory.PHOTOS in availableCategories -> GalleryCategory.PHOTOS
                else -> GalleryCategory.ALL
            }
        }
        searchCategory = targetCategory

        searchImageUri = imageUri
        searchImageName = imageName ?: FileImporter.resolveDisplayName(context.contentResolver, imageUri)
        searchQuery = ""
        excludeSourceUri = excludeUri
        val categoryTypes = if (activeSearchFile != null) null else targetCategory.types

        currentSearchJob = scope.launch {
            isSearching = true
            searchError = null
            try {
                val results = withContext(Dispatchers.Default) {
                    val embedding = if (useCachedEmbedding && searchImageEmbeddingCache[0] != null) {
                        searchImageEmbeddingCache[0]!!
                    } else {
                        val existingEmbedding = withContext(Dispatchers.IO) {
                            try {
                                EngineManager.withStore(context) { store ->
                                    store.get("${imageUri}_0")?.embeddings
                                }
                            } catch (_: Exception) {
                                null
                            }
                        }

                        if (existingEmbedding != null && existingEmbedding.isNotEmpty()) {
                            existingEmbedding
                        } else {
                            val bitmap = withContext(Dispatchers.IO) {
                                com.localfind.index.image.ImageIndexer.decodeNormalizedBitmap(
                                    context,
                                    imageUri,
                                    maxDimension = settings.maxImageDimension
                                )
                            }
                            val emb = try {
                                EngineManager.withSearcher(context) { searcher ->
                                    searcher.embedImage(bitmap)
                                }
                            } finally {
                                bitmap.recycle()
                            }
                            emb
                        }
                    }

                    searchImageEmbeddingCache[0] = embedding

                    EngineManager.withSearcher(context) { searcher ->
                        searcher.searchByEmbedding(
                            queryEmbedding = embedding,
                            topK = settings.topK,
                            threshold = settings.similarityThreshold,
                            metadataFilter = fileFilter,
                            fileTypes = categoryTypes,
                            excludeFileUri = if (activeSearchFile != null) null else excludeUri
                        )
                    }
                }
                searchResults = results
                hasSearched = true
                scope.launch {
                    try {
                        searchListState.scrollToItem(0)
                    } catch (_: Exception) {}
                }
            } catch (_: CancellationException) {
                // Cancelled by a newer search request; do not show error
            } catch (e: Exception) {
                searchError = e.message ?: "Visual similarity search failed"
                searchResults = emptyList()
                hasSearched = true
            } finally {
                isSearching = false
            }
        }
    }

    /**
     * Prepares and navigates to the search view scoped to a specific [target] file.
     * If the target is an image, initiates visual similarity search to find similar photos.
     */
    fun startSearchInFile(target: ImportedFile) {
        if (!FileAvailability.isFileAvailable(context, target.uri)) {
            missingFileToDelete = target
            return
        }
        val isPhoto = ThumbnailHelper.categorizeFile(target.displayName, target.mimeType) == GalleryCategory.PHOTOS
        if (isPhoto) {
            activeSearchFile = null
            searchCategory = GalleryCategory.PHOTOS
            viewerState.closeAll()
            isSearchOpen = true
            performImageSearch(
                imageUri = target.uri.toUri(),
                imageName = target.displayName,
                category = GalleryCategory.PHOTOS,
                excludeUri = target.uri
            )
            return
        }
        currentSearchJob?.cancel()
        activeSearchFile = target
        searchCategory = GalleryCategory.ALL
        searchQuery = ""
        searchImageUri = null
        searchImageName = null
        searchImageEmbeddingCache[0] = null
        excludeSourceUri = null
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
        if (trimmed.isBlank()) return
        if (modelStatus is ModelStatus.Error) {
            searchError = "Search unavailable: Embedding model is not loaded."
            hasSearched = true
            return
        }
        currentSearchJob?.cancel()
        val fileFilter = activeSearchFile?.let { mapOf("fileId" to it.uri) } ?: emptyMap()
        val categoryTypes = if (activeSearchFile != null) null else category.types
        currentSearchJob = scope.launch {
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
            } catch (_: CancellationException) {
                // Cancelled by a newer search request; do not show error
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
                        IndexingProgressState.setPaused(true, "Paused ($pendingCount pending)")
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
                        IndexingProgressState.setPaused(true, "Paused ($pendingCount pending)")
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
                        ShareImporter.deleteInternalFileIfShared(context, uri)
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

    fun moveFilesToFolder(files: List<ImportedFile>, targetFolderId: String?) {
        if (files.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val store = FileStore.getInstance(context)
            store.updateFilesFolder(files.map { it.uri }, targetFolderId)
            reloadFiles()
            withContext(Dispatchers.Main) {
                val targetName = if (targetFolderId == null) "Library Root" else folders.firstOrNull { it.id == targetFolderId }?.name ?: "folder"
                Toast.makeText(context, "Moved ${files.size} file(s) to $targetName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Confirmation dialog before removing a file and deleting its embeddings
    filePendingDeletion?.let { file ->
        AlertDialog(
            onDismissRequest = { filePendingDeletion = null },
            title = { Text("Remove file?") },
            text = {
                Text(
                    "Remove \"${file.displayName}\" from LocalFind library?\n\n" +
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
            onMoveMultipleFiles = { files, targetFolderId -> moveFilesToFolder(files, targetFolderId) },
            onDeleteMultipleFiles = { files -> removeFiles(files) },
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
            onRetryAllFailed = {
                scope.launch(Dispatchers.IO) {
                    val store = FileStore.getInstance(context)
                    store.markFailedAsPending()
                    importedFiles = store.getAll()
                    IndexingWorker.enqueue(context, replace = true)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Retrying failed files...", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onPauseIndexing = {
                scope.launch(Dispatchers.IO) {
                    IndexingWorker.pause(context)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Paused", Toast.LENGTH_SHORT).show()
                    }
                    reloadFiles()
                }
            },
            onResumeIndexing = {
                scope.launch(Dispatchers.IO) {
                    status = "Resuming..."
                    IndexingWorker.resume(context)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Resumed", Toast.LENGTH_SHORT).show()
                    }
                    reloadFiles()
                }
            },
            onCancelIndexing = {
                scope.launch(Dispatchers.IO) {
                    IndexingWorker.cancel(context)
                    withContext(Dispatchers.Main) {
                        status = "Ready"
                        Toast.makeText(context, "Analysis cancelled", Toast.LENGTH_SHORT).show()
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
                onQueryChange = {
                    searchQuery = it
                    if (searchImageUri != null) {
                        searchImageUri = null
                        searchImageName = null
                        searchImageEmbeddingCache[0] = null
                        excludeSourceUri = null
                    }
                },
                onSearch = { performSearch(searchQuery, searchCategory) },
                onClear = {
                    currentSearchJob?.cancel()
                    searchQuery = ""
                    searchImageUri = null
                    searchImageName = null
                    searchImageEmbeddingCache[0] = null
                    excludeSourceUri = null
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
                    if (searchImageUri != null && hasSearched) {
                        performImageSearch(
                            imageUri = searchImageUri!!,
                            imageName = searchImageName,
                            category = newCategory,
                            excludeUri = excludeSourceUri,
                            useCachedEmbedding = true
                        )
                    } else if (searchQuery.isNotBlank() && hasSearched) {
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
                    if (searchImageUri != null) {
                        performImageSearch(
                            imageUri = searchImageUri!!,
                            imageName = searchImageName,
                            category = GalleryCategory.ALL,
                            excludeUri = excludeSourceUri,
                            useCachedEmbedding = true
                        )
                    } else if (searchQuery.isNotBlank()) {
                        performSearch(searchQuery, GalleryCategory.ALL)
                    }
                },
                errorMessage = searchError ?: if (modelStatus is ModelStatus.Error) (modelStatus as ModelStatus.Error).message else null,
                indexedChunksCount = indexedChunksCount,
                onClose = {
                    currentSearchJob?.cancel()
                    isSearchOpen = false
                    activeSearchFile = null
                    searchCategory = GalleryCategory.ALL
                    if (searchImageUri != null) {
                        hasSearched = false
                        searchResults = emptyList()
                    }
                    searchImageUri = null
                    searchImageName = null
                    searchImageEmbeddingCache[0] = null
                    excludeSourceUri = null
                },
                queryImageUri = searchImageUri,
                queryImageName = searchImageName,
                onSelectQueryImage = { uri ->
                    performImageSearch(uri)
                },
                onClearQueryImage = {
                    currentSearchJob?.cancel()
                    searchImageUri = null
                    searchImageName = null
                    searchImageEmbeddingCache[0] = null
                    excludeSourceUri = null
                    hasSearched = false
                    searchResults = emptyList()
                    searchError = null
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
            onSearchInFile = { file -> startSearchInFile(file) },
            onSearchSimilarImage = { uriStr, name ->
                if (!FileAvailability.isFileAvailable(context, uriStr)) {
                    val matchedFile = importedFiles.firstOrNull { it.uri == uriStr }
                    if (matchedFile != null) missingFileToDelete = matchedFile
                } else {
                    activeSearchFile = null
                    searchCategory = GalleryCategory.PHOTOS
                    isSearchOpen = true
                    performImageSearch(
                        imageUri = uriStr.toUri(),
                        imageName = name,
                        category = GalleryCategory.PHOTOS,
                        excludeUri = uriStr
                    )
                }
            }
        )

        // 5. Model Loading Splash / Modal Progress Overlay (Blocks user interactions while LiteRT engine loads)
        ModelLoadingOverlay(
            isLoading = modelStatus is ModelStatus.Loading
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
