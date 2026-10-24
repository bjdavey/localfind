package com.localfind.core.search

import com.localfind.core.model.ChunkMetadata
import com.localfind.core.model.FileKind
import com.localfind.core.model.SearchResult

/** Pure conversion and ranking of raw vector-store hits into [SearchResult]s. */
object ResultRanking {

    /**
     * Builds a [SearchResult] from a stored chunk using the metadata layout in [ChunkMetadata].
     *
     * @param snippet Text passage stored with the chunk (empty for images and media).
     * @param score Cosine similarity between the query and the chunk embedding.
     * @param excludeFileUri Source file to omit from results (e.g. the query image itself).
     * @return The result, or null when the chunk belongs to [excludeFileUri].
     */
    fun toResult(
        id: String,
        metadata: Map<String, String>,
        snippet: String,
        score: Float,
        excludeFileUri: String?
    ): SearchResult? {
        val fileUri = metadata[ChunkMetadata.URI] ?: metadata[ChunkMetadata.FILE_ID] ?: ""
        if (excludeFileUri != null && fileUri == excludeFileUri) return null
        return SearchResult(
            chunkId = id,
            fileUri = fileUri,
            fileName = metadata[ChunkMetadata.NAME] ?: "",
            fileType = metadata[ChunkMetadata.TYPE] ?: FileKind.TEXT.id,
            snippet = snippet,
            location = metadata[ChunkMetadata.LOCATION]?.toLongOrNull() ?: 0L,
            score = score
        )
    }

    /**
     * Keeps results at or above [threshold], removes duplicate chunk ids (so UI list keys stay unique),
     * orders them by descending score and applies [topK] twice: at most [topK] different files,
     * and at most [topK] hits per file (the ones the UI shows grouped under that file).
     */
    fun rank(results: List<SearchResult>, threshold: Float, topK: Int): List<SearchResult> =
        FileGrouping.groupByFile(
            results
                .filter { it.score >= threshold }
                .distinctBy { it.chunkId }
        )
            .take(topK)
            .flatMap { listOf(it.best) + it.others.take(topK - 1) }
            .sortedByDescending { it.score }

    /**
     * Number of raw chunks to fetch from the vector store so that [topK] files with up to [topK]
     * hits each can be filled (a plain top-K of chunks could be all from a few files). Capped to
     * keep the query cheap at large [topK].
     */
    fun candidateCount(topK: Int): Int = minOf(topK * topK, MAX_CANDIDATES)

    private const val MAX_CANDIDATES = 300
}