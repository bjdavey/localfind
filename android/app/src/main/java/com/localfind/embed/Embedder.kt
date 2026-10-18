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
import com.localfind.data.Accelerator
import java.io.File

/**
 * File name for the on-device embedding model.
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
 * 5. Configuring hardware acceleration (CPU, GPU, NPU) depending on device support.
 *
 * Implements [AutoCloseable] to ensure underlying native C++ LiteRT resources are promptly freed.
 */
class Embedder(
    context: Context,
    val accelerator: Accelerator = Accelerator.CPU
) : AutoCloseable {
    private val embedder: UniversalEmbedder

    init {
        // Locate the model file in the application's internal files directory,
        // copying from fallback emulator/dev paths if missing.
        val model = ensureModelFile(context)
        require(model.exists() && model.length() > 0L) {
            "Embedding model not found at ${model.absolutePath}. " +
                    "For development on emulator or device, push the model file using:\n" +
                    "adb push .local/models/$MODEL_FILE_NAME /data/local/tmp/"
        }

        val delegate = accelerator.toMediaPipeDelegate()

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(model.absolutePath)
            .setDelegate(delegate)
            .build()

        // Set L2 normalization to true so vectors have unit length (norm = 1.0),
        // and set visionTokensPerImage to 70 for fast vision encoding.
        // BaseOptions configures the global accelerator (CPU, GPU, NPU) for text and vision subgraphs,
        // while the audio spectrogram frontend executes on CPU where DSP operators are natively implemented.
        val optionsBuilder = UniversalEmbedderOptions.builder()
            .setBaseOptions(baseOptions)
            .setL2Normalize(true)
            .setVisionTokensPerImage(DEFAULT_VISION_TOKENS_PER_IMAGE)

        if (accelerator == Accelerator.GPU || accelerator == Accelerator.NPU) {
            optionsBuilder.setAudioDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
        }

        embedder = UniversalEmbedder.createFromOptions(context, optionsBuilder.build())
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
         *
         * If missing from internal storage, checks fallback emulator development locations:
         * 1. `/data/local/tmp/embeddinggemma-2-740m.litertlm` (destination of adb push)
         * 2. `/sdcard/Download/embeddinggemma-2-740m.litertlm`
         * 3. `/sdcard/embeddinggemma-2-740m.litertlm`
         *
         * If found in any of those locations, copies it into internal app storage via an intermediate
         * `.tmp` file and atomic rename, ensuring clean self-healing on emulator startup.
         *
         * @return [File] pointing to the target model in [context.filesDir].
         */
        fun ensureModelFile(context: Context): File {
            val target = File(context.filesDir, MODEL_FILE_NAME)
            if (target.exists() && target.length() > 0L) {
                return target
            }

            // Check candidate development locations (emulator / adb push destination)
            val candidateSources = listOf(
                File("/data/local/tmp", MODEL_FILE_NAME),
                File("/sdcard/Download", MODEL_FILE_NAME),
                File("/sdcard", MODEL_FILE_NAME)
            )

            val source = candidateSources.firstOrNull { it.exists() && it.canRead() && it.length() > 0L }
            if (source != null) {
                try {
                    Log.i(TAG, "Found model at ${source.absolutePath} (${source.length()} bytes). Copying to internal storage ${target.absolutePath}...")
                    val tempFile = File(context.filesDir, "$MODEL_FILE_NAME.tmp")
                    source.inputStream().use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tempFile.renameTo(target)) {
                        Log.i(TAG, "Successfully copied model to ${target.absolutePath} (${target.length()} bytes)")
                    } else {
                        Log.w(TAG, "Failed to rename temp file to ${target.name}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error copying model from ${source.absolutePath} to ${target.absolutePath}", e)
                }
            }

            return target
        }

        /**
         * Probes device hardware acceleration support by verifying which accelerators
         * can safely execute the on-device embedding model.
         *
         * Model architecture notes:
         * - EmbeddingGemma 2 740M multimodal contains interleaved audio and vision subgraphs.
         * - LiteRT LM requires CPU (XNNPack) for ops that cannot be accelerated on NPU.
         * - Current mobile GPU drivers (e.g. Qualcomm Adreno Vulkan) crash during WebGPU shader
         *   compilation for these multimodal subgraphs, so GPU is safely marked unsupported.
         * - CPU is the universal, fully-accelerated execution engine via XNNPack.
         *
         * @return Set of [Accelerator]s verified to work on this device.
         */
        fun probeSupportedAccelerators(context: Context): Set<Accelerator> {
            val supported = mutableSetOf(Accelerator.CPU)

            // Probe NPU: attempts initialization and catches unsupported op exceptions
            try {
                Embedder(context, Accelerator.NPU).use {
                    supported.add(Accelerator.NPU)
                    Log.i(TAG, "NPU acceleration is verified supported on this device.")
                }
            } catch (e: Throwable) {
                Log.d(TAG, "NPU acceleration is not supported on this device: ${e.message}")
            }

            // Note on GPU: LiteRT / MediaPipe's GPU delegate for multimodal EmbeddingGemma
            // compiles WebGPU/Vulkan compute shaders at runtime. On mobile Vulkan drivers
            // (e.g. Qualcomm Snapdragon 8 Gen 3 Adreno 750), vkCreateComputePipelines encounters
            // a native null-pointer dereference inside libllvm-qgl.so (CreateQGLCProgram), which raises
            // an uncatchable SIGSEGV signal. On Android Emulator, GFXStream hangs during shader compile.
            // Therefore, GPU acceleration is safely excluded from runtime probing.
            Log.d(TAG, "GPU acceleration excluded from probing due to known Vulkan compute driver incompatibility.")

            return supported
        }
    }
}
