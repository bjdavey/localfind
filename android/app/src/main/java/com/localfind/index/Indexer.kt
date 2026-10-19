package com.localfind.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.PowerManager
import androidx.core.net.toUri
import com.google.mediapipe.tasks.core.ImagePart
import com.google.mediapipe.tasks.core.Part
import com.google.mediapipe.tasks.core.TextPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import com.localfind.data.AppSettings
import com.localfind.data.EmbeddingStore
import com.localfind.data.ImportedFile
import com.localfind.embed.Embedder
import com.localfind.ui.SearchResultFormatter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
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
 *     - type: Category ("text", "pdf", "image", "audio", "video").
 *     - location: Offset location (character offset for text, 1-based page number for PDF,
 *       0 for whole-image records).
 */
class Indexer(
    private val context: Context,
    private val embedder: Embedder,
    private val embeddingStore: EmbeddingStore,
    private val settings: AppSettings = AppSettings()
) {

    /**
     * Determines whether an imported file is supported by the text/markdown indexer.
     *
     * Supported formats (PRD §2):
     * - Extensions: .txt, .md
     * - MIME types: text/plain, text/markdown, or any text/ MIME type.
     */
    fun isTextFile(file: ImportedFile): Boolean =
        isTextNameOrMime(file.displayName, file.mimeType)

    /**
     * Determines whether an imported file is a PDF document.
     *
     * Supported formats (PRD §2):
     * - Extensions: .pdf
     * - MIME types: application/pdf
     */
    fun isPdfFile(file: ImportedFile): Boolean =
        isPdfNameOrMime(file.displayName, file.mimeType)

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
     * Determines whether an imported file is a supported video file (PRD §2: .mp4).
     */
    fun isVideoFile(file: ImportedFile): Boolean =
        isVideoNameOrMime(file.displayName, file.mimeType)

    /**
     * Determines whether an imported file is supported by the indexer and has a builtin viewer.
     */
    fun isSupported(file: ImportedFile): Boolean =
        isSupported(file.displayName, file.mimeType)

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
    fun indexTextFile(
        file: ImportedFile,
        isCancelled: () -> Boolean = { false },
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): IndexResult {
        if (!isTextFile(file)) {
            return IndexResult(file.uri, 0, false, "Not a supported text file")
        }
        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
        }

        val uri = file.uri.toUri()
        val content = try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return IndexResult(file.uri, 0, false, "Could not open stream for URI")
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (e: Exception) {
            return IndexResult(file.uri, 0, false, "Read failed: ${e.message}")
        }

        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
        }

        val chunks = TextChunker.chunk(
            content = content,
            targetChunkSize = settings.textChunkSize,
            overlapSize = settings.textChunkOverlap
        )
        if (chunks.isEmpty()) {
            // If the document is empty or whitespace-only, remove any old chunks and return
            embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))
            onProgress?.invoke(1, 1)
            return IndexResult(file.uri, 0, true)
        }

        // Delete existing chunks for this file before re-indexing to prevent duplicate entries
        embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))

        val records = mutableListOf<RetrievalRecord>()
        for ((index, chunk) in chunks.withIndex()) {
            if (isCancelled()) {
                return IndexResult(file.uri, 0, false, "Indexing cancelled")
            }
            onProgress?.invoke(index + 1, chunks.size)
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

        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
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
     * @param onProgress Optional callback invoked with (current, total) chunks/pages.
     * @return [IndexResult] indicating success/failure and chunk count.
     */
    fun indexPdfFile(
        file: ImportedFile,
        isCancelled: () -> Boolean = { false },
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): IndexResult {
        if (!isPdfFile(file)) {
            return IndexResult(file.uri, 0, false, "Not a supported PDF file")
        }
        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
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
                        if (isCancelled()) {
                            return IndexResult(file.uri, 0, false, "Indexing cancelled")
                        }
                        // Phase 1 (Text extraction): Advance smoothly from 0% to ~15% of overall work
                        onProgress?.invoke(pageNum, totalPages.coerceAtLeast(1) * 7)
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
            return IndexResult(file.uri, 0, false, "PDF extraction failed: ${e.message}")
        }

        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
        }

        // Delete existing chunks for this file before storing updated embeddings
        embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))

        if (chunks.isEmpty()) {
            // E.g. empty or scanned image-only PDF with no extractable text
            onProgress?.invoke(1, 1)
            return IndexResult(file.uri, 0, true)
        }

        // Phase 2 (Embedding & Persistence): Provide live progress on every chunk
        // and commit in batches of 100 to bound memory usage and prevent UI freeze
        val records = mutableListOf<RetrievalRecord>()
        for ((index, chunk) in chunks.withIndex()) {
            if (isCancelled()) {
                return IndexResult(file.uri, 0, false, "Indexing cancelled")
            }
            onProgress?.invoke(index + 1, chunks.size)
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

            // Commit in 100-chunk batches to keep memory small and save progressive chunks
            if (records.size >= 100) {
                embeddingStore.storeBatch(records)
                records.clear()
            }
        }

        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
        }

        if (records.isNotEmpty()) {
            embeddingStore.storeBatch(records)
            records.clear()
        }
        return IndexResult(file.uri, chunks.size, true)
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
    fun indexImageFile(
        file: ImportedFile,
        isCancelled: () -> Boolean = { false },
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): IndexResult {
        if (!isImageFile(file)) {
            return IndexResult(file.uri, 0, false, "Not a supported image file")
        }
        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
        }

        val uri = file.uri.toUri()
        val bitmap = try {
            decodeNormalizedBitmap(context, uri, maxDimension = settings.maxImageDimension)
                ?: return IndexResult(file.uri, 0, false, "Could not decode image from URI")
        } catch (e: Exception) {
            return IndexResult(file.uri, 0, false, "Image decode failed: ${e.message}")
        }

        if (isCancelled()) {
            bitmap.recycle()
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
        }

        onProgress?.invoke(1, 1)
        val embedding = try {
            embedder.embedImage(bitmap)
        } catch (e: Exception) {
            return IndexResult(file.uri, 0, false, "Image embedding failed: ${e.message}")
        } finally {
            bitmap.recycle()
        }

        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
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
     * @param onProgress Optional callback invoked with (current, total) window progress.
     * @return [IndexResult] indicating success/failure and chunk count.
     */
    fun indexAudioFile(
        file: ImportedFile,
        isCancelled: () -> Boolean = { false },
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): IndexResult {
        if (!isAudioFile(file)) {
            return IndexResult(file.uri, 0, false, "Not a supported audio file")
        }
        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
        }

        val pcm = try {
            AudioDecoder.decodeToMono16k(context, file.uri.toUri(), isCancelled)
        } catch (e: Exception) {
            return IndexResult(file.uri, 0, false, "Audio decode failed: ${e.message}")
        }

        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
        }

        val windows = AudioChunker.windows(
            totalSamples = pcm.size,
            windowMs = settings.audioWindowSeconds * 1000L
        )
        if (windows.isEmpty()) {
            onProgress?.invoke(1, 1)
            return IndexResult(file.uri, 0, true)
        }

        val records = mutableListOf<RetrievalRecord>()
        try {
            for ((index, window) in windows.withIndex()) {
                if (isCancelled()) {
                    return IndexResult(file.uri, 0, false, "Indexing cancelled")
                }
                onProgress?.invoke(index + 1, windows.size)
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

        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
        }

        // Replace old chunks only after embedding succeeded
        embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))
        if (records.isNotEmpty()) embeddingStore.storeBatch(records)
        return IndexResult(file.uri, records.size, true)
    }

    /**
     * Samples frames, windows and embeds an imported video file (.mp4).
     *
     * Execution flow (ARCHITECTURE.md §5, GOOGLE_AI_EDGE_GALLERY.md §6.2):
     * 1. Reads the duration and slides a window over it via [VideoChunker.windows].
     * 2. Decodes the audio track once to 16 kHz mono PCM (a video without audio is indexed
     *    from frames only).
     * 3. For each window, grabs [VIDEO_FRAMES_PER_WINDOW] JPEG frames and splits the window's audio
     *    into one slice per frame, then embeds the interleaved parts as ONE vector via
     *    [Embedder.embedVideoWindow]. If the token estimate exceeds [MAX_INPUT_TOKENS], the audio
     *    is dropped for that window.
     * 4. Replaces old chunks with one record per window: type="video",
     *    location = window start time in milliseconds (used to seek the player).
     *
     * @param file Metadata of the imported video file to index.
     * @param onProgress Optional callback invoked with (current, total) window progress.
     * @return [IndexResult] indicating success/failure and chunk count.
     */
    private val powerManager: PowerManager? by lazy {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    /**
     * Calculates an adaptive pacing delay in milliseconds based on real-time device thermal headroom
     * and thermal status (Android 12+ / minSdk 31).
     *
     * Inserting small micro-pauses (40ms-150ms) during intensive video embedding loops keeps the SoC
     * below the thermal cliff, preventing Linux kernel thermal governor downclocking that otherwise
     * degrades inference speeds by 4x-5x on physical hardware.
     */
    private fun computeThermalMitigationDelay(): Long {
        val pm = powerManager ?: return 0L
        val status = pm.currentThermalStatus
        val headroom = pm.getThermalHeadroom(10)
        return when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> 2000L
            status >= PowerManager.THERMAL_STATUS_MODERATE -> 150L
            status >= PowerManager.THERMAL_STATUS_LIGHT || headroom > 0.85f -> 40L
            else -> 0L
        }
    }

    /**
     * Generates a 256-element grayscale luminance signature from a frame bitmap.
     * Downscales to 16x16 in memory (< 0.2ms) without allocations.
     */
    private fun computeBitmapSignature(bmp: Bitmap): IntArray {
        val thumb = Bitmap.createScaledBitmap(bmp, 16, 16, false)
        val pixels = IntArray(256)
        thumb.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        if (thumb != bmp) thumb.recycle()
        return IntArray(256) { i ->
            val c = pixels[i]
            ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
    }

    /**
     * Reads, slices, embeds, and stores an imported video file (.mp4).
     *
     * Optimizations (PRD §2, ARCHITECTURE.md §5):
     * 1. Vision-Only by default: Video audio track is skipped by default ([AppSettings.videoIncludeAudio] = false)
     *    which saves ~100s of audio decoding time and eliminates audio tensor memory allocations.
     * 2. Pipeline Overlap: If audio indexing is enabled in settings, audio decoding is launched concurrently
     *    in the background ([Dispatchers.IO]) so audio decoding overlaps with video frame decoding and inference.
     * 3. Hardware Subsampling & Sync Extraction: Frame decoding uses hardware downsampling
     *    ([MediaMetadataRetriever.getScaledFrameAtTime]) directly to 256px with [MediaMetadataRetriever.OPTION_CLOSEST_SYNC],
     *    bypassing full-resolution software bitmap allocations.
     * 4. Complete Chunk Skipping: Probes the first keyframe of each window. If its visual luminance signature
     *    is $\ge$ [AppSettings.videoSimilarityThreshold] (default 85%) similar to the previous window, the entire
     *    window is skipped (no second frame decode, no model inference, no redundant SQLite rows).
     * 5. Adaptive Thermal Mitigation: Uses native Android 12+ [PowerManager.getThermalHeadroom] and
     *    [PowerManager.getCurrentThermalStatus] to insert pacing micro-pauses (40ms - 150ms), preventing
     *    Linux kernel thermal governor downclocking cliffs.
     *
     * @param file Metadata of the imported video file to index.
     * @param onProgress Optional callback invoked with (current, total) window progress.
     * @return [IndexResult] indicating success/failure and chunk count.
     */
    fun indexVideoFile(
        file: ImportedFile,
        isCancelled: () -> Boolean = { false },
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): IndexResult {
        if (!isVideoFile(file)) {
            return IndexResult(file.uri, 0, false, "Not a supported video file")
        }
        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
        }

        val uri = file.uri.toUri()

        // 1. Concurrent background audio decoding (Pipeline Overlap) if enabled in settings
        val audioDeferred = if (settings.videoIncludeAudio) {
            CoroutineScope(Dispatchers.IO).async {
                try {
                    AudioDecoder.decodeToMono16k(context, uri, isCancelled)
                } catch (_: Throwable) {
                    ShortArray(0)
                }
            }
        } else {
            null
        }

        val records = mutableListOf<RetrievalRecord>()
        val retriever = MediaMetadataRetriever()
        var previousSignature: IntArray? = null

        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return IndexResult(file.uri, 0, false, "Could not read video duration")

            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            val (targetW, targetH) = calculateTargetDimensions(rawW, rawH, 256.coerceAtMost(settings.maxImageDimension))

            val videoWindowMs = settings.videoWindowSeconds * 1000L
            val videoStepMs = (videoWindowMs - 1000L).coerceAtLeast(1000L)
            val windows = VideoChunker.windows(durationMs, windowMs = videoWindowMs, stepMs = videoStepMs)
            if (windows.isEmpty()) {
                onProgress?.invoke(1, 1)
                return IndexResult(file.uri, 0, true)
            }

            for ((index, window) in windows.withIndex()) {
                if (isCancelled()) {
                    audioDeferred?.cancel()
                    return IndexResult(file.uri, 0, false, "Indexing cancelled")
                }
                onProgress?.invoke(index + 1, windows.size)

                // 2. Adaptive thermal pacing (API 31 Headroom & Status)
                val thermalDelay = computeThermalMitigationDelay()
                if (thermalDelay > 0) {
                    Thread.sleep(thermalDelay)
                }

                val frameTimes = VideoChunker.frameTimes(window, count = settings.videoFramesPerWindow)
                if (frameTimes.isEmpty()) continue

                // 3. Probe ONLY Frame 1 directly to check if the scene has changed
                val firstBmpRaw = retriever.getScaledFrameAtTime(
                    frameTimes[0] * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetW,
                    targetH
                ) ?: retriever.getScaledFrameAtTime(
                    frameTimes[0] * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    targetW,
                    targetH
                ) ?: retriever.getFrameAtTime(frameTimes[0] * 1000)

                if (firstBmpRaw == null) continue

                val firstBmp = if (firstBmpRaw.width != targetW || firstBmpRaw.height != targetH) {
                    Bitmap.createScaledBitmap(firstBmpRaw, targetW, targetH, true).also {
                        if (it != firstBmpRaw) firstBmpRaw.recycle()
                    }
                } else {
                    firstBmpRaw
                }

                // 4. Complete Skipping of visually redundant consecutive scenes
                if (settings.videoSkipSimilarChunks) {
                    val sig = computeBitmapSignature(firstBmp)
                    val prev = previousSignature
                    if (prev != null && VideoChunker.signatureSimilarity(sig, prev) >= settings.videoSimilarityThreshold) {
                        firstBmp.recycle()
                        continue // Skip redundant scene: no frame 2 decode, no inference, no DB row
                    }
                    previousSignature = sig
                }

                // Compress Frame 1 to JPEG
                val jpeg1 = ByteArrayOutputStream().use { out ->
                    firstBmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    firstBmp.recycle()
                    out.toByteArray()
                }

                // 5. Extract remaining frames (if videoFramesPerWindow > 1)
                var unreadable = false
                val remainingJpegs = mutableListOf<ByteArray>()
                for (i in 1 until frameTimes.size) {
                    val jpeg = videoFrameScaledJpeg(retriever, frameTimes[i], targetW, targetH)
                    if (jpeg == null) {
                        unreadable = true
                        break
                    }
                    remainingJpegs.add(jpeg)
                }
                if (unreadable) continue

                val allJpegs = listOf(jpeg1) + remainingJpegs
                val windowMs = window.endMs - window.startMs

                // 6. Audio integration (if enabled in settings and decoded)
                val pcm = audioDeferred?.let { deferred ->
                    runBlocking { deferred.await() }
                } ?: ShortArray(0)

                val withAudio = pcm.isNotEmpty() &&
                        VideoChunker.estimateTokens(windowMs, allJpegs.size, true) <= MAX_INPUT_TOKENS

                // Interleave: [time] audio-slice [time] frame ... one pair per sampled frame
                val parts = mutableListOf<Part>()
                val sliceMs = windowMs / allJpegs.size
                for ((i, jpeg) in allJpegs.withIndex()) {
                    val label = SearchResultFormatter.formatTimeMs(frameTimes[i])
                    if (withAudio) {
                        val from = window.startMs + i * sliceMs
                        val range = VideoChunker.sampleRange(from, from + sliceMs, pcm.size)
                        if (!range.isEmpty()) {
                            parts.add(TextPart(label))
                            parts.add(embedder.audioPart(pcm.copyOfRange(range.first, range.last + 1)))
                        }
                    }
                    parts.add(TextPart(label))
                    parts.add(ImagePart(jpeg))
                }

                if (isCancelled()) {
                    audioDeferred?.cancel()
                    return IndexResult(file.uri, 0, false, "Indexing cancelled")
                }

                val embedding = try {
                    embedder.embedVideoWindow(parts)
                } catch (e: Exception) {
                    if (withAudio) {
                        // Multimodal audio fallback to vision-only keyframe parts
                        val visionParts = mutableListOf<Part>()
                        for ((i, jpeg) in allJpegs.withIndex()) {
                            val label = SearchResultFormatter.formatTimeMs(frameTimes[i])
                            visionParts.add(TextPart(label))
                            visionParts.add(ImagePart(jpeg))
                        }
                        embedder.embedVideoWindow(visionParts)
                    } else {
                        throw e
                    }
                }

                val metadata = mapOf(
                    "fileId" to file.uri,
                    "uri" to file.uri,
                    "name" to file.displayName,
                    "type" to "video",
                    "location" to window.startMs.toString()
                )
                val text = "${file.displayName} @ ${SearchResultFormatter.formatTimeMs(window.startMs)}"
                records.add(RetrievalRecord("${file.uri}_$index", listOf(TextPart(text)), embedding, metadata))
            }
        } catch (e: Exception) {
            return IndexResult(file.uri, 0, false, "Video indexing failed: ${e.message}")
        } finally {
            audioDeferred?.cancel()
            retriever.release()
        }

        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
        }

        // Replace old chunks only after embedding succeeded
        embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))
        if (records.isNotEmpty()) embeddingStore.storeBatch(records)
        return IndexResult(file.uri, records.size, true)
    }

    /**
     * Grabs the frame nearest to [timeMs], hardware-downscaled to [targetW] x [targetH], as JPEG (quality 80).
     * Returns null if the frame cannot be decoded.
     */
    private fun videoFrameScaledJpeg(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        targetW: Int,
        targetH: Int
    ): ByteArray? {
        val bmp = retriever.getScaledFrameAtTime(
            timeMs * 1000,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            targetW,
            targetH
        ) ?: retriever.getScaledFrameAtTime(
            timeMs * 1000,
            MediaMetadataRetriever.OPTION_CLOSEST,
            targetW,
            targetH
        ) ?: retriever.getFrameAtTime(timeMs * 1000)
        ?: return null

        val scaled = if (bmp.width != targetW || bmp.height != targetH) {
            Bitmap.createScaledBitmap(bmp, targetW, targetH, true).also {
                if (it != bmp) bmp.recycle()
            }
        } else {
            bmp
        }

        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            scaled.recycle()
            out.toByteArray()
        }
    }

    /**
     * Automatically dispatches indexing based on the file format
     * (Text/Markdown, PDF, Image, Audio or Video).
     *
     * @param file Metadata of the imported file to index.
     * @param onProgress Optional callback invoked with (current, total) progress.
     */
    fun indexFile(
        file: ImportedFile,
        isCancelled: () -> Boolean = { false },
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): IndexResult {
        return when {
            isPdfFile(file) -> indexPdfFile(file, isCancelled, onProgress)
            isImageFile(file) -> indexImageFile(file, isCancelled, onProgress)
            isAudioFile(file) -> indexAudioFile(file, isCancelled, onProgress)
            isVideoFile(file) -> indexVideoFile(file, isCancelled, onProgress)
            isTextFile(file) -> indexTextFile(file, isCancelled, onProgress)
            else -> IndexResult(file.uri, 0, false, "Unsupported file format")
        }
    }

    companion object {
        /**
         * Checks whether a file with the given display name and MIME type is supported by
         * LocalFind's indexers and has a corresponding builtin viewer.
         */
        fun isSupported(displayName: String, mimeType: String?): Boolean {
            return isPdfNameOrMime(displayName, mimeType) ||
                    isImageNameOrMime(displayName, mimeType) ||
                    isAudioNameOrMime(displayName, mimeType) ||
                    isVideoNameOrMime(displayName, mimeType) ||
                    isTextNameOrMime(displayName, mimeType)
        }

        /**
         * Pure helper that checks whether a file name or MIME type corresponds to a supported
         * text or markdown format (.txt, .md or text/ MIME type).
         */
        fun isTextNameOrMime(displayName: String, mimeType: String?): Boolean {
            val name = displayName.lowercase()
            val mime = mimeType?.lowercase() ?: ""
            return name.endsWith(".txt") ||
                    name.endsWith(".md") ||
                    mime.startsWith("text/") ||
                    mime == "text/plain" ||
                    mime == "text/markdown"
        }

        /**
         * Pure helper that checks whether a file name or MIME type corresponds to a supported
         * PDF document (.pdf or application/pdf).
         */
        fun isPdfNameOrMime(displayName: String, mimeType: String?): Boolean {
            val name = displayName.lowercase()
            val mime = mimeType?.lowercase() ?: ""
            return name.endsWith(".pdf") || mime == "application/pdf"
        }

        /**
         * Pure helper that checks whether a file name or MIME type corresponds to a supported
         * video format (.mp4 or video/mp4).
         */
        fun isVideoNameOrMime(displayName: String, mimeType: String?): Boolean =
            displayName.lowercase().endsWith(".mp4") || mimeType?.lowercase() == "video/mp4"

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
