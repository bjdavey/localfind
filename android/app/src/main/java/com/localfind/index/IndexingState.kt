package com.localfind.index

import com.localfind.core.model.IndexingProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

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
        progressFraction: Float,
        message: String
    ) {
        _progress.value = IndexingProgress(
            isIndexing = isIndexing,
            currentUri = currentUri,
            currentFileName = currentFileName,
            fileIndex = fileIndex,
            totalFiles = totalFiles,
            currentChunk = currentChunk,
            progressFraction = progressFraction.coerceIn(0f, 1f),
            message = message
        )
    }

    /**
     * Updates state to reflect that the indexing queue has been paused.
     */
    fun setPaused(message: String) {
        val cur = _progress.value
        _progress.value = cur.copy(
            isIndexing = false,
            isPaused = true,
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
