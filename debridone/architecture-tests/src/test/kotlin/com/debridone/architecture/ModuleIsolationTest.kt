package com.debridone.architecture

import com.debridone.gaming.GamingModule
import com.debridone.manga.MangaModule
import com.debridone.module.EntertainmentModule
import com.debridone.module.ModuleId
import com.debridone.module.ModuleStatus
import com.debridone.module.PluginActionResult
import com.debridone.module.PluginKind
import com.debridone.module.storage.ModuleStorage
import com.debridone.module.storage.PluginDescriptor
import com.debridone.module.storage.SqliteBackedModule
import com.debridone.music.MusicModule
import com.debridone.platform.AppPaths
import com.debridone.platform.db.OpenOutcome
import com.debridone.platform.secrets.FileSecretStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Runtime proof of the module boundary: every module runs against the same data root, exactly as
 * in the app, and must stay unaffected by what happens in the others.
 */
class ModuleIsolationTest {
    private lateinit var dir: Path
    private lateinit var paths: AppPaths
    private lateinit var secrets: FileSecretStore
    private val opened = ArrayList<EntertainmentModule>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("debridone-isolation-test")
        paths = AppPaths(dir.resolve("data"), dir.resolve("cache"))
        secrets = FileSecretStore(paths.secretsDir)
    }

    @AfterTest
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        dir.toFile().deleteRecursively()
    }

    private fun storage(id: ModuleId) = ModuleStorage(id, paths, secrets)

    private fun music() = MusicModule(storage(ModuleId.MUSIC)).also { opened += it }

    /** Music lists its built-in source kinds in its registry; these tests are about what is added on top. */
    private fun MusicModule.added() = plugins.installed.value.filterNot { it.builtIn }
    private fun gaming() = GamingModule(storage(ModuleId.GAMING)).also { opened += it }
    private fun manga() = MangaModule(storage(ModuleId.MANGA)).also { opened += it }

    @Test
    fun musicSourcesExistOnlyInMusic() = runBlocking {
        val music = music().apply { start() }
        val gaming = gaming().apply { start() }
        val manga = manga().apply { start() }
        assertEquals(
            setOf("local-files", "subsonic", "jellyfin", "stream-url"),
            music.plugins.installed.value.map { it.id }.toSet(),
            "Music lists its own source kinds",
        )
        assertEquals(emptyList(), gaming.plugins.installed.value, "Music's sources must not show up in Games")
        assertEquals(emptyList(), manga.plugins.installed.value, "Music's sources must not show up in Manga")
        // A Music source id cannot be switched or removed through another module's registry.
        assertIs<PluginActionResult.Failure>(gaming.plugins.setEnabled("subsonic", false))
        assertIs<PluginActionResult.Failure>(manga.plugins.remove("jellyfin"))
        assertTrue(music.plugins.installed.value.all { it.enabled })
        // And Music's metadata providers are not offered by the others.
        val musicProviders = music.metadataProviders.providers.value.map { it.id }.toSet()
        assertTrue(musicProviders.containsAll(setOf("musicbrainz", "cover-art-archive", "lrclib")))
        assertTrue(gaming.metadataProviders.providers.value.none { it.id in musicProviders })
        assertTrue(manga.metadataProviders.providers.value.none { it.id in musicProviders })
    }

    @Test
    fun everyModuleOpensItsOwnDatabaseFile() = runBlocking {
        val modules = listOf(music(), gaming(), manga())
        modules.forEach { it.start() }
        modules.forEach { assertEquals(ModuleStatus.Running, it.status.value, "${it.id} should be running") }

        val files = listOf("music/music.db", "gaming/gaming.db", "manga/manga.db").map { paths.dataRoot.resolve("modules").resolve(it) }
        files.forEach { assertTrue(it.exists(), "$it should exist") }
        assertEquals(3, files.map { it.parent }.toSet().size, "each module needs its own directory")
        assertFalse(paths.dataRoot.resolve("modules/streaming/streaming.db").exists(), "Streaming keeps Nuvio's own storage")
    }

    @Test
    fun pluginInstalledInOneModuleNeverAppearsInAnother() = runBlocking {
        val music = music().apply { start() }
        val gaming = gaming().apply { start() }
        val manga = manga().apply { start() }

        music.registerPlugin(PluginDescriptor("navidrome-home", "Home Navidrome", PluginKind.LIBRARY_INTEGRATION, origin = "https://music.example/manifest.json"))
        assertEquals(listOf("navidrome-home"), music.added().map { it.id })
        assertEquals(emptyList(), gaming.plugins.installed.value)
        assertEquals(emptyList(), manga.plugins.installed.value)

        manga.registerPlugin(PluginDescriptor("tiyo", "Tiyo", PluginKind.SOURCE, version = "1.0.3", origin = "npm:@tiyo/core"))
        assertEquals(listOf("tiyo"), manga.plugins.installed.value.map { it.id })
        assertEquals(listOf("navidrome-home"), music.added().map { it.id })
        assertEquals(emptyList(), gaming.plugins.installed.value)
    }

    @Test
    fun sameIdInTwoModulesAreUnrelatedPlugins() = runBlocking {
        val music = music().apply { start() }
        val gaming = gaming().apply { start() }
        music.registerPlugin(PluginDescriptor("shared-id", "Music thing", PluginKind.SOURCE))
        gaming.registerPlugin(PluginDescriptor("shared-id", "Games thing", PluginKind.SOURCE))

        assertIs<PluginActionResult.Success>(music.plugins.setEnabled("shared-id", false))
        assertFalse(music.added().single().enabled)
        assertTrue(gaming.plugins.installed.value.single().enabled, "disabling in Music must not touch Games")

        assertIs<PluginActionResult.Success>(gaming.plugins.remove("shared-id"))
        assertEquals(emptyList(), gaming.plugins.installed.value)
        assertEquals("Music thing", music.added().single().name, "removing from Games must not touch Music")
    }

    @Test
    fun modulesRejectPluginKindsTheyDoNotSupport() = runBlocking {
        val manga = manga().apply { start() }
        assertFalse(PluginKind.TEXT_TRACKS in manga.plugins.supportedKinds)
        assertFailsWith<IllegalArgumentException> {
            manga.registerPlugin(PluginDescriptor("lyrics", "Lyrics provider", PluginKind.TEXT_TRACKS))
        }
        assertEquals(emptyList(), manga.plugins.installed.value)
    }

    @Test
    fun pluginRegistryAndSettingsSurviveRestart() = runBlocking {
        music().apply {
            start()
            registerPlugin(PluginDescriptor("lrclib-extra", "Extra lyrics", PluginKind.TEXT_TRACKS, version = "2.1"))
            plugins.setEnabled("lrclib-extra", false)
            metadataProviders.setEnabled("musicbrainz", false)
            onProfileChanged(1001)
            appearance.set("card_size", "large")
            appearance.set("accent", "#FF8800")
            close()
        }

        val reopened = music().apply { start() }
        assertEquals(OpenOutcome.OPENED, reopened.lastOpenOutcome)
        val plugin = reopened.added().single()
        assertEquals("lrclib-extra", plugin.id)
        assertEquals("2.1", plugin.version)
        assertFalse(plugin.enabled, "the disabled state must persist")
        assertFalse(reopened.metadataProviders.providers.value.first { it.id == "musicbrainz" }.enabled)
        reopened.onProfileChanged(1001)
        assertEquals("large", reopened.appearance.current.value.values["card_size"])
        assertEquals("#FF8800", reopened.appearance.current.value.accentHex)
    }

    @Test
    fun metadataProvidersAreModuleSpecific() = runBlocking {
        val music = music().apply { start() }
        val gaming = gaming().apply { start() }
        val manga = manga().apply { start() }
        val musicIds = music.metadataProviders.providers.value.map { it.id }.toSet()
        val gamingIds = gaming.metadataProviders.providers.value.map { it.id }.toSet()
        val mangaIds = manga.metadataProviders.providers.value.map { it.id }.toSet()
        assertTrue("musicbrainz" in musicIds && "steam-store" in gamingIds && "anilist" in mangaIds)
        assertFalse("musicbrainz" in gamingIds || "musicbrainz" in mangaIds)
        assertFalse("steam-store" in musicIds || "steam-store" in mangaIds)
        assertFalse("anilist" in musicIds || "anilist" in gamingIds)

        // "manual" exists in both Games and Manga; changing one must not change the other.
        gaming.metadataProviders.setEnabled("manual", false)
        assertFalse(gaming.metadataProviders.providers.value.first { it.id == "manual" }.enabled)
        assertTrue(manga.metadataProviders.providers.value.first { it.id == "manual" }.enabled)
    }

    @Test
    fun appearanceIsPerModuleAndPerProfile() = runBlocking {
        val music = music().apply { start() }
        val gaming = gaming().apply { start() }
        listOf(music, gaming).forEach { it.onProfileChanged(1001) }

        music.appearance.set("accent", "#AA0000")
        music.appearance.set("card_size", "small")
        assertNull(gaming.appearance.current.value.accentHex, "Music's accent must not leak into Games")
        gaming.appearance.set("accent", "#00AA00")
        gaming.appearance.set("layout", "list")

        // A second profile starts from defaults and keeps its own values.
        listOf(music, gaming).forEach { it.onProfileChanged(1002) }
        assertNull(music.appearance.current.value.accentHex)
        music.appearance.set("accent", "#0000AA")

        listOf(music, gaming).forEach { it.onProfileChanged(1001) }
        assertEquals("#AA0000", music.appearance.current.value.accentHex)
        assertEquals("small", music.appearance.current.value.values["card_size"])
        assertEquals("#00AA00", gaming.appearance.current.value.accentHex)
        assertEquals("list", gaming.appearance.current.value.values["layout"])

        // Invalid values and options of another module are rejected.
        music.appearance.set("accent", "red")
        music.appearance.set("manga_direction", "rtl")
        assertEquals("#AA0000", music.appearance.current.value.accentHex)
        assertFalse("manga_direction" in music.appearance.current.value.values)

        // Deleting a profile's data in one module leaves the other module's copy alone.
        (music as SqliteBackedModule).deleteProfileData(1001)
        music.onProfileChanged(1001)
        assertNull(music.appearance.current.value.accentHex)
        assertEquals("#00AA00", gaming.appearance.current.value.accentHex)
    }

    @Test
    fun brokenModuleDoesNotStopTheOthers() = runBlocking {
        // Make Gaming's database unreadable AND unrecoverable by blocking its directory with a file.
        Files.createDirectories(paths.dataRoot.resolve("modules"))
        paths.dataRoot.resolve("modules/gaming").writeBytes(byteArrayOf(1, 2, 3))

        val music = music()
        val gaming = gaming()
        val manga = manga()
        listOf(music, gaming, manga).forEach { it.start() }

        assertIs<ModuleStatus.Failed>(gaming.status.value)
        assertEquals(ModuleStatus.Running, music.status.value)
        assertEquals(ModuleStatus.Running, manga.status.value)
        music.registerPlugin(PluginDescriptor("still-works", "Still works", PluginKind.SOURCE))
        assertEquals(1, music.added().size)
    }

    @Test
    fun corruptModuleDatabaseIsRecoveredWithoutAffectingOthers() = runBlocking {
        music().apply {
            start()
            registerPlugin(PluginDescriptor("keep-me", "Keep me", PluginKind.SOURCE))
            close()
        }
        gaming().apply {
            start()
            registerPlugin(PluginDescriptor("lost", "Lost", PluginKind.SOURCE))
            close()
        }
        val gamingDb = paths.dataRoot.resolve("modules/gaming/gaming.db")
        listOf("-wal", "-shm").forEach { Files.deleteIfExists(gamingDb.resolveSibling(gamingDb.fileName.toString() + it)) }
        gamingDb.writeBytes(ByteArray(8192) { 0x11 })

        val gaming = gaming().apply { start() }
        val music = music().apply { start() }
        assertEquals(ModuleStatus.Running, gaming.status.value)
        assertEquals(OpenOutcome.RECREATED_AFTER_CORRUPTION, gaming.lastOpenOutcome)
        assertEquals(emptyList(), gaming.plugins.installed.value)
        assertEquals(OpenOutcome.OPENED, music.lastOpenOutcome)
        assertEquals(listOf("keep-me"), music.added().map { it.id })
        assertTrue(paths.backupDirFor("gaming").resolve("corrupt").exists(), "the damaged file is kept for inspection")
    }

    @Test
    fun moduleSecretsAreNamespaced() {
        val music = storage(ModuleId.MUSIC).secrets
        val gaming = storage(ModuleId.GAMING).secrets
        music.child("profile.1001").put("subsonic-password", "pw")
        assertNull(gaming.child("profile.1001").get("subsonic-password"))
        assertNull(music.child("profile.1002").get("subsonic-password"))
        assertEquals("pw", music.child("profile.1001").get("subsonic-password"))
    }

    @Test
    fun unfinishedImportIsReportedHonestly() = runBlocking {
        val gaming = gaming().apply { start() }
        assertFalse(gaming.plugins.importAvailable)
        assertIs<PluginActionResult.NotAvailableYet>(gaming.plugins.importFromLocation("https://example.com/source.json"))
        assertEquals(emptyList(), gaming.plugins.installed.value, "a failed import must not leave a record behind")
    }

    @Test
    fun moduleRefusesStorageOfAnotherModule() {
        assertFailsWith<IllegalArgumentException> { MusicModule(storage(ModuleId.GAMING)) }
    }
}
