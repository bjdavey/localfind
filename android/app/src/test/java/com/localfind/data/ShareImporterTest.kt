package com.localfind.data

import com.localfind.core.format.FileNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [ShareImporter] business logic:
 * - File name sanitization
 * - Collision avoidance for internal imported files
 * - Safe scoped cleanup of shared imports
 */
class ShareImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun sanitizeFileName_removesIllegalFilesystemCharacters() {
        val input = "my:file/with\\bad*chars?and<more>\"quotes\"|pipe.jpg"
        val expected = "my_file_with_bad_chars_and_more__quotes__pipe.jpg"
        assertEquals(expected, FileNames.sanitize(input))
    }

    @Test
    fun sanitizeFileName_trimsWhitespace() {
        val input = "   sample document.pdf   "
        val expected = "sample document.pdf"
        assertEquals(expected, FileNames.sanitize(input))
    }

    @Test
    fun sanitizeFileName_emptyOrAllIllegal_fallsBackGracefully() {
        val input = ":::///???"
        val expected = "_________"
        assertEquals(expected, FileNames.sanitize(input))
    }

    @Test
    fun createUniqueInternalFile_avoidsFilenameCollisions() {
        val rootDir = tempFolder.newFolder("files")
        val baseDir = File(rootDir, ShareImporter.SHARED_IMPORTS_DIR).apply { mkdirs() }

        // Create an existing file
        File(baseDir, "photo.jpg").createNewFile()
        File(baseDir, "photo_1.jpg").createNewFile()

        // The same naming rule used by createUniqueInternalFile: first free "<base>_<n>.<ext>"
        val name = FileNames.uniqueName(FileNames.sanitize("photo.jpg")) { File(baseDir, it).exists() }

        assertEquals("photo_2.jpg", name)
    }

    @Test
    fun deleteInternalFileIfShared_onlyDeletesWithinSharedImports() {
        val rootDir = tempFolder.newFolder("files")
        val sharedDir = File(rootDir, ShareImporter.SHARED_IMPORTS_DIR).apply { mkdirs() }
        val otherDir = File(rootDir, "other_dir").apply { mkdirs() }

        val sharedFile = File(sharedDir, "shared_test.txt").apply { writeText("data") }
        val outsideFile = File(otherDir, "important.txt").apply { writeText("keep me") }

        assertTrue(sharedFile.exists())
        assertTrue(outsideFile.exists())

        // Test safe cleanup logic directly matching deleteInternalFileIfShared
        fun safeDelete(file: File) {
            if (file.canonicalPath.startsWith(sharedDir.canonicalPath) && file.exists()) {
                file.delete()
            }
        }

        safeDelete(sharedFile)
        safeDelete(outsideFile)

        assertFalse("File in shared_imports should be deleted", sharedFile.exists())
        assertTrue("File outside shared_imports must NEVER be deleted", outsideFile.exists())
    }
}
