package com.localfind.embed

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Information about the host device's System-on-Chip (SoC) processor and Neural Processing Unit (NPU).
 *
 * @property vendor Standardized SoC manufacturer (e.g. "Qualcomm", "MediaTek", "Google", or "Unknown").
 * @property marketingName Human-readable marketing name (e.g. "Snapdragon 8 Gen 3", "Dimensity 9400").
 * @property expectedNpuModelName Expected pre-compiled model file name from Hugging Face LiteRT repository.
 * @property htpArchitecture Qualcomm Hexagon HTP architecture version (e.g. "V73", "V75", "V79", "V81").
 */
data class SocInfo(
    val vendor: String,
    val marketingName: String,
    val expectedNpuModelName: String?,
    val htpArchitecture: String? = null
)

/**
 * Utility for detecting the host device's chipset (SoC) and checking whether its NPU runtime is available.
 *
 * Model file resolution and staging live in [ModelFiles]; this object only answers chip questions
 * (which SoC, which NPU model name it expects, are its dispatch libraries packaged, link them into `filesDir`).
 */
object SocDetector {
    private const val TAG = "SocDetector"

    /**
     * Inspects system build properties to detect the device's SoC manufacturer, model, and marketing name.
     */
    fun detectSoc(): SocInfo = detectedSoc

    /**
     * The SoC never changes while the process lives, so the system property reads and parsing
     * run once (lazily, thread-safe) instead of on every call.
     */
    private val detectedSoc: SocInfo by lazy {
        val rawManufacturer = firstKnown(
            getSystemProperty("ro.soc.manufacturer"), Build.SOC_MANUFACTURER, Build.MANUFACTURER
        )
        val rawModel = firstKnown(
            getSystemProperty("ro.soc.model"), Build.SOC_MODEL, getSystemProperty("ro.board.platform"), Build.HARDWARE
        )
        val rawHardware = Build.HARDWARE.lowercase()
        val rawBoard = Build.BOARD.lowercase()

        parseSocInfo(
            rawManufacturer = rawManufacturer,
            rawModel = rawModel,
            rawHardware = rawHardware,
            rawBoard = rawBoard
        )
    }

    /**
     * First candidate that carries information. Blank values and `"unknown"` ([Build.UNKNOWN], what the
     * `Build.SOC_*` fields return when the device does not report them) count as unset, so a missing value
     * falls through to the next source instead of ending up as the SoC "name".
     */
    fun firstKnown(vararg candidates: String): String =
        candidates.firstOrNull { it.isNotBlank() && !it.equals(Build.UNKNOWN, ignoreCase = true) }.orEmpty()

    /**
     * Pure parsing logic separated for unit testing without Android runtime dependencies.
     */
    fun parseSocInfo(
        rawManufacturer: String,
        rawModel: String,
        rawHardware: String,
        rawBoard: String
    ): SocInfo {
        val mfgLower = rawManufacturer.lowercase()
        val modelUpper = rawModel.uppercase().replace(" ", "_")
        // The Snapdragon 8 Elite board is exactly "sun"; a substring match would also hit e.g. "sunfish" (Pixel 4a)
        val isSunBoard = rawBoard == "sun"

        // 1. Qualcomm Snapdragon
        if (mfgLower.contains("qti") || mfgLower.contains("qualcomm") ||
            rawHardware.contains("qcom") || modelUpper.contains("SM") ||
            rawBoard.contains("pineapple") || rawBoard.contains("kalama") || isSunBoard
        ) {
            val vendor = "Qualcomm"
            val (chipModel, marketing, htpArch) = when {
                modelUpper.contains("SM8650") || rawBoard.contains("pineapple") ->
                    Triple("SM8650", "Snapdragon 8 Gen 3", "V75")
                modelUpper.contains("SM8550") || rawBoard.contains("kalama") ->
                    Triple("SM8550", "Snapdragon 8 Gen 2", "V73")
                modelUpper.contains("SM8750") || isSunBoard ->
                    Triple("SM8750", "Snapdragon 8 Elite", "V79")
                modelUpper.contains("SM8850") ->
                    Triple("SM8850", "Snapdragon 8 Elite Gen 5", "V81")
                modelUpper.startsWith("SM") ->
                    Triple(modelUpper, "Snapdragon ($modelUpper)", null)
                else ->
                    Triple(if (modelUpper.isNotEmpty()) modelUpper else "Snapdragon", "Qualcomm Snapdragon", null)
            }
            val expectedModel = if (htpArch != null) {
                "embeddinggemma-2-740m_Qualcomm_$chipModel.litertlm"
            } else {
                null
            }
            return SocInfo(vendor, marketing, expectedModel, htpArch)
        }

        // 2. MediaTek Dimensity
        if (mfgLower.contains("mediatek") || rawHardware.startsWith("mt") || modelUpper.contains("MT69")) {
            val vendor = "MediaTek"
            val (chipModel, marketing, supportedNpu) = when {
                modelUpper.contains("MT6991") || modelUpper.contains("6991") ->
                    Triple("MT6991", "Dimensity 9400", true)
                modelUpper.contains("MT6993") || modelUpper.contains("6993") ->
                    Triple("MT6993", "Dimensity 9500", true)
                modelUpper.startsWith("MT") ->
                    Triple(modelUpper, "MediaTek ($modelUpper)", false)
                else ->
                    Triple(modelUpper, "MediaTek Dimensity", false)
            }
            val expectedModel = if (supportedNpu) {
                "embeddinggemma-2-740m_MediaTek_$chipModel.litertlm"
            } else {
                null
            }
            return SocInfo(vendor, marketing, expectedModel)
        }

        // 3. Google Tensor
        if (mfgLower.contains("google") || rawHardware.contains("tensor") ||
            rawBoard.contains("zuma") || rawBoard.contains("laguna") || modelUpper.contains("TENSOR")
        ) {
            val vendor = "Google"
            val (chipModel, marketing, supportedNpu) = when {
                modelUpper.contains("G5") || rawBoard.contains("laguna") ->
                    Triple("Tensor_G5", "Google Tensor G5", true)
                modelUpper.contains("G6") ->
                    Triple("Tensor_G6", "Google Tensor G6", true)
                else ->
                    Triple("Tensor", "Google Tensor", false)
            }
            val expectedModel = if (supportedNpu) {
                "embeddinggemma-2-740m_Google_$chipModel.litertlm"
            } else {
                null
            }
            return SocInfo(vendor, marketing, expectedModel)
        }

        // Generic / Unknown SoC
        val cleanVendor = rawManufacturer.ifBlank { "Generic" }
        val cleanModel = rawModel.ifBlank { "Unknown" }
        return SocInfo(cleanVendor, "$cleanVendor $cleanModel", null)
    }

    /**
     * Ensures that native hardware dispatch and NPU runtime libraries from [nativeLibraryDir]
     * are linked (via zero-byte POSIX symlink, or copied as fallback) into [targetDir]
     * (defaulting to [context.filesDir]), where LiteRT-LM's C++ `litert_dispatch.cc` searches
     * for `libLiteRtDispatch_*.so` and vendor QNN/NeuroPilot libraries.
     */
    fun ensureDispatchLibraries(context: Context, targetDir: File = context.filesDir) {
        val soc = detectSoc()
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)

        val dispatchLibs = nativeLibDir.listFiles { _, name ->
            when (soc.vendor) {
                "Qualcomm" -> name.startsWith("libLiteRtDispatch_Qualcomm") ||
                    name.startsWith("libLiteRtCompilerPlugin_Qualcomm") ||
                    name.startsWith("libQnn")
                "MediaTek" -> name.startsWith("libLiteRtDispatch_MediaTek") ||
                    name.startsWith("libneuron")
                "Google" -> name.startsWith("libLiteRtDispatch_Google") ||
                    name.startsWith("libedgetpu")
                else -> false
            }
        } ?: emptyArray()

        for (src in dispatchLibs) {
            val dst = File(targetDir, src.name)
            if (isLinkUpToDate(src, dst)) continue
            // Remove any stale symlink (pointing at an old nativeLibraryDir) or outdated binary after an APK update
            try {
                Os.remove(dst.absolutePath)
            } catch (_: Throwable) {
                dst.delete()
            }
            try {
                Os.symlink(src.absolutePath, dst.absolutePath)
                Log.i(TAG, "Symlinked native dispatch library ${src.name} -> ${dst.absolutePath}")
            } catch (_: Throwable) {
                try {
                    src.copyTo(dst, overwrite = true)
                    Log.i(TAG, "Staged native dispatch library ${src.name} (${src.length()} bytes) into ${dst.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stage dispatch library ${src.name}: ${e.message}")
                }
            }
        }
    }

    /**
     * True when [dst] already serves the current [src] library.
     *
     * A symlink is only current if it points at [src]'s present path: `File.exists()` follows links, so after an
     * APK update an old link whose target directory still exists would wrongly look fine. A regular file is the
     * copy fallback (used when symlinking fails) and is compared by size. A missing [dst] is never up to date.
     */
    private fun isLinkUpToDate(src: File, dst: File): Boolean =
        try {
            Os.readlink(dst.absolutePath) == src.absolutePath
        } catch (_: Throwable) {
            // Not a symlink (EINVAL for a regular file, ENOENT when missing)
            dst.isFile && dst.length() == src.length()
        }

    /**
     * Determines whether the native NPU dispatch and architecture-specific driver binaries
     * for the host device's chipset are packaged and installed in [context.applicationInfo.nativeLibraryDir].
     */
    fun isNpuRuntimeAvailable(context: Context): Boolean {
        val soc = detectSoc()
        if (soc.expectedNpuModelName == null) return false
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        return when (soc.vendor) {
            "Qualcomm" -> {
                val arch = soc.htpArchitecture ?: return false
                val stubName = "libQnnHtp${arch}Stub.so"
                File(nativeLibDir, stubName).exists() &&
                    File(nativeLibDir, "libLiteRtDispatch_Qualcomm.so").exists()
            }
            "MediaTek" -> {
                File(nativeLibDir, "libLiteRtDispatch_MediaTek.so").exists() &&
                    (File(nativeLibDir, "libneuronusdk_adapter.mtk.so").exists() ||
                        File(nativeLibDir, "libneuronusdk_adapter.so").exists())
            }
            "Google" -> {
                File(nativeLibDir, "libLiteRtDispatch_GoogleTensor.so").exists() ||
                    File(nativeLibDir, "libLiteRtDispatch_Google.so").exists()
            }
            else -> false
        }
    }

    private fun getSystemProperty(key: String): String {
        return try {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val getMethod = systemProperties.getMethod("get", String::class.java)
            (getMethod.invoke(null, key) as? String)?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }
}
