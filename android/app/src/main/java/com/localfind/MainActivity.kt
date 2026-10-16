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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.localfind.data.AppSettings
import com.localfind.data.EmbeddingStore
import com.localfind.data.FileImporter
import com.localfind.data.FileStore
import com.localfind.data.ImportedFile
import com.localfind.data.SettingsStore
import com.localfind.embed.Embedder
import com.localfind.index.Indexer
import com.localfind.search.SearchResult
import com.localfind.search.Searcher
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // UI state
    var status by remember { mutableStateOf("Ready") }
    var importedFiles by remember { mutableStateOf<List<ImportedFile>>(emptyList()) }
    var indexedChunksCount by remember { mutableIntStateOf(0) }
    var isIndexing by remember { mutableStateOf(false) }

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

    // Keep Embedder (485MB EmbeddingGemma 2) and EmbeddingStore warm in memory across the session
    // so indexing and search execute without repeated 1.5–3s model initialization overhead.
    val engineMutex = remember { Mutex() }
    var cachedEmbedder by remember { mutableStateOf<Embedder?>(null) }
    var cachedStore by remember { mutableStateOf<EmbeddingStore?>(null) }

    fun getOrCreateStore(): EmbeddingStore =
        cachedStore ?: EmbeddingStore.create(context).also { cachedStore = it }

    fun getOrCreateEmbedder(): Embedder =
        cachedEmbedder ?: Embedder(context).also { cachedEmbedder = it }

    DisposableEffect(Unit) {
        onDispose {
            try {
                cachedEmbedder?.close()
                cachedEmbedder = null
                cachedStore?.close()
                cachedStore = null
            } catch (_: Exception) {
                // Ignore teardown exceptions
            }
        }
    }

    /**
     * Queries [EmbeddingStore] on IO dispatcher to count how many total chunks
     * are stored in the vector database.
     */
    suspend fun refreshIndexCount() {
        withContext(Dispatchers.IO) {
            engineMutex.withLock {
                try {
                    indexedChunksCount = getOrCreateStore().getAllRecordIds().size
                } catch (_: Exception) {
                    // Ignore if database is temporarily busy or uninitialized
                }
            }
        }
    }

    // Load persisted files and vector count when the screen is first composed
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val store = FileStore(context)
            importedFiles = store.getAll()
        }
        refreshIndexCount()
    }

    /**
     * Executes indexing for a given list of [ImportedFile] objects on a background thread.
     * Keeps UI responsive while updating per-file progress status.
     */
    fun indexFiles(filesToIndex: List<ImportedFile>) {
        scope.launch {
            isIndexing = true
            status = "Initializing indexer..."
            withContext(Dispatchers.Default) {
                engineMutex.withLock {
                    try {
                        val embedder = getOrCreateEmbedder()
                        val store = getOrCreateStore()
                        val indexer = Indexer(context, embedder, store, settings)
                        var indexedFiles = 0
                        var totalChunks = 0

                        for ((i, file) in filesToIndex.withIndex()) {
                            if (indexer.isSupported(file)) {
                                status = "Indexing ${i + 1}/${filesToIndex.size}: ${file.displayName}..."
                                val result = indexer.indexFile(file)
                                if (result.isSuccess) {
                                    indexedFiles++
                                    totalChunks += result.chunksCount
                                }
                            }
                        }
                        status = "Indexed $indexedFiles file(s) ($totalChunks chunks)"
                    } catch (e: Exception) {
                        status = "Indexing failed: ${e.message}"
                    }
                }
            }
            refreshIndexCount()
            isIndexing = false
        }
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

                engineMutex.withLock {
                    try {
                        getOrCreateStore().deleteByFileUri(file.uri)
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
        scope.launch {
            isSearching = true
            searchError = null
            try {
                val results = withContext(Dispatchers.Default) {
                    engineMutex.withLock {
                        val embedder = getOrCreateEmbedder()
                        val store = getOrCreateStore()
                        val searcher = Searcher(store, embedder)
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
                val newFiles = withContext(Dispatchers.IO) {
                    val store = FileStore(context)
                    val files = uris.map { uri ->
                        FileImporter.importUri(context.contentResolver, uri)
                    }
                    store.insertAll(files)
                    importedFiles = store.getAll()
                    files
                }
                indexFiles(newFiles)
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
                        indexFiles(importedFiles)
                        isSettingsOpen = false
                    },
                    isIndexing = isIndexing,
                    fileCount = importedFiles.size,
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
                    errorMessage = searchError,
                    indexedChunksCount = indexedChunksCount,
                    onClose = { isSearchOpen = false }
                )
            }

            ActiveScreen.GALLERY -> {
                GalleryScreen(
                    importedFiles = importedFiles,
                    indexedChunksCount = indexedChunksCount,
                    isIndexing = isIndexing,
                    statusMessage = status,
                    onOpenSearch = { isSearchOpen = true },
                    onImportFiles = { pickerLauncher.launch(arrayOf("*/*")) },
                    onOpenSettings = { isSettingsOpen = true },
                    onFileClick = { file -> openLibraryFile(file) },
                    onRequestDeleteFile = { file -> filePendingDeletion = file }
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
