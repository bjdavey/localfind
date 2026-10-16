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
 * - Empty or blank pages produce no chunks (text-based only, no OCR per PRD §2).
 * - Short or normal pages within target chunk size become a single chunk with location = pageNumber.
 * - Long pages are split into paragraph-aware chunks via [TextChunker.chunk], with each chunk
 *   retaining the 1-based page number.
 */
object PdfChunker {
    /**
     * Splits extracted page text into one or more [PdfChunk] items.
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
        if (trimmed.isEmpty()) return emptyList()

        val textChunks = TextChunker.chunk(trimmed, targetChunkSize, overlapSize)
        return textChunks.map { chunk ->
            PdfChunk(
                text = chunk.text,
                pageNumber = pageNumber
            )
        }
    }
}

