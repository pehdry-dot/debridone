package com.debridone.music.sources

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A sign-in in progress: the code the user types on Real-Debrid's own page, and where that page is. */
data class RealDebridSignIn(
    val baseUrl: String,
    /** Identifies this sign-in to Real-Debrid. Kept in memory only. */
    val deviceCode: String,
    /** What the user types on [verificationUrl]. */
    val userCode: String,
    val verificationUrl: String,
    /** How often to ask whether the code was approved. */
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)

/**
 * The audio files in the user's own Real-Debrid cloud, as a music source.
 *
 * It is a personal source, like a Navidrome server: the user signs in to their own account and
 * the songs found there join the library and play from the cloud. It uses Real-Debrid's official
 * REST API (api.real-debrid.com) and nothing else, and only **reads what is already there**:
 *
 * ```
 * GET  /rest/1.0/user                      who is signed in
 * GET  /rest/1.0/torrents                  the torrents of the account (only finished ones are used)
 * GET  /rest/1.0/torrents/info/{id}        the files of one of them
 * GET  /rest/1.0/downloads                 the links the account generated before
 * POST /rest/1.0/unrestrict/link           a download address for a file of the account's own cloud
 * ```
 *
 * It never adds a torrent or a magnet, never deletes anything, never searches, and never unlocks a
 * link of another file host: a file listed under "downloads" that came from another host is played
 * from the address Real-Debrid already generated for it, not unlocked anew.
 *
 * Sign-in is Real-Debrid's documented "OAuth2 for opensource apps": the user approves DebridOne on
 * Real-Debrid's own page with a short code; DebridOne never sees the password. The credentials it
 * receives (limited to the scopes `unrestrict`, `torrents`, `downloads`, `user`) are the
 * connection's secret, kept in the system's credential store, per profile, and renewed when the
 * access token expires.
 *
 * This integration is independent of addons: an addon is never given these credentials and never
 * uses this cloud.
 */
class RealDebridAdapter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) : MusicSourceAdapter {
    override val id = ID
    override val displayName = "Real-Debrid"
    override val description = "The audio files in your own Real-Debrid cloud. You sign in on Real-Debrid's page; DebridOne only reads what is already in your account."
    override val kind = IntegrationKind.PERSONAL_CLOUD
    override val defaultBaseUrl = DEFAULT_BASE_URL
    override val fields = listOf(SourceField("secret", "Sign-in", secret = true))
    override val syncsLibrary = true
    override val allowsDownload = true

    /** A download address is asked for when a song starts: it is made for the moment and expires. */
    override val resolvesAtPlayTime = true

    // ---------------------------------------------------------------- sign-in

    /** Step one: asks Real-Debrid for a code for the user to approve. */
    fun beginSignIn(baseUrl: String = DEFAULT_BASE_URL): RealDebridSignIn {
        val base = MusicHttp.normalizeBaseUrl(baseUrl)
        val root = json(request("$base$OAUTH/device/code?" + MusicHttp.query(listOf("client_id" to OPEN_SOURCE_CLIENT_ID, "new_credentials" to "yes"))), "sign-in")
        return RealDebridSignIn(
            baseUrl = base,
            deviceCode = root.string("device_code") ?: throw SourceException("Real-Debrid did not start the sign-in."),
            userCode = root.string("user_code") ?: throw SourceException("Real-Debrid did not give a code to enter."),
            verificationUrl = root.string("verification_url")?.takeIf { it.startsWith("https://") } ?: "https://real-debrid.com/device",
            intervalSeconds = (root.int("interval") ?: 5).coerceIn(2, 60),
            expiresInSeconds = (root.int("expires_in") ?: 1800).coerceIn(60, 3600),
        )
    }

    /**
     * Step two, repeated every [RealDebridSignIn.intervalSeconds]: null while the user has not
     * approved the code yet; once they have, the credentials to keep (as [connect] expects them).
     */
    fun pollSignIn(signIn: RealDebridSignIn): String? {
        val pending = request("${signIn.baseUrl}$OAUTH/device/credentials?" + MusicHttp.query(listOf("client_id" to OPEN_SOURCE_CLIENT_ID, "code" to signIn.deviceCode)))
        // Until the code is approved Real-Debrid answers with an error; that is the normal "not yet".
        if (pending.status >= 400) return null
        val granted = json(pending, "sign-in")
        val clientId = granted.string("client_id") ?: return null
        val clientSecret = granted.string("client_secret") ?: return null
        return token(signIn.baseUrl, clientId, clientSecret, signIn.deviceCode).encode()
    }

    /**
     * The other way in: the private API token the user copies from their Real-Debrid account page.
     * Real-Debrid advises the code sign-in instead, because such a token is not limited to reading
     * the cloud; it is offered because some people prefer it. It is kept exactly like the other
     * credentials and is never renewed: it works until the user replaces it on Real-Debrid.
     */
    fun tokenCredentials(apiToken: String): String {
        val token = apiToken.trim()
        if (token.length < 16 || token.any { it.isWhitespace() }) throw SourceException("That does not look like a Real-Debrid API token.")
        return Credentials(clientId = null, clientSecret = null, accessToken = token, refreshToken = null, expiresAtMs = Long.MAX_VALUE).encode()
    }

    /** [SourceDraft.secret] holds the credentials [pollSignIn] or [tokenCredentials] returned. */
    override fun connect(draft: SourceDraft): SourceSession {
        val base = MusicHttp.normalizeBaseUrl(draft.baseUrl.ifBlank { DEFAULT_BASE_URL })
        val credentials = Credentials.decode(draft.secret) ?: throw SourceException("Sign in to Real-Debrid first.")
        val answer = exchange(base, credentials, "/user")
        if (answer.status == 401) throw SourceException("Real-Debrid does not accept this sign-in. Check the token, or sign in with a code instead.")
        val user = json(answer, "answer")
        val name = user.string("username") ?: throw SourceException("Real-Debrid did not say which account this is.")
        return SourceSession(
            config = buildMap {
                put("account", name)
                user.string("type")?.let { put("type", it) }
                user.string("expiration")?.let { put("expiration", it) }
            },
            secret = draft.secret,
            serverName = "Real-Debrid",
            serverVersion = null,
            account = name,
        )
    }

    override fun test(connection: SourceConnection, secret: String) {
        api(base(connection), credentials(connection, secret), "/user")
    }

    /** A new access token when the saved one is about to expire. The refresh token does not expire. */
    override fun renewedSecret(connection: SourceConnection, secret: String): String? {
        val credentials = Credentials.decode(secret) ?: return null
        val clientId = credentials.clientId ?: return null
        val clientSecret = credentials.clientSecret ?: return null
        val refreshToken = credentials.refreshToken ?: return null
        if (credentials.expiresAtMs - clock() > RENEW_BEFORE_MS) return null
        return token(base(connection), clientId, clientSecret, refreshToken).encode()
    }

    private fun token(base: String, clientId: String, clientSecret: String, code: String): Credentials {
        val answer = request(
            "$base$OAUTH/token",
            form = listOf("client_id" to clientId, "client_secret" to clientSecret, "code" to code, "grant_type" to DEVICE_GRANT),
        )
        if (answer.status >= 400) {
            throw SourceException("Real-Debrid no longer accepts this sign-in (${error(answer) ?: answer.status}). Remove the connection and sign in again.")
        }
        val root = json(answer, "sign-in")
        return Credentials(
            clientId = clientId,
            clientSecret = clientSecret,
            accessToken = root.string("access_token") ?: throw SourceException("Real-Debrid gave no access token."),
            refreshToken = root.string("refresh_token") ?: code,
            expiresAtMs = clock() + (root.long("expires_in") ?: 3600).coerceAtLeast(60) * 1000,
        )
    }

    // ---------------------------------------------------------------- what is in the cloud

    /** For a listing asked without a memory: what was learned is kept while the application runs. */
    private val remembered = ConcurrentHashMap<String, String>()

    /** The addresses of the links the account generated, by connection, remembered for a few minutes. */
    private val generated = ConcurrentHashMap<Long, Pair<Long, Map<String, String>>>()

    override fun listTracks(connection: SourceConnection, secret: String, onPage: (List<RemoteTrack>) -> Unit) = listTracks(
        connection, secret,
        object : ListingMemory {
            override fun get(key: String): String? = remembered["${connection.id}/$key"]
            override fun put(key: String, value: String) { remembered["${connection.id}/$key"] = value }
        },
        onPage,
    )

    /**
     * The audio files of the account: those of its finished torrents, then the audio among the
     * links it generated before. Requests are spaced out to stay well inside Real-Debrid's limit of
     * 250 a minute, and a finished torrent is looked into once: what it contains does not change,
     * so it is written into [memory] and not asked again, at this listing or after a restart.
     *
     * Any failure other than "this one torrent is gone" stops the listing with an error: a listing
     * cut short must never be mistaken for a cloud that has become smaller.
     */
    override fun listTracks(connection: SourceConnection, secret: String, memory: ListingMemory, onPage: (List<RemoteTrack>) -> Unit) {
        val base = base(connection)
        val credentials = credentials(connection, secret)
        val cloudLinks = HashSet<String>()

        var page = 1
        var torrents = 0
        while (torrents < MAX_TORRENTS) {
            val list = apiArray(base, credentials, "/torrents", listOf("page" to page.toString(), "limit" to PAGE_SIZE.toString()))
            for (torrent in list.mapNotNull { it.obj() }) {
                torrents++
                if (torrent.string("status") != "downloaded") continue
                val torrentId = torrent.string("id") ?: continue
                // What is remembered counts only while the torrent still has the same links
                // (choosing other files of it on Real-Debrid gives it new ones).
                val listed = torrent["links"].array().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
                val known = memory.get(torrentId)?.let(::decodeAudio)?.takeIf { it.linkCount == listed.size && it.files.all { file -> file.link in listed } }
                val audio = known ?: run {
                    pause(REQUEST_SPACING_MS)
                    val info = patiently { exchange(base, credentials, "/torrents/info/${MusicHttp.encode(torrentId)}") }
                    // Deleted between the two requests: it is simply not there any more.
                    if (info.status == 404) return@run null
                    audioOf(json(info, "answer")).also { memory.put(torrentId, encodeAudio(it)) }
                } ?: continue
                val tracks = torrentTracks(audio)
                tracks.forEach { cloudLinks += it.remoteId.removePrefix(LINK_PREFIX) }
                if (tracks.isNotEmpty()) onPage(tracks)
            }
            if (list.size < PAGE_SIZE) break
            page++
            pause(REQUEST_SPACING_MS)
        }

        val audio = downloads(base, credentials).mapNotNull { entry ->
            val name = entry.string("filename") ?: return@mapNotNull null
            val format = audioFormat(name, entry.string("mimeType")) ?: return@mapNotNull null
            // The same file may be listed as a torrent's file and as a generated link: once is enough.
            if (entry.string("link") in cloudLinks) return@mapNotNull null
            val entryId = entry.string("id") ?: return@mapNotNull null
            track(DOWNLOAD_PREFIX + entryId, name, folder = null, format = format, size = entry.long("filesize") ?: 0, number = null)
        }
        // The same link generated twice is one file.
        audio.distinctBy { it.title to it.sizeBytes }.chunked(200).forEach(onPage)
    }

    /** The links the account generated before, newest first. */
    private fun downloads(base: String, credentials: Credentials): List<JsonObject> {
        val all = ArrayList<JsonObject>()
        var page = 1
        while (all.size < MAX_DOWNLOADS) {
            if (page > 1) pause(REQUEST_SPACING_MS)
            val list = apiArray(base, credentials, "/downloads", listOf("page" to page.toString(), "limit" to PAGE_SIZE.toString()))
            all += list.mapNotNull { it.obj() }
            if (list.size < PAGE_SIZE) break
            page++
        }
        return all
    }

    /** The audio files of a finished torrent, as they are remembered: where each is, its link, its type and size. */
    private class TorrentAudio(val name: String?, val linkCount: Int, val files: List<CloudFile>)

    private class CloudFile(val path: String, val link: String, val format: String, val size: Long)

    /**
     * The audio files of one finished torrent. Real-Debrid lists one link per selected file, in the
     * files' order; a torrent it packed into a single archive has fewer links than files and is
     * left out, because its files cannot be told apart.
     */
    private fun audioOf(info: JsonObject): TorrentAudio {
        val selected = info["files"].array().mapNotNull { it.obj() }.filter { (it.int("selected") ?: 0) == 1 }
        val links = info["links"].array().mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { link -> link.startsWith("https://") } }
        if (selected.isEmpty() || selected.size != links.size) return TorrentAudio(info.string("filename"), links.size, emptyList())
        return TorrentAudio(
            info.string("filename"), links.size,
            selected.zip(links).mapNotNull { (file, link) ->
                val path = file.string("path") ?: return@mapNotNull null
                val format = audioFormat(path, null) ?: return@mapNotNull null
                CloudFile(path.trim('/'), link, format, file.long("bytes") ?: 0)
            },
        )
    }

    private fun encodeAudio(audio: TorrentAudio): String = buildJsonObject {
        audio.name?.let { put("name", it) }
        put("links", audio.linkCount)
        put(
            "files",
            JsonArray(audio.files.map { file -> buildJsonObject { put("path", file.path); put("link", file.link); put("format", file.format); put("size", file.size) } }),
        )
    }.toString()

    private fun decodeAudio(text: String): TorrentAudio? {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        return TorrentAudio(
            root.string("name"), root.int("links") ?: return null,
            root["files"].array().mapNotNull { it.obj() }.mapNotNull { file ->
                CloudFile(file.string("path") ?: return@mapNotNull null, file.string("link") ?: return@mapNotNull null, file.string("format") ?: return@mapNotNull null, file.long("size") ?: 0)
            },
        )
    }

    /** The songs of a torrent's audio files. A folder in which every song's name starts with a number is an album in track order. */
    private fun torrentTracks(audio: TorrentAudio): List<RemoteTrack> =
        audio.files.groupBy { it.path.substringBeforeLast('/', "") }.flatMap { (folderPath, files) ->
            val numbered = files.size > 1 && files.all { LOOSE_NUMBER.containsMatchIn(it.path.substringAfterLast('/')) }
            val folder = folderPath.substringAfterLast('/').ifBlank { null } ?: audio.name
            files.mapIndexed { index, file ->
                track(LINK_PREFIX + file.link, file.path.substringAfterLast('/'), folder, file.format, file.size, number = index + 1, numberedFolder = numbered)
            }
        }

    /**
     * A song from a file's name and its folder's. Real-Debrid knows files, not tags, so this reads
     * the usual conventions ("03 - Title", "Artist - Title", a folder called "Artist - Album
     * (1999)") and nothing more; the result can be corrected in the library like any other song.
     */
    private fun track(remoteId: String, fileName: String, folder: String?, format: String, size: Long, number: Int?, numberedFolder: Boolean = false): RemoteTrack {
        val stem = fileName.substringBeforeLast('.')
        // On its own, a leading number is a track number only when it is written like one ("03 Title",
        // "3. Title"): "7 Lamps - Evening" is a band, not track seven.
        val numbered = (if (numberedFolder) LOOSE_NUMBER else TRACK_NUMBER).find(stem)
        val rest = cleanName(numbered?.groupValues?.get(2) ?: stem)
        val named = rest.contains(" - ")
        val trackArtist = rest.substringBefore(" - ").trim().takeIf { named && it.isNotEmpty() }
        val title = (if (trackArtist != null) rest.substringAfter(" - ") else rest).trim().ifEmpty { cleanName(stem) }

        val year = folder?.let { YEAR.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val folderName = folder?.let { cleanName(it.replace(BRACKETED, " ")) }?.takeIf { it.isNotEmpty() }
        val albumArtist = folderName?.substringBefore(" - ")?.trim()?.takeIf { folderName.contains(" - ") && it.isNotEmpty() }
        val album = (if (albumArtist != null) folderName.substringAfter(" - ") else folderName)?.trim()?.takeIf { it.isNotEmpty() }
        return RemoteTrack(
            remoteId = remoteId,
            title = title.take(300),
            artist = (trackArtist ?: albumArtist)?.take(300),
            album = album?.take(300),
            albumArtist = albumArtist?.take(300),
            genre = null,
            year = year,
            trackNo = numbered?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..999 } ?: number,
            discNo = null,
            durationMs = 0,
            format = format,
            bitrateKbps = null,
            sizeBytes = size,
            artworkKey = null,
        )
    }

    private fun cleanName(text: String): String = text.replace('_', ' ').replace(SPACES, " ").trim()

    /** The audio format of a file by its name (or what Real-Debrid called it); null when it is not audio. */
    private fun audioFormat(name: String, mimeType: String?): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        AUDIO_EXTENSIONS[extension]?.let { return it }
        if (extension in OTHER_EXTENSIONS) return null
        return mimeType?.takeIf { it.startsWith("audio/", ignoreCase = true) }?.substringAfter('/')?.substringBefore(';')?.trim()?.lowercase()?.let { AUDIO_TYPES[it] }
    }

    // ---------------------------------------------------------------- playback

    /**
     * A download address for one song, made now. A file of the cloud is "unrestricted" (its own
     * Real-Debrid link turned into an address); a link generated earlier is looked up in the
     * account's list and its existing address used.
     */
    override fun streamUrl(connection: SourceConnection, secret: String, remoteId: String): String {
        val base = base(connection)
        val credentials = credentials(connection, secret)
        if (remoteId.startsWith(LINK_PREFIX)) {
            val answer = request("$base$REST/unrestrict/link", bearer(credentials), form = listOf("link" to remoteId.removePrefix(LINK_PREFIX)))
            if (answer.status >= 400) throw failure(answer)
            return json(answer, "file").string("download")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: throw SourceException("Real-Debrid gave no address for this file.")
        }
        val entryId = remoteId.removePrefix(DOWNLOAD_PREFIX)
        val known = generated[connection.id]?.takeIf { clock() - it.first < GENERATED_TTL_MS }?.second
        val addresses = known?.takeIf { entryId in it } ?: downloads(base, credentials)
            .mapNotNull { entry -> (entry.string("id") ?: return@mapNotNull null) to (entry.string("download") ?: return@mapNotNull null) }
            .toMap()
            .also { generated[connection.id] = clock() to it }
        return addresses[entryId]?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: throw SourceException("This file is no longer in your Real-Debrid downloads. Synchronise the connection to bring the list up to date.")
    }

    override fun artworkUrl(connection: SourceConnection, secret: String, artworkKey: String): String = artworkKey

    // ---------------------------------------------------------------- requests

    private fun base(connection: SourceConnection): String = connection.baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    private fun credentials(connection: SourceConnection, secret: String): Credentials =
        Credentials.decode(secret) ?: throw SourceException("The saved sign-in for ${connection.name} cannot be read. Remove the connection and sign in again.")

    private fun bearer(credentials: Credentials) = mapOf("Authorization" to "Bearer ${credentials.accessToken}", "Accept" to "application/json")

    private fun exchange(base: String, credentials: Credentials, path: String, parameters: List<Pair<String, String>> = emptyList()): HttpAnswer =
        request(base + REST + path + if (parameters.isEmpty()) "" else "?" + MusicHttp.query(parameters), bearer(credentials))

    /** One more try, after a real pause, when Real-Debrid says requests are coming too fast. */
    private fun patiently(ask: () -> HttpAnswer): HttpAnswer {
        val first = ask()
        if (first.status != 429) return first
        pause(RATE_LIMIT_PAUSE_MS)
        return ask()
    }

    private fun api(base: String, credentials: Credentials, path: String, parameters: List<Pair<String, String>> = emptyList()): JsonObject =
        json(exchange(base, credentials, path, parameters), "answer")

    private fun apiArray(base: String, credentials: Credentials, path: String, parameters: List<Pair<String, String>>): JsonArray {
        val answer = patiently { exchange(base, credentials, path, parameters) }
        // An empty list is answered with 204 and no body.
        if (answer.status == 204) return JsonArray(emptyList())
        if (answer.status >= 400) throw failure(answer)
        if (answer.body.isEmpty()) return JsonArray(emptyList())
        return runCatching { Json.parseToJsonElement(answer.text()) }.getOrNull() as? JsonArray
            ?: throw SourceException("Real-Debrid's answer could not be read.")
    }

    private fun request(url: String, headers: Map<String, String> = mapOf("Accept" to "application/json"), form: List<Pair<String, String>>? = null): HttpAnswer = try {
        MusicHttp.exchange(url, headers, form = form)
    } catch (e: IOException) {
        // The exception's own text is not repeated: a request may carry a credential.
        throw SourceException("Real-Debrid could not be reached.")
    } catch (e: IllegalArgumentException) {
        throw SourceException("The address of Real-Debrid is not valid.")
    }

    private fun json(answer: HttpAnswer, what: String): JsonObject {
        if (answer.status >= 400) throw failure(answer)
        return runCatching { Json.parseToJsonElement(answer.text()).obj() }.getOrNull() ?: throw SourceException("Real-Debrid's $what could not be read.")
    }

    /** Real-Debrid's own error text (`{"error": "...", "error_code": n}`), when it sent one. */
    private fun error(answer: HttpAnswer): String? =
        (runCatching { Json.parseToJsonElement(answer.text()) }.getOrNull() as? JsonObject)?.string("error")?.replace('_', ' ')?.take(80)

    private fun failure(answer: HttpAnswer): SourceException = SourceException(
        when (answer.status) {
            401 -> "Real-Debrid no longer accepts the saved sign-in. Remove the connection and sign in again."
            403 -> "Real-Debrid refused the request (${error(answer) ?: "permission denied"}). The account may be locked or not premium."
            429 -> "Real-Debrid is limiting requests right now. Try again in a minute."
            503 -> "Real-Debrid is not available right now (${error(answer) ?: "service unavailable"})."
            else -> "Real-Debrid answered with an error (${error(answer) ?: answer.status})."
        },
    )

    /** What a sign-in is made of. Stored as one JSON text in the credential store; never anywhere else. */
    internal class Credentials(val clientId: String?, val clientSecret: String?, val accessToken: String, val refreshToken: String?, val expiresAtMs: Long) {
        fun encode(): String = buildJsonObject {
            clientId?.let { put("client_id", it) }
            clientSecret?.let { put("client_secret", it) }
            put("access_token", accessToken)
            refreshToken?.let { put("refresh_token", it) }
            put("expires_at", expiresAtMs)
        }.toString()

        companion object {
            fun decode(text: String): Credentials? {
                val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
                return Credentials(
                    clientId = root.string("client_id"),
                    clientSecret = root.string("client_secret"),
                    accessToken = root.string("access_token") ?: return null,
                    refreshToken = root.string("refresh_token"),
                    expiresAtMs = root.long("expires_at") ?: 0,
                )
            }
        }
    }

    companion object {
        const val ID = "real-debrid"
        const val DEFAULT_BASE_URL = "https://api.real-debrid.com"

        /** The client id Real-Debrid publishes for open-source apps (scopes: unrestrict, torrents, downloads, user). */
        const val OPEN_SOURCE_CLIENT_ID = "X245A4XAIBGVM"

        const val LINK_PREFIX = "l:"
        const val DOWNLOAD_PREFIX = "d:"
        private const val REST = "/rest/1.0"
        private const val OAUTH = "/oauth/v2"
        private const val DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0"
        /** Real-Debrid allows up to 5000 entries a request; fewer, larger requests are kinder to its limit. */
        private const val PAGE_SIZE = 500
        private const val MAX_TORRENTS = 5_000
        private const val MAX_DOWNLOADS = 5_000

        /** About 170 requests a minute at most, under the documented 250. */
        private const val REQUEST_SPACING_MS = 350L
        private const val RATE_LIMIT_PAUSE_MS = 20_000L
        private const val RENEW_BEFORE_MS = 2 * 60 * 1000L
        private const val GENERATED_TTL_MS = 5 * 60 * 1000L
        private val TRACK_NUMBER = Regex("^\\s*(0\\d{1,2}(?=\\s)|\\d{1,3}(?=\\s*[-._)]))\\s*[-._)]?\\s*(.+)$")
        private val LOOSE_NUMBER = Regex("^\\s*(\\d{1,3})\\s*[-._)\\s]\\s*(.+)$")
        private val YEAR = Regex("[(\\[]((?:19|20)\\d{2})[)\\]]")
        private val BRACKETED = Regex("\\[[^\\]]*]|\\((?:19|20)\\d{2}\\)")
        private val SPACES = Regex("\\s+")
        private val AUDIO_EXTENSIONS = mapOf(
            "mp3" to "mp3", "flac" to "flac", "m4a" to "m4a", "m4b" to "m4a", "aac" to "aac", "ogg" to "ogg", "oga" to "ogg",
            "opus" to "opus", "wav" to "wav", "aiff" to "aiff", "aif" to "aiff",
        )

        /** What Real-Debrid may call an audio file that has no telling name. */
        private val AUDIO_TYPES = mapOf(
            "mpeg" to "mp3", "mp3" to "mp3", "flac" to "flac", "x-flac" to "flac", "mp4" to "m4a", "x-m4a" to "m4a", "aac" to "aac",
            "ogg" to "ogg", "opus" to "opus", "wav" to "wav", "x-wav" to "wav", "wave" to "wav", "aiff" to "aiff", "x-aiff" to "aiff",
        )

        /** Names that settle the question the other way, whatever type is claimed for them. */
        private val OTHER_EXTENSIONS = setOf("mkv", "mp4", "avi", "mov", "webm", "ts", "m2ts", "zip", "rar", "7z", "iso", "pdf", "epub", "cbz", "jpg", "jpeg", "png", "nfo", "txt", "srt", "cue", "log", "m3u", "m3u8")
    }
}
