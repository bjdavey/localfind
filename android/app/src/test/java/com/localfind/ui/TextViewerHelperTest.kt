package com.localfind.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [TextViewerHelper].
 *
 * Verifies pure line splitting, offset mapping, search highlight range resolution,
 * and line-level intersection logic.
 */
class TextViewerHelperTest {

    @Test
    fun computeLinesHandlesEmptyString() {
        val lines = TextViewerHelper.computeLines("")
        assertEquals(0, lines.size)
    }

    @Test
    fun computeLinesHandlesSingleLine() {
        val lines = TextViewerHelper.computeLines("Hello world")
        assertEquals(1, lines.size)
        assertEquals("Hello world", lines[0].lineText)
        assertEquals(0, lines[0].startOffset)
        assertEquals(11, lines[0].endOffset)
    }

    @Test
    fun computeLinesHandlesMultipleLinesWithLfAndCrlf() {
        val text = "Line 1\nLine 2\r\nLine 3"
        val lines = TextViewerHelper.computeLines(text)

        assertEquals(3, lines.size)
        assertEquals("Line 1", lines[0].lineText)
        assertEquals(0, lines[0].startOffset)
        assertEquals(6, lines[0].endOffset)

        assertEquals("Line 2", lines[1].lineText)
        assertEquals(7, lines[1].startOffset)
        assertEquals(13, lines[1].endOffset)

        assertEquals("Line 3", lines[2].lineText)
        assertEquals(15, lines[2].startOffset)
        assertEquals(21, lines[2].endOffset)
    }

    @Test
    fun findTargetLineIndexFindsCorrectLine() {
        val text = "First line\nSecond line\nThird line\nFourth line"
        val lines = TextViewerHelper.computeLines(text)

        // Line 0 is 0..10
        assertEquals(0, TextViewerHelper.findTargetLineIndex(lines, 0))
        assertEquals(0, TextViewerHelper.findTargetLineIndex(lines, 5))

        // Line 1 is 11..22 ("Second line")
        assertEquals(1, TextViewerHelper.findTargetLineIndex(lines, 11))
        assertEquals(1, TextViewerHelper.findTargetLineIndex(lines, 15))

        // Line 2 is 23..33 ("Third line")
        assertEquals(2, TextViewerHelper.findTargetLineIndex(lines, 25))

        // Out of range returns last line
        assertEquals(3, TextViewerHelper.findTargetLineIndex(lines, 999))
    }

    @Test
    fun findHighlightRangeFindsMatchingSnippet() {
        val text = "The quick brown fox jumps over the lazy dog."
        val snippet = "brown fox"
        val range = TextViewerHelper.findHighlightRange(text, targetOffset = 10, snippet = snippet)

        assertNotNull(range)
        assertEquals(10 until 19, range)
        assertEquals("brown fox", text.substring(range!!))
    }

    @Test
    fun findHighlightRangeFallsBackToOffsetWhenSnippetMissing() {
        val text = "The quick brown fox jumps over the lazy dog."
        val range = TextViewerHelper.findHighlightRange(text, targetOffset = 16, snippet = null)

        assertNotNull(range)
        assertEquals(16, range!!.first)
    }

    @Test
    fun findHighlightRangeReturnsNullForWholeFileReading() {
        val text = "The quick brown fox jumps over the lazy dog."
        val range = TextViewerHelper.findHighlightRange(text, targetOffset = 0, snippet = null)
        assertNull(range)
    }

    @Test
    fun lineHighlightSubRangeCalculatesCorrectSubrange() {
        val line = TextLine(lineIndex = 1, lineText = "quick brown fox", startOffset = 10, endOffset = 25)

        // Highlight "brown" located at absolute 16..21
        val highlightRange = 16..21
        val subRange = TextViewerHelper.lineHighlightSubRange(line, highlightRange)

        assertNotNull(subRange)
        assertEquals(6 until 11, subRange)
        assertEquals("brown", line.lineText.substring(subRange!!))
    }

    @Test
    fun lineHighlightSubRangeReturnsNullWhenNoIntersection() {
        val line = TextLine(lineIndex = 0, lineText = "Hello", startOffset = 0, endOffset = 5)
        val highlightRange = 20..30
        assertNull(TextViewerHelper.lineHighlightSubRange(line, highlightRange))
    }
}

