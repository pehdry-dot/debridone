package com.debridone.cloud.testing

import com.sun.net.httpserver.HttpExchange
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * TorBox's API as its documentation (api-docs.torbox.app) and official SDKs describe it: the
 * `{success, error, detail, data}` envelope, `/v1/api/user/me`, the three `mylist` endpoints with
 * their files inline, `requestdl` (which wants the key as its `token` parameter) and the three
 * control endpoints. A stand-in, not the service. Every request is recorded, without its query.
 */
class StandInTorBox(samples: Path? = null, port: Int = 0) : StandInServer(port) {
    /** The JSON bodies of every POST, in order. */
    val bodies = CopyOnWriteArrayList<String>()

    /** The `bypass_cache` parameter of every listing ("" when absent). */
    val bypassCache = CopyOnWriteArrayList<String>()

    /** The user replaced their key on TorBox: the old one is refused. */
    @Volatile
    var keyReplaced = false

    /** This many of the next API requests are refused with 429. */
    val rateLimited = AtomicInteger(0)

    /** Makes `requestdl` answer that no download server is available. */
    @Volatile
    var noServers = false

    class Entry(val id: Long, val name: String, val state: String, val finished: Boolean, val present: Boolean, val progress: Double, val files: List<StandInFile>)

    private val second = "Other Artist - Second Album"

    val torrents = CopyOnWriteArrayList(
        listOf(
            Entry(101, second, "cached", finished = true, present = true, progress = 1.0, files = listOf(StandInFile("$second/01 Elsewhere.mp3", samples = samples), StandInFile("$second/02 Somewhere.flac", samples = samples))),
            Entry(102, "Still.Downloading.2024", "downloading", finished = false, present = false, progress = 0.4, files = listOf(StandInFile("Still.Downloading.2024/Still.Downloading.2024.mkv", samples = samples))),
        ),
    )
    val usenet = CopyOnWriteArrayList(listOf(Entry(201, "Documentary.2019", "completed", finished = true, present = true, progress = 1.0, files = listOf(StandInFile("Documentary.2019/Documentary.2019.1080p.mkv", samples = samples)))))
    val web = CopyOnWriteArrayList(
        listOf(
            Entry(301, "Photo Archive.zip", "completed", finished = true, present = true, progress = 1.0, files = listOf(StandInFile("Photo Archive.zip", samples = samples))),
            Entry(302, "Comic Issue 7.cbz", "completed", finished = true, present = true, progress = 1.0, files = listOf(StandInFile("Comic Issue 7.cbz", samples = samples))),
        ),
    )

    /** A wrong checksum for this file id, to prove a corrupted download is not kept. */
    @Volatile
    var wrongChecksumFor: Pair<Long, Int>? = null

    override fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath
        val method = exchange.requestMethod
        val query = fields(exchange.requestURI.rawQuery.orEmpty())
        val body = if (method == "POST") String(exchange.requestBody.readAllBytes()).also { bodies += it } else ""
        if (!path.startsWith("/v1/api/")) return fail(exchange, 404, "ENDPOINT_NOT_FOUND", "This endpoint does not exist.")
        val segments = path.removePrefix("/v1/api/").split('/')
        val requestDl = segments.getOrNull(1) == "requestdl"
        // requestdl takes the key as a parameter; everything else as a Bearer header.
        val key = if (requestDl) query["token"] else exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
        when {
            key.isNullOrEmpty() -> return fail(exchange, 401, "NO_AUTH", "There are no provided credentials.")
            key != API_KEY || keyReplaced -> return fail(exchange, 403, "BAD_TOKEN", "The provided token is invalid.")
            rateLimited.getAndUpdate { if (it > 0) it - 1 else 0 } > 0 -> return send(exchange, 429, "application/json", """{"detail":"Rate limit exceeded: 300 per 1 minute"}""".toByteArray())
        }
        val list = when (segments[0]) {
            "torrents" -> torrents
            "usenet" -> usenet
            "webdl" -> web
            else -> null
        }
        when {
            method == "GET" && path == "/v1/api/user/me" -> ok(
                exchange, "Successfully retrieved your user information.",
                """{"id":7,"auth_id":"auth-7","created_at":"2023-08-30T20:23:00Z","updated_at":"2026-03-09T00:22:40Z","plan":2,"total_downloaded":42,"customer":"cus_x","is_subscribed":true,"premium_expires_at":"2031-02-18T04:08:43Z","cooldown_until":"2020-01-01T00:00:00Z","email":"$EMAIL","user_referral":"ref","base_email":"$EMAIL"}""",
            )
            method == "GET" && list != null && segments.getOrNull(1) == "mylist" -> {
                bypassCache += query["bypass_cache"].orEmpty()
                val offset = query["offset"]?.toIntOrNull() ?: 0
                val limit = query["limit"]?.toIntOrNull() ?: 1000
                ok(exchange, "List retrieved successfully.", list.drop(offset).take(limit).joinToString(",", "[", "]") { entry(it) })
            }
            method == "GET" && list != null && requestDl -> {
                val idName = when (segments[0]) {
                    "torrents" -> "torrent_id"
                    "usenet" -> "usenet_id"
                    else -> "web_id"
                }
                val entry = list.firstOrNull { it.id.toString() == query[idName] } ?: return fail(exchange, 404, "ITEM_NOT_FOUND", "The item you queried cannot be found.")
                val file = entry.files.getOrNull((query["file_id"]?.toIntOrNull() ?: -1)) ?: return fail(exchange, 404, "ITEM_NOT_FOUND", "The file you queried cannot be found.")
                when {
                    noServers -> fail(exchange, 500, "NO_SERVERS_AVAILABLE_ERROR", "There are no download servers available to handle this request.")
                    !entry.present -> fail(exchange, 400, "DOWNLOAD_SERVER_ERROR", "This download is not ready yet.")
                    else -> ok(exchange, "Download requested successfully.", quote(addressOf(file)))
                }
            }
            method == "POST" && list != null && segments.getOrNull(1) in setOf("controltorrent", "controlusenetdownload", "controlwebdownload") -> {
                val idName = when (segments[0]) {
                    "torrents" -> "torrent_id"
                    "usenet" -> "usenet_id"
                    else -> "webdl_id"
                }
                val wanted = Regex("\"$idName\"\\s*:\\s*\"?(\\d+)\"?").find(body)?.groupValues?.get(1)?.toLongOrNull()
                val operation = Regex("\"operation\"\\s*:\\s*\"([a-z]+)\"").find(body)?.groupValues?.get(1)
                val entry = list.firstOrNull { it.id == wanted }
                when {
                    wanted == null || operation == null -> fail(exchange, 400, "MISSING_REQUIRED_OPTION", "The API is missing required information to process the request.")
                    entry == null -> fail(exchange, 404, "ITEM_NOT_FOUND", "The item you queried cannot be found.")
                    operation != "delete" -> fail(exchange, 400, "INVALID_OPTION", "The provided option is invalid.")
                    else -> {
                        list.remove(entry)
                        ok(exchange, "Operation performed successfully.", "null")
                    }
                }
            }
            // Everything else exists on the real service (creating, searching) but is never called.
            else -> fail(exchange, 404, "ENDPOINT_NOT_FOUND", "This endpoint does not exist.")
        }
    }

    private fun entry(entry: Entry): String {
        val files = entry.files.mapIndexed { index, file ->
            val md5 = if (wrongChecksumFor == entry.id to index) "0".repeat(32) else file.md5
            """{"id":$index,"md5":"$md5","hash":"h$index","name":${quote(file.path)},"size":${file.size},"zipped":false,"s3_path":${quote("s3/" + file.path)},"infected":false,"mimetype":"${mime(file.name)}","short_name":${quote(file.name)},"absolute_path":${quote("/data/" + file.path)}}"""
        }
        return """{"id":${entry.id},"auth_id":"auth-7","server":1,"hash":"${"a".repeat(40)}","name":${quote(entry.name)},"size":${entry.files.sumOf { it.size }},"active":${!entry.finished},"created_at":"2026-03-08T21:21:28Z","updated_at":"2026-03-08T21:41:28Z","download_state":"${entry.state}","progress":${entry.progress},"download_speed":0,"upload_speed":0,"eta":0,"torrent_file":true,"expires_at":"2026-12-07T21:21:41Z","download_present":${entry.present},"files":[${files.joinToString(",")}],"download_finished":${entry.finished},"cached":${entry.state == "cached"}${if (entry.finished) ""","cached_at":"2026-03-08T21:31:28Z"""" else ""}}"""
    }

    private fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "mkv" -> "video/x-matroska"
        "zip", "cbz" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun ok(exchange: HttpExchange, detail: String, data: String) = json(exchange, 200, """{"success":true,"error":null,"detail":${quote(detail)},"data":$data}""")

    private fun fail(exchange: HttpExchange, status: Int, code: String, detail: String) = json(exchange, status, """{"success":false,"error":"$code","detail":${quote(detail)},"data":null}""")

    companion object {
        const val API_KEY = "torbox-api-key-0123456789-PRIVATE"
        const val EMAIL = "listener@example.org"
    }
}
