package com.localfind.core.library

import com.localfind.core.text.PdfPageMath

/** Pure helpers behind gallery and search-result previews (timestamps, page indices, cache keys, text). */
object PreviewMath {

    /** Offset in milliseconds added to video thumbnail extraction to avoid black/fade-in frames. */
    const val VIDEO_THUMBNAIL_OFFSET_MS = 2_000L

    /** Maximum characters of a text file shown in its miniature document preview. */
    const val TEXT_PREVIEW_MAX_CHARS = 320

    /**
     * Calculates the target seek time in milliseconds for video thumbnail extraction.
     *
     * @param baseTimeMs Starting timestamp (e.g. search chunk offset or 0L for gallery grid).
     * @param durationMs Total video duration in milliseconds (if known, or 0L).
     * @param offsetMs Additional offset to advance into the scene (defaults to [VIDEO_THUMBNAIL_OFFSET_MS] = 2000ms).
     * @return Target timestamp clamped within the video duration boundaries.
     */
    fun computeVideoThumbnailTargetTimeMs(
        baseTimeMs: Long,
        durationMs: Long,
        offsetMs: Long = VIDEO_THUMBNAIL_OFFSET_MS
    ): Long {
        val rawTarget = baseTimeMs.coerceAtLeast(0L) + offsetMs
        return if (durationMs > 0L) {
            rawTarget.coerceAtMost((durationMs - 100L).coerceAtLeast(0L))
        } else {
            rawTarget
        }
    }

    /**
     * Computes the 0-based page index to render for a PDF document.
     *
     * @param pageNumber 1-based page number from search result location, or null for cover page.
     * @param pageCount Total page count in the PDF.
     * @return 0-based page index clamped to [0, pageCount - 1].
     */
    fun computePdfTargetPageIndex(pageNumber: Int?, pageCount: Int): Int {
        return PdfPageMath.toZeroBasedPage(pageNumber ?: 1, pageCount)
    }

    /**
     * Builds the cache key for preview data. For video search results with a specific timestamp,
     * or PDF search results with a matching page number, appends the offset so multiple matches
     * from the same file have distinct previews.
     */
    fun previewCacheKey(
        uriString: String,
        timeOffsetMs: Long? = null,
        pageNumber: Int? = null
    ): String {
        return when {
            pageNumber != null -> "$uriString@p$pageNumber"
            timeOffsetMs != null -> "$uriString@$timeOffsetMs"
            else -> uriString
        }
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
}
