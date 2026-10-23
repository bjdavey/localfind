package com.localfind.index

import com.localfind.core.indexing.IndexingAttempts
import com.localfind.core.indexing.IndexingResumeLogic
import android.content.Context
import androidx.core.content.edit

/**
 * Persistent manager for tracking and resuming progress within individual file indexing sessions.
 *
 * Problem Solved:
 * When indexing large files (especially multi-minute videos or multi-page PDFs requiring dozens of
 * neural network embeddings), an interruption (e.g. app pause, OS process preemption, device reboot)
 * would previously discard all intermediate embeddings and restart extraction and inference from chunk 0.
 *
 * Architecture and Guarantees:
 * - Granular Resumption: Persists the active chunk/window index per file URI in [SharedPreferences].
 * - Atomicity & Coherence: Pairs each checkpoint with the file size on disk; if the file changes,
 *   the checkpoint is automatically invalidated.
 * - Zero SQLite Contention: Backed by SharedPreferences so checkpoint writes do not compete with
 *   MediaPipe [SqliteVectorStore] transactions.
 * - Clean Cleanup: Checkpoints are wiped on completion ([clearResumeChunk]), cancellation, or re-index.
 */
object IndexingResumeManager {
    const val PREFS_NAME = "localfind_indexing_prefs"
    const val KEY_RESUME_CHUNK_PREFIX = "resume_chunk_"
    const val KEY_RESUME_SIZE_PREFIX = "resume_size_"
    const val KEY_INTERRUPTED_PREFIX = "interrupted_attempts_"

    /**
     * Retrieves the validated chunk index from which indexing should resume for [uri].
     *
     * Returns 0 if no checkpoint exists, if the file size changed, or if the checkpoint is invalid.
     */
    fun getResumeChunk(context: Context, uri: String, currentFileSize: Long, totalChunks: Int = Int.MAX_VALUE): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedChunk = prefs.getInt(KEY_RESUME_CHUNK_PREFIX + uri, 0)
        val savedSize = prefs.getLong(KEY_RESUME_SIZE_PREFIX + uri, -1L)

        val validated = IndexingResumeLogic.calculateResumeStartIndex(
            savedChunk = savedChunk,
            savedSize = savedSize,
            currentSize = currentFileSize,
            totalChunks = totalChunks
        )
        // If checkpoint was invalid or file changed, remove stale entries
        if (validated == 0 && (savedChunk != 0 || savedSize != -1L)) {
            clearResumeChunk(context, uri)
        }
        return validated
    }

    /**
     * Persists the current chunk index checkpoint and file size for [uri].
     *
     * @param context Application context.
     * @param uri Persistable file URI.
     * @param chunkIndex 0-based index of the next chunk to be embedded.
     * @param fileSize Size of the file in bytes at the time of recording.
     */
    fun setResumeChunk(context: Context, uri: String, chunkIndex: Int, fileSize: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putInt(KEY_RESUME_CHUNK_PREFIX + uri, chunkIndex)
            putLong(KEY_RESUME_SIZE_PREFIX + uri, fileSize)
        }
    }

    /**
     * Clears any saved indexing checkpoint for [uri].
     * Called upon successful completion of indexing or explicit cancellation.
     */
    fun clearResumeChunk(context: Context, uri: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            remove(KEY_RESUME_CHUNK_PREFIX + uri)
            remove(KEY_RESUME_SIZE_PREFIX + uri)
        }
    }

    /**
     * Records that indexing of [uri] was interrupted abnormally (the process died while it was running)
     * and returns the number of such interruptions so far. See [IndexingAttempts].
     */
    fun recordInterruptedAttempt(context: Context, uri: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val attempts = prefs.getInt(KEY_INTERRUPTED_PREFIX + uri, 0) + 1
        prefs.edit { putInt(KEY_INTERRUPTED_PREFIX + uri, attempts) }
        return attempts
    }

    /** Forgets the interruption count of [uri] after it indexed successfully, failed or was cancelled. */
    fun clearAttempts(context: Context, uri: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { remove(KEY_INTERRUPTED_PREFIX + uri) }
    }

    /**
     * Clears all saved resume checkpoints across all files.
     * Called when the user initiates "Re-index all" or cancels the entire indexing queue.
     */
    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            for (key in prefs.all.keys) {
                if (key.startsWith(KEY_RESUME_CHUNK_PREFIX) || key.startsWith(KEY_RESUME_SIZE_PREFIX) ||
                    key.startsWith(KEY_INTERRUPTED_PREFIX)
                ) {
                    remove(key)
                }
            }
        }
    }
}

