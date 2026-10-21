package com.localfind.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo

/**
 * Screen orientation coordinator for LocalFind.
 *
 * Implements requirement: "Support landscape orientation in file viewer only".
 *
 * Architectural Invariants:
 * - LocalFind's main interface (Gallery grid, Search overlay, Settings) is strictly locked
 *   to portrait orientation ([ActivityInfo.SCREEN_ORIENTATION_PORTRAIT]) to maintain a consistent,
 *   one-handed navigation experience.
 * - When any builtin file viewer (Image, Video/Audio, PDF, Text) is active, the orientation
 *   is dynamically unlocked to [ActivityInfo.SCREEN_ORIENTATION_FULL_USER] so users can freely rotate
 *   their device to landscape (in either direction) or portrait based on system sensor settings.
 * - When the file viewer is dismissed, the activity orientation is immediately restored to portrait,
 *   rotating the screen back if the user was holding their device sideways.
 */
object ViewerOrientationHelper {

    /**
     * Returns the target [ActivityInfo] screen orientation constant based on whether
     * a builtin file viewer is currently open.
     *
     * @param isViewerOpen True when any of the 4 builtin viewers is actively displayed.
     * @return [ActivityInfo.SCREEN_ORIENTATION_FULL_USER] when viewing files;
     *         [ActivityInfo.SCREEN_ORIENTATION_PORTRAIT] otherwise.
     */
    fun getDesiredOrientation(isViewerOpen: Boolean): Int {
        return if (isViewerOpen) {
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    /**
     * Safely traverses the [Context] hierarchy to find the enclosing [Activity],
     * unwrapping any [ContextWrapper] instances (e.g. from Compose or themed views).
     *
     * @param context Current Compose or Android context.
     * @return The underlying [Activity], or null if context is not activity-backed.
     */
    fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /**
     * Applies the desired orientation to the specified [Activity] if it differs
     * from the currently requested orientation, avoiding redundant framework IPC calls.
     *
     * @param activity The target host activity to update.
     * @param isViewerOpen True when any builtin viewer is currently active.
     */
    fun applyOrientation(activity: Activity?, isViewerOpen: Boolean) {
        if (activity == null) return
        val target = getDesiredOrientation(isViewerOpen)
        if (activity.requestedOrientation != target) {
            activity.requestedOrientation = target
        }
    }
}

