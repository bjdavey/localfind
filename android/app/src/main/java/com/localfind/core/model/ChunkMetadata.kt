package com.localfind.core.model

/**
 * Layout of the metadata stored with every embedded chunk in the vector store, and of chunk ids.
 *
 * - [FILE_ID] and [URI] both hold the source file's URI ([URI] is read first by search; [FILE_ID]
 *   is what per-file deletion matches on).
 * - [LOCATION] is the chunk's position: character offset (text), 1-based page (PDF), 0 (image),
 *   start time in ms (audio/video).
 */
object ChunkMetadata {
    const val FILE_ID = "fileId"
    const val URI = "uri"
    const val NAME = "name"
    const val TYPE = "type"
    const val LOCATION = "location"

    /** Builds the canonical metadata map stored alongside each chunk vector. */
    fun build(file: ImportedFile, kind: FileKind, location: String): Map<String, String> = mapOf(
        FILE_ID to file.uri,
        URI to file.uri,
        NAME to file.displayName,
        TYPE to kind.id,
        LOCATION to location
    )

    /** Unique id of the [index]-th chunk of the file at [fileUri]. */
    fun chunkId(fileUri: String, index: Int): String = "${fileUri}_$index"
}
