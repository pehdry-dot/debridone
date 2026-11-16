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
)

/** A text box of the "add a source" form. */
data class SourceField(val key: String, val label: String, val secret: Boolean = false, val placeholder: String = "", val optional: Boolean = false)

/** What the user typed into the form. */
data class SourceDraft(val name: String, val baseUrl: String, val username: String, val secret: String)

/** What a successful sign-in produced: the non-secret settings to keep and the secret to store. */
data class SourceSession(val config: Map<String, String>, val secret: String, val serverName: String?, val serverVersion: String?)

class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

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

    /**
     * Signs in with what the user typed. Throws [SourceException] with a message for the user when
     * the server cannot be reached, is not this kind of server, or rejects the credentials.
     */
    fun connect(draft: SourceDraft): SourceSession

    /** Checks that a saved connection still works. */
    fun test(connection: SourceConnection, secret: String)

    /** Lists every track of the library, a page at a time, calling [onPage] for each page. */
    fun listTracks(connection: SourceConnection, secret: String, onPage: (List<RemoteTrack>) -> Unit)

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
