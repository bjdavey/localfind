package com.localfind.embed

import com.localfind.core.model.MODEL_FILE_NAME
import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import com.localfind.core.settings.HardwareAccelerator
import com.localfind.core.storage.StorageCheck
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

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
 * Utility for detecting host device chipset architecture, checking NPU runtime availability,
 * and resolving matching on-device LiteRT-LM model files from Google Play AI Packs or local storage.
 *
 * Supported model naming convention from Hugging Face:
 * https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/tree/main
 *
 * - Default Base Model: `embeddinggemma-2-740m.litertlm` (universal CPU/GPU fallback across all devices)
 * - Chipset-specific NPU models: `embeddinggemma-2-740m_<Vendor>_<Model>.litertlm`
 *
 * Delivery channels:
 * 1. Production (Google Play AI Pack `install-time`): Installed with the app as the asset `model/embeddinggemma-2-740m.litertlm` (device-targeted via `#group_<Vendor>_<Model>`) and copied once into `context.filesDir`.
 * 2. Development (ADB): Staged from `/data/local/tmp` or `/sdcard/Download` into `context.filesDir`.
 */
object SocDetector {
    private const val TAG = "SocDetector"

    /** Relative asset path inside the install-time Play AI Pack (:ai_pack:embedding_model). */
    private const val AI_PACK_MODEL_RELATIVE_PATH = "model/$MODEL_FILE_NAME"

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
     * Makes the model of the install-time Play AI Pack available as a real file in `context.filesDir`.
     *
     * An install-time pack is installed together with the app (so the model exists at first launch, offline),
     * but Play only exposes its files through [android.content.res.AssetManager], while LiteRT-LM needs a file
     * path. The model is therefore copied once (temp file + rename, so a killed copy never leaves a truncated
     * model behind). The copy is repeated when its size differs from the packed asset (e.g. after an app update
     * that ships a new model). Costs one extra copy of the model on disk.
     *
     * @return the staged model file, or null when the app has no pack asset (development builds that stage the
     * model over ADB) or there is not enough free storage for the copy.
     */
    fun stageAiPackModel(context: Context): File? {
        val target = File(context.filesDir, MODEL_FILE_NAME)
        return try {
            // openFd needs an uncompressed asset; if the pack asset is compressed, the size is unknown (-1).
            val assetSize = try {
                context.assets.openFd(AI_PACK_MODEL_RELATIVE_PATH).use { it.length }
            } catch (_: IOException) {
                -1L
            }
            if (target.exists() && target.length() > 0L && (assetSize < 0L || target.length() == assetSize)) {
                return target
            }
            // Fails with FileNotFoundException when the pack asset does not exist
            context.assets.open(AI_PACK_MODEL_RELATIVE_PATH).use { input ->
                val needed = maxOf(assetSize, 0L)
                if (!StorageCheck.hasEnoughSpace(context.filesDir.usableSpace, neededBytes = needed)) {
                    Log.w(TAG, "Not enough free storage to copy the packed model ($needed bytes)")
                    return null
                }
                val tempFile = File(context.filesDir, "${target.name}.tmp")
                try {
                    Log.i(TAG, "Copying packed model to ${target.absolutePath}...")
                    tempFile.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
                    if (!tempFile.renameTo(target)) throw IOException("Could not move $tempFile to $target")
                } catch (e: Exception) {
                    tempFile.delete()
                    throw e
                }
            }
            target
        } catch (_: FileNotFoundException) {
            null
        } catch (e: IOException) {
            Log.e(TAG, "Error copying the packed model", e)
            null
        }
    }

    /**
     * Resolves the expected model file name for the given accelerator without scanning internal storage.
     *
     * Directly checks if the device's target NPU model file exists in `filesDir` or candidate staging paths.
     * If not found or if accelerator is not NPU, falls back to the default [MODEL_FILE_NAME].
     */
    fun resolveModelName(context: Context, accelerator: HardwareAccelerator? = null): String {
        if (accelerator == HardwareAccelerator.NPU) {
            val soc = detectSoc()
            val expectedName = soc.expectedNpuModelName
            if (expectedName != null) {
                if (File(context.filesDir, expectedName).exists() || hasStagedFile(expectedName)) {
                    return expectedName
                }
            }
        }
        return MODEL_FILE_NAME
    }

    /**
     * Returns a [File] handle to the active model in [context.filesDir] (staged from the Play AI Pack or ADB)
     * based on the current accelerator.
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
     * Ensures that the required model file is available on disk.
     *
     * Resolution priority:
     * 1. Model of the install-time Google Play AI Pack, copied into [context.filesDir] ([stageAiPackModel]).
     * 2. Existing model file in [context.filesDir].
     * 3. Candidate ADB staging locations (`/data/local/tmp`, `/sdcard/Download`, `/sdcard`), copied atomically.
     *
     * @param context Application context.
     * @param accelerator Current hardware accelerator preference.
     * @param modelName Optional explicit model file name override.
     * @return [File] pointing to the ready-to-load `.litertlm` model on disk.
     */
    fun ensureModelFile(
        context: Context,
        accelerator: HardwareAccelerator? = null,
        modelName: String? = null
    ): File {
        if (modelName == null) {
            stageAiPackModel(context)?.let { aiPackFile ->
                Log.i(TAG, "Using Play AI Pack model at ${aiPackFile.absolutePath}")
                return aiPackFile
            }
        }

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
                // The copy needs the model's size plus headroom; a half-written copy would just fail later
                if (!StorageCheck.hasEnoughSpace(target.parentFile?.usableSpace ?: 0L, neededBytes = source.length())) {
                    Log.w(TAG, "Not enough free storage to stage ${source.name} (${source.length()} bytes), skipping")
                    continue
                }
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
