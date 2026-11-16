package com.debridone.music.sources

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A music catalogue served over HTTP by someone else's server, following DebridOne's own small
 * protocol ("DebridOne Music Catalog", version 1; see `debridone/docs/MUSIC_PLUGINS.md`).
 *
 * This is how an integration is added without changing DebridOne: the integration is a server that
 * answers these requests. No code from it runs inside DebridOne; only JSON is read and audio is
 * played from the addresses it gives.
 *
 * ```
 * GET {base}/search?q=&limit=          -> {"tracks":[],"artists":[],"albums":[],"playlists":[]}
 * GET {base}/featured?genre=&limit=    -> {"tracks":[]}
 * GET {base}/genres                    -> {"genres":["Rock", ...]}
 * GET {base}/artists/{id}/tracks       -> {"tracks":[]}
 * GET {base}/albums/{id}/tracks        -> {"tracks":[]}
 * GET {base}/playlists                 -> {"playlists":[]}
 * GET {base}/playlists/{id}/tracks     -> {"tracks":[]}
 * GET {base}/tracks/{id}/stream        -> the audio, or a redirect to it
 * ```
 *
 * With a token, requests carry `Authorization: Bearer <token>`; the stream address, which the
 * audio engine opens itself, carries it as `?token=`.
 */
class HttpCatalogAdapter : MusicCatalogAdapter {
    override val id = ID
    override val displayName = "DebridOne Music Catalog"
    override val description = "A catalogue server that speaks DebridOne's Music Catalog protocol."
    override val kind = IntegrationKind.PLAYBACK_SOURCE
    override val syncsLibrary = false
    override val fields = listOf(
        SourceField("baseUrl", "Catalogue address", placeholder = "https://music.example.com/debridone"),
        SourceField("secret", "Access token", secret = true, optional = true),
    )

    /** A token is optional, so nothing forces a Keychain entry. */
    override val needsCredentials = false

    override val capabilities = CatalogCapabilities(
        searchTracks = true, searchArtists = true, searchAlbums = true, searchPlaylists = true, featured = true, genres = true, ownPlaylists = true,
    )

    override fun connect(draft: SourceDraft): SourceSession {
        val baseUrl = MusicHttp.normalizeBaseUrl(draft.baseUrl)
        val manifest = get(baseUrl, draft.secret, "/manifest.json", emptyList(), what = "manifest")
        val parsed = MusicPluginManifest.parse(manifest.toString()).getOrElse { throw SourceException(it.message ?: "The catalogue's manifest is not valid.") }
        if (parsed.adapter != ID) throw SourceException("That address is a \"${parsed.adapter}\" integration, not a catalogue server.")
        return SourceSession(mapOf("pluginId" to parsed.id), draft.secret, parsed.name, parsed.version)
    }

    override fun test(connection: SourceConnection, secret: String) {
        get(requireNotNull(connection.baseUrl), secret, "/manifest.json", emptyList(), what = "manifest")
    }

    override fun listTracks(connection: SourceConnection, secret: String, onPage: (List<RemoteTrack>) -> Unit) = Unit

    override fun streamUrl(connection: SourceConnection, secret: String, remoteId: String): String {
        // A song may carry its own address; the id then is "url:<address>".
        if (remoteId.startsWith(DIRECT_PREFIX)) return remoteId.removePrefix(DIRECT_PREFIX)
        val base = "${connection.baseUrl}/tracks/${MusicHttp.encode(remoteId)}/stream"
        return if (secret.isBlank()) base else base + "?" + MusicHttp.query(listOf("token" to secret))
    }

    override fun artworkUrl(connection: SourceConnection, secret: String, artworkKey: String): String = artworkKey

    override fun search(connection: SourceConnection, secret: String, query: String, limit: Int): CatalogResults {
        if (query.isBlank()) return CatalogResults()
        val root = get(requireNotNull(connection.baseUrl), secret, "/search", listOf("q" to query, "limit" to limit.coerceIn(1, 100).toString()))
        return CatalogResults(
            tracks = tracks(root),
            artists = root["artists"].array().mapNotNull { it.obj() }.mapNotNull { node ->
                RemoteArtist(
                    remoteId = node.string("id") ?: return@mapNotNull null,
                    name = node.string("name") ?: return@mapNotNull null,
                    artworkUrl = node.string("artworkUrl")?.let(::safeAddress),
                    trackCount = node.int("trackCount"),
                    description = node.string("description"),
                )
            },
            albums = root["albums"].array().mapNotNull { it.obj() }.mapNotNull { node ->
                RemoteAlbum(
                    remoteId = node.string("id") ?: return@mapNotNull null,
                    title = node.string("title") ?: return@mapNotNull null,
                    artist = node.string("artist"),
                    year = node.int("year"),
                    trackCount = node.int("trackCount"),
                    artworkUrl = node.string("artworkUrl")?.let(::safeAddress),
                )
            },
            playlists = playlists(root),
        )
    }

    override fun featured(connection: SourceConnection, secret: String, genre: String?, limit: Int): List<RemoteTrack> =
        tracks(get(requireNotNull(connection.baseUrl), secret, "/featured", listOfNotNull(genre?.takeIf { it.isNotBlank() }?.let { "genre" to it }, "limit" to limit.coerceIn(1, 200).toString())))

    override fun genres(connection: SourceConnection, secret: String): List<String> =
        runCatching { get(requireNotNull(connection.baseUrl), secret, "/genres", emptyList()) }.getOrNull()
            ?.get("genres").array().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.take(200)

    override fun artistTracks(connection: SourceConnection, secret: String, artistId: String, limit: Int): List<RemoteTrack> =
        tracks(get(requireNotNull(connection.baseUrl), secret, "/artists/${MusicHttp.encode(artistId)}/tracks", listOf("limit" to limit.toString()))).take(limit)

    override fun albumTracks(connection: SourceConnection, secret: String, albumId: String): List<RemoteTrack> =
        tracks(get(requireNotNull(connection.baseUrl), secret, "/albums/${MusicHttp.encode(albumId)}/tracks", emptyList()))

    override fun ownPlaylists(connection: SourceConnection, secret: String): List<RemotePlaylist> =
        runCatching { playlists(get(requireNotNull(connection.baseUrl), secret, "/playlists", emptyList())) }.getOrDefault(emptyList())

    override fun playlistTracks(connection: SourceConnection, secret: String, playlistId: String): List<RemoteTrack> =
        tracks(get(requireNotNull(connection.baseUrl), secret, "/playlists/${MusicHttp.encode(playlistId)}/tracks", emptyList()))

    private fun tracks(root: JsonObject): List<RemoteTrack> = root["tracks"].array().mapNotNull { it.obj() }.take(MAX_ITEMS).mapNotNull { node ->
        val streamUrl = node.string("streamUrl")?.let(::safeAddress)
        val id = node.string("id") ?: streamUrl?.let { DIRECT_PREFIX + it } ?: return@mapNotNull null
        RemoteTrack(
            remoteId = if (streamUrl != null) DIRECT_PREFIX + streamUrl else id,
            title = node.string("title")?.take(300) ?: return@mapNotNull null,
            artist = node.string("artist")?.take(300),
            album = node.string("album")?.take(300),
            albumArtist = node.string("albumArtist")?.take(300),
            genre = node.string("genre")?.take(100),
            year = node.int("year"),
            trackNo = node.int("trackNumber"),
            discNo = node.int("discNumber"),
            durationMs = node.long("durationMs") ?: 0,
            format = node.string("format")?.take(12),
            bitrateKbps = node.int("bitrate"),
            sizeBytes = 0,
            artworkKey = null,
            artworkUrl = node.string("artworkUrl")?.let(::safeAddress),
            playable = node["playable"]?.toString() != "false",
        )
    }

    private fun playlists(root: JsonObject): List<RemotePlaylist> = root["playlists"].array().mapNotNull { it.obj() }.take(MAX_ITEMS).mapNotNull { node ->
        RemotePlaylist(
            remoteId = node.string("id") ?: return@mapNotNull null,
            name = node.string("name")?.take(300) ?: return@mapNotNull null,
            owner = node.string("owner"),
            trackCount = node.int("trackCount"),
            description = node.string("description")?.take(1000),
            artworkUrl = node.string("artworkUrl")?.let(::safeAddress),
        )
    }

    /** Only http(s) addresses are taken from a catalogue; anything else (file:, javascript:) is dropped. */
    private fun safeAddress(text: String): String? = text.trim().takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }

    private fun get(baseUrl: String, token: String, path: String, parameters: List<Pair<String, String>>, what: String = "answer"): JsonObject {
        val url = baseUrl.trimEnd('/') + path + if (parameters.isEmpty()) "" else "?" + MusicHttp.query(parameters)
        val headers = buildMap {
            put("Accept", "application/json")
            if (token.isNotBlank()) put("Authorization", "Bearer $token")
        }
        val response = try {
            MusicHttp.get(url, headers, maxBytes = 8 * 1024 * 1024)
        } catch (e: HttpStatusException) {
            throw SourceException(
                when (e.status) {
                    401, 403 -> "The catalogue refused the access token."
                    404 -> "No DebridOne Music Catalog answers at that address."
                    else -> "The catalogue answered with an error (${e.status})."
                },
                e,
            )
        } catch (e: IOException) {
            throw SourceException("The catalogue could not be reached: ${e.message ?: e::class.simpleName}", e)
        }
        return runCatching { Json.parseToJsonElement(response.text()).obj() }.getOrNull()
            ?: throw SourceException("The catalogue's $what is not JSON; that address is not a DebridOne Music Catalog.")
    }

    companion object {
        const val ID = "debridone-catalog"
        const val DIRECT_PREFIX = "url:"
        private const val MAX_ITEMS = 500
    }
}
