package com.debridone.manga.mangayomi

import com.debridone.manga.suwayomi.MangaConnection
import com.debridone.manga.MangaModule
import com.debridone.manga.MangaServices
import com.debridone.manga.reader.PageBytes
import com.debridone.manga.reader.PageImageCheck
import com.debridone.manga.sources.BrowseMode
import com.debridone.manga.suwayomi.SuwayomiTestRuntime
import com.debridone.module.ModuleId
import com.debridone.module.storage.ModuleStorage
import com.debridone.platform.AppPaths
import com.debridone.platform.secrets.FileSecretStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A Mangayomi index, imported the way the Extensions tab does it, on the real Suwayomi-Server:
 * the JavaScript source is packed with the bridge, installed by the server and read through it
 * (list, series, chapters, pages and a page's picture, with the headers the source asks for); the
 * Dart and anime sources are refused with their reason; a new version is found and installed. The
 * site, the index and the code are served from this computer.
 */
class MangayomiImportTest {
    private lateinit var dir: Path
    private lateinit var site: HttpServer
    private lateinit var module: MangaModule
    private lateinit var services: MangaServices
    private var version = "1.0.0"
    private val pictures = ArrayList<String>()

    private val png: ByteArray = BufferedImage(60, 90, BufferedImage.TYPE_INT_RGB).let { image ->
        image.createGraphics().apply { color = Color(30, 110, 230); fillRect(0, 0, 60, 90); dispose() }
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private val base get() = "http://127.0.0.1:${site.address.port}"

    private fun index() = """[
        {"name":"Stand-in Comics","id":424242,"baseUrl":"$base","lang":"en","typeSource":"single","iconUrl":"$base/icon.png","dateFormat":"","dateFormatLocale":"",
         "isNsfw":false,"hasCloudflare":false,"sourceCodeUrl":"$base/src/standin.js","apiUrl":"","version":"$version","isManga":true,"itemType":0,"isFullData":false,
         "appMinVerReq":"0.5.0","additionalParams":"","sourceCodeLanguage":1},
        {"name":"Some Madara Site","id":515151,"baseUrl":"$base/madara","lang":"en","typeSource":"madara","iconUrl":"","dateFormat":"MMMM dd, yyyy","dateFormatLocale":"en_us",
         "isNsfw":false,"hasCloudflare":false,"sourceCodeUrl":"$base/src/madara.dart","apiUrl":"","version":"0.1.15","isManga":true,"itemType":0,"isFullData":false,
         "appMinVerReq":"0.2.0","additionalParams":"","sourceCodeLanguage":0},
        {"name":"Some Anime Site","id":616161,"baseUrl":"$base/anime","lang":"en","typeSource":"single","iconUrl":"","dateFormat":"","dateFormatLocale":"",
         "isNsfw":false,"hasCloudflare":false,"sourceCodeUrl":"$base/src/anime.js","apiUrl":"","version":"0.0.1","isManga":false,"itemType":1,"isFullData":false,
         "appMinVerReq":"0.5.0","additionalParams":"","sourceCodeLanguage":1}
    ]"""

    private val code = """
        class DefaultExtension extends MProvider {
          getHeaders(url) { return { Referer: this.source.baseUrl + "/" }; }
          get supportsLatest() { return false; }
          async getPopular(page) {
            const doc = new Document((await new Client().get(this.source.baseUrl + "/list", this.getHeaders())).body);
            return { list: doc.select("div.item a").map((a) => ({ name: a.text.trim(), link: a.getHref, imageUrl: a.selectFirst("img").getSrc })), hasNextPage: false };
          }
          async search(query, page, filters) { return await this.getPopular(page); }
          async getDetail(url) {
            const doc = new Document((await new Client().get(this.source.baseUrl + url, this.getHeaders())).body);
            return {
              name: doc.selectFirst("h1").text, description: doc.selectFirst("p").text, author: "Someone", status: 1, genre: ["Action"],
              chapters: doc.select("li a").map((a) => ({ name: a.text, url: a.getHref, dateUpload: "1700000000000" })),
            };
          }
          async getPageList(url) {
            const doc = new Document((await new Client().get(this.source.baseUrl + url, this.getHeaders())).body);
            return doc.select("img.page").map((img) => img.getSrc);
          }
          getFilterList() { return []; }
          getSourcePreferences() { return []; }
        }
    """.trimIndent()

    @BeforeTest
    fun start() {
        val runtime = SuwayomiTestRuntime.runtimeOrSkip()
        dir = Files.createTempDirectory("mangayomi-import")
        site = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val (status, type, body) = when {
                    path == "/index.json" -> Triple(200, "application/json", index().toByteArray())
                    path == "/src/standin.js" -> Triple(200, "text/javascript", code.toByteArray())
                    path == "/list" -> Triple(200, "text/html", """<div class="item"><a href="/series/7"><img src="/covers/7.png"> The Duck Saga </a></div><div class="item"><a href="/series/8"><img src="/covers/8.png">Other</a></div>""".toByteArray())
                    path == "/series/7" -> Triple(200, "text/html", """<h1>The Duck Saga</h1><p>A duck.</p><ul><li><a href="/chapter/71">Chapter 1</a></li></ul>""".toByteArray())
                    path == "/chapter/71" -> Triple(200, "text/html", """<img class="page" src="$base/img/1.png"><img class="page" src="$base/img/2.png">""".toByteArray())
                    path.startsWith("/img/") || path.startsWith("/covers/") -> {
                        // Pictures only for requests that carry the source's Referer, as many sites do.
                        synchronized(pictures) { pictures += "$path referer=${exchange.requestHeaders.getFirst("Referer")}" }
                        if (exchange.requestHeaders.getFirst("Referer") == "$base/") Triple(200, "image/png", png) else Triple(403, "text/plain", "no".toByteArray())
                    }
                    else -> Triple(404, "text/plain", "not here".toByteArray())
                }
                exchange.responseHeaders.add("Content-Type", type)
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val paths = AppPaths(dir.resolve("data"), dir.resolve("cache"))
        module = MangaModule(ModuleStorage(ModuleId.MANGA, paths, FileSecretStore(paths.secretsDir)), runtime = { runtime })
        runBlocking {
            module.start()
            module.onProfileChanged(PROFILE)
        }
        services = module.services.value!!
        module.ensureServer()
        val deadline = System.currentTimeMillis() + 120_000
        while (services.server.state.value !is MangaConnection.Ready) {
            check(System.currentTimeMillis() < deadline) { "The manga server did not start: ${services.server.state.value}" }
            Thread.sleep(50)
        }
    }

    @AfterTest
    fun stop() {
        if (::module.isInitialized) module.close()
        if (::site.isInitialized) site.stop(0)
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    @Test
    fun aMangayomiIndexIsImportedAndItsJavaScriptSourceReadThroughTheServer() {
        val preview = services.imports.inspect("$base/index.json")
        assertEquals(IndexFormat.MANGAYOMI, preview.format)
        val byName = preview.items.associateBy { it.name }
        assertEquals(Support.ADAPTER, byName.getValue("Stand-in Comics").compatibility.support)
        assertEquals(Support.NONE, byName.getValue("Some Madara Site").compatibility.support)
        assertTrue(byName.getValue("Some Madara Site").compatibility.reason.contains("Dart"))
        assertTrue(byName.getValue("Some Anime Site").compatibility.reason.contains("anime"))
        assertTrue(preview.note.startsWith("1 of 3 can run here."), preview.note)
        assertEquals("127.0.0.1 · 3 sources", preview.title)

        val report = services.imports.install(preview, preview.items.map { it.key })
        assertEquals(listOf("Stand-in Comics"), report.installed, report.toString())
        assertEquals(setOf("Some Madara Site", "Some Anime Site"), report.skipped.map { it.first }.toSet())
        assertEquals(emptyList(), report.failed)

        // The manga server knows the source now, like any extension's.
        val source = assertNotNull(services.sources.list(PROFILE, refresh = true).firstOrNull { it.name == "Stand-in Comics" })
        assertEquals("en", source.language)
        assertEquals(false, source.supportsLatest)
        val listed = services.sources.browse(source.id, BrowseMode.POPULAR)
        assertEquals(listOf("The Duck Saga", "Other"), listed.series.map { it.title })

        // A series' details and chapters, then a chapter's pages and the first picture, as the reader asks for them.
        val series = listed.series.first()
        assertEquals(1, services.sources.refresh(series.id))
        val chapter = services.repository.chapters(series.id).single()
        assertEquals("A duck.", services.repository.series(series.id)!!.description)
        val opened = runBlocking { services.pages.open(chapter.id) }
        assertEquals(2, opened.pageCount)
        val picture = runBlocking { services.pages.page(opened, 0) }
        assertIs<PageBytes.Whole>(PageImageCheck.inspect(picture))
        assertTrue(picture.contentEquals(png))
        assertTrue(pictures.any { it.startsWith("/img/1.png referer=$base/") }, "the server fetched the picture with the source's headers: $pictures")

        // The source test goes through every step.
        val health = services.sources.test(source.id)
        assertTrue(health.ok, health.steps.joinToString { "${it.name}: ${it.detail}" })

        // What was imported is kept with the profile's library.
        assertEquals(listOf("$base/index.json"), services.imports.indexes().map { it.address })
        val record = assertNotNull(services.imports.installedRecords()["debridone.mangayomi.m424242.en"])
        assertEquals("1.0.0", record.version)

        // A new version in the index is found and installed over the old one.
        version = "1.0.1"
        val updates = services.imports.checkUpdates()
        assertEquals(listOf("Stand-in Comics"), updates.map { it.name })
        services.imports.update("debridone.mangayomi.m424242.en")
        assertEquals("1.0.1", services.imports.installedRecords().getValue("debridone.mangayomi.m424242.en").version)
        assertEquals(listOf("The Duck Saga", "Other"), services.sources.browse(source.id, BrowseMode.POPULAR).series.map { it.title }, "the same source id after the update")

        // Removed, it is gone from the server and from the records.
        services.imports.uninstall("debridone.mangayomi.m424242.en")
        assertTrue(services.sources.list(PROFILE, refresh = true).none { it.name == "Stand-in Comics" })
        assertTrue("debridone.mangayomi.m424242.en" !in services.imports.installedRecords())
    }

    @Test
    fun whatIsNotAnIndexIsSaidPlainly() {
        val page = runCatching { services.imports.inspect("$base/list") }.exceptionOrNull()
        assertTrue(page?.message.orEmpty().contains("web page"), page?.message)
        val missing = runCatching { services.imports.inspect("$base/nothing.json") }.exceptionOrNull()
        assertTrue(missing?.message.orEmpty().contains("404"), missing?.message)
    }

    private companion object {
        const val PROFILE = 2001L
    }
}
