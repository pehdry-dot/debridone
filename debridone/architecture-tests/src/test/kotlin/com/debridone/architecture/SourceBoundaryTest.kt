package com.debridone.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * macOS file systems ignore letter case. Gradle's type-safe project accessors generate one Java
     * file per project, named after the root project's name and after each project path, so two
     * names that differ only by case become one file on a Mac and the build fails there (and only
     * there). This reproduces Gradle's naming so the mistake is caught on any system.
     */
    @Test
    fun gradleProjectAccessorNamesAreUniqueIgnoringCase() {
        val settings = File(root, "settings.gradle.kts").readText()
        val rootName = Regex("""rootProject\.name\s*=\s*"([^"]+)"""").find(settings)?.groupValues?.get(1)
            ?: fail("rootProject.name not found in settings.gradle.kts")
        val included = Regex("""include\(\s*"(:[^"]+)"\s*\)""").findAll(settings).map { it.groupValues[1] }.toList()
        assertTrue(included.any { it.startsWith(":debridone:") }, "expected :debridone projects in settings.gradle.kts")

        // Every included project and each of its parent projects gets an accessor class.
        val paths = included.flatMap { path ->
            val segments = path.removePrefix(":").split(':')
            segments.indices.map { end -> segments.subList(0, end + 1) }
        }.distinct()

        fun camel(value: String): String = value.split('-', '_', '.', ' ')
            .filter { it.isNotEmpty() }
            .joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }

        val accessorNames = listOf(camel(rootName)) + paths.map { segments -> segments.joinToString("_", transform = ::camel) }
        val clashes = accessorNames.groupBy { it.lowercase() }.filterValues { it.distinct().size > 1 }.values
        assertTrue(
            clashes.isEmpty(),
            "Gradle project accessor names that differ only by case break the build on macOS: $clashes. " +
                "Rename the root project or the clashing project.",
        )
    }

    /** Two paths that differ only by letter case cannot both exist in a checkout on macOS. */
    @Test
    fun noTrackedPathsDifferOnlyByCase() {
        val listing = runCatching {
            val process = ProcessBuilder("git", "ls-files").directory(root).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) output.lines().filter { it.isNotBlank() } else null
        }.getOrNull()
        // Not a git checkout (e.g. a source archive): scan DebridOne's own trees instead.
        val paths = listing ?: listOf("debridone", "composeApp/src/debridoneMain", "scripts/debridone").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && "/build/" !in it.invariantSeparatorsPath }
                .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
        }
        assertTrue(paths.isNotEmpty(), "no files found to check")
        val clashes = paths.groupBy { it.lowercase() }.filterValues { it.size > 1 }.values
        assertTrue(clashes.isEmpty(), "Paths that differ only by case cannot coexist on macOS: $clashes")
    }

    /**
     * Upstream's .gitignore ignores new files under `scripts/`. A script that the documentation or
     * the CI workflow tells people to run must really be in the repository, not just on the
     * author's disk.
     */
    @Test
    fun referencedScriptsAreTrackedByGit() {
        val tracked = runCatching {
            val process = ProcessBuilder("git", "ls-files").directory(root).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) output.lines().filter { it.isNotBlank() }.toSet() else null
        }.getOrNull() ?: return // Not a git checkout: nothing to compare against.

        val sources = listOf(File(root, "README.md")) +
            File(root, "debridone/docs").listFiles { file -> file.extension == "md" }.orEmpty() +
            File(root, ".github/workflows").listFiles { file -> file.extension == "yml" }.orEmpty()
        val reference = Regex("""scripts/debridone/[A-Za-z0-9._-]+\.(?:sh|py)""")
        val referenced = sources.filter { it.isFile }.flatMap { file ->
            reference.findAll(file.readText()).map { it.value to file.relativeTo(root).path }.toList()
        }
        assertTrue(referenced.isNotEmpty(), "expected the docs to reference scripts/debridone")
        val missing = referenced.filter { (script, _) -> script !in tracked }.map { (script, source) -> "$script (referenced in $source)" }.distinct()
        assertTrue(missing.isEmpty(), "Scripts referenced but not committed (check .gitignore): $missing")
    }

    /**
     * Games is not part of the application for now. Its module stays in the repository so it can
     * return, but the app neither links nor mentions it.
     */
    @Test
    fun gamesIsDormantInTheApplication() {
        assertEquals(listOf("streaming", "music", "manga"), com.debridone.module.ModuleId.active.map { it.key }, "the sections the application offers")

        val appBuild = File(root, "composeApp/build.gradle.kts").readText()
        val linked = appBuild.lineSequence().map { it.trim() }.filter { it.startsWith("implementation(") && it.contains("modules.gaming") }.toList()
        assertTrue(linked.isEmpty(), "the application must not link the gaming module: $linked")
        assertTrue(File(root, "settings.gradle.kts").readText().contains("\":debridone:modules:gaming\""), "the gaming module stays in the build so it can be brought back")
        assertTrue(File(root, "debridone/modules/gaming/src/main/kotlin/com/debridone/gaming/GamingModule.kt").isFile, "its source is kept")

        val appSources = kotlinFiles(File(root, "composeApp/src/debridoneMain")) + kotlinFiles(File(root, "composeApp/src/desktopMain"))
        val importing = appSources.filter { file -> imports(file).any { it.startsWith("com.debridone.gaming") } }
        assertTrue(importing.isEmpty(), "no application source may use the gaming module: ${importing.map { it.name }}")

        val shell = kotlinFiles(File(root, "composeApp/src/debridoneMain/kotlin/com/debridone/app/shell"))
        val mentions = shell.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val code = line.substringBefore("//").trim()
                val comment = code.startsWith("*") || code.startsWith("/*")
                if (!comment && Regex("\\bGAMES\\b|\"Games\"|\\bHydra\\b|ModuleId\\.GAMING").containsMatchIn(code)) "${file.name}:${index + 1}" else null
            }
        }
        assertTrue(mentions.isEmpty(), "the shell must not offer Games anywhere: $mentions")

        val state = File(root, "composeApp/src/debridoneMain/kotlin/com/debridone/app/shell/ShellState.kt").readText()
        val sections = Regex("""^\s{4}([A-Z_]+)\("([a-z]+)", "([A-Za-z]+)"""", RegexOption.MULTILINE).findAll(state).map { it.groupValues[3] }.toList()
        assertEquals(listOf("Home", "Streaming", "Music", "Manga", "Plugins", "Downloads", "Settings"), sections, "the sidebar's sections")
    }

    /**
     * Music has two ways to something that is not the user's own disk, and they do not mix: addons
     * (a manifest address the user pastes) and the user's own Real-Debrid cloud. The addon code
     * knows nothing of Real-Debrid, so installing an addon can never reach the cloud or its
     * credentials; and the cloud code only reads: it contains none of Real-Debrid's methods that
     * add, select or delete.
     */
    @Test
    fun musicAddonsAndTheRealDebridCloudDoNotMix() {
        val sources = File(root, "debridone/modules/music/src/main/kotlin/com/debridone/music/sources")
        fun code(name: String): List<String> = File(sources, name).readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
            .map { it.substringBefore(" // ") }

        val addonFiles = listOf("EclipseAddonAdapter.kt", "AddonManifest.kt", "MusicPluginManifest.kt")
        val cloudInAddons = addonFiles.flatMap { name -> code(name).filter { Regex("RealDebrid|real-debrid|realdebrid", RegexOption.IGNORE_CASE).containsMatchIn(it) }.map { "$name: $it" } }
        assertTrue(cloudInAddons.isEmpty(), "addon code must not know Real-Debrid:\n" + cloudInAddons.joinToString("\n"))

        val cloud = code("RealDebridAdapter.kt")
        val addonsInCloud = cloud.filter { Regex("EclipseAddon|AddonManifest|MusicPluginManifest|manifest\\.json").containsMatchIn(it) }
        assertTrue(addonsInCloud.isEmpty(), "the Real-Debrid adapter must not know addons:\n" + addonsInCloud.joinToString("\n"))
        val changing = cloud.filter { Regex("addMagnet|addTorrent|selectFiles|/delete|instantAvailability|/hosts|/settings|/streaming|\"DELETE\"|\"PUT\"").containsMatchIn(it) }
        assertTrue(changing.isEmpty(), "the Real-Debrid adapter only reads the cloud:\n" + changing.joinToString("\n"))

        // The only screens that may sign in to it are Music's own.
        val elsewhere = (kotlinFiles(File(root, "composeApp/src/debridoneMain")) + kotlinFiles(File(root, "debridone")))
            .filter { "/build/" !in it.invariantSeparatorsPath && "/modules/music/" !in it.invariantSeparatorsPath && "/app/music/" !in it.invariantSeparatorsPath && "/architecture-tests/" !in it.invariantSeparatorsPath }
            .filter { file -> imports(file).any { it.startsWith("com.debridone.music.sources.RealDebrid") } }
        assertTrue(elsewhere.isEmpty(), "only Music may use its Real-Debrid integration: ${elsewhere.map { it.name }}")
    }

    private fun kotlinFiles(dir: File): List<File> =
        if (dir.isDirectory) dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() else emptyList()

    private fun imports(file: File): List<String> =
        file.readLines().map { it.trim() }.filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }
}
