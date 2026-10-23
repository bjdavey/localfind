package com.localfind.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UriGrantLimitTest {

    @Test
    fun belowWarningThreshold_isNotNearLimit() {
        assertFalse(UriGrantLimit.isNearLimit(0))
        assertFalse(UriGrantLimit.isNearLimit(UriGrantLimit.WARN_AT - 1))
    }

    @Test
    fun atOrAboveWarningThreshold_isNearLimit() {
        assertTrue(UriGrantLimit.isNearLimit(UriGrantLimit.WARN_AT))
        assertTrue(UriGrantLimit.isNearLimit(UriGrantLimit.MAX_GRANTS))
    }

    @Test
    fun remaining_neverGoesNegative() {
        assertEquals(UriGrantLimit.MAX_GRANTS, UriGrantLimit.remaining(0))
        assertEquals(12, UriGrantLimit.remaining(500))
        assertEquals(0, UriGrantLimit.remaining(UriGrantLimit.MAX_GRANTS + 50))
    }
}
