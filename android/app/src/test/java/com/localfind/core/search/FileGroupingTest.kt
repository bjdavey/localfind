package com.localfind.core.search

import com.localfind.core.model.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileGroupingTest {

    private fun hit(id: String, uri: String, score: Float, location: Long = 0L) =
        SearchResult(id, uri, "name", "video", "", location, score)

    @Test
    fun groupsHitsOfSameFileAndKeepsBestFirst() {
        val groups = FileGrouping.groupByFile(
            listOf(
                hit("a1", "a", 0.5f),
                hit("b1", "b", 0.9f),
                hit("a2", "a", 0.7f)
            )
        )
        // File "b" has the strongest hit so it leads, even though "a" has more hits
        assertEquals(listOf("b", "a"), groups.map { it.fileUri })
        assertEquals("a2", groups[1].best.chunkId)
        assertEquals(listOf("a1"), groups[1].others.map { it.chunkId })
        assertTrue(groups[0].others.isEmpty())
    }

    @Test
    fun blankUrisAreNotMerged() {
        val groups = FileGrouping.groupByFile(listOf(hit("x", "", 0.6f), hit("y", "", 0.5f)))
        assertEquals(2, groups.size)
    }

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertTrue(FileGrouping.groupByFile(emptyList()).isEmpty())
    }
}

