package com.localfind.index

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.localfind.MainActivity
import com.localfind.R
import com.localfind.data.EngineManager
import com.localfind.data.FileStore
import com.localfind.data.IndexingStatus
import com.localfind.data.ModelStatus
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
 * - Live Progress: Emits chunk-level progress to [IndexingProgressState] and WorkManager progress.
 */
class IndexingWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "localfind_background_indexing"
        const val TAG_INDEXING = "localfind_indexing"
        const val NOTIFICATION_CHANNEL_ID = "localfind_indexing_channel"
        const val NOTIFICATION_ID = 1001

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
            setPausedPref(context, false)
            IndexingCancellationState.resume()
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
            setPausedPref(context, true)
            IndexingCancellationState.pause()
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
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (remainingCount > 0) {
                val msg = "Paused ($remainingCount pending)"
                IndexingProgressState.setPaused(message = msg)
                notificationManager?.notify(NOTIFICATION_ID, buildPausedNotification(context, remainingCount))
            } else {
                IndexingProgressState.reset()
                notificationManager?.cancel(NOTIFICATION_ID)
            }
        }

        /**
         * Resumes the background indexing queue.
         * Resets paused states, updates UI progress banner to resuming, and enqueues
         * a fresh WorkManager worker to pick up all remaining PENDING files.
         */
        fun resume(context: Context) {
            setPausedPref(context, false)
            IndexingCancellationState.resume()
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
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NOTIFICATION_ID)
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
                Log.w(TAG_INDEXING, "Failed to delete chunks for cancelled files: ${e.message}")
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
                Log.w(TAG_INDEXING, "Failed to delete chunks for cancelled file $uri: ${e.message}")
            }
            if (IndexingProgressState.progress.value.currentUri == uri) {
                IndexingProgressState.reset()
            }
        }

        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "File Analysis",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while LocalFind analyzes files in the background"
                setShowBadge(false)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }

        fun buildNotification(
            context: Context,
            message: String,
            progressFraction: Float,
            fileIndex: Int,
            totalFiles: Int,
            isPaused: Boolean
        ): Notification {
            createNotificationChannel(context)

            val title = if (isPaused) {
                "Paused"
            } else if (totalFiles > 0) {
                "Analyzing files ($fileIndex/$totalFiles)"
            } else {
                "Analyzing files"
            }

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val maxProgress = 100
            val currentProgress = (progressFraction * 100).toInt().coerceIn(0, 100)
            val isIndeterminate = progressFraction <= 0f && totalFiles <= 0 && !isPaused

            val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_search)
                .setOngoing(!isPaused)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .setProgress(maxProgress, currentProgress, isIndeterminate)

            // Add Pause/Resume and Cancel notification action buttons
            if (isPaused) {
                val resumeIntent = Intent(context, IndexingActionReceiver::class.java).apply {
                    action = IndexingActionReceiver.ACTION_RESUME
                }
                val resumePending = PendingIntent.getBroadcast(
                    context,
                    1,
                    resumeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePending)
            } else {
                val pauseIntent = Intent(context, IndexingActionReceiver::class.java).apply {
                    action = IndexingActionReceiver.ACTION_PAUSE
                }
                val pausePending = PendingIntent.getBroadcast(
                    context,
                    2,
                    pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePending)
            }

            val cancelIntent = Intent(context, IndexingActionReceiver::class.java).apply {
                action = IndexingActionReceiver.ACTION_CANCEL
            }
            val cancelPending = PendingIntent.getBroadcast(
                context,
                3,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending)

            return builder.build()
        }

        fun buildPausedNotification(context: Context, remainingCount: Int): Notification {
            return buildNotification(
                context = context,
                message = "Paused: $remainingCount file(s) waiting in queue",
                progressFraction = 0f,
                fileIndex = 0,
                totalFiles = remainingCount,
                isPaused = true
            )
        }
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
        val notification = buildNotification(
            context = context,
            message = message,
            progressFraction = progressFraction,
            fileIndex = fileIndex,
            totalFiles = totalFiles,
            isPaused = false
        )
        return ForegroundInfo(
            NOTIFICATION_ID,
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
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.notify(NOTIFICATION_ID, info.notification)
            }
        } catch (e: Exception) {
            Log.w(TAG_INDEXING, "Failed to update notification: ${e.message}")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val fileStore = FileStore.getInstance(context)
        val settingsStore = SettingsStore(context)
        val settings = settingsStore.load()

        try {
            // Check if the indexing queue is currently paused
            if (isPaused(context)) {
                val pending = fileStore.getPendingFiles()
                val msg = if (pending.isNotEmpty()) "Paused (${pending.size} pending)" else "Paused"
                IndexingProgressState.setPaused(msg)
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
                if (isStopped || IndexingCancellationState.isAllCancelled() || isPaused(context)) {
                    if (isPaused(context)) {
                        val pending = fileStore.getPendingFiles()
                        val msg = if (pending.isNotEmpty()) "Paused (${pending.size} pending)" else "Paused"
                        IndexingProgressState.setPaused(msg)
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
                    if (isStopped || IndexingCancellationState.isAllCancelled() || isPaused(context)) {
                        if (isPaused(context)) {
                            fileStore.updateStatus(file.uri, IndexingStatus.PENDING)
                            val remaining = fileStore.getPendingFiles()
                            val msg = if (remaining.isNotEmpty()) "Paused (${remaining.size} pending)" else "Paused"
                            IndexingProgressState.setPaused(msg)
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
                                    isPaused(context)
                                },
                                onProgress = { cur, tot ->
                                    if (isPaused(context)) return@indexFile
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
                        if (isPaused(context)) {
                            val resumeChunk = IndexingResumeManager.getResumeChunk(context, file.uri, file.size)
                            Log.i(TAG_INDEXING, "Indexing paused by user while indexing ${file.displayName} at chunk $resumeChunk, reverting to PENDING")
                            fileStore.updateStatus(file.uri, IndexingStatus.PENDING, resumeChunk)
                            val remaining = fileStore.getPendingFiles()
                            val msg = if (remaining.isNotEmpty()) "Paused (${remaining.size} pending)" else "Paused"
                            IndexingProgressState.setPaused(msg)
                            return@withContext Result.success()
                        }

                        // Explicit user cancellation of this specific file or all files
                        if (IndexingCancellationState.isCancelled(file.uri)) {
                            IndexingCancellationState.clear(file.uri)
                            IndexingResumeManager.clearResumeChunk(context, file.uri)
                            Log.i(TAG_INDEXING, "Indexing explicitly cancelled for ${file.displayName}")
                            fileStore.updateStatus(file.uri, IndexingStatus.FAILED, 0)
                            try {
                                EngineManager.withStore(context) { it.deleteByFileUri(file.uri) }
                            } catch (_: Exception) {}
                            if (IndexingCancellationState.isAllCancelled()) {
                                IndexingProgressState.reset()
                                return@withContext Result.success()
                            }
                            continue
                        }

                        // Interruption / worker preemption: revert to PENDING so next session auto-resumes
                        if (isStopped) {
                            if (IndexingCancellationState.isAllCancelled()) {
                                Log.i(TAG_INDEXING, "Indexing worker stopped due to cancellation by user for ${file.displayName}")
                                IndexingResumeManager.clearResumeChunk(context, file.uri)
                                fileStore.updateStatus(file.uri, IndexingStatus.FAILED, 0)
                                try {
                                    EngineManager.withStore(context) { it.deleteByFileUri(file.uri) }
                                } catch (_: Exception) {}
                                IndexingProgressState.reset()
                                return@withContext Result.success()
                            }
                            val resumeChunk = IndexingResumeManager.getResumeChunk(context, file.uri, file.size)
                            Log.i(TAG_INDEXING, "Worker stopped while indexing ${file.displayName} at chunk $resumeChunk, reverting to PENDING for auto-resume")
                            fileStore.updateStatus(file.uri, IndexingStatus.PENDING, resumeChunk)
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
                                if (IndexingCancellationState.isAllCancelled() || IndexingCancellationState.isCancelled(file.uri)) {
                                    IndexingResumeManager.clearResumeChunk(context, file.uri)
                                    fileStore.updateStatus(file.uri, IndexingStatus.FAILED, 0)
                                    try {
                                        EngineManager.withStore(context) { it.deleteByFileUri(file.uri) }
                                    } catch (_: Exception) {}
                                } else {
                                    val resumeChunk = IndexingResumeManager.getResumeChunk(context, file.uri, file.size)
                                    Log.i(TAG_INDEXING, "Indexing interrupted for ${file.displayName} (${result.errorMessage}), reverting to PENDING at chunk $resumeChunk")
                                    fileStore.updateStatus(file.uri, IndexingStatus.PENDING, resumeChunk)
                                }
                            } else {
                                Log.e(TAG_INDEXING, "Indexing failed for ${file.displayName}: ${result.errorMessage}")
                                IndexingResumeManager.clearResumeChunk(context, file.uri)
                                fileStore.updateStatus(file.uri, IndexingStatus.FAILED, 0)
                                try {
                                    EngineManager.withStore(context) { it.deleteByFileUri(file.uri) }
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG_INDEXING, "Exception indexing ${file.displayName}", e)
                        val resumeChunk = IndexingResumeManager.getResumeChunk(context, file.uri, file.size)
                        if (isPaused(context)) {
                            fileStore.updateStatus(file.uri, IndexingStatus.PENDING, resumeChunk)
                            val remaining = fileStore.getPendingFiles()
                            val msg = if (remaining.isNotEmpty()) "Paused (${remaining.size} pending)" else "Paused"
                            IndexingProgressState.setPaused(msg)
                            return@withContext Result.success()
                        }
                        if (IndexingCancellationState.isCancelled(file.uri)) {
                            IndexingCancellationState.clear(file.uri)
                            IndexingResumeManager.clearResumeChunk(context, file.uri)
                            fileStore.updateStatus(file.uri, IndexingStatus.FAILED, 0)
                            try {
                                EngineManager.withStore(context) { it.deleteByFileUri(file.uri) }
                            } catch (_: Exception) {}
                            if (IndexingCancellationState.isAllCancelled()) {
                                IndexingProgressState.reset()
                                return@withContext Result.success()
                            }
                            continue
                        }
                        if (isStopped || e is kotlinx.coroutines.CancellationException || e is InterruptedException) {
                            if (IndexingCancellationState.isAllCancelled()) {
                                IndexingResumeManager.clearResumeChunk(context, file.uri)
                                fileStore.updateStatus(file.uri, IndexingStatus.FAILED, 0)
                                try {
                                    EngineManager.withStore(context) { it.deleteByFileUri(file.uri) }
                                } catch (_: Exception) {}
                                IndexingProgressState.reset()
                                return@withContext Result.success()
                            }
                            Log.i(TAG_INDEXING, "Worker interrupted while indexing ${file.displayName}, reverting to PENDING at chunk $resumeChunk")
                            fileStore.updateStatus(file.uri, IndexingStatus.PENDING, resumeChunk)
                            IndexingProgressState.reset()
                            return@withContext Result.retry()
                        }
                        IndexingResumeManager.clearResumeChunk(context, file.uri)
                        fileStore.updateStatus(file.uri, IndexingStatus.FAILED, 0)
                        try {
                            EngineManager.withStore(context) { it.deleteByFileUri(file.uri) }
                        } catch (_: Exception) {}
                    }

                    // Micro-pacing / Frame yielding: brief pause between consecutive files
                    // yields GPU and CPU time slices to SurfaceFlinger and HWUI compositor,
                    // keeping the phone's UI buttery smooth even during bulk image/file indexing.
                    if (idx < pending.lastIndex && !isStopped && !isPaused(context)) {
                        kotlinx.coroutines.delay(com.localfind.index.video.DEFAULT_MICRO_PACING_DELAY_MS)
                    }
                }
            }

            Result.success()
        } catch (e: Throwable) {
            Log.e(TAG_INDEXING, "Fatal error in IndexingWorker", e)
            Result.failure()
        } finally {
            if (isPaused(context)) {
                val remaining = fileStore.getPendingFiles().size
                if (remaining > 0) {
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    notificationManager?.notify(NOTIFICATION_ID, buildPausedNotification(context, remaining))
                }
            } else {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.cancel(NOTIFICATION_ID)
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

