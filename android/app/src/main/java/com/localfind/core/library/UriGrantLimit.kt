package com.localfind.core.library

/**
 * Android limits how many persistable URI grants one app may hold (512 since Android 11; LocalFind's
 * minSdk is 31). Once the limit is exceeded the system silently drops the OLDEST grants, and those files
 * can then no longer be opened or re-indexed.
 *
 * One grant is used per picked file, but only ONE per imported folder (the tree grant covers every file
 * below it), so users with many files should be steered towards folder import.
 */
object UriGrantLimit {
    /** Maximum persisted URI grants per app on Android 11+. */
    const val MAX_GRANTS = 512

    /** Warn from here on (~90% of the limit) so users still have room to react. */
    const val WARN_AT = 460

    fun isNearLimit(grantCount: Int): Boolean = grantCount >= WARN_AT

    /** Grants that can still be taken before the system starts dropping the oldest ones. */
    fun remaining(grantCount: Int): Int = (MAX_GRANTS - grantCount).coerceAtLeast(0)
}
