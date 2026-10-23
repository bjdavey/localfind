package com.localfind.index.audio

import com.localfind.core.model.FileKind
import com.localfind.core.chunking.AudioChunker
import androidx.core.net.toUri
import com.localfind.core.model.ImportedFile
import com.localfind.index.ChunkEmbeddingData
import com.localfind.core.model.INDEXING_CANCELLED_MESSAGE
import com.localfind.core.model.IndexResult
import com.localfind.index.Indexer
import com.localfind.core.format.SearchResultFormatter

/**
 * Audio (`.mp3`, `.m4a`, `.wav`) indexing pipeline for [Indexer].
 *
 * Execution flow (ARCHITECTURE.md §5):
 * 1. Decodes the file to 16 kHz mono PCM via [AudioDecoder].
 * 2. Splits it into fixed windows via [AudioChunker.windows].
 * 3. Embeds each window directly with the multimodal model ([com.localfind.embed.Embedder.embedAudio]);
 *    there is no speech-to-text step.
 * 4. Persists records in batches via [Indexer.indexChunkedItems] with `type="audio"`
 *    and `location` = window start time in milliseconds (used to seek the player).
 */
fun Indexer.indexAudioFile(
    file: ImportedFile,
    isCancelled: () -> Boolean,
    onProgress: ((current: Int, total: Int) -> Unit)?
): IndexResult {
    if (!isAudioFile(file)) {
        return IndexResult(0, false, "Not a supported audio file")
    }
    if (isCancelled()) {
        return IndexResult(0, false, INDEXING_CANCELLED_MESSAGE)
    }

    val pcm = try {
        AudioDecoder.decodeToMono16k(context, file.uri.toUri(), isCancelled)
    } catch (e: Exception) {
        return IndexResult(0, false, "Audio decode failed: ${e.message}")
    }

    if (isCancelled()) {
        return IndexResult(0, false, INDEXING_CANCELLED_MESSAGE)
    }

    val windows = AudioChunker.windows(
        totalSamples = pcm.size,
        windowMs = settings.audioWindowSeconds * 1000L
    )

    return indexChunkedItems(
        file = file,
        kind = FileKind.AUDIO,
        items = windows,
        batchSize = 5,
        deleteOnEmpty = false,
        errorPrefix = "Audio embedding failed",
        isCancelled = isCancelled,
        onProgress = onProgress
    ) { window ->
        val embedding = embedder.embedAudio(pcm.copyOfRange(window.startSample, window.endSample))
        // Text label only: the audio itself stays in the linked file (no copy)
        val label = "${file.displayName} @ ${SearchResultFormatter.formatTimeMs(window.startMs)}"
        ChunkEmbeddingData(
            snippetText = label,
            location = window.startMs.toString(),
            embedding = embedding
        )
    }
}
