package com.localfind.embed

import com.localfind.core.model.DEFAULT_VISION_TOKENS_PER_IMAGE
import com.localfind.core.model.EmbeddingSpace
import com.localfind.core.model.MODEL_FILE_NAME
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
import com.localfind.core.settings.HardwareAccelerator
import java.io.File


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
    val configuredAccelerator: HardwareAccelerator = HardwareAccelerator.NPU,
    preferredModelName: String? = null
) : EmbeddingModel, AutoCloseable {
    private val embedder: UniversalEmbedder

    /** Concrete accelerator in use after resolving any dynamic policy (e.g. [HardwareAccelerator.SMART]). */
    override val effectiveAccelerator: HardwareAccelerator = configuredAccelerator.resolveEffective(context)

    /**
     * Whether NPU acceleration is actively enabled for the vision encoder.
     * When true, vision encoding executes via dedicated hardware NPU (e.g. Qualcomm Hexagon HTP).
     */
    val isNpuVisionEnabled: Boolean

    /**
     * Whether NPU acceleration is actively enabled for text encoding (e.g. PDF indexing, text files, queries).
     * When true, text encoding executes via dedicated hardware NPU.
     */
    val isNpuTextEnabled: Boolean

    /**
     * Whether GPU acceleration is actively enabled for the vision encoder.
     * When false, vision encoding executes via CPU (XNNPack).
     */
    val isGpuVisionEnabled: Boolean

    /**
     * [EmbeddingSpace] id of the vectors this instance produces. Reflects what actually loaded (an NPU request
     * that fell back to GPU/CPU gives another id), so files are tagged with the space they really got.
     */
    val embeddingSpaceId: String

    init {
        // Locate the model file (from Play AI Pack or internal storage), copying from staging paths if missing.
        val model = SocDetector.ensureModelFile(context, effectiveAccelerator, preferredModelName)

        require(model.exists() && model.length() > 0L) {
            "Embedding model not found at ${model.absolutePath}. " +
                    "For development on emulator or device, push the model file using:\n" +
                    "adb push .local/models/${model.name} /data/local/tmp/"
        }

        var createdEmbedder: UniversalEmbedder? = null
        var npuVisionSuccess = false
        var npuTextSuccess = false
        var gpuVisionSuccess = false

        if (effectiveAccelerator == HardwareAccelerator.NPU) {
            // Link NPU dispatch and QNN/NeuroPilot runtime libraries from nativeLibraryDir into filesDir
            SocDetector.ensureDispatchLibraries(context)
            model.parentFile?.takeIf { it != context.filesDir && it.canWrite() }?.let {
                SocDetector.ensureDispatchLibraries(context, it)
            }

            val npuBaseOptions = BaseOptions.builder()
                .setModelAssetPath(model.absolutePath)
                .setDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                .build()

            // Attempt Text=NPU + Vision=NPU (Audio executes on CPU for DSP mel-spectrogram frontend)
            try {
                val textVisionOptions = commonOptions(context, npuBaseOptions)
                    .setTextDelegate(com.google.mediapipe.tasks.core.Delegate.NPU)
                    .setAudioDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                    .setVisionDelegate(com.google.mediapipe.tasks.core.Delegate.NPU)
                    .build()

                val candidate = UniversalEmbedder.createFromOptions(context, textVisionOptions)
                // Warm up text embedding to verify graph execution on NPU
                candidate.embedText("localfind npu warmup")
                createdEmbedder = candidate
                npuVisionSuccess = true
                npuTextSuccess = true
                Log.i(TAG, "UniversalEmbedder successfully initialized with Text=NPU and Vision=NPU (Audio=CPU).")
            } catch (t: Throwable) {
                Log.w(TAG, "Text+Vision NPU initialization or warmup failed: ${t.message}. Falling back to Vision-only NPU.", t)
            }

            // Fallback: Vision-only NPU (Text=CPU, Audio=CPU, Vision=NPU)
            if (createdEmbedder == null) {
                try {
                    val visionOnlyOptions = commonOptions(context, npuBaseOptions)
                        .setTextDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                        .setAudioDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                        .setVisionDelegate(com.google.mediapipe.tasks.core.Delegate.NPU)
                        .build()

                    createdEmbedder = UniversalEmbedder.createFromOptions(context, visionOnlyOptions)
                    npuVisionSuccess = true
                    npuTextSuccess = false
                    Log.i(TAG, "UniversalEmbedder successfully initialized with Vision-only NPU (Text=CPU, Audio=CPU).")
                } catch (t: Throwable) {
                    Log.w(TAG, "NPU vision acceleration unavailable or failed to initialize (${t.message}). Falling back to Smart Mode (GPU/CPU).", t)
                }
            }
        }

        // Resolve the Smart Mode target used when NPU init failed. The fallback loads the SAME model file as the
        // NPU path: the device's NPU-compiled .litertlm also runs on the GPU / CPU delegates, so NPU phones do
        // not need (or download) a second, generic model.
        val fallbackEffective = if (effectiveAccelerator == HardwareAccelerator.NPU) {
            HardwareAccelerator.SMART.resolveEffective(context)
        } else {
            effectiveAccelerator
        }
        val fallbackBaseOptions = BaseOptions.builder()
            .setModelAssetPath(model.absolutePath)
            .setDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
            .build()

        if (createdEmbedder == null && fallbackEffective == HardwareAccelerator.GPU) {
            // Attempt 1: Text=GPU + Vision=GPU (Audio=CPU)
            try {
                val fullGpuOptions = commonOptions(context, fallbackBaseOptions)
                    .setTextDelegate(com.google.mediapipe.tasks.core.Delegate.GPU)
                    .setAudioDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                    .setVisionDelegate(com.google.mediapipe.tasks.core.Delegate.GPU)
                    .build()

                val candidate = UniversalEmbedder.createFromOptions(context, fullGpuOptions)
                // Warm up text embedding to verify graph execution on GPU
                candidate.embedText("localfind gpu warmup")
                createdEmbedder = candidate
                gpuVisionSuccess = true
                Log.i(TAG, "UniversalEmbedder initialized with GPU text and vision acceleration (configured=$configuredAccelerator, effective=$fallbackEffective).")
            } catch (t: Throwable) {
                Log.w(TAG, "GPU text acceleration unavailable or failed to initialize (${t.message}). Falling back to GPU vision only.", t)
            }

            // Attempt 2: Vision-only GPU (Text=CPU, Audio=CPU, Vision=GPU)
            if (createdEmbedder == null) {
                try {
                    val visionOnlyGpuOptions = commonOptions(context, fallbackBaseOptions)
                        .setTextDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                        .setAudioDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                        .setVisionDelegate(com.google.mediapipe.tasks.core.Delegate.GPU)
                        .build()

                    createdEmbedder = UniversalEmbedder.createFromOptions(context, visionOnlyGpuOptions)
                    gpuVisionSuccess = true
                    Log.i(TAG, "UniversalEmbedder initialized with GPU vision acceleration (configured=$configuredAccelerator, effective=$fallbackEffective).")
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU vision acceleration unavailable or failed to initialize (${t.message}). Falling back to CPU vision.", t)
                }
            }
        }

        if (createdEmbedder == null) {
            // CPU mode or fallback: All modalities execute via CPU (XNNPack).
            val cpuOptions = commonOptions(context, fallbackBaseOptions)
                .setTextDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                .setAudioDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                .setVisionDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                .build()

            createdEmbedder = UniversalEmbedder.createFromOptions(context, cpuOptions)
            Log.i(TAG, "UniversalEmbedder initialized with CPU vision delegate (configured=$configuredAccelerator, effective=$fallbackEffective).")
        }

        embedder = createdEmbedder
        isNpuVisionEnabled = npuVisionSuccess
        isNpuTextEnabled = npuTextSuccess
        isGpuVisionEnabled = gpuVisionSuccess
        embeddingSpaceId = EmbeddingSpace.id(
            activationDataType = ACTIVATION_DATA_TYPE.name,
            modelBytes = model.length(),
            textOnNpu = npuTextSuccess,
            visionOnNpu = npuVisionSuccess
        )
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
    override fun embedText(text: String): FloatArray = synchronized(this) {
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
    override fun embedImage(bitmap: Bitmap): FloatArray = synchronized(this) {
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
    override fun embedAudio(samples: ShortArray): FloatArray = synchronized(this) {
        embedder.embedAudio(toAudioData(samples)).embeddings()[0].floatEmbedding()
    }

    /**
     * Wraps 16 kHz mono PCM samples as a content part for [embedVideoWindow].
     */
    override fun audioPart(samples: ShortArray): AudioPart = AudioPart(toAudioData(samples))

    /**
     * Embeds one video window as a single vector from interleaved parts, e.g.
     * `[time] audio-slice [time] frame-jpeg [time] audio-slice [time] frame-jpeg`
     * (GOOGLE_AI_EDGE_GALLERY.md §6.2). The model has no dedicated video input,
     * so a video window is represented as frames plus audio in one sequence.
     *
     * Synchronized on `this` to ensure thread-safe native inference.
     *
     * @param parts `TextPart` time markers, [AudioPart] slices and `ImagePart` JPEG frames.
     * @return FloatArray of 768 elements representing the semantic vector.
     */
    override fun embedVideoWindow(parts: List<Part>): FloatArray = synchronized(this) {
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

        /** Activation precision; part of the [EmbeddingSpace] id because it changes the vectors. */
        private val ACTIVATION_DATA_TYPE = UniversalEmbedderOptions.ActivationDataType.FLOAT32

        /**
         * Options shared by every accelerator path, so they cannot drift apart:
         * - L2 normalization, so cosine similarity is a plain dot product.
         * - The vision token budget.
         * - A compiled-model cache dir, so GPU / NPU graphs are not recompiled on every model load
         *   (matters most when Smart mode swaps the embedder).
         * - FLOAT32 activations, pinned explicitly (it is the library default). The precision changes the
         *   vectors, so it must not change silently with a library update.
         */
        private fun commonOptions(context: Context, baseOptions: BaseOptions) =
            UniversalEmbedderOptions.builder()
                .setBaseOptions(baseOptions)
                .setL2Normalize(true)
                .setVisionTokensPerImage(DEFAULT_VISION_TOKENS_PER_IMAGE)
                .setCacheDir(context.cacheDir.absolutePath)
                .setActivationDataType(ACTIVATION_DATA_TYPE)

        /**
         * Ensures that the on-device model file exists in [context.filesDir].
         * Delegates to [SocDetector.ensureModelFile].
         */
        fun ensureModelFile(context: Context, modelName: String = MODEL_FILE_NAME): File {
            return SocDetector.ensureModelFile(context, modelName = modelName)
        }
    }
}
