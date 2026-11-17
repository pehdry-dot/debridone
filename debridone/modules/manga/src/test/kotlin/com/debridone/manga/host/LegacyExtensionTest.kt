package com.debridone.manga.host

import com.debridone.manga.StandInKomga
import com.debridone.manga.TestRuntime
import com.debridone.manga.plugins.InstallPlan
import com.debridone.manga.plugins.PluginInstaller
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Houdoku's older extension format: a real `@houdoku/extension-komga` 1.3.1 package from the
 * registry, resolved and installed by DebridOne, loaded through the legacy adapter and exercised
 * against the stand-in Komga server.
 */
class LegacyExtensionTest {
    private lateinit var root: Path
    private lateinit var host: ExtensionHost
    private lateinit var extensions: MangaExtensions
    private lateinit var komga: StandInKomga

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("manga-legacy")
        komga = StandInKomga()
        host = TestRuntime.host(legacyPlugins(), root)
        extensions = MangaExtensions(host)
    }

    @AfterTest
    fun tearDown() {
        if (::host.isInitialized) host.close()
        if (::komga.isInitialized) komga.close()
        if (::root.isInitialized) PluginInstaller.deleteRecursively(root)
    }

    /** Installed once per build folder: resolved live (legacy packages have no pinned plan), every archive checksummed. */
    private fun legacyPlugins(): Path {
        val dir = Paths.get(System.getProperty("debridone.manga.fixtureDir") ?: "build/tiyo-fixture").resolve("legacy")
        val plugins = dir.resolve("plugins")
        val marker = dir.resolve("installed-komga-1.3.1")
        if (Files.exists(marker)) return plugins
        val outcome = runCatching {
            runBlocking {
                val resolver = TestRuntime.host(Files.createDirectories(dir.resolve("empty")), root)
                try {
                    val answer = resolver.call(
                        "plugins.resolve",
                        buildJsonObject {
                            put("name", PACKAGE)
                            put("version", "1.3.1")
                        },
                        timeoutMs = 120_000,
                    )
                    val plan = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(InstallPlan.serializer(), answer)
                    val installer = PluginInstaller(plugins, dir.resolve("work"))
                    installer.activate(installer.stage(plan, pinned = false))
                } finally {
                    resolver.close()
                }
            }
            Files.writeString(marker, "ok")
        }
        if (outcome.isFailure) {
            if (System.getenv("DEBRIDONE_REQUIRE_NODE") == "1") throw AssertionError("The legacy package could not be installed", outcome.exceptionOrNull())
            Assume.assumeTrue("The legacy package could not be installed: ${outcome.exceptionOrNull()?.message}", false)
        }
        return plugins
    }

    @Test
    fun aLegacyPackageLoadsThroughTheAdapterAndReads(): Unit = runBlocking {
        val state = extensions.ensureLoaded()
        val pkg = state.packages.single()
        assertEquals(PACKAGE, pkg.name)
        assertEquals("1.3.1", pkg.version)
        assertEquals("legacy", pkg.kind)
        assertTrue(pkg.loaded, pkg.error)
        val source = state.sources.single()
        assertEquals("Komga", source.name)
        assertEquals(PACKAGE, source.origin)
        assertEquals(setOf("Address (with port)", "Username", "Password"), source.settingTypes.keys)

        extensions.applySettings(
            source.id,
            buildJsonObject { komga.settings().forEach { (key, value) -> put(key, value) } },
        )
        val directory = extensions.directory(source.id, 1)
        assertEquals(listOf("Paper Lanterns", "Tower of Stairs"), directory.series.map { it.title })
        val series = assertNotNull(extensions.series(source.id, "S1"))
        assertEquals("Paper Lanterns", series.title)
        val chapters = extensions.chapters(source.id, "S1")
        assertEquals(listOf("B1", "B2"), chapters.map { it.sourceId })
        val pages = extensions.pages(source.id, "S1", "B1")
        assertEquals(5, pages.size)
        val image = assertIs<ImageRef.Spooled>(extensions.image(source.id, series.raw, pages[1]))
        assertTrue(Files.readAllBytes(image.file).contentEquals(komga.book("B1")!!.pages[1]))
    }

    companion object {
        const val PACKAGE = "@houdoku/extension-komga"
    }
}
