package com.debridone.cloud

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** An answer of any status. */
internal class HttpAnswer(val status: Int, val body: ByteArray, private val header: (String) -> String?) {
    fun text(): String = String(body, StandardCharsets.UTF_8)
    fun header(name: String): String? = header.invoke(name)
    val retryAfterSeconds: Int? get() = header("Retry-After")?.trim()?.toIntOrNull()
    fun json(): JsonElement? = runCatching { Json.parseToJsonElement(text()) }.getOrNull()
}

/**
 * The HTTP the cloud layer needs, on plain [HttpURLConnection]: every request has timeouts and a
 * size limit and identifies the application. Failures to connect are reported without the
 * exception's own text, because an address can carry a key.
 */
internal object CloudHttp {
    const val USER_AGENT = "DebridOne/0.1 (desktop)"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 45_000

    fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    fun query(parameters: List<Pair<String, String>>): String = parameters.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    /**
     * One request and its whole answer. [form] makes it a form POST, [json] a JSON body; [service]
     * names the far end in the message when it cannot be reached.
     */
    fun exchange(
        service: String,
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        form: List<Pair<String, String>>? = null,
        json: String? = null,
        maxBytes: Int = 64 * 1024 * 1024,
    ): HttpAnswer {
        val body = form?.let { query(it).toByteArray(StandardCharsets.UTF_8) } ?: json?.toByteArray(StandardCharsets.UTF_8)
        val type = when {
            form != null -> "application/x-www-form-urlencoded"
            json != null -> "application/json"
            else -> null
        }
        val connection = try {
            open(url, headers + listOfNotNull(type?.let { "Content-Type" to it }))
        } catch (e: IllegalArgumentException) {
            throw CloudException(CloudException.Reason.PROTOCOL, "The address of $service is not valid.")
        }
        try {
            connection.requestMethod = method
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream: InputStream? = if (status >= 400) connection.errorStream else runCatching { connection.inputStream }.getOrNull()
            val bytes = stream?.use { readLimited(it, maxBytes) } ?: ByteArray(0)
            val names = connection.headerFields
            return HttpAnswer(status, bytes) { name -> names.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull() }
        } catch (e: CloudException) {
            throw e
        } catch (e: IOException) {
            // Not e.message: it may quote the address, and the address may carry a key.
            throw CloudException(CloudException.Reason.NETWORK, "$service could not be reached.")
        } finally {
            connection.disconnect()
        }
    }

    /** A connection for a download, not yet read. The caller reads the status and the stream and disconnects. */
    fun open(url: String, headers: Map<String, String> = emptyMap()): HttpURLConnection {
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
            if (output.size() + read > maxBytes) throw CloudException(CloudException.Reason.PROTOCOL, "The answer is larger than expected.")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

/** Keeps requests to one service apart in time, so its request limit is never approached. */
internal class Throttle(private val spacingMs: Long, private val clock: () -> Long, private val pause: (Long) -> Unit) {
    private var nextAt = 0L

    @Synchronized
    fun await() {
        if (spacingMs <= 0) return
        val now = clock()
        if (now < nextAt) pause(nextAt - now)
        nextAt = maxOf(now, nextAt) + spacingMs
    }
}

// ---------------------------------------------------------------- reading JSON leniently

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.array(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

/** An id that may arrive as a number or a text: `12` and `12.0` are both "12". */
internal fun JsonObject.id(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { value ->
    value.longOrNull?.toString() ?: value.doubleOrNull?.takeIf { it == Math.floor(it) }?.toLong()?.toString() ?: value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

/** A point in time written the ISO way (`2026-03-08T21:21:28Z`, with or without fractions or an offset). */
internal fun parseTime(text: String?): Long? {
    if (text.isNullOrBlank()) return null
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}
