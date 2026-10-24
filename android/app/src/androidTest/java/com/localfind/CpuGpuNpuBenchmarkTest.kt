package com.localfind

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localfind.core.model.ImportedFile
import com.localfind.core.model.IndexResult
import com.localfind.core.model.MODEL_FILE_NAME
import com.localfind.core.settings.AppSettings
import com.localfind.core.settings.HardwareAccelerator
import com.localfind.data.EmbeddingStore
import com.localfind.embed.Embedder
import com.localfind.embed.ModelFiles
import com.localfind.embed.SocDetector
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * End-to-end Hardware Accelerator Benchmark: CPU vs. GPU vs. NPU on target device.
 *
 * Compares video indexing throughput and latency across:
 * 1. Test 1 (CPU): Multi-threaded CPU execution via XNNPack
 * 2. Test 2 (GPU): OpenCL compute shaders (e.g. Adreno GPU on Snapdragon)
 * 3. Test 3 (NPU): Dedicated hardware NPU (e.g. Qualcomm Hexagon HTP v75 on SM8650)
 *
 * Variable configuration via instrumentation arguments:
 * - `-e video_name <filename>`: Name of the test video (default: A_Perfect_Planet_-_Down_To_Earth_-_S1-E18.webm)
 * - `-e target_device <dev>`: Display name of the target device (default: Build.MANUFACTURER + MODEL)
 *
 * Results are logged to logcat and written as a Markdown summary file
 * in app internal storage (context.cacheDir/benchmark_report.md) for script retrieval.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CpuGpuNpuBenchmarkTest {

    companion object {
        const val TAG = "BENCHMARK_CPU_GPU_NPU"
        const val DEFAULT_VIDEO_NAME = "A_Perfect_Planet_-_Down_To_Earth_-_S1-E18.webm"

        var currentVideoName: String = DEFAULT_VIDEO_NAME
        var currentTargetDevice: String = ""
        var currentVideoSizeBytes: Long = 0L

        data class BenchmarkRun(
            val mode: String,
            val hardwareName: String,
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
        fun printSummaryAndSaveReport() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val deviceLabel = if (currentTargetDevice.isNotBlank()) currentTargetDevice else "${Build.MANUFACTURER} ${Build.MODEL}"

            Log.i(TAG, "\n")
            Log.i(TAG, "=======================================================================================================")
            Log.i(TAG, "        HARDWARE ACCELERATION BENCHMARK: CPU vs. GPU vs. NPU                                           ")
            Log.i(TAG, "        Device: $deviceLabel                                                                           ")
            Log.i(TAG, "        Video:  $currentVideoName (${currentVideoSizeBytes / (1024 * 1024)} MB)                        ")
            Log.i(TAG, "=======================================================================================================")
            Log.i(TAG, String.format(Locale.US, "%-6s | %-16s | %-12s | %-10s | %-7s | %-12s | %-12s",
                "MODE", "HARDWARE", "VERIFIED", "DURATION", "CHUNKS", "AVG MS/CHUNK", "THERMAL (H/S)"))
            Log.i(TAG, "-------------------------------------------------------------------------------------------------------")

            for (r in benchmarkRuns) {
                val durationSec = String.format(Locale.US, "%.2fs", r.durationMs / 1000.0)
                val thermalStr = when {
                    !r.headroomBefore.isNaN() && !r.headroomAfter.isNaN() -> String.format(Locale.US, "%.2f->%.2f", r.headroomBefore, r.headroomAfter)
                    !r.headroomAfter.isNaN() -> String.format(Locale.US, "->%.2f", r.headroomAfter)
                    !r.headroomBefore.isNaN() -> String.format(Locale.US, "%.2f->", r.headroomBefore)
                    else -> "N/A"
                }
                val verifiedStr = when {
                    r.isNpuVerified -> "REAL NPU"
                    r.isGpuVerified -> "REAL GPU"
                    else -> "REAL CPU"
                }
                Log.i(TAG, String.format(Locale.US, "%-6s | %-16s | %-12s | %-10s | %-7d | %-12.1f | %-12s",
                    r.mode, r.hardwareName, verifiedStr, durationSec, r.chunksCount, r.avgMsPerChunk, thermalStr))
            }
            Log.i(TAG, "=======================================================================================================")

            val cpuRun = benchmarkRuns.firstOrNull { it.mode == "CPU" }
            val gpuRun = benchmarkRuns.firstOrNull { it.mode == "GPU" }
            val npuRun = benchmarkRuns.firstOrNull { it.mode == "NPU" }

            if (cpuRun != null && gpuRun != null && gpuRun.durationMs > 0) {
                val speedup = cpuRun.durationMs.toDouble() / gpuRun.durationMs.toDouble()
                val latencyDiff = cpuRun.avgMsPerChunk - gpuRun.avgMsPerChunk
                Log.i(TAG, String.format(Locale.US, ">>> GPU SPEEDUP vs CPU: %.2fx (%.1f ms/chunk saved) <<<", speedup, latencyDiff))
            }
            if (cpuRun != null && npuRun != null && npuRun.durationMs > 0) {
                val speedup = cpuRun.durationMs.toDouble() / npuRun.durationMs.toDouble()
                val latencyDiff = cpuRun.avgMsPerChunk - npuRun.avgMsPerChunk
                Log.i(TAG, String.format(Locale.US, ">>> NPU SPEEDUP vs CPU: %.2fx (%.1f ms/chunk saved) <<<", speedup, latencyDiff))
            }
            if (gpuRun != null && npuRun != null && npuRun.durationMs > 0) {
                val speedup = gpuRun.durationMs.toDouble() / npuRun.durationMs.toDouble()
                val latencyDiff = gpuRun.avgMsPerChunk - npuRun.avgMsPerChunk
                Log.i(TAG, String.format(Locale.US, ">>> NPU SPEEDUP vs GPU: %.2fx (%.1f ms/chunk saved) <<<", speedup, latencyDiff))
            }
            Log.i(TAG, "=======================================================================================================\n")

            // Build Markdown report
            val markdown = buildMarkdownReport(deviceLabel, currentVideoName, currentVideoSizeBytes, benchmarkRuns)

            // Save Markdown to application cache directory (accessible via run-as or adb pull)
            try {
                val cacheReport = File(context.cacheDir, "benchmark_report.md")
                cacheReport.writeText(markdown)
                Log.i(TAG, "Saved benchmark report to: ${cacheReport.absolutePath}")

                val filesReport = File(context.filesDir, "benchmark_report.md")
                filesReport.writeText(markdown)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write report file to app storage: ${e.message}")
            }

            // Also attempt to write to /data/local/tmp for direct adb pull
            try {
                val tmpReport = File("/data/local/tmp/benchmark_report.md")
                tmpReport.writeText(markdown)
                tmpReport.setReadable(true, false)
            } catch (_: Exception) {
                // Expected on some SELinux configurations
            }

            // Print full markdown report between markers for shell parsing
            Log.i(TAG, "<<<BENCHMARK_MARKDOWN_START>>>\n$markdown\n<<<BENCHMARK_MARKDOWN_END>>>")
        }

        private fun buildMarkdownReport(
            deviceName: String,
            videoName: String,
            videoSize: Long,
            runs: List<BenchmarkRun>
        ): String {
            val sb = StringBuilder()
            val soc = SocDetector.detectSoc()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val nowStr = dateFormat.format(Date())
            val videoSizeMb = String.format(Locale.US, "%.1f MB", videoSize / (1024.0 * 1024.0))

            sb.appendLine("# LocalFind Hardware Acceleration Benchmark: CPU vs GPU vs NPU")
            sb.appendLine()
            sb.appendLine("## Test Environment")
            sb.appendLine()
            sb.appendLine("| Property | Value |")
            sb.appendLine("| :--- | :--- |")
            sb.appendLine("| **Target Device** | $deviceName |")
            sb.appendLine("| **Chipset (SoC)** | ${soc.marketingName} (${soc.vendor}) |")
            sb.appendLine("| **Android Version** | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) |")
            sb.appendLine("| **Architecture** | ${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"} |")
            sb.appendLine("| **Test Video** | `$videoName` ($videoSizeMb) |")
            sb.appendLine("| **Timestamp** | $nowStr |")
            sb.appendLine()
            sb.appendLine("## Benchmark Results")
            sb.appendLine()
            sb.appendLine("| Mode | Hardware Engine | Verified Delegate | Duration | Chunks | Throughput (chunks/s) | Avg Latency | Thermal Headroom (Start -> End) |")
            sb.appendLine("| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |")

            for (r in runs) {
                val verifiedStr = when {
                    r.isNpuVerified -> "REAL NPU"
                    r.isGpuVerified -> "REAL GPU"
                    else -> "REAL CPU (XNNPack)"
                }
                val durationSec = String.format(Locale.US, "%.2fs", r.durationMs / 1000.0)
                val throughput = if (r.durationMs > 0) String.format(Locale.US, "%.2f", (r.chunksCount * 1000.0) / r.durationMs) else "0.0"
                val avgLatency = String.format(Locale.US, "%.1f ms/chunk", r.avgMsPerChunk)
                val thermalStr = when {
                    !r.headroomBefore.isNaN() && !r.headroomAfter.isNaN() -> String.format(Locale.US, "%.2f -> %.2f", r.headroomBefore, r.headroomAfter)
                    !r.headroomAfter.isNaN() -> String.format(Locale.US, "End: %.2f", r.headroomAfter)
                    !r.headroomBefore.isNaN() -> String.format(Locale.US, "Start: %.2f", r.headroomBefore)
                    else -> "N/A"
                }

                sb.appendLine("| **${r.mode}** | ${r.hardwareName} | $verifiedStr | $durationSec | ${r.chunksCount} | $throughput | $avgLatency | $thermalStr |")
            }

            sb.appendLine()
            sb.appendLine("## Performance & Speedup Analysis")
            sb.appendLine()

            val cpuRun = runs.firstOrNull { it.mode == "CPU" }
            val gpuRun = runs.firstOrNull { it.mode == "GPU" }
            val npuRun = runs.firstOrNull { it.mode == "NPU" }

            if (cpuRun != null && gpuRun != null && gpuRun.durationMs > 0) {
                val speedup = cpuRun.durationMs.toDouble() / gpuRun.durationMs.toDouble()
                val latencyDiff = cpuRun.avgMsPerChunk - gpuRun.avgMsPerChunk
                sb.appendLine("- **GPU vs CPU Speedup**: **${String.format(Locale.US, "%.2fx", speedup)} faster** (${String.format(Locale.US, "%.1f", latencyDiff)} ms saved per chunk)")
            }
            if (cpuRun != null && npuRun != null && npuRun.durationMs > 0) {
                val speedup = cpuRun.durationMs.toDouble() / npuRun.durationMs.toDouble()
                val latencyDiff = cpuRun.avgMsPerChunk - npuRun.avgMsPerChunk
                sb.appendLine("- **NPU vs CPU Speedup**: **${String.format(Locale.US, "%.2fx", speedup)} faster** (${String.format(Locale.US, "%.1f", latencyDiff)} ms saved per chunk)")
            }
            if (gpuRun != null && npuRun != null && npuRun.durationMs > 0) {
                val speedup = gpuRun.durationMs.toDouble() / npuRun.durationMs.toDouble()
                val latencyDiff = gpuRun.avgMsPerChunk - npuRun.avgMsPerChunk
                sb.appendLine("- **NPU vs GPU Speedup**: **${String.format(Locale.US, "%.2fx", speedup)} faster** (${String.format(Locale.US, "%.1f", latencyDiff)} ms saved per chunk)")
            }

            sb.appendLine()
            sb.appendLine("## Thermal & Throttling Observations")
            sb.appendLine()
            for (r in runs) {
                val deltaStr = if (!r.headroomBefore.isNaN() && !r.headroomAfter.isNaN()) {
                    val delta = r.headroomAfter - r.headroomBefore
                    val deltaSign = if (delta >= 0) "+" else ""
                    ", headroom delta: ${deltaSign}${String.format(Locale.US, "%.2f", delta)}"
                } else ""
                sb.appendLine("- **${r.mode} (${r.hardwareName})**: Thermal status ${r.thermalStatusBefore} -> ${r.thermalStatusAfter}$deltaStr")
            }

            return sb.toString()
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

        // Read variable arguments passed from instrumentation CLI or runner
        val args = InstrumentationRegistry.getArguments()
        val argVideo = args.getString("video_name") ?: args.getString("video")
        if (!argVideo.isNullOrBlank()) {
            currentVideoName = argVideo
        }

        val argDevice = args.getString("target_device") ?: args.getString("device")
        if (!argDevice.isNullOrBlank()) {
            currentTargetDevice = argDevice
        } else if (currentTargetDevice.isBlank()) {
            currentTargetDevice = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE})"
        }

        // Locate video file from candidates
        val candidates = listOf(
            File(context.filesDir, currentVideoName),
            File(context.cacheDir, currentVideoName),
            File("/data/local/tmp", currentVideoName),
            File("/sdcard/Download", currentVideoName),
            File("/sdcard", currentVideoName)
        )
        val source = candidates.firstOrNull { it.exists() && it.length() > 0L }
        assumeTrue("Test video '$currentVideoName' must exist in /data/local/tmp or app storage", source != null)
        val validSource = checkNotNull(source)

        videoFile = File(context.filesDir, currentVideoName)
        if (!videoFile.exists() || videoFile.length() != validSource.length()) {
            validSource.copyTo(videoFile, overwrite = true)
        }
        currentVideoSizeBytes = videoFile.length()

        val mimeType = when (videoFile.extension.lowercase(Locale.US)) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            else -> "video/*"
        }

        importedFile = ImportedFile(
            uri = Uri.fromFile(videoFile).toString(),
            displayName = currentVideoName,
            mimeType = mimeType,
            size = videoFile.length()
        )
    }

    /**
     * Test 0: Warmup and verify embedding primitives (Text and Single Image Bitmap).
     */
    @Test
    fun test00_verifyPrimitives() {
        Log.i(TAG, "\n================================================================================")
        Log.i(TAG, ">>> TEST 0: VERIFY EMBEDDING PRIMITIVES (Text, Single Image Bitmap) <<<")
        Log.i(TAG, "================================================================================")

        val soc = SocDetector.detectSoc()
        val preferredAccel = if (soc.expectedNpuModelName != null) HardwareAccelerator.NPU else HardwareAccelerator.CPU
        val modelName = resolveModelForBenchmark(context, preferredAccel)

        openEmbedderOn(context, preferredAccel).use { embedder ->
            Log.i(TAG, "Embedder initialized: isNpuVision=${(embedder.effectiveAccelerator == HardwareAccelerator.NPU)}, isNpuText=${(embedder.effectiveAccelerator == HardwareAccelerator.NPU)}, isGpuVision=${(embedder.effectiveAccelerator == HardwareAccelerator.GPU)}")

            // 1. Text embedding test
            val t0 = System.currentTimeMillis()
            val textVec = embedder.embedText("planet earth nature documentary wildlife")
            val textMs = System.currentTimeMillis() - t0
            Log.i(TAG, "Text embedding successful! Dim: ${textVec.size}, Time: ${textMs}ms")
            assertTrue("Text vector should be 768 dims", textVec.size == 768)

            // 2. Single Image Bitmap test
            val bmp = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
            val t1 = System.currentTimeMillis()
            val imgVec = embedder.embedImage(bmp)
            val imgMs = System.currentTimeMillis() - t1
            Log.i(TAG, "Image embedding successful! Dim: ${imgVec.size}, Time: ${imgMs}ms")
            assertTrue("Image vector should be 768 dims", imgVec.size == 768)
        }
    }

    /**
     * Test 1: Full video indexing using CPU (multi-threaded XNNPack).
     */
    @Test
    fun test01_benchmarkCpu() {
        Log.i(TAG, "\n================================================================================")
        Log.i(TAG, ">>> STARTING BENCHMARK 1: CPU (Multi-threaded XNNPack) on $currentVideoName <<<")
        Log.i(TAG, "================================================================================")

        val cpuModelName = resolveModelForBenchmark(context, HardwareAccelerator.CPU)
        val run = runVideoIndexBenchmark(
            modeLabel = "CPU",
            accelerator = HardwareAccelerator.CPU,
            modelFileName = cpuModelName
        )

        // Strict verification: GPU and NPU must NOT be active
        assertFalse("GPU vision should NOT be active during CPU benchmark", run.isGpuVerified)
        assertFalse("NPU vision should NOT be active during CPU benchmark", run.isNpuVerified)
        assertTrue("CPU indexing must produce chunks", run.chunksCount > 0)
    }

    /**
     * Test 2: Full video indexing using GPU (OpenCL compute shaders).
     */
    @Test
    fun test02_benchmarkGpu() {
        Log.i(TAG, "\n================================================================================")
        Log.i(TAG, ">>> STARTING BENCHMARK 2: GPU (OpenCL shaders) on $currentVideoName <<<")
        Log.i(TAG, "================================================================================")

        val gpuModelName = resolveModelForBenchmark(context, HardwareAccelerator.GPU)
        val run = runVideoIndexBenchmark(
            modeLabel = "GPU",
            accelerator = HardwareAccelerator.GPU,
            modelFileName = gpuModelName
        )

        // Strict verification: GPU must be active, NPU must be false
        assertTrue("GPU vision acceleration MUST be enabled!", run.isGpuVerified)
        assertFalse("NPU vision should NOT be active during GPU benchmark", run.isNpuVerified)
        assertTrue("GPU indexing must produce chunks", run.chunksCount > 0)
    }

    /**
     * Test 3: Full video indexing using dedicated hardware NPU (e.g. Qualcomm Hexagon).
     */
    @Test
    fun test03_benchmarkNpu() {
        Log.i(TAG, "\n================================================================================")
        Log.i(TAG, ">>> STARTING BENCHMARK 3: NPU on $currentVideoName <<<")
        Log.i(TAG, "================================================================================")

        val soc = SocDetector.detectSoc()
        assumeTrue("NPU acceleration not supported on this chipset (${soc.marketingName})", soc.expectedNpuModelName != null)

        val npuModel = modelFor(context, HardwareAccelerator.NPU)
        assumeTrue("NPU model (${soc.expectedNpuModelName}) must exist on device", npuModel != null)
        val npuModelName = npuModel!!.name

        // Check Qualcomm DSP FastRPC node if on Qualcomm chipset
        if (soc.vendor == "Qualcomm") {
            val cdsprpc = File("/dev/cdsprpc-smd")
            Log.i(TAG, "Checking Qualcomm cDSP FastRPC node (/dev/cdsprpc-smd): exists=${cdsprpc.exists()}, canRead=${cdsprpc.canRead()}")
        }

        val run = runVideoIndexBenchmark(
            modeLabel = "NPU",
            accelerator = HardwareAccelerator.NPU,
            modelFileName = npuModelName
        )

        // Strict NPU verification: Must NOT fall back to CPU or GPU
        assertTrue(
            "CRITICAL: Expected REAL NPU acceleration, but it FELL BACK to CPU or GPU! " +
                    "(isNpuVisionEnabled=${run.isNpuVerified}, isGpuVisionEnabled=${run.isGpuVerified}). " +
                    "Check logcat for Qualcomm QNN / LiteRT dispatch initialization errors.",
            run.isNpuVerified
        )
        assertFalse(
            "CRITICAL: GPU was active instead of NPU! It must execute purely on hardware NPU.",
            run.isGpuVerified
        )
        assertTrue("NPU indexing must produce chunks", run.chunksCount > 0)
        Log.i(TAG, ">>> SUCCESS: Confirmed REAL NPU execution with 0 fallback! <<<")
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
            openEmbedderOn(context, accelerator).use { embedder ->
                isNpuActive = (embedder.effectiveAccelerator == HardwareAccelerator.NPU)
                isGpuActive = (embedder.effectiveAccelerator == HardwareAccelerator.GPU)

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
        val hwName = resolveHardwareName(modeLabel, isNpuActive, isGpuActive)

        val run = BenchmarkRun(
            mode = modeLabel,
            hardwareName = hwName,
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

        Log.i(TAG, "[$modeLabel COMPLETE] Duration: ${durationMs}ms (${durationMs / 1000.0}s) | Chunks: ${indexResult.chunksCount} | Avg: ${String.format(Locale.US, "%.1f", avgMs)}ms/chunk")
        benchmarkRuns.add(run)
        return run
    }

    /**
     * Name of the model a benchmark on [accelerator] uses (for the report). GPU/CPU need the universal model: the
     * NPU model cannot run there, so the run is skipped by [openEmbedderOn] when it is not installed.
     */
    private fun resolveModelForBenchmark(context: Context, accelerator: HardwareAccelerator): String =
        modelFor(context, accelerator)?.name ?: "(not installed)"

    private fun resolveHardwareName(mode: String, isNpu: Boolean, isGpu: Boolean): String {
        val soc = SocDetector.detectSoc()
        return when {
            isNpu -> soc.htpArchitecture?.let { "Hexagon $it" } ?: "${soc.vendor} NPU"
            isGpu -> when {
                soc.vendor == "Qualcomm" -> "Adreno GPU"
                soc.vendor == "MediaTek" -> "Mali/Immortalis"
                soc.vendor == "Google" -> "Mali GPU"
                else -> "GPU (${Build.HARDWARE})"
            }
            else -> "${soc.marketingName.ifEmpty { Build.HARDWARE }} CPU"
        }
    }
}
