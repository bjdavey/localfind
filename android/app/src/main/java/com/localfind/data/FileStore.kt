package com.localfind.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import com.localfind.index.Indexer
import java.util.Locale

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
    val chunksCount: Int = 0
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
    fun importUri(contentResolver: ContentResolver, uri: Uri): ImportedFile? {
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
            size = size
        )
    }
}

/**
 * SQLite-backed local store for managing imported files and persistent URIs.
 *
 * Uses Android's built-in [SQLiteOpenHelper] with zero external dependencies to persist:
 * - URI (Primary Key)
 * - Display Name
 * - MIME Type
 * - File Size
 * - Import Timestamp
 */
class FileStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "localfind_files.db"
        const val DATABASE_VERSION = 2
        const val TABLE_FILES = "imported_files"
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
                chunks_count INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_FILES ADD COLUMN indexing_status TEXT NOT NULL DEFAULT 'INDEXED'")
                db.execSQL("ALTER TABLE $TABLE_FILES ADD COLUMN chunks_count INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) {
                // Ignore if already added
            }
        }
    }

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
            arrayOf("uri", "display_name", "mime_type", "size", "added_at", "indexing_status", "chunks_count"),
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
                        chunksCount = if (it.isNull(6)) 0 else it.getInt(6)
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
            arrayOf("uri", "display_name", "mime_type", "size", "added_at", "indexing_status", "chunks_count"),
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
                        chunksCount = if (it.isNull(6)) 0 else it.getInt(6)
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
            arrayOf("uri", "display_name", "mime_type", "size", "added_at", "indexing_status", "chunks_count"),
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
                    chunksCount = if (it.isNull(6)) 0 else it.getInt(6)
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
     * Clears all imported file records.
     */
    fun deleteAll() {
        writableDatabase.delete(TABLE_FILES, null, null)
    }
}
