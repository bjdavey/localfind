package com.localfind.index

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.localfind.MainActivity
import com.localfind.R
import com.localfind.data.EngineManager
import com.localfind.data.FileStore
import com.localfind.data.IndexingStatus
import com.localfind.data.ModelStatus
import com.localfind.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Snapshot representing live background indexing progress for UI observation.
 *
 * @property isIndexing True if a background indexing worker is currently executing.
 * @property currentUri URI of the file currently being extracted and embedded.
 * @property currentFileName Display name of the active file.
 * @property fileIndex 1-based index of the active file within the current pending queue.
 * @property totalFiles Total number of files pending in the active batch.
 * @property currentChunk 1-based index of the chunk/page/window currently being embedded.
 * @property totalChunks Total chunks/pages/windows for the active file.
 * @property progressFraction Floating ratio (0.0f..1.0f) representing current file completion.
 * @property message Human-readable status string for top banner progress displays.
 */
data class IndexingProgress(
    val isIndexing: Boolean = false,
    val currentUri: String? = null,
    val currentFileName: String? = null,
    val fileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val progressFraction: Float = 0f,
    val message: String = ""
)

/**
 * Observable in-memory state manager for background indexing progress.
 *
 * Bridges WorkManager background task execution to Jetpack Compose UI state,
 * allowing instant per-file and banner progress updates without database polling overhead.
 */
object IndexingProgressState {
    private val _progress = MutableStateFlow(IndexingProgress())
    val progress: StateFlow<IndexingProgress> = _progress.asStateFlow()

    fun update(
        isIndexing: Boolean,
        currentUri: String?,
        currentFileName: String?,
        fileIndex: Int,
        totalFiles: Int,
        currentChunk: Int,
        totalChunks: Int,
        progressFraction: Float,
        message: String
    ) {
        _progress.value = IndexingProgress(
            isIndexing = isIndexing,
            currentUri = currentUri,
            currentFileName = currentFileName,
            fileIndex = fileIndex,
            totalFiles = totalFiles,
            currentChunk = currentChunk,
            totalChunks = totalChunks,
            progressFraction = progressFraction.coerceIn(0f, 1f),
            message = message
        )
    }

    fun reset() {
        _progress.value = IndexingProgress(isIndexing = false, message = "Ready")
    }
}

/**
 * In-memory thread-safe state tracking for explicitly cancelled file URIs.
 *
 * Allows real-time cooperative cancellation of running or queued indexing tasks
 * so stuck files can be aborted immediately without waiting for chunk loops to complete.
 */
object IndexingCancellationState {
    private val cancelledUris = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun cancelFile(uri: String) {
        cancelledUris.add(uri)
    }

    fun isFileCancelled(uri: String): Boolean {
        return cancelledUris.contains(uri)
    }

    fun clear(uri: String) {
        cancelledUris.remove(uri)
    }

    fun clearAll() {
        cancelledUris.clear()
    }
}

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

        const val KEY_CURRENT_FILE_URI = "key_current_file_uri"
        const val KEY_CURRENT_FILE_NAME = "key_current_file_name"
        const val KEY_FILE_INDEX = "key_file_index"
        const val KEY_TOTAL_FILES = "key_total_files"
        const val KEY_PROGRESS_FRACTION = "key_progress_fraction"

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
            val request = OneTimeWorkRequestBuilder<IndexingWorker>()
                .addTag(TAG_INDEXING)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }

        /**
         * Cancels any running or enqueued indexing work.
         * Marks all pending or active indexing files as FAILED so they
         * do not get stuck in an infinite retry loop.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
            IndexingCancellationState.clearAll()
            IndexingProgressState.reset()
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NOTIFICATION_ID)
            val fileStore = FileStore(context)
            val pending = fileStore.getPendingFiles()
            for (f in pending) {
                fileStore.updateStatus(f.uri, IndexingStatus.FAILED, 0)
            }
        }

        /**
         * Cancels indexing for a specific file.
         * Signals the active worker (if currently processing this file) to abort,
         * and updates its status in [FileStore] to [IndexingStatus.FAILED].
         */
        fun cancelFile(context: Context, uri: String) {
            IndexingCancellationState.cancelFile(uri)
            val fileStore = FileStore(context)
            fileStore.updateStatus(uri, IndexingStatus.FAILED, 0)
            if (IndexingProgressState.progress.value.currentUri == uri) {
                IndexingProgressState.reset()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = buildNotification(
            message = "Indexing files for local search...",
            progressFraction = 0f,
            fileIndex = 0,
            totalFiles = 0
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "File Indexing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while LocalFind indexes files in the background"
                setShowBadge(false)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        message: String,
        progressFraction: Float = 0f,
        fileIndex: Int = 0,
        totalFiles: Int = 0
    ): Notification {
        createNotificationChannel()

        val title = if (totalFiles > 0) {
            "Indexing files ($fileIndex/$totalFiles)"
        } else {
            "Indexing files"
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
        val isIndeterminate = progressFraction <= 0f && totalFiles <= 0

        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_search)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setProgress(maxProgress, currentProgress, isIndeterminate)
            .build()
    }

    private var lastNotificationTime = 0L

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
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notification = buildNotification(message, progressFraction, fileIndex, totalFiles)
            notificationManager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG_INDEXING, "Failed to update notification: ${e.message}")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val fileStore = FileStore(context)
        val settingsStore = SettingsStore(context)
        val settings = settingsStore.load()

        try {
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
                    totalChunks = 0,
                    progressFraction = 0f,
                    message = "Indexing paused: Model not loaded"
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
                if (isStopped) {
                    IndexingProgressState.reset()
                    return@withContext Result.retry()
                }

                val pending = fileStore.getPendingFiles()
                if (pending.isEmpty()) {
                    break
                }

                val totalInBatch = pending.size

                for ((idx, file) in pending.withIndex()) {
                    if (isStopped) {
                        fileStore.updateStatus(file.uri, IndexingStatus.PENDING)
                        IndexingProgressState.reset()
                        return@withContext Result.retry()
                    }

                    // If file was already cancelled or marked failed, skip it
                    val currentStatus = fileStore.get(file.uri)?.indexingStatus
                    if (currentStatus == IndexingStatus.FAILED || IndexingCancellationState.isFileCancelled(file.uri)) {
                        IndexingCancellationState.clear(file.uri)
                        continue
                    }

                    val fileIndex = idx + 1
                    fileStore.updateStatus(file.uri, IndexingStatus.INDEXING)

                    val startMessage = "Indexing $fileIndex/$totalInBatch: ${file.displayName}..."
                    IndexingProgressState.update(
                        isIndexing = true,
                        currentUri = file.uri,
                        currentFileName = file.displayName,
                        fileIndex = fileIndex,
                        totalFiles = totalInBatch,
                        currentChunk = 0,
                        totalChunks = 1,
                        progressFraction = 0f,
                        message = startMessage
                    )
                    updateNotification(startMessage, 0f, fileIndex, totalInBatch, force = true)

                    setProgress(
                        workDataOf(
                            KEY_CURRENT_FILE_URI to file.uri,
                            KEY_CURRENT_FILE_NAME to file.displayName,
                            KEY_FILE_INDEX to fileIndex,
                            KEY_TOTAL_FILES to totalInBatch,
                            KEY_PROGRESS_FRACTION to 0f
                        )
                    )

                    try {
                        val result = EngineManager.withIndexer(context, settings) { indexer ->
                            indexer.indexFile(
                                file = file,
                                isCancelled = { isStopped || IndexingCancellationState.isFileCancelled(file.uri) },
                                onProgress = { cur, tot ->
                                    val fraction = if (tot > 0) cur.toFloat() / tot.toFloat() else 0f
                                    val percent = (fraction * 100).toInt()
                                    val msg = "Indexing $fileIndex/$totalInBatch: ${file.displayName} ($percent%)"
                                    IndexingProgressState.update(
                                        isIndexing = true,
                                        currentUri = file.uri,
                                        currentFileName = file.displayName,
                                        fileIndex = fileIndex,
                                        totalFiles = totalInBatch,
                                        currentChunk = cur,
                                        totalChunks = tot,
                                        progressFraction = fraction,
                                        message = msg
                                    )
                                    updateNotification(msg, fraction, fileIndex, totalInBatch)
                                }
                            )
                        }

                        // Explicit user cancellation of this specific file
                        if (IndexingCancellationState.isFileCancelled(file.uri)) {
                            IndexingCancellationState.clear(file.uri)
                            Log.i(TAG_INDEXING, "Indexing explicitly cancelled for ${file.displayName}")
                            fileStore.updateStatus(file.uri, IndexingStatus.FAILED, 0)
                            continue
                        }

                        // Interruption / worker preemption: revert to PENDING so next session auto-resumes
                        if (isStopped) {
                            Log.i(TAG_INDEXING, "Worker stopped while indexing ${file.displayName}, reverting to PENDING for auto-resume")
                            fileStore.updateStatus(file.uri, IndexingStatus.PENDING, 0)
                            IndexingProgressState.reset()
                            return@withContext Result.retry()
                        }

                        if (result.isSuccess) {
                            Log.i(TAG_INDEXING, "Indexed ${file.displayName}: ${result.chunksCount} chunks")
                            fileStore.updateStatus(file.uri, IndexingStatus.INDEXED, result.chunksCount)
                        } else {
                            // If indexing failed because it was interrupted, cancelled by stop, or engine closed, keep as PENDING
                            if (result.errorMessage?.contains("cancelled", ignoreCase = true) == true ||
                                result.errorMessage?.contains("not initialized", ignoreCase = true) == true
                            ) {
                                Log.i(TAG_INDEXING, "Indexing interrupted for ${file.displayName} (${result.errorMessage}), reverting to PENDING")
                                fileStore.updateStatus(file.uri, IndexingStatus.PENDING, 0)
                            } else {
                                Log.e(TAG_INDEXING, "Indexing failed for ${file.displayName}: ${result.errorMessage}")
                                fileStore.updateStatus(file.uri, IndexingStatus.FAILED, 0)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG_INDEXING, "Exception indexing ${file.displayName}", e)
                        if (IndexingCancellationState.isFileCancelled(file.uri)) {
                            IndexingCancellationState.clear(file.uri)
                            fileStore.updateStatus(file.uri, IndexingStatus.FAILED, 0)
                            continue
                        }
                        if (isStopped || e is kotlinx.coroutines.CancellationException || e is InterruptedException) {
                            Log.i(TAG_INDEXING, "Worker interrupted while indexing ${file.displayName}, reverting to PENDING")
                            fileStore.updateStatus(file.uri, IndexingStatus.PENDING, 0)
                            IndexingProgressState.reset()
                            return@withContext Result.retry()
                        }
                        fileStore.updateStatus(file.uri, IndexingStatus.FAILED, 0)
                    }
                }
            }

            Result.success()
        } catch (e: Throwable) {
            Log.e(TAG_INDEXING, "Fatal error in IndexingWorker", e)
            Result.failure()
        } finally {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NOTIFICATION_ID)
            IndexingProgressState.reset()
            // If worker was stopped or cancelled, revert any in-flight files from INDEXING to PENDING so they auto-resume next time
            if (isStopped) {
                try {
                    val pending = fileStore.getPendingFiles()
                    for (f in pending) {
                        if (f.indexingStatus == IndexingStatus.INDEXING) {
                            fileStore.updateStatus(f.uri, IndexingStatus.PENDING)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG_INDEXING, "Error resetting status on stop: ${e.message}")
                }
            }
        }
    }
}

