package com.localfind.core.format

/** Pure helpers for naming files that are copied into app storage. */
object FileNames {

    /** Replaces filesystem-illegal or problematic characters in file names. */
    fun sanitize(name: String): String {
        return name.replace(Regex("[/\\\\?%*:|\"<>]"), "_").trim()
    }

    /**
     * Returns [name], or `<base>_<n>.<ext>` for the first n >= 1 that does not already exist,
     * so a new file never overwrites an existing one.
     *
     * @param exists Whether a candidate file name is already taken in the target folder.
     */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        val nameWithoutExt = name.substringBeforeLast('.')
        val ext = if (name.contains('.')) ".${name.substringAfterLast('.')}" else ""

        var candidate = name
        var index = 1
        while (exists(candidate)) {
            candidate = "${nameWithoutExt}_$index$ext"
            index++
        }
        return candidate
    }
}
