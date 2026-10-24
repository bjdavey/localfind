package com.localfind.embed

import android.content.Context
import android.util.Log
import com.localfind.core.model.MODEL_FILE_NAME
import com.localfind.core.storage.StorageCheck
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Finds and stages the on-device LiteRT-LM model files (LiteRT-LM needs a real file path, not an asset).
 * [locate] is the ONLY place that inspects storage for models; everything else works from its [Models] result.
 *
 * Model kinds (naming convention from Hugging Face,
 * https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/tree/main):
 * - Universal: `embeddinggemma-2-740m.litertlm`, runs on GPU and CPU on every device.
 * - Chipset-specific NPU: `embeddinggemma-2-740m_<Vendor>_<Model>.litertlm` (name from [SocDetector.detectSoc]).
 *   Runs ONLY on the NPU: its text/vision encoders are an NPU-only `DISPATCH_OP` (audio is plain CPU code).
 *
 * Files in `filesDir` are always stored under the name of their kind, so the name tells what a file is.
 *
 * Delivery channels:
 * 1. Production, install-time Play AI Pack (:ai_pack:embedding_model): device-targeted, so NPU device groups get
 *    their NPU model and `group_other` the universal one. Copied once into `filesDir` ([stageAiPackModel]).
 * 2. Production, on-demand Play AI Pack (:ai_pack:universal_model): the universal model, downloaded only when
 *    the user asks for it after the NPU failed ([RecoveryModel]); used in place from Play's storage.
 * 3. Development (ADB / deploy-bundle scripts): copied from `/data/local/tmp`, `/sdcard/Download` or `/sdcard`.
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
     * The model files available on this device.
     * @property npu the chipset-specific NPU model, or null (none for this SoC, or not installed).
     * @property universal the universal GPU/CPU model, or null (not installed / not downloaded).
     */
    data class Models(val npu: File?, val universal: File?)

    /**
     * Finds every model the app can load, staging it into `filesDir` first when needed (copies of ~500MB, so
     * call off the main thread). Not cached here: [com.localfind.data.EngineManager] calls it on startup, on
     * "Retry Loading" and after a download, and Play requires on-demand pack locations to be checked every launch.
     */
    fun locate(context: Context): Models {
        stageAiPackModel(context)
        val npuName = SocDetector.detectSoc().expectedNpuModelName
        return Models(
            npu = npuName?.let { stagedFile(context, it) },
            universal = stagedFile(context, MODEL_FILE_NAME) ?: RecoveryModel.location(context)
        )
    }

    /** [name] in `filesDir`, copied there from a staging directory if needed; null when it is nowhere. */
    private fun stagedFile(context: Context, name: String): File? {
        val target = File(context.filesDir, name)
        if (target.isUsable()) return target
        return if (copyFromStagingIfPresent(name, target)) target else null
    }

    private fun File.isUsable() = exists() && length() > 0L

    /**
     * Makes the model of the install-time Play AI Pack available as a real file in `context.filesDir`.
     *
     * An install-time pack is installed together with the app (so the model exists at first launch, offline),
     * but Play only exposes its files through [android.content.res.AssetManager], while LiteRT-LM needs a file
     * path. The model is therefore copied once (temp file + rename, so a killed copy never leaves a truncated
     * model behind). The copy is repeated when its size differs from the packed asset (e.g. after an app update
     * that ships a new model). Costs one extra copy of the model on disk.
     *
     * The pack asset always has the universal file name, whatever it holds. Its targeting groups are exactly the
     * SoCs with an [SocInfo.expectedNpuModelName] (`device_targeting_configuration.xml`), so on those devices it is
     * the NPU model and is stored under that chipset name; elsewhere it is the universal model.
     *
     * @return the staged model file, or null when the app has no pack asset (development builds that stage the
     * model over ADB) or there is not enough free storage for the copy.
     */
    private fun stageAiPackModel(context: Context): File? {
        val target = File(context.filesDir, SocDetector.detectSoc().expectedNpuModelName ?: MODEL_FILE_NAME)
        return try {
            // No pack asset (development builds) -> FileNotFoundException -> null, before touching filesDir
            context.assets.open(AI_PACK_MODEL_RELATIVE_PATH).close()

            // openFd needs an uncompressed asset; if the pack asset is compressed, the size is unknown (-1).
            val assetSize = try {
                context.assets.openFd(AI_PACK_MODEL_RELATIVE_PATH).use { it.length }
            } catch (_: IOException) {
                -1L
            }
            if (target.isUsable() && (assetSize < 0L || target.length() == assetSize)) {
                return target
            }
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
