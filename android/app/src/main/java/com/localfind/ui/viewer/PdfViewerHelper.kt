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
     * - Minimum Android S (API level 31), which is always satisfied because the app's minSdk is 31
     * - SDK extension level 13 or higher (or Android 15+ where extension level >= 13 is native)
     */
    fun isJetpackPdfSupported(
        extensionVersionProvider: () -> Int = { SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) }
    ): Boolean {
        return try {
            extensionVersionProvider() >= 13
        } catch (_: Throwable) {
            false
        }
    }
}
