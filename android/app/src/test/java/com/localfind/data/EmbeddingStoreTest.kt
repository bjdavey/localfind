package com.localfind.data

import com.google.mediapipe.tasks.retrieval.components.VectorStore
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for [EmbeddingStore].
 * Uses a lightweight in-memory [FakeVectorStore] implementation of MediaPipe's [VectorStore] interface.
 */
class EmbeddingStoreTest {

    /**
     * In-memory mock for [VectorStore] to allow fast JVM unit testing
     * without needing native SQLite or Android instrumentation.
     */
    private class FakeVectorStore : VectorStore {
        // Use a list to mirror SQLite table behavior (which does not automatically overwrite duplicate IDs)
        private val records = java.util.concurrent.CopyOnWriteArrayList<RetrievalRecord>()

        override fun upsert(records: List<RetrievalRecord>) {
            this.records.addAll(records)
        }

        override fun get(ids: List<String>): List<RetrievalRecord> =
            ids.mapNotNull { id -> records.firstOrNull { it.id == id } }

        override fun getAllRecordIds(): List<String> =
            records.map { it.id }

        override fun delete(ids: List<String>) {
            records.removeAll { it.id in ids }
        }

        override fun delete(metadata: Map<String, String>) {
            records.removeAll { r -> metadata.all { (k, v) -> r.metadata[k] == v } }
        }

        override fun deleteAll() {
            records.clear()
        }

        override fun search(queryEmbedding: FloatArray, topK: Int): List<RetrievalRecord> =
            search(queryEmbedding, topK, emptyMap())

        override fun search(
            queryEmbedding: FloatArray,
            topK: Int,
            metadataFilter: Map<String, String>
        ): List<RetrievalRecord> {
            return records
                .filter { r -> metadataFilter.all { (k, v) -> r.metadata[k] == v } }
                .take(topK)
        }

        override fun close() {
            records.clear()
        }
    }

    @Test
    fun storeAndRetrieveRecord() {
        // Validates storing a vector with metadata and retrieving it by ID
        val store = EmbeddingStore(FakeVectorStore())
        val embedding = floatArrayOf(0.1f, 0.2f, 0.3f)
        store.store(
            id = "doc1_chunk0",
            embedding = embedding,
            text = "Hello local search",
            metadata = mapOf("fileId" to "doc1", "type" to "text")
        )

        val record = store.get("doc1_chunk0")
        assertNotNull(record)
        assertEquals("doc1_chunk0", record!!.id)
        assertEquals(3, record.embeddings.size)
        assertEquals("doc1", record.metadata["fileId"])
        assertEquals("text", record.metadata["type"])
    }

    @Test
    fun deleteRecordById() {
        // Validates deleting a specific record by unique ID
        val store = EmbeddingStore(FakeVectorStore())
        store.store("id1", floatArrayOf(1f))
        store.store("id2", floatArrayOf(2f))

        assertEquals(2, store.getAllRecordIds().size)

        store.delete("id1")
        assertNull(store.get("id1"))
        assertNotNull(store.get("id2"))
        assertEquals(listOf("id2"), store.getAllRecordIds())
    }

    @Test
    fun deleteByMetadata() {
        // Validates deleting all chunks matching metadata criteria (e.g., fileId)
        val store = EmbeddingStore(FakeVectorStore())
        store.store("f1_c1", floatArrayOf(1f), metadata = mapOf("fileId" to "f1"))
        store.store("f1_c2", floatArrayOf(2f), metadata = mapOf("fileId" to "f1"))
        store.store("f2_c1", floatArrayOf(3f), metadata = mapOf("fileId" to "f2"))

        assertEquals(3, store.getAllRecordIds().size)

        store.deleteByMetadata(mapOf("fileId" to "f1"))
        assertEquals(1, store.getAllRecordIds().size)
        assertNotNull(store.get("f2_c1"))
        assertNull(store.get("f1_c1"))
        assertNull(store.get("f1_c2"))
    }

    @Test
    fun deleteByFileUri() {
        // Validates deleting all embeddings belonging to a specific file URI
        val store = EmbeddingStore(FakeVectorStore())
        val uri1 = "content://com.android.providers.media.documents/document/123"
        val uri2 = "content://com.android.providers.media.documents/document/456"

        store.store("chunk1", floatArrayOf(0.1f), text = "Snippet 1", metadata = mapOf("fileId" to uri1))
        store.store("chunk2", floatArrayOf(0.2f), text = "Snippet 2", metadata = mapOf("fileId" to uri1))
        store.store("chunk3", floatArrayOf(0.3f), text = "Snippet 3", metadata = mapOf("fileId" to uri2))

        assertEquals(3, store.getAllRecordIds().size)

        // Delete all chunks for uri1
        store.deleteByFileUri(uri1)

        assertEquals(1, store.getAllRecordIds().size)
        assertNull(store.get("chunk1"))
        assertNull(store.get("chunk2"))
        assertNotNull(store.get("chunk3"))
        assertEquals(uri2, store.get("chunk3")?.metadata?.get("fileId"))
    }
}
