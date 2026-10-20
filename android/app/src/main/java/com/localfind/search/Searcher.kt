package com.localfind.search

import android.graphics.Bitmap
import com.google.mediapipe.tasks.core.TextPart
import com.localfind.data.DEFAULT_TOP_K
import com.localfind.data.EmbeddingStore
import com.localfind.embed.Embedder
import kotlin.math.sqrt

/**
 * Domain model representing a ranked search result item.
 *
 * Designed according to ARCHITECTURE.md §6 & §7 and PRD.md §2:
 *
 * @property chunkId Unique identifier of the matched chunk (e.g., `"<fileUri>_<chunkIndex>"`).
 * @property fileUri Content URI of the document (used by the UI to open/view the document).
 * @property fileName Display name of the file (e.g., `"notes.md"`).
 * @property fileType Category of the file (`"text"`, `"pdf"`, `"image"`, `"audio"`, `"video"`).
 * @property snippet Text passage or snippet of the match.
 * @property location Offset location within the document (character offset for text, page for PDF, ms for audio/video).
 * @property score Cosine similarity score between the query embedding and chunk embedding (1.0 = identical direction).
 */
data class SearchResult(
    val chunkId: String,
    val fileUri: String,
    val fileName: String,
    val fileType: String,
    val snippet: String,
    val location: Long,
    val score: Float
)

/**
 * Orchestrates semantic and visual similarity search over indexed file chunks.
 *
 * Architectural Role (ARCHITECTURE.md §7):
 * 1. Accepts natural language text queries or image query bitmaps.
 * 2. Generates a 768-dimensional normalized float embedding vector via [Embedder]
 *    (using text embedding or vision embedding in the shared multimodal embedding space).
 * 3. Queries [EmbeddingStore.search] for nearest neighbors via cosine similarity.
 * 4. Extracts snippet text, file URI, display name, chunk type, and location offset from metadata.
 * 5. Computes cosine similarity scores and returns the results ranked from most to least relevant.
 *
 * Design for testability:
 * Primary constructor accepts `embedQuery: (String) -> FloatArray` and optional `embedImageQuery`,
 * allowing fast JVM unit testing with simulated embeddings without needing LiteRT / native C++ libraries.
 * A secondary constructor accepts [Embedder] for production on-device usage.
 */
class Searcher(
    private val embeddingStore: EmbeddingStore,
    private val embedQuery: (String) -> FloatArray,
    private val embedImageQuery: ((Bitmap) -> FloatArray)? = null
) {
    /**
     * Test and lightweight constructor using [EmbeddingStore] and a query text embedding lambda.
     */
    constructor(
        embeddingStore: EmbeddingStore,
        embedQuery: (String) -> FloatArray
    ) : this(
        embeddingStore = embeddingStore,
        embedQuery = embedQuery,
        embedImageQuery = null
    )

    /**
     * Production constructor using [EmbeddingStore] and on-device [Embedder].
     * Configures multimodal embedding delegates for both text and image queries.
     */
    constructor(
        embeddingStore: EmbeddingStore,
        embedder: Embedder
    ) : this(
        embeddingStore = embeddingStore,
        embedQuery = { text -> embedder.embedText(text) },
        embedImageQuery = { bitmap -> embedder.embedImage(bitmap) }
    )

    /**
     * Executes a semantic search for [query] text and returns ranked results.
     *
     * @param query User's natural language search query. Blank queries return an empty list.
     * @param topK Maximum number of results to retrieve (default: [DEFAULT_TOP_K]).
     * @param threshold Minimum cosine similarity score required for a result to be included (default: 0.0f).
     * @param metadataFilter Optional metadata filter criteria (e.g. `mapOf("fileId" to uri)`).
     * @param fileTypes Optional collection of file type values (`"text"`, `"pdf"`, `"image"`, `"audio"`, `"video"`).
     *                  When specified, filtering is performed at query time inside SQLite ("Search after filter,
     *                  not filter search results"), preventing unrelated modalities from consuming the topK budget.
     * @param excludeFileUri Optional source file URI to exclude from results (e.g. to avoid returning a query file).
     * @return Ranked list of [SearchResult], sorted by descending similarity score (up to [topK]).
     */
    fun search(
        query: String,
        topK: Int = DEFAULT_TOP_K,
        threshold: Float = 0.0f,
        metadataFilter: Map<String, String> = emptyMap(),
        fileTypes: Collection<String>? = null,
        excludeFileUri: String? = null
    ): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || topK <= 0) {
            return emptyList()
        }

        // 1. Generate 768-dimensional normalized embedding for query text
        val queryEmbedding = embedQuery(trimmed)

        // 2. Delegate to vector nearest-neighbor retrieval
        return searchByEmbedding(
            queryEmbedding = queryEmbedding,
            topK = topK,
            threshold = threshold,
            metadataFilter = metadataFilter,
            fileTypes = fileTypes,
            excludeFileUri = excludeFileUri
        )
    }

    /**
     * Converts an image [Bitmap] into a normalized 768-dimensional float embedding vector.
     */
    fun embedImage(bitmap: Bitmap): FloatArray {
        val embed = embedImageQuery ?: throw IllegalStateException("Image embedder is not configured in Searcher")
        return embed(bitmap)
    }

    /**
     * Executes a visual similarity search using an image [Bitmap] and returns ranked results.
     *
     * Encodes the query image into the shared 768-dimensional multimodal vector space and
     * retrieves the nearest visually and semantically related items in the library.
     *
     * @param bitmap Decoded software ARGB_8888 bitmap of the query image.
     * @param topK Maximum number of results to retrieve (default: [DEFAULT_TOP_K]).
     * @param threshold Minimum cosine similarity score required for a result to be included (default: 0.0f).
     * @param metadataFilter Optional metadata filter criteria (e.g. `mapOf("fileId" to uri)`).
     * @param fileTypes Optional collection of file type values (`"image"`, `"video"`, etc.).
     * @param excludeFileUri Optional source file URI to exclude from results (e.g. to avoid returning the query image itself).
     * @return Ranked list of [SearchResult], sorted by descending similarity score (up to [topK]).
     */
    fun searchImage(
        bitmap: Bitmap,
        topK: Int = DEFAULT_TOP_K,
        threshold: Float = 0.0f,
        metadataFilter: Map<String, String> = emptyMap(),
        fileTypes: Collection<String>? = null,
        excludeFileUri: String? = null
    ): List<SearchResult> {
        val queryEmbedding = embedImage(bitmap)
        return searchByEmbedding(
            queryEmbedding = queryEmbedding,
            topK = topK,
            threshold = threshold,
            metadataFilter = metadataFilter,
            fileTypes = fileTypes,
            excludeFileUri = excludeFileUri
        )
    }

    /**
     * Core nearest-neighbor search logic against [EmbeddingStore] given a precomputed 768-dimensional float embedding.
     *
     * Reused by both text search and visual similarity image search.
     *
     * @param queryEmbedding Normalized 768-dimensional float embedding vector.
     * @param topK Maximum number of results to retrieve.
     * @param threshold Minimum cosine similarity score required for a result to be included.
     * @param metadataFilter Optional metadata filter criteria (e.g. `mapOf("fileId" to uri)`).
     * @param fileTypes Optional collection of file type values for SQLite pre-filtering.
     * @param excludeFileUri Optional source file URI to exclude from results.
     * @return Ranked list of [SearchResult], sorted by descending similarity score.
     */
    fun searchByEmbedding(
        queryEmbedding: FloatArray,
        topK: Int = DEFAULT_TOP_K,
        threshold: Float = 0.0f,
        metadataFilter: Map<String, String> = emptyMap(),
        fileTypes: Collection<String>? = null,
        excludeFileUri: String? = null
    ): List<SearchResult> {
        if (topK <= 0 || queryEmbedding.isEmpty()) {
            return emptyList()
        }

        // Query the SQLite vector store for nearest neighbors.
        // Applies modality/file-type filter before nearest-neighbor retrieval ("search after filter"),
        // preventing irrelevant modalities from consuming the top-K budget.
        // If excludeFileUri is set, request topK + 1 candidates so excluding the query file doesn't reduce top-K count.
        val fetchLimit = if (excludeFileUri != null) topK + 1 else topK
        val records = if (fileTypes.isNullOrEmpty()) {
            embeddingStore.search(queryEmbedding, fetchLimit, metadataFilter)
        } else if (fileTypes.size == 1) {
            embeddingStore.search(queryEmbedding, fetchLimit, metadataFilter + ("type" to fileTypes.first()))
        } else {
            // For categories spanning multiple formats (e.g. Docs spanning "pdf" and "text"),
            // retrieve top candidates for each type and merge before ranking
            fileTypes
                .flatMap { type ->
                    embeddingStore.search(queryEmbedding, fetchLimit, metadataFilter + ("type" to type))
                }
                .distinctBy { it.id }
        }

        // Map RetrievalRecord items to SearchResult objects, filtering out excluded source URI if present
        val results = records.mapNotNull { record ->
            val fileUri = record.metadata["uri"] ?: record.metadata["fileId"] ?: ""
            // Omit the query file itself if requested (e.g. when querying visual similarity for an existing library file)
            if (excludeFileUri != null && fileUri == excludeFileUri) {
                return@mapNotNull null
            }

            // Extract text passage from TextPart if present
            val snippet = record.content
                .filterIsInstance<TextPart>()
                .firstOrNull()?.text
                ?: ""

            // Extract structured metadata populated by Indexer
            val fileName = record.metadata["name"] ?: ""
            val fileType = record.metadata["type"] ?: "text"
            val location = record.metadata["location"]?.toLongOrNull() ?: 0L

            // Calculate cosine similarity between query and chunk embedding if vector is present
            val score = if (record.embeddings.isNotEmpty()) {
                calculateCosineSimilarity(queryEmbedding, record.embeddings)
            } else {
                0f
            }

            SearchResult(
                chunkId = record.id,
                fileUri = fileUri,
                fileName = fileName,
                fileType = fileType,
                snippet = snippet,
                location = location,
                score = score
            )
        }

        // Filter by minimum similarity threshold, order by descending score, and take topK
        return results
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .take(topK)
    }

    companion object {
        /**
         * Computes the cosine similarity between two float vectors:
         *   similarity = (u · v) / (||u|| * ||v||)
         *
         * Returns a value between -1.0 and 1.0 (clamped). Returns 0.0 if vectors are empty
         * or dimension mismatched.
         */
        fun calculateCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
            if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 0f
            var dot = 0f
            var norm1 = 0f
            var norm2 = 0f
            for (i in v1.indices) {
                dot += v1[i] * v2[i]
                norm1 += v1[i] * v1[i]
                norm2 += v2[i] * v2[i]
            }
            val denominator = (sqrt(norm1.toDouble()) * sqrt(norm2.toDouble())).toFloat()
            return if (denominator > 0f) {
                (dot / denominator).coerceIn(-1f, 1f)
            } else {
                0f
            }
        }
    }
}

