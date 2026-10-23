package com.localfind.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [ChunkMetadata] and the [FileKind] ids it stores. */
class ChunkMetadataTest {

    @Test
    fun build_storesFileIdentityTypeAndLocation() {
        val file = ImportedFile(uri = "content://x/1", displayName = "a.pdf", mimeType = "application/pdf", size = 10)

        val metadata = ChunkMetadata.build(file, FileKind.PDF, location = "3")

        assertEquals("content://x/1", metadata[ChunkMetadata.FILE_ID])
        assertEquals("content://x/1", metadata[ChunkMetadata.URI])
        assertEquals("a.pdf", metadata[ChunkMetadata.NAME])
        assertEquals("pdf", metadata[ChunkMetadata.TYPE])
        assertEquals("3", metadata[ChunkMetadata.LOCATION])
    }

    @Test
    fun keysAndKindIdsMatchTheStoredFormat() {
        // These strings are persisted in the vector database; changing them would orphan existing indexes.
        assertEquals("fileId", ChunkMetadata.FILE_ID)
        assertEquals("uri", ChunkMetadata.URI)
        assertEquals("name", ChunkMetadata.NAME)
        assertEquals("type", ChunkMetadata.TYPE)
        assertEquals("location", ChunkMetadata.LOCATION)
        assertEquals(listOf("text", "pdf", "image", "audio", "video"), FileKind.entries.map { it.id })
    }

    @Test
    fun chunkId_isUriPlusIndex() {
        assertEquals("content://x/1_0", ChunkMetadata.chunkId("content://x/1", 0))
        assertEquals("content://x/1_12", ChunkMetadata.chunkId("content://x/1", 12))
    }
}
