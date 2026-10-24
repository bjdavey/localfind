package com.localfind

import com.localfind.core.model.FileTypes
import com.localfind.index.image.ImageIndexer
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfind.data.EmbeddingStore
import com.localfind.core.model.ImportedFile
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
 * End-to-end instrumented integration test for image indexing, multimodal search,
 * and bitmap decoding on an Android device/emulator.
 *
 * Verifies:
 * 1. Decoding and normalizing `.png` and `.jpg` images into `ARGB_8888` bitmaps.
 * 2. Embedding whole images with on-device EmbeddingGemma 2 (`Embedder.embedImage`)
 *    into the shared 768-dimensional vector space.
 * 3. Storing image records in `SqliteVectorStore` with `type="image"` and `location="0"`.
 * 4. Cross-modal text-to-image search ranking the matching image at the top.
 */
@RunWith(AndroidJUnit4::class)
class ImageIndexingIntegrationTest {

    @Test
    fun testImageIndexingAndCrossModalSearch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. Create two distinct test images (.png and .jpg) in cache directory
        val redPngFile = File(context.cacheDir, "test_red_${System.currentTimeMillis()}.png")
        val blueJpgFile = File(context.cacheDir, "test_blue_${System.currentTimeMillis()}.jpg")

        createRedStopImage(redPngFile, Bitmap.CompressFormat.PNG)
        createBlueSkyOceanImage(blueJpgFile, Bitmap.CompressFormat.JPEG)

        val redUri = Uri.fromFile(redPngFile)
        val blueUri = Uri.fromFile(blueJpgFile)

        // 2. Verify decodeNormalizedBitmap produces valid ARGB_8888 bitmaps
        val decodedRed = ImageIndexer.decodeNormalizedBitmap(context, redUri)
        assertNotNull("Decoded PNG bitmap must not be null", decodedRed)
        assertEquals(Bitmap.Config.ARGB_8888, decodedRed.config)
        decodedRed.recycle()

        val decodedBlue = ImageIndexer.decodeNormalizedBitmap(context, blueUri)
        assertNotNull("Decoded JPG bitmap must not be null", decodedBlue)
        assertEquals(Bitmap.Config.ARGB_8888, decodedBlue.config)
        decodedBlue.recycle()

        val redImported = ImportedFile(
            uri = redUri.toString(),
            displayName = redPngFile.name,
            mimeType = "image/png",
            size = redPngFile.length()
        )
        val blueImported = ImportedFile(
            uri = blueUri.toString(),
            displayName = blueJpgFile.name,
            mimeType = "image/jpeg",
            size = blueJpgFile.length()
        )

        // 3. Index both images using on-device Embedder and SqliteVectorStore
        openEmbedder(context).use { embedder ->
            EmbeddingStore.create(context).use { store ->
                val indexer = Indexer(context, embedder, store)

                assertTrue(indexer.isImageFile(redImported))
                assertTrue(indexer.isImageFile(blueImported))
                assertTrue(FileTypes.isSupported(redImported.displayName, redImported.mimeType))
                assertTrue(FileTypes.isSupported(blueImported.displayName, blueImported.mimeType))

                val resRed = indexer.indexFile(redImported)
                assertTrue("PNG indexing must succeed: ${resRed.errorMessage}", resRed.isSuccess)
                assertEquals("Whole image produces 1 chunk", 1, resRed.chunksCount)

                val resBlue = indexer.indexFile(blueImported)
                assertTrue("JPG indexing must succeed: ${resBlue.errorMessage}", resBlue.isSuccess)
                assertEquals("Whole image produces 1 chunk", 1, resBlue.chunksCount)

                // 4. Perform cross-modal text search for the red STOP sign image
                val searcher = Searcher(store, embedder)
                val redQueryResults = searcher.search(
                    query = "red stop sign",
                    topK = 5,
                    metadataFilter = mapOf("type" to "image")
                )
                assertTrue("Should return indexed image results", redQueryResults.isNotEmpty())

                val topRedMatch = redQueryResults.first()
                assertEquals("image", topRedMatch.fileType)
                assertEquals(0L, topRedMatch.location)
                assertEquals(redImported.uri, topRedMatch.fileUri)
                assertTrue("Cosine similarity score should be positive", topRedMatch.score > 0f)

                // Clean up test records from vector store
                store.deleteByMetadata(mapOf("fileId" to redImported.uri))
                store.deleteByMetadata(mapOf("fileId" to blueImported.uri))
            }
        }

        // Clean up temporary files
        redPngFile.delete()
        blueJpgFile.delete()
    }

    private fun createRedStopImage(file: File, format: Bitmap.CompressFormat) {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        canvas.drawCircle(128f, 128f, 100f, redPaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("STOP", 128f, 148f, textPaint)
        FileOutputStream(file).use { out ->
            bitmap.compress(format, 95, out)
        }
        bitmap.recycle()
    }

    private fun createBlueSkyOceanImage(file: File, format: Bitmap.CompressFormat) {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val skyPaint = Paint().apply { color = Color.rgb(135, 206, 235) }
        val oceanPaint = Paint().apply { color = Color.rgb(0, 50, 180) }
        canvas.drawRect(0f, 0f, 256f, 128f, skyPaint)
        canvas.drawRect(0f, 128f, 256f, 256f, oceanPaint)
        FileOutputStream(file).use { out ->
            bitmap.compress(format, 95, out)
        }
        bitmap.recycle()
    }
}

