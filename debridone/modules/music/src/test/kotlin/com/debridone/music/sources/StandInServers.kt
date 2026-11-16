package com.debridone.music.sources

import com.debridone.music.Fixtures
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Local stand-ins for a Subsonic/Navidrome server, a Jellyfin server and a radio station. They
 * speak just enough of each protocol for the adapters, serve the real fixture audio, and check
 * credentials the way the real servers do.
 */
class StandInServers : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
    val subsonicUrl: String get() = "$baseUrl/subsonic"
    val jellyfinUrl: String get() = "$baseUrl/jellyfin"

    /** Paths of requests that passed authentication, e.g. `/subsonic/rest/stream.view`. */
    val accepted = CopyOnWriteArrayList<String>()
    val rejected = CopyOnWriteArrayList<String>()

    class Song(val id: String, val file: Path, val title: String, val track: Int, val suffix: String)
    class AlbumEntry(val id: String, val name: String, val artist: String, val year: Int, val genre: String, val cover: ByteArray?, val songs: MutableList<Song>)

    private val firstCover: ByteArray = checkNotNull(com.debridone.music.library.AudioTagReader.read(Fixtures.flac).artwork).bytes
    val albums = mutableListOf(
        AlbumEntry("al-1", "First Album", "Test Artist", 2021, "Electronic", firstCover, mutableListOf(
            Song("s-1", Fixtures.mp3, "Opening", 1, "mp3"),
            Song("s-2", Fixtures.flac, "Second Light", 2, "flac"),
            Song("s-6", Fixtures.ogg, "Sixth Sense", 6, "ogg"),
        )),
        AlbumEntry("al-2", "Second Album", "Other Artist", 1999, "Jazz", Files.readAllBytes(Fixtures.secondAlbum.resolve("cover.jpg")), mutableListOf(
            Song("s-9", Fixtures.elsewhere, "Elsewhere", 1, "mp3"),
        )),
    )

    init {
        server.executor = Executors.newFixedThreadPool(4) { Thread(it, "stand-in-server").apply { isDaemon = true } }
        server.createContext("/subsonic/rest/") { exchange -> guard(exchange) { subsonic(exchange) } }
        server.createContext("/jellyfin/") { exchange -> guard(exchange) { jellyfin(exchange) } }
        server.createContext("/radio/") { exchange -> guard(exchange) { radio(exchange) } }
        server.createContext("/metadata/") { exchange -> guard(exchange) { metadata(exchange) } }
        server.start()
    }

    override fun close() = server.stop(0)

    private fun guard(exchange: HttpExchange, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            runCatching { send(exchange, 500, "text/plain", t.toString().toByteArray()) }
        } finally {
            exchange.close()
        }
    }

    private fun send(exchange: HttpExchange, status: Int, type: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
    }

    private fun json(exchange: HttpExchange, status: Int, body: String) = send(exchange, status, "application/json", body.toByteArray())

    private fun parameters(exchange: HttpExchange): Map<String, String> = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
        val (key, value) = (it + "=").split('=', limit = 3)
        URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }

    private fun contentType(suffix: String) = when (suffix) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ---------------------------------------------------------------- Subsonic

    private fun subsonic(exchange: HttpExchange) {
        val method = exchange.requestURI.path.substringAfterLast('/').removeSuffix(".view")
        val query = parameters(exchange)
        val expected = MessageDigest.getInstance("MD5").digest((SUBSONIC_PASSWORD + query["s"].orEmpty()).toByteArray()).joinToString("") { "%02x".format(it) }
        if (query["u"] != SUBSONIC_USER || query["t"] != expected || query["s"].isNullOrEmpty()) {
            rejected += exchange.requestURI.path
            json(exchange, 200, """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}""")
            return
        }
        accepted += exchange.requestURI.path
        fun ok(body: String) = json(exchange, 200, """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.53.0"$body}}""")
        when (method) {
            "ping" -> ok("")
            "getAlbumList2" -> {
                val offset = query["offset"]?.toIntOrNull() ?: 0
                val size = query["size"]?.toIntOrNull() ?: 10
                val page = albums.drop(offset).take(size).joinToString(",") { album ->
                    """{"id":${quote(album.id)},"name":${quote(album.name)},"artist":${quote(album.artist)},"songCount":${album.songs.size},"year":${album.year}}"""
                }
                ok(""","albumList2":{"album":[$page]}""")
            }
            "getAlbum" -> {
                val album = albums.firstOrNull { it.id == query["id"] }
                if (album == null) {
                    json(exchange, 200, """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"Album not found"}}}""")
                    return
                }
                val songs = album.songs.joinToString(",") { song ->
                    """{"id":${quote(song.id)},"title":${quote(song.title)},"album":${quote(album.name)},"artist":${quote(album.artist)},"track":${song.track},"discNumber":1,""" +
                        """"year":${album.year},"genre":${quote(album.genre)},"coverArt":${quote(album.id)},"size":${Files.size(song.file)},"suffix":${quote(song.suffix)},"duration":2,"bitRate":64}"""
                }
                ok(""","album":{"id":${quote(album.id)},"name":${quote(album.name)},"artist":${quote(album.artist)},"year":${album.year},"genre":${quote(album.genre)},"coverArt":${quote(album.id)},"song":[$songs]}""")
            }
            "stream" -> {
                val song = albums.flatMap { it.songs }.firstOrNull { it.id == query["id"] }
                if (song == null) send(exchange, 404, "text/plain", "no such song".toByteArray()) else send(exchange, 200, contentType(song.suffix), Files.readAllBytes(song.file))
            }
            "getCoverArt" -> {
                val cover = albums.firstOrNull { it.id == query["id"] }?.cover
                if (cover == null) send(exchange, 404, "text/plain", "no cover".toByteArray()) else send(exchange, 200, "image/jpeg", cover)
            }
            else -> send(exchange, 404, "text/plain", "unknown method".toByteArray())
        }
    }

    // ---------------------------------------------------------------- Jellyfin

    private fun jellyfin(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix("/jellyfin")
        val query = parameters(exchange)
        val header = exchange.requestHeaders.getFirst("Authorization").orEmpty()
        val authorised = header.contains("Token=\"$JELLYFIN_TOKEN\"") || query["api_key"] == JELLYFIN_TOKEN
        fun requireAuth(): Boolean {
            if (authorised) {
                accepted += exchange.requestURI.path
                return true
            }
            rejected += exchange.requestURI.path
            send(exchange, 401, "text/plain", "Unauthorized".toByteArray())
            return false
        }
        val songs = albums.flatMap { album -> album.songs.map { album to it } }
        when {
            path == "/System/Info/Public" -> json(exchange, 200, """{"ServerName":"Stand-in Jellyfin","Version":"10.9.0","Id":"server-1","ProductName":"Jellyfin Server"}""")
            path == "/Users/AuthenticateByName" && exchange.requestMethod == "POST" -> {
                val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                if (body.contains("\"Username\":\"$JELLYFIN_USER\"") && body.contains("\"Pw\":\"$JELLYFIN_PASSWORD\"") && header.contains("DeviceId=")) {
                    json(exchange, 200, """{"AccessToken":"$JELLYFIN_TOKEN","User":{"Id":"$JELLYFIN_USER_ID","Name":"$JELLYFIN_USER"}}""")
                } else {
                    rejected += path
                    send(exchange, 401, "text/plain", "Invalid username or password".toByteArray())
                }
            }
            path == "/Users/$JELLYFIN_USER_ID" -> if (requireAuth()) json(exchange, 200, """{"Id":"$JELLYFIN_USER_ID"}""")
            path == "/Users/$JELLYFIN_USER_ID/Items" -> if (requireAuth()) {
                val start = query["StartIndex"]?.toIntOrNull() ?: 0
                val limit = query["Limit"]?.toIntOrNull() ?: 100
                val items = songs.drop(start).take(limit).joinToString(",") { (album, song) ->
                    """{"Id":${quote(song.id)},"Name":${quote(song.title)},"Album":${quote(album.name)},"AlbumId":${quote(album.id)},"AlbumArtist":${quote(album.artist)},""" +
                        """"Artists":[${quote(album.artist)}],"IndexNumber":${song.track},"ParentIndexNumber":1,"ProductionYear":${album.year},"RunTimeTicks":20000000,""" +
                        """"Genres":[${quote(album.genre)}],"ImageTags":{},"AlbumPrimaryImageTag":"tag","MediaSources":[{"Container":${quote(song.suffix)},"Bitrate":64000,"Size":${Files.size(song.file)}}]}"""
                }
                json(exchange, 200, """{"Items":[$items],"TotalRecordCount":${songs.size},"StartIndex":$start}""")
            }
            path.startsWith("/Audio/") && path.endsWith("/stream") -> if (requireAuth()) {
                val song = songs.firstOrNull { it.second.id == path.removePrefix("/Audio/").removeSuffix("/stream") }?.second
                if (song == null || query["static"] != "true") send(exchange, 404, "text/plain", "not found".toByteArray()) else send(exchange, 200, contentType(song.suffix), Files.readAllBytes(song.file))
            }
            path.startsWith("/Items/") && path.endsWith("/Images/Primary") -> if (requireAuth()) {
                val cover = albums.firstOrNull { it.id == path.removePrefix("/Items/").removeSuffix("/Images/Primary") }?.cover
                if (cover == null) send(exchange, 404, "text/plain", "no image".toByteArray()) else send(exchange, 200, "image/jpeg", cover)
            }
            else -> send(exchange, 404, "text/plain", "not found".toByteArray())
        }
    }

    // ---------------------------------------------------------------- radio

    private fun radio(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/radio/live.mp3" -> send(exchange, 200, "audio/mpeg", Files.readAllBytes(Fixtures.mp3))
            "/radio/station.m3u" -> send(exchange, 200, "audio/x-mpegurl", "#EXTM3U\n#EXTINF:-1,Stand-in FM\n$baseUrl/radio/live.mp3\n".toByteArray())
            "/radio/station.pls" -> send(exchange, 200, "audio/x-scpls", "[playlist]\nNumberOfEntries=1\nFile1=$baseUrl/radio/live.mp3\nTitle1=Stand-in FM\n".toByteArray())
            "/radio/page.html" -> send(exchange, 200, "text/html; charset=utf-8", "<html><body>Listen live</body></html>".toByteArray())
            "/radio/empty.m3u" -> send(exchange, 200, "audio/x-mpegurl", "#EXTM3U\n".toByteArray())
            else -> send(exchange, 404, "text/plain", "not found".toByteArray())
        }
    }

    // ---------------------------------------------------------------- MusicBrainz, Cover Art Archive, LRCLIB

    /** Every metadata request received, as `path?query`. */
    val metadataRequests = CopyOnWriteArrayList<String>()
    val metadataRequestTimes = CopyOnWriteArrayList<Long>()

    val metadataEndpoints get() = com.debridone.music.metadata.MetadataEndpoints(
        musicBrainz = "$baseUrl/metadata/ws/2",
        coverArtArchive = "$baseUrl/metadata/caa",
        lrclib = "$baseUrl/metadata/lrclib",
    )

    private fun metadata(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix("/metadata")
        val query = parameters(exchange)
        metadataRequests += path + "?" + exchange.requestURI.query.orEmpty()
        metadataRequestTimes += System.currentTimeMillis()
        when {
            path == "/ws/2/recording" -> {
                val lucene = query["query"].orEmpty()
                if (!exchange.requestHeaders.getFirst("User-Agent").orEmpty().startsWith("DebridOne/")) {
                    send(exchange, 403, "text/plain", "A User-Agent is required".toByteArray())
                } else if (lucene.contains("Second Light")) {
                    // Shaped like a real MusicBrainz recording search answer.
                    json(exchange, 200, """{"created":"2026-01-01T00:00:00.000Z","count":2,"offset":0,"recordings":[
                      {"id":"rec-live","score":88,"title":"Second Light (live)","length":251000,
                       "artist-credit":[{"name":"Test Artist","artist":{"id":"art-1","name":"Test Artist"}}],
                       "releases":[{"id":"rel-live","title":"Live at the Hall","date":"2023-05-01","track-count":14,
                         "media":[{"position":1,"track-count":14,"track-offset":8,"track":[{"number":"9","title":"Second Light (live)"}]}]}]},
                      {"id":"rec-studio","score":100,"title":"Second Light","length":2000,
                       "artist-credit":[{"name":"Test Artist","joinphrase":" feat. ","artist":{"id":"art-1","name":"Test Artist"}},{"name":"A Guest","artist":{"id":"art-2","name":"A Guest"}}],
                       "releases":[
                         {"id":"rel-comp","title":"Best Of","date":"2024","track-count":20,"artist-credit":[{"name":"Various Artists"}],
                          "media":[{"position":1,"track-count":20,"track-offset":4,"track":[{"number":"5","title":"Second Light"}]}]},
                         {"id":"rel-album","title":"First Album","date":"2021-03-12","track-count":7,"artist-credit":[{"name":"Test Artist"}],
                          "media":[{"position":1,"track-count":7,"track-offset":1,"track":[{"number":"2","title":"Second Light"}]}]}]}
                    ]}""")
                } else {
                    json(exchange, 200, """{"created":"2026-01-01T00:00:00.000Z","count":0,"offset":0,"recordings":[]}""")
                }
            }
            path == "/caa/release/rel-album/front-500" -> send(exchange, 200, "image/jpeg", Files.readAllBytes(Fixtures.secondAlbum.resolve("cover.jpg")))
            path.startsWith("/caa/release/") -> send(exchange, 404, "text/plain", "No cover art found".toByteArray())
            path == "/lrclib/get" -> when {
                query["track_name"] == "Opening" && query["artist_name"] == "Test Artist" ->
                    json(exchange, 200, """{"id":1,"trackName":"Opening","artistName":"Test Artist","duration":2,"instrumental":false,
                      "plainLyrics":"First line\nSecond line","syncedLyrics":"[00:00.20] First line\n[00:01.10] Second line"}""")
                query["track_name"] == "Fifth Element" -> json(exchange, 200, """{"id":5,"trackName":"Fifth Element","artistName":"Test Artist","duration":2,"instrumental":true,"plainLyrics":null,"syncedLyrics":null}""")
                else -> json(exchange, 404, """{"code":404,"name":"TrackNotFound","message":"Failed to find specified track"}""")
            }
            path == "/lrclib/search" -> when (query["track_name"]) {
                "Third Wave" -> json(exchange, 200, """[
                  {"id":30,"trackName":"Third Wave","artistName":"Test Artist","duration":190,"instrumental":false,"plainLyrics":"The long version","syncedLyrics":null},
                  {"id":31,"trackName":"Third Wave","artistName":"Test Artist","duration":3,"instrumental":false,"plainLyrics":"The right version","syncedLyrics":null}]""")
                else -> json(exchange, 200, "[]")
            }
            else -> send(exchange, 404, "text/plain", "not found".toByteArray())
        }
    }

    companion object {
        const val SUBSONIC_USER = "listener"
        const val SUBSONIC_PASSWORD = "correct horse battery staple"
        const val JELLYFIN_USER = "jelly"
        const val JELLYFIN_PASSWORD = "wobble-wobble-7"
        const val JELLYFIN_TOKEN = "tok-4f9a2c7e"
        const val JELLYFIN_USER_ID = "user-77"
    }
}
