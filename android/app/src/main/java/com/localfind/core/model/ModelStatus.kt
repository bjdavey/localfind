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
     */
    data class Error(val message: String, val instructions: String) : ModelStatus
}
