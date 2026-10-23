package com.localfind.index.document

import androidx.core.net.toUri
import com.localfind.data.ImportedFile
import com.localfind.index.ChunkEmbeddingData
import com.localfind.index.INDEXING_CANCELLED_MESSAGE
import com.localfind.index.IndexResult
import com.localfind.index.Indexer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.BufferedReader
import java.io.InputStreamReader

// Text/Markdown (`.txt`, `.md`) and PDF (`.pdf`) indexing pipelines for [Indexer].
//
// Uses [Indexer.indexChunkedItems] to share resumable checkpointing, batch SQLite commits,
// cooperative cancellation, and metadata formatting across document modalities.

/**
 * Reads, chunks, embeds, and stores an imported text or Markdown file.
 *
 * Execution flow:
 * 1. Validates that the file is a supported text/markdown type.
 * 2. Opens an input stream from Android's ContentResolver using the SAF URI.
 * 3. Reads the text content into memory using UTF-8 encoding.
 * 4. Splits the text into semantic passages via [TextChunker.chunk].
 * 5. Embeds each chunk and persists batches via [Indexer.indexChunkedItems] with `type="text"`
 *    and `location` set to the 0-based character offset.
 */
fun Indexer.indexTextFile(
    file: ImportedFile,
    isCancelled: () -> Boolean,
    onProgress: ((current: Int, total: Int) -> Unit)?
): IndexResult {
    if (!isTextFile(file)) {
        return IndexResult(0, false, "Not a supported text file")
    }
    if (isCancelled()) {
        return IndexResult(0, false, INDEXING_CANCELLED_MESSAGE)
    }

    val uri = file.uri.toUri()
    val content = try {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return IndexResult(0, false, "Could not open stream for URI")
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText() }
    } catch (e: Exception) {
        return IndexResult(0, false, "Read failed: ${e.message}")
    }

    if (isCancelled()) {
        return IndexResult(0, false, INDEXING_CANCELLED_MESSAGE)
    }

    val chunks = TextChunker.chunk(
        content = content,
        targetChunkSize = settings.textChunkSize,
        overlapSize = settings.textChunkOverlap
    )

    return indexChunkedItems(
        file = file,
        type = "text",
        items = chunks,
        batchSize = 10,
        deleteOnEmpty = true,
        isCancelled = isCancelled,
        onProgress = onProgress
    ) { chunk ->
        ChunkEmbeddingData(
            snippetText = chunk.text,
            location = chunk.charOffset.toString(),
            embedding = embedder.embedText(chunk.text)
        )
    }
}

/**
 * Reads, extracts text per page, chunks, embeds, and stores an imported PDF file.
 *
 * Execution flow (ARCHITECTURE.md §5):
 * 1. Validates that the file is a PDF.
 * 2. Initializes [PDFBoxResourceLoader] if not already initialized.
 * 3. Opens the SAF input stream and loads the document via [PDDocument.load].
 * 4. Iterates through all pages, extracting text using [PDFTextStripper].
 * 5. Splits extracted text into passages via [PdfChunker.chunkPage], preserving 1-based page numbers.
 * 6. Embeds each chunk and persists batches via [Indexer.indexChunkedItems] with `type="pdf"`
 *    and `location` set to the 1-based page number.
 */
fun Indexer.indexPdfFile(
    file: ImportedFile,
    isCancelled: () -> Boolean,
    onProgress: ((current: Int, total: Int) -> Unit)?
): IndexResult {
    if (!isPdfFile(file)) {
        return IndexResult(0, false, "Not a supported PDF file")
    }
    if (isCancelled()) {
        return IndexResult(0, false, INDEXING_CANCELLED_MESSAGE)
    }

    try {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context)
        }
    } catch (e: Exception) {
        return IndexResult(0, false, "PDFBox initialization failed: ${e.message}")
    }

    val uri = file.uri.toUri()
    val chunks = mutableListOf<PdfChunk>()
    try {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return IndexResult(0, false, "Could not open stream for URI")

        inputStream.use { stream ->
            PDDocument.load(stream).use { document ->
                val stripper = PDFTextStripper()
                val totalPages = document.numberOfPages
                for (pageNum in 1..totalPages) {
                    if (isCancelled()) {
                        return IndexResult(0, false, INDEXING_CANCELLED_MESSAGE)
                    }
                    // Phase 1 (Text extraction): 0% to 15%
                    onProgress?.invoke(pageNum * 15 / totalPages.coerceAtLeast(1), 100)
                    stripper.startPage = pageNum
                    stripper.endPage = pageNum
                    val pageText = stripper.getText(document)
                    val pageChunks = PdfChunker.chunkPage(
                        pageText = pageText,
                        pageNumber = pageNum,
                        targetChunkSize = settings.textChunkSize,
                        overlapSize = settings.textChunkOverlap
                    )
                    chunks.addAll(pageChunks)
                }
            }
        }
    } catch (e: Exception) {
        return IndexResult(0, false, "PDF extraction failed: ${e.message}")
    }

    // Phase 2 (Embedding & persistence): 15% to 100%
    val chunkProgress: ((Int, Int) -> Unit)? = onProgress?.let { cb ->
        { cur, tot -> cb(15 + (if (tot > 0) cur * 85 / tot else 85), 100) }
    }

    return indexChunkedItems(
        file = file,
        type = "pdf",
        items = chunks,
        batchSize = 10,
        deleteOnEmpty = true,
        isCancelled = isCancelled,
        onProgress = chunkProgress
    ) { chunk ->
        ChunkEmbeddingData(
            snippetText = chunk.text,
            location = chunk.pageNumber.toString(),
            embedding = embedder.embedText(chunk.text)
        )
    }
}
