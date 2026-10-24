package com.localfind.ui.search

import com.localfind.core.library.LibraryFilters
import com.localfind.core.search.FileMatches
import com.localfind.core.search.FileGrouping
import com.localfind.core.format.SearchResultFormatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfind.core.model.SearchResult
import com.localfind.core.model.FileTypes
import com.localfind.ui.gallery.FileTypeIcon

/**
 * Scrollable list displaying ranked [SearchResult] cards.
 */
@Composable
fun SearchResultsList(
    results: List<SearchResult>,
    onResultClick: (SearchResult) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    // One entry per file (best hit first). Chunk ids are deduped first; group keys are unique by
    // construction, so LazyColumn never throws an IllegalArgumentException.
    val groups = remember(results) {
        FileGrouping.groupByFile(results.distinctBy { it.chunkId })
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = groups,
            key = { it.fileUri }
        ) { group ->
            FileMatchesItem(group = group, onResultClick = onResultClick)
        }
    }
}

/**
 * Best match of a file as a full card. When the file has more hits, a horizontal strip of
 * thumbnail tiles (one per extra hit, best first) follows; each tile shows the preview at that
 * spot (video frame / PDF page / passage), its location and score, and opens the file there.
 */
@Composable
private fun FileMatchesItem(
    group: FileMatches,
    onResultClick: (SearchResult) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SearchResultCard(result = group.best, onClick = { onResultClick(group.best) })

        if (group.others.isNotEmpty()) {
            val count = group.others.size
            Text(
                text = "$count more ${if (count == 1) "match" else "matches"} in this file",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items = group.others, key = { it.chunkId }) { hit ->
                    MoreMatchTile(hit = hit, onClick = { onResultClick(hit) })
                }
            }
        }
    }
}

/** Small thumbnail tile for an extra hit: preview with a location label overlay, score below. */
@Composable
private fun MoreMatchTile(hit: SearchResult, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box {
            SearchResultThumbnail(result = hit, modifier = Modifier.size(96.dp))
            // Location label (e.g. "Page 4", "01:20") on a dark pill so it reads on any preview
            Text(
                text = SearchResultFormatter.formatLocation(hit.fileType, hit.location),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
        Text(
            text = SearchResultFormatter.formatScore(hit.score),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}
/**
 * Rich visual result card showing:
 * - Left: 78dp square visual thumbnail (Image, Video frame, PDF page 1, Text preview, or Audio cover).
 * - Right: File name, similarity score badge (`88% match`), file type + location badge (`Page 4`, `01:20`),
 *   and matched snippet text.
 */
@Composable
fun SearchResultCard(
    result: SearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val category = remember(result.fileType, result.fileName) {
        LibraryFilters.categorizeSearchResult(result)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Left: Square Thumbnail Preview
            SearchResultThumbnail(result = result, modifier = Modifier.size(78.dp))

            // Right: Metadata & Snippet
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Header: File Name + Match Score Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = result.fileName.ifEmpty { "Unknown Document" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = SearchResultFormatter.formatScore(result.score),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                // Sub-header: Type Badge + Location Offset
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shared icon-only file-type indicator (same as gallery and details)
                    FileTypeIcon(category, isGif = FileTypes.isGifNameOrMime(result.fileName, null), size = 16.dp)

                    Text(
                        text = SearchResultFormatter.formatLocation(result.fileType, result.location),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Snippet Box
                if (result.snippet.isNotBlank()) {
                    Text(
                        text = SearchResultFormatter.formatSnippet(result.snippet, maxLength = 180),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
