package com.localfind.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.localfind.index.Indexer
import java.util.Locale
import java.util.UUID

/**
 * Metadata record for a user or imported folder.
 *
 * @property id Unique identifier for the folder (e.g. UUID string).
 * @property name User-facing display name of the folder.
 * @property parentId ID of the parent folder if nested, or null if located at the library root.
 * @property createdAt Epoch timestamp (ms) when the folder was created or imported.
 */
data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

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
 * Metadata record for a file imported via Android's Storage Access Framework (SAF).
 *
 * Privacy / Link Architecture:
 * - We link, not copy: The original file remains in device storage; we do not duplicate it.
 * - Minimal permissions: No broad storage permission (READ_EXTERNAL_STORAGE / MANAGE_EXTERNAL_STORAGE)
 *   is requested. The app only accesses files explicitly chosen by the user in the system picker.
 *
 * @property uri Persistable Content URI string (e.g., `content://com.android.providers.downloads.documents/...`).
 * @property displayName Human-readable file name (e.g., `notes.txt`).
 * @property mimeType MIME type reported by the system content provider (e.g., `text/plain`, `text/markdown`).
 * @property size Size of the file in bytes.
 * @property addedAt Epoch timestamp (ms) when the file was first imported into LocalFind.
 * @property indexingStatus Current background indexing lifecycle status ([IndexingStatus]).
 * @property chunksCount Number of semantic chunks extracted and stored for this file.
 * @property folderId ID of the containing [Folder], or null if the file resides at the library root.
 */
enum class IndexingStatus {
    PENDING,
    INDEXING,
    INDEXED,
    FAILED
}

data class ImportedFile(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val size: Long,
    val addedAt: Long = System.currentTimeMillis(),
    val indexingStatus: IndexingStatus = IndexingStatus.INDEXED,
    val chunksCount: Int = 0,
    val folderId: String? = null
)

/**
 * Formats raw byte sizes into human-readable strings (e.g., "512 B", "1.5 KB", "24.0 MB").
 *
 * Handles negative or zero values safely by returning "0 B".
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    // Calculate the scale index (0 = B, 1 = KB, 2 = MB, 3 = GB, 4 = TB)
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceIn(0, units.size - 1)
    if (digitGroups == 0) return "$bytes B"
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
}

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
     * Checks whether an [ImportedFile] is supported and has a builtin viewer.
     */
    fun isSupported(file: ImportedFile): Boolean =
        Indexer.isSupported(file.displayName, file.mimeType)

    /**
     * Resolves metadata for a picked document URI, verifies that it is supported by LocalFind's
     * indexers and builtin viewers, and acquires persistable read permission.
     *
     * If the file is not supported, returns `null` and does NOT take persistable permissions.
     *
     * @param contentResolver The Android [ContentResolver] instance.
     * @param uri The document [Uri] returned by the system file picker.
     * @return Fully populated [ImportedFile] metadata object if supported, or null if unsupported.
     */
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
                    if (nameIdx != -1 && !cursor.isNull(nameIdx)) {
                        displayName = cursor.getString(nameIdx)
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                        size = cursor.getLong(sizeIdx)
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

        val fileStore = FileStore(context)
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
                    if (nameIdx != -1 && !cursor.isNull(nameIdx)) {
                        rootFolderName = cursor.getString(nameIdx)
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
                        val childId = if (idIdx != -1 && !cursor.isNull(idIdx)) cursor.getString(idIdx) else null ?: continue
                        val name = if (nameIdx != -1 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else "unknown"
                        val mimeType = if (mimeIdx != -1 && !cursor.isNull(mimeIdx)) cursor.getString(mimeIdx) else null
                        val size = if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L

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

/**
 * SQLite-backed local store for managing imported files, persistent URIs, and folder hierarchies.
 *
 * Uses Android's built-in [SQLiteOpenHelper] with zero external dependencies to persist:
 * - Files: URI (PK), Display Name, MIME Type, File Size, Added Timestamp, Status, Chunks, Folder ID
 * - Folders: ID (PK), Name, Parent Folder ID, Created Timestamp
 */
class FileStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "localfind_files.db"
        const val DATABASE_VERSION = 1
        const val TABLE_FILES = "imported_files"
        const val TABLE_FOLDERS = "folders"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FILES (
                uri TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                mime_type TEXT,
                size INTEGER NOT NULL,
                added_at INTEGER NOT NULL,
                indexing_status TEXT NOT NULL DEFAULT 'INDEXED',
                chunks_count INTEGER NOT NULL DEFAULT 0,
                folder_id TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_FOLDERS (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                parent_id TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No-op
    }

    // =========================================================================
    // FOLDER OPERATIONS
    // =========================================================================

    /**
     * Creates and stores a new [Folder] entry in the database.
     */
    fun createFolder(name: String, parentId: String? = null, id: String = UUID.randomUUID().toString()): Folder {
        val folder = Folder(id = id, name = name.trim(), parentId = parentId)
        val values = ContentValues().apply {
            put("id", folder.id)
            put("name", folder.name)
            put("parent_id", folder.parentId)
            put("created_at", folder.createdAt)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_FOLDERS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        return folder
    }

    /**
     * Retrieves an existing folder with matching [name] and [parentId], or creates a new one.
     * Prevents duplicate folders during re-imports.
     */
    fun getOrCreateFolder(name: String, parentId: String? = null): Folder {
        val trimmed = name.trim()
        val selection = if (parentId == null) "name = ? AND parent_id IS NULL" else "name = ? AND parent_id = ?"
        val selectionArgs = if (parentId == null) arrayOf(trimmed) else arrayOf(trimmed, parentId)
        val cursor = readableDatabase.query(
            TABLE_FOLDERS,
            arrayOf("id", "name", "parent_id", "created_at"),
            selection,
            selectionArgs,
            null, null, null
        )
        cursor.use {
            if (it.moveToNext()) {
                return Folder(
                    id = it.getString(0),
                    name = it.getString(1),
                    parentId = if (it.isNull(2)) null else it.getString(2),
                    createdAt = it.getLong(3)
                )
            }
        }
        return createFolder(trimmed, parentId)
    }

    /**
     * Retrieves all folders whose parent matches [parentId] (null for root level),
     * ordered alphabetically by name.
     */
    fun getFolders(parentId: String? = null): List<Folder> {
        val list = mutableListOf<Folder>()
        val selection = if (parentId == null) "parent_id IS NULL" else "parent_id = ?"
        val selectionArgs = if (parentId == null) null else arrayOf(parentId)
        val cursor = readableDatabase.query(
            TABLE_FOLDERS,
            arrayOf("id", "name", "parent_id", "created_at"),
            selection,
            selectionArgs,
            null, null,
            "name ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    Folder(
                        id = it.getString(0),
                        name = it.getString(1),
                        parentId = if (it.isNull(2)) null else it.getString(2),
                        createdAt = it.getLong(3)
                    )
                )
            }
        }
        return list
    }

    /**
     * Retrieves all folders across the entire library.
     */
    fun getAllFolders(): List<Folder> {
        val list = mutableListOf<Folder>()
        val cursor = readableDatabase.query(
            TABLE_FOLDERS,
            arrayOf("id", "name", "parent_id", "created_at"),
            null, null, null, null,
            "name ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    Folder(
                        id = it.getString(0),
                        name = it.getString(1),
                        parentId = if (it.isNull(2)) null else it.getString(2),
                        createdAt = it.getLong(3)
                    )
                )
            }
        }
        return list
    }

    /**
     * Retrieves a single folder by its unique [id].
     */
    fun getFolder(id: String): Folder? {
        val cursor = readableDatabase.query(
            TABLE_FOLDERS,
            arrayOf("id", "name", "parent_id", "created_at"),
            "id = ?",
            arrayOf(id),
            null, null, null
        )
        cursor.use {
            if (it.moveToNext()) {
                return Folder(
                    id = it.getString(0),
                    name = it.getString(1),
                    parentId = if (it.isNull(2)) null else it.getString(2),
                    createdAt = it.getLong(3)
                )
            }
        }
        return null
    }

    /**
     * Renames an existing folder.
     */
    fun renameFolder(id: String, newName: String) {
        val values = ContentValues().apply {
            put("name", newName.trim())
        }
        writableDatabase.update(TABLE_FOLDERS, values, "id = ?", arrayOf(id))
    }

    /**
     * Deletes a folder, all its subfolders, and all contained files from the library.
     *
     * @param id Folder ID to delete.
     * @return List of URIs of deleted files.
     */
    fun deleteFolder(id: String): List<String> {
        val deletedUris = mutableListOf<String>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Find all subfolder IDs recursively
            val allFolderIds = mutableListOf(id)
            var i = 0
            while (i < allFolderIds.size) {
                val currentId = allFolderIds[i]
                val subCursor = db.query(TABLE_FOLDERS, arrayOf("id"), "parent_id = ?", arrayOf(currentId), null, null, null)
                subCursor.use {
                    while (it.moveToNext()) {
                        allFolderIds.add(it.getString(0))
                    }
                }
                i++
            }

            for (folderId in allFolderIds) {
                val fileCursor = db.query(TABLE_FILES, arrayOf("uri"), "folder_id = ?", arrayOf(folderId), null, null, null)
                fileCursor.use {
                    while (it.moveToNext()) {
                        deletedUris.add(it.getString(0))
                    }
                }
                db.delete(TABLE_FILES, "folder_id = ?", arrayOf(folderId))
                db.delete(TABLE_FOLDERS, "id = ?", arrayOf(folderId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return deletedUris
    }

    /**
     * Reassigns a file's containing folder. Passing `null` moves the file to library root.
     */
    fun updateFileFolder(uri: String, folderId: String?) {
        val values = ContentValues().apply {
            if (folderId != null) {
                put("folder_id", folderId)
            } else {
                putNull("folder_id")
            }
        }
        writableDatabase.update(TABLE_FILES, values, "uri = ?", arrayOf(uri))
    }

    /**
     * Retrieves files filtered by [folderId] (or root level if `null`).
     */
    fun getFilesByFolder(folderId: String? = null): List<ImportedFile> {
        val list = mutableListOf<ImportedFile>()
        val selection = if (folderId == null) "folder_id IS NULL" else "folder_id = ?"
        val selectionArgs = if (folderId == null) null else arrayOf(folderId)
        val cursor = readableDatabase.query(
            TABLE_FILES,
            arrayOf("uri", "display_name", "mime_type", "size", "added_at", "indexing_status", "chunks_count", "folder_id"),
            selection,
            selectionArgs,
            null, null,
            "added_at DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val statusStr = if (it.isNull(5)) null else it.getString(5)
                val status = try {
                    if (statusStr != null) IndexingStatus.valueOf(statusStr) else IndexingStatus.INDEXED
                } catch (_: Exception) {
                    IndexingStatus.INDEXED
                }
                list.add(
                    ImportedFile(
                        uri = it.getString(0),
                        displayName = it.getString(1),
                        mimeType = if (it.isNull(2)) null else it.getString(2),
                        size = it.getLong(3),
                        addedAt = it.getLong(4),
                        indexingStatus = status,
                        chunksCount = if (it.isNull(6)) 0 else it.getInt(6),
                        folderId = if (it.isNull(7)) null else it.getString(7)
                    )
                )
            }
        }
        return list
    }

    // =========================================================================
    // FILE OPERATIONS
    // =========================================================================

    /**
     * Inserts or updates an imported file entry.
     */
    fun insert(file: ImportedFile) {
        val values = ContentValues().apply {
            put("uri", file.uri)
            put("display_name", file.displayName)
            put("mime_type", file.mimeType)
            put("size", file.size)
            put("added_at", file.addedAt)
            put("indexing_status", file.indexingStatus.name)
            put("chunks_count", file.chunksCount)
            if (file.folderId != null) {
                put("folder_id", file.folderId)
            } else {
                putNull("folder_id")
            }
        }
        writableDatabase.insertWithOnConflict(
            TABLE_FILES,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Inserts multiple imported files inside a single atomic database transaction.
     */
    fun insertAll(files: List<ImportedFile>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (file in files) {
                val values = ContentValues().apply {
                    put("uri", file.uri)
                    put("display_name", file.displayName)
                    put("mime_type", file.mimeType)
                    put("size", file.size)
                    put("added_at", file.addedAt)
                    put("indexing_status", file.indexingStatus.name)
                    put("chunks_count", file.chunksCount)
                    if (file.folderId != null) {
                        put("folder_id", file.folderId)
                    } else {
                        putNull("folder_id")
                    }
                }
                db.insertWithOnConflict(
                    TABLE_FILES,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Updates the indexing lifecycle status and optional indexed chunk count for a file.
     */
    fun updateStatus(uri: String, status: IndexingStatus, chunksCount: Int? = null) {
        val values = ContentValues().apply {
            put("indexing_status", status.name)
            if (chunksCount != null) {
                put("chunks_count", chunksCount)
            }
        }
        writableDatabase.update(TABLE_FILES, values, "uri = ?", arrayOf(uri))
    }

    /**
     * Marks all imported files as [IndexingStatus.PENDING].
     * Used when the user initiates "Re-index all" from Settings.
     */
    fun markAllPending() {
        val values = ContentValues().apply {
            put("indexing_status", IndexingStatus.PENDING.name)
        }
        writableDatabase.update(TABLE_FILES, values, null, null)
    }

    /**
     * Retrieves all files that are pending or were interrupted during indexing.
     * Ordered by [ImportedFile.addedAt] ascending so earlier imports are indexed first.
     */
    fun getPendingFiles(): List<ImportedFile> {
        val list = mutableListOf<ImportedFile>()
        val cursor = readableDatabase.query(
            TABLE_FILES,
            arrayOf("uri", "display_name", "mime_type", "size", "added_at", "indexing_status", "chunks_count", "folder_id"),
            "indexing_status IN (?, ?)",
            arrayOf(IndexingStatus.PENDING.name, IndexingStatus.INDEXING.name),
            null, null,
            "added_at ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val statusStr = if (it.isNull(5)) null else it.getString(5)
                val status = try {
                    if (statusStr != null) IndexingStatus.valueOf(statusStr) else IndexingStatus.PENDING
                } catch (_: Exception) {
                    IndexingStatus.PENDING
                }
                list.add(
                    ImportedFile(
                        uri = it.getString(0),
                        displayName = it.getString(1),
                        mimeType = if (it.isNull(2)) null else it.getString(2),
                        size = it.getLong(3),
                        addedAt = it.getLong(4),
                        indexingStatus = status,
                        chunksCount = if (it.isNull(6)) 0 else it.getInt(6),
                        folderId = if (it.isNull(7)) null else it.getString(7)
                    )
                )
            }
        }
        return list
    }

    /**
     * Retrieves all imported files ordered by most recently added first.
     */
    fun getAll(): List<ImportedFile> {
        val list = mutableListOf<ImportedFile>()
        val cursor = readableDatabase.query(
            TABLE_FILES,
            arrayOf("uri", "display_name", "mime_type", "size", "added_at", "indexing_status", "chunks_count", "folder_id"),
            null, null, null, null,
            "added_at DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val statusStr = if (it.isNull(5)) null else it.getString(5)
                val status = try {
                    if (statusStr != null) IndexingStatus.valueOf(statusStr) else IndexingStatus.INDEXED
                } catch (_: Exception) {
                    IndexingStatus.INDEXED
                }
                list.add(
                    ImportedFile(
                        uri = it.getString(0),
                        displayName = it.getString(1),
                        mimeType = if (it.isNull(2)) null else it.getString(2),
                        size = it.getLong(3),
                        addedAt = it.getLong(4),
                        indexingStatus = status,
                        chunksCount = if (it.isNull(6)) 0 else it.getInt(6),
                        folderId = if (it.isNull(7)) null else it.getString(7)
                    )
                )
            }
        }
        return list
    }

    /**
     * Retrieves a single imported file by its URI, or null if not found.
     */
    fun get(uri: String): ImportedFile? {
        val cursor = readableDatabase.query(
            TABLE_FILES,
            arrayOf("uri", "display_name", "mime_type", "size", "added_at", "indexing_status", "chunks_count", "folder_id"),
            "uri = ?",
            arrayOf(uri),
            null, null, null
        )
        cursor.use {
            if (it.moveToNext()) {
                val statusStr = if (it.isNull(5)) null else it.getString(5)
                val status = try {
                    if (statusStr != null) IndexingStatus.valueOf(statusStr) else IndexingStatus.INDEXED
                } catch (_: Exception) {
                    IndexingStatus.INDEXED
                }
                return ImportedFile(
                    uri = it.getString(0),
                    displayName = it.getString(1),
                    mimeType = if (it.isNull(2)) null else it.getString(2),
                    size = it.getLong(3),
                    addedAt = it.getLong(4),
                    indexingStatus = status,
                    chunksCount = if (it.isNull(6)) 0 else it.getInt(6),
                    folderId = if (it.isNull(7)) null else it.getString(7)
                )
            }
        }
        return null
    }

    /**
     * Deletes an imported file record by its URI.
     *
     * @return True if a row was deleted, false otherwise.
     */
    fun delete(uri: String): Boolean {
        return writableDatabase.delete(TABLE_FILES, "uri = ?", arrayOf(uri)) > 0
    }

    /**
     * Returns the total count of imported files currently in the database.
     */
    fun count(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_FILES", null)
        cursor.use {
            if (it.moveToNext()) return it.getInt(0)
        }
        return 0
    }

    /**
     * Clears all imported file and folder records.
     */
    fun deleteAll() {
        writableDatabase.delete(TABLE_FILES, null, null)
        writableDatabase.delete(TABLE_FOLDERS, null, null)
    }
}
