package com.debridone.manga.sources

import com.debridone.manga.net.NetworkDiagnosis
import com.debridone.manga.net.NetworkProbe
import com.debridone.manga.net.ProbeAddress
import com.debridone.manga.net.ProbeConnect
import com.debridone.manga.net.ProbeHttp
import com.debridone.manga.net.ProbeReport
import com.debridone.manga.suwayomi.SuwayomiException
import com.debridone.manga.suwayomi.SuwayomiTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Failures are told apart by what actually happened, not by their wording. The reports here are the
 * real Suwayomi-Server's, captured from a server whose source pointed at addresses that fail in
 * each way (see `fetch-errors.json`).
 */
class SourceFailureTest {
    private val reports = Json.parseToJsonElement(javaClass.getResource("/suwayomi/fetch-errors.json")!!.readText()).jsonObject
        .filterKeys { !it.startsWith("_") }.mapValues { it.value.jsonPrimitive.content }

    /** The exception the transport raises for such a report: a short message, and the report in full. */
    private fun reported(name: String): SuwayomiException {
        val raw = reports.getValue(name)
        return SuwayomiException(SuwayomiException.Kind.SERVER, SuwayomiTransport.cleanMessage(raw), detail = raw)
    }

    private fun kind(name: String) = SourceFailure.of("MangaDot", reported(name), "https://mangadot.example").kind

    @Test
    fun eachRealFailureIsItsOwnKind() {
        assertEquals(FailureKind.DNS, kind("dns"))
        assertEquals(FailureKind.UNAVAILABLE, kind("refused"))
        assertEquals(FailureKind.TIMEOUT, kind("timeout"))
        assertEquals(FailureKind.TLS, kind("tls"))
        assertEquals(FailureKind.UNAVAILABLE, kind("http503"))
        assertEquals(FailureKind.HTTP, kind("http404"))
        assertEquals(FailureKind.HTTP, kind("http403"))
        assertEquals(FailureKind.HTTP, kind("http429"))
        assertEquals(FailureKind.BROWSER_CHECK, kind("cloudflare"))
    }

    @Test
    fun noRouteToHostIsNotAnHttpErrorNorAnExtensionError() {
        // Not captured: this test machine intercepts every outgoing connection and cannot produce
        // EHOSTUNREACH. The report is built on the captured ones, with the exception Java raises for
        // "No route to host" (java.net.NoRouteToHostException), as the person's Mac reported it.
        val failure = SourceFailure.of("MangaDot", reported("noroute_constructed"), "https://mangadot.example")
        assertEquals(FailureKind.NO_ROUTE, failure.kind)
        assertEquals("java.net.NoRouteToHostException", failure.exception)
        assertEquals("mangadot.example", failure.host)
        assertTrue(failure.explanation.contains("no network route", ignoreCase = true))
        assertFalse(failure.temporary, "waiting does not give a route")
    }

    @Test
    fun theStatusAndTheHostAreKeptAndOnlyPassingFailuresAreRetried() {
        val notFound = SourceFailure.of("MangaDot", reported("http404"), "https://mangadot.example")
        assertEquals(404, notFound.httpStatus)
        assertEquals("HTTP error 404", notFound.title)
        assertFalse(notFound.temporary)
        assertTrue(SourceFailure.of("MangaDot", reported("http503")).temporary)
        assertTrue(SourceFailure.of("MangaDot", reported("http429")).temporary)
        assertTrue(SourceFailure.of("MangaDot", reported("timeout")).temporary)
        assertFalse(SourceFailure.of("MangaDot", reported("dns")).temporary)
        assertEquals("does-not-exist.invalid", SourceFailure.of("Komga", reported("dns")).host)
        // A second lookup of the same name is answered from Java's cache, with the name alone (as seen in the app).
        val cached = SuwayomiException(
            SuwayomiException.Kind.SERVER, "does-not-exist.invalid",
            detail = "Exception while fetching data (/fetchSourceManga) : does-not-exist.invalid\r\n\r\njava.io.IOException: does-not-exist.invalid\nCaused by: java.net.UnknownHostException: does-not-exist.invalid",
        )
        assertEquals(FailureKind.DNS, SourceFailure.of("Komga", cached).kind)
        assertEquals("does-not-exist.invalid", SourceFailure.of("Komga", cached).host)

        // A failure between DebridOne and its own server is not the source's.
        val server = SourceFailure.of("MangaDot", SuwayomiException(SuwayomiException.Kind.UNREACHABLE, "The manga server is not reachable."))
        assertEquals(FailureKind.SERVER, server.kind)

        // Anything else the extension's code throws is the extension's.
        val broken = SourceFailure.of("MangaDot", SuwayomiException(SuwayomiException.Kind.SERVER, "Index 0 out of bounds for length 0", detail = "java.lang.IndexOutOfBoundsException: Index 0 out of bounds for length 0"))
        assertEquals(FailureKind.EXTENSION, broken.kind)
    }

    @Test
    fun technicalDetailsLeaveOutWhatCouldBeSecret() {
        val text = SourceFailure.sanitize(
            "GET https://user:pa55@site.example/api/chapter?token=abc123&page=2 failed\nAuthorization: Bearer xyz\n\tat java.base/Some.call(Some.java:1)\nCookie=session123",
        )
        assertFalse(text.contains("pa55"))
        assertFalse(text.contains("abc123"))
        assertFalse(text.contains("xyz"))
        assertFalse(text.contains("session123"))
        assertTrue(text.contains("https://site.example/api/chapter?…"))
        assertFalse(text.contains("at java.base"))
    }

    // ---- the diagnosis ----------------------------------------------------------------------------

    private fun address(text: String) = NetworkProbe.describe(InetAddress.getByName(text))

    private fun report(
        runtime: String = "DebridOne",
        system: List<ProbeAddress> = listOf(address("93.184.215.14")),
        public: List<ProbeAddress>? = listOf(address("93.184.215.14")),
        connects: List<ProbeConnect> = listOf(ProbeConnect("93.184.215.14", true, 30)),
        http: ProbeHttp? = ProbeHttp(true, 80, 200),
        dnsError: String? = null,
    ) = ProbeReport(runtime, "21", "https://site.example", "site.example", 443, system, 5, dnsError, null, public, null, connects, null, http)

    @Test
    fun theDiagnosisNamesTheLayerThatFails() {
        // This Mac's DNS sends the site to 0.0.0.0 while a public DNS knows it.
        val blocked = NetworkDiagnosis.conclude(report(system = listOf(address("0.0.0.0")), connects = listOf(ProbeConnect("0.0.0.0", false, 1, "No route to host", "java.net.NoRouteToHostException")), http = null), null)
        assertEquals(FailureKind.DNS, blocked.kind)
        assertTrue(blocked.dnsDiffers)
        assertTrue(blocked.explanation.contains("No route to host"))

        // The name is not known here, but it is publicly.
        val unknown = NetworkDiagnosis.conclude(report(system = emptyList(), dnsError = "nodename nor servname provided", connects = emptyList(), http = null), null)
        assertEquals(FailureKind.DNS, unknown.kind)
        assertTrue(unknown.dnsDiffers)

        // Without a public answer to compare with, nothing is claimed about the site.
        val uncompared = NetworkDiagnosis.conclude(report(system = emptyList(), public = null, dnsError = "nodename nor servname provided", connects = emptyList(), http = null), null)
        assertEquals(FailureKind.DNS, uncompared.kind)
        assertFalse(uncompared.headline.contains("does not exist"))

        // DebridOne connects; the manga server's Java does not.
        val program = NetworkDiagnosis.conclude(
            report(),
            report(runtime = "Manga server", public = null, connects = listOf(ProbeConnect("93.184.215.14", false, 2, "No route to host", "java.net.NoRouteToHostException")), http = null),
        )
        assertEquals(FailureKind.NO_ROUTE, program.kind)
        assertTrue(program.headline.contains("manga server's program"))

        // Nobody on this Mac gets a route.
        val route = NetworkDiagnosis.conclude(report(connects = listOf(ProbeConnect("93.184.215.14", false, 2, "No route to host", "java.net.NoRouteToHostException")), http = null), null)
        assertEquals(FailureKind.NO_ROUTE, route.kind)

        // Reachable, but behind a browser check.
        val check = NetworkDiagnosis.conclude(report(http = ProbeHttp(false, 50, 403, "cloudflare", "text/html", browserCheck = true)), null)
        assertEquals(FailureKind.BROWSER_CHECK, check.kind)

        // Reachable and answering: what is left is the extension.
        val fine = NetworkDiagnosis.conclude(report(), report(runtime = "Manga server", public = null))
        assertEquals(null, fine.kind)
        assertTrue(fine.explanation.contains("extension"))
        assertTrue(fine.steps.any { it.name.contains("manga server's Java") && it.ok == true })
    }

    @Test
    fun theProbeReallyConnectsAndReportsEachStep() {
        // A web server on this computer: the probe resolves, connects and makes one request.
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            // The probe connects once to test the connection and once more for its request.
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use {
                        it.soTimeout = 3000
                        val read = runCatching { it.getInputStream().read(ByteArray(4096)) }.getOrDefault(-1)
                        if (read > 0) it.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nServer: test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
            }
        }
        val report = NetworkProbe.run("http://localhost:${server.localPort}/", runtime = "DebridOne", comparePublicDns = false)
        server.close()
        assertTrue(report.systemDns.isNotEmpty())
        assertTrue(report.connects.any { it.ok }, "connected: ${report.connects}")
        val http = assertNotNull(report.http)
        assertEquals(503, http.status)
        assertEquals(FailureKind.UNAVAILABLE, NetworkDiagnosis.conclude(report, null).kind)

        // A port nobody listens on is refused, and the diagnosis says so.
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val refused = NetworkProbe.run("http://127.0.0.1:$closed/", runtime = "DebridOne", comparePublicDns = false)
        assertTrue(refused.connects.isNotEmpty() && refused.connects.none { it.ok })
        assertEquals(FailureKind.UNAVAILABLE, NetworkDiagnosis.conclude(refused, null).kind)
    }
}
