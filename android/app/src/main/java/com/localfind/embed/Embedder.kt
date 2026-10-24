package com.localfind.embed

import com.localfind.core.model.DEFAULT_VISION_TOKENS_PER_IMAGE
import com.localfind.core.model.EmbeddingSpace
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.AudioPart
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.core.Part
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import com.localfind.core.settings.HardwareAccelerator
import java.io.File


/**
 * Wrapper around MediaPipe's [UniversalEmbedder] for ONE model file on ONE accelerator.
 *
 * Responsible for:
 * 1. Loading [model] with text and vision on [effectiveAccelerator] (audio always on CPU, see [create]).
 * 2. Verifying the load with one text embedding, so a model that cannot run there fails here, not at search.
 * 3. Configuring L2 normalization so that cosine similarity can be computed via dot product.
 * 4. Setting the 70-token vision budget for fast image encoding.
 * 5. Producing 768-dimensional float embeddings for text, images, audio and video windows.
 *
 * It makes no choices: which model and which accelerator (and the fallbacks) are decided by the caller, see
 * [open] and `HardwareAccelerator.loadOrder`. Implements [AutoCloseable] to free native LiteRT memory promptly.
 *
 * @throws Exception from the constructor when the model cannot be loaded or run on [effectiveAccelerator].
 */
class Embedder(
    context: Context,
    val model: File,
    override val effectiveAccelerator: HardwareAccelerator
) : EmbeddingModel, AutoCloseable {
    private val embedder: UniversalEmbedder

    /**
     * [EmbeddingSpace] id of the vectors this instance produces (NPU vectors differ from GPU/CPU ones), so files
     * are tagged with the space they really got.
     */
    val embeddingSpaceId: String

    init {
        require(effectiveAccelerator != HardwareAccelerator.SMART) { "Smart must be resolved before loading" }
        require(model.exists() && model.length() > 0L) { "Embedding model not found at ${model.absolutePath}" }

        val onNpu = effectiveAccelerator == HardwareAccelerator.NPU
        if (onNpu) {
            // Link NPU dispatch and QNN/NeuroPilot runtime libraries from nativeLibraryDir into filesDir
            SocDetector.ensureDispatchLibraries(context)
            model.parentFile?.takeIf { it != context.filesDir && it.canWrite() }?.let {
                SocDetector.ensureDispatchLibraries(context, it)
            }
        }
        embedder = create(context, model, effectiveAccelerator)
        Log.i(TAG, "UniversalEmbedder initialized: text+vision=$effectiveAccelerator, audio=CPU, model=${model.name}")
        embeddingSpaceId = EmbeddingSpace.id(
            activationDataType = ACTIVATION_PRECISION,
            modelBytes = model.length(),
            textOnNpu = onNpu,
            visionOnNpu = onNpu
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

        /**
         * Activation precision label for the [EmbeddingSpace] id. Deliberately NOT set on the options: when unset,
         * UniversalEmbedder 1.1.1 passes null and the engine uses the precision the model was built for. Forcing
         * FLOAT32 (the value Google's docs name as default) made the NPU text graph fail on a Galaxy S24 Ultra
         * (SM8650). If a precision is ever pinned, change this label with it.
         */
        private const val ACTIVATION_PRECISION = "ENGINE_DEFAULT"

        /**
         * Loads the first accelerator of [order] that works and returns it, the way the app loads its model.
         *
         * NPU models go with NPU, the universal model with GPU/CPU ([ModelFiles.Models]); an accelerator whose model
         * is missing is skipped. A failed NPU load is retried once, because the NPU can fail transiently (busy with
         * another app, memory pressure). Before the retry the model's cache files are cleared in case one is stale;
         * NPU models are precompiled, so on SM8650 that cache only holds XNNPack weights of the CPU audio encoder.
         *
         * @throws IllegalStateException when nothing in [order] loads, naming every attempt and its error.
         */
        fun open(context: Context, models: ModelFiles.Models, order: List<HardwareAccelerator>): Embedder {
            check(order.isNotEmpty()) { "No installed model can run on this device" }
            val failures = mutableListOf<String>()
            for (accelerator in order) {
                val model = (if (accelerator == HardwareAccelerator.NPU) models.npu else models.universal) ?: continue
                val attempts = if (accelerator == HardwareAccelerator.NPU) 2 else 1
                for (attempt in 1..attempts) {
                    if (attempt > 1) clearCompiledCache(context, model)
                    try {
                        return Embedder(context, model, accelerator)
                    } catch (t: Throwable) {
                        val reason = t.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: t.javaClass.simpleName
                        Log.w(TAG, "Loading ${model.name} on $accelerator failed (attempt $attempt/$attempts): $reason", t)
                        failures += "$accelerator: $reason"
                    }
                }
            }
            throw IllegalStateException("Could not load the model (${failures.joinToString("; ")})")
        }

        /**
         * Deletes the cache files of [model] in `cacheDir` (named after the model file: XNNPack weight caches and
         * compiled graphs); they are rebuilt on the next load.
         */
        private fun clearCompiledCache(context: Context, model: File) {
            context.cacheDir.listFiles { file -> file.name.startsWith(model.name) }?.forEach { it.delete() }
            Log.i(TAG, "Cleared the compiled cache of ${model.name} before retrying")
        }

        /**
         * Builds the [UniversalEmbedder] with these options shared by every accelerator, so they cannot drift apart:
         * - Text and vision on [accelerator]; audio always on CPU (the DSP mel-spectrogram frontend; even NPU models
         *   keep a plain XNNPack audio encoder).
         * - L2 normalization, so cosine similarity is a plain dot product.
         * - The vision token budget.
         * - A compiled-model cache dir, so GPU / NPU graphs are not recompiled on every model load
         *   (matters most when Smart mode swaps the embedder).
         * - No activation type: see [ACTIVATION_PRECISION] (pinning FLOAT32 broke the NPU text graph).
         *
         * Then runs one text embedding, because a model can load fine on a delegate it cannot execute (an
         * NPU-compiled model on CPU fails only at the first inference). Closes the instance when that fails, so a
         * failed attempt does not leak ~500MB of native memory.
         */
        private fun create(context: Context, model: File, accelerator: HardwareAccelerator): UniversalEmbedder {
            val delegate = when (accelerator) {
                HardwareAccelerator.NPU -> Delegate.NPU
                HardwareAccelerator.GPU -> Delegate.GPU
                else -> Delegate.CPU
            }
            val options = UniversalEmbedderOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(model.absolutePath)
                        .setDelegate(Delegate.CPU)
                        .build()
                )
                .setTextDelegate(delegate)
                .setVisionDelegate(delegate)
                .setAudioDelegate(Delegate.CPU)
                .setL2Normalize(true)
                .setVisionTokensPerImage(DEFAULT_VISION_TOKENS_PER_IMAGE)
                .setCacheDir(context.cacheDir.absolutePath)
                .build()
            val candidate = UniversalEmbedder.createFromOptions(context, options)
            try {
                candidate.embedText("localfind $accelerator warmup")
            } catch (t: Throwable) {
                runCatching { candidate.close() }
                throw t
            }
            return candidate
        }
    }
}
