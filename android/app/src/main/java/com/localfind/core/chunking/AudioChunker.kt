package com.localfind.core.chunking

/** Sample rate expected by EmbeddingGemma's mel-spectrogram frontend (GOOGLE_AI_EDGE_GALLERY.md §6.2). */
const val AUDIO_SAMPLE_RATE = 16_000

/** A trailing window shorter than this is dropped (unless it is the only window): too little signal. */
const val MIN_AUDIO_WINDOW_MS = 1_000L

/**
 * One fixed time window of a 16 kHz mono PCM stream.
 *
 * @property startMs Window start time in milliseconds (the chunk's `location`).
 * @property startSample Index of the first sample (inclusive).
 * @property endSample Index of the last sample (exclusive).
 */
data class AudioWindow(val startMs: Long, val startSample: Int, val endSample: Int)

/**
 * Pure helpers for turning decoded audio into fixed-time-window chunks.
 * No Android classes are used here, so everything is covered by plain JVM unit tests.
 */
object AudioChunker {

    /**
     * Splits [totalSamples] (16 kHz mono) into consecutive non-overlapping windows of [windowMs].
     * A short trailing window (< [MIN_AUDIO_WINDOW_MS]) is dropped unless it is the only window.
     */
    fun windows(
        totalSamples: Int,
        windowMs: Long
    ): List<AudioWindow> {
        if (totalSamples <= 0) return emptyList()
        val windowSamples = (AUDIO_SAMPLE_RATE * windowMs / 1000).toInt().coerceAtLeast(1)
        val minSamples = (AUDIO_SAMPLE_RATE * MIN_AUDIO_WINDOW_MS / 1000).toInt()
        val result = mutableListOf<AudioWindow>()
        var start = 0
        while (start < totalSamples) {
            val end = minOf(start + windowSamples, totalSamples)
            val isShortTail = (end - start) < minSamples && result.isNotEmpty()
            if (!isShortTail) {
                result.add(AudioWindow(start * 1000L / AUDIO_SAMPLE_RATE, start, end))
            }
            start = end
        }
        return result
    }

    /**
     * Linear-interpolation resampler from [srcRate] to [AUDIO_SAMPLE_RATE].
     * Good enough for embedding input; avoids pulling in a DSP library.
     */
    fun resample(input: ShortArray, srcRate: Int): ShortArray {
        if (srcRate == AUDIO_SAMPLE_RATE || input.isEmpty()) return input
        val outSize = (input.size.toLong() * AUDIO_SAMPLE_RATE / srcRate).toInt()
        val ratio = srcRate.toDouble() / AUDIO_SAMPLE_RATE
        return ShortArray(outSize) { i ->
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = pos - i0
            (input[i0] * (1 - frac) + input[i1] * frac).toInt().toShort()
        }
    }
}
