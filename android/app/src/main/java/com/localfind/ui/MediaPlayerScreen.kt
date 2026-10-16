package com.localfind.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

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
 * In-app player using Media3 ExoPlayer + [PlayerView] (ARCHITECTURE.md §8).
 *
 * Plays directly from the linked SAF URI, seeks to [MediaPlayerParams.startMs] and starts playing.
 * The player is released when the screen leaves composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPlayerScreen(
    params: MediaPlayerParams,
    onClose: () -> Unit,
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
    DisposableEffect(player) { onDispose { player.release() } }

    BackHandler { onClose() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = params.fileName.ifEmpty { "Player" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Text(text = "←", style = MaterialTheme.typography.titleLarge)
                    }
                }
            )
        }
    ) { innerPadding ->
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    // Keep controls visible: audio has no picture to tap on
                    controllerShowTimeoutMs = 0
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

