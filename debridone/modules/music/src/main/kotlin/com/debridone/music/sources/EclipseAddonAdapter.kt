package com.debridone.music.sources

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** What an addon answered when asked where a song's audio is (`GET /stream/{id}`). */
data class AddonStream(
    val url: String,
    val format: String?,
    val codec: String?,
    val container: String?,
    val quality: String?,
    /** `none` (the address is the audio file), `hls` or `dash`. Null when the addon does not say. */
    val manifest: String?,
    val expiresAtSeconds: Long?,
)

/** One step of an addon's connection test, in words. Never contains the addon's address. */
data class AddonCheck(val step: String, val ok: Boolean, val detail: String)

/**
 * Music addons in the manifest format shared by **Eclipse Music** and **BeatBoss**: an HTTP server,
 * added by the address of its `manifest.json`, that answers
 *
 * ```
 * GET {base}/manifest.json                 what the addon is and which resources it serves
 * GET {base}/search?q=&limit=              {"tracks":[], "albums":[], "artists":[], "playlists":[]}
 * GET {base}/stream/{id}                   {"url": "...", "format": "...", ...}
 * GET {base}/album/{id}  /artist/{id}  /playlist/{id}
 * GET {base}/catalog/{id}?skip=            {"items":[]}   rows declared as "catalogs" in the manifest
 * GET {base}/lyrics?artist=&title=         {"lyrics": "..."}   (BeatBoss)
 * ```
 *
 * Sources: Eclipse Music's Addon Developer Guide (eclipsemusic.app/docs) and BeatBoss's ADDONS.md
 * and Dart client. DebridOne is a client of this protocol only: no code of an addon runs here; JSON
 * is read and audio is played from the addresses the addon gives.
 *
 * **The address is a secret.** An addon signs its user in through its address
 * (`https://host/<token>/manifest.json`), so the address is handed to this adapter as the
 * connection's secret, kept only in the system's credential store, and never put into the
 * database, a log line or an error message. Every message this class produces names the addon,
 * not its address.
 *
 * Not done on purpose: requests are not signed as coming from the Eclipse app (addons that answer
 * Eclipse only will refuse, and that is reported as such), and a stream marked `encrypted` is not
 * played.
 */
class EclipseAddonAdapter(private val pause: (Long) -> Unit = { Thread.sleep(it) }) : MusicCatalogAdapter {
    override val id = ID
    override val displayName = "Music addon"
    override val description = "An addon in the Eclipse Music / BeatBoss manifest format, added by the address of its manifest."
    override val kind = IntegrationKind.PLAYBACK_SOURCE
    override val syncsLibrary = false
    override val addressIsSecret = true
    override val needsCredentials = true
    override val fields = listOf(SourceField("baseUrl", "Addon address", secret = true, placeholder = "https://addon.example.org/manifest.json"))

    /** What an addon may offer at most; each connection is narrowed by its own manifest. */
    override val capabilities = CatalogCapabilities(searchTracks = true, searchArtists = true, searchAlbums = true, searchPlaylists = true, featured = true, genres = true)

    override fun capabilities(connection: SourceConnection): CatalogCapabilities {
        val manifest = manifest(connection)
        val trackRows = manifest.rows.count { it.type == "track" }
        return CatalogCapabilities(
            searchTracks = manifest.searches,
            searchArtists = manifest.searches,
            searchAlbums = manifest.searches,
            searchPlaylists = manifest.searches,
            featured = trackRows > 0,
            genres = trackRows > 1,
        )
    }

    /** Saving songs is off unless the user switched it on for this addon; see [DOWNLOADS_KEY]. */
    override val allowsDownload = false

    override fun allowsDownload(connection: SourceConnection): Boolean = connection.config[DOWNLOADS_KEY] == "on"

    // ---------------------------------------------------------------- installing and checking

    /** Reads the manifest behind [address] without installing anything. */
    fun preview(address: String): AddonManifest = fetchManifest(AddonAddress.parse(address))

    override fun connect(draft: SourceDraft): SourceSession {
        val address = AddonAddress.parse(draft.baseUrl.ifBlank { draft.secret })
        val manifest = fetchManifest(address)
        val compatibility = manifest.compatibility()
        if (!compatibility.usable) throw SourceException("${manifest.name} cannot be used: ${compatibility.notes.lastOrNull() ?: "it offers nothing DebridOne can show."}")
        return SourceSession(
            config = manifest.toConfig(),
            secret = address.secret,
            serverName = manifest.name,
            serverVersion = manifest.version,
            publicAddress = address.display,
            account = manifest.id,
        )
    }

    override fun test(connection: SourceConnection, secret: String) {
        val manifest = fetchManifest(AddonAddress.parse(secret))
        val expected = connection.config[AddonManifest.KEY_ID]
        if (expected != null && manifest.id != expected) {
            throw SourceException("The address of ${connection.name} now serves a different addon (${manifest.name}). Remove it and add it again if that is intended.")
        }
    }

    /** An addon may change what it offers at any time; a successful test takes the new manifest over. */
    override fun refreshedConfig(connection: SourceConnection, secret: String): Map<String, String>? =
        runCatching { fetchManifest(AddonAddress.parse(secret)).toConfig() }.getOrNull()

    /**
     * Tries the addon step by step: its manifest, a search for [query], and (when a song was found)
     * resolving that song's address and reading the start of its audio. No audio is downloaded
     * beyond the first few kilobytes.
     */
    fun diagnose(connection: SourceConnection, secret: String, query: String): List<AddonCheck> {
        val checks = ArrayList<AddonCheck>()
        val address = runCatching { AddonAddress.parse(secret) }.getOrElse { return listOf(AddonCheck("Address", false, it.message ?: "The saved address is not valid.")) }
        val manifest = try {
            fetchManifest(address).also { checks += AddonCheck("Manifest", true, "${it.name} ${it.version}; resources: ${it.resources.sorted().joinToString(", ").ifEmpty { "none" }}") }
        } catch (e: SourceException) {
            return checks + AddonCheck("Manifest", false, e.message.orEmpty())
        }
        val current = connection.copy(config = connection.config + manifest.toConfig())
        var sample: RemoteTrack? = null
        if (manifest.searches) {
            try {
                val results = search(current, secret, query, 10)
                sample = results.tracks.firstOrNull { it.playable }
                checks += AddonCheck(
                    "Search", true,
                    "\"$query\": ${count(results.tracks.size, "song")}, ${count(results.albums.size, "album")}, ${count(results.artists.size, "artist")}, ${count(results.playlists.size, "playlist")}",
                )
            } catch (e: SourceException) {
                checks += AddonCheck("Search", false, e.message.orEmpty())
            }
        }
        val firstRow = manifest.rows.firstOrNull()
        if (firstRow != null) {
            try {
                val items = row(current, secret, firstRow.id, 0)
                if (sample == null) sample = items.tracks.firstOrNull { it.playable }
                val count = items.tracks.size + items.albums.size + items.artists.size + items.playlists.size
                checks += AddonCheck("Browsing rows", true, "\"${firstRow.name}\": ${count(count, "item")}")
            } catch (e: SourceException) {
                checks += AddonCheck("Browsing rows", false, e.message.orEmpty())
            }
        }
        val track = sample ?: return checks + AddonCheck("Playback", true, "Not tried: no playable song was found to test with.")
        val stream = try {
            resolve(current, secret, track.remoteId).also {
                checks += AddonCheck("Stream address", true, "\"${track.title}\": " + listOfNotNull(it.format ?: it.codec, it.quality, it.manifest?.takeIf { m -> m != "none" }?.uppercase()).joinToString(", ").ifEmpty { "an address was given" })
            }
        } catch (e: SourceException) {
            return checks + AddonCheck("Stream address", false, e.message.orEmpty())
        }
        checks += try {
            val head = MusicHttp.probe(stream.url)
            val detected = AudioSniffer.detect(head.body, head.contentType)
            if (detected != null) AddonCheck("Audio", true, "The address answers with ${AudioSniffer.label(detected)}.")
            else AddonCheck("Audio", false, "The address answered, but not with audio DebridOne recognises (${head.contentType?.substringBefore(';') ?: "no content type"}).")
        } catch (e: HttpStatusException) {
            AddonCheck("Audio", false, "The audio address answered with an error (${e.status}).")
        } catch (e: IOException) {
            AddonCheck("Audio", false, "The audio address could not be opened (${reason(e)}).")
        } catch (e: IllegalArgumentException) {
            AddonCheck("Audio", false, "The addon gave an address that cannot be opened.")
        }
        return checks
    }

    private fun count(number: Int, noun: String): String = if (number == 1) "1 $noun" else "$number ${noun}s"

    // ---------------------------------------------------------------- catalogue

    override fun listTracks(connection: SourceConnection, secret: String, onPage: (List<RemoteTrack>) -> Unit) = Unit

    override fun artworkUrl(connection: SourceConnection, secret: String, artworkKey: String): String = artworkKey

    override fun search(connection: SourceConnection, secret: String, query: String, limit: Int): CatalogResults {
        if (query.isBlank()) return CatalogResults()
        val manifest = manifest(connection)
        if (!manifest.searches) throw SourceException("${connection.name} does not offer search.")
        val root = call(connection, secret, "/search", listOf("q" to query, "limit" to limit.coerceIn(1, 100).toString()), "search")
        return results(connection, secret, manifest, root)
    }

    /** One browsing row (`/catalog/{id}`), [skip] items in. Every item of a row has the row's type. */
    fun row(connection: SourceConnection, secret: String, rowId: String, skip: Int): CatalogResults {
        val manifest = manifest(connection)
        val declared = manifest.rows.firstOrNull { it.id == rowId } ?: throw SourceException("${connection.name} has no row \"$rowId\".")
        val root = call(connection, secret, "/catalog/${MusicHttp.encode(rowId)}", listOf("skip" to skip.coerceAtLeast(0).toString()), "row")
        val items = root["items"].array().mapNotNull { it.obj() }.take(MAX_ITEMS)
        val address = AddonAddress.parse(secret)
        return when (declared.type) {
            "track" -> CatalogResults(tracks = items.mapNotNull { track(connection, address, manifest, it) })
            "album" -> CatalogResults(albums = items.mapNotNull(::album))
            "artist" -> CatalogResults(artists = items.mapNotNull(::artist))
            else -> CatalogResults(playlists = items.mapNotNull(::playlist))
        }
    }

    /** The rows this connection's addon declares, in its order. */
    fun rows(connection: SourceConnection): List<AddonRow> = manifest(connection).rows

    /** Track rows stand in for "featured" songs; with [genre] the row of that name, else the first. */
    override fun featured(connection: SourceConnection, secret: String, genre: String?, limit: Int): List<RemoteTrack> {
        val trackRows = manifest(connection).rows.filter { it.type == "track" }
        val row = trackRows.firstOrNull { it.name.equals(genre, ignoreCase = true) } ?: trackRows.firstOrNull() ?: return emptyList()
        return row(connection, secret, row.id, 0).tracks.take(limit)
    }

    override fun genres(connection: SourceConnection, secret: String): List<String> =
        manifest(connection).rows.filter { it.type == "track" }.map { it.name }

    override fun albumTracks(connection: SourceConnection, secret: String, albumId: String): List<RemoteTrack> {
        val manifest = manifest(connection)
        val root = call(connection, secret, "/album/${MusicHttp.encode(albumId)}", emptyList(), "album")
        val title = root.string("title") ?: root.string("name")
        val artist = root.string("artist") ?: root.string("artistName")
        val artwork = artwork(root)
        val year = root.int("year")
        val address = AddonAddress.parse(secret)
        return (root["tracks"] ?: root["songs"] ?: root["songList"]).array().mapNotNull { it.obj() }.take(MAX_ITEMS).mapIndexedNotNull { index, node ->
            track(connection, address, manifest, node)?.let { track ->
                track.copy(
                    album = track.album ?: title,
                    albumArtist = artist,
                    artist = track.artist ?: artist,
                    year = track.year ?: year,
                    trackNo = track.trackNo ?: (index + 1),
                    artworkUrl = track.artworkUrl ?: artwork,
                )
            }
        }
    }

    override fun artistTracks(connection: SourceConnection, secret: String, artistId: String, limit: Int): List<RemoteTrack> {
        val manifest = manifest(connection)
        val root = call(connection, secret, "/artist/${MusicHttp.encode(artistId)}", emptyList(), "artist")
        val address = AddonAddress.parse(secret)
        return (root["topTracks"] ?: root["tracks"] ?: root["songList"]).array().mapNotNull { it.obj() }.take(limit.coerceIn(1, MAX_ITEMS))
            .mapNotNull { track(connection, address, manifest, it) }
    }

    /** An artist's albums, from the same `/artist/{id}` answer as [artistTracks]. */
    fun artistAlbums(connection: SourceConnection, secret: String, artistId: String): List<RemoteAlbum> =
        call(connection, secret, "/artist/${MusicHttp.encode(artistId)}", emptyList(), "artist")["albums"].array().mapNotNull { it.obj() }.take(MAX_ITEMS).mapNotNull(::album)

    override fun playlistTracks(connection: SourceConnection, secret: String, playlistId: String): List<RemoteTrack> {
        val manifest = manifest(connection)
        val root = call(connection, secret, "/playlist/${MusicHttp.encode(playlistId)}", emptyList(), "playlist")
        val address = AddonAddress.parse(secret)
        return root["tracks"].array().mapNotNull { it.obj() }.take(MAX_ITEMS).mapNotNull { track(connection, address, manifest, it) }
    }

    /** Lyrics from an addon that lists `lyrics` (BeatBoss): plain text or LRC. Null when it has none. */
    fun lyrics(connection: SourceConnection, secret: String, artist: String, title: String): String? {
        if (!manifest(connection).hasLyrics || title.isBlank()) return null
        val answer = exchange(connection, AddonAddress.parse(secret), "/lyrics", listOf("artist" to artist, "title" to title))
        if (answer.status == 404) return null
        if (answer.status >= 400) throw failure(connection.name, answer, "lyrics")
        val text = answer.text().trim()
        val fromJson = (runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject)?.string("lyrics")
        return (fromJson ?: text.takeIf { !it.startsWith("{") && !it.startsWith("<") })?.take(MAX_LYRICS)?.takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------- playback

    override fun streamUrl(connection: SourceConnection, secret: String, remoteId: String): String = resolve(connection, secret, remoteId).url

    /**
     * Where the audio of a song is. A song that came with its own address plays from it; any other
     * is resolved by the addon each time, because such addresses expire. A song that is still being
     * prepared (`503` with `Retry-After`) is asked for again a few times.
     */
    fun resolve(connection: SourceConnection, secret: String, remoteId: String): AddonStream {
        val address = AddonAddress.parse(secret)
        if (remoteId.startsWith(DIRECT_PREFIX)) {
            val url = address.absolute(remoteId.removePrefix(DIRECT_PREFIX))
            return AddonStream(url, null, null, null, null, null, null)
        }
        val trackId = remoteId.removePrefix(TRACK_PREFIX)
        hint(connection.id, trackId)?.let { return AddonStream(it, null, null, null, null, null, null) }
        if (!manifest(connection).resolvesStreams) throw SourceException("${connection.name} gave no address for this song and does not resolve one.")
        var attempt = 0
        while (true) {
            attempt++
            val answer = exchange(connection, address, "/stream/${MusicHttp.encode(trackId)}", emptyList())
            if (answer.status == 503 && attempt < STREAM_ATTEMPTS) {
                pause((answer.retryAfterSeconds ?: 2).coerceIn(1, MAX_RETRY_AFTER_SECONDS) * 1000L)
                continue
            }
            if (answer.status == 404) throw SourceException(notice(answer) ?: "${connection.name} no longer has this song.")
            if (answer.status == 503) throw SourceException(notice(answer) ?: "${connection.name} is still preparing this song. Try again in a moment.")
            if (answer.status >= 400) throw failure(connection.name, answer, "stream")
            val root = json(connection.name, answer, "stream")
            if ((root["encrypted"] as? JsonPrimitive)?.booleanOrNull == true) {
                throw SourceException("${connection.name} offers this song only as a protected (encrypted) stream, which DebridOne does not play.")
            }
            val url = root.string("url")?.let(::safeAddress)
                ?: throw SourceException(notice(answer) ?: "${connection.name} gave no playable address for this song.")
            return AddonStream(
                url = url,
                format = root.string("format")?.lowercase()?.take(12),
                codec = root.string("codec")?.lowercase()?.take(16),
                container = root.string("container")?.lowercase()?.take(12),
                quality = root.string("quality")?.take(40),
                manifest = root.string("manifest")?.lowercase()?.take(8),
                expiresAtSeconds = root.long("expiresAt"),
            )
        }
    }

    // ---------------------------------------------------------------- reading answers

    private fun results(connection: SourceConnection, secret: String, manifest: AddonManifest, root: JsonObject): CatalogResults {
        val address = AddonAddress.parse(secret)
        return CatalogResults(
            tracks = root["tracks"].array().mapNotNull { it.obj() }.take(MAX_ITEMS).mapNotNull { track(connection, address, manifest, it) },
            albums = root["albums"].array().mapNotNull { it.obj() }.take(MAX_ITEMS).mapNotNull(::album),
            artists = root["artists"].array().mapNotNull { it.obj() }.take(MAX_ITEMS).mapNotNull(::artist),
            playlists = root["playlists"].array().mapNotNull { it.obj() }.take(MAX_ITEMS).mapNotNull(::playlist),
        )
    }

    /**
     * A song. Its identity is the addon's `id`; when the addon resolves streams, that id is what
     * is kept and the address is asked for at play time. A song of an addon that does not resolve
     * streams is kept by its direct address, written relative to the addon's own address when it
     * lies under it, so that a token in the addon's address never reaches the database.
     */
    private fun track(connection: SourceConnection, address: AddonAddress, manifest: AddonManifest, node: JsonObject): RemoteTrack? {
        val trackId = node.text("id")?.take(500)
        val title = (node.string("title") ?: node.string("name"))?.take(300) ?: return null
        val direct = (node.string("streamURL") ?: node.string("streamUrl") ?: node.string("url"))?.let(::safeAddress)
        val remoteId = when {
            trackId != null && manifest.resolvesStreams -> TRACK_PREFIX + trackId
            direct != null -> DIRECT_PREFIX + address.relative(direct)
            trackId != null -> TRACK_PREFIX + trackId
            else -> return null
        }
        if (trackId != null && direct != null && manifest.resolvesStreams) remember(connection.id, trackId, direct)
        val seconds = (node["duration"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        return RemoteTrack(
            remoteId = remoteId,
            title = title,
            artist = (node.string("artist") ?: node.string("artistName"))?.take(300),
            album = (node.string("album") ?: node.string("albumTitle"))?.take(300),
            albumArtist = null,
            genre = node.string("genre")?.take(100),
            year = node.int("year"),
            trackNo = node.int("trackNumber"),
            discNo = node.int("discNumber"),
            durationMs = node.long("durationMs") ?: seconds?.let { (it * 1000).toLong() } ?: 0,
            format = node.string("format")?.take(12),
            bitrateKbps = null,
            sizeBytes = 0,
            artworkKey = null,
            artworkUrl = artwork(node),
            playable = (trackId != null && manifest.resolvesStreams) || direct != null,
        )
    }

    private fun album(node: JsonObject): RemoteAlbum? = RemoteAlbum(
        remoteId = node.text("id") ?: return null,
        title = (node.string("title") ?: node.string("name"))?.take(300) ?: return null,
        artist = (node.string("artist") ?: node.string("artistName"))?.take(300),
        year = node.int("year"),
        trackCount = node.int("trackCount") ?: node.int("numberOfTracks"),
        durationMs = node.long("durationMs"),
        artworkUrl = artwork(node),
    )

    private fun artist(node: JsonObject): RemoteArtist? = RemoteArtist(
        remoteId = node.text("id") ?: return null,
        name = (node.string("name") ?: node.string("title"))?.take(300) ?: return null,
        artworkUrl = artwork(node),
        description = node.string("bio")?.take(2000),
    )

    private fun playlist(node: JsonObject): RemotePlaylist? = RemotePlaylist(
        remoteId = node.text("id") ?: return null,
        name = (node.string("title") ?: node.string("name"))?.take(300) ?: return null,
        owner = (node.string("creator") ?: node.string("artist"))?.take(200),
        trackCount = node.int("trackCount"),
        description = node.string("description")?.take(1000),
        artworkUrl = artwork(node),
    )

    /** Cover address under any of the names addons use for it. */
    private fun artwork(node: JsonObject): String? =
        (node.string("artworkURL") ?: node.string("artworkUrl") ?: node.string("albumCover") ?: node.string("image") ?: node.string("cover"))?.let(::safeAddress)

    /** An id may be written as a string or a number. */
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** Only http(s) addresses are taken from an addon; anything else (file:, javascript:) is dropped. */
    private fun safeAddress(text: String): String? = text.trim().takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }

    private fun manifest(connection: SourceConnection): AddonManifest =
        AddonManifest.fromConfig(connection.name, connection.config) ?: throw SourceException("${connection.name} is not set up as an addon. Remove it and add it again.")

    // ---------------------------------------------------------------- requests

    private fun fetchManifest(address: AddonAddress): AddonManifest {
        val answer = try {
            MusicHttp.exchange(address.url("/manifest.json", emptyList()), JSON_HEADERS, maxBytes = AddonManifest.MAX_BYTES + 1)
        } catch (e: IOException) {
            throw SourceException("The addon could not be reached: ${reason(e)}.")
        } catch (e: IllegalArgumentException) {
            throw SourceException("That is not a valid addon address.")
        }
        when {
            answer.status == 404 -> throw SourceException("There is no addon manifest at that address. The address of an addon ends in /manifest.json.")
            answer.status == 401 || answer.status == 403 -> throw SourceException("The addon refused the request (${answer.status}). Its address may be missing your personal token, or the token is no longer valid.")
            answer.status >= 400 -> throw SourceException("The addon answered with an error (${answer.status}).")
        }
        return AddonManifest.parse(answer.text()).getOrElse { throw SourceException(it.message ?: "The addon's manifest is not valid.") }
    }

    private fun call(connection: SourceConnection, secret: String, path: String, parameters: List<Pair<String, String>>, what: String): JsonObject {
        val answer = exchange(connection, AddonAddress.parse(secret), path, parameters)
        if (answer.status >= 400) throw failure(connection.name, answer, what)
        return json(connection.name, answer, what)
    }

    /** One request to the addon. The user's settings for this addon ride along as query parameters, as the protocol asks. */
    private fun exchange(connection: SourceConnection, address: AddonAddress, path: String, parameters: List<Pair<String, String>>): HttpAnswer = try {
        MusicHttp.exchange(address.url(path, parameters + settingParameters(connection)), JSON_HEADERS)
    } catch (e: IOException) {
        throw SourceException("${connection.name} could not be reached: ${reason(e)}.")
    } catch (e: IllegalArgumentException) {
        throw SourceException("The saved address of ${connection.name} is not valid. Remove it and add it again.")
    }

    private fun settingParameters(connection: SourceConnection): List<Pair<String, String>> =
        manifest(connection).settings.map { setting -> setting.key to (connection.config[AddonManifest.VALUE_PREFIX + setting.key] ?: setting.default) }

    private fun json(name: String, answer: HttpAnswer, what: String): JsonObject =
        runCatching { Json.parseToJsonElement(answer.text()).obj() }.getOrNull()
            ?: throw SourceException("$name answered the $what request with something that is not JSON, so it does not follow the addon format there.")

    /** An error answer in words. An addon's own message (`notice`) is preferred; it is plain text and shortened. */
    private fun failure(name: String, answer: HttpAnswer, what: String): SourceException {
        notice(answer)?.let { return SourceException("$name: $it") }
        return SourceException(
            when (answer.status) {
                401, 403 -> "$name refused the $what request (${answer.status}). Its address may carry a token that is no longer valid, or the addon answers the Eclipse app only."
                404 -> "$name has no $what page for this."
                429 -> "$name is limiting requests right now. Try again in a little while."
                in 500..599 -> "$name had an error of its own (${answer.status}) answering the $what request."
                else -> "$name answered the $what request with an error (${answer.status})."
            },
        )
    }

    /** The `notice` an addon may put in any JSON answer: `{"notice": {"title": "...", "message": "..."}}`. */
    private fun notice(answer: HttpAnswer): String? {
        val node = (runCatching { Json.parseToJsonElement(answer.text()) }.getOrNull() as? JsonObject)?.get("notice").obj() ?: return null
        val message = node.string("message")?.replace(Regex("\\s+"), " ")?.take(120) ?: return null
        val title = node.string("title")?.replace(Regex("\\s+"), " ")?.take(40)
        return if (title != null) "$title. $message" else message
    }

    /** Why a connection failed, without the exception's own text (which can contain the address). */
    private fun reason(e: IOException): String = when (e) {
        is UnknownHostException -> "its server name was not found"
        is SocketTimeoutException -> "it did not answer in time"
        is ConnectException -> "its server refused the connection"
        is SSLException -> "its certificate was not accepted"
        else -> "the connection failed"
    }

    // ---------------------------------------------------------------- direct addresses seen in listings

    private class Hint(val url: String, val at: Long)

    /** Direct addresses that came with songs in a listing, kept for a few minutes so that playing one skips the `/stream` call. */
    private val hints = object : LinkedHashMap<String, Hint>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Hint>?): Boolean = size > MAX_HINTS
    }

    private fun remember(connectionId: Long, trackId: String, url: String) = synchronized(hints) { hints["$connectionId|$trackId"] = Hint(url, System.currentTimeMillis()) }

    private fun hint(connectionId: Long, trackId: String): String? = synchronized(hints) {
        hints["$connectionId|$trackId"]?.takeIf { System.currentTimeMillis() - it.at < HINT_LIFETIME_MS }?.url
    }

    companion object {
        const val ID = "eclipse-addon"

        /** Connection setting: `on` when the user allowed saving songs from this addon. */
        const val DOWNLOADS_KEY = "downloads"

        const val TRACK_PREFIX = "t:"
        const val DIRECT_PREFIX = "u:"
        private const val MAX_ITEMS = 500
        private const val MAX_LYRICS = 200_000
        private const val STREAM_ATTEMPTS = 4
        private const val MAX_RETRY_AFTER_SECONDS = 10
        private const val MAX_HINTS = 4_000
        private const val HINT_LIFETIME_MS = 10 * 60 * 1000L
        private val JSON_HEADERS = mapOf("Accept" to "application/json")
    }
}

/**
 * The address of an addon, taken apart: everything before `/manifest.json` is the base every
 * request is made under (it may contain the user's token), and a query string, if any, is repeated
 * on every request.
 */
internal class AddonAddress private constructor(private val base: String, private val query: List<Pair<String, String>>, val display: String) {
    /** What is stored in the credential store; [parse] reads it back. */
    val secret: String get() = base + if (query.isEmpty()) "" else "?" + MusicHttp.query(query)

    fun url(path: String, parameters: List<Pair<String, String>>): String {
        val all = parameters + query
        return base + path + if (all.isEmpty()) "" else "?" + MusicHttp.query(all)
    }

    /** [url] written relative to the base when it lies under it (so no token is repeated), else unchanged. */
    fun relative(url: String): String = if (url.startsWith("$base/")) RELATIVE_MARK + url.removePrefix(base) else url

    fun absolute(stored: String): String = if (stored.startsWith(RELATIVE_MARK)) base + stored.removePrefix(RELATIVE_MARK) else stored

    companion object {
        private const val RELATIVE_MARK = "~"

        fun parse(text: String): AddonAddress {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) throw SourceException("Enter the address of the addon's manifest.")
            val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: throw SourceException("That is not a valid addon address.")
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase() ?: throw SourceException("That is not a valid addon address.")
            if (scheme != "https" && !(scheme == "http" && isLocal(host))) {
                throw SourceException("An addon address must use https (plain http is accepted only on this computer or the local network).")
            }
            if (uri.userInfo != null) throw SourceException("The addon address must not contain a user name or password.")
            var path = uri.rawPath.orEmpty()
            if (path.endsWith("/manifest.json")) path = path.removeSuffix("/manifest.json")
            path = path.trimEnd('/')
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val origin = "$scheme://$host$port"
            val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                java.net.URLDecoder.decode(name, Charsets.UTF_8) to java.net.URLDecoder.decode(value, Charsets.UTF_8)
            }
            return AddonAddress(origin + path, query, origin)
        }

        private fun isLocal(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host == "::1" || host == "[::1]") return true
            val parts = host.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            return parts[0] == 127 || parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
        }
    }
}

/** Tells what kind of audio a file or stream is from its first bytes, falling back on what the server called it. */
object AudioSniffer {
    /** `mp3`, `flac`, `m4a`, `aac`, `ogg`, `opus`, `wav`, `aiff`, `hls`, `dash`, or null when it is not recognised as audio. */
    fun detect(head: ByteArray, contentType: String? = null): String? = fromBytes(head) ?: fromContentType(contentType)

    fun fromBytes(head: ByteArray): String? {
        if (head.size < 4) return null
        fun ascii(offset: Int, length: Int) = if (head.size >= offset + length) String(head, offset, length, Charsets.ISO_8859_1) else ""
        val start = ascii(0, 4)
        return when {
            start == "fLaC" -> "flac"
            start == "OggS" -> if (String(head, 0, minOf(head.size, 96), Charsets.ISO_8859_1).contains("OpusHead")) "opus" else "ogg"
            start == "RIFF" && ascii(8, 4) == "WAVE" -> "wav"
            start == "FORM" && ascii(8, 4).startsWith("AIF") -> "aiff"
            ascii(4, 4) == "ftyp" -> "m4a"
            start.startsWith("ID3") -> "mp3"
            ascii(0, 7) == "#EXTM3U" -> "hls"
            String(head, 0, minOf(head.size, 512), Charsets.ISO_8859_1).contains("<MPD") -> "dash"
            // MPEG audio frame sync: eleven set bits. Layer bits 00 mean ADTS (AAC); anything else is MPEG layer audio.
            (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 -> if ((head[1].toInt() and 0x06) == 0) "aac" else "mp3"
            else -> null
        }
    }

    fun fromContentType(contentType: String?): String? = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/mpeg", "audio/mp3", "audio/mpeg3" -> "mp3"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mp4", "audio/m4a", "audio/x-m4a", "video/mp4" -> "m4a"
        "audio/aac", "audio/aacp", "audio/x-aac" -> "aac"
        "audio/ogg", "application/ogg", "audio/vorbis" -> "ogg"
        "audio/opus" -> "opus"
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
        "audio/aiff", "audio/x-aiff" -> "aiff"
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> "hls"
        "application/dash+xml" -> "dash"
        else -> null
    }

    /** The file extension for a saved copy; null for playlists of segments, which are not single files. */
    fun extension(kind: String): String? = when (kind) {
        "hls", "dash" -> null
        else -> kind
    }

    fun label(kind: String): String = when (kind) {
        "mp3" -> "MP3 audio"
        "flac" -> "FLAC audio"
        "m4a" -> "MPEG-4 audio (AAC or ALAC)"
        "aac" -> "AAC audio"
        "ogg" -> "Ogg Vorbis audio"
        "opus" -> "Opus audio"
        "wav" -> "WAV audio"
        "aiff" -> "AIFF audio"
        "hls" -> "an HLS playlist"
        "dash" -> "a DASH manifest"
        else -> kind
    }
}
