package com.localfind.core.library

import com.localfind.core.model.FileKind

/**
 * Filter categories used in both the Gallery Grid and the Dedicated Search View.
 *
 * Allows users to quickly narrow down files or search hits by media modality.
 */
enum class GalleryCategory(
    val label: String,
    /**
     * Chunk metadata "type" values associated with this category.
     * Used by Searcher to restrict nearest-neighbor vector search directly in SQLite
     * ("Search after filter, not filter search results"), preventing irrelevant modalities
     * from consuming the top-K budget.
     * Null means no modality restriction (matches all file types).
     */
    val types: List<String>? = null
) {
    ALL("All", null),
    PHOTOS("Photos", listOf(FileKind.IMAGE.id)),
    VIDEOS("Videos", listOf(FileKind.VIDEO.id)),
    PDFS("PDFs", listOf(FileKind.PDF.id)),
    TEXT("Text", listOf(FileKind.TEXT.id)),
    AUDIO("Audio", listOf(FileKind.AUDIO.id))
}
