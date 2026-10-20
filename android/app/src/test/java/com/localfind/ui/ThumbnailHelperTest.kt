package com.localfind.ui

import com.localfind.data.ImportedFile
import com.localfind.ui.gallery.GalleryCategory
import com.localfind.ui.gallery.ThumbnailHelper
import com.localfind.ui.gallery.iconResId
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

    @Test
    fun `galleryCategory iconResId maps to valid Material Symbol drawables for all categories`() {
        // Ensures all modalities map to their respective Material Symbol icon drawable
        assertEquals(com.localfind.R.drawable.ic_grid_view, GalleryCategory.ALL.iconResId)
        assertEquals(com.localfind.R.drawable.ic_image, GalleryCategory.PHOTOS.iconResId)
        assertEquals(com.localfind.R.drawable.ic_videocam, GalleryCategory.VIDEOS.iconResId)
        assertEquals(com.localfind.R.drawable.ic_picture_as_pdf, GalleryCategory.PDFS.iconResId)
        assertEquals(com.localfind.R.drawable.ic_description, GalleryCategory.TEXT.iconResId)
        assertEquals(com.localfind.R.drawable.ic_audiotrack, GalleryCategory.AUDIO.iconResId)
    }

    @Test
    fun `computeVideoThumbnailTargetTimeMs applies 2s offset and avoids first second`() {
        // Main gallery tile (baseTimeMs = 0L) with long video: extracts at 2000ms (2.0s), not at the start
        val galleryTargetMs = ThumbnailHelper.computeVideoThumbnailTargetTimeMs(
            baseTimeMs = 0L,
            durationMs = 60_000L
        )
        assertEquals(2_000L, galleryTargetMs)

        // Video search result at 15000ms: extracts at 17000ms (15000 + 2000ms)
        val searchTargetMs = ThumbnailHelper.computeVideoThumbnailTargetTimeMs(
            baseTimeMs = 15_000L,
            durationMs = 60_000L
        )
        assertEquals(17_000L, searchTargetMs)
    }

    @Test
    fun `computeVideoThumbnailTargetTimeMs clamps within video duration for short videos`() {
        // Short video of 1500ms duration: does not seek past end of video
        val shortTargetMs = ThumbnailHelper.computeVideoThumbnailTargetTimeMs(
            baseTimeMs = 0L,
            durationMs = 1_500L
        )
        assertEquals(1_400L, shortTargetMs)

        // Video with 0 or unknown duration returns base + 2000ms without throwing
        val unknownDurationTargetMs = ThumbnailHelper.computeVideoThumbnailTargetTimeMs(
            baseTimeMs = 5_000L,
            durationMs = 0L
        )
        assertEquals(7_000L, unknownDurationTargetMs)
    }

    @Test
    fun `previewCacheKey differentiates timestamps for search results while keeping base uri for standard gallery tiles`() {
        // Main gallery preview uses raw URI as key
        assertEquals("content://video.mp4", ThumbnailHelper.previewCacheKey("content://video.mp4", null))

        // Search results with moment timestamps use distinct URI@timestamp keys
        assertEquals("content://video.mp4@0", ThumbnailHelper.previewCacheKey("content://video.mp4", 0L))
        assertEquals("content://video.mp4@15000", ThumbnailHelper.previewCacheKey("content://video.mp4", 15_000L))

        // Search results with PDF page numbers use distinct URI@p<page> keys
        assertEquals("content://doc.pdf@p4", ThumbnailHelper.previewCacheKey("content://doc.pdf", pageNumber = 4))
        assertEquals("content://doc.pdf", ThumbnailHelper.previewCacheKey("content://doc.pdf", pageNumber = null))
    }

    @Test
    fun `computePdfTargetPageIndex computes 0-based page index with safe clamping`() {
        // Null page defaults to page index 0 (page 1)
        assertEquals(0, ThumbnailHelper.computePdfTargetPageIndex(null, 10))

        // 1-based page 1 maps to index 0
        assertEquals(0, ThumbnailHelper.computePdfTargetPageIndex(1, 10))

        // 1-based page 4 maps to index 3
        assertEquals(3, ThumbnailHelper.computePdfTargetPageIndex(4, 10))

        // Out-of-bounds page numbers clamp safely within [0, pageCount - 1]
        assertEquals(9, ThumbnailHelper.computePdfTargetPageIndex(15, 10))
        assertEquals(0, ThumbnailHelper.computePdfTargetPageIndex(0, 10))
        assertEquals(0, ThumbnailHelper.computePdfTargetPageIndex(-5, 10))

        // Document with 0 pages handled safely without division-by-zero or crash
        assertEquals(0, ThumbnailHelper.computePdfTargetPageIndex(1, 0))
    }
}
