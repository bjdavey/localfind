package com.localfind.core.search

import com.localfind.core.model.ChunkMetadata
import com.localfind.core.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [ResultRanking]. */
class ResultRankingTest {

    private fun result(id: String, score: Float) = SearchResult(id, "uri", "f", "text", "", 0L, score)

    @Test
    fun toResult_readsMetadataAndAppliesDefaults() {
        val full = ResultRanking.toResult(
            id = "c1",
            metadata = mapOf(
                ChunkMetadata.URI to "content://a",
                ChunkMetadata.NAME to "a.mp4",
                ChunkMetadata.TYPE to "video",
                ChunkMetadata.LOCATION to "4000"
            ),
            snippet = "",
            score = 0.8f,
            excludeFileUri = null
        )!!
        assertEquals("content://a", full.fileUri)
        assertEquals("a.mp4", full.fileName)
        assertEquals("video", full.fileType)
        assertEquals(4000L, full.location)

        // Missing metadata: falls back to fileId for the URI, "text" for the type and 0 for the location
        val sparse = ResultRanking.toResult("c2", mapOf(ChunkMetadata.FILE_ID to "content://b"), "snippet", 0.1f, null)!!
        assertEquals("content://b", sparse.fileUri)
        assertEquals("text", sparse.fileType)
        assertEquals(0L, sparse.location)
        assertEquals("snippet", sparse.snippet)
    }

    @Test
    fun toResult_dropsTheExcludedSourceFile() {
        val metadata = mapOf(ChunkMetadata.URI to "content://query")
        assertNull(ResultRanking.toResult("c", metadata, "", 1f, excludeFileUri = "content://query"))
    }

    @Test
    fun rank_filtersByThresholdDedupesSortsAndLimits() {
        val ranked = ResultRanking.rank(
            listOf(result("a", 0.5f), result("b", 0.9f), result("a", 0.5f), result("c", 0.1f), result("d", 0.7f)),
            threshold = 0.3f,
            topK = 2
        )
        assertEquals(listOf("b", "d"), ranked.map { it.chunkId })
    }
}
