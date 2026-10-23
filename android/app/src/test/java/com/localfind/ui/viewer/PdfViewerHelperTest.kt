package com.localfind.ui.viewer

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
}
