package com.localfind.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [FileAvailability] checking availability of files and detecting deletions.
 */
class FileAvailabilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun isLocalFileAvailable_existingFile_returnsTrue() {
        val file = tempFolder.newFile("sample.txt")
        file.writeText("hello world")

        assertTrue("Existing file path should be available", FileAvailability.isLocalFileAvailable(file.absolutePath))
        assertTrue("Existing file:// URI should be available", FileAvailability.isLocalFileAvailable("file://${file.absolutePath}"))
    }

    @Test
    fun isLocalFileAvailable_deletedFile_returnsFalse() {
        val file = tempFolder.newFile("deleted_sample.pdf")
        val path = file.absolutePath
        assertTrue(file.exists())
        file.delete()
        assertFalse(file.exists())

        assertFalse("Deleted file path should return false", FileAvailability.isLocalFileAvailable(path))
        assertFalse("Deleted file:// URI should return false", FileAvailability.isLocalFileAvailable("file://$path"))
    }

    @Test
    fun isLocalFileAvailable_nullAndBlank_returnsFalse() {
        assertFalse(FileAvailability.isLocalFileAvailable(null))
        assertFalse(FileAvailability.isLocalFileAvailable(""))
        assertFalse(FileAvailability.isLocalFileAvailable("   "))
    }

    @Test
    fun isLocalFileAvailable_nonExistentPath_returnsFalse() {
        assertFalse(FileAvailability.isLocalFileAvailable("/path/to/non_existent_file.mp4"))
    }
}

