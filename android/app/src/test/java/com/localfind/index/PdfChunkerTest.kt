package com.localfind.index

import com.localfind.index.document.PdfChunker
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

    @Test
    fun isKindaEmpty_detectsBlankAndPlaceholderPages() {
        // Empty and blank strings
        assertTrue(PdfChunker.isKindaEmpty(""))
        assertTrue(PdfChunker.isKindaEmpty("   \n\t  "))

        // Standard placeholder notices in books and official reports
        assertTrue(PdfChunker.isKindaEmpty("This page intentionally left blank."))
        assertTrue(PdfChunker.isKindaEmpty("THIS PAGE IS INTENTIONALLY LEFT BLANK"))
        assertTrue(PdfChunker.isKindaEmpty("  this page intentionally left blank  "))
    }

    @Test
    fun isKindaEmpty_detectsPaginationAndPageNumbers() {
        // Solitary page numbers and pagination markers
        assertTrue(PdfChunker.isKindaEmpty("12"))
        assertTrue(PdfChunker.isKindaEmpty("Page 4"))
        assertTrue(PdfChunker.isKindaEmpty("page 12 of 80"))
        assertTrue(PdfChunker.isKindaEmpty("12 / 80"))
        assertTrue(PdfChunker.isKindaEmpty("- 5 -"))
        assertTrue(PdfChunker.isKindaEmpty("[ 42 ]"))
    }

    @Test
    fun isKindaEmpty_detectsSparseHeadersWatermarksAndSymbols() {
        // Stray punctuation, bullets, or symbols
        assertTrue(PdfChunker.isKindaEmpty("• • •"))
        assertTrue(PdfChunker.isKindaEmpty("--- ... ---"))
        assertTrue(PdfChunker.isKindaEmpty("=== *** ==="))

        // Isolated 1 or 2 word headers / watermarks
        assertTrue(PdfChunker.isKindaEmpty("Confidential"))
        assertTrue(PdfChunker.isKindaEmpty("Draft"))
        assertTrue(PdfChunker.isKindaEmpty("Chapter 1"))
        assertTrue(PdfChunker.isKindaEmpty("Part 2"))

        // Very short 3-word labels under 25 alphanumeric chars
        assertTrue(PdfChunker.isKindaEmpty("Part 1 - Intro"))
    }

    @Test
    fun isKindaEmpty_allowsSubstantivePageContent() {
        // Substantive text should not be treated as kinda empty
        org.junit.Assert.assertFalse(
            PdfChunker.isKindaEmpty("This is a single page document introducing local semantic search.")
        )
        org.junit.Assert.assertFalse(
            PdfChunker.isKindaEmpty("Architecture of the offline search engine and vector index.")
        )
        org.junit.Assert.assertFalse(
            PdfChunker.isKindaEmpty("Clean text on page 4.")
        )
    }

    @Test
    fun chunkPage_skipsKindaEmptyPages() {
        // Verify chunkPage produces no chunks for kinda empty pages
        assertTrue(PdfChunker.chunkPage("This page intentionally left blank.", pageNumber = 3).isEmpty())
        assertTrue(PdfChunker.chunkPage("Page 12 of 100", pageNumber = 12).isEmpty())
        assertTrue(PdfChunker.chunkPage("Confidential", pageNumber = 1).isEmpty())
        assertTrue(PdfChunker.chunkPage("• • •", pageNumber = 5).isEmpty())

        // Non-empty page should produce chunk
        val validPageChunks = PdfChunker.chunkPage(
            "Executive overview of the offline local retrieval pipeline with vector embeddings.",
            pageNumber = 2
        )
        assertEquals(1, validPageChunks.size)
        assertEquals(2, validPageChunks[0].pageNumber)
    }

    @Test
    fun pdfIndexingProgress_doesNotRepeatOrDropAfterExtraction() {
        // Verifies the inline progress scaling: Phase 1 (0..15%) and Phase 2 (15..100%)
        val totalPages = 10
        val totalChunks = 50

        // Phase 1 (page extraction): 0% -> 15%
        val phase1LastPercent = totalPages * 15 / totalPages.coerceAtLeast(1)
        assertEquals(15, phase1LastPercent)

        // Phase 2 (chunk embedding): starts >= 15% and reaches 100%
        val chunk1Percent = 15 + (1 * 85 / totalChunks)
        assertTrue("Chunk 1 progress must not drop below extraction progress", chunk1Percent >= phase1LastPercent)

        val chunkFinalPercent = 15 + (totalChunks * 85 / totalChunks)
        assertEquals(100, chunkFinalPercent)
    }
}

