package com.debridone.manga.host

import com.debridone.manga.StandInKomga
import com.debridone.manga.TestRuntime
import com.debridone.manga.TiyoFixture
import com.debridone.manga.plugins.PluginInstaller
import com.debridone.manga.reply
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Compatibility with the original package: the real, unmodified `@tiyo/core` 1.0.3 — installed from
 * the package registry by DebridOne's installer — is loaded by the host the way Houdoku loads it,
 * and one of its own sources is exercised through every operation a reader needs.
 *
 * The source used is Tiyo's Komga extension, because its server address is a setting: it can be
 * pointed at a stand-in server here, so the test needs nobody else's content or uptime.
 */
class TiyoCompatibilityTest {
    private lateinit var root: Path
    private lateinit var host: ExtensionHost
    private lateinit var extensions: MangaExtensions
    private lateinit var komga: StandInKomga

    @BeforeTest
    fun setUp() {
        val plugins = TiyoFixture.pluginsDir()
        root = Files.createTempDirectory("manga-tiyo")
        komga = StandInKomga()
        host = TestRuntime.host(plugins, root)
        extensions = MangaExtensions(host)
    }

    @AfterTest
    fun tearDown() {
        if (::host.isInitialized) host.close()
        if (::komga.isInitialized) komga.close()
        if (::root.isInitialized) PluginInstaller.deleteRecursively(root)
    }

    private fun settings(values: Map<String, String>): JsonObject = buildJsonObject { values.forEach { (key, value) -> put(key, value) } }

    private suspend fun komgaSource(): SourceDescriptor = extensions.ensureLoaded().sources.first { it.name == "Komga" }

    @Test
    fun theOriginalPackageLoadsAndRegistersItsSources(): Unit = runBlocking {
        val state = extensions.ensureLoaded()
        assertTrue((host.state.value as HostState.Running).info.restricted)
        assertEquals("1.0.3", state.tiyoVersion)
        val pkg = state.packages.single()
        assertEquals("@tiyo/core", pkg.name)
        assertEquals("1.0.3", pkg.version)
        assertTrue(pkg.loaded, pkg.error)
        assertEquals(44, state.sources.size)
        assertEquals(44, pkg.sourceCount)
        // Every source implements the whole extension interface.
        assertTrue(state.sources.all { it.problems.isEmpty() }, state.sources.filter { it.problems.isNotEmpty() }.toString())
        assertTrue(state.sources.all { it.id.isNotBlank() && it.name.isNotBlank() && it.origin == "@tiyo/core" })

        val mangadex = state.sources.first { it.name == "MangaDex" }
        assertEquals("6b4e9df1-b369-4adc-8d36-fe954dd793e3", mangadex.id)
        assertEquals("MULTI", mangadex.language)
        assertEquals(mapOf("Use data saver" to "BOOLEAN"), mangadex.settingTypes)
        assertTrue(mangadex.filterCount > 5)
        assertEquals(false, mangadex.usesBrowserWindow)

        val komgaSource = state.sources.first { it.name == "Komga" }
        assertEquals(setOf("Address (with port)", "Username", "Password"), komgaSource.settingTypes.keys)

        // Sources that go through Houdoku's hidden browser window are identified as such.
        val windowed = state.sources.filter { it.usesBrowserWindow == true }.map { it.name }.toSet()
        assertTrue(windowed.containsAll(setOf("ComicK", "Mangakakalot", "MangaSee", "TCB - Scans")), windowed.toString())
        assertTrue(state.sources.map { it.language }.toSet().containsAll(setOf("ENGLISH", "FRENCH", "ITALIAN", "MULTI")))
    }

    @Test
    fun anOriginalSourceIsConfiguredSearchedOpenedAndRead(): Unit = runBlocking {
        val source = komgaSource()
        // Source configuration and authentication, as Houdoku's settings dialog would set them.
        extensions.applySettings(source.id, settings(komga.settings()))
        assertEquals(komga.url, extensions.settings(source.id)["Address (with port)"]!!.jsonPrimitive.content)

        // Directory and search.
        val directory = extensions.directory(source.id, 1)
        assertEquals(listOf("Paper Lanterns", "Tower of Stairs"), directory.series.map { it.title })
        assertEquals(false, directory.hasMore)
        val found = extensions.search(source.id, "tower", 1)
        assertEquals(listOf("Tower of Stairs"), found.series.map { it.title })
        val hit = found.series.single()
        assertEquals(source.id, hit.extensionId)
        assertEquals("S2", hit.sourceId)
        assertEquals("KOREAN", hit.originalLanguage)
        assertEquals("Completed", hit.status)
        assertEquals(listOf("Fantasy", "Webtoon"), hit.tags)
        assertEquals("${komga.url}/api/v1/series/S2/thumbnail", hit.coverUrl)

        // Series details.
        val series = assertNotNull(extensions.series(source.id, "S1"))
        assertEquals("Paper Lanterns", series.title)
        assertEquals("A stand-in series drawn for DebridOne's tests.", series.description)
        assertEquals(listOf("Test Author"), series.authors)
        // Upstream quirk, kept as it is: Tiyo's language table lists Japanese under "jp", so a server
        // that says "ja" (as Komga does) is reported as MULTI. Korean ("ko") is recognised.
        assertEquals("MULTI", series.originalLanguage)
        assertEquals("Ongoing", series.status)

        // Chapters.
        val chapters = extensions.chapters(source.id, "S1")
        assertEquals(listOf("Paper Lanterns 001", "Paper Lanterns 002"), chapters.map { it.title })
        assertEquals(listOf("1", "2"), chapters.map { it.chapterNumber })
        assertEquals(listOf("B1", "B2"), chapters.map { it.sourceId })
        assertTrue(chapters.all { it.time > 0 && it.language == "MULTI" })

        // Pages: addresses, then the images themselves, byte for byte what the server holds.
        val pages = extensions.pages(source.id, "S1", "B1")
        assertEquals((1..5).map { "${komga.url}/api/v1/books/B1/pages/$it" }, pages)
        pages.forEachIndexed { index, address ->
            val image = assertIs<ImageRef.Spooled>(extensions.image(source.id, series.raw, address))
            assertTrue(Files.readAllBytes(image.file).contentEquals(komga.book("B1")!!.pages[index]), "page ${index + 1}")
        }
        // The source logged in with the configured account.
        assertTrue(komga.requests.any { it.startsWith("GET /api/v1/books/B1/pages/3") })
    }

    @Test
    fun aWrongPasswordSurfacesAsAnErrorFromTheSource(): Unit = runBlocking {
        val source = komgaSource()
        extensions.applySettings(source.id, settings(komga.settings(password = "wrong")))
        val failure = assertFailsWith<HostException> { extensions.directory(source.id, 1) }
        // The server answers 401 with a body the extension does not expect.
        assertEquals("UNEXPECTED_ANSWER", failure.code)
        // Correcting the setting is enough; nothing has to be restarted.
        extensions.applySettings(source.id, settings(komga.settings()))
        assertEquals(2, extensions.directory(source.id, 1).series.size)
    }

    @Test
    fun anUnreachableServerIsReportedAsANetworkProblem(): Unit = runBlocking {
        val source = komgaSource()
        extensions.applySettings(source.id, settings(mapOf("Address (with port)" to "http://127.0.0.1:9", "Username" to "u", "Password" to "p")))
        val failure = assertFailsWith<HostException> { extensions.directory(source.id, 1) }
        assertEquals("NETWORK", failure.code)
        assertTrue(failure.temporary)
    }

    @Test
    fun theBrowserWindowStandInLoadsPlainPagesAndRefusesBrowserChecks(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var seenUserAgent: String? = null
        var seenReferer: String? = null
        var seenCookie: String? = null
        server.createContext("/plain") { exchange ->
            seenUserAgent = exchange.requestHeaders.getFirst("User-Agent")
            seenReferer = exchange.requestHeaders.getFirst("Referer")
            seenCookie = exchange.requestHeaders.getFirst("Cookie")
            exchange.reply(
                200,
                "<html><head><title>Chapter list</title><script>document.title='changed by script'</script></head><body><ul id=c><li>one<li>two</ul></body></html>".toByteArray(),
                "text/html; charset=utf-8",
                mapOf("Set-Cookie" to "session=abc; Path=/"),
            )
        }
        server.createContext("/check") { exchange ->
            exchange.reply(403, "<html><head><title>Just a moment...</title></head><body>Checking your browser</body></html>".toByteArray(), "text/html", mapOf("cf-mitigated" to "challenge"))
        }
        server.createContext("/gone") { exchange -> exchange.reply(404, "<html><body>nothing here</body></html>".toByteArray(), "text/html") }
        server.createContext("/empty") { exchange -> exchange.reply(200, "<html><head><title>t</title></head><body></body></html>".toByteArray(), "text/html") }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            extensions.ensureLoaded()
            fun request(path: String, options: JsonObject? = null) = buildJsonObject {
                put("url", base + path)
                if (options != null) put("options", options)
            }

            // What Tiyo's loadInWebView hands to a source: the page's body, address and title.
            val page = host.call("test.webview", request("/plain", buildJsonObject { put("httpReferrer", "https://example.org/list"); put("userAgent", "TestAgent/1") })) as JsonObject
            assertEquals("<ul id=\"c\"><li>one</li><li>two</li></ul>", page["text"]!!.jsonPrimitive.content)
            assertEquals("$base/plain", page["url"]!!.jsonPrimitive.content)
            // Page scripts are not run.
            assertEquals("Chapter list", page["title"]!!.jsonPrimitive.content)
            assertEquals("TestAgent/1", seenUserAgent)
            assertEquals("https://example.org/list", seenReferer)

            // Cookies set by the site are sent back, and the app says honestly what it is by default.
            host.call("test.webview", request("/plain"))
            assertEquals("session=abc", seenCookie)
            assertTrue(seenUserAgent!!.contains("DebridOne"), seenUserAgent)

            // A browser check ends with an explanation instead of waiting forever, as Tiyo's own code would.
            val check = assertFailsWith<HostException> { host.call("test.webview", request("/check"), timeoutMs = 10_000) }
            assertEquals("BROWSER_CHECK", check.code)
            assertTrue(check.message!!.contains("real browser window"))

            assertEquals("HTTP_ERROR", assertFailsWith<HostException> { host.call("test.webview", request("/gone"), timeoutMs = 10_000) }.code)
            // Tiyo's own rule for an empty page.
            val empty = assertFailsWith<HostException> { host.call("test.webview", request("/empty"), timeoutMs = 10_000) }
            assertTrue(empty.message!!.contains("no content"), empty.message)
            assertEquals("NETWORK", assertFailsWith<HostException> { host.call("test.webview", buildJsonObject { put("url", "http://127.0.0.1:9/x") }, timeoutMs = 10_000) }.code)

            // And the window is usable again afterwards.
            val again = host.call("test.webview", request("/plain")) as JsonObject
            assertEquals(JsonPrimitive("Chapter list"), again["title"])
        } finally {
            server.stop(0)
        }
    }
}
