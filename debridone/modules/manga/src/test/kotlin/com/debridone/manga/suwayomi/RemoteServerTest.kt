package com.debridone.manga.suwayomi

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A server the person runs themselves, as [MangaServer] treats it: which addresses are accepted,
 * and what happens when the server goes away and comes back. A stand-in answers as Suwayomi does
 * to "who are you"; no Suwayomi is started here.
 */
class RemoteServerTest {
    private fun standIn(port: Int = 0): HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0).apply {
        createContext("/api/graphql") { exchange ->
            exchange.requestBody.readBytes()
            val body = """{"data":{"aboutServer":{"name":"Suwayomi-Server","version":"v2.4.2366","buildType":"Stable","buildTime":"1760000000","github":"x"}}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    private fun await(server: MangaServer, what: String, timeoutMs: Long = 20_000, condition: (MangaConnection) -> Boolean): MangaConnection {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = server.state.value
            if (condition(state)) return state
            Thread.sleep(25)
        }
        throw AssertionError("Not $what in time: ${server.state.value}")
    }

    @Test
    fun onlyAddressesThatCanBeTrustedWithASignInAreAccepted() {
        fun endpoint(url: String, https: Boolean = true) = MangaServer.endpoint(RemoteServer(url, "me", "pw", requireHttps = https))
        assertEquals("https://manga.example.org", endpoint("https://manga.example.org/").baseUrl)
        // Plain http is refused unless the person says the server is on their own network…
        assertTrue(assertFailsWith<SuwayomiUnavailable> { endpoint("http://192.168.1.20:4567") }.message!!.contains("not encrypted"))
        assertEquals("http://192.168.1.20:4567", endpoint("http://192.168.1.20:4567", https = false).baseUrl)
        // …except to this computer itself, where nothing travels.
        assertEquals("http://127.0.0.1:4567", endpoint("http://127.0.0.1:4567").baseUrl)
        assertEquals("http://localhost:4567", endpoint("http://localhost:4567").baseUrl)
        // A password does not belong in an address, and other things are not addresses.
        assertTrue(assertFailsWith<SuwayomiUnavailable> { endpoint("https://me:pw@manga.example.org") }.message!!.contains("own fields"))
        assertFailsWith<SuwayomiUnavailable> { endpoint("manga.example.org") }
        assertFailsWith<SuwayomiUnavailable> { endpoint("ftp://manga.example.org") }
        assertFailsWith<SuwayomiUnavailable> { endpoint("") }
        // The waiting time is kept within reason.
        assertEquals(5, MangaServer.endpoint(RemoteServer("https://x.example", null, null, timeoutSeconds = 1)).timeoutSeconds)
        assertEquals(300, MangaServer.endpoint(RemoteServer("https://x.example", null, null, timeoutSeconds = 9999)).timeoutSeconds)
    }

    @Test
    fun aServerThatGoesAwayIsReportedAndIsUsedAgainWhenItIsBack() {
        var their = standIn()
        val port = their.address.port
        val root = Files.createTempDirectory("debridone-remote")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = MangaServer(SuwayomiServerManager(root, { throw SuwayomiUnavailable("no server of DebridOne's is needed for a remote one") }), scope) {
            RemoteServer("http://127.0.0.1:$port", null, null, timeoutSeconds = 5)
        }
        server.healthIntervalMs = 150
        server.retryIntervalMs = 150
        try {
            assertIs<MangaConnection.Idle>(server.state.value)
            server.activate(5)
            assertIs<MangaConnection.Idle>(server.state.value, "nothing is contacted before Manga is opened")
            server.ensureStarted()
            val ready = assertIs<MangaConnection.Ready>(await(server, "connected") { it is MangaConnection.Ready })
            assertTrue(ready.remote)
            assertEquals("v2.4.2366", ready.version)
            assertTrue(server.isRemote && server.isReady)
            val first = server.generation.value

            // Their server stops: it is noticed, said, and tried again by itself.
            their.stop(0)
            val lost = assertIs<MangaConnection.Failed>(await(server, "noticed as gone") { it is MangaConnection.Failed })
            assertTrue(lost.remote && lost.retrying)
            assertTrue(lost.message.contains("cannot be reached"), lost.message)
            assertEquals(SuwayomiException.Kind.UNREACHABLE, assertFailsWith<SuwayomiException> { server.api() }.kind)

            // It is back at the same address: used again without anybody asking.
            their = standIn(port)
            assertIs<MangaConnection.Ready>(await(server, "connected again") { it is MangaConnection.Ready })
            assertTrue(server.generation.value > first, "what was remembered from before is known to be stale")
            server.api().about()
        } finally {
            server.close()
            scope.cancel()
            their.stop(0)
            root.toFile().deleteRecursively()
        }
    }
}
