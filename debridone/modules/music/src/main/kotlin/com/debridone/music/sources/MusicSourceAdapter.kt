package com.debridone.music.sources

/** One saved connection to a music source, for one profile. Never holds the password or token. */
data class SourceConnection(
    val id: Long,
    val adapter: String,
    val name: String,
    val baseUrl: String?,
    val username: String?,
    /** Adapter-specific, non-secret settings (for Jellyfin: the user id and device id). */
    val config: Map<String, String>,
    val enabled: Boolean,
    val priority: Int,
    val lastSyncAt: Long?,
    val lastTestAt: Long?,
    val lastTestOk: Boolean?,
    val lastError: String?,
    val trackCount: Int = 0,
    /** The plugin (manifest) this connection was set up from; null for the built-in integrations. */
    val pluginId: String? = null,
)

/** A text box of the "add a source" form. */
data class SourceField(val key: String, val label: String, val secret: Boolean = false, val placeholder: String = "", val optional: Boolean = false)

/** What the user typed into the form. */
data class SourceDraft(val name: String, val baseUrl: String, val username: String, val secret: String)

/**
 * What a successful sign-in produced: the non-secret settings to keep and the secret to store.
 * For a source whose address is itself a secret ([MusicSourceAdapter.addressIsSecret]),
 * [publicAddress] is what may be shown and saved in its place and [account] tells two
 * connections on the same server apart.
 */
data class SourceSession(
    val config: Map<String, String>,
    val secret: String,
    val serverName: String?,
    val serverVersion: String?,
    val publicAddress: String? = null,
    val account: String? = null,
)

class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * What an adapter may remember about one connection between two listings of it: facts about the
 * source's content that do not change (what a finished download contains). Never a secret: it is
 * kept in the database.
 */
interface ListingMemory {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** A track as a source describes it. */
data class RemoteTrack(
    val remoteId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val genre: String?,
    val year: Int?,
    val trackNo: Int?,
    val discNo: Int?,
    val durationMs: Long,
    val format: String?,
    val bitrateKbps: Int?,
    val sizeBytes: Long,
    /** Key the adapter understands for [MusicSourceAdapter.artworkUrl]; equal keys share one cover. */
    val artworkKey: String?,
    /** A cover address that needs no sign-in (open catalogues). Used when [artworkKey] is null. */
    val artworkUrl: String? = null,
    /** Other addresses of the same cover, tried in order when [artworkUrl] does not answer. */
    val artworkAlternates: List<String> = emptyList(),
    /** False when the source will not let this song be played (for example it is sold, not streamed). */
    val playable: Boolean = true,
)

/**
 * A kind of music source that DebridOne knows how to talk to. A source only exists as one of these
 * adapters: an address is accepted when the adapter can sign in to it, never merely because it
 * was typed in.
 *
 * Adapters are stateless and belong to the Music module alone.
 */
interface MusicSourceAdapter {
    val id: String
    val displayName: String
    val description: String
    val fields: List<SourceField>

    /** What this source is, for the catalogue of integrations. */
    val kind: IntegrationKind get() = IntegrationKind.PERSONAL_SERVER

    /** The address used when the form has none: open catalogues live at one well-known place. */
    val defaultBaseUrl: String? get() = null

    /** False for sources that need no sign-in; nothing is stored in the Keychain for them. */
    val needsCredentials: Boolean get() = fields.any { it.secret }

    /**
     * True when the whole library of the source is copied into the profile's library (a personal
     * server). False for catalogues too large to copy, which are searched instead.
     */
    val syncsLibrary: Boolean get() = true

    /** Whether songs may be saved for offline listening. */
    val allowsDownload: Boolean get() = syncsLibrary

    /** The same question for one connection, for sources where the user decides it per connection. */
    fun allowsDownload(connection: SourceConnection): Boolean = allowsDownload

    /**
     * True when the address the user enters may carry a private token (an addon address such as
     * `https://host/<token>/manifest.json`). It is then kept only in the system's credential store,
     * handed to the adapter as its secret, and never written to the database, a log or a message.
     */
    val addressIsSecret: Boolean get() = false

    /**
     * True when a song's address must be asked from the source at the moment it plays (it expires,
     * or costs a request each): the queue then holds a promise, not an address.
     */
    val resolvesAtPlayTime: Boolean get() = addressIsSecret

    /**
     * For sign-ins that expire (an access token with a refresh token): the secret to store in place
     * of [secret] when it had to be renewed, else null. Called before the secret is used.
     */
    fun renewedSecret(connection: SourceConnection, secret: String): String? = null

    /**
     * Settings to keep after a successful [test], when the source describes itself anew each time
     * (an addon's manifest). Null: nothing changes.
     */
    fun refreshedConfig(connection: SourceConnection, secret: String): Map<String, String>? = null

    /**
     * Signs in with what the user typed. Throws [SourceException] with a message for the user when
     * the server cannot be reached, is not this kind of server, or rejects the credentials.
     */
    fun connect(draft: SourceDraft): SourceSession

    /** Checks that a saved connection still works. */
    fun test(connection: SourceConnection, secret: String)

    /** Lists every track of the library, a page at a time, calling [onPage] for each page. */
    fun listTracks(connection: SourceConnection, secret: String, onPage: (List<RemoteTrack>) -> Unit)

    /**
     * The same, for sources that would otherwise ask the same questions at every listing: [memory]
     * is kept for the connection between listings and across restarts, and is erased with it.
     */
    fun listTracks(connection: SourceConnection, secret: String, memory: ListingMemory, onPage: (List<RemoteTrack>) -> Unit) =
        listTracks(connection, secret, onPage)

    /** A URL the audio engine can open. It carries credentials and must never be stored or logged. */
    fun streamUrl(connection: SourceConnection, secret: String, remoteId: String): String

    fun artworkUrl(connection: SourceConnection, secret: String, artworkKey: String): String

    /** File extension for a downloaded copy of [track]'s format. */
    fun fileExtension(format: String?): String = when (format?.lowercase()) {
        "mp3", "mpeg" -> "mp3"
        "flac" -> "flac"
        "aac", "m4a", "alac", "mp4" -> "m4a"
        "ogg", "ogg vorbis", "vorbis" -> "ogg"
        "opus" -> "opus"
        "wav", "wave" -> "wav"
        "aiff", "aif" -> "aiff"
        else -> "bin"
    }
}

/** What an integration is, as the catalogue of Music integrations groups them. */
enum class IntegrationKind(val label: String) {
    PLAYBACK_SOURCE("Playback source"),
    PERSONAL_SERVER("Personal music server"),
    PERSONAL_CLOUD("Personal cloud"),
    METADATA_PROVIDER("Metadata provider"),
    LYRICS_PROVIDER("Lyrics provider"),
    LOCAL_LIBRARY("Local library"),
}
