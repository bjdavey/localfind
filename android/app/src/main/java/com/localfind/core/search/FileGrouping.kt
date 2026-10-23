package com.localfind.core.search

import com.localfind.core.model.SearchResult

/**
 * All matches of one file: the best-scoring hit plus the remaining hits (best first).
 */
data class FileMatches(
    val fileUri: String,
    val best: SearchResult,
    val others: List<SearchResult>
)

/** Pure grouping of ranked [SearchResult]s by source file. */
object FileGrouping {

    /**
     * Groups [ranked] hits by `fileUri`. Groups are ordered by their best score (descending),
     * and hits inside a group are ordered by score too, so the file with the strongest match comes first.
     * Hits with a blank uri are kept apart (grouped by chunk id) so they never merge into one entry.
     */
    fun groupByFile(ranked: List<SearchResult>): List<FileMatches> =
        ranked
            .groupBy { it.fileUri.ifBlank { "chunk:${it.chunkId}" } }
            .map { (key, hits) ->
                val sorted = hits.sortedByDescending { it.score }
                FileMatches(fileUri = key, best = sorted.first(), others = sorted.drop(1))
            }
            .sortedByDescending { it.best.score }
}

