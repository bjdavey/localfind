package com.localfind.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [VectorMath]. */
class VectorMathTest {

    @Test
    fun cosineSimilarityMath() {
        // Identical vectors -> 1.0
        val v1 = floatArrayOf(3f, 4f)
        val v2 = floatArrayOf(3f, 4f)
        assertEquals(1.0f, VectorMath.cosineSimilarity(v1, v2), 0.0001f)

        // Scaled vectors in same direction -> 1.0
        val v3 = floatArrayOf(6f, 8f)
        assertEquals(1.0f, VectorMath.cosineSimilarity(v1, v3), 0.0001f)

        // Opposite vectors -> -1.0
        val vOpposite = floatArrayOf(-3f, -4f)
        assertEquals(-1.0f, VectorMath.cosineSimilarity(v1, vOpposite), 0.0001f)

        // Orthogonal vectors -> 0.0
        val vOrtho = floatArrayOf(-4f, 3f) // (3*-4 + 4*3) = 0
        assertEquals(0.0f, VectorMath.cosineSimilarity(v1, vOrtho), 0.0001f)

        // Empty or dimension mismatched -> 0.0
        assertEquals(0.0f, VectorMath.cosineSimilarity(floatArrayOf(), floatArrayOf(1f)), 0.0001f)
        assertEquals(0.0f, VectorMath.cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(1f)), 0.0001f)
    }
}
