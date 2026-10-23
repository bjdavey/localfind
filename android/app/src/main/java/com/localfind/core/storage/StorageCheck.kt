package com.localfind.core.storage

/**
 * Pure rules for "is there enough free storage" and "did this error mean the disk is full".
 * The platform part (reading the free bytes) stays in the Android adapters.
 */
object StorageCheck {
    /**
     * Headroom to keep free while indexing: the vector database grows, SQLite needs room for its journal,
     * and extractors write temporary files. Starting below this would most likely fail mid-file.
     */
    const val INDEXING_RESERVE_BYTES = 100L * 1024 * 1024

    /** True when [freeBytes] covers [neededBytes] plus the [reserveBytes] headroom. */
    fun hasEnoughSpace(
        freeBytes: Long,
        neededBytes: Long = 0L,
        reserveBytes: Long = INDEXING_RESERVE_BYTES
    ): Boolean = freeBytes >= neededBytes + reserveBytes

    /**
     * Recognises a full-disk failure from an error message. Errors often reach the worker only as text
     * (the indexer wraps exceptions into [com.localfind.core.model.IndexResult.errorMessage]), so this matches:
     * SQLite's `SQLiteFullException` ("database or disk is full") and the OS `ENOSPC` ("No space left on device").
     */
    fun isOutOfSpaceMessage(message: String?): Boolean {
        if (message == null) return false
        return message.contains("no space left", ignoreCase = true) ||
            message.contains("disk is full", ignoreCase = true) ||
            message.contains("enospc", ignoreCase = true)
    }
}
