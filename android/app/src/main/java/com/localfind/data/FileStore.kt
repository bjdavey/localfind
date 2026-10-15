package com.localfind.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

/**
 * Metadata for a file imported via Storage Access Framework.
 */
data class ImportedFile(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val size: Long,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * Formats byte size into human-readable text (e.g. "1.5 MB").
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceIn(0, units.size - 1)
    if (digitGroups == 0) return "$bytes B"
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
}

/**
 * Helper to resolve SAF document URIs and obtain persistable read permission.
 */
object FileImporter {
    fun importUri(contentResolver: ContentResolver, uri: Uri): ImportedFile {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers or temporary URIs may not support persistable permissions
        }

        var displayName: String? = null
        var size: Long = 0L

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
            // Fallback gracefully if query is not supported
        }

        val name = displayName ?: uri.lastPathSegment ?: "unknown"
        val mimeType = try {
            contentResolver.getType(uri)
        } catch (_: Exception) {
            null
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
 * SQLite-backed local store for imported file metadata and URIs.
 */
class FileStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "localfind_files.db"
        const val DATABASE_VERSION = 1
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
                added_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No-op for v1
    }

    fun insert(file: ImportedFile) {
        val values = ContentValues().apply {
            put("uri", file.uri)
            put("display_name", file.displayName)
            put("mime_type", file.mimeType)
            put("size", file.size)
            put("added_at", file.addedAt)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_FILES,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

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

    fun getAll(): List<ImportedFile> {
        val list = mutableListOf<ImportedFile>()
        val cursor = readableDatabase.query(
            TABLE_FILES,
            arrayOf("uri", "display_name", "mime_type", "size", "added_at"),
            null, null, null, null,
            "added_at DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ImportedFile(
                        uri = it.getString(0),
                        displayName = it.getString(1),
                        mimeType = if (it.isNull(2)) null else it.getString(2),
                        size = it.getLong(3),
                        addedAt = it.getLong(4)
                    )
                )
            }
        }
        return list
    }

    fun get(uri: String): ImportedFile? {
        val cursor = readableDatabase.query(
            TABLE_FILES,
            arrayOf("uri", "display_name", "mime_type", "size", "added_at"),
            "uri = ?",
            arrayOf(uri),
            null, null, null
        )
        cursor.use {
            if (it.moveToNext()) {
                return ImportedFile(
                    uri = it.getString(0),
                    displayName = it.getString(1),
                    mimeType = if (it.isNull(2)) null else it.getString(2),
                    size = it.getLong(3),
                    addedAt = it.getLong(4)
                )
            }
        }
        return null
    }

    fun delete(uri: String): Boolean {
        return writableDatabase.delete(TABLE_FILES, "uri = ?", arrayOf(uri)) > 0
    }

    fun count(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_FILES", null)
        cursor.use {
            if (it.moveToNext()) return it.getInt(0)
        }
        return 0
    }

    fun deleteAll() {
        writableDatabase.delete(TABLE_FILES, null, null)
    }
}

