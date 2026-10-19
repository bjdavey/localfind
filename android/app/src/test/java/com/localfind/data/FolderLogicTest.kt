package com.localfind.data

import com.localfind.ui.GalleryCategory
import com.localfind.ui.ThumbnailHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for Folder data models and folder organization logic.
 */
class FolderLogicTest {

    @Test
    fun folderModel_rootFolderProperties() {
        val folder = Folder(
            id = "folder-1",
            name = "Project Work",
            parentId = null
        )
        assertEquals("folder-1", folder.id)
        assertEquals("Project Work", folder.name)
        assertNull(folder.parentId)
    }

    @Test
    fun folderModel_subfolderProperties() {
        val subfolder = Folder(
            id = "folder-sub-1",
            name = "Receipts",
            parentId = "folder-1"
        )
        assertEquals("folder-sub-1", subfolder.id)
        assertEquals("Receipts", subfolder.name)
        assertEquals("folder-1", subfolder.parentId)
    }

    @Test
    fun importedFile_withFolderId() {
        val rootFile = ImportedFile(
            uri = "content://media/file/1",
            displayName = "doc1.txt",
            mimeType = "text/plain",
            size = 100L,
            folderId = null
        )
        val folderFile = ImportedFile(
            uri = "content://media/file/2",
            displayName = "photo.jpg",
            mimeType = "image/jpeg",
            size = 200L,
            folderId = "folder-1"
        )
        assertNull(rootFile.folderId)
        assertEquals("folder-1", folderFile.folderId)
    }

    @Test
    fun filterFilesByFolder_separatesRootAndFolderFiles() {
        val fileRoot = ImportedFile(
            uri = "content://media/1",
            displayName = "root.txt",
            mimeType = "text/plain",
            size = 50L,
            folderId = null
        )
        val fileFolderA = ImportedFile(
            uri = "content://media/2",
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            size = 50L,
            folderId = "folder-a"
        )
        val fileFolderB = ImportedFile(
            uri = "content://media/3",
            displayName = "b.pdf",
            mimeType = "application/pdf",
            size = 50L,
            folderId = "folder-b"
        )
        val allFiles = listOf(fileRoot, fileFolderA, fileFolderB)

        // Root files only
        val rootFiles = ThumbnailHelper.filterFilesByFolder(allFiles, folderId = null)
        assertEquals(1, rootFiles.size)
        assertEquals("root.txt", rootFiles[0].displayName)

        // Folder A files only
        val folderAFiles = ThumbnailHelper.filterFilesByFolder(allFiles, folderId = "folder-a")
        assertEquals(1, folderAFiles.size)
        assertEquals("a.jpg", folderAFiles[0].displayName)

        // Folder A with category filter
        val folderAPhotos = ThumbnailHelper.filterFilesByFolder(allFiles, folderId = "folder-a", category = GalleryCategory.PHOTOS)
        assertEquals(1, folderAPhotos.size)
        val folderAPDFs = ThumbnailHelper.filterFilesByFolder(allFiles, folderId = "folder-a", category = GalleryCategory.PDFS)
        assertEquals(0, folderAPDFs.size)
    }

    @Test
    fun getFolderItemCount_countsFilesAndSubfolders() {
        val folders = listOf(
            Folder(id = "root-folder", name = "My Project", parentId = null),
            Folder(id = "sub-1", name = "Subfolder 1", parentId = "root-folder"),
            Folder(id = "sub-2", name = "Subfolder 2", parentId = "root-folder"),
            Folder(id = "other", name = "Other", parentId = null)
        )
        val files = listOf(
            ImportedFile(uri = "1", displayName = "f1.txt", mimeType = "text/plain", size = 10L, folderId = "root-folder"),
            ImportedFile(uri = "2", displayName = "f2.jpg", mimeType = "image/jpeg", size = 10L, folderId = "root-folder"),
            ImportedFile(uri = "3", displayName = "f3.png", mimeType = "image/png", size = 10L, folderId = "sub-1"),
            ImportedFile(uri = "4", displayName = "root.txt", mimeType = "text/plain", size = 10L, folderId = null)
        )

        // root-folder has 2 subfolders + 2 files = 4 direct items
        val countRootFolder = ThumbnailHelper.getFolderItemCount(files, folders, "root-folder")
        assertEquals(4, countRootFolder)

        // sub-1 has 0 subfolders + 1 file = 1 direct item
        val countSub1 = ThumbnailHelper.getFolderItemCount(files, folders, "sub-1")
        assertEquals(1, countSub1)

        // sub-2 has 0 subfolders + 0 files = 0 direct items
        val countSub2 = ThumbnailHelper.getFolderItemCount(files, folders, "sub-2")
        assertEquals(0, countSub2)
    }
}
