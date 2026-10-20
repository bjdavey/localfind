package com.localfind.ui.viewer

import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.view.PdfView
import androidx.pdf.viewer.fragment.PdfViewerFragment

/**
 * Custom [PdfViewerFragment] using the official AndroidX Jetpack PDF library (`androidx.pdf`).
 *
 * Implements:
 * - High-performance paginated PDF document rendering with sandboxed execution.
 * - Fluid pinch-to-zoom and continuous vertical scrolling across pages.
 * - In-document keyword search with match highlighting ([isTextSearchActive]).
 * - Native text selection and copy.
 * - Programmatic jump to [initialPage] upon document layout.
 */
@OptIn(ExperimentalPdfApi::class)
class LocalFindPdfViewerFragment : PdfViewerFragment() {

    /** 1-based page number to scroll to when the document is first rendered. */
    var initialPage: Int = 1

    /** Callback invoked on the main thread after the document loads successfully. */
    var onLoadSuccess: (() -> Unit)? = null

    /** Callback invoked if document loading fails. */
    var onLoadError: ((Throwable) -> Unit)? = null

    private var hasScrolledToInitial: Boolean = false

    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        val targetPage = initialPage
        if (!hasScrolledToInitial && targetPage > 1) {
            hasScrolledToInitial = true
            pdfView.post {
                try {
                    // scrollToPage in PdfView is 0-based
                    pdfView.scrollToPage(targetPage - 1)
                } catch (_: Exception) {
                    // Ignore if target page index is out of bounds
                }
            }
        }
    }

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        onLoadSuccess?.invoke()
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        onLoadError?.invoke(error)
    }
}
