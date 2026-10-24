package com.localfind.data

import com.google.mediapipe.tasks.core.Part
import android.graphics.Bitmap
import kotlin.concurrent.write
import kotlin.concurrent.read
import java.util.concurrent.locks.ReentrantReadWriteLock
import com.localfind.embed.EmbeddingModel
import com.localfind.core.settings.AppSettings
import com.localfind.core.settings.Capabilities
import com.localfind.core.settings.HardwareAccelerator
import com.localfind.core.model.MODEL_FILE_NAME
import com.localfind.core.model.ModelStatus
import android.content.Context
import android.util.Log
import com.localfind.embed.Embedder
import com.localfind.embed.ModelFiles
import com.localfind.embed.RecoveryModel
import com.localfind.embed.SocDetector
import com.localfind.embed.capabilitiesOf
import com.localfind.embed.loadOrder
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
 * - One decision point: the installed models are located once ([ModelFiles.locate], refreshed on startup,
 *   "Retry Loading" and after a download) into [capabilities]; which accelerator to load, and the fallbacks,
 *   come from the pure plan `HardwareAccelerator.loadOrder`; [Embedder] only executes one attempt.
 * - Thread Safety: MediaPipe / LiteRT native C++ inference and SQLite operations are safely synchronized
 *   across UI threads, background Coroutines, and WorkManager tasks.
 * - Non-Blocking Search vs Background Indexing: Inference calls in [Embedder] and SQLite operations
 *   in [EmbeddingStore] synchronize individually so UI searches and background indexing coordinate cooperatively.
 * - 100% Offline: Operates strictly with on-device model assets and local SQLite databases. The only download,
 *   the universal model when the NPU model cannot run, is done by Play on the user's request ([RecoveryModel]).
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

    /**
     * The (setting, planned first accelerator) the current [embedder] was loaded for. Compared instead of the
     * accelerator that actually loaded, so a fallback (e.g. Smart planned GPU, GPU failed, CPU loaded) is not
     * retried on every indexing chunk; a new plan (setting change, Smart screen switch) reloads.
     */
    @Volatile
    private var loadedFor: Pair<HardwareAccelerator, HardwareAccelerator>? = null

    /** Installed models from the last [ModelFiles.locate]; null until the first load. */
    @Volatile
    private var models: ModelFiles.Models? = null

    /** What this device can run with its installed models, for the planner and Settings; null until located. */
    private val _capabilities = MutableStateFlow<Capabilities?>(null)
    val capabilities: StateFlow<Capabilities?> = _capabilities.asStateFlow()

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

    /** Locates the installed models (staging them if needed) and updates [capabilities]. */
    private fun refreshModels(context: Context): ModelFiles.Models =
        ModelFiles.locate(context).also {
            models = it
            _capabilities.value = capabilitiesOf(context, it)
            Log.i(TAG, "Models: npu=${it.npu?.name}, universal=${it.universal?.name}, capabilities=${_capabilities.value}")
        }

    /**
     * Locates the installed models and loads the embedder for the accelerator chosen in Settings (NPU by default
     * on supported devices), falling back along its load order. Also used by "Retry Loading" and after the
     * universal model was downloaded, so it re-locates the models and reloads when the plan changed.
     *
     * Emits state to [modelStatus] for live UI observation.
     */
    suspend fun ensureModelLoaded(context: Context): ModelStatus = withContext(Dispatchers.IO) {
        _modelStatus.value = ModelStatus.Loading
        val app = context.applicationContext
        // The mutex serializes concurrent model-load requests from coroutines, and getEmbedder takes the write
        // lock as well, so two ~485MB native Embedder instances are never created at once.
        engineMutex.withLock {
            val found = refreshModels(app)
            if (found.npu == null && found.universal == null) {
                val expected = SocDetector.detectSoc().expectedNpuModelName ?: MODEL_FILE_NAME
                val err = ModelStatus.Error(
                    message = "Embedding model not found in internal storage",
                    instructions = "To install the model on this emulator or device:\n" +
                            "1. Connect with ADB\n" +
                            "2. Run: adb push .local/models/$expected /data/local/tmp/\n" +
                            "3. Tap 'Retry Loading'",
                    canDownloadCompatibleModel = true
                )
                Log.w(TAG, "Embedding model not found (expected $expected)")
                _modelStatus.value = err
                return@withContext err
            }
            try {
                getEmbedder(app, SettingsStore(app).load().accelerator)
                // Set explicitly: getEmbedder returns early (without a status) when the plan is already loaded
                val loaded = ModelStatus.Loaded(embedder?.model?.length() ?: 0L)
                _modelStatus.value = loaded
                Log.i(TAG, "Embedding model successfully loaded.")
                loaded
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Embedder", e)
                loadError(e, found).also { _modelStatus.value = it }
            }
        }
    }

    /** The [ModelStatus.Error] for a failed load; offers the Play download when no universal model is installed. */
    private fun loadError(e: Exception, found: ModelFiles.Models?): ModelStatus.Error {
        val noUniversal = found?.universal == null
        return ModelStatus.Error(
            message = "Failed to load model: ${e.message ?: e.javaClass.simpleName}",
            instructions = if (noUniversal) {
                "This device's NPU model could not be loaded. Download the compatible GPU/CPU model " +
                        "(${RecoveryModel.DOWNLOAD_SIZE_MB} MB) from Google Play, or tap 'Retry Loading'."
            } else {
                "Check that the model file is not corrupted and compatible with the device architecture."
            },
            canDownloadCompatibleModel = noUniversal
        )
    }

    /**
     * Downloads the universal GPU/CPU model from Play (on-demand AI pack) and loads it. Reports progress through
     * [modelStatus] ([ModelStatus.Downloading]); a failed download ends as an [ModelStatus.Error] that offers the
     * download again.
     */
    suspend fun downloadCompatibleModel(context: Context): ModelStatus {
        val app = context.applicationContext
        _modelStatus.value = ModelStatus.Downloading(percent = 0, needsConfirmation = false)
        return try {
            RecoveryModel.download(app) { progress ->
                _modelStatus.value = ModelStatus.Downloading(progress.percent, progress.needsConfirmation)
            }
            ensureModelLoaded(app)
        } catch (e: Exception) {
            Log.e(TAG, "Downloading the compatible model failed", e)
            ModelStatus.Error(
                message = "Download failed: ${e.message ?: e.javaClass.simpleName}",
                instructions = "Connect to the internet and try the download again, or tap 'Retry Loading'.",
                canDownloadCompatibleModel = true
            ).also { _modelStatus.value = it }
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
     * With an explicit [accelerator] (the user's setting) the underlying [Embedder] is replaced when its load plan
     * changed, e.g. a new setting or Smart mode switching between GPU and CPU. Without one, whatever is already
     * loaded is reused, so searches never trigger a reload; only if nothing is loaded yet is the persisted
     * setting used.
     * Note: Initializing loads the ~485MB model file from filesDir.
     */
    fun getEmbedder(
        context: Context,
        accelerator: HardwareAccelerator? = null
    ): EmbeddingModel {
        if (accelerator == null && embedder != null) return sharedEmbedder
        val configured = accelerator ?: SettingsStore(context).load().accelerator
        val found = models ?: refreshModels(context.applicationContext)
        // Capabilities are cached with the models: called for every indexing chunk, so no file checks here
        val caps = _capabilities.value ?: capabilitiesOf(context, found)
        val order = configured.loadOrder(context, caps)
        val plan = order.firstOrNull()?.let { configured to it }
        if (embedder != null && plan == loadedFor) return sharedEmbedder
        embedderLock.write {
            if (embedder == null || plan != loadedFor) {
                replaceEmbedder(context, found, order, plan)
            }
        }
        return sharedEmbedder
    }

    /** Closes the current [Embedder] and loads the first of [order] that works. Must hold the write lock. */
    private fun replaceEmbedder(
        context: Context,
        found: ModelFiles.Models,
        order: List<HardwareAccelerator>,
        plan: Pair<HardwareAccelerator, HardwareAccelerator>?
    ) {
        try {
            embedder?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing previous embedder: ${e.message}")
        }
        embedder = null
        loadedFor = null
        val loaded = Embedder.open(context.applicationContext, found, order)
        embedder = loaded
        loadedFor = plan
        Log.i(TAG, "Initialized singleton Embedder: plan=$plan, loaded=${loaded.effectiveAccelerator} (${loaded.model.name}).")
        _embeddingSpaceId.value = loaded.embeddingSpaceId
        _modelStatus.value = ModelStatus.Loaded(loaded.model.length())
    }

    /**
     * Applies the hardware accelerator chosen in Settings, replacing the [Embedder] if the load plan changed.
     * Waits for in-flight inference to finish.
     */
    fun updateAccelerator(context: Context, accelerator: HardwareAccelerator) {
        try {
            getEmbedder(context, accelerator)
        } catch (e: Exception) {
            // Called from a background coroutine on a settings change: an uncaught exception here would crash
            // the app, so surface it as a model error instead.
            Log.e(TAG, "Failed to switch accelerator to $accelerator", e)
            _modelStatus.value = loadError(e, models)
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
