package com.localfind.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import com.localfind.index.Indexer

/**
 * Result returned by recursive folder import via [FileImporter.importTreeUri].
 *
 * @property importedFiles All supported files extracted and staged for indexing.
 * @property createdFolders All folders and subfolders created in the library hierarchy.
 * @property skippedUnsupportedCount Count of files skipped because they lack builtin viewers.
 */
data class FolderImportResult(
    val importedFiles: List<ImportedFile>,
    val createdFolders: List<Folder>,
    val skippedUnsupportedCount: Int
)

/**
 * Helper utility to validate and resolve SAF document URIs into [ImportedFile] objects.
 *
 * Privacy / Viewer Guarantee:
 * - LocalFind strictly only imports file types that have a corresponding builtin viewer
 *   (Text/Markdown, PDF, Image, Audio, Video). Unsupported formats are rejected without
 *   acquiring persistable read permissions.
 * - Takes persistable URI read permission via [ContentResolver.takePersistableUriPermission]
 *   only for validated, supported files.
 */
object FileImporter {

    /**
     * Supported MIME types for system document pickers.
     * Restricts the system file picker to only show supported file types with builtin viewers.
     */
    val SUPPORTED_MIME_TYPES = arrayOf(
        "text/plain",
        "text/markdown",
        "text/*",
        "application/pdf",
        "image/jpeg",
        "image/png",
        "audio/mpeg",
        "audio/mp4",
        "audio/x-m4a",
        "audio/wav",
        "audio/x-wav",
        "audio/*",
        "video/mp4"
    )

    /**
     * Checks whether a file with given name and MIME type is supported and has a builtin viewer.
     */
    fun isSupported(displayName: String, mimeType: String?): Boolean =
        Indexer.isSupported(displayName, mimeType)

    /**
     * Resolves the human-readable display name for a given [Uri] from its content provider,
     * falling back to the last path segment or "image".
     */
    fun resolveDisplayName(contentResolver: ContentResolver, uri: Uri): String {
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        val name = cursor.getStringOrNull(nameIdx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "Image" } ?: "Image"
    }

    /**
     * Resolves metadata for a picked document URI, verifies that it is supported by LocalFind's
     * indexers and builtin viewers, and acquires persistable read permission.
     *
     * If the file is not supported, returns `null` and does NOT take persistable permissions.
     *
     * @param contentResolver The Android [ContentResolver] instance.
     * @param uri The document [Uri] returned by the system file picker.
     * @param folderId Optional ID of the [Folder] containing this file.
     * @return Fully populated [ImportedFile] metadata object if supported, or null if unsupported.
     */
    fun importUri(contentResolver: ContentResolver, uri: Uri, folderId: String? = null): ImportedFile? {
        var displayName: String? = null
        var size: Long = 0L

        // Query metadata from the document provider first
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
        } catch (_: Exception) {
            // Gracefully handle providers that fail cursor querying
        }

        // Fallback to the last URI path segment if display name is unavailable
        val name = displayName ?: uri.lastPathSegment ?: "unknown"
        val mimeType = try {
            contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        }

        // Enforce supported formats: reject any file that does not have a builtin viewer
        if (!isSupported(name, mimeType)) {
            return null
        }

        // Take persistable permission only after validating the file is supported,
        // retaining access across device reboots without copying or needing broad storage permissions.
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers or temporary test URIs might not support persistable grants
        }

        return ImportedFile(
            uri = uri.toString(),
            displayName = name,
            mimeType = mimeType,
            size = size,
            folderId = folderId
        )
    }

    /**
     * Recursively traverses a picked tree URI using [DocumentsContract], discovering all subdirectories
     * and supported files.
     *
     * Architectural Details:
     * - Preserves the directory hierarchy: Root and subfolder names are created in [FileStore]
     *   as [Folder] records linked by [Folder.parentId].
     * - Only supported files with builtin viewers (text, PDF, image, audio, video) are staged
     *   and imported with [IndexingStatus.PENDING].
     * - Takes persistable read permission on the tree URI so access to all descendant documents
     *   survives device reboots.
     * - High performance: Uses native [DocumentsContract] batch projection queries (1 IPC call per directory)
     *   with zero external dependencies and zero broad storage permissions.
     *
     * @param context Application context.
     * @param treeUri Tree URI returned by Android SAF `OpenDocumentTree` picker.
     * @param parentFolderId Optional ID of the parent folder if importing into an existing folder.
     * @return [FolderImportResult] with imported files, created folders, and skipped unsupported counts.
     */
    fun importTreeUri(
        context: Context,
        treeUri: Uri,
        parentFolderId: String? = null
    ): FolderImportResult {
        val contentResolver = context.contentResolver

        // Take persistable permission on the root tree URI
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers or test environments might not grant persistable permissions
        }

        val rootDocId = try {
            if (DocumentsContract.isTreeUri(treeUri)) {
                DocumentsContract.getTreeDocumentId(treeUri)
            } else {
                DocumentsContract.getDocumentId(treeUri)
            }
        } catch (_: Exception) {
            null
        } ?: return FolderImportResult(emptyList(), emptyList(), 0)

        val fileStore = FileStore.getInstance(context)
        val importedFiles = mutableListOf<ImportedFile>()
        val createdFolders = mutableListOf<Folder>()
        var unsupportedCount = 0

        // Query root folder display name
        var rootFolderName = "Imported Folder"
        try {
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            contentResolver.query(
                rootDocUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (nameIdx != -1) {
                        cursor.getStringOrNull(nameIdx)?.let { rootFolderName = it }
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback to default
        }

        val rootFolder = fileStore.getOrCreateFolder(rootFolderName, parentFolderId)
        createdFolders.add(rootFolder)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )

        // Recursive directory traversal using DocumentsContract batch query
        fun traverse(docId: String, currentFolderId: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            try {
                contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                    while (cursor.moveToNext()) {
                        val childId = (if (idIdx != -1) cursor.getStringOrNull(idIdx) else null) ?: continue
                        val name = (if (nameIdx != -1) cursor.getStringOrNull(nameIdx) else null) ?: "unknown"
                        val mimeType = if (mimeIdx != -1) cursor.getStringOrNull(mimeIdx) else null
                        val size = (if (sizeIdx != -1) cursor.getLongOrNull(sizeIdx) else null) ?: 0L

                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val subfolder = fileStore.getOrCreateFolder(name, currentFolderId)
                            createdFolders.add(subfolder)
                            traverse(childId, subfolder.id)
                        } else {
                            if (isSupported(name, mimeType)) {
                                val fileDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                try {
                                    contentResolver.takePersistableUriPermission(
                                        fileDocUri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                } catch (_: Exception) {
                                    // Covered by root tree grant
                                }
                                importedFiles.add(
                                    ImportedFile(
                                        uri = fileDocUri.toString(),
                                        displayName = name,
                                        mimeType = mimeType,
                                        size = size,
                                        folderId = currentFolderId,
                                        indexingStatus = IndexingStatus.PENDING
                                    )
                                )
                            } else {
                                unsupportedCount++
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Gracefully handle inaccessible subdirectories
            }
        }

        traverse(rootDocId, rootFolder.id)

        if (importedFiles.isNotEmpty()) {
            fileStore.insertAll(importedFiles)
        }

        return FolderImportResult(importedFiles, createdFolders, unsupportedCount)
    }
}
