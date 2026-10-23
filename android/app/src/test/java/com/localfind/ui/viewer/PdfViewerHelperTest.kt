package com.localfind.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PdfViewerHelper].
 */
class PdfViewerHelperTest {

    @Test
    fun isJetpackPdfSupported_requiresExtension13() {
        // minSdk is 31, so only the SDK extension level (S extension >= 13) decides support
        assertFalse(PdfViewerHelper.isJetpackPdfSupported { 12 })
        assertTrue(PdfViewerHelper.isJetpackPdfSupported { 13 })
        assertTrue(PdfViewerHelper.isJetpackPdfSupported { 14 })

        // Exception during extension check -> safely false
        assertFalse(PdfViewerHelper.isJetpackPdfSupported { throw RuntimeException("Unsupported") })
    }

    @Test
    fun clampPage_boundsCorrectly() {
        assertEquals(1, PdfViewerHelper.clampPage(0, 10))
        assertEquals(1, PdfViewerHelper.clampPage(1, 10))
        assertEquals(5, PdfViewerHelper.clampPage(5, 10))
        assertEquals(10, PdfViewerHelper.clampPage(10, 10))
        assertEquals(10, PdfViewerHelper.clampPage(25, 10))
        assertEquals(1, PdfViewerHelper.clampPage(5, 0))
    }

    @Test
    fun toZeroBasedPage_boundsCorrectly() {
        assertEquals(0, PdfViewerHelper.toZeroBasedPage(0, 10))
        assertEquals(0, PdfViewerHelper.toZeroBasedPage(1, 10))
        assertEquals(4, PdfViewerHelper.toZeroBasedPage(5, 10))
        assertEquals(9, PdfViewerHelper.toZeroBasedPage(10, 10))
        assertEquals(9, PdfViewerHelper.toZeroBasedPage(25, 10))
        assertEquals(0, PdfViewerHelper.toZeroBasedPage(5, 0))
    }

    @Test
    fun clampZoom_boundsWithinMinAndMax() {
        assertEquals(1.5f, PdfViewerHelper.clampZoom(1f, 1.5f, 1f, 4f), 0.001f)
        assertEquals(1f, PdfViewerHelper.clampZoom(1f, 0.5f, 1f, 4f), 0.001f)
        assertEquals(4f, PdfViewerHelper.clampZoom(3f, 2f, 1f, 4f), 0.001f)
    }
}

