package com.debridone.manga.host

import com.debridone.manga.FakeTiyo
import com.debridone.manga.TestRuntime
import com.debridone.manga.plugins.PluginInstaller
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The host process itself: start, stop, isolation, and what happens when an extension misbehaves. */
class ExtensionHostTest {
    private lateinit var root: Path
    private lateinit var plugins: Path
    private lateinit var host: ExtensionHost
    private lateinit var extensions: MangaExtensions

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("manga-host")
        plugins = root.resolve("plugins")
        FakeTiyo.installInto(plugins)
        host = TestRuntime.host(plugins, root)
        extensions = MangaExtensions(host)
    }

    @AfterTest
    fun tearDown() {
        if (::host.isInitialized) host.close()
        PluginInstaller.deleteRecursively(root)
    }

    @Test
    fun startsRestrictedAndLoadsThePackageThroughAki(): Unit = runBlocking {
        val state = extensions.ensureLoaded()
        val running = assertIs<HostState.Running>(host.state.value)
        assertTrue(running.info.restricted, "the host runs under Node's permission model")
        assertTrue(running.info.nodeVersion.startsWith("v"))
        assertEquals("1.0.99", state.tiyoVersion)
        val pkg = state.packages.single()
        assertEquals("@tiyo/core", pkg.name)
        assertEquals("tiyo", pkg.kind)
        assertTrue(pkg.loaded)
        assertEquals(2, pkg.sourceCount)
        val source = state.source(FakeTiyo.SOURCE_ID)!!
        assertEquals("Stand-in Source", source.name)
        assertEquals("ENGLISH", source.language)
        assertEquals(mapOf("Token" to "STRING"), source.settingTypes)
        assertEquals(1, source.filterCount)
        assertEquals("@tiyo/core", source.origin)
    }

    @Test
    fun everyOperationOfTheExtensionInterfaceIsForwarded(): Unit = runBlocking {
        val series = extensions.series(FakeTiyo.SOURCE_ID, "abc")!!
        assertEquals("Series abc", series.title)
        assertEquals(listOf("Alt abc"), series.altTitles)
        assertEquals("KOREAN", series.originalLanguage)
        assertEquals("Ongoing", series.status)
        assertNull(extensions.series(FakeTiyo.SOURCE_ID, "missing"))

        val chapters = extensions.chapters(FakeTiyo.SOURCE_ID, "abc")
        assertEquals(listOf("1", "2", "3"), chapters.map { it.chapterNumber })
        assertEquals("abc-c1", chapters.first().sourceId)
        assertEquals(1700000000001, chapters.first().time)

        val pages = extensions.pages(FakeTiyo.SOURCE_ID, "abc", "abc-c1")
        assertEquals(listOf("https://pages.invalid/abc-c1/1.png", "https://pages.invalid/abc-c1/2.png"), pages)

        // A source answers with an address, or with the bytes themselves.
        assertEquals(ImageRef.Url(pages[0]), extensions.image(FakeTiyo.SOURCE_ID, series.raw, pages[0]))
        val spooled = assertIs<ImageRef.Spooled>(extensions.image(FakeTiyo.SOURCE_ID, series.raw, pages[1]))
        assertEquals(4, spooled.size)
        assertTrue(Files.readAllBytes(spooled.file).contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertTrue(spooled.file.toRealPath().startsWith(root.resolve("spool").toRealPath()))

        val found = extensions.search(FakeTiyo.SOURCE_ID, "lantern", 1)
        assertEquals("Found lantern", found.series.single().title)
        assertTrue(found.hasMore)
        assertFalse(extensions.directory(FakeTiyo.SOURCE_ID, 2).hasMore)

        assertEquals("checkbox", extensions.filterOptions(FakeTiyo.SOURCE_ID).single().jsonObject["kind"]!!.jsonPrimitive.content)
        extensions.applySettings(FakeTiyo.SOURCE_ID, buildJsonObject { put("Token", "secret") })
        assertEquals("secret", extensions.settings(FakeTiyo.SOURCE_ID)["Token"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFailingSourceReportsItsErrorAndNothingElseIsAffected(): Unit = runBlocking {
        val changed = assertFailsWith<HostException> { extensions.series(FakeTiyo.BROKEN_ID, "x") }
        assertEquals("SOURCE_ERROR", changed.code)
        assertEquals("the site changed", changed.message)
        // A type error inside an extension usually means the site's answer no longer has the expected shape.
        val shape = assertFailsWith<HostException> { extensions.chapters(FakeTiyo.BROKEN_ID, "x") }
        assertEquals("UNEXPECTED_ANSWER", shape.code)
        val unknown = assertFailsWith<HostException> { extensions.series("no-such-source", "x") }
        assertEquals("UNKNOWN_SOURCE", unknown.code)
        // The other source, and the host, carry on.
        assertEquals("Series ok", extensions.series(FakeTiyo.SOURCE_ID, "ok")!!.title)
        assertEquals(1, host.generation)
    }

    @Test
    fun anExtensionCannotReadOrWriteOutsideItsFoldersOrStartPrograms(): Unit = runBlocking {
        val secret = root.resolve("outside.txt")
        Files.writeString(secret, "private")
        val read = assertFailsWith<HostException> { extensions.series(FakeTiyo.SOURCE_ID, "read:$secret") }
        assertTrue(read.message!!.contains("Access to this API has been restricted"), read.message)
        val write = assertFailsWith<HostException> { extensions.series(FakeTiyo.SOURCE_ID, "write:${root.resolve("written.txt")}") }
        assertTrue(write.message!!.contains("Access to this API has been restricted"), write.message)
        assertFalse(Files.exists(root.resolve("written.txt")))
        // The plugin folder itself is read-only for the host.
        assertFailsWith<HostException> { extensions.series(FakeTiyo.SOURCE_ID, "write:${plugins.resolve("@tiyo/core/src/index.js")}") }
        val spawn = assertFailsWith<HostException> { extensions.series(FakeTiyo.SOURCE_ID, "spawn") }
        assertTrue(spawn.message!!.contains("Access to this API has been restricted"), spawn.message)
        // It can read its own package, which is all it needs.
        assertTrue(extensions.series(FakeTiyo.SOURCE_ID, "read:${plugins.toRealPath().resolve("@tiyo/core/package.json")}")!!.title.contains("@tiyo/core"))
    }

    @Test
    fun anExtensionSeesNoneOfTheAppsEnvironment(): Unit = runBlocking {
        val names = extensions.series(FakeTiyo.SOURCE_ID, "env")!!.title.split(',').toSet()
        val allowed = setOf(
            "PATH", "HOME", "TMPDIR", "LANG", "DEBRIDONE_MANGA_HOST_TEST",
            "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "NODE_USE_ENV_PROXY", "NODE_EXTRA_CA_CERTS",
        )
        assertTrue((names - allowed).isEmpty(), "unexpected variables: ${names - allowed}")
        assertTrue("HOME" in names)
    }

    @Test
    fun whatAnExtensionPrintsNeverBreaksTheProtocol(): Unit = runBlocking {
        extensions.ensureLoaded()
        host.call("test.noise")
        assertEquals("Series after", extensions.series(FakeTiyo.SOURCE_ID, "after")!!.title)
        delay(200)
        val lines = host.log.value.map { it.text }
        assertTrue(lines.any { it.contains("this line is not part of the protocol") }, lines.toString())
        assertTrue(lines.any { it.contains("neither is this one") })
    }

    @Test
    fun aCrashFailsTheWaitingCallAndTheNextCallStartsANewHost(): Unit = runBlocking {
        extensions.ensureLoaded()
        val before = host.generation
        // The extension ends the whole process in the middle of a call.
        val crash = assertFailsWith<HostException> { extensions.series(FakeTiyo.SOURCE_ID, "exit") }
        assertEquals("HOST_STOPPED", crash.code)
        assertTrue(crash.temporary)
        assertEquals(1, host.crashCount)
        // Nothing has to be done by the caller: the next request starts a host and loads the plugins again.
        assertEquals("Series again", extensions.series(FakeTiyo.SOURCE_ID, "again")!!.title)
        assertNotEquals(before, host.generation)
        assertIs<HostState.Running>(host.state.value)
    }

    @Test
    fun storedSettingsAreAppliedAgainAfterARestart(): Unit = runBlocking {
        val stored = buildJsonObject { put("Token", "from-the-keychain") }
        val withSettings = MangaExtensions(host, storedSettings = { source -> stored.takeIf { source.id == FakeTiyo.SOURCE_ID } })
        assertEquals("from-the-keychain", withSettings.settings(FakeTiyo.SOURCE_ID)["Token"]!!.jsonPrimitive.content)
        runCatching { host.call("test.crash", timeoutMs = 5_000) }
        repeat(40) { if (host.state.value is HostState.Running) delay(50) }
        assertEquals("from-the-keychain", withSettings.settings(FakeTiyo.SOURCE_ID)["Token"]!!.jsonPrimitive.content)
    }

    @Test
    fun aCallThatNeverAnswersTimesOutAndTheHostStaysUsable(): Unit = runBlocking {
        extensions.ensureLoaded()
        val hung = assertFailsWith<HostException> { host.call("test.hang", timeoutMs = 400) }
        assertEquals("TIMEOUT", hung.code)
        assertEquals("Series fine", extensions.series(FakeTiyo.SOURCE_ID, "fine")!!.title)
        assertEquals(1, host.generation)
    }

    @Test
    fun aHostThatStopsAnsweringAltogetherIsReplaced(): Unit = runBlocking {
        extensions.ensureLoaded()
        repeat(4) { assertFailsWith<HostException> { host.call("test.hang", timeoutMs = 150) } }
        delay(300)
        assertEquals("Series back", extensions.series(FakeTiyo.SOURCE_ID, "back")!!.title)
        assertEquals(2, host.generation)
    }

    @Test
    fun repeatedCrashesStopTheAutomaticRestartsUntilAskedAgain(): Unit = runBlocking {
        repeat(5) {
            host.ensureStarted()
            runCatching { host.call("test.crash", timeoutMs = 5_000) }
            repeat(40) { if (host.state.value is HostState.Running) delay(50) }
        }
        val failure = assertFailsWith<HostException> { host.call("ping") }
        assertEquals("HOST_UNSTABLE", failure.code)
        assertIs<HostState.Unavailable>(host.state.value)
        host.restart()
        assertEquals("Series recovered", extensions.series(FakeTiyo.SOURCE_ID, "recovered")!!.title)
    }

    @Test
    fun callsRunConcurrentlyAndEachGetsItsOwnAnswer(): Unit = runBlocking {
        extensions.ensureLoaded()
        val answers = (1..12).map { index -> async { extensions.series(FakeTiyo.SOURCE_ID, "n$index")!!.title } }.map { it.await() }
        assertEquals((1..12).map { "Series n$it" }, answers)
    }

    @Test
    fun closingStopsTheProcess(): Unit = runBlocking {
        extensions.ensureLoaded()
        val pid = (host.state.value as HostState.Running).info.pid
        assertTrue(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        host.close()
        repeat(40) { if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(100) }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the host process ends with the app")
        assertEquals("HOST_STOPPED", assertFailsWith<HostException> { host.call("ping") }.code)
    }

    @Test
    fun aPackageThatIsNotAnExtensionPackageIsReportedNotLoaded(): Unit = runBlocking {
        Files.writeString(plugins.resolve("@tiyo/core/src/index.js"), "exports.somethingElse = 1;")
        val other = plugins.resolve("left-pad")
        Files.createDirectories(other)
        Files.writeString(other.resolve("package.json"), """{"name":"left-pad","version":"1.0.0"}""")
        val state = extensions.reload()
        assertTrue(state.sources.isEmpty())
        val tiyo = state.packages.first { it.name == "@tiyo/core" }
        assertFalse(tiyo.loaded)
        assertTrue(tiyo.error!!.contains("TiyoClient"))
        // Like Houdoku, anything else in the folder is ignored.
        assertEquals("unsupported", state.packages.first { it.name == "left-pad" }.kind)
        assertFalse(state.packages.first { it.name == "left-pad" }.loaded)
    }

    @Test
    fun withNoPackageInstalledTheHostRunsAndListsNothing(): Unit = runBlocking {
        PluginInstaller.deleteRecursively(plugins.resolve("@tiyo"))
        val state = extensions.reload()
        assertTrue(state.packages.isEmpty())
        assertTrue(state.sources.isEmpty())
        assertNull(state.tiyoVersion)
    }

    @Test
    fun theRuntimeIsNeverTakenFromThePath() {
        val failure = assertFailsWith<HostException> { MangaHostRuntime.locate(property = { null }, environment = { name -> if (name == "PATH") "/usr/bin:/opt/node22/bin" else null }) }
        assertEquals("NO_RUNTIME", failure.code)
        val half = assertFailsWith<HostException> { MangaHostRuntime.locate(property = { null }, environment = { name -> if (name == MangaHostRuntime.NODE_ENV) "/usr/bin/node" else null }) }
        assertEquals("NO_RUNTIME", half.code)
    }

    @Test
    fun aBundledRuntimeThatLostItsPermissionsIsRepaired() {
        // The layout of a packaged app's resources, with the program copied without its permissions.
        val resources = Files.createTempDirectory("debridone-bundled-runtime")
        try {
            val bundle = resources.resolve("manga-host")
            Files.createDirectories(bundle.resolve("host/src"))
            Files.createDirectories(bundle.resolve("host/node_modules/aki-plugin-manager"))
            Files.writeString(bundle.resolve("host/src/host.js"), "// host")
            val node = bundle.resolve("node")
            Files.writeString(node, "#!/bin/sh\n")
            node.toFile().setExecutable(false, false)
            assertTrue(!Files.isExecutable(node))

            val runtime = MangaHostRuntime.locate(property = { name -> if (name == "compose.application.resources.dir") resources.toString() else null }, environment = { null })
            assertEquals("bundled", runtime.origin)
            assertEquals(node, runtime.node)
            assertTrue(Files.isExecutable(node), "the bundled program is made executable again")
        } finally {
            resources.toFile().deleteRecursively()
        }
    }
}
