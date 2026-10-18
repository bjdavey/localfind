package com.localfind.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

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
 * In-app PDF Viewer Screen using Android's native [PdfRenderer].
 *
 * Implements ARCHITECTURE.md §2 & §8:
 * - 100% offline document rendering using built-in Android SDK `PdfRenderer`.
 * - Opens directly to the matched page ([initialPage]).
 * - Provides navigation controls (Previous/Next page, page count display).
 * - Caches document to private cache file to guarantee seekable descriptor access for [PdfRenderer].
 * - Safely recycles and disposes bitmaps and file descriptors on screen dismissal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    params: PdfViewerParams,
    onClose: () -> Unit,
    onSearchInFile: (() -> Unit)? = null,
    backHandlerEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Internal 1-based page tracking
    var currentPageNumber by remember { mutableIntStateOf(params.initialPage.coerceAtLeast(1)) }
    var totalPages by remember { mutableIntStateOf(0) }
    var renderedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // References held across recompositions for proper resource cleanup
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var tempFile by remember { mutableStateOf<File?>(null) }

    // Intercept hardware and gesture back navigation when active
    BackHandler(enabled = backHandlerEnabled) {
        onClose()
    }

    // Delay showing the loading spinner by 200ms so fast page renders don't flash a spinner
    var showLoadingSpinner by remember(params.uri) { mutableStateOf(false) }
    LaunchedEffect(isLoading, renderedBitmap) {
        if (isLoading && renderedBitmap == null) {
            delay(200)
            showLoadingSpinner = true
        } else {
            showLoadingSpinner = false
        }
    }

    // Initialize PdfRenderer from the document URI
    LaunchedEffect(params.uri) {
        isLoading = true
        errorMessage = null
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(params.uri)
                // Copy to cache dir to guarantee seekable ParcelFileDescriptor across all SAF providers
                val cacheFile = File(context.cacheDir, "pdf_preview_${System.currentTimeMillis()}.pdf")
                tempFile = cacheFile

                context.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalArgumentException("Could not open input stream for URI")

                val descriptor = ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = descriptor

                val renderer = PdfRenderer(descriptor)
                pdfRenderer = renderer
                totalPages = renderer.pageCount

                // Clamp initial page within valid range [1, totalPages]
                if (totalPages > 0) {
                    currentPageNumber = currentPageNumber.coerceIn(1, totalPages)
                }
            } catch (e: Exception) {
                errorMessage = "Failed to open PDF: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Render the active page whenever currentPageNumber or pdfRenderer changes
    LaunchedEffect(currentPageNumber, pdfRenderer) {
        val renderer = pdfRenderer ?: return@LaunchedEffect
        if (totalPages <= 0) return@LaunchedEffect

        isLoading = true
        withContext(Dispatchers.Default) {
            try {
                val zeroBasedIndex = (currentPageNumber - 1).coerceIn(0, totalPages - 1)
                val page = renderer.openPage(zeroBasedIndex)
                try {
                    // Render page at screen density for sharp text
                    val density = context.resources.displayMetrics.density.coerceIn(1.5f, 3f)
                    val width = (page.width * density).toInt().coerceAtLeast(1)
                    val height = (page.height * density).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    renderedBitmap = bitmap
                    errorMessage = null
                } finally {
                    page.close()
                }
            } catch (e: Exception) {
                errorMessage = "Failed to render page: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Clean up PdfRenderer, file descriptors, and temporary cache files when exiting
    DisposableEffect(params.uri) {
        onDispose {
            try {
                pdfRenderer?.close()
                pfd?.close()
                tempFile?.delete()
            } catch (_: Exception) {
                // Ignore cleanup errors during teardown
            }
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
                        if (totalPages > 0) {
                            Text(
                                text = "Page $currentPageNumber of $totalPages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Text(
                            text = "←",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                actions = {
                    if (onSearchInFile != null) {
                        IconButton(onClick = onSearchInFile) {
                            Text(
                                text = "🔍",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (totalPages > 0) {
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (currentPageNumber > 1) {
                                    currentPageNumber--
                                }
                            },
                            enabled = currentPageNumber > 1 && !isLoading
                        ) {
                            Text("Previous")
                        }

                        Text(
                            text = "$currentPageNumber / $totalPages",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        Button(
                            onClick = {
                                if (currentPageNumber < totalPages) {
                                    currentPageNumber++
                                }
                            },
                            enabled = currentPageNumber < totalPages && !isLoading
                        ) {
                            Text("Next")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading && renderedBitmap == null && showLoadingSpinner -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Loading PDF page...",
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

                renderedBitmap != null -> {
                    val verticalScroll = rememberScrollState()
                    val horizontalScroll = rememberScrollState()

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(verticalScroll)
                            .horizontalScroll(horizontalScroll),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = renderedBitmap!!.asImageBitmap(),
                            contentDescription = "PDF Page $currentPageNumber",
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                else -> {
                    Text(
                        text = "Document has no pages",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

