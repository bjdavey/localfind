package com.localfind.index

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.localfind.core.model.IndexingStatus
import com.localfind.data.EngineManager
import com.localfind.data.FileStore

/**
 * Control surface for the background indexing queue: enqueue, pause, resume and cancel work,
 * and query the user's pause flag. The work itself runs in [IndexingWorker].
 */
object IndexingQueue {
    const val WORK_NAME = "localfind_background_indexing"
    private const val TAG = "IndexingQueue"

    private const val PREFS_NAME = IndexingResumeManager.PREFS_NAME
    const val KEY_IS_PAUSED = "key_is_indexing_paused"

    /**
     * Checks whether background indexing is currently paused by the user.
     */
    fun isPaused(context: Context): Boolean {
        if (IndexingCancellationState.isPaused()) return true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_IS_PAUSED, false)
    }

    /**
     * Persists paused preference flag to survive app process recreation.
     */
    fun setPausedPref(context: Context, paused: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_IS_PAUSED, paused) }
    }

    /**
     * Flips the pause flag in both places that [isPaused] reads: the persisted preference
     * (survives process death) and the in-memory flag (cheap check inside indexing loops).
     */
    private fun setPausedFlags(context: Context, paused: Boolean) {
        setPausedPref(context, paused)
        if (paused) IndexingCancellationState.pause() else IndexingCancellationState.resume()
    }


    /**
     * Enqueues or resumes background indexing.
     *
     * @param context Application context.
     * @param replace If true, replaces any existing/completed unique work with a fresh worker.
     *                If false (default), uses [ExistingWorkPolicy.APPEND_OR_REPLACE] so that any
     *                actively executing worker continues uninterrupted while newly imported
     *                files are picked up and indexed seamlessly.
     */
    fun enqueue(context: Context, replace: Boolean = false) {
        // Reset pause and global cancellation before enqueueing new work
        setPausedFlags(context, false)
        IndexingCancellationState.resetAllCancelled()
        val request = OneTimeWorkRequestBuilder<IndexingWorker>().build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    /**
     * Pauses the background indexing queue.
     * Signals running workers to stop chunk extraction/embedding cooperatively,
     * reverts any in-flight file from INDEXING to PENDING so it can be resumed later,
     * cancels the active WorkManager work, updates [IndexingProgressState], and displays
     * a paused notification with a Resume action.
     */
    fun pause(context: Context) {
        setPausedFlags(context, true)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)

        val fileStore = FileStore.getInstance(context)
        val pending = fileStore.getPendingFiles()
        for (f in pending) {
            if (f.indexingStatus == IndexingStatus.INDEXING) {
                val resumeChunk = IndexingResumeManager.getResumeChunk(context, f.uri, f.size)
                fileStore.updateStatus(f.uri, IndexingStatus.PENDING, resumeChunk)
            }
        }
        val remainingCount = fileStore.getPendingFiles().size
        val notificationManager = IndexingNotifications.manager(context)
        if (remainingCount > 0) {
            val msg = "Paused ($remainingCount pending)"
            IndexingProgressState.setPaused(message = msg)
            notificationManager?.notify(IndexingNotifications.NOTIFICATION_ID, IndexingNotifications.buildPaused(context, remainingCount))
        } else {
            IndexingProgressState.reset()
            notificationManager?.cancel(IndexingNotifications.NOTIFICATION_ID)
        }
    }

    /**
     * Resumes the background indexing queue.
     * Resets paused states, updates UI progress banner to resuming, and enqueues
     * a fresh WorkManager worker to pick up all remaining PENDING files.
     */
    fun resume(context: Context) {
        setPausedFlags(context, false)
        IndexingCancellationState.resetAllCancelled()
        val fileStore = FileStore.getInstance(context)
        val pending = fileStore.getPendingFiles()
        if (pending.isEmpty()) {
            IndexingProgressState.reset()
            return
        }
        val msg = "Resuming indexing (${pending.size} pending)..."
        IndexingProgressState.update(
            isIndexing = true,
            currentUri = pending.firstOrNull()?.uri,
            currentFileName = pending.firstOrNull()?.displayName,
            fileIndex = 0,
            totalFiles = pending.size,
            currentChunk = 0,
            progressFraction = 0f,
            message = msg
        )
        enqueue(context, replace = true)
    }

    /**
     * Cancels any running or enqueued indexing work.
     * Signals immediate cooperative cancellation to running workers, clears resume checkpoints,
     * purges partial embeddings, and marks all pending or active indexing files as FAILED.
     */
    fun cancel(context: Context) {
        setPausedPref(context, false)
        IndexingCancellationState.cancelAll()
        IndexingResumeManager.clearAll(context)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        IndexingProgressState.reset()
        val notificationManager = IndexingNotifications.manager(context)
        notificationManager?.cancel(IndexingNotifications.NOTIFICATION_ID)
        val fileStore = FileStore.getInstance(context)
        val pending = fileStore.getPendingFiles()
        for (f in pending) {
            fileStore.updateStatus(f.uri, IndexingStatus.FAILED, 0)
        }
        try {
            val store = EngineManager.getStore(context)
            for (f in pending) {
                store.deleteByFileUri(f.uri)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete chunks for cancelled files: ${e.message}")
        }
    }

    /**
     * Cancels indexing for a specific file.
     * Signals the active worker (if currently processing this file) to abort,
     * clears its resume checkpoint, removes any partial embeddings, and marks it as FAILED.
     */
    fun cancelFile(context: Context, uri: String) {
        IndexingCancellationState.cancelFile(uri)
        IndexingResumeManager.clearResumeChunk(context, uri)
        val fileStore = FileStore.getInstance(context)
        fileStore.updateStatus(uri, IndexingStatus.FAILED, 0)
        try {
            val store = EngineManager.getStore(context)
            store.deleteByFileUri(uri)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete chunks for cancelled file $uri: ${e.message}")
        }
        if (IndexingProgressState.progress.value.currentUri == uri) {
            IndexingProgressState.reset()
        }
    }
}
