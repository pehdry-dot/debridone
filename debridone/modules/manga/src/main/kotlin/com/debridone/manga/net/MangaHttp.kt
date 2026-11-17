package com.debridone.manga.net

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

class HttpResponse(val status: Int, val contentType: String?, val body: ByteArray, val finalUrl: String, val headers: Map<String, String> = emptyMap()) {
    fun text(): String = String(body, StandardCharsets.UTF_8)
}

/**
 * The HTTP operations the Manga engine itself performs (package registry, metadata providers,
 * images whose address a source hands back). Requests made by source extensions do not go through
 * here; they are made by the extension inside the host process.
 *
 * Every request has timeouts and a size limit and identifies the app honestly.
 */
object MangaHttp {
    const val USER_AGENT = "DebridOne/0.1 (desktop manga reader)"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    fun get(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 16 * 1024 * 1024): HttpResponse =
        request("GET", url, headers, null, maxBytes)

    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = 8 * 1024 * 1024): HttpResponse =
        request("POST", url, headers + ("Content-Type" to "application/json") + ("Accept" to "application/json"), json.toByteArray(StandardCharsets.UTF_8), maxBytes)

    fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): HttpResponse {
        val body = fields.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        return request("POST", url, headers + ("Content-Type" to "application/x-www-form-urlencoded"), body.toByteArray(StandardCharsets.UTF_8), 4 * 1024 * 1024)
    }

    fun send(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null, maxBytes: Int = 8 * 1024 * 1024): HttpResponse =
        request(method, url, headers, body, maxBytes)

    private fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Int): HttpResponse {
        val connection = open(url, headers)
        try {
            if (method == "PATCH") {
                // HttpURLConnection has no PATCH; the documented override header is understood by the APIs used here.
                connection.requestMethod = "POST"
                connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                connection.requestMethod = method
            }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { readLimited(it, maxBytes) } ?: ByteArray(0)
            if (status >= 400) {
                val detail = bytes.takeIf { it.isNotEmpty() }?.let { ": " + String(it, StandardCharsets.UTF_8).take(300) }.orEmpty()
                throw HttpStatusException(status, url.substringBefore('?'), "The server answered $status$detail")
            }
            val responseHeaders = connection.headerFields.orEmpty()
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.joinToString(", ") }
            return HttpResponse(status, connection.contentType, bytes, connection.url.toString(), responseHeaders)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Streams [url] into [target] through a temporary file next to it and returns the number of
     * bytes written. [onBytes] is called with each chunk (used to hash the download while it arrives).
     */
    fun download(
        url: String,
        target: Path,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = 256L * 1024 * 1024,
        onBytes: (ByteArray, Int) -> Unit = { _, _ -> },
    ): Long {
        val connection = open(url, headers)
        val partial = target.resolveSibling(target.fileName.toString() + ".part")
        try {
            val status = connection.responseCode
            if (status >= 400) throw HttpStatusException(status, url.substringBefore('?'), "The server answered $status")
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
                        onBytes(buffer, read)
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
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw IOException("The answer is larger than the allowed size.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
