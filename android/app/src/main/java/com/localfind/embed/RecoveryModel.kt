package com.localfind.embed

import android.content.Context
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.aipacks.AiPackManagerFactory
import com.google.android.play.core.aipacks.AiPackState
import com.google.android.play.core.aipacks.AiPackStateUpdateListener
import com.google.android.play.core.aipacks.model.AiPackErrorCode
import com.google.android.play.core.aipacks.model.AiPackStatus
import com.localfind.core.model.MODEL_FILE_NAME
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The universal GPU/CPU model as an on-demand Play AI Pack (:ai_pack:universal_model).
 *
 * NPU devices are only given their NPU-compiled model, which cannot run on GPU/CPU. If the NPU then fails on
 * such a device (firmware / driver), nothing can run and search is impossible; this pack is the way out. It is
 * only fetched when the user asks for it, and Play does the download (the app makes no network calls). Only
 * works when the app was installed from Play (or with bundletool `--local-testing`).
 */
object RecoveryModel {
    private const val TAG = "RecoveryModel"
    private const val PACK_NAME = "universal_model"
    private const val MODEL_PATH = "model/$MODEL_FILE_NAME"

    /** Approximate download size shown to the user before they confirm (the universal model is ~485MB). */
    const val DOWNLOAD_SIZE_MB = 485

    /**
     * The downloaded universal model, or null when the pack is not downloaded (or Play is unavailable). Play
     * stores on-demand packs as real files, so the model is used in place without another copy.
     */
    fun location(context: Context): File? = try {
        AiPackManagerFactory.getInstance(context).getPackLocation(PACK_NAME)?.assetsPath()
            ?.let { File(it, MODEL_PATH) }
            ?.takeIf { it.exists() && it.length() > 0L }
    } catch (e: Exception) {
        Log.w(TAG, "Could not query the recovery model pack: ${e.message}")
        null
    }

    /** Download progress reported to [download]'s caller. */
    data class Progress(val percent: Int, val needsConfirmation: Boolean)

    /**
     * Asks Play to download the pack and suspends until it is on the device.
     *
     * [onProgress] is called for every state update; `needsConfirmation` is set while Play waits for the user
     * (cellular download over 200MB, or an install Play does not recognize): call [showConfirmation] then.
     *
     * @return the downloaded model file.
     * @throws IOException when the download fails or is canceled, with a message for the user.
     */
    suspend fun download(context: Context, onProgress: (Progress) -> Unit): File {
        val manager = AiPackManagerFactory.getInstance(context)
        return suspendCancellableCoroutine { cont ->
            lateinit var listener: AiPackStateUpdateListener
            fun finish(result: Result<File>) {
                manager.unregisterListener(listener)
                if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }
            fun onState(state: AiPackState) {
                if (state.name() != PACK_NAME) return
                when (state.status()) {
                    AiPackStatus.COMPLETED -> location(context)?.let { finish(Result.success(it)) }
                        ?: finish(Result.failure(IOException("Download finished but the model file is missing")))
                    AiPackStatus.FAILED -> finish(Result.failure(IOException(errorMessage(state.errorCode()))))
                    AiPackStatus.CANCELED -> finish(Result.failure(IOException("Download canceled")))
                    AiPackStatus.WAITING_FOR_WIFI, AiPackStatus.REQUIRES_USER_CONFIRMATION ->
                        onProgress(Progress(percentOf(state), needsConfirmation = true))
                    else -> onProgress(Progress(percentOf(state), needsConfirmation = false))
                }
            }
            listener = AiPackStateUpdateListener { onState(it) }
            manager.registerListener(listener)
            cont.invokeOnCancellation { manager.unregisterListener(listener) }
            // fetch() also returns the current state: a pack that is already downloaded completes right away
            manager.fetch(listOf(PACK_NAME))
                .addOnSuccessListener { states -> states.packStates()[PACK_NAME]?.let { onState(it) } }
                // The fetch exception type is not public, so its message is all there is to show
                .addOnFailureListener { finish(Result.failure(IOException(it.message ?: "Download failed", it))) }
        }
    }

    /** Shows Play's dialog that lets the user approve a pending download (cellular / unrecognized install). */
    fun showConfirmation(context: Context, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        AiPackManagerFactory.getInstance(context).showConfirmationDialog(launcher)
    }

    private fun percentOf(state: AiPackState): Int =
        if (state.totalBytesToDownload() > 0L) (state.bytesDownloaded() * 100 / state.totalBytesToDownload()).toInt() else 0

    private fun errorMessage(errorCode: Int): String = when (errorCode) {
        AiPackErrorCode.APP_UNAVAILABLE, AiPackErrorCode.APP_NOT_OWNED, AiPackErrorCode.UNRECOGNIZED_INSTALLATION,
        AiPackErrorCode.API_NOT_AVAILABLE -> "Only available when LocalFind is installed from Google Play"
        AiPackErrorCode.PACK_UNAVAILABLE -> "The compatible model is not available for this app version"
        AiPackErrorCode.NETWORK_ERROR -> "No internet connection. Connect and try again"
        AiPackErrorCode.INSUFFICIENT_STORAGE -> "Not enough free storage for the ${DOWNLOAD_SIZE_MB} MB model"
        else -> "Download failed (Play error $errorCode)"
    }
}
