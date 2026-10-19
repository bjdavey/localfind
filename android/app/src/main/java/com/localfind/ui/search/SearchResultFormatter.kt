package com.localfind.ui.search

import java.util.Locale

/**
 * Utility functions for formatting [com.localfind.search.SearchResult] data for presentation in the UI.
 *
 * Implements formatting rules specified in PRD.md §2:
 * - Text: character offset of matching passage
 * - PDF: matching page number
 * - Audio/Video: matching timestamp (mm:ss)
 * - Similarity score: formatted as percentage
 */
object SearchResultFormatter {

    /**
     * Formats the location offset into a user-friendly description based on file type.
     *
     * @param fileType The file type category ("text", "pdf", "image", "audio", "video").
     * @param location Offset location (character offset for text, page for PDF, ms for audio/video).
     * @return Formatted string representation for the UI.
     */
    fun formatLocation(fileType: String, location: Long): String {
        return when (fileType.lowercase(Locale.ROOT)) {
            "text" -> "Offset $location"
            "pdf" -> "Page $location"
            "image" -> "Full image"
            "audio", "video" -> formatTimeMs(location)
            else -> "Location $location"
        }
    }

    /**
     * Formats a millisecond duration into a readable time string (mm:ss or hh:mm:ss).
     *
     * @param timeMs Time offset in milliseconds.
     * @return Formatted time string.
     */
    fun formatTimeMs(timeMs: Long): String {
        if (timeMs < 0) return "00:00"
        val totalSeconds = timeMs / 1000
        val seconds = totalSeconds % 60
        val totalMinutes = totalSeconds / 60
        val minutes = totalMinutes % 60
        val hours = totalMinutes / 60

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Formats the cosine similarity score (0.0 to 1.0) into a user-friendly percentage badge.
     *
     * @param score Raw similarity score from Searcher.
     * @return String representation like "85% match".
     */
    fun formatScore(score: Float): String {
        val clamped = score.coerceIn(0f, 1f)
        val percentage = (clamped * 100).toInt()
        return "$percentage% match"
    }

    /**
     * Cleans and truncates a snippet for display, replacing excessive whitespace and newlines.
     *
     * @param snippet Raw snippet text from chunk metadata.
     * @param maxLength Maximum length before truncating with ellipsis.
     * @return Formatted single- or multi-line snippet string.
     */
    fun formatSnippet(snippet: String, maxLength: Int = 300): String {
        val trimmed = snippet.trim()
        if (trimmed.length <= maxLength) {
            return trimmed
        }
        return trimmed.take(maxLength).trimEnd() + "…"
    }
}
