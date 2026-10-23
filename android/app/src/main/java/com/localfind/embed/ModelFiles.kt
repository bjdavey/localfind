package com.localfind.embed

import android.content.Context
import android.util.Log
import com.localfind.core.model.MODEL_FILE_NAME
import com.localfind.core.settings.HardwareAccelerator
import com.localfind.core.storage.StorageCheck
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Resolves and stages the on-device LiteRT-LM model file in `context.filesDir`
 * (LiteRT-LM needs a real file path, not an asset).
 *
 * Supported model naming convention from Hugging Face:
 * https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/tree/main
 *
 * - Default Base Model: `embeddinggemma-2-740m.litertlm` (universal CPU/GPU fallback across all devices)
 * - Chipset-specific NPU models: `embeddinggemma-2-740m_<Vendor>_<Model>.litertlm`
 *   (the expected name comes from [SocDetector.detectSoc])
 *
 * Delivery channels:
 * 1. Production (Google Play AI Pack `install-time`): Installed with the app as the asset
 *    `model/embeddinggemma-2-740m.litertlm` (device-targeted via `#group_<Vendor>_<Model>`) and copied once
 *    into `context.filesDir`.
 * 2. Development (ADB): Staged from `/data/local/tmp` or `/sdcard/Download` into `context.filesDir`.
 */
object ModelFiles {
    private const val TAG = "ModelFiles"

    /** Relative asset path inside the install-time Play AI Pack (:ai_pack:embedding_model). */
    private const val AI_PACK_MODEL_RELATIVE_PATH = "model/$MODEL_FILE_NAME"

    /** Candidate staging paths on Android devices (e.g. from ADB push or browser downloads). */
    private val STAGING_DIRECTORIES = listOf(
        File("/data/local/tmp"),
        File("/sdcard/Download"),
        File("/sdcard")
    )

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
            // No pack asset (development builds) -> null, BEFORE looking at filesDir. Otherwise an unrelated model
            // already in filesDir (e.g. the generic CPU/GPU one) would be returned as "the pack model" and win
            // over the device's NPU-compiled model, which then fails to run on the NPU.
            context.assets.open(AI_PACK_MODEL_RELATIVE_PATH).close()

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
                Log.i(TAG, "Copying packed model to ${target.absolutePath}...")
                copyAtomically(input, target)
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
            val expectedName = SocDetector.detectSoc().expectedNpuModelName
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
                try {
                    Log.i(TAG, "Found model at ${source.absolutePath} (${source.length()} bytes). Copying to ${target.absolutePath}...")
                    source.inputStream().use { copyAtomically(it, target) }
                    Log.i(TAG, "Successfully staged model to ${target.absolutePath}")
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Error copying model from ${source.absolutePath}", e)
                }
            }
        }
        return false
    }

    /**
     * Copies [input] to [target] through a temp file in the same directory and renames it into place, so a
     * killed or failed copy never leaves a truncated model at [target]. The temp file is removed on failure.
     *
     * @throws IOException (or another exception from the copy) when the copy or the rename fails.
     */
    private fun copyAtomically(input: InputStream, target: File) {
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        try {
            tempFile.outputStream().use { output -> input.copyTo(output, bufferSize = 64 * 1024) }
            if (!tempFile.renameTo(target)) throw IOException("Could not move $tempFile to $target")
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }
}

