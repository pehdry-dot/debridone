package com.debridone.mangayomi.bridge

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The bridge runs a Mangayomi-style source against a site on this computer: the source lists,
 * opens a series, lists pages, reads its settings and posts, through the same calls a real
 * Mangayomi source makes. Failures keep their nature: the source's own errors read as its
 * messages, a network failure stays a network failure.
 */
class JsBridgeTest {
    private val requests = ArrayList<String>()
    private val site = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            synchronized(requests) { requests += "${exchange.requestMethod} $path referer=${exchange.requestHeaders.getFirst("Referer")}" }
            val body: String = when {
                path.startsWith("/list") -> """
                    <html><body><div class="grid">
                      <div class="item"><a href="/series/1"><img src="/covers/1.jpg"><span class="title"> First  Series </span></a></div>
                      <div class="item"><a href="/series/2"><img data-src="/covers/2.jpg" src="/blank.gif"><span class="title">Second</span></a></div>
                    </div><a class="next" href="/list?page=2">next</a></body></html>"""
                path.startsWith("/series/1") -> """
                    <html><body><h1>First Series</h1><p class="desc">A story.</p><span class="author">Someone</span>
                    <ul class="genres"><li>Action</li><li>Drama</li></ul><span class="status">Ongoing</span>
                    <ul class="chapters"><li><a href="/chapter/2">Chapter 2</a><time>March 3, 2024</time></li>
                    <li><a href="/chapter/1">Chapter 1</a><time>2 days ago</time></li></ul></body></html>"""
                path.startsWith("/chapter/") -> """<html><body><div id="pages"><img src="https://cdn.example/1.webp"><img src="https://cdn.example/2.webp"></div></body></html>"""
                path.startsWith("/cf") -> {
                    exchange.responseHeaders.add("cf-mitigated", "challenge")
                    exchange.responseHeaders.add("Server", "cloudflare")
                    val page = "<html><head><title>Just a moment...</title></head></html>".toByteArray()
                    exchange.sendResponseHeaders(403, page.size.toLong())
                    exchange.responseBody.use { it.write(page) }
                    return@createContext
                }
                path.startsWith("/api") -> "{\"method\":\"${exchange.requestMethod}\",\"body\":${quote(exchange.requestBody.readBytes().decodeToString())},\"type\":${quote(exchange.requestHeaders.getFirst("Content-Type").orEmpty())}}"
                else -> "not here"
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (body == "not here") 404 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val base = "http://127.0.0.1:${site.address.port}"
    private val opened = ArrayList<LoadedSource>()

    @AfterTest
    fun tearDown() {
        opened.forEach { it.close() }
        site.stop(0)
    }

    private fun quote(text: String) = JsonPrimitive(text).toString()

    private fun source(code: String): LoadedSource =
        LoadedSource(code, JsonObject(mapOf("name" to JsonPrimitive("Stand-in"), "id" to JsonPrimitive(42), "lang" to JsonPrimitive("pt-br"), "baseUrl" to JsonPrimitive(base)))).also { opened += it }

    private val code = """
        const mangayomiSources = [{ "name": "Stand-in", "lang": "pt-br" }];
        class DefaultExtension extends MProvider {
          getHeaders(url) { return { Referer: this.source.baseUrl + "/" }; }
          async getPopular(page) {
            const res = await new Client().get(this.source.baseUrl + "/list?page=" + page, this.getHeaders());
            const doc = new Document(res.body);
            const list = doc.select("div.item").map((item) => {
              const a = item.selectFirst("a");
              const img = item.selectFirst("img");
              return { name: item.selectFirst("span.title").text.trim(), link: a.getHref, imageUrl: img.attr("data-src") || img.getSrc };
            });
            return { list, hasNextPage: doc.selectFirst("a.next").attr("href") !== "" && res.statusCode === 200 };
          }
          async getDetail(url) {
            const doc = new Document((await new Client().get(this.source.baseUrl + url)).body);
            const chapters = doc.select("ul.chapters li").map((li) => ({
              name: li.selectFirst("a").text, url: li.selectFirst("a").getHref,
              dateUpload: parseDates([li.selectFirst("time").text], "MMMM d, yyyy", "en")[0],
            }));
            const lang = new SharedPreferences().get("title_lang");
            return {
              name: doc.selectFirst("h1").text + " [" + lang + "]", description: doc.selectFirst("p.desc").text,
              author: doc.selectFirst("span.author").text, genre: doc.select("ul.genres li").map((li) => li.text),
              status: doc.selectFirst("span.status").text === "Ongoing" ? 0 : 1, chapters,
            };
          }
          async getPageList(url) {
            const doc = new Document((await new Client().get(this.source.baseUrl + url)).body);
            return doc.select("#pages img").map((img, i) => i === 0 ? img.getSrc : { url: img.getSrc, headers: { Referer: "x" } });
          }
          async search(query, page, filters) {
            const sort = filters[0].values[filters[0].state].value;
            const checked = filters[1].state.filter((f) => f.state).map((f) => f.value).join(",");
            const res = await new Client().post(this.source.baseUrl + "/api", { "Content-Type": "application/json" }, { query, sort, checked });
            const echo = JSON.parse(res.body);
            return { list: [{ name: echo.body, link: "/x", imageUrl: "" }], hasNextPage: false };
          }
          getFilterList() {
            return [
              { type_name: "SelectFilter", name: "Sort", state: 0, values: [ { type_name: "SelectOption", name: "Popular", value: "pop" }, { type_name: "SelectOption", name: "New", value: "new" } ] },
              { type_name: "GroupFilter", name: "Genres", state: [ { type_name: "CheckBox", name: "Action", value: "action" }, { type_name: "CheckBox", name: "Drama", value: "drama" } ] },
            ];
          }
          getSourcePreferences() {
            return [{ key: "title_lang", listPreference: { title: "Title", summary: "", valueIndex: 1, entries: ["Romaji", "English"], entryValues: ["ro", "en"] } }];
          }
          async failing() { throw new Error("The site changed its layout"); }
          async secrets() {
            const sealed = encryptAESCryptoJS("hello", "pass");
            new SharedPreferences().setString("token", "abc");
            return { opened: decryptAESCryptoJS(sealed, "pass"), kept: new SharedPreferences().getString("token", ""), relative: "x".substringAfter("y") };
          }
        }
    """.trimIndent()

    @Test
    fun aSourceListsOpensAndPagesThroughTheBridge() {
        val src = source(code)
        val popular = src.call("getPopular(1)").jsonObject
        val list = popular["list"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("First  Series", "Second"), list.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("/series/1", "/series/2"), list.map { it["link"]!!.jsonPrimitive.content })
        assertEquals(listOf("/covers/1.jpg", "/covers/2.jpg"), list.map { it["imageUrl"]!!.jsonPrimitive.content })
        // getSrc finds the first src="…" in the element's HTML, a data-src too, as Mangayomi's does.
        assertEquals("/covers/2.jpg", src.call("getPopular(1)").jsonObject["list"]!!.jsonArray[1].jsonObject["imageUrl"]!!.jsonPrimitive.content)
        assertTrue(popular["hasNextPage"]!!.jsonPrimitive.boolean)
        assertTrue(requests.first().endsWith("referer=$base/"), "the source's own headers were sent: ${requests.first()}")

        // The setting's default is the second value until the person chose another.
        val detail = src.call("getDetail(\"/series/1\")").jsonObject
        assertEquals("First Series [en]", detail["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("Action", "Drama"), detail["genre"]!!.jsonArray.map { it.jsonPrimitive.content })
        val chapters = detail["chapters"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("/chapter/2", "/chapter/1"), chapters.map { it["url"]!!.jsonPrimitive.content })
        val march = java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.ENGLISH).parse("March 3, 2024").time
        assertEquals(march, chapters[0]["dateUpload"]!!.jsonPrimitive.content.toLong())
        val twoDaysAgo = chapters[1]["dateUpload"]!!.jsonPrimitive.content.toLong()
        assertTrue(System.currentTimeMillis() - twoDaysAgo in (2 * 86_400_000L - 60_000)..(2 * 86_400_000L + 60_000))
        src.preferences.edit().putString("title_lang", "ro").apply()
        assertEquals("First Series [ro]", src.call("getDetail(\"/series/1\")").jsonObject["name"]!!.jsonPrimitive.content)

        val pages = src.call("getPageList(\"/chapter/1\")").jsonArray
        assertEquals("https://cdn.example/1.webp", pages[0].jsonPrimitive.content)
        assertEquals("x", pages[1].jsonObject["headers"]!!.jsonObject["Referer"]!!.jsonPrimitive.content)
    }

    @Test
    fun filtersGoBackToTheSourceWithTheChosenStates() {
        val src = source(code)
        val filters = FilterMapping.fromJson(src.call("getFilterList()").jsonArray)
        assertEquals(listOf("Sort", "Genres"), filters.map { it.name })
        (filters[0] as FilterMapping.Select).state = 1
        @Suppress("UNCHECKED_CAST")
        ((filters[1] as FilterMapping.Group).state[1] as FilterMapping.CheckBox).state = true
        val result = src.call("search(\"duck\", 1, ${FilterMapping.toJson(filters)})").jsonObject
        val echo = kotlinx.serialization.json.Json.parseToJsonElement(result["list"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content).jsonObject
        assertEquals("duck", echo["query"]!!.jsonPrimitive.content)
        assertEquals("new", echo["sort"]!!.jsonPrimitive.content)
        assertEquals("drama", echo["checked"]!!.jsonPrimitive.content)
        assertTrue(requests.any { it.startsWith("POST /api") })
    }

    @Test
    fun failuresKeepTheirNature() {
        val src = source(code)
        val own = assertFailsWith<MangayomiSourceException> { src.call("failing()") }
        assertEquals("The site changed its layout", own.message)

        // A site that is not there: the network failure itself reaches the server, not a script error.
        val closed = ServerSocket(0).use { it.localPort }
        val offline = LoadedSource(code, JsonObject(mapOf("name" to JsonPrimitive("Gone"), "id" to JsonPrimitive(1), "lang" to JsonPrimitive("en"), "baseUrl" to JsonPrimitive("http://127.0.0.1:$closed")))).also { opened += it }
        val network = assertFailsWith<IOException> { offline.call("getPopular(1)") }
        assertTrue(network is java.net.ConnectException, "was ${network.javaClass}: ${network.message}")

        // A source that does not define something optional is not a failure.
        assertEquals(null, src.runtime.callOptional("supportsLatest"))
    }

    @Test
    fun selectorsAreReadTheWayMangayomiReadsThem() {
        val html = org.jsoup.Jsoup.parse(
            "<div class=\"grid justify-end gap-3 grid-cols-2 md:grid-cols-3 lg:grid-cols-5\"><div>one</div><div>two</div></div>" +
                "<p class=\"note\">1.5 apples</p><a href=\"x.y\" class=\"btn btn-sm\">go</a>",
        )
        fun texts(selector: String) = html.select(HostCalls.Selectors.likeMangayomi(selector)).map { it.text() }
        // `.lg` is a word inside "lg:grid-cols-5": Mangayomi's selector library matches it, CSS would not.
        assertEquals(listOf("one", "two"), texts("div.grid.gap-3.lg > div"))
        assertEquals(emptyList(), html.select("div.grid.gap-3.lg > div").map { it.text() }, "plain CSS finds nothing there")
        assertEquals(listOf("1.5 apples"), texts("p:contains(1.5)"))
        assertEquals(listOf("go"), texts("a[href=x.y].btn-sm"))
        assertEquals(listOf("one", "two"), texts("div:has(> div).grid > div"))
    }

    @Test
    fun aBrowserCheckIsReportedRatherThanAnEmptyList() {
        val src = source(code.replace("\"/list?page=\" + page", "\"/cf\""))
        val failure = assertFailsWith<IOException> { src.call("getPopular(1)") }
        assertTrue(failure.message!!.startsWith("HTTP error 403"), failure.message)
        assertTrue(failure.message!!.contains("browser check"))
    }

    @Test
    fun helpersAndKeptStrings() {
        val result = source(code).call("secrets()").jsonObject
        assertEquals("hello", result["opened"]!!.jsonPrimitive.content)
        assertEquals("abc", result["kept"]!!.jsonPrimitive.content)
        assertEquals("x", result["relative"]!!.jsonPrimitive.content)
        assertEquals("pt-BR", SourceMeta.normalizeLanguage("pt-br"))
        assertEquals("all", SourceMeta.normalizeLanguage(""))
        assertEquals(
            "var a=1;",
            Unpacker.unpack("eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}('0 1=2;',3,3,'var|a|1'.split('|'),0,{}))"),
        )
        val hourAgo = DateParsing.parse("an hour ago", "", "")
        assertTrue(System.currentTimeMillis() - hourAgo in 3_500_000L..3_700_000L, "an hour, not a year: $hourAgo")
    }
}
