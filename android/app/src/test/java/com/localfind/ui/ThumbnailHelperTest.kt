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
            GalleryCategory.PDFS,
            ThumbnailHelper.categorizeFile("contract.pdf", "application/pdf")
        )
        assertEquals(
            GalleryCategory.TEXT,
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
        assertEquals(1, ThumbnailHelper.filterFiles(files, GalleryCategory.PDFS).size)
        assertEquals(1, ThumbnailHelper.filterFiles(files, GalleryCategory.TEXT).size)
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
        assertEquals(1, ThumbnailHelper.filterSearchResults(results, GalleryCategory.PDFS).size)
        assertEquals(0, ThumbnailHelper.filterSearchResults(results, GalleryCategory.TEXT).size)
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

    @Test
    fun `galleryCategory defines correct chunk type mappings for search after filter`() {
        // Validates that each modality category maps to the exact chunk "type" metadata values
        // used by Indexer when storing vectors into SqliteVectorStore.
        org.junit.Assert.assertNull(GalleryCategory.ALL.types)
        assertEquals(listOf("image"), GalleryCategory.PHOTOS.types)
        assertEquals(listOf("video"), GalleryCategory.VIDEOS.types)
        assertEquals(listOf("pdf"), GalleryCategory.PDFS.types)
        assertEquals(listOf("text"), GalleryCategory.TEXT.types)
        assertEquals(listOf("audio"), GalleryCategory.AUDIO.types)
    }

    @Test
    fun `getAvailableCategories returns only categories present in imported files plus ALL`() {
        // Empty library returns only ALL
        assertEquals(listOf(GalleryCategory.ALL), ThumbnailHelper.getAvailableCategories(emptyList()))

        // Library with only photos returns ALL and PHOTOS (omits VIDEOS, PDFS, TEXT, AUDIO)
        val photosOnly = listOf(ImportedFile("u1", "photo.jpg", "image/jpeg", 100L))
        assertEquals(
            listOf(GalleryCategory.ALL, GalleryCategory.PHOTOS),
            ThumbnailHelper.getAvailableCategories(photosOnly)
        )

        // Library with photos and PDFs returns ALL, PHOTOS, and PDFS
        val mixedFiles = listOf(
            ImportedFile("u1", "photo.jpg", "image/jpeg", 100L),
            ImportedFile("u2", "contract.pdf", "application/pdf", 200L)
        )
        assertEquals(
            listOf(GalleryCategory.ALL, GalleryCategory.PHOTOS, GalleryCategory.PDFS),
            ThumbnailHelper.getAvailableCategories(mixedFiles)
        )

        // Library with all file types returns all 6 categories
        val allTypes = listOf(
            ImportedFile("u1", "photo.jpg", "image/jpeg", 100L),
            ImportedFile("u2", "video.mp4", "video/mp4", 100L),
            ImportedFile("u3", "doc.pdf", "application/pdf", 100L),
            ImportedFile("u4", "notes.txt", "text/plain", 100L),
            ImportedFile("u5", "song.mp3", "audio/mpeg", 100L)
        )
        assertEquals(
            GalleryCategory.entries.toList(),
            ThumbnailHelper.getAvailableCategories(allTypes)
        )
    }
}
