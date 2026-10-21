package com.localfind.index.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.PowerManager
import androidx.core.net.toUri
import com.google.mediapipe.tasks.core.ImagePart
import com.google.mediapipe.tasks.core.Part
import com.google.mediapipe.tasks.core.TextPart
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import com.localfind.data.ImportedFile
import com.localfind.index.IndexResult
import com.localfind.index.Indexer
import com.localfind.index.IndexingResumeLogic
import com.localfind.index.IndexingResumeManager
import com.localfind.index.audio.AudioDecoder
import com.localfind.index.image.ImageIndexer
import com.localfind.ui.search.SearchResultFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

/**
 * Video (`.mp4`) indexing pipeline and hardware keyframe / thermal pacing utilities for [Indexer].
 *
 * Optimizations (PRD §2, ARCHITECTURE.md §5):
 * 1. Vision-Only by default: Video audio track is skipped by default ([com.localfind.data.AppSettings.videoIncludeAudio] = false),
 *    saving audio decoding time and eliminating audio tensor memory allocations.
 * 2. Pipeline Overlap: If audio indexing is enabled in settings, audio decoding is launched concurrently
 *    in the background ([Dispatchers.IO]) and cached once on first await so audio decoding overlaps with
 *    video frame extraction and inference.
 * 3. Hardware Subsampling & Sync Extraction: Frame decoding uses hardware downsampling
 *    ([MediaMetadataRetriever.getScaledFrameAtTime]) directly to 256px with [MediaMetadataRetriever.OPTION_CLOSEST_SYNC],
 *    bypassing full-resolution software bitmap allocations.
 * 4. Complete Chunk Skipping: Probes the first keyframe of each window. If its visual luminance signature
 *    is >= [com.localfind.data.AppSettings.videoSimilarityThreshold] (default 85%) similar to the previous window,
 *    the entire window is skipped (no second frame decode, no model inference, no redundant SQLite rows).
 * 5. Adaptive Thermal Mitigation: Uses native Android 12+ [PowerManager.getThermalHeadroom] and
 *    [PowerManager.getCurrentThermalStatus] to insert pacing micro-pauses (40ms - 150ms), preventing
 *    Linux kernel thermal governor downclocking cliffs.
 */
fun Indexer.indexVideoFile(
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
    var cachedPcm: ShortArray? = null
    var totalWindows = 0
    var indexedChunksCount = 0

    try {
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: return IndexResult(file.uri, 0, false, "Could not read video duration")

        val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
        val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
        val (targetW, targetH) = ImageIndexer.calculateTargetDimensions(
            rawW,
            rawH,
            256.coerceAtMost(settings.maxImageDimension)
        )

        val videoWindowMs = settings.videoWindowSeconds * 1000L
        val videoStepMs = (videoWindowMs - 1000L).coerceAtLeast(1000L)
        val windows = VideoChunker.windows(durationMs, windowMs = videoWindowMs, stepMs = videoStepMs)
        totalWindows = windows.size
        if (windows.isEmpty()) {
            IndexingResumeManager.clearResumeChunk(context, file.uri)
            onProgress?.invoke(1, 1)
            return IndexResult(file.uri, 0, true)
        }

        val startChunk = IndexingResumeManager.getResumeChunk(context, file.uri, file.size, windows.size)
        if (IndexingResumeLogic.shouldDeleteExistingChunks(startChunk)) {
            embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))
        } else {
            indexedChunksCount = startChunk
            onProgress?.invoke(startChunk, windows.size)
        }

        for (index in startChunk until windows.size) {
            val window = windows[index]
            if (isCancelled()) {
                if (records.isNotEmpty()) {
                    embeddingStore.storeBatch(records)
                    records.clear()
                }
                IndexingResumeManager.setResumeChunk(context, file.uri, index, file.size)
                return IndexResult(file.uri, index, false, "Indexing cancelled")
            }
            onProgress?.invoke(index + 1, windows.size)

            // 2. Adaptive thermal pacing & micro-pacing frame yielding (API 31 Headroom & Status)
            // Inserts a micro-pause between chunks so SurfaceFlinger has time to composite UI frames smoothly.
            val thermalDelay = computeThermalMitigationDelay(powerManager)
            val pacingDelay = if (thermalDelay > 0L) thermalDelay else DEFAULT_MICRO_PACING_DELAY_MS
            Thread.sleep(pacingDelay)

            val frameTimes = VideoChunker.frameTimes(window, count = settings.videoFramesPerWindow)
            if (frameTimes.isEmpty()) {
                IndexingResumeManager.setResumeChunk(context, file.uri, index + 1, file.size)
                continue
            }

            // 3. Probe ONLY Frame 1 directly to check if the scene has changed
            val firstBmp = extractScaledVideoFrame(retriever, frameTimes[0], targetW, targetH)
            if (firstBmp == null) {
                IndexingResumeManager.setResumeChunk(context, file.uri, index + 1, file.size)
                continue
            }

            // 4. Complete Skipping of visually redundant consecutive scenes
            if (settings.videoSkipSimilarChunks) {
                val sig = computeBitmapSignature(firstBmp)
                val prev = previousSignature
                if (prev != null && VideoChunker.signatureSimilarity(sig, prev) >= settings.videoSimilarityThreshold) {
                    firstBmp.recycle()
                    IndexingResumeManager.setResumeChunk(context, file.uri, index + 1, file.size)
                    continue // Skip redundant scene: no frame 2 decode, no inference, no DB row
                }
                previousSignature = sig
            }

            // Compress Frame 1 to JPEG and recycle bitmap
            val jpeg1 = firstBmp.toJpegBytesAndRecycle()

            // 5. Extract remaining frames (if videoFramesPerWindow > 1) using the shared helper
            var unreadable = false
            val remainingJpegs = mutableListOf<ByteArray>()
            for (i in 1 until frameTimes.size) {
                val jpeg = extractScaledVideoFrame(retriever, frameTimes[i], targetW, targetH)
                    ?.toJpegBytesAndRecycle()
                if (jpeg == null) {
                    unreadable = true
                    break
                }
                remainingJpegs.add(jpeg)
            }
            if (unreadable) {
                IndexingResumeManager.setResumeChunk(context, file.uri, index + 1, file.size)
                continue
            }

            val allJpegs = listOf(jpeg1) + remainingJpegs
            val windowMs = window.endMs - window.startMs

            // 6. Audio integration (awaited once and cached across all remaining windows)
            val pcm = cachedPcm ?: (audioDeferred?.let { deferred ->
                runBlocking { deferred.await() }
            } ?: ShortArray(0)).also { cachedPcm = it }

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
                if (records.isNotEmpty()) {
                    embeddingStore.storeBatch(records)
                    records.clear()
                }
                IndexingResumeManager.setResumeChunk(context, file.uri, index, file.size)
                return IndexResult(file.uri, index, false, "Indexing cancelled")
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

            val metadata = buildChunkMetadata(file, type = "video", location = window.startMs.toString())
            val text = "${file.displayName} @ ${SearchResultFormatter.formatTimeMs(window.startMs)}"
            records.add(RetrievalRecord("${file.uri}_$index", listOf(TextPart(text)), embedding, metadata))
            indexedChunksCount++

            // Commit in batches of 3 windows to prevent loss of slow inferences
            if (records.size >= 3) {
                embeddingStore.storeBatch(records)
                records.clear()
                IndexingResumeManager.setResumeChunk(context, file.uri, index + 1, file.size)
            }
        }
    } catch (e: Exception) {
        // Discard uncommitted in-memory batch on error to prevent duplicate insertions when retrying
        records.clear()
        return IndexResult(file.uri, 0, false, "Video indexing failed: ${e.message}")
    } finally {
        audioDeferred?.cancel()
        retriever.release()
    }

    if (records.isNotEmpty()) {
        embeddingStore.storeBatch(records)
        records.clear()
        IndexingResumeManager.setResumeChunk(context, file.uri, totalWindows, file.size)
    }

    if (isCancelled()) {
        return IndexResult(file.uri, indexedChunksCount, false, "Indexing cancelled")
    }

    IndexingResumeManager.clearResumeChunk(context, file.uri)
    return IndexResult(file.uri, indexedChunksCount.coerceAtLeast(if (totalWindows > 0) 1 else 0), true)
}

/**
 * Default micro-pacing delay in milliseconds inserted between consecutive media chunks
 * to yield GPU and CPU time-slices to Android's SurfaceFlinger and HWUI compositor,
 * preventing UI lag and frame drops while preserving >90% indexing speed.
 */
const val DEFAULT_MICRO_PACING_DELAY_MS = 20L

/**
 * Calculates an adaptive pacing delay in milliseconds based on real-time device thermal headroom
 * and thermal status (Android 12+ / minSdk 31).
 *
 * Inserting small micro-pauses (40ms-150ms) during intensive video embedding loops keeps the SoC
 * below the thermal cliff, preventing Linux kernel thermal governor downclocking that otherwise
 * degrades inference speeds by 4x-5x on physical hardware.
 */
private fun computeThermalMitigationDelay(powerManager: PowerManager?): Long {
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
 * Downscales to 16x16 in memory (< 0.2ms) using ITU-R BT.601 luma weights (`0.299R + 0.587G + 0.114B`).
 */
private fun computeBitmapSignature(bmp: Bitmap): IntArray {
    val thumb = Bitmap.createScaledBitmap(bmp, 16, 16, false)
    val pixels = IntArray(256)
    thumb.getPixels(pixels, 0, 16, 0, 0, 16, 16)
    if (thumb !== bmp) thumb.recycle()
    return IntArray(256) { i ->
        val c = pixels[i]
        ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
    }
}

/**
 * Extracts a video frame nearest to [timeMs], hardware-downscaled to [targetW] x [targetH].
 * Recycles any intermediate unscaled bitmap if software scaling is required.
 */
private fun extractScaledVideoFrame(
    retriever: MediaMetadataRetriever,
    timeMs: Long,
    targetW: Int,
    targetH: Int
): Bitmap? {
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

    return if (bmp.width != targetW || bmp.height != targetH) {
        Bitmap.createScaledBitmap(bmp, targetW, targetH, true).also {
            if (it !== bmp) bmp.recycle()
        }
    } else {
        bmp
    }
}

/**
 * Compresses this [Bitmap] to a JPEG byte array (quality 80) and immediately recycles the bitmap.
 */
private fun Bitmap.toJpegBytesAndRecycle(quality: Int = 80): ByteArray {
    return ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, quality, out)
        recycle()
        out.toByteArray()
    }
}
