package com.localfind.core.model

/** Error message of an [IndexResult] that stopped because the user cancelled or paused indexing. */
const val INDEXING_CANCELLED_MESSAGE = "Indexing cancelled"

/**
 * Result report from indexing a file.
 *
 * @property chunksCount Number of chunks extracted, embedded, and saved.
 * @property isSuccess Whether indexing succeeded without errors.
 * @property errorMessage Descriptive error message if indexing failed.
 */
data class IndexResult(
    val chunksCount: Int,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)
