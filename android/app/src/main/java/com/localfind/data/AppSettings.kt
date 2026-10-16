package com.localfind.data

import android.content.Context
import android.content.SharedPreferences

/** Default minimum cosine similarity score required for search results (user-requested default: 0.6). */
const val DEFAULT_SIMILARITY_THRESHOLD = 0.6f

/** Default maximum number of top nearest-neighbor search results returned. */
const val DEFAULT_TOP_K = 10

/** Default passage character length when chunking text and Markdown documents. */
const val DEFAULT_TEXT_CHUNK_SIZE = 500

/** Default character overlap between consecutive sliding text chunks. */
const val DEFAULT_TEXT_CHUNK_OVERLAP = 100

/** Default fixed audio chunk duration in seconds. */
const val DEFAULT_AUDIO_WINDOW_SECONDS = 10

/** Default video sliding window duration in seconds. */
const val DEFAULT_VIDEO_WINDOW_SECONDS = 5

/** Default number of frames sampled and embedded per video window. */
const val DEFAULT_VIDEO_FRAMES_PER_WINDOW = 2

/** Default maximum width or height in pixels for decoding images and video frames. */
const val DEFAULT_MAX_IMAGE_DIMENSION = 512

/**
 * Configuration model holding all tunable variables for searching and indexing in LocalFind.
 *
 * All settings run 100% offline on-device and are persisted via Android [SharedPreferences].
 *
 * @property similarityThreshold Minimum cosine similarity cutoff score (0.0 .. 1.0). Matches below
 *                               this threshold are filtered out from search results.
 * @property topK Maximum number of nearest-neighbor results retrieved per search query.
 * @property textChunkSize Preferred character length per text/markdown chunk.
 * @property textChunkOverlap Sliding overlap characters between consecutive text chunks.
 * @property audioWindowSeconds Duration of each audio slice embedded by the multimodal model.
 * @property videoWindowSeconds Duration of each video window sliding across the media.
 * @property videoFramesPerWindow Number of visual frames extracted and embedded per video window.
 * @property maxImageDimension Maximum dimension (width/height px) for subsampling image/video inputs.
 */
data class AppSettings(
    val similarityThreshold: Float = DEFAULT_SIMILARITY_THRESHOLD,
    val topK: Int = DEFAULT_TOP_K,
    val textChunkSize: Int = DEFAULT_TEXT_CHUNK_SIZE,
    val textChunkOverlap: Int = DEFAULT_TEXT_CHUNK_OVERLAP,
    val audioWindowSeconds: Int = DEFAULT_AUDIO_WINDOW_SECONDS,
    val videoWindowSeconds: Int = DEFAULT_VIDEO_WINDOW_SECONDS,
    val videoFramesPerWindow: Int = DEFAULT_VIDEO_FRAMES_PER_WINDOW,
    val maxImageDimension: Int = DEFAULT_MAX_IMAGE_DIMENSION
) {
    /**
     * Returns a copy of [AppSettings] with all values clamped to valid and safe ranges.
     */
    fun sanitized(): AppSettings {
        val safeTextChunkSize = textChunkSize.coerceIn(100, 5000)
        return copy(
            similarityThreshold = similarityThreshold.coerceIn(0.0f, 1.0f),
            topK = topK.coerceIn(1, 100),
            textChunkSize = safeTextChunkSize,
            textChunkOverlap = textChunkOverlap.coerceIn(0, (safeTextChunkSize / 2).coerceAtLeast(0)),
            audioWindowSeconds = audioWindowSeconds.coerceIn(1, 60),
            videoWindowSeconds = videoWindowSeconds.coerceIn(1, 60),
            videoFramesPerWindow = videoFramesPerWindow.coerceIn(1, 10),
            maxImageDimension = maxImageDimension.coerceIn(128, 2048)
        )
    }
}

/**
 * Storage manager for persisting and loading [AppSettings] using Android's native [SharedPreferences].
 *
 * 100% offline, lightweight, and thread-safe.
 */
class SettingsStore(private val prefs: SharedPreferences) {

    /** Secondary constructor accepting Android [Context] for production app usage. */
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    /**
     * Loads the current persisted settings, applying defaults for any unset keys and
     * validating bounds via [AppSettings.sanitized].
     */
    fun load(): AppSettings {
        return AppSettings(
            similarityThreshold = prefs.getFloat(KEY_SIMILARITY_THRESHOLD, DEFAULT_SIMILARITY_THRESHOLD),
            topK = prefs.getInt(KEY_TOP_K, DEFAULT_TOP_K),
            textChunkSize = prefs.getInt(KEY_TEXT_CHUNK_SIZE, DEFAULT_TEXT_CHUNK_SIZE),
            textChunkOverlap = prefs.getInt(KEY_TEXT_CHUNK_OVERLAP, DEFAULT_TEXT_CHUNK_OVERLAP),
            audioWindowSeconds = prefs.getInt(KEY_AUDIO_WINDOW_SECONDS, DEFAULT_AUDIO_WINDOW_SECONDS),
            videoWindowSeconds = prefs.getInt(KEY_VIDEO_WINDOW_SECONDS, DEFAULT_VIDEO_WINDOW_SECONDS),
            videoFramesPerWindow = prefs.getInt(KEY_VIDEO_FRAMES_PER_WINDOW, DEFAULT_VIDEO_FRAMES_PER_WINDOW),
            maxImageDimension = prefs.getInt(KEY_MAX_IMAGE_DIMENSION, DEFAULT_MAX_IMAGE_DIMENSION)
        ).sanitized()
    }

    /**
     * Persists [settings] to [SharedPreferences] after clamping values to safe bounds.
     */
    fun save(settings: AppSettings) {
        val valid = settings.sanitized()
        prefs.edit()
            .putFloat(KEY_SIMILARITY_THRESHOLD, valid.similarityThreshold)
            .putInt(KEY_TOP_K, valid.topK)
            .putInt(KEY_TEXT_CHUNK_SIZE, valid.textChunkSize)
            .putInt(KEY_TEXT_CHUNK_OVERLAP, valid.textChunkOverlap)
            .putInt(KEY_AUDIO_WINDOW_SECONDS, valid.audioWindowSeconds)
            .putInt(KEY_VIDEO_WINDOW_SECONDS, valid.videoWindowSeconds)
            .putInt(KEY_VIDEO_FRAMES_PER_WINDOW, valid.videoFramesPerWindow)
            .putInt(KEY_MAX_IMAGE_DIMENSION, valid.maxImageDimension)
            .apply()
    }

    /**
     * Clears all persisted settings and resets to system default values.
     *
     * @return Fresh instance of [AppSettings] initialized with defaults.
     */
    fun reset(): AppSettings {
        prefs.edit().clear().apply()
        return AppSettings()
    }

    companion object {
        const val PREFS_NAME = "localfind_settings"
        const val KEY_SIMILARITY_THRESHOLD = "similarity_threshold"
        const val KEY_TOP_K = "top_k"
        const val KEY_TEXT_CHUNK_SIZE = "text_chunk_size"
        const val KEY_TEXT_CHUNK_OVERLAP = "text_chunk_overlap"
        const val KEY_AUDIO_WINDOW_SECONDS = "audio_window_seconds"
        const val KEY_VIDEO_WINDOW_SECONDS = "video_window_seconds"
        const val KEY_VIDEO_FRAMES_PER_WINDOW = "video_frames_per_window"
        const val KEY_MAX_IMAGE_DIMENSION = "max_image_dimension"
    }
}

