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
     * orders them by descending score and returns the best [topK].
     */
    fun rank(results: List<SearchResult>, threshold: Float, topK: Int): List<SearchResult> =
        results
            .filter { it.score >= threshold }
            .distinctBy { it.chunkId }
            .sortedByDescending { it.score }
            .take(topK)
}
