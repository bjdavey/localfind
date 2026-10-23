package com.localfind.ui.main

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.localfind.core.library.GalleryCategory
import com.localfind.core.library.LibraryFilters
import com.localfind.core.model.ChunkMetadata
import com.localfind.core.model.ImportedFile
import com.localfind.core.model.ModelStatus
import com.localfind.core.model.SearchResult
import com.localfind.core.settings.AppSettings
import com.localfind.data.EngineManager
import com.localfind.data.FileAvailability
import com.localfind.data.FileImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the state of the dedicated search screen (query, filters, results, optional query image or
 * single-file scope) and runs text and visual-similarity searches against the local index.
 *
 * Plain state holder like [LibraryState]; [settings] and [availableCategories] are read lazily so the
 * latest values are used when a search starts.
 */
@Stable
class SearchState(
    private val context: Context,
    private val scope: CoroutineScope,
    val listState: LazyListState,
    private val settings: () -> AppSettings,
    private val availableCategories: () -> List<GalleryCategory>,
    private val onMissingFile: (ImportedFile) -> Unit,
    private val closeViewers: () -> Unit
) {
    var isSearchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var searchCategory by mutableStateOf(GalleryCategory.ALL)

    /** File the search is scoped to ("search in file"), or null when searching the whole library. */
    var activeSearchFile by mutableStateOf<ImportedFile?>(null)
    var isSearching by mutableStateOf(false)
    var hasSearched by mutableStateOf(false)
    var searchResults by mutableStateOf<List<SearchResult>>(emptyList())
    var searchError by mutableStateOf<String?>(null)

    /** Query image of a search-by-image, its display name and cached embedding. */
    var searchImageUri by mutableStateOf<Uri?>(null)
    var searchImageName by mutableStateOf<String?>(null)
    private val searchImageEmbeddingCache = arrayOfNulls<FloatArray>(1)

    /** Library file excluded from similar-image results (the query file itself). */
    var excludeSourceUri by mutableStateOf<String?>(null)
    private var currentSearchJob: Job? = null

    /** Drops results that belong to files that were just removed from the library. */
    fun removeResultsFor(uris: Set<String>) {
        searchResults = searchResults.filter { it.fileUri !in uris }
    }

    /** Forgets the active query image (and its cached embedding), leaving text query and results alone. */
    fun clearImageQuery() {
        searchImageUri = null
        searchImageName = null
        searchImageEmbeddingCache[0] = null
        excludeSourceUri = null
    }

    /** Resets the results area to its "nothing searched yet" state. */
    fun clearResults() {
        hasSearched = false
        searchResults = emptyList()
        searchError = null
    }

    /** Scrolls the results list back to the first hit after new results arrive. */
    fun scrollResultsToTop() {
        scope.launch {
            try {
                listState.scrollToItem(0)
            } catch (_: Exception) {}
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
        if (EngineManager.modelStatus.value is ModelStatus.Error) {
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
                GalleryCategory.PHOTOS in availableCategories() -> GalleryCategory.PHOTOS
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
                                    store.get(ChunkMetadata.chunkId(imageUri.toString(), 0))?.embeddings
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
                                    maxDimension = settings().maxImageDimension
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
                            topK = settings().topK,
                            threshold = settings().similarityThreshold,
                            metadataFilter = fileFilter,
                            fileTypes = categoryTypes,
                            excludeFileUri = if (activeSearchFile != null) null else excludeUri
                        )
                    }
                }
                searchResults = results
                hasSearched = true
                scrollResultsToTop()
            } catch (_: CancellationException) {
                // Cancelled by a newer search request; do not show error
            } catch (e: Exception) {
                searchError = e.message ?: "Search by image failed"
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
        scope.launch {
            // Content provider access is IPC, so the availability check must not run on the main thread
            if (!withContext(Dispatchers.IO) { FileAvailability.isFileAvailable(context, target.uri) }) {
                onMissingFile(target)
                return@launch
            }
            openSearchInFile(target)
        }
    }

    private fun openSearchInFile(target: ImportedFile) {
        val isPhoto = LibraryFilters.categorizeFile(target.displayName, target.mimeType) == GalleryCategory.PHOTOS
        if (isPhoto) {
            activeSearchFile = null
            searchCategory = GalleryCategory.PHOTOS
            closeViewers()
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
        clearImageQuery()
        clearResults()
        // Close any active viewers first so the search screen becomes active
        closeViewers()
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
        if (EngineManager.modelStatus.value is ModelStatus.Error) {
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
                            topK = settings().topK,
                            threshold = settings().similarityThreshold,
                            metadataFilter = fileFilter,
                            fileTypes = categoryTypes
                        )
                    }
                }
                searchResults = results
                hasSearched = true
                scrollResultsToTop()
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

    /** Typing a text query replaces an active query image. */
    fun onQueryChange(newQuery: String) {
        searchQuery = newQuery
        if (searchImageUri != null) {
            clearImageQuery()
        }
    }

    /** Clears the query, query image, results and category filter. */
    fun clearSearch() {
        currentSearchJob?.cancel()
        searchQuery = ""
        clearImageQuery()
        clearResults()
        searchCategory = GalleryCategory.ALL
        scrollResultsToTop()
    }

    /** Applies a modality filter and re-runs the current search (reusing the cached image embedding). */
    fun changeCategory(newCategory: GalleryCategory) {
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
    }

    /** Leaves single-file scope and searches the whole library again. */
    fun clearTargetFile() {
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
    }

    /** Closes the search screen; an image search is reset so it does not reappear on the next open. */
    fun close() {
        currentSearchJob?.cancel()
        isSearchOpen = false
        activeSearchFile = null
        searchCategory = GalleryCategory.ALL
        if (searchImageUri != null) {
            hasSearched = false
            searchResults = emptyList()
        }
        clearImageQuery()
    }

    /** Removes the query image and its results. */
    fun clearQueryImage() {
        currentSearchJob?.cancel()
        clearImageQuery()
        clearResults()
    }
}

/** Creates the [SearchState] for the main screen. */
@Composable
fun rememberSearchState(
    listState: LazyListState,
    settings: AppSettings,
    availableCategories: List<GalleryCategory>,
    onMissingFile: (ImportedFile) -> Unit,
    closeViewers: () -> Unit
): SearchState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestSettings = rememberUpdatedState(settings)
    val latestCategories = rememberUpdatedState(availableCategories)
    val latestOnMissingFile = rememberUpdatedState(onMissingFile)
    val latestCloseViewers = rememberUpdatedState(closeViewers)
    return remember {
        SearchState(
            context = context,
            scope = scope,
            listState = listState,
            settings = { latestSettings.value },
            availableCategories = { latestCategories.value },
            onMissingFile = { latestOnMissingFile.value(it) },
            closeViewers = { latestCloseViewers.value() }
        )
    }
}
