package com.localfind.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the portability rule for `com.localfind.core.*`: this code must not depend on Android,
 * AndroidX, MediaPipe, PDFBox or any non-core LocalFind package, so it can be shared with other
 * platforms later. Only Kotlin/Java standard library imports and other core packages are allowed.
 */
class CoreIsPureTest {

    private val allowedImportPrefixes = listOf("java.", "kotlin.", "com.localfind.core.")

    @Test
    fun coreSourcesImportOnlyStandardLibraryAndCore() {
        // Unit tests run with the module directory (app/) as working directory
        val coreDir = File("src/main/java/com/localfind/core")
        assertTrue("core source folder not found at ${coreDir.absolutePath}", coreDir.isDirectory)

        val violations = coreDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines()
                    .map { it.trim() }
                    .filter { it.startsWith("import ") }
                    .map { it.removePrefix("import ").substringBefore(" as ").trim() }
                    .filter { import -> allowedImportPrefixes.none { import.startsWith(it) } }
                    .map { "${file.name}: import $it" }
            }
            .toList()

        assertTrue("Platform imports found in core:\n" + violations.joinToString("\n"), violations.isEmpty())
    }
}
