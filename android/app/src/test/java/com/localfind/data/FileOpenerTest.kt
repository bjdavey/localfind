package com.localfind.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [FileOpener] MIME type resolution logic.
 */
class FileOpenerTest {

    @Test
    fun resolveMimeTypeFromExtension_withExplicitMime_usesExplicit() {
        assertEquals(
            "image/webp",
            FileOpener.resolveMimeTypeFromExtension("test.webp", "image/webp")
        )
        assertEquals(
            "video/x-matroska",
            FileOpener.resolveMimeTypeFromExtension("clip.mkv", "video/x-matroska")
        )
    }

    @Test
    fun resolveMimeTypeFromExtension_withGenericMime_fallsBackToExtension() {
        assertEquals(
            "video/mp4",
            FileOpener.resolveMimeTypeFromExtension("BBC The New Wild.mp4", "*/*")
        )
        assertEquals(
            "video/mp4",
            FileOpener.resolveMimeTypeFromExtension("BBC The New Wild.mp4", "application/octet-stream")
        )
    }

    @Test
    fun resolveMimeTypeFromExtension_commonExtensions_resolvesCorrectMime() {
        // Videos
        assertEquals("video/mp4", FileOpener.resolveMimeTypeFromExtension("nature.mp4"))

        // Images
        assertEquals("image/jpeg", FileOpener.resolveMimeTypeFromExtension("sunset.jpg"))
        assertEquals("image/jpeg", FileOpener.resolveMimeTypeFromExtension("landscape.jpeg"))
        assertEquals("image/png", FileOpener.resolveMimeTypeFromExtension("screenshot.png"))
        assertEquals("image/gif", FileOpener.resolveMimeTypeFromExtension("animation.gif"))

        // PDFs
        assertEquals("application/pdf", FileOpener.resolveMimeTypeFromExtension("report.pdf"))

        // Documents
        assertEquals("text/plain", FileOpener.resolveMimeTypeFromExtension("notes.txt"))
        assertEquals("text/plain", FileOpener.resolveMimeTypeFromExtension("README.md"))

        // Audio
        assertEquals("audio/mpeg", FileOpener.resolveMimeTypeFromExtension("podcast.mp3"))
        assertEquals("audio/mp4", FileOpener.resolveMimeTypeFromExtension("recording.m4a"))
        assertEquals("audio/wav", FileOpener.resolveMimeTypeFromExtension("track.wav"))
    }

    @Test
    fun resolveMimeTypeFromExtension_unknownExtension_fallsBackToWildcard() {
        assertEquals("*/*", FileOpener.resolveMimeTypeFromExtension("archive.xyz"))
    }
}

