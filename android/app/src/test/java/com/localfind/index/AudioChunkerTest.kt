package com.localfind.index

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [AudioChunker]: window splitting, mono downmix and resampling. */
class AudioChunkerTest {

    private val rate = AUDIO_SAMPLE_RATE

    @Test
    fun audioDetection_byExtensionOrMime() {
        assertTrue(Indexer.isAudioNameOrMime("song.MP3", null))
        assertTrue(Indexer.isAudioNameOrMime("voice.m4a", null))
        assertTrue(Indexer.isAudioNameOrMime("noext", "audio/wav"))
        assertTrue(!Indexer.isAudioNameOrMime("notes.txt", "text/plain"))
    }

    @Test
    fun emptyAudio_hasNoWindows() {
        assertTrue(AudioChunker.windows(0).isEmpty())
    }

    @Test
    fun shortAudio_isSingleWindow() {
        // 0.5 s is below the tail minimum, but it is the only window so it is kept
        val windows = AudioChunker.windows(rate / 2)
        assertEquals(listOf(AudioWindow(0, 0, rate / 2)), windows)
    }

    @Test
    fun longAudio_splitsIntoTenSecondWindowsWithStartTimes() {
        // 25 s -> windows at 0 s, 10 s, 20 s (the last one is 5 s long)
        val windows = AudioChunker.windows(25 * rate)
        assertEquals(listOf(0L, 10_000L, 20_000L), windows.map { it.startMs })
        assertEquals(25 * rate, windows.last().endSample)
    }

    @Test
    fun tinyTail_isDropped() {
        // 10.5 s -> the 0.5 s tail is dropped
        val windows = AudioChunker.windows(10 * rate + rate / 2)
        assertEquals(1, windows.size)
        assertEquals(10 * rate, windows[0].endSample)
    }

    @Test
    fun downmix_averagesChannels() {
        val stereo = shortArrayOf(100, 300, -50, 50)
        assertArrayEquals(shortArrayOf(200, 0), AudioChunker.downmixToMono(stereo, 2))
    }

    @Test
    fun resample_changesLengthByRateRatio() {
        val input = ShortArray(48_000) { 1000 }
        val out = AudioChunker.resample(input, 48_000, 16_000)
        assertEquals(16_000, out.size)
        assertEquals(1000, out[100].toInt())
    }

    @Test
    fun resample_sameRateIsNoOp() {
        val input = shortArrayOf(1, 2, 3)
        assertArrayEquals(input, AudioChunker.resample(input, rate, rate))
    }
}
