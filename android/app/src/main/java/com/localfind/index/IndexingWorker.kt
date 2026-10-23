package com.localfind.index

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.localfind.data.EngineManager
import com.localfind.data.FileStore
import com.localfind.core.model.ImportedFile
import com.localfind.core.model.IndexingStatus
import com.localfind.core.model.ModelStatus
import com.localfind.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android [WorkManager] [CoroutineWorker] for offline background file indexing.
 *
 * Architecture and Resilience:
 * - Survives App Killing: Scheduled via WorkManager, persisting work execution in Android's
 *   system work database. If the user kills the app process or the OS reclaims resources,
 *   WorkManager resumes unindexed files when restarted.
 * - Idempotency & Resumption: Reads pending files from [FileStore.getPendingFiles].
 *   Completed files are already marked [IndexingStatus.INDEXED] in SQLite and skipped,
 *   while interrupted or newly queued files are picked up and indexed seamlessly.
 * - Concurrency Control: Coordinates through [EngineManager.withIndexer], ensuring
 *   only one 485MB EmbeddingGemma 2 model is loaded and synchronized with search queries.
 * - Live Progress: Emits chunk-level progress to [IndexingProgressState] for the UI.
 */
class IndexingWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        /** Log tag for the indexing pipeline. */
        const val TAG_INDEXING = "localfind_indexing"
    }

    /**
     * Builds a [ForegroundInfo] instance wrapping the indexing progress notification.
     */
    fun createForegroundInfo(
        message: String,
        progressFraction: Float,
        fileIndex: Int,
        totalFiles: Int
    ): ForegroundInfo {
        val notification = IndexingNotifications.build(
            context = context,
            message = message,
            progressFraction = progressFraction,
            fileIndex = fileIndex,
            totalFiles = totalFiles,
            isPaused = false
        )
        return ForegroundInfo(
            IndexingNotifications.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(
            message = "Analyzing files for local search...",
            progressFraction = 0f,
            fileIndex = 0,
            totalFiles = 0
        )
    }

    private var lastNotificationTime = 0L

    /**
     * Updates the persistent ongoing notification and keeps the WorkManager Foreground Service
     * actively refreshed with Android's system notification service.
     */
    private fun updateNotification(
        message: String,
        progressFraction: Float,
        fileIndex: Int,
        totalFiles: Int,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastNotificationTime < 500L)) {
            return
        }
        lastNotificationTime = now
        try {
            val info = createForegroundInfo(message, progressFraction, fileIndex, totalFiles)
            try {
                // Keep foreground service active and update ongoing notification via WorkManager
                setForegroundAsync(info)
            } catch (_: Exception) {
                // If setForegroundAsync is unavailable or throws, fallback to NotificationManager
                val notificationManager = IndexingNotifications.manager(context)
                notificationManager?.notify(IndexingNotifications.NOTIFICATION_ID, info.notification)
            }
        } catch (e: Exception) {
            Log.w(TAG_INDEXING, "Failed to update notification: ${e.message}")
        }
    }

    /** Publishes the "Paused (n pending)" banner for the files still waiting in the queue. */
    private fun publishPaused(fileStore: FileStore) {
        val remaining = fileStore.getPendingFiles()
        val msg = if (remaining.isNotEmpty()) "Paused (${remaining.size} pending)" else "Paused"
        IndexingProgressState.setPaused(msg)
    }

    /** Gives up on [uri]: forgets its resume checkpoint, marks it FAILED and drops its partial vectors. */
    private suspend fun markFailedAndPurge(fileStore: FileStore, uri: String) {
        IndexingResumeManager.clearResumeChunk(context, uri)
        fileStore.updateStatus(uri, IndexingStatus.FAILED, 0)
        try {
            EngineManager.withStore(context) { it.deleteByFileUri(uri) }
        } catch (_: Exception) {}
    }

    /** Puts [file] back in the queue at its saved resume chunk and returns that chunk (for logging). */
    private fun revertToPending(fileStore: FileStore, file: ImportedFile): Int {
        val resumeChunk = IndexingResumeManager.getResumeChunk(context, file.uri, file.size)
        fileStore.updateStatus(file.uri, IndexingStatus.PENDING, resumeChunk)
        return resumeChunk
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val fileStore = FileStore.getInstance(context)
        val settingsStore = SettingsStore(context)
        val settings = settingsStore.load()

        try {
            // Check if the indexing queue is currently paused
            if (IndexingQueue.isPaused(context)) {
                publishPaused(fileStore)
                return@withContext Result.success()
            }

            // Ensure model is loaded; if missing or failed, pause indexing and retry later rather than failing files
            val modelStatus = EngineManager.ensureModelLoaded(context)
            if (modelStatus is ModelStatus.Error) {
                Log.w(TAG_INDEXING, "Indexing paused: ${modelStatus.message}. WorkManager will retry when model is loaded.")
                IndexingProgressState.update(
                    isIndexing = false,
                    currentUri = null,
                    currentFileName = null,
                    fileIndex = 0,
                    totalFiles = 0,
                    currentChunk = 0,
                    progressFraction = 0f,
                    message = "Paused: Model not loaded"
                )
                return@withContext Result.retry()
            }

            // Elevate to Foreground Service with ongoing notification so Android keeps indexing alive in background
            val initialPending = fileStore.getPendingFiles()
            if (initialPending.isNotEmpty()) {
                try {
                    setForeground(getForegroundInfo())
                } catch (e: Exception) {
                    Log.w(TAG_INDEXING, "Unable to run as foreground service: ${e.message}")
                }
            }

            // Process batches until no pending or interrupted files remain in FileStore
            while (true) {
                if (isStopped || IndexingCancellationState.isAllCancelled() || IndexingQueue.isPaused(context)) {
                    if (IndexingQueue.isPaused(context)) {
                        publishPaused(fileStore)
                        return@withContext Result.success()
                    }
                    IndexingProgressState.reset()
                    return@withContext if (IndexingCancellationState.isAllCancelled()) Result.success() else Result.retry()
                }

                val pending = fileStore.getPendingFiles()
                if (pending.isEmpty()) {
                    break
                }

                val totalInBatch = pending.size

                for ((idx, file) in pending.withIndex()) {
                    if (isStopped || IndexingCancellationState.isAllCancelled() || IndexingQueue.isPaused(context)) {
                        if (IndexingQueue.isPaused(context)) {
                            fileStore.updateStatus(file.uri, IndexingStatus.PENDING)
                            publishPaused(fileStore)
                            return@withContext Result.success()
                        }
                        if (!IndexingCancellationState.isAllCancelled()) {
                            fileStore.updateStatus(file.uri, IndexingStatus.PENDING)
                        }
                        IndexingProgressState.reset()
                        return@withContext if (IndexingCancellationState.isAllCancelled()) Result.success() else Result.retry()
                    }

                    // If file was already cancelled or marked failed, skip it
                    val currentStatus = fileStore.get(file.uri)?.indexingStatus
                    if (currentStatus == IndexingStatus.FAILED || IndexingCancellationState.isCancelled(file.uri)) {
                        IndexingCancellationState.clear(file.uri)
                        continue
                    }

                    val fileIndex = idx + 1
                    fileStore.updateStatus(file.uri, IndexingStatus.INDEXING)

                    val startMessage = "Analyzing $fileIndex/$totalInBatch: ${file.displayName}..."
                    IndexingProgressState.update(
                        isIndexing = true,
                        currentUri = file.uri,
                        currentFileName = file.displayName,
                        fileIndex = fileIndex,
                        totalFiles = totalInBatch,
                        currentChunk = 0,
                        progressFraction = 0f,
                        message = startMessage
                    )
                    updateNotification(startMessage, 0f, fileIndex, totalInBatch, force = true)

                    try {
                        val result = EngineManager.withIndexer(context, settings) { indexer ->
                            indexer.indexFile(
                                file = file,
                                isCancelled = {
                                    isStopped ||
                                    IndexingCancellationState.isCancelled(file.uri) ||
                                    IndexingQueue.isPaused(context)
                                },
                                onProgress = { cur, tot ->
                                    if (IndexingQueue.isPaused(context)) return@indexFile
                                    val fraction = if (tot > 0) cur.toFloat() / tot.toFloat() else 0f
                                    val percent = (fraction * 100).toInt()
                                    val activeDelegate = indexer.getActiveEmbedder().effectiveAccelerator.name
                                    val msg = "Analyzing $fileIndex/$totalInBatch: ${file.displayName} ($percent%) • $activeDelegate"
                                    IndexingProgressState.update(
                                        isIndexing = true,
                                        currentUri = file.uri,
                                        currentFileName = file.displayName,
                                        fileIndex = fileIndex,
                                        totalFiles = totalInBatch,
                                        currentChunk = cur,
                                        progressFraction = fraction,
                                        message = msg
                                    )
                                    updateNotification(msg, fraction, fileIndex, totalInBatch)
                                }
                            )
                        }

                        // Explicit user pause during file indexing
                        if (IndexingQueue.isPaused(context)) {
                            val resumeChunk = revertToPending(fileStore, file)
                            Log.i(TAG_INDEXING, "Indexing paused by user while indexing ${file.displayName} at chunk $resumeChunk, reverted to PENDING")
                            publishPaused(fileStore)
                            return@withContext Result.success()
                        }

                        // Explicit user cancellation of this specific file or all files
                        if (IndexingCancellationState.isCancelled(file.uri)) {
                            IndexingCancellationState.clear(file.uri)
                            Log.i(TAG_INDEXING, "Indexing explicitly cancelled for ${file.displayName}")
                            markFailedAndPurge(fileStore, file.uri)
                            if (IndexingCancellationState.isAllCancelled()) {
                                IndexingProgressState.reset()
                                return@withContext Result.success()
                            }
                            continue
                        }

                        // Interruption / worker preemption: revert to PENDING so next session auto-resumes
                        if (isStopped) {
                            // (A user "cancel all" was already handled by the isCancelled branch above)
                            val resumeChunk = revertToPending(fileStore, file)
                            Log.i(TAG_INDEXING, "Worker stopped while indexing ${file.displayName} at chunk $resumeChunk, reverted to PENDING for auto-resume")
                            IndexingProgressState.reset()
                            return@withContext Result.retry()
                        }

                        if (result.isSuccess) {
                            Log.i(TAG_INDEXING, "Indexed ${file.displayName}: ${result.chunksCount} chunks")
                            fileStore.updateStatus(file.uri, IndexingStatus.INDEXED, result.chunksCount)
                            val doneMsg = "Analyzed $fileIndex/$totalInBatch: ${file.displayName}"
                            IndexingProgressState.update(
                                isIndexing = true,
                                currentUri = file.uri,
                                currentFileName = file.displayName,
                                fileIndex = fileIndex,
                                totalFiles = totalInBatch,
                                currentChunk = result.chunksCount,
                                progressFraction = 1f,
                                message = doneMsg
                            )
                            updateNotification(doneMsg, 1f, fileIndex, totalInBatch)
                        } else {
                            // If indexing failed because it was interrupted, cancelled by stop, or engine closed, keep as PENDING
                            if (result.errorMessage?.contains("cancelled", ignoreCase = true) == true ||
                                result.errorMessage?.contains("not initialized", ignoreCase = true) == true
                            ) {
                                // (isAllCancelled implies isCancelled for every URI)
                                if (IndexingCancellationState.isCancelled(file.uri)) {
                                    markFailedAndPurge(fileStore, file.uri)
                                } else {
                                    val resumeChunk = revertToPending(fileStore, file)
                                    Log.i(TAG_INDEXING, "Indexing interrupted for ${file.displayName} (${result.errorMessage}), reverted to PENDING at chunk $resumeChunk")
                                }
                            } else {
                                Log.e(TAG_INDEXING, "Indexing failed for ${file.displayName}: ${result.errorMessage}")
                                markFailedAndPurge(fileStore, file.uri)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG_INDEXING, "Exception indexing ${file.displayName}", e)
                        if (IndexingQueue.isPaused(context)) {
                            revertToPending(fileStore, file)
                            publishPaused(fileStore)
                            return@withContext Result.success()
                        }
                        if (IndexingCancellationState.isCancelled(file.uri)) {
                            IndexingCancellationState.clear(file.uri)
                            markFailedAndPurge(fileStore, file.uri)
                            if (IndexingCancellationState.isAllCancelled()) {
                                IndexingProgressState.reset()
                                return@withContext Result.success()
                            }
                            continue
                        }
                        if (isStopped || e is kotlinx.coroutines.CancellationException || e is InterruptedException) {
                            // (A user "cancel all" was already handled by the isCancelled branch above)
                            val resumeChunk = revertToPending(fileStore, file)
                            Log.i(TAG_INDEXING, "Worker interrupted while indexing ${file.displayName}, reverted to PENDING at chunk $resumeChunk")
                            IndexingProgressState.reset()
                            return@withContext Result.retry()
                        }
                        markFailedAndPurge(fileStore, file.uri)
                    }

                    // Micro-pacing / Frame yielding: brief pause between consecutive files
                    // yields GPU and CPU time slices to SurfaceFlinger and HWUI compositor,
                    // keeping the phone's UI buttery smooth even during bulk image/file indexing.
                    if (idx < pending.lastIndex && !isStopped && !IndexingQueue.isPaused(context)) {
                        kotlinx.coroutines.delay(com.localfind.core.indexing.DEFAULT_MICRO_PACING_DELAY_MS)
                    }
                }
            }

            Result.success()
        } catch (e: Throwable) {
            Log.e(TAG_INDEXING, "Fatal error in IndexingWorker", e)
            Result.failure()
        } finally {
            if (IndexingQueue.isPaused(context)) {
                val remaining = fileStore.getPendingFiles().size
                if (remaining > 0) {
                    val notificationManager = IndexingNotifications.manager(context)
                    notificationManager?.notify(IndexingNotifications.NOTIFICATION_ID, IndexingNotifications.buildPaused(context, remaining))
                }
            } else {
                val notificationManager = IndexingNotifications.manager(context)
                notificationManager?.cancel(IndexingNotifications.NOTIFICATION_ID)
                IndexingProgressState.reset()
            }
            // If worker was stopped by OS preemption (not user cancellation or pause), revert in-flight files from INDEXING to PENDING
            if (isStopped && !IndexingCancellationState.isAllCancelled()) {
                try {
                    val pending = fileStore.getPendingFiles()
                    for (f in pending) {
                        if (f.indexingStatus == IndexingStatus.INDEXING) {
                            val resumeChunk = IndexingResumeManager.getResumeChunk(context, f.uri, f.size)
                            fileStore.updateStatus(f.uri, IndexingStatus.PENDING, resumeChunk)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG_INDEXING, "Error resetting status on stop: ${e.message}")
                }
            }
        }
    }
}

