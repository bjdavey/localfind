package com.localfind.data

import com.google.mediapipe.tasks.retrieval.components.VectorStore
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class EmbeddingStoreTest {

    private class FakeVectorStore : VectorStore {
        private val records = ConcurrentHashMap<String, RetrievalRecord>()

        override fun upsert(records: List<RetrievalRecord>) {
            for (r in records) {
                this.records[r.id] = r
            }
        }

        override fun get(ids: List<String>): List<RetrievalRecord> =
            ids.mapNotNull { records[it] }

        override fun getAllRecordIds(): List<String> =
            records.keys.toList()

        override fun delete(ids: List<String>) {
            ids.forEach { records.remove(it) }
        }

        override fun delete(metadata: Map<String, String>) {
            val toRemove = records.values.filter { r ->
                metadata.all { (k, v) -> r.metadata[k] == v }
            }.map { it.id }
            toRemove.forEach { records.remove(it) }
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
            return records.values
                .filter { r -> metadataFilter.all { (k, v) -> r.metadata[k] == v } }
                .take(topK)
        }

        override fun close() {
            records.clear()
        }
    }

    @Test
    fun storeAndRetrieveRecord() {
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
}

