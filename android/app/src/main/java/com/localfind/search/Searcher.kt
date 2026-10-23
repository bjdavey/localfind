package com.localfind.search

import com.localfind.core.model.ChunkMetadata
import com.localfind.core.search.VectorMath
import com.localfind.core.search.ResultRanking
import com.localfind.core.model.SearchResult
import android.graphics.Bitmap
import com.google.mediapipe.tasks.core.TextPart
import com.localfind.core.settings.DEFAULT_TOP_K
import com.localfind.data.EmbeddingStore
import com.localfind.embed.EmbeddingModel

/**
 * Orchestrates semantic and visual similarity search over indexed file chunks.
 *
 * Architectural Role (ARCHITECTURE.md §7):
 * 1. Accepts natural language text queries or image query bitmaps.
 * 2. Generates a 768-dimensional normalized float embedding vector via [EmbeddingModel]
 *    (using text embedding or vision embedding in the shared multimodal embedding space).
 * 3. Queries [EmbeddingStore.search] for nearest neighbors via cosine similarity.
 * 4. Extracts snippet text, file URI, display name, chunk type, and location offset from metadata.
 * 5. Computes cosine similarity scores and returns the results ranked from most to least relevant.
 *
 * Design for testability:
 * Primary constructor accepts `embedQuery: (String) -> FloatArray` and optional `embedImageQuery`,
 * allowing fast JVM unit testing with simulated embeddings without needing LiteRT / native C++ libraries.
 * A secondary constructor accepts [EmbeddingModel] for production on-device usage.
 */
class Searcher(
    private val embeddingStore: EmbeddingStore,
    private val embedQuery: (String) -> FloatArray,
    private val embedImageQuery: ((Bitmap) -> FloatArray)? = null
) {
    /**
     * Production constructor using [EmbeddingStore] and on-device [EmbeddingModel].
     * Configures multimodal embedding delegates for both text and image queries.
     */
    constructor(
        embeddingStore: EmbeddingStore,
        embedder: EmbeddingModel
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
     * @return Ranked list of [SearchResult], sorted by descending similarity score (up to [topK]).
     */
    fun search(
        query: String,
        topK: Int = DEFAULT_TOP_K,
        threshold: Float = 0.0f,
        metadataFilter: Map<String, String> = emptyMap(),
        fileTypes: Collection<String>? = null
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
            fileTypes = fileTypes
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
     * Core nearest-neighbor search logic against [EmbeddingStore] given a precomputed 768-dimensional float embedding.
     *
     * Reused by text search and by visual similarity (search by image) queries, which pass an
     * image embedding obtained from [embedImage].
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
            embeddingStore.search(queryEmbedding, fetchLimit, metadataFilter + (ChunkMetadata.TYPE to fileTypes.first()))
        } else {
            // For categories spanning multiple formats (e.g. Docs spanning "pdf" and "text"),
            // retrieve top candidates for each type and merge before ranking
            fileTypes
                .flatMap { type ->
                    embeddingStore.search(queryEmbedding, fetchLimit, metadataFilter + (ChunkMetadata.TYPE to type))
                }
        }.distinctBy { it.id }

        // Map stored chunks to results (dropping the excluded source file) and rank them
        val results = records.mapNotNull { record ->
            // Extract text passage from TextPart if present
            val snippet = record.content
                .filterIsInstance<TextPart>()
                .firstOrNull()?.text
                ?: ""

            // Cosine similarity between query and chunk embedding if a vector is present
            val score = if (record.embeddings.isNotEmpty()) {
                VectorMath.cosineSimilarity(queryEmbedding, record.embeddings)
            } else {
                0f
            }

            ResultRanking.toResult(record.id, record.metadata, snippet, score, excludeFileUri)
        }

        return ResultRanking.rank(results, threshold, topK)
    }
}
