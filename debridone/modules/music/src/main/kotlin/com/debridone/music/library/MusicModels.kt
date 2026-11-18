package com.debridone.music.library

import java.text.Normalizer
import java.util.Locale

/** A folder the user added to one profile's library. Files are referenced where they are. */
data class LibraryFolder(
    val id: Long,
    val path: String,
    val addedAt: Long,
    val lastScanAt: Long?,
    val trackCount: Int,
    /** False when the folder cannot be reached right now (unplugged drive, deleted). */
    val available: Boolean,
)

/** Artwork as the screens need it: file paths and the colour to tint with. */
data class ArtworkRef(
    val id: Long,
    /** Small image for lists and grids. */
    val thumbPath: String,
    /** Full-size image for the album page and the player. */
    val filePath: String,
    /** ARGB, or null when it could not be computed. */
    val dominantColor: Int?,
)

data class Track(
    val id: Long,
    /** 0 for files on this computer, otherwise the id of the source connection. */
    val sourceId: Long,
    val remoteId: String?,
    val path: String?,
    /** The file was in the library but is not where it used to be. */
    val missing: Boolean,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val genre: String?,
    val composer: String?,
    val year: Int?,
    val trackNo: Int?,
    val trackTotal: Int?,
    val discNo: Int?,
    val discTotal: Int?,
    val durationMs: Long,
    val format: String?,
    val codec: String?,
    val bitrateKbps: Int?,
    val sampleRate: Int?,
    val channels: Int?,
    val bitDepth: Int?,
    val fileSize: Long,
    val albumId: Long?,
    val artistId: Long?,
    val artwork: ArtworkRef?,
    val favorite: Boolean,
    val playCount: Int,
    val lastPlayedAt: Long?,
    val addedAt: Long,
    /** Fields the user fixed by hand; a rescan or a metadata lookup never changes these. */
    val lockedFields: Set<String>,
    val offlinePath: String?,
    /** Played from an online catalogue but not added to the library. Shown in the history only. */
    val hidden: Boolean = false,
) {
    val isLocal: Boolean get() = sourceId == LOCAL_SOURCE_ID
    val displayArtist: String get() = artist ?: albumArtist ?: UNKNOWN_ARTIST
    val displayAlbum: String get() = album ?: UNKNOWN_ALBUM

    companion object {
        const val LOCAL_SOURCE_ID = 0L
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"
    }
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String?,
    val artistId: Long?,
    val year: Int?,
    val trackCount: Int,
    val durationMs: Long,
    val artwork: ArtworkRef?,
    val favorite: Boolean,
    val addedAt: Long,
) {
    val displayArtist: String get() = artist ?: Track.UNKNOWN_ARTIST
}

data class Artist(
    val id: Long,
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    val artwork: ArtworkRef?,
    val favorite: Boolean,
)

data class Genre(val name: String, val trackCount: Int, val albumCount: Int)

data class LibraryStats(
    val tracks: Int,
    val albums: Int,
    val artists: Int,
    val totalDurationMs: Long,
    val missing: Int,
    val favorites: Int,
)

data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

enum class TrackSort(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    ALBUM("Album"),
    RECENTLY_ADDED("Recently added"),
    YEAR("Year"),
    DURATION("Duration"),
    MOST_PLAYED("Most played"),
    RECENTLY_PLAYED("Recently played"),
}

enum class AlbumSort(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    YEAR("Year"),
    RECENTLY_ADDED("Recently added"),
}

/** Which tracks a list shows. All conditions are combined. */
data class TrackFilter(
    val text: String? = null,
    val favoritesOnly: Boolean = false,
    val genre: String? = null,
    val artistId: Long? = null,
    val albumId: Long? = null,
    val sourceId: Long? = null,
    /** Only songs of these source connections. An empty list matches nothing. */
    val sourceIds: Collection<Long>? = null,
    val missingOnly: Boolean = false,
    val offlineOnly: Boolean = false,
    val format: String? = null,
    /** Also list songs that were only played from an online catalogue. */
    val includeHidden: Boolean = false,
)

/** The editable fields of a track, by the names used for locks and for the stored original. */
object TrackField {
    const val TITLE = "title"
    const val ARTIST = "artist"
    const val ALBUM = "album"
    const val ALBUM_ARTIST = "album_artist"
    const val GENRE = "genre"
    const val COMPOSER = "composer"
    const val YEAR = "year"
    const val TRACK_NO = "track_no"
    const val TRACK_TOTAL = "track_total"
    const val DISC_NO = "disc_no"
    const val DISC_TOTAL = "disc_total"
    const val ARTWORK = "artwork"

    val text = listOf(TITLE, ARTIST, ALBUM, ALBUM_ARTIST, GENRE, COMPOSER)
    val numeric = listOf(YEAR, TRACK_NO, TRACK_TOTAL, DISC_NO, DISC_TOTAL)
    val all = text + numeric + ARTWORK
}

internal object MusicText {
    private val marks = Regex("\\p{M}+")
    private val spaces = Regex("\\s+")

    /** Case- and accent-insensitive form used for matching and sorting ("João" -> "joao"). */
    fun key(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
        return spaces.replace(marks.replace(decomposed, ""), " ").trim().lowercase(Locale.ROOT)
    }

    /** Like [key] but ignoring a leading article, so "The Beatles" sorts under B. */
    fun sortKey(value: String?): String {
        val key = key(value)
        return when {
            key.startsWith("the ") -> key.substring(4)
            else -> key
        }
    }

    fun clean(value: String?): String? = value?.replace('\u0000', ' ')?.trim()?.takeIf { it.isNotEmpty() }
}
