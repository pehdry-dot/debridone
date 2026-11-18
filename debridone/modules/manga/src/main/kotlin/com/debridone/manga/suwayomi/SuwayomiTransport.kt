package com.debridone.manga.suwayomi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** Something went wrong talking to a Suwayomi server. [message] is a sentence for the person. */
class SuwayomiException(val kind: Kind, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind {
        /** Nothing answers at the address: the server is not running, or the address is wrong. */
        UNREACHABLE,

        /** The server refused the sign-in. */
        UNAUTHORIZED,

        /** The request took too long. */
        TIMEOUT,

        /** The server (or the source it asked) reported an error. */
        SERVER,

        /** The answer was not what Suwayomi's API documents. */
        PROTOCOL,
    }
}

/**
 * HTTP to one Suwayomi server: GraphQL requests to `/api/graphql`, file uploads in the GraphQL
 * multipart form, and plain GETs for the images the API hands out as paths (`/api/v1/…`).
 * Every request carries the sign-in; the password never appears in an address or a message.
 */
class SuwayomiTransport(val endpoint: ServerEndpoint) {
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        // The server DebridOne started is on this computer: no proxy can be between them.
        .apply { if (endpoint.managed) proxy(NO_PROXY) }
        .build()

    private val authorization: String? = endpoint.username?.let { user ->
        "Basic " + Base64.getEncoder().encodeToString("$user:${endpoint.password.orEmpty()}".toByteArray())
    }

    private val base: String = endpoint.baseUrl.trimEnd('/')

    private fun request(path: String, timeoutSeconds: Int): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .header("User-Agent", "DebridOne")
            .apply { authorization?.let { header("Authorization", it) } }

    /** Runs one GraphQL document and returns its `data`. */
    fun execute(document: String, variables: JsonObject? = null, timeoutSeconds: Int = endpoint.timeoutSeconds): JsonObject {
        val body = buildJsonObject {
            put("query", document)
            if (variables != null) put("variables", variables)
        }.toString()
        val request = request("/api/graphql", timeoutSeconds)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return data(send(request, HttpResponse.BodyHandlers.ofString()))
    }

    /**
     * Runs a mutation that takes a file (GraphQL multipart request). [filePath] names the variable
     * the file becomes, e.g. `variables.extensionFile`. Suwayomi fills top-level variables only, so
     * the document declares the file as a variable of its own (`$extensionFile: Upload!`).
     */
    fun upload(document: String, variables: JsonObject, filePath: String, fileName: String, bytes: ByteArray, timeoutSeconds: Int = 300): JsonObject {
        val boundary = "debridone-" + UUID.randomUUID()
        val operations = buildJsonObject {
            put("query", document)
            put("variables", variables)
        }.toString()
        val map = buildJsonObject { put("0", JsonArray(listOf(JsonPrimitive(filePath)))) }.toString()
        val out = ByteArrayOutputStream(bytes.size + 2048)
        fun part(name: String, header: String, content: ByteArray) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"$header\r\n\r\n".toByteArray())
            out.write(content)
            out.write("\r\n".toByteArray())
        }
        part("operations", "", operations.toByteArray())
        part("map", "", map.toByteArray())
        val safeName = fileName.replace(Regex("[\"\\r\\n]"), "_")
        part("0", "; filename=\"$safeName\"\r\nContent-Type: application/octet-stream", bytes)
        out.write("--$boundary--\r\n".toByteArray())
        val request = request("/api/graphql", timeoutSeconds)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
            .build()
        return data(send(request, HttpResponse.BodyHandlers.ofString()))
    }

    /** Fetches what the API named by a path of its own (a page, a cover, an icon, a backup file). */
    fun bytes(path: String, timeoutSeconds: Int = endpoint.timeoutSeconds, maxBytes: Int = MAX_IMAGE_BYTES): ByteArray {
        require(path.startsWith("/")) { "not a path of the server" }
        val response = send(request(path, timeoutSeconds).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) throw SuwayomiException(SuwayomiException.Kind.SERVER, "The manga server answered ${response.statusCode()} for an image.")
        val body = response.body()
        if (body.size > maxBytes) throw SuwayomiException(SuwayomiException.Kind.PROTOCOL, "The manga server sent a file larger than DebridOne accepts.")
        return body
    }

    private fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val response = try {
            client.send(request, handler)
        } catch (e: HttpConnectTimeoutException) {
            throw SuwayomiException(SuwayomiException.Kind.UNREACHABLE, "The manga server did not answer.", e)
        } catch (e: HttpTimeoutException) {
            throw SuwayomiException(SuwayomiException.Kind.TIMEOUT, "The manga server took too long to answer.", e)
        } catch (e: ConnectException) {
            throw SuwayomiException(SuwayomiException.Kind.UNREACHABLE, "The manga server is not reachable.", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SuwayomiException(SuwayomiException.Kind.TIMEOUT, "The request to the manga server was interrupted.", e)
        } catch (e: IOException) {
            throw SuwayomiException(SuwayomiException.Kind.UNREACHABLE, "The connection to the manga server was lost.", e)
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw SuwayomiException(SuwayomiException.Kind.UNAUTHORIZED, "The manga server did not accept the sign-in.")
        }
        return response
    }

    private fun data(response: HttpResponse<String>): JsonObject {
        val root = try {
            JSON.parseToJsonElement(response.body()).jsonObject
        } catch (e: Exception) {
            throw SuwayomiException(
                if (response.statusCode() >= 500) SuwayomiException.Kind.SERVER else SuwayomiException.Kind.PROTOCOL,
                "The manga server answered ${response.statusCode()} with something that is not its API.",
            )
        }
        val errors = root["errors"] as? JsonArray
        if (errors != null && errors.isNotEmpty()) {
            val raw = (errors.first().jsonObject["message"] as? JsonPrimitive)?.content.orEmpty()
            throw SuwayomiException(SuwayomiException.Kind.SERVER, cleanMessage(raw))
        }
        return (root["data"] as? JsonObject) ?: throw SuwayomiException(SuwayomiException.Kind.PROTOCOL, "The manga server's answer had no data.")
    }

    companion object {
        internal val JSON = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }
        private val NO_PROXY: ProxySelector = HttpClient.Builder.NO_PROXY
        private const val MAX_IMAGE_BYTES = 96 * 1024 * 1024

        /**
         * What Suwayomi says went wrong, without its wrapper and without the stack trace it appends
         * ("Exception while fetching data (/path) : <message>\r\n\r\n<trace>").
         */
        internal fun cleanMessage(raw: String): String {
            val afterPrefix = if (raw.startsWith("Exception while fetching data")) raw.substringAfter(" : ", raw) else raw
            val first = afterPrefix.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            // "java.io.IOException: text" reads better as "text".
            val text = first.replace(Regex("^(?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error):\\s*"), "")
            return text.ifBlank { "The manga server reported an error." }.take(400)
        }
    }
}

// ---- small helpers for reading GraphQL answers ----------------------------------------------------

internal fun JsonObject.obj(name: String): JsonObject? = (this[name] as? JsonObject)
internal fun JsonObject.need(name: String): JsonObject = obj(name) ?: throw SuwayomiException(SuwayomiException.Kind.PROTOCOL, "The manga server's answer had no \"$name\".")
internal fun JsonObject.list(name: String): List<JsonElement> = (this[name] as? JsonArray)?.toList().orEmpty()
internal fun JsonObject.nodes(name: String): List<JsonObject> = obj(name)?.let { (it["nodes"] as? JsonArray) }?.map { it.jsonObject }.orEmpty()
internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
internal fun JsonObject.int(name: String): Int? = text(name)?.toDoubleOrNull()?.toInt()
internal fun JsonObject.long(name: String): Long? = text(name)?.toDoubleOrNull()?.toLong()
internal fun JsonObject.bool(name: String): Boolean? = text(name)?.toBooleanStrictOrNull()
internal fun JsonObject.strings(name: String): List<String> = (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }.orEmpty()
internal fun JsonObject.objects(name: String): List<JsonObject> = (this[name] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
