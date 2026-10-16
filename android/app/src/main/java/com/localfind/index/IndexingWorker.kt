package com.localfind.index

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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
         *                Defaults to true so that newly queued, retried, or re-indexed files
         *                are guaranteed to be processed immediately by WorkManager.
         */
        fun enqueue(context: Context, replace: Boolean = true) {
            val request = OneTimeWorkRequestBuilder<IndexingWorker>()
                .addTag(TAG_INDEXING)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Cancels any running or enqueued indexing work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
            IndexingProgressState.reset()
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
                        // Mark back to PENDING so resumption picks it up cleanly on next launch
                        fileStore.updateStatus(file.uri, IndexingStatus.PENDING)
                        IndexingProgressState.reset()
                        return@withContext Result.retry()
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
                            indexer.indexFile(file) { cur, tot ->
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
                            }
                        }

                        if (result.isSuccess) {
                            Log.i(TAG_INDEXING, "Indexed ${file.displayName}: ${result.chunksCount} chunks")
                            fileStore.updateStatus(file.uri, IndexingStatus.INDEXED, result.chunksCount)
                        } else {
                            Log.e(TAG_INDEXING, "Indexing failed for ${file.displayName}: ${result.errorMessage}")
                            fileStore.updateStatus(file.uri, IndexingStatus.FAILED, 0)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG_INDEXING, "Exception indexing ${file.displayName}", e)
                        if (isStopped) {
                            fileStore.updateStatus(file.uri, IndexingStatus.PENDING)
                            IndexingProgressState.reset()
                            return@withContext Result.retry()
                        }
                        fileStore.updateStatus(file.uri, IndexingStatus.FAILED, 0)
                    }
                }
            }

            IndexingProgressState.reset()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG_INDEXING, "Fatal error in IndexingWorker", e)
            IndexingProgressState.reset()
            if (isStopped) Result.retry() else Result.failure()
        }
    }
}

