package com.localfind.data

import android.content.Context
import android.content.SharedPreferences

/** Default dark mode state (false = light mode, true = dark mode). */
const val DEFAULT_DARK_MODE = false

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

/** Default whether to decode and embed audio tracks in video files (false = vision-only fastest). */
const val DEFAULT_VIDEO_INCLUDE_AUDIO = false

/** Default whether to skip visually redundant consecutive video windows. */
const val DEFAULT_VIDEO_SKIP_SIMILAR = true

/** Default visual similarity threshold (0.0 .. 1.0) above which consecutive scenes are skipped. */
const val DEFAULT_VIDEO_SIMILARITY_THRESHOLD = 0.85f

/**
 * Configuration model holding all tunable variables for searching and indexing in LocalFind.
 *
 * All settings run 100% offline on-device and are persisted via Android [SharedPreferences].
 *
 * @property darkMode Whether dark theme is enabled across the application.
 * @property similarityThreshold Minimum cosine similarity cutoff score (0.0 .. 1.0). Matches below
 *                               this threshold are filtered out from search results.
 * @property topK Maximum number of nearest-neighbor results retrieved per search query.
 * @property textChunkSize Preferred character length per text/markdown chunk.
 * @property textChunkOverlap Sliding overlap characters between consecutive text chunks.
 * @property audioWindowSeconds Duration of each audio slice embedded by the multimodal model.
 * @property videoWindowSeconds Duration of each video window sliding across the media.
 * @property videoFramesPerWindow Number of visual frames extracted and embedded per video window.
 * @property maxImageDimension Maximum dimension (width/height px) for subsampling image/video inputs.
 * @property videoIncludeAudio Whether to decode and embed audio tracks in videos alongside visual keyframes.
 * @property videoSkipSimilarChunks Whether to skip consecutive video windows with visually redundant content.
 * @property videoSimilarityThreshold Similarity cutoff (0.5 .. 1.0) for detecting redundant video windows.
 */
data class AppSettings(
    val darkMode: Boolean = DEFAULT_DARK_MODE,
    val similarityThreshold: Float = DEFAULT_SIMILARITY_THRESHOLD,
    val topK: Int = DEFAULT_TOP_K,
    val textChunkSize: Int = DEFAULT_TEXT_CHUNK_SIZE,
    val textChunkOverlap: Int = DEFAULT_TEXT_CHUNK_OVERLAP,
    val audioWindowSeconds: Int = DEFAULT_AUDIO_WINDOW_SECONDS,
    val videoWindowSeconds: Int = DEFAULT_VIDEO_WINDOW_SECONDS,
    val videoFramesPerWindow: Int = DEFAULT_VIDEO_FRAMES_PER_WINDOW,
    val maxImageDimension: Int = DEFAULT_MAX_IMAGE_DIMENSION,
    val videoIncludeAudio: Boolean = DEFAULT_VIDEO_INCLUDE_AUDIO,
    val videoSkipSimilarChunks: Boolean = DEFAULT_VIDEO_SKIP_SIMILAR,
    val videoSimilarityThreshold: Float = DEFAULT_VIDEO_SIMILARITY_THRESHOLD
) {
    /**
     * Returns a copy of [AppSettings] with all values clamped to valid and safe ranges.
     */
    fun sanitized(): AppSettings {
        val safeTextChunkSize = textChunkSize.coerceIn(100, 5000)
        return copy(
            darkMode = darkMode,
            similarityThreshold = similarityThreshold.coerceIn(0.0f, 1.0f),
            topK = topK.coerceIn(1, 100),
            textChunkSize = safeTextChunkSize,
            textChunkOverlap = textChunkOverlap.coerceIn(0, (safeTextChunkSize / 2).coerceAtLeast(0)),
            audioWindowSeconds = audioWindowSeconds.coerceIn(1, 60),
            videoWindowSeconds = videoWindowSeconds.coerceIn(1, 60),
            videoFramesPerWindow = videoFramesPerWindow.coerceIn(1, 10),
            maxImageDimension = maxImageDimension.coerceIn(128, 2048),
            videoIncludeAudio = videoIncludeAudio,
            videoSkipSimilarChunks = videoSkipSimilarChunks,
            videoSimilarityThreshold = videoSimilarityThreshold.coerceIn(0.5f, 1.0f)
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
     * Checks if the user has explicitly chosen an override for dark mode in settings.
     */
    fun hasCustomTheme(): Boolean = prefs.contains(KEY_DARK_MODE)

    /**
     * Loads the current persisted settings, applying defaults for any unset keys and
     * validating bounds via [AppSettings.sanitized].
     *
     * @param defaultDarkMode Fallback dark mode value when not explicitly set by user. Defaults
     *                        to the host device's system dark mode setting.
     */
    fun load(defaultDarkMode: Boolean = false): AppSettings {
        val isDarkMode = if (prefs.contains(KEY_DARK_MODE)) {
            prefs.getBoolean(KEY_DARK_MODE, defaultDarkMode)
        } else {
            defaultDarkMode
        }
        return AppSettings(
            darkMode = isDarkMode,
            similarityThreshold = prefs.getFloat(KEY_SIMILARITY_THRESHOLD, DEFAULT_SIMILARITY_THRESHOLD),
            topK = prefs.getInt(KEY_TOP_K, DEFAULT_TOP_K),
            textChunkSize = prefs.getInt(KEY_TEXT_CHUNK_SIZE, DEFAULT_TEXT_CHUNK_SIZE),
            textChunkOverlap = prefs.getInt(KEY_TEXT_CHUNK_OVERLAP, DEFAULT_TEXT_CHUNK_OVERLAP),
            audioWindowSeconds = prefs.getInt(KEY_AUDIO_WINDOW_SECONDS, DEFAULT_AUDIO_WINDOW_SECONDS),
            videoWindowSeconds = prefs.getInt(KEY_VIDEO_WINDOW_SECONDS, DEFAULT_VIDEO_WINDOW_SECONDS),
            videoFramesPerWindow = prefs.getInt(KEY_VIDEO_FRAMES_PER_WINDOW, DEFAULT_VIDEO_FRAMES_PER_WINDOW),
            maxImageDimension = prefs.getInt(KEY_MAX_IMAGE_DIMENSION, DEFAULT_MAX_IMAGE_DIMENSION),
            videoIncludeAudio = prefs.getBoolean(KEY_VIDEO_INCLUDE_AUDIO, DEFAULT_VIDEO_INCLUDE_AUDIO),
            videoSkipSimilarChunks = prefs.getBoolean(KEY_VIDEO_SKIP_SIMILAR, DEFAULT_VIDEO_SKIP_SIMILAR),
            videoSimilarityThreshold = prefs.getFloat(KEY_VIDEO_SIMILARITY_THRESHOLD, DEFAULT_VIDEO_SIMILARITY_THRESHOLD)
        ).sanitized()
    }

    /**
     * Persists [settings] to [SharedPreferences] after clamping values to safe bounds.
     */
    fun save(settings: AppSettings) {
        val valid = settings.sanitized()
        prefs.edit()
            .putBoolean(KEY_DARK_MODE, valid.darkMode)
            .putFloat(KEY_SIMILARITY_THRESHOLD, valid.similarityThreshold)
            .putInt(KEY_TOP_K, valid.topK)
            .putInt(KEY_TEXT_CHUNK_SIZE, valid.textChunkSize)
            .putInt(KEY_TEXT_CHUNK_OVERLAP, valid.textChunkOverlap)
            .putInt(KEY_AUDIO_WINDOW_SECONDS, valid.audioWindowSeconds)
            .putInt(KEY_VIDEO_WINDOW_SECONDS, valid.videoWindowSeconds)
            .putInt(KEY_VIDEO_FRAMES_PER_WINDOW, valid.videoFramesPerWindow)
            .putInt(KEY_MAX_IMAGE_DIMENSION, valid.maxImageDimension)
            .putBoolean(KEY_VIDEO_INCLUDE_AUDIO, valid.videoIncludeAudio)
            .putBoolean(KEY_VIDEO_SKIP_SIMILAR, valid.videoSkipSimilarChunks)
            .putFloat(KEY_VIDEO_SIMILARITY_THRESHOLD, valid.videoSimilarityThreshold)
            .apply()
    }

    /**
     * Clears all persisted user settings and resets to default values.
     *
     * @param defaultDarkMode Default dark mode to apply, usually following the phone's system theme.
     * @return Fresh instance of [AppSettings] initialized with defaults.
     */
    fun reset(defaultDarkMode: Boolean = false): AppSettings {
        prefs.edit().clear().apply()
        return load(defaultDarkMode)
    }

    companion object {
        const val PREFS_NAME = "localfind_settings"
        const val KEY_DARK_MODE = "dark_mode"
        const val KEY_SIMILARITY_THRESHOLD = "similarity_threshold"
        const val KEY_TOP_K = "top_k"
        const val KEY_TEXT_CHUNK_SIZE = "text_chunk_size"
        const val KEY_TEXT_CHUNK_OVERLAP = "text_chunk_overlap"
        const val KEY_AUDIO_WINDOW_SECONDS = "audio_window_seconds"
        const val KEY_VIDEO_WINDOW_SECONDS = "video_window_seconds"
        const val KEY_VIDEO_FRAMES_PER_WINDOW = "video_frames_per_window"
        const val KEY_MAX_IMAGE_DIMENSION = "max_image_dimension"
        const val KEY_VIDEO_INCLUDE_AUDIO = "video_include_audio"
        const val KEY_VIDEO_SKIP_SIMILAR = "video_skip_similar"
        const val KEY_VIDEO_SIMILARITY_THRESHOLD = "video_similarity_threshold"
    }
}

