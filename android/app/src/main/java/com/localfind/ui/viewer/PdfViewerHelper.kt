package com.localfind.ui.viewer

import android.os.Build
import android.os.ext.SdkExtensions

/**
 * Pure helper utilities for PDF viewing, bounds calculation, and SDK extension detection.
 */
object PdfViewerHelper {

    /**
     * Checks if the device environment satisfies requirements for the official
     * AndroidX Jetpack PDF library (`androidx.pdf:pdf-viewer-fragment`).
     *
     * Requirements according to official Android documentation:
     * - Minimum Android S (API level 31)
     * - SDK extension level 13 or higher (or Android 15+ where extension level >= 13 is native)
     */
    fun isJetpackPdfSupported(
        sdkInt: Int = Build.VERSION.SDK_INT,
        extensionVersionProvider: () -> Int = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S)
            } else {
                0
            }
        }
    ): Boolean {
        return try {
            if (sdkInt >= Build.VERSION_CODES.S) {
                extensionVersionProvider() >= 13
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

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

