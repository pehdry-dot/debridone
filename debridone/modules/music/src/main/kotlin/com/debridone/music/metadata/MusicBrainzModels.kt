package com.debridone.music.metadata

/** An artist as MusicBrainz describes it. */
data class MbArtist(
    val id: String,
    val name: String,
    val sortName: String?,
    /** Person, Group, Orchestra... */
    val type: String?,
    val country: String?,
    /** Tells same-named artists apart, e.g. "UK rock band". */
    val disambiguation: String?,
    val begin: String?,
    val end: String?,
    /** MusicBrainz's own 0-100 confidence that this is what was searched for. */
    val score: Int,
)

/** An album as a work, whatever its editions: MusicBrainz's "release group". */
data class MbReleaseGroup(
    val id: String,
    val title: String,
    val artist: String?,
    val artistId: String?,
    /** Album, Single, EP... */
    val primaryType: String?,
    /** Live, Compilation, Soundtrack... */
    val secondaryTypes: List<String>,
    val firstReleaseDate: String?,
    val score: Int,
) {
    val year: Int? get() = firstReleaseDate?.take(4)?.toIntOrNull()
}

/** One edition of an album: a particular pressing, with its own track list and cover. */
data class MbRelease(
    val id: String,
    val title: String,
    val artist: String?,
    val date: String?,
    val country: String?,
    val status: String?,
    val trackCount: Int?,
    val format: String?,
    val releaseGroupId: String?,
    val label: String?,
    val score: Int,
) {
    val year: Int? get() = date?.take(4)?.toIntOrNull()
}

data class MbTrack(
    val disc: Int,
    val position: Int,
    /** The number as printed, which can be "A1" on vinyl. */
    val number: String?,
    val title: String,
    val artist: String?,
    val lengthMs: Long?,
    val recordingId: String?,
)

data class MbReleaseDetail(
    val release: MbRelease,
    val releaseGroup: MbReleaseGroup?,
    val tracks: List<MbTrack>,
    val discCount: Int,
) {
    val totalLengthMs: Long get() = tracks.sumOf { it.lengthMs ?: 0L }
}

data class MbReleaseGroupDetail(val group: MbReleaseGroup, val releases: List<MbRelease>)

/** What applying a MusicBrainz release to an album of the library changed. */
data class AlbumUpdate(
    /** The album's id afterwards; a corrected title or artist can move its songs to another album. */
    val albumId: Long?,
    val tracksMatched: Int,
    val tracksUnmatched: Int,
    val fieldsChanged: Int,
    val coverApplied: Boolean,
)

sealed interface MetadataRefresh {
    /** The unlocked fields were brought up to date; [changed] names the ones that differ now. */
    data class Updated(val changed: Set<String>) : MetadataRefresh

    /** Nothing is known that identifies this item; the user has to choose a match first. */
    data object NeedsMatch : MetadataRefresh
}

/** One image the Cover Art Archive holds for a release. */
data class CoverOption(
    val id: String,
    val releaseId: String,
    /** Front, Back, Booklet, Medium... */
    val types: List<String>,
    val front: Boolean,
    val back: Boolean,
    val comment: String?,
    val thumbnailUrl: String,
    val imageUrl: String,
) {
    val label: String
        get() = when {
            front -> "Front"
            back -> "Back"
            else -> types.firstOrNull() ?: "Other"
        } + comment?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
}

/** Lyrics LRCLIB offers for a song. */
data class LyricsCandidate(
    val id: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationSeconds: Int?,
    val instrumental: Boolean,
    /** Time-stamped lines are available. */
    val synced: Boolean,
    val lyrics: Lyrics?,
)

/** The outcome of the last request to a provider, or of "Test". */
data class ProviderHealth(
    val providerId: String,
    val ok: Boolean,
    val message: String,
    val latencyMs: Long?,
    val checkedAt: Long,
)
