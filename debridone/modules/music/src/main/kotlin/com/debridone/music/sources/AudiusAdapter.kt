package com.debridone.music.sources

import java.io.IOException
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Audius: an open catalogue where artists publish their own tracks, reached through its public API
 * (https://docs.audius.co/api). No account is needed.
 *
 * Only tracks Audius itself marks as freely streamable are played. A track that is sold or
 * reserved for supporters is listed as not playable; nothing here works around that.
 */
class AudiusAdapter(private val apiBase: String = DEFAULT_API) : MusicCatalogAdapter {
    override val id = ID
    override val displayName = "Audius"
    override val description = "Open music catalogue of independent artists. Search, discover and play; no account needed."
    override val fields = emptyList<SourceField>()
    override val kind = IntegrationKind.PLAYBACK_SOURCE
    override val defaultBaseUrl: String get() = apiBase
    override val syncsLibrary = false
    override val capabilities = CatalogCapabilities(
        searchTracks = true, searchArtists = true, searchAlbums = true, searchPlaylists = true, featured = true, genres = true,
    )

    override fun connect(draft: SourceDraft): SourceSession {
        val base = draft.baseUrl.ifBlank { apiBase }
        call(base, "/tracks/trending", listOf("limit" to "1"))
        return SourceSession(emptyMap(), "", "Audius", null)
    }

    override fun test(connection: SourceConnection, secret: String) {
        call(base(connection), "/tracks/trending", listOf("limit" to "1"))
    }

    /** The catalogue is far too large to copy; it is searched instead. */
    override fun listTracks(connection: SourceConnection, secret: String, onPage: (List<RemoteTrack>) -> Unit) = Unit

    /** Audius answers this address with a redirect to the node that holds the audio. */
    override fun streamUrl(connection: SourceConnection, secret: String, remoteId: String): String =
        "${base(connection)}/tracks/${MusicHttp.encode(remoteId)}/stream?" + MusicHttp.query(listOf("app_name" to APP_NAME))

    override fun artworkUrl(connection: SourceConnection, secret: String, artworkKey: String): String = artworkKey

    override fun search(connection: SourceConnection, secret: String, query: String, limit: Int): CatalogResults {
        if (query.isBlank()) return CatalogResults()
        val base = base(connection)
        val size = limit.coerceIn(1, 50).toString()
        val tracks = list(base, "/tracks/search", listOf("query" to query, "limit" to size)).mapNotNull(::toTrack)
        val artists = runCatching { list(base, "/users/search", listOf("query" to query, "limit" to size)).mapNotNull(::toArtist) }.getOrDefault(emptyList())
        val collections = runCatching { list(base, "/playlists/search", listOf("query" to query, "limit" to size)) }.getOrDefault(emptyList())
        return CatalogResults(
            tracks = tracks,
            artists = artists,
            albums = collections.filter { it["is_album"]?.toString() == "true" }.mapNotNull(::toAlbum),
            playlists = collections.filter { it["is_album"]?.toString() != "true" }.mapNotNull(::toPlaylist),
        )
    }

    override fun featured(connection: SourceConnection, secret: String, genre: String?, limit: Int): List<RemoteTrack> {
        val parameters = buildList {
            add("limit" to limit.coerceIn(1, 100).toString())
            add("time" to "week")
            if (!genre.isNullOrBlank()) add("genre" to genre)
        }
        return list(base(connection), "/tracks/trending", parameters).mapNotNull(::toTrack)
    }

    override fun genres(connection: SourceConnection, secret: String): List<String> = GENRES

    override fun artistTracks(connection: SourceConnection, secret: String, artistId: String, limit: Int): List<RemoteTrack> =
        list(base(connection), "/users/${MusicHttp.encode(artistId)}/tracks", listOf("limit" to limit.coerceIn(1, 100).toString())).mapNotNull(::toTrack)

    override fun albumTracks(connection: SourceConnection, secret: String, albumId: String): List<RemoteTrack> =
        playlistTracks(connection, secret, albumId)

    override fun playlistTracks(connection: SourceConnection, secret: String, playlistId: String): List<RemoteTrack> =
        list(base(connection), "/playlists/${MusicHttp.encode(playlistId)}/tracks", emptyList()).mapNotNull(::toTrack)

    private fun base(connection: SourceConnection): String = connection.baseUrl?.takeIf { it.isNotBlank() } ?: apiBase

    private fun list(base: String, path: String, parameters: List<Pair<String, String>>): List<JsonObject> =
        call(base, path, parameters)["data"].array().mapNotNull { it.obj() }

    private fun call(base: String, path: String, parameters: List<Pair<String, String>>): JsonObject {
        val url = base.trimEnd('/') + path + "?" + MusicHttp.query(parameters + ("app_name" to APP_NAME))
        val response = try {
            MusicHttp.get(url, mapOf("Accept" to "application/json"), maxBytes = 24 * 1024 * 1024)
        } catch (e: HttpStatusException) {
            throw SourceException(if (e.status == 404) "Audius has nothing at that address." else "Audius answered with an error (${e.status}).", e)
        } catch (e: IOException) {
            throw SourceException("Audius could not be reached: ${e.message ?: e::class.simpleName}", e)
        }
        return runCatching { Json.parseToJsonElement(response.text()).obj() }.getOrNull()?.takeIf { it.containsKey("data") }
            ?: throw SourceException("That address does not answer like the Audius API.")
    }

    private fun toTrack(node: JsonObject): RemoteTrack? {
        val id = node.string("id") ?: return null
        if (node["is_delete"]?.toString() == "true" || node["is_available"]?.toString() == "false") return null
        val user = node["user"].obj()
        val access = node["access"].obj()
        val playable = node["is_streamable"]?.toString() != "false" &&
            node["is_stream_gated"]?.toString() != "true" &&
            access?.get("stream")?.toString() != "false"
        val (artwork, alternates) = artwork(node["artwork"].obj())
        return RemoteTrack(
            remoteId = id,
            title = node.string("title") ?: "Untitled",
            artist = user?.string("name") ?: user?.string("handle"),
            album = node["album_backlink"].obj()?.string("playlist_name"),
            albumArtist = null,
            genre = node.string("genre"),
            year = node.string("release_date")?.take(4)?.toIntOrNull(),
            trackNo = null,
            discNo = null,
            durationMs = (node.long("duration") ?: 0) * 1000,
            format = "MP3",
            bitrateKbps = null,
            sizeBytes = 0,
            artworkKey = null,
            artworkUrl = artwork,
            artworkAlternates = alternates,
            playable = playable,
        )
    }

    private fun toArtist(node: JsonObject): RemoteArtist? {
        val id = node.string("id") ?: return null
        if (node["is_deactivated"]?.toString() == "true") return null
        val (picture, alternates) = artwork(node["profile_picture"].obj())
        return RemoteArtist(
            remoteId = id,
            name = node.string("name") ?: node.string("handle") ?: return null,
            artworkUrl = picture,
            artworkAlternates = alternates,
            trackCount = node.int("track_count"),
            albumCount = node.int("album_count"),
            followerCount = node.int("follower_count"),
            verified = node["is_verified"]?.toString() == "true",
            description = node.string("bio"),
        )
    }

    private fun toAlbum(node: JsonObject): RemoteAlbum? {
        val id = node.string("id") ?: return null
        val (cover, alternates) = artwork(node["artwork"].obj())
        return RemoteAlbum(
            remoteId = id,
            title = node.string("playlist_name") ?: return null,
            artist = node["user"].obj()?.string("name"),
            year = node.string("release_date")?.take(4)?.toIntOrNull(),
            trackCount = node.int("track_count"),
            artworkUrl = cover,
            artworkAlternates = alternates,
        )
    }

    private fun toPlaylist(node: JsonObject): RemotePlaylist? {
        val id = node.string("id") ?: return null
        val (cover, alternates) = artwork(node["artwork"].obj())
        return RemotePlaylist(
            remoteId = id,
            name = node.string("playlist_name") ?: return null,
            owner = node["user"].obj()?.string("name"),
            trackCount = node.int("track_count"),
            description = node.string("description"),
            artworkUrl = cover,
            artworkAlternates = alternates,
        )
    }

    /**
     * The 480-pixel cover and the same picture on the other nodes Audius names for it. Audius
     * stores every image on several independent nodes; one of them being down is ordinary.
     */
    private fun artwork(node: JsonObject?): Pair<String?, List<String>> {
        val primary = node?.string("480x480") ?: node?.string("1000x1000") ?: node?.string("150x150") ?: return null to emptyList()
        val path = runCatching { URI(primary).rawPath }.getOrNull() ?: return primary to emptyList()
        val mirrors = node?.get("mirrors").array().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trimEnd('/') }
        return primary to (mirrors + CONTENT_FALLBACK).map { it + path }.filter { it != primary }.distinct()
    }

    companion object {
        const val ID = "audius"
        const val DEFAULT_API = "https://api.audius.co/v1"

        /** Audius asks every client to name itself. */
        private const val APP_NAME = "DebridOne"
        private const val CONTENT_FALLBACK = "https://creatornode.audius.co"

        /** The genres Audius files tracks under, as its API accepts them. */
        val GENRES = listOf(
            "Electronic", "Hip-Hop/Rap", "Pop", "Rock", "Alternative", "R&B/Soul", "Lo-Fi", "House", "Techno", "Trap",
            "Ambient", "Jazz", "Latin", "Reggae", "Metal", "Folk", "Classical", "Country", "Experimental", "Punk",
            "Funk", "Acoustic", "World", "Soundtrack", "Drum & Bass", "Dubstep", "Deep House", "Downtempo", "Disco", "Blues",
        )
    }
}
