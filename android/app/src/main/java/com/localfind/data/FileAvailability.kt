package com.localfind.data

import android.content.Context
import androidx.core.net.toUri
import java.io.File

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
        val cleanPath = path.removePrefix("file://")
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

