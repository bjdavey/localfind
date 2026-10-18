package com.localfind.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.localfind.index.Indexer

/**
 * Parameters used to open the in-app audio/video player.
 *
 * @property uri Document URI string of the media file.
 * @property fileName Display name shown in the top bar.
 * @property startMs Position to seek to before playing (the matching chunk's start time).
 */
data class MediaPlayerParams(
    val uri: String,
    val fileName: String,
    val startMs: Long
)

/**
 * In-app player styled as a gallery popup overlay using Media3 ExoPlayer + [PlayerView] (ARCHITECTURE.md §8).
 *
 * Plays directly from the linked SAF URI, seeks to [MediaPlayerParams.startMs] and starts playing.
 * - Deep black gallery backdrop with translucent top bar.
 * - Shutter background set to black to avoid any white flash/flicker while surface initializes.
 * - Controls auto-hide for videos and stay visible for audio tracks.
 * - The player is cleanly released when the screen leaves composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPlayerScreen(
    params: MediaPlayerParams,
    onClose: () -> Unit,
    onSearchInFile: (() -> Unit)? = null,
    backHandlerEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val player = remember(params.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(params.uri)), params.startMs)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose {
            player.stop()
            player.release()
        }
    }

    // Immediately stop playback when the user closes the player so audio does not leak during exit animations
    val handleClose = {
        player.stop()
        onClose()
    }

    BackHandler(enabled = backHandlerEnabled) { handleClose() }

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
                            text = params.fileName.ifEmpty { "Player" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val subtitle = if (params.startMs > 0L) {
                            "From ${SearchResultFormatter.formatTimeMs(params.startMs)}"
                        } else {
                            "Full playback • 00:00"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xCCFFFFFF)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = handleClose) {
                        Text(text = "←", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    }
                },
                actions = {
                    if (onSearchInFile != null) {
                        IconButton(onClick = onSearchInFile) {
                            Text(text = "🔍", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        // Note: Do NOT set an opaque background on PlayerView or the AndroidView modifier.
        // PlayerView hosts an underlying SurfaceView; drawing an opaque background on top covers the video punch-through hole.
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    val isAudio = Indexer.isAudioNameOrMime(params.fileName, null)
                    controllerShowTimeoutMs = if (isAudio) 0 else 3000
                    controllerHideOnTouch = !isAudio
                    showController()
                }
            },
            update = { playerView ->
                if (playerView.player != player) {
                    playerView.player = player
                }
                val isAudio = Indexer.isAudioNameOrMime(params.fileName, null)
                playerView.controllerShowTimeoutMs = if (isAudio) 0 else 3000
                playerView.controllerHideOnTouch = !isAudio
                playerView.showController()
            },
            onRelease = { playerView ->
                playerView.player = null
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

