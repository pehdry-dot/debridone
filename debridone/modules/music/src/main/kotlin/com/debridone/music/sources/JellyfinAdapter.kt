package com.debridone.music.sources

import java.io.IOException
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Jellyfin server. Signing in exchanges the password for an access token; only the token is
 * kept (in the secret store), the password is not stored anywhere.
 */
class JellyfinAdapter : MusicCatalogAdapter {
    override val id = ID
    override val displayName = "Jellyfin"
    override val description = "The music libraries of your own Jellyfin server."
    override val kind = IntegrationKind.PERSONAL_SERVER
    override val fields = listOf(
        SourceField("baseUrl", "Server address", placeholder = "https://jellyfin.example.com"),
        SourceField("username", "User name"),
        SourceField("secret", "Password", secret = true, optional = true),
    )

    override fun connect(draft: SourceDraft): SourceSession {
        val baseUrl = MusicHttp.normalizeBaseUrl(draft.baseUrl)
        if (draft.username.isBlank()) throw SourceException("Enter your user name.")
        val info = guarded { MusicHttp.get("$baseUrl/System/Info/Public") }
        val server = runCatching { Json.parseToJsonElement(info.text()).obj() }.getOrNull()
        if (server?.string("Version") == null || server.string("Id") == null) {
            throw SourceException("That address does not answer like a Jellyfin server.")
        }
        val deviceId = UUID.randomUUID().toString()
        val body = buildJsonObject {
            put("Username", draft.username.trim())
            put("Pw", draft.secret)
        }.toString()
        val response = try {
            MusicHttp.postJson("$baseUrl/Users/AuthenticateByName", body, mapOf("Authorization" to authorization(deviceId, null)))
        } catch (e: HttpStatusException) {
            throw SourceException(if (e.status == 401 || e.status == 403) "Wrong user name or password." else "The server answered with an error (${e.status}).", e)
        } catch (e: IOException) {
            throw SourceException("The server could not be reached: ${e.message ?: e::class.simpleName}", e)
        }
        val root = runCatching { Json.parseToJsonElement(response.text()).obj() }.getOrNull()
        val token = root?.string("AccessToken")
        val userId = root?.get("User").obj()?.string("Id")
        if (token == null || userId == null) throw SourceException("The server did not return a sign-in token.")
        return SourceSession(mapOf(CONFIG_USER_ID to userId, CONFIG_DEVICE_ID to deviceId), token, server.string("ServerName") ?: "Jellyfin", server.string("Version"))
    }

    override fun test(connection: SourceConnection, secret: String) {
        val userId = connection.config[CONFIG_USER_ID] ?: throw SourceException("This connection is incomplete; remove it and add it again.")
        guarded { MusicHttp.get("${connection.baseUrl}/Users/$userId", headers(connection, secret)) }
    }

    override fun listTracks(connection: SourceConnection, secret: String, onPage: (List<RemoteTrack>) -> Unit) {
        val userId = connection.config[CONFIG_USER_ID] ?: throw SourceException("This connection is incomplete; remove it and add it again.")
        var start = 0
        while (true) {
            val query = MusicHttp.query(
                listOf(
                    "IncludeItemTypes" to "Audio", "Recursive" to "true", "Fields" to "Genres,MediaSources",
                    "SortBy" to "AlbumArtist,Album,ParentIndexNumber,IndexNumber,SortName", "StartIndex" to start.toString(), "Limit" to PAGE.toString(),
                ),
            )
            val response = guarded { MusicHttp.get("${connection.baseUrl}/Users/$userId/Items?$query", headers(connection, secret), maxBytes = 64 * 1024 * 1024) }
            val root = runCatching { Json.parseToJsonElement(response.text()).obj() }.getOrNull() ?: throw SourceException("The server sent an unreadable answer.")
            val items = root["Items"].array()
            if (items.isEmpty()) break
            onPage(items.mapNotNull { it.obj()?.let(::toTrack) })
            start += items.size
            val total = root.int("TotalRecordCount") ?: Int.MAX_VALUE
            if (items.size < PAGE || start >= total) break
        }
    }

    private fun toTrack(node: JsonObject): RemoteTrack? {
        val remoteId = node.string("Id") ?: return null
        val media = node["MediaSources"].array().firstOrNull().obj()
        val albumId = node.string("AlbumId")
        val ownImage = node["ImageTags"].obj()?.string("Primary")
        return RemoteTrack(
            remoteId = remoteId,
            title = node.string("Name") ?: "Untitled",
            artist = node["Artists"].array().mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }.joinToString(", ").takeIf { it.isNotEmpty() }
                ?: node.string("AlbumArtist"),
            album = node.string("Album"),
            albumArtist = node.string("AlbumArtist"),
            genre = node["Genres"].array().firstNotNullOfOrNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) },
            year = node.int("ProductionYear"),
            trackNo = node.int("IndexNumber"),
            discNo = node.int("ParentIndexNumber"),
            durationMs = (node.long("RunTimeTicks") ?: 0) / 10_000,
            format = media?.string("Container")?.substringBefore(','),
            bitrateKbps = media?.int("Bitrate")?.let { it / 1000 },
            sizeBytes = media?.long("Size") ?: 0,
            artworkKey = when {
                ownImage != null -> remoteId
                albumId != null && node.string("AlbumPrimaryImageTag") != null -> albumId
                else -> null
            },
        )
    }

    // ---------------------------------------------------------------- searching and browsing

    override val capabilities = CatalogCapabilities(
        searchTracks = true, searchArtists = true, searchAlbums = true, searchPlaylists = true, featured = true, genres = true, ownPlaylists = true,
    )

    private fun userId(connection: SourceConnection): String =
        connection.config[CONFIG_USER_ID] ?: throw SourceException("This connection is incomplete; remove it and add it again.")

    /** Items of the user's library matching [parameters]. */
    private fun items(connection: SourceConnection, secret: String, parameters: List<Pair<String, String>>, path: String? = null): List<JsonObject> {
        val address = path ?: "/Users/${userId(connection)}/Items"
        val response = guarded { MusicHttp.get("${connection.baseUrl}$address?" + MusicHttp.query(parameters), headers(connection, secret), maxBytes = 32 * 1024 * 1024) }
        val root = runCatching { Json.parseToJsonElement(response.text()).obj() }.getOrNull() ?: throw SourceException("The server sent an unreadable answer.")
        return root["Items"].array().mapNotNull { it.obj() }
    }

    private fun audio(extra: List<Pair<String, String>>, limit: Int): List<Pair<String, String>> =
        listOf("IncludeItemTypes" to "Audio", "Recursive" to "true", "Fields" to "Genres,MediaSources", "Limit" to limit.coerceIn(1, 500).toString()) + extra

    override fun search(connection: SourceConnection, secret: String, query: String, limit: Int): CatalogResults {
        if (query.isBlank()) return CatalogResults()
        val size = limit.coerceIn(1, 100)
        fun find(type: String) = listOf("searchTerm" to query, "IncludeItemTypes" to type, "Recursive" to "true", "Limit" to size.toString())
        return CatalogResults(
            tracks = items(connection, secret, audio(listOf("searchTerm" to query), size)).mapNotNull(::toTrack),
            artists = runCatching {
                items(connection, secret, listOf("searchTerm" to query, "Limit" to size.toString(), "userId" to userId(connection)), path = "/Artists")
            }.getOrDefault(emptyList()).mapNotNull { node ->
                val id = node.string("Id") ?: return@mapNotNull null
                RemoteArtist(remoteId = id, name = node.string("Name") ?: return@mapNotNull null, artworkKey = id.takeIf { node["ImageTags"].obj()?.string("Primary") != null })
            },
            albums = items(connection, secret, find("MusicAlbum")).mapNotNull { node ->
                val id = node.string("Id") ?: return@mapNotNull null
                RemoteAlbum(
                    remoteId = id,
                    title = node.string("Name") ?: return@mapNotNull null,
                    artist = node.string("AlbumArtist"),
                    year = node.int("ProductionYear"),
                    trackCount = node.int("ChildCount"),
                    durationMs = node.long("RunTimeTicks")?.div(10_000),
                    artworkKey = id.takeIf { node["ImageTags"].obj()?.string("Primary") != null },
                )
            },
            playlists = items(connection, secret, find("Playlist")).mapNotNull(::toPlaylist),
        )
    }

    private fun toPlaylist(node: JsonObject): RemotePlaylist? {
        val id = node.string("Id") ?: return null
        return RemotePlaylist(
            remoteId = id,
            name = node.string("Name") ?: return null,
            trackCount = node.int("ChildCount"),
            description = node.string("Overview"),
            artworkKey = id.takeIf { node["ImageTags"].obj()?.string("Primary") != null },
        )
    }

    override fun featured(connection: SourceConnection, secret: String, genre: String?, limit: Int): List<RemoteTrack> =
        items(connection, secret, audio(listOfNotNull("SortBy" to "Random", genre?.takeIf { it.isNotBlank() }?.let { "Genres" to it }), limit)).mapNotNull(::toTrack)

    override fun genres(connection: SourceConnection, secret: String): List<String> =
        items(connection, secret, listOf("UserId" to userId(connection), "SortBy" to "SortName"), path = "/MusicGenres").mapNotNull { it.string("Name") }

    override fun artistTracks(connection: SourceConnection, secret: String, artistId: String, limit: Int): List<RemoteTrack> =
        items(connection, secret, audio(listOf("ArtistIds" to artistId, "SortBy" to "Album,ParentIndexNumber,IndexNumber"), limit)).mapNotNull(::toTrack)

    override fun albumTracks(connection: SourceConnection, secret: String, albumId: String): List<RemoteTrack> =
        items(connection, secret, audio(listOf("ParentId" to albumId, "SortBy" to "ParentIndexNumber,IndexNumber"), 500)).mapNotNull(::toTrack)

    override fun ownPlaylists(connection: SourceConnection, secret: String): List<RemotePlaylist> =
        items(connection, secret, listOf("IncludeItemTypes" to "Playlist", "Recursive" to "true", "SortBy" to "SortName")).mapNotNull(::toPlaylist)

    override fun playlistTracks(connection: SourceConnection, secret: String, playlistId: String): List<RemoteTrack> =
        items(
            connection, secret, listOf("UserId" to userId(connection), "Fields" to "Genres,MediaSources"),
            path = "/Playlists/${MusicHttp.encode(playlistId)}/Items",
        ).filter { it.string("Type") == null || it.string("Type") == "Audio" }.mapNotNull(::toTrack)

    override fun streamUrl(connection: SourceConnection, secret: String, remoteId: String): String =
        // static=true serves the original file without transcoding.
        "${connection.baseUrl}/Audio/${MusicHttp.encode(remoteId)}/stream?" + MusicHttp.query(listOf("static" to "true", "api_key" to secret))

    override fun artworkUrl(connection: SourceConnection, secret: String, artworkKey: String): String =
        "${connection.baseUrl}/Items/${MusicHttp.encode(artworkKey)}/Images/Primary?" + MusicHttp.query(listOf("maxWidth" to "800", "quality" to "90", "api_key" to secret))

    private fun headers(connection: SourceConnection, token: String): Map<String, String> =
        mapOf("Authorization" to authorization(connection.config[CONFIG_DEVICE_ID] ?: "debridone", token))

    private fun authorization(deviceId: String, token: String?): String =
        "MediaBrowser Client=\"DebridOne\", Device=\"Desktop\", DeviceId=\"$deviceId\", Version=\"0.1\"" + (token?.let { ", Token=\"$it\"" } ?: "")

    private fun guarded(block: () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: HttpStatusException) {
        throw SourceException(
            when (e.status) {
                401, 403 -> "The server no longer accepts this sign-in. Remove the connection and add it again."
                404 -> "No Jellyfin server answers at that address."
                else -> "The server answered with an error (${e.status})."
            },
            e,
        )
    } catch (e: IOException) {
        throw SourceException("The server could not be reached: ${e.message ?: e::class.simpleName}", e)
    }

    companion object {
        const val ID = "jellyfin"
        const val CONFIG_USER_ID = "userId"
        const val CONFIG_DEVICE_ID = "deviceId"
        private const val PAGE = 500
    }
}
