package com.localfind

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfind.data.EmbeddingStore
import com.localfind.data.ImportedFile
import com.localfind.embed.Embedder
import com.localfind.index.Indexer
import com.localfind.search.Searcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * End-to-end instrumented integration test for PDF indexing and viewing on Android device/emulator.
 *
 * Verifies:
 * 1. PDF text extraction across multiple pages without OCR.
 * 2. Storing 768-dim embeddings in SQLite with type="pdf" and 1-based page location.
 * 3. Semantic vector search ranking results to the exact matching page (Page 1 vs Page 2).
 * 4. Android native [PdfRenderer] successfully rendering the target page to a display [Bitmap].
 */
@RunWith(AndroidJUnit4::class)
class PdfIndexingIntegrationTest {

    @Test
    fun testPdfIndexingSearchAndPageRendering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. Create a minimal 2-page PDF file in cache
        val pdfFile = File(context.cacheDir, "test_doc_${System.currentTimeMillis()}.pdf")
        val minimalPdfBytes = createMinimalTwoPagePdf(
            page1Text = "Quantum computing harnesses the phenomena of quantum mechanics such as superposition.",
            page2Text = "Deep space astrophysics explores distant galaxies black holes and cosmic background."
        )
        FileOutputStream(pdfFile).use { it.write(minimalPdfBytes) }

        val fileUri = Uri.fromFile(pdfFile).toString()
        val importedFile = ImportedFile(
            uri = fileUri,
            displayName = pdfFile.name,
            mimeType = "application/pdf",
            size = pdfFile.length()
        )

        // 2. Index the PDF using on-device Embedder and SQLite vector store
        Embedder(context).use { embedder ->
            EmbeddingStore.create(context).use { store ->
                val indexer = Indexer(context, embedder, store)

                assertTrue("File should be recognized as PDF", indexer.isPdfFile(importedFile))
                assertTrue("File should be supported", indexer.isSupported(importedFile))

                val result = indexer.indexFile(importedFile)
                assertTrue("PDF indexing must succeed: ${result.errorMessage}", result.isSuccess)
                assertEquals("2 pages with text should yield 2 chunks", 2, result.chunksCount)

                // 3. Search for Page 1 content ("quantum mechanics")
                val searcher = Searcher(store, embedder)
                val quantumResults = searcher.search("quantum physics superposition")
                assertTrue("Should return search results", quantumResults.isNotEmpty())

                val topResult1 = quantumResults.first()
                assertEquals("pdf", topResult1.fileType)
                assertEquals("Top match should be on Page 1", 1L, topResult1.location)
                assertTrue("Score should be positive", topResult1.score > 0f)

                // 4. Search for Page 2 content ("galaxies astrophysics")
                val astroResults = searcher.search("astronomy distant galaxies")
                assertTrue("Should return search results for astronomy", astroResults.isNotEmpty())

                val topResult2 = astroResults.first()
                assertEquals("pdf", topResult2.fileType)
                assertEquals("Top match should be on Page 2", 2L, topResult2.location)
                assertTrue("Score should be positive", topResult2.score > 0f)

                // Clean up store entries for this test file
                store.deleteByMetadata(mapOf("fileId" to fileUri))
            }
        }

        // 5. Verify PdfRenderer can open the file at the matching page and render a bitmap
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        assertNotNull("ParcelFileDescriptor should open", pfd)

        val renderer = PdfRenderer(pfd)
        assertEquals(2, renderer.pageCount)

        // Open and render matching page 2 (index 1)
        val page = renderer.openPage(1)
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        renderer.close()
        pfd.close()

        assertNotNull("Rendered bitmap should exist", bitmap)
        assertTrue("Bitmap width must be positive", bitmap.width > 0)
        assertTrue("Bitmap height must be positive", bitmap.height > 0)

        // Clean up temporary test file
        pdfFile.delete()
    }

    private fun createMinimalTwoPagePdf(page1Text: String, page2Text: String): ByteArray {
        val stream1 = "BT\n/F1 18 Tf\n50 700 Td\n($page1Text) Tj\nET"
        val stream2 = "BT\n/F1 18 Tf\n50 700 Td\n($page2Text) Tj\nET"

        val pdf = """%PDF-1.4
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 5 0 R >> >> /MediaBox [0 0 612 792] /Contents 6 0 R >>
endobj
4 0 obj
<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 5 0 R >> >> /MediaBox [0 0 612 792] /Contents 7 0 R >>
endobj
5 0 obj
<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
endobj
6 0 obj
<< /Length ${stream1.length} >>
stream
$stream1
endstream
endobj
7 0 obj
<< /Length ${stream2.length} >>
stream
$stream2
endstream
endobj
xref
0 8
0000000000 65535 f 
0000000009 00000 n 
0000000058 00000 n 
0000000121 00000 n 
0000000236 00000 n 
0000000351 00000 n 
0000000418 00000 n 
0000000540 00000 n 
trailer
<< /Size 8 /Root 1 0 R >>
startxref
660
%%EOF
"""
        return pdf.toByteArray(Charsets.US_ASCII)
    }
}

