package com.localfind.core.model

/**
 * Identity of the "embedding space" a vector was produced in.
 *
 * The same text or image embedded on the NPU, on the GPU/CPU, with another precision or with another model
 * file gives slightly different vectors. Mixing them in one index lowers cosine scores between a query and
 * files indexed with another setup, and the fixed similarity threshold then drops valid matches. Recording the
 * id per file lets the app notice a change and offer to re-index the affected files.
 *
 * GPU and CPU are deliberately ONE class ("gpu-cpu"): Smart mode switches between them while the screen is
 * on/off, even in the middle of a file, and that must not mark the library as outdated. (Assumption: their
 * vectors agree closely; this has not been measured.) Audio always runs on the CPU, so it is not part of the id.
 */
object EmbeddingSpace {

    /**
     * @param activationDataType Precision of the activations (e.g. `FLOAT32`).
     * @param modelBytes Size of the loaded model file; changes when an app update ships a new model.
     * @param textOnNpu Whether text is embedded on the NPU.
     * @param visionOnNpu Whether images / video frames are embedded on the NPU.
     */
    fun id(activationDataType: String, modelBytes: Long, textOnNpu: Boolean, visionOnNpu: Boolean): String =
        "$activationDataType|m$modelBytes|text=${backend(textOnNpu)}|vision=${backend(visionOnNpu)}"

    /**
     * True when a file indexed in [fileSpaceId] should be re-indexed for the [currentSpaceId] setup.
     * A null id (indexed before ids were recorded) is unknown and counts as compatible, so existing
     * libraries are not flagged wholesale.
     */
    fun isStale(fileSpaceId: String?, currentSpaceId: String?): Boolean =
        fileSpaceId != null && currentSpaceId != null && fileSpaceId != currentSpaceId

    private fun backend(onNpu: Boolean) = if (onNpu) "npu" else "gpu-cpu"
}
