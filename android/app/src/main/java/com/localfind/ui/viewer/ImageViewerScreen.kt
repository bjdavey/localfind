package com.localfind.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.localfind.R
import com.localfind.data.FileOpener
import kotlinx.coroutines.delay

/**
 * Parameters used to open and display an image in the in-app image viewer.
 *
 * @property uri Document URI string from SAF or local file.
 * @property fileName Display name of the image file shown in the top bar.
 */
data class ImageViewerParams(
    val uri: String,
    val fileName: String
)

/**
 * In-app Image Viewer Screen styled as a gallery popup overlay with interactive zoom & pan.
 *
 * Implements ARCHITECTURE.md §2 & §8 and PRD.md §2:
 * - 100% offline image rendering directly from the persisted SAF document URI.
 * - Interactive pinch-to-zoom (up to 5x) and pan gestures.
 * - Double-tap gesture to quickly toggle between 1x and 2.5x zoom.
 * - Deep black gallery backdrop with translucent top bar.
 * - Flicker-free: delays progress spinner by 200ms so fast local bitmap loads render instantly without flashing.
 * - Tap background backdrop when unzoomed or back button to dismiss smoothly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    params: ImageViewerParams,
    onClose: () -> Unit,
    onSearchSimilar: ((uri: String, fileName: String) -> Unit)? = null,
    backHandlerEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var painterState by remember(params.uri) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }

    // Zoom and pan gesture state
    var scale by remember(params.uri) { mutableFloatStateOf(1f) }
    var offset by remember(params.uri) { mutableStateOf(Offset.Zero) }

    // Intercept hardware and gesture back navigation when active
    BackHandler(enabled = backHandlerEnabled) {
        onClose()
    }

    // Delay showing the progress spinner by 200ms so local image files decode and display
    // instantly without an annoying visual spinner flicker/flash
    var showLoadingSpinner by remember(params.uri) { mutableStateOf(false) }
    LaunchedEffect(painterState) {
        if (painterState is AsyncImagePainter.State.Loading) {
            delay(200)
            showLoadingSpinner = true
        } else {
            showLoadingSpinner = false
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xCC111111),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = {
                    Column {
                        Text(
                            text = params.fileName.ifEmpty { "Image Viewer" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (scale > 1.05f) {
                        IconButton(onClick = {
                            scale = 1f
                            offset = Offset.Zero
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "Reset zoom",
                                tint = Color.White
                            )
                        }
                    }
                    if (onSearchSimilar != null) {
                        IconButton(onClick = { onSearchSimilar(params.uri, params.fileName) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = "Find visually similar",
                                tint = Color.White
                            )
                        }
                    }
                    IconButton(onClick = {
                        FileOpener.openInExternalApp(context, params.uri, params.fileName)
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_open_in_new),
                            contentDescription = "Open in phone gallery or app",
                            tint = Color.White
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
                .background(Color.Black)
                .pointerInput(params.uri) {
                    detectTapGestures(
                        onTap = {
                            if (scale <= 1.05f) {
                                onClose()
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(params.uri.toUri())
                    .crossfade(150)
                    .build(),
                contentDescription = params.fileName.ifEmpty { "Matched image" },
                contentScale = ContentScale.Fit,
                onState = { state -> painterState = state },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(params.uri) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    .pointerInput(params.uri) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            if (newScale > 1f) {
                                offset += pan
                            } else {
                                offset = Offset.Zero
                            }
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .padding(8.dp)
            )

            when {
                painterState is AsyncImagePainter.State.Loading && showLoadingSpinner -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = "Loading image...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xCCFFFFFF)
                        )
                    }
                }

                painterState is AsyncImagePainter.State.Error -> {
                    val errorState = painterState as AsyncImagePainter.State.Error
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "Failed to load image: ${errorState.result.throwable.message ?: "Unknown error"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = onClose) {
                            Text("Go back")
                        }
                    }
                }

                else -> {
                    // Image loaded or decoding without spinner
                }
            }
        }
    }
}
