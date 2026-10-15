package com.localfind.data

import org.junit.Assert.assertEquals
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
}
