package com.localfind.index

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for handling notification action intents (Pause, Resume, Cancel).
 *
 * Architecture and Offline Guarantee:
 * - 100% offline: Operates strictly within local app process with zero network usage.
 * - Non-exported: Registered with `android:exported="false"` in AndroidManifest.xml so it can only
 *   receive explicit PendingIntents triggered from LocalFind's own indexing notification actions.
 * - Asynchronous execution: Uses [goAsync] and [Dispatchers.IO] to safely execute queue state
 *   transitions and SQLite updates without blocking the main Android broadcast thread.
 */
class IndexingActionReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "IndexingActionReceiver"
        const val ACTION_PAUSE = "com.localfind.action.PAUSE_INDEXING"
        const val ACTION_RESUME = "com.localfind.action.RESUME_INDEXING"
        const val ACTION_CANCEL = "com.localfind.action.CANCEL_INDEXING"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received indexing notification action: $action")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_PAUSE -> {
                        IndexingWorker.pause(context)
                    }
                    ACTION_RESUME -> {
                        IndexingWorker.resume(context)
                    }
                    ACTION_CANCEL -> {
                        IndexingWorker.cancel(context)
                    }
                    else -> {
                        Log.w(TAG, "Unknown action received: $action")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling action $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

