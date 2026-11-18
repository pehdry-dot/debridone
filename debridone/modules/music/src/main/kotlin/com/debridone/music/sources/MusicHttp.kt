package com.debridone.music.sources

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class HttpStatusException(val status: Int, val url: String, message: String) : IOException(message)

/** An answer of any status; see [MusicHttp.exchange]. */
internal class HttpAnswer(val status: Int, val contentType: String?, val body: ByteArray, val retryAfterSeconds: Int?) {
    fun text(): String = String(body, StandardCharsets.UTF_8)
}

internal class HttpResponse(val status: Int, val contentType: String?, val body: ByteArray, val finalUrl: String) {
    fun text(): String = String(body, StandardCharsets.UTF_8)
}

/**
 * The few HTTP operations Music's sources and metadata providers need, on plain
 * [HttpURLConnection]. Every request has timeouts and a size limit, and identifies the app.
 */
internal object MusicHttp {
    const val USER_AGENT = "DebridOne/0.1 (desktop music library)"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    fun query(parameters: List<Pair<String, String>>): String = parameters.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    /** Checks that [text] is an http(s) address and returns it without a trailing slash. */
    fun normalizeBaseUrl(text: String): String {
        val trimmed = text.trim().trimEnd('/')
        val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull()
        require(uri != null && !uri.host.isNullOrBlank()) { "That is not a valid server address." }
        require(uri.userInfo == null) { "Do not put a user name or password in the address; use the fields below." }
        return withScheme
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 16 * 1024 * 1024): HttpResponse =
        request("GET", url, headers, null, maxBytes)

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        request("POST", url, headers + ("Content-Type" to "application/json"), json.toByteArray(StandardCharsets.UTF_8), 4 * 1024 * 1024)

    private fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Int): HttpResponse {
        val connection = open(url, headers)
        try {
            connection.requestMethod = method
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { readLimited(it, maxBytes) } ?: ByteArray(0)
            if (status >= 400) {
                throw HttpStatusException(status, redact(url), "The server answered $status" + bytes.takeIf { it.isNotEmpty() }?.let { ": " + String(it, StandardCharsets.UTF_8).take(200) }.orEmpty())
            }
            return HttpResponse(status, connection.contentType, bytes, connection.url.toString())
        } finally {
            connection.disconnect()
        }
    }

    /**
     * A GET whose error answers are returned instead of thrown, with the `Retry-After` header: for
     * protocols that put a message for the user in the body of a 4xx or 5xx answer.
     */
    fun exchange(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = 8 * 1024 * 1024,
        /** When given, the request is a POST with these fields as a form. */
        form: List<Pair<String, String>>? = null,
    ): HttpAnswer {
        val connection = open(url, if (form == null) headers else headers + ("Content-Type" to "application/x-www-form-urlencoded"))
        try {
            if (form != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(query(form).toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { readLimited(it, maxBytes) } ?: ByteArray(0)
            return HttpAnswer(status, connection.contentType, bytes, connection.getHeaderField("Retry-After")?.trim()?.toIntOrNull())
        } finally {
            connection.disconnect()
        }
    }

    /** Streams [url] into [target] (through a temporary file) and returns the number of bytes written. */
    fun download(url: String, target: Path, headers: Map<String, String> = emptyMap(), maxBytes: Long = 2L * 1024 * 1024 * 1024): Long {
        val connection = open(url, headers)
        val partial = target.resolveSibling(target.fileName.toString() + ".part")
        try {
            val status = connection.responseCode
            if (status >= 400) throw HttpStatusException(status, redact(url), "The server answered $status")
            Files.createDirectories(target.parent)
            var total = 0L
            connection.inputStream.use { input ->
                Files.newOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw IOException("The file is larger than the allowed size.")
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) throw IOException("The server sent an empty file.")
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
            return total
        } finally {
            runCatching { Files.deleteIfExists(partial) }
            connection.disconnect()
        }
    }

    /** Opens [url] just far enough to learn its status and content type. */
    fun probe(url: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        val connection = open(url, headers)
        try {
            val status = connection.responseCode
            if (status >= 400) throw HttpStatusException(status, redact(url), "The server answered $status")
            val head = connection.inputStream.use { input -> input.readNBytes(2_048) }
            return HttpResponse(status, connection.contentType, head, connection.url.toString())
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val uri = URI(url)
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "Only http and https addresses are supported." }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "*/*")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return connection
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > maxBytes) throw IOException("The response is larger than expected.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /** Removes the query string, which may carry credentials, before a URL goes into a message. */
    fun redact(url: String): String = url.substringBefore('?')
}
