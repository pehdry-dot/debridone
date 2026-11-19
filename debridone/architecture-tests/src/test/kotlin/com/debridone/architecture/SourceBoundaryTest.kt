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

    /**
     * A module's own parts: projects beside it named `<module>-<part>` (Manga's Mangayomi bridge,
     * which is built into a separate jar because it runs inside Manga's server, not in the app).
     */
    private fun partsOf(key: String): List<String> =
        File(root, "debridone/modules").listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("$key-") }.map { it.name }

    @Test
    fun moduleBuildFilesDependOnlyOnSharedInfrastructure() {
        val projectReference = Regex("""projects\.debridone\.([A-Za-z0-9.]+)|project\(\s*"(:[^"]+)"\s*\)""")
        val allowed = setOf("moduleApi", "platform", ":debridone:module-api", ":debridone:platform")
        val violations = ArrayList<String>()
        fun check(owner: String, directory: String, alsoAllowed: Set<String>) {
            val buildFile = File(root, "debridone/modules/$directory/build.gradle.kts")
            assertTrue(buildFile.isFile, "missing ${buildFile.path}")
            buildFile.readLines().filterNot { it.trim().startsWith("//") }.forEach { line ->
                projectReference.findAll(line).forEach { match ->
                    val reference = match.groupValues[1].ifEmpty { match.groupValues[2] }
                    if (reference !in allowed && reference !in alsoAllowed) violations += "$owner -> $reference"
                }
            }
        }
        for (key in moduleKeys) {
            val parts = partsOf(key)
            // A module may use its own parts; a part may use nothing but the shared infrastructure.
            check(key, key, parts.map { ":debridone:modules:$it" }.toSet())
            parts.forEach { part -> check(part, part, emptySet()) }
        }
        if (violations.isNotEmpty()) fail("Modules may depend only on :debridone:platform, :debridone:module-api and their own parts: $violations")
    }

    @Test
    fun aModulesPartsDoNotImportOtherModulesOrStreaming() {
        val violations = ArrayList<String>()
        for (key in moduleKeys) {
            val forbidden = enginePackage.filterKeys { it != key }.values + listOf("com.nuvio.", "com.debridone.accounts", "com.debridone.cloud")
            partsOf(key).forEach { part ->
                kotlinFiles(File(root, "debridone/modules/$part/src/main")).forEach { file ->
                    imports(file).filter { import -> forbidden.any(import::startsWith) }.forEach { violations += "${file.relativeTo(root)}: $it" }
                }
            }
        }
        if (violations.isNotEmpty()) fail("A module's parts must not know other modules:\n" + violations.joinToString("\n"))
    }

    @Test
    fun sharedInfrastructureDoesNotDependOnAnyModule() {
        val forbidden = enginePackage.values + "com.nuvio."
        val violations = ArrayList<String>()
        listOf("platform", "module-api", "accounts", "cloud").forEach { project ->
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
            // Nor the cloud layer: connected accounts are the application's, and a module is handed
            // only its own kind of file through an interface of its own.
            val forbidden = enginePackage.filterKeys { it != key }.values + listOf("com.nuvio.", "com.debridone.accounts", "com.debridone.cloud")
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
                listOf("com.debridone.app.streaming.", "com.nuvio.app.features.", "com.debridone.cloud", "com.debridone.app.cloud.")
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
     * (a manifest address the user pastes) and the audio of the accounts connected in Settings.
     * The addon code knows nothing of the cloud, so installing an addon can never reach an account
     * or its files. Music as a whole holds no sign-in for those accounts: it is handed names and
     * one address at a time through the one interface it defines (`CloudAudioSource`), and the only code that
     * implements that interface is the application's.
     */
    @Test
    fun musicAddonsNeverSeeTheCloud() {
        val sources = File(root, "debridone/modules/music/src/main/kotlin/com/debridone/music/sources")
        fun code(name: String): List<String> = File(sources, name).readLines()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
            .map { it.substringBefore(" // ") }

        val addonFiles = listOf("EclipseAddonAdapter.kt", "AddonManifest.kt", "MusicPluginManifest.kt")
        val cloudWords = Regex("CloudAudioSource|CloudLibraryAdapter|RealDebrid|real-debrid|realdebrid|TorBox|torbox", RegexOption.IGNORE_CASE)
        val cloudInAddons = addonFiles.flatMap { name -> code(name).filter(cloudWords::containsMatchIn).map { "$name: $it" } }
        assertTrue(cloudInAddons.isEmpty(), "addon code must not know the cloud accounts:\n" + cloudInAddons.joinToString("\n"))

        val cloudFiles = listOf("CloudAudioSource.kt", "CloudLibraryAdapter.kt")
        val addonsInCloud = cloudFiles.flatMap { name -> code(name).filter { Regex("EclipseAddon|AddonManifest|MusicPluginManifest|manifest\\.json").containsMatchIn(it) }.map { "$name: $it" } }
        assertTrue(addonsInCloud.isEmpty(), "Music's cloud source must not know addons:\n" + addonsInCloud.joinToString("\n"))

        // What Music can ask of the cloud is this and nothing more: none of it is a credential or a way to change an account.
        val asked = Regex("""^\s*(?:fun|val)\s+(\w+)""", RegexOption.MULTILINE)
            .findAll(File(sources, "CloudAudioSource.kt").readText().substringAfter("interface CloudAudioSource")).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("revision", "accounts", "audioFiles", "address", "download"), asked, "what Music may ask of the connected accounts")

        // Music's own Real-Debrid sign-in is gone: no provider API, token or device code anywhere in the engine or its screens.
        val provider = Regex("api\\.real-debrid\\.com|api\\.torbox\\.app|/oauth/v2|device/code|unrestrict/link|requestdl")
        val leftovers = (kotlinFiles(File(root, "debridone/modules/music/src/main")) + kotlinFiles(File(root, "composeApp/src/debridoneMain/kotlin/com/debridone/app/music")))
            .flatMap { file -> file.readLines().filter(provider::containsMatchIn).map { "${file.name}: ${it.trim()}" } }
        assertTrue(leftovers.isEmpty(), "Music must not talk to a debrid provider itself:\n" + leftovers.joinToString("\n"))

        // The interface is implemented by the application's cloud bridge and by nothing else.
        val implements = Regex("""(?:class|object)\s+\w+(?:\([^)]*\))?\s*:[^{]*\bCloudAudioSource\b""")
        val implementers = (kotlinFiles(File(root, "composeApp/src/debridoneMain")) + kotlinFiles(File(root, "debridone")))
            .filter { "/build/" !in it.invariantSeparatorsPath && "/src/test/" !in it.invariantSeparatorsPath && "/architecture-tests/" !in it.invariantSeparatorsPath }
            .filter { implements.containsMatchIn(it.readText()) }
            .map { it.invariantSeparatorsPath.substringAfter(root.invariantSeparatorsPath + "/") }
        assertEquals(listOf("composeApp/src/debridoneMain/kotlin/com/debridone/app/cloud/MusicCloudSource.kt"), implementers, "who may give Music cloud audio")
    }

    /**
     * The cloud layer shows and fetches what is **already** in the user's own accounts. It has no
     * way to add or look for content at a provider, and the only thing it can remove there is an
     * entry the user explicitly asked to delete: one call site, behind the confirmed action.
     */
    @Test
    fun theCloudLayerOnlyReadsExceptForTheConfirmedDeletion() {
        val sources = kotlinFiles(File(root, "debridone/cloud/src/main"))
        assertTrue(sources.isNotEmpty(), "the cloud layer's sources were not found")
        fun code(file: File): List<String> = file.readLines().map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
            .map { it.substringBefore(" // ") }

        val adding = Regex("addMagnet|addTorrent|createtorrent|createusenetdownload|createwebdownload|asynccreate|selectFiles|instantAvailability|checkcached|/search|torrentinfo|exportdata|/unrestrict/folder|/unrestrict/containerLink", RegexOption.IGNORE_CASE)
        val added = sources.flatMap { file -> code(file).filter(adding::containsMatchIn).map { "${file.name}: $it" } }
        assertTrue(added.isEmpty(), "the cloud layer must not add or look for content at a provider:\n" + added.joinToString("\n"))

        // Requests that remove something exist in the two clients only...
        val removing = Regex("/delete/|control(torrent|usenetdownload|webdownload)|\"DELETE\"")
        val elsewhere = sources.filter { it.name !in setOf("RealDebridClient.kt", "TorBoxClient.kt") }
            .flatMap { file -> code(file).filter(removing::containsMatchIn).map { "${file.name}: $it" } }
        assertTrue(elsewhere.isEmpty(), "only the provider clients may contain a removal request:\n" + elsewhere.joinToString("\n"))
        // ...and are reached from exactly one place: the explicit "delete from the cloud" action.
        val callers = sources.flatMap { file -> code(file).filter { Regex("\\bprovider\\.delete\\(").containsMatchIn(it) }.map { file.name } }
        assertEquals(listOf("CloudFileRepository.kt"), callers, "a provider's delete may be called from the confirmed deletion only")

        // Credentials stay in the cloud layer: no module engine and no module screen may use it.
        val users = moduleKeys.flatMap { key -> kotlinFiles(File(root, "debridone/modules/$key/src/main")) + kotlinFiles(File(root, "composeApp/src/debridoneMain/kotlin/com/debridone/app/$key")) }
            .filter { file -> imports(file).any { it.startsWith("com.debridone.cloud") } }
        assertTrue(users.isEmpty(), "modules must not reach the cloud layer: ${users.map { it.name }}")
    }

    /**
     * Each section is configured inside itself: its settings are a panel over its own page, and a
     * plugin's "Configure" opens in the tab it was clicked in. Nothing in a section's screens may
     * send the person to the application's Settings or Plugins section to configure that section,
     * and there is no full-page settings route to go to.
     */
    @Test
    fun sectionsAreConfiguredInPlace() {
        val app = File(root, "composeApp/src/debridoneMain/kotlin/com/debridone/app")
        val violations = ArrayList<String>()
        for (section in listOf("music", "manga")) {
            kotlinFiles(File(app, section)).forEach { file ->
                file.readLines().forEachIndexed { index, raw ->
                    val line = raw.substringBefore("//")
                    val where = "${file.relativeTo(root)}:${index + 1}"
                    if ("ShellState.openPlugins(" in line) violations += "$where leaves the section for the Plugins section"
                    if (Regex("""ShellState\.select\(\s*ShellSection\.(PLUGINS|SETTINGS)""").containsMatchIn(line)) violations += "$where leaves the section for the application's Plugins or Settings section"
                    if ("SettingsPage.APPEARANCE" in line) violations += "$where sends appearance settings to the application's Settings"
                    if (Regex("""(Music|Manga)Route\.Settings""").containsMatchIn(line)) violations += "$where uses a full-page settings route"
                }
            }
        }
        // A link from a section to one of the application's own pages (connected accounts,
        // download folders) shows that page over the section; only the shell itself navigates
        // to the Settings section.
        for (section in listOf("music", "manga", "cloud", "streaming", "modules")) {
            kotlinFiles(File(app, section)).forEach { file ->
                file.readLines().forEachIndexed { index, raw ->
                    if ("ShellState.openSettings(" in raw.substringBefore("//")) violations += "${file.relativeTo(root)}:${index + 1} jumps to the application's Settings section"
                }
            }
        }
        // The shared pieces exist and are the ones in use.
        val drawer = File(root, "composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/SideDrawer.kt")
        val configuration = File(root, "composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/PluginConfigurationPanel.kt")
        assertTrue(drawer.isFile && configuration.isFile, "the shared panel components are missing")
        fun uses(path: String, what: String) {
            val file = File(app, path)
            if (!file.isFile || what !in file.readText()) violations += "$path does not use $what"
        }
        uses("music/MusicSettingsPanel.kt", "ModuleSettingsPanel(")
        uses("manga/MangaSettings.kt", "ModuleSettingsPanel(")
        uses("music/MusicSettingsPanel.kt", "PluginConfigurationPanel(")
        uses("manga/MangaSettings.kt", "PluginConfigurationPanel(")
        val streaming = File(root, "composeApp/src/commonMain/kotlin/com/nuvio/app/StreamingSettingsOverlay.kt")
        if (!streaming.isFile || "SideDrawer(" !in streaming.readText()) violations += "Streaming's settings are not shown in the shared side panel"
        val addons = File(root, "composeApp/src/commonMain/kotlin/com/nuvio/app/features/addons/AddonConfigurationPanel.kt")
        if (!addons.isFile || "PluginConfigurationPanel(" !in addons.readText()) violations += "Streaming addons are not configured in the shared configuration panel"
        if (violations.isNotEmpty()) fail("Sections must be configured in place:\n" + violations.joinToString("\n"))
    }

    private fun kotlinFiles(dir: File): List<File> =
        if (dir.isDirectory) dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() else emptyList()

    private fun imports(file: File): List<String> =
        file.readLines().map { it.trim() }.filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }
}
