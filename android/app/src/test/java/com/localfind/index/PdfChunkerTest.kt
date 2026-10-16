package com.localfind.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PdfChunker].
 * Verifies page chunking, preservation of 1-based page numbers across split chunks,
 * empty page handling, and boundary behavior.
 */
class PdfChunkerTest {

    @Test
    fun chunkEmptyPageReturnsEmpty() {
        // Blank or whitespace-only PDF pages (e.g. scanned images without text) must produce no chunks
        assertTrue(PdfChunker.chunkPage("", pageNumber = 1).isEmpty())
        assertTrue(PdfChunker.chunkPage("   \n\n  \t  ", pageNumber = 3).isEmpty())
    }

    @Test
    fun chunkPageShortTextReturnsSingleChunkWithPageNumber() {
        // Short page text should result in exactly one chunk with the given page number
        val pageText = "This is a single page document introducing local semantic search."
        val chunks = PdfChunker.chunkPage(pageText, pageNumber = 2, targetChunkSize = 200)

        assertEquals(1, chunks.size)
        assertEquals(pageText, chunks[0].text)
        assertEquals(2, chunks[0].pageNumber)
    }

    @Test
    fun chunkPageLongTextSplitsAndPreservesPageNumber() {
        // Long pages that exceed target chunk size should be split into multiple passages,
        // and EVERY split chunk must retain the exact source page number
        val paragraph1 = "Section 1: Architecture of the offline search engine and vector index."
        val paragraph2 = "Section 2: EmbeddingGemma 2 multimodal representations for passages."
        val paragraph3 = "Section 3: Fast cosine similarity search with SqliteVectorStore."
        val longPageText = "$paragraph1\n\n$paragraph2\n\n$paragraph3"

        val chunks = PdfChunker.chunkPage(
            pageText = longPageText,
            pageNumber = 5,
            targetChunkSize = 80,
            overlapSize = 15
        )

        assertTrue("Expected multiple chunks for long page, got ${chunks.size}", chunks.size >= 2)
        for (chunk in chunks) {
            assertTrue("Chunk text should not be blank", chunk.text.isNotBlank())
            assertEquals("All chunks from page 5 must have pageNumber = 5", 5, chunk.pageNumber)
        }
    }

    @Test
    fun chunkPagePreservesDifferentPageNumbers() {
        // Verifies distinct page numbers are correctly tagged onto chunks
        val page1Chunks = PdfChunker.chunkPage("Introduction text on page 1", pageNumber = 1)
        val page10Chunks = PdfChunker.chunkPage("Conclusion text on page 10", pageNumber = 10)

        assertEquals(1, page1Chunks.size)
        assertEquals(1, page1Chunks[0].pageNumber)

        assertEquals(1, page10Chunks.size)
        assertEquals(10, page10Chunks[0].pageNumber)
    }

    @Test
    fun chunkPageTrimsWhitespace() {
        // Ensures leading/trailing whitespace on a page is trimmed
        val rawPage = "   \n\nClean text on page 4.  \n "
        val chunks = PdfChunker.chunkPage(rawPage, pageNumber = 4)

        assertEquals(1, chunks.size)
        assertEquals("Clean text on page 4.", chunks[0].text)
        assertEquals(4, chunks[0].pageNumber)
    }
}

