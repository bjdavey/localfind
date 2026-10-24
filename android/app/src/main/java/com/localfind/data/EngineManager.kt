package com.localfind.data

import com.google.mediapipe.tasks.core.Part
import android.graphics.Bitmap
import kotlin.concurrent.write
import kotlin.concurrent.read
import java.util.concurrent.locks.ReentrantReadWriteLock
import com.localfind.embed.EmbeddingModel
import com.localfind.core.settings.AppSettings
import com.localfind.core.settings.DEFAULT_HARDWARE_ACCELERATOR
import com.localfind.core.settings.HardwareAccelerator
import com.localfind.core.model.ModelStatus
import android.content.Context
import android.util.Log
import com.localfind.embed.Embedder
import com.localfind.embed.resolveEffective
import com.localfind.embed.ModelFiles
import com.localfind.index.Indexer
import com.localfind.search.Searcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

    /**
     * Guards the single [Embedder] instance. Inference holds the read lock; replacing or closing the
     * instance (accelerator change) takes the write lock, so it waits for in-flight inference and no
     * caller ever runs on a closed native model.
     */
    private val embedderLock = ReentrantReadWriteLock()
    @Volatile
    private var embedder: Embedder? = null

    /** Runs [block] on the current [Embedder] under the read lock. */
    private fun <T> withEmbedder(block: (Embedder) -> T): T = embedderLock.read {
        val current = embedder ?: throw IllegalStateException("Embedder not initialized")
        block(current)
    }

    /**
     * Stable handle that indexers and the searcher keep for as long as they like: every call is routed
     * to the current [Embedder], which may be replaced between calls.
     */
    private val sharedEmbedder = object : EmbeddingModel {
        override val effectiveAccelerator: HardwareAccelerator
            get() = embedder?.effectiveAccelerator ?: HardwareAccelerator.CPU

        override fun embedText(text: String) = withEmbedder { it.embedText(text) }
        override fun embedImage(bitmap: Bitmap) = withEmbedder { it.embedImage(bitmap) }
        override fun embedAudio(samples: ShortArray) = withEmbedder { it.embedAudio(samples) }
        override fun audioPart(samples: ShortArray) = withEmbedder { it.audioPart(samples) }
        override fun embedVideoWindow(parts: List<Part>) = withEmbedder { it.embedVideoWindow(parts) }
    }
    @Volatile
    private var store: EmbeddingStore? = null

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Loading)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    /** [com.localfind.core.model.EmbeddingSpace] id of the loaded embedder, or null before one is loaded. */
    private val _embeddingSpaceId = MutableStateFlow<String?>(null)
    val embeddingSpaceId: StateFlow<String?> = _embeddingSpaceId.asStateFlow()

    /**
     * Verifies that the model is loaded in internal storage and initializes [Embedder].
     *
     * The [Embedder] uses the accelerator chosen in Settings (NPU by default on supported devices,
     * otherwise Smart/GPU/CPU) for vision and text, and falls back to CPU when a delegate is unavailable.
     * Audio always runs on CPU (XNNPack).
     *
     * Emits state to [modelStatus] for live UI observation.
     */
    suspend fun ensureModelLoaded(context: Context): ModelStatus = withContext(Dispatchers.IO) {
        _modelStatus.value = ModelStatus.Loading

        // Pre-check with the default (NPU) accelerator so a chipset-specific model staged alone (e.g. by
        // deploy-bundle.ps1 on a Snapdragon device) is found; without it only the universal name is checked.
        val modelFile = ModelFiles.ensureModelFile(context, DEFAULT_HARDWARE_ACCELERATOR)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            val instructions = "To install the model on this emulator or device:\n" +
                    "1. Connect with ADB\n" +
                    "2. Run: adb push .local/models/${modelFile.name} /data/local/tmp/\n" +
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
            // The mutex serializes concurrent model-load requests from coroutines, and getEmbedder
            // synchronizes on the engine as well, so two ~485MB native Embedder instances are never created at once.
            engineMutex.withLock {
                getEmbedder(context.applicationContext)
            }
            val loaded = ModelStatus.Loaded(modelFile.length())
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
     * Returns the shared [EmbeddingModel], loading it first if needed.
     *
     * With an explicit [accelerator] (the user's setting) the underlying [Embedder] is replaced when it
     * was built for a different accelerator or a different resolved target, e.g. Smart mode switching
     * between GPU and CPU. Without one, whatever is already loaded is reused, so searches never trigger
     * a reload; only if nothing is loaded yet is the persisted setting used.
     * Note: Initializing loads the ~485MB model file from filesDir.
     */
    fun getEmbedder(
        context: Context,
        accelerator: HardwareAccelerator? = null
    ): EmbeddingModel {
        if (accelerator == null && embedder != null) return sharedEmbedder
        val configured = accelerator ?: SettingsStore(context).load().accelerator
        val targetEffective = configured.resolveEffective(context)
        if (isLoaded(configured, targetEffective)) return sharedEmbedder
        embedderLock.write {
            if (!isLoaded(configured, targetEffective)) {
                replaceEmbedder(context, configured, targetEffective)
            }
        }
        return sharedEmbedder
    }

    private fun isLoaded(configured: HardwareAccelerator, effective: HardwareAccelerator): Boolean =
        embedder?.let { it.effectiveAccelerator == effective && it.configuredAccelerator == configured } == true

    /** Closes the current [Embedder] and builds a new one. Must be called with the write lock held. */
    private fun replaceEmbedder(context: Context, configured: HardwareAccelerator, effective: HardwareAccelerator) {
        try {
            embedder?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing previous embedder: ${e.message}")
        }
        embedder = null
        embedder = Embedder(context.applicationContext, configured).also {
            Log.i(TAG, "Initialized singleton Embedder instance with configured=$configured (effective=$effective).")
            _embeddingSpaceId.value = it.embeddingSpaceId
            val activeFile = ModelFiles.getActiveModelFile(context, effective)
            if (activeFile.exists()) {
                _modelStatus.value = ModelStatus.Loaded(activeFile.length())
            }
        }
    }

    /**
     * Applies the hardware accelerator chosen in Settings, replacing the [Embedder] if the accelerator
     * setting or its effective runtime target has changed. Waits for in-flight inference to finish.
     */
    fun updateAccelerator(context: Context, accelerator: HardwareAccelerator) {
        getEmbedder(context, accelerator)
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
     * Inference calls on the shared embedder and database transactions in [EmbeddingStore] are
     * individually synchronized, allowing concurrent search queries and UI stats updates to execute
     * cooperatively between the chunks of a long file indexing operation.
     */
    suspend fun <T> withIndexer(
        context: Context,
        settings: AppSettings = AppSettings(),
        block: suspend (indexer: Indexer) -> T
    ): T {
        val emb = getEmbedder(context, settings.accelerator)
        val st = getStore(context)
        val indexer = Indexer(context.applicationContext, emb, st, settings)
        return block(indexer)
    }

    /**
     * Executes a semantic search query with [Searcher].
     * Shares the single embedder with background indexing without ever replacing it; inference calls
     * are synchronized, so a query may wait for an in-flight indexing chunk to finish.
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
}
