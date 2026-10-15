package com.localfind.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FileUtilTest {

    @Test
    fun formatFileSize_zeroAndNegative() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("0 B", formatFileSize(-10))
    }

    @Test
    fun formatFileSize_bytes() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1023 B", formatFileSize(1023))
    }

    @Test
    fun formatFileSize_kilobytes() {
        assertEquals("1.0 KB", formatFileSize(1024))
        assertEquals("1.5 KB", formatFileSize(1536))
    }

    @Test
    fun formatFileSize_megabytes() {
        assertEquals("1.0 MB", formatFileSize(1024 * 1024))
        assertEquals("2.5 MB", formatFileSize((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatFileSize_gigabytes() {
        assertEquals("1.0 GB", formatFileSize(1024L * 1024L * 1024L))
    }

    @Test
    fun importedFile_properties() {
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

