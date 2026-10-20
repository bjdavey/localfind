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
    fun isJetpackPdfSupported_requiresAndroidSAndExtension13() {
        // Below Android S (API < 31) -> false regardless of extension level
        assertFalse(PdfViewerHelper.isJetpackPdfSupported(sdkInt = 30) { 13 })

        // Android S (API 31) with extension < 13 -> false
        assertFalse(PdfViewerHelper.isJetpackPdfSupported(sdkInt = 31) { 12 })

        // Android S (API 31) with extension 13 -> true
        assertTrue(PdfViewerHelper.isJetpackPdfSupported(sdkInt = 31) { 13 })

        // Android 14/15 (API 34/35) with extension >= 13 -> true
        assertTrue(PdfViewerHelper.isJetpackPdfSupported(sdkInt = 35) { 14 })

        // Exception during extension check -> safely false
        assertFalse(PdfViewerHelper.isJetpackPdfSupported(sdkInt = 31) { throw RuntimeException("Unsupported") })
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
    fun clampZoom_boundsWithinMinAndMax() {
        assertEquals(1.5f, PdfViewerHelper.clampZoom(1f, 1.5f, 1f, 4f), 0.001f)
        assertEquals(1f, PdfViewerHelper.clampZoom(1f, 0.5f, 1f, 4f), 0.001f)
        assertEquals(4f, PdfViewerHelper.clampZoom(3f, 2f, 1f, 4f), 0.001f)
    }
}

