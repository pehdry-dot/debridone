package com.debridone.manga.net

import com.debridone.manga.sources.FailureKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** One line of a diagnosis as the screen shows it. [ok] null means "not tried" (an earlier step failed). */
data class DiagnosisStep(val name: String, val ok: Boolean?, val detail: String)

/**
 * Where a connection to a source's site stops, found by comparing what DebridOne and the manga
 * server's own Java program see. [kind] is null when the site is reachable and answers: then the
 * problem, if any, is in the extension.
 */
data class Diagnosis(
    val url: String,
    val kind: FailureKind?,
    val headline: String,
    val explanation: String,
    val steps: List<DiagnosisStep>,
    val reports: List<ProbeReport>,
    /** This Mac's DNS hides or diverts the site while a public DNS knows it. */
    val dnsDiffers: Boolean = false,
)

/**
 * Runs the probe from DebridOne and from the manga server's Java (when the server runs on this
 * computer), and says in plain words which layer fails.
 */
class NetworkDiagnosis(
    /** The Java program the manga server runs with, or null when the server is another computer. */
    private val serverJava: () -> Path?,
    /** The classes the probe runs from (DebridOne's own class path). */
    private val classPath: () -> String = { System.getProperty("java.class.path").orEmpty() },
    private val timeoutSeconds: Long = 60,
) {
    fun diagnose(url: String): Diagnosis {
        val ours = NetworkProbe.run(url, runtime = "DebridOne", comparePublicDns = true)
        val theirs = serverJava()?.takeIf { Files.isExecutable(it) }?.let { runWith(it, url) }
        return conclude(ours, theirs)
    }

    private fun runWith(java: Path, url: String): ProbeReport? = runCatching {
        val process = ProcessBuilder(
            java.toString(), "-Djava.awt.headless=true", "-Dapple.awt.UIElement=true", "-Xmx96m",
            "-cp", classPath(), NetworkProbeMain::class.java.name, url, "Manga server",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        NetworkProbe.JSON.decodeFromString(ProbeReport.serializer(), output.lineSequence().last { it.isNotBlank() })
    }.getOrNull()

    companion object {
        fun conclude(ours: ProbeReport, theirs: ProbeReport?): Diagnosis {
            val host = ours.host
            val steps = ArrayList<DiagnosisStep>()
            val local = ours.systemDns.isNotEmpty() && ours.systemDns.none { it.public }
            val publicAnswer = ours.publicDns.orEmpty()

            steps += DiagnosisStep(
                "Name lookup (this Mac's DNS)",
                ours.systemDnsError == null && !local,
                when {
                    ours.systemDnsError != null -> "Not found: ${ours.systemDnsError}"
                    else -> ours.systemDns.joinToString(", ") { "${it.address} (${it.family}${if (it.public) "" else ", " + it.kind})" } + " · ${ours.systemDnsMillis} ms"
                },
            )
            steps += DiagnosisStep(
                "Name lookup (public DNS, for comparison)",
                when {
                    ours.publicDns == null -> null
                    else -> publicAnswer.any { it.public }
                },
                when {
                    ours.publicDnsError != null -> "Could not ask: ${ours.publicDnsError}"
                    ours.publicDns == null -> "Not asked"
                    publicAnswer.isEmpty() -> "The name does not exist there either"
                    else -> publicAnswer.joinToString(", ") { it.address }
                },
            )
            for (report in listOfNotNull(ours, theirs)) {
                val who = if (report.runtime == "DebridOne") "DebridOne" else "the manga server's Java"
                if (report.connects.isEmpty()) {
                    steps += DiagnosisStep("Connection from $who", null, "Not tried: there was no address to connect to")
                    continue
                }
                val okOnes = report.connects.filter { it.ok }
                steps += DiagnosisStep(
                    "Connection from $who",
                    okOnes.isNotEmpty(),
                    report.connects.joinToString("; ") { if (it.ok) "${it.address}: connected in ${it.millis} ms" else "${it.address}: ${shortError(it.errorClass, it.error)} after ${it.millis} ms" },
                )
                report.tls?.let { tls ->
                    steps += DiagnosisStep("Secure connection from $who", tls.ok, if (tls.ok) "${tls.protocol} · ${commonName(tls.subject)}, issued by ${commonName(tls.issuer)}" else shortError(tls.errorClass, tls.error))
                }
                report.http?.let { http ->
                    steps += DiagnosisStep(
                        "Web request from $who",
                        http.ok && !http.browserCheck,
                        when {
                            http.status == null -> shortError(http.errorClass, http.error)
                            http.browserCheck -> "HTTP ${http.status}: a browser check (${http.server ?: "unknown server"})"
                            http.location != null -> "HTTP ${http.status}, sends on to ${http.location}"
                            else -> "HTTP ${http.status}" + (http.server?.let { " · $it" } ?: "") + " · ${http.millis} ms"
                        },
                    )
                }
            }

            val oursConnects = ours.connects.any { it.ok }
            val theirsConnects = theirs?.connects?.any { it.ok }
            val oursErrors = ours.connects.mapNotNull { it.errorClass }
            fun only(errorName: String) = oursErrors.isNotEmpty() && oursErrors.all { it.endsWith(errorName) }
            val http = ours.http
            val (kind, headline, explanation) = when {
                ours.systemDnsError != null && publicAnswer.any { it.public } -> Triple(
                    FailureKind.DNS, "This Mac's DNS does not find $host",
                    "A public DNS knows $host, but the DNS this Mac uses (from your router or provider) does not answer it: it is filtered or blocked there. A browser that uses secure DNS still reaches the site, which is why it may work there. The manga server uses this Mac's DNS; which DNS this Mac uses is set in System Settings › Network › (your connection) › Details › DNS, or on your router.",
                )
                ours.systemDnsError != null && ours.publicDns == null -> Triple(
                    FailureKind.DNS, "This Mac's DNS does not find $host",
                    "The DNS this Mac uses does not know $host. A public DNS could not be asked to compare (${ours.publicDnsError ?: "no answer"}), so it is not known whether the site moved or your network's DNS leaves it out.",
                )
                ours.systemDnsError != null -> Triple(
                    FailureKind.DNS, "$host does not exist", "Neither this Mac's DNS nor a public DNS knows $host. The site has moved or closed; an update of the extension may have its new address.",
                )
                local && publicAnswer.any { it.public } -> Triple(
                    FailureKind.DNS, "This Mac's DNS sends $host nowhere",
                    "This Mac's DNS answers ${ours.systemDns.joinToString { it.address }} for $host, an address that is not the site (a public DNS says ${publicAnswer.first { it.public }.address}). Your network or provider blocks or redirects the site by DNS; connections then fail with \"No route to host\". A browser that uses secure DNS still reaches it, which is why it may work there. The manga server uses this Mac's DNS; which DNS this Mac uses is set in System Settings › Network › (your connection) › Details › DNS, or on your router.",
                )
                oursConnects && theirsConnects == false -> Triple(
                    FailureKind.NO_ROUTE, "The manga server's program is not allowed to connect",
                    "DebridOne reaches $host, but the manga server's own Java program does not, from the same Mac. Something treats that program differently: a firewall app (Little Snitch, LuLu…), a VPN with per-app rules, or macOS's Local Network permission for DebridOne (System Settings › Privacy & Security › Local Network). Allow the program that the details name.",
                )
                !oursConnects && only("NoRouteToHostException") -> Triple(
                    FailureKind.NO_ROUTE, "No network route to $host",
                    "This Mac has no route to $host's address" + (if (ours.systemDns.all { it.family == "IPv6" }) " (it only has IPv6 addresses, and this network may not have IPv6)" else "") + ". The connection is stopped before it reaches the site: a firewall or VPN, or your network or provider blocking that address. The site itself may be fine; opening it in a browser on the same network shows whether it is blocked for everything.",
                )
                !oursConnects && only("SocketTimeoutException") -> Triple(
                    FailureKind.TIMEOUT, "$host does not answer", "Connections to $host get no answer at all. The site may be down, or a network in between silently drops connections to it.",
                )
                !oursConnects && only("ConnectException") -> Triple(
                    FailureKind.UNAVAILABLE, "$host refuses connections", "$host's address refuses connections: the site is down, or not served at that address right now.",
                )
                !oursConnects -> Triple(
                    FailureKind.NO_ROUTE, "$host cannot be reached", "No connection to $host could be made (${ours.connects.firstOrNull()?.let { shortError(it.errorClass, it.error) } ?: "no address"}).",
                )
                ours.tls?.ok == false -> Triple(
                    FailureKind.TLS, "The secure connection to $host fails", "The connection is made, but the secure handshake fails (${shortError(ours.tls.errorClass, ours.tls.error)}). Something between this Mac and the site (an antivirus, a company proxy) may intercept secure connections, or the site's certificate is wrong.",
                )
                http?.browserCheck == true -> Triple(
                    FailureKind.BROWSER_CHECK, "$host shows a browser check", "The site is reachable but puts up a browser check (${http.server ?: "Cloudflare or similar"}). The manga server has no browser to pass it; a FlareSolverr of your own (Manga settings › Server) can.",
                )
                http?.status != null && http.status >= 500 -> Triple(
                    FailureKind.UNAVAILABLE, "$host has an error of its own", "The site is reachable but answers with HTTP ${http.status}: a problem on its side, usually temporary.",
                )
                http?.status == 403 -> Triple(
                    FailureKind.HTTP, "$host refuses requests", "The site is reachable but answers 403 Forbidden to a browser-like request: it blocks this network or this kind of client.",
                )
                http?.status == null && http != null -> Triple(
                    FailureKind.UNAVAILABLE, "$host stops answering", "The connection is made but the request fails (${shortError(http.errorClass, http.error)}).",
                )
                else -> Triple(
                    null, "$host is reachable",
                    "This Mac reaches $host and it answers (HTTP ${http?.status ?: "?"}). If the source still fails, the problem is in its extension: the site changed in a way the extension does not expect, and an update of the extension may fix it.",
                )
            }
            val dnsDiffers = kind == FailureKind.DNS && publicAnswer.any { it.public }
            return Diagnosis(ours.url, kind, headline, explanation, steps, listOfNotNull(ours, theirs), dnsDiffers)
        }

        private fun shortError(errorClass: String?, message: String?): String {
            val name = errorClass?.substringAfterLast('.')
            return when (name) {
                "NoRouteToHostException" -> "no route to host"
                "SocketTimeoutException" -> "timed out"
                "ConnectException" -> "refused" + (message?.takeIf { !it.contains("refused", true) }?.let { " ($it)" } ?: "")
                "UnknownHostException" -> "name not found"
                "SSLHandshakeException" -> "handshake failed: ${message.orEmpty().take(120)}"
                null -> message.orEmpty().take(120)
                else -> "$name: ${message.orEmpty().take(120)}"
            }
        }

        private fun commonName(principal: String?): String =
            principal?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("CN=") }?.removePrefix("CN=") ?: principal.orEmpty().take(60)
    }
}
