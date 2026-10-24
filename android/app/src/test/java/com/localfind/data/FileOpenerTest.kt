package com.localfind.data

import com.localfind.core.model.FileTypes
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
            FileTypes.resolveMimeTypeFromExtension("test.webp", "image/webp")
        )
        assertEquals(
            "video/x-matroska",
            FileTypes.resolveMimeTypeFromExtension("clip.mkv", "video/x-matroska")
        )
    }

    @Test
    fun resolveMimeTypeFromExtension_withGenericMime_fallsBackToExtension() {
        assertEquals(
            "video/mp4",
            FileTypes.resolveMimeTypeFromExtension("BBC The New Wild.mp4", "*/*")
        )
        assertEquals(
            "video/mp4",
            FileTypes.resolveMimeTypeFromExtension("BBC The New Wild.mp4", "application/octet-stream")
        )
        assertEquals(
            "video/x-matroska",
            FileTypes.resolveMimeTypeFromExtension("movie.mkv", "*/*")
        )
        assertEquals(
            "video/webm",
            FileTypes.resolveMimeTypeFromExtension("clip.webm", "application/octet-stream")
        )
    }

    @Test
    fun resolveMimeTypeFromExtension_commonExtensions_resolvesCorrectMime() {
        // Videos
        assertEquals("video/mp4", FileTypes.resolveMimeTypeFromExtension("nature.mp4"))
        assertEquals("video/x-matroska", FileTypes.resolveMimeTypeFromExtension("movie.mkv"))
        assertEquals("video/webm", FileTypes.resolveMimeTypeFromExtension("clip.webm"))

        // Images
        assertEquals("image/jpeg", FileTypes.resolveMimeTypeFromExtension("sunset.jpg"))
        assertEquals("image/jpeg", FileTypes.resolveMimeTypeFromExtension("landscape.jpeg"))
        assertEquals("image/png", FileTypes.resolveMimeTypeFromExtension("screenshot.png"))
        assertEquals("image/gif", FileTypes.resolveMimeTypeFromExtension("animation.gif"))

        // PDFs
        assertEquals("application/pdf", FileTypes.resolveMimeTypeFromExtension("report.pdf"))

        // Documents
        assertEquals("text/plain", FileTypes.resolveMimeTypeFromExtension("notes.txt"))
        assertEquals("text/plain", FileTypes.resolveMimeTypeFromExtension("README.md"))

        // Audio
        assertEquals("audio/mpeg", FileTypes.resolveMimeTypeFromExtension("podcast.mp3"))
        assertEquals("audio/mp4", FileTypes.resolveMimeTypeFromExtension("recording.m4a"))
        assertEquals("audio/wav", FileTypes.resolveMimeTypeFromExtension("track.wav"))
    }

    @Test
    fun resolveMimeTypeFromExtension_unknownExtension_fallsBackToWildcard() {
        assertEquals("*/*", FileTypes.resolveMimeTypeFromExtension("archive.xyz"))
    }
}

