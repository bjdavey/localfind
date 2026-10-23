package com.localfind.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.fragment.compose.AndroidFragment
import androidx.pdf.ExperimentalPdfApi
import com.localfind.R
import com.localfind.data.FileOpener

/**
 * Parameters used to open and navigate within the PDF viewer.
 *
 * @property uri Document URI string from SAF.
 * @property fileName Name of the PDF file to display in the header.
 * @property initialPage 1-based page number where the viewer should start.
 */
data class PdfViewerParams(
    val uri: String,
    val fileName: String,
    val initialPage: Int = 1
)

/**
 * Modern PDF Viewer Screen powered primarily by the official AndroidX Jetpack PDF library
 * ([LocalFindPdfViewerFragment]), with seamless automatic fallback to [FallbackPdfRendererView]
 * on environments where SDK extension requirements (level 13+) are not met.
 *
 * Implements:
 * - 100% offline, native PDF rendering with zero cloud dependencies.
 * - Official Jetpack PDF: fluid pinch-to-zoom, fling scrolling, text selection, and in-doc search.
 * - Programmatic jump directly to the target matched page ([initialPage]).
 * - Dual search controls: in-document text search ([LocalFindPdfViewerFragment.isTextSearchActive])
 *   and LocalFind AI semantic search ([onSearchInFile]).
 * - Resilient fallback to [FallbackPdfRendererView] for de-Googled ROMs or older emulators.
 * - External PDF reader launch support via [FileOpener.openInExternalApp].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPdfApi::class)
@Composable
fun PdfViewerScreen(
    params: PdfViewerParams,
    onClose: () -> Unit,
    onSearchInFile: (() -> Unit)? = null,
    backHandlerEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Check whether the device supports official AndroidX Jetpack PDF (Android S + Extension 13+)
    val isJetpackSupported = remember { PdfViewerHelper.isJetpackPdfSupported() }
    var useFallbackRenderer by remember { mutableStateOf(!isJetpackSupported) }

    // State for Jetpack PDF Viewer
    var fragmentRef by remember { mutableStateOf<LocalFindPdfViewerFragment?>(null) }
    var isTextSearchActive by remember { mutableStateOf(false) }
    var isDocumentLoading by remember(params.uri) { mutableStateOf(true) }

    // Keep Jetpack PDF fragment's target page updated if params change
    LaunchedEffect(params.initialPage, fragmentRef) {
        fragmentRef?.let { fragment ->
            fragment.initialPage = params.initialPage
        }
    }

    // Intercept back navigation: if in-document search is active, dismiss search first; otherwise close viewer
    BackHandler(enabled = backHandlerEnabled) {
        if (isTextSearchActive) {
            fragmentRef?.let { fragment ->
                fragment.isTextSearchActive = false
                isTextSearchActive = false
            } ?: onClose()
        } else {
            onClose()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = params.fileName.ifEmpty { "PDF Viewer" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (useFallbackRenderer) "PDF Viewer (Native Engine)" else "PDF Viewer",
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
                    // 1. In-document keyword text search toggle (Jetpack PDF only)
                    if (!useFallbackRenderer) {
                        IconButton(onClick = {
                            fragmentRef?.let { fragment ->
                                val next = !fragment.isTextSearchActive
                                fragment.isTextSearchActive = next
                                isTextSearchActive = next
                            }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = "Find text in document",
                                tint = if (isTextSearchActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }

                    // 2. LocalFind AI semantic search in this document
                    if (onSearchInFile != null) {
                        IconButton(onClick = onSearchInFile) {
                            Icon(
                                painter = painterResource(R.drawable.ic_auto_awesome),
                                contentDescription = "Semantic AI search in file"
                            )
                        }
                    }

                    // 3. Open in external PDF reader app
                    IconButton(onClick = {
                        FileOpener.openInExternalApp(context, params.uri, params.fileName)
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_open_in_new),
                            contentDescription = "Open in external PDF reader"
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
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val parsedDocUri = remember(params.uri) {
                try {
                    params.uri.toUri()
                } catch (_: Exception) {
                    null
                }
            }

            if (!useFallbackRenderer && parsedDocUri != null) {
                // Primary: Official AndroidX Jetpack PDF Viewer Fragment
                Box(modifier = Modifier.fillMaxSize()) {
                    key(params.uri) {
                        AndroidFragment<LocalFindPdfViewerFragment>(
                            modifier = Modifier.fillMaxSize(),
                            arguments = remember(parsedDocUri, params.initialPage) {
                                bundleOf(
                                    "documentUri" to parsedDocUri,
                                    "initialPage" to params.initialPage
                                )
                            }
                        ) { fragment ->
                            // initialPage comes from the arguments bundle on creation and from the
                            // LaunchedEffect above whenever params change
                            fragmentRef = fragment
                            fragment.documentUri = parsedDocUri
                            fragment.onLoadSuccess = {
                                isDocumentLoading = false
                            }
                            fragment.onLoadError = {
                                isDocumentLoading = false
                                // If Jetpack PDF fails on device, gracefully fall back to native PdfRenderer
                                useFallbackRenderer = true
                            }
                        }
                    }

                    if (isDocumentLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = "Loading PDF...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                // Fallback: Enhanced native Android SDK PdfRenderer with pinch-to-zoom
                key(params.uri) {
                    FallbackPdfRendererView(
                        params = params,
                        onClose = onClose
                    )
                }
            }
        }
    }
}
