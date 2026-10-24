package com.localfind.ui.gallery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localfind.core.model.ModelStatus
import com.localfind.embed.RecoveryModel

/**
 * Progress banner while Play downloads the universal GPU/CPU model ([ModelStatus.Downloading]).
 *
 * When Play waits for the user (cellular download over 200MB, or an install Play does not recognize), a
 * "Continue" button opens Play's confirmation dialog; the download then resumes by itself.
 */
@Composable
internal fun ColumnScope.ModelDownloadBanner(modelStatus: ModelStatus) {
    val context = LocalContext.current
    // Play's confirmation dialog is started through an IntentSender; its result arrives as a pack state update
    val confirmationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

    AnimatedVisibility(
        visible = modelStatus is ModelStatus.Downloading,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        val downloading = modelStatus as? ModelStatus.Downloading ?: return@AnimatedVisibility
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Downloading compatible model",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = if (downloading.needsConfirmation) {
                                "Waiting for your confirmation (${RecoveryModel.DOWNLOAD_SIZE_MB} MB)"
                            } else {
                                "${downloading.percent}% of ${RecoveryModel.DOWNLOAD_SIZE_MB} MB, via Google Play"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                        )
                    }
                    if (downloading.needsConfirmation) {
                        FilledTonalButton(onClick = { RecoveryModel.showConfirmation(context, confirmationLauncher) }) {
                            Text("Continue", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                LinearProgressIndicator(
                    progress = { downloading.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
