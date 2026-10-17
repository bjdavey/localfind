package com.localfind.index

import com.localfind.data.ImportedFile
import com.localfind.data.IndexingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [IndexingProgressState], [IndexingStatus], and background progress calculations.
 *
 * Verifies that:
 * 1. [IndexingStatus] transitions and default states behave correctly for [ImportedFile].
 * 2. [IndexingProgressState] correctly records active file URI, chunks, and clamps progress fractions.
 * 3. Reset clears state back to idle.
 */
class IndexingWorkerLogicTest {

    @Test
    fun importedFile_defaultAndCustomIndexingStatus() {
        // Default constructor should default to INDEXED with 0 chunks
        val defaultFile = ImportedFile(
            uri = "content://media/1",
            displayName = "doc.txt",
            mimeType = "text/plain",
            size = 100L
        )
        assertEquals(IndexingStatus.INDEXED, defaultFile.indexingStatus)
        assertEquals(0, defaultFile.chunksCount)

        // Pending file
        val pendingFile = defaultFile.copy(
            indexingStatus = IndexingStatus.PENDING
        )
        assertEquals(IndexingStatus.PENDING, pendingFile.indexingStatus)

        // Indexing file
        val indexingFile = defaultFile.copy(
            indexingStatus = IndexingStatus.INDEXING,
            chunksCount = 5
        )
        assertEquals(IndexingStatus.INDEXING, indexingFile.indexingStatus)
        assertEquals(5, indexingFile.chunksCount)

        // Failed file
        val failedFile = defaultFile.copy(
            indexingStatus = IndexingStatus.FAILED
        )
        assertEquals(IndexingStatus.FAILED, failedFile.indexingStatus)
    }

    @Test
    fun indexingProgressState_updateAndClamping() {
        IndexingProgressState.reset()
        val initial = IndexingProgressState.progress.value
        assertFalse(initial.isIndexing)
        assertEquals(0f, initial.progressFraction, 0.001f)

        // Update with progress
        IndexingProgressState.update(
            isIndexing = true,
            currentUri = "content://media/test.mp4",
            currentFileName = "test.mp4",
            fileIndex = 1,
            totalFiles = 3,
            currentChunk = 2,
            totalChunks = 4,
            progressFraction = 0.5f,
            message = "Indexing 1/3: test.mp4 (50%)"
        )

        val updated = IndexingProgressState.progress.value
        assertTrue(updated.isIndexing)
        assertEquals("content://media/test.mp4", updated.currentUri)
        assertEquals("test.mp4", updated.currentFileName)
        assertEquals(1, updated.fileIndex)
        assertEquals(3, updated.totalFiles)
        assertEquals(2, updated.currentChunk)
        assertEquals(4, updated.totalChunks)
        assertEquals(0.5f, updated.progressFraction, 0.001f)
        assertEquals("Indexing 1/3: test.mp4 (50%)", updated.message)

        // Test fraction clamping (greater than 1.0f or less than 0.0f)
        IndexingProgressState.update(
            isIndexing = true,
            currentUri = "content://media/test.mp4",
            currentFileName = "test.mp4",
            fileIndex = 1,
            totalFiles = 3,
            currentChunk = 5,
            totalChunks = 4,
            progressFraction = 1.25f,
            message = "Indexing 1/3: test.mp4 (100%)"
        )
        assertEquals(1.0f, IndexingProgressState.progress.value.progressFraction, 0.001f)

        IndexingProgressState.update(
            isIndexing = true,
            currentUri = "content://media/test.mp4",
            currentFileName = "test.mp4",
            fileIndex = 1,
            totalFiles = 3,
            currentChunk = 0,
            totalChunks = 4,
            progressFraction = -0.5f,
            message = "Indexing 1/3: test.mp4 (0%)"
        )
        assertEquals(0.0f, IndexingProgressState.progress.value.progressFraction, 0.001f)

        // Reset
        IndexingProgressState.reset()
        val reset = IndexingProgressState.progress.value
        assertFalse(reset.isIndexing)
        assertEquals("Ready", reset.message)
    }

    @Test
    fun indexingCancellationState_trackAndClear() {
        IndexingCancellationState.clearAll()
        assertFalse(IndexingCancellationState.isFileCancelled("content://test/1"))

        IndexingCancellationState.cancelFile("content://test/1")
        assertTrue(IndexingCancellationState.isFileCancelled("content://test/1"))
        assertFalse(IndexingCancellationState.isFileCancelled("content://test/2"))

        IndexingCancellationState.cancelFile("content://test/2")
        assertTrue(IndexingCancellationState.isFileCancelled("content://test/1"))
        assertTrue(IndexingCancellationState.isFileCancelled("content://test/2"))

        IndexingCancellationState.clear("content://test/1")
        assertFalse(IndexingCancellationState.isFileCancelled("content://test/1"))
        assertTrue(IndexingCancellationState.isFileCancelled("content://test/2"))

        IndexingCancellationState.clearAll()
        assertFalse(IndexingCancellationState.isFileCancelled("content://test/2"))
    }

    @Test
    fun indexingProgressState_cancelledActiveFileReset() {
        IndexingProgressState.update(
            isIndexing = true,
            currentUri = "content://test/stuck.pdf",
            currentFileName = "stuck.pdf",
            fileIndex = 1,
            totalFiles = 1,
            currentChunk = 0,
            totalChunks = 10,
            progressFraction = 0f,
            message = "Indexing 1/1: stuck.pdf..."
        )
        assertTrue(IndexingProgressState.progress.value.isIndexing)
        assertEquals("content://test/stuck.pdf", IndexingProgressState.progress.value.currentUri)

        // Simulating cancellation of the active file
        IndexingCancellationState.cancelFile("content://test/stuck.pdf")
        assertTrue(IndexingCancellationState.isFileCancelled("content://test/stuck.pdf"))

        if (IndexingProgressState.progress.value.currentUri == "content://test/stuck.pdf") {
            IndexingProgressState.reset()
        }

        assertFalse(IndexingProgressState.progress.value.isIndexing)
        assertEquals("Ready", IndexingProgressState.progress.value.message)
    }

    @Test
    fun indexingStatus_enumValues() {
        val names = IndexingStatus.entries.map { it.name }
        assertTrue(names.contains("PENDING"))
        assertTrue(names.contains("INDEXING"))
        assertTrue(names.contains("INDEXED"))
        assertTrue(names.contains("FAILED"))
        assertEquals(4, IndexingStatus.entries.size)
    }
}

