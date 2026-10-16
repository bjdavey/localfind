package com.localfind.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.net.toUri
import com.google.mediapipe.tasks.core.ImagePart
import com.google.mediapipe.tasks.core.TextPart
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import com.localfind.data.EmbeddingStore
import com.localfind.data.ImportedFile
import com.localfind.embed.Embedder
import com.localfind.ui.SearchResultFormatter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Maximum width or height (in pixels) used when decoding an image for embedding.
 *
 * Bounding dimensions to 512px keeps the intermediate pixel and TGA byte buffers small (~1 MB)
 * and speeds up ImageDecoder subsampling before the vision encoder resizes to its patch grid.
 */
const val MAX_IMAGE_DIMENSION = 512

/**
 * Result report from indexing a file.
 *
 * @property fileUri Document URI identifying the indexed file.
 * @property chunksCount Number of chunks extracted, embedded, and saved.
 * @property isSuccess Whether indexing succeeded without errors.
 * @property errorMessage Descriptive error message if indexing failed.
 */
data class IndexResult(
    val fileUri: String,
    val chunksCount: Int,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

/**
 * Orchestrates extracting chunks from imported files, embedding them with [Embedder],
 * and persisting the resulting vectors and metadata in [EmbeddingStore].
 *
 * Architecture and Design:
 * - Direct Content Access: Reads file content on-the-fly using the persistent SAF URI
 *   (ContentResolver.openInputStream) without copying the file into app storage.
 * - Idempotency: Before writing new chunks for a file, existing chunks associated with the
 *   file's URI are deleted via [EmbeddingStore.deleteByMetadata]. This prevents duplicate
 *   or outdated entries if a file is modified or re-indexed.
 * - Chunk Metadata Format:
 *     - fileId: The file's URI string (used for deleting all chunks of a file).
 *     - uri: The file's URI string (used to open/render the document).
 *     - name: Display name shown in search result cards.
 *     - type: Category ("text", "pdf", "image"; future: "audio", "video").
 *     - location: Offset location (character offset for text, 1-based page number for PDF,
 *       0 for whole-image records).
 */
class Indexer(
    private val context: Context,
    private val embedder: Embedder,
    private val embeddingStore: EmbeddingStore
) {

    /**
     * Determines whether an imported file is supported by the text/markdown indexer.
     *
     * Supported formats (PRD §2):
     * - Extensions: .txt, .md
     * - MIME types: text/plain, text/markdown, or any text/ MIME type.
     */
    fun isTextFile(file: ImportedFile): Boolean {
        val name = file.displayName.lowercase()
        val mime = file.mimeType?.lowercase() ?: ""
        return name.endsWith(".txt") ||
                name.endsWith(".md") ||
                mime.startsWith("text/") ||
                mime == "text/plain" ||
                mime == "text/markdown"
    }

    /**
     * Determines whether an imported file is a PDF document.
     *
     * Supported formats (PRD §2):
     * - Extensions: .pdf
     * - MIME types: application/pdf
     */
    fun isPdfFile(file: ImportedFile): Boolean {
        val name = file.displayName.lowercase()
        val mime = file.mimeType?.lowercase() ?: ""
        return name.endsWith(".pdf") || mime == "application/pdf"
    }

    /**
     * Determines whether an imported file is a supported image file.
     *
     * Supported formats (PRD §2):
     * - Extensions: .jpg, .jpeg, .png
     * - MIME types: image/jpeg, image/jpg, image/png
     */
    fun isImageFile(file: ImportedFile): Boolean =
        isImageNameOrMime(file.displayName, file.mimeType)

    /**
     * Determines whether an imported file is a supported audio file.
     *
     * Supported formats (PRD §2): .mp3, .m4a, .wav (or any audio/ MIME type).
     */
    fun isAudioFile(file: ImportedFile): Boolean =
        isAudioNameOrMime(file.displayName, file.mimeType)

    /**
     * Determines whether an imported file is supported by the indexer.
     */
    fun isSupported(file: ImportedFile): Boolean =
        isTextFile(file) || isPdfFile(file) || isImageFile(file) || isAudioFile(file)

    /**
     * Reads, chunks, embeds, and stores an imported text or Markdown file.
     *
     * Execution flow:
     * 1. Validates that the file is a supported text/markdown type.
     * 2. Opens an input stream from Android's ContentResolver using the SAF URI.
     * 3. Reads the text content into memory using UTF-8 encoding.
     * 4. Splits the text into semantic passages via [TextChunker.chunk].
     * 5. Deletes any pre-existing chunks for this file from [EmbeddingStore] to maintain consistency.
     * 6. Generates a 768-dimensional normalized embedding for each chunk using [Embedder.embedText].
     * 7. Stores the batch of [RetrievalRecord] items in [EmbeddingStore] within a single transaction.
     *
     * @param file Metadata of the imported file to index.
     * @return [IndexResult] indicating success/failure and chunk count.
     */
    fun indexTextFile(file: ImportedFile): IndexResult {
        if (!isTextFile(file)) {
            return IndexResult(file.uri, 0, false, "Not a supported text file")
        }

        val uri = file.uri.toUri()
        val content = try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return IndexResult(file.uri, 0, false, "Could not open stream for URI")
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (e: Exception) {
            return IndexResult(file.uri, 0, false, "Read failed: ${e.message}")
        }

        val chunks = TextChunker.chunk(content)
        if (chunks.isEmpty()) {
            // If the document is empty or whitespace-only, remove any old chunks and return
            embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))
            return IndexResult(file.uri, 0, true)
        }

        // Delete existing chunks for this file before re-indexing to prevent duplicate entries
        embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))

        val records = mutableListOf<RetrievalRecord>()
        for ((index, chunk) in chunks.withIndex()) {
            // Unique ID per chunk: "<fileUri>_<chunkIndex>"
            val chunkId = "${file.uri}_$index"

            // Compute 768-dim float embedding vector via on-device EmbeddingGemma 2
            val embedding = embedder.embedText(chunk.text)

            // Structured metadata stored alongside the vector in SQLite
            val metadata = mapOf(
                "fileId" to file.uri,
                "uri" to file.uri,
                "name" to file.displayName,
                "type" to "text",
                "location" to chunk.charOffset.toString()
            )

            records.add(
                RetrievalRecord(
                    chunkId,
                    listOf(TextPart(chunk.text)),
                    embedding,
                    metadata
                )
            )
        }

        // Store all chunk records in one atomic SQLite transaction
        embeddingStore.storeBatch(records)
        return IndexResult(file.uri, records.size, true)
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
     * 6. Deletes any pre-existing chunks for this file from [EmbeddingStore].
     * 7. Computes 768-dim embeddings for each chunk and persists them in SQLite with type="pdf"
     *    and location set to the 1-based page number.
     *
     * @param file Metadata of the imported PDF to index.
     * @return [IndexResult] indicating success/failure and chunk count.
     */
    fun indexPdfFile(file: ImportedFile): IndexResult {
        if (!isPdfFile(file)) {
            return IndexResult(file.uri, 0, false, "Not a supported PDF file")
        }

        // Initialize PDFBox resource loader if not already initialized
        try {
            if (!PDFBoxResourceLoader.isReady()) {
                PDFBoxResourceLoader.init(context)
            }
        } catch (e: Exception) {
            return IndexResult(file.uri, 0, false, "PDFBox initialization failed: ${e.message}")
        }

        val uri = file.uri.toUri()
        val chunks = mutableListOf<PdfChunk>()
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return IndexResult(file.uri, 0, false, "Could not open stream for URI")

            inputStream.use { stream ->
                PDDocument.load(stream).use { document ->
                    val stripper = PDFTextStripper()
                    val totalPages = document.numberOfPages
                    for (pageNum in 1..totalPages) {
                        stripper.startPage = pageNum
                        stripper.endPage = pageNum
                        val pageText = stripper.getText(document)
                        val pageChunks = PdfChunker.chunkPage(pageText, pageNum)
                        chunks.addAll(pageChunks)
                    }
                }
            }
        } catch (e: Exception) {
            return IndexResult(file.uri, 0, false, "PDF extraction failed: ${e.message}")
        }

        // Delete existing chunks for this file before re-indexing
        embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))

        if (chunks.isEmpty()) {
            // E.g. empty or scanned image-only PDF with no extractable text
            return IndexResult(file.uri, 0, true)
        }

        val records = mutableListOf<RetrievalRecord>()
        for ((index, chunk) in chunks.withIndex()) {
            val chunkId = "${file.uri}_$index"
            val embedding = embedder.embedText(chunk.text)
            val metadata = mapOf(
                "fileId" to file.uri,
                "uri" to file.uri,
                "name" to file.displayName,
                "type" to "pdf",
                "location" to chunk.pageNumber.toString()
            )

            records.add(
                RetrievalRecord(
                    chunkId,
                    listOf(TextPart(chunk.text)),
                    embedding,
                    metadata
                )
            )
        }

        embeddingStore.storeBatch(records)
        return IndexResult(file.uri, records.size, true)
    }

    /**
     * Decodes, normalizes, embeds, and stores an imported image file (.jpg, .png).
     *
     * Execution flow (ARCHITECTURE.md §5 & GOOGLE_AI_EDGE_GALLERY.md §6.3):
     * 1. Validates that the file is a supported image format (.jpg, .jpeg, .png).
     * 2. Decodes the image from the SAF URI into a software-backed ARGB_8888 [Bitmap],
     *    applying EXIF orientation corrections and bounding dimensions to [MAX_IMAGE_DIMENSION]
     *    to keep memory usage low.
     * 3. Computes a 768-dimensional normalized vector for the whole image via [Embedder.embedImage].
     * 4. Deletes any pre-existing chunks for this file from [EmbeddingStore].
     * 5. Stores a single [RetrievalRecord] with type="image" and location="0" in [EmbeddingStore].
     *
     * @param file Metadata of the imported image to index.
     * @return [IndexResult] indicating success/failure and chunk count (1 for a valid image).
     */
    fun indexImageFile(file: ImportedFile): IndexResult {
        if (!isImageFile(file)) {
            return IndexResult(file.uri, 0, false, "Not a supported image file")
        }

        val uri = file.uri.toUri()
        val bitmap = try {
            decodeNormalizedBitmap(context, uri)
                ?: return IndexResult(file.uri, 0, false, "Could not decode image from URI")
        } catch (e: Exception) {
            return IndexResult(file.uri, 0, false, "Image decode failed: ${e.message}")
        }

        val embedding = try {
            embedder.embedImage(bitmap)
        } catch (e: Exception) {
            return IndexResult(file.uri, 0, false, "Image embedding failed: ${e.message}")
        } finally {
            bitmap.recycle()
        }

        // Delete existing chunks for this file before storing the updated embedding
        embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))

        val chunkId = "${file.uri}_0"
        val metadata = mapOf(
            "fileId" to file.uri,
            "uri" to file.uri,
            "name" to file.displayName,
            "type" to "image",
            "location" to "0"
        )

        val record = RetrievalRecord(
            chunkId,
            listOf(ImagePart(uri)),
            embedding,
            metadata
        )

        embeddingStore.storeBatch(listOf(record))
        return IndexResult(file.uri, 1, true)
    }

    /**
     * Decodes, windows, embeds, and stores an imported audio file (.mp3, .m4a, .wav).
     *
     * Execution flow (ARCHITECTURE.md §5):
     * 1. Decodes the file to 16 kHz mono PCM via [AudioDecoder].
     * 2. Splits it into fixed 10 s windows via [AudioChunker.windows].
     * 3. Embeds each window directly with the multimodal model ([Embedder.embedAudio]);
     *    there is no speech-to-text step.
     * 4. Replaces any old chunks of this file with one record per window: type="audio",
     *    location = window start time in milliseconds (used to seek the player).
     *
     * @param file Metadata of the imported audio file to index.
     * @return [IndexResult] indicating success/failure and chunk count.
     */
    fun indexAudioFile(file: ImportedFile): IndexResult {
        if (!isAudioFile(file)) {
            return IndexResult(file.uri, 0, false, "Not a supported audio file")
        }

        val pcm = try {
            AudioDecoder.decodeToMono16k(context, file.uri.toUri())
        } catch (e: Exception) {
            return IndexResult(file.uri, 0, false, "Audio decode failed: ${e.message}")
        }

        val windows = AudioChunker.windows(pcm.size)
        val records = mutableListOf<RetrievalRecord>()
        try {
            for ((index, window) in windows.withIndex()) {
                val embedding = embedder.embedAudio(pcm.copyOfRange(window.startSample, window.endSample))
                val metadata = mapOf(
                    "fileId" to file.uri,
                    "uri" to file.uri,
                    "name" to file.displayName,
                    "type" to "audio",
                    "location" to window.startMs.toString()
                )
                // Text label only: the audio itself stays in the linked file (no copy)
                val label = "${file.displayName} @ ${SearchResultFormatter.formatTimeMs(window.startMs)}"
                records.add(
                    RetrievalRecord("${file.uri}_$index", listOf(TextPart(label)), embedding, metadata)
                )
            }
        } catch (e: Exception) {
            return IndexResult(file.uri, 0, false, "Audio embedding failed: ${e.message}")
        }

        // Replace old chunks only after embedding succeeded
        embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))
        if (records.isNotEmpty()) embeddingStore.storeBatch(records)
        return IndexResult(file.uri, records.size, true)
    }

    /**
     * Automatically dispatches indexing based on the file format (Text/Markdown, PDF, Image or Audio).
     */
    fun indexFile(file: ImportedFile): IndexResult {
        return when {
            isPdfFile(file) -> indexPdfFile(file)
            isImageFile(file) -> indexImageFile(file)
            isAudioFile(file) -> indexAudioFile(file)
            isTextFile(file) -> indexTextFile(file)
            else -> IndexResult(file.uri, 0, false, "Unsupported file format")
        }
    }

    companion object {
        /**
         * Pure helper that checks whether a file name or MIME type corresponds to a supported
         * audio format (.mp3, .m4a, .wav or any audio/ MIME type).
         */
        fun isAudioNameOrMime(displayName: String, mimeType: String?): Boolean {
            val name = displayName.lowercase()
            return name.endsWith(".mp3") ||
                    name.endsWith(".m4a") ||
                    name.endsWith(".wav") ||
                    mimeType?.lowercase()?.startsWith("audio/") == true
        }

        /**
         * Pure helper that checks whether a file name or MIME type corresponds to a supported
         * image format (.jpg, .jpeg, .png).
         */
        fun isImageNameOrMime(displayName: String, mimeType: String?): Boolean {
            val name = displayName.lowercase()
            val mime = mimeType?.lowercase() ?: ""
            return name.endsWith(".jpg") ||
                    name.endsWith(".jpeg") ||
                    name.endsWith(".png") ||
                    mime == "image/jpeg" ||
                    mime == "image/jpg" ||
                    mime == "image/png"
        }

        /**
         * Calculates bounded target dimensions (width, height) that fit within [maxDimension]
         * while preserving the original aspect ratio.
         *
         * @param width Original image width in pixels.
         * @param height Original image height in pixels.
         * @param maxDimension Maximum allowed width or height (default [MAX_IMAGE_DIMENSION]).
         * @return Pair of (targetWidth, targetHeight), each guaranteed to be >= 1.
         */
        fun calculateTargetDimensions(
            width: Int,
            height: Int,
            maxDimension: Int = MAX_IMAGE_DIMENSION
        ): Pair<Int, Int> {
            val safeW = width.coerceAtLeast(1)
            val safeH = height.coerceAtLeast(1)
            val safeMax = maxDimension.coerceAtLeast(1)
            val maxSide = maxOf(safeW, safeH)
            if (maxSide <= safeMax) {
                return safeW to safeH
            }
            val scale = safeMax.toFloat() / maxSide.toFloat()
            val targetW = (safeW * scale).toInt().coerceAtLeast(1)
            val targetH = (safeH * scale).toInt().coerceAtLeast(1)
            return targetW to targetH
        }

        /**
         * Decodes an image from [uri] into a software-backed `ARGB_8888` [Bitmap] with EXIF
         * orientation applied and dimensions bounded by [maxDimension].
         *
         * Uses Android's [ImageDecoder] (API 28+), which performs subsampling, scaling, and
         * EXIF rotation/flipping in a single native C++ pass.
         */
        fun decodeNormalizedBitmap(
            context: Context,
            uri: Uri,
            maxDimension: Int = MAX_IMAGE_DIMENSION
        ): Bitmap {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (targetW, targetH) = calculateTargetDimensions(
                    info.size.width,
                    info.size.height,
                    maxDimension
                )
                if (targetW != info.size.width || targetH != info.size.height) {
                    decoder.setTargetSize(targetW, targetH)
                }
                // Software allocator is required because UniversalEmbedder calls Bitmap.getPixels()
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
    }
}
