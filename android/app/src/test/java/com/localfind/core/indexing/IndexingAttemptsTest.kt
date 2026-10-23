package com.localfind.core.indexing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the retry limit of files that keep being interrupted abnormally. */
class IndexingAttemptsTest {

    @Test
    fun retriesUntilTheLimitIsReached() {
        assertEquals(3, IndexingAttempts.MAX_INTERRUPTED_ATTEMPTS)
        assertFalse(IndexingAttempts.shouldGiveUp(1))
        assertFalse(IndexingAttempts.shouldGiveUp(2))
        assertTrue(IndexingAttempts.shouldGiveUp(3))
        assertTrue(IndexingAttempts.shouldGiveUp(10))
    }

    @Test
    fun aFileThatNeverWasInterruptedIsNotGivenUp() {
        assertFalse(IndexingAttempts.shouldGiveUp(0))
    }
}
