package com.debridone.app.manga

import com.debridone.manga.MangaModule
import com.debridone.manga.MangaServices
import com.debridone.manga.suwayomi.MangaConnection
import com.debridone.manga.suwayomi.ServerState
import com.debridone.manga.suwayomi.SuwayomiApi
import com.debridone.manga.suwayomi.SuwayomiException
import com.debridone.manga.suwayomi.SuwayomiRuntimeLocator
import com.debridone.manga.suwayomi.SuwayomiTransport
import com.debridone.module.ModuleId
import com.debridone.module.ModuleStatus
import com.debridone.module.storage.ModuleStorage
import com.debridone.platform.AppPaths
import com.debridone.platform.secrets.FileSecretStore
import kotlinx.coroutines.runBlocking
import com.sun.net.httpserver.HttpServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Checks Manga without opening a window, then exits with 0 (all good) or 1:
 *
 *     DebridOne --manga-self-test
 *
 * It uses a temporary data folder and does, in this build, what a reader would do:
 *  1. finds Suwayomi-Server and the Java runtime that ship inside the app;
 *  2. starts the server for a profile and checks that only DebridOne can talk to it;
 *  3. reads a comic archive through the server's Local source, closes DebridOne, starts it again
 *     and resumes where it stopped;
 *  4. imports a Mangayomi index served from this computer, installs its JavaScript source through
 *     DebridOne's bridge, and reads a page through it (the bridge runs inside the server, on the
 *     server's JavaScript engine: this proves it does on each Mac);
 *  5. checks that no server process is left behind.
 *
 * It needs no network and adds no extension store or index: DebridOne ships none. (Installing a real
 * extension from a store and reading through it is covered by the opt-in live test of the Manga
 * module, which names a store on the command line.) The build runs this inside the packaged macOS
 * app, so the DMG is known to carry a server and a Java runtime that start.
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
            if (run()) 0 else 1
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

    private fun run(): Boolean {
        println("DebridOne Manga self-test")
        val workspace = Files.createTempDirectory("debridone-manga-self-test")
        val paths = AppPaths(workspace.resolve("data"), workspace.resolve("cache"))
        val secrets = FileSecretStore(workspace.resolve("secrets"))
        var current: MangaModule? = null

        fun open(): Pair<MangaModule, MangaServices>? {
            val module = MangaModule(ModuleStorage(ModuleId.MANGA, paths, secrets))
            runBlocking {
                module.start()
                module.onProfileChanged(PROFILE)
            }
            current = module
            val status = module.status.value
            check(status == ModuleStatus.Running, "Manga starts ($status)")
            val services = module.services.value ?: return null
            check(services.server.state.value is MangaConnection.Idle, "no server is started before Manga is needed")
            val started = runCatching { runBlocking { module.awaitServer() } }
            check(started.isSuccess, "the manga server starts and answers" + (started.exceptionOrNull()?.let { " (${it.message})" } ?: ""))
            if (started.isFailure) {
                services.server.manager.log().takeLast(25).forEach { println("      | $it") }
                return null
            }
            return module to services
        }

        try {
            println("\n1. What ships inside this build")
            val runtime = runCatching { SuwayomiRuntimeLocator.locate() }
            check(runtime.isSuccess, "Suwayomi-Server and a Java runtime are present" + (runtime.exceptionOrNull()?.let { " (${it.message})" } ?: ""))
            if (runtime.isFailure) return report()
            println("   server:  ${runtime.getOrThrow().jar}")
            println("   java:    ${runtime.getOrThrow().java}")
            val named = !System.getenv("DEBRIDONE_SUWAYOMI_JAVA").isNullOrBlank() || !System.getProperty("debridone.suwayomi.java").isNullOrBlank()
            if (named) {
                println("   (a runtime was named for this run, as when running from source; the packaged app uses the copies inside it)")
            } else {
                val resources = System.getProperty("compose.application.resources.dir").orEmpty()
                check(resources.isNotBlank() && runtime.getOrThrow().java.startsWith(resources) && runtime.getOrThrow().jar.startsWith(resources), "they are the copies inside the application, not ones found on this computer")
            }
            val version = runCatching {
                val process = ProcessBuilder(runtime.getOrThrow().java.toString(), "-version").redirectErrorStream(true).start()
                // The line that names the version; a computer can make Java print other lines before it.
                process.inputStream.bufferedReader().readText().lineSequence().firstOrNull { " version \"" in it }.orEmpty().also { process.waitFor() }
            }.getOrDefault("")
            check(Regex("version \"(\\d+)").find(version)?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 21 } == true, "the bundled Java is version 21 or newer ($version)")

            println("\n2. The server, started for one profile")
            var (module, manga) = open() ?: return report()
            val ready = manga.server.state.value as? MangaConnection.Ready
            println("   Suwayomi-Server ${ready?.version} at ${ready?.address}")
            check(ready != null && !ready.remote && ready.address.startsWith("http://127.0.0.1:"), "it listens on this computer only")
            val endpoint = (manga.server.manager.state.value as? ServerState.Ready)?.endpoint
            val refused = endpoint != null && runCatching { SuwayomiApi(SuwayomiTransport(endpoint.copy(username = null, password = null))).about() }
                .exceptionOrNull().let { it is SuwayomiException && it.kind == SuwayomiException.Kind.UNAUTHORIZED }
            check(refused, "without DebridOne's password it answers nothing")
            check(manga.extensions.stores().isEmpty(), "no extension store is configured")
            check(manga.sources.list(PROFILE).map { it.id } == listOf("0"), "the only source is Suwayomi's own Local source")
            val pid = manga.server.manager.runningProcess
            check(pid != null, "the server process is known (pid $pid)")

            println("\n3. A comic archive, through the server, across a restart of DebridOne")
            val pages = (1..9).map { pageImage("page $it") }
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
            val chapter = manga.repository.chapters(local.id).single()
            runBlocking {
                val opened = manga.pages.open(chapter.id)
                check(opened.pageCount == 9 && opened.origin == "local", "it opens with 9 pages")
                check((0..4).all { manga.pages.page(opened, it).contentEquals(pages[it]) }, "pages 1 to 5 are the pages in the archive, in order")
                check(manga.pages.cover(local) != null, "it has a cover")
            }
            manga.repository.saveProgress(chapter.id, page = 4, pageCount = 9)
            manga.repository.setFavorite(local.id, true)
            val health = manga.sources.test("0")
            check(health.ok, "the source test passes: " + health.steps.joinToString { it.name + (if (it.ok) " ok" else " FAILED: ${it.detail}") })

            module.close()
            check(pid != null && ProcessHandle.of(pid).map { !it.isAlive }.orElse(true), "closing DebridOne ends the server process")
            open()?.let { module = it.first; manga = it.second } ?: return report()
            val resumed = manga.repository.continueReading(PROFILE).firstOrNull()
            check(resumed?.chapter?.id == chapter.id && resumed.chapter.lastPage == 4, "after a restart it resumes on page 5")
            check(manga.repository.series(local.id)?.favorite == true, "what DebridOne keeps with the series (a favourite) is still there")
            runBlocking {
                val reopened = manga.pages.open(chapter.id)
                check(manga.pages.page(reopened, 8).contentEquals(pages[8]), "the last page reads after the restart")
            }

            println("\n4. A Mangayomi JavaScript source, through the bridge")
            mangayomi(manga)

            println("\n5. Nothing left behind")
            val second = manga.server.manager.runningProcess
            module.close()
            check(second != null && ProcessHandle.of(second).map { !it.isAlive }.orElse(true), "no server process remains")
            check(Files.isRegularFile(cbz), "the person's own file is where it was")
            return report()
        } finally {
            runCatching { current?.close() }
            runCatching { workspace.toFile().deleteRecursively() }
        }
    }

    /** A stand-in Mangayomi index and site on this computer, imported and read the way the Import tab does it. */
    private fun mangayomi(manga: MangaServices) {
        val png = pageImage("bridge")
        val site = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${site.address.port}"
        val code = """
            class DefaultExtension extends MProvider {
              getHeaders(url) { return { Referer: this.source.baseUrl + "/" }; }
              async getPopular(page) {
                const doc = new Document((await new Client().get(this.source.baseUrl + "/list", this.getHeaders())).body);
                return { list: doc.select("div.item a").map((a) => ({ name: a.text.trim(), link: a.getHref, imageUrl: "" })), hasNextPage: false };
              }
              async getLatestUpdates(page) { return await this.getPopular(page); }
              async search(query, page, filters) { return await this.getPopular(page); }
              async getDetail(url) {
                const doc = new Document((await new Client().get(this.source.baseUrl + url, this.getHeaders())).body);
                return { name: doc.selectFirst("h1").text, description: "", status: 1, genre: [], chapters: doc.select("li a").map((a) => ({ name: a.text, url: a.getHref, dateUpload: "1700000000000" })) };
              }
              async getPageList(url) {
                const doc = new Document((await new Client().get(this.source.baseUrl + url, this.getHeaders())).body);
                return doc.select("img.page").map((img) => img.getSrc);
              }
              getFilterList() { return []; }
              getSourcePreferences() { return []; }
            }
        """.trimIndent()
        val index = """[{"name":"Self Test Comics","id":777001,"baseUrl":"$base","lang":"en","typeSource":"single","iconUrl":"","isNsfw":false,"hasCloudflare":false,
            "sourceCodeUrl":"$base/src/selftest.js","version":"1.0.0","itemType":0,"sourceCodeLanguage":1},
            {"name":"Self Test Dart","id":777002,"baseUrl":"$base","lang":"en","typeSource":"madara","iconUrl":"","isNsfw":false,"hasCloudflare":false,
            "sourceCodeUrl":"$base/src/selftest.dart","version":"1.0.0","itemType":0,"sourceCodeLanguage":0}]"""
        site.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val (status, type, body) = when (path) {
                "/index.json" -> Triple(200, "application/json", index.toByteArray())
                "/src/selftest.js" -> Triple(200, "text/javascript", code.toByteArray())
                "/list" -> Triple(200, "text/html", """<div class="item"><a href="/series/1">Bridge Saga</a></div>""".toByteArray())
                "/series/1" -> Triple(200, "text/html", """<h1>Bridge Saga</h1><ul><li><a href="/chapter/1">Chapter 1</a></li></ul>""".toByteArray())
                "/chapter/1" -> Triple(200, "text/html", """<img class="page" src="$base/img/1.png"><img class="page" src="$base/img/2.png">""".toByteArray())
                "/img/1.png", "/img/2.png" ->
                    if (exchange.requestHeaders.getFirst("Referer") == "$base/") Triple(200, "image/png", png) else Triple(403, "text/plain", "no".toByteArray())
                else -> Triple(404, "text/plain", "not here".toByteArray())
            }
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        site.start()
        try {
            val preview = runCatching { manga.imports.inspect("$base/index.json") }
            check(preview.isSuccess, "the index is read and recognised" + (preview.exceptionOrNull()?.let { " (${it.message})" } ?: ""))
            val items = preview.getOrNull()?.items.orEmpty().associateBy { it.name }
            check(items["Self Test Comics"]?.installable == true, "its JavaScript source can run here")
            check(items["Self Test Dart"]?.installable == false, "its Dart source is refused, with its reason")
            val report = runCatching { manga.imports.install(preview.getOrThrow(), items.values.map { it.key }) }
            check(report.getOrNull()?.installed == listOf("Self Test Comics"), "the JavaScript source is installed by the server: ${report.getOrNull() ?: report.exceptionOrNull()?.message}")
            val source = manga.sources.list(PROFILE, refresh = true).firstOrNull { it.name == "Self Test Comics" }
            check(source != null, "the server lists it as a source")
            if (source == null) {
                manga.server.manager.log().takeLast(20).forEach { println("      | $it") }
                return
            }
            val health = manga.sources.test(source.id)
            check(health.ok, "its test passes: " + health.steps.joinToString { it.name + (if (it.ok) " ok" else " FAILED: ${it.detail}") })
            val listed = runCatching { manga.sources.browse(source.id, com.debridone.manga.sources.BrowseMode.POPULAR).series }
            val series = listed.getOrNull()?.firstOrNull()
            check(series?.title == "Bridge Saga", "it lists the site's series")
            if (series != null) {
                manga.sources.refresh(series.id)
                val chapter = manga.repository.chapters(series.id).firstOrNull()
                check(chapter != null, "the series has its chapter")
                if (chapter != null) runBlocking {
                    val opened = manga.pages.open(chapter.id)
                    check(opened.pageCount == 2 && manga.pages.page(opened, 0).contentEquals(png), "a page is read through it, with the headers the source asks for")
                }
            }
            runCatching { manga.imports.uninstall(manga.imports.installedRecords().keys.first()) }
            check(manga.sources.list(PROFILE, refresh = true).none { it.name == "Self Test Comics" }, "it is removed again")
        } finally {
            site.stop(0)
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
}
