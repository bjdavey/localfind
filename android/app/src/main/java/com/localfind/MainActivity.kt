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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.localfind.ui.SearchScreen
import com.localfind.ui.theme.LocalFindTheme
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
 * - Automatically indexes text and Markdown files into [EmbeddingStore] via [Indexer].
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

    /**
     * Queries [EmbeddingStore] on IO dispatcher to count how many total chunks
     * are stored in the vector database.
     */
    suspend fun refreshIndexCount() {
        withContext(Dispatchers.IO) {
            try {
                EmbeddingStore.create(context).use { store ->
                    indexedChunksCount = store.getAllRecordIds().size
                }
            } catch (_: Exception) {
                // Ignore if database is temporarily busy or uninitialized
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
                try {
                    // Open Embedder (loads EmbeddingGemma 2) and EmbeddingStore (SQLite vector store)
                    Embedder(context).use { embedder ->
                        EmbeddingStore.create(context).use { store ->
                            val indexer = Indexer(context, embedder, store)
                            var indexedFiles = 0
                            var totalChunks = 0

                            for ((i, file) in filesToIndex.withIndex()) {
                                if (indexer.isTextFile(file)) {
                                    // Update status to show current file being processed
                                    status = "Indexing ${i + 1}/${filesToIndex.size}: ${file.displayName}..."
                                    val result = indexer.indexTextFile(file)
                                    if (result.isSuccess) {
                                        indexedFiles++
                                        totalChunks += result.chunksCount
                                    }
                                }
                            }
                            status = "Indexed $indexedFiles file(s) ($totalChunks chunks)"
                        }
                    }
                } catch (e: Exception) {
                    status = "Indexing failed: ${e.message}"
                }
            }
            // Update total chunk counter after indexing completes
            refreshIndexCount()
            isIndexing = false
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
                    Embedder(context).use { embedder ->
                        EmbeddingStore.create(context).use { store ->
                            val searcher = Searcher(store, embedder)
                            searcher.search(trimmed)
                        }
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
     * Opens the matched document using standard Android [Intent.ACTION_VIEW].
     * Forwards read permission via [Intent.FLAG_GRANT_READ_URI_PERMISSION].
     */
    fun openSearchResult(result: SearchResult) {
        try {
            if (result.fileUri.isBlank()) {
                Toast.makeText(context, "No file URI associated with result", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = Uri.parse(result.fileUri)
            val mimeType = when {
                result.fileName.endsWith(".md", ignoreCase = true) -> "text/markdown"
                result.fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
                result.fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
                else -> "text/plain"
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
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
                // Automatically index imported text and markdown files
                indexFiles(newFiles)
            }
        }
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
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = file.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${formatFileSize(file.size)}${if (file.mimeType != null) " • ${file.mimeType}" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    LocalFindTheme {
        MainScreen()
    }
}
