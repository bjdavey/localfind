package com.localfind.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Result data class returned when processing an incoming share intent.
 *
 * @property importedFiles All supported files that were successfully resolved, staged, or copied.
 * @property unsupportedCount Number of files that were rejected because they lack builtin viewers.
 * @property isTextNote True if the shared content was plain text without stream attachments, saved as a text document.
 */
data class ShareImportResult(
    val importedFiles: List<ImportedFile>,
    val unsupportedCount: Int,
    val isTextNote: Boolean = false
)

/**
 * Handles incoming share intents (`ACTION_SEND` and `ACTION_SEND_MULTIPLE`) from external Android applications.
 *
 * Architectural Context & Security Model:
 * 1. Offline Guarantee: LocalFind operates 100% offline without any network access or broad storage permissions.
 * 2. Supported Formats: Files are verified against [FileImporter.isSupported]. Unsupported formats are skipped
 *    and not added to the library or database.
 * 3. URI Lifetime & Persistence:
 *    - In Android, external apps sharing files via `ACTION_SEND` typically grant temporary read access
 *      (e.g., FileProvider URIs) that expire when the activity lifecycle terminates or the device reboots.
 *    - Calling [ContentResolver.takePersistableUriPermission] on temporary FileProvider URIs fails with [SecurityException].
 *    - To ensure [com.localfind.index.IndexingWorker] can index in the background and builtin viewers can open
 *      the file reliably across app restarts and reboots, [ShareImporter] first attempts persistable permission.
 *      If persistable permission cannot be taken, it safely copies the file into the app's internal private
 *      storage directory (`context.filesDir/shared_imports/`).
 *    - If persistable permission succeeds (e.g. SAF DocumentsProvider share), the original URI is retained with zero copying.
 * 4. Text Sharing: If an app shares plain text snippet or notes without a file stream, [ShareImporter] saves it
 *    as a local `.txt` file so it can be viewed and semantically searched.
 */
object ShareImporter {

    /** Subdirectory in app's internal files directory where temporary shared imports are saved */
    const val SHARED_IMPORTS_DIR = "shared_imports"

    /**
     * Checks if the given [intent] is an incoming share intent from another app.
     */
    fun isShareIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action ?: return false
        return action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE
    }

    /**
     * Extracts all file URIs from an incoming share [Intent].
     *
     * Handles:
     * - `ACTION_SEND` with `EXTRA_STREAM` (single [Uri])
     * - `ACTION_SEND_MULTIPLE` with `EXTRA_STREAM` (`ArrayList<Uri>`)
     * - `ClipData` items attached to the intent
     * - `intent.data` fallback
     *
     * Returns a deduplicated list preserving original order.
     */
    fun extractUris(intent: Intent): List<Uri> {
        val uris = LinkedHashSet<Uri>()
        val action = intent.action

        if (action == Intent.ACTION_SEND) {
            val streamUri = try {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            } catch (_: Exception) {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            if (streamUri != null) {
                uris.add(streamUri)
            } else {
                intent.getStringExtra(Intent.EXTRA_STREAM)?.let {
                    try { uris.add(it.toUri()) } catch (_: Exception) {}
                }
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val streamUris = try {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            } catch (_: Exception) {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (streamUris != null) {
                for (u in streamUris) {
                    if (u != null) uris.add(u)
                }
            }
            if (uris.isEmpty()) {
                val stringUris = intent.getStringArrayListExtra(Intent.EXTRA_STREAM)
                if (stringUris != null) {
                    for (s in stringUris) {
                        try { uris.add(s.toUri()) } catch (_: Exception) {}
                    }
                }
            }
        }

        // Check ClipData for attached item URIs
        val clipData = intent.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                val itemUri = clipData.getItemAt(i)?.uri
                if (itemUri != null) {
                    uris.add(itemUri)
                }
            }
        }

        // Fallback to data URI if no streams were present
        if (uris.isEmpty() && intent.data != null) {
            uris.add(intent.data!!)
        }

        return uris.toList()
    }

    /**
     * Extracts shared plain text from an `ACTION_SEND` intent if present.
     */
    fun extractSharedText(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        return text?.takeIf { it.isNotBlank() }
    }

    /**
     * Replaces filesystem-illegal or problematic characters in file names.
     */
    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\?%*:|\"<>]"), "_").trim()
    }

    /**
     * Generates a collision-free target file within `context.filesDir/shared_imports/`.
     */
    fun createUniqueInternalFile(context: Context, fileName: String): File {
        val baseDir = File(context.filesDir, SHARED_IMPORTS_DIR).apply { mkdirs() }
        val sanitized = sanitizeFileName(fileName).ifBlank { "shared_file" }
        val nameWithoutExt = sanitized.substringBeforeLast('.')
        val ext = if (sanitized.contains('.')) ".${sanitized.substringAfterLast('.')}" else ""

        var candidate = File(baseDir, sanitized)
        var index = 1
        while (candidate.exists()) {
            candidate = File(baseDir, "${nameWithoutExt}_$index$ext")
            index++
        }
        return candidate
    }

    /**
     * Resolves the display name and size from the [ContentResolver] query, falling back to URI path heuristics.
     */
    fun resolveMetadata(contentResolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var displayName: String? = null
        var size = 0L

        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        displayName = cursor.getStringOrNull(nameIdx)
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx != -1) {
                        size = cursor.getLongOrNull(sizeIdx) ?: 0L
                    }
                }
            }
        } catch (_: Exception) {}

        val name = displayName ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "shared_file" } ?: "shared_file"
        return Pair(name, size)
    }

    /**
     * Imports a single shared URI:
     * - Validates supported format.
     * - Tries persistable read permission.
     * - If persistable permission is unavailable (temporary FileProvider share), copies the content
     *   to internal app storage to guarantee offline, durable access across device reboots.
     *
     * @return [ImportedFile] with [IndexingStatus.PENDING] if supported; null if unsupported or unreadable.
     */
    fun importSharedUri(
        context: Context,
        uri: Uri,
        folderId: String? = null,
        fallbackMimeType: String? = null
    ): ImportedFile? {
        val contentResolver = context.contentResolver
        val (initialName, reportedSize) = resolveMetadata(contentResolver, uri)

        val queriedMime = try {
            contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }
        val effectiveMime = queriedMime?.takeIf { it != "*/*" && it != "application/octet-stream" }
            ?: fallbackMimeType?.takeIf { it != "*/*" && it != "application/octet-stream" }

        var resolvedName = initialName
        // If the resolved name lacks a file extension, attempt deduction from MIME type
        if (!resolvedName.contains('.') && !effectiveMime.isNullOrBlank()) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(effectiveMime)
            if (!ext.isNullOrBlank()) {
                resolvedName = "$resolvedName.$ext"
            }
        }

        android.util.Log.d(
            "ShareImporter",
            "importSharedUri: uri=$uri initialName=$initialName resolvedName=$resolvedName mime=$effectiveMime size=$reportedSize"
        )

        // Reject files that do not have a corresponding builtin viewer in LocalFind
        if (!FileImporter.isSupported(resolvedName, effectiveMime)) {
            android.util.Log.d("ShareImporter", "importSharedUri: unsupported $resolvedName mime=$effectiveMime")
            return null
        }

        // Try persistable permission grant first (e.g. if shared from a SAF DocumentsProvider)
        var hasPersistablePermission = false
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            hasPersistablePermission = true
        } catch (_: SecurityException) {
            // Expected for FileProvider and temporary share streams from other apps
        }

        if (hasPersistablePermission) {
            android.util.Log.d("ShareImporter", "importSharedUri: persisted SAF grant retained for $resolvedName")
            return ImportedFile(
                uri = uri.toString(),
                displayName = resolvedName,
                mimeType = effectiveMime,
                size = reportedSize,
                folderId = folderId,
                indexingStatus = IndexingStatus.PENDING
            )
        }

        // If persistable permission cannot be acquired, copy file stream into app's private filesDir
        return try {
            val targetFile = createUniqueInternalFile(context, resolvedName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                android.util.Log.e("ShareImporter", "importSharedUri: openInputStream returned null for $uri")
                return null
            }

            val actualSize = if (reportedSize > 0L) reportedSize else targetFile.length()
            android.util.Log.d("ShareImporter", "importSharedUri: copied to internal file ${targetFile.absolutePath} size=$actualSize")
            ImportedFile(
                uri = Uri.fromFile(targetFile).toString(),
                displayName = targetFile.name,
                mimeType = effectiveMime,
                size = actualSize,
                folderId = folderId,
                indexingStatus = IndexingStatus.PENDING
            )
        } catch (e: Exception) {
            android.util.Log.e("ShareImporter", "importSharedUri: failed copying $uri: ${e.message}", e)
            null
        }
    }

    /**
     * Saves shared plain text as a `.txt` document in `context.filesDir/shared_imports/`.
     */
    fun createSharedTextFile(
        context: Context,
        text: String,
        subject: String? = null,
        folderId: String? = null
    ): ImportedFile? {
        return try {
            val baseName = if (!subject.isNullOrBlank()) {
                sanitizeFileName(subject)
            } else {
                val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                "Shared_Note_$dateStr"
            }
            val fileName = if (baseName.endsWith(".txt", ignoreCase = true) || baseName.endsWith(".md", ignoreCase = true)) {
                baseName
            } else {
                "$baseName.txt"
            }

            val targetFile = createUniqueInternalFile(context, fileName)
            targetFile.writeText(text, Charsets.UTF_8)

            ImportedFile(
                uri = Uri.fromFile(targetFile).toString(),
                displayName = targetFile.name,
                mimeType = "text/plain",
                size = targetFile.length(),
                folderId = folderId,
                indexingStatus = IndexingStatus.PENDING
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Processes an incoming share [Intent], extracting and importing all supported files or text notes.
     */
    fun processShareIntent(
        context: Context,
        intent: Intent,
        folderId: String? = null
    ): ShareImportResult {
        android.util.Log.d("ShareImporter", "processShareIntent action=${intent.action} type=${intent.type}")
        val uris = extractUris(intent)
        android.util.Log.d("ShareImporter", "extracted uris=${uris.size}: $uris")
        val imported = mutableListOf<ImportedFile>()
        var unsupported = 0

        if (uris.isNotEmpty()) {
            for (uri in uris) {
                val file = importSharedUri(context, uri, folderId, fallbackMimeType = intent.type)
                android.util.Log.d("ShareImporter", "importSharedUri($uri) -> file=$file")
                if (file != null) {
                    imported.add(file)
                } else {
                    unsupported++
                }
            }
            return ShareImportResult(imported, unsupportedCount = unsupported, isTextNote = false)
        }

        // If no file streams were found, check if plain text was shared
        val text = extractSharedText(intent)
        if (!text.isNullOrBlank()) {
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            val file = createSharedTextFile(context, text, subject, folderId)
            if (file != null) {
                imported.add(file)
                return ShareImportResult(imported, unsupportedCount = 0, isTextNote = true)
            }
        }

        return ShareImportResult(emptyList(), unsupportedCount = 0, isTextNote = false)
    }

    /**
     * Deletes any locally copied internal file associated with [uriString] when the user removes
     * the file from their library. Only deletes files within the `shared_imports/` directory.
     */
    fun deleteInternalFileIfShared(context: Context, uriString: String) {
        if (!uriString.startsWith("file://")) return
        try {
            val filePath = uriString.removePrefix("file://")
            val file = File(filePath)
            val sharedDir = File(context.filesDir, SHARED_IMPORTS_DIR)
            if (file.canonicalPath.startsWith(sharedDir.canonicalPath) && file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {
            // Ignore filesystem cleanup errors
        }
    }
}
