package com.localfind.embed

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.AudioPart
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Part
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import com.localfind.data.HardwareAccelerator
import com.localfind.data.resolveEffective
import java.io.File

/**
 * Default base on-device embedding model file name.
 *
 * Model details:
 * - EmbeddingGemma 2 740M multimodal (text, image, audio)
 * - Output dimensionality: 768 float values per embedding
 * - Runtime delivery:
 *     - Production: Google Play Asset Delivery (install-time asset pack)
 *     - Development: Pushed via ADB to context.filesDir (`adb push ...`)
 * - 100% offline: Inference runs entirely on-device via LiteRT / MediaPipe Tasks.
 */
const val MODEL_FILE_NAME = "embeddinggemma-2-740m.litertlm"

/**
 * Vision token budget per image (GOOGLE_AI_EDGE_GALLERY.md §6.2).
 *
 * Using 70 tokens per image reduces transformer prefill length during image embedding
 * for significantly faster indexing while preserving semantic retrieval accuracy.
 */
const val DEFAULT_VISION_TOKENS_PER_IMAGE = 70

/**
 * Wrapper around MediaPipe's [UniversalEmbedder].
 *
 * Responsible for:
 * 1. Loading the local weights file from the app's private files directory.
 * 2. Configuring L2 normalization so that cosine similarity can be computed via dot product.
 * 3. Setting the 70-token vision budget for fast image encoding.
 * 4. Producing 768-dimensional float embeddings for input text strings, passages, and images.
 * 5. Providing configurable NPU / GPU vision acceleration with graceful CPU fallback.
 *
 * Implements [AutoCloseable] to ensure underlying native C++ LiteRT resources are promptly freed.
 */
class Embedder(
    context: Context,
    val configuredAccelerator: HardwareAccelerator = HardwareAccelerator.SMART,
    preferredModelName: String? = null
) : AutoCloseable {
    private val embedder: UniversalEmbedder

    /** Concrete accelerator in use after resolving any dynamic policy (e.g. [HardwareAccelerator.SMART]). */
    val effectiveAccelerator: HardwareAccelerator = configuredAccelerator.resolveEffective(context)

    /** Backward-compatible property exposing the configured accelerator. */
    val accelerator: HardwareAccelerator get() = configuredAccelerator

    /**
     * Whether NPU acceleration is actively enabled for the vision encoder.
     * When true, vision encoding executes via dedicated hardware NPU (e.g. Qualcomm Hexagon HTP).
     */
    val isNpuVisionEnabled: Boolean

    /**
     * Whether GPU acceleration is actively enabled for the vision encoder.
     * When false, vision encoding executes via CPU (XNNPack).
     */
    val isGpuVisionEnabled: Boolean

    init {
        // Locate the model file in internal storage, copying from staging paths if missing.
        val model = SocDetector.ensureModelFile(context, effectiveAccelerator, preferredModelName)

        require(model.exists() && model.length() > 0L) {
            "Embedding model not found at ${model.absolutePath}. " +
                    "For development on emulator or device, push the model file using:\n" +
                    "adb push .local/models/${model.name} /data/local/tmp/"
        }

        // Set L2 normalization to true so vectors have unit length (norm = 1.0),
        // and set visionTokensPerImage to 70 for fast vision encoding.
        // BaseOptions, Text, and Audio run on CPU (XNNPack), avoiding Qualcomm Adreno's proprietary
        // Vulkan compiler crash in libllvm-qgl.so (SIGSEGV during text attention compute pipeline compilation).
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(model.absolutePath)
            .setDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
            .build()

        var createdEmbedder: UniversalEmbedder? = null
        var npuSuccess = false
        var gpuSuccess = false

        if (effectiveAccelerator == HardwareAccelerator.NPU) {
            try {
                // Ensure Qualcomm QNN and LiteRT dispatch runtime libraries are staged in filesDir
                SocDetector.ensureDispatchLibraries(context)

                // Attempt NPU acceleration for vision encoding via Qualcomm Hexagon HTP v75
                val npuOptions = UniversalEmbedderOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setL2Normalize(true)
                    .setVisionTokensPerImage(DEFAULT_VISION_TOKENS_PER_IMAGE)
                    .setCacheDir(context.cacheDir.absolutePath)
                    .setTextDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                    .setAudioDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                    .setVisionDelegate(com.google.mediapipe.tasks.core.Delegate.NPU)
                    .build()

                createdEmbedder = UniversalEmbedder.createFromOptions(context, npuOptions)
                npuSuccess = true
                Log.i(TAG, "UniversalEmbedder successfully initialized with Qualcomm Hexagon NPU vision acceleration (configured=$configuredAccelerator, effective=$effectiveAccelerator).")
            } catch (t: Throwable) {
                Log.w(TAG, "NPU vision acceleration unavailable or failed to initialize (${t.message}). Falling back to GPU/CPU.", t)
            }
        }

        if (createdEmbedder == null && (effectiveAccelerator == HardwareAccelerator.GPU || effectiveAccelerator == HardwareAccelerator.NPU)) {
            try {
                // Attempt GPU acceleration for vision encoding (images and video frame indexing).
                val gpuOptions = UniversalEmbedderOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setL2Normalize(true)
                    .setVisionTokensPerImage(DEFAULT_VISION_TOKENS_PER_IMAGE)
                    .setTextDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                    .setAudioDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                    .setVisionDelegate(com.google.mediapipe.tasks.core.Delegate.GPU)
                    .build()

                createdEmbedder = UniversalEmbedder.createFromOptions(context, gpuOptions)
                gpuSuccess = true
                Log.i(TAG, "UniversalEmbedder initialized with GPU vision acceleration (configured=$configuredAccelerator, effective=$effectiveAccelerator).")
            } catch (t: Throwable) {
                Log.w(TAG, "GPU vision acceleration unavailable or failed to initialize (${t.message}). Falling back to CPU vision.", t)
            }
        }

        if (createdEmbedder == null) {
            // CPU mode or fallback: All modalities execute via CPU (XNNPack).
            val cpuOptions = UniversalEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .setL2Normalize(true)
                .setVisionTokensPerImage(DEFAULT_VISION_TOKENS_PER_IMAGE)
                .setTextDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                .setAudioDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                .setVisionDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                .build()

            createdEmbedder = UniversalEmbedder.createFromOptions(context, cpuOptions)
            Log.i(TAG, "UniversalEmbedder initialized with CPU vision delegate (configured=$configuredAccelerator, effective=$effectiveAccelerator).")
        }

        embedder = createdEmbedder
        isNpuVisionEnabled = npuSuccess
        isGpuVisionEnabled = gpuSuccess
    }

    /**
     * Converts a text string or passage into a 768-dimensional normalized float embedding vector.
     *
     * Synchronized on `this` to prevent concurrent native LiteRT inference calls between
     * background indexing workers and UI search queries.
     *
     * @param text The input passage or search query string to embed.
     * @return FloatArray of 768 elements representing the semantic vector.
     */
    fun embedText(text: String): FloatArray = synchronized(this) {
        embedder.embedText(text).embeddings()[0].floatEmbedding()
    }

    /**
     * Converts an image [Bitmap] into a 768-dimensional normalized float embedding vector
     * in the same shared multimodal vector space as text.
     *
     * Synchronized on `this` to ensure thread-safe native inference.
     *
     * Under the hood, wraps the [Bitmap] in a MediaPipe [com.google.mediapipe.framework.image.MPImage]
     * via [BitmapImageBuilder] and invokes [UniversalEmbedder.embedImage], which encodes the bitmap
     * pixels into a 32-bit BGRA TGA byte buffer and runs the EmbeddingGemma 2 vision encoder.
     *
     * @param bitmap The decoded, upright ARGB_8888 image bitmap to embed.
     * @return FloatArray of 768 elements representing the semantic vector.
     */
    fun embedImage(bitmap: Bitmap): FloatArray = synchronized(this) {
        BitmapImageBuilder(bitmap).build().use { mpImage ->
            embedder.embedImage(mpImage).embeddings()[0].floatEmbedding()
        }
    }

    /**
     * Converts a window of audio into a 768-dimensional normalized float embedding vector
     * in the same shared multimodal vector space as text and images.
     *
     * Synchronized on `this` to ensure thread-safe native inference.
     *
     * @param samples 16 kHz mono signed 16-bit PCM samples (the rate EmbeddingGemma's
     *   mel-spectrogram frontend expects).
     * @return FloatArray of 768 elements representing the semantic vector.
     */
    fun embedAudio(samples: ShortArray): FloatArray = synchronized(this) {
        embedder.embedAudio(toAudioData(samples)).embeddings()[0].floatEmbedding()
    }

    /**
     * Wraps 16 kHz mono PCM samples as a content part for [embedVideoWindow].
     */
    fun audioPart(samples: ShortArray): AudioPart = AudioPart(toAudioData(samples))

    /**
     * Embeds one video window as a single vector from interleaved parts, e.g.
     * `[time] audio-slice [time] frame-jpeg [time] audio-slice [time] frame-jpeg`
     * (GOOGLE_AI_EDGE_GALLERY.md §6.2). The model has no dedicated video input,
     * so a video window is represented as frames plus audio in one sequence.
     *
     * Synchronized on `this` to ensure thread-safe native inference.
     *
     * @param parts [TextPart] time markers, [AudioPart] slices and [ImagePart] JPEG frames.
     * @return FloatArray of 768 elements representing the semantic vector.
     */
    fun embedVideoWindow(parts: List<Part>): FloatArray = synchronized(this) {
        embedder.embedContent(parts).embeddings()[0].floatEmbedding()
    }

    private fun toAudioData(samples: ShortArray): AudioData {
        val format = AudioData.AudioDataFormat.builder()
            .setNumOfChannels(1)
            .setSampleRate(16_000f)
            .build()
        return AudioData.create(format, samples.size).also { it.load(samples) }
    }

    /**
     * Releases native memory and interpreter instances allocated by MediaPipe Tasks.
     * Synchronized on `this` to avoid closing while an active inference call is running.
     */
    override fun close() = synchronized(this) {
        embedder.close()
    }

    companion object {
        private const val TAG = "Embedder"

        /**
         * Ensures that the on-device model file exists in [context.filesDir].
         * Delegates to [SocDetector.ensureModelFile].
         */
        fun ensureModelFile(context: Context, modelName: String = MODEL_FILE_NAME): File {
            return SocDetector.ensureModelFile(context, modelName = modelName)
        }
    }
}
