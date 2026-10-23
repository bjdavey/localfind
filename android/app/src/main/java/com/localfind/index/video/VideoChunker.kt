package com.localfind.index.video

import com.localfind.index.audio.AUDIO_SAMPLE_RATE

/** Model input limit in tokens (GOOGLE_AI_EDGE_GALLERY.md §6.2). Longer windows fall back to frames only. */
const val MAX_INPUT_TOKENS = 1024

/** A window that is shorter than this (a leftover tail) is skipped. */
private const val MIN_VIDEO_WINDOW_MS = 1_000L

/** One sliding time window of a video. [startMs] is the chunk's `location`. */
data class VideoWindow(val startMs: Long, val endMs: Long)

/**
 * Pure helpers for slicing a video into windows and budgeting model tokens.
 * No Android classes, so everything is covered by JVM unit tests.
 */
object VideoChunker {

    /**
     * Slides a [windowMs] window over the video in [stepMs] steps.
     * Stops after the window that reaches the end, and skips a tiny leftover tail.
     */
    fun windows(
        durationMs: Long,
        windowMs: Long,
        stepMs: Long
    ): List<VideoWindow> {
        if (durationMs <= 0) return emptyList()
        val result = mutableListOf<VideoWindow>()
        var start = 0L
        while (start < durationMs) {
            val end = minOf(start + windowMs, durationMs)
            if (result.isEmpty() || end - start >= MIN_VIDEO_WINDOW_MS) {
                result.add(VideoWindow(start, end))
            }
            if (end >= durationMs) break
            start += stepMs
        }
        return result
    }

    /** Timestamps of [count] frames, taken at the middle of [count] equal slices of the window. */
    fun frameTimes(window: VideoWindow, count: Int): List<Long> {
        val len = window.endMs - window.startMs
        return List(count) { i -> window.startMs + (2L * i + 1) * len / (2L * count) }
    }

    /**
     * Estimated model tokens for one window (Gallery numbers): audio ~6.25 tokens/s,
     * 70 tokens per frame (see DEFAULT_VISION_TOKENS_PER_IMAGE), 8 tokens per slice for time markers.
     */
    fun estimateTokens(windowMs: Long, frames: Int, withAudio: Boolean): Int {
        val audio = if (withAudio) (windowMs * 6.25 / 1000).toInt() else 0
        return audio + frames * 70 + frames * 8
    }

    /**
     * Sample range [from, to) of 16 kHz audio for the time range, clamped to [totalSamples].
     */
    fun sampleRange(startMs: Long, endMs: Long, totalSamples: Int): IntRange {
        val from = (startMs * AUDIO_SAMPLE_RATE / 1000).toInt().coerceIn(0, totalSamples)
        val to = (endMs * AUDIO_SAMPLE_RATE / 1000).toInt().coerceIn(from, totalSamples)
        return from until to
    }

    /**
     * Computes the normalized visual similarity (0.0 .. 1.0) between two 256-element
     * luminance signatures using Mean Absolute Difference (MAD).
     *
     * Returns 1.0f for identical visual signatures, and drops towards 0.0f as
     * frame content diverges. Pure JVM function with no Android dependencies.
     */
    fun signatureSimilarity(sig1: IntArray, sig2: IntArray): Float {
        if (sig1.size != sig2.size || sig1.isEmpty()) return 0f
        var diff = 0
        for (i in sig1.indices) {
            diff += kotlin.math.abs(sig1[i] - sig2[i])
        }
        return 1.0f - (diff / (sig1.size * 255.0f))
    }
}
