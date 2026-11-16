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
        server.createContext("/audius/") { exchange -> guard(exchange) { audius(exchange) } }
        server.createContext("/catalog/") { exchange -> guard(exchange) { catalog(exchange, token = null) } }
        server.createContext("/private-catalog/") { exchange -> guard(exchange) { catalog(exchange, token = CATALOG_TOKEN) } }
        server.createContext("/plugins/") { exchange -> guard(exchange) { plugins(exchange) } }
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
            "search3" -> {
                val text = query["query"].orEmpty().lowercase()
                val songs = albums.flatMap { album -> album.songs.filter { it.title.lowercase().contains(text) }.map { subsonicSong(album, it) } }
                val foundAlbums = albums.filter { it.name.lowercase().contains(text) }.joinToString(",") {
                    """{"id":${quote(it.id)},"name":${quote(it.name)},"artist":${quote(it.artist)},"songCount":${it.songs.size},"year":${it.year},"coverArt":${quote(it.id)}}"""
                }
                val artists = albums.map { it.artist }.distinct().filter { it.lowercase().contains(text) }.joinToString(",") {
                    """{"id":${quote("ar-" + it.replace(' ', '-').lowercase())},"name":${quote(it)},"albumCount":1}"""
                }
                ok(""","searchResult3":{"song":[${songs.joinToString(",")}],"album":[$foundAlbums],"artist":[$artists]}""")
            }
            "getRandomSongs" -> ok(""","randomSongs":{"song":[${albums.flatMap { album -> album.songs.map { subsonicSong(album, it) } }.take(query["size"]?.toIntOrNull() ?: 10).joinToString(",")}]}""")
            "getSongsByGenre" -> ok(""","songsByGenre":{"song":[${albums.filter { it.genre == query["genre"] }.flatMap { album -> album.songs.map { subsonicSong(album, it) } }.joinToString(",")}]}""")
            "getGenres" -> ok(""","genres":{"genre":[${albums.joinToString(",") { """{"value":${quote(it.genre)},"songCount":${it.songs.size},"albumCount":1}""" }}]}""")
            "getArtist" -> {
                val own = albums.filter { "ar-" + it.artist.replace(' ', '-').lowercase() == query["id"] }
                ok(""","artist":{"id":${quote(query["id"].orEmpty())},"name":${quote(own.firstOrNull()?.artist ?: "?")},"album":[${own.joinToString(",") { """{"id":${quote(it.id)},"name":${quote(it.name)}}""" }}]}""")
            }
            "getPlaylists" -> ok(""","playlists":{"playlist":[{"id":"pl-1","name":"Road Trip","owner":"$SUBSONIC_USER","songCount":2,"comment":"For the car","coverArt":"al-1"}]}""")
            "getPlaylist" -> if (query["id"] == "pl-1") {
                val entries = listOf(albums[1] to albums[1].songs[0], albums[0] to albums[0].songs[1]).joinToString(",") { (album, song) -> subsonicSong(album, song) }
                ok(""","playlist":{"id":"pl-1","name":"Road Trip","songCount":2,"entry":[$entries]}""")
            } else {
                json(exchange, 200, """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":70,"message":"Playlist not found"}}}""")
            }
            "getCoverArt" -> {
                val cover = albums.firstOrNull { it.id == query["id"] }?.cover
                if (cover == null) send(exchange, 404, "text/plain", "no cover".toByteArray()) else send(exchange, 200, "image/jpeg", cover)
            }
            else -> send(exchange, 404, "text/plain", "unknown method".toByteArray())
        }
    }

    private fun subsonicSong(album: AlbumEntry, song: Song): String =
        """{"id":${quote(song.id)},"title":${quote(song.title)},"album":${quote(album.name)},"artist":${quote(album.artist)},"track":${song.track},"discNumber":1,""" +
            """"year":${album.year},"genre":${quote(album.genre)},"coverArt":${quote(album.id)},"size":${Files.size(song.file)},"suffix":${quote(song.suffix)},"duration":2,"bitRate":64}"""

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
                val term = query["searchTerm"]?.lowercase()
                when (query["IncludeItemTypes"]) {
                    "MusicAlbum" -> {
                        val found = albums.filter { term == null || it.name.lowercase().contains(term) }.joinToString(",") {
                            """{"Id":${quote(it.id)},"Name":${quote(it.name)},"AlbumArtist":${quote(it.artist)},"ProductionYear":${it.year},"ChildCount":${it.songs.size},"ImageTags":{"Primary":"tag"}}"""
                        }
                        json(exchange, 200, """{"Items":[$found],"TotalRecordCount":${albums.size}}""")
                    }
                    "Playlist" -> json(
                        exchange, 200,
                        if (term == null || "evening".contains(term)) """{"Items":[{"Id":"jpl-1","Name":"Evening","ChildCount":2,"Overview":"Slow ones","ImageTags":{}}],"TotalRecordCount":1}"""
                        else """{"Items":[],"TotalRecordCount":0}""",
                    )
                    else -> {
                        val filtered = songs
                            .filter { (_, song) -> term == null || song.title.lowercase().contains(term) }
                            .filter { (album, _) -> query["ParentId"] == null || album.id == query["ParentId"] }
                            .filter { (album, _) -> query["Genres"] == null || album.genre == query["Genres"] }
                            .filter { (album, _) -> query["ArtistIds"] == null || "jar-" + album.artist.replace(' ', '-').lowercase() == query["ArtistIds"] }
                        val items = filtered.drop(start).take(limit).joinToString(",") { (album, song) -> jellyfinSong(album, song) }
                        json(exchange, 200, """{"Items":[$items],"TotalRecordCount":${filtered.size},"StartIndex":$start}""")
                    }
                }
            }
            path == "/Artists" -> if (requireAuth()) {
                val term = query["searchTerm"].orEmpty().lowercase()
                val found = albums.map { it.artist }.distinct().filter { it.lowercase().contains(term) }.joinToString(",") {
                    """{"Id":${quote("jar-" + it.replace(' ', '-').lowercase())},"Name":${quote(it)},"ImageTags":{}}"""
                }
                json(exchange, 200, """{"Items":[$found]}""")
            }
            path == "/MusicGenres" -> if (requireAuth()) {
                json(exchange, 200, """{"Items":[${albums.map { it.genre }.distinct().joinToString(",") { """{"Name":${quote(it)},"Id":${quote("g-$it")}}""" }}]}""")
            }
            path == "/Playlists/jpl-1/Items" -> if (requireAuth()) {
                json(exchange, 200, """{"Items":[${jellyfinSong(albums[0], albums[0].songs[2])},${jellyfinSong(albums[1], albums[1].songs[0])}],"TotalRecordCount":2}""")
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

    private fun jellyfinSong(album: AlbumEntry, song: Song): String =
        """{"Id":${quote(song.id)},"Type":"Audio","Name":${quote(song.title)},"Album":${quote(album.name)},"AlbumId":${quote(album.id)},"AlbumArtist":${quote(album.artist)},""" +
            """"Artists":[${quote(album.artist)}],"IndexNumber":${song.track},"ParentIndexNumber":1,"ProductionYear":${album.year},"RunTimeTicks":20000000,""" +
            """"Genres":[${quote(album.genre)}],"ImageTags":{},"AlbumPrimaryImageTag":"tag","MediaSources":[{"Container":${quote(song.suffix)},"Bitrate":64000,"Size":${Files.size(song.file)}}]}"""

    // ---------------------------------------------------------------- radio

    private fun radio(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/radio/live.mp3" -> send(exchange, 200, "audio/mpeg", Files.readAllBytes(Fixtures.mp3))
            "/radio/second.mp3" -> send(exchange, 200, "audio/mpeg", Files.readAllBytes(Fixtures.elsewhere))
            "/radio/second.m3u" -> send(exchange, 200, "audio/x-mpegurl", "#EXTM3U\n#EXTINF:-1,Second FM\n$baseUrl/radio/second.mp3\n".toByteArray())
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
        coverArtProbeRelease = "rel-album",
    )

    /** Set to make every metadata request fail, as when a provider is down. */
    @Volatile var metadataDown = false

    private fun metadata(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix("/metadata")
        val query = parameters(exchange)
        metadataRequests += path + "?" + exchange.requestURI.query.orEmpty()
        metadataRequestTimes += System.currentTimeMillis()
        if (metadataDown) {
            send(exchange, 503, "text/plain", "Service Unavailable".toByteArray())
            return
        }
        when {
            path == "/ws/2/artist" -> json(exchange, 200, if (query["query"].orEmpty().contains("Test Artist") || query["query"].orEmpty().contains("Queen")) {
                """{"created":"2026-01-01T00:00:00.000Z","count":2,"offset":0,"artists":[
                  {"id":"art-1","type":"Group","score":100,"name":"Test Artist","sort-name":"Artist, Test","country":"PT","disambiguation":"electronic duo",
                   "life-span":{"begin":"2015","ended":null}},
                  {"id":"art-9","type":"Person","score":61,"name":"Test Artist Jr.","sort-name":"Artist Jr., Test","area":{"name":"Brazil"},"life-span":{"begin":"1990-02-01"}}]}"""
            } else {
                """{"created":"2026-01-01T00:00:00.000Z","count":0,"offset":0,"artists":[]}"""
            })
            path == "/ws/2/release-group" && query["artist"] == "art-1" -> json(exchange, 200, """{"release-group-count":2,"release-groups":[
                  {"id":"rg-second","title":"Second Thoughts","primary-type":"EP","first-release-date":"2023-01-10","secondary-types":[]},
                  {"id":"rg-first","title":"First Album","primary-type":"Album","first-release-date":"2021-03-12","secondary-types":[]}]}""")
            path == "/ws/2/release-group" -> json(exchange, 200, if (query["query"].orEmpty().contains("First Album")) {
                """{"count":1,"release-groups":[{"id":"rg-first","score":100,"title":"First Album","primary-type":"Album","secondary-types":["Live"],"first-release-date":"2021-03-12",
                   "artist-credit":[{"name":"Test Artist","artist":{"id":"art-1","name":"Test Artist"}}]}]}"""
            } else {
                """{"count":0,"release-groups":[]}"""
            })
            path == "/ws/2/release" -> json(exchange, 200, if (query["query"].orEmpty().contains("First Album")) {
                """{"count":3,"releases":[
                  {"id":"rel-deluxe","score":100,"title":"First Album","status":"Official","date":"2022-06-01","country":"US","track-count":12,
                   "artist-credit":[{"name":"Test Artist"}],"release-group":{"id":"rg-first","primary-type":"Album"},"media":[{"format":"CD","track-count":12}]},
                  {"id":"rel-album","score":100,"title":"First Album","status":"Official","date":"2021-03-12","country":"PT","track-count":7,
                   "artist-credit":[{"name":"Test Artist"}],"release-group":{"id":"rg-first","primary-type":"Album"},"media":[{"format":"Digital Media","track-count":7}],
                   "label-info":[{"label":{"name":"Own Label"}}]},
                  {"id":"rel-other","score":70,"title":"First Album Remixed","status":"Bootleg","date":"2021","track-count":7,"artist-credit":[{"name":"Someone Else"}]}]}"""
            } else {
                """{"count":0,"releases":[]}"""
            })
            path == "/ws/2/release/rel-album" -> json(exchange, 200, """{"id":"rel-album","title":"First Album (Remastered)","status":"Official","date":"2021-03-12","country":"PT",
                  "artist-credit":[{"name":"Test Artist","artist":{"id":"art-1","name":"Test Artist"}}],
                  "release-group":{"id":"rg-first","title":"First Album","primary-type":"Album","first-release-date":"2020-11-01"},
                  "media":[{"position":1,"format":"Digital Media","track-count":7,"tracks":[
                    {"id":"t1","number":"1","position":1,"title":"Opening Song","length":2000,"recording":{"id":"rec-1","title":"Opening Song","length":2000}},
                    {"id":"t2","number":"2","position":2,"title":"Second Light","length":2000,"recording":{"id":"rec-studio","title":"Second Light","length":2000},
                     "artist-credit":[{"name":"Test Artist","joinphrase":" feat. "},{"name":"A Guest"}]},
                    {"id":"t3","number":"3","position":3,"title":"Third Wave","length":2100,"recording":{"id":"rec-3","title":"Third Wave"}},
                    {"id":"t4","number":"4","position":4,"title":"Fourth Wall","length":2000,"recording":{"id":"rec-4","title":"Fourth Wall"}},
                    {"id":"t5","number":"5","position":5,"title":"Fifth Element","length":2000,"recording":{"id":"rec-5","title":"Fifth Element"}},
                    {"id":"t6","number":"6","position":6,"title":"Sixth Sense","length":2000,"recording":{"id":"rec-6","title":"Sixth Sense"}},
                    {"id":"t7","number":"7","position":7,"title":"A Song That Is Not In The Folder","length":300000,"recording":{"id":"rec-7","title":"A Song That Is Not In The Folder"}}]}]}""")
            path == "/ws/2/release-group/rg-first" -> json(exchange, 200, """{"id":"rg-first","title":"First Album","primary-type":"Album","first-release-date":"2021-03-12",
                  "artist-credit":[{"name":"Test Artist","artist":{"id":"art-1","name":"Test Artist"}}],
                  "releases":[{"id":"rel-deluxe","title":"First Album","date":"2022-06-01","country":"US","status":"Official"},
                              {"id":"rel-album","title":"First Album","date":"2021-03-12","country":"PT","status":"Official"}]}""")
            path == "/ws/2/recording/rec-studio" -> json(exchange, 200, """{"id":"rec-studio","title":"Second Light (2024 mix)","length":2000,
                  "artist-credit":[{"name":"Test Artist","joinphrase":" feat. "},{"name":"A Guest"}],
                  "releases":[{"id":"rel-comp","title":"Best Of","date":"2024","artist-credit":[{"name":"Various Artists"}],"media":[{"position":1,"track-count":20,"track-offset":4}]},
                              {"id":"rel-album","title":"First Album","date":"2021-03-12","artist-credit":[{"name":"Test Artist"}],"media":[{"position":1,"track-count":7,"track-offset":1}]}]}""")
            path.startsWith("/ws/2/release/") || path.startsWith("/ws/2/release-group/") || path.startsWith("/ws/2/recording/") ->
                json(exchange, 404, """{"error":"Not Found","help":"For usage, please see: https://musicbrainz.org/development/mmd"}""")
            path == "/caa/release/rel-album" || path == "/caa/release-group/rg-first" -> json(exchange, 200, """{"release":"https://musicbrainz.org/release/rel-album","images":[
                  {"id":111,"types":["Back"],"front":false,"back":true,"comment":"","approved":true,"image":"$baseUrl/metadata/caa/img/back.jpg",
                   "thumbnails":{"250":"$baseUrl/metadata/caa/img/back-250.jpg","500":"$baseUrl/metadata/caa/img/back.jpg","small":"$baseUrl/metadata/caa/img/back-250.jpg"}},
                  {"id":110,"types":["Front"],"front":true,"back":false,"comment":"digital booklet","approved":true,"image":"$baseUrl/metadata/caa/img/front.jpg",
                   "thumbnails":{"250":"$baseUrl/metadata/caa/img/front-250.jpg","500":"$baseUrl/metadata/caa/img/front.jpg","1200":"$baseUrl/metadata/caa/img/front.jpg"}},
                  {"id":112,"types":["Booklet"],"front":false,"back":false,"comment":"page 2","approved":true,"image":"$baseUrl/metadata/caa/img/gone.jpg","thumbnails":{}}]}""")
            path.startsWith("/caa/img/front") -> send(exchange, 200, "image/jpeg", Files.readAllBytes(Fixtures.secondAlbum.resolve("cover.jpg")))
            path.startsWith("/caa/img/back") -> send(exchange, 200, "image/jpeg", firstCover)
            path.startsWith("/caa/img/") -> send(exchange, 404, "text/plain", "not found".toByteArray())
            path == "/lrclib/search" && query["q"] != null -> json(exchange, 200, if (query["q"].orEmpty().lowercase().let { it.contains("opening") || it.contains("yesterday") }) {
                """[{"id":40,"trackName":"Opening","artistName":"Somebody Else","albumName":"Covers","duration":201,"instrumental":false,"plainLyrics":"A cover","syncedLyrics":null},
                    {"id":41,"trackName":"Opening","artistName":"Test Artist","albumName":"First Album","duration":2,"instrumental":false,"plainLyrics":"First line\nSecond line","syncedLyrics":"[00:00.20] First line\n[00:01.10] Second line"},
                    {"id":42,"trackName":"Opening (karaoke)","artistName":"Test Artist","duration":2,"instrumental":true,"plainLyrics":null,"syncedLyrics":null}]"""
            } else {
                "[]"
            })
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

    // ---------------------------------------------------------------- Audius

    val audiusUrl: String get() = "$baseUrl/audius/v1"

    /** Every Audius request received, as `path?query`. */
    val audiusRequests = CopyOnWriteArrayList<String>()

    private fun audiusTrack(id: String, title: String, artist: String, genre: String, gated: Boolean = false): String =
        """{"id":${quote(id)},"track_id":1,"title":${quote(title)},"genre":${quote(genre)},"duration":2,"release_date":"2024-05-01T10:00:00Z",
           "is_streamable":true,"is_stream_gated":$gated,"is_delete":false,"is_available":true,"access":{"stream":${!gated},"download":false},
           "artwork":{"150x150":"https://dead-node.invalid/content/cid-$id/150x150.jpg","480x480":"https://dead-node.invalid/content/cid-$id/480x480.jpg",
                      "mirrors":["$baseUrl/audius/mirror"]},
           "user":{"id":"u-1","name":${quote(artist)},"handle":"testartist","is_verified":true,"track_count":3,"follower_count":1200,
                   "profile_picture":{"480x480":"$baseUrl/audius/mirror/content/cid-user/480x480.jpg","mirrors":[]}}}"""

    private fun audius(exchange: HttpExchange) {
        val path = exchange.requestURI.path.removePrefix("/audius")
        val query = parameters(exchange)
        audiusRequests += path + "?" + exchange.requestURI.query.orEmpty()
        val trending = listOf(
            audiusTrack("AUD1", "Open Road", "Indie Artist", "Electronic"),
            audiusTrack("AUD2", "Night Shift", "Indie Artist", "House"),
            audiusTrack("AUD3", "Supporters Only", "Indie Artist", "Electronic", gated = true),
        )
        when {
            path.startsWith("/v1/") && query["app_name"].isNullOrBlank() -> json(exchange, 400, """{"error":"app_name is required"}""")
            path == "/v1/tracks/trending" -> {
                val genre = query["genre"]
                val list = trending.filter { genre == null || it.contains("\"genre\":${quote(genre)}") }.take(query["limit"]?.toIntOrNull() ?: 10)
                json(exchange, 200, """{"data":[${list.joinToString(",")}]}""")
            }
            path == "/v1/tracks/search" -> json(exchange, 200, """{"data":[${trending.filter { it.lowercase().contains(query["query"].orEmpty().lowercase()) }.joinToString(",")}]}""")
            path == "/v1/users/search" -> json(exchange, 200, """{"data":[{"id":"u-1","name":"Indie Artist","handle":"indie","is_verified":true,"track_count":3,"album_count":1,"follower_count":1200,"bio":"Makes music",
                 "profile_picture":{"480x480":"$baseUrl/audius/mirror/content/cid-user/480x480.jpg","mirrors":[]}}]}""")
            path == "/v1/playlists/search" -> json(exchange, 200, """{"data":[
                 {"id":"PL1","playlist_name":"Late Night Mix","is_album":false,"track_count":2,"description":"After hours","user":{"name":"Curator"},"artwork":null},
                 {"id":"ALB1","playlist_name":"Open Roads","is_album":true,"track_count":2,"release_date":"2024-05-01","user":{"name":"Indie Artist"},"artwork":null}]}""")
            path == "/v1/users/u-1/tracks" -> json(exchange, 200, """{"data":[${trending.joinToString(",")}]}""")
            path == "/v1/playlists/PL1/tracks" || path == "/v1/playlists/ALB1/tracks" -> json(exchange, 200, """{"data":[${trending.take(2).joinToString(",")}]}""")
            path.startsWith("/v1/tracks/") && path.endsWith("/stream") -> {
                // Like the real API: a redirect to the node that holds the audio.
                exchange.responseHeaders.add("Location", "$baseUrl/audius/node/${path.removePrefix("/v1/tracks/").removeSuffix("/stream")}.mp3")
                exchange.sendResponseHeaders(302, -1)
            }
            path.startsWith("/node/") -> send(exchange, 200, "audio/mpeg", Files.readAllBytes(Fixtures.mp3))
            path.startsWith("/mirror/content/") -> send(exchange, 200, "image/jpeg", firstCover)
            else -> send(exchange, 404, "text/plain", "not found".toByteArray())
        }
    }

    // ---------------------------------------------------------------- DebridOne Music Catalog protocol

    val catalogUrl: String get() = "$baseUrl/catalog"
    val privateCatalogUrl: String get() = "$baseUrl/private-catalog"
    val catalogRequests = CopyOnWriteArrayList<String>()

    private fun catalog(exchange: HttpExchange, token: String?) {
        val prefix = if (token == null) "/catalog" else "/private-catalog"
        val path = exchange.requestURI.path.removePrefix(prefix)
        val query = parameters(exchange)
        catalogRequests += prefix + path + "?" + exchange.requestURI.query.orEmpty()
        if (token != null) {
            val authorised = exchange.requestHeaders.getFirst("Authorization") == "Bearer $token" || (path.endsWith("/stream") && query["token"] == token)
            if (!authorised) {
                rejected += exchange.requestURI.path
                send(exchange, 401, "text/plain", "Unauthorized".toByteArray())
                return
            }
        }
        fun track(id: String, title: String, genre: String) =
            """{"id":${quote(id)},"title":${quote(title)},"artist":"Catalogue Band","album":"Catalogue Album","genre":${quote(genre)},"year":2022,"trackNumber":1,
               "durationMs":2000,"format":"mp3","artworkUrl":"$baseUrl$prefix/art.jpg"}"""
        val all = listOf(track("c-1", "River", "Folk"), track("c-2", "Mountain", "Rock"))
        when {
            path == "/manifest.json" -> json(exchange, 200, """{"debridoneMusicPlugin":1,"id":"org.example.catalog","name":"Example Catalogue","version":"2.1.0",
                 "description":"A small catalogue.","adapter":"debridone-catalog","baseUrl":"$baseUrl$prefix","auth":"${if (token == null) "none" else "token"}"}""")
            path == "/search" -> json(exchange, 200, """{"tracks":[${all.filter { it.lowercase().contains(query["q"].orEmpty().lowercase()) }.joinToString(",")}],
                 "artists":[{"id":"ca-1","name":"Catalogue Band","trackCount":2}],
                 "albums":[{"id":"cal-1","title":"Catalogue Album","artist":"Catalogue Band","year":2022,"trackCount":2}],
                 "playlists":[{"id":"cpl-1","name":"Staff Picks","trackCount":2}]}""")
            path == "/featured" -> json(exchange, 200, """{"tracks":[${all.filter { query["genre"] == null || it.contains("\"genre\":${quote(query["genre"].orEmpty())}") }.joinToString(",")},
                 {"title":"Direct One","artist":"Catalogue Band","streamUrl":"$baseUrl/radio/live.mp3"},
                 {"id":"c-bad","title":"Not Allowed","playable":false},
                 {"id":"c-evil","title":"Bad Address","streamUrl":"file:///etc/passwd","artworkUrl":"javascript:alert(1)"}]}""")
            path == "/genres" -> json(exchange, 200, """{"genres":["Folk","Rock"]}""")
            path == "/artists/ca-1/tracks" || path == "/albums/cal-1/tracks" || path == "/playlists/cpl-1/tracks" -> json(exchange, 200, """{"tracks":[${all.joinToString(",")}]}""")
            path == "/playlists" -> json(exchange, 200, """{"playlists":[{"id":"cpl-1","name":"Staff Picks","trackCount":2,"description":"Chosen by hand"}]}""")
            path.startsWith("/tracks/") && path.endsWith("/stream") -> send(exchange, 200, "audio/mpeg", Files.readAllBytes(Fixtures.mp3))
            path == "/art.jpg" -> send(exchange, 200, "image/jpeg", firstCover)
            else -> send(exchange, 404, "text/plain", "not found".toByteArray())
        }
    }

    // ---------------------------------------------------------------- plugin manifests

    private fun plugins(exchange: HttpExchange) {
        when (exchange.requestURI.path) {
            "/plugins/radio.json" -> json(exchange, 200, """{"debridoneMusicPlugin":1,"id":"org.example.radio","name":"Example Radio","version":"1.0.0",
                 "description":"Two stations and a dead one.","adapter":"streams","streams":[
                   {"name":"Station One","url":"$baseUrl/radio/live.mp3","genre":"Jazz"},
                   {"name":"Station Two","url":"$baseUrl/radio/second.m3u"},
                   {"name":"Dead Air","url":"$baseUrl/radio/page.html"}]}""")
            "/plugins/navidrome.json" -> json(exchange, 200, """{"debridoneMusicPlugin":1,"id":"org.example.family-server","name":"Family Server","adapter":"subsonic","baseUrl":"$subsonicUrl"}""")
            "/plugins/stremio.json" -> json(exchange, 200, """{"id":"org.stremio.example","version":"1.0.0","name":"Some Addon","resources":["stream"],"types":["movie"],"catalogs":[]}""")
            "/plugins/future.json" -> json(exchange, 200, """{"debridoneMusicPlugin":2,"id":"org.example.future","name":"Future","adapter":"streams"}""")
            "/plugins/unknown-adapter.json" -> json(exchange, 200, """{"debridoneMusicPlugin":1,"id":"org.example.spot","name":"Spot","adapter":"spotify"}""")
            "/plugins/page.html" -> send(exchange, 200, "text/html", "<!doctype html><html><body>Plugin page</body></html>".toByteArray())
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
        const val CATALOG_TOKEN = "cat-9d1e"
    }
}
