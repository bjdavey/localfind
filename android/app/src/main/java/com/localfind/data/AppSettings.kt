package com.localfind.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Default dark mode state (false = light mode, true = dark mode). */
const val DEFAULT_DARK_MODE = false

/** Default theme mode (SYSTEM = follow system setting). */
val DEFAULT_THEME_MODE = ThemeMode.SYSTEM

/**
 * User preference for application visual theme.
 *
 * - [SYSTEM]: Automatically follow host device's dark/light system setting.
 * - [LIGHT]: Force light theme across all screens.
 * - [DARK]: Force dark theme across all screens.
 */
enum class ThemeMode(val title: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark")
}

/**
 * Hardware accelerator choice for multimodal vision indexing (images and video keyframes).
 *
 * - [NPU]: Default on supported chipsets (Qualcomm Hexagon HTP, MediaTek NeuroPilot, Google Tensor).
 *   Runs on dedicated AI tensor cores without switching on screen/battery state so all embeddings
 *   remain in a consistent vector distribution. Automatically falls back to [SMART] on unsupported devices.
 * - [SMART]: Dynamically routes between GPU and CPU based on device state.
 *   Uses GPU when the screen is OFF and not in Battery Saver mode for maximum speed.
 *   Switches to CPU when the screen is ON or Battery Saver is ON to keep the UI smooth and cool.
 * - [GPU]: Always uses mobile GPU compute shaders for maximum indexing speed.
 * - [CPU]: Always uses CPU (XNNPack) to keep the device cool, save power, and avoid UI frame contention.
 */
enum class HardwareAccelerator(val title: String) {
    NPU("NPU (Neural Processing Unit)"),
    SMART("Smart (Auto)"),
    GPU("GPU (Faster)"),
    CPU("CPU (Smoother UI)")
}

/** Default hardware accelerator for vision indexing (NPU on supported devices, falling back to Smart). */
val DEFAULT_HARDWARE_ACCELERATOR = HardwareAccelerator.NPU

/**
 * Resolves the effective concrete hardware accelerator ([HardwareAccelerator.NPU],
 * [HardwareAccelerator.GPU], or [HardwareAccelerator.CPU]).
 *
 * Policy:
 * - If [HardwareAccelerator.NPU] is chosen and the device supports NPU ([com.localfind.embed.SocDetector.isNpuRuntimeAvailable]),
 *   returns [HardwareAccelerator.NPU] permanently without switching on screen/battery state.
 * - If [HardwareAccelerator.NPU] is chosen on an unsupported device, falls back to [HardwareAccelerator.SMART].
 * - If [HardwareAccelerator.GPU] or [HardwareAccelerator.CPU] is explicitly chosen, returns that choice.
 * - If [HardwareAccelerator.SMART], uses GPU only when the screen is off and Battery Saver is disabled.
 */
fun HardwareAccelerator.resolveEffective(context: Context): HardwareAccelerator {
    if (this == HardwareAccelerator.NPU) {
        return if (com.localfind.embed.SocDetector.isNpuRuntimeAvailable(context)) {
            HardwareAccelerator.NPU
        } else {
            HardwareAccelerator.SMART.resolveEffective(context)
        }
    }
    if (this != HardwareAccelerator.SMART) return this
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    val isScreenOn = powerManager?.isInteractive == true
    val isPowerSaveMode = powerManager?.isPowerSaveMode == true
    return if (!isScreenOn && !isPowerSaveMode) {
        HardwareAccelerator.GPU
    } else {
        HardwareAccelerator.CPU
    }
}

/** Default minimum cosine similarity score required for search results (tuned for NPU embeddings). */
const val DEFAULT_SIMILARITY_THRESHOLD = 0.3f

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

/** Default whether folder import is enabled (hidden by default unless enabled from advanced settings). */
const val DEFAULT_ENABLE_FOLDER_IMPORT = false

/**
 * Configuration model holding all tunable variables for searching and indexing in LocalFind.
 *
 * All settings run 100% offline on-device and are persisted via Android [SharedPreferences].
 *
 * @property themeMode Visual theme mode preference ([ThemeMode.SYSTEM], [ThemeMode.LIGHT], or [ThemeMode.DARK]).
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
 * @property enableFolderImport Whether importing entire directories is exposed in the gallery UI.
 * @property accelerator Preferred hardware accelerator for vision indexing ([HardwareAccelerator.NPU], [HardwareAccelerator.GPU], [HardwareAccelerator.CPU] or the dynamic [HardwareAccelerator.SMART]).
 */
data class AppSettings(
    val themeMode: ThemeMode = DEFAULT_THEME_MODE,
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
    val videoSimilarityThreshold: Float = DEFAULT_VIDEO_SIMILARITY_THRESHOLD,
    val enableFolderImport: Boolean = DEFAULT_ENABLE_FOLDER_IMPORT,
    val accelerator: HardwareAccelerator = DEFAULT_HARDWARE_ACCELERATOR
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
            maxImageDimension = maxImageDimension.coerceIn(128, 2048),
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
     * Loads the current persisted settings, applying defaults for any unset keys and
     * validating bounds via [AppSettings.sanitized].
     *
     * @param defaultDarkMode Fallback dark mode value when not explicitly set by user. Defaults
     *                        to the host device's system dark mode setting.
     */
    fun load(defaultDarkMode: Boolean = false): AppSettings {
        val themeMode = if (prefs.contains(KEY_THEME_MODE)) {
            val modeName = prefs.getString(KEY_THEME_MODE, null)
            try {
                if (modeName != null) ThemeMode.valueOf(modeName) else ThemeMode.SYSTEM
            } catch (_: Exception) {
                ThemeMode.SYSTEM
            }
        } else if (prefs.contains(KEY_DARK_MODE)) {
            if (prefs.getBoolean(KEY_DARK_MODE, defaultDarkMode)) ThemeMode.DARK else ThemeMode.LIGHT
        } else {
            ThemeMode.SYSTEM
        }

        val isDarkMode = when (themeMode) {
            ThemeMode.SYSTEM -> defaultDarkMode
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }

        val accelerator = if (prefs.contains(KEY_HARDWARE_ACCELERATOR)) {
            val name = prefs.getString(KEY_HARDWARE_ACCELERATOR, null)
            try {
                if (name != null) HardwareAccelerator.valueOf(name) else DEFAULT_HARDWARE_ACCELERATOR
            } catch (_: Exception) {
                DEFAULT_HARDWARE_ACCELERATOR
            }
        } else {
            DEFAULT_HARDWARE_ACCELERATOR
        }

        return AppSettings(
            themeMode = themeMode,
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
            videoSimilarityThreshold = prefs.getFloat(KEY_VIDEO_SIMILARITY_THRESHOLD, DEFAULT_VIDEO_SIMILARITY_THRESHOLD),
            enableFolderImport = prefs.getBoolean(KEY_ENABLE_FOLDER_IMPORT, DEFAULT_ENABLE_FOLDER_IMPORT),
            accelerator = accelerator
        ).sanitized()
    }

    /**
     * Persists [settings] to [SharedPreferences] after clamping values to safe bounds.
     */
    fun save(settings: AppSettings) {
        val valid = settings.sanitized()
        // If darkMode boolean was explicitly changed without touching themeMode (e.g. in legacy tests),
        // resolve themeMode accordingly.
        val resolvedThemeMode = when {
            valid.themeMode == ThemeMode.SYSTEM && !prefs.contains(KEY_THEME_MODE) && valid.darkMode -> ThemeMode.DARK
            else -> valid.themeMode
        }
        val isDark = when (resolvedThemeMode) {
            ThemeMode.SYSTEM -> valid.darkMode
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
        prefs.edit {
            putString(KEY_THEME_MODE, resolvedThemeMode.name)
            putBoolean(KEY_DARK_MODE, isDark)
            putFloat(KEY_SIMILARITY_THRESHOLD, valid.similarityThreshold)
            putInt(KEY_TOP_K, valid.topK)
            putInt(KEY_TEXT_CHUNK_SIZE, valid.textChunkSize)
            putInt(KEY_TEXT_CHUNK_OVERLAP, valid.textChunkOverlap)
            putInt(KEY_AUDIO_WINDOW_SECONDS, valid.audioWindowSeconds)
            putInt(KEY_VIDEO_WINDOW_SECONDS, valid.videoWindowSeconds)
            putInt(KEY_VIDEO_FRAMES_PER_WINDOW, valid.videoFramesPerWindow)
            putInt(KEY_MAX_IMAGE_DIMENSION, valid.maxImageDimension)
            putBoolean(KEY_VIDEO_INCLUDE_AUDIO, valid.videoIncludeAudio)
            putBoolean(KEY_VIDEO_SKIP_SIMILAR, valid.videoSkipSimilarChunks)
            putFloat(KEY_VIDEO_SIMILARITY_THRESHOLD, valid.videoSimilarityThreshold)
            putBoolean(KEY_ENABLE_FOLDER_IMPORT, valid.enableFolderImport)
            putString(KEY_HARDWARE_ACCELERATOR, valid.accelerator.name)
        }
    }

    /**
     * Clears all persisted user settings and resets to default values.
     *
     * @param defaultDarkMode Default dark mode to apply, usually following the phone's system theme.
     * @return Fresh instance of [AppSettings] initialized with defaults.
     */
    fun reset(defaultDarkMode: Boolean = false): AppSettings {
        prefs.edit { clear() }
        return load(defaultDarkMode)
    }

    companion object {
        const val PREFS_NAME = "localfind_settings"
        const val KEY_THEME_MODE = "theme_mode"
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
        const val KEY_ENABLE_FOLDER_IMPORT = "enable_folder_import"
        const val KEY_HARDWARE_ACCELERATOR = "hardware_accelerator"
    }
}
