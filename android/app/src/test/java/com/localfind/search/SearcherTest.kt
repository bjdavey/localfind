package com.localfind.search

import com.localfind.core.search.VectorMath
import com.google.mediapipe.tasks.core.TextPart
import com.google.mediapipe.tasks.retrieval.components.VectorStore
import com.google.mediapipe.tasks.retrieval.model.RetrievalRecord
import com.localfind.data.EmbeddingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

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
                .sortedByDescending { VectorMath.cosineSimilarity(queryEmbedding, it.embeddings) }
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
        val searcher = Searcher(embeddingStore, embedQuery = { _ ->
            floatArrayOf(1.0f, 0.0f, 0.0f)
        })

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
        val searcher = Searcher(embeddingStore, embedQuery = { floatArrayOf(1f, 0f) })

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

        val searcher = Searcher(embeddingStore, embedQuery = { floatArrayOf(1f, 0f) })

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

        val searcher = Searcher(embeddingStore, embedQuery = { floatArrayOf(1f, 0f) })

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

        val searcher = Searcher(embeddingStore, embedQuery = { _ -> floatArrayOf(1.0f, 0.0f, 0.0f) })

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

        val searcher = Searcher(embeddingStore, embedQuery = { _ -> floatArrayOf(1.0f, 0.0f, 0.0f) })

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

        val searcher = Searcher(embeddingStore, embedQuery = { _ -> floatArrayOf(1.0f, 0.0f, 0.0f) })

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
                    embeddingStore.storeBatch(listOf(record))
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
        assertTrue("Store contains all chunks after concurrent indexing", embeddingStore.count() >= 21)
    }

    @Test
    fun searchAfterFilterFindsModalityMatchesThatGlobalSearchWouldCrowdOut() {
        // Validates the architectural requirement: "Search after filter, not filter search results"
        // Problem: When a global top-K search is performed, higher-scoring chunks from dominant modalities
        // (e.g. 10 text documents) crowd out lower-scoring chunks from another modality (e.g. 3 images).
        // If results were simply filtered in-memory post-search ("filter search results"), 0 images would be found.
        // Solution: Query-time modality filtering ("search after filter") searches specifically for the
        // requested fileType in SQLite, successfully returning the top matching images.
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)

        // Seed 10 text chunks with high similarity (angles 0.05 rad to 0.5 rad -> scores ~0.998 to 0.877)
        for (i in 1..10) {
            val angle = 0.05 * i
            val textRec = RetrievalRecord(
                "text_$i",
                listOf(TextPart("Text content passage $i")),
                floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat()),
                mapOf("name" to "doc$i.txt", "type" to "text")
            )
            fakeStore.upsert(listOf(textRec))
        }

        // Seed 3 image chunks with lower similarity (angles 1.1 rad to 1.3 rad -> scores ~0.453 to 0.267)
        for (i in 1..3) {
            val angle = 1.0 + (0.1 * i)
            val imgRec = RetrievalRecord(
                "image_$i",
                emptyList(),
                floatArrayOf(cos(angle).toFloat(), sin(angle).toFloat()),
                mapOf("name" to "photo$i.jpg", "type" to "image")
            )
            fakeStore.upsert(listOf(imgRec))
        }

        val searcher = Searcher(embeddingStore, embedQuery = { _ -> floatArrayOf(1.0f, 0.0f) })

        // Global search with topK = 10 returns all 10 text chunks because their scores are higher (> 0.87 vs < 0.46)
        val globalResults = searcher.search("sunset photo", topK = 10)
        assertEquals(10, globalResults.size)
        assertTrue(globalResults.all { it.fileType == "text" })

        // In the old buggy "filter search results" pattern, filtering globalResults for images yielded 0 items
        val oldPatternFilteredResults = globalResults.filter { it.fileType == "image" }
        assertEquals(0, oldPatternFilteredResults.size)

        // With the new "search after filter" pattern, search queries specifically for "image" in SQLite
        val imageSearchResults = searcher.search("sunset photo", topK = 10, fileTypes = listOf("image"))
        assertEquals(3, imageSearchResults.size)
        assertTrue(imageSearchResults.all { it.fileType == "image" })
        assertEquals("image_1", imageSearchResults[0].chunkId)
        assertEquals("image_2", imageSearchResults[1].chunkId)
        assertEquals("image_3", imageSearchResults[2].chunkId)
    }

    @Test
    fun searchWithMultipleFileTypesRetrievesAndRanksAcrossCombinedTypes() {
        // Validates multi-type modality categories (e.g. DOCUMENTS which covers both "pdf" and "text").
        // Searcher queries candidates for each type in SQLite, merges them, and returns top-K ranked by score.
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)

        // Query vector is along [1.0, 0.0]. Vectors have varying angles:
        // aud1: angle 0.1 -> score cos(0.1) ~ 0.995 (highest score, but audio)
        // pdf1: angle 0.2 -> score cos(0.2) ~ 0.980
        // txt1: angle 0.4 -> score cos(0.4) ~ 0.921
        // pdf2: angle 0.6 -> score cos(0.6) ~ 0.825
        // txt2: angle 0.8 -> score cos(0.8) ~ 0.697
        val aud1 = RetrievalRecord("aud_1", listOf(TextPart("Audio")), floatArrayOf(cos(0.1).toFloat(), sin(0.1).toFloat()), mapOf("name" to "a1.mp3", "type" to "audio"))
        val pdf1 = RetrievalRecord("pdf_1", listOf(TextPart("PDF 1")), floatArrayOf(cos(0.2).toFloat(), sin(0.2).toFloat()), mapOf("name" to "p1.pdf", "type" to "pdf"))
        val txt1 = RetrievalRecord("txt_1", listOf(TextPart("TXT 1")), floatArrayOf(cos(0.4).toFloat(), sin(0.4).toFloat()), mapOf("name" to "t1.txt", "type" to "text"))
        val pdf2 = RetrievalRecord("pdf_2", listOf(TextPart("PDF 2")), floatArrayOf(cos(0.6).toFloat(), sin(0.6).toFloat()), mapOf("name" to "p2.pdf", "type" to "pdf"))
        val txt2 = RetrievalRecord("txt_2", listOf(TextPart("TXT 2")), floatArrayOf(cos(0.8).toFloat(), sin(0.8).toFloat()), mapOf("name" to "t2.txt", "type" to "text"))

        fakeStore.upsert(listOf(pdf1, pdf2, txt1, txt2, aud1))

        val searcher = Searcher(embeddingStore, embedQuery = { floatArrayOf(1.0f, 0.0f) })

        // Search for DOCUMENTS (types: "pdf", "text") with topK = 3
        val docResults = searcher.search("contract", topK = 3, fileTypes = listOf("pdf", "text"))

        // Must return top 3 between pdf and text, ranked by score (excluding audio even though audio score 0.995 is higher)
        assertEquals(3, docResults.size)
        assertEquals("pdf_1", docResults[0].chunkId) // score ~0.980
        assertEquals("txt_1", docResults[1].chunkId) // score ~0.921
        assertEquals("pdf_2", docResults[2].chunkId) // score ~0.825
        assertTrue(docResults.none { it.fileType == "audio" })
    }

    @Test
    fun searchWithEmptyOrNullFileTypesSearchesAllModalities() {
        // Validates that null or empty fileTypes continues to search across all modalities (GalleryCategory.ALL)
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)

        val img = RetrievalRecord("img_1", emptyList(), floatArrayOf(1f, 0f), mapOf("name" to "i.jpg", "type" to "image"))
        val txt = RetrievalRecord("txt_1", emptyList(), floatArrayOf(1f, 0f), mapOf("name" to "t.txt", "type" to "text"))
        fakeStore.upsert(listOf(img, txt))

        val searcher = Searcher(embeddingStore, embedQuery = { floatArrayOf(1f, 0f) })

        val nullFilterResults = searcher.search("test", fileTypes = null)
        assertEquals(2, nullFilterResults.size)

        val emptyFilterResults = searcher.search("test", fileTypes = emptyList())
        assertEquals(2, emptyFilterResults.size)
    }

    @Test
    fun searchByEmbeddingDirectlyRetrievesNearestNeighbors() {
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)

        val targetVec = floatArrayOf(1f, 0f, 0f)
        val rec1 = RetrievalRecord("img_1", emptyList(), floatArrayOf(0.95f, 0.31f, 0f), mapOf("name" to "similar.jpg", "type" to "image"))
        val rec2 = RetrievalRecord("img_2", emptyList(), floatArrayOf(0.1f, 0.99f, 0f), mapOf("name" to "different.jpg", "type" to "image"))
        fakeStore.upsert(listOf(rec1, rec2))

        val searcher = Searcher(embeddingStore, embedQuery = { floatArrayOf(0f, 0f, 0f) })

        val results = searcher.searchByEmbedding(targetVec, topK = 5)
        assertEquals(2, results.size)
        assertEquals("img_1", results[0].chunkId)
        assertTrue(results[0].score > results[1].score)
    }

    @Test
    fun searchByEmbeddingExcludesSourceFileUriWhenSpecified() {
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)

        val queryVec = floatArrayOf(1f, 0f, 0f)
        val sourceUri = "content://com.localfind/source_photo.jpg"

        // Exact match with score 1.0 is the query file itself
        val sourceRec = RetrievalRecord(
            "source_0",
            emptyList(),
            floatArrayOf(1f, 0f, 0f),
            mapOf("uri" to sourceUri, "name" to "source_photo.jpg", "type" to "image")
        )
        // Similar photo with score ~0.9
        val similarRec = RetrievalRecord(
            "other_0",
            emptyList(),
            floatArrayOf(0.9f, 0.43f, 0f),
            mapOf("uri" to "content://com.localfind/other.jpg", "name" to "other.jpg", "type" to "image")
        )
        fakeStore.upsert(listOf(sourceRec, similarRec))

        val searcher = Searcher(embeddingStore, embedQuery = { floatArrayOf(0f, 0f, 0f) })

        // When excludeFileUri is null, source photo is returned at #1
        val withSource = searcher.searchByEmbedding(queryVec, topK = 5, excludeFileUri = null)
        assertEquals(2, withSource.size)
        assertEquals("source_0", withSource[0].chunkId)

        // When excludeFileUri is set to sourceUri, source photo is filtered out and similar photo becomes #1
        val withoutSource = searcher.searchByEmbedding(queryVec, topK = 5, excludeFileUri = sourceUri)
        assertEquals(1, withoutSource.size)
        assertEquals("other_0", withoutSource[0].chunkId)
        assertEquals("other.jpg", withoutSource[0].fileName)
    }

    @Test
    fun imageQueryEmbeddingInvokesEmbedImageQueryAndRanksMatches() {
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)

        val rec = RetrievalRecord(
            "img_match",
            emptyList(),
            floatArrayOf(1f, 0f),
            mapOf("name" to "beach.jpg", "type" to "image")
        )
        fakeStore.upsert(listOf(rec))

        var imageEmbedderInvoked = false
        val searcher = Searcher(
            embeddingStore = embeddingStore,
            embedQuery = { floatArrayOf(0f, 1f) },
            embedImageQuery = { _ ->
                imageEmbedderInvoked = true
                floatArrayOf(1f, 0f)
            }
        )

        // Allocate a dummy Bitmap instance without invoking Android native methods
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val dummyBitmap = unsafe.allocateInstance(android.graphics.Bitmap::class.java) as android.graphics.Bitmap

        // Search by image: embed the bitmap, then run nearest-neighbor retrieval on its vector
        val results = searcher.searchByEmbedding(searcher.embedImage(dummyBitmap), topK = 5)
        assertTrue(imageEmbedderInvoked)
        assertEquals(1, results.size)
        assertEquals("img_match", results[0].chunkId)
        assertEquals(1.0f, results[0].score, 0.001f)
    }

    @Test(expected = IllegalStateException::class)
    fun embedImageThrowsWhenEmbedImageQueryNotConfigured() {
        val fakeStore = FakeVectorStore()
        val embeddingStore = EmbeddingStore(fakeStore)

        val searcher = Searcher(embeddingStore, embedQuery = { floatArrayOf(1f, 0f) })

        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val dummyBitmap = unsafe.allocateInstance(android.graphics.Bitmap::class.java) as android.graphics.Bitmap

        searcher.embedImage(dummyBitmap)
    }

    @Test
    fun searchDeduplicatesResultsByChunkId() {
        // Simulates a scenario where vectorStore returns duplicate records with identical IDs
        // (e.g. MediaPipe SqliteVectorStore having duplicate rows after an interrupted resume)
        val duplicateStore = object : VectorStore {
            private val duplicateList = mutableListOf<RetrievalRecord>()

            override fun upsert(records: List<RetrievalRecord>) {
                duplicateList.addAll(records)
            }
            override fun get(ids: List<String>): List<RetrievalRecord> =
                duplicateList.filter { it.id in ids }
            override fun getAllRecordIds(): List<String> = duplicateList.map { it.id }
            override fun delete(ids: List<String>) { duplicateList.removeAll { it.id in ids } }
            override fun delete(metadata: Map<String, String>) {
                duplicateList.removeAll { r -> metadata.all { (k, v) -> r.metadata[k] == v } }
            }
            override fun deleteAll() { duplicateList.clear() }
            override fun search(queryEmbedding: FloatArray, topK: Int): List<RetrievalRecord> =
                search(queryEmbedding, topK, emptyMap())
            override fun search(
                queryEmbedding: FloatArray,
                topK: Int,
                metadataFilter: Map<String, String>
            ): List<RetrievalRecord> {
                return duplicateList
            }
            override fun close() { duplicateList.clear() }
        }

        val embeddingStore = EmbeddingStore(duplicateStore)
        val searcher = Searcher(embeddingStore, embedQuery = { floatArrayOf(1f, 0f) })

        val rec1 = RetrievalRecord(
            "chunk_dup_1",
            listOf(TextPart("Duplicate chunk test")),
            floatArrayOf(1f, 0f),
            mapOf("name" to "doc.txt", "type" to "text")
        )
        val rec2 = RetrievalRecord(
            "chunk_dup_1",
            listOf(TextPart("Duplicate chunk test")),
            floatArrayOf(1f, 0f),
            mapOf("name" to "doc.txt", "type" to "text")
        )
        duplicateStore.upsert(listOf(rec1, rec2))

        val results = searcher.search("test", topK = 10)
        // Must deduplicate so only 1 result is returned
        assertEquals(1, results.size)
        assertEquals("chunk_dup_1", results[0].chunkId)
    }
}
