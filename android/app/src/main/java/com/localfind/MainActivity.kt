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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.localfind.data.EmbeddingStore
import com.localfind.data.FileImporter
import com.localfind.data.FileStore
import com.localfind.data.ImportedFile
import com.localfind.data.formatFileSize
import com.localfind.embed.Embedder
import com.localfind.index.Indexer
import com.localfind.search.SearchResult
import com.localfind.search.Searcher
import com.localfind.ui.ImageViewerParams
import com.localfind.ui.ImageViewerScreen
import com.localfind.ui.MediaPlayerParams
import com.localfind.ui.MediaPlayerScreen
import com.localfind.ui.PdfViewerParams
import com.localfind.ui.PdfViewerScreen
import com.localfind.ui.TextViewerParams
import com.localfind.ui.TextViewerScreen
import com.localfind.ui.SearchScreen
import com.localfind.ui.theme.LocalFindTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
            LocalFindTheme {
                MainScreen()
            }
        }
    }
}

/**
 * Primary UI screen for LocalFind:
 * - Allows picking files from device storage via Storage Access Framework (SAF).
 * - Persists picked URIs and metadata in [FileStore].
 * - Automatically indexes supported files (text, Markdown, PDF, images) into [EmbeddingStore] via [Indexer].
 * - Displays imported files list and indexing progress/counts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // UI state
    var status by remember { mutableStateOf("Ready") }
    var importedFiles by remember { mutableStateOf<List<ImportedFile>>(emptyList()) }
    var indexedChunksCount by remember { mutableStateOf(0) }
    var isIndexing by remember { mutableStateOf(false) }

    // Tab state (0: Search, 1: Library)
    var selectedTab by remember { mutableIntStateOf(0) }

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var activePdfViewer by remember { mutableStateOf<PdfViewerParams?>(null) }
    var activeImageViewer by remember { mutableStateOf<ImageViewerParams?>(null) }
    var activeMediaPlayer by remember { mutableStateOf<MediaPlayerParams?>(null) }
    var activeTextViewer by remember { mutableStateOf<TextViewerParams?>(null) }
    var filePendingDeletion by remember { mutableStateOf<ImportedFile?>(null) }

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
                        val indexer = Indexer(context, embedder, store)
                        var indexedFiles = 0
                        var totalChunks = 0

                        for ((i, file) in filesToIndex.withIndex()) {
                            if (indexer.isSupported(file)) {
                                // Update status to show current file being processed
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
            // Update total chunk counter after indexing completes
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
                // 1. Release persistable SAF URI permission
                try {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(file.uri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Non-SAF URIs (like test file://) or revoked grants don't have persistable grants
                }

                // 2. Remove file metadata record from SQLite FileStore
                val fileStore = FileStore(context)
                fileStore.delete(file.uri)
                val updatedFiles = fileStore.getAll()

                // 3. Delete all vector chunks associated with this file from EmbeddingStore
                engineMutex.withLock {
                    try {
                        getOrCreateStore().deleteByFileUri(file.uri)
                    } catch (_: Exception) {
                        // Ignore if store is temporarily inaccessible
                    }
                }

                withContext(Dispatchers.Main) {
                    importedFiles = updatedFiles
                    // Remove any search results currently referencing the deleted file
                    searchResults = searchResults.filter { it.fileUri != file.uri }
                }
            }

            // 4. Refresh vector store count and update status
            refreshIndexCount()
            status = "Removed ${file.displayName} and deleted its embeddings"
            Toast.makeText(
                context,
                "Removed ${file.displayName} from library",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Executes a semantic search query against the local vector database.
     * Runs embedding inference and nearest-neighbor retrieval on [Dispatchers.Default]
     * to ensure UI smoothness.
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
                        searcher.search(trimmed)
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
     * Opens the matched document.
     *
     * In accordance with PRD §2 & ARCHITECTURE.md §8:
     * - PDF results open directly in the in-app [PdfViewerScreen] at the matching page.
     * - Image results open directly in the in-app [ImageViewerScreen] (Coil).
     * - Audio & video results open directly in the in-app [MediaPlayerScreen] (ExoPlayer).
     * - Text & markdown results open directly in the in-app [TextViewerScreen], scrolled to the matching passage.
     */
    fun openSearchResult(result: SearchResult) {
        if (result.fileUri.isBlank()) {
            Toast.makeText(context, "No file URI associated with result", Toast.LENGTH_SHORT).show()
            return
        }

        val isPdf = result.fileType.equals("pdf", ignoreCase = true) ||
                Indexer.isPdfNameOrMime(result.fileName, null)

        if (isPdf) {
            // Open in-app PDF viewer at the matching page (1-based location)
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
            // Open in-app image viewer (Coil)
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
            // Open in-app player (audio or video), seeking to the chunk start time (ms)
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
            // Open in-app text viewer, scrolled and highlighted at matching passage
            activeTextViewer = TextViewerParams(
                uri = result.fileUri,
                fileName = result.fileName,
                targetOffset = result.location.toInt().coerceAtLeast(0),
                highlightSnippet = result.snippet
            )
            return
        }

        // Fallback for any unknown file types
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
     * Opens an imported file from the Library tab in its corresponding builtin viewer
     * (PDF at page 1, Image, Audio/Video at 00:00, or Text at line 1).
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

        // External fallback for any unsupported document formats
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

    /**
     * System file picker launcher using Storage Access Framework (SAF).
     *
     * Privacy & Security Note:
     * - We use ActivityResultContracts.OpenMultipleDocuments() which launches DocumentsUI.
     * - No broad storage permissions (READ_EXTERNAL_STORAGE / MANAGE_EXTERNAL_STORAGE) are needed.
     * - The user explicitly chooses which files to share; Android grants read grants for those URIs only.
     */
    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                status = "Importing ${uris.size} file(s)..."
                // Resolve URIs, take persistable permissions, and save to FileStore
                val newFiles = withContext(Dispatchers.IO) {
                    val store = FileStore(context)
                    val files = uris.map { uri ->
                        FileImporter.importUri(context.contentResolver, uri)
                    }
                    store.insertAll(files)
                    importedFiles = store.getAll()
                    files
                }
                // Automatically index imported supported files
                indexFiles(newFiles)
            }
        }
    }

    if (activeTextViewer != null) {
        TextViewerScreen(
            params = activeTextViewer!!,
            onClose = { activeTextViewer = null }
        )
    } else if (activePdfViewer != null) {
        PdfViewerScreen(
            params = activePdfViewer!!,
            onClose = { activePdfViewer = null }
        )
    } else if (activeMediaPlayer != null) {
        MediaPlayerScreen(
            params = activeMediaPlayer!!,
            onClose = { activeMediaPlayer = null }
        )
    } else if (activeImageViewer != null) {
        ImageViewerScreen(
            params = activeImageViewer!!,
            onClose = { activeImageViewer = null }
        )
    } else {
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

        Scaffold(
            modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(text = stringResource(id = R.string.app_name)) }
                )
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Search") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Library (${importedFiles.size})") }
                    )
                }
            }
        }
    ) { innerPadding ->
        if (selectedTab == 0) {
            // Search Tab: input field and ranked results list UI
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
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            // Library Tab: import files, re-index, chunk counts, and file list
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Action Buttons (Import files, Re-index all)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { pickerLauncher.launch(arrayOf("*/*")) },
                        enabled = !isIndexing
                    ) {
                        Text("Import files")
                    }

                    if (importedFiles.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { indexFiles(importedFiles) },
                            enabled = !isIndexing
                        ) {
                            Text("Re-index all")
                        }
                    }
                }

                // Status feedback line
                Text(
                    text = "Status: $status",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                // Vector store statistics
                Text(
                    text = "Vector store: $indexedChunksCount chunks indexed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                // Privacy disclosure badge
                Text(
                    text = "Only the files you pick are accessed. No broad storage permissions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )

                HorizontalDivider()

                Spacer(modifier = Modifier.height(8.dp))

                // Section header
                Text(
                    text = "Imported files (${importedFiles.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // Empty state placeholder
                if (importedFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No files imported yet.\nTap 'Import files' to choose files from device storage.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Scrollable list of imported files
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(importedFiles, key = { it.uri }) { file ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { openLibraryFile(file) },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = file.displayName,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${formatFileSize(file.size)}${if (file.mimeType != null) " • ${file.mimeType}" else ""} • Tap to view",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { filePendingDeletion = file },
                                        enabled = !isIndexing
                                    ) {
                                        Text(
                                            text = "🗑",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
