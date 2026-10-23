package com.localfind.index

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.localfind.MainActivity
import com.localfind.R

/**
 * Builds and manages the ongoing "Analyzing files" / "Paused" notification that keeps background
 * indexing visible (and alive as a foreground service) while the app is closed.
 */
object IndexingNotifications {
    const val CHANNEL_ID = "localfind_indexing_channel"
    const val NOTIFICATION_ID = 1001

    fun manager(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File Analysis",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while LocalFind analyzes files in the background"
            setShowBadge(false)
        }
        val notificationManager = manager(context)
        notificationManager?.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        message: String,
        progressFraction: Float,
        fileIndex: Int,
        totalFiles: Int,
        isPaused: Boolean
    ): Notification {
        createChannel(context)

        val title = if (isPaused) {
            "Paused"
        } else if (totalFiles > 0) {
            "Analyzing files ($fileIndex/$totalFiles)"
        } else {
            "Analyzing files"
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val maxProgress = 100
        val currentProgress = (progressFraction * 100).toInt().coerceIn(0, 100)
        val isIndeterminate = progressFraction <= 0f && totalFiles <= 0 && !isPaused

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_search)
            .setOngoing(!isPaused)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setProgress(maxProgress, currentProgress, isIndeterminate)

        // Add Pause/Resume and Cancel notification action buttons
        if (isPaused) {
            val resumeIntent = Intent(context, IndexingActionReceiver::class.java).apply {
                action = IndexingActionReceiver.ACTION_RESUME
            }
            val resumePending = PendingIntent.getBroadcast(
                context,
                1,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePending)
        } else {
            val pauseIntent = Intent(context, IndexingActionReceiver::class.java).apply {
                action = IndexingActionReceiver.ACTION_PAUSE
            }
            val pausePending = PendingIntent.getBroadcast(
                context,
                2,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePending)
        }

        val cancelIntent = Intent(context, IndexingActionReceiver::class.java).apply {
            action = IndexingActionReceiver.ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getBroadcast(
            context,
            3,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPending)

        return builder.build()
    }

    fun buildPaused(context: Context, remainingCount: Int): Notification {
        return build(
            context = context,
            message = "Paused: $remainingCount file(s) waiting in queue",
            progressFraction = 0f,
            fileIndex = 0,
            totalFiles = remainingCount,
            isPaused = true
        )
    }
}
