package com.localfind.index

import com.localfind.index.video.MAX_INPUT_TOKENS
import com.localfind.index.video.VideoChunker
import com.localfind.index.video.VideoWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [VideoChunker]: sliding windows, frame times, token budget and audio ranges. */
class VideoChunkerTest {

    @Test
    fun emptyVideo_hasNoWindows() {
        assertTrue(VideoChunker.windows(0).isEmpty())
    }

    @Test
    fun shortVideo_isSingleWindow() {
        assertEquals(listOf(VideoWindow(0, 3_000)), VideoChunker.windows(3_000))
    }

    @Test
    fun windowsOverlapAndEndAtDuration() {
        // 5 s windows every 4 s over 10 s: [0,5] [4,9] [8,10]
        val w = VideoChunker.windows(10_000)
        assertEquals(listOf(0L, 4_000L, 8_000L), w.map { it.startMs })
        assertEquals(10_000L, w.last().endMs)
    }

    @Test
    fun exactFit_doesNotAddRedundantWindow() {
        // [0,5] already reaches the end of a 5 s video
        assertEquals(1, VideoChunker.windows(5_000).size)
    }

    @Test
    fun tinyTail_isSkipped() {
        // 8.5 s: [0,5] [4,8.5] -> next start 8 s would be a 0.5 s tail, but [4,8.5] already reached the end
        val w = VideoChunker.windows(8_500)
        assertEquals(2, w.size)
        // 9.2 s: [0,5] [4,9] then [8,9.2] is 1.2 s long and kept
        assertEquals(3, VideoChunker.windows(9_200).size)
    }

    @Test
    fun frameTimes_areMidpointsOfEqualSlices() {
        assertEquals(listOf(1_250L, 3_750L), VideoChunker.frameTimes(VideoWindow(0, 5_000), 2))
        assertEquals(listOf(5_250L, 7_750L), VideoChunker.frameTimes(VideoWindow(4_000, 9_000), 2))
    }

    @Test
    fun tokenEstimate_defaultWindowFitsBudget() {
        // 5 s audio (31) + 2 frames (140) + markers (16)
        val tokens = VideoChunker.estimateTokens(5_000, 2, true)
        assertEquals(187, tokens)
        assertTrue(tokens < MAX_INPUT_TOKENS)
    }

    @Test
    fun tokenEstimate_withoutAudioIsFramesOnly() {
        assertEquals(156, VideoChunker.estimateTokens(5_000, 2, false))
    }

    @Test
    fun sampleRange_isClampedToAvailableAudio() {
        val r = VideoChunker.sampleRange(4_000, 9_000, 16_000 * 6)
        assertEquals(64_000, r.first)
        assertEquals(96_000, r.last + 1)
        assertTrue(VideoChunker.sampleRange(0, 1_000, 0).isEmpty())
    }

    @Test
    fun signatureSimilarity_identicalSignaturesReturnOne() {
        val sigA = IntArray(256) { 128 }
        val sigB = IntArray(256) { 128 }
        assertEquals(1.0f, VideoChunker.signatureSimilarity(sigA, sigB), 0.0001f)
    }

    @Test
    fun signatureSimilarity_oppositeSignaturesReturnZero() {
        val sigWhite = IntArray(256) { 255 }
        val sigBlack = IntArray(256) { 0 }
        assertEquals(0.0f, VideoChunker.signatureSimilarity(sigWhite, sigBlack), 0.0001f)
    }

    @Test
    fun signatureSimilarity_slightDifferencesYieldHighSimilarity() {
        val sigA = IntArray(256) { 100 }
        val sigB = IntArray(256) { if (it < 20) 120 else 100 } // minor variance in 20/256 pixels
        val sim = VideoChunker.signatureSimilarity(sigA, sigB)
        assertTrue(sim > 0.95f)
    }

    @Test
    fun signatureSimilarity_mismatchedOrEmptySignaturesReturnZero() {
        assertEquals(0.0f, VideoChunker.signatureSimilarity(IntArray(0), IntArray(0)), 0.0001f)
        assertEquals(0.0f, VideoChunker.signatureSimilarity(IntArray(256), IntArray(100)), 0.0001f)
    }
}

