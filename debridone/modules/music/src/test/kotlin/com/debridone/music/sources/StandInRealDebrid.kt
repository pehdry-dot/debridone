package com.debridone.music.sources

import com.debridone.music.Fixtures
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-Debrid's API as its documentation (api.real-debrid.com) describes it, for the tests: the
 * sign-in for open-source apps (`/oauth/v2/device/code`, `/device/credentials`, `/token`) and the
 * REST methods DebridOne's Music uses (`/rest/1.0/user`, `/torrents`, `/torrents/info/{id}`,
 * `/downloads`, `/unrestrict/link`), with the documented field names, error objects and status
 * codes. The "cloud" holds DebridOne's own test recordings.
 *
 * It is a stand-in, not the service: it proves DebridOne speaks the documented format. Every
 * request is recorded so a test can show which methods were called, and that none of them adds,
 * selects or deletes anything.
 */
class StandInRealDebrid : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val origin: String get() = "http://127.0.0.1:${server.address.port}"

    /** Every request as "METHOD /path" (without the query). */
    val requests = CopyOnWriteArrayList<String>()

    /** The form fields of every POST, in order. */
    val forms = CopyOnWriteArrayList<Map<String, String>>()

    /** The user has typed the code on the verification page and allowed the app. */
    @Volatile
    var approved = false

    /** How long the access tokens it hands out are valid. */
    @Volatile
    var tokenLifetimeSeconds = 3_600

    /** The user withdrew the app's rights on Real-Debrid: no token is accepted or renewed any more. */
    @Volatile
    var revoked = false

    /** The account is locked: every REST method answers 403. */
    @Volatile
    var locked = false

    /** This many of the next REST requests are refused with 429. */
    val rateLimited = AtomicInteger(0)

    /** Makes `/torrents/info/{id}` fail, as a service in trouble would. */
    @Volatile
    var infoBroken = false

    /** The torrent that was still downloading has finished: it now has its link. */
    @Volatile
    var busyFinished = false

    /** Ids of torrents the user deleted on Real-Debrid since. */
    val deletedTorrents = CopyOnWriteArrayList<String>()

    /** The access token that is valid now (null before sign-in). */
    @Volatile
    var accessToken: String? = null
        private set

    @Volatile
    var refreshToken: String? = null
        private set

    private val issued = AtomicInteger(0)

    class CloudFile(val path: String, val bytes: Long, val selected: Boolean, val source: Path?)
    class Torrent(val id: String, val filename: String, val status: String, val files: List<CloudFile>, val links: List<String>)
    class Download(val id: String, val filename: String, val mimeType: String, val link: String, val source: Path)

    private fun file(folder: String, name: String, source: Path?, selected: Boolean = true) =
        CloudFile("/$folder/$name", source?.let(Files::size) ?: 1_234, selected, source)

    private val albumFolder = "Test Artist - First Album (2021) [FLAC]"

    val torrents: List<Torrent> get() = if (busyFinished) stored.map { if (it.id == "BUSY1") Torrent(it.id, it.filename, "downloaded", it.files, listOf("$LINK_HOST/BUSY1A")) else it } else stored

    private val stored = listOf(
        // A finished album: three songs, a cover that was selected and a note that was not.
        Torrent(
            "ALBUM1", albumFolder, "downloaded",
            listOf(
                file(albumFolder, "01 - Opening.mp3", Fixtures.mp3),
                file(albumFolder, "02 - Second Light.flac", Fixtures.flac),
                file(albumFolder, "cover.jpg", null),
                file(albumFolder, "info.nfo", null, selected = false),
                file(albumFolder, "03 - Third Wave.m4a", Fixtures.aac),
            ),
            listOf("$LINK_HOST/ALBUM1A", "$LINK_HOST/ALBUM1B", "$LINK_HOST/ALBUM1C", "$LINK_HOST/ALBUM1D"),
        ),
        // Finished, but a film.
        Torrent("FILM1", "Some.Holiday.2020.1080p", "downloaded", listOf(CloudFile("/Some.Holiday.2020.1080p.mkv", 9_000_000, true, null)), listOf("$LINK_HOST/FILM1A")),
        // Still downloading: it has no links yet and must not be listed.
        Torrent(
            "BUSY1", "Other Artist - Second Album", "downloading",
            listOf(file("Other Artist - Second Album", "01 Elsewhere.mp3", Fixtures.elsewhere)), emptyList(),
        ),
        // Finished, but packed into one archive: three files, one link.
        Torrent(
            "PACKED1", "Packed Album", "downloaded",
            listOf(file("Packed Album", "01 One.mp3", null), file("Packed Album", "02 Two.mp3", null), file("Packed Album", "03 Three.mp3", null)),
            listOf("$LINK_HOST/PACKED1A"),
        ),
    )

    val downloads = listOf(
        Download("DL1", "06 Sixth Sense.ogg", "audio/ogg", "https://files.example.org/f/sixth", Fixtures.ogg),
        Download("DL2", "Holiday.mp4", "video/mp4", "https://files.example.org/f/holiday", Fixtures.aac),
        // The album's first song again, as the link generated when it was played before.
        Download("DL3", "01 - Opening.mp3", "audio/mpeg", "$LINK_HOST/ALBUM1A", Fixtures.mp3),
        // No telling name: only the type says it is audio.
        Download("DL4", "recording", "audio/x-wav", "https://files.example.org/f/recording", Fixtures.wav),
        // A band whose name starts with a number is not a track number.
        Download("DL5", "7 Lamps - Evening.opus", "audio/ogg", "https://files.example.org/f/evening", Fixtures.opus),
    )

    init {
        server.createContext("/") { exchange -> runCatching { handle(exchange) }.onFailure { runCatching { json(exchange, 500, """{"error":"internal_error","error_code":-1}""") } } }
        server.start()
    }

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath
        val method = exchange.requestMethod
        val query = fields(exchange.requestURI.rawQuery.orEmpty())
        val form = if (method == "POST") fields(String(exchange.requestBody.readAllBytes())).also { forms += it } else emptyMap()
        requests += "$method $path"

        when {
            // The files themselves: plain download addresses, as Real-Debrid's are.
            path.startsWith("/files/") -> {
                val name = URLDecoder.decode(path.removePrefix("/files/"), "UTF-8")
                val source = torrents.flatMap { it.files }.firstOrNull { it.path.substringAfterLast('/') == name }?.source
                    ?: downloads.firstOrNull { it.filename == name }?.source
                    ?: return json(exchange, 404, "{}")
                send(exchange, 200, "application/force-download", Files.readAllBytes(source))
            }

            // ------------------------------------------------ sign-in for open-source apps
            path == "/oauth/v2/device/code" -> {
                if (query["client_id"] != RealDebridAdapter.OPEN_SOURCE_CLIENT_ID || query["new_credentials"] != "yes") return json(exchange, 400, """{"error":"invalid_client","error_code":1}""")
                json(exchange, 200, """{"device_code":"$DEVICE_CODE","user_code":"$USER_CODE","interval":5,"expires_in":1800,"verification_url":"https:\/\/real-debrid.com\/device"}""")
            }
            path == "/oauth/v2/device/credentials" -> when {
                query["client_id"] != RealDebridAdapter.OPEN_SOURCE_CLIENT_ID || query["code"] != DEVICE_CODE -> json(exchange, 400, """{"error":"invalid_request","error_code":1}""")
                !approved -> json(exchange, 403, """{"error":"authorization_pending","error_code":-1}""")
                else -> json(exchange, 200, """{"client_id":"$CLIENT_ID","client_secret":"$CLIENT_SECRET"}""")
            }
            path == "/oauth/v2/token" && method == "POST" -> {
                val knownCode = form["code"] == DEVICE_CODE && approved || form["code"] != null && form["code"] == refreshToken
                when {
                    form["grant_type"] != "http://oauth.net/grant_type/device/1.0" -> json(exchange, 400, """{"error":"unsupported_grant_type","error_code":1}""")
                    revoked || form["client_id"] != CLIENT_ID || form["client_secret"] != CLIENT_SECRET || !knownCode -> json(exchange, 403, """{"error":"invalid_grant","error_code":9}""")
                    else -> {
                        val number = issued.incrementAndGet()
                        accessToken = "ACCESS-$number-SECRET"
                        refreshToken = "REFRESH-$number-SECRET"
                        json(exchange, 200, """{"access_token":"$accessToken","expires_in":$tokenLifetimeSeconds,"token_type":"Bearer","refresh_token":"$refreshToken"}""")
                    }
                }
            }

            // ------------------------------------------------ REST
            path.startsWith("/rest/1.0/") -> {
                val bearer = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")
                when {
                    revoked || bearer == null || (bearer != accessToken && bearer != PRIVATE_TOKEN) -> return json(exchange, 401, """{"error":"bad_token","error_code":8}""")
                    rateLimited.getAndUpdate { if (it > 0) it - 1 else 0 } > 0 -> return json(exchange, 429, """{"error":"too_many_requests","error_code":34}""")
                    locked -> return json(exchange, 403, """{"error":"permission_denied","error_code":9}""")
                }
                rest(exchange, method, path.removePrefix("/rest/1.0"), query, form)
            }
            else -> json(exchange, 404, """{"error":"unknown_ressource","error_code":3}""")
        }
    }

    private fun rest(exchange: HttpExchange, method: String, path: String, query: Map<String, String>, form: Map<String, String>) {
        val page = query["page"]?.toIntOrNull() ?: 1
        val limit = query["limit"]?.toIntOrNull() ?: 100
        when {
            method == "GET" && path == "/user" -> json(
                exchange, 200,
                """{"id":4242,"username":"$USERNAME","email":"listener@example.org","points":10,"locale":"en","avatar":"https:\/\/example.org\/a.png","type":"premium","premium":864000,"expiration":"2027-01-01T00:00:00.000Z"}""",
            )
            method == "GET" && path == "/torrents" -> {
                val list = torrents.filter { it.id !in deletedTorrents }.drop((page - 1) * limit).take(limit)
                if (list.isEmpty()) return send(exchange, 204, "application/json", ByteArray(0))
                exchange.responseHeaders.add("X-Total-Count", torrents.size.toString())
                json(exchange, 200, list.joinToString(",", "[", "]") { torrent(it, withFiles = false) })
            }
            method == "GET" && path.startsWith("/torrents/info/") -> {
                val found = torrents.firstOrNull { it.id == path.removePrefix("/torrents/info/") && it.id !in deletedTorrents }
                when {
                    infoBroken -> json(exchange, 503, """{"error":"service_unavailable","error_code":25}""")
                    found == null -> json(exchange, 404, """{"error":"unknown_ressource","error_code":7}""")
                    else -> json(exchange, 200, torrent(found, withFiles = true))
                }
            }
            method == "GET" && path == "/downloads" -> {
                val list = downloads.drop((page - 1) * limit).take(limit)
                if (list.isEmpty()) return send(exchange, 204, "application/json", ByteArray(0))
                exchange.responseHeaders.add("X-Total-Count", downloads.size.toString())
                json(exchange, 200, list.joinToString(",", "[", "]") { download(it.id, it.filename, it.mimeType, Files.size(it.source), it.link) })
            }
            method == "POST" && path == "/unrestrict/link" -> {
                val link = form["link"].orEmpty()
                val owner = torrents.firstOrNull { link in it.links && it.id !in deletedTorrents } ?: return json(exchange, 503, """{"error":"hoster_unsupported","error_code":16}""")
                val file = owner.files.filter { it.selected }[owner.links.indexOf(link)]
                val name = file.path.substringAfterLast('/')
                if (file.source == null) return json(exchange, 503, """{"error":"hoster_temporarily_unavailable","error_code":19}""")
                json(exchange, 200, download("GEN${link.takeLast(1)}", name, "audio/mpeg", file.bytes, link).dropLast(1) + ""","crc":1,"streamable":0}""")
            }
            // Anything else exists on the real service (adding, selecting, deleting) but is never called.
            else -> json(exchange, 404, """{"error":"unknown_ressource","error_code":3}""")
        }
    }

    private fun torrent(torrent: Torrent, withFiles: Boolean): String {
        val files = if (withFiles) {
            ""","original_filename":"${torrent.filename}","original_bytes":${torrent.files.sumOf { it.bytes }},"files":[${
                torrent.files.mapIndexed { index, file -> """{"id":${index + 1},"path":"${file.path}","bytes":${file.bytes},"selected":${if (file.selected) 1 else 0}}""" }.joinToString(",")
            }]"""
        } else {
            ""
        }
        val progress = if (torrent.status == "downloaded") 100 else 40
        return """{"id":"${torrent.id}","filename":"${torrent.filename}","hash":"${"0".repeat(40)}","bytes":${torrent.files.filter { it.selected }.sumOf { it.bytes }},"host":"real-debrid.com","split":2000,"progress":$progress,"status":"${torrent.status}","added":"2026-01-02T03:04:05.000Z","links":[${torrent.links.joinToString(",") { "\"${it.replace("/", "\\/")}\"" }}]${if (torrent.status == "downloaded") ""","ended":"2026-01-02T04:04:05.000Z"""" else ""","speed":1000,"seeders":3"""}$files}"""
    }

    private fun download(id: String, filename: String, mimeType: String, size: Long, link: String): String =
        """{"id":"$id","filename":"$filename","mimeType":"$mimeType","filesize":$size,"link":"${link.replace("/", "\\/")}","host":"real-debrid.com","chunks":16,"download":"${"$origin/files/${filename.replace(" ", "%20")}".replace("/", "\\/")}","generated":"2026-01-03T00:00:00.000Z"}"""

    private fun fields(encoded: String): Map<String, String> = encoded.split('&').filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

    private fun json(exchange: HttpExchange, status: Int, body: String) = send(exchange, status, "application/json", body.toByteArray())

    private fun send(exchange: HttpExchange, status: Int, type: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
            return
        }
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    companion object {
        const val USERNAME = "listener"
        const val DEVICE_CODE = "DEVICECODE0123456789SECRET"
        const val USER_CODE = "ABCDEF0123456"

        /** The credentials Real-Debrid binds to the user; with the tokens, they must stay in the credential store. */
        const val CLIENT_ID = "BOUNDCLIENTID"
        const val CLIENT_SECRET = "bound-client-secret-PRIVATE"

        /** The private API token of the account page, for the other way to sign in. */
        const val PRIVATE_TOKEN = "PRIVATEAPITOKEN0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"

        /** What the links of a torrent's files look like; they are only ever sent back to Real-Debrid. */
        const val LINK_HOST = "https://real-debrid.com/d"

        /** The only methods Music may call: all of them read, except the one that turns an own file into an address. */
        val READ_ONLY = setOf(
            "GET /oauth/v2/device/code", "GET /oauth/v2/device/credentials", "POST /oauth/v2/token",
            "GET /rest/1.0/user", "GET /rest/1.0/torrents", "GET /rest/1.0/downloads", "POST /rest/1.0/unrestrict/link",
        )
    }
}
