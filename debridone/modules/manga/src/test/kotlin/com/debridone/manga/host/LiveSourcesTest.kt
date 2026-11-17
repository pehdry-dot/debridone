package com.debridone.manga.host

import com.debridone.manga.TestRuntime
import com.debridone.manga.TiyoFixture
import com.debridone.manga.plugins.InstallPlan
import com.debridone.manga.plugins.PluginInstaller
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The original Tiyo sources against the real services. Only run with DEBRIDONE_LIVE_TESTS=1.
 *
 * Test content: MangaDex's own "Official Test Manga" (an entry MangaDex keeps for API testing) and
 * the public demo of Komga, which serves public-domain comics.
 */
class LiveSourcesTest {
    private lateinit var root: Path
    private lateinit var host: ExtensionHost
    private lateinit var extensions: MangaExtensions

    @BeforeTest
    fun setUp() {
        TestRuntime.assumeLive()
        root = Files.createTempDirectory("manga-live")
        host = TestRuntime.host(TiyoFixture.pluginsDir(), root)
        extensions = MangaExtensions(host)
    }

    @AfterTest
    fun tearDown() {
        if (::host.isInitialized) host.close()
        if (::root.isInitialized) PluginInstaller.deleteRecursively(root)
    }

    @Test
    fun mangaDexSearchDetailsChaptersAndPageList(): Unit = runBlocking {
        val source = extensions.ensureLoaded().sources.first { it.name == "MangaDex" }
        val found = extensions.search(source.id, "Official \"Test\" Manga", 1)
        val hit = assertNotNull(found.series.firstOrNull { it.sourceId == TEST_MANGA }, "the test title is found by search")
        assertEquals("Official \"Test\" Manga", hit.title)
        assertTrue(hit.coverUrl.startsWith("https://uploads.mangadex.org/covers/$TEST_MANGA/"))

        val series = assertNotNull(extensions.series(source.id, TEST_MANGA))
        assertEquals("Official \"Test\" Manga", series.title)
        assertEquals("ENGLISH", series.originalLanguage)

        val chapters = extensions.chapters(source.id, TEST_MANGA)
        assertTrue(chapters.size > 100, "chapters: ${chapters.size}")
        assertTrue(chapters.any { it.language == "ENGLISH" })

        val chapter = chapters.first { it.language == "ENGLISH" }
        val pages = extensions.pages(source.id, TEST_MANGA, chapter.sourceId)
        assertTrue(pages.isNotEmpty())
        assertTrue(pages.all { it.startsWith("https://") })
        // MangaDex hands out the address; the app fetches the image itself.
        assertIs<ImageRef.Url>(extensions.image(source.id, series.raw, pages.first()))

        // The directory (browse) and the source's own filters.
        assertTrue(extensions.directory(source.id, 1).series.isNotEmpty())
        assertTrue(extensions.filterOptions(source.id).size > 5)
    }

    @Test
    fun komgaDemoLoginBrowseAndReadAPage(): Unit = runBlocking {
        val source = extensions.ensureLoaded().sources.first { it.name == "Komga" }
        extensions.applySettings(
            source.id,
            buildJsonObject {
                put("Address (with port)", "https://demo.komga.org")
                put("Username", "demo@komga.org")
                put("Password", "komga-demo")
            },
        )
        val directory = extensions.directory(source.id, 1)
        assertTrue(directory.series.isNotEmpty())
        val first = directory.series.first()
        val series = assertNotNull(extensions.series(source.id, first.sourceId))
        val chapters = extensions.chapters(source.id, first.sourceId)
        assertTrue(chapters.isNotEmpty())
        val pages = extensions.pages(source.id, first.sourceId, chapters.first().sourceId)
        assertTrue(pages.isNotEmpty())
        val image = assertIs<ImageRef.Spooled>(extensions.image(source.id, series.raw, pages.first()))
        val bytes = Files.readAllBytes(image.file)
        assertTrue(bytes.size > 10_000)
        // JPEG or PNG.
        assertTrue((bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) || (bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()))
    }

    @Test
    fun theResolverAgreesWithThePinnedPlanOnThePackageItself(): Unit = runBlocking {
        val answer = host.call(
            "plugins.resolve",
            buildJsonObject {
                put("name", "@tiyo/core")
                put("version", "1.0.3")
            },
            timeoutMs = 120_000,
        )
        val live = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(InstallPlan.serializer(), answer)
        val pinned = PluginInstaller(root.resolve("p"), root.resolve("w")).pinnedPlan("@tiyo/core", "1.0.3")!!
        assertEquals(pinned.packages.first().integrity, live.packages.first().integrity)
        // Tiyo names exact versions for its direct dependencies, so those cannot drift.
        val direct = { plan: InstallPlan -> plan.packages.filter { it.path.count { c -> c == '/' } == 1 }.associate { it.name to it.version } }
        assertEquals(direct(pinned)["jsdom"], direct(live)["jsdom"])
        assertEquals(direct(pinned)["node-fetch"], direct(live)["node-fetch"])
        assertTrue(live.packages.all { it.integrity!!.startsWith("sha512-") })
    }

    @Test
    fun theRegistryKnowsThePackageHoudokuInstalls(): Unit = runBlocking {
        val installer = PluginInstaller(root.resolve("p"), root.resolve("w"))
        val info = installer.registryInfo("@tiyo/core")
        assertNotNull(info.latest)
        assertTrue(info.versions.any { it.version == "1.0.3" && it.publishedAt != null })
        val legacy = installer.searchLegacyPackages()
        assertTrue(legacy.any { it.name == "@houdoku/extension-mangadex" }, legacy.map { it.name }.toString())
    }

    companion object {
        const val TEST_MANGA = "f9c33607-9180-4ba6-b85c-e4b5faee7192"
    }
}
