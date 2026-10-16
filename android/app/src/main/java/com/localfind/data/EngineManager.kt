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
 *   across UI threads, background Coroutines, and WorkManager tasks via [engineMutex].
 * - Non-Blocking Search vs Background Indexing: By structuring operations through [withLock],
 *   UI search queries and background WorkManager file indexing coordinate cooperatively.
 * - 100% Offline: Operates strictly with on-device model assets and local SQLite databases.
 */
object EngineManager {
    private const val TAG = "EngineManager"
    private val engineMutex = Mutex()
    private var embedder: Embedder? = null
    private var store: EmbeddingStore? = null

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Loading)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    /**
     * Verifies that the model is loaded in internal storage and initializes [Embedder].
     *
     * If missing, checks emulator development fallback paths (such as `/data/local/tmp`)
     * and automatically copies it to the app's internal storage directory.
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
            engineMutex.withLock {
                if (embedder == null) {
                    Log.i(TAG, "Initializing Embedder with ${modelFile.absolutePath} (${modelFile.length()} bytes)...")
                    embedder = Embedder(context.applicationContext)
                }
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
     * Note: Initializing loads the ~485MB model file from filesDir.
     */
    fun getEmbedder(context: Context): Embedder {
        return embedder ?: synchronized(this) {
            embedder ?: try {
                Embedder(context.applicationContext).also {
                    embedder = it
                    val f = File(context.filesDir, MODEL_FILE_NAME)
                    if (f.exists()) {
                        _modelStatus.value = ModelStatus.Loaded(f.absolutePath, f.length())
                    }
                }
            } catch (e: Exception) {
                _modelStatus.value = ModelStatus.Error(e.message ?: "Failed to initialize Embedder")
                throw e
            }
        }
    }

    /**
     * Executes a suspending block with exclusive access to [EmbeddingStore] without initializing [Embedder].
     */
    suspend fun <T> withStore(
        context: Context,
        block: suspend (store: EmbeddingStore) -> T
    ): T {
        return engineMutex.withLock {
            val st = getStore(context)
            block(st)
        }
    }

    /**
     * Executes a suspending block with exclusive, thread-safe access to the shared [Embedder]
     * and [EmbeddingStore] under the mutual exclusion lock.
     */
    suspend fun <T> withLock(
        context: Context,
        block: suspend (embedder: Embedder, store: EmbeddingStore) -> T
    ): T {
        return engineMutex.withLock {
            val emb = getEmbedder(context)
            val st = getStore(context)
            block(emb, st)
        }
    }

    /**
     * Executes an indexing operation with a freshly configured [Indexer] under the lock.
     */
    suspend fun <T> withIndexer(
        context: Context,
        settings: AppSettings = AppSettings(),
        block: suspend (indexer: Indexer) -> T
    ): T {
        return withLock(context) { emb, st ->
            val indexer = Indexer(context.applicationContext, emb, st, settings)
            block(indexer)
        }
    }

    /**
     * Executes a semantic search query with [Searcher] under the lock.
     */
    suspend fun <T> withSearcher(
        context: Context,
        block: suspend (searcher: Searcher) -> T
    ): T {
        return withLock(context) { emb, st ->
            val searcher = Searcher(st, emb)
            block(searcher)
        }
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

