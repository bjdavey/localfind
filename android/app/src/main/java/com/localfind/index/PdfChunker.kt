package com.localfind.index

/**
 * Represents a chunk of text extracted from a specific page of a PDF document.
 *
 * @property text The passage text to be embedded and indexed.
 * @property pageNumber 1-based page number where this text appears in the source PDF.
 *                      Used in Search results and to navigate directly in [com.localfind.ui.PdfViewerScreen].
 */
data class PdfChunk(
    val text: String,
    val pageNumber: Int
)

/**
 * Chunking logic for PDF documents according to ARCHITECTURE.md §5:
 * "PDF: Text of one page (long pages are split). Location: Page number".
 *
 * - Empty, blank, or kinda empty pages produce no chunks (text-based only, no OCR per PRD §2).
 * - Short or normal pages within target chunk size become a single chunk with location = pageNumber.
 * - Long pages are split into paragraph-aware chunks via [TextChunker.chunk], with each chunk
 *   retaining the 1-based page number.
 */
object PdfChunker {

    /**
     * Regex matching page numbering patterns (e.g. "Page 12", "12", "- 12 -", "Page 12 of 80", "12 / 80").
     */
    private val PAGE_NUMBER_REGEX = Regex("""(?i)^[-—–\s\\[\\]()]*([pP]age\s+)?\d+(\s*(of|/)\s*\d+)?[-—–\s\\[\\]()]*$""")

    /**
     * Regex matching standard "intentionally left blank" placeholder phrases commonly found in manuals/books.
     */
    private val BLANK_PLACEHOLDER_REGEX = Regex("""(?i)\bthis\s+page\s+(?:is\s+)?intentionally\s+left\s+blank\b""")

    /**
     * Determines whether the extracted text of a PDF page is considered "kinda empty".
     *
     * In real-world PDF documents, pages often contain only headers/footers, solitary page numbers,
     * watermarks ("Confidential", "Draft"), or placeholder notices ("This page intentionally left blank").
     * Indexing these creates useless 768-dim embeddings that pollute search ranking.
     *
     * A page is considered "kinda empty" if:
     * 1. It is blank or whitespace-only.
     * 2. It contains standard "intentionally left blank" placeholder notices.
     * 3. It contains solely page numbering or pagination markers (e.g. "Page 12", "12 of 80", "- 5 -").
     * 4. It contains fewer than 10 alphanumeric characters (e.g. stray symbols, dots, bullets).
     * 5. It contains fewer than 3 words (e.g. isolated watermarks like "Draft", "Confidential", "Chapter 1").
     * 6. It contains fewer than 4 words AND fewer than 25 alphanumeric characters (e.g. short section labels like "Part 1 - Intro").
     *
     * @param pageText Extracted text from a PDF page.
     * @return True if the page is considered kinda empty and should not be indexed; false otherwise.
     */
    fun isKindaEmpty(pageText: String): Boolean {
        val trimmed = pageText.trim()
        if (trimmed.isEmpty()) return true

        // Normalize whitespace for consistent pattern matching
        val normalized = trimmed.replace(Regex("""\s+"""), " ")

        // 1. Placeholder notice: "This page is intentionally left blank"
        if (normalized.length < 120 && BLANK_PLACEHOLDER_REGEX.containsMatchIn(normalized)) {
            return true
        }

        // 2. Standalone page number or pagination pattern
        if (PAGE_NUMBER_REGEX.matches(normalized)) {
            return true
        }

        // 3. Count meaningful alphanumeric characters and words
        val alphanumericCount = normalized.count { it.isLetterOrDigit() }
        if (alphanumericCount < 10) {
            return true
        }

        val words = normalized.split(" ").filter { word -> word.any { it.isLetterOrDigit() } }
        if (words.size < 3) {
            return true
        }
        if (words.size < 4 && alphanumericCount < 25) {
            return true
        }

        return false
    }

    /**
     * Splits extracted page text into one or more [PdfChunk] items.
     *
     * If the page is empty or "kinda empty" (per [isKindaEmpty]), an empty list is returned so
     * that no wasteful embedding vectors are computed or indexed.
     *
     * @param pageText Raw extracted text from a single PDF page.
     * @param pageNumber 1-based page number of this page.
     * @param targetChunkSize Preferred passage length.
     * @param overlapSize Character overlap between consecutive chunks if split.
     * @return List of [PdfChunk] items, each containing a passage and the page number.
     */
    fun chunkPage(
        pageText: String,
        pageNumber: Int,
        targetChunkSize: Int = 500,
        overlapSize: Int = 100
    ): List<PdfChunk> {
        val trimmed = pageText.trim()
        if (isKindaEmpty(trimmed)) return emptyList()

        val textChunks = TextChunker.chunk(trimmed, targetChunkSize, overlapSize)
        return textChunks.map { chunk ->
            PdfChunk(
                text = chunk.text,
                pageNumber = pageNumber
            )
        }
    }
}

