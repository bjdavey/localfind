package com.localfind.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.getIntOrNull
import androidx.core.database.getStringOrNull
import androidx.core.database.sqlite.transaction
import java.util.Locale
import java.util.UUID
import kotlin.math.log10
import kotlin.math.pow

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
    // Calculate the base-1024 scale index (0 = B, 1 = KB, 2 = MB, 3 = GB, 4 = TB)
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        .coerceIn(0, units.size - 1)
    if (digitGroups == 0) return "$bytes B"
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
}

/**
 * SQLite-backed local store for managing imported files, persistent URIs, and folder hierarchies.
 *
 * Uses Android's built-in [SQLiteOpenHelper] with zero external dependencies to persist:
 * - Files: URI (PK), Display Name, MIME Type, File Size, Added Timestamp, Status, Chunks, Folder ID
 * - Folders: ID (PK), Name, Parent Folder ID, Created Timestamp
 *
 * Concurrency: Obtain the process-wide singleton via [getInstance] so all threads share SQLite's
 * internal connection pool safely without leaking database file descriptors.
 */
class FileStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        const val DATABASE_NAME = "localfind_files.db"
        const val DATABASE_VERSION = 1
        const val TABLE_FILES = "imported_files"
        const val TABLE_FOLDERS = "folders"

        private val FOLDER_COLUMNS = arrayOf("id", "name", "parent_id", "created_at")
        private val FILE_COLUMNS = arrayOf(
            "uri",
            "display_name",
            "mime_type",
            "size",
            "added_at",
            "indexing_status",
            "chunks_count",
            "folder_id"
        )

        @Volatile
        private var instance: FileStore? = null

        /**
         * Returns the process-wide singleton [FileStore] instance backed by `applicationContext`.
         */
        fun getInstance(context: Context): FileStore {
            return instance ?: synchronized(this) {
                instance ?: FileStore(context.applicationContext).also { instance = it }
            }
        }
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
    fun createFolder(name: String, parentId: String? = null): Folder {
        val folder = Folder(id = UUID.randomUUID().toString(), name = name.trim(), parentId = parentId)
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
        readableDatabase.query(
            TABLE_FOLDERS,
            FOLDER_COLUMNS,
            selection,
            selectionArgs,
            null, null, null
        ).use { cursor ->
            if (cursor.moveToNext()) {
                return cursor.toFolder()
            }
        }
        return createFolder(trimmed, parentId)
    }

    /**
     * Retrieves all folders across the entire library, ordered alphabetically by name.
     */
    fun getAllFolders(): List<Folder> {
        val list = mutableListOf<Folder>()
        readableDatabase.query(
            TABLE_FOLDERS,
            FOLDER_COLUMNS,
            null, null, null, null,
            "name ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toFolder())
            }
        }
        return list
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
        writableDatabase.transaction {
            // Find all subfolder IDs recursively using breadth-first traversal
            val allFolderIds = mutableListOf(id)
            var i = 0
            while (i < allFolderIds.size) {
                val currentId = allFolderIds[i]
                query(TABLE_FOLDERS, arrayOf("id"), "parent_id = ?", arrayOf(currentId), null, null, null).use { subCursor ->
                    while (subCursor.moveToNext()) {
                        allFolderIds.add(subCursor.getString(0))
                    }
                }
                i++
            }

            for (folderId in allFolderIds) {
                query(TABLE_FILES, arrayOf("uri"), "folder_id = ?", arrayOf(folderId), null, null, null).use { fileCursor ->
                    while (fileCursor.moveToNext()) {
                        deletedUris.add(fileCursor.getString(0))
                    }
                }
                delete(TABLE_FILES, "folder_id = ?", arrayOf(folderId))
                delete(TABLE_FOLDERS, "id = ?", arrayOf(folderId))
            }
        }
        return deletedUris
    }

    /**
     * Reassigns multiple files' containing folder in a single atomic database transaction.
     * Passing `null` moves the files to library root.
     */
    fun updateFilesFolder(uris: List<String>, folderId: String?) {
        if (uris.isEmpty()) return
        writableDatabase.transaction {
            val values = ContentValues().apply {
                if (folderId != null) {
                    put("folder_id", folderId)
                } else {
                    putNull("folder_id")
                }
            }
            for (uri in uris) {
                update(TABLE_FILES, values, "uri = ?", arrayOf(uri))
            }
        }
    }

    // =========================================================================
    // FILE OPERATIONS
    // =========================================================================

    /**
     * Inserts multiple imported files inside a single atomic database transaction.
     */
    fun insertAll(files: List<ImportedFile>) {
        writableDatabase.transaction {
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
                insertWithOnConflict(
                    TABLE_FILES,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
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
     * Marks all failed files as [IndexingStatus.PENDING].
     * Used when the user initiates "Retry All" from the failed indexing banner.
     */
    fun markFailedAsPending() {
        val values = ContentValues().apply {
            put("indexing_status", IndexingStatus.PENDING.name)
        }
        writableDatabase.update(TABLE_FILES, values, "indexing_status = ?", arrayOf(IndexingStatus.FAILED.name))
    }

    /**
     * Retrieves all files that are pending or were interrupted during indexing.
     * Ordered by [ImportedFile.addedAt] ascending so earlier imports are indexed first.
     */
    fun getPendingFiles(): List<ImportedFile> {
        val list = mutableListOf<ImportedFile>()
        readableDatabase.query(
            TABLE_FILES,
            FILE_COLUMNS,
            "indexing_status IN (?, ?)",
            arrayOf(IndexingStatus.PENDING.name, IndexingStatus.INDEXING.name),
            null, null,
            "added_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toImportedFile(defaultStatus = IndexingStatus.PENDING))
            }
        }
        return list
    }

    /**
     * Retrieves all imported files ordered by most recently added first.
     */
    fun getAll(): List<ImportedFile> {
        val list = mutableListOf<ImportedFile>()
        readableDatabase.query(
            TABLE_FILES,
            FILE_COLUMNS,
            null, null, null, null,
            "added_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toImportedFile(defaultStatus = IndexingStatus.INDEXED))
            }
        }
        return list
    }

    /**
     * Retrieves a single imported file by its URI, or null if not found.
     */
    fun get(uri: String): ImportedFile? {
        readableDatabase.query(
            TABLE_FILES,
            FILE_COLUMNS,
            "uri = ?",
            arrayOf(uri),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToNext()) {
                return cursor.toImportedFile(defaultStatus = IndexingStatus.INDEXED)
            }
        }
        return null
    }

    /**
     * Deletes an imported file record by its URI.
     */
    fun delete(uri: String) = deleteFiles(listOf(uri))

    /**
     * Deletes multiple imported file records by their URIs inside an atomic transaction.
     */
    fun deleteFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        writableDatabase.transaction {
            for (uri in uris) {
                delete(TABLE_FILES, "uri = ?", arrayOf(uri))
            }
        }
    }

    private fun Cursor.toFolder(): Folder = Folder(
        id = getString(0),
        name = getString(1),
        parentId = getStringOrNull(2),
        createdAt = getLong(3)
    )

    private fun Cursor.toImportedFile(defaultStatus: IndexingStatus): ImportedFile {
        val statusStr = getStringOrNull(5)
        val status = try {
            if (statusStr != null) IndexingStatus.valueOf(statusStr) else defaultStatus
        } catch (_: Exception) {
            defaultStatus
        }
        return ImportedFile(
            uri = getString(0),
            displayName = getString(1),
            mimeType = getStringOrNull(2),
            size = getLong(3),
            addedAt = getLong(4),
            indexingStatus = status,
            chunksCount = getIntOrNull(6) ?: 0,
            folderId = getStringOrNull(7)
        )
    }
}
