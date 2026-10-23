package com.localfind.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingSpaceTest {

    private fun id(npuText: Boolean = true, npuVision: Boolean = true, bytes: Long = 100L, type: String = "FLOAT32") =
        EmbeddingSpace.id(type, bytes, npuText, npuVision)

    @Test
    fun sameSetup_givesSameId() {
        assertEquals(id(), id())
    }

    @Test
    fun npuVersusGpuCpu_differ() {
        assertNotEquals(id(npuText = true, npuVision = true), id(npuText = false, npuVision = false))
    }

    @Test
    fun textAndVisionBackendsAreSeparate() {
        // NPU init can fall back to "vision only on NPU": text vectors then live in another space
        assertNotEquals(id(npuText = true, npuVision = true), id(npuText = false, npuVision = true))
    }

    @Test
    fun otherModelOrPrecision_differ() {
        assertNotEquals(id(), id(bytes = 101L))
        assertNotEquals(id(), id(type = "FLOAT16"))
    }

    @Test
    fun unknownFileSpace_isNeverStale() {
        assertFalse(EmbeddingSpace.isStale(null, id()))
    }

    @Test
    fun unknownCurrentSpace_isNeverStale() {
        assertFalse(EmbeddingSpace.isStale(id(), null))
    }

    @Test
    fun differentIds_areStale_equalIds_arent() {
        assertTrue(EmbeddingSpace.isStale(id(npuText = false, npuVision = false), id()))
        assertFalse(EmbeddingSpace.isStale(id(), id()))
    }
}
