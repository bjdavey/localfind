package com.localfind.ui.gallery

import com.localfind.core.library.GalleryCategory
import com.localfind.core.library.LibraryFilters
import com.localfind.core.library.PreviewMath
import com.localfind.core.model.FileKind
import com.localfind.core.model.FileTypes
import com.localfind.core.text.PdfPageMath
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import com.localfind.R
import com.localfind.core.model.Folder
import com.localfind.core.model.ImportedFile
import com.localfind.index.Indexer
import com.localfind.core.model.SearchResult
import com.localfind.core.format.SearchResultFormatter
import com.localfind.ui.viewer.PdfViewerHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Material Symbol drawable resource associated with each [GalleryCategory].
 */
val GalleryCategory.iconResId: Int
    get() = when (this) {
        GalleryCategory.ALL -> R.drawable.ic_grid_view
        GalleryCategory.PHOTOS -> R.drawable.ic_image
        GalleryCategory.VIDEOS -> R.drawable.ic_videocam
        GalleryCategory.PDFS -> R.drawable.ic_picture_as_pdf
        GalleryCategory.TEXT -> R.drawable.ic_description
        GalleryCategory.AUDIO -> R.drawable.ic_audiotrack
    }

/**
 * Loads (and caches) the rich preview of a library file for a tile or search result card.
 *
 * Returns the cached [FilePreviewData] immediately when available and otherwise loads it on
 * [Dispatchers.IO]. Pass [skip] = true for photos, which are rendered directly by Coil.
 *
 * @param timeOffsetMs Video keyframe offset for search hits (null = default thumbnail time).
 * @param pageNumber 1-based PDF page for search hits (null = first page).
 */
@Composable
fun rememberFilePreview(
    uri: String,
    fileName: String,
    mimeType: String?,
    skip: Boolean,
    timeOffsetMs: Long? = null,
    pageNumber: Int? = null
): FilePreviewData? {
    val context = LocalContext.current
    var preview by remember(uri, timeOffsetMs, pageNumber) {
        mutableStateOf(
            if (uri.isNotBlank()) ThumbnailHelper.getCachedPreview(uri, timeOffsetMs, pageNumber) else null
        )
    }
    LaunchedEffect(uri, timeOffsetMs, pageNumber) {
        if (preview == null && uri.isNotBlank() && !skip) {
            preview = withContext(Dispatchers.IO) {
                ThumbnailHelper.loadPreviewBlocking(
                    context = context,
                    uriString = uri,
                    fileName = fileName,
                    mimeType = mimeType,
                    timeOffsetMs = timeOffsetMs,
                    pageNumber = pageNumber
                )
            }
        }
    }
    return preview
}

/**
 * Cached preview data for a single file in the gallery or search results.
 *
 * @property bitmap Rendered thumbnail bitmap (PDF page 1, video keyframe, or audio cover art).
 * @property badgeText Duration label ("02:45") for video/audio, or the "VIDEO" / "AUDIO" placeholder when unreadable.
 * @property textSnippet First lines of text for `.txt` and `.md` files to render as a mini document sheet.
 */
data class FilePreviewData(
    val bitmap: Bitmap? = null,
    val badgeText: String? = null,
    val textSnippet: String? = null
)

/**
 * Helper utility for categorizing files and generating rich visual thumbnails for all supported
 * file types (PDF page 1, video keyframe, audio cover art, and text document first lines).
 *
 * Architecture & Performance Notes:
 * - 100% offline: Uses native Android APIs ([PdfRenderer], [MediaMetadataRetriever], [ContentResolver]).
 * - Memory-safe: Downsamples bitmaps to thumbnail dimensions (~360px) and caches results in an
 *   in-memory [LruCache] keyed by URI so scrolling the gallery grid stays smooth at 60+ FPS.
 */
object ThumbnailHelper {

    private const val THUMBNAIL_MAX_DIM = 360

    // Thread-safe in-memory cache storing up to 80 file preview records
    private val previewCache = object : LruCache<String, FilePreviewData>(80) {}

    /**
     * Retrieves cached [FilePreviewData] immediately if available, or `null` if not yet loaded.
     *
     * @param uri File URI string.
     * @param timeOffsetMs Optional timestamp offset in ms for video search results.
     * @param pageNumber Optional 1-based page number for PDF search results.
     */
    fun getCachedPreview(
        uri: String,
        timeOffsetMs: Long? = null,
        pageNumber: Int? = null
    ): FilePreviewData? {
        val key = PreviewMath.previewCacheKey(uri, timeOffsetMs, pageNumber)
        synchronized(previewCache) {
            return previewCache.get(key)
        }
    }

    /**
     * Evicts a cached preview entry when a file or folder is removed from the library.
     * Also clears all moment-specific previews for that URI.
     */
    fun evictPreview(uri: String) {
        synchronized(previewCache) {
            val keysToRemove = previewCache.snapshot().keys.filter { it == uri || it.startsWith("$uri@") }
            for (key in keysToRemove) {
                previewCache.remove(key)
            }
        }
    }

    /**
     * Loads or generates the visual preview for a file on a background thread and stores it in cache.
     *
     * - **PDF**: Renders matching page (or Page 1) onto a white bitmap via [PdfRenderer] and formats badge.
     * - **Video**: Extracts a frame at target moment + 2s offset via [MediaMetadataRetriever] and formats `"mm:ss"` duration.
     * - **Audio**: Extracts embedded album cover art if present and formats `"mm:ss"` duration.
     * - **Text / Markdown**: Reads the first lines of text for the document sheet preview card.
     */
    fun loadPreviewBlocking(
        context: Context,
        uriString: String,
        fileName: String,
        mimeType: String?,
        timeOffsetMs: Long? = null,
        pageNumber: Int? = null
    ): FilePreviewData {
        val cacheKey = PreviewMath.previewCacheKey(uriString, timeOffsetMs, pageNumber)
        synchronized(previewCache) {
            previewCache.get(cacheKey)?.let { return it }
        }

        val uri = try {
            uriString.toUri()
        } catch (_: Exception) {
            return FilePreviewData()
        }

        val generated = when {
            FileTypes.isPdfNameOrMime(fileName, mimeType) -> loadPdfPreview(context, uri, pageNumber)
            FileTypes.isVideoNameOrMime(fileName, mimeType) -> loadVideoPreview(context, uri, timeOffsetMs)
            FileTypes.isAudioNameOrMime(fileName, mimeType) -> loadAudioPreview(context, uri)
            FileTypes.isTextNameOrMime(fileName, mimeType) -> loadTextPreview(context, uri, fileName)
            else -> FilePreviewData()
        }

        synchronized(previewCache) {
            previewCache.put(cacheKey, generated)
        }
        return generated
    }

    /**
     * Renders a specific page (or page 1 if not specified) of a PDF document into a downsampled [Bitmap]
     * (the tile shows no badge text for PDFs, only the rendered page).
     */
    private fun loadPdfPreview(
        context: Context,
        uri: Uri,
        pageNumber: Int? = null
    ): FilePreviewData {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pageCount = renderer.pageCount
                    if (pageCount <= 0) {
                        return FilePreviewData()
                    }
                    val pageIndex = PreviewMath.computePdfTargetPageIndex(pageNumber, pageCount)
                    renderer.openPage(pageIndex).use { page ->
                        val scale = THUMBNAIL_MAX_DIM.toFloat() / max(page.width, page.height).coerceAtLeast(1)
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        // Fill with white paper background before rendering transparent PDF layer
                        bitmap.eraseColor(AndroidColor.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        return FilePreviewData(bitmap = bitmap)
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback gracefully if PDF is encrypted or unreadable
        }
        return FilePreviewData()
    }

    /**
     * Extracts a video preview frame plus duration badge.
     * - Gallery tiles (`timeOffsetMs == null`): first try native [ContentResolver.loadThumbnail]
     *   (provider/OS-cached, small decode). Not every SAF provider supports it, so on failure
     *   we fall through to the same [MediaMetadataRetriever] path used by search results.
     * - Search results (`timeOffsetMs != null`): frame at matched `timeOffsetMs + 2s`.
     *
     * The retriever is still opened for the duration badge; only the costly frame decode is skipped
     * when the native thumbnail succeeds.
     */
    private fun loadVideoPreview(
        context: Context,
        uri: Uri,
        timeOffsetMs: Long? = null
    ): FilePreviewData {
        val nativeThumbnail = if (timeOffsetMs == null) {
            try {
                context.contentResolver.loadThumbnail(uri, Size(THUMBNAIL_MAX_DIM, THUMBNAIL_MAX_DIM), null)
            } catch (_: Exception) {
                null // Provider has no thumbnail support: use the retriever fallback below
            }
        } else {
            null
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val badge = if (durationMs > 0L) SearchResultFormatter.formatTimeMs(durationMs) else "VIDEO"

            // Native thumbnail is already <= THUMBNAIL_MAX_DIM, so no re-scaling needed
            if (nativeThumbnail != null) {
                return FilePreviewData(bitmap = nativeThumbnail, badgeText = badge)
            }

            // 2-second offset avoids black/blank fade-ins; clamped within duration
            val targetTimeMs = PreviewMath.computeVideoThumbnailTargetTimeMs(
                baseTimeMs = timeOffsetMs ?: 0L,
                durationMs = durationMs
            )
            val targetTimeUs = targetTimeMs * 1000L

            // Hardware-downscaled closest keyframe, falling back to software full-size frames
            val rawFrame = retriever.getScaledFrameAtTime(
                targetTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                THUMBNAIL_MAX_DIM,
                THUMBNAIL_MAX_DIM
            ) ?: retriever.getFrameAtTime(
                targetTimeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.frameAtTime

            return FilePreviewData(
                bitmap = rawFrame?.let { scaleDownBitmap(it, THUMBNAIL_MAX_DIM) },
                badgeText = badge
            )
        } catch (_: Exception) {
            return FilePreviewData(bitmap = nativeThumbnail, badgeText = "VIDEO")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Extracts embedded album art (ID3 APIC / MP4 covr) and duration from an audio file.
     */
    private fun loadAudioPreview(context: Context, uri: Uri): FilePreviewData {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val badge = if (durationMs > 0L) SearchResultFormatter.formatTimeMs(durationMs) else "AUDIO"

            val artBytes = retriever.embeddedPicture
            val artBitmap = if (artBytes != null && artBytes.isNotEmpty()) {
                val decoded = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                decoded?.let { scaleDownBitmap(it, THUMBNAIL_MAX_DIM) }
            } else {
                null
            }

            return FilePreviewData(
                bitmap = artBitmap,
                badgeText = badge
            )
        } catch (_: Exception) {
            return FilePreviewData(badgeText = "AUDIO")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Streams the first few hundred characters of a text or Markdown file to display
     * as a miniature page preview in the gallery grid.
     */
    private fun loadTextPreview(context: Context, uri: Uri, fileName: String): FilePreviewData {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    val buffer = CharArray(PreviewMath.TEXT_PREVIEW_MAX_CHARS * 2)
                    val readCount = reader.read(buffer, 0, buffer.size)
                    val raw = if (readCount > 0) String(buffer, 0, readCount) else ""
                    return FilePreviewData(textSnippet = PreviewMath.formatFirstLinesPreview(raw))
                }
            }
        } catch (_: Exception) {
            // Ignore stream errors
        }
        return FilePreviewData(textSnippet = fileName)
    }

    /**
     * Scales down a [Bitmap] so neither width nor height exceeds [maxDim], recycling the
     * intermediate [source] bitmap when a new scaled bitmap is allocated.
     */
    private fun scaleDownBitmap(source: Bitmap, maxDim: Int): Bitmap {
        val maxSide = max(source.width, source.height)
        if (maxSide <= maxDim) return source
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        val targetWidth = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val targetHeight = (source.height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        if (scaled !== source) {
            source.recycle()
        }
        return scaled
    }
}
