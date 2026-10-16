package com.localfind.embed

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.AudioPart
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Part
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
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
 *
 * Implements [AutoCloseable] to ensure underlying native C++ LiteRT resources are promptly freed.
 */
class Embedder(context: Context) : AutoCloseable {
    private val embedder: UniversalEmbedder

    init {
        // Locate the model file in the application's internal files directory
        val model = File(context.filesDir, MODEL_FILE_NAME)
        require(model.exists()) {
            "Embedding model not found at ${model.absolutePath}. " +
                    "For development, push the model file using adb push."
        }

        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(model.absolutePath)
            .build()

        // Set L2 normalization to true so vectors have unit length (norm = 1.0),
        // and set visionTokensPerImage to 70 for fast vision encoding.
        val options = UniversalEmbedderOptions.builder()
            .setBaseOptions(baseOptions)
            .setL2Normalize(true)
            .setVisionTokensPerImage(DEFAULT_VISION_TOKENS_PER_IMAGE)
            .build()

        embedder = UniversalEmbedder.createFromOptions(context, options)
    }

    /**
     * Converts a text string or passage into a 768-dimensional normalized float embedding vector.
     *
     * @param text The input passage or search query string to embed.
     * @return FloatArray of 768 elements representing the semantic vector.
     */
    fun embedText(text: String): FloatArray =
        embedder.embedText(text).embeddings()[0].floatEmbedding()

    /**
     * Converts an image [Bitmap] into a 768-dimensional normalized float embedding vector
     * in the same shared multimodal vector space as text.
     *
     * Under the hood, wraps the [Bitmap] in a MediaPipe [com.google.mediapipe.framework.image.MPImage]
     * via [BitmapImageBuilder] and invokes [UniversalEmbedder.embedImage], which encodes the bitmap
     * pixels into a 32-bit BGRA TGA byte buffer and runs the EmbeddingGemma 2 vision encoder.
     *
     * @param bitmap The decoded, upright ARGB_8888 image bitmap to embed.
     * @return FloatArray of 768 elements representing the semantic vector.
     */
    fun embedImage(bitmap: Bitmap): FloatArray =
        BitmapImageBuilder(bitmap).build().use { mpImage ->
            embedder.embedImage(mpImage).embeddings()[0].floatEmbedding()
        }

    /**
     * Converts a window of audio into a 768-dimensional normalized float embedding vector
     * in the same shared multimodal vector space as text and images.
     *
     * @param samples 16 kHz mono signed 16-bit PCM samples (the rate EmbeddingGemma's
     *   mel-spectrogram frontend expects).
     * @return FloatArray of 768 elements representing the semantic vector.
     */
    fun embedAudio(samples: ShortArray): FloatArray =
        embedder.embedAudio(toAudioData(samples)).embeddings()[0].floatEmbedding()

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
     * @param parts [TextPart] time markers, [AudioPart] slices and [ImagePart] JPEG frames.
     * @return FloatArray of 768 elements representing the semantic vector.
     */
    fun embedVideoWindow(parts: List<Part>): FloatArray =
        embedder.embedContent(parts).embeddings()[0].floatEmbedding()

    private fun toAudioData(samples: ShortArray): AudioData {
        val format = AudioData.AudioDataFormat.builder()
            .setNumOfChannels(1)
            .setSampleRate(16_000f)
            .build()
        return AudioData.create(format, samples.size).also { it.load(samples) }
    }

    /**
     * Releases native memory and interpreter instances allocated by MediaPipe Tasks.
     */
    override fun close() = embedder.close()
}
