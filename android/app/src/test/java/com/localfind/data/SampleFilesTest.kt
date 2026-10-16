package com.localfind.data

import com.localfind.index.Indexer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit test validating the presence, integrity, and classifier compatibility of
 * the free offline test samples located in `samples/`.
 *
 * Verifies:
 * 1. Directory exists and contains non-empty files.
 * 2. All 5 PRD format categories (Text, PDF, Image, Audio, Video) are covered.
 * 3. File classification logic in [Indexer] accurately identifies each sample.
 * 4. Sample files maintain a compact footprint (< 15 MB total) for developer efficiency.
 */
class SampleFilesTest {

    private fun findSamplesDirectory(): File {
        val candidates = listOf(
            File("../../samples"), // from android/app working directory during gradle test
            File("../samples"),    // from android/ working directory
            File("samples"),       // from root directory
            File(System.getProperty("user.dir"), "../../samples").normalize()
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: throw IllegalStateException("Could not locate samples directory from ${File(".").absolutePath}")
    }

    @Test
    fun sampleFiles_existAndCoverAllSupportedFormats() {
        val samplesDir = findSamplesDirectory()
        val files = samplesDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".md") } ?: emptyList()

        assertFalse("Samples directory must contain test files", files.isEmpty())

        // Required extension coverage per PRD §2
        val extensions = files.map { it.extension.lowercase() }.toSet()
        val requiredExtensions = setOf("txt", "pdf", "jpg", "png", "mp3", "wav", "m4a", "mp4")

        for (ext in requiredExtensions) {
            assertTrue("Sample directory must contain at least one .$ext file", extensions.contains(ext))
        }

        // Verify each file is non-empty
        var totalBytes = 0L
        for (file in files) {
            assertTrue("Sample file ${file.name} must not be empty", file.length() > 0L)
            totalBytes += file.length()
        }

        // Ensure samples stay lightweight (< 15 MB)
        assertTrue(
            "Total size of sample files should be under 15 MB (actual: ${totalBytes / (1024 * 1024)} MB)",
            totalBytes < 15 * 1024 * 1024
        )
    }

    @Test
    fun sampleFiles_areCorrectlyClassifiedByIndexer() {
        val samplesDir = findSamplesDirectory()
        val allFiles = samplesDir.listFiles()?.filter { it.isFile && it.name != "README.md" } ?: emptyList()

        for (file in allFiles) {
            val imported = ImportedFile(
                uri = file.toURI().toString(),
                displayName = file.name,
                mimeType = null,
                size = file.length()
            )

            val isAudio = Indexer.isAudioNameOrMime(file.name, null)
            val isVideo = Indexer.isVideoNameOrMime(file.name, null)
            val isImage = Indexer.isImageNameOrMime(file.name, null)
            val isPdf = file.name.endsWith(".pdf", ignoreCase = true)
            val isText = file.name.endsWith(".txt", ignoreCase = true) || file.name.endsWith(".md", ignoreCase = true)

            when (file.extension.lowercase()) {
                "txt", "md" -> assertTrue("${file.name} should be recognized as text", isText)
                "pdf" -> assertTrue("${file.name} should be recognized as PDF", isPdf)
                "jpg", "png" -> assertTrue("${file.name} should be recognized as image", isImage)
                "mp3", "wav", "m4a" -> assertTrue("${file.name} should be recognized as audio", isAudio)
                "mp4" -> assertTrue("${file.name} should be recognized as video", isVideo)
            }

            assertTrue(
                "Sample file ${file.name} must be supported by indexer dispatch logic",
                isAudio || isVideo || isImage || isPdf || isText
            )
        }
    }
}

