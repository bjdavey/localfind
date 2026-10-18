package com.localfind.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.localfind.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.localfind.data.ImportedFile
import com.localfind.index.Indexer
import com.localfind.search.SearchResult
import com.localfind.ui.theme.BadgeAudio
import com.localfind.ui.theme.BadgeImage
import com.localfind.ui.theme.BadgePdf
import com.localfind.ui.theme.BadgeText
import com.localfind.ui.theme.BadgeVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated Full-Screen Semantic Search View.
 *
 * Opened when the user taps the bottom floating search bar in [GalleryScreen]
 * or triggers a search inside a specific file from file details / viewers.
 *
 * Features:
 * - Top search bar with back navigation (`←`), auto-focused query input, clear button, and Search button.
 * - Optional scoped search banner when searching within a specific [targetFile].
 * - Modality filter chips (`All`, `Photos`, `Videos`, `Docs`, `Audio`) to filter ranked hits.
 * - Interactive example suggestion pills when idle.
 * - Ranked result cards with visual thumbnail previews, similarity percentage badge, location offset,
 *   and text snippet preview.
 */
@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    isSearching: Boolean,
    hasSearched: Boolean,
    searchResults: List<SearchResult>,
    onResultClick: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
    targetFile: ImportedFile? = null,
    onClearTargetFile: (() -> Unit)? = null,
    errorMessage: String? = null,
    indexedChunksCount: Int = 0,
    listState: LazyListState = rememberLazyListState(),
    backHandlerEnabled: Boolean = true,
    onClose: (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var selectedCategory by remember { mutableStateOf(GalleryCategory.ALL) }

    // Intercept system back button to return to the Gallery
    if (onClose != null) {
        BackHandler(enabled = backHandlerEnabled) {
            onClose()
        }
    }

    // Automatically focus the search box when entering the dedicated search screen if query is empty
    LaunchedEffect(Unit) {
        if (query.isEmpty() && !hasSearched) {
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    val filteredResults = remember(searchResults, selectedCategory) {
        ThumbnailHelper.filterSearchResults(searchResults, selectedCategory)
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                top = statusBarPadding.calculateTopPadding() + 8.dp,
                bottom = navBarPadding.calculateBottomPadding()
            )
    ) {
        // =====================================================================
        // 1. TOP SEARCH INPUT HEADER WITH BACK BUTTON
        // =====================================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Text(
                        text = "←",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        text = if (targetFile != null) "Search inside ${targetFile.displayName}..." else "Search files by meaning...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                shape = RoundedCornerShape(26.dp),
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(20.dp)
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Text(
                                text = "✕",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        if (query.isNotBlank() && !isSearching) {
                            onSearch()
                        }
                    }
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    onSearch()
                },
                enabled = query.isNotBlank() && !isSearching,
                shape = RoundedCornerShape(22.dp)
            ) {
                Text("Go", fontWeight = FontWeight.Bold)
            }
        }

        // =====================================================================
        // 1.5. SCOPED TARGET FILE BANNER (When searching inside a specific file)
        // =====================================================================
        if (targetFile != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "🎯 In:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = targetFile.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (onClearTargetFile != null) {
                        TextButton(
                            onClick = onClearTargetFile,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = "Search All Files ✕",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // =====================================================================
        // 2. MODALITY FILTER CHIPS (Only shown when searching across all files)
        // =====================================================================
        if (targetFile == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GalleryCategory.entries.forEach { category ->
                    val count = remember(searchResults, category) {
                        ThumbnailHelper.filterSearchResults(searchResults, category).size
                    }
                    val isSelected = selectedCategory == category
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = category },
                        label = {
                            val suffix = if (hasSearched && searchResults.isNotEmpty()) " ($count)" else ""
                            Text(
                                text = "${category.icon} ${category.label}$suffix",
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }
        }

        // =====================================================================
        // 3. MAIN SEARCH CONTENT AREA
        // =====================================================================
        when {
            isSearching -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Embedding query & searching across $indexedChunksCount chunks...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }

            hasSearched && filteredResults.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "🔎",
                            fontSize = 42.sp
                        )
                        Text(
                            text = if (searchResults.isEmpty()) {
                                "No matching content found"
                            } else {
                                "No matching ${selectedCategory.label.lowercase()} found"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Try describing the concept differently, switching the category filter above, or lowering the Similarity Threshold in Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            hasSearched && filteredResults.isNotEmpty() -> {
                Text(
                    text = "Showing ${filteredResults.size} ranked match(es)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                )

                SearchResultsList(
                    results = filteredResults,
                    onResultClick = onResultClick,
                    listState = listState,
                    modifier = Modifier.weight(1f)
                )
            }

            else -> {
                // Idle / Prompt State with Interactive Suggestion Chips
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "✨",
                            fontSize = 44.sp
                        )
                        Text(
                            text = "Multimodal Semantic Search",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Describe a scene in a photo or video, a topic in a PDF or note, or a sound in an audio clip.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Try an example query:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )

                        val suggestions = listOf(
                            "sunset over the water",
                            "important deadline or contract",
                            "person smiling outdoors",
                            "music or voice discussion"
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            suggestions.forEach { sample ->
                                SuggestionChip(
                                    onClick = {
                                        onQueryChange(sample)
                                    },
                                    label = { Text("\"$sample\"") },
                                    shape = RoundedCornerShape(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (indexedChunksCount > 0) {
                                "$indexedChunksCount multimodal chunks indexed & ready (100% offline)"
                            } else {
                                "No files indexed yet — return to Gallery and tap + to import files."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

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
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = results,
            key = { it.chunkId }
        ) { result ->
            SearchResultCard(
                result = result,
                onClick = { onResultClick(result) }
            )
        }
    }
}

/**
 * Rich visual result card showing:
 * - Left: 76dp square visual thumbnail (Image, Video frame, PDF page 1, Text preview, or Audio cover).
 * - Right: File name, similarity score badge (`88% match`), file type + location badge (`Page 4`, `01:20`),
 *   and matched snippet text.
 */
@Composable
fun SearchResultCard(
    result: SearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val category = remember(result.fileType, result.fileName) {
        ThumbnailHelper.categorizeSearchResult(result)
    }
    val isPdf = remember(result.fileType, result.fileName) {
        result.fileType.equals("pdf", ignoreCase = true) ||
                Indexer.isPdfNameOrMime(result.fileName, null)
    }

    var previewData by remember(result.fileUri) {
        mutableStateOf(
            if (result.fileUri.isNotBlank()) ThumbnailHelper.getCachedPreview(result.fileUri) else null
        )
    }

    LaunchedEffect(result.fileUri) {
        if (previewData == null && result.fileUri.isNotBlank() && category != GalleryCategory.PHOTOS) {
            previewData = withContext(Dispatchers.IO) {
                ThumbnailHelper.loadPreviewBlocking(
                    context = context,
                    uriString = result.fileUri,
                    fileName = result.fileName,
                    mimeType = null
                )
            }
        }
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
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                when (category) {
                    GalleryCategory.PHOTOS -> {
                        if (result.fileUri.isNotBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(Uri.parse(result.fileUri))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = result.fileName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("🖼", fontSize = 28.sp)
                        }
                    }

                    GalleryCategory.VIDEOS -> {
                        val bmp = previewData?.bitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = result.fileName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("▶", color = Color.White, fontSize = 12.sp)
                        }
                    }

                    GalleryCategory.DOCUMENTS -> {
                        val bmp = previewData?.bitmap
                        if (isPdf && bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = result.fileName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFFFAFAFA))
                                    .padding(6.dp)
                            ) {
                                Text(
                                    text = result.snippet.ifBlank { previewData?.textSnippet ?: result.fileName },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 8.sp,
                                        lineHeight = 10.sp
                                    ),
                                    color = Color(0xFF212121),
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    GalleryCategory.AUDIO -> {
                        val bmp = previewData?.bitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = result.fileName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF4A148C), Color(0xFF7B1FA2))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("♫", color = Color.White, fontSize = 26.sp)
                            }
                        }
                    }

                    GalleryCategory.ALL -> {}
                }
            }

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
                val typeBadgeColor = when (category) {
                    GalleryCategory.PHOTOS -> BadgeImage
                    GalleryCategory.VIDEOS -> BadgeVideo
                    GalleryCategory.DOCUMENTS -> if (isPdf) BadgePdf else BadgeText
                    GalleryCategory.AUDIO -> BadgeAudio
                    GalleryCategory.ALL -> BadgeImage
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = typeBadgeColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = result.fileType.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = typeBadgeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }

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
