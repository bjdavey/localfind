package com.localfind.index

import com.localfind.core.model.FileKind
import com.localfind.core.model.ChunkMetadata
import com.localfind.core.model.FileTypes
import com.localfind.core.indexing.IndexingResumeLogic
import com.localfind.core.model.INDEXING_CANCELLED_MESSAGE
import com.localfind.core.model.IndexResult
import android.content.Context
import android.os.PowerManager
import com.google.mediapipe.tasks.core.TextPart
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import com.localfind.core.settings.AppSettings
import com.localfind.data.EmbeddingStore
import com.localfind.data.EngineManager
import com.localfind.core.settings.HardwareAccelerator
import com.localfind.core.model.ImportedFile
import com.localfind.embed.EmbeddingModel
import com.localfind.index.audio.indexAudioFile
import com.localfind.index.document.indexPdfFile
import com.localfind.index.document.indexTextFile
import com.localfind.index.image.indexImageFile
import com.localfind.index.video.indexVideoFile

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
 * - [indexImageFile] & `ImageIndexer` in `index/image/`
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
    internal val embedder: EmbeddingModel,
    internal val embeddingStore: EmbeddingStore,
    internal val settings: AppSettings = AppSettings()
) {
    internal val powerManager: PowerManager? by lazy {
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    /**
     * Resolves the active [EmbeddingModel]. In [HardwareAccelerator.SMART] mode, queries
     * [EngineManager] so changes in device interactivity (screen on/off) or battery saver
     * dynamically adapt the active hardware delegate during long multi-chunk operations.
     */
    fun getActiveEmbedder(): EmbeddingModel {
        if (settings.accelerator == HardwareAccelerator.SMART) {
            return EngineManager.getEmbedder(context, settings.accelerator)
        }
        return embedder
    }

    /** Determines whether an imported file is supported by the text/markdown indexer (.txt, .md). */
    fun isTextFile(file: ImportedFile): Boolean =
        FileTypes.isTextNameOrMime(file.displayName, file.mimeType)

    /** Determines whether an imported file is a PDF document (.pdf). */
    fun isPdfFile(file: ImportedFile): Boolean =
        FileTypes.isPdfNameOrMime(file.displayName, file.mimeType)

    /** Determines whether an imported file is a supported image file (.jpg, .jpeg, .png, .gif). */
    fun isImageFile(file: ImportedFile): Boolean =
        FileTypes.isImageNameOrMime(file.displayName, file.mimeType)

    /** Determines whether an imported file is a supported audio file (.mp3, .m4a, .wav). */
    fun isAudioFile(file: ImportedFile): Boolean =
        FileTypes.isAudioNameOrMime(file.displayName, file.mimeType)

    /** Determines whether an imported file is a supported video file (.mp4, .mkv, .webm). */
    fun isVideoFile(file: ImportedFile): Boolean =
        FileTypes.isVideoNameOrMime(file.displayName, file.mimeType)

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
            else -> IndexResult(0, false, "Unsupported file format")
        }
    }

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
        kind: FileKind,
        items: List<T>,
        batchSize: Int,
        deleteOnEmpty: Boolean,
        errorPrefix: String? = null,
        isCancelled: () -> Boolean,
        noinline onProgress: ((current: Int, total: Int) -> Unit)?,
        embedItem: (item: T) -> ChunkEmbeddingData
    ): IndexResult {
        if (isCancelled()) {
            return IndexResult(0, false, INDEXING_CANCELLED_MESSAGE)
        }

        if (items.isEmpty()) {
            if (deleteOnEmpty) {
                embeddingStore.deleteByFileUri(file.uri)
            }
            IndexingResumeManager.clearResumeChunk(context, file.uri)
            onProgress?.invoke(1, 1)
            return IndexResult(0, true)
        }

        val startChunk = IndexingResumeManager.getResumeChunk(context, file.uri, file.size, items.size)
        if (IndexingResumeLogic.shouldDeleteExistingChunks(startChunk)) {
            embeddingStore.deleteByFileUri(file.uri)
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
                    return IndexResult(index, false, INDEXING_CANCELLED_MESSAGE)
                }

                onProgress?.invoke(index + 1, items.size)
                val data = embedItem(items[index])
                val metadata = ChunkMetadata.build(file, kind = kind, location = data.location)
                records.add(
                    RetrievalRecord(
                        ChunkMetadata.chunkId(file.uri, index),
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
                return IndexResult(0, false, "$errorPrefix: ${e.message}")
            }
            throw e
        }

        if (records.isNotEmpty()) {
            embeddingStore.storeBatch(records)
            records.clear()
            IndexingResumeManager.setResumeChunk(context, file.uri, items.size, file.size)
        }

        if (isCancelled()) {
            return IndexResult(items.size, false, INDEXING_CANCELLED_MESSAGE)
        }

        IndexingResumeManager.clearResumeChunk(context, file.uri)
        return IndexResult(items.size, true)
    }
}
