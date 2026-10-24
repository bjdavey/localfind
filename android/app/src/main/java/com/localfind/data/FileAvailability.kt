package com.localfind.data

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import java.net.URLDecoder

/**
 * Utility for verifying whether a file's underlying storage source is still present
 * and readable on the device.
 *
 * Used when opening files from the Gallery or Search Results to detect if the original
 * file was deleted, moved, or had permissions revoked outside LocalFind.
 */
object FileAvailability {

    /**
     * Checks if a local file path exists and is readable on the filesystem.
     */
    fun isLocalFileAvailable(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        // Only file:// URIs are percent-encoded (e.g. "my%20file.pdf"); raw paths may legitimately
        // contain '%', so they are left untouched. '+' is escaped first because URLDecoder
        // would otherwise turn it into a space (it is a literal '+' in URI paths).
        val cleanPath = if (path.startsWith("file://")) {
            try {
                URLDecoder.decode(path.removePrefix("file://").replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                path.removePrefix("file://") // malformed escape: use as-is
            }
        } else {
            path
        }
        val file = File(cleanPath)
        return file.exists() && file.canRead()
    }

    /**
     * Checks if the file referenced by [uriString] still exists and can be opened for reading.
     *
     * @param context Android context.
     * @param uriString URI string (content:// or file://).
     * @return True if the file exists and is accessible; false if deleted, missing, or unreadable.
     */
    fun isFileAvailable(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false

        // Quick check for direct file:// URIs or absolute file paths
        if (uriString.startsWith("file://") || uriString.startsWith("/")) {
            return isLocalFileAvailable(uriString)
        }

        val uri = try {
            uriString.toUri()
        } catch (_: Exception) {
            return false
        }

        return try {
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val path = uri.path ?: uriString.removePrefix("file://")
                isLocalFileAvailable(path)
            } else {
                // Verify we can open a descriptor or stream directly via ContentResolver.
                // This catches cases where the provider row is stale or the underlying file was deleted.
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                        return true
                    }
                } catch (_: Exception) {
                    // Try openInputStream as fallback for virtual/streamed documents
                }

                try {
                    context.contentResolver.openInputStream(uri)?.use {
                        return true
                    } ?: false
                } catch (_: Exception) {
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}

