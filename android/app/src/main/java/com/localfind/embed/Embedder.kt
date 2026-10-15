package com.localfind.embed

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder
import com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions
import java.io.File

/** Name of the model file. Dev: `adb push` it to the app's files dir (see docs/ARCHITECTURE.md). */
const val MODEL_FILE_NAME = "embeddinggemma-2-740m.litertlm"

class Embedder(context: Context) : AutoCloseable {
    private val embedder: UniversalEmbedder

    init {
        val model = File(context.filesDir, MODEL_FILE_NAME)
        require(model.exists()) { "Model not found: ${model.absolutePath}" }
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(model.absolutePath)
            .build()
        val options = UniversalEmbedderOptions.builder()
            .setBaseOptions(baseOptions)
            .setL2Normalize(true)
            .build()
        embedder = UniversalEmbedder.createFromOptions(context, options)
    }

    fun embedText(text: String): FloatArray =
        embedder.embedText(text).embeddings()[0].floatEmbedding()

    override fun close() = embedder.close()
}

