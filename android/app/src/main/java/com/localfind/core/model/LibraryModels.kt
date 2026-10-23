package com.localfind.core.model

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

/** Background indexing lifecycle of an imported file. */
enum class IndexingStatus {
    PENDING,
    INDEXING,
    INDEXED,
    FAILED
}

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
