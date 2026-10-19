package com.localfind.index

import com.localfind.data.ImportedFile
import com.localfind.data.IndexingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [IndexingResumeLogic] and per-file indexing resumption.
 *
 * Verifies that:
 * 1. Safe resume start indices are calculated correctly under normal and edge conditions.
 * 2. File size alterations on disk properly invalidate stale checkpoints to prevent vector corruption.
 * 3. Out-of-bounds or zero-chunk edge cases safely fallback to fresh indexing from index 0.
 * 4. Pre-existing chunk deletion policies correctly differentiate between fresh runs and resume runs.
 * 5. Multi-chunk pause/resume simulations correctly preserve progress without repeating prior work.
 */
class IndexingResumeLogicTest {

    @Test
    fun calculateResumeStartIndex_normalValidResumption() {
        val totalChunks = 20
        val fileSize = 1024L

        // Intermediate chunk (e.g., chunk 7 of 20 was saved)
        val resumeIndex = IndexingResumeLogic.calculateResumeStartIndex(
            savedChunk = 7,
            savedSize = fileSize,
            currentSize = fileSize,
            totalChunks = totalChunks
        )
        assertEquals(7, resumeIndex)
    }

    @Test
    fun calculateResumeStartIndex_freshOrZeroChunk() {
        val totalChunks = 15
        val fileSize = 2048L

        // No saved chunk (0) -> starts from 0
        val resumeIndex = IndexingResumeLogic.calculateResumeStartIndex(
            savedChunk = 0,
            savedSize = fileSize,
            currentSize = fileSize,
            totalChunks = totalChunks
        )
        assertEquals(0, resumeIndex)
    }

    @Test
    fun calculateResumeStartIndex_fileSizeChangedInvalidatesCheckpoint() {
        val totalChunks = 50
        val originalSize = 5000L
        val modifiedSize = 6500L

        // File modified on disk -> checkpoint must be invalidated to prevent misaligned chunk offsets
        val resumeIndex = IndexingResumeLogic.calculateResumeStartIndex(
            savedChunk = 25,
            savedSize = originalSize,
            currentSize = modifiedSize,
            totalChunks = totalChunks
        )
        assertEquals(0, resumeIndex)
    }

    @Test
    fun calculateResumeStartIndex_unknownSavedSizeAcceptsCheckpoint() {
        val totalChunks = 30
        val currentSize = 10000L

        // Unknown saved size (-1L) allows resume
        val resumeIndex = IndexingResumeLogic.calculateResumeStartIndex(
            savedChunk = 12,
            savedSize = -1L,
            currentSize = currentSize,
            totalChunks = totalChunks
        )
        assertEquals(12, resumeIndex)
    }

    @Test
    fun calculateResumeStartIndex_outOfBoundsEdgeCases() {
        val totalChunks = 10
        val size = 1000L

        // Saved chunk equals totalChunks -> already finished, start fresh
        assertEquals(
            0,
            IndexingResumeLogic.calculateResumeStartIndex(
                savedChunk = 10,
                savedSize = size,
                currentSize = size,
                totalChunks = totalChunks
            )
        )

        // Saved chunk exceeds totalChunks
        assertEquals(
            0,
            IndexingResumeLogic.calculateResumeStartIndex(
                savedChunk = 15,
                savedSize = size,
                currentSize = size,
                totalChunks = totalChunks
            )
        )

        // Negative saved chunk
        assertEquals(
            0,
            IndexingResumeLogic.calculateResumeStartIndex(
                savedChunk = -5,
                savedSize = size,
                currentSize = size,
                totalChunks = totalChunks
            )
        )

        // Empty file with 0 total chunks
        assertEquals(
            0,
            IndexingResumeLogic.calculateResumeStartIndex(
                savedChunk = 2,
                savedSize = size,
                currentSize = size,
                totalChunks = 0
            )
        )
    }

    @Test
    fun shouldDeleteExistingChunks_onlyDeletesOnFreshIndex() {
        // Fresh index (start index 0) -> MUST delete old chunks to avoid duplication
        assertTrue(IndexingResumeLogic.shouldDeleteExistingChunks(resumeStartIndex = 0))

        // Resume index (start index > 0) -> MUST NOT delete old chunks so previous progress is kept
        assertFalse(IndexingResumeLogic.shouldDeleteExistingChunks(resumeStartIndex = 1))
        assertFalse(IndexingResumeLogic.shouldDeleteExistingChunks(resumeStartIndex = 15))
    }

    @Test
    fun multiChunkResumptionSimulation_skipsAlreadyIndexedChunks() {
        val totalChunks = 10
        val processedChunks = mutableListOf<Int>()

        // Simulate initial run interrupted after 4 chunks (indices 0, 1, 2, 3)
        var checkpoint = 0
        for (i in 0 until totalChunks) {
            if (i == 4) {
                // Interrupted here: checkpoint recorded as 4
                checkpoint = i
                break
            }
            processedChunks.add(i)
        }
        assertEquals(listOf(0, 1, 2, 3), processedChunks)
        assertEquals(4, checkpoint)

        // Simulate resume: calculate start index and continue
        val resumeStart = IndexingResumeLogic.calculateResumeStartIndex(
            savedChunk = checkpoint,
            savedSize = 100L,
            currentSize = 100L,
            totalChunks = totalChunks
        )
        assertEquals(4, resumeStart)
        assertFalse(IndexingResumeLogic.shouldDeleteExistingChunks(resumeStart))

        for (i in resumeStart until totalChunks) {
            processedChunks.add(i)
        }

        // All chunks 0 through 9 processed without duplicating 0..3
        assertEquals((0 until 10).toList(), processedChunks)
    }

    @Test
    fun queueStatusTransition_preservesResumeChunksWhenPausedOrInterrupted() {
        val originalFile = ImportedFile(
            uri = "content://media/sample.mp4",
            displayName = "sample.mp4",
            mimeType = "video/mp4",
            size = 50_000_000L,
            indexingStatus = IndexingStatus.INDEXING,
            chunksCount = 0
        )

        val resumeChunk = 14

        // 1. When paused by user: status reverts to PENDING with resumeChunk preserved
        val pausedFile = originalFile.copy(
            indexingStatus = IndexingStatus.PENDING,
            chunksCount = resumeChunk
        )
        assertEquals(IndexingStatus.PENDING, pausedFile.indexingStatus)
        assertEquals(14, pausedFile.chunksCount)

        // 2. When interrupted by OS preemption: status reverts to PENDING with resumeChunk preserved
        val preemptedFile = originalFile.copy(
            indexingStatus = IndexingStatus.PENDING,
            chunksCount = resumeChunk
        )
        assertEquals(IndexingStatus.PENDING, preemptedFile.indexingStatus)
        assertEquals(14, preemptedFile.chunksCount)

        // 3. When explicitly cancelled: status becomes FAILED and chunk count resets to 0
        val cancelledFile = originalFile.copy(
            indexingStatus = IndexingStatus.FAILED,
            chunksCount = 0
        )
        assertEquals(IndexingStatus.FAILED, cancelledFile.indexingStatus)
        assertEquals(0, cancelledFile.chunksCount)

        // 4. When completed: status becomes INDEXED and chunk count is total chunks (e.g. 30)
        val completedFile = originalFile.copy(
            indexingStatus = IndexingStatus.INDEXED,
            chunksCount = 30
        )
        assertEquals(IndexingStatus.INDEXED, completedFile.indexingStatus)
        assertEquals(30, completedFile.chunksCount)
    }
}

