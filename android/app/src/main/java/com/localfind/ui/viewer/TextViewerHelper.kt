package com.localfind.ui.viewer

/**
 * Represents a single text line within a document with its 0-based character offsets.
 *
 * @property lineIndex 0-based line index in the document.
 * @property lineText String contents of the line (excluding newline delimiter).
 * @property startOffset 0-based character offset where this line starts in the document.
 * @property endOffset 0-based character offset where this line ends (exclusive of newline).
 */
data class TextLine(
    val lineIndex: Int,
    val lineText: String,
    val startOffset: Int,
    val endOffset: Int
)

/**
 * Pure helper methods for text document viewing, line splitting, offset resolution,
 * and search passage highlight range calculations.
 *
 * Extracted as pure Kotlin logic to enable fast, mock-free JVM unit testing (AGENTS.md §3).
 */
object TextViewerHelper {

    /**
     * Splits document [text] into [TextLine] items while computing exact character offsets.
     * Handles `\r\n` (CRLF) and `\n` (LF) line breaks correctly.
     */
    fun computeLines(text: String): List<TextLine> {
        if (text.isEmpty()) return emptyList()

        val lines = mutableListOf<TextLine>()
        var start = 0
        var lineIndex = 0
        val length = text.length

        while (start < length) {
            val newlineIndex = text.indexOf('\n', start)
            val lineEnd: Int
            val nextStart: Int

            if (newlineIndex >= 0) {
                // Check for preceding carriage return '\r'
                lineEnd = if (newlineIndex > start && text[newlineIndex - 1] == '\r') {
                    newlineIndex - 1
                } else {
                    newlineIndex
                }
                nextStart = newlineIndex + 1
            } else {
                lineEnd = length
                nextStart = length
            }

            lines.add(
                TextLine(
                    lineIndex = lineIndex++,
                    lineText = text.substring(start, lineEnd),
                    startOffset = start,
                    endOffset = lineEnd
                )
            )

            start = nextStart
        }

        // Special case: text ends with a trailing newline, add final empty line
        if (text.endsWith('\n') || text.endsWith('\r')) {
            lines.add(
                TextLine(
                    lineIndex = lineIndex,
                    lineText = "",
                    startOffset = length,
                    endOffset = length
                )
            )
        }

        return lines
    }

    /**
     * Finds the 0-based line index containing [targetOffset].
     *
     * @param lines Pre-calculated lines from [computeLines].
     * @param targetOffset 0-based character offset in the document.
     * @return The line index containing [targetOffset], or 0 if empty or out of range.
     */
    fun findTargetLineIndex(lines: List<TextLine>, targetOffset: Int): Int {
        if (lines.isEmpty() || targetOffset <= 0) return 0

        for (line in lines) {
            if (targetOffset in line.startOffset..line.endOffset) {
                return line.lineIndex
            }
        }

        // If offset is past the end, return the last line
        return lines.lastIndex
    }

    /**
     * Calculates the exact character range `[start, end)` within [text] to highlight.
     *
     * Rules:
     * - If [snippet] is provided and non-empty, looks for an exact or trimmed match near [targetOffset].
     *   If found, returns the matching range.
     * - If [snippet] is not found, but [targetOffset] > 0, returns a window starting at [targetOffset].
     * - If [targetOffset] == 0 and [snippet] is null/blank, returns `null` (no highlight, full reading mode).
     */
    fun findHighlightRange(text: String, targetOffset: Int, snippet: String?): IntRange? {
        if (text.isEmpty()) return null

        val trimmedSnippet = snippet?.trim().orEmpty()
        if (trimmedSnippet.isNotEmpty()) {
            // 1. Search for snippet around targetOffset (within a 200-char radius)
            val searchStart = (targetOffset - 100).coerceAtLeast(0)
            val localIndex = text.indexOf(trimmedSnippet, searchStart)
            if (localIndex >= 0 && localIndex <= targetOffset + 300) {
                return localIndex until (localIndex + trimmedSnippet.length)
            }

            // 2. Global search fallback for snippet
            val globalIndex = text.indexOf(trimmedSnippet)
            if (globalIndex >= 0) {
                return globalIndex until (globalIndex + trimmedSnippet.length)
            }

            // 3. Fallback: first 50 chars of snippet
            val prefix = trimmedSnippet.take(50)
            val prefixIndex = text.indexOf(prefix)
            if (prefixIndex >= 0) {
                return prefixIndex until (prefixIndex + prefix.length)
            }
        }

        // 4. Fallback to target offset if positive
        if (targetOffset > 0 && targetOffset < text.length) {
            val length = 80.coerceAtMost(text.length - targetOffset)
            return targetOffset until (targetOffset + length)
        }

        return null
    }

    /**
     * Determines whether a given [TextLine] intersects with [highlightRange].
     * If it intersects, returns the sub-range within the line's text [0..lineText.length) to highlight.
     */
    fun lineHighlightSubRange(line: TextLine, highlightRange: IntRange?): IntRange? {
        if (highlightRange == null) return null
        if (line.endOffset < highlightRange.first || line.startOffset >= highlightRange.last) {
            return null
        }

        val localStart = (highlightRange.first - line.startOffset).coerceIn(0, line.lineText.length)
        val localEnd = (highlightRange.last - line.startOffset).coerceIn(0, line.lineText.length)

        return if (localStart < localEnd) localStart until localEnd else null
    }
}
