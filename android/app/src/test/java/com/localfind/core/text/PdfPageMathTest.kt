package com.localfind.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [PdfPageMath]. */
class PdfPageMathTest {

    @Test
    fun clampPage_boundsCorrectly() {
        assertEquals(1, PdfPageMath.clampPage(0, 10))
        assertEquals(1, PdfPageMath.clampPage(1, 10))
        assertEquals(5, PdfPageMath.clampPage(5, 10))
        assertEquals(10, PdfPageMath.clampPage(10, 10))
        assertEquals(10, PdfPageMath.clampPage(25, 10))
        assertEquals(1, PdfPageMath.clampPage(5, 0))
    }

    @Test
    fun toZeroBasedPage_boundsCorrectly() {
        assertEquals(0, PdfPageMath.toZeroBasedPage(0, 10))
        assertEquals(0, PdfPageMath.toZeroBasedPage(1, 10))
        assertEquals(4, PdfPageMath.toZeroBasedPage(5, 10))
        assertEquals(9, PdfPageMath.toZeroBasedPage(10, 10))
        assertEquals(9, PdfPageMath.toZeroBasedPage(25, 10))
        assertEquals(0, PdfPageMath.toZeroBasedPage(5, 0))
    }

    @Test
    fun clampZoom_boundsWithinMinAndMax() {
        assertEquals(1.5f, PdfPageMath.clampZoom(1f, 1.5f, 1f, 4f), 0.001f)
        assertEquals(1f, PdfPageMath.clampZoom(1f, 0.5f, 1f, 4f), 0.001f)
        assertEquals(4f, PdfPageMath.clampZoom(3f, 2f, 1f, 4f), 0.001f)
    }
}
