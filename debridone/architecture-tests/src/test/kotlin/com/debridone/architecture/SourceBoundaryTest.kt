package com.debridone.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Static guard rails. These scan build files and sources so that a forbidden dependency fails the
 * build even before anything runs.
 */
class SourceBoundaryTest {
    private val root = File(
        System.getProperty("debridone.repo.root") ?: error("debridone.repo.root system property is not set"),
    )
    private val moduleKeys = listOf("music", "gaming", "manga")

    /** Package each engine lives in, and the UI package that may host it inside the app. */
    private val enginePackage = mapOf("music" to "com.debridone.music", "gaming" to "com.debridone.gaming", "manga" to "com.debridone.manga")

    @Test
    fun moduleBuildFilesDependOnlyOnSharedInfrastructure() {
        val projectReference = Regex("""projects\.debridone\.([A-Za-z0-9.]+)|project\(\s*"(:[^"]+)"\s*\)""")
        val allowed = setOf("moduleApi", "platform", ":debridone:module-api", ":debridone:platform")
        val violations = ArrayList<String>()
        for (key in moduleKeys) {
            val buildFile = File(root, "debridone/modules/$key/build.gradle.kts")
            assertTrue(buildFile.isFile, "missing ${buildFile.path}")
            buildFile.readLines().filterNot { it.trim().startsWith("//") }.forEach { line ->
                projectReference.findAll(line).forEach { match ->
                    val reference = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    if (reference !in allowed) violations += "$key -> $reference"
                }
            }
        }
        if (violations.isNotEmpty()) fail("Modules may depend only on :debridone:platform and :debridone:module-api: $violations")
    }

    @Test
    fun sharedInfrastructureDoesNotDependOnAnyModule() {
        val forbidden = enginePackage.values + "com.nuvio."
        val violations = ArrayList<String>()
        listOf("platform", "module-api", "accounts").forEach { project ->
            kotlinFiles(File(root, "debridone/$project/src/main")).forEach { file ->
                imports(file).filter { import -> forbidden.any(import::startsWith) }
                    .forEach { violations += "${file.relativeTo(root)}: $it" }
            }
        }
        if (violations.isNotEmpty()) fail("Shared infrastructure must not know any entertainment module:\n" + violations.joinToString("\n"))
    }

    @Test
    fun moduleEnginesDoNotImportEachOtherOrStreaming() {
        val violations = ArrayList<String>()
        for (key in moduleKeys) {
            val forbidden = enginePackage.filterKeys { it != key }.values + listOf("com.nuvio.", "com.debridone.accounts")
            kotlinFiles(File(root, "debridone/modules/$key/src/main")).forEach { file ->
                imports(file).filter { import -> forbidden.any(import::startsWith) }
                    .forEach { violations += "${file.relativeTo(root)}: $it" }
            }
        }
        if (violations.isNotEmpty()) fail("Cross-module imports found:\n" + violations.joinToString("\n"))
    }

    /**
     * Module screens live inside the app under `com.debridone.app.<module>`. A module's screens may
     * use that module's engine only: never another module's engine or screens, and never Nuvio's
     * streaming features.
     */
    @Test
    fun moduleScreensStayInsideTheirModule() {
        val uiRoot = File(root, "composeApp/src/debridoneMain/kotlin/com/debridone/app")
        if (!uiRoot.isDirectory) return
        val violations = ArrayList<String>()
        for (key in moduleKeys) {
            val forbidden = enginePackage.filterKeys { it != key }.values +
                moduleKeys.filter { it != key }.map { "com.debridone.app.$it." } +
                listOf("com.debridone.app.streaming.", "com.nuvio.app.features.")
            kotlinFiles(File(uiRoot, key)).forEach { file ->
                imports(file).filter { import -> forbidden.any(import::startsWith) }
                    .forEach { violations += "${file.relativeTo(root)}: $it" }
            }
        }
        if (violations.isNotEmpty()) fail("Module screens reach outside their module:\n" + violations.joinToString("\n"))
    }

    @Test
    fun noBooksOrEbookModuleExists() {
        val names = (File(root, "debridone/modules").list().orEmpty().toList() +
            File(root, "composeApp/src/debridoneMain/kotlin/com/debridone/app").list().orEmpty().toList())
            .map { it.lowercase() }
        val banned = names.filter { it in setOf("books", "ebooks", "ebook", "book", "reader-epub") }
        assertTrue(banned.isEmpty(), "The Books/Ebooks module was removed from the project and must not return: $banned")
    }

    private fun kotlinFiles(dir: File): List<File> =
        if (dir.isDirectory) dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() else emptyList()

    private fun imports(file: File): List<String> =
        file.readLines().map { it.trim() }.filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }
}
