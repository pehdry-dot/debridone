package com.debridone.cloud.testing

import com.sun.net.httpserver.HttpExchange
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-Debrid's API as its documentation (api.real-debrid.com) describes it, with what was seen
 * on the real service where the documentation is silent (the links of torrents are left out of
 * listings asked with a limit above 100). It is a stand-in, not the service: it proves DebridOne
 * speaks the documented format. Every request is recorded.
 */
class StandInRealDebrid(samples: Path? = null) : StandInServer() {
    /** The form fields of every POST, in order. */
    val forms = CopyOnWriteArrayList<Map<String, String>>()

    /** The user has typed the code on the verification page and allowed the app. */
    @Volatile
    var approved = false

    /** How long the access tokens it hands out are valid. */
    @Volatile
    var tokenLifetimeSeconds = 3_600

    /** The user withdrew the app's rights: no token is accepted or renewed any more. */
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

    /** The torrent that was still downloading has finished. */
    @Volatile
    var busyFinished = false

    @Volatile
    var accessToken: String? = null
        private set

    @Volatile
    var refreshToken: String? = null
        private set

    private val issuedTokens = AtomicInteger(0)

    class TorrentFile(val path: String, val selected: Boolean, val file: StandInFile?)
    class Torrent(val id: String, val filename: String, val status: String, val files: List<TorrentFile>, val links: List<String>)
    class Generated(val id: String, val filename: String, val mimeType: String, val link: String, val file: StandInFile)

    private fun entry(folder: String, name: String, samples: Path?, selected: Boolean = true, served: Boolean = true) =
        TorrentFile("/$folder/$name", selected, if (served) StandInFile("$folder/$name", samples = samples) else null)

    private val album = "Test Artist - First Album (2021) [FLAC]"
    private val show = "Some.Show.S01.1080p"
    private val comics = "Comics Pack"

    private val stored = listOf(
        Torrent(
            "ALBUM1", album, "downloaded",
            listOf(
                entry(album, "01 - Opening.mp3", samples), entry(album, "02 - Second Light.flac", samples), entry(album, "cover.jpg", samples),
                entry(album, "info.nfo", samples, selected = false, served = false), entry(album, "03 - Third Wave.m4a", samples),
            ),
            listOf("$LINK_HOST/ALBUM1A", "$LINK_HOST/ALBUM1B", "$LINK_HOST/ALBUM1C", "$LINK_HOST/ALBUM1D"),
        ),
        Torrent("SHOW1", show, "downloaded", listOf(entry(show, "Some.Show.S01E01.mkv", samples), entry(show, "Some.Show.S01E02.mkv", samples)), listOf("$LINK_HOST/SHOW1A", "$LINK_HOST/SHOW1B")),
        // A single file at the top of the torrent.
        Torrent("FILM1", "A.Holiday.Film.2020.1080p.mkv", "downloaded", listOf(TorrentFile("/A.Holiday.Film.2020.1080p.mkv", true, StandInFile("A.Holiday.Film.2020.1080p.mkv", samples = samples))), listOf("$LINK_HOST/FILM1A")),
        Torrent("COMICS1", comics, "downloaded", listOf(entry(comics, "Vol 01.cbz", samples), entry(comics, "Vol 02.cbz", samples), entry(comics, "notes.txt", samples)), listOf("$LINK_HOST/COMICS1A", "$LINK_HOST/COMICS1B", "$LINK_HOST/COMICS1C")),
        // Still downloading: no links yet.
        Torrent("BUSY1", "Other Artist - Second Album", "downloading", listOf(entry("Other Artist - Second Album", "01 Elsewhere.mp3", samples)), emptyList()),
        // Finished, but packed into one archive: three files, one link.
        Torrent("PACKED1", "Packed Album", "downloaded", listOf(entry("Packed Album", "01 One.mp3", samples), entry("Packed Album", "02 Two.mp3", samples), entry("Packed Album", "03 Three.mp3", samples)), listOf("$LINK_HOST/PACKED1A")),
        Torrent("BROKEN1", "Broken Transfer", "error", emptyList(), emptyList()),
    )

    val torrents: List<Torrent>
        get() = stored.filter { it.id !in deletedTorrents }.map { if (it.id == "BUSY1" && busyFinished) Torrent(it.id, it.filename, "downloaded", it.files, listOf("$LINK_HOST/BUSY1A")) else it }

    val deletedTorrents = CopyOnWriteArrayList<String>()
    val deletedDownloads = CopyOnWriteArrayList<String>()

    private val storedDownloads = listOf(
        // The first episode again, as the link generated when it was played before.
        Generated("DL1", "Some.Show.S01E01.mkv", "video/x-matroska", "$LINK_HOST/SHOW1A", StandInFile("$show/Some.Show.S01E01.mkv", samples = samples)),
        Generated("DL2", "Loose Song.ogg", "audio/ogg", "https://files.example.org/f/loose", StandInFile("Loose Song.ogg", samples = samples)),
        Generated("DL3", "tool.pkg", "application/octet-stream", "https://files.example.org/f/tool", StandInFile("tool.pkg", samples = samples)),
    )

    private val unrestricted = CopyOnWriteArrayList<Generated>()

    val downloads: List<Generated> get() = (unrestricted.reversed() + storedDownloads).filter { it.id !in deletedDownloads }

    override fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath
        val method = exchange.requestMethod
        val query = fields(exchange.requestURI.rawQuery.orEmpty())
        val form = if (method == "POST") fields(String(exchange.requestBody.readAllBytes())).also { forms += it } else emptyMap()
        when {
            path == "/oauth/v2/device/code" -> {
                if (query["client_id"] != CLIENT_ID_OPEN_SOURCE || query["new_credentials"] != "yes") return json(exchange, 400, """{"error":"invalid_client","error_code":1}""")
                json(exchange, 200, """{"device_code":"$DEVICE_CODE","user_code":"$USER_CODE","interval":5,"expires_in":1800,"verification_url":"https:\/\/real-debrid.com\/device"}""")
            }
            path == "/oauth/v2/device/credentials" -> when {
                query["client_id"] != CLIENT_ID_OPEN_SOURCE || query["code"] != DEVICE_CODE -> json(exchange, 400, """{"error":"invalid_request","error_code":1}""")
                !approved -> json(exchange, 403, """{"error":"authorization_pending","error_code":-1}""")
                else -> json(exchange, 200, """{"client_id":"$CLIENT_ID","client_secret":"$CLIENT_SECRET"}""")
            }
            path == "/oauth/v2/token" && method == "POST" -> {
                val knownCode = form["code"] == DEVICE_CODE && approved || form["code"] != null && form["code"] == refreshToken
                when {
                    form["grant_type"] != "http://oauth.net/grant_type/device/1.0" -> json(exchange, 400, """{"error":"unsupported_grant_type","error_code":1}""")
                    revoked || form["client_id"] != CLIENT_ID || form["client_secret"] != CLIENT_SECRET || !knownCode -> json(exchange, 403, """{"error":"invalid_grant","error_code":9}""")
                    else -> {
                        val number = issuedTokens.incrementAndGet()
                        accessToken = "ACCESS-$number-SECRET"
                        refreshToken = "REFRESH-$number-SECRET"
                        json(exchange, 200, """{"access_token":"$accessToken","expires_in":$tokenLifetimeSeconds,"token_type":"Bearer","refresh_token":"$refreshToken"}""")
                    }
                }
            }
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
                val list = torrents.drop((page - 1) * limit).take(limit)
                if (list.isEmpty()) return send(exchange, 204, "application/json", ByteArray(0))
                exchange.responseHeaders.add("X-Total-Count", torrents.size.toString())
                // As on the real service: a listing asked with a limit above 100 comes without links.
                json(exchange, 200, list.joinToString(",", "[", "]") { torrent(it, withFiles = false, withLinks = limit <= 100) })
            }
            method == "GET" && path.startsWith("/torrents/info/") -> {
                val found = torrents.firstOrNull { it.id == path.removePrefix("/torrents/info/") }
                when {
                    infoBroken -> json(exchange, 503, """{"error":"service_unavailable","error_code":25}""")
                    found == null -> json(exchange, 404, """{"error":"unknown_ressource","error_code":7}""")
                    else -> json(exchange, 200, torrent(found, withFiles = true, withLinks = true))
                }
            }
            method == "DELETE" && path.startsWith("/torrents/delete/") -> {
                val torrentId = path.removePrefix("/torrents/delete/")
                if (torrents.none { it.id == torrentId }) return json(exchange, 404, """{"error":"unknown_ressource","error_code":7}""")
                deletedTorrents += torrentId
                send(exchange, 204, "application/json", ByteArray(0))
            }
            method == "GET" && path == "/downloads" -> {
                val list = downloads.drop((page - 1) * limit).take(limit)
                if (list.isEmpty()) return send(exchange, 204, "application/json", ByteArray(0))
                exchange.responseHeaders.add("X-Total-Count", downloads.size.toString())
                json(exchange, 200, list.joinToString(",", "[", "]") { download(it) + "}" })
            }
            method == "DELETE" && path.startsWith("/downloads/delete/") -> {
                val entryId = path.removePrefix("/downloads/delete/")
                if (downloads.none { it.id == entryId }) return json(exchange, 404, """{"error":"unknown_ressource","error_code":7}""")
                deletedDownloads += entryId
                send(exchange, 204, "application/json", ByteArray(0))
            }
            method == "POST" && path == "/unrestrict/link" -> {
                val link = form["link"].orEmpty()
                val owner = torrents.firstOrNull { link in it.links } ?: return json(exchange, 503, """{"error":"hoster_unsupported","error_code":16}""")
                val packed = owner.files.count { it.selected } != owner.links.size
                val served = if (packed) StandInFile("${owner.filename}.rar") else owner.files.filter { it.selected }[owner.links.indexOf(link)].file
                    ?: return json(exchange, 503, """{"error":"hoster_temporarily_unavailable","error_code":19}""")
                val generated = Generated("GEN${unrestricted.size + 1}", served.name, "application/octet-stream", link, served)
                unrestricted += generated
                json(exchange, 200, download(generated) + ""","crc":1,"streamable":0}""")
            }
            // Everything else exists on the real service (adding, selecting) but is never called.
            else -> json(exchange, 404, """{"error":"unknown_ressource","error_code":3}""")
        }
    }

    private fun torrent(torrent: Torrent, withFiles: Boolean, withLinks: Boolean): String {
        val files = if (withFiles) {
            ""","original_filename":${quote(torrent.filename)},"files":[${
                torrent.files.mapIndexed { index, file -> """{"id":${index + 1},"path":${quote(file.path)},"bytes":${file.file?.size ?: 1234},"selected":${if (file.selected) 1 else 0}}""" }.joinToString(",")
            }]"""
        } else {
            ""
        }
        val done = torrent.status == "downloaded"
        return """{"id":"${torrent.id}","filename":${quote(torrent.filename)},"hash":"${"0".repeat(40)}","bytes":${torrent.files.filter { it.selected }.sumOf { it.file?.size ?: 1234 }},"host":"real-debrid.com","split":2000,"progress":${if (done) 100 else if (torrent.status == "error") 0 else 40},"status":"${torrent.status}","added":"2026-01-02T03:04:05.000Z","links":[${if (withLinks) torrent.links.joinToString(",") { quote(it) } else ""}]${if (done) ""","ended":"2026-01-02T04:04:05.000Z"""" else ""}$files}"""
    }

    /** Without the closing brace, so the unrestrict answer can add its own fields. */
    private fun download(entry: Generated): String =
        """{"id":"${entry.id}","filename":${quote(entry.filename)},"mimeType":"${entry.mimeType}","filesize":${entry.file.size},"link":${quote(entry.link)},"host":"real-debrid.com","chunks":16,"download":${quote(addressOf(entry.file))},"generated":"2026-01-03T00:00:00.000Z""""

    companion object {
        const val CLIENT_ID_OPEN_SOURCE = "X245A4XAIBGVM"
        const val USERNAME = "listener"
        const val DEVICE_CODE = "DEVICECODE0123456789SECRET"
        const val USER_CODE = "ABCDEF0123456"
        const val CLIENT_ID = "BOUNDCLIENTID"
        const val CLIENT_SECRET = "bound-client-secret-PRIVATE"
        const val PRIVATE_TOKEN = "PRIVATEAPITOKEN0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        const val LINK_HOST = "https://real-debrid.com/d"
    }
}
