package com.localfind.ui.viewer

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.localfind.index.Indexer
import com.localfind.R
import com.localfind.data.FileOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
 * - Hardware-accelerated animated GIF playback via Android's native [ImageDecoder] & [AnimatedImageDrawable].
 * - Deep black gallery backdrop with translucent top bar.
 * - Flicker-free: delays progress spinner by 200ms so fast local bitmap loads render instantly without flashing.
 * - Tap background backdrop when unzoomed or back button to dismiss smoothly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    params: ImageViewerParams,
    onClose: () -> Unit,
    onSearchSimilar: (uri: String, fileName: String) -> Unit,
    backHandlerEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Detect if this image is a GIF (animated or static)
    val isGif = remember(params.fileName, params.uri) {
        Indexer.isGifNameOrMime(params.fileName, null) ||
            Indexer.isGifNameOrMime(
                "",
                try { context.contentResolver.getType(params.uri.toUri()) } catch (_: Exception) { null }
            )
    }

    // GIF animation state using Android's native ImageDecoder / AnimatedImageDrawable
    var gifDrawable by remember(params.uri) { mutableStateOf<Drawable?>(null) }
    var gifLoading by remember(params.uri) { mutableStateOf(isGif) }
    var gifError by remember(params.uri) { mutableStateOf<String?>(null) }

    // Static image state for Coil AsyncImage
    var painterState by remember(params.uri) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }

    // Load animated GIF natively via ImageDecoder on IO dispatcher
    LaunchedEffect(params.uri, isGif) {
        if (isGif) {
            gifLoading = true
            gifError = null
            withContext(Dispatchers.IO) {
                try {
                    val source = ImageDecoder.createSource(context.contentResolver, params.uri.toUri())
                    val drawable = ImageDecoder.decodeDrawable(source)
                    withContext(Dispatchers.Main) {
                        gifDrawable = drawable
                        gifLoading = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        gifError = e.message ?: "Failed to decode GIF"
                        gifLoading = false
                    }
                }
            }
        }
    }

    // Stop animated GIF loop when viewer is closed to save battery and GPU cycles
    DisposableEffect(gifDrawable) {
        onDispose {
            (gifDrawable as? Animatable)?.stop()
        }
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
    val isActivelyLoading = if (isGif) gifLoading else (painterState is AsyncImagePainter.State.Loading)

    LaunchedEffect(isActivelyLoading) {
        if (isActivelyLoading) {
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
                    IconButton(onClick = { onSearchSimilar(params.uri, params.fileName) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = "Search by image",
                            tint = Color.White
                        )
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
            val gestureModifier = Modifier
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
                        val newScale = PdfViewerHelper.clampZoom(scale, zoom, maxScale = 5f)
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

            if (isGif && gifDrawable != null) {
                // Native hardware-accelerated animated GIF rendering via AnimatedImageDrawable
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            isClickable = false
                            isFocusable = false
                            setImageDrawable(gifDrawable)
                            (gifDrawable as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                                override fun onViewAttachedToWindow(v: android.view.View) {
                                    (drawable as? Animatable)?.let { anim ->
                                        if (!anim.isRunning) anim.start()
                                    }
                                }
                                override fun onViewDetachedFromWindow(v: android.view.View) {
                                    (drawable as? Animatable)?.stop()
                                }
                            })
                            post {
                                (drawable as? Animatable)?.let { anim ->
                                    if (!anim.isRunning) anim.start()
                                }
                            }
                        }
                    },
                    update = { view ->
                        if (view.drawable != gifDrawable) {
                            (view.drawable as? Animatable)?.stop()
                            view.setImageDrawable(gifDrawable)
                            (gifDrawable as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            view.post {
                                (view.drawable as? Animatable)?.let { anim ->
                                    if (!anim.isRunning) anim.start()
                                }
                            }
                        } else {
                            (view.drawable as? Animatable)?.let { anim ->
                                if (!anim.isRunning) anim.start()
                            }
                        }
                    },
                    modifier = gestureModifier
                )
            } else if (!isGif) {
                // High-performance static image rendering via Coil AsyncImage
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(params.uri.toUri())
                        .crossfade(150)
                        .build(),
                    contentDescription = params.fileName.ifEmpty { "Matched image" },
                    contentScale = ContentScale.Fit,
                    onState = { state -> painterState = state },
                    modifier = gestureModifier
                )
            }

            val hasError = if (isGif) gifError != null else (painterState is AsyncImagePainter.State.Error)
            val errorMessage = if (isGif) {
                gifError
            } else {
                (painterState as? AsyncImagePainter.State.Error)?.result?.throwable?.message
            }

            when {
                isActivelyLoading && showLoadingSpinner -> {
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

                hasError -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "Failed to load image: ${errorMessage ?: "Unknown error"}",
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

