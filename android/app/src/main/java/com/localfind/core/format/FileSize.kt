package com.localfind.core.format

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * Formats raw byte sizes into human-readable strings (e.g., "512 B", "1.5 KB", "24.0 MB").
 *
 * Handles negative or zero values safely by returning "0 B".
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    // Calculate the base-1024 scale index (0 = B, 1 = KB, 2 = MB, 3 = GB, 4 = TB)
    val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        .coerceIn(0, units.size - 1)
    if (digitGroups == 0) return "$bytes B"
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
}
