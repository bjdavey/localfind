package com.localfind.index.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes the first audio track of a file (mp3, m4a, wav, ...) into 16 kHz mono signed 16-bit PCM,
 * the format EmbeddingGemma's audio frontend expects.
 *
 * Uses the Android framework only ([MediaExtractor] + [MediaCodec]), so no extra dependency.
 * The whole file is decoded into memory (~1.9 MB per minute), which is fine for the MVP.
 * Supports cooperative cancellation via [isCancelled] to abort decoding loops immediately.
 */
object AudioDecoder {

    private const val TIMEOUT_US = 10_000L

    /**
     * @param isCancelled Cooperative cancellation probe checked inside the decode loop.
     *                    If it returns true, decoding stops immediately and returns empty PCM.
     * @return 16 kHz mono PCM samples.
     * @throws IllegalArgumentException if the file has no audio track.
     */
    fun decodeToMono16k(
        context: Context,
        uri: Uri,
        isCancelled: () -> Boolean = { false }
    ): ShortArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio track found")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val pcmChunks = mutableListOf<ShortArray>()
            var totalShorts = 0

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                // Check cooperative cancellation before processing next chunk
                if (isCancelled()) {
                    pcmChunks.clear()
                    return ShortArray(0)
                }
                // Feed compressed input
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                // Drain decoded PCM
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        val out = codec.getOutputBuffer(outIdx)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val shorts = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val count = shorts.remaining()
                        if (count > 0) {
                            // Downmix multi-channel audio directly per output buffer so peak heap memory
                            // stays bounded to 1x mono instead of 2x-6x multi-channel PCM.
                            val monoChunk = if (channels <= 1) {
                                ShortArray(count).also { shorts.get(it) }
                            } else {
                                val frames = count / channels
                                ShortArray(frames) {
                                    var sum = 0
                                    for (c in 0 until channels) {
                                        sum += shorts.get().toInt()
                                    }
                                    (sum / channels).toShort()
                                }
                            }
                            if (monoChunk.isNotEmpty()) {
                                pcmChunks.add(monoChunk)
                                totalShorts += monoChunk.size
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The decoder reports the real PCM layout here
                        val outFormat = codec.outputFormat
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            }

            val mono = ShortArray(totalShorts)
            var offset = 0
            for (chunk in pcmChunks) {
                chunk.copyInto(mono, destinationOffset = offset)
                offset += chunk.size
            }
            pcmChunks.clear()

            return AudioChunker.resample(mono, sampleRate)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            extractor.release()
        }
    }
}
