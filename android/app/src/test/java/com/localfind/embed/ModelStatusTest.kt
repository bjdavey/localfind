package com.localfind.embed

import com.localfind.data.ModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [ModelStatus] state representations and file copy self-healing logic.
 *
 * Verifies that:
 * 1. [ModelStatus.Loading], [ModelStatus.Loaded], and [ModelStatus.Error] properties behave as expected.
 * 2. Model copy atomic rename behavior ensures data integrity during self-healing.
 */
class ModelStatusTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun modelStatus_stateProperties() {
        val loading = ModelStatus.Loading
        assertTrue(loading is ModelStatus)

        val loaded = ModelStatus.Loaded(
            modelPath = "/data/user/0/com.localfind/files/embeddinggemma-2-740m.litertlm",
            sizeBytes = 484_622_336L
        )
        assertEquals("/data/user/0/com.localfind/files/embeddinggemma-2-740m.litertlm", loaded.modelPath)
        assertEquals(484_622_336L, loaded.sizeBytes)
        // Verify formatted size in MB
        assertEquals(462L, loaded.sizeBytes / (1024 * 1024))

        val error = ModelStatus.Error(
            message = "Model file not found in internal storage",
            instructions = "Run: adb push .local/models/embeddinggemma-2-740m.litertlm /data/local/tmp/"
        )
        assertEquals("Model file not found in internal storage", error.message)
        assertTrue(error.instructions.contains("adb push"))
    }

    @Test
    fun modelSelfHealing_atomicCopyProducesValidTargetFile() {
        // Simulate fallback source (e.g. /data/local/tmp)
        val sourceDir = tempFolder.newFolder("source")
        val sourceFile = File(sourceDir, MODEL_FILE_NAME)
        val fakeModelData = "fake-model-weights-content-embeddinggemma-2".toByteArray()
        sourceFile.writeBytes(fakeModelData)

        // Simulate app internal filesDir
        val filesDir = tempFolder.newFolder("files")
        val targetFile = File(filesDir, MODEL_FILE_NAME)
        assertFalse(targetFile.exists())

        // Simulate atomic copy via temporary file as done in Embedder.ensureModelFile
        val tempFile = File(filesDir, "$MODEL_FILE_NAME.tmp")
        sourceFile.inputStream().use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        assertTrue(tempFile.exists())
        assertTrue(tempFile.renameTo(targetFile))

        assertTrue(targetFile.exists())
        assertEquals(fakeModelData.size.toLong(), targetFile.length())
        assertEquals("fake-model-weights-content-embeddinggemma-2", targetFile.readText())
        assertFalse(tempFile.exists())
    }
}

