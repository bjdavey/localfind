package com.localfind.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageCheckTest {

    private val mb = 1024L * 1024

    @Test
    fun freeSpaceBelowReserve_isNotEnough() {
        assertFalse(StorageCheck.hasEnoughSpace(freeBytes = 50 * mb))
    }

    @Test
    fun freeSpaceAtReserve_isEnough() {
        assertTrue(StorageCheck.hasEnoughSpace(freeBytes = StorageCheck.INDEXING_RESERVE_BYTES))
    }

    @Test
    fun neededBytesAreAddedToTheReserve() {
        // 485 MB model + 100 MB reserve = 585 MB required
        assertFalse(StorageCheck.hasEnoughSpace(freeBytes = 500 * mb, neededBytes = 485 * mb))
        assertTrue(StorageCheck.hasEnoughSpace(freeBytes = 585 * mb, neededBytes = 485 * mb))
    }

    @Test
    fun recognisesSqliteAndOsFullDiskMessages() {
        assertTrue(StorageCheck.isOutOfSpaceMessage("database or disk is full (code 13 SQLITE_FULL)"))
        assertTrue(StorageCheck.isOutOfSpaceMessage("Text indexing failed: write failed: ENOSPC (No space left on device)"))
        assertTrue(StorageCheck.isOutOfSpaceMessage("No space left on device"))
    }

    @Test
    fun ignoresOtherErrors() {
        assertFalse(StorageCheck.isOutOfSpaceMessage(null))
        assertFalse(StorageCheck.isOutOfSpaceMessage("Unsupported file format"))
        assertFalse(StorageCheck.isOutOfSpaceMessage("Indexing cancelled"))
    }
}
