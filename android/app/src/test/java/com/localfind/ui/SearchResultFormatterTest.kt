package com.localfind.ui

import com.localfind.ui.search.SearchResultFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [SearchResultFormatter].
 *
 * Verifies business logic for formatting location offsets, timestamps, scores, and snippets.
 */
class SearchResultFormatterTest {

    @Test
    fun formatLocationTextReturnsMatchingPassage() {
        assertEquals("Matching passage", SearchResultFormatter.formatLocation("text", 150L))
        assertEquals("Matching passage", SearchResultFormatter.formatLocation("TEXT", 0L))
    }

    @Test
    fun formatLocationPdfReturnsPageNumber() {
        assertEquals("Page 5", SearchResultFormatter.formatLocation("pdf", 5L))
        assertEquals("Page 1", SearchResultFormatter.formatLocation("PDF", 1L))
    }

    @Test
    fun formatLocationImageReturnsFullImage() {
        assertEquals("Full image", SearchResultFormatter.formatLocation("image", 0L))
    }

    @Test
    fun formatLocationAudioVideoReturnsFormattedTime() {
        // 65 seconds = 1 minute 5 seconds
        assertEquals("01:05", SearchResultFormatter.formatLocation("audio", 65_000L))
        // 3661 seconds = 1 hour 1 minute 1 second
        assertEquals("1:01:01", SearchResultFormatter.formatLocation("video", 3_661_000L))
    }

    @Test
    fun formatTimeMsHandlesNegativeAndZero() {
        assertEquals("00:00", SearchResultFormatter.formatTimeMs(-500L))
        assertEquals("00:00", SearchResultFormatter.formatTimeMs(0L))
        assertEquals("00:45", SearchResultFormatter.formatTimeMs(45_000L))
    }

    @Test
    fun formatScorePercentages() {
        assertEquals("100% match", SearchResultFormatter.formatScore(1.0f))
        assertEquals("75% match", SearchResultFormatter.formatScore(0.75f))
        assertEquals("0% match", SearchResultFormatter.formatScore(0.0f))
        // Handles out-of-range clamping
        assertEquals("100% match", SearchResultFormatter.formatScore(1.2f))
        assertEquals("0% match", SearchResultFormatter.formatScore(-0.1f))
    }

    @Test
    fun formatSnippetTruncatesProperly() {
        val shortSnippet = "Hello world"
        assertEquals("Hello world", SearchResultFormatter.formatSnippet(shortSnippet, 50))

        val longSnippet = "A".repeat(100)
        val formatted = SearchResultFormatter.formatSnippet(longSnippet, 10)
        assertEquals("AAAAAAAAAA…", formatted)
    }
}

