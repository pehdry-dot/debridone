package com.debridone.music.sources

import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.array(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

/**
 * Navidrome and other servers that speak the Subsonic API (Airsonic, Gonic, Ampache's Subsonic
 * mode...). Uses token authentication: the password itself is never sent, only a salted hash.
 */
class SubsonicAdapter : MusicCatalogAdapter {
    override val id = ID
    override val displayName = "Navidrome / OpenSubsonic"
    override val description = "Your own Navidrome, Airsonic, Gonic or other server that speaks the Subsonic or OpenSubsonic API."
    override val capabilities = CatalogCapabilities(
        searchTracks = true, searchArtists = true, searchAlbums = true, featured = true, genres = true, ownPlaylists = true,
    )
    override val fields = listOf(
        SourceField("baseUrl", "Server address", placeholder = "https://music.example.com"),
        SourceField("username", "User name"),
        SourceField("secret", "Password", secret = true),
    )

    private val random = SecureRandom()

    override fun connect(draft: SourceDraft): SourceSession {
        val baseUrl = MusicHttp.normalizeBaseUrl(draft.baseUrl)
        if (draft.username.isBlank() || draft.secret.isEmpty()) throw SourceException("Enter your user name and password.")
        val response = call(baseUrl, draft.username.trim(), draft.secret, "ping")
        return SourceSession(emptyMap(), draft.secret, response.string("type") ?: "Subsonic", response.string("serverVersion") ?: response.string("version"))
    }

    override fun test(connection: SourceConnection, secret: String) {
        call(requireNotNull(connection.baseUrl), connection.username.orEmpty(), secret, "ping")
    }

    override fun listTracks(connection: SourceConnection, secret: String, onPage: (List<RemoteTrack>) -> Unit) {
        val baseUrl = requireNotNull(connection.baseUrl)
        val user = connection.username.orEmpty()
        var offset = 0
        while (true) {
            val albums = call(baseUrl, user, secret, "getAlbumList2", listOf("type" to "alphabeticalByName", "size" to PAGE.toString(), "offset" to offset.toString()))
                .get("albumList2").obj()?.get("album").array()
            if (albums.isEmpty()) break
            for (album in albums) {
                val albumId = album.obj()?.string("id") ?: continue
                val detail = call(baseUrl, user, secret, "getAlbum", listOf("id" to albumId)).get("album").obj() ?: continue
                val albumArtist = detail.string("artist")
                val songs = detail["song"].array().mapNotNull { song -> song.obj()?.let { toTrack(it, detail) } }
                if (songs.isNotEmpty()) onPage(songs)
            }
            if (albums.size < PAGE) break
            offset += albums.size
        }
    }

    private fun toTrack(node: JsonObject, album: JsonObject? = null): RemoteTrack? {
        val remoteId = node.string("id") ?: return null
        return RemoteTrack(
            remoteId = remoteId,
            title = node.string("title") ?: "Untitled",
            artist = node.string("artist"),
            album = node.string("album") ?: album?.string("name"),
            albumArtist = album?.string("artist") ?: node.string("displayAlbumArtist"),
            genre = node.string("genre") ?: album?.string("genre"),
            year = node.int("year") ?: album?.int("year"),
            trackNo = node.int("track"),
            discNo = node.int("discNumber"),
            durationMs = (node.long("duration") ?: 0) * 1000,
            format = node.string("suffix"),
            bitrateKbps = node.int("bitRate"),
            sizeBytes = node.long("size") ?: 0,
            artworkKey = node.string("coverArt") ?: album?.string("coverArt"),
        )
    }

    // ---------------------------------------------------------------- searching and browsing

    private fun call(connection: SourceConnection, secret: String, method: String, parameters: List<Pair<String, String>> = emptyList()): JsonObject =
        call(requireNotNull(connection.baseUrl), connection.username.orEmpty(), secret, method, parameters)

    override fun search(connection: SourceConnection, secret: String, query: String, limit: Int): CatalogResults {
        if (query.isBlank()) return CatalogResults()
        val size = limit.coerceIn(1, 100).toString()
        val result = call(connection, secret, "search3", listOf("query" to query, "songCount" to size, "albumCount" to size, "artistCount" to size))["searchResult3"].obj()
            ?: return CatalogResults()
        return CatalogResults(
            tracks = result["song"].array().mapNotNull { it.obj()?.let { node -> toTrack(node) } },
            artists = result["artist"].array().mapNotNull { it.obj() }.mapNotNull { node ->
                RemoteArtist(
                    remoteId = node.string("id") ?: return@mapNotNull null,
                    name = node.string("name") ?: return@mapNotNull null,
                    artworkKey = node.string("coverArt"),
                    albumCount = node.int("albumCount"),
                )
            },
            albums = result["album"].array().mapNotNull { it.obj()?.let(::toAlbum) },
        )
    }

    private fun toAlbum(node: JsonObject): RemoteAlbum? = RemoteAlbum(
        remoteId = node.string("id") ?: return null,
        title = node.string("name") ?: node.string("title") ?: return null,
        artist = node.string("artist"),
        year = node.int("year"),
        trackCount = node.int("songCount"),
        durationMs = node.long("duration")?.times(1000),
        artworkKey = node.string("coverArt"),
    )

    override fun featured(connection: SourceConnection, secret: String, genre: String?, limit: Int): List<RemoteTrack> {
        val size = limit.coerceIn(1, 200).toString()
        return if (genre.isNullOrBlank()) {
            call(connection, secret, "getRandomSongs", listOf("size" to size))["randomSongs"].obj()?.get("song").array()
        } else {
            call(connection, secret, "getSongsByGenre", listOf("genre" to genre, "count" to size))["songsByGenre"].obj()?.get("song").array()
        }.mapNotNull { it.obj()?.let { node -> toTrack(node) } }
    }

    override fun genres(connection: SourceConnection, secret: String): List<String> =
        call(connection, secret, "getGenres")["genres"].obj()?.get("genre").array()
            .mapNotNull { it.obj() }
            .sortedByDescending { it.int("songCount") ?: 0 }
            .mapNotNull { it.string("value") }

    override fun artistTracks(connection: SourceConnection, secret: String, artistId: String, limit: Int): List<RemoteTrack> {
        val albums = call(connection, secret, "getArtist", listOf("id" to artistId))["artist"].obj()?.get("album").array().mapNotNull { it.obj()?.string("id") }
        val tracks = ArrayList<RemoteTrack>()
        for (albumId in albums) {
            if (tracks.size >= limit) break
            tracks += albumTracks(connection, secret, albumId)
        }
        return tracks.take(limit)
    }

    override fun albumTracks(connection: SourceConnection, secret: String, albumId: String): List<RemoteTrack> {
        val album = call(connection, secret, "getAlbum", listOf("id" to albumId))["album"].obj() ?: return emptyList()
        return album["song"].array().mapNotNull { it.obj()?.let { node -> toTrack(node, album) } }
    }

    override fun ownPlaylists(connection: SourceConnection, secret: String): List<RemotePlaylist> =
        call(connection, secret, "getPlaylists")["playlists"].obj()?.get("playlist").array().mapNotNull { it.obj() }.mapNotNull { node ->
            RemotePlaylist(
                remoteId = node.string("id") ?: return@mapNotNull null,
                name = node.string("name") ?: return@mapNotNull null,
                owner = node.string("owner"),
                trackCount = node.int("songCount"),
                description = node.string("comment"),
                artworkKey = node.string("coverArt"),
            )
        }

    override fun playlistTracks(connection: SourceConnection, secret: String, playlistId: String): List<RemoteTrack> =
        call(connection, secret, "getPlaylist", listOf("id" to playlistId))["playlist"].obj()?.get("entry").array()
            .mapNotNull { it.obj()?.let { node -> toTrack(node) } }

    override fun streamUrl(connection: SourceConnection, secret: String, remoteId: String): String =
        // format=raw asks for the original file rather than a transcode.
        url(requireNotNull(connection.baseUrl), connection.username.orEmpty(), secret, "stream", listOf("id" to remoteId, "format" to "raw"))

    override fun artworkUrl(connection: SourceConnection, secret: String, artworkKey: String): String =
        url(requireNotNull(connection.baseUrl), connection.username.orEmpty(), secret, "getCoverArt", listOf("id" to artworkKey, "size" to "800"))

    private fun url(baseUrl: String, user: String, password: String, method: String, parameters: List<Pair<String, String>> = emptyList()): String {
        val salt = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val token = MessageDigest.getInstance("MD5").digest((password + salt).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val all = listOf("u" to user, "t" to token, "s" to salt, "v" to API_VERSION, "c" to CLIENT, "f" to "json") + parameters
        return "$baseUrl/rest/$method.view?" + MusicHttp.query(all)
    }

    /** Calls a Subsonic method and returns the `subsonic-response` object, or throws with the server's reason. */
    private fun call(baseUrl: String, user: String, password: String, method: String, parameters: List<Pair<String, String>> = emptyList()): JsonObject {
        val response = try {
            MusicHttp.get(url(baseUrl, user, password, method, parameters))
        } catch (e: HttpStatusException) {
            throw SourceException(if (e.status == 404) "No Subsonic-compatible server answers at that address." else "The server answered with an error (${e.status}).", e)
        } catch (e: IOException) {
            throw SourceException("The server could not be reached: ${e.message ?: e::class.simpleName}", e)
        }
        val root = runCatching { Json.parseToJsonElement(response.text()).obj()?.get("subsonic-response").obj() }.getOrNull()
            ?: throw SourceException("That address does not answer like a Subsonic-compatible server.")
        if (root.string("status") != "ok") {
            val error = root["error"].obj()
            val message = when (error?.int("code")) {
                40 -> "Wrong user name or password."
                41 -> "This server does not accept token sign-in for this account (LDAP users are not supported)."
                50 -> "This account is not allowed to do that on the server."
                else -> error?.string("message") ?: "The server refused the request."
            }
            throw SourceException(message)
        }
        return root
    }

    companion object {
        const val ID = "subsonic"
        private const val API_VERSION = "1.16.1"
        private const val CLIENT = "DebridOne"
        private const val PAGE = 200
    }
}
