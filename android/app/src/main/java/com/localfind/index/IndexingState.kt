package com.localfind.index

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

/**
 * Snapshot representing live background indexing progress for UI observation.
 *
 * @property isIndexing True if a background indexing worker is currently executing.
 * @property currentUri URI of the file currently being extracted and embedded.
 * @property currentFileName Display name of the active file.
 * @property fileIndex 1-based index of the active file within the current pending queue.
 * @property totalFiles Total number of files pending in the active batch.
 * @property currentChunk 1-based index of the chunk/page/window currently being embedded.
 * @property totalChunks Total chunks/pages/windows for the active file.
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
    val totalChunks: Int = 0,
    val progressFraction: Float = 0f,
    val message: String = ""
)

/**
 * Observable in-memory state manager for background indexing progress.
 *
 * Bridges WorkManager background task execution to Jetpack Compose UI state,
 * allowing instant per-file and banner progress updates without database polling overhead.
 */
object IndexingProgressState {
    private val _progress = MutableStateFlow(IndexingProgress())
    val progress: StateFlow<IndexingProgress> = _progress.asStateFlow()

    fun update(
        isIndexing: Boolean,
        currentUri: String?,
        currentFileName: String?,
        fileIndex: Int,
        totalFiles: Int,
        currentChunk: Int,
        totalChunks: Int,
        progressFraction: Float,
        message: String,
        isPaused: Boolean = false
    ) {
        _progress.value = IndexingProgress(
            isIndexing = isIndexing,
            isPaused = isPaused,
            currentUri = currentUri,
            currentFileName = currentFileName,
            fileIndex = fileIndex,
            totalFiles = totalFiles,
            currentChunk = currentChunk,
            totalChunks = totalChunks,
            progressFraction = progressFraction.coerceIn(0f, 1f),
            message = message
        )
    }

    /**
     * Updates state to reflect that the indexing queue has been paused.
     */
    fun setPaused(isPaused: Boolean, message: String = "Indexing paused") {
        val cur = _progress.value
        _progress.value = cur.copy(
            isIndexing = false,
            isPaused = isPaused,
            message = message
        )
    }

    fun reset() {
        _progress.value = IndexingProgress(isIndexing = false, isPaused = false, message = "Ready")
    }
}

/**
 * In-memory thread-safe state tracking for explicitly cancelled file URIs, queue cancellations,
 * and queue pausing.
 *
 * Allows real-time cooperative cancellation or pausing of running or queued indexing tasks
 * so stuck files and queues can be aborted or paused immediately without waiting for chunk loops to complete.
 */
object IndexingCancellationState {
    private val cancelledUris = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile
    private var allCancelled: Boolean = false
    @Volatile
    private var isPaused: Boolean = false

    /**
     * Signals cooperative cancellation to all running and enqueued indexing tasks.
     */
    fun cancelAll() {
        allCancelled = true
        isPaused = false
    }

    /**
     * Resets global cancellation state before launching a new indexing batch.
     */
    fun resetAllCancelled() {
        allCancelled = false
    }

    /**
     * Cancels indexing for a specific file URI.
     */
    fun cancelFile(uri: String) {
        cancelledUris.add(uri)
    }

    /**
     * Checks whether the specified file URI or the entire queue has been cancelled.
     */
    fun isCancelled(uri: String): Boolean {
        return allCancelled || cancelledUris.contains(uri)
    }

    fun isAllCancelled(): Boolean = allCancelled

    fun clear(uri: String) {
        cancelledUris.remove(uri)
    }

    fun clearAll() {
        allCancelled = false
        cancelledUris.clear()
        isPaused = false
    }

    /**
     * Flags the queue as paused for cooperative abortion of active loops.
     */
    fun pause() {
        isPaused = true
    }

    /**
     * Unsets the paused flag when resuming or enqueueing new work.
     */
    fun resume() {
        isPaused = false
    }

    fun isPaused(): Boolean = isPaused
}
