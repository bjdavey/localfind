package com.localfind.search

import com.google.mediapipe.tasks.core.TextPart
import com.google.mediapipe.tasks.retrieval.components.VectorStore
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import com.localfind.data.EmbeddingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Unit tests for [Searcher] and [SearchResult].
 *
 * Runs on standard JVM without requiring Android instrumentation, native LiteRT,
 * or on-device model files.
 */
class SearcherTest {

    /**
     * In-memory implementation of [VectorStore] that ranks records by cosine similarity
     * to simulate MediaPipe's [com.google.mediapipe.tasks.retrieval.components.SqliteVectorStore].
     */
    private class FakeVectorStore : VectorStore {
        private val records = ConcurrentHashMap<String, RetrievalRecord>()

        override fun upsert(records: List<RetrievalRecord>) {
            records.forEach { this.records[it.id] = it }
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
                .sortedByDescending { Searcher.calculateCosineSimilarity(queryEmbedding, it.embeddings) }
                .take(topK)
        }

        override fun close() {
            records.clear()
        }
    }

    @Test
    fun searchReturnsRankedResultsByCosineSimilarity() {
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)

        // Add 3 sample records with known 3-dimensional normalized vectors
        // Query will be [1.0, 0.0, 0.0]
        // Record 1: [1.0, 0.0, 0.0] -> similarity 1.0 (exact match)
        // Record 2: [0.7071, 0.7071, 0.0] -> similarity ~0.707 (partial match)
        // Record 3: [0.0, 1.0, 0.0] -> similarity 0.0 (orthogonal)
        val rec1 = RetrievalRecord(
            "file1_c0",
            listOf(TextPart("Exact match snippet")),
            floatArrayOf(1.0f, 0.0f, 0.0f),
            mapOf(
                "uri" to "content://com.localfind/file1",
                "name" to "first_document.txt",
                "type" to "text",
                "location" to "42"
            )
        )
        val rec2 = RetrievalRecord(
            "file2_c0",
            listOf(TextPart("Partial match snippet")),
            floatArrayOf(0.7071f, 0.7071f, 0.0f),
            mapOf(
                "uri" to "content://com.localfind/file2",
                "name" to "second_document.md",
                "type" to "text",
                "location" to "120"
            )
        )
        val rec3 = RetrievalRecord(
            "file3_c0",
            listOf(TextPart("Unrelated snippet")),
            floatArrayOf(0.0f, 1.0f, 0.0f),
            mapOf(
                "uri" to "content://com.localfind/file3",
                "name" to "unrelated.txt",
                "type" to "text",
                "location" to "0"
            )
        )
        fakeStore.upsert(listOf(rec1, rec2, rec3))

        // Create Searcher with mock embedder returning [1.0, 0.0, 0.0] for "first"
        val searcher = Searcher(embeddingStore) { _ ->
            floatArrayOf(1.0f, 0.0f, 0.0f)
        }

        val results = searcher.search("first", topK = 10)

        assertEquals(3, results.size)

        // 1st result should be rec1 (score 1.0)
        val first = results[0]
        assertEquals("file1_c0", first.chunkId)
        assertEquals("content://com.localfind/file1", first.fileUri)
        assertEquals("first_document.txt", first.fileName)
        assertEquals("text", first.fileType)
        assertEquals("Exact match snippet", first.snippet)
        assertEquals(42L, first.location)
        assertEquals(1.0f, first.score, 0.001f)

        // 2nd result should be rec2 (score ~0.707)
        val second = results[1]
        assertEquals("file2_c0", second.chunkId)
        assertEquals("second_document.md", second.fileName)
        assertEquals(0.7071f, second.score, 0.001f)

        // 3rd result should be rec3 (score 0.0)
        val third = results[2]
        assertEquals("file3_c0", third.chunkId)
        assertEquals(0.0f, third.score, 0.001f)

        // Verify descending score ordering
        assertTrue(results[0].score >= results[1].score)
        assertTrue(results[1].score >= results[2].score)
    }

    @Test
    fun blankOrEmptyQueryReturnsEmptyList() {
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)
        val searcher = Searcher(embeddingStore) { floatArrayOf(1f, 0f) }

        assertTrue(searcher.search("").isEmpty())
        assertTrue(searcher.search("   ").isEmpty())
        assertTrue(searcher.search("\t\n").isEmpty())
    }

    @Test
    fun topKLimitsResultCount() {
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)
        for (i in 1..10) {
            fakeStore.upsert(
                listOf(
                    RetrievalRecord(
                        "rec_$i",
                        listOf(TextPart("Item $i")),
                        floatArrayOf(1f, 0f),
                        mapOf("name" to "doc$i.txt")
                    )
                )
            )
        }

        val searcher = Searcher(embeddingStore) { floatArrayOf(1f, 0f) }

        val results = searcher.search("query", topK = 3)
        assertEquals(3, results.size)

        // Negative or zero topK returns empty
        assertTrue(searcher.search("query", topK = 0).isEmpty())
        assertTrue(searcher.search("query", topK = -1).isEmpty())
    }

    @Test
    fun metadataFilterRestrictsResults() {
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)

        val textRecord = RetrievalRecord(
            "text_1",
            listOf(TextPart("Text content")),
            floatArrayOf(1f, 0f),
            mapOf("type" to "text", "name" to "file.txt")
        )
        val pdfRecord = RetrievalRecord(
            "pdf_1",
            listOf(TextPart("PDF content")),
            floatArrayOf(1f, 0f),
            mapOf("type" to "pdf", "name" to "file.pdf")
        )
        fakeStore.upsert(listOf(textRecord, pdfRecord))

        val searcher = Searcher(embeddingStore) { floatArrayOf(1f, 0f) }

        val textOnly = searcher.search("query", metadataFilter = mapOf("type" to "text"))
        assertEquals(1, textOnly.size)
        assertEquals("text", textOnly[0].fileType)
        assertEquals("file.txt", textOnly[0].fileName)

        val pdfOnly = searcher.search("query", metadataFilter = mapOf("type" to "pdf"))
        assertEquals(1, pdfOnly.size)
        assertEquals("pdf", pdfOnly[0].fileType)
        assertEquals("file.pdf", pdfOnly[0].fileName)
    }

    @Test
    fun calculateCosineSimilarityMath() {
        // Identical vectors -> 1.0
        val v1 = floatArrayOf(3f, 4f)
        val v2 = floatArrayOf(3f, 4f)
        assertEquals(1.0f, Searcher.calculateCosineSimilarity(v1, v2), 0.0001f)

        // Scaled vectors in same direction -> 1.0
        val v3 = floatArrayOf(6f, 8f)
        assertEquals(1.0f, Searcher.calculateCosineSimilarity(v1, v3), 0.0001f)

        // Opposite vectors -> -1.0
        val vOpposite = floatArrayOf(-3f, -4f)
        assertEquals(-1.0f, Searcher.calculateCosineSimilarity(v1, vOpposite), 0.0001f)

        // Orthogonal vectors -> 0.0
        val vOrtho = floatArrayOf(-4f, 3f) // (3*-4 + 4*3) = 0
        assertEquals(0.0f, Searcher.calculateCosineSimilarity(v1, vOrtho), 0.0001f)

        // Empty or dimension mismatched -> 0.0
        assertEquals(0.0f, Searcher.calculateCosineSimilarity(floatArrayOf(), floatArrayOf(1f)), 0.0001f)
        assertEquals(0.0f, Searcher.calculateCosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(1f)), 0.0001f)
    }

    @Test
    fun searchAppliesSimilarityThresholdFilter() {
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)

        // rec1: score 1.0 (exact match)
        val rec1 = RetrievalRecord(
            "file1_c0",
            listOf(TextPart("Exact match")),
            floatArrayOf(1.0f, 0.0f, 0.0f),
            mapOf("name" to "exact.txt", "type" to "text")
        )
        // rec2: score ~0.707 (partial match)
        val rec2 = RetrievalRecord(
            "file2_c0",
            listOf(TextPart("Partial match")),
            floatArrayOf(0.7071f, 0.7071f, 0.0f),
            mapOf("name" to "partial.txt", "type" to "text")
        )
        // rec3: score 0.0 (orthogonal)
        val rec3 = RetrievalRecord(
            "file3_c0",
            listOf(TextPart("Unrelated")),
            floatArrayOf(0.0f, 1.0f, 0.0f),
            mapOf("name" to "unrelated.txt", "type" to "text")
        )
        fakeStore.upsert(listOf(rec1, rec2, rec3))

        val searcher = Searcher(embeddingStore) { _ -> floatArrayOf(1.0f, 0.0f, 0.0f) }

        // With threshold 0.0: all 3 returned
        val defaultResults = searcher.search("query", topK = 10, threshold = 0.0f)
        assertEquals(3, defaultResults.size)

        // With user-requested default threshold 0.6f: exact (1.0) and partial (0.707) returned, orthogonal (0.0) filtered out
        val threshold06Results = searcher.search("query", topK = 10, threshold = 0.6f)
        assertEquals(2, threshold06Results.size)
        assertEquals("file1_c0", threshold06Results[0].chunkId)
        assertEquals("file2_c0", threshold06Results[1].chunkId)

        // With high threshold 0.8f: only exact match returned
        val strictResults = searcher.search("query", topK = 10, threshold = 0.8f)
        assertEquals(1, strictResults.size)
        assertEquals("file1_c0", strictResults[0].chunkId)

        // With impossible threshold > 1.0: no results returned
        val impossibleResults = searcher.search("query", topK = 10, threshold = 1.01f)
        assertTrue(impossibleResults.isEmpty())
    }

    @Test
    fun searchFilteredBySpecificFileIdReturnsOnlyChunksFromThatFile() {
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)

        // Two chunks from file A
        val fileAChunk1 = RetrievalRecord(
            "fileA_c0",
            listOf(TextPart("Document A intro")),
            floatArrayOf(1.0f, 0.0f, 0.0f),
            mapOf("fileId" to "content://com.localfind/fileA", "uri" to "content://com.localfind/fileA", "name" to "docA.txt", "type" to "text")
        )
        val fileAChunk2 = RetrievalRecord(
            "fileA_c1",
            listOf(TextPart("Document A details")),
            floatArrayOf(0.9f, 0.1f, 0.0f),
            mapOf("fileId" to "content://com.localfind/fileA", "uri" to "content://com.localfind/fileA", "name" to "docA.txt", "type" to "text")
        )
        // One chunk from file B
        val fileBChunk1 = RetrievalRecord(
            "fileB_c0",
            listOf(TextPart("Document B content")),
            floatArrayOf(0.95f, 0.05f, 0.0f),
            mapOf("fileId" to "content://com.localfind/fileB", "uri" to "content://com.localfind/fileB", "name" to "docB.txt", "type" to "text")
        )

        fakeStore.upsert(listOf(fileAChunk1, fileAChunk2, fileBChunk1))

        val searcher = Searcher(embeddingStore) { _ -> floatArrayOf(1.0f, 0.0f, 0.0f) }

        // Unfiltered search: returns all 3 records
        val allResults = searcher.search("query", topK = 10)
        assertEquals(3, allResults.size)

        // Search scoped specifically to file A
        val fileAResults = searcher.search(
            query = "query",
            topK = 10,
            metadataFilter = mapOf("fileId" to "content://com.localfind/fileA")
        )
        assertEquals(2, fileAResults.size)
        assertTrue(fileAResults.all { it.fileUri == "content://com.localfind/fileA" })
        assertEquals("fileA_c0", fileAResults[0].chunkId)
        assertEquals("fileA_c1", fileAResults[1].chunkId)

        // Search scoped specifically to file B
        val fileBResults = searcher.search(
            query = "query",
            topK = 10,
            metadataFilter = mapOf("fileId" to "content://com.localfind/fileB")
        )
        assertEquals(1, fileBResults.size)
        assertEquals("fileB_c0", fileBResults[0].chunkId)
        assertEquals("content://com.localfind/fileB", fileBResults[0].fileUri)
    }

    /**
     * Verifies that searching while indexing is actively writing new chunks executes concurrently
     * and returns ranked results without race conditions, deadlocks, or exceptions.
     */
    @Test
    fun search_whileIndexingConcurrently_succeedsWithoutDeadlock() {
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)

        // Seed store with initial indexed file
        val initialRecord = RetrievalRecord(
            "file0_c0",
            listOf(TextPart("Initial indexed document")),
            floatArrayOf(1.0f, 0.0f, 0.0f),
            mapOf("fileId" to "content://com.localfind/file0", "uri" to "content://com.localfind/file0", "name" to "file0.txt", "type" to "text")
        )
        embeddingStore.storeBatch(listOf(initialRecord))

        val searcher = Searcher(embeddingStore) { _ -> floatArrayOf(1.0f, 0.0f, 0.0f) }

        val indexingDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val searchSuccessCount = java.util.concurrent.atomic.AtomicInteger(0)
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        // Background thread: continuously simulates chunks being indexed and saved to EmbeddingStore
        val indexThread = Thread {
            try {
                for (i in 1..20) {
                    val record = RetrievalRecord(
                        "stream_c$i",
                        listOf(TextPart("Streaming chunk $i")),
                        floatArrayOf(0.9f, 0.1f, 0.0f),
                        mapOf("fileId" to "content://com.localfind/stream", "uri" to "content://com.localfind/stream", "name" to "stream.txt", "type" to "text")
                    )
                    embeddingStore.store(record.id, record.embeddings, "Streaming chunk $i", record.metadata)
                    Thread.sleep(5)
                }
            } catch (t: Throwable) {
                errors.add(t)
            } finally {
                indexingDone.set(true)
            }
        }

        // Foreground / UI thread: repeatedly searches while indexing is running
        val searchThread = Thread {
            try {
                while (!indexingDone.get() || searchSuccessCount.get() < 5) {
                    val results = searcher.search(query = "query", topK = 5)
                    assertTrue("Search must return at least initial indexed record", results.isNotEmpty())
                    searchSuccessCount.incrementAndGet()
                    Thread.sleep(3)
                }
            } catch (t: Throwable) {
                errors.add(t)
            }
        }

        indexThread.start()
        searchThread.start()

        indexThread.join(5000)
        searchThread.join(5000)

        assertTrue("No thread exceptions occurred during concurrent search and indexing", errors.isEmpty())
        assertTrue("At least 5 searches executed while indexing was active", searchSuccessCount.get() >= 5)
        assertTrue("Store contains all chunks after concurrent indexing", embeddingStore.getAllRecordIds().size >= 21)
    }
}

