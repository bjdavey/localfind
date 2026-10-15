package com.localfind.data

import android.content.Context
import com.google.mediapipe.tasks.core.Part
import com.google.mediapipe.tasks.core.TextPart
import com.google.mediapipe.tasks.retrieval.components.SqliteVectorStore
import com.google.mediapipe.tasks.retrieval.components.VectorStore
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import java.io.File

const val DEFAULT_DATABASE_NAME = "localfind_vectors.db"
const val DEFAULT_EMBEDDING_DIMENSION = 768

/**
 * Local storage for embeddings and chunk metadata using MediaPipe's [VectorStore].
 */
class EmbeddingStore(
    private val vectorStore: VectorStore
) : AutoCloseable {

    companion object {
        fun create(
            context: Context,
            databaseName: String = DEFAULT_DATABASE_NAME,
            dimension: Int = DEFAULT_EMBEDDING_DIMENSION
        ): EmbeddingStore {
            val dbFile = File(context.filesDir, databaseName)
            val store = SqliteVectorStore(context, dbFile.absolutePath, dimension)
            return EmbeddingStore(store)
        }
    }

    /**
     * Stores or updates an embedding record with optional text content and metadata.
     */
    fun store(
        id: String,
        embedding: FloatArray,
        text: String? = null,
        metadata: Map<String, String> = emptyMap()
    ) {
        val parts: List<Part> = if (text != null) listOf(TextPart(text)) else emptyList()
        val record = RetrievalRecord(id, parts, embedding, metadata)
        vectorStore.upsert(listOf(record))
    }

    /**
     * Stores or updates a batch of records.
     */
    fun storeBatch(records: List<RetrievalRecord>) {
        vectorStore.upsert(records)
    }

    /**
     * Returns a record by its unique ID, or null if not found.
     */
    fun get(id: String): RetrievalRecord? =
        vectorStore.get(listOf(id)).firstOrNull()

    /**
     * Returns all stored record IDs.
     */
    fun getAllRecordIds(): List<String> =
        vectorStore.allRecordIds

    /**
     * Deletes a record by ID.
     */
    fun delete(id: String) {
        vectorStore.delete(listOf(id))
    }

    /**
     * Deletes records matching the given metadata filter (e.g. `mapOf("fileId" to fileId)`).
     */
    fun deleteByMetadata(metadata: Map<String, String>) {
        vectorStore.delete(metadata)
    }

    /**
     * Deletes all records.
     */
    fun deleteAll() {
        vectorStore.deleteAll()
    }

    /**
     * Searches for top-K matching records for the given query vector.
     */
    fun search(
        queryEmbedding: FloatArray,
        topK: Int = 10,
        metadataFilter: Map<String, String> = emptyMap()
    ): List<RetrievalRecord> {
        return if (metadataFilter.isEmpty()) {
            vectorStore.search(queryEmbedding, topK)
        } else {
            vectorStore.search(queryEmbedding, topK, metadataFilter)
        }
    }

    override fun close() {
        vectorStore.close()
    }
}

