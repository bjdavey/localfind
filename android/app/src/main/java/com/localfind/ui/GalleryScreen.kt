package com.localfind.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.localfind.R
import com.localfind.data.ImportedFile
import com.localfind.data.IndexingStatus
import com.localfind.data.ModelStatus
import com.localfind.data.formatFileSize
import com.localfind.index.Indexer
import com.localfind.index.IndexingProgress
import com.localfind.ui.theme.BadgeAudio
import com.localfind.ui.theme.BadgeImage
import com.localfind.ui.theme.BadgePdf
import com.localfind.ui.theme.BadgeText
import com.localfind.ui.theme.BadgeVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google Photos-inspired Gallery Home Screen supporting Images, Videos, PDFs, Text Documents, and Audio.
 *
 * Key UX Features:
 * 1. **Top Header**: Displays app title, indexed library statistics, and top-right Settings icon.
 * 2. **Per-File Progress**: Displays live progress indicator per file and queue status for background WorkManager indexing.
 * 3. **Category Filter Chips**: Instant filtering across `All`, `Photos`, `Videos`, `Docs`, and `Audio`.
 * 4. **2-Column Visual Grid**: Rich thumbnails for every modality.
 * 5. **Option C Unified Bottom Floating Dock**: Single floating pill combining search and import.
 */
@Composable
fun GalleryScreen(
    importedFiles: List<ImportedFile>,
    indexedChunksCount: Int,
    isIndexing: Boolean,
    statusMessage: String,
    indexingProgress: IndexingProgress = IndexingProgress(),
    modelStatus: ModelStatus = ModelStatus.Loading,
    onRetryLoadModel: () -> Unit = {},
    onOpenSearch: () -> Unit,
    onImportFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onFileClick: (ImportedFile) -> Unit,
    onRequestDeleteFile: (ImportedFile) -> Unit,
    onRetryIndexFile: ((ImportedFile) -> Unit)? = null,
    onCancelIndexing: (() -> Unit)? = null,
    onCancelIndexFile: ((ImportedFile) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(GalleryCategory.ALL) }
    var inspectedFile by remember { mutableStateOf<ImportedFile?>(null) }

    val filteredFiles = remember(importedFiles, selectedCategory) {
        ThumbnailHelper.filterFiles(importedFiles, selectedCategory)
    }

    // File Details & Quick Actions Modal when long-pressing or tapping info on a tile
    inspectedFile?.let { file ->
        FileDetailsDialog(
            file = file,
            isIndexing = isIndexing || indexingProgress.isIndexing,
            onDismiss = { inspectedFile = null },
            onOpen = {
                val target = file
                inspectedFile = null
                onFileClick(target)
            },
            onDelete = {
                val target = file
                inspectedFile = null
                onRequestDeleteFile(target)
            },
            onRetry = onRetryIndexFile?.let { retryFn ->
                {
                    val target = file
                    inspectedFile = null
                    retryFn(target)
                }
            },
            onCancelSync = onCancelIndexFile?.let { cancelFn ->
                {
                    val target = file
                    inspectedFile = null
                    cancelFn(target)
                }
            }
        )
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding())
        ) {
            // =====================================================================
            // 1. TOP HEADER BAR (App Title + Stats + Top-Right Settings)
            // =====================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LocalFind",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "${importedFiles.size} files • $indexedChunksCount indexed chunks • 100% offline",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onOpenSettings) {
                    Text(
                        text = "⚙",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // =====================================================================
            // 1.5. MODEL STATUS & ERROR BANNER
            // =====================================================================
            var showModelHelpDialog by remember { mutableStateOf(false) }

            if (showModelHelpDialog && modelStatus is ModelStatus.Error) {
                AlertDialog(
                    onDismissRequest = { showModelHelpDialog = false },
                    title = { Text("Embedding Model Not Loaded") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(modelStatus.message)
                            if (modelStatus.instructions.isNotBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = modelStatus.instructions,
                                        modifier = Modifier.padding(10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showModelHelpDialog = false
                                onRetryLoadModel()
                            }
                        ) {
                            Text("Retry Loading")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showModelHelpDialog = false }) {
                            Text("Close")
                        }
                    }
                )
            }

            AnimatedVisibility(
                visible = modelStatus is ModelStatus.Error,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (modelStatus is ModelStatus.Error) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clickable { showModelHelpDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "⚠️",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Model not loaded",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = modelStatus.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            FilledTonalButton(
                                onClick = onRetryLoadModel,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Retry", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // =====================================================================
            // 2. LIVE INDEXING PROGRESS BANNER
            // =====================================================================
            AnimatedVisibility(
                visible = isIndexing || indexingProgress.isIndexing || statusMessage.startsWith("Indexing") || statusMessage.startsWith("Importing"),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val bannerText = if (indexingProgress.isIndexing && indexingProgress.message.isNotBlank()) {
                                indexingProgress.message
                            } else {
                                statusMessage
                            }
                            Text(
                                text = bannerText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            if (indexingProgress.isIndexing && indexingProgress.progressFraction > 0f) {
                                LinearProgressIndicator(
                                    progress = { indexingProgress.progressFraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                            }
                        }
                        if (onCancelIndexing != null) {
                            Spacer(modifier = Modifier.width(10.dp))
                            TextButton(
                                onClick = onCancelIndexing,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    text = "Cancel",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // =====================================================================
            // 3. CATEGORY FILTER CHIPS ROW
            // =====================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GalleryCategory.entries.forEach { category ->
                    val count = remember(importedFiles, category) {
                        ThumbnailHelper.filterFiles(importedFiles, category).size
                    }
                    val isSelected = selectedCategory == category
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = category },
                        label = {
                            Text(
                                text = if (count > 0) "${category.icon} ${category.label} ($count)" else "${category.icon} ${category.label}",
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

            // =====================================================================
            // 4. GALLERY GRID OR EMPTY STATE
            // =====================================================================
            if (importedFiles.isEmpty()) {
                EmptyGalleryState(
                    onImportFiles = onImportFiles,
                    isIndexing = isIndexing,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else if (filteredFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = selectedCategory.icon,
                            fontSize = 40.sp
                        )
                        Text(
                            text = "No ${selectedCategory.label.lowercase()} in your library yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Tap the + button in the bottom bar to import files.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 6.dp,
                        // Extra bottom padding so the floating dock never obscures the bottom row
                        bottom = 104.dp + navBarPadding.calculateBottomPadding()
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = filteredFiles,
                        key = { it.uri }
                    ) { file ->
                        val isActivelyIndexing = file.indexingStatus == IndexingStatus.INDEXING ||
                                (indexingProgress.isIndexing && indexingProgress.currentUri == file.uri)
                        val activeFraction = if (indexingProgress.currentUri == file.uri) {
                            indexingProgress.progressFraction
                        } else 0f

                        MediaGalleryTile(
                            file = file,
                            isActivelyIndexing = isActivelyIndexing,
                            activeProgressFraction = activeFraction,
                            onClick = { onFileClick(file) },
                            onLongClick = { inspectedFile = file },
                            onCancelIndex = onCancelIndexFile?.let { { it(file) } }
                        )
                    }
                }
            }
        }

        // =====================================================================
        // 5. OPTION C UNIFIED BOTTOM FLOATING DOCK (Search Pill + Import Button)
        // =====================================================================
        FloatingSearchAndImportDock(
            onSearchClick = onOpenSearch,
            onImportClick = onImportFiles,
            isIndexing = isIndexing,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 16.dp + navBarPadding.calculateBottomPadding()
                )
        )
    }
}

/**
 * Option C Unified Bottom Floating Dock:
 * Combines the `[ 🔍 Search files by meaning... ]` trigger on the left with a dedicated
 * `[ + ]` Import button on the right inside one sleek floating pill container.
 */
@Composable
fun FloatingSearchAndImportDock(
    onSearchClick: () -> Unit,
    onImportClick: () -> Unit,
    isIndexing: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(30.dp),
                clip = false
            ),
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(30.dp)
                )
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Dedicated Search Area that opens the Full-Screen Search View
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .clickable(onClick = onSearchClick)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Search files by meaning...",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Subtle vertical divider separating Search from Import
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(26.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            )

            Spacer(modifier = Modifier.width(6.dp))

            // Right: Dedicated Import Button (+)
            Surface(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !isIndexing, onClick = onImportClick),
                shape = CircleShape,
                color = if (isIndexing) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.primary
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "+",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isIndexing) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        }
                    )
                }
            }
        }
    }
}

/**
 * Individual square visual tile in the 2-column gallery grid.
 *
 * Renders a rich visual thumbnail tailored to the file's modality:
 * - **Photos**: Full-bleed Coil [AsyncImage].
 * - **Videos**: Extracted video keyframe + centered translucent play icon + duration pill.
 * - **PDFs**: Rendered Page 1 bitmap on white paper + page count pill.
 * - **Text / Markdown**: Miniature document sheet showing the actual first lines of text.
 * - **Audio**: Embedded cover art or stylized waveform bars + duration pill.
 * - **Progress Overlay**: Live determinate/indeterminate progress indicator during background indexing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGalleryTile(
    file: ImportedFile,
    isActivelyIndexing: Boolean = false,
    activeProgressFraction: Float = 0f,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCancelIndex: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val category = remember(file.displayName, file.mimeType) {
        ThumbnailHelper.categorizeFile(file.displayName, file.mimeType)
    }
    val isPdf = remember(file.displayName, file.mimeType) {
        Indexer.isPdfNameOrMime(file.displayName, file.mimeType)
    }

    var previewData by remember(file.uri) {
        mutableStateOf(ThumbnailHelper.getCachedPreview(file.uri))
    }

    LaunchedEffect(file.uri) {
        if (previewData == null && category != GalleryCategory.PHOTOS) {
            previewData = withContext(Dispatchers.IO) {
                ThumbnailHelper.loadPreviewBlocking(
                    context = context,
                    uriString = file.uri,
                    fileName = file.displayName,
                    mimeType = file.mimeType
                )
            }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // -----------------------------------------------------------------
            // 1. BACKGROUND VISUAL PREVIEW BY FILE TYPE
            // -----------------------------------------------------------------
            when (category) {
                GalleryCategory.PHOTOS -> {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(Uri.parse(file.uri))
                            .crossfade(true)
                            .build(),
                        contentDescription = file.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                GalleryCategory.VIDEOS -> {
                    val bitmap = previewData?.bitmap
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = file.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF1A1F2C), Color(0xFF2D3748))
                                    )
                                )
                        )
                    }
                    // Centered Play Button Circle
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "▶",
                            color = Color.White,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(start = 2.dp)
                        )
                    }
                }

                GalleryCategory.DOCUMENTS -> {
                    if (isPdf) {
                        // PDF Page 1 Thumbnail framed as a document sheet with top alignment
                        val bitmap = previewData?.bitmap
                        if (bitmap != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF263238), Color(0xFF37474F))
                                        )
                                    )
                                    .padding(top = 36.dp, start = 10.dp, end = 10.dp, bottom = 10.dp),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
                                    color = Color.White,
                                    shadowElevation = 2.dp
                                ) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = file.displayName,
                                        contentScale = ContentScale.Crop,
                                        alignment = Alignment.TopCenter,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(top = 4.dp, start = 4.dp, end = 4.dp)
                                    )
                                }
                            }
                        } else {
                            DocumentPlaceholderSheet(
                                title = file.displayName,
                                snippet = "PDF Document\nTap to view pages",
                                isPdf = true
                            )
                        }
                    } else {
                        // Text / Markdown First Lines Miniature Document Sheet
                        DocumentPlaceholderSheet(
                            title = file.displayName,
                            snippet = previewData?.textSnippet ?: "Loading preview…",
                            isPdf = false
                        )
                    }
                }

                GalleryCategory.AUDIO -> {
                    val bitmap = previewData?.bitmap
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = file.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AudioWaveformPlaceholder()
                    }
                }

                GalleryCategory.ALL -> {}
            }

            // -----------------------------------------------------------------
            // 2. ACTIVE INDEXING OVERLAY (determinate spinner + percent)
            // -----------------------------------------------------------------
            if (isActivelyIndexing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.52f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (activeProgressFraction > 0f) {
                            Box(contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = { activeProgressFraction },
                                    modifier = Modifier.size(44.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.5.dp
                                )
                                Text(
                                    text = "${(activeProgressFraction * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                        }
                        Text(
                            text = "Indexing...",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        if (onCancelIndex != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.65f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(onClick = onCancelIndex)
                            ) {
                                Text(
                                    text = "Cancel",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF8A80),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // -----------------------------------------------------------------
            // 3. TOP-LEFT & TOP-RIGHT BADGES (Type Badge + Status Pill + Menu)
            // -----------------------------------------------------------------
            val badgeColor = when (category) {
                GalleryCategory.PHOTOS -> BadgeImage
                GalleryCategory.VIDEOS -> BadgeVideo
                GalleryCategory.DOCUMENTS -> if (isPdf) BadgePdf else BadgeText
                GalleryCategory.AUDIO -> BadgeAudio
                GalleryCategory.ALL -> BadgeImage
            }

            val badgeLabel = when (category) {
                GalleryCategory.PHOTOS -> "IMG"
                GalleryCategory.VIDEOS -> previewData?.badgeText?.let { "▶ $it" } ?: "VIDEO"
                GalleryCategory.DOCUMENTS -> previewData?.badgeText ?: if (isPdf) "PDF" else "TXT"
                GalleryCategory.AUDIO -> previewData?.badgeText?.let { "♫ $it" } ?: "AUDIO"
                GalleryCategory.ALL -> ""
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Modality / Duration / Page Count Pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeColor.copy(alpha = 0.90f)
                    ) {
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }

                    // Background Queue or Failure Status Chip
                    if (file.indexingStatus == IndexingStatus.PENDING && !isActivelyIndexing) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = 0.70f)
                        ) {
                            Text(
                                text = "⏳ Queue",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    } else if (file.indexingStatus == IndexingStatus.FAILED && !isActivelyIndexing) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.90f)
                        ) {
                            Text(
                                text = "⚠️ Failed",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                // Quick actions menu button (opens file info / delete dialog)
                Surface(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onLongClick),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.45f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "⋮",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // -----------------------------------------------------------------
            // 4. BOTTOM GRADIENT SCRIM WITH FILE NAME, SIZE & STATUS
            // -----------------------------------------------------------------
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.65f),
                                Color.Black.copy(alpha = 0.88f)
                            )
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column {
                    Text(
                        text = file.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = when {
                        isActivelyIndexing && activeProgressFraction > 0f ->
                            "Indexing ${(activeProgressFraction * 100).toInt()}% • ${formatFileSize(file.size)}"
                        isActivelyIndexing ->
                            "Indexing... • ${formatFileSize(file.size)}"
                        file.indexingStatus == IndexingStatus.PENDING ->
                            "In queue • ${formatFileSize(file.size)}"
                        file.indexingStatus == IndexingStatus.FAILED ->
                            "Indexing failed • ${formatFileSize(file.size)}"
                        file.chunksCount > 0 ->
                            "${formatFileSize(file.size)} • ${file.chunksCount} chunks"
                        else ->
                            formatFileSize(file.size)
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Miniature document page preview card displaying the first lines of a `.txt` or `.md` file
 * on a realistic paper-like surface so documents are visually recognizable in the gallery grid.
 */
@Composable
private fun DocumentPlaceholderSheet(
    title: String,
    snippet: String,
    isPdf: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF263238), Color(0xFF37474F))
                )
            )
            .padding(top = 36.dp, start = 10.dp, end = 10.dp, bottom = 10.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
            color = Color(0xFFFAFAFA),
            shadowElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                // Decorative header accent line
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isPdf) BadgePdf else BadgeText)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    ),
                    color = Color(0xFF212121),
                    maxLines = 7,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Stylized audio card for audio files without embedded album art.
 */
@Composable
private fun AudioWaveformPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF311B92), Color(0xFF6A1B9A), Color(0xFF4A148C))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            val heights = listOf(18.dp, 34.dp, 24.dp, 46.dp, 30.dp, 40.dp, 20.dp, 28.dp, 16.dp)
            heights.forEach { barHeight ->
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .height(barHeight)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.75f))
                )
            }
        }
    }
}

/**
 * Welcoming Empty Gallery State when no files have been imported yet.
 */
@Composable
private fun EmptyGalleryState(
    onImportFiles: () -> Unit,
    isIndexing: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "🖼  🎬  📄  🎵",
                    fontSize = 32.sp
                )
                Text(
                    text = "Your Visual Library is Empty",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Import photos, videos, PDFs, text documents, or audio clips to browse and search them by meaning — 100% offline on your device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onImportFiles,
                    enabled = !isIndexing,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = "+  Import Files",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Dialog displaying file metadata and providing quick actions ("Open", "Remove from Library").
 */
@Composable
private fun FileDetailsDialog(
    file: ImportedFile,
    isIndexing: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onCancelSync: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = file.displayName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Size: ${formatFileSize(file.size)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!file.mimeType.isNullOrBlank()) {
                    Text(
                        text = "Type: ${file.mimeType}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "Category: ${ThumbnailHelper.categorizeFile(file.displayName, file.mimeType).label}",
                    style = MaterialTheme.typography.bodyMedium
                )
                val statusText = when (file.indexingStatus) {
                    IndexingStatus.INDEXED -> "Indexed (${file.chunksCount} chunks)"
                    IndexingStatus.INDEXING -> "Indexing in background..."
                    IndexingStatus.PENDING -> "Waiting in background queue"
                    IndexingStatus.FAILED -> "Indexing failed / cancelled"
                }
                Text(
                    text = "Index Status: $statusText",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when (file.indexingStatus) {
                        IndexingStatus.FAILED -> MaterialTheme.colorScheme.error
                        IndexingStatus.INDEXED -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (file.indexingStatus == IndexingStatus.FAILED && onRetry != null) {
                    Button(
                        onClick = onRetry
                    ) {
                        Text("Retry Index")
                    }
                }
                if ((file.indexingStatus == IndexingStatus.INDEXING || file.indexingStatus == IndexingStatus.PENDING) && onCancelSync != null) {
                    FilledTonalButton(
                        onClick = onCancelSync
                    ) {
                        Text("Cancel Sync", color = MaterialTheme.colorScheme.error)
                    }
                }
                Button(onClick = onOpen) {
                    Text("Open File")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onDelete
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}
