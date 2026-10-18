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

    private val _activeAccelerator = MutableStateFlow<Accelerator>(Accelerator.CPU)
    val activeAccelerator: StateFlow<Accelerator> = _activeAccelerator.asStateFlow()

    private val _supportedAccelerators = MutableStateFlow<Set<Accelerator>>(setOf(Accelerator.CPU))
    val supportedAccelerators: StateFlow<Set<Accelerator>> = _supportedAccelerators.asStateFlow()

    /**
     * Verifies that the model is loaded in internal storage and initializes [Embedder].
     *
     * Startup and Acceleration Logic:
     * 1. On first startup, automatically probes device support (NPU -> GPU -> CPU) and selects
     *    the best supported hardware accelerator without interrupting or notifying the user.
     * 2. On subsequent startups, uses the saved user preference.
     * 3. If an accelerator fails to initialize on the device, silently falls back to CPU.
     *
     * Emits state to [modelStatus], [activeAccelerator], and [supportedAccelerators] for live UI observation.
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
                val settingsStore = SettingsStore(context)
                var supported = settingsStore.getSupportedAccelerators()

                // On first launch (when accelerator has not yet been chosen or probed)
                if (!settingsStore.hasCustomAccelerator()) {
                    Log.i(TAG, "First launch detected: probing hardware accelerators (NPU, GPU, CPU)...")
                    supported = Embedder.probeSupportedAccelerators(context)
                    settingsStore.saveSupportedAccelerators(supported)

                    // Auto-select best supported choice: NPU > GPU > CPU
                    val bestChoice = when {
                        Accelerator.NPU in supported -> Accelerator.NPU
                        Accelerator.GPU in supported -> Accelerator.GPU
                        else -> Accelerator.CPU
                    }
                    settingsStore.save(settingsStore.load().copy(accelerator = bestChoice))
                    Log.i(TAG, "Auto-selected default accelerator: $bestChoice (Device supports: $supported)")
                }

                _supportedAccelerators.value = supported

                val rawAccelerator = settingsStore.load().accelerator
                // Guard: only attempt loading accelerators verified supported on this device
                val targetAccelerator = if (rawAccelerator in supported) rawAccelerator else Accelerator.CPU
                if (targetAccelerator != rawAccelerator) {
                    settingsStore.save(settingsStore.load().copy(accelerator = targetAccelerator))
                }

                if (embedder == null || embedder?.accelerator != targetAccelerator) {
                    try {
                        Log.i(TAG, "Initializing Embedder on $targetAccelerator with ${modelFile.absolutePath}...")
                        val newEmb = Embedder(context.applicationContext, targetAccelerator)
                        embedder?.close()
                        embedder = newEmb
                        _activeAccelerator.value = targetAccelerator
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load on $targetAccelerator. Silently falling back to CPU.", e)
                        // Silent fallback to CPU
                        val fallbackEmb = Embedder(context.applicationContext, Accelerator.CPU)
                        embedder?.close()
                        embedder = fallbackEmb
                        _activeAccelerator.value = Accelerator.CPU
                        settingsStore.save(settingsStore.load().copy(accelerator = Accelerator.CPU))
                    }
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
     * Changes the hardware accelerator used for embeddings.
     * Safely closes previous embedder instance and loads with new accelerator under mutex lock.
     * If the requested accelerator fails on the device, silently reverts to CPU.
     */
    suspend fun setAccelerator(context: Context, newAccelerator: Accelerator) = withContext(Dispatchers.IO) {
        engineMutex.withLock {
            if (newAccelerator !in _supportedAccelerators.value) {
                Log.w(TAG, "Requested accelerator $newAccelerator is not supported on this device. Ignoring switch.")
                return@withLock
            }
            if (embedder?.accelerator == newAccelerator) return@withLock
            val settingsStore = SettingsStore(context)
            try {
                Log.i(TAG, "Switching accelerator to $newAccelerator...")
                val newEmb = Embedder(context.applicationContext, newAccelerator)
                embedder?.close()
                embedder = newEmb
                _activeAccelerator.value = newAccelerator
                settingsStore.save(settingsStore.load().copy(accelerator = newAccelerator))
                Log.i(TAG, "Successfully switched accelerator to $newAccelerator.")
            } catch (e: Exception) {
                Log.w(TAG, "Requested accelerator $newAccelerator failed. Silently reverting to CPU.", e)
                if (embedder == null || embedder?.accelerator != Accelerator.CPU) {
                    try {
                        embedder?.close()
                        embedder = Embedder(context.applicationContext, Accelerator.CPU)
                    } catch (_: Exception) {}
                }
                _activeAccelerator.value = Accelerator.CPU
                settingsStore.save(settingsStore.load().copy(accelerator = Accelerator.CPU))
            }
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
                val targetAcc = SettingsStore(context).load().accelerator
                Embedder(context.applicationContext, targetAcc).also {
                    embedder = it
                    _activeAccelerator.value = targetAcc
                    val f = File(context.filesDir, MODEL_FILE_NAME)
                    if (f.exists()) {
                        _modelStatus.value = ModelStatus.Loaded(f.absolutePath, f.length())
                    }
                }
            } catch (e: Exception) {
                // If requested accelerator fails, silently fall back to CPU
                try {
                    Embedder(context.applicationContext, Accelerator.CPU).also {
                        embedder = it
                        _activeAccelerator.value = Accelerator.CPU
                        val f = File(context.filesDir, MODEL_FILE_NAME)
                        if (f.exists()) {
                            _modelStatus.value = ModelStatus.Loaded(f.absolutePath, f.length())
                        }
                    }
                } catch (fallbackEx: Exception) {
                    _modelStatus.value = ModelStatus.Error(e.message ?: "Failed to initialize Embedder")
                    throw e
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
     * Executes a suspending block with access to the shared [Embedder] and [EmbeddingStore].
     */
    suspend fun <T> withLock(
        context: Context,
        block: suspend (embedder: Embedder, store: EmbeddingStore) -> T
    ): T {
        val emb = getEmbedder(context)
        val st = getStore(context)
        return block(emb, st)
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
        _activeAccelerator.value = Accelerator.CPU

        try {
            store?.close()
        } catch (_: Exception) {
            // Ignore teardown errors
        }
        store = null
    }
}

