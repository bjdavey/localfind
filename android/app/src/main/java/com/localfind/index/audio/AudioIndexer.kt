package com.localfind.index.audio

import androidx.core.net.toUri
import com.localfind.data.ImportedFile
import com.localfind.index.ChunkEmbeddingData
import com.localfind.index.IndexResult
import com.localfind.index.Indexer
import com.localfind.ui.search.SearchResultFormatter

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
    isCancelled: () -> Boolean = { false },
    onProgress: ((current: Int, total: Int) -> Unit)? = null
): IndexResult {
    if (!isAudioFile(file)) {
        return IndexResult(file.uri, 0, false, "Not a supported audio file")
    }
    if (isCancelled()) {
        return IndexResult(file.uri, 0, false, "Indexing cancelled")
    }

    val pcm = try {
        AudioDecoder.decodeToMono16k(context, file.uri.toUri(), isCancelled)
    } catch (e: Exception) {
        return IndexResult(file.uri, 0, false, "Audio decode failed: ${e.message}")
    }

    if (isCancelled()) {
        return IndexResult(file.uri, 0, false, "Indexing cancelled")
    }

    val windows = AudioChunker.windows(
        totalSamples = pcm.size,
        windowMs = settings.audioWindowSeconds * 1000L
    )

    return indexChunkedItems(
        file = file,
        type = "audio",
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
