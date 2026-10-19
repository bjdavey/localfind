package com.localfind.index.document

import com.localfind.data.DEFAULT_TEXT_CHUNK_OVERLAP
import com.localfind.data.DEFAULT_TEXT_CHUNK_SIZE

/**
 * Represents a text passage extracted from a document for embedding and indexing.
 *
 * @property text The actual passage string to be converted into an embedding.
 * @property charOffset 0-indexed character offset within the source document where this passage begins.
 *                      Used in Search / Viewer UI to scroll directly to the matching location.
 */
data class TextChunk(
    val text: String,
    val charOffset: Int
)

/**
 * Text splitting utility for semantic indexing.
 *
 * Strategy (ARCHITECTURE.md §5):
 * - Target chunk size: ~500 characters (representing a passage/paragraph, well within
 *   the optimal attention context of EmbeddingGemma 2).
 * - Paragraph-aware splitting: Prioritizes splitting on double newlines (`\n\n`) to preserve
 *   semantic cohesion within paragraphs.
 * - Secondary split fallback: If no paragraph break is within the target window, looks for
 *   single line breaks (`\n`) or word boundaries (` `) to avoid cutting words in half.
 * - Sliding window overlap: Overlaps contiguous chunks by ~100 characters when splitting across
 *   non-paragraph boundaries so boundary sentences/concepts are not lost.
 * - Exact offset preservation: Accurately computes [TextChunk.charOffset] taking into account
 *   leading trimmed whitespace.
 */
object TextChunker {

    /**
     * Splits document text into a list of [TextChunk] instances.
     *
     * @param content The full text content of the document.
     * @param targetChunkSize Preferred maximum character length per chunk.
     * @param overlapSize Character overlap between consecutive chunks when sliding.
     * @return List of non-empty [TextChunk] items with valid character offsets.
     */
    fun chunk(
        content: String,
        targetChunkSize: Int = DEFAULT_TEXT_CHUNK_SIZE,
        overlapSize: Int = DEFAULT_TEXT_CHUNK_OVERLAP
    ): List<TextChunk> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Short document optimization: Return as a single chunk if smaller than target size
        if (content.length <= targetChunkSize) {
            val startOffset = content.indexOf(trimmed)
            return listOf(TextChunk(text = trimmed, charOffset = if (startOffset >= 0) startOffset else 0))
        }

        val chunks = mutableListOf<TextChunk>()
        var start = 0
        val length = content.length

        while (start < length) {
            var end = (start + targetChunkSize).coerceAtMost(length)
            var splitOnParagraph = false

            if (end < length) {
                // Search for natural boundaries in the latter half of the window
                val searchRangeStart = (start + targetChunkSize / 2).coerceAtMost(end)

                // 1. Try finding a paragraph break (\n\n)
                val paragraphBreak = content.lastIndexOf("\n\n", end)
                if (paragraphBreak in searchRangeStart until end) {
                    end = paragraphBreak
                    splitOnParagraph = true
                } else {
                    // 2. Fall back to single newline (\n)
                    val lineBreak = content.lastIndexOf('\n', end)
                    if (lineBreak in searchRangeStart until end) {
                        end = lineBreak
                    } else {
                        // 3. Fall back to space character
                        val spaceBreak = content.lastIndexOf(' ', end)
                        if (spaceBreak in searchRangeStart until end) {
                            end = spaceBreak
                        }
                    }
                }
            }

            // Extract the chunk text and compute exact character offset
            val chunkSubstring = content.substring(start, end)
            val chunkTrimmed = chunkSubstring.trim()
            if (chunkTrimmed.isNotEmpty()) {
                val offsetInSubstring = chunkSubstring.indexOf(chunkTrimmed)
                val actualCharOffset = start + (if (offsetInSubstring >= 0) offsetInSubstring else 0)
                chunks.add(TextChunk(text = chunkTrimmed, charOffset = actualCharOffset))
            }

            if (end >= length) break

            if (splitOnParagraph) {
                // For clean paragraph breaks, start the next chunk right after the double newline
                var next = end + 2
                while (next < length && content[next].isWhitespace()) {
                    next++
                }
                start = next
            } else {
                // Otherwise advance with sliding window overlap
                val nextStart = (end - overlapSize).coerceAtLeast(start + 1)
                var adjustedNextStart = nextStart
                while (adjustedNextStart < length && content[adjustedNextStart].isWhitespace()) {
                    adjustedNextStart++
                }
                start = if (adjustedNextStart <= start) end else adjustedNextStart
            }
        }

        return chunks
    }
}
