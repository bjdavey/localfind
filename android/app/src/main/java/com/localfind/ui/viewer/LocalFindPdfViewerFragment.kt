package com.localfind.ui.viewer

import android.os.Bundle
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.viewer.fragment.PdfViewerFragment

/**
 * Custom [PdfViewerFragment] using the official AndroidX Jetpack PDF library (`androidx.pdf`).
 *
 * Implements:
 * - High-performance paginated PDF document rendering with sandboxed execution.
 * - Fluid pinch-to-zoom and continuous vertical scrolling across pages.
 * - In-document keyword search with match highlighting ([isTextSearchActive]).
 * - Native text selection and copy.
 * - Programmatic jump to [initialPage] upon document layout and when search results are selected.
 */
@OptIn(ExperimentalPdfApi::class)
class LocalFindPdfViewerFragment : PdfViewerFragment() {

    /** 1-based page number to scroll to when the document is rendered or updated. */
    var initialPage: Int = 1
        set(value) {
            field = value
            if (isDocumentLoaded) {
                jumpToPage(value)
            }
        }

    /** Callback invoked on the main thread after the document loads successfully. */
    var onLoadSuccess: (() -> Unit)? = null

    /** Callback invoked if document loading fails. */
    var onLoadError: ((Throwable) -> Unit)? = null

    private var isDocumentLoaded: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val argsPage = arguments?.getInt("initialPage", 1) ?: 1
        if (argsPage >= 1) {
            initialPage = argsPage
        }
    }

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        isDocumentLoaded = true
        onLoadSuccess?.invoke()
        // Document has finished loading; jump to initialPage
        jumpToPage(initialPage)
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        isDocumentLoaded = false
        onLoadError?.invoke(error)
    }

    /**
     * Programmatically scrolls to a 1-based page number within the loaded document.
     */
    fun jumpToPage(pageOneBased: Int) {
        val targetPage = pageOneBased.coerceAtLeast(1)

        val runScroll = Runnable {
            try {
                val doc = pdfView.pdfDocument
                val count = doc?.pageCount ?: 0
                val zeroBasedPage = PdfViewerHelper.toZeroBasedPage(targetPage, count)
                pdfView.scrollToPage(zeroBasedPage)
            } catch (_: Exception) {
                // If page layout has not yet finished, retry once after a short delay
                view?.postDelayed({
                    try {
                        val doc = pdfView.pdfDocument
                        val count = doc?.pageCount ?: 0
                        val zeroBasedPage = PdfViewerHelper.toZeroBasedPage(targetPage, count)
                        pdfView.scrollToPage(zeroBasedPage)
                    } catch (_: Exception) {}
                }, 150)
            }
        }

        // Post on view message queue to ensure handleDocumentLoaded has set pdfView.setPdfDocument
        val v = view
        if (v != null) {
            v.post(runScroll)
        } else {
            pdfView.post(runScroll)
        }
    }
}
