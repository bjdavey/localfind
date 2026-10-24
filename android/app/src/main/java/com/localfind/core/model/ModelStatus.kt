package com.localfind.core.model

/**
 * Observable lifecycle status of the on-device embedding model.
 */
sealed interface ModelStatus {
    /** Model is currently being verified, copied, or initialized in memory. */
    data object Loading : ModelStatus

    /**
     * Model is verified in internal storage and initialized in memory.
     *
     * @property sizeBytes File size in bytes.
     */
    data class Loaded(val sizeBytes: Long) : ModelStatus

    /**
     * Model is not loaded or failed to initialize.
     *
     * @property message Descriptive explanation of the issue.
     * @property instructions Clear action steps for loading the model on device or emulator.
     * @property canDownloadCompatibleModel true when no GPU/CPU (universal) model is installed, so downloading it
     *   from Play (on-demand AI pack) can make the app work, e.g. when the device's NPU model fails to load.
     */
    data class Error(
        val message: String,
        val instructions: String,
        val canDownloadCompatibleModel: Boolean = false
    ) : ModelStatus

    /**
     * The universal model is being downloaded by Play.
     *
     * @property percent Download progress, 0-100.
     * @property needsConfirmation Play waits for the user (cellular download over 200MB, or an install Play
     *   does not recognize) until its confirmation dialog is accepted.
     */
    data class Downloading(val percent: Int, val needsConfirmation: Boolean) : ModelStatus
}
