package com.localfind.ui.viewer

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.localfind.data.FileAvailability
import com.localfind.data.FileOpener
import com.localfind.data.ImportedFile
import com.localfind.index.Indexer
import com.localfind.search.SearchResult

/**
 * Holds active state and exit-animation display caches for all 4 builtin viewers
 * ([ImageViewerScreen], [MediaPlayerScreen], [PdfViewerScreen], [TextViewerScreen]),
 * along with routing logic to open library files or search hits at their exact offset/page/timestamp.
 */
@Stable
class ViewerState {
    var activePdfViewer by mutableStateOf<PdfViewerParams?>(null)
    var displayedPdfViewer by mutableStateOf<PdfViewerParams?>(null)

    var activeImageViewer by mutableStateOf<ImageViewerParams?>(null)
    var displayedImageViewer by mutableStateOf<ImageViewerParams?>(null)

    var activeMediaPlayer by mutableStateOf<MediaPlayerParams?>(null)
    var displayedMediaPlayer by mutableStateOf<MediaPlayerParams?>(null)

    var activeTextViewer by mutableStateOf<TextViewerParams?>(null)
    var displayedTextViewer by mutableStateOf<TextViewerParams?>(null)

    /** True when any of the 4 builtin viewers is currently open. */
    val isAnyViewerOpen: Boolean
        get() = activePdfViewer != null ||
            activeImageViewer != null ||
            activeMediaPlayer != null ||
            activeTextViewer != null

    /** Dismisses all active viewers. */
    fun closeAll() {
        activePdfViewer = null
        activeImageViewer = null
        activeMediaPlayer = null
        activeTextViewer = null
    }

    /**
     * Opens a matched [SearchResult] at its exact offset/page/timestamp in the corresponding builtin viewer.
     */
    fun openSearchResult(
        context: Context,
        result: SearchResult,
        importedFiles: List<ImportedFile>,
        onMissingFile: (ImportedFile) -> Unit
    ) {
        if (result.fileUri.isBlank()) {
            Toast.makeText(context, "No file URI associated with result", Toast.LENGTH_SHORT).show()
            return
        }

        if (!FileAvailability.isFileAvailable(context, result.fileUri)) {
            val matchedFile = importedFiles.firstOrNull { it.uri == result.fileUri }
                ?: ImportedFile(
                    uri = result.fileUri,
                    displayName = result.fileName,
                    size = 0L,
                    mimeType = "*/*",
                    addedAt = 0L
                )
            onMissingFile(matchedFile)
            return
        }

        val isPdf = result.fileType.equals("pdf", ignoreCase = true) ||
            Indexer.isPdfNameOrMime(result.fileName, null)

        if (isPdf) {
            val targetPage = result.location.toInt().coerceAtLeast(1)
            val params = PdfViewerParams(
                uri = result.fileUri,
                fileName = result.fileName,
                initialPage = targetPage
            )
            activePdfViewer = params
            displayedPdfViewer = params
            return
        }

        val isImage = result.fileType.equals("image", ignoreCase = true) ||
            Indexer.isImageNameOrMime(result.fileName, null)

        if (isImage) {
            val params = ImageViewerParams(
                uri = result.fileUri,
                fileName = result.fileName
            )
            activeImageViewer = params
            displayedImageViewer = params
            return
        }

        val isPlayable = result.fileType.equals("audio", ignoreCase = true) ||
            result.fileType.equals("video", ignoreCase = true) ||
            Indexer.isAudioNameOrMime(result.fileName, null) ||
            Indexer.isVideoNameOrMime(result.fileName, null)

        if (isPlayable) {
            val params = MediaPlayerParams(
                uri = result.fileUri,
                fileName = result.fileName,
                startMs = result.location.coerceAtLeast(0L)
            )
            activeMediaPlayer = params
            displayedMediaPlayer = params
            return
        }

        val isText = result.fileType.equals("text", ignoreCase = true) ||
            Indexer.isTextNameOrMime(result.fileName, null)

        if (isText) {
            val params = TextViewerParams(
                uri = result.fileUri,
                fileName = result.fileName,
                targetOffset = result.location.toInt().coerceAtLeast(0),
                highlightSnippet = result.snippet
            )
            activeTextViewer = params
            displayedTextViewer = params
            return
        }

        FileOpener.openInExternalApp(context, result.fileUri, result.fileName)
    }

    /**
     * Opens an imported file from the Gallery grid in its corresponding builtin viewer.
     */
    fun openLibraryFile(
        context: Context,
        file: ImportedFile,
        onMissingFile: (ImportedFile) -> Unit
    ) {
        if (file.uri.isBlank()) {
            Toast.makeText(context, "No file URI associated with file", Toast.LENGTH_SHORT).show()
            return
        }

        if (!FileAvailability.isFileAvailable(context, file.uri)) {
            onMissingFile(file)
            return
        }

        if (Indexer.isPdfNameOrMime(file.displayName, file.mimeType)) {
            val params = PdfViewerParams(
                uri = file.uri,
                fileName = file.displayName,
                initialPage = 1
            )
            activePdfViewer = params
            displayedPdfViewer = params
            return
        }

        if (Indexer.isImageNameOrMime(file.displayName, file.mimeType)) {
            val params = ImageViewerParams(
                uri = file.uri,
                fileName = file.displayName
            )
            activeImageViewer = params
            displayedImageViewer = params
            return
        }

        if (Indexer.isAudioNameOrMime(file.displayName, file.mimeType) ||
            Indexer.isVideoNameOrMime(file.displayName, file.mimeType)
        ) {
            val params = MediaPlayerParams(
                uri = file.uri,
                fileName = file.displayName,
                startMs = 0L
            )
            activeMediaPlayer = params
            displayedMediaPlayer = params
            return
        }

        if (Indexer.isTextNameOrMime(file.displayName, file.mimeType)) {
            val params = TextViewerParams(
                uri = file.uri,
                fileName = file.displayName,
                targetOffset = 0,
                highlightSnippet = null
            )
            activeTextViewer = params
            displayedTextViewer = params
            return
        }

        FileOpener.openInExternalApp(context, file.uri, file.displayName, file.mimeType)
    }
}

/** Remembers a [ViewerState] instance across recompositions. */
@Composable
fun rememberViewerState(): ViewerState = remember { ViewerState() }

/**
 * Renders the animated full-screen overlays for all 4 builtin viewers
 * ([ImageViewerScreen], [MediaPlayerScreen], [PdfViewerScreen], [TextViewerScreen]).
 */
@Composable
fun ViewerOverlayHost(
    viewerState: ViewerState,
    importedFiles: List<ImportedFile>,
    onSearchInFile: (ImportedFile) -> Unit,
    onSearchSimilarImage: ((uri: String, fileName: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = remember(context) { ViewerOrientationHelper.findActivity(context) }

    // Dynamically unlock landscape orientation when any builtin file viewer is open,
    // and restore strictly to portrait orientation when viewing gallery, search, or settings.
    DisposableEffect(viewerState.isAnyViewerOpen) {
        ViewerOrientationHelper.applyOrientation(activity, viewerState.isAnyViewerOpen)
        onDispose {
            ViewerOrientationHelper.applyOrientation(activity, false)
        }
    }

    // 1. Image Viewer: Gallery app style popup transition
    AnimatedVisibility(
        visible = viewerState.activeImageViewer != null,
        enter = fadeIn(animationSpec = tween(200)) + scaleIn(
            initialScale = 0.90f,
            animationSpec = tween(240, easing = FastOutSlowInEasing)
        ),
        exit = fadeOut(animationSpec = tween(180)) + scaleOut(
            targetScale = 0.90f,
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        )
    ) {
        viewerState.displayedImageViewer?.let { params ->
            ImageViewerScreen(
                params = params,
                onClose = { viewerState.activeImageViewer = null },
                onSearchSimilar = if (onSearchSimilarImage != null) {
                    { uri, name ->
                        viewerState.activeImageViewer = null
                        onSearchSimilarImage(uri, name)
                    }
                } else null,
                backHandlerEnabled = viewerState.activeImageViewer != null
            )
        }
    }

    // 2. Media Player (Video & Audio): Gallery app style popup transition with smooth crossfade
    AnimatedVisibility(
        visible = viewerState.activeMediaPlayer != null,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(180))
    ) {
        viewerState.displayedMediaPlayer?.let { params ->
            MediaPlayerScreen(
                params = params,
                onClose = { viewerState.activeMediaPlayer = null },
                backHandlerEnabled = viewerState.activeMediaPlayer != null,
                onSearchInFile = {
                    val matchedFile = importedFiles.firstOrNull { it.uri == params.uri }
                        ?: ImportedFile(
                            uri = params.uri,
                            displayName = params.fileName,
                            size = 0L,
                            mimeType = "video/*",
                            addedAt = 0L
                        )
                    onSearchInFile(matchedFile)
                }
            )
        }
    }

    // 3. PDF Viewer: Smooth overlay over current screen
    AnimatedVisibility(
        visible = viewerState.activePdfViewer != null,
        enter = fadeIn(animationSpec = tween(200)) + slideInVertically(
            initialOffsetY = { (it * 0.06f).toInt() },
            animationSpec = tween(240, easing = FastOutSlowInEasing)
        ),
        exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(
            targetOffsetY = { (it * 0.06f).toInt() },
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        )
    ) {
        viewerState.displayedPdfViewer?.let { params ->
            PdfViewerScreen(
                params = params,
                onClose = { viewerState.activePdfViewer = null },
                backHandlerEnabled = viewerState.activePdfViewer != null,
                onSearchInFile = {
                    val matchedFile = importedFiles.firstOrNull { it.uri == params.uri }
                        ?: ImportedFile(
                            uri = params.uri,
                            displayName = params.fileName,
                            size = 0L,
                            mimeType = "application/pdf",
                            addedAt = 0L
                        )
                    onSearchInFile(matchedFile)
                }
            )
        }
    }

    // 4. Text Viewer: Smooth overlay over current screen
    AnimatedVisibility(
        visible = viewerState.activeTextViewer != null,
        enter = fadeIn(animationSpec = tween(200)) + slideInVertically(
            initialOffsetY = { (it * 0.06f).toInt() },
            animationSpec = tween(240, easing = FastOutSlowInEasing)
        ),
        exit = fadeOut(animationSpec = tween(180)) + slideOutVertically(
            targetOffsetY = { (it * 0.06f).toInt() },
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        )
    ) {
        viewerState.displayedTextViewer?.let { params ->
            TextViewerScreen(
                params = params,
                onClose = { viewerState.activeTextViewer = null },
                backHandlerEnabled = viewerState.activeTextViewer != null,
                onSearchInFile = {
                    val matchedFile = importedFiles.firstOrNull { it.uri == params.uri }
                        ?: ImportedFile(
                            uri = params.uri,
                            displayName = params.fileName,
                            size = 0L,
                            mimeType = "text/plain",
                            addedAt = 0L
                        )
                    onSearchInFile(matchedFile)
                }
            )
        }
    }
}
