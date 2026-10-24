package com.localfind.ui.viewer

import com.localfind.core.model.FileTypes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.localfind.R
import com.localfind.index.Indexer
import com.localfind.core.format.SearchResultFormatter

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
    onSearchInFile: () -> Unit,
    onShowDetails: () -> Unit,
    backHandlerEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var playerError by remember(params.uri) { mutableStateOf<String?>(null) }

    val player = remember(params.uri) {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    playerError = "Playback failed: Original file not found or inaccessible."
                }
            })
            setMediaItem(MediaItem.fromUri(params.uri.toUri()), params.startMs)
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
                        if (params.startMs > 0L) {
                            Text(
                                text = "From ${SearchResultFormatter.formatTimeMs(params.startMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xCCFFFFFF)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = handleClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSearchInFile) {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = "Search in file",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = {
                        player.pause()
                        onShowDetails()
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_info),
                            contentDescription = "File details",
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
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        val isAudio = FileTypes.isAudioNameOrMime(params.fileName, null)
                        controllerShowTimeoutMs = if (isAudio) 0 else 3000
                        controllerHideOnTouch = !isAudio
                        showController()
                    }
                },
                update = { playerView ->
                    if (playerView.player != player) {
                        playerView.player = player
                    }
                    val isAudio = FileTypes.isAudioNameOrMime(params.fileName, null)
                    playerView.controllerShowTimeoutMs = if (isAudio) 0 else 3000
                    playerView.controllerHideOnTouch = !isAudio
                    playerView.showController()
                },
                onRelease = { playerView ->
                    playerView.player = null
                },
                modifier = Modifier.fillMaxSize()
            )

            val currentError = playerError
            if (currentError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_warning),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = currentError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = handleClose) {
                            Text("Go back")
                        }
                    }
                }
            }
        }
    }
}
