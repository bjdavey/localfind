package com.localfind.core.model

/**
 * Category of an indexed file. [id] is the value stored in each chunk's `type` metadata and in
 * [SearchResult.fileType], so the ids must stay stable across releases.
 */
enum class FileKind(val id: String) {
    TEXT("text"),
    PDF("pdf"),
    IMAGE("image"),
    AUDIO("audio"),
    VIDEO("video")
}

/**
 * Pure file-type detection (by display name or MIME type) for the formats LocalFind can index
 * and show in a builtin viewer.
 */
object FileTypes {

    /**
     * Supported MIME types for system document pickers.
     * Restricts the system file picker to only show supported file types with builtin viewers.
     */
    val SUPPORTED_MIME_TYPES = arrayOf(
        "text/plain",
        "text/markdown",
        "text/*",
        "application/pdf",
        "image/jpeg",
        "image/png",
        "image/gif",
        "audio/mpeg",
        "audio/mp4",
        "audio/x-m4a",
        "audio/wav",
        "audio/x-wav",
        "audio/*",
        "video/mp4",
        "video/x-matroska",
        "video/webm",
        "video/mkv"
    )

    /**
     * Checks whether a file with the given display name and MIME type is supported by
     * LocalFind's indexers and has a corresponding builtin viewer.
     */
    fun isSupported(displayName: String, mimeType: String?): Boolean {
        return isPdfNameOrMime(displayName, mimeType) ||
            isImageNameOrMime(displayName, mimeType) ||
            isAudioNameOrMime(displayName, mimeType) ||
            isVideoNameOrMime(displayName, mimeType) ||
            isTextNameOrMime(displayName, mimeType)
    }

    /** Checks whether a file name or MIME type corresponds to a supported text or markdown format. */
    fun isTextNameOrMime(displayName: String, mimeType: String?): Boolean {
        val name = displayName.lowercase()
        val mime = mimeType?.lowercase() ?: ""
        return name.endsWith(".txt") ||
            name.endsWith(".md") ||
            mime.startsWith("text/")
    }

    /** Checks whether a file name or MIME type corresponds to a supported PDF document. */
    fun isPdfNameOrMime(displayName: String, mimeType: String?): Boolean {
        val name = displayName.lowercase()
        val mime = mimeType?.lowercase() ?: ""
        return name.endsWith(".pdf") || mime == "application/pdf"
    }

    /** Checks whether a file name or MIME type corresponds to a supported video format (.mp4, .mkv, .webm). */
    fun isVideoNameOrMime(displayName: String, mimeType: String?): Boolean {
        val name = displayName.lowercase()
        val mime = mimeType?.lowercase() ?: ""
        return name.endsWith(".mp4") ||
            name.endsWith(".mkv") ||
            name.endsWith(".webm") ||
            mime == "video/mp4" ||
            mime == "video/x-matroska" ||
            mime == "video/webm" ||
            mime == "video/mkv"
    }


    /** Checks whether a file name or MIME type corresponds to a supported audio format. */
    fun isAudioNameOrMime(displayName: String, mimeType: String?): Boolean {
        val name = displayName.lowercase()
        return name.endsWith(".mp3") ||
            name.endsWith(".m4a") ||
            name.endsWith(".wav") ||
            mimeType?.lowercase()?.startsWith("audio/") == true
    }

    /** Checks whether a file name or MIME type corresponds to a supported image format (.jpg, .jpeg, .png, .gif). */
    fun isImageNameOrMime(displayName: String, mimeType: String?): Boolean {
        val name = displayName.lowercase()
        val mime = mimeType?.lowercase() ?: ""
        return name.endsWith(".jpg") ||
            name.endsWith(".jpeg") ||
            name.endsWith(".png") ||
            name.endsWith(".gif") ||
            mime == "image/jpeg" ||
            mime == "image/jpg" ||
            mime == "image/png" ||
            mime == "image/gif"
    }

    /** Checks whether a file name or MIME type specifically corresponds to a GIF image (.gif, image/gif). */
    fun isGifNameOrMime(displayName: String, mimeType: String?): Boolean {
        val name = displayName.lowercase()
        val mime = mimeType?.lowercase() ?: ""
        return name.endsWith(".gif") || mime == "image/gif"
    }

    /**
     * Resolves MIME type using file extension and the name/MIME heuristics above, without any platform dependency.
     * Pure business logic method suitable for fast unit testing.
     */
    fun resolveMimeTypeFromExtension(
        displayName: String,
        explicitMimeType: String? = null
    ): String {
        if (!explicitMimeType.isNullOrBlank() &&
            explicitMimeType != "*/*" &&
            explicitMimeType != "application/octet-stream"
        ) {
            return explicitMimeType
        }

        val ext = displayName.substringAfterLast('.', "").lowercase()
        when (ext) {
            "mp4" -> return "video/mp4"
            "mkv" -> return "video/x-matroska"
            "webm" -> return "video/webm"
            "jpg", "jpeg" -> return "image/jpeg"
            "png" -> return "image/png"
            "gif" -> return "image/gif"
            "pdf" -> return "application/pdf"
            "mp3" -> return "audio/mpeg"
            "m4a" -> return "audio/mp4"
            "wav" -> return "audio/wav"
            "txt", "md" -> return "text/plain"
        }

        return when {
            isImageNameOrMime(displayName, explicitMimeType) -> "image/*"
            isVideoNameOrMime(displayName, explicitMimeType) -> "video/*"
            isAudioNameOrMime(displayName, explicitMimeType) -> "audio/*"
            isPdfNameOrMime(displayName, explicitMimeType) -> "application/pdf"
            isTextNameOrMime(displayName, explicitMimeType) -> "text/plain"
            else -> "*/*"
        }
    }
}
