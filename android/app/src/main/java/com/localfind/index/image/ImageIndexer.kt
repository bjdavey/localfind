package com.localfind.index.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.net.toUri
import com.google.mediapipe.tasks.core.ImagePart
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import com.localfind.data.ImportedFile
import com.localfind.index.IndexResult
import com.localfind.index.Indexer
import com.localfind.index.IndexingResumeManager
import com.localfind.index.MAX_IMAGE_DIMENSION

/**
 * Image (`.jpg`, `.jpeg`, `.png`) indexing pipeline and hardware-subsampled bitmap decoding utilities.
 */
object ImageIndexer {

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

/**
 * Decodes, normalizes, embeds, and stores an imported image file (.jpg, .png).
 *
 * Execution flow (ARCHITECTURE.md §5 & GOOGLE_AI_EDGE_GALLERY.md §6.3):
 * 1. Validates that the file is a supported image format (.jpg, .jpeg, .png).
 * 2. Decodes the image from the SAF URI into a software-backed ARGB_8888 [Bitmap],
 *    applying EXIF orientation corrections and bounding dimensions to [MAX_IMAGE_DIMENSION]
 *    to keep memory usage low.
 * 3. Computes a 768-dimensional normalized vector for the whole image via [com.localfind.embed.Embedder.embedImage].
 * 4. Deletes any pre-existing chunks for this file from [com.localfind.data.EmbeddingStore].
 * 5. Stores a single [RetrievalRecord] with `type="image"` and `location="0"`.
 */
fun Indexer.indexImageFile(
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
        ImageIndexer.decodeNormalizedBitmap(context, uri, maxDimension = settings.maxImageDimension)
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

    val record = RetrievalRecord(
        "${file.uri}_0",
        listOf(ImagePart(uri)),
        embedding,
        buildChunkMetadata(file, type = "image", location = "0")
    )

    embeddingStore.storeBatch(listOf(record))
    IndexingResumeManager.clearResumeChunk(context, file.uri)
    return IndexResult(file.uri, 1, true)
}
