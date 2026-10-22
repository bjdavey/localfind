package com.localfind.embed

import android.content.Context
import android.os.Build
import android.util.Log
import com.localfind.data.HardwareAccelerator
import java.io.File

/**
 * Information about the host device's System-on-Chip (SoC) processor and Neural Processing Unit (NPU).
 *
 * @property vendor Standardized SoC manufacturer (e.g. "Qualcomm", "MediaTek", "Google", or "Unknown").
 * @property model Standardized SoC chipset model (e.g. "SM8650", "SM8550", "SM8750", "MT6991", "Tensor_G5").
 * @property marketingName Human-readable marketing name (e.g. "Snapdragon 8 Gen 3", "Dimensity 9400").
 * @property expectedNpuModelName Expected pre-compiled model file name from Hugging Face LiteRT repository.
 */
data class SocInfo(
    val vendor: String,
    val model: String,
    val marketingName: String,
    val expectedNpuModelName: String?,
    val htpArchitecture: String? = null
)

/**
 * Utility for detecting host device chipset architecture, staging native dispatch libraries,
 * and resolving matching on-device LiteRT model files.
 *
 * Supported model naming convention from Hugging Face:
 * https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/tree/main
 *
 * - Default Base Model: `embeddinggemma-2-740m.litertlm` (universal fallback across all devices)
 * - Chipset-specific NPU models: `embeddinggemma-2-740m_<Vendor>_<Model>.litertlm`
 *
 * Direct Targeting: Checks specific expected file paths without scanning or walking storage directories.
 * 100% Standalone: Operates with zero dependency on external apps or Google AI Edge Gallery.
 */
object SocDetector {
    private const val TAG = "SocDetector"

    /** Candidate staging paths on Android devices (e.g. from ADB push or browser downloads). */
    private val STAGING_DIRECTORIES = listOf(
        File("/data/local/tmp"),
        File("/sdcard/Download"),
        File("/sdcard")
    )

    /**
     * Inspects system build properties to detect the device's SoC manufacturer, model, and marketing name.
     */
    fun detectSoc(): SocInfo {
        val rawManufacturer = getSystemProperty("ro.soc.manufacturer")
            .ifEmpty { Build.SOC_MANUFACTURER }
            .ifEmpty { Build.MANUFACTURER }
        val rawModel = getSystemProperty("ro.soc.model")
            .ifEmpty { Build.SOC_MODEL }
            .ifEmpty { getSystemProperty("ro.board.platform") }
            .ifEmpty { Build.HARDWARE }
        val rawHardware = Build.HARDWARE.lowercase()
        val rawBoard = Build.BOARD.lowercase()

        return parseSocInfo(
            rawManufacturer = rawManufacturer,
            rawModel = rawModel,
            rawHardware = rawHardware,
            rawBoard = rawBoard
        )
    }

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

        // 1. Qualcomm Snapdragon
        if (mfgLower.contains("qti") || mfgLower.contains("qualcomm") ||
            rawHardware.contains("qcom") || modelUpper.contains("SM") ||
            rawBoard.contains("pineapple") || rawBoard.contains("kalama") || rawBoard.contains("sun")
        ) {
            val vendor = "Qualcomm"
            val (chipModel, marketing, htpArch) = when {
                modelUpper.contains("SM8650") || rawBoard.contains("pineapple") ->
                    Triple("SM8650", "Snapdragon 8 Gen 3", "V75")
                modelUpper.contains("SM8550") || rawBoard.contains("kalama") ->
                    Triple("SM8550", "Snapdragon 8 Gen 2", "V73")
                modelUpper.contains("SM8750") || rawBoard.contains("sun") ->
                    Triple("SM8750", "Snapdragon 8 Elite", "V79")
                modelUpper.contains("SM8850") ->
                    Triple("SM8850", "Snapdragon Next-Gen", null)
                modelUpper.startsWith("SM") ->
                    Triple(modelUpper, "Snapdragon ($modelUpper)", null)
                else ->
                    Triple(if (modelUpper.isNotEmpty()) modelUpper else "Snapdragon", "Qualcomm Snapdragon", null)
            }
            val expectedModel = "embeddinggemma-2-740m_Qualcomm_$chipModel.litertlm"
            return SocInfo(vendor, chipModel, marketing, expectedModel, htpArch)
        }

        // 2. MediaTek Dimensity
        if (mfgLower.contains("mediatek") || rawHardware.startsWith("mt") || modelUpper.contains("MT69")) {
            val vendor = "MediaTek"
            val (chipModel, marketing) = when {
                modelUpper.contains("MT6991") || modelUpper.contains("6991") ->
                    "MT6991" to "Dimensity 9400"
                modelUpper.contains("MT6993") || modelUpper.contains("6993") ->
                    "MT6993" to "Dimensity Next-Gen"
                modelUpper.startsWith("MT") ->
                    modelUpper to "MediaTek ($modelUpper)"
                else ->
                    modelUpper to "MediaTek Dimensity"
            }
            val expectedModel = "embeddinggemma-2-740m_MediaTek_$chipModel.litertlm"
            return SocInfo(vendor, chipModel, marketing, expectedModel)
        }

        // 3. Google Tensor
        if (mfgLower.contains("google") || rawHardware.contains("tensor") ||
            rawBoard.contains("zuma") || rawBoard.contains("laguna") || modelUpper.contains("TENSOR")
        ) {
            val vendor = "Google"
            val (chipModel, marketing) = when {
                modelUpper.contains("G5") || rawBoard.contains("laguna") ->
                    "Tensor_G5" to "Google Tensor G5"
                modelUpper.contains("G6") ->
                    "Tensor_G6" to "Google Tensor G6"
                else ->
                    "Tensor" to "Google Tensor"
            }
            val expectedModel = "embeddinggemma-2-740m_Google_$chipModel.litertlm"
            return SocInfo(vendor, chipModel, marketing, expectedModel)
        }

        // Generic / Unknown SoC
        val cleanVendor = rawManufacturer.ifBlank { "Generic" }
        val cleanModel = rawModel.ifBlank { "Unknown" }
        return SocInfo(cleanVendor, cleanModel, "$cleanVendor $cleanModel", null)
    }

    /**
     * Resolves the expected model file name for the given accelerator without scanning internal storage.
     *
     * Directly checks if the device's target NPU model file exists in filesDir or candidate staging paths.
     * If not found or if accelerator is not NPU, falls back to the default [MODEL_FILE_NAME].
     */
    fun resolveModelName(context: Context, accelerator: HardwareAccelerator? = null): String {
        if (accelerator == HardwareAccelerator.NPU) {
            val soc = detectSoc()
            val expectedName = soc.expectedNpuModelName
            if (expectedName != null) {
                // Direct existence check: only checks the specific expected file
                if (File(context.filesDir, expectedName).exists() || hasStagedFile(expectedName)) {
                    return expectedName
                }
            }
        }
        return MODEL_FILE_NAME
    }

    /**
     * Returns a [File] handle to the active model in [context.filesDir] based on the current accelerator.
     */
    fun getActiveModelFile(context: Context, accelerator: HardwareAccelerator? = null): File {
        val resolvedName = resolveModelName(context, accelerator)
        val file = File(context.filesDir, resolvedName)
        return if (file.exists() && file.length() > 0L) {
            file
        } else {
            File(context.filesDir, MODEL_FILE_NAME)
        }
    }

    /**
     * Ensures that the required model file exists in [context.filesDir].
     *
     * Directly checks for the target model (or default [MODEL_FILE_NAME]) without scanning storage directories.
     * If missing from internal storage, checks exact candidate staging locations (`/data/local/tmp`, `/sdcard/Download`)
     * and copies the file atomically.
     *
     * @param context Application context.
     * @param accelerator Current hardware accelerator preference.
     * @param modelName Optional explicit model file name override.
     * @return [File] pointing to the model in [context.filesDir].
     */
    fun ensureModelFile(
        context: Context,
        accelerator: HardwareAccelerator? = null,
        modelName: String? = null
    ): File {
        val targetName = modelName ?: resolveModelName(context, accelerator)
        val target = File(context.filesDir, targetName)
        if (target.exists() && target.length() > 0L) {
            return target
        }

        // Check exact staging locations for the target model
        val staged = copyFromStagingIfPresent(targetName, target)
        if (staged) {
            return target
        }

        // If NPU-specific model was requested but not found, fallback to checking default MODEL_FILE_NAME
        if (targetName != MODEL_FILE_NAME) {
            val fallbackTarget = File(context.filesDir, MODEL_FILE_NAME)
            if (fallbackTarget.exists() && fallbackTarget.length() > 0L) {
                Log.w(TAG, "Preferred model $targetName not found. Falling back to default $MODEL_FILE_NAME.")
                return fallbackTarget
            }
            if (copyFromStagingIfPresent(MODEL_FILE_NAME, fallbackTarget)) {
                Log.w(TAG, "Staged default model $MODEL_FILE_NAME as fallback for $targetName.")
                return fallbackTarget
            }
        }

        return target
    }

    /**
     * Ensures that native hardware dispatch and NPU runtime libraries from [nativeLibraryDir]
     * are staged into [context.filesDir], matching the host device's specific chipset vendor.
     *
     * Vendor filtering:
     * - Qualcomm: Stages `libLiteRtDispatch_Qualcomm.so` and Qualcomm QNN binaries (`libQnn*.so`).
     * - MediaTek: Stages `libLiteRtDispatch_MediaTek.so` / Neuron binaries when packaged.
     * - Google Tensor: Stages `libLiteRtDispatch_Google*.so` when packaged.
     * - Other/Unsupported: Skips staging to avoid wasting internal storage with incompatible ~21MB binaries.
     */
    fun ensureDispatchLibraries(context: Context) {
        val soc = detectSoc()
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val filesDir = context.filesDir

        // Filter native libraries to match only the current hardware chipset vendor
        val dispatchLibs = nativeLibDir.listFiles { _, name ->
            when (soc.vendor) {
                "Qualcomm" -> name.startsWith("libLiteRtDispatch_Qualcomm") || name.startsWith("libQnn")
                "MediaTek" -> name.startsWith("libLiteRtDispatch_MediaTek") || name.startsWith("libneuron")
                "Google" -> name.startsWith("libLiteRtDispatch_Google") || name.startsWith("libedgetpu")
                else -> false
            }
        } ?: emptyArray()

        for (src in dispatchLibs) {
            val dst = File(filesDir, src.name)
            if (!dst.exists() || dst.length() != src.length()) {
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
     * Determines whether the native NPU dispatch and architecture-specific driver binaries
     * for the host device's chipset are packaged in this APK build.
     */
    fun isNpuRuntimeAvailable(context: Context): Boolean {
        val soc = detectSoc()
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        return when (soc.vendor) {
            "Qualcomm" -> {
                val stubName = "libQnnHtp${soc.htpArchitecture ?: "V75"}Stub.so"
                File(nativeLibDir, stubName).exists() && File(nativeLibDir, "libLiteRtDispatch_Qualcomm.so").exists()
            }
            "MediaTek" -> File(nativeLibDir, "libLiteRtDispatch_MediaTek.so").exists()
            "Google" -> File(nativeLibDir, "libLiteRtDispatch_Google.so").exists() || File(nativeLibDir, "libLiteRtDispatch_GoogleTensor.so").exists()
            else -> false
        }
    }

    private fun hasStagedFile(fileName: String): Boolean {
        return STAGING_DIRECTORIES.any { dir ->
            val file = File(dir, fileName)
            file.exists() && file.length() > 0L
        }
    }

    private fun copyFromStagingIfPresent(fileName: String, target: File): Boolean {
        for (dir in STAGING_DIRECTORIES) {
            val source = File(dir, fileName)
            if (source.exists() && source.canRead() && source.length() > 0L) {
                val tempFile = File(target.parentFile, "${target.name}.tmp")
                try {
                    Log.i(TAG, "Found model at ${source.absolutePath} (${source.length()} bytes). Copying to ${target.absolutePath}...")
                    source.inputStream().use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                    if (tempFile.renameTo(target)) {
                        Log.i(TAG, "Successfully staged model to ${target.absolutePath}")
                        return true
                    } else {
                        tempFile.delete()
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    Log.e(TAG, "Error copying model from ${source.absolutePath}", e)
                }
            }
        }
        return false
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
