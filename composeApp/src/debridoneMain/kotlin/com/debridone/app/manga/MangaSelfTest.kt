package com.debridone.app.manga

import com.debridone.manga.MangaModule
import com.debridone.manga.MangaServices
import com.debridone.manga.host.HostState
import com.debridone.manga.host.MangaHostRuntime
import com.debridone.manga.library.SeriesEdit
import com.debridone.manga.library.SeriesField
import com.debridone.manga.plugins.CompatibilityLevel
import com.debridone.manga.plugins.PluginRules
import com.debridone.module.ModuleId
import com.debridone.module.ModuleStatus
import com.debridone.module.PluginActionResult
import com.debridone.module.storage.ModuleStorage
import com.debridone.platform.AppPaths
import com.debridone.platform.secrets.FileSecretStore
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Checks Manga without opening a window, then exits with 0 (all good) or 1:
 *
 *     DebridOne --manga-self-test
 *
 * It uses a temporary data folder and does, in this build, what a reader would do:
 *  1. reads a CBZ with no extension and no network, restarts, and resumes;
 *  2. starts the extension host on the Node.js runtime that ships inside the app;
 *  3. installs Houdoku's real extension package (Tiyo) from the package registry, through the
 *     same verified installer and trial load the app uses;
 *  4. sets up one of Tiyo's own sources (Komga) against a small stand-in server started by this
 *     test, then searches, opens a series, lists chapters, reads pages, restarts and resumes.
 *
 * Step 3 needs access to registry.npmjs.org. With `--offline` steps 3 and 4 are skipped and said so.
 * The build runs this inside the packaged macOS app, so the DMG is known to carry a working runtime.
 */
object MangaSelfTest {
    private const val FLAG = "--manga-self-test"
    private const val PROFILE = 1001L
    private val failures = ArrayList<String>()

    /** Returns false when no self-test was requested; otherwise runs it and exits the process. */
    fun runIfRequested(args: Array<String>): Boolean {
        if (FLAG !in args) return false
        System.setProperty("java.awt.headless", "true")
        val code = try {
            if (run(offline = "--offline" in args)) 0 else 1
        } catch (failure: Throwable) {
            println("FAILED with an unexpected error: $failure")
            failure.printStackTrace(System.out)
            1
        }
        System.out.flush()
        exitProcess(code)
    }

    private fun check(condition: Boolean, what: String) {
        println((if (condition) "  ok    " else "  FAIL  ") + what)
        if (!condition) failures += what
    }

    private fun run(offline: Boolean): Boolean {
        println("DebridOne Manga self-test")
        val workspace = Files.createTempDirectory("debridone-manga-self-test")
        val paths = AppPaths(workspace.resolve("data"), workspace.resolve("cache"))
        val secrets = FileSecretStore(workspace.resolve("secrets"))
        var current: MangaModule? = null
        fun open(): Pair<MangaModule, MangaServices> {
            val module = MangaModule(ModuleStorage(ModuleId.MANGA, paths, secrets))
            runBlocking {
                module.start()
                module.onProfileChanged(PROFILE)
            }
            val status = module.status.value
            check(status == ModuleStatus.Running, "Manga starts ($status)")
            current = module
            return module to checkNotNull(module.services.value)
        }
        val komga = StandInKomga()
        try {
            println("\n1. A local CBZ, with no extension and no network")
            var (module, manga) = open()
            val pages = (1..9).map { pageImage("local page $it") }
            val cbz = workspace.resolve("files").resolve("Self Test v01.cbz")
            Files.createDirectories(cbz.parent)
            ZipOutputStream(Files.newOutputStream(cbz)).use { zip ->
                pages.forEachIndexed { index, bytes ->
                    zip.putNextEntry(ZipEntry("page${index + 1}.png"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            val local = manga.local.import(PROFILE, cbz)
            check(manga.repository.library(PROFILE).singleOrNull()?.series?.title == "Self Test v01", "the archive is in the library")
            val localChapter = manga.repository.chapters(local.id).single()
            runBlocking {
                val opened = manga.pages.open(localChapter.id)
                check(opened.pageCount == 9 && opened.origin == "local", "it opens with 9 pages")
                check((0..4).all { manga.pages.page(opened, it).contentEquals(pages[it]) }, "pages 1 to 5 are the pages in the archive, in order")
            }
            manga.repository.saveProgress(localChapter.id, page = 4, pageCount = 9)
            check(manga.host.generation == 0, "the extension host was not needed")
            module.close()
            open().let { module = it.first; manga = it.second }
            val resumedLocal = manga.repository.continueReading(PROFILE).firstOrNull()
            check(resumedLocal?.chapter?.id == localChapter.id && resumedLocal.chapter.lastPage == 4, "after a restart it resumes on page 5")

            println("\n2. The extension runtime inside this build")
            val runtime = runCatching { MangaHostRuntime.locate() }
            check(runtime.isSuccess, "a Node.js runtime is present" + (runtime.exceptionOrNull()?.let { " (${it.message})" } ?: ""))
            if (runtime.isFailure) return report()
            println("   runtime: ${runtime.getOrThrow().origin}, ${runtime.getOrThrow().node}")
            runBlocking { manga.host.ensureStarted() }
            val running = manga.host.state.value as? HostState.Running
            check(running != null, "the extension host starts")
            println("   Node.js ${running?.info?.nodeVersion}, host ${running?.info?.hostVersion}")
            check(running?.info?.restricted == true, "it runs with file access restricted to its own folders")
            val named = !System.getenv(MangaHostRuntime.NODE_ENV).isNullOrBlank() || !System.getProperty(MangaHostRuntime.NODE_PROPERTY).isNullOrBlank()
            if (named) {
                println("   (a runtime was named for this run, as when running from source; the packaged app uses the copy inside it)")
            } else {
                check(runtime.getOrThrow().origin == "bundled", "it is the copy inside the application, not one found on this computer")
            }

            if (offline) {
                println("\n3. and 4. skipped (--offline): installing Tiyo needs the package registry.")
                return report()
            }

            println("\n3. Houdoku's extension package, from the registry")
            val version = PluginRules.VERIFIED_TIYO_VERSIONS.maxWithOrNull(PluginRules::compareVersions)!!
            val prepared = runBlocking { manga.plugins.prepare(PluginRules.TIYO_PACKAGE, version) }
            check(prepared.staged.pinned && prepared.compatibility.level == CompatibilityLevel.VERIFIED, "Tiyo $version: ${prepared.staged.packages} archives downloaded, each matching its pinned checksum")
            check(prepared.sources.size >= 40, "loaded on trial it registers ${prepared.sources.size} sources")
            check(runBlocking { manga.plugins.installed() }.isEmpty(), "nothing is installed before it is confirmed")
            val installed = runBlocking { module.confirmInstall(prepared) }
            check(installed is PluginActionResult.Success, "it installs" + ((installed as? PluginActionResult.Failure)?.let { ": ${it.message}" } ?: ""))
            val sources = manga.sources.list(PROFILE)
            check(sources.size >= 40, "${sources.size} sources are available in the running host")
            check(module.plugins.installed.value.any { it.id == PluginRules.TIYO_PACKAGE }, "it is listed in Manga's own plugin registry")

            println("\n4. One of its sources, end to end (Tiyo's Komga source against a stand-in server)")
            val source = sources.first { it.name == "Komga" }
            runBlocking {
                manga.sources.saveSettings(PROFILE, source.descriptor, buildJsonObject { komga.settings().forEach { (key, value) -> put(key, value) } })
                val results = manga.sources.search(PROFILE, "lanterns", listOf(source.id)).single()
                check(results.error == null && results.series.singleOrNull()?.title == "Paper Lanterns", "search finds the series" + (results.error?.let { " (${it.message})" } ?: ""))
                val series = manga.sources.remember(PROFILE, results.series.first())
                val added = manga.sources.refresh(series.id)
                val chapters = manga.repository.chapters(series.id)
                check(added == 2 && chapters.size == 2, "its details and ${chapters.size} chapters are retrieved")
                val health = manga.sources.test(source.id)
                check(health.ok, "the source test passes: " + health.steps.joinToString { it.name + (if (it.ok) " ok" else " FAILED") })
                val opened = manga.pages.open(chapters.first().id)
                check(opened.pageCount == 5 && opened.origin == "online", "the first chapter opens with 5 pages")
                check((0..2).all { manga.pages.page(opened, it).contentEquals(komga.pages[it]) }, "its pages are the server's pages")
                manga.repository.setInLibrary(series.id, true)
                manga.repository.saveProgress(chapters.first().id, page = 2, pageCount = 5)
                // An edit by hand survives a refresh from the source.
                manga.repository.editSeries(series.id, SeriesEdit(title = "My Title"))
                manga.sources.refresh(series.id)
                val after = manga.repository.series(series.id)
                check(after?.title == "My Title" && SeriesField.TITLE in after.lockedFields, "a locked edit survives a refresh")
            }
            module.close()
            open().let { module = it.first; manga = it.second }
            val resumed = manga.repository.continueReading(PROFILE).firstOrNull { it.summary.series.title == "My Title" }
            check(resumed?.chapter?.lastPage == 2, "after a restart the series resumes on page 3")
            runBlocking {
                val reopened = resumed?.let { manga.pages.open(it.chapter.id) }
                check(reopened != null && manga.pages.page(reopened, 4).contentEquals(komga.pages[4]), "the source still works after the restart, with its saved settings")
            }
            check(manga.host.crashCount == 0, "the extension host did not crash")
            return report()
        } finally {
            runCatching { current?.close() }
            komga.close()
            runCatching { workspace.toFile().deleteRecursively() }
        }
    }

    private fun report(): Boolean {
        println()
        if (failures.isEmpty()) println("MANGA SELF-TEST PASSED") else println("MANGA SELF-TEST FAILED: ${failures.size} check(s)\n  - " + failures.joinToString("\n  - "))
        return failures.isEmpty()
    }

    private fun pageImage(label: String): ByteArray {
        val image = BufferedImage(240, 360, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(245, 245, 240)
        graphics.fillRect(0, 0, 240, 360)
        graphics.color = Color(30, 30, 30)
        graphics.drawRect(8, 8, 223, 343)
        graphics.drawString(label, 24, 180)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** The part of Komga's REST API that Tiyo's Komga source uses, serving one drawn series. Local only. */
    private class StandInKomga : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val user = "reader@example.org"
        private val password = "self-test"
        val pages: List<ByteArray> = (1..5).map { pageImage("online page $it") }

        fun settings(): Map<String, String> = mapOf("Address (with port)" to "http://127.0.0.1:${server.address.port}", "Username" to user, "Password" to password)

        init {
            val series = """{"id":"S1","metadata":{"title":"Paper Lanterns","summary":"A stand-in series.","status":"ONGOING","language":"en","genres":["Drama"],"tags":[]},"booksMetadata":{"authors":[{"name":"Test Author","role":"writer"}]}}"""
            server.createContext("/") { exchange ->
                val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
                if (exchange.requestHeaders.getFirst("Authorization") != expected && exchange.requestHeaders.getFirst("X-Auth-Token") != "session") {
                    exchange.reply(401, "{}".toByteArray(), "application/json")
                    return@createContext
                }
                val path = exchange.requestURI.path
                val page = Regex("^/api/v1/books/B[12]/pages/(\\d+)$").find(path)?.groupValues?.get(1)?.toIntOrNull()
                when {
                    path == "/api/v1/series" -> exchange.reply(200, """{"content":[$series],"last":true}""".toByteArray(), "application/json")
                    path == "/api/v1/series/S1" -> exchange.reply(200, series.toByteArray(), "application/json")
                    path == "/api/v1/series/S1/books" -> exchange.reply(
                        200,
                        """{"content":[{"id":"B1","metadata":{"title":"Paper Lanterns 001","number":"1"},"fileLastModified":"2024-03-01T10:00:00Z"},{"id":"B2","metadata":{"title":"Paper Lanterns 002","number":"2"},"fileLastModified":"2024-03-02T10:00:00Z"}],"last":true}""".toByteArray(),
                        "application/json",
                    )
                    Regex("^/api/v1/books/B[12]/pages$").matches(path) -> exchange.reply(200, pages.indices.joinToString(",", "[", "]") { """{"number":${it + 1}}""" }.toByteArray(), "application/json")
                    page != null && page in 1..pages.size -> exchange.reply(200, pages[page - 1], "image/png")
                    path == "/api/v1/series/S1/thumbnail" -> exchange.reply(200, pages[0], "image/png")
                    else -> exchange.reply(404, "{}".toByteArray(), "application/json")
                }
            }
            server.start()
        }

        private fun HttpExchange.reply(status: Int, body: ByteArray, contentType: String) {
            responseHeaders.add("Content-Type", contentType)
            responseHeaders.add("X-Auth-Token", "session")
            sendResponseHeaders(status, body.size.toLong())
            responseBody.use { it.write(body) }
        }

        override fun close() = server.stop(0)
    }
}
