package com.localfind.index

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.PowerManager
import com.google.mediapipe.tasks.core.TextPart
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import com.localfind.data.AppSettings
import com.localfind.data.DEFAULT_MAX_IMAGE_DIMENSION
import com.localfind.data.EmbeddingStore
import com.localfind.data.EngineManager
import com.localfind.data.HardwareAccelerator
import com.localfind.data.ImportedFile
import com.localfind.embed.Embedder
import com.localfind.index.audio.indexAudioFile
import com.localfind.index.document.indexPdfFile
import com.localfind.index.document.indexTextFile
import com.localfind.index.image.ImageIndexer
import com.localfind.index.image.indexImageFile
import com.localfind.index.video.indexVideoFile

/**
 * Maximum width or height (in pixels) used when decoding an image for embedding.
 *
 * Aliases [DEFAULT_MAX_IMAGE_DIMENSION] (512px) to keep intermediate pixel buffers small (~1 MB)
 * and speed up ImageDecoder subsampling before the vision encoder resizes to its patch grid.
 */
const val MAX_IMAGE_DIMENSION = DEFAULT_MAX_IMAGE_DIMENSION

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
 * Intermediate payload produced for a single chunk inside [Indexer.indexChunkedItems].
 */
internal class ChunkEmbeddingData(
    val snippetText: String,
    val location: String,
    val embedding: FloatArray
)

/**
 * Orchestrates extracting chunks from imported files, embedding them with [Embedder],
 * and persisting the resulting vectors and metadata in [EmbeddingStore].
 *
 * Modality-specific pipelines are organized into dedicated subpackages:
 * - [indexTextFile] & [indexPdfFile] in `index/document/`
 * - [indexImageFile] & [ImageIndexer] in `index/image/`
 * - [indexAudioFile] in `index/audio/`
 * - [indexVideoFile] in `index/video/`
 *
 * Architecture and Design:
 * - Direct Content Access: Reads file content on-the-fly using the persistent SAF URI
 *   (ContentResolver.openInputStream) without copying the file into app storage.
 * - Idempotency & Granular Resume: Uses [IndexingResumeManager] to resume interrupted multi-chunk
 *   files from the last committed batch, or deletes stale chunks on fresh starts.
 * - Chunk Metadata Format:
 *     - `fileId`: The file's URI string (used for deleting all chunks of a file).
 *     - `uri`: The file's URI string (used to open/render the document).
 *     - `name`: Display name shown in search result cards.
 *     - `type`: Category (`"text"`, `"pdf"`, `"image"`, `"audio"`, `"video"`).
 *     - `location`: Offset location (character offset for text, 1-based page number for PDF,
 *       0 for whole-image records, start timestamp in ms for audio/video).
 */
class Indexer(
    internal val context: Context,
    internal val embedder: Embedder,
    internal val embeddingStore: EmbeddingStore,
    internal val settings: AppSettings = AppSettings()
) {
    internal val powerManager: PowerManager? by lazy {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    /**
     * Resolves the active [Embedder] instance. In [HardwareAccelerator.SMART] mode, queries
     * [EngineManager] so changes in device interactivity (screen on/off) or battery saver
     * dynamically adapt the active hardware delegate during long multi-chunk operations.
     */
    fun getActiveEmbedder(): Embedder {
        if (settings.accelerator == HardwareAccelerator.SMART) {
            return EngineManager.getEmbedder(context, settings.accelerator)
        }
        return embedder
    }

    /** Determines whether an imported file is supported by the text/markdown indexer (.txt, .md). */
    fun isTextFile(file: ImportedFile): Boolean =
        isTextNameOrMime(file.displayName, file.mimeType)

    /** Determines whether an imported file is a PDF document (.pdf). */
    fun isPdfFile(file: ImportedFile): Boolean =
        isPdfNameOrMime(file.displayName, file.mimeType)

    /** Determines whether an imported file is a supported image file (.jpg, .jpeg, .png, .gif). */
    fun isImageFile(file: ImportedFile): Boolean =
        isImageNameOrMime(file.displayName, file.mimeType)

    /** Determines whether an imported file is a GIF image (.gif). */
    fun isGifFile(file: ImportedFile): Boolean =
        isGifNameOrMime(file.displayName, file.mimeType)

    /** Determines whether an imported file is a supported audio file (.mp3, .m4a, .wav). */
    fun isAudioFile(file: ImportedFile): Boolean =
        isAudioNameOrMime(file.displayName, file.mimeType)

    /** Determines whether an imported file is a supported video file (.mp4). */
    fun isVideoFile(file: ImportedFile): Boolean =
        isVideoNameOrMime(file.displayName, file.mimeType)

    /** Determines whether an imported file is supported by the indexer and has a builtin viewer. */
    fun isSupported(file: ImportedFile): Boolean =
        isSupported(file.displayName, file.mimeType)

    /**
     * Automatically dispatches indexing based on the file format
     * (Text/Markdown, PDF, Image, Audio, or Video).
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

    /**
     * Builds the canonical metadata map stored alongside each chunk vector in SQLite.
     */
    internal fun buildChunkMetadata(
        file: ImportedFile,
        type: String,
        location: String
    ): Map<String, String> = mapOf(
        "fileId" to file.uri,
        "uri" to file.uri,
        "name" to file.displayName,
        "type" to type,
        "location" to location
    )

    /**
     * Shared resumable batch-indexing loop used by text, PDF, and audio indexers.
     *
     * Handles:
     * 1. Empty item list cleanup and early return.
     * 2. Resume checkpoint lookup ([IndexingResumeManager.getResumeChunk]) and stale chunk deletion on fresh starts.
     * 3. Cooperative cancellation before each chunk and after the loop, flushing partial batches safely.
     * 4. Periodic batch commits every [batchSize] records to bound memory and persist progress.
     */
    internal inline fun <T> indexChunkedItems(
        file: ImportedFile,
        type: String,
        items: List<T>,
        batchSize: Int,
        deleteOnEmpty: Boolean = true,
        errorPrefix: String? = null,
        isCancelled: () -> Boolean,
        noinline onProgress: ((current: Int, total: Int) -> Unit)?,
        embedItem: (item: T) -> ChunkEmbeddingData
    ): IndexResult {
        if (isCancelled()) {
            return IndexResult(file.uri, 0, false, "Indexing cancelled")
        }

        if (items.isEmpty()) {
            if (deleteOnEmpty) {
                embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))
            }
            IndexingResumeManager.clearResumeChunk(context, file.uri)
            onProgress?.invoke(1, 1)
            return IndexResult(file.uri, 0, true)
        }

        val startChunk = IndexingResumeManager.getResumeChunk(context, file.uri, file.size, items.size)
        if (IndexingResumeLogic.shouldDeleteExistingChunks(startChunk)) {
            embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))
        } else {
            onProgress?.invoke(startChunk, items.size)
        }

        val records = mutableListOf<RetrievalRecord>()
        try {
            for (index in startChunk until items.size) {
                if (isCancelled()) {
                    if (records.isNotEmpty()) {
                        embeddingStore.storeBatch(records)
                        records.clear()
                    }
                    IndexingResumeManager.setResumeChunk(context, file.uri, index, file.size)
                    return IndexResult(file.uri, index, false, "Indexing cancelled")
                }

                onProgress?.invoke(index + 1, items.size)
                val data = embedItem(items[index])
                val metadata = buildChunkMetadata(file, type = type, location = data.location)
                records.add(
                    RetrievalRecord(
                        "${file.uri}_$index",
                        listOf(TextPart(data.snippetText)),
                        data.embedding,
                        metadata
                    )
                )

                if (records.size >= batchSize) {
                    embeddingStore.storeBatch(records)
                    records.clear()
                    IndexingResumeManager.setResumeChunk(context, file.uri, index + 1, file.size)
                }
            }
        } catch (e: Exception) {
            // Discard uncommitted in-memory batch on error to prevent duplicate insertions when retrying
            records.clear()
            if (errorPrefix != null) {
                return IndexResult(file.uri, 0, false, "$errorPrefix: ${e.message}")
            }
            throw e
        }

        if (records.isNotEmpty()) {
            embeddingStore.storeBatch(records)
            records.clear()
            IndexingResumeManager.setResumeChunk(context, file.uri, items.size, file.size)
        }

        if (isCancelled()) {
            return IndexResult(file.uri, items.size, false, "Indexing cancelled")
        }

        IndexingResumeManager.clearResumeChunk(context, file.uri)
        return IndexResult(file.uri, items.size, true)
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

        /** Checks whether a file name or MIME type corresponds to a supported text or markdown format. */
        fun isTextNameOrMime(displayName: String, mimeType: String?): Boolean {
            val name = displayName.lowercase()
            val mime = mimeType?.lowercase() ?: ""
            return name.endsWith(".txt") ||
                name.endsWith(".md") ||
                mime.startsWith("text/") ||
                mime == "text/plain" ||
                mime == "text/markdown"
        }

        /** Checks whether a file name or MIME type corresponds to a supported PDF document. */
        fun isPdfNameOrMime(displayName: String, mimeType: String?): Boolean {
            val name = displayName.lowercase()
            val mime = mimeType?.lowercase() ?: ""
            return name.endsWith(".pdf") || mime == "application/pdf"
        }

        /** Checks whether a file name or MIME type corresponds to a supported video format (.mp4). */
        fun isVideoNameOrMime(displayName: String, mimeType: String?): Boolean =
            displayName.lowercase().endsWith(".mp4") || mimeType?.lowercase() == "video/mp4"

        /** Checks whether a file name or MIME type corresponds to a supported audio format. */
        fun isAudioNameOrMime(displayName: String, mimeType: String?): Boolean {
            val name = displayName.lowercase()
            return name.endsWith(".mp3") ||
                name.endsWith(".m4a") ||
                name.endsWith(".wav") ||
                mimeType?.lowercase()?.startsWith("audio/") == true
        }

        /** Checks whether a file name or MIME type corresponds to a supported image format (.jpg, .jpeg, .png, .gif). */
        fun isImageNameOrMime(displayName: String, mimeType: String?): Boolean {
            val name = displayName.lowercase()
            val mime = mimeType?.lowercase() ?: ""
            return name.endsWith(".jpg") ||
                name.endsWith(".jpeg") ||
                name.endsWith(".png") ||
                name.endsWith(".gif") ||
                mime == "image/jpeg" ||
                mime == "image/jpg" ||
                mime == "image/png" ||
                mime == "image/gif"
        }

        /** Checks whether a file name or MIME type specifically corresponds to a GIF image (.gif, image/gif). */
        fun isGifNameOrMime(displayName: String, mimeType: String?): Boolean {
            val name = displayName.lowercase()
            val mime = mimeType?.lowercase() ?: ""
            return name.endsWith(".gif") || mime == "image/gif"
        }

        /** Delegates to [ImageIndexer.calculateTargetDimensions]. */
        fun calculateTargetDimensions(
            width: Int,
            height: Int,
            maxDimension: Int = MAX_IMAGE_DIMENSION
        ): Pair<Int, Int> = ImageIndexer.calculateTargetDimensions(width, height, maxDimension)

        /** Delegates to [ImageIndexer.decodeNormalizedBitmap]. */
        fun decodeNormalizedBitmap(
            context: Context,
            uri: Uri,
            maxDimension: Int = MAX_IMAGE_DIMENSION
        ): Bitmap = ImageIndexer.decodeNormalizedBitmap(context, uri, maxDimension)
    }
}
