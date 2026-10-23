package com.localfind.core.model

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
