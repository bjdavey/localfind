package com.localfind.core.indexing

import com.localfind.core.settings.DEFAULT_MAX_IMAGE_DIMENSION

/** Pure sizing math for decoding images before they are embedded. */
object ImageSizing {

    /**
     * Calculates bounded target dimensions (width, height) that fit within [maxDimension]
     * while preserving the original aspect ratio.
     *
     * @param width Original image width in pixels.
     * @param height Original image height in pixels.
     * @param maxDimension Maximum allowed width or height (default [DEFAULT_MAX_IMAGE_DIMENSION]).
     * @return Pair of (targetWidth, targetHeight), each guaranteed to be >= 1.
     */
    fun calculateTargetDimensions(
        width: Int,
        height: Int,
        maxDimension: Int = DEFAULT_MAX_IMAGE_DIMENSION
    ): Pair<Int, Int> {
        val safeW = width.coerceAtLeast(1)
        val safeH = height.coerceAtLeast(1)
        val safeMax = maxDimension.coerceAtLeast(1)
        val maxSide = maxOf(safeW, safeH)
        if (maxSide <= safeMax) {
            return safeW to safeH
        }
        val scale = safeMax.toFloat() / maxSide.toFloat()
        val targetW = (safeW * scale).toInt().coerceAtLeast(1)
        val targetH = (safeH * scale).toInt().coerceAtLeast(1)
        return targetW to targetH
    }
}
