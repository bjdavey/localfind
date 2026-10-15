package com.localfind.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TextChunker].
 * Verifies chunking edge cases, paragraph splitting, whitespace offsets, and sliding window boundaries.
 */
class TextChunkerTest {

    @Test
    fun chunkEmptyStringReturnsEmpty() {
        // Empty or whitespace-only content should produce 0 chunks
        assertTrue(TextChunker.chunk("").isEmpty())
        assertTrue(TextChunker.chunk("   \n\n  ").isEmpty())
    }

    @Test
    fun chunkShortTextReturnsSingleChunk() {
        // Content smaller than targetChunkSize should be returned as a single chunk starting at offset 0
        val text = "Hello world! This is a test passage."
        val chunks = TextChunker.chunk(text, targetChunkSize = 100)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0].text)
        assertEquals(0, chunks[0].charOffset)
    }

    @Test
    fun chunkPreservesOffsetWithLeadingWhitespace() {
        // Verifies that character offset points to the actual trimmed text start (skipping leading spaces)
        val text = "   Indented text starts here."
        val chunks = TextChunker.chunk(text, targetChunkSize = 100)
        assertEquals(1, chunks.size)
        assertEquals("Indented text starts here.", chunks[0].text)
        assertEquals(3, chunks[0].charOffset)
    }

    @Test
    fun chunkSplitsOnParagraphBreaks() {
        // Verifies that chunker cleanly splits on double newlines (\n\n) without cutting sentences
        val p1 = "First paragraph content that has some words in it."
        val p2 = "Second paragraph content that has more words in it."
        val content = "$p1\n\n$p2"

        val chunks = TextChunker.chunk(content, targetChunkSize = 60, overlapSize = 10)
        assertTrue(chunks.size >= 2)
        assertEquals(p1, chunks[0].text)
        assertEquals(0, chunks[0].charOffset)
        assertEquals(p2, chunks[1].text)
        assertEquals(content.indexOf(p2), chunks[1].charOffset)
    }

    @Test
    fun chunkLongDocumentCreatesMultipleChunks() {
        // Simulates a multi-paragraph document and validates that every chunk's text
        // corresponds exactly to document.substring(charOffset, charOffset + text.length)
        val paragraphs = (1..10).map { i ->
            "Paragraph $i: This is a longer text body describing section number $i in sufficient detail."
        }
        val document = paragraphs.joinToString("\n\n")

        val chunks = TextChunker.chunk(document, targetChunkSize = 120, overlapSize = 20)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(chunk.text.isNotEmpty())
            assertTrue(chunk.charOffset >= 0)
            assertEquals(chunk.text, document.substring(chunk.charOffset, chunk.charOffset + chunk.text.length))
        }
    }
}
