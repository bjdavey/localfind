package com.localfind.core.format

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [FileNames.uniqueName]. */
class FileNamesTest {

    @Test
    fun uniqueName_returnsNameWhenFree() {
        assertEquals("note.txt", FileNames.uniqueName("note.txt") { false })
    }

    @Test
    fun uniqueName_countsUpBeforeTheExtension() {
        val taken = setOf("note.txt", "note_1.txt", "note_2.txt")
        assertEquals("note_3.txt", FileNames.uniqueName("note.txt") { it in taken })
    }

    @Test
    fun uniqueName_worksWithoutExtension() {
        val taken = setOf("README", "README_1")
        assertEquals("README_2", FileNames.uniqueName("README") { it in taken })
    }
}
