package com.debridone.manga.suwayomi

import org.junit.AfterClass
import org.junit.BeforeClass
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The real, pinned Suwayomi-Server, started by [SuwayomiServerManager] with the pinned Java 21
 * runtime, reading sample comics through its own Local source. Nothing here leaves this computer.
 *
 * Skipped when the runtime was not fetched (scripts/debridone/fetch-suwayomi-runtime.sh); CI sets
 * DEBRIDONE_REQUIRE_SUWAYOMI=1, which turns the skip into a failure.
 */
class SuwayomiServerTest {
    companion object {
        private const val PROFILE = 7L
        private lateinit var root: Path
        private lateinit var manager: SuwayomiServerManager
        private lateinit var endpoint: ServerEndpoint
        private lateinit var api: SuwayomiApi
        private lateinit var transport: SuwayomiTransport

        @BeforeClass
        @JvmStatic
        fun startServer() {
            val runtime = SuwayomiTestRuntime.runtimeOrSkip()
            root = Files.createTempDirectory("debridone-suwayomi")
            manager = SuwayomiServerManager(root, { runtime })
            SampleComics.series(manager.localSourceDirectory(PROFILE), "Sample Series", chapters = 2, pages = 3)
            SampleComics.series(manager.localSourceDirectory(PROFILE), "Another One", chapters = 1, pages = 2)
            connect()
        }

        private fun connect() {
            endpoint = manager.start(PROFILE)
            transport = SuwayomiTransport(endpoint)
            api = SuwayomiApi(transport)
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            if (::manager.isInitialized) manager.close()
            if (::root.isInitialized) root.toFile().deleteRecursively()
        }
    }

    @Test
    fun theServerIsOursAloneOnTheLoopbackInterface() {
        val ready = assertIs<ServerState.Ready>(manager.state.value)
        assertEquals(PROFILE, ready.profileId)
        assertTrue(ready.version.startsWith("v2."), ready.version)
        assertTrue(endpoint.baseUrl.startsWith("http://127.0.0.1:"))

        // Without the password, and with a wrong one, nothing is answered: neither the API nor an image.
        val anonymous = SuwayomiTransport(endpoint.copy(username = null, password = null))
        assertEquals(SuwayomiException.Kind.UNAUTHORIZED, assertFailsWith<SuwayomiException> { SuwayomiApi(anonymous).about() }.kind)
        assertEquals(SuwayomiException.Kind.UNAUTHORIZED, assertFailsWith<SuwayomiException> { anonymous.bytes("/api/v1/manga/1/thumbnail") }.kind)
        val wrong = SuwayomiTransport(endpoint.copy(password = "not-the-password"))
        assertEquals(SuwayomiException.Kind.UNAUTHORIZED, assertFailsWith<SuwayomiException> { SuwayomiApi(wrong).about() }.kind)

        // The settings the server reports are the ones DebridOne decided, and it serves no web interface.
        val settings = api.settings(listOf("ip", "systemTrayEnabled", "initialOpenInBrowserEnabled", "kcefEnabled", "authMode"))
        assertEquals("\"127.0.0.1\"", settings.getValue("ip").toString())
        assertEquals("false", settings.getValue("systemTrayEnabled").toString())
        assertEquals("false", settings.getValue("initialOpenInBrowserEnabled").toString())
        assertEquals("false", settings.getValue("kcefEnabled").toString())
        assertEquals("\"BASIC_AUTH\"", settings.getValue("authMode").toString())
        assertTrue(Files.readAllLines(manager.directory(PROFILE).resolve("server.conf")).any { it.replace(" ", "").startsWith("server.webUIEnabled=false") })
        assertFailsWith<SuwayomiException>("no web interface is served") { transport.bytes("/") }
        assertFalse(Files.exists(manager.directory(PROFILE).resolve("webUI").resolve("index.html")), "no web interface is downloaded")

        // The password is in a file only its owner can read, and in no line the server printed.
        val directory = manager.directory(PROFILE)
        val conf = directory.resolve("server.conf")
        assertTrue(Files.readString(conf).contains(endpoint.password!!))
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(conf))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            Files.getPosixFilePermissions(directory),
        )
        assertTrue(manager.log().isNotEmpty())
        assertFalse(manager.log().any { it.contains(endpoint.password!!) }, "the password must not be printed")
        val commandLine = manager.runningProcess()!!.info().commandLine().orElse("") +
            manager.runningProcess()!!.info().arguments().map { it.joinToString(" ") }.orElse("")
        assertFalse(commandLine.contains(endpoint.password!!), "the password must not be on the command line")
    }

    @Test
    fun everyDocumentIsValidForThePinnedServer() {
        // Sent without variables: the server validates a document against its schema before it looks
        // at them, so a field or type that does not exist is reported, and nothing is changed.
        val problems = SuwayomiApi.ALL.mapNotNull { (name, document) ->
            if (name == "CREATE_BACKUP") return@mapNotNull null
            try {
                transport.execute(document)
                null
            } catch (e: SuwayomiException) {
                val message = e.message.orEmpty()
                if (message.contains("Validation error") || message.contains("Invalid syntax", ignoreCase = true) || e.kind != SuwayomiException.Kind.SERVER) "$name: $message" else null
            }
        }
        assertTrue(problems.isEmpty(), "Documents the server rejects:\n" + problems.joinToString("\n"))
        // And the check itself notices a document that is wrong.
        val wrong = assertFailsWith<SuwayomiException> { transport.execute("query { manga(id: 1) { noSuchField } }") }
        assertTrue(wrong.message!!.contains("Validation error"), wrong.message)
    }

    @Test
    fun aLocalSeriesIsListedReadAndRemembered() {
        val local = api.sources().single { it.id == "0" }
        assertTrue(local.name.contains("Local", ignoreCase = true), local.name)

        // Browse and search.
        val popular = api.browse(local.id, SuwayomiApi.BrowseType.POPULAR, page = 1)
        assertEquals(setOf("Sample Series", "Another One"), popular.items.map { it.title }.toSet())
        val found = api.browse(local.id, SuwayomiApi.BrowseType.SEARCH, page = 1, query = "sample")
        val series = found.items.single()
        assertEquals("Sample Series", series.title)
        assertFalse(series.inLibrary)

        // Details and chapters.
        val (details, chapters) = api.refresh(series.id)
        assertEquals("Sample Series", details.title)
        assertEquals(listOf("Chapter 01", "Chapter 02"), chapters.sortedBy { it.sourceOrder }.map { it.name.removeSuffix(".cbz") })
        assertEquals(chapters.map { it.id }.toSet(), api.chapters(series.id).map { it.id }.toSet())
        val first = api.chapters(series.id).first()
        assertEquals(-1, first.pageCount, "pages are not known before the chapter is opened")

        // Pages: real pictures, the ones that were put in the archive, in order.
        val (opened, pages) = api.chapterPages(first.id)
        assertEquals(3, pages.size)
        assertEquals(3, opened.pageCount)
        pages.forEachIndexed { index, path ->
            val bytes = transport.bytes(path)
            assertContentEquals(SampleComics.PNG_SIGNATURE, bytes.copyOf(4))
            assertContentEquals(SampleComics.page(index), bytes, "page ${index + 1}")
        }
        val cover = assertNotNull(details.thumbnailUrl ?: api.manga(series.id).thumbnailUrl)
        assertTrue(transport.bytes(cover).size > 100)

        // Progress, library, and what DebridOne keeps beside them.
        val progressed = api.updateChapters(listOf(first.id), lastPageRead = 2).single()
        assertEquals(2, progressed.lastPageRead)
        assertTrue(progressed.lastReadAt > 0)
        api.setInLibrary(listOf(series.id), true)
        api.setMangaMeta(series.id, "debridone.status", "READING")
        api.setMangaMeta(series.id, "debridone.favorite", "1")
        api.setGlobalMeta("debridone.test", "kept")
        val category = api.createCategory("Shelf A")
        api.setMangaCategories(series.id, add = listOf(category.id))
        api.updateChapters(listOf(chapters.maxBy { it.sourceOrder }.id), bookmarked = true)

        fun check(inLibrary: SwManga) {
            assertEquals("Sample Series", inLibrary.title)
            assertTrue(inLibrary.inLibrary)
            assertEquals(2, inLibrary.chapterCount)
            assertEquals(2, inLibrary.unreadCount)
            assertEquals(1, inLibrary.bookmarkCount)
            assertEquals("READING", inLibrary.meta["debridone.status"])
            assertEquals("1", inLibrary.meta["debridone.favorite"])
            assertEquals(listOf(category.id), inLibrary.categoryIds)
            assertEquals(first.id, inLibrary.lastReadChapter?.id)
            assertEquals(2, inLibrary.lastReadChapter?.lastPageRead)
            assertEquals("0", inLibrary.sourceId)
        }
        check(api.library().single())
        assertEquals("Shelf A", api.categories().single { it.id == category.id }.name)
        assertEquals(first.id, api.recentlyRead(sinceSeconds = 0, limit = 10).first().id)
        assertEquals(1, api.bookmarkedChapters().size)
        assertEquals(series.id, api.mangasByUrl("0", series.url).single().id)

        // A restart: a new port and a new password, the same library.
        val before = endpoint
        manager.stop()
        assertIs<ServerState.Stopped>(manager.state.value)
        assertEquals(SuwayomiException.Kind.UNREACHABLE, assertFailsWith<SuwayomiException> { SuwayomiApi(SuwayomiTransport(before)).about() }.kind)
        connect()
        assertNotEquals(before.password, endpoint.password)
        check(api.library().single())
        assertEquals("kept", api.globalMeta()["debridone.test"])
        assertEquals(2, api.chapter(first.id).lastPageRead)
        assertEquals(3, api.chapter(first.id).pageCount)

        // Marking read, removing a value, leaving the library.
        api.updateChapters(api.chapters(series.id).map { it.id }, read = true)
        assertEquals(0, api.manga(series.id).unreadCount)
        api.deleteMangaMeta(series.id, "debridone.favorite")
        assertNull(api.manga(series.id).meta["debridone.favorite"])
        api.renameCategory(category.id, "Shelf B")
        assertEquals("Shelf B", api.categories().single { it.id == category.id }.name)
        api.deleteCategory(category.id)
        assertTrue(api.categories().none { it.id == category.id })
        api.setInLibrary(listOf(series.id), false)
        assertTrue(api.library().isEmpty())
    }

    @Test
    fun withoutStoresThereAreNoExtensionsAndNothingIsPreconfigured() {
        assertTrue(api.stores().isEmpty(), "no store may be configured by DebridOne")
        assertTrue(api.extensions().none { it.installed })
        assertEquals(listOf("0"), api.sources().map { it.id }, "only Suwayomi's own Local source exists")
        val refused = assertFailsWith<SuwayomiException> { api.addStore("http://127.0.0.1:9/not-a-store.json") }
        assertEquals(SuwayomiException.Kind.SERVER, refused.kind)
        assertFalse(refused.message!!.contains("\tat "), refused.message)
        assertTrue(api.stores().isEmpty())
        val status = api.downloadStatus()
        assertTrue(status.queue.isEmpty())
        assertTrue(api.trackers().isNotEmpty(), "Suwayomi's trackers are listed")
        assertTrue(api.trackers().none { it.loggedIn })
    }

    @Test
    fun aBackupOfTheLibraryCanBeMade() {
        val backup = api.createBackup()
        assertTrue(backup.size > 20, "a backup file is returned")
    }
}

/** Starting and stopping, each test with a server (or none) of its own. */
class SuwayomiServerLifecycleTest {
    private val root: Path = Files.createTempDirectory("debridone-suwayomi-life")

    @kotlin.test.AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun aMissingRuntimeIsAStateWithASentenceNotACrash() {
        val manager = SuwayomiServerManager(root, { throw SuwayomiUnavailable("Suwayomi-Server is missing (test).") })
        val failure = assertFailsWith<SuwayomiUnavailable> { manager.start(1) }
        assertEquals("Suwayomi-Server is missing (test).", failure.message)
        val state = assertIs<ServerState.Failed>(manager.state.value)
        assertEquals(1, state.profileId)
        assertEquals("Suwayomi-Server is missing (test).", state.message)
        manager.close()
        assertIs<ServerState.Stopped>(manager.state.value)
    }

    @Test
    fun aJavaThatCannotRunTheServerIsReportedWithItsLastWords() {
        val runtime = SuwayomiTestRuntime.runtimeOrSkip()
        val notAJar = Files.writeString(root.resolve("broken.jar"), "this is not a jar")
        val manager = SuwayomiServerManager(root, { SuwayomiRuntime(runtime.java, notAJar) }, startTimeoutMs = 20_000)
        val failure = assertFailsWith<SuwayomiUnavailable> { manager.start(1) }
        assertTrue(failure.message!!.startsWith("The manga server ended while starting."), failure.message)
        val state = assertIs<ServerState.Failed>(manager.state.value)
        assertTrue(state.log.isNotEmpty(), "the diagnostics keep what the process printed")
        assertNull(manager.runningProcess())
        manager.close()
    }

    @Test
    fun onlyAProcessThatIsProvablyOursIsEndedBeforeAStart() {
        val runtime = SuwayomiTestRuntime.runtimeOrSkip()
        val manager = SuwayomiServerManager(root, { SuwayomiRuntime(runtime.java, Files.writeString(root.resolve("broken.jar"), "x")) }, startTimeoutMs = 20_000)
        val directory = Files.createDirectories(manager.directory(3))

        // Someone else's process whose id happens to be in the pid file: left alone.
        val unrelated = ProcessBuilder("sh", "-c", "sleep 120; :").start()
        Files.writeString(directory.resolve("debridone-server.pid"), "${unrelated.pid()}\n")
        assertFailsWith<SuwayomiUnavailable> { manager.start(3) }
        assertTrue(unrelated.isAlive, "a process that is not this folder's server must never be stopped")
        unrelated.destroyForcibly()

        // A process started for this very folder (its command line says so): ended.
        val leftover = ProcessBuilder("sh", "-c", "sleep 120; : -Dsuwayomi.tachidesk.config.server.rootDir=$directory").start()
        Files.writeString(directory.resolve("debridone-server.pid"), "${leftover.pid()}\n")
        assertFailsWith<SuwayomiUnavailable> { manager.start(3) }
        assertTrue(leftover.waitFor(15, TimeUnit.SECONDS), "the leftover of an earlier run is ended")
        manager.close()
    }

    @Test
    fun theServerEndsWhenDebridOneIsGoneAndOneFolderHasOneServer() {
        val runtime = SuwayomiTestRuntime.runtimeOrSkip()
        val manager = SuwayomiServerManager(root, { runtime })
        val endpoint = manager.start(1)
        assertTrue(SuwayomiApi(SuwayomiTransport(endpoint)).about().version.isNotBlank())

        // A second DebridOne cannot start a second server on the same profile's data.
        val second = SuwayomiServerManager(root, { runtime })
        val refused = assertFailsWith<SuwayomiUnavailable> { second.start(1) }
        assertTrue(refused.message!!.contains("in use"), refused.message)
        assertTrue(manager.runningProcess()!!.isAlive, "the refusal must not disturb the running server")
        second.close()
        assertTrue(manager.runningProcess()!!.isAlive)

        // Starting again for the same profile is the same server; another profile replaces it.
        assertEquals(endpoint, manager.start(1))
        val process = manager.runningProcess()!!
        val other = manager.start(2)
        assertNotEquals(endpoint.baseUrl + endpoint.password, other.baseUrl + other.password)
        assertFalse(process.isAlive, "only the active profile's server runs")
        assertTrue(Files.isDirectory(manager.directory(1)) && Files.isDirectory(manager.directory(2)))

        // DebridOne dies: the server's input closes, and it exits by itself.
        val orphan = manager.runningProcess()!!
        orphan.outputStream.close()
        if (!orphan.waitFor(30, TimeUnit.SECONDS)) fail("the server kept running after its DebridOne was gone")
        val deadline = System.currentTimeMillis() + 5_000
        while (manager.state.value !is ServerState.Failed && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val failed = assertIs<ServerState.Failed>(manager.state.value)
        assertTrue(failed.message.contains("stopped unexpectedly"), failed.message)

        // And it can be started again.
        val again = manager.start(2)
        assertTrue(SuwayomiApi(SuwayomiTransport(again)).about().version.isNotBlank())
        manager.close()
        assertIs<ServerState.Stopped>(manager.state.value)
    }
}
