package com.localfind.core.text

/** Pure page-number and zoom math shared by the PDF viewers and thumbnail generation. */
object PdfPageMath {

    /**
     * Clamps a requested 1-based page number within valid document boundaries [1, totalPages].
     */
    fun clampPage(requestedPage: Int, totalPages: Int): Int {
        if (totalPages <= 0) return 1
        return requestedPage.coerceIn(1, totalPages)
    }

    /**
     * Converts a 1-based page number (e.g. from search results or indexer) to a safe 0-based page index
     * for framework or Jetpack PDF viewers clamped within [0, totalPages - 1].
     */
    fun toZeroBasedPage(oneBasedPage: Int, totalPages: Int): Int {
        if (totalPages <= 0) return 0
        return (oneBasedPage - 1).coerceIn(0, totalPages - 1)
    }

    /**
     * Calculates zoom scale clamped within [minScale, maxScale].
     */
    fun clampZoom(
        currentScale: Float,
        zoomChange: Float,
        minScale: Float = 1f,
        maxScale: Float = 4f
    ): Float {
        return (currentScale * zoomChange).coerceIn(minScale, maxScale)
    }
}
