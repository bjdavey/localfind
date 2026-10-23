package com.localfind.data

import com.localfind.core.model.Folder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [FilePathHelper] original file path resolution and percent-decoding logic.
 */
class FilePathHelperTest {

    @Test
    fun resolvePathFromUri_emptyAndBlank_returnsEmptyString() {
        assertEquals("", FilePathHelper.resolvePathFromUri(""))
        assertEquals("", FilePathHelper.resolvePathFromUri("   "))
    }

    @Test
    fun resolvePathFromUri_directFilesystemPath() {
        assertEquals(
            "/storage/emulated/0/Download/document.pdf",
            FilePathHelper.resolvePathFromUri("/storage/emulated/0/Download/document.pdf")
        )
        // With percent-encoded space
        assertEquals(
            "/storage/emulated/0/Download/My Folder/document.pdf",
            FilePathHelper.resolvePathFromUri("/storage/emulated/0/Download/My%20Folder/document.pdf")
        )
    }

    @Test
    fun resolvePathFromUri_fileUriScheme() {
        // Shared import private app storage
        assertEquals(
            "/data/user/0/com.localfind/files/shared_imports/1234_notes.txt",
            FilePathHelper.resolvePathFromUri("file:///data/user/0/com.localfind/files/shared_imports/1234_notes.txt")
        )
        // Standard external storage file URI
        assertEquals(
            "/storage/emulated/0/Documents/report.pdf",
            FilePathHelper.resolvePathFromUri("file:///storage/emulated/0/Documents/report.pdf")
        )
        // Preserves literal '+' in file name
        assertEquals(
            "/storage/emulated/0/Download/C++_Primer.pdf",
            FilePathHelper.resolvePathFromUri("file:///storage/emulated/0/Download/C++_Primer.pdf")
        )
    }

    @Test
    fun resolvePathFromUri_safExternalStorageProvider_primaryVolume() {
        // Standard document picker URI
        val uri = "content://com.android.externalstorage.documents/document/primary%3ADownload%2Fsample.pdf"
        assertEquals(
            "/storage/emulated/0/Download/sample.pdf",
            FilePathHelper.resolvePathFromUri(uri)
        )

        // Nested directory with spaces
        val nestedUri = "content://com.android.externalstorage.documents/document/primary%3ADocuments%2FWork%20Files%2FSpecs%202026.pdf"
        assertEquals(
            "/storage/emulated/0/Documents/Work Files/Specs 2026.pdf",
            FilePathHelper.resolvePathFromUri(nestedUri)
        )
    }

    @Test
    fun resolvePathFromUri_safExternalStorageProvider_treeDocumentUri() {
        // Recursive folder import tree URI
        val treeDocUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments/document/primary%3ADocuments%2FProject%2Fnotes.txt"
        assertEquals(
            "/storage/emulated/0/Documents/Project/notes.txt",
            FilePathHelper.resolvePathFromUri(treeDocUri)
        )
    }

    @Test
    fun resolvePathFromUri_safExternalStorageProvider_sdCardVolume() {
        val sdUri = "content://com.android.externalstorage.documents/document/1234-5678%3ADCIM%2FCamera%2FIMG_0001.JPG"
        assertEquals(
            "/storage/1234-5678/DCIM/Camera/IMG_0001.JPG",
            FilePathHelper.resolvePathFromUri(sdUri)
        )
    }

    @Test
    fun resolvePathFromUri_safExternalStorageProvider_homeAlias() {
        val homeUri = "content://com.android.externalstorage.documents/document/home%3Ameeting_notes.txt"
        assertEquals(
            "/storage/emulated/0/Documents/meeting_notes.txt",
            FilePathHelper.resolvePathFromUri(homeUri)
        )
    }

    @Test
    fun resolvePathFromUri_safDownloadsProvider() {
        // Raw path document ID
        val rawUri = "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fmanual.pdf"
        assertEquals(
            "/storage/emulated/0/Download/manual.pdf",
            FilePathHelper.resolvePathFromUri(rawUri)
        )

        // Numeric document ID with display name
        val idUri = "content://com.android.providers.downloads.documents/document/54321"
        assertEquals(
            "/storage/emulated/0/Download/invoice.pdf",
            FilePathHelper.resolvePathFromUri(idUri, displayName = "invoice.pdf")
        )
    }

    @Test
    fun resolvePathFromUri_safMediaDocumentsProvider() {
        // Photo
        val imgUri = "content://com.android.providers.media.documents/document/image%3A999"
        assertEquals(
            "/storage/emulated/0/Pictures/sunset.jpg",
            FilePathHelper.resolvePathFromUri(imgUri, displayName = "sunset.jpg")
        )

        // Video
        val videoUri = "content://com.android.providers.media.documents/document/video%3A888"
        assertEquals(
            "/storage/emulated/0/Movies/clip.mp4",
            FilePathHelper.resolvePathFromUri(videoUri, displayName = "clip.mp4")
        )

        // Audio
        val audioUri = "content://com.android.providers.media.documents/document/audio%3A777"
        assertEquals(
            "/storage/emulated/0/Music/track.mp3",
            FilePathHelper.resolvePathFromUri(audioUri, displayName = "track.mp3")
        )

        // Document
        val docUri = "content://com.android.providers.media.documents/document/document%3A666"
        assertEquals(
            "/storage/emulated/0/Documents/paper.pdf",
            FilePathHelper.resolvePathFromUri(docUri, displayName = "paper.pdf")
        )
    }

    @Test
    fun resolvePathFromUri_genericContentUri_fallsBackToDecodedUri() {
        val genericUri = "content://com.thirdparty.provider/files%2Fshared%2Fdata.csv"
        assertEquals(
            "content://com.thirdparty.provider/files/shared/data.csv",
            FilePathHelper.resolvePathFromUri(genericUri)
        )
    }

    @Test
    fun formatUserFriendlyPath_simplifiesStoragePrefixes() {
        // Internal storage: removes /storage/emulated/0/
        assertEquals(
            "Download/car1.jpeg",
            FilePathHelper.formatUserFriendlyPath("/storage/emulated/0/Download/car1.jpeg")
        )
        assertEquals(
            "Documents/Work/notes.pdf",
            FilePathHelper.formatUserFriendlyPath("/storage/emulated/0/Documents/Work/notes.pdf")
        )

        // SD card: clarifies as SD Card/...
        assertEquals(
            "SD Card/DCIM/Camera/photo.jpg",
            FilePathHelper.formatUserFriendlyPath("/storage/1234-5678/DCIM/Camera/photo.jpg")
        )

        // App internal shared storage
        assertEquals(
            "App Storage/shared_imports/note.txt",
            FilePathHelper.formatUserFriendlyPath("/data/user/0/com.localfind/files/shared_imports/note.txt")
        )

        // Empty/blank
        assertEquals("", FilePathHelper.formatUserFriendlyPath(""))
    }

    @Test
    fun decodePercentEncoding_validEncodings() {
        // Space, slash, colon
        assertEquals(
            "primary:Download/My Document.pdf",
            FilePathHelper.decodePercentEncoding("primary%3ADownload%2FMy%20Document.pdf")
        )

        // Preserves '+' character
        assertEquals(
            "C++_Guide+Extra.pdf",
            FilePathHelper.decodePercentEncoding("C++_Guide+Extra.pdf")
        )

        // Accented characters (e.g., 'é' -> %C3%A9)
        assertEquals(
            "café_receipt.pdf",
            FilePathHelper.decodePercentEncoding("caf%C3%A9_receipt.pdf")
        )
    }

    @Test
    fun decodePercentEncoding_noPercentsOrMalformed() {
        assertEquals("normal_text.txt", FilePathHelper.decodePercentEncoding("normal_text.txt"))
        assertEquals("incomplete%", FilePathHelper.decodePercentEncoding("incomplete%"))
        assertEquals("invalid%ZZhex", FilePathHelper.decodePercentEncoding("invalid%ZZhex"))
    }
}
