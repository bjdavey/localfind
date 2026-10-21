package com.localfind.ui.viewer

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ViewerOrientationHelper] and [ViewerState] orientation logic.
 *
 * Verifies that the app strictly preserves portrait orientation for library/search/settings,
 * while unlocking landscape orientation ([ActivityInfo.SCREEN_ORIENTATION_FULL_USER]) whenever
 * any of the 4 builtin file viewers (Image, Video/Audio, PDF, Text) is active.
 */
class ViewerOrientationHelperTest {

    @Test
    fun getDesiredOrientation_locksPortraitWhenNoViewerOpen() {
        val orientation = ViewerOrientationHelper.getDesiredOrientation(isViewerOpen = false)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, orientation)
    }

    @Test
    fun getDesiredOrientation_unlocksFullUserWhenViewerIsOpen() {
        val orientation = ViewerOrientationHelper.getDesiredOrientation(isViewerOpen = true)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, orientation)
    }

    @Test
    fun viewerState_tracksActiveViewersAndControlsOrientation() {
        val state = ViewerState()

        // Initially no viewers are open -> Portrait
        assertFalse(state.isAnyViewerOpen)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ViewerOrientationHelper.getDesiredOrientation(state.isAnyViewerOpen)
        )

        // 1. Image Viewer opened -> Unlocked
        state.activeImageViewer = ImageViewerParams(uri = "content://image.jpg", fileName = "photo.jpg")
        assertTrue(state.isAnyViewerOpen)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            ViewerOrientationHelper.getDesiredOrientation(state.isAnyViewerOpen)
        )

        // Dismiss Image Viewer -> Portrait
        state.activeImageViewer = null
        assertFalse(state.isAnyViewerOpen)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ViewerOrientationHelper.getDesiredOrientation(state.isAnyViewerOpen)
        )

        // 2. Media Player opened -> Unlocked
        state.activeMediaPlayer = MediaPlayerParams(uri = "content://clip.mp4", fileName = "clip.mp4", startMs = 0L)
        assertTrue(state.isAnyViewerOpen)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            ViewerOrientationHelper.getDesiredOrientation(state.isAnyViewerOpen)
        )

        state.activeMediaPlayer = null
        assertFalse(state.isAnyViewerOpen)

        // 3. PDF Viewer opened -> Unlocked
        state.activePdfViewer = PdfViewerParams(uri = "content://doc.pdf", fileName = "doc.pdf", initialPage = 1)
        assertTrue(state.isAnyViewerOpen)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            ViewerOrientationHelper.getDesiredOrientation(state.isAnyViewerOpen)
        )

        state.activePdfViewer = null
        assertFalse(state.isAnyViewerOpen)

        // 4. Text Viewer opened -> Unlocked
        state.activeTextViewer = TextViewerParams(uri = "content://notes.txt", fileName = "notes.txt")
        assertTrue(state.isAnyViewerOpen)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            ViewerOrientationHelper.getDesiredOrientation(state.isAnyViewerOpen)
        )

        // closeAll dismisses all active viewers -> Portrait
        state.closeAll()
        assertFalse(state.isAnyViewerOpen)
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ViewerOrientationHelper.getDesiredOrientation(state.isAnyViewerOpen)
        )
    }
}

