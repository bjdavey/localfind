package com.localfind.index

import android.content.Context
import androidx.core.net.toUri
import com.google.mediapipe.tasks.core.TextPart
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import com.localfind.data.EmbeddingStore
import com.localfind.data.ImportedFile
import com.localfind.embed.Embedder
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Result report from indexing a file.
 *
 * @property fileUri Document URI identifying the indexed file.
 * @property chunksCount Number of chunks extracted, embedded, and saved.
 * @property isSuccess Whether indexing succeeded without errors.
 * @property errorMessage Descriptive error message if indexing failed.
 */
data class IndexResult(
    val fileUri: String,
    val chunksCount: Int,
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

/**
 * Orchestrates extracting chunks from imported files, embedding them with [Embedder],
 * and persisting the resulting vectors and metadata in [EmbeddingStore].
 *
 * Architecture and Design:
 * - Direct Content Access: Reads file content on-the-fly using the persistent SAF URI
 *   (ContentResolver.openInputStream) without copying the file into app storage.
 * - Idempotency: Before writing new chunks for a file, existing chunks associated with the
 *   file's URI are deleted via [EmbeddingStore.deleteByMetadata]. This prevents duplicate
 *   or outdated entries if a file is modified or re-indexed.
 * - Chunk Metadata Format:
 *     - fileId: The file's URI string (used for deleting all chunks of a file).
 *     - uri: The file's URI string (used to open/render the document).
 *     - name: Display name shown in search result cards.
 *     - type: Category ("text" for .txt and .md files; future: "pdf", "image", "audio", "video").
 *     - location: Exact character offset within the document where the passage starts,
 *       allowing the viewer screen to scroll immediately to the matched passage.
 */
class Indexer(
    private val context: Context,
    private val embedder: Embedder,
    private val embeddingStore: EmbeddingStore
) {

    /**
     * Determines whether an imported file is supported by the text/markdown indexer.
     *
     * Supported formats (PRD §2):
     * - Extensions: .txt, .md
     * - MIME types: text/plain, text/markdown, or any text/ MIME type.
     */
    fun isTextFile(file: ImportedFile): Boolean {
        val name = file.displayName.lowercase()
        val mime = file.mimeType?.lowercase() ?: ""
        return name.endsWith(".txt") ||
                name.endsWith(".md") ||
                mime.startsWith("text/") ||
                mime == "text/plain" ||
                mime == "text/markdown"
    }

    /**
     * Reads, chunks, embeds, and stores an imported text or Markdown file.
     *
     * Execution flow:
     * 1. Validates that the file is a supported text/markdown type.
     * 2. Opens an input stream from Android's ContentResolver using the SAF URI.
     * 3. Reads the text content into memory using UTF-8 encoding.
     * 4. Splits the text into semantic passages via [TextChunker.chunk].
     * 5. Deletes any pre-existing chunks for this file from [EmbeddingStore] to maintain consistency.
     * 6. Generates a 768-dimensional normalized embedding for each chunk using [Embedder.embedText].
     * 7. Stores the batch of [RetrievalRecord] items in [EmbeddingStore] within a single transaction.
     *
     * @param file Metadata of the imported file to index.
     * @return [IndexResult] indicating success/failure and chunk count.
     */
    fun indexTextFile(file: ImportedFile): IndexResult {
        if (!isTextFile(file)) {
            return IndexResult(file.uri, 0, false, "Not a supported text file")
        }

        val uri = file.uri.toUri()
        val content = try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return IndexResult(file.uri, 0, false, "Could not open stream for URI")
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText() }
        } catch (e: Exception) {
            return IndexResult(file.uri, 0, false, "Read failed: ${e.message}")
        }

        val chunks = TextChunker.chunk(content)
        if (chunks.isEmpty()) {
            // If the document is empty or whitespace-only, remove any old chunks and return
            embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))
            return IndexResult(file.uri, 0, true)
        }

        // Delete existing chunks for this file before re-indexing to prevent duplicate entries
        embeddingStore.deleteByMetadata(mapOf("fileId" to file.uri))

        val records = mutableListOf<RetrievalRecord>()
        for ((index, chunk) in chunks.withIndex()) {
            // Unique ID per chunk: "<fileUri>_<chunkIndex>"
            val chunkId = "${file.uri}_$index"

            // Compute 768-dim float embedding vector via on-device EmbeddingGemma 2
            val embedding = embedder.embedText(chunk.text)

            // Structured metadata stored alongside the vector in SQLite
            val metadata = mapOf(
                "fileId" to file.uri,
                "uri" to file.uri,
                "name" to file.displayName,
                "type" to "text",
                "location" to chunk.charOffset.toString()
            )

            records.add(
                RetrievalRecord(
                    chunkId,
                    listOf(TextPart(chunk.text)),
                    embedding,
                    metadata
                )
            )
        }

        // Store all chunk records in one atomic SQLite transaction
        embeddingStore.storeBatch(records)
        return IndexResult(file.uri, records.size, true)
    }
}
