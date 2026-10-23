package com.localfind.data

import com.localfind.core.settings.AppSettings
import com.localfind.core.settings.DEFAULT_ENABLE_FOLDER_IMPORT
import com.localfind.core.settings.DEFAULT_VIDEO_SIMILARITY_THRESHOLD
import com.localfind.core.settings.DEFAULT_VIDEO_SKIP_SIMILAR
import com.localfind.core.settings.DEFAULT_VIDEO_INCLUDE_AUDIO
import com.localfind.core.settings.DEFAULT_MAX_IMAGE_DIMENSION
import com.localfind.core.settings.DEFAULT_VIDEO_FRAMES_PER_WINDOW
import com.localfind.core.settings.DEFAULT_VIDEO_WINDOW_SECONDS
import com.localfind.core.settings.DEFAULT_AUDIO_WINDOW_SECONDS
import com.localfind.core.settings.DEFAULT_TEXT_CHUNK_OVERLAP
import com.localfind.core.settings.DEFAULT_TEXT_CHUNK_SIZE
import com.localfind.core.settings.DEFAULT_TOP_K
import com.localfind.core.settings.DEFAULT_SIMILARITY_THRESHOLD
import com.localfind.core.settings.DEFAULT_HARDWARE_ACCELERATOR
import com.localfind.core.settings.HardwareAccelerator
import com.localfind.core.settings.ThemeMode
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

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
