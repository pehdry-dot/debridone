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
 * A music addon as Eclipse Music and BeatBoss define one, for the tests: an HTTP server that
 * answers `/manifest.json`, `/search`, `/stream/{id}`, `/album/{id}`, `/artist/{id}`,
 * `/playlist/{id}`, `/catalog/{id}` and `/lyrics`, under a path that starts with a private token
 * (the way addons sign their users in). The audio is DebridOne's own test recordings.
 */
class StandInAddon : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Every request path with its query, without the token. */
    val requests = CopyOnWriteArrayList<String>()

    val origin: String get() = "http://127.0.0.1:${server.address.port}"

    /** What a user pastes into DebridOne. */
    val manifestAddress: String get() = "$origin/$TOKEN/manifest.json"

    /** How often `/stream/{id}` answers "still preparing" before it gives the address. */
    val preparing = AtomicInteger(0)

    /** Makes `/search` and `/stream` answer like an addon that serves the Eclipse app only. */
    @Volatile
    var eclipseOnly = false

    /** Makes `/stream` say the stream is encrypted. */
    @Volatile
    var encrypted = false

    /** Makes `/stream` answer with a rate-limit notice. */
    @Volatile
    var rateLimited = false

    /** Replaces the manifest (for manifests DebridOne must refuse, or an addon that changed). */
    @Volatile
    var manifestOverride: String? = null

    class Song(val id: String, val title: String, val file: Path, val declaredFormat: String, val seconds: Int)

    /** The declared formats are deliberately not all true: `s2` says mp3 and is FLAC. */
    val songs = listOf(
        Song("s1", "Opening", Fixtures.mp3, "mp3", 2),
        Song("s2", "Second Light", Fixtures.flac, "mp3", 2),
        Song("s3", "Third Wave", Fixtures.aac, "m4a", 2),
        Song("s4", "Sixth Sense", Fixtures.ogg, "ogg", 2),
    )

    init {
        server.createContext("/") { exchange -> runCatching { handle(exchange) }.onFailure { runCatching { send(exchange, 500, "text/plain", "error".toByteArray()) } } }
        server.start()
    }

    override fun close() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val rawPath = exchange.requestURI.rawPath
        if (!rawPath.startsWith("/$TOKEN/")) return json(exchange, 401, """{"error":"unknown token"}""")
        val path = rawPath.removePrefix("/$TOKEN")
        val parameters = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        requests += path + exchange.requestURI.rawQuery?.let { "?$it" }.orEmpty()
        val base = "$origin/$TOKEN"
        when {
            path == "/manifest.json" -> json(exchange, 200, manifestOverride ?: MANIFEST)
            path.startsWith("/audio/") -> {
                val song = songs.firstOrNull { it.id == path.removePrefix("/audio/").substringBefore('.') } ?: return json(exchange, 404, "{}")
                // The content type is as wrong as the declared format: only the bytes tell the truth.
                send(exchange, 200, "application/octet-stream", Files.readAllBytes(song.file))
            }
            eclipseOnly && path != "/lyrics" -> json(exchange, 403, """{"error":"forbidden"}""")
            path == "/search" -> {
                val query = parameters["q"].orEmpty().lowercase()
                val found = songs.filter { query.isBlank() || it.title.lowercase().contains(query) || query == "test" }
                json(
                    exchange, 200,
                    """{"tracks":[${found.joinToString(",") { track(it) }}],
                       "albums":[{"id":"al1","name":"First Album","artistName":"Test Artist","image":"$base/audio/cover.jpg","year":2021,"numberOfTracks":4}],
                       "artists":[{"id":"ar1","name":"Test Artist","image":"$base/audio/artist.jpg","genres":["Test"]}],
                       "playlists":[{"id":"pl1","title":"Editor's Picks","creator":"The Editor","trackCount":2}]}""",
                )
            }
            path.startsWith("/stream/") -> {
                val song = songs.firstOrNull { it.id == path.removePrefix("/stream/") }
                when {
                    rateLimited -> json(exchange, 429, """{"notice":{"level":"warning","title":"Rate limited","message":"Too many new songs this hour. They play again in about 5 minutes."}}""")
                    song == null -> json(exchange, 404, """{"notice":{"level":"error","title":"Could not prepare this song","message":"It is not available from the source."}}""")
                    preparing.getAndUpdate { if (it > 0) it - 1 else 0 } > 0 -> {
                        exchange.responseHeaders.add("Retry-After", "1")
                        json(exchange, 503, """{"notice":{"id":"prep-${song.id}","title":"Preparing","message":"Finding a copy of this song."}}""")
                    }
                    else -> json(
                        exchange, 200,
                        """{"url":"$base/audio/${song.id}.bin","format":"${song.declaredFormat}","quality":"test","expiresAt":${System.currentTimeMillis() / 1000 + 300}${if (encrypted) ""","encrypted":true""" else ""}}""",
                    )
                }
            }
            path == "/album/al1" -> json(
                exchange, 200,
                """{"id":"al1","title":"First Album","artist":"Test Artist","artworkURL":"$base/audio/cover.jpg","year":"2021","songs":[${songs.joinToString(",") { track(it, withAlbum = false) }}]}""",
            )
            path == "/artist/ar1" -> json(
                exchange, 200,
                """{"id":"ar1","name":"Test Artist","bio":"Plays test tones.","topTracks":[${songs.take(2).joinToString(",") { track(it) }}],
                   "albums":[{"id":"al1","title":"First Album","artist":"Test Artist","trackCount":4,"year":"2021"}]}""",
            )
            path == "/playlist/pl1" -> json(exchange, 200, """{"id":"pl1","title":"Editor's Picks","creator":"The Editor","tracks":[${songs.drop(2).joinToString(",") { track(it) }}]}""")
            path == "/catalog/top" -> json(
                exchange, 200,
                """{"items":[${songs.joinToString(",") { """{"id":"${it.id}","type":"track","title":"${it.title}","artist":"Test Artist","durationMs":${it.seconds * 1000},"isrc":"QZTST2100001"}""" }}]}""",
            )
            path == "/catalog/new" -> json(exchange, 200, """{"items":[{"id":"al1","type":"album","title":"First Album","artist":"Test Artist","year":2021}]}""")
            path == "/lyrics" -> if (parameters["title"] == "Opening") json(exchange, 200, """{"lyrics":"[00:00.50] A test tone begins\n[00:01.50] and ends"}""") else json(exchange, 404, "{}")
            else -> json(exchange, 404, """{"error":"not found"}""")
        }
    }

    /** A search result. `s3` carries its own address, as the format allows; the others are resolved through `/stream`. */
    private fun track(song: Song, withAlbum: Boolean = true): String {
        val direct = if (song.id == "s3") ""","streamURL":"$origin/$TOKEN/audio/${song.id}.bin"""" else ""
        val album = if (withAlbum) ""","album":"First Album"""" else ""
        return """{"id":"${song.id}","title":"${song.title}","artistName":"Test Artist"$album,"duration":${song.seconds},"albumCover":"$origin/$TOKEN/audio/cover.jpg","format":"${song.declaredFormat}"$direct}"""
    }

    private fun json(exchange: HttpExchange, status: Int, body: String) = send(exchange, status, "application/json", body.toByteArray())

    private fun send(exchange: HttpExchange, status: Int, type: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", type)
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    companion object {
        /** The private part of the addon's address. It must never reach the database, a log or a message. */
        const val TOKEN = "tok-9f3a-PRIVATE"

        const val MANIFEST = """{
          "id": "org.debridone.test-addon",
          "name": "Test Tones",
          "version": "1.2.0",
          "description": "Serves DebridOne's own test recordings.",
          "icon": "https://example.org/icon.png",
          "contentType": "music",
          "types": ["track", "album", "artist", "playlist"],
          "resources": ["search", "stream", "catalog", "settings", "lyrics", "notifications", "library", "isrc"],
          "catalogs": [
            {"id": "top", "type": "track", "name": "Top Tones"},
            {"id": "new", "type": "album", "name": "New Releases"}
          ],
          "settings": [
            {"key": "quality", "type": "select", "label": "Audio quality", "default": "high",
             "options": [{"value": "high", "label": "High"}, {"value": "low", "label": "Low"}]},
            {"key": "preferOpus", "type": "toggle", "label": "Prefer Opus", "default": true},
            {"key": "region", "type": "text", "label": "Region code", "default": "US", "maxLength": 2},
            {"key": "gain", "type": "number", "label": "Gain", "default": 0, "min": -6, "max": 6}
          ]
        }"""
    }
}
