package com.localfind.core.indexing

/**
 * Limit for files whose indexing keeps being interrupted abnormally (app crash or process kill while the
 * file was being indexed). Without a limit, a file that crashes the indexer would be retried, and crash
 * the app, on every start.
 */
object IndexingAttempts {
    /** After this many abnormal interruptions in a row the file is marked as failed. */
    const val MAX_INTERRUPTED_ATTEMPTS = 3

    /** Whether a file that was interrupted [interruptedAttempts] times should no longer be retried. */
    fun shouldGiveUp(interruptedAttempts: Int): Boolean = interruptedAttempts >= MAX_INTERRUPTED_ATTEMPTS
}
