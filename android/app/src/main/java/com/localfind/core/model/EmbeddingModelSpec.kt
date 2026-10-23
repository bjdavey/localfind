package com.localfind.core.model

/**
 * Default base on-device embedding model file name.
 *
 * Model details:
 * - EmbeddingGemma 2 740M multimodal (text, image, audio)
 * - Output dimensionality: 768 float values per embedding
 * - Runtime delivery:
 *     - Production: Google Play AI Pack (fast-follow, device-targeted)
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

/** Dimension of the embeddings output by the EmbeddingGemma 2 740M model. */
const val EMBEDDING_DIMENSION = 768
