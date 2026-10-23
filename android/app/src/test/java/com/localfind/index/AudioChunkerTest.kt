package com.localfind.index

import com.localfind.data.DEFAULT_AUDIO_WINDOW_SECONDS
import com.localfind.index.audio.AUDIO_SAMPLE_RATE
import com.localfind.index.audio.AudioChunker
import com.localfind.index.audio.AudioWindow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [AudioChunker]: window splitting and resampling. */
class AudioChunkerTest {

    private val rate = AUDIO_SAMPLE_RATE

    // Same value as the AppSettings default (10 s) used by production indexing
    private val windowMs = DEFAULT_AUDIO_WINDOW_SECONDS * 1000L

    @Test
    fun audioDetection_byExtensionOrMime() {
        assertTrue(Indexer.isAudioNameOrMime("song.MP3", null))
        assertTrue(Indexer.isAudioNameOrMime("voice.m4a", null))
        assertTrue(Indexer.isAudioNameOrMime("noext", "audio/wav"))
        assertTrue(!Indexer.isAudioNameOrMime("notes.txt", "text/plain"))
    }

    @Test
    fun emptyAudio_hasNoWindows() {
        assertTrue(AudioChunker.windows(0, windowMs).isEmpty())
    }

    @Test
    fun shortAudio_isSingleWindow() {
        // 0.5 s is below the tail minimum, but it is the only window so it is kept
        val windows = AudioChunker.windows(rate / 2, windowMs)
        assertEquals(listOf(AudioWindow(0, 0, rate / 2)), windows)
    }

    @Test
    fun longAudio_splitsIntoTenSecondWindowsWithStartTimes() {
        // 25 s -> windows at 0 s, 10 s, 20 s (the last one is 5 s long)
        val windows = AudioChunker.windows(25 * rate, windowMs)
        assertEquals(listOf(0L, 10_000L, 20_000L), windows.map { it.startMs })
        assertEquals(25 * rate, windows.last().endSample)
    }

    @Test
    fun tinyTail_isDropped() {
        // 10.5 s -> the 0.5 s tail is dropped
        val windows = AudioChunker.windows(10 * rate + rate / 2, windowMs)
        assertEquals(1, windows.size)
        assertEquals(10 * rate, windows[0].endSample)
    }

    @Test
    fun resample_changesLengthByRateRatio() {
        val input = ShortArray(48_000) { 1000 }
        val out = AudioChunker.resample(input, 48_000)
        assertEquals(16_000, out.size)
        assertEquals(1000, out[100].toInt())
    }

    @Test
    fun resample_sameRateIsNoOp() {
        val input = shortArrayOf(1, 2, 3)
        assertArrayEquals(input, AudioChunker.resample(input, rate))
    }
}
