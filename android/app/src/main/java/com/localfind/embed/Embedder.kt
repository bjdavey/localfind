package com.localfind.embed

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
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
 * Wrapper around MediaPipe's [UniversalEmbedder].
 *
 * Responsible for:
 * 1. Loading the local weights file from the app's private files directory.
 * 2. Configuring L2 normalization so that cosine similarity can be computed via dot product.
 * 3. Producing 768-dimensional float embeddings for input text strings and passages.
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

        // Configure MediaPipe Tasks base options pointing to the on-disk model path
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(model.absolutePath)
            .build()

        // Set L2 normalization to true:
        // This ensures vectors have unit length (norm = 1.0), which makes cosine distance
        // equivalent to simple Euclidean or dot product comparisons in the vector store.
        val options = UniversalEmbedderOptions.builder()
            .setBaseOptions(baseOptions)
            .setL2Normalize(true)
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
     * Releases native memory and interpreter instances allocated by MediaPipe Tasks.
     */
    override fun close() = embedder.close()
}
