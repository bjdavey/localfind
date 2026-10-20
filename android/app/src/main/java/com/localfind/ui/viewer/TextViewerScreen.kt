package com.localfind.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.localfind.R
import com.localfind.data.FileOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Parameters used to open and navigate within the in-app text/markdown viewer.
 *
 * @property uri Document URI string from SAF.
 * @property fileName Name of the text file to display in the header.
 * @property targetOffset 0-indexed character offset where the matching passage begins (0 for whole file).
 * @property highlightSnippet Optional snippet string to highlight in the document text.
 */
data class TextViewerParams(
    val uri: String,
    val fileName: String,
    val targetOffset: Int = 0,
    val highlightSnippet: String? = null
)

/**
 * In-app Text and Markdown Viewer Screen.
 *
 * Implements ARCHITECTURE.md §8 and TODO.md:
 * - 100% offline rendering directly from the persisted SAF document URI.
 * - Displays the entire text file with line numbers and selectable text.
 * - Highlights the matched search passage and automatically scrolls to the passage offset.
 * - Also supports viewing full library documents without search highlighting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextViewerScreen(
    params: TextViewerParams,
    onClose: () -> Unit,
    onSearchInFile: (() -> Unit)? = null,
    backHandlerEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    var fullText by remember(params.uri) { mutableStateOf<String?>(null) }
    var isLoading by remember(params.uri) { mutableStateOf(true) }
    var errorMessage by remember(params.uri) { mutableStateOf<String?>(null) }

    // Intercept hardware and gesture back navigation when active
    BackHandler(enabled = backHandlerEnabled) {
        onClose()
    }

    // Delay showing the loading spinner by 200ms so fast local reads don't flash a spinner
    var showLoadingSpinner by remember(params.uri) { mutableStateOf(false) }
    LaunchedEffect(isLoading, fullText) {
        if (isLoading && fullText == null) {
            delay(200)
            showLoadingSpinner = true
        } else {
            showLoadingSpinner = false
        }
    }

    // Asynchronously read text content from SAF document URI on Dispatchers.IO
    LaunchedEffect(params.uri) {
        isLoading = true
        errorMessage = null
        withContext(Dispatchers.IO) {
            try {
                val uri = params.uri.toUri()
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                    it.readText()
                } ?: throw IllegalArgumentException("Could not open input stream for document URI")
                fullText = content
            } catch (e: Exception) {
                errorMessage = "Failed to open document: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Compute line data and highlight range when text is loaded
    val lines = remember(fullText) {
        fullText?.let { TextViewerHelper.computeLines(it) } ?: emptyList()
    }
    val targetLineIndex = remember(lines, params.targetOffset) {
        TextViewerHelper.findTargetLineIndex(lines, params.targetOffset)
    }
    val highlightRange = remember(fullText, params.targetOffset, params.highlightSnippet) {
        fullText?.let {
            TextViewerHelper.findHighlightRange(it, params.targetOffset, params.highlightSnippet)
        }
    }

    val listState = rememberLazyListState()

    // Automatically scroll to the matched passage line when content is ready
    LaunchedEffect(lines, targetLineIndex) {
        if (lines.isNotEmpty() && targetLineIndex > 0) {
            // Scroll so the matched line is displayed with a bit of context above it
            val scrollDestination = (targetLineIndex - 2).coerceAtLeast(0)
            listState.scrollToItem(scrollDestination)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = params.fileName.ifEmpty { "Text Viewer" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = when {
                            highlightRange != null || params.targetOffset > 0 ->
                                "Line ${targetLineIndex + 1} • Matching passage"
                            lines.isNotEmpty() ->
                                "${lines.size} lines"
                            else -> "Document"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Close"
                        )
                    }
                },
                actions = {
                    if (onSearchInFile != null) {
                        IconButton(onClick = onSearchInFile) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = "Search in file"
                            )
                        }
                    }
                    IconButton(onClick = {
                        FileOpener.openInExternalApp(context, params.uri, params.fileName)
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_open_in_new),
                            contentDescription = "Open in external text editor"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading && fullText == null && showLoadingSpinner -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Loading document...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                errorMessage != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = errorMessage ?: "Unknown error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = onClose) {
                            Text("Go back")
                        }
                    }
                }

                lines.isEmpty() -> {
                    Text(
                        text = "File is empty",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    val horizontalScrollState = rememberScrollState()
                    val highlightBg = MaterialTheme.colorScheme.primaryContainer
                    val highlightOnBg = MaterialTheme.colorScheme.onPrimaryContainer
                    val rowHighlightBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.20f)

                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(horizontalScrollState)
                                .padding(vertical = 8.dp)
                        ) {
                            items(lines, key = { it.lineIndex }) { line ->
                                val lineSubRange = TextViewerHelper.lineHighlightSubRange(line, highlightRange)
                                val isLineHighlighted = lineSubRange != null

                                val annotatedText = remember(line, lineSubRange, highlightBg, highlightOnBg) {
                                    buildAnnotatedString {
                                        val text = line.lineText
                                        if (lineSubRange != null) {
                                            val start = lineSubRange.first
                                            val end = lineSubRange.last + 1
                                            if (start > 0) {
                                                append(text.substring(0, start))
                                            }
                                            val highlightPart = text.substring(start, end.coerceAtMost(text.length))
                                            pushStyle(
                                                SpanStyle(
                                                    background = highlightBg,
                                                    color = highlightOnBg,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                            append(highlightPart)
                                            pop()
                                            if (end < text.length) {
                                                append(text.substring(end))
                                            }
                                        } else {
                                            append(text)
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (isLineHighlighted) {
                                                Modifier.background(rowHighlightBg)
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .padding(horizontal = 12.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    // Monospace line number gutter
                                    Text(
                                        text = "${line.lineIndex + 1}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = if (isLineHighlighted) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.width(42.dp)
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // Line text content
                                    Text(
                                        text = annotatedText,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
