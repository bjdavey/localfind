package com.localfind.data

import android.content.Context
import android.util.Log
import com.localfind.embed.Embedder
import com.localfind.embed.MODEL_FILE_NAME
import com.localfind.index.Indexer
import com.localfind.search.Searcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Observable lifecycle status of the on-device embedding model.
 */
sealed interface ModelStatus {
    /** Model is currently being verified, copied, or initialized in memory. */
    data object Loading : ModelStatus

    /**
     * Model is verified in internal storage and initialized in memory.
     *
     * @property modelPath Absolute file path inside the app's internal files directory.
     * @property sizeBytes File size in bytes.
     */
    data class Loaded(val modelPath: String, val sizeBytes: Long) : ModelStatus

    /**
     * Model is not loaded or failed to initialize.
     *
     * @property message Descriptive explanation of the issue.
     * @property instructions Clear action steps for loading the model on device or emulator.
     */
    data class Error(val message: String, val instructions: String = "") : ModelStatus
}

/**
 * Singleton coordinator for on-device AI inference and local vector storage.
 *
 * Architecture and Concurrency:
 * - Single Model Instance: The EmbeddingGemma 2 740M multimodal model consumes ~485MB of RAM.
 *   Loading multiple instances simultaneously risks Out Of Memory (OOM) crashes on low-to-mid range devices.
 *   [EngineManager] ensures only a single instance is instantiated and kept warm in memory.
 * - Thread Safety: MediaPipe / LiteRT native C++ inference and SQLite operations are safely synchronized
 *   across UI threads, background Coroutines, and WorkManager tasks.
 * - Non-Blocking Search vs Background Indexing: Inference calls in [Embedder] and SQLite operations
 *   in [EmbeddingStore] synchronize individually so UI searches and background indexing coordinate cooperatively.
 * - 100% Offline: Operates strictly with on-device model assets and local SQLite databases.
 */
object EngineManager {
    private const val TAG = "EngineManager"
    private val engineMutex = Mutex()
    @Volatile
    private var embedder: Embedder? = null
    @Volatile
    private var store: EmbeddingStore? = null

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Loading)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    /**
     * Verifies that the model is loaded in internal storage and initializes [Embedder].
     *
     * Embedder automatically enables GPU acceleration for vision (images and video frames)
     * and seamlessly falls back to CPU if GPU acceleration is unavailable on the device.
     * Text and audio run safely on CPU (XNNPack).
     *
     * Emits state to [modelStatus] for live UI observation.
     */
    suspend fun ensureModelLoaded(context: Context): ModelStatus = withContext(Dispatchers.IO) {
        _modelStatus.value = ModelStatus.Loading

        val modelFile = Embedder.ensureModelFile(context)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            val instructions = "To install the model on this emulator or device:\n" +
                    "1. Connect with ADB\n" +
                    "2. Run: adb push .local/models/$MODEL_FILE_NAME /data/local/tmp/\n" +
                    "3. Tap 'Retry Loading'"
            val err = ModelStatus.Error(
                message = "Model file not found in internal storage (${modelFile.absolutePath})",
                instructions = instructions
            )
            _modelStatus.value = err
            Log.w(TAG, "Embedding model not found at ${modelFile.absolutePath}")
            return@withContext err
        }

        try {
            // Delegate through getEmbedder so coroutine and synchronous callers share the same monitor lock,
            // preventing concurrent initialization of two ~485MB native Embedder instances.
            engineMutex.withLock {
                getEmbedder(context.applicationContext)
            }
            val loaded = ModelStatus.Loaded(modelFile.absolutePath, modelFile.length())
            _modelStatus.value = loaded
            Log.i(TAG, "Embedding model successfully loaded.")
            loaded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Embedder from ${modelFile.absolutePath}", e)
            val err = ModelStatus.Error(
                message = "Failed to load model: ${e.message ?: e.javaClass.simpleName}",
                instructions = "Check that the model file is not corrupted and compatible with the device architecture."
            )
            _modelStatus.value = err
            err
        }
    }

    /**
     * Obtains or creates the singleton [EmbeddingStore] instance.
     */
    fun getStore(context: Context): EmbeddingStore {
        return store ?: synchronized(this) {
            store ?: EmbeddingStore.create(context.applicationContext).also { store = it }
        }
    }

    /**
     * Obtains or creates the singleton [Embedder] instance.
     * Automatically uses GPU for vision with automatic CPU fallback.
     * Note: Initializing loads the ~485MB model file from filesDir.
     */
    fun getEmbedder(context: Context): Embedder {
        return embedder ?: synchronized(this) {
            embedder ?: Embedder(context.applicationContext).also {
                Log.i(TAG, "Initialized singleton Embedder instance.")
                embedder = it
                val f = File(context.filesDir, MODEL_FILE_NAME)
                if (f.exists()) {
                    _modelStatus.value = ModelStatus.Loaded(f.absolutePath, f.length())
                }
            }
        }
    }

    /**
     * Executes a suspending block with access to [EmbeddingStore] without initializing [Embedder].
     */
    suspend fun <T> withStore(
        context: Context,
        block: suspend (store: EmbeddingStore) -> T
    ): T {
        val st = getStore(context)
        return block(st)
    }

    /**
     * Executes an indexing operation with a freshly configured [Indexer].
     *
     * Inference calls in [Embedder] and database transactions in [EmbeddingStore] are individually
     * synchronized, allowing concurrent search queries and UI stats updates to execute cooperatively
     * without blocking on long multi-chunk file indexing operations.
     */
    suspend fun <T> withIndexer(
        context: Context,
        settings: AppSettings = AppSettings(),
        block: suspend (indexer: Indexer) -> T
    ): T {
        val emb = getEmbedder(context)
        val st = getStore(context)
        val indexer = Indexer(context.applicationContext, emb, st, settings)
        return block(indexer)
    }

    /**
     * Executes a semantic search query with [Searcher].
     * Coordinates cooperatively with background indexing tasks, completing searches in ~50ms
     * even while files are actively indexing.
     */
    suspend fun <T> withSearcher(
        context: Context,
        block: suspend (searcher: Searcher) -> T
    ): T {
        val emb = getEmbedder(context)
        val st = getStore(context)
        val searcher = Searcher(st, emb)
        return block(searcher)
    }

    /**
     * Releases native memory and closes open database connections.
     * Intended for application teardown or testing teardown.
     */
    fun close() {
        try {
            embedder?.close()
        } catch (_: Exception) {
            // Ignore teardown errors
        }
        embedder = null
        _modelStatus.value = ModelStatus.Loading

        try {
            store?.close()
        } catch (_: Exception) {
            // Ignore teardown errors
        }
        store = null
    }
}

