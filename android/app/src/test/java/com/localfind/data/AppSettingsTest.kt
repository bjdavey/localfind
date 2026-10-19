package com.localfind.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AppSettings] and [SettingsStore].
 *
 * Runs on standard JVM without heavy Android framework mocks or instrumentation.
 */
class AppSettingsTest {

    @Test
    fun defaultValues_matchRequirements() {
        val settings = AppSettings()

        assertEquals(false, settings.darkMode)
        // User explicitly requested Similarity Threshold default 0.6
        assertEquals(0.6f, settings.similarityThreshold, 0.0001f)
        assertEquals(10, settings.topK)
        assertEquals(500, settings.textChunkSize)
        assertEquals(100, settings.textChunkOverlap)
        assertEquals(10, settings.audioWindowSeconds)
        assertEquals(5, settings.videoWindowSeconds)
        assertEquals(2, settings.videoFramesPerWindow)
        assertEquals(512, settings.maxImageDimension)
        assertEquals(false, settings.videoIncludeAudio) // Vision-only default
        assertEquals(true, settings.videoSkipSimilarChunks)
        assertEquals(0.85f, settings.videoSimilarityThreshold, 0.0001f)
    }

    @Test
    fun sanitized_clampsOutOfBoundsValues() {
        val outOfBounds = AppSettings(
            darkMode = true,
            similarityThreshold = -0.5f,
            topK = 0,
            textChunkSize = 20,
            textChunkOverlap = 5000,
            audioWindowSeconds = -10,
            videoWindowSeconds = 120,
            videoFramesPerWindow = 0,
            maxImageDimension = 50,
            videoIncludeAudio = true,
            videoSkipSimilarChunks = false,
            videoSimilarityThreshold = 0.2f
        )

        val clean = outOfBounds.sanitized()

        assertEquals(true, clean.darkMode)
        assertEquals(0.0f, clean.similarityThreshold, 0.0001f)
        assertEquals(1, clean.topK)
        assertEquals(100, clean.textChunkSize)
        // Overlap clamped to at most half of textChunkSize
        assertTrue(clean.textChunkOverlap <= clean.textChunkSize / 2)
        assertEquals(1, clean.audioWindowSeconds)
        assertEquals(60, clean.videoWindowSeconds)
        assertEquals(1, clean.videoFramesPerWindow)
        assertEquals(128, clean.maxImageDimension)
        assertEquals(true, clean.videoIncludeAudio)
        assertEquals(false, clean.videoSkipSimilarChunks)
        assertEquals(0.5f, clean.videoSimilarityThreshold, 0.0001f)
    }

    @Test
    fun settingsStore_saveLoadAndReset() {
        val fakePrefs = FakeSharedPreferences()
        val store = SettingsStore(fakePrefs)

        // 1. Initial load should yield defaults
        val initial = store.load()
        assertEquals(false, initial.darkMode)
        assertEquals(DEFAULT_SIMILARITY_THRESHOLD, initial.similarityThreshold, 0.0001f)
        assertEquals(DEFAULT_TOP_K, initial.topK)
        assertEquals(false, initial.videoIncludeAudio)
        assertEquals(true, initial.videoSkipSimilarChunks)
        assertEquals(0.85f, initial.videoSimilarityThreshold, 0.0001f)

        // 2. Save modified settings
        val modified = AppSettings(
            darkMode = true,
            similarityThreshold = 0.75f,
            topK = 25,
            textChunkSize = 800,
            textChunkOverlap = 150,
            audioWindowSeconds = 15,
            videoWindowSeconds = 8,
            videoFramesPerWindow = 3,
            maxImageDimension = 768,
            videoIncludeAudio = true,
            videoSkipSimilarChunks = false,
            videoSimilarityThreshold = 0.9f
        )
        store.save(modified)

        val reloaded = store.load()
        assertEquals(true, reloaded.darkMode)
        assertEquals(0.75f, reloaded.similarityThreshold, 0.0001f)
        assertEquals(25, reloaded.topK)
        assertEquals(800, reloaded.textChunkSize)
        assertEquals(150, reloaded.textChunkOverlap)
        assertEquals(15, reloaded.audioWindowSeconds)
        assertEquals(8, reloaded.videoWindowSeconds)
        assertEquals(3, reloaded.videoFramesPerWindow)
        assertEquals(768, reloaded.maxImageDimension)
        assertEquals(true, reloaded.videoIncludeAudio)
        assertEquals(false, reloaded.videoSkipSimilarChunks)
        assertEquals(0.9f, reloaded.videoSimilarityThreshold, 0.0001f)

        // 3. Reset settings to default
        val reset = store.reset()
        assertEquals(false, reset.darkMode)
        assertEquals(DEFAULT_SIMILARITY_THRESHOLD, reset.similarityThreshold, 0.0001f)
        assertEquals(DEFAULT_TOP_K, reset.topK)
        assertEquals(DEFAULT_TEXT_CHUNK_SIZE, reset.textChunkSize)
        assertEquals(false, reset.videoIncludeAudio)
        assertEquals(true, reset.videoSkipSimilarChunks)
        assertEquals(0.85f, reset.videoSimilarityThreshold, 0.0001f)

        val afterReset = store.load()
        assertEquals(false, afterReset.darkMode)
        assertEquals(DEFAULT_SIMILARITY_THRESHOLD, afterReset.similarityThreshold, 0.0001f)
    }

    @Test
    fun settingsStore_followsSystemThemeDefaultWhenUnset() {
        val fakePrefs = FakeSharedPreferences()
        val store = SettingsStore(fakePrefs)

        // When phone is in dark theme, uncustomized store should load dark mode = true
        assertEquals(false, store.hasCustomTheme())
        val systemDarkSettings = store.load(defaultDarkMode = true)
        assertEquals(true, systemDarkSettings.darkMode)

        // When phone is in light theme, uncustomized store should load dark mode = false
        val systemLightSettings = store.load(defaultDarkMode = false)
        assertEquals(false, systemLightSettings.darkMode)

        // Explicitly saving custom theme
        store.save(systemLightSettings.copy(darkMode = true))
        assertEquals(true, store.hasCustomTheme())
        // Even if system theme is light, saved preference wins
        assertEquals(true, store.load(defaultDarkMode = false).darkMode)

        // Resetting restores fallback to phone system setting
        val resetSettings = store.reset(defaultDarkMode = true)
        assertEquals(false, store.hasCustomTheme())
        assertEquals(true, resetSettings.darkMode)
    }

    @Test
    fun settingsStore_customPreferencesPersistenceAndReset() {
        val fakePrefs = FakeSharedPreferences()
        val store = SettingsStore(fakePrefs)

        // 1. Initial state defaults
        val initial = store.load()
        assertEquals(0.6f, initial.similarityThreshold, 0.0001f)
        assertEquals(10, initial.topK)

        // 2. Save custom settings
        val custom = initial.copy(similarityThreshold = 0.8f, topK = 25, darkMode = true)
        store.save(custom)

        val loaded = store.load()
        assertEquals(0.8f, loaded.similarityThreshold, 0.0001f)
        assertEquals(25, loaded.topK)
        assertEquals(true, loaded.darkMode)

        // 3. Resetting user preferences resets to defaults
        val reset = store.reset(defaultDarkMode = false)
        assertEquals(0.6f, reset.similarityThreshold, 0.0001f)
        assertEquals(10, reset.topK)
        assertEquals(false, reset.darkMode)
    }

    /**
     * Minimal in-memory implementation of [SharedPreferences] for JVM unit testing.
     */
    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { temp[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { temp[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { temp[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { temp[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { temp[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { temp[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { temp[key!!] = null }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                temp.forEach { (k, v) ->
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}

