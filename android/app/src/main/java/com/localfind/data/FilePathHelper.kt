package com.localfind.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream

/**
 * Helper utility to resolve, parse, and format original device filesystem paths
 * from Android document URIs (SAF, MediaStore, file://, and content://).
 *
 * Architectural Context:
 * In modern Android (Storage Access Framework / Scoped Storage), files imported into LocalFind
 * are identified by content URIs (e.g. `content://com.android.externalstorage.documents/...`).
 * While content URIs are used internally by [com.localfind.index.IndexingWorker] and builtin viewers
 * to read file bytes via persistable permissions, users expect to see the real, human-readable
 * file path on their device storage (e.g. `/storage/emulated/0/Download/document.pdf`) in the
 * file details view.
 *
 * Resolution Hierarchy:
 * 1. Direct filesystem paths & file:// URIs: Strips prefix and decodes percent encodings.
 * 2. ContentResolver queries: When [Context] is available, queries `_data` and `relative_path` columns.
 * 3. SAF External Storage Provider (`com.android.externalstorage.documents`):
 *    - Parses `primary:path` into `/storage/emulated/0/path`.
 *    - Parses `UUID:path` (SD cards / USB OTG) into `/storage/UUID/path`.
 *    - Parses `home:path` into `/storage/emulated/0/Documents/path`.
 * 4. SAF Downloads Provider (`com.android.providers.downloads.documents`):
 *    - Extracts `raw:/storage/...` absolute path or formats `/storage/emulated/0/Download/...`.
 * 5. SAF Media Documents Provider (`com.android.providers.media.documents`):
 *    - Maps `image:`, `video:`, `audio:`, `document:` IDs to MediaStore queries or canonical folders.
 * 6. Fallback: URL-decoded human-readable representation of the URI.
 */
object FilePathHelper {

    /**
     * Resolves the best available human-readable filesystem path for [uriString],
     * utilizing Android [Context] queries when available and falling back to static URI parsing.
     *
     * @param context Optional Android [Context] for querying ContentResolver.
     * @param uriString The URI string representing the file.
     * @param displayName Optional file display name to enrich fallbacks.
     * @return Formatted human-readable original file path.
     */
    fun resolveDisplayPath(context: Context?, uriString: String, displayName: String? = null): String {
        if (uriString.isBlank()) return ""

        // Fast path for direct file paths or file:// URIs: no ContentResolver IPC needed
        if (uriString.startsWith("/") || uriString.startsWith("file://", ignoreCase = true)) {
            return resolvePathFromUri(uriString, displayName)
        }

        // If context is available, attempt to resolve underlying path from provider columns
        if (context != null) {
            val uri = try {
                Uri.parse(uriString)
            } catch (_: Exception) {
                null
            }

            if (uri != null) {
                // 1. Try querying the classic "_data" column for underlying filesystem path
                try {
                    context.contentResolver.query(
                        uri,
                        arrayOf("_data"),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val dataIdx = cursor.getColumnIndex("_data")
                            if (dataIdx != -1) {
                                val dataPath = cursor.getString(dataIdx)
                                if (!dataPath.isNullOrBlank() && dataPath.startsWith("/")) {
                                    return dataPath
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Content providers on Android 10+ (API 29+) may restrict direct _data column access
                }

                // 2. Try querying "relative_path" + "_display_name" (Android 10+ MediaStore standard)
                try {
                    context.contentResolver.query(
                        uri,
                        arrayOf("relative_path", "_display_name"),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val relIdx = cursor.getColumnIndex("relative_path")
                            val nameIdx = cursor.getColumnIndex("_display_name")
                            if (relIdx != -1) {
                                val relPath = cursor.getString(relIdx)
                                if (!relPath.isNullOrBlank()) {
                                    val queriedName = if (nameIdx != -1) cursor.getString(nameIdx) else null
                                    val fileName = queriedName ?: displayName ?: ""
                                    val cleanRel = relPath.trim('/')
                                    return if (fileName.isNotEmpty()) {
                                        "/storage/emulated/0/$cleanRel/$fileName"
                                    } else {
                                        "/storage/emulated/0/$cleanRel"
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore resolver exceptions and proceed to static URI parsing
                }

                // 3. For SAF Media Documents provider (e.g. image:123), query through MediaStore
                if (uriString.contains("com.android.providers.media.documents", ignoreCase = true)) {
                    val mediaResolved = resolveMediaViaMediaStore(context, uriString, displayName)
                    if (mediaResolved != null) return mediaResolved
                }
            }
        }

        // Fallback to static URI structural parser (pure logic)
        return resolvePathFromUri(uriString, displayName)
    }

    /**
     * Resolves the original filesystem path using pure string and structural analysis.
     * Does NOT require an Android [Context], making it fast and 100% testable in unit tests.
     *
     * @param uriString URI string or direct file path.
     * @param displayName Optional file display name to refine fallbacks.
     * @return Formatted human-readable original file path.
     */
    fun resolvePathFromUri(uriString: String, displayName: String? = null): String {
        if (uriString.isBlank()) return ""

        // Case 1: Plain absolute filesystem path
        if (uriString.startsWith("/") && !uriString.startsWith("//")) {
            return decodePercentEncoding(uriString)
        }

        // Case 2: File URI scheme (file:///storage/emulated/0/...)
        if (uriString.startsWith("file://", ignoreCase = true)) {
            val rawPath = uriString.removePrefix("file://").removePrefix("file:")
            return decodePercentEncoding(rawPath)
        }

        // Case 3: Storage Access Framework ExternalStorageProvider
        // Example: content://com.android.externalstorage.documents/document/primary%3ADownload%2Freport.pdf
        if (uriString.contains("com.android.externalstorage.documents", ignoreCase = true)) {
            val resolvedSaf = resolveExternalStorageSaf(uriString)
            if (resolvedSaf != null) return resolvedSaf
        }

        // Case 4: Storage Access Framework DownloadsProvider
        // Example: content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fmanual.pdf
        if (uriString.contains("com.android.providers.downloads.documents", ignoreCase = true)) {
            val resolvedDownloads = resolveDownloadsSaf(uriString, displayName)
            if (resolvedDownloads != null) return resolvedDownloads
        }

        // Case 5: Storage Access Framework Media Documents Provider
        // Example: content://com.android.providers.media.documents/document/image%3A12345
        if (uriString.contains("com.android.providers.media.documents", ignoreCase = true)) {
            val resolvedMedia = resolveMediaDocumentsSaf(uriString, displayName)
            if (resolvedMedia != null) return resolvedMedia
        }

        // Case 6: Fallback for generic content:// URIs
        return decodePercentEncoding(uriString)
    }

    /**
     * Resolves SAF External Storage document URIs to device filesystem paths.
     */
    private fun resolveExternalStorageSaf(uriString: String): String? {
        // Extract the document ID segment after "/document/" (or "/tree/" if it's a root tree URI)
        val docIdPart = when {
            uriString.contains("/document/") -> uriString.substringAfter("/document/").substringBefore("?").substringBefore("#")
            uriString.contains("/tree/") -> uriString.substringAfter("/tree/").substringBefore("?").substringBefore("#")
            else -> return null
        }

        val docId = decodePercentEncoding(docIdPart)

        return when {
            // Primary internal shared storage: "primary:Download/file.pdf"
            docId.startsWith("primary:", ignoreCase = true) -> {
                val relPath = docId.substring(8).trimStart('/')
                "/storage/emulated/0/$relPath"
            }
            // Home directory alias: "home:Documents/file.pdf"
            docId.startsWith("home:", ignoreCase = true) -> {
                val relPath = docId.substring(5).trimStart('/')
                "/storage/emulated/0/Documents/$relPath"
            }
            // Secondary storage / SD Card: "1234-5678:DCIM/Camera/IMG_0001.jpg"
            docId.contains(":") -> {
                val volumeId = docId.substringBefore(":")
                val relPath = docId.substringAfter(":").trimStart('/')
                "/storage/$volumeId/$relPath"
            }
            // Plain path inside docId: "/storage/..." or "Download/..."
            docId.startsWith("/") -> docId
            else -> "/storage/emulated/0/$docId"
        }
    }

    /**
     * Resolves SAF Downloads document URIs to device filesystem paths.
     */
    private fun resolveDownloadsSaf(uriString: String, displayName: String?): String? {
        val docIdPart = when {
            uriString.contains("/document/") -> uriString.substringAfter("/document/").substringBefore("?").substringBefore("#")
            uriString.contains("/tree/") -> uriString.substringAfter("/tree/").substringBefore("?").substringBefore("#")
            else -> return null
        }

        val docId = decodePercentEncoding(docIdPart)

        return when {
            // Raw filesystem path: "raw:/storage/emulated/0/Download/file.pdf"
            docId.startsWith("raw:", ignoreCase = true) -> {
                docId.substring(4)
            }
            docId.startsWith("/storage/") -> {
                docId
            }
            !displayName.isNullOrBlank() -> {
                "/storage/emulated/0/Download/$displayName"
            }
            else -> {
                "/storage/emulated/0/Download/$docId"
            }
        }
    }

    /**
     * Resolves SAF Media document URIs to device filesystem paths using heuristics.
     */
    private fun resolveMediaDocumentsSaf(uriString: String, displayName: String?): String? {
        val docIdPart = when {
            uriString.contains("/document/") -> uriString.substringAfter("/document/").substringBefore("?").substringBefore("#")
            uriString.contains("/tree/") -> uriString.substringAfter("/tree/").substringBefore("?").substringBefore("#")
            else -> return null
        }

        val docId = decodePercentEncoding(docIdPart)
        val type = docId.substringBefore(":").lowercase()
        val idOrName = docId.substringAfter(":", "").ifBlank { displayName ?: "" }

        val targetFolder = when (type) {
            "image" -> "Pictures"
            "video" -> "Movies"
            "audio" -> "Music"
            "document" -> "Documents"
            else -> "Download"
        }

        val finalName = displayName ?: idOrName
        return if (finalName.isNotEmpty()) {
            "/storage/emulated/0/$targetFolder/$finalName"
        } else {
            "/storage/emulated/0/$targetFolder"
        }
    }

    /**
     * Maps SAF Media Document URI to standard MediaStore query and extracts `_data` or `relative_path`.
     */
    private fun resolveMediaViaMediaStore(context: Context, uriString: String, displayName: String?): String? {
        val docIdPart = uriString.substringAfter("/document/", "").substringBefore("?").substringBefore("#")
        if (docIdPart.isBlank()) return null

        val docId = decodePercentEncoding(docIdPart)
        val type = docId.substringBefore(":").lowercase()
        val id = docId.substringAfter(":", "").toLongOrNull() ?: return null

        val baseUri = when (type) {
            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

        val mediaItemUri = ContentUris.withAppendedId(baseUri, id)

        // Try querying _data column on the MediaStore item
        try {
            context.contentResolver.query(
                mediaItemUri,
                arrayOf("_data"),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("_data")
                    if (idx != -1) {
                        val path = cursor.getString(idx)
                        if (!path.isNullOrBlank() && path.startsWith("/")) {
                            return path
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore MediaStore query errors
        }

        return null
    }

    /**
     * Safely decodes `%XX` hexadecimal percent-encoded byte sequences (e.g., `%20` -> ` `, `%2F` -> `/`)
     * using UTF-8, while preserving literal `+` characters without turning them into spaces.
     *
     * Pure Kotlin implementation with zero Android framework dependencies, ensuring fast JVM unit testing.
     */
    fun decodePercentEncoding(input: String): String {
        if (!input.contains('%')) return input

        val output = ByteArrayOutputStream(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 2 < input.length) {
                val hex = input.substring(i + 1, i + 3)
                val byteVal = hex.toIntOrNull(16)
                if (byteVal != null) {
                    output.write(byteVal)
                    i += 3
                    continue
                }
            }
            // Preserve unencoded characters as proper UTF-8 bytes
            val charBytes = c.toString().toByteArray(Charsets.UTF_8)
            output.write(charBytes, 0, charBytes.size)
            i++
        }

        return try {
            output.toString("UTF-8")
        } catch (_: Exception) {
            input
        }
    }

    /**
     * Converts a fully resolved filesystem path into a clean, human-readable display path.
     *
     * Simplifications:
     * - Primary internal storage: `/storage/emulated/0/Download/car1.jpeg` -> `Download/car1.jpeg`
     * - SD Card / Secondary storage: `/storage/1234-5678/DCIM/photo.jpg` -> `SD Card/DCIM/photo.jpg`
     * - App private storage: `/data/user/0/.../files/shared_imports/note.txt` -> `App Storage/shared_imports/note.txt`
     *
     * @param fullPath Fully resolved filesystem path or URI.
     * @return Simplified, user-friendly display path.
     */
    fun formatUserFriendlyPath(fullPath: String): String {
        if (fullPath.isBlank()) return ""

        // Case 1: Primary shared internal storage (/storage/emulated/0/...)
        if (fullPath.startsWith("/storage/emulated/0/", ignoreCase = true)) {
            val rel = fullPath.removePrefix("/storage/emulated/0/").removePrefix("/storage/emulated/0")
            return rel.trimStart('/')
        }

        // Case 2: SD card or external storage volume (/storage/<UUID>/...)
        if (fullPath.startsWith("/storage/", ignoreCase = true)) {
            val sub = fullPath.removePrefix("/storage/")
            val volume = sub.substringBefore('/')
            val rest = sub.substringAfter('/', "")
            return if (rest.isNotEmpty()) "SD Card/$rest" else "SD Card ($volume)"
        }

        // Case 3: App-internal storage (shared imports)
        if (fullPath.contains("/shared_imports/")) {
            val rel = fullPath.substringAfter("/shared_imports/")
            return "App Storage/shared_imports/$rel"
        }

        return fullPath
    }

    /**
     * Copies the given text string to the system clipboard.
     *
     * @param context Android context.
     * @param text Text content to place on clipboard.
     * @param label Human-readable clip description.
     */
    fun copyToClipboard(context: Context, text: String, label: String = "File Path") {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (_: Exception) {
            // Defensively catch security or service initialization errors
        }
    }
}
