package com.localfind.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.localfind.data.ImportedFile
import com.localfind.index.Indexer
import com.localfind.search.SearchResult
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Filter categories used in both the Gallery Grid and the Dedicated Search View.
 *
 * Allows users to quickly narrow down files or search hits by media modality.
 */
enum class GalleryCategory(val label: String, val icon: String) {
    ALL("All", "✦"),
    PHOTOS("Photos", "🖼"),
    VIDEOS("Videos", "🎬"),
    DOCUMENTS("Docs", "📄"),
    AUDIO("Audio", "🎵")
}

/**
 * Cached preview data for a single file in the gallery or search results.
 *
 * @property bitmap Rendered thumbnail bitmap (PDF page 1, video keyframe, or audio cover art).
 * @property badgeText Corner badge label (e.g., "14 p." for PDFs, "02:45" for video/audio, "MD" / "TXT").
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
    private const val TEXT_PREVIEW_MAX_CHARS = 320

    // Thread-safe in-memory cache storing up to 80 file preview records
    private val previewCache = object : LruCache<String, FilePreviewData>(80) {}

    /**
     * Determines the [GalleryCategory] for an [ImportedFile] based on its name and MIME type.
     */
    fun categorizeFile(fileName: String, mimeType: String?): GalleryCategory {
        return when {
            Indexer.isImageNameOrMime(fileName, mimeType) -> GalleryCategory.PHOTOS
            Indexer.isVideoNameOrMime(fileName, mimeType) -> GalleryCategory.VIDEOS
            Indexer.isAudioNameOrMime(fileName, mimeType) -> GalleryCategory.AUDIO
            Indexer.isPdfNameOrMime(fileName, mimeType) ||
                    Indexer.isTextNameOrMime(fileName, mimeType) -> GalleryCategory.DOCUMENTS
            else -> GalleryCategory.DOCUMENTS
        }
    }

    /**
     * Determines the [GalleryCategory] for a [SearchResult] based on its recorded `fileType` or file name.
     */
    fun categorizeSearchResult(result: SearchResult): GalleryCategory {
        return when (result.fileType.lowercase()) {
            "image" -> GalleryCategory.PHOTOS
            "video" -> GalleryCategory.VIDEOS
            "audio" -> GalleryCategory.AUDIO
            "pdf", "text" -> GalleryCategory.DOCUMENTS
            else -> categorizeFile(result.fileName, null)
        }
    }

    /**
     * Filters a list of [ImportedFile] items by the selected [GalleryCategory].
     */
    fun filterFiles(files: List<ImportedFile>, category: GalleryCategory): List<ImportedFile> {
        if (category == GalleryCategory.ALL) return files
        return files.filter { categorizeFile(it.displayName, it.mimeType) == category }
    }

    /**
     * Filters a list of [SearchResult] items by the selected [GalleryCategory].
     */
    fun filterSearchResults(results: List<SearchResult>, category: GalleryCategory): List<SearchResult> {
        if (category == GalleryCategory.ALL) return results
        return results.filter { categorizeSearchResult(it) == category }
    }

    /**
     * Cleans raw text from the beginning of a `.txt` or `.md` file so it displays neatly
     * inside a miniature document thumbnail card.
     */
    fun formatFirstLinesPreview(rawText: String, maxChars: Int = TEXT_PREVIEW_MAX_CHARS): String {
        val normalized = rawText
            .replace("\r\n", "\n")
            .lines()
            .map { it.trimEnd() }
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .trim()
        if (normalized.isEmpty()) return "(Empty document)"
        return if (normalized.length <= maxChars) {
            normalized
        } else {
            normalized.take(maxChars).trimEnd() + "…"
        }
    }

    /**
     * Retrieves cached [FilePreviewData] immediately if available, or `null` if not yet loaded.
     */
    fun getCachedPreview(uri: String): FilePreviewData? {
        synchronized(previewCache) {
            return previewCache.get(uri)
        }
    }

    /**
     * Loads or generates the visual preview for a file on a background thread and stores it in cache.
     *
     * - **PDF**: Renders Page 1 onto a white bitmap via [PdfRenderer] and formats `"N p."` badge.
     * - **Video**: Extracts a frame at 0.5s via [MediaMetadataRetriever] and formats `"mm:ss"` duration.
     * - **Audio**: Extracts embedded album cover art if present and formats `"mm:ss"` duration.
     * - **Text / Markdown**: Reads the first lines of text for the document sheet preview card.
     */
    fun loadPreviewBlocking(
        context: Context,
        uriString: String,
        fileName: String,
        mimeType: String?
    ): FilePreviewData {
        getCachedPreview(uriString)?.let { return it }

        val uri = try {
            Uri.parse(uriString)
        } catch (_: Exception) {
            return FilePreviewData()
        }

        val generated = when {
            Indexer.isPdfNameOrMime(fileName, mimeType) -> loadPdfPreview(context, uri)
            Indexer.isVideoNameOrMime(fileName, mimeType) -> loadVideoPreview(context, uri)
            Indexer.isAudioNameOrMime(fileName, mimeType) -> loadAudioPreview(context, uri)
            Indexer.isTextNameOrMime(fileName, mimeType) -> loadTextPreview(context, uri, fileName)
            else -> FilePreviewData()
        }

        synchronized(previewCache) {
            previewCache.put(uriString, generated)
        }
        return generated
    }

    /**
     * Renders the first page (index 0) of a PDF document into a downsampled [Bitmap]
     * and returns the total page count badge (e.g., `"12 p."`).
     */
    private fun loadPdfPreview(context: Context, uri: Uri): FilePreviewData {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    val pageCount = renderer.pageCount
                    if (pageCount <= 0) {
                        return FilePreviewData(badgeText = "PDF")
                    }
                    renderer.openPage(0).use { page ->
                        val scale = THUMBNAIL_MAX_DIM.toFloat() / max(page.width, page.height).coerceAtLeast(1)
                        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (page.height * scale).roundToInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        // Fill with white paper background before rendering transparent PDF layer
                        bitmap.eraseColor(AndroidColor.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        return FilePreviewData(
                            bitmap = bitmap,
                            badgeText = "PDF • $pageCount p."
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback gracefully if PDF is encrypted or unreadable
        }
        return FilePreviewData(badgeText = "PDF")
    }

    /**
     * Extracts a representative keyframe and duration from a video file using [MediaMetadataRetriever].
     */
    private fun loadVideoPreview(context: Context, uri: Uri): FilePreviewData {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val badge = if (durationMs > 0L) SearchResultFormatter.formatTimeMs(durationMs) else "VIDEO"

            // Try extracting a frame at 500ms (avoids black fade-in at 0ms), fallback to any frame
            val rawFrame = retriever.getFrameAtTime(
                500_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.frameAtTime

            val scaledFrame = rawFrame?.let { scaleDownBitmap(it, THUMBNAIL_MAX_DIM) }
            return FilePreviewData(
                bitmap = scaledFrame,
                badgeText = badge
            )
        } catch (_: Exception) {
            return FilePreviewData(badgeText = "VIDEO")
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
        val extBadge = if (fileName.lowercase().endsWith(".md") ||
            fileName.lowercase().endsWith(".markdown")
        ) {
            "MD"
        } else {
            "TXT"
        }

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    val buffer = CharArray(TEXT_PREVIEW_MAX_CHARS * 2)
                    val readCount = reader.read(buffer, 0, buffer.size)
                    val raw = if (readCount > 0) String(buffer, 0, readCount) else ""
                    return FilePreviewData(
                        badgeText = extBadge,
                        textSnippet = formatFirstLinesPreview(raw)
                    )
                }
            }
        } catch (_: Exception) {
            // Ignore stream errors
        }
        return FilePreviewData(badgeText = extBadge, textSnippet = fileName)
    }

    /**
     * Scales down a [Bitmap] so neither width nor height exceeds [maxDim].
     */
    private fun scaleDownBitmap(source: Bitmap, maxDim: Int): Bitmap {
        val maxSide = max(source.width, source.height)
        if (maxSide <= maxDim) return source
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        val targetWidth = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val targetHeight = (source.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }
}
