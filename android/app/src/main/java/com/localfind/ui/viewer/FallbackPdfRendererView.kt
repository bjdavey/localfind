package com.localfind.ui.viewer

import com.localfind.core.text.PdfPageMath
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.localfind.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Enhanced fallback PDF renderer using Android's built-in framework [PdfRenderer].
 *
 * Used automatically on devices without SDK extension level 13 (e.g. de-Googled ROMs,
 * offline devices, or older AOSP emulators) or as a resilient fallback if Jetpack PDF fails.
 * Includes interactive pinch-to-zoom, pan, and double-tap to reset/zoom.
 */
@Composable
fun FallbackPdfRendererView(
    params: PdfViewerParams,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentPageNumber by remember(params.uri, params.initialPage) {
        mutableIntStateOf(params.initialPage.coerceAtLeast(1))
    }
    var totalPages by remember { mutableIntStateOf(0) }
    var renderedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val rendererLock = remember { Any() }
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var tempFile by remember { mutableStateOf<File?>(null) }

    // Pinch-to-zoom and pan state for the rendered page
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Reset zoom when navigating across pages
    LaunchedEffect(currentPageNumber) {
        scale = 1f
        offset = Offset.Zero
    }

    // Keep currentPageNumber synchronized if params.initialPage changes externally
    LaunchedEffect(params.initialPage) {
        if (totalPages > 0) {
            currentPageNumber = PdfPageMath.clampPage(params.initialPage, totalPages)
        } else {
            currentPageNumber = params.initialPage.coerceAtLeast(1)
        }
    }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        val newScale = PdfPageMath.clampZoom(scale, zoomChange, minScale = 1f, maxScale = 4f)
        scale = newScale
        if (newScale <= 1.02f) {
            offset = Offset.Zero
        } else {
            offset += offsetChange
        }
    }

    // Delayed loading spinner to avoid flashing on rapid renders
    var showLoadingSpinner by remember(params.uri) { mutableStateOf(false) }
    LaunchedEffect(isLoading, renderedBitmap) {
        if (isLoading && renderedBitmap == null) {
            delay(200)
            showLoadingSpinner = true
        } else {
            showLoadingSpinner = false
        }
    }

    // Initialize PdfRenderer from URI
    LaunchedEffect(params.uri) {
        isLoading = true
        errorMessage = null
        withContext(Dispatchers.IO) {
            try {
                val uri = params.uri.toUri()
                var descriptor: ParcelFileDescriptor? = null
                var renderer: PdfRenderer? = null
                try {
                    val directPfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (directPfd != null) {
                        renderer = PdfRenderer(directPfd)
                        descriptor = directPfd
                    }
                } catch (_: Exception) {
                    descriptor?.close()
                    descriptor = null
                }

                if (renderer == null) {
                    val cacheFile = File(context.cacheDir, "pdf_preview_${System.currentTimeMillis()}.pdf")
                    tempFile = cacheFile
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            cacheFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: throw IllegalArgumentException("Could not open input stream for URI")
                        descriptor = ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        renderer = PdfRenderer(descriptor)
                    } catch (e: Exception) {
                        cacheFile.delete()
                        throw e
                    }
                }

                pfd = descriptor
                pdfRenderer = renderer
                totalPages = renderer.pageCount
                if (totalPages > 0) {
                    currentPageNumber = PdfPageMath.clampPage(params.initialPage, totalPages)
                }
            } catch (e: Exception) {
                errorMessage = "Failed to open PDF: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    // Render active page
    LaunchedEffect(currentPageNumber, pdfRenderer) {
        val renderer = pdfRenderer ?: return@LaunchedEffect
        if (totalPages <= 0) return@LaunchedEffect

        isLoading = true
        withContext(Dispatchers.Default) {
            try {
                val bitmap = synchronized(rendererLock) {
                    if (pdfRenderer !== renderer) return@withContext
                    val zeroBasedIndex = PdfPageMath.toZeroBasedPage(currentPageNumber, totalPages)
                    renderer.openPage(zeroBasedIndex).use { page ->
                        val density = context.resources.displayMetrics.density.coerceIn(1.5f, 3f)
                        val width = (page.width * density).toInt().coerceAtLeast(1)
                        val height = (page.height * density).toInt().coerceAtLeast(1)

                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        canvas.drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }

                renderedBitmap = bitmap
                errorMessage = null
            } catch (e: Exception) {
                errorMessage = "Failed to render page: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    DisposableEffect(params.uri) {
        onDispose {
            synchronized(rendererLock) {
                try {
                    val r = pdfRenderer
                    pdfRenderer = null
                    r?.close()
                    pfd?.close()
                    tempFile?.delete()
                } catch (_: Exception) {}
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (scale > 1.2f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2.5f
                                        }
                                    }
                                )
                            }
                            .transformable(state = transformState),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = renderedBitmap!!.asImageBitmap(),
                            contentDescription = "PDF Page $currentPageNumber",
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                                .padding(8.dp)
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

        // Bottom navigation bar for page controls
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
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
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
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_forward),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

