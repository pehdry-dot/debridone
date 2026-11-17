package com.debridone.manga

import com.debridone.manga.host.HostState
import com.debridone.manga.library.MediaType
import com.debridone.manga.plugins.CompatibilityLevel
import com.debridone.manga.plugins.PackageRejectedException
import com.debridone.manga.plugins.PluginInstaller
import com.debridone.manga.sources.SourceEntry
import com.debridone.module.ModuleId
import com.debridone.module.PluginActionResult
import com.debridone.module.storage.ModuleStorage
import com.debridone.platform.AppPaths
import com.debridone.platform.secrets.FileSecretStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The module as the app runs it: storage, profiles, the extension host, installing, reading, restarting. */
class MangaModuleTest {
    private lateinit var dir: Path
    private lateinit var paths: AppPaths
    private lateinit var storage: ModuleStorage
    private lateinit var registry: StandInRegistry
    private val modules = ArrayList<MangaModule>()
    private val alice = 1001L
    private val bob = 1002L

    @BeforeTest
    fun setUp() {
        TestRuntime.runtimeOrSkip()
        dir = Files.createTempDirectory("manga-module")
        paths = AppPaths(dir.resolve("data"), dir.resolve("cache"))
        storage = ModuleStorage(ModuleId.MANGA, paths, FileSecretStore(paths.secretsDir))
        registry = StandInRegistry()
    }

    @AfterTest
    fun tearDown() {
        modules.forEach { runCatching { it.close() } }
        if (::registry.isInitialized) registry.close()
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun start(profile: Long? = alice): MangaModule = MangaModule(storage, runtimeLocator = { TestRuntime.runtimeOrSkip() }, registry = registry.url)
        .also { module ->
            modules += module
            runBlocking {
                module.start()
                module.onProfileChanged(profile)
            }
        }

    private val pluginsDir: Path get() = paths.moduleDataDir("manga").resolve("plugins")

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { source ->
                val target = to.resolve(from.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        }
    }

    private suspend fun MangaModule.awaitSources(profile: Long, count: Int): List<SourceEntry> {
        val services = services.value!!
        repeat(100) {
            val list = services.sources.list(profile)
            if (list.size == count) return list
            delay(100)
        }
        return services.sources.list(profile)
    }

    /**
     * The first milestone, at engine level, with the original package: load Tiyo, list its sources,
     * search, open a series, list chapters, read a page, save progress, restart, resume.
     */
    @Test
    fun theOriginalTiyoPackageIsSearchedReadAndResumedAfterARestart(): Unit = runBlocking {
        copyTree(TiyoFixture.pluginsDir(), pluginsDir)
        val komga = StandInKomga()
        try {
            var module = start()
            var services = module.services.value!!

            // Load the extension system and browse its sources.
            val state = services.extensions.ensureLoaded()
            assertEquals("1.0.3", state.tiyoVersion)
            val sources = services.sources.list(alice)
            assertEquals(44, sources.size)
            assertTrue(sources.all { it.enabled })
            // The package found on disk is listed in Manga's own plugin registry, next to the built-in local files.
            assertEquals(setOf("local-files", "@tiyo/core"), module.plugins.installed.value.map { it.id }.toSet())
            val status = services.plugins.installed().single()
            assertEquals(CompatibilityLevel.VERIFIED, status.compatibility.level)
            assertEquals(44, status.sourceCount)

            // Configure a source. Its settings go to the credential store, not the database.
            val source = sources.first { it.name == "Komga" }
            assertTrue(source.needsSetup)
            services.sources.saveSettings(alice, source.descriptor, buildJsonObject { komga.settings().forEach { (key, value) -> put(key, value) } })
            assertFalse(services.sources.list(alice).first { it.name == "Komga" }.needsSetup)
            assertFalse(Files.readAllBytes(storage.databaseFile).toString(Charsets.ISO_8859_1).contains("correct horse"))

            // Search for a series, in the selected source only.
            val results = services.sources.search(alice, "lanterns", listOf(source.id))
            val hit = results.single().series.single()
            assertNull(results.single().error)
            assertEquals("Paper Lanterns", hit.title)

            // Open its series page: details and chapters are stored.
            val series = services.sources.remember(alice, hit)
            assertEquals(2, services.sources.refresh(series.id))
            val stored = services.repository.series(series.id)!!
            assertEquals("Komga", stored.sourceName)
            assertEquals("S1", stored.sourceId)
            assertEquals(listOf("Test Author"), stored.authors)
            val chapters = services.repository.chapters(series.id)
            assertEquals(listOf("Paper Lanterns 001", "Paper Lanterns 002"), chapters.map { it.title })

            // Open a chapter and display actual pages.
            val open = services.pages.open(chapters[0].id)
            assertEquals("online", open.origin)
            assertEquals(5, open.pageCount)
            for (index in 0 until 3) assertTrue(services.pages.page(open, index).contentEquals(komga.book("B1")!!.pages[index]))
            // The cover comes through the source too (this server wants its login for it).
            assertTrue(services.pages.cover(stored)!!.contentEquals(komga.book("B1")!!.pages[0]))

            // Save reading progress, add to the library.
            services.repository.setInLibrary(series.id, true)
            services.repository.saveProgress(chapters[0].id, page = 2, pageCount = 5)

            // Restart.
            val pid = (services.host.state.value as HostState.Running).info.pid
            module.close()
            repeat(40) { if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(100) }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the host stops with the app")
            module = start()
            services = module.services.value!!

            // Resume: the library, the chapter and the page are there, and the source works without setting it up again.
            val resume = services.repository.continueReading(alice).single()
            assertEquals("Paper Lanterns", resume.summary.series.title)
            assertEquals(chapters[0].id, resume.chapter.id)
            assertEquals(2, resume.chapter.lastPage)
            assertEquals(5, resume.chapter.pageCount)
            // A page that was not read before the restart has to come from the source again.
            val reopened = services.pages.open(resume.chapter.id)
            assertTrue(services.pages.page(reopened, 4).contentEquals(komga.book("B1")!!.pages[4]))
            assertTrue(komga.requests.count { it.contains("/api/v1/books/B1/pages/5") } == 1)

            // Another profile has none of it: no library, no progress, and not the first profile's login.
            module.onProfileChanged(bob)
            assertTrue(services.repository.library(bob).isEmpty())
            assertTrue(services.repository.continueReading(bob).isEmpty())
            val bobSource = module.awaitSources(bob, 44).first { it.name == "Komga" }
            assertTrue(bobSource.needsSetup)
            assertNull(services.sources.storedSettings(bob, bobSource.id))
            val refused = services.sources.search(bob, "lanterns", listOf(bobSource.id)).single()
            assertNotNull(refused.error, "without the first profile's settings the source cannot reach the server")
            assertTrue(refused.series.isEmpty())
        } finally {
            komga.close()
        }
    }

    @Test
    fun aPackageIsReviewedBeforeItIsInstalledThenUpdatedDisabledAndRemoved(): Unit = runBlocking {
        registry.publish("@tiyo/core", "1.0.98", FakeTiyo.archive("1.0.98"))
        val module = start()
        val services = module.services.value!!

        // Nothing installed: no sources, and the host has not been started.
        assertTrue(services.sources.list(alice).isEmpty())
        assertEquals(0, services.host.generation)
        assertEquals(listOf("local-files"), module.plugins.installed.value.map { it.id })

        // Browse lists what the registry has.
        val offered = services.plugins.available().single()
        assertEquals("@tiyo/core", offered.name)
        assertEquals("1.0.98", offered.latest)
        assertNull(offered.installedVersion)

        // Step one: download, verify, load on trial. Nothing is in use yet.
        val prepared = services.plugins.prepare("@tiyo/core", "1.0.98")
        assertEquals(listOf("Broken Source", "Stand-in Source"), prepared.sources.map { it.name })
        assertEquals(CompatibilityLevel.UNTESTED, prepared.compatibility.level)
        assertTrue(prepared.warnings.any { it.contains("has not been tested") })
        assertEquals(listOf("https://broken.invalid", "https://pages.invalid"), prepared.sites)
        assertTrue(services.plugins.installed().isEmpty())
        assertFalse(Files.exists(pluginsDir.resolve("@tiyo")))

        // Declining leaves no trace.
        services.plugins.cancel(prepared)
        assertFalse(Files.exists(prepared.staged.root))

        // Step two: confirm.
        val installed = assertIs<PluginActionResult.Success>(module.confirmInstall(services.plugins.prepare("@tiyo/core", "1.0.98")))
        assertEquals("1.0.98", installed.plugin!!.version)
        assertEquals(setOf("local-files", "@tiyo/core"), module.plugins.installed.value.map { it.id }.toSet())
        assertEquals(2, services.sources.list(alice).size)
        assertEquals("1.0.98", services.plugins.installed().single().version)

        // Read something through it.
        val hit = services.sources.search(alice, "book", listOf(FakeTiyo.SOURCE_ID)).single().series.single()
        val series = services.sources.remember(alice, hit)
        assertEquals(MediaType.MANHWA, series.mediaType)
        services.sources.refresh(series.id)
        val chapter = services.repository.chapters(series.id).first()
        val open = services.pages.open(chapter.id)
        assertEquals(4, open.pageCount)
        assertTrue(services.pages.page(open, 3).contentEquals(FakeTiyo.pages[3]))
        services.repository.saveProgress(chapter.id, 3, 4)
        // A search across all sources reports the broken one without failing the rest.
        val all = services.sources.search(alice, "anything")
        assertEquals(2, all.size)
        assertTrue(all.any { it.error == null && it.series.isNotEmpty() })

        // An update is offered, reviewed and installed; progress is untouched.
        registry.publish("@tiyo/core", "1.0.99", FakeTiyo.archive("1.0.99"))
        assertEquals(mapOf("@tiyo/core" to "1.0.99"), services.plugins.updates())
        val update = services.plugins.prepare("@tiyo/core", "1.0.99")
        assertEquals("1.0.98", update.replacesVersion)
        assertIs<PluginActionResult.Success>(module.confirmInstall(update))
        assertEquals("1.0.99", services.plugins.installed().single().version)
        assertEquals("1.0.99", module.plugins.installed.value.first { it.id == "@tiyo/core" }.version)
        assertEquals(3, services.repository.chapter(chapter.id)!!.lastPage)
        assertTrue(services.plugins.updates().isEmpty())

        // Switched off: its code is not loaded and its sources are gone. Switched on: back.
        assertIs<PluginActionResult.Success>(module.plugins.setEnabled("@tiyo/core", false))
        assertTrue(module.awaitSources(alice, 0).isEmpty())
        assertFalse(services.plugins.installed(setOf("@tiyo/core")).single().loaded)
        assertTrue(services.extensions.state.value!!.packages.single().disabled)
        assertIs<PluginActionResult.Success>(module.plugins.setEnabled("@tiyo/core", true))
        assertEquals(2, module.awaitSources(alice, 2).size)

        // Removed: files, sources and registry entry go; what was read stays in the library's records.
        assertIs<PluginActionResult.Success>(module.plugins.remove("@tiyo/core"))
        assertFalse(Files.exists(pluginsDir.resolve("@tiyo")))
        assertTrue(module.awaitSources(alice, 0).isEmpty())
        assertEquals(listOf("local-files"), module.plugins.installed.value.map { it.id })
        assertNotNull(services.repository.series(series.id))
    }

    @Test
    fun aPackageThatIsNotAnExtensionPackageIsRejectedBeforeItIsInstalled(): Unit = runBlocking {
        registry.publish("@tiyo/core", "1.0.97", FakeTiyo.archive("1.0.97", indexSource = "exports.hello = () => 'not an extension package';"))
        val module = start()
        val services = module.services.value!!
        val failure = assertFailsWith<PackageRejectedException> { services.plugins.prepare("@tiyo/core", "1.0.97") }
        assertTrue(failure.message!!.contains("TiyoClient"), failure.message)
        assertTrue(services.plugins.installed().isEmpty())
        val staging = paths.moduleDataDir("manga").resolve("plugin-staging")
        assertTrue(!Files.exists(staging) || Files.newDirectoryStream(staging).use { it.none() })
        // A package that crashes while loading is rejected the same way, and the app's own host is unaffected.
        registry.publish("@tiyo/core", "1.0.96", FakeTiyo.archive("1.0.96", indexSource = "process.exit(9);"))
        assertFailsWith<PackageRejectedException> { services.plugins.prepare("@tiyo/core", "1.0.96") }
        // The generic import only takes what DebridOne has verified, and names what it refuses.
        assertTrue(assertIs<PluginActionResult.Failure>(module.plugins.importFromLocation("left-pad")).message.contains("not a Houdoku extension package"))
        assertTrue(assertIs<PluginActionResult.Failure>(module.plugins.importFromLocation("@tiyo/core@1.0.97")).message.contains("verified"))
        assertEquals(listOf("local-files"), module.plugins.installed.value.map { it.id })
    }

    @Test
    fun aSourceTestRecordsWhatWorkedOnThisComputer(): Unit = runBlocking {
        FakeTiyo.installInto(pluginsDir)
        val module = start()
        val services = module.services.value!!
        services.extensions.ensureLoaded()
        val good = services.sources.test(FakeTiyo.SOURCE_ID)
        assertTrue(good.ok, good.steps.toString())
        assertEquals(listOf("Browse", "Search", "Series details", "Chapters", "Pages"), good.steps.map { it.name })
        val bad = services.sources.test(FakeTiyo.BROKEN_ID)
        assertFalse(bad.ok)
        assertTrue(bad.steps.any { !it.ok })
        assertNotNull(bad.message)
        val listed = services.sources.list(alice).associateBy { it.id }
        assertTrue(listed.getValue(FakeTiyo.SOURCE_ID).health!!.ok)
        assertFalse(listed.getValue(FakeTiyo.BROKEN_ID).health!!.ok)

        // Sources are switched off and ordered per profile.
        services.sources.setEnabled(alice, FakeTiyo.BROKEN_ID, false)
        assertEquals(listOf(FakeTiyo.SOURCE_ID), services.sources.enabled(alice).map { it.id })
        assertEquals(2, services.sources.enabled(bob).size)
        services.sources.setPreferred(alice, listOf(FakeTiyo.SOURCE_ID))
        assertEquals(FakeTiyo.SOURCE_ID, services.sources.list(alice).first().id)

        // Settings are stored per profile and only for names the source declares.
        val descriptor = listed.getValue(FakeTiyo.SOURCE_ID).descriptor
        services.sources.saveSettings(alice, descriptor, buildJsonObject { put("Token", "alice-token"); put("Injected", "x"); put("__proto__", "y") })
        assertEquals(setOf("Token"), services.sources.storedSettings(alice, descriptor.id)!!.keys)
        assertEquals("alice-token", services.extensions.settings(descriptor.id)["Token"]!!.jsonPrimitive.content)
        assertNull(services.sources.storedSettings(bob, descriptor.id))
    }

    @Test
    fun deletingAProfileErasesItsMangaData(): Unit = runBlocking {
        FakeTiyo.installInto(pluginsDir)
        val module = start()
        val services = module.services.value!!
        services.extensions.ensureLoaded()
        val descriptor = services.sources.find(FakeTiyo.SOURCE_ID)!!
        services.sources.saveSettings(alice, descriptor, buildJsonObject { put("Token", "secret") })
        val series = services.sources.remember(alice, services.sources.search(alice, "book", listOf(FakeTiyo.SOURCE_ID)).single().series.single())
        services.repository.setInLibrary(series.id, true)
        services.preferences.set(MangaPreferences.LIBRARY_SORT, "PROGRESS")

        module.deleteProfileData(alice)
        assertTrue(services.repository.library(alice).isEmpty())
        assertNull(services.sources.storedSettings(alice, descriptor.id))
        module.onProfileChanged(alice)
        assertNull(services.preferences[MangaPreferences.LIBRARY_SORT])
        // The shared package is still installed for everyone else.
        assertTrue(PluginInstaller(pluginsDir, dir.resolve("w")).installed().isNotEmpty())
    }
}
