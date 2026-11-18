package com.debridone.cloud

import java.io.IOException

/** The cloud services DebridOne can connect to. Each is the user's own account at that service. */
enum class ProviderId(val key: String, val displayName: String, val manageUrl: String) {
    REAL_DEBRID("real-debrid", "Real-Debrid", "https://real-debrid.com/"),
    TORBOX("torbox", "TorBox", "https://torbox.app/settings");

    companion object {
        fun of(key: String): ProviderId? = entries.firstOrNull { it.key == key }
    }
}

/** What an entry of an account is. Real-Debrid has torrents and generated links; TorBox has the first three. */
enum class ItemKind(val key: String, val label: String, val plural: String) {
    TORRENT("torrent", "Torrent", "Torrents"),
    USENET("usenet", "Usenet download", "Usenet"),
    WEB("web", "Web download", "Web downloads"),
    LINK("link", "Generated link", "Generated links");

    companion object {
        fun of(key: String): ItemKind? = entries.firstOrNull { it.key == key }
    }
}

enum class ItemStatus(val label: String) {
    READY("Ready"),
    DOWNLOADING("Downloading"),
    QUEUED("Queued"),
    PAUSED("Paused"),
    ERROR("Failed"),
    UNKNOWN("Unknown"),
}

/** The shelves of the file browser. See [MediaFileClassifier] for the rules. */
enum class MediaClass(val key: String, val label: String) {
    MOVIE("movie", "Movies"),
    TV_SHOW("tv", "TV Shows"),
    MUSIC("music", "Music"),
    COMIC("comic", "Manga & Comics"),
    OTHER("other", "Other Files");

    val isVideo: Boolean get() = this == MOVIE || this == TV_SHOW

    companion object {
        fun of(key: String): MediaClass = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/**
 * What a provider handed over at sign-in, as one text kept in the system's credential store and
 * nowhere else. It never appears in a message: [toString] says nothing.
 */
class CloudCredentials(internal val secret: String, /** Tells one account's short-lived caches from another's. */ internal val cacheKey: String = "") {
    override fun toString(): String = "CloudCredentials(…)"
}

/** A sign-in with a code in progress: the user types [userCode] on the provider's own page. */
data class DeviceSignIn(
    val provider: ProviderId,
    /** Identifies this sign-in to the provider. Kept in memory only. */
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
) {
    override fun toString(): String = "DeviceSignIn($provider)"
}

/** Who is signed in, as the provider describes them. */
data class AccountInfo(
    val remoteUserId: String,
    val username: String?,
    val email: String?,
    /** The plan in words ("Premium", "Pro"). */
    val plan: String?,
    val premium: Boolean,
    val premiumUntil: Long?,
    /** Other facts worth showing, label to value. */
    val details: Map<String, String> = emptyMap(),
)

/** One file inside an entry. [ref] is what the provider needs to hand the file out; it is private. */
data class CloudFile(
    val ref: String,
    /** Path inside the entry, with `/` between folders. */
    val path: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val md5: String? = null,
    /** True for an archive the provider made of several files: it can be downloaded, but what is inside is not known here. */
    val opaque: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** One entry of an account: a transfer (torrent, usenet, web download) or a link generated before. */
data class CloudItem(
    val kind: ItemKind,
    val remoteId: String,
    val name: String,
    val sizeBytes: Long,
    val status: ItemStatus,
    /** The provider's own word for the state. */
    val rawStatus: String?,
    /** 0 to 1, when the provider reports it. */
    val progress: Float?,
    val createdAt: Long?,
    val completedAt: Long?,
    val expiresAt: Long?,
    /** Null when the files are not part of the listing and must be asked for ([CloudProvider.files]). */
    val files: List<CloudFile>?,
    /** Changes when the entry's files change; lets a listing skip asking again. */
    val signature: String? = null,
    /** For a generated link: the link it was generated from, to recognise a torrent's file listed twice. */
    val origin: String? = null,
)

/** A download address made for the moment. Never stored, never logged: [toString] says nothing. */
class ResolvedLink(val url: String, val fileName: String? = null, val sizeBytes: Long? = null) {
    override fun toString(): String = "ResolvedLink(…)"
}

/**
 * Anything that went wrong talking to a provider, with a message fit for the user. The message
 * never contains an address, a key or a token.
 */
class CloudException(val reason: Reason, message: String, val retryAfterSeconds: Int? = null) : IOException(message) {
    enum class Reason {
        /** The sign-in is no longer accepted: the user has to sign in again. */
        AUTH_EXPIRED,
        FORBIDDEN,
        RATE_LIMITED,
        NOT_FOUND,
        UNAVAILABLE,
        UNSUPPORTED,
        NETWORK,
        PROTOCOL,

        /** This profile may not use that account. */
        NOT_ALLOWED,
    }
}
