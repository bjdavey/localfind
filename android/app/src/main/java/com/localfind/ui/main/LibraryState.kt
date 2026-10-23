package com.localfind.ui.main

import android.content.Context
import com.localfind.data.FileAvailability
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.work.WorkManager
import com.localfind.core.library.UriGrantLimit
import com.localfind.core.model.Folder
import com.localfind.core.model.ImportedFile
import com.localfind.core.model.IndexingStatus
import com.localfind.core.model.ModelStatus
import com.localfind.data.EngineManager
import com.localfind.data.FileImporter
import com.localfind.data.FileStore
import com.localfind.data.ShareImporter
import com.localfind.index.IndexingProgressState
import com.localfind.index.IndexingQueue
import com.localfind.ui.gallery.ThumbnailHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the library (imported files, folders, indexed chunk count) shown on the main screen together
 * with the operations that change it: importing, removing, folder management, moving and indexing control.
 *
 * Plain state holder (no ViewModel): it lives as long as the main screen composition, like
 * [com.localfind.ui.viewer.ViewerState]. State is Compose snapshot state, so reads recompose the UI.
 */
@Stable
class LibraryState(
    private val context: Context,
    private val scope: CoroutineScope
) {
    /** Short status line, e.g. "Imported 3 file(s)". */
    var status by mutableStateOf("Ready")
    var importedFiles by mutableStateOf<List<ImportedFile>>(emptyList())
    var folders by mutableStateOf<List<Folder>>(emptyList())

    /** Folder currently shown in the gallery, or null for the library root. */
    var currentFolderId by mutableStateOf<String?>(null)
    var indexedChunksCount by mutableIntStateOf(0)

    /** Called with the URIs of files that were just removed, so search results can drop them. */
    var onFilesRemoved: (Set<String>) -> Unit = {}

    /** Called when a file's original is no longer available, so the UI can offer to remove it. */
    var onMissingFile: (ImportedFile) -> Unit = {}

    /**
     * Retries loading the model and resumes background indexing if files are pending.
     */
    fun retryLoadModel() {
        scope.launch(Dispatchers.IO) {
            status = "Loading model..."
            val result = EngineManager.ensureModelLoaded(context)
            withContext(Dispatchers.Main) {
                if (result is ModelStatus.Loaded) {
                    status = "Model loaded successfully"
                    Toast.makeText(context, "Model loaded (${result.sizeBytes / 1_048_576} MB)", Toast.LENGTH_SHORT).show()
                } else if (result is ModelStatus.Error) {
                    status = "Model load failed"
                }
            }
            if (result is ModelStatus.Loaded) {
                val store = FileStore.getInstance(context)
                if (store.getPendingFiles().isNotEmpty()) {
                    IndexingQueue.enqueue(context, replace = true)
                }
            }
        }
    }

    /**
     * Queries [EmbeddingStore] via [EngineManager] to count total chunks in the vector database.
     */
    suspend fun refreshIndexCount() {
        withContext(Dispatchers.IO) {
            EngineManager.withStore(context) { store ->
                try {
                    indexedChunksCount = store.count()
                } catch (_: Exception) {
                    // Ignore if database is temporarily busy
                }
            }
        }
    }

    suspend fun reloadFiles() {
        withContext(Dispatchers.IO) {
            val store = FileStore.getInstance(context)
            importedFiles = store.getAll()
            folders = store.getAllFolders()
        }
        refreshIndexCount()
    }

    /**
     * Starts (or appends to) background indexing, unless the user paused the queue,
     * in which case only the paused banner is refreshed. Blocking: call from a background dispatcher.
     */
    fun enqueueOrMarkPaused(replace: Boolean = false) {
        if (IndexingQueue.isPaused(context)) {
            val pendingCount = FileStore.getInstance(context).getPendingFiles().size
            IndexingProgressState.setPaused("Paused ($pendingCount pending)")
        } else {
            IndexingQueue.enqueue(context, replace)
        }
    }

    /** Loads the model and persisted library on startup, resuming interrupted indexing unless paused. */
    suspend fun loadInitial() {
        withContext(Dispatchers.IO) {
            EngineManager.ensureModelLoaded(context)
            val store = FileStore.getInstance(context)
            // Re-queue files left in INDEXING status by an interrupted session (or fail them if they keep crashing)
            val pending = store.getPendingFiles()
            for (f in pending) {
                if (f.indexingStatus == IndexingStatus.INDEXING) {
                    IndexingQueue.recoverInterruptedFile(context, store, f)
                }
            }
            importedFiles = store.getAll()
            folders = store.getAllFolders()
            // If the app was closed/killed while indexing was in-flight, resume immediately via WorkManager unless paused
            if (store.getPendingFiles().isNotEmpty()) {
                enqueueOrMarkPaused(replace = true)
            }
        }
        refreshIndexCount()
    }

    /** Imports files or a note shared from another app (ACTION_SEND / ACTION_SEND_MULTIPLE). */
    suspend fun importShared(shareIntent: Intent) {
        status = "Importing shared content..."
        val result = withContext(Dispatchers.IO) {
            val res = ShareImporter.processShareIntent(context, shareIntent, folderId = currentFolderId)
            if (res.importedFiles.isNotEmpty()) {
                val store = FileStore.getInstance(context)
                store.insertAll(res.importedFiles)
            }
            res
        }
        reloadFiles()

        if (result.importedFiles.isNotEmpty()) {
            withContext(Dispatchers.IO) { enqueueOrMarkPaused() }

            val msg = if (result.isTextNote) {
                "Imported shared note"
            } else if (result.unsupportedCount > 0) {
                "Imported ${result.importedFiles.size} file(s) from share. Skipped ${result.unsupportedCount} unsupported file(s)."
            } else {
                "Imported ${result.importedFiles.size} file(s) from share"
            }
            status = msg
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } else if (result.unsupportedCount > 0) {
            val msg = "Cannot import unsupported file(s). Only files with builtin viewers (text, PDF, image, audio, video) are supported."
            status = msg
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Warns when the app is close to Android's persisted-URI-grant limit: past it the system silently drops
     * the oldest grants and those files stop opening. Importing whole folders costs one grant per folder.
     */
    private suspend fun warnIfGrantsNearLimit() {
        val grants = withContext(Dispatchers.IO) { FileImporter.persistedGrantCount(context.contentResolver) }
        if (UriGrantLimit.isNearLimit(grants)) {
            Toast.makeText(
                context,
                "Android only lets LocalFind keep access to ${UriGrantLimit.MAX_GRANTS} picked files " +
                    "($grants used). Import whole folders instead of single files to avoid losing access to older ones.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Imports the files chosen in the system document picker into the current folder. */
    fun importPicked(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            scope.launch {
                val (supportedFiles, unsupportedCount) = withContext(Dispatchers.IO) {
                    val store = FileStore.getInstance(context)
                    val supported = mutableListOf<ImportedFile>()
                    var unsupported = 0

                    for (uri in uris) {
                        val imported = FileImporter.importUri(context.contentResolver, uri, folderId = currentFolderId)
                        if (imported != null) {
                            supported.add(imported.copy(indexingStatus = IndexingStatus.PENDING))
                        } else {
                            unsupported++
                        }
                    }

                    if (supported.isNotEmpty()) {
                        store.insertAll(supported)
                        importedFiles = store.getAll()
                    }

                    Pair(supported, unsupported)
                }

                if (supportedFiles.isNotEmpty()) {
                    withContext(Dispatchers.IO) { enqueueOrMarkPaused() }
                    if (unsupportedCount > 0) {
                        val msg = "Imported ${supportedFiles.size} file(s). Skipped $unsupportedCount unsupported file(s)."
                        status = msg
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    } else {
                        val msg = "Imported ${supportedFiles.size} file(s)"
                        status = msg
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                } else if (unsupportedCount > 0) {
                    val msg = "Cannot import unsupported file(s). Only files with builtin viewers (text, PDF, image, audio, video) are supported."
                    status = msg
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                warnIfGrantsNearLimit()
            }
        }
    }

    /** Imports the folder tree chosen in the system folder picker as a nested library folder. */
    fun importTree(treeUri: Uri?) {
        if (treeUri != null) {
            scope.launch {
                status = "Importing folder..."
                val result = withContext(Dispatchers.IO) {
                    FileImporter.importTreeUri(context, treeUri, parentFolderId = currentFolderId)
                }
                reloadFiles()
                if (result.importedFiles.isNotEmpty()) {
                    withContext(Dispatchers.IO) { enqueueOrMarkPaused() }
                    val folderName = result.createdFolders.firstOrNull()?.name ?: "folder"
                    val msg = "Imported ${result.importedFiles.size} file(s) into \"$folderName\"" +
                            if (result.skippedUnsupportedCount > 0) " (${result.skippedUnsupportedCount} unsupported skipped)" else ""
                    status = msg
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                } else if (result.createdFolders.isNotEmpty()) {
                    val folderName = result.createdFolders.first().name
                    val msg = if (result.skippedUnsupportedCount > 0)
                        "Created folder \"$folderName\" (${result.skippedUnsupportedCount} unsupported file(s) skipped)"
                    else "Created empty folder \"$folderName\""
                    status = msg
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Releases everything tied to a library file before its record is deleted: aborts any in-flight
     * indexing, evicts the cached preview, removes the private copy of shared files and drops the
     * persistable SAF read grant. Blocking: call from a background dispatcher.
     */
    fun releaseFileResources(uri: String) {
        IndexingQueue.cancelFile(context, uri)
        ThumbnailHelper.evictPreview(uri)
        ShareImporter.deleteInternalFileIfShared(context, uri)
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // Non-SAF URIs, folder-tree children or revoked grants don't have persistable grants
        }
    }

    /** Deletes the embedding vectors of [uris], tolerating a temporarily inaccessible store. */
    suspend fun deleteEmbeddings(uris: List<String>) {
        EngineManager.withStore(context) { store ->
            for (uri in uris) {
                try {
                    store.deleteByFileUri(uri)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Removes an imported file from the local library:
     * 1. Releases persistable SAF URI read grant (if held) and evicts cached thumbnail preview.
     * 2. Deletes the file metadata record from [FileStore].
     * 3. Deletes all associated embedding vectors from [EmbeddingStore].
     * 4. Updates library UI state and refreshes the total indexed chunks count.
     */
    fun removeFile(file: ImportedFile) {
        scope.launch {
            status = "Removing ${file.displayName}..."
            withContext(Dispatchers.IO) {
                // If file is actively indexing or queued, abort it immediately
                releaseFileResources(file.uri)

                val fileStore = FileStore.getInstance(context)
                fileStore.delete(file.uri)
                val updatedFiles = fileStore.getAll()

                deleteEmbeddings(listOf(file.uri))

                withContext(Dispatchers.Main) {
                    importedFiles = updatedFiles
                    onFilesRemoved(setOf(file.uri))
                }
            }

            refreshIndexCount()
            status = "Removed ${file.displayName}"
            Toast.makeText(
                context,
                "Removed ${file.displayName} from library",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Batch removes multiple imported files from the local library.
     */
    fun removeFiles(files: List<ImportedFile>) {
        if (files.isEmpty()) return
        scope.launch {
            val count = files.size
            status = "Removing $count file(s)..."
            withContext(Dispatchers.IO) {
                val uris = files.map { it.uri }
                uris.forEach { releaseFileResources(it) }

                val fileStore = FileStore.getInstance(context)
                fileStore.deleteFiles(uris)
                val updatedFiles = fileStore.getAll()

                deleteEmbeddings(uris)

                withContext(Dispatchers.Main) {
                    importedFiles = updatedFiles
                    onFilesRemoved(uris.toSet())
                }
            }

            refreshIndexCount()
            status = "Removed $count file(s)"
            Toast.makeText(
                context,
                "Removed $count file(s) from library",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun createFolder(name: String) {
        scope.launch(Dispatchers.IO) {
            val store = FileStore.getInstance(context)
            store.createFolder(name, currentFolderId)
            reloadFiles()
        }
    }

    fun renameFolder(folder: Folder, newName: String) {
        scope.launch(Dispatchers.IO) {
            val store = FileStore.getInstance(context)
            store.renameFolder(folder.id, newName)
            reloadFiles()
        }
    }

    fun deleteFolder(folder: Folder) {
        scope.launch {
            status = "Deleting folder ${folder.name}..."
            val deletedUris = withContext(Dispatchers.IO) {
                val store = FileStore.getInstance(context)
                val uris = store.deleteFolder(folder.id)
                if (uris.isNotEmpty()) {
                    // Cancel any in-flight indexing, clear resume checkpoints and drop vectors
                    uris.forEach { releaseFileResources(it) }
                    deleteEmbeddings(uris)
                }
                uris
            }
            if (deletedUris.isNotEmpty()) onFilesRemoved(deletedUris.toSet())
            if (currentFolderId == folder.id) {
                currentFolderId = folder.parentId
            }
            reloadFiles()
            val msg = "Removed folder and ${deletedUris.size} file(s)"
            status = msg
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun moveFilesToFolder(files: List<ImportedFile>, targetFolderId: String?, showToast: Boolean = true) {
        if (files.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val store = FileStore.getInstance(context)
            store.updateFilesFolder(files.map { it.uri }, targetFolderId)
            reloadFiles()
            if (showToast) {
                withContext(Dispatchers.Main) {
                    val targetName = if (targetFolderId == null) "Library Root" else folders.firstOrNull { it.id == targetFolderId }?.name ?: "folder"
                    Toast.makeText(context, "Moved ${files.size} file(s) to $targetName", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun moveFileToFolder(file: ImportedFile, targetFolderId: String?) =
        moveFilesToFolder(listOf(file), targetFolderId, showToast = false)

    /** Re-queues a failed file for indexing, unless its original file is gone. */
    fun retryFile(file: ImportedFile) {
        scope.launch(Dispatchers.IO) {
            if (!FileAvailability.isFileAvailable(context, file.uri)) {
                withContext(Dispatchers.Main) { onMissingFile(file) }
            } else {
                val store = FileStore.getInstance(context)
                store.updateStatus(file.uri, IndexingStatus.PENDING)
                importedFiles = store.getAll()
                IndexingQueue.enqueue(context, replace = true)
            }
        }
    }

    /** Re-queues every failed file for indexing. */
    fun retryAllFailed() {
        scope.launch(Dispatchers.IO) {
            val store = FileStore.getInstance(context)
            store.markFailedAsPending()
            importedFiles = store.getAll()
            IndexingQueue.enqueue(context, replace = true)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Retrying failed files...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Pauses the indexing queue; progress of the current file is kept for resuming. */
    fun pauseIndexing() {
        scope.launch(Dispatchers.IO) {
            IndexingQueue.pause(context)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Paused", Toast.LENGTH_SHORT).show()
            }
            reloadFiles()
        }
    }

    /** Resumes a paused indexing queue. */
    fun resumeIndexing() {
        scope.launch(Dispatchers.IO) {
            status = "Resuming..."
            IndexingQueue.resume(context)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Resumed", Toast.LENGTH_SHORT).show()
            }
            reloadFiles()
        }
    }

    /** Cancels all queued and running indexing. */
    fun cancelIndexing() {
        scope.launch(Dispatchers.IO) {
            IndexingQueue.cancel(context)
            withContext(Dispatchers.Main) {
                status = "Ready"
                Toast.makeText(context, "Analysis cancelled", Toast.LENGTH_SHORT).show()
            }
            reloadFiles()
        }
    }

    /** Cancels indexing of a single file. */
    fun cancelFile(file: ImportedFile) {
        scope.launch(Dispatchers.IO) {
            IndexingQueue.cancelFile(context, file.uri)
            withContext(Dispatchers.Main) {
                status = "Ready"
                Toast.makeText(context, "Cancelled sync for ${file.displayName}", Toast.LENGTH_SHORT).show()
            }
            reloadFiles()
        }
    }
}

/** Creates the [LibraryState] for the main screen. */
@Composable
fun rememberLibraryState(): LibraryState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { LibraryState(context, scope) }
}
