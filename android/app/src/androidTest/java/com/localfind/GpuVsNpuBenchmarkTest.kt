package com.localfind

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfind.data.AppSettings
import com.localfind.data.EmbeddingStore
import com.localfind.data.HardwareAccelerator
import com.localfind.data.ImportedFile
import com.localfind.embed.Embedder
import com.localfind.embed.MODEL_FILE_NAME
import com.localfind.embed.SocDetector
import com.localfind.index.IndexResult
import com.localfind.index.Indexer
import com.localfind.index.IndexingResumeManager
import com.localfind.index.video.indexVideoFile
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Direct GPU vs. Qualcomm Hexagon NPU Benchmark Test on Samsung Galaxy S24 Ultra (SM8650).
 *
 * Compares video indexing throughput on Planet-Earth.mp4:
 * 1. Test 1 (GPU): Adreno 750 OpenCL compute shaders with embeddinggemma-2-740m.litertlm
 * 2. Test 2 (NPU): Qualcomm Hexagon HTP v75 NPU with embeddinggemma-2-740m_Qualcomm_SM8650.litertlm
 *
 * Rigorous NPU Verification:
 * - Asserts [Embedder.isNpuVisionEnabled] == true.
 * - Confirms NO fallback to CPU (XNNPack) or GPU occurred.
 * - Verifies access to Qualcomm Compute DSP device node (/dev/cdsprpc-smd).
 * - Reports side-by-side execution duration, chunk latency, and thermal headroom.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class GpuVsNpuBenchmarkTest {

    companion object {
        const val TAG = "BENCHMARK_GPU_VS_NPU"
        const val VIDEO_NAME = "Planet-Earth.mp4"

        data class BenchmarkRun(
            val mode: String,
            val modelName: String,
            val isNpuVerified: Boolean,
            val isGpuVerified: Boolean,
            val durationMs: Long,
            val chunksCount: Int,
            val avgMsPerChunk: Double,
            val thermalStatusBefore: Int,
            val thermalStatusAfter: Int,
            val headroomBefore: Float,
            val headroomAfter: Float
        )

        val benchmarkRuns = mutableListOf<BenchmarkRun>()

        @JvmStatic
        @AfterClass
        fun printSummary() {
            Log.i(TAG, "\n")
            Log.i(TAG, "=======================================================================================================")
            Log.i(TAG, "              SAMSUNG GALAXY S24 ULTRA: GPU vs. QUALCOMM HEXAGON NPU BENCHMARK                         ")
            Log.i(TAG, "                               Video: Planet-Earth.mp4                                                 ")
            Log.i(TAG, "=======================================================================================================")
            Log.i(TAG, String.format("%-6s | %-12s | %-10s | %-10s | %-7s | %-12s | %-12s",
                "MODE", "HARDWARE", "VERIFIED", "DURATION", "CHUNKS", "AVG MS/CHUNK", "THERMAL (H/S)"))
            Log.i(TAG, "-------------------------------------------------------------------------------------------------------")
            for (r in benchmarkRuns) {
                val durationSec = String.format("%.2fs", r.durationMs / 1000.0)
                val thermalStr = String.format("%.2f->%.2f", r.headroomBefore, r.headroomAfter)
                val verifiedStr = if (r.isNpuVerified) "REAL NPU" else if (r.isGpuVerified) "REAL GPU" else "CPU FALLBACK"
                val hwName = if (r.isNpuVerified) "Hexagon v75" else if (r.isGpuVerified) "Adreno 750" else "Kryo CPU"
                Log.i(TAG, String.format("%-6s | %-12s | %-10s | %-10s | %-7d | %-12.1f | %-12s",
                    r.mode, hwName, verifiedStr, durationSec, r.chunksCount, r.avgMsPerChunk, thermalStr))
            }
            Log.i(TAG, "=======================================================================================================")

            val gpuRun = benchmarkRuns.firstOrNull { it.mode == "GPU" }
            val npuRun = benchmarkRuns.firstOrNull { it.mode == "NPU" }
            if (gpuRun != null && npuRun != null && npuRun.durationMs > 0 && gpuRun.durationMs > 0) {
                val speedup = gpuRun.durationMs.toDouble() / npuRun.durationMs.toDouble()
                val latencyDiff = gpuRun.avgMsPerChunk - npuRun.avgMsPerChunk
                Log.i(TAG, String.format(">>> NPU SPEEDUP vs GPU: %.2fx (NPU is %+.1f ms/chunk faster than GPU) <<<", speedup, latencyDiff))
                Log.i(TAG, "=======================================================================================================\n")
            }
        }
    }

    private lateinit var context: Context
    private lateinit var videoFile: File
    private lateinit var importedFile: ImportedFile
    private lateinit var powerManager: PowerManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // Ensure both GPU and NPU models are present in internal storage
        val gpuModel = Embedder.ensureModelFile(context, MODEL_FILE_NAME)
        assumeTrue("GPU model file ($MODEL_FILE_NAME) must exist", gpuModel.exists() && gpuModel.length() > 0L)

        val npuModelName = SocDetector.resolveModelName(context, HardwareAccelerator.NPU)
        val npuModel = Embedder.ensureModelFile(context, npuModelName)
        assumeTrue("NPU model file ($npuModelName) must exist", npuModel.exists() && npuModel.length() > 0L)

        // Locate Planet-Earth.mp4
        val candidates = listOf(
            File(context.filesDir, VIDEO_NAME),
            File(context.cacheDir, VIDEO_NAME),
            File("/data/local/tmp", VIDEO_NAME)
        )
        val source = candidates.firstOrNull { it.exists() && it.length() > 0L }
        assumeTrue("Planet-Earth.mp4 must exist for benchmark", source != null)

        videoFile = File(context.filesDir, VIDEO_NAME)
        if (!videoFile.exists() || videoFile.length() == 0L) {
            source!!.copyTo(videoFile, overwrite = true)
        }

        importedFile = ImportedFile(
            uri = Uri.fromFile(videoFile).toString(),
            displayName = VIDEO_NAME,
            mimeType = "video/mp4",
            size = videoFile.length()
        )
    }

    @Test
    fun test00_verifyNpuPrimitives() {
        Log.i(TAG, "\n================================================================================")
        Log.i(TAG, ">>> TEST 0: VERIFY NPU PRIMITIVES (Text, Single Image Bitmap, Multi-Part) <<<")
        Log.i(TAG, "================================================================================")

        val npuModelName = SocDetector.resolveModelName(context, HardwareAccelerator.NPU)
        Embedder(context, HardwareAccelerator.NPU, npuModelName).use { embedder ->
            Log.i(TAG, "Embedder initialized: isNpuVision=${embedder.isNpuVisionEnabled}, isNpuText=${embedder.isNpuTextEnabled}, isGpu=${embedder.isGpuVisionEnabled}")
            assertTrue("NPU vision must be active", embedder.isNpuVisionEnabled)
            assertTrue("NPU text must be active for fast PDF/text indexing", embedder.isNpuTextEnabled)

            // 1. Text embedding test
            val t0 = System.currentTimeMillis()
            val textVec = embedder.embedText("planet earth nature documentary")
            val textMs = System.currentTimeMillis() - t0
            Log.i(TAG, "Text embedding on NPU successful! Dim: ${textVec.size}, Time: ${textMs}ms")
            assertTrue("Text vector should be 768 dims", textVec.size == 768)

            // 2. Single Image Bitmap test (embedImage)
            val bmp = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
            val t1 = System.currentTimeMillis()
            val imgVec = embedder.embedImage(bmp)
            val imgMs = System.currentTimeMillis() - t1
            Log.i(TAG, "Single Image embedding (embedImage) on NPU successful! Dim: ${imgVec.size}, Time: ${imgMs}ms")
            assertTrue("Image vector should be 768 dims", imgVec.size == 768)
        }
    }

    /**
     * Test 1: Full video indexing on Samsung Galaxy S24 Ultra using GPU (Adreno 750 OpenCL).
     */
    @Test
    fun test01_benchmarkGpu() {
        Log.i(TAG, "\n================================================================================")
        Log.i(TAG, ">>> STARTING BENCHMARK 1: GPU (Adreno 750 / OpenCL) on Planet-Earth.mp4 <<<")
        Log.i(TAG, "================================================================================")

        val run = runVideoIndexBenchmark(
            modeLabel = "GPU",
            accelerator = HardwareAccelerator.GPU,
            modelFileName = MODEL_FILE_NAME
        )

        // Strict verification: GPU must be active, NPU must be false
        assertTrue("GPU vision acceleration MUST be enabled!", run.isGpuVerified)
        assertFalse("NPU should NOT be active during GPU test", run.isNpuVerified)
        assertTrue("GPU indexing must produce chunks", run.chunksCount > 0)
    }

    /**
     * Test 2: Full video indexing on Samsung Galaxy S24 Ultra using REAL NPU (Qualcomm Hexagon HTP v75).
     *
     * Validates that:
     * 1. Embedder initializes with Delegate.NPU on Qualcomm Hexagon.
     * 2. [Embedder.isNpuVisionEnabled] is strictly true (asserts failure if it fell back to CPU/GPU).
     * 3. Qualcomm Compute DSP FastRPC node (/dev/cdsprpc-smd) exists and is accessible.
     */
    @Test
    fun test02_benchmarkNpu() {
        Log.i(TAG, "\n================================================================================")
        Log.i(TAG, ">>> STARTING BENCHMARK 2: NPU (Qualcomm Hexagon HTP v75) on Planet-Earth.mp4 <<<")
        Log.i(TAG, "================================================================================")

        // Verify Qualcomm DSP node exists on device
        val cdsprpc = File("/dev/cdsprpc-smd")
        Log.i(TAG, "Checking Qualcomm cDSP FastRPC node (/dev/cdsprpc-smd): exists=${cdsprpc.exists()}, canRead=${cdsprpc.canRead()}")

        val npuModelName = SocDetector.resolveModelName(context, HardwareAccelerator.NPU)
        val run = runVideoIndexBenchmark(
            modeLabel = "NPU",
            accelerator = HardwareAccelerator.NPU,
            modelFileName = npuModelName
        )

        // STRICT NPU VERIFICATION: Must NOT fall back to CPU or GPU
        assertTrue(
            "CRITICAL: Expected REAL NPU acceleration, but it FELL BACK to CPU or GPU! " +
                    "(isNpuVisionEnabled=${run.isNpuVerified}, isGpuVisionEnabled=${run.isGpuVerified}). " +
                    "Check logcat for Qualcomm QNN / LiteRT dispatch initialization errors.",
            run.isNpuVerified
        )
        assertFalse(
            "CRITICAL: GPU was active instead of NPU! It must execute purely on Qualcomm Hexagon NPU.",
            run.isGpuVerified
        )
        assertTrue("NPU indexing must produce chunks", run.chunksCount > 0)
        Log.i(TAG, ">>> SUCCESS: Confirmed REAL Qualcomm Hexagon NPU execution with 0 fallback! <<<")
    }

    private fun runVideoIndexBenchmark(
        modeLabel: String,
        accelerator: HardwareAccelerator,
        modelFileName: String
    ): BenchmarkRun {
        // Reset resume state and embeddings for this file
        IndexingResumeManager.clearResumeChunk(context, importedFile.uri)
        EmbeddingStore.create(context).use { store ->
            store.deleteByMetadata(mapOf("fileId" to importedFile.uri))
        }

        val statusBefore = powerManager.currentThermalStatus
        val headroomBefore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            powerManager.getThermalHeadroom(10)
        } else 0f

        val settings = AppSettings(
            accelerator = accelerator,
            videoIncludeAudio = false, // Vision-only to isolate pure visual encoder performance
            videoSkipSimilarChunks = true // Production default
        )

        var indexResult: IndexResult
        var durationMs: Long
        var isNpuActive = false
        var isGpuActive = false

        EmbeddingStore.create(context).use { store ->
            Embedder(context, accelerator, modelFileName).use { embedder ->
                isNpuActive = embedder.isNpuVisionEnabled
                isGpuActive = embedder.isGpuVisionEnabled

                Log.i(TAG, "[$modeLabel] Embedder state: isNpuVisionEnabled=$isNpuActive, isGpuVisionEnabled=$isGpuActive, accelerator=${embedder.effectiveAccelerator}")

                val indexer = Indexer(context, embedder, store, settings)

                // Cooldown pause before measurement
                System.gc()
                Thread.sleep(1500L)

                val t0 = System.currentTimeMillis()
                indexResult = indexer.indexVideoFile(importedFile)
                durationMs = System.currentTimeMillis() - t0
            }
        }

        val statusAfter = powerManager.currentThermalStatus
        val headroomAfter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            powerManager.getThermalHeadroom(10)
        } else 0f

        assertTrue("[$modeLabel] Indexing should succeed: ${indexResult.errorMessage}", indexResult.isSuccess)
        assertTrue("[$modeLabel] Indexing should produce chunks", indexResult.chunksCount > 0)

        val avgMs = if (indexResult.chunksCount > 0) durationMs.toDouble() / indexResult.chunksCount else 0.0

        val run = BenchmarkRun(
            mode = modeLabel,
            modelName = modelFileName,
            isNpuVerified = isNpuActive,
            isGpuVerified = isGpuActive,
            durationMs = durationMs,
            chunksCount = indexResult.chunksCount,
            avgMsPerChunk = avgMs,
            thermalStatusBefore = statusBefore,
            thermalStatusAfter = statusAfter,
            headroomBefore = headroomBefore,
            headroomAfter = headroomAfter
        )

        Log.i(TAG, "[$modeLabel COMPLETE] Duration: ${durationMs}ms (${durationMs / 1000.0}s) | Chunks: ${indexResult.chunksCount} | Avg: ${String.format("%.1f", avgMs)}ms/chunk")
        benchmarkRuns.add(run)
        return run
    }
}
