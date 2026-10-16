package com.localfind.ui

import com.localfind.data.ImportedFile
import com.localfind.search.SearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ThumbnailHelper] pure categorization, filtering, and first-lines formatting logic.
 */
class ThumbnailHelperTest {

    @Test
    fun `categorizeFile classifies photos, videos, audio, and documents accurately`() {
        assertEquals(
            GalleryCategory.PHOTOS,
            ThumbnailHelper.categorizeFile("vacation.jpg", "image/jpeg")
        )
        assertEquals(
            GalleryCategory.PHOTOS,
            ThumbnailHelper.categorizeFile("diagram.png", null)
        )
        assertEquals(
            GalleryCategory.VIDEOS,
            ThumbnailHelper.categorizeFile("clip.mp4", "video/mp4")
        )
        assertEquals(
            GalleryCategory.AUDIO,
            ThumbnailHelper.categorizeFile("memo.m4a", "audio/mp4")
        )
        assertEquals(
            GalleryCategory.DOCUMENTS,
            ThumbnailHelper.categorizeFile("contract.pdf", "application/pdf")
        )
        assertEquals(
            GalleryCategory.DOCUMENTS,
            ThumbnailHelper.categorizeFile("notes.md", "text/markdown")
        )
    }

    @Test
    fun `filterFiles returns matching category or all files when ALL is selected`() {
        val files = listOf(
            ImportedFile("uri://1", "beach.jpg", "image/jpeg", 1024L),
            ImportedFile("uri://2", "movie.mp4", "video/mp4", 4096L),
            ImportedFile("uri://3", "paper.pdf", "application/pdf", 2048L),
            ImportedFile("uri://4", "readme.md", "text/markdown", 512L),
            ImportedFile("uri://5", "song.mp3", "audio/mpeg", 3072L)
        )

        assertEquals(5, ThumbnailHelper.filterFiles(files, GalleryCategory.ALL).size)
        assertEquals(1, ThumbnailHelper.filterFiles(files, GalleryCategory.PHOTOS).size)
        assertEquals(1, ThumbnailHelper.filterFiles(files, GalleryCategory.VIDEOS).size)
        assertEquals(2, ThumbnailHelper.filterFiles(files, GalleryCategory.DOCUMENTS).size)
        assertEquals(1, ThumbnailHelper.filterFiles(files, GalleryCategory.AUDIO).size)
    }

    @Test
    fun `filterSearchResults filters ranked results by modality category`() {
        val results = listOf(
            SearchResult("1", "uri://1", "beach.jpg", "image", "", 0L, 0.91f),
            SearchResult("2", "uri://2", "paper.pdf", "pdf", "Abstract...", 3L, 0.85f),
            SearchResult("3", "uri://3", "podcast.mp3", "audio", "Audio segment", 12000L, 0.78f)
        )

        assertEquals(3, ThumbnailHelper.filterSearchResults(results, GalleryCategory.ALL).size)
        assertEquals(1, ThumbnailHelper.filterSearchResults(results, GalleryCategory.PHOTOS).size)
        assertEquals(1, ThumbnailHelper.filterSearchResults(results, GalleryCategory.DOCUMENTS).size)
        assertEquals(1, ThumbnailHelper.filterSearchResults(results, GalleryCategory.AUDIO).size)
        assertTrue(ThumbnailHelper.filterSearchResults(results, GalleryCategory.VIDEOS).isEmpty())
    }

    @Test
    fun `formatFirstLinesPreview strips leading blank lines and truncates cleanly`() {
        val raw = "\n  \n# Project Roadmap\n1. Build offline multimodal gallery\n2. Add seamless viewer transitions"
        val preview = ThumbnailHelper.formatFirstLinesPreview(raw, maxChars = 45)
        assertTrue(preview.startsWith("# Project Roadmap"))
        assertTrue(preview.endsWith("…"))

        val emptyPreview = ThumbnailHelper.formatFirstLinesPreview("   \n  \r\n ")
        assertEquals("(Empty document)", emptyPreview)
    }
}
