package com.debridone.manga.suwayomi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The transport and the answers' reading, against a stand-in that speaks just enough HTTP. */
class SuwayomiTransportTest {
    private class Seen(val method: String, val path: String, val authorization: String?, val contentType: String?, val body: ByteArray)

    private val seen = CopyOnWriteArrayList<Seen>()
    private var answer: (HttpExchange, Seen) -> Pair<Int, ByteArray> = { _, _ -> 200 to """{"data":{}}""".toByteArray() }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val request = Seen(
                exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestHeaders.getFirst("Content-Type"), exchange.requestBody.readBytes(),
            )
            seen += request
            val (status, body) = answer(exchange, request)
            exchange.sendResponseHeaders(status, body.size.toLong().takeIf { it > 0 } ?: -1)
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) } else exchange.close()
        }
        start()
    }

    private fun transport(user: String? = "debridone", password: String? = "s3cret/+pass") =
        SuwayomiTransport(ServerEndpoint("http://127.0.0.1:${server.address.port}", user, password, managed = true, timeoutSeconds = 5))

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun aRequestCarriesTheSignInAndReturnsTheData() {
        answer = { _, _ -> 200 to """{"data":{"aboutServer":{"name":"Suwayomi-Server","version":"v2.4.2366","buildType":"Stable","buildTime":"1760000000","github":"x"}}}""".toByteArray() }
        val about = SuwayomiApi(transport()).about()
        assertEquals("v2.4.2366", about.version)
        assertEquals(1760000000L, about.buildTime)
        val request = seen.single()
        assertEquals("POST", request.method)
        assertEquals("/api/graphql", request.path)
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("debridone:s3cret/+pass".toByteArray()), request.authorization)
        assertTrue(String(request.body).contains("aboutServer"))
    }

    @Test
    fun aRefusedSignInIsToldApartFromAServerThatIsNotThere() {
        answer = { _, _ -> 401 to ByteArray(0) }
        assertEquals(SuwayomiException.Kind.UNAUTHORIZED, assertFailsWith<SuwayomiException> { transport().execute("query { x }") }.kind)
        server.stop(0)
        assertEquals(SuwayomiException.Kind.UNREACHABLE, assertFailsWith<SuwayomiException> { transport().execute("query { x }") }.kind)
    }

    @Test
    fun aServerErrorIsOneSentenceWithoutTheStackTrace() {
        val raw = "Exception while fetching data (/addExtensionStore) : HTTP error 403\\r\\n\\r\\njava.lang.Exception: HTTP error 403\\n\\tat suwayomi.tachidesk.Foo.bar(Foo.kt:12)"
        answer = { _, _ -> 200 to """{"errors":[{"message":"$raw","path":["addExtensionStore"]}],"data":null}""".toByteArray() }
        val failure = assertFailsWith<SuwayomiException> { transport().execute("mutation { x }") }
        assertEquals(SuwayomiException.Kind.SERVER, failure.kind)
        assertEquals("HTTP error 403", failure.message)
    }

    @Test
    fun messagesLoseTheirJavaClassNamesAndNeverCarryATrace() {
        assertEquals("Source not found", SuwayomiTransport.cleanMessage("Exception while fetching data (/source) : java.lang.NullPointerException: Source not found\r\n\tat a.b.C.d(C.kt:1)"))
        assertEquals("Validation error (FieldUndefined@[manga/nope]) : Field 'nope' in type 'MangaType' is undefined", SuwayomiTransport.cleanMessage("Validation error (FieldUndefined@[manga/nope]) : Field 'nope' in type 'MangaType' is undefined"))
        assertEquals("The manga server reported an error.", SuwayomiTransport.cleanMessage(""))
        assertFalse(SuwayomiTransport.cleanMessage("Exception while fetching data (/x) : boom\n\tat x.Y.z(Y.kt:3)").contains("at x.Y"))
    }

    @Test
    fun somethingThatIsNotTheApiIsReportedAsSuch() {
        answer = { _, _ -> 200 to "<html>hello</html>".toByteArray() }
        assertEquals(SuwayomiException.Kind.PROTOCOL, assertFailsWith<SuwayomiException> { transport().execute("query { x }") }.kind)
        answer = { _, _ -> 502 to "Bad gateway".toByteArray() }
        assertEquals(SuwayomiException.Kind.SERVER, assertFailsWith<SuwayomiException> { transport().execute("query { x }") }.kind)
    }

    @Test
    fun aFileIsUploadedInTheGraphQlMultipartForm() {
        answer = { _, _ -> 200 to """{"data":{"installExternalExtension":{"extension":{"pkgName":"a.b","name":"A","lang":"en","versionName":"1","versionCodeLong":"1","isInstalled":true,"hasUpdate":false,"isObsolete":false,"iconUrl":"/i","contentWarning":"SAFE"}}}}""".toByteArray() }
        val bytes = ByteArray(300) { it.toByte() }
        val extension = SuwayomiApi(transport()).installExtensionFile("my \"ext\".apk", bytes)
        assertEquals("a.b", extension.pkgName)
        val request = seen.single()
        assertTrue(request.contentType!!.startsWith("multipart/form-data; boundary="))
        val body = String(request.body, Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"operations\""))
        assertTrue(body.contains("\"extensionFile\":null"))
        assertTrue(body.contains("""{"0":["variables.extensionFile"]}"""))
        assertTrue(body.contains("(\$extensionFile: Upload!)"), "the file is a variable of its own, which is where Suwayomi puts an upload")
        assertTrue(body.contains("filename=\"my _ext_.apk\""))
        assertTrue(body.contains(String(bytes, Charsets.ISO_8859_1)))
    }

    @Test
    fun imagesAreFetchedWithTheSignInAndOnlyFromThisServer() {
        val picture = SampleComics.page(1)
        answer = { _, request -> if (request.path == "/api/v1/manga/1/chapter/0/page/0") 200 to picture else 404 to ByteArray(0) }
        assertContentEquals(picture, transport().bytes("/api/v1/manga/1/chapter/0/page/0"))
        assertTrue(seen.single().authorization!!.startsWith("Basic "))
        assertFailsWith<SuwayomiException> { transport().bytes("/api/v1/manga/1/chapter/0/page/9") }
        assertFailsWith<IllegalArgumentException> { transport().bytes("https://elsewhere.example/x.png") }
    }

    @Test
    fun theSignInNeverShowsInWhatIsPrinted() {
        val endpoint = ServerEndpoint("http://127.0.0.1:1", "debridone", "top-secret", managed = true)
        assertFalse(endpoint.toString().contains("top-secret"))
        assertFalse(ServerState.Ready(1, endpoint, "v").toString().contains("top-secret"))
    }

    @Test
    fun onlyTheNamedLinesOfTheServersConfigurationAreChanged() {
        val file = Files.createTempFile("server", ".conf")
        Files.write(file, listOf("# Suwayomi's own comment", "server.ip = \"0.0.0.0\"", "server.port = 4567", "server.downloadAsCbz = true", "server.extensionStores = [", "  \"https://example.org/index\"", "]"))
        ServerConf.write(file, linkedMapOf("ip" to ServerConf.text("127.0.0.1"), "port" to "5001", "authPassword" to ServerConf.text("a\"b\\c")))
        val lines = Files.readAllLines(file)
        assertEquals("# Suwayomi's own comment", lines[0])
        assertEquals("server.ip = \"127.0.0.1\"", lines[1])
        assertEquals("server.port = 5001", lines[2])
        assertEquals("server.downloadAsCbz = true", lines[3])
        assertTrue(lines.containsAll(listOf("server.extensionStores = [", "  \"https://example.org/index\"", "]")))
        assertEquals("server.authPassword = \"a\\\"b\\\\c\"", lines.last())
        // Writing again replaces, never duplicates.
        ServerConf.write(file, linkedMapOf("authPassword" to ServerConf.text("next")))
        assertEquals(1, Files.readAllLines(file).count { it.startsWith("server.authPassword") })
    }

    @Test
    fun aPreferenceChangeNamesItsKindAndPosition() {
        answer = { _, _ -> 200 to """{"data":{"updateSourcePreference":{"preferences":[
            {"__typename":"EditTextPreference","key":"addr","title":"Address","summary":null,"enabled":true,"visible":true,"textValue":"https://demo","textDefault":"","dialogTitle":"Address","dialogMessage":null},
            {"__typename":"SwitchPreference","key":"s","title":"Switch","summary":"","enabled":true,"visible":true,"boolValue":null,"boolDefault":true},
            {"__typename":"SomethingNew","key":"n"},
            {"__typename":"MultiSelectListPreference","key":"m","title":"Libraries","summary":"","enabled":true,"visible":true,"listValue":["a"],"listDefault":[],"entries":["A","B"],"entryValues":["a","b"]}
        ]}}}""".toByteArray() }
        val result = SuwayomiApi(transport()).setSourcePreference("4508733312114627536", SwPreferenceChange(position = 1, text = "https://demo"))
        val sent = String(seen.single().body)
        assertTrue(sent.contains(""""source":"4508733312114627536""""))
        assertTrue(sent.contains(""""change":{"position":1,"editTextState":"https://demo"}"""))
        assertEquals(listOf(0, 1, 3), result.map { it.position })
        assertEquals("https://demo", (result[0] as SwPreference.Text).value)
        assertTrue((result[1] as SwPreference.Toggle).value, "an unset switch shows its default")
        assertEquals(listOf("a"), (result[2] as SwPreference.MultiChoice).values)
    }
}
