package com.localfind.core.model

/**
 * Snapshot representing live background indexing progress for UI observation.
 *
 * @property isIndexing True if a background indexing worker is currently executing.
 * @property currentUri URI of the file currently being extracted and embedded.
 * @property currentFileName Display name of the active file.
 * @property fileIndex 1-based index of the active file within the current pending queue.
 * @property totalFiles Total number of files pending in the active batch.
 * @property currentChunk 1-based index of the chunk/page/window currently being embedded.
 * @property progressFraction Floating ratio (0.0f..1.0f) representing current file completion.
 * @property message Human-readable status string for top banner progress displays.
 */
data class IndexingProgress(
    val isIndexing: Boolean = false,
    val isPaused: Boolean = false,
    val currentUri: String? = null,
    val currentFileName: String? = null,
    val fileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentChunk: Int = 0,
    val progressFraction: Float = 0f,
    val message: String = ""
)
