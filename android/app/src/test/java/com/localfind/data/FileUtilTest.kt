package com.localfind.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for file utility functions and data models in [com.localfind.data].
 */
class FileUtilTest {

    @Test
    fun formatFileSize_zeroAndNegative() {
        // Zero and negative sizes should safely return "0 B"
        assertEquals("0 B", formatFileSize(0))
        assertEquals("0 B", formatFileSize(-10))
    }

    @Test
    fun formatFileSize_bytes() {
        // Values under 1024 bytes format directly as integer bytes
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1023 B", formatFileSize(1023))
    }

    @Test
    fun formatFileSize_kilobytes() {
        // Kilobyte boundary and fractional values
        assertEquals("1.0 KB", formatFileSize(1024))
        assertEquals("1.5 KB", formatFileSize(1536))
    }

    @Test
    fun formatFileSize_megabytes() {
        // Megabyte boundary and fractional values
        assertEquals("1.0 MB", formatFileSize(1024 * 1024))
        assertEquals("2.5 MB", formatFileSize((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatFileSize_gigabytes() {
        // Gigabyte scaling
        assertEquals("1.0 GB", formatFileSize(1024L * 1024L * 1024L))
    }

    @Test
    fun importedFile_properties() {
        // Validates construction of ImportedFile model and attribute accessibility
        val file = ImportedFile(
            uri = "content://media/external/file/1",
            displayName = "test.txt",
            mimeType = "text/plain",
            size = 2048
        )
        assertEquals("content://media/external/file/1", file.uri)
        assertEquals("test.txt", file.displayName)
        assertEquals("text/plain", file.mimeType)
        assertEquals(2048L, file.size)
    }

    @Test
    fun supportedFilesWithBuiltinViewer_areAccepted() {
        // Text and Markdown (Builtin viewer: TextViewerScreen)
        assertTrue(FileImporter.isSupported("notes.txt", "text/plain"))
        assertTrue(FileImporter.isSupported("README.md", "text/markdown"))
        assertTrue(FileImporter.isSupported("doc.txt", null))
        assertTrue(FileImporter.isSupported("doc.md", null))

        // PDF documents (Builtin viewer: PdfViewerScreen)
        assertTrue(FileImporter.isSupported("manual.pdf", "application/pdf"))
        assertTrue(FileImporter.isSupported("guide.pdf", null))

        // Images (Builtin viewer: ImageViewerScreen)
        assertTrue(FileImporter.isSupported("photo.jpg", "image/jpeg"))
        assertTrue(FileImporter.isSupported("photo.jpeg", "image/jpeg"))
        assertTrue(FileImporter.isSupported("chart.png", "image/png"))
        assertTrue(FileImporter.isSupported("animation.gif", "image/gif"))
        assertTrue(FileImporter.isSupported("meme.gif", null))

        // Audio (Builtin viewer: MediaPlayerScreen)
        assertTrue(FileImporter.isSupported("voice.mp3", "audio/mpeg"))
        assertTrue(FileImporter.isSupported("voice.m4a", "audio/mp4"))
        assertTrue(FileImporter.isSupported("voice.wav", "audio/wav"))

        // Video (Builtin viewer: MediaPlayerScreen)
        assertTrue(FileImporter.isSupported("clip.mp4", "video/mp4"))
        assertTrue(FileImporter.isSupported("clip.mp4", null))
    }

    @Test
    fun unsupportedFilesWithoutBuiltinViewer_areRejected() {
        // Compressed archives
        assertFalse(FileImporter.isSupported("archive.zip", "application/zip"))
        assertFalse(FileImporter.isSupported("backup.tar.gz", "application/gzip"))
        assertFalse(FileImporter.isSupported("data.rar", null))
        assertFalse(FileImporter.isSupported("data.7z", null))

        // Binaries / packages
        assertFalse(FileImporter.isSupported("app.apk", "application/vnd.android.package-archive"))
        assertFalse(FileImporter.isSupported("program.exe", "application/x-msdownload"))
        assertFalse(FileImporter.isSupported("binary.bin", "application/octet-stream"))

        // Office documents without builtin viewers
        assertFalse(FileImporter.isSupported("notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertFalse(FileImporter.isSupported("sheet.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertFalse(FileImporter.isSupported("slides.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"))

        // Non-supported media formats
        assertFalse(FileImporter.isSupported("movie.mkv", "video/x-matroska"))
        assertFalse(FileImporter.isSupported("movie.avi", "video/x-msvideo"))
        assertFalse(FileImporter.isSupported("image.webp", "image/webp"))
        assertFalse(FileImporter.isSupported("image.bmp", "image/bmp"))

        // Unknown / extensionless
        assertFalse(FileImporter.isSupported("unknown", null))
        assertFalse(FileImporter.isSupported("file.xyz", null))
    }

    @Test
    fun supportedMimeTypes_containsExpectedTypes() {
        // Document picker MIME filter must include all 5 PRD modalities
        val mimeList = FileImporter.SUPPORTED_MIME_TYPES.toList()
        assertTrue(mimeList.contains("application/pdf"))
        assertTrue(mimeList.contains("image/jpeg"))
        assertTrue(mimeList.contains("image/png"))
        assertTrue(mimeList.contains("image/gif"))
        assertTrue(mimeList.contains("video/mp4"))
        assertTrue(mimeList.contains("text/plain"))
        assertTrue(mimeList.contains("text/markdown"))
        assertTrue(mimeList.contains("audio/mpeg"))
        assertTrue(mimeList.contains("audio/wav"))
    }
}
