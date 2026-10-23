package com.localfind.core.library

import com.localfind.core.model.FileKind
import com.localfind.core.model.FileTypes
import com.localfind.core.model.Folder
import com.localfind.core.model.ImportedFile
import com.localfind.core.model.SearchResult

/** Pure categorisation and filtering of library files and search results. */
object LibraryFilters {

    /**
     * Determines the [GalleryCategory] for an [ImportedFile] based on its name and MIME type.
     */
    fun categorizeFile(fileName: String, mimeType: String?): GalleryCategory {
        return when {
            FileTypes.isImageNameOrMime(fileName, mimeType) -> GalleryCategory.PHOTOS
            FileTypes.isVideoNameOrMime(fileName, mimeType) -> GalleryCategory.VIDEOS
            FileTypes.isAudioNameOrMime(fileName, mimeType) -> GalleryCategory.AUDIO
            FileTypes.isPdfNameOrMime(fileName, mimeType) -> GalleryCategory.PDFS
            // Plain text, Markdown and any other supported file fall back to the text category
            else -> GalleryCategory.TEXT
        }
    }

    /**
     * Determines the [GalleryCategory] for a [SearchResult] based on its recorded `fileType` or file name.
     */
    fun categorizeSearchResult(result: SearchResult): GalleryCategory {
        return when (result.fileType.lowercase()) {
            FileKind.IMAGE.id -> GalleryCategory.PHOTOS
            FileKind.VIDEO.id -> GalleryCategory.VIDEOS
            FileKind.AUDIO.id -> GalleryCategory.AUDIO
            FileKind.PDF.id -> GalleryCategory.PDFS
            FileKind.TEXT.id -> GalleryCategory.TEXT
            else -> categorizeFile(result.fileName, null)
        }
    }

    /**
     * Filters a list of [ImportedFile] items by the selected [GalleryCategory].
     */
    fun filterFiles(files: List<ImportedFile>, category: GalleryCategory): List<ImportedFile> {
        if (category == GalleryCategory.ALL) return files
        return files.filter { categorizeFile(it.displayName, it.mimeType) == category }
    }

    /**
     * Filters a list of [ImportedFile] items located in the given [folderId]
     * (or root level if `folderId == null`), and optionally filters by [GalleryCategory].
     */
    fun filterFilesByFolder(
        files: List<ImportedFile>,
        folderId: String?,
        category: GalleryCategory = GalleryCategory.ALL
    ): List<ImportedFile> {
        val folderFiles = files.filter { it.folderId == folderId }
        return filterFiles(folderFiles, category)
    }

    /**
     * Calculates the total direct items (files + subfolders) inside a folder.
     */
    fun getFolderItemCount(
        files: List<ImportedFile>,
        folders: List<Folder>,
        folderId: String
    ): Int {
        val fileCount = files.count { it.folderId == folderId }
        val subfolderCount = folders.count { it.parentId == folderId }
        return fileCount + subfolderCount
    }

    /**
     * Returns the list of [GalleryCategory] entries that have at least one imported file
     * in the library, always preserving [GalleryCategory.ALL] as the first option.
     *
     * Used to hide category filter chips for file types that do not exist in the app,
     * reducing UI clutter and preventing searches in empty modalities.
     */
    fun getAvailableCategories(files: List<ImportedFile>): List<GalleryCategory> {
        if (files.isEmpty()) return listOf(GalleryCategory.ALL)
        val presentCategories = files.map { categorizeFile(it.displayName, it.mimeType) }.toSet()
        return GalleryCategory.entries.filter { it == GalleryCategory.ALL || it in presentCategories }
    }
}
