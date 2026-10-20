package com.localfind.data

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Instrumented integration tests for [ShareImporter] verifying real Android Intent handling,
 * single and multi-file sharing, format rejection, and plain text notes.
 */
@RunWith(AndroidJUnit4::class)
class ShareImporterIntegrationTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun testProcessShareIntent_singleImageFile() {
        // Create sample image file
        val sampleFile = File(context.cacheDir, "shared_test_${System.currentTimeMillis()}.png")
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        FileOutputStream(sampleFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = Uri.fromFile(sampleFile)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        val result = ShareImporter.processShareIntent(context, intent)

        assertEquals(1, result.importedFiles.size)
        assertEquals(0, result.unsupportedCount)
        assertFalse(result.isTextNote)

        val imported = result.importedFiles.first()
        assertTrue(imported.displayName.contains("shared_test"))
        assertTrue(imported.uri.startsWith("file://"))

        // Cleanup
        ShareImporter.deleteInternalFileIfShared(context, imported.uri)
        sampleFile.delete()
    }

    @Test
    fun testProcessShareIntent_multipleFiles() {
        val file1 = File(context.cacheDir, "multi1_${System.currentTimeMillis()}.png")
        val file2 = File(context.cacheDir, "multi2_${System.currentTimeMillis()}.txt")

        Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).compress(
            Bitmap.CompressFormat.PNG, 100, FileOutputStream(file1)
        )
        file2.writeText("Sample text file for multi-share test")

        val uris = arrayListOf(Uri.fromFile(file1), Uri.fromFile(file2))

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }

        val result = ShareImporter.processShareIntent(context, intent)

        assertEquals(2, result.importedFiles.size)
        assertEquals(0, result.unsupportedCount)

        for (file in result.importedFiles) {
            ShareImporter.deleteInternalFileIfShared(context, file.uri)
        }
        file1.delete()
        file2.delete()
    }

    @Test
    fun testProcessShareIntent_unsupportedFileFormat_isRejected() {
        val unsupportedFile = File(context.cacheDir, "test_archive_${System.currentTimeMillis()}.zip")
        unsupportedFile.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        val uri = Uri.fromFile(unsupportedFile)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        val result = ShareImporter.processShareIntent(context, intent)

        assertEquals(0, result.importedFiles.size)
        assertEquals(1, result.unsupportedCount)

        unsupportedFile.delete()
    }

    @Test
    fun testProcessShareIntent_plainTextNote() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Offline architecture ideas for LocalFind")
            putExtra(Intent.EXTRA_SUBJECT, "IdeasNote")
        }

        val result = ShareImporter.processShareIntent(context, intent)

        assertEquals(1, result.importedFiles.size)
        assertEquals(0, result.unsupportedCount)
        assertTrue(result.isTextNote)

        val imported = result.importedFiles.first()
        assertTrue(imported.displayName.contains("IdeasNote.txt"))

        val content = File(imported.uri.removePrefix("file://")).readText()
        assertEquals("Offline architecture ideas for LocalFind", content)

        ShareImporter.deleteInternalFileIfShared(context, imported.uri)
    }

    @Test
    fun testProcessShareIntent_mixedSupportedAndUnsupported() {
        val validFile = File(context.cacheDir, "valid_${System.currentTimeMillis()}.txt")
        val invalidFile = File(context.cacheDir, "invalid_${System.currentTimeMillis()}.exe")

        validFile.writeText("Valid document content")
        invalidFile.writeBytes(byteArrayOf(0x4D, 0x5A))

        val uris = arrayListOf(Uri.fromFile(validFile), Uri.fromFile(invalidFile))

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }

        val result = ShareImporter.processShareIntent(context, intent)

        assertEquals(1, result.importedFiles.size)
        assertEquals(1, result.unsupportedCount)

        for (file in result.importedFiles) {
            ShareImporter.deleteInternalFileIfShared(context, file.uri)
        }
        validFile.delete()
        invalidFile.delete()
    }
}
