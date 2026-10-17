package com.localfind

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.localfind.data.AppSettings
import com.localfind.data.EngineManager
import com.localfind.data.FileImporter
import com.localfind.data.FileStore
import com.localfind.data.ImportedFile
import com.localfind.data.IndexingStatus
import com.localfind.data.ModelStatus
import com.localfind.data.SettingsStore
import com.localfind.index.Indexer
import com.localfind.index.IndexingProgressState
import com.localfind.index.IndexingWorker
import com.localfind.search.SearchResult
import com.localfind.ui.GalleryScreen
import com.localfind.ui.ImageViewerParams
import com.localfind.ui.ImageViewerScreen
import com.localfind.ui.MediaPlayerParams
import com.localfind.ui.MediaPlayerScreen
import com.localfind.ui.PdfViewerParams
import com.localfind.ui.PdfViewerScreen
import com.localfind.ui.SearchScreen
import com.localfind.ui.SettingsScreen
import com.localfind.ui.TextViewerParams
import com.localfind.ui.TextViewerScreen
import com.localfind.ui.theme.LocalFindTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Active navigation screen key used by [AnimatedContent] to perform seamless
 * zoom/expand transitions between the Gallery grid, Search view, Settings, and Viewers.
 */
private enum class ActiveScreen {
    GALLERY,
    SEARCH,
    SETTINGS,
    VIEWER_IMAGE,
    VIEWER_MEDIA,
    VIEWER_PDF,
    VIEWER_TEXT
}

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
 * - Tapping any media tile or search result transitions smoothly via scale+fade expansion
 *   into its builtin viewer ([ImageViewerScreen], [MediaPlayerScreen], [PdfViewerScreen], [TextViewerScreen]).
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
    var indexedChunksCount by remember { mutableIntStateOf(0) }

    // Navigation & Search state
    var isSearchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }

    // Active full-screen viewers
    var activePdfViewer by remember { mutableStateOf<PdfViewerParams?>(null) }
    var activeImageViewer by remember { mutableStateOf<ImageViewerParams?>(null) }
    var activeMediaPlayer by remember { mutableStateOf<MediaPlayerParams?>(null) }
    var activeTextViewer by remember { mutableStateOf<TextViewerParams?>(null) }
    var filePendingDeletion by remember { mutableStateOf<ImportedFile?>(null) }
    var isSettingsOpen by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            EngineManager.close()
        }
    }

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
                val store = FileStore(context)
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
                    indexedChunksCount = store.getAllRecordIds().size
                } catch (_: Exception) {
                    // Ignore if database is temporarily busy
                }
            }
        }
    }

    suspend fun reloadFiles() {
        withContext(Dispatchers.IO) {
            val store = FileStore(context)
            importedFiles = store.getAll()
        }
        refreshIndexCount()
    }

    // Load model and persisted files on startup, automatically resuming background indexing if ready
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            EngineManager.ensureModelLoaded(context)
            val store = FileStore(context)
            importedFiles = store.getAll()
            // If the app was killed while indexing was in-flight, resume immediately via WorkManager
            if (store.getPendingFiles().isNotEmpty()) {
                IndexingWorker.enqueue(context, replace = true)
            }
        }
        refreshIndexCount()
    }

    // Refresh file statuses and index counts as files transition lifecycle states
    LaunchedEffect(activeWorkInfo?.state, indexingProgress.currentUri, indexingProgress.isIndexing) {
        reloadFiles()
    }

    /**
     * Removes an imported file from the local library:
     * 1. Releases persistable SAF URI read grant (if held).
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

                try {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(file.uri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Non-SAF URIs or revoked grants don't have persistable grants
                }

                val fileStore = FileStore(context)
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
     * Executes a semantic search query against the local vector database.
     */
    fun performSearch(queryText: String) {
        val trimmed = queryText.trim()
        if (trimmed.isBlank() || isSearching) return
        if (modelStatus is ModelStatus.Error) {
            searchError = "Search unavailable: Embedding model is not loaded."
            hasSearched = true
            return
        }
        scope.launch {
            isSearching = true
            searchError = null
            try {
                val results = withContext(Dispatchers.Default) {
                    EngineManager.withSearcher(context) { searcher ->
                        searcher.search(
                            query = trimmed,
                            topK = settings.topK,
                            threshold = settings.similarityThreshold
                        )
                    }
                }
                searchResults = results
                hasSearched = true
            } catch (e: Exception) {
                searchError = e.message ?: "An error occurred during search"
                searchResults = emptyList()
                hasSearched = true
            } finally {
                isSearching = false
            }
        }
    }

    /**
     * Opens a matched [SearchResult] at its exact offset/page/timestamp in the builtin viewer.
     */
    fun openSearchResult(result: SearchResult) {
        if (result.fileUri.isBlank()) {
            Toast.makeText(context, "No file URI associated with result", Toast.LENGTH_SHORT).show()
            return
        }

        val isPdf = result.fileType.equals("pdf", ignoreCase = true) ||
                Indexer.isPdfNameOrMime(result.fileName, null)

        if (isPdf) {
            val targetPage = result.location.toInt().coerceAtLeast(1)
            activePdfViewer = PdfViewerParams(
                uri = result.fileUri,
                fileName = result.fileName,
                initialPage = targetPage
            )
            return
        }

        val isImage = result.fileType.equals("image", ignoreCase = true) ||
                Indexer.isImageNameOrMime(result.fileName, null)

        if (isImage) {
            activeImageViewer = ImageViewerParams(
                uri = result.fileUri,
                fileName = result.fileName
            )
            return
        }

        val isPlayable = result.fileType.equals("audio", ignoreCase = true) ||
                result.fileType.equals("video", ignoreCase = true) ||
                Indexer.isAudioNameOrMime(result.fileName, null) ||
                Indexer.isVideoNameOrMime(result.fileName, null)

        if (isPlayable) {
            activeMediaPlayer = MediaPlayerParams(
                uri = result.fileUri,
                fileName = result.fileName,
                startMs = result.location.coerceAtLeast(0L)
            )
            return
        }

        val isText = result.fileType.equals("text", ignoreCase = true) ||
                Indexer.isTextNameOrMime(result.fileName, null)

        if (isText) {
            activeTextViewer = TextViewerParams(
                uri = result.fileUri,
                fileName = result.fileName,
                targetOffset = result.location.toInt().coerceAtLeast(0),
                highlightSnippet = result.snippet
            )
            return
        }

        try {
            val uri = Uri.parse(result.fileUri)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Could not open ${result.fileName}: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Opens an imported file from the Gallery grid in its corresponding builtin viewer.
     */
    fun openLibraryFile(file: ImportedFile) {
        if (file.uri.isBlank()) {
            Toast.makeText(context, "No file URI associated with file", Toast.LENGTH_SHORT).show()
            return
        }

        if (Indexer.isPdfNameOrMime(file.displayName, file.mimeType)) {
            activePdfViewer = PdfViewerParams(
                uri = file.uri,
                fileName = file.displayName,
                initialPage = 1
            )
            return
        }

        if (Indexer.isImageNameOrMime(file.displayName, file.mimeType)) {
            activeImageViewer = ImageViewerParams(
                uri = file.uri,
                fileName = file.displayName
            )
            return
        }

        if (Indexer.isAudioNameOrMime(file.displayName, file.mimeType) ||
            Indexer.isVideoNameOrMime(file.displayName, file.mimeType)
        ) {
            activeMediaPlayer = MediaPlayerParams(
                uri = file.uri,
                fileName = file.displayName,
                startMs = 0L
            )
            return
        }

        if (Indexer.isTextNameOrMime(file.displayName, file.mimeType)) {
            activeTextViewer = TextViewerParams(
                uri = file.uri,
                fileName = file.displayName,
                targetOffset = 0,
                highlightSnippet = null
            )
            return
        }

        try {
            val uri = Uri.parse(file.uri)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, file.mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Could not open ${file.displayName}: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // System file picker launcher using Storage Access Framework (SAF)
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                status = "Importing ${uris.size} file(s)..."
                withContext(Dispatchers.IO) {
                    val store = FileStore(context)
                    val files = uris.map { uri ->
                        FileImporter.importUri(context.contentResolver, uri)
                            .copy(indexingStatus = IndexingStatus.PENDING)
                    }
                    store.insertAll(files)
                    importedFiles = store.getAll()
                }
                IndexingWorker.enqueue(context)
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

    // Determine current active screen for seamless animated transition
    val currentScreen = when {
        activeImageViewer != null -> ActiveScreen.VIEWER_IMAGE
        activeMediaPlayer != null -> ActiveScreen.VIEWER_MEDIA
        activePdfViewer != null -> ActiveScreen.VIEWER_PDF
        activeTextViewer != null -> ActiveScreen.VIEWER_TEXT
        isSettingsOpen -> ActiveScreen.SETTINGS
        isSearchOpen -> ActiveScreen.SEARCH
        else -> ActiveScreen.GALLERY
    }

    // Seamless scale + fade transition when opening/closing images, videos, documents, or search
    AnimatedContent(
        targetState = currentScreen,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val isOpeningSubScreen = targetState != ActiveScreen.GALLERY
            if (isOpeningSubScreen) {
                (scaleIn(
                    initialScale = 0.88f,
                    animationSpec = tween(260, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(220))) togetherWith
                        (scaleOut(
                            targetScale = 0.96f,
                            animationSpec = tween(220, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(180)))
            } else {
                (scaleIn(
                    initialScale = 0.96f,
                    animationSpec = tween(240, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(200))) togetherWith
                        (scaleOut(
                            targetScale = 0.88f,
                            animationSpec = tween(220, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(180)))
            }
        },
        label = "ScreenTransition"
    ) { screen ->
        when (screen) {
            ActiveScreen.VIEWER_IMAGE -> {
                activeImageViewer?.let { params ->
                    ImageViewerScreen(
                        params = params,
                        onClose = { activeImageViewer = null }
                    )
                }
            }

            ActiveScreen.VIEWER_MEDIA -> {
                activeMediaPlayer?.let { params ->
                    MediaPlayerScreen(
                        params = params,
                        onClose = { activeMediaPlayer = null }
                    )
                }
            }

            ActiveScreen.VIEWER_PDF -> {
                activePdfViewer?.let { params ->
                    PdfViewerScreen(
                        params = params,
                        onClose = { activePdfViewer = null }
                    )
                }
            }

            ActiveScreen.VIEWER_TEXT -> {
                activeTextViewer?.let { params ->
                    TextViewerScreen(
                        params = params,
                        onClose = { activeTextViewer = null }
                    )
                }
            }

            ActiveScreen.SETTINGS -> {
                SettingsScreen(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onResetDefaults = onResetDefaults,
                    onReindexAll = {
                        scope.launch(Dispatchers.IO) {
                            val store = FileStore(context)
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
                    onClose = { isSettingsOpen = false }
                )
            }

            ActiveScreen.SEARCH -> {
                SearchScreen(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = { performSearch(searchQuery) },
                    onClear = {
                        searchQuery = ""
                        hasSearched = false
                        searchResults = emptyList()
                        searchError = null
                    },
                    isSearching = isSearching,
                    hasSearched = hasSearched,
                    searchResults = searchResults,
                    onResultClick = { result -> openSearchResult(result) },
                    errorMessage = searchError ?: if (modelStatus is ModelStatus.Error) (modelStatus as ModelStatus.Error).message else null,
                    indexedChunksCount = indexedChunksCount,
                    onClose = { isSearchOpen = false }
                )
            }

            ActiveScreen.GALLERY -> {
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
                    onRetryLoadModel = { retryLoadModel() },
                    onOpenSearch = { isSearchOpen = true },
                    onImportFiles = { pickerLauncher.launch(arrayOf("*/*")) },
                    onOpenSettings = { isSettingsOpen = true },
                    onFileClick = { file -> openLibraryFile(file) },
                    onRequestDeleteFile = { file -> filePendingDeletion = file },
                    onRetryIndexFile = { file ->
                        scope.launch(Dispatchers.IO) {
                            val store = FileStore(context)
                            store.updateStatus(file.uri, IndexingStatus.PENDING)
                            importedFiles = store.getAll()
                            IndexingWorker.enqueue(context, replace = true)
                        }
                    },
                    onCancelIndexing = {
                        scope.launch(Dispatchers.IO) {
                            IndexingWorker.cancel(context)
                            withContext(Dispatchers.Main) {
                                status = "Indexing cancelled"
                                Toast.makeText(context, "Indexing cancelled", Toast.LENGTH_SHORT).show()
                            }
                            reloadFiles()
                        }
                    },
                    onCancelIndexFile = { file ->
                        scope.launch(Dispatchers.IO) {
                            IndexingWorker.cancelFile(context, file.uri)
                            withContext(Dispatchers.Main) {
                                status = "Cancelled sync for ${file.displayName}"
                                Toast.makeText(context, "Cancelled sync for ${file.displayName}", Toast.LENGTH_SHORT).show()
                            }
                            reloadFiles()
                        }
                    }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    LocalFindTheme {
        MainScreen()
    }
}
