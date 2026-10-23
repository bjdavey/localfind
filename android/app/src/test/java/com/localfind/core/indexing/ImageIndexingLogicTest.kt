package com.localfind.core.indexing

import com.localfind.core.model.FileTypes
import com.localfind.core.indexing.ImageSizing
import com.localfind.index.image.ImageIndexer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for image indexing math and format detection logic in [Indexer].
 *
 * Tests:
 * - Supported image extension and MIME type detection (.jpg, .jpeg, .png).
 * - Aspect-ratio-preserving target dimension downscaling within [DEFAULT_MAX_IMAGE_DIMENSION] (512px).
 */
class ImageIndexingLogicTest {

    @Test
    fun isImageNameOrMime_recognizesSupportedImageFormats() {
        assertTrue(FileTypes.isImageNameOrMime("sunset.jpg", null))
        assertTrue(FileTypes.isImageNameOrMime("PHOTO.JPEG", null))
        assertTrue(FileTypes.isImageNameOrMime("diagram.png", null))
        assertTrue(FileTypes.isImageNameOrMime("animation.gif", null))
        assertTrue(FileTypes.isImageNameOrMime("MEME.GIF", null))
        assertTrue(FileTypes.isImageNameOrMime("document_without_ext", "image/jpeg"))
        assertTrue(FileTypes.isImageNameOrMime("document_without_ext", "image/png"))
        assertTrue(FileTypes.isImageNameOrMime("document_without_ext", "image/gif"))

        assertTrue(FileTypes.isGifNameOrMime("reaction.gif", null))
        assertTrue(FileTypes.isGifNameOrMime("reaction_no_ext", "image/gif"))
        assertFalse(FileTypes.isGifNameOrMime("photo.jpg", "image/jpeg"))

        assertFalse(FileTypes.isImageNameOrMime("notes.txt", "text/plain"))
        assertFalse(FileTypes.isImageNameOrMime("report.pdf", "application/pdf"))
        assertFalse(FileTypes.isImageNameOrMime("clip.mp4", "video/mp4"))
    }

    @Test
    fun calculateTargetDimensions_preservesSmallerImages() {
        val (w, h) = ImageSizing.calculateTargetDimensions(width = 400, height = 300, maxDimension = 512)
        assertEquals(400, w)
        assertEquals(300, h)
    }

    @Test
    fun calculateTargetDimensions_scalesDownLandscapeAndPortraitProportionally() {
        // Landscape 4000x3000 -> 512x384 (default DEFAULT_MAX_IMAGE_DIMENSION = 512)
        val (landW, landH) = ImageSizing.calculateTargetDimensions(width = 4000, height = 3000)
        assertEquals(512, landW)
        assertEquals(384, landH)

        // Portrait 1500x3000 -> 256x512
        val (portW, portH) = ImageSizing.calculateTargetDimensions(width = 1500, height = 3000)
        assertEquals(256, portW)
        assertEquals(512, portH)
    }
}
