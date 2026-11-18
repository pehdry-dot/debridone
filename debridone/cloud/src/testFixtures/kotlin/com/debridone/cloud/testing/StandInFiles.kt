package com.debridone.cloud.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A file of a stand-in account. Its bytes are either a real sample (when a folder of samples has
 * one with the same extension) or made up from the name, always the same for the same name.
 */
class StandInFile(val path: String, sizeBytes: Int = if (path.endsWith(".mkv")) 400_000 else 48_000, samples: Path? = null) {
    val name: String get() = path.substringAfterLast('/')
    val bytes: ByteArray by lazy {
        val extension = name.substringAfterLast('.', "").lowercase()
        val sample = samples?.resolve("sample.$extension")?.takeIf { Files.isRegularFile(it) }
        sample?.let(Files::readAllBytes) ?: ByteArray(sizeBytes).also { java.util.Random(path.hashCode().toLong()).nextBytes(it) }
    }
    val size: Long get() = bytes.size.toLong()
    val md5: String by lazy { MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) } }
}

/**
 * The part of a stand-in provider that hands files out, the way the real download servers do:
 * plain addresses with a token in them, range requests answered with 206, an ETag. Tests can make
 * it misbehave: drop a connection part of the way, ignore ranges, forget the addresses it issued,
 * or be busy.
 */
abstract class StandInServer(port: Int = 0) : AutoCloseable {
    protected val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

    val origin: String get() = "http://127.0.0.1:${server.address.port}"

    /** Every request as "METHOD /path" (without the query, which may carry a key). */
    val requests = CopyOnWriteArrayList<String>()

    /** The `Range` header of every file request, in order ("" when there was none). */
    val ranges = CopyOnWriteArrayList<String>()

    /** Cut the connection after this many bytes of a file answer; applies [dropTimes] times. */
    val dropAfterBytes = AtomicLong(0)
    val dropTimes = AtomicInteger(0)

    /** Answer range requests with the whole file, as a server without range support does. */
    @Volatile
    var ignoreRanges = false

    /** This many of the next file requests are answered 503. */
    val busyTimes = AtomicInteger(0)

    /** Slows file answers down (milliseconds per 16 KiB), so a test can pause or cancel a transfer in progress. */
    @Volatile
    var slowMs = 0L

    private val issued = ConcurrentHashMap<String, StandInFile>()
    private val counter = AtomicInteger(0)

    /** This many of the next addresses handed out have already expired when they are used. */
    val bornExpired = AtomicInteger(0)

    /** A new download address for [file]. */
    protected fun addressOf(file: StandInFile): String {
        val token = "t${counter.incrementAndGet()}x${Integer.toHexString(file.path.hashCode())}"
        if (bornExpired.getAndUpdate { if (it > 0) it - 1 else 0 } == 0) issued[token] = file
        return "$origin/files/$token/${file.name.replace(" ", "%20")}"
    }

    /** Every address issued so far stops working, as addresses do when they expire. */
    fun expireAddresses() = issued.clear()

    val addressesIssued: Int get() = counter.get()

    init {
        server.createContext("/") { exchange ->
            runCatching {
                requests += "${exchange.requestMethod} ${exchange.requestURI.rawPath.let { if (it.startsWith("/files/")) "/files" else it }}"
                if (exchange.requestURI.rawPath.startsWith("/files/")) serveFile(exchange) else handle(exchange)
            }.onFailure { runCatching { send(exchange, 500, "application/json", """{"error":"internal_error"}""".toByteArray()) } }
        }
        server.start()
    }

    override fun close() = server.stop(0)

    protected abstract fun handle(exchange: HttpExchange)

    private fun serveFile(exchange: HttpExchange) {
        val token = exchange.requestURI.rawPath.removePrefix("/files/").substringBefore('/')
        val range = exchange.requestHeaders.getFirst("Range").orEmpty()
        ranges += range
        if (busyTimes.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
            exchange.responseHeaders.add("Retry-After", "0")
            return send(exchange, 503, "text/plain", "busy".toByteArray())
        }
        val file = issued[token] ?: return send(exchange, 404, "text/plain", "gone".toByteArray())
        val data = file.bytes
        val from = if (ignoreRanges) null else Regex("bytes=(\\d+)-").find(range)?.groupValues?.get(1)?.toLongOrNull()
        exchange.responseHeaders.add("ETag", "\"${file.md5}\"")
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.responseHeaders.add("Content-Type", "application/force-download")
        if (from != null && from >= data.size) {
            exchange.responseHeaders.add("Content-Range", "bytes */${data.size}")
            exchange.sendResponseHeaders(416, -1)
            exchange.close()
            return
        }
        val start = (from ?: 0).toInt()
        if (from != null) exchange.responseHeaders.add("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
        exchange.sendResponseHeaders(if (from != null) 206 else 200, (data.size - start).toLong())
        val cut = if (dropTimes.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) dropAfterBytes.get().toInt() else Int.MAX_VALUE
        val body = exchange.responseBody
        var sent = 0
        var position = start
        try {
            while (position < data.size && sent < cut) {
                val chunk = minOf(16 * 1024, data.size - position, cut - sent)
                body.write(data, position, chunk)
                body.flush()
                position += chunk
                sent += chunk
                if (slowMs > 0) Thread.sleep(slowMs)
            }
        } finally {
            // Closing before everything promised was sent is what a dropped connection looks like.
            runCatching { exchange.close() }
        }
    }

    protected fun fields(encoded: String): Map<String, String> = encoded.split('&').filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

    protected fun json(exchange: HttpExchange, status: Int, body: String) = send(exchange, status, "application/json", body.toByteArray())

    protected fun send(exchange: HttpExchange, status: Int, type: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
            return
        }
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    protected fun quote(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("/", "\\/") + "\""
}
