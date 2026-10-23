package com.localfind.core.search

import kotlin.math.sqrt

/** Pure vector math for comparing embeddings. */
object VectorMath {

    /**
     * Computes the cosine similarity between two float vectors:
     *   similarity = (u · v) / (||u|| * ||v||)
     *
     * Returns a value between -1.0 and 1.0 (clamped). Returns 0.0 if vectors are empty
     * or dimension mismatched.
     */
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.isEmpty() || v2.isEmpty() || v1.size != v2.size) return 0f
        var dot = 0f
        var norm1 = 0f
        var norm2 = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            norm1 += v1[i] * v1[i]
            norm2 += v2[i] * v2[i]
        }
        val denominator = (sqrt(norm1.toDouble()) * sqrt(norm2.toDouble())).toFloat()
        return if (denominator > 0f) {
            (dot / denominator).coerceIn(-1f, 1f)
        } else {
            0f
        }
    }
}
