package com.debridone.manga.sources

import com.debridone.manga.suwayomi.SuwayomiException
import java.net.URI

/**
 * What kind of failure a source had. The kinds are kept apart on purpose: a site that cannot be
 * found by name, a computer with no route to it, a site that refuses, and an extension whose code
 * broke all need something different from the person.
 */
enum class FailureKind(val label: String, val temporary: Boolean) {
    /** The site's name did not resolve (DNS), or resolved to an address that leads nowhere. */
    DNS("Name not found (DNS)", false),

    /** The computer has no network route to the site's address. */
    NO_ROUTE("No network route", false),

    /** The site took too long, or stopped answering mid-way. */
    TIMEOUT("Timed out", true),

    /** The site is down or refusing connections, or had an error of its own (HTTP 5xx). */
    UNAVAILABLE("Site unavailable", true),

    /** The secure connection (TLS) to the site failed. */
    TLS("Secure connection failed", false),

    /** The site answered with an HTTP error (404, 403 without a browser check, 429…). */
    HTTP("HTTP error", false),

    /** The site wants a sign-in, or did not accept the one given. */
    AUTH("Sign-in needed", false),

    /** The site put up a browser check (Cloudflare and the like). */
    BROWSER_CHECK("Browser check", false),

    /** The source needs something filled in by the person first (an address, an API key, a sign-in). */
    NEEDS_SETTINGS("Needs its settings", false),

    /** The extension's own code failed: the site answered, but not the way the extension expects. */
    EXTENSION("Extension error", false),

    /** The chapter's list of pages could not be had. */
    CHAPTER("Chapter failed to load", true),

    /** A page's picture could not be had or is not a picture. */
    IMAGE("Image failed to load", true),

    /** The extension is of a kind this server cannot run. */
    INCOMPATIBLE("Incompatible format", false),

    /** DebridOne could not talk to its manga server (not the source's fault). */
    SERVER("Manga server problem", true),
}

/**
 * A source failure put in words a person can act on. [title] is short (for a status chip),
 * [explanation] says what it means and what to do, [detail] is the technical text (shown only when
 * the details are opened), cleaned of credentials and long query strings.
 */
data class SourceFailure(
    val kind: FailureKind,
    val title: String,
    val explanation: String,
    val detail: String,
    val host: String? = null,
    val httpStatus: Int? = null,
    val exception: String? = null,
) {
    val temporary: Boolean get() = kind.temporary || httpStatus == 429 || (httpStatus ?: 0) >= 500

    companion object {
        /**
         * Classifies [failure] for the source called [sourceName] whose requests go to [baseUrl].
         * The exception the server reports (kept in [SuwayomiException.detail]) decides, not the
         * wording of the message.
         */
        fun of(sourceName: String, failure: Throwable, baseUrl: String? = null): SourceFailure {
            val server = failure as? SuwayomiException
            val raw = server?.detail ?: failure.message.orEmpty()
            val short = failure.message.orEmpty()
            val text = "$short\n$raw"
            val lower = text.lowercase()
            val host = hostIn(text) ?: baseUrl?.let { runCatching { URI(it).host }.getOrNull() }
            val exception = exceptionIn(raw)
            val status = Regex("http error (\\d{3})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("\\b(?:status|code)[ =:]+(\\d{3})\\b", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 400..599 }
            val site = host ?: "the site"
            val detail = sanitize(raw.ifBlank { short })
            fun make(kind: FailureKind, explanation: String): SourceFailure {
                val suffix = when {
                    status == null -> ""
                    kind == FailureKind.HTTP -> " $status"
                    kind == FailureKind.UNAVAILABLE && status >= 500 -> " (HTTP $status)"
                    else -> ""
                }
                return SourceFailure(kind, kind.label + suffix, explanation, detail, host, status, exception)
            }

            if (server != null && server.kind != SuwayomiException.Kind.SERVER && server.kind != SuwayomiException.Kind.TIMEOUT) {
                return make(FailureKind.SERVER, short.ifBlank { "DebridOne could not reach its manga server." } + " This is between DebridOne and its manga server, not $sourceName.")
            }
            return when {
                has(lower, exception, "unknownhostexception", "unable to resolve host", "nodename nor servname", "no address associated", "name or service not known", "temporary failure in name resolution") ->
                    make(FailureKind.DNS, "$sourceName's address ($site) could not be found by name. Your network's DNS does not know it, or blocks it; the site may also have moved. \"Diagnose connection\" compares your DNS with a public one.")
                has(lower, exception, "noroutetohostexception", "no route to host", "network is unreachable", "host is unreachable") ->
                    make(FailureKind.NO_ROUTE, "This computer has no network route to $site: the connection was stopped before it reached the site. Usually a firewall or VPN, your network or provider blocking the address, or a DNS answer that points nowhere. \"Diagnose connection\" shows which.")
                has(lower, exception, "sockettimeoutexception", "timeout", "timed out", "interruptedioexception") || server?.kind == SuwayomiException.Kind.TIMEOUT ->
                    make(FailureKind.TIMEOUT, "$sourceName ($site) did not answer in time. The site may be slow or overloaded, or a network in between drops the connection.")
                has(lower, exception, "sslhandshakeexception", "sslexception", "sslpeerunverified", "pkix path", "certpathvalidator", "certificate_unknown", "handshake_failure", "unable to find valid certification") && !lower.contains("http error") ->
                    make(FailureKind.TLS, "The secure connection to $site failed. Its certificate is not trusted on this computer, or something in between (an antivirus, a company proxy) intercepts secure connections.")
                lower.contains("cloudflare") || lower.contains("cf_clearance") || lower.contains("cf-mitigated") || lower.contains("webview") || lower.contains("captcha") || lower.contains("ddos-guard") || lower.contains("bypass") ->
                    make(FailureKind.BROWSER_CHECK, "$site asks for a browser check (Cloudflare or similar) before it answers. The manga server has no browser to pass it with; a FlareSolverr of your own, named in Manga settings › Server, can do it.")
                NEEDS_SETTINGS.containsMatchIn(short) ->
                    make(FailureKind.NEEDS_SETTINGS, "$sourceName needs its settings filled in before it can be used: \"${short.trim().take(160)}\". Open Configure, fill in what it asks for, then test again.")
                has(lower, exception, "connectexception", "connection refused", "failed to connect") ->
                    make(FailureKind.UNAVAILABLE, "$site refused the connection: the site is down or does not accept connections on that address right now.")
                status != null && status >= 500 ->
                    make(FailureKind.UNAVAILABLE, "$site had an error of its own (HTTP $status). It is usually temporary; try again later.")
                status == 401 || lower.contains("unauthorized") || lower.contains("not logged in") || lower.contains("login required") || lower.contains("please log in") ->
                    make(FailureKind.AUTH, "$sourceName needs a sign-in, or did not accept the one in its settings (Configure).")
                status == 403 ->
                    make(FailureKind.HTTP, "$site refused the request (HTTP 403 Forbidden). It may block this kind of client or your network; a browser check that was not recognised as one also looks like this.")
                status == 404 ->
                    make(FailureKind.HTTP, "$site has nothing at the address the extension asked for (HTTP 404). The site probably changed; an update of the extension may fix it.")
                status == 429 ->
                    make(FailureKind.HTTP, "$site asked for fewer requests (HTTP 429). Wait a while before trying again.")
                status != null ->
                    make(FailureKind.HTTP, "$site answered with HTTP $status.")
                has(lower, exception, "connection reset", "unexpected end of stream", "eofexception", "broken pipe", "stream was reset", "connection closed") ->
                    make(FailureKind.UNAVAILABLE, "The connection to $site was cut off while it was answering. Usually temporary.")
                lower.contains("no chapters found") ->
                    make(FailureKind.EXTENSION, "$sourceName lists no chapters for this series.")
                lower.contains("source not found") || lower.contains("not installed") || lower.contains("classnotfound") || lower.contains("noclassdeffound") || lower.contains("abstractmethoderror") || lower.contains("nosuchmethod") ->
                    make(FailureKind.INCOMPATIBLE, "$sourceName's extension could not be loaded by the manga server: it was made for another version of the extension system.")
                else ->
                    make(FailureKind.EXTENSION, "$site answered, but $sourceName's extension could not read what came back" + (short.takeIf { it.isNotBlank() }?.let { ": ${it.take(200)}" } ?: ".") + " The site probably changed; an update of the extension may fix it.")
            }
        }

        /** What extensions say when the person has not filled in their settings yet ("Set Address in Settings", "Please configure the API key"…). */
        private val NEEDS_SETTINGS = Regex(
            "\\b(set|enter|fill in|add|provide|configure)\\b[^.\\n]{0,60}\\b(in|from|under) (the )?(source |extension )?(settings|preferences|configuration)\\b" +
                "|\\b(not configured|please configure|configure the source|missing (server )?(address|url|api key|credentials))\\b",
            RegexOption.IGNORE_CASE,
        )

        private fun has(lower: String, exception: String?, vararg needles: String): Boolean =
            needles.any { lower.contains(it) || exception?.lowercase()?.contains(it) == true }

        /** The innermost exception class the server's report names ("Caused by: java.net.NoRouteToHostException"). */
        fun exceptionIn(raw: String): String? {
            val names = Regex("(?:^|\\n|Caused by: )((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error))").findAll(raw).map { it.groupValues[1] }.toList()
            return names.lastOrNull()
        }

        private fun hostIn(text: String): String? =
            Regex("UnknownHostException: ([a-z0-9.-]+\\.[a-z]{2,})\\b", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.lowercase()
                ?: Regex("([a-z0-9.-]+\\.[a-z]{2,}): (?:Name or service not known|nodename nor servname|No address associated|Temporary failure in name resolution)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.lowercase()
                ?: Regex("(?:resolve host|connect to|host)[ \"':]+([a-z0-9.-]+\\.[a-z]{2,})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.lowercase()
                ?: Regex("https?://([a-z0-9.-]+\\.[a-z]{2,})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.lowercase()

        /**
         * Technical text safe to show: no query strings (they can carry tokens), no user:password in
         * addresses, no long stack traces, no cookie or authorization values.
         */
        fun sanitize(text: String): String = text
            .replace(Regex("(https?://)[^/\\s:@]+:[^/\\s@]+@"), "$1")
            .replace(Regex("(https?://[^\\s?#\"']+)\\?[^\\s\"']*"), "$1?…")
            .replace(Regex("(?im)\\b(proxy-authorization|authorization|set-cookie|cookie)\\s*[:=].*$"), "$1: …")
            .replace(Regex("(?i)\\b(token|access_token|password|passwd|apikey|api_key|secret)([\"' ]*[:=][\"' ]*)[^\\s\"',;&]+"), "$1$2…")
            .lineSequence().filter { it.isNotBlank() }
            .filterNot { it.trimStart().startsWith("at ") && !it.contains("tachiyomi.extension") }
            .take(12).joinToString("\n").take(2000)
    }
}
