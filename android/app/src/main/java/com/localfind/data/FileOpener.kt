package com.localfind.data

import com.localfind.core.model.FileTypes
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.localfind.index.Indexer
import java.io.File

/**
 * Utility to launch external viewer or editor intents, allowing users to open any file
 * in the phone gallery or its default/installed application.
 */
object FileOpener {

    /**
     * Resolves the best MIME type for the specified file and URI.
     */
    fun resolveMimeType(
        context: Context,
        uri: Uri,
        displayName: String,
        explicitMimeType: String? = null
    ): String {
        // 1. If explicit MIME is specific (not generic wildcard or octet-stream), use it
        if (!explicitMimeType.isNullOrBlank() &&
            explicitMimeType != "*/*" &&
            explicitMimeType != "application/octet-stream"
        ) {
            return explicitMimeType
        }

        // 2. Try querying ContentResolver for content:// URIs
        try {
            val queriedMime = context.contentResolver.getType(uri)
            if (!queriedMime.isNullOrBlank() &&
                queriedMime != "*/*" &&
                queriedMime != "application/octet-stream"
            ) {
                return queriedMime
            }
        } catch (_: Exception) {
            // Ignore resolver error and proceed with file name heuristics
        }

        // 3. Try MimeTypeMap lookup
        try {
            val ext = displayName.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty()) {
                val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                if (!fromMap.isNullOrBlank()) {
                    return fromMap
                }
            }
        } catch (_: Exception) {
            // Ignore if MimeTypeMap unavailable
        }

        // 4. Fallback based on extension heuristics and Indexer categories
        return FileTypes.resolveMimeTypeFromExtension(displayName, explicitMimeType)
    }

    /**
     * Opens the specified file in an external app or phone gallery via Android's ACTION_VIEW intent chooser.
     *
     * @param context Context used to start the chooser activity.
     * @param uriString Document URI string.
     * @param displayName File name used in error toasts and MIME resolution.
     * @param mimeType Optional known MIME type.
     */
    fun openInExternalApp(
        context: Context,
        uriString: String,
        displayName: String,
        mimeType: String? = null
    ) {
        if (uriString.isBlank()) {
            Toast.makeText(context, "No file URI associated with file", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = try {
            uriString.toUri()
        } catch (_: Exception) {
            Toast.makeText(context, "Invalid file URI", Toast.LENGTH_SHORT).show()
            return
        }

        val resolvedMime = resolveMimeType(context, uri, displayName, mimeType)

        // For internal file:// URIs (e.g. shared imports), use FileProvider to prevent FileUriExposedException
        val shareableUri = if (uri.scheme.equals("file", ignoreCase = true)) {
            try {
                val path = uri.path ?: uriString.removePrefix("file://")
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(path)
                )
            } catch (_: Exception) {
                uri
            }
        } else {
            uri
        }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(shareableUri, resolvedMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(viewIntent, "Open with").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open $displayName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open $displayName: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
