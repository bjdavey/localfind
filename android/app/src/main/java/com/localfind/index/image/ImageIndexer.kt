package com.localfind.index.image

import com.localfind.core.model.FileKind
import com.localfind.core.model.ChunkMetadata
import com.localfind.core.indexing.ImageSizing
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.net.toUri
import com.google.mediapipe.tasks.core.ImagePart
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import com.localfind.core.settings.DEFAULT_MAX_IMAGE_DIMENSION
import com.localfind.core.model.ImportedFile
import com.localfind.core.model.INDEXING_CANCELLED_MESSAGE
import com.localfind.core.model.IndexResult
import com.localfind.index.Indexer
import com.localfind.index.IndexingResumeManager

/**
 * Image (`.jpg`, `.jpeg`, `.png`) indexing pipeline and hardware-subsampled bitmap decoding utilities.
 */
object ImageIndexer {

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
        maxDimension: Int = DEFAULT_MAX_IMAGE_DIMENSION
    ): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (targetW, targetH) = ImageSizing.calculateTargetDimensions(
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

/**
 * Decodes, normalizes, embeds, and stores an imported image file (.jpg, .png, .gif).
 *
 * Execution flow (ARCHITECTURE.md §5 & GOOGLE_AI_EDGE_GALLERY.md §6.3):
 * 1. Validates that the file is a supported image format (.jpg, .jpeg, .png, .gif).
 * 2. Decodes the image from the SAF URI into a software-backed ARGB_8888 [Bitmap],
 *    applying EXIF orientation corrections and bounding dimensions to [DEFAULT_MAX_IMAGE_DIMENSION]
 *    to keep memory usage low.
 * 3. Computes a 768-dimensional normalized vector for the whole image via [com.localfind.embed.Embedder.embedImage].
 * 4. Deletes any pre-existing chunks for this file from [com.localfind.data.EmbeddingStore].
 * 5. Stores a single [RetrievalRecord] with `type="image"` and `location="0"`.
 */
fun Indexer.indexImageFile(
    file: ImportedFile,
    isCancelled: () -> Boolean,
    onProgress: ((current: Int, total: Int) -> Unit)?
): IndexResult {
    if (!isImageFile(file)) {
        return IndexResult(0, false, "Not a supported image file")
    }
    if (isCancelled()) {
        return IndexResult(0, false, INDEXING_CANCELLED_MESSAGE)
    }

    val uri = file.uri.toUri()
    val bitmap = try {
        ImageIndexer.decodeNormalizedBitmap(context, uri, maxDimension = settings.maxImageDimension)
    } catch (e: Exception) {
        return IndexResult(0, false, "Image decode failed: ${e.message}")
    }

    if (isCancelled()) {
        bitmap.recycle()
        return IndexResult(0, false, INDEXING_CANCELLED_MESSAGE)
    }

    onProgress?.invoke(1, 1)
    val embedding = try {
        getActiveEmbedder().embedImage(bitmap)
    } catch (e: Exception) {
        return IndexResult(0, false, "Image embedding failed: ${e.message}")
    } finally {
        bitmap.recycle()
    }

    if (isCancelled()) {
        return IndexResult(0, false, INDEXING_CANCELLED_MESSAGE)
    }

    // Delete existing chunks for this file before storing the updated embedding
    embeddingStore.deleteByFileUri(file.uri)

    val record = RetrievalRecord(
        ChunkMetadata.chunkId(file.uri, 0),
        listOf(ImagePart(uri)),
        embedding,
        ChunkMetadata.build(file, kind = FileKind.IMAGE, location = "0")
    )

    embeddingStore.storeBatch(listOf(record))
    IndexingResumeManager.clearResumeChunk(context, file.uri)
    return IndexResult(1, true)
}
