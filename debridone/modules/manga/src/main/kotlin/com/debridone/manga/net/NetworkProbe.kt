package com.debridone.manga.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** One address a name resolved to, and what kind of address it is. */
@Serializable
data class ProbeAddress(val address: String, val family: String, val kind: String) {
    /** An address on the public internet (not this computer, not a home network, not "nowhere"). */
    val public: Boolean get() = kind == "public"
}

@Serializable
data class ProbeConnect(val address: String, val ok: Boolean, val millis: Long, val error: String? = null, val errorClass: String? = null)

@Serializable
data class ProbeTls(val ok: Boolean, val millis: Long, val protocol: String? = null, val subject: String? = null, val issuer: String? = null, val error: String? = null, val errorClass: String? = null)

@Serializable
data class ProbeHttp(
    val ok: Boolean,
    val millis: Long,
    val status: Int? = null,
    val server: String? = null,
    val contentType: String? = null,
    val browserCheck: Boolean = false,
    val location: String? = null,
    val error: String? = null,
    val errorClass: String? = null,
)

/**
 * What one Java program on this computer saw when it tried to reach a site, step by step: the
 * name through this computer's DNS (and, for comparison, a public DNS), a TCP connection to each
 * address, the secure handshake, and one HTTP request. Nothing is sent but the requests a browser
 * makes when it opens the site's front page.
 */
@Serializable
data class ProbeReport(
    /** Which program ran the probe: "DebridOne" or "Manga server". */
    val runtime: String,
    val javaVersion: String,
    val url: String,
    val host: String,
    val port: Int,
    val systemDns: List<ProbeAddress> = emptyList(),
    val systemDnsMillis: Long = 0,
    val systemDnsError: String? = null,
    val systemDnsErrorClass: String? = null,
    /** What a public DNS (Cloudflare, else Google) answers for the name; null when it was not asked. */
    val publicDns: List<ProbeAddress>? = null,
    val publicDnsError: String? = null,
    val connects: List<ProbeConnect> = emptyList(),
    val tls: ProbeTls? = null,
    val http: ProbeHttp? = null,
)

object NetworkProbe {
    val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    /**
     * Probes [url]. [comparePublicDns] also asks a public DNS over HTTPS for the name, so a DNS that
     * leaves the name out or answers a local address can be told apart from a site that is down.
     */
    fun run(url: String, runtime: String, comparePublicDns: Boolean, timeoutMs: Int = 6000): ProbeReport {
        val uri = URI(url.trim())
        val host = uri.host ?: throw IllegalArgumentException("Not a web address: $url")
        val https = uri.scheme.equals("https", ignoreCase = true)
        val port = if (uri.port > 0) uri.port else if (https) 443 else 80
        var report = ProbeReport(runtime, System.getProperty("java.version").orEmpty(), url, host, port)

        // 1. This computer's DNS, as every program here uses it.
        val dnsStart = System.nanoTime()
        val addresses = try {
            InetAddress.getAllByName(host).toList()
        } catch (failure: Exception) {
            report = report.copy(systemDnsMillis = elapsed(dnsStart), systemDnsError = failure.message ?: failure.javaClass.simpleName, systemDnsErrorClass = failure.javaClass.name)
            emptyList()
        }
        if (addresses.isNotEmpty()) report = report.copy(systemDns = addresses.map(::describe), systemDnsMillis = elapsed(dnsStart))

        // 2. A public DNS, for comparison.
        if (comparePublicDns) {
            report = try {
                report.copy(publicDns = publicDns(host, timeoutMs))
            } catch (failure: Exception) {
                report.copy(publicDnsError = failure.message ?: failure.javaClass.simpleName)
            }
        }

        // 3. A TCP connection to each address (a few of each family).
        val tried = addresses.filterIsInstance<Inet4Address>().take(3) + addresses.filterIsInstance<Inet6Address>().take(2)
        val connects = tried.map { address ->
            val start = System.nanoTime()
            try {
                Socket(Proxy.NO_PROXY).use { it.connect(InetSocketAddress(address, port), timeoutMs) }
                ProbeConnect(address.hostAddress, true, elapsed(start))
            } catch (failure: Exception) {
                ProbeConnect(address.hostAddress, false, elapsed(start), failure.message ?: failure.javaClass.simpleName, failure.javaClass.name)
            }
        }
        report = report.copy(connects = connects)

        // 4. The secure handshake, over the first address that connected.
        val reachable = connects.firstOrNull { it.ok }?.let { ok -> tried.first { it.hostAddress == ok.address } }
        if (https && reachable != null) report = report.copy(tls = handshake(reachable, host, port, timeoutMs))

        // 5. One request for the front page.
        if (reachable != null) report = report.copy(http = request(url, timeoutMs))
        return report
    }

    fun describe(address: InetAddress): ProbeAddress {
        val family = if (address is Inet6Address) "IPv6" else "IPv4"
        val bytes = address.address
        fun u(i: Int) = bytes[i].toInt() and 0xff
        val kind = when {
            address.isAnyLocalAddress -> "unspecified"
            address.isLoopbackAddress -> "loopback"
            address.isLinkLocalAddress -> "link-local"
            address.isSiteLocalAddress -> "private"
            address is Inet4Address && u(0) == 100 && u(1) in 64..127 -> "shared"
            address is Inet4Address && (u(0) == 0 || u(0) >= 224) -> "unroutable"
            address is Inet6Address && (u(0) and 0xfe) == 0xfc -> "private"
            else -> "public"
        }
        return ProbeAddress(address.hostAddress.substringBefore('%'), family, kind)
    }

    private fun handshake(address: InetAddress, host: String, port: Int, timeoutMs: Int): ProbeTls {
        val start = System.nanoTime()
        return try {
            Socket(Proxy.NO_PROXY).use { plain ->
                plain.connect(InetSocketAddress(address, port), timeoutMs)
                plain.soTimeout = timeoutMs
                ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, false) as SSLSocket).use { secure ->
                    secure.startHandshake()
                    val certificate = secure.session.peerCertificates.firstOrNull() as? X509Certificate
                    ProbeTls(true, elapsed(start), secure.session.protocol, certificate?.subjectX500Principal?.name?.take(200), certificate?.issuerX500Principal?.name?.take(200))
                }
            }
        } catch (failure: Exception) {
            ProbeTls(false, elapsed(start), error = failure.message ?: failure.javaClass.simpleName, errorClass = failure.javaClass.name)
        }
    }

    private fun request(url: String, timeoutMs: Int): ProbeHttp {
        val start = System.nanoTime()
        return try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            try {
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val head = stream?.use { input -> String(input.readNBytes(16_384), Charsets.UTF_8).lowercase() }.orEmpty()
                val server = connection.getHeaderField("Server")
                val check = connection.getHeaderField("cf-mitigated") != null ||
                    listOf("just a moment", "cf-browser-verification", "challenge-platform", "ddos-guard", "checking your browser").any { it in head }
                ProbeHttp(status in 200..399, elapsed(start), status, server?.take(80), connection.contentType?.take(80), check, connection.getHeaderField("Location")?.take(300))
            } finally {
                connection.disconnect()
            }
        } catch (failure: Exception) {
            ProbeHttp(false, elapsed(start), error = failure.message ?: failure.javaClass.simpleName, errorClass = failure.javaClass.name)
        }
    }

    /** The name's addresses according to a public DNS, asked over HTTPS at its own address (no name lookup needed). */
    private fun publicDns(host: String, timeoutMs: Int): List<ProbeAddress> {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs.toLong())).build()
        // At their own addresses first (no name lookup needed); by name when those cannot be reached.
        val resolvers = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve", "https://cloudflare-dns.com/dns-query", "https://dns.google/resolve")
        var last: Exception? = null
        for (resolver in resolvers) {
            try {
                val found = LinkedHashSet<ProbeAddress>()
                for (type in listOf("A", "AAAA")) {
                    val request = HttpRequest.newBuilder(URI("$resolver?name=${URLEncoder.encode(host, Charsets.UTF_8)}&type=$type"))
                        .header("Accept", "application/dns-json").timeout(Duration.ofMillis(timeoutMs.toLong())).GET().build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() != 200) error("The public DNS answered ${response.statusCode()}.")
                    val answers = JSON.parseToJsonElement(response.body()).jsonObject["Answer"]?.jsonArray.orEmpty()
                    for (answer in answers) {
                        val record = answer.jsonObject
                        val recordType = record["type"]?.jsonPrimitive?.int
                        val data = record["data"]?.jsonPrimitive?.content ?: continue
                        if (recordType == 1 || recordType == 28) found += describe(InetAddress.getByName(data))
                    }
                }
                return found.toList()
            } catch (failure: Exception) {
                last = failure
            }
        }
        throw last ?: IllegalStateException("No public DNS answered.")
    }

    private fun elapsed(start: Long): Long = (System.nanoTime() - start) / 1_000_000
}

/**
 * Runs the probe in a Java program of its own and prints the report as JSON: started with the
 * manga server's own Java, it shows what that program (the one that makes the extensions' requests)
 * can reach, which a firewall or a permission can treat differently from DebridOne itself.
 */
object NetworkProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val url = args.firstOrNull() ?: run {
            System.err.println("usage: NetworkProbeMain <url>")
            kotlin.system.exitProcess(2)
        }
        val report = try {
            NetworkProbe.run(url, runtime = args.getOrNull(1) ?: "Manga server", comparePublicDns = false)
        } catch (failure: Exception) {
            System.err.println(failure.message)
            kotlin.system.exitProcess(1)
        }
        println(NetworkProbe.JSON.encodeToString(ProbeReport.serializer(), report))
    }
}
