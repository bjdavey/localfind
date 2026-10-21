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
import com.localfind.index.IndexResult
import com.localfind.index.Indexer
import com.localfind.index.IndexingResumeManager
import com.localfind.index.video.indexVideoFile
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Direct Benchmark Test for Planet-Earth.mp4 on Physical Device:
 * Compares CPU vs. GPU mode with and without Process.THREAD_PRIORITY_BACKGROUND.
 *
 * Evaluates:
 * 1. CPU Mode with THREAD_PRIORITY_DEFAULT (nice 0)
 * 2. CPU Mode with THREAD_PRIORITY_BACKGROUND (nice 10)
 * 3. GPU Mode with THREAD_PRIORITY_DEFAULT (nice 0)
 * 4. GPU Mode with THREAD_PRIORITY_BACKGROUND (nice 10)
 *
 * Benchmark Results on Samsung Galaxy S24 Ultra (SM-S928B, Snapdragon 8 Gen 3)
 * Video: Planet-Earth.mp4 (5-minute video, 68.8 MB, 39 chunks indexed after similarity skipping)
 *
 * +------+------------+------+----------+--------+--------------+---------------+------------------------------------------+
 * | MODE | PRIORITY   | NICE | DURATION | CHUNKS | AVG MS/CHUNK | THERMAL (H/S) | IMPACT / OBSERVATION                     |
 * +------+------------+------+----------+--------+--------------+---------------+------------------------------------------+
 * | CPU  | DEFAULT    |  0   | 73.12s   |   39   |   1874.9 ms  |  0.69 -> 0.83 | Baseline CPU speed                       |
 * | CPU  | BACKGROUND |  10  | 91.99s   |   39   |   2358.8 ms  |   NaN -> 0.88 | +25.8% slower (+18.87s) due to cgroups   |
 * | GPU  | DEFAULT    |  0   | 65.79s   |   39   |   1686.8 ms  |   NaN -> 0.85 | 10.0% faster than CPU Default            |
 * | GPU  | BACKGROUND |  10  | 58.71s   |   39   |   1505.4 ms  |   NaN -> 0.89 | 19.7% faster than CPU Default            |
 * +------+------------+------+----------+--------+--------------+---------------+------------------------------------------+
 *
 * Findings:
 * 1. CPU Mode: THREAD_PRIORITY_BACKGROUND (nice 10) significantly slows down indexing (~26% slower)
 *    because Android's Linux cgroups restrict CPU time-slices and core frequencies on performance cores.
 * 2. GPU Mode: THREAD_PRIORITY_BACKGROUND does not slow down indexing because tensor computation executes
 *    on the Adreno 750 GPU hardware via WebGPU compute shaders, decoupled from the host CPU thread nice value.
 * 3. UI responsiveness: System UI lag during indexing is caused by continuous GPU pipeline contention with
 *    SurfaceFlinger / One UI compositor, not thread priority. Frame-yielding micro-pacing (20ms) and the
 *    CPU mode toggle in settings allow 120Hz UI rendering without frame drops.
 */
@Ignore("Manual benchmark suite")
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PriorityAndAcceleratorBenchmarkTest {

    companion object {
        const val TAG = "BENCHMARK_PRIORITY"
        const val VIDEO_NAME = "Planet-Earth.mp4"

        data class BenchmarkEntry(
            val accelerator: HardwareAccelerator,
            val priorityName: String,
            val threadNice: Int,
            val isGpuActive: Boolean,
            val durationMs: Long,
            val chunksCount: Int,
            val avgMsPerChunk: Double,
            val thermalStatusBefore: Int,
            val thermalStatusAfter: Int,
            val headroomBefore: Float,
            val headroomAfter: Float
        )

        val results = mutableListOf<BenchmarkEntry>()

        @JvmStatic
        @AfterClass
        fun printSummaryTable() {
            Log.i(TAG, "==========================================================================================")
            Log.i(TAG, "                    PLANET-EARTH.MP4 BENCHMARK SUMMARY TABLE                              ")
            Log.i(TAG, "==========================================================================================")
            Log.i(TAG, String.format("%-6s | %-12s | %-6s | %-9s | %-7s | %-12s | %-12s",
                "MODE", "PRIORITY", "NICE", "DURATION", "CHUNKS", "AVG MS/CHUNK", "THERMAL (H/S)"))
            Log.i(TAG, "------------------------------------------------------------------------------------------")
            for (r in results) {
                val durationSec = String.format("%.2fs", r.durationMs / 1000.0)
                val thermalStr = String.format("%.2f->%.2f", r.headroomBefore, r.headroomAfter)
                Log.i(TAG, String.format("%-6s | %-12s | %-6d | %-9s | %-7d | %-12.1f | %-12s",
                    r.accelerator.name, r.priorityName, r.threadNice, durationSec, r.chunksCount, r.avgMsPerChunk, thermalStr))
            }
            Log.i(TAG, "==========================================================================================")
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

        // Ensure model file exists
        val model = Embedder.ensureModelFile(context)
        assumeTrue("Embedding model must exist", model.exists() && model.length() > 0L)

        // Locate Planet-Earth.mp4 (check filesDir, cacheDir, or fallback /data/local/tmp/)
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

    private fun executeBenchmark(
        accelerator: HardwareAccelerator,
        targetPriority: Int,
        priorityLabel: String
    ): BenchmarkEntry {
        val tid = Process.myTid()
        Process.setThreadPriority(targetPriority)
        val actualNice = Process.getThreadPriority(tid)

        Log.i(TAG, ">>> Starting Benchmark: [${accelerator.name}] [$priorityLabel] (TID $tid, nice=$actualNice) <<<")

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
            videoIncludeAudio = false, // Vision-only for pure visual inference measurement
            videoSkipSimilarChunks = true // Production default
        )

        var indexResult: IndexResult
        var durationMs: Long
        var isGpuActive = false

        EmbeddingStore.create(context).use { store ->
            Embedder(context, accelerator).use { embedder ->
                isGpuActive = embedder.isGpuVisionEnabled
                val indexer = Indexer(context, embedder, store, settings)

                // Cooldown pause before measurement
                System.gc()
                Thread.sleep(1500L)

                val t0 = System.currentTimeMillis()
                indexResult = indexer.indexVideoFile(importedFile)
                durationMs = System.currentTimeMillis() - t0
            }
        }

        // Restore default priority
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)

        val statusAfter = powerManager.currentThermalStatus
        val headroomAfter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            powerManager.getThermalHeadroom(10)
        } else 0f

        assertTrue("Indexing should succeed: ${indexResult.errorMessage}", indexResult.isSuccess)
        assertTrue("Indexing should produce chunks", indexResult.chunksCount > 0)

        val avgMs = if (indexResult.chunksCount > 0) durationMs.toDouble() / indexResult.chunksCount else 0.0

        val entry = BenchmarkEntry(
            accelerator = accelerator,
            priorityName = priorityLabel,
            threadNice = actualNice,
            isGpuActive = isGpuActive,
            durationMs = durationMs,
            chunksCount = indexResult.chunksCount,
            avgMsPerChunk = avgMs,
            thermalStatusBefore = statusBefore,
            thermalStatusAfter = statusAfter,
            headroomBefore = headroomBefore,
            headroomAfter = headroomAfter
        )

        Log.i(TAG, "--> [COMPLETED ${accelerator.name} / $priorityLabel] Duration: ${durationMs}ms (${durationMs / 1000.0}s), Chunks: ${indexResult.chunksCount}, Avg: ${avgMs.toInt()}ms/chunk")
        results.add(entry)
        return entry
    }

    @Test
    fun test01_cpu_normalPriority() {
        Log.i(TAG, "\n--- RUNNING TEST 1: CPU + NORMAL PRIORITY (nice 0) ---")
        val entry = executeBenchmark(
            accelerator = HardwareAccelerator.CPU,
            targetPriority = Process.THREAD_PRIORITY_DEFAULT,
            priorityLabel = "DEFAULT"
        )
        assertTrue(entry.durationMs > 0)
    }

    @Test
    fun test02_cpu_backgroundPriority() {
        Log.i(TAG, "\n--- RUNNING TEST 2: CPU + BACKGROUND PRIORITY (nice 10) ---")
        val entry = executeBenchmark(
            accelerator = HardwareAccelerator.CPU,
            targetPriority = Process.THREAD_PRIORITY_BACKGROUND,
            priorityLabel = "BACKGROUND"
        )
        assertTrue(entry.durationMs > 0)
    }

    @Test
    fun test03_gpu_normalPriority() {
        Log.i(TAG, "\n--- RUNNING TEST 3: GPU + NORMAL PRIORITY (nice 0) ---")
        val entry = executeBenchmark(
            accelerator = HardwareAccelerator.GPU,
            targetPriority = Process.THREAD_PRIORITY_DEFAULT,
            priorityLabel = "DEFAULT"
        )
        assertTrue(entry.durationMs > 0)
    }

    @Test
    fun test04_gpu_backgroundPriority() {
        Log.i(TAG, "\n--- RUNNING TEST 4: GPU + BACKGROUND PRIORITY (nice 10) ---")
        val entry = executeBenchmark(
            accelerator = HardwareAccelerator.GPU,
            targetPriority = Process.THREAD_PRIORITY_BACKGROUND,
            priorityLabel = "BACKGROUND"
        )
        assertTrue(entry.durationMs > 0)
    }
}

