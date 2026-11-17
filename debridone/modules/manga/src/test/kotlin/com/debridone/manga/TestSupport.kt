package com.debridone.manga

import com.debridone.manga.host.ExtensionHost
import com.debridone.manga.host.HostLaunch
import com.debridone.manga.host.HostRuntime
import com.debridone.manga.host.MangaHostRuntime
import com.debridone.manga.plugins.PluginInstaller
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO

/** Finds the Node.js runtime and prepared host the build points the tests at. */
object TestRuntime {
    private val required = System.getenv("DEBRIDONE_REQUIRE_NODE") == "1"
    val live: Boolean = System.getenv("DEBRIDONE_LIVE_TESTS") == "1"

    fun runtimeOrSkip(): HostRuntime {
        val runtime = runCatching { MangaHostRuntime.locate() }
        if (runtime.isFailure) {
            if (required) throw AssertionError("Node.js is required for these tests: ${runtime.exceptionOrNull()?.message}")
            Assume.assumeTrue("No Node.js runtime for the extension host: ${runtime.exceptionOrNull()?.message}", false)
        }
        return runtime.getOrThrow()
    }

    fun assumeLive() {
        Assume.assumeTrue("Live tests are off (set DEBRIDONE_LIVE_TESTS=1)", live)
    }

    /** A proxy for the host's own requests, when this machine reaches the internet through one. */
    fun proxy(): String? = listOf("HTTPS_PROXY", "https_proxy").firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }

    fun host(pluginsDir: Path, work: Path, restricted: Boolean = true): ExtensionHost {
        val runtime = runtimeOrSkip()
        return ExtensionHost({
            HostLaunch(
                runtime = runtime,
                pluginsDir = pluginsDir,
                spoolDir = work.resolve("spool"),
                homeDir = work.resolve("home"),
                proxy = proxy(),
                restricted = restricted,
                testMode = true,
            )
        })
    }
}

/**
 * The real, unmodified `@tiyo/core` 1.0.3, installed once per build folder by DebridOne's own
 * installer from the package registry (from the pinned, checksummed plan) and reused by every test.
 */
object TiyoFixture {
    private val dir: Path = Paths.get(System.getProperty("debridone.manga.fixtureDir") ?: "build/tiyo-fixture")
    private val required = System.getenv("DEBRIDONE_REQUIRE_NODE") == "1"

    @Synchronized
    fun pluginsDir(): Path {
        val plugins = dir.resolve("plugins")
        val marker = dir.resolve("installed-1.0.3")
        if (Files.exists(marker) && Files.isRegularFile(plugins.resolve("@tiyo/core/package.json"))) return plugins
        val installer = PluginInstaller(plugins, dir.resolve("work"))
        val outcome = runCatching {
            runBlocking {
                val plan = installer.pinnedPlan("@tiyo/core", "1.0.3") ?: error("The pinned plan for @tiyo/core 1.0.3 is missing.")
                installer.activate(installer.stage(plan, pinned = true))
            }
            Files.createDirectories(dir)
            Files.writeString(marker, "ok")
        }
        if (outcome.isFailure) {
            if (required) throw AssertionError("Tiyo could not be installed for the tests", outcome.exceptionOrNull())
            Assume.assumeTrue("Tiyo could not be installed (no access to the package registry?): ${outcome.exceptionOrNull()?.message}", false)
        }
        return plugins
    }
}

/** Builds `.tgz` package archives in memory, including deliberately malicious ones. */
class TarBuilder {
    private val out = ByteArrayOutputStream()

    fun file(path: String, content: String): TarBuilder = entry(path, content.toByteArray(StandardCharsets.UTF_8), '0')

    fun file(path: String, content: ByteArray): TarBuilder = entry(path, content, '0')

    fun directory(path: String): TarBuilder = entry(path, ByteArray(0), '5')

    fun symlink(path: String, target: String): TarBuilder = entry(path, ByteArray(0), '2', target)

    private fun entry(path: String, content: ByteArray, type: Char, link: String = ""): TarBuilder {
        val header = ByteArray(512)
        fun put(text: String, offset: Int, length: Int) {
            val bytes = text.toByteArray(StandardCharsets.UTF_8)
            System.arraycopy(bytes, 0, header, offset, minOf(bytes.size, length))
        }
        put(path, 0, 100)
        put("0000644", 100, 8)
        put("0000000", 108, 8)
        put("0000000", 116, 8)
        put(String.format("%011o", content.size), 124, 12)
        put("00000000000", 136, 12)
        put("        ", 148, 8)
        header[156] = type.code.toByte()
        put(link, 157, 100)
        put("ustar", 257, 6)
        put("00", 263, 2)
        val checksum = header.sumOf { it.toInt() and 0xff }
        put(String.format("%06o", checksum), 148, 6)
        header[154] = 0
        header[155] = ' '.code.toByte()
        out.write(header)
        out.write(content)
        val padding = (512 - content.size % 512) % 512
        out.write(ByteArray(padding))
        return this
    }

    fun build(): ByteArray {
        val tar = out.toByteArray() + ByteArray(1024)
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tar) }
        return gz.toByteArray()
    }
}

fun sha512Integrity(bytes: ByteArray): String =
    "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes))

/** A tiny stand-in for the npm registry: serves whatever archives a test registers. */
class StandInRegistry : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val files = HashMap<String, ByteArray>()
    private val documents = HashMap<String, String>()
    val requests = CopyOnWriteArrayList<String>()

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.rawPath
            requests += path
            val file = files[path]
            val document = documents[path]
            when {
                file != null -> exchange.reply(200, file, "application/octet-stream")
                document != null -> exchange.reply(200, document.toByteArray(), "application/json")
                else -> exchange.reply(404, "{}".toByteArray(), "application/json")
            }
        }
        server.start()
    }

    /** Registers an archive and returns its address. */
    fun archive(name: String, version: String, bytes: ByteArray): String {
        val path = "/$name/-/${name.substringAfterLast('/')}-$version.tgz"
        files[path] = bytes
        return url + path
    }

    fun document(path: String, json: String) {
        documents[path] = json
    }

    private val published = LinkedHashMap<String, LinkedHashMap<String, String>>()

    /** Publishes a version the way the real registry lists one: archive, checksum and a package document. */
    fun publish(name: String, version: String, bytes: ByteArray, dependencies: Map<String, String> = emptyMap()) {
        val tarball = archive(name, version, bytes)
        val deps = dependencies.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        published.getOrPut(name) { LinkedHashMap() }[version] =
            "{\"name\":\"$name\",\"version\":\"$version\",\"dependencies\":{$deps},\"dist\":{\"tarball\":\"$tarball\",\"integrity\":\"${sha512Integrity(bytes)}\"}}"
        val versions = published.getValue(name)
        val latest = versions.keys.last()
        document(
            "/" + name.replace("/", "%2F"),
            "{\"name\":\"$name\",\"description\":\"stand-in package\",\"dist-tags\":{\"latest\":\"$latest\"}," +
                "\"versions\":{${versions.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}," +
                "\"time\":{${versions.keys.joinToString(",") { "\"$it\":\"2024-05-05T00:00:00.000Z\"" }}}}",
        )
    }

    override fun close() = server.stop(0)
}

fun HttpExchange.reply(status: Int, body: ByteArray, contentType: String, headers: Map<String, String> = emptyMap()) {
    responseHeaders.add("Content-Type", contentType)
    headers.forEach { (name, value) -> responseHeaders.add(name, value) }
    sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
    if (body.isNotEmpty()) responseBody.use { it.write(body) } else close()
}

/** A drawn page with its number on it, so a test can tell pages apart by their bytes. */
fun pageImage(label: String, width: Int = 320, height: Int = 480, format: String = "png"): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = Color(245, 245, 240)
    graphics.fillRect(0, 0, width, height)
    graphics.color = Color(30, 30, 30)
    graphics.drawRect(8, 8, width - 17, height - 17)
    graphics.font = graphics.font.deriveFont(36f)
    graphics.drawString(label, 28, height / 2)
    graphics.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, format, out)
    return out.toByteArray()
}

/**
 * Speaks the part of the Komga REST API that Tiyo's Komga source uses. Komga is a self-hosted
 * comics server, and the address is a setting of the source, so the real, unmodified extension can
 * be pointed at this stand-in and exercised end to end without anyone else's server.
 */
class StandInKomga(private val user: String = "reader@example.org", private val password: String = "correct horse") : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<String>()
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    data class Book(val id: String, val title: String, val number: Int, val pages: List<ByteArray>)

    data class SeriesEntry(
        val id: String,
        val title: String,
        val summary: String,
        val status: String,
        val language: String,
        val genres: List<String>,
        val authors: List<String>,
        val books: List<Book>,
    )

    val series = listOf(
        SeriesEntry(
            "S1", "Paper Lanterns", "A stand-in series drawn for DebridOne's tests.", "ONGOING", "ja", listOf("Drama", "Slice of Life"), listOf("Test Author"),
            listOf(
                Book("B1", "Paper Lanterns 001", 1, (1..5).map { pageImage("S1 c1 p$it") }),
                Book("B2", "Paper Lanterns 002", 2, (1..3).map { pageImage("S1 c2 p$it") }),
            ),
        ),
        SeriesEntry(
            "S2", "Tower of Stairs", "A long-strip stand-in series.", "ENDED", "ko", listOf("Fantasy", "Webtoon"), listOf("Another Author"),
            listOf(Book("B3", "Tower of Stairs 001", 1, (1..4).map { pageImage("S2 c1 p$it", 300, 1400) })),
        ),
    )

    init {
        server.createContext("/") { exchange ->
            requests += exchange.requestMethod + " " + exchange.requestURI.toString()
            if (!authorized(exchange)) {
                exchange.reply(401, """{"error":"Unauthorized"}""".toByteArray(), "application/json")
                return@createContext
            }
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery.orEmpty()
            val token = mapOf("X-Auth-Token" to "stand-in-session")
            val seriesMatch = Regex("^/api/v1/series/([^/]+)$").find(path)
            val booksMatch = Regex("^/api/v1/series/([^/]+)/books$").find(path)
            val pagesMatch = Regex("^/api/v1/books/([^/]+)/pages$").find(path)
            val pageMatch = Regex("^/api/v1/books/([^/]+)/pages/(\\d+)$").find(path)
            val thumbnailMatch = Regex("^/api/v1/series/([^/]+)/thumbnail$").find(path)
            when {
                path == "/api/v1/series" -> {
                    val search = Regex("(?:^|&)search=([^&]*)").find(query)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                    val matching = series.filter { search.isBlank() || it.title.contains(search, ignoreCase = true) }
                    exchange.reply(200, """{"content":[${matching.joinToString(",") { seriesJson(it) }}],"last":true}""".toByteArray(), "application/json", token)
                }
                seriesMatch != null -> {
                    val entry = series.firstOrNull { it.id == seriesMatch.groupValues[1] }
                    if (entry == null) exchange.reply(404, "{}".toByteArray(), "application/json") else exchange.reply(200, seriesJson(entry).toByteArray(), "application/json", token)
                }
                booksMatch != null -> {
                    val entry = series.firstOrNull { it.id == booksMatch.groupValues[1] }
                    val books = entry?.books.orEmpty().joinToString(",") { """{"id":"${it.id}","metadata":{"title":"${it.title}","number":"${it.number}"},"fileLastModified":"2024-03-0${it.number}T10:00:00Z"}""" }
                    exchange.reply(200, """{"content":[$books],"last":true}""".toByteArray(), "application/json", token)
                }
                pagesMatch != null -> {
                    val book = book(pagesMatch.groupValues[1])
                    val pages = book?.pages.orEmpty().indices.joinToString(",") { """{"number":${it + 1},"fileName":"${it + 1}.png","mediaType":"image/png"}""" }
                    exchange.reply(200, "[$pages]".toByteArray(), "application/json", token)
                }
                pageMatch != null -> {
                    val page = book(pageMatch.groupValues[1])?.pages?.getOrNull(pageMatch.groupValues[2].toInt() - 1)
                    if (page == null) exchange.reply(404, ByteArray(0), "text/plain") else exchange.reply(200, page, "image/png", token)
                }
                thumbnailMatch != null -> {
                    val cover = series.firstOrNull { it.id == thumbnailMatch.groupValues[1] }?.books?.firstOrNull()?.pages?.firstOrNull()
                    if (cover == null) exchange.reply(404, ByteArray(0), "text/plain") else exchange.reply(200, cover, "image/png", token)
                }
                else -> exchange.reply(404, "{}".toByteArray(), "application/json")
            }
        }
        server.start()
    }

    fun book(id: String): Book? = series.flatMap { it.books }.firstOrNull { it.id == id }

    fun settings(password: String = this.password): Map<String, String> =
        mapOf("Address (with port)" to url, "Username" to user, "Password" to password)

    private fun authorized(exchange: HttpExchange): Boolean {
        if (exchange.requestHeaders.getFirst("X-Auth-Token") == "stand-in-session") return true
        val header = exchange.requestHeaders.getFirst("Authorization") ?: return false
        val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        return header == expected
    }

    private fun seriesJson(entry: SeriesEntry): String =
        """{"id":"${entry.id}","metadata":{"title":"${entry.title}","summary":"${entry.summary}","status":"${entry.status}","language":"${entry.language}",""" +
            """"genres":[${entry.genres.joinToString(",") { "\"$it\"" }}],"tags":[]},""" +
            """"booksMetadata":{"authors":[${entry.authors.joinToString(",") { """{"name":"$it","role":"writer"}""" }}]},"booksCount":${entry.books.size}}"""

    override fun close() = server.stop(0)
}

/** A small extension package with the same shape as Tiyo, for tests that must not need the network. */
object FakeTiyo {
    const val SOURCE_ID = "11111111-2222-3333-4444-555555555555"
    const val BROKEN_ID = "99999999-2222-3333-4444-555555555555"

    /** The pages of every chapter of a series whose id starts with "book": real images, so they can be read. */
    val pages: List<ByteArray> = (1..4).map { pageImage("book p$it", 200, 300) }

    private val index = """
        'use strict';
        const fs = require('fs');
        const PAGES = [${pages.joinToString(",") { "'" + Base64.getEncoder().encodeToString(it) + "'" }}];
        class Good {
          constructor(webviewFn) { this.webviewFn = webviewFn; this.settings = { Token: '' }; }
          getSeries(id) {
            if (id.startsWith('read:')) return Promise.resolve({ sourceId: id, title: fs.readFileSync(id.slice(5), 'utf8') });
            if (id.startsWith('write:')) { fs.writeFileSync(id.slice(6), 'x'); return Promise.resolve({ sourceId: id, title: 'written' }); }
            if (id === 'spawn') { require('child_process').execSync('id'); return Promise.resolve({ sourceId: id, title: 'spawned' }); }
            if (id === 'env') return Promise.resolve({ sourceId: id, title: Object.keys(process.env).sort().join(',') });
            if (id === 'exit') { process.exit(7); }
            if (id === 'missing') return Promise.resolve(undefined);
            return Promise.resolve({ extensionId: '$SOURCE_ID', sourceId: id, title: 'Series ' + id, altTitles: ['Alt ' + id], description: 'About ' + id,
              authors: ['A'], artists: ['B'], tags: ['Action'], status: 'Ongoing', originalLanguageKey: 'KOREAN', numberUnread: 0, remoteCoverUrl: '' });
          }
          getChapters(id) { return Promise.resolve([1, 2, 3].map(n => ({ sourceId: id + '-c' + n, title: 'Chapter ' + n, chapterNumber: String(n), volumeNumber: '', languageKey: 'ENGLISH', groupName: 'G', time: 1700000000000 + n, read: false }))); }
          getPageRequesterData(seriesId, chapterId) {
            if (seriesId.startsWith('book')) return Promise.resolve({ server: 'page:', hash: '', numPages: PAGES.length, pageFilenames: PAGES.map((_, i) => String(i)) });
            if (seriesId.startsWith('flaky')) return Promise.resolve({ server: 'flaky:', hash: '', numPages: PAGES.length, pageFilenames: PAGES.map((_, i) => String(i)) });
            return Promise.resolve({ server: 'https://pages.invalid', hash: chapterId, numPages: 2, pageFilenames: ['1.png', '2.png'] });
          }
          getPageUrls(data) {
            if (data.server === 'page:' || data.server === 'flaky:') return data.pageFilenames.map(f => data.server + f);
            return data.pageFilenames.map(f => data.server + '/' + data.hash + '/' + f);
          }
          getImage(series, url) {
            if (url.startsWith('page:')) return Promise.resolve(Buffer.from(PAGES[Number(url.slice(5))], 'base64'));
            if (url.startsWith('flaky:')) {
              // The second page drops its connection the first time it is asked for; the last one always does.
              const n = Number(url.slice(6));
              this.asked = this.asked || {};
              this.asked[url] = (this.asked[url] || 0) + 1;
              const always = series && series.sourceId === 'flaky-always' && n === PAGES.length - 1;
              if (always || (n === 1 && this.asked[url] === 1)) { const e = new Error('socket hang up'); e.code = 'ECONNRESET'; return Promise.reject(e); }
              return Promise.resolve(Buffer.from(PAGES[n], 'base64'));
            }
            return Promise.resolve(url.endsWith('2.png') ? new Uint8Array([1, 2, 3, 4]).buffer : url);
          }
          getSearch(text, page) {
            if (text === 'flaky' || text === 'flaky-always') return Promise.resolve({ seriesList: [{ extensionId: '$SOURCE_ID', sourceId: text === 'flaky' ? 'flaky-1' : 'flaky-always', title: 'A Flaky Book', remoteCoverUrl: '' }], hasMore: false });
            if (text === 'book') return Promise.resolve({ seriesList: [{ extensionId: '$SOURCE_ID', sourceId: 'book-1', title: 'A Book', tags: ['Webtoon'], originalLanguageKey: 'KOREAN', remoteCoverUrl: 'page:0' }], hasMore: false });
            return Promise.resolve({ seriesList: [{ sourceId: 'found-' + text + '-' + page, title: 'Found ' + text }], hasMore: page < 2 });
          }
          getDirectory(page) { return this.getSearch('', page); }
          getSettingTypes() { return { Token: 'STRING' }; }
          getSettings() { return this.settings; }
          setSettings(s) { Object.assign(this.settings, s); }
          getFilterOptions() { return [{ kind: 'checkbox', id: 'c', label: 'Completed', defaultValue: false }]; }
        }
        class Broken extends Good {
          getSeries() { return Promise.reject(new Error('the site changed')); }
          getChapters() { throw new TypeError("Cannot read properties of undefined (reading 'map')"); }
          getSettingTypes() { return {}; }
        }
        class TiyoClient {
          constructor(spoofWindow) { this.spoofWindow = spoofWindow; this._webviewFn = () => Promise.reject(new Error('no window')); }
          getVersion() { return require('../package.json').version; }
          getExtensions() {
            return {
              '$SOURCE_ID': { metadata: { id: '$SOURCE_ID', name: 'Stand-in Source', url: 'https://pages.invalid', translatedLanguage: 'ENGLISH' }, client: new Good(this._webviewFn) },
              '$BROKEN_ID': { metadata: { id: '$BROKEN_ID', name: 'Broken Source', url: 'https://broken.invalid', translatedLanguage: 'FRENCH' }, client: new Broken(this._webviewFn) },
            };
          }
        }
        exports.TiyoClient = TiyoClient;
    """.trimIndent()

    fun archive(version: String = "1.0.99", name: String = "@tiyo/core", indexSource: String = index): ByteArray = TarBuilder()
        .file("package/package.json", """{"name":"$name","version":"$version","main":"./src/index.js"}""")
        .file("package/src/index.js", indexSource)
        .build()

    /** Unpacks the fake package straight into a plugin folder (for host tests that do not test the installer). */
    fun installInto(pluginsDir: Path, version: String = "1.0.99") {
        val target = pluginsDir.resolve("@tiyo/core")
        Files.createDirectories(target.resolve("src"))
        Files.writeString(target.resolve("package.json"), """{"name":"@tiyo/core","version":"$version","main":"./src/index.js"}""")
        Files.writeString(target.resolve("src/index.js"), index)
    }
}

/** Writes a ZIP/CBZ archive with the given entries, in the given order. */
fun writeZip(file: Path, entries: List<Pair<String, ByteArray>>) {
    Files.createDirectories(file.parent)
    java.util.zip.ZipOutputStream(Files.newOutputStream(file)).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(java.util.zip.ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}
