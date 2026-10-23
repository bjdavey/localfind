package com.localfind.core.indexing

/**
 * Pure business logic for validating and calculating indexing resume points.
 *
 * Platform independent, so it is covered by fast, deterministic unit tests.
 */
object IndexingResumeLogic {

    /**
     * Calculates the safe 0-based chunk/window index to resume indexing from.
     *
     * Validates that:
     * 1. The file size has not changed since the checkpoint was recorded (preventing misaligned chunks).
     * 2. The saved chunk index is within valid bounds: `0 <= savedChunk < totalChunks`.
     *
     * @param savedChunk The last recorded chunk index from persistent storage.
     * @param savedSize File size recorded when the checkpoint was saved, or -1 if unknown.
     * @param currentSize Current file size on disk in bytes.
     * @param totalChunks Total chunks/windows planned for this file.
     * @return Validated chunk index to resume from, or 0 to start indexing fresh.
     */
    fun calculateResumeStartIndex(
        savedChunk: Int,
        savedSize: Long,
        currentSize: Long,
        totalChunks: Int
    ): Int {
        if (totalChunks <= 0) return 0
        // If file size changed on disk, previous chunks are invalidated
        if (savedSize != -1L && savedSize != currentSize) return 0
        // Bounds check
        if (savedChunk <= 0 || savedChunk >= totalChunks) return 0
        return savedChunk
    }

    /**
     * Determines whether pre-existing chunks for a file must be cleared before indexing.
     *
     * If starting fresh (`resumeStartIndex == 0`), old chunks should be wiped to maintain idempotency.
     * If resuming (`resumeStartIndex > 0`), existing chunks must be preserved so previous work is not lost.
     */
    fun shouldDeleteExistingChunks(resumeStartIndex: Int): Boolean = resumeStartIndex == 0
}
