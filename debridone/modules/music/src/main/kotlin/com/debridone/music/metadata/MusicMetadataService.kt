package com.debridone.music.metadata

import com.debridone.music.library.Album
import com.debridone.music.library.ArtworkStore
import com.debridone.music.library.AudioTagReader
import com.debridone.music.library.MusicLibraryRepository
import com.debridone.music.library.MusicText
import com.debridone.music.library.Track
import com.debridone.music.library.TrackField
import com.debridone.music.sources.HttpStatusException
import com.debridone.music.sources.MusicHttp
import com.debridone.music.sources.array
import com.debridone.music.sources.int
import com.debridone.music.sources.long
import com.debridone.music.sources.obj
import com.debridone.music.sources.string
import com.debridone.platform.db.SqliteDatabase
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A recording MusicBrainz offers as a match for a song, with the release it would be filed under. */
data class RecordingMatch(
    val recordingId: String,
    val title: String,
    val artist: String?,
    val releaseId: String?,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
    val trackNo: Int?,
    val trackTotal: Int?,
    val durationMs: Long?,
    /** MusicBrainz's own 0-100 confidence in the match. */
    val score: Int,
)

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    val plain: String?,
    /** Time-stamped lines, when the lyrics are synchronised. */
    val synced: List<LyricLine>,
    /** `embedded`, `lrclib` or `manual`. */
    val origin: String,
    val instrumental: Boolean = false,
)

class MetadataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Where the online providers live. Tests point these at a local stand-in server. */
data class MetadataEndpoints(
    val musicBrainz: String = "https://musicbrainz.org/ws/2",
    val coverArtArchive: String = "https://coverartarchive.org",
    val lrclib: String = "https://lrclib.net/api",
    /** A release known to have artwork, asked for by "Test" of the Cover Art Archive. */
    val coverArtProbeRelease: String = "76df3287-6cda-33eb-8e9a-044b5e15ffdd",
)

/**
 * Looks song details, covers and lyrics up online, for Music only.
 *
 * It never changes a song by itself: a lookup returns candidates, and applying one fills only the
 * fields that are not locked. Answers are cached in `music.db`; MusicBrainz is asked at most once
 * a second, as its terms require.
 */
class MusicMetadataService(
    private val database: SqliteDatabase,
    private val library: MusicLibraryRepository,
    /** Whether the user has the provider with this id switched on. */
    private val providerEnabled: (String) -> Boolean,
    private val endpoints: MetadataEndpoints = MetadataEndpoints(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val minimumRequestIntervalMs: Long = 1_100,
    /** Where previews of covers offered for choosing are kept. Null: previews are not cached. */
    private val previewDirectory: Path? = null,
    /** The first pause after MusicBrainz says it is busy; the following ones are multiples of it. */
    private val busyRetryDelayMs: Long = 2_000,
) {
    private val rateLock = Any()
    private var lastMusicBrainzRequest = 0L

    private val _health = MutableStateFlow(loadHealth())

    /** The outcome of the latest request to each provider, or of its last "Test". */
    val health: StateFlow<Map<String, ProviderHealth>> = _health.asStateFlow()

    // ---------------------------------------------------------------- song details

    /** Candidates for [trackId] from MusicBrainz, best first. Empty when nothing plausible exists. */
    fun findRecordings(profileId: Long, trackId: Long, limit: Int = 8): List<RecordingMatch> {
        requireProvider(MUSICBRAINZ, "MusicBrainz")
        val track = library.track(profileId, trackId) ?: return emptyList()
        return findRecordings(track.title, track.artist ?: track.albumArtist, track.album, track.durationMs, limit)
    }

    fun findRecordings(title: String, artist: String?, album: String?, durationMs: Long, limit: Int = 8): List<RecordingMatch> {
        requireProvider(MUSICBRAINZ, "MusicBrainz")
        // Title and artist are required; the album only pushes recordings that are on it to the top.
        val query = buildList {
            add("+recording:\"${escape(title)}\"")
            if (!artist.isNullOrBlank()) add("+artist:\"${escape(artist)}\"")
            if (!album.isNullOrBlank()) add("release:\"${escape(album)}\"")
        }.joinToString(" ")
        val url = "${endpoints.musicBrainz}/recording?" + MusicHttp.query(listOf("query" to query, "fmt" to "json", "limit" to "12"))
        val root = cachedJson(MUSICBRAINZ, "recording:$query", url, rateLimited = true) ?: return emptyList()
        val matches = root["recordings"].array().flatMap { recording ->
            val node = recording.obj() ?: return@flatMap emptyList()
            val recordingId = node.string("id") ?: return@flatMap emptyList()
            val credit = node["artist-credit"].array().mapNotNull { it.obj()?.let { part ->
                    // The join phrase (" feat. ", " & ") carries its own spaces and must not be trimmed.
                    (part.string("name") ?: part["artist"].obj()?.string("name")).orEmpty() + (part["joinphrase"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                }
            }
                .joinToString("").trim().takeIf { it.isNotEmpty() }
            val base = RecordingMatch(recordingId, node.string("title") ?: title, credit, null, null, null, null, null, null, node.long("length"), node.int("score") ?: 0)
            val releases = node["releases"].array().mapNotNull { it.obj() }
            if (releases.isEmpty()) return@flatMap listOf(base)
            releases.map { release ->
                val medium = release["media"].array().firstOrNull().obj()
                val number = medium?.get("track").array().firstOrNull().obj()?.string("number")?.toIntOrNull() ?: medium?.int("track-offset")?.plus(1)
                base.copy(
                    releaseId = release.string("id"),
                    album = release.string("title"),
                    albumArtist = release["artist-credit"].array().firstOrNull().obj()?.string("name") ?: credit,
                    year = release.string("date")?.take(4)?.toIntOrNull(),
                    trackNo = number,
                    trackTotal = medium?.int("track-count") ?: release.int("track-count"),
                )
            }
        }
        // MusicBrainz's score first; then a release whose title matches the album we already have,
        // and a length close to the file's.
        fun rank(match: RecordingMatch): Int {
            var value = match.score * 10
            if (!album.isNullOrBlank() && match.album != null && sameText(match.album, album)) value += 150
            if (durationMs > 0 && match.durationMs != null) value += (60 - (abs(match.durationMs - durationMs) / 1000).coerceAtMost(60)).toInt()
            return value
        }
        return matches.sortedByDescending(::rank).distinctBy { it.recordingId to it.releaseId }.take(limit)
    }

    /**
     * Fills the song's unlocked fields from [match], and its cover from the Cover Art Archive when
     * the song has none of its own. Returns the names of the fields that changed.
     */
    fun apply(profileId: Long, trackId: Long, match: RecordingMatch, fetchCover: Boolean = true): Set<String> {
        val track = library.track(profileId, trackId) ?: return emptySet()
        val values = linkedMapOf(
            TrackField.TITLE to match.title,
            TrackField.ARTIST to match.artist,
            TrackField.ALBUM to match.album,
            TrackField.ALBUM_ARTIST to match.albumArtist,
            TrackField.YEAR to match.year?.toString(),
            TrackField.TRACK_NO to match.trackNo?.toString(),
            TrackField.TRACK_TOTAL to match.trackTotal?.toString(),
        )
        val applied = library.applyProviderMetadata(profileId, trackId, values).toMutableSet()
        library.setTrackIdentifiers(profileId, trackId, match.recordingId, match.releaseId)
        if (fetchCover && match.releaseId != null && track.artwork == null && TrackField.ARTWORK !in track.lockedFields && providerEnabled(COVER_ART_ARCHIVE)) {
            val artworkId = runCatching { fetchCover(profileId, match.releaseId) }.getOrNull()
            if (artworkId != null) {
                library.setTrackArtwork(profileId, trackId, artworkId, custom = false)
                applied += TrackField.ARTWORK
            }
        }
        return applied
    }

    /** The front cover of a MusicBrainz release, stored as artwork. Null when the archive has none. */
    fun fetchCover(profileId: Long, releaseId: String): Long? {
        requireProvider(COVER_ART_ARCHIVE, "the Cover Art Archive")
        val image = try {
            MusicHttp.get("${endpoints.coverArtArchive}/release/${MusicHttp.encode(releaseId)}/front-500", maxBytes = 12 * 1024 * 1024)
        } catch (e: HttpStatusException) {
            if (e.status == 404) return null
            throw MetadataException("The Cover Art Archive answered with an error (${e.status}).", e)
        } catch (e: IOException) {
            throw MetadataException("The Cover Art Archive could not be reached: ${e.message ?: e::class.simpleName}", e)
        }
        return library.artwork.store(profileId, image.body, ArtworkStore.ORIGIN_ONLINE)
    }

    // ---------------------------------------------------------------- lyrics

    /**
     * Lyrics for a song: the ones saved for it, else the ones embedded in its file, else (when
     * [online] and LRCLIB is switched on) a lookup. Null when there are none.
     */
    fun lyrics(profileId: Long, trackId: Long, online: Boolean = true): Lyrics? {
        stored(profileId, trackId)?.let { return it }
        val track = library.track(profileId, trackId) ?: return null
        if (track.isLocal && track.path != null) {
            val embedded = runCatching { AudioTagReader.read(Paths.get(track.path)).lyrics }.getOrNull()
            if (!embedded.isNullOrBlank()) return save(profileId, trackId, embedded, ORIGIN_EMBEDDED)
        }
        if (!online || !providerEnabled(LRCLIB)) return null
        val found = fetchFromLrclib(track) ?: return null
        store(profileId, trackId, found)
        return found
    }

    /** Saves lyrics the user typed or pasted. Plain text or LRC with time stamps. */
    fun saveLyrics(profileId: Long, trackId: Long, text: String): Lyrics? {
        if (library.track(profileId, trackId) == null) return null
        if (text.isBlank()) {
            clearLyrics(profileId, trackId)
            return null
        }
        return save(profileId, trackId, text, ORIGIN_MANUAL)
    }

    fun clearLyrics(profileId: Long, trackId: Long) {
        database.execute("DELETE FROM music_lyrics WHERE profile_id = ? AND track_id = ?", profileId, trackId)
    }

    private fun save(profileId: Long, trackId: Long, text: String, origin: String): Lyrics {
        val synced = parseLrc(text)
        val lyrics = Lyrics(plain = if (synced.isEmpty()) text.trim() else synced.joinToString("\n") { it.text }, synced = synced, origin = origin)
        store(profileId, trackId, lyrics, rawSynced = text.takeIf { synced.isNotEmpty() })
        return lyrics
    }

    private fun store(profileId: Long, trackId: Long, lyrics: Lyrics, rawSynced: String? = null) {
        val synced = rawSynced ?: lyrics.synced.takeIf { it.isNotEmpty() }?.joinToString("\n") { formatLrcLine(it) }
        database.execute(
            """
            INSERT INTO music_lyrics (track_id, profile_id, plain, synced, origin, fetched_at) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(track_id) DO UPDATE SET plain = excluded.plain, synced = excluded.synced, origin = excluded.origin, fetched_at = excluded.fetched_at
            """.trimIndent(),
            trackId, profileId, if (lyrics.instrumental) INSTRUMENTAL_MARK else lyrics.plain, synced, lyrics.origin, clock(),
        )
    }

    private fun stored(profileId: Long, trackId: Long): Lyrics? = database.queryOne(
        "SELECT plain, synced, origin FROM music_lyrics WHERE profile_id = ? AND track_id = ?", profileId, trackId,
    ) { rs -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }?.let { (plain, synced, origin) ->
        Lyrics(plain?.takeIf { it != INSTRUMENTAL_MARK }, synced?.let(::parseLrc).orEmpty(), origin, instrumental = plain == INSTRUMENTAL_MARK)
    }

    private fun fetchFromLrclib(track: Track): Lyrics? {
        val artist = track.artist ?: track.albumArtist ?: return null
        val parameters = buildList {
            add("artist_name" to artist)
            add("track_name" to track.title)
            track.album?.let { add("album_name" to it) }
            if (track.durationMs > 0) add("duration" to ((track.durationMs + 500) / 1000).toString())
        }
        val exact = cachedJson(LRCLIB, "get:" + MusicHttp.query(parameters), "${endpoints.lrclib}/get?" + MusicHttp.query(parameters), rateLimited = false)
        exact?.let(::toLyrics)?.let { return it }
        // No exact entry: search, and take one whose length is close to this file's.
        val search = listOf("track_name" to track.title, "artist_name" to artist)
        val results = cachedJson(LRCLIB, "search:" + MusicHttp.query(search), "${endpoints.lrclib}/search?" + MusicHttp.query(search), rateLimited = false, arrayKey = "results")
        return results?.get("results").array().mapNotNull { it.obj() }
            .filter { track.durationMs <= 0 || abs(((it.long("duration") ?: 0) * 1000) - track.durationMs) <= 4_000 }
            .firstNotNullOfOrNull(::toLyrics)
    }

    private fun toLyrics(node: JsonObject): Lyrics? {
        val synced = node.string("syncedLyrics")?.let(::parseLrc).orEmpty()
        val plain = node.string("plainLyrics") ?: synced.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }
        val instrumental = node["instrumental"]?.toString() == "true"
        if (plain == null && !instrumental) return null
        return Lyrics(plain, synced, ORIGIN_LRCLIB, instrumental)
    }


    // ---------------------------------------------------------------- MusicBrainz: artists and albums

    /** Artists whose name matches [query], best first. */
    fun searchArtists(query: String, limit: Int = 10): List<MbArtist> {
        requireProvider(MUSICBRAINZ, "MusicBrainz")
        if (query.isBlank()) return emptyList()
        val lucene = "artist:\"${escape(query.trim())}\" OR alias:\"${escape(query.trim())}\""
        val url = "${endpoints.musicBrainz}/artist?" + MusicHttp.query(listOf("query" to lucene, "fmt" to "json", "limit" to limit.coerceIn(1, 50).toString()))
        val root = cachedJson(MUSICBRAINZ, "artist:$lucene:$limit", url, rateLimited = true) ?: return emptyList()
        return root["artists"].array().mapNotNull { it.obj()?.let(::toArtist) }
    }

    /** Albums (as works, whatever their editions) titled like [title], optionally by [artist]. */
    fun searchReleaseGroups(title: String, artist: String? = null, limit: Int = 12): List<MbReleaseGroup> {
        requireProvider(MUSICBRAINZ, "MusicBrainz")
        if (title.isBlank()) return emptyList()
        val lucene = buildList {
            add("releasegroup:\"${escape(title.trim())}\"")
            if (!artist.isNullOrBlank()) add("artist:\"${escape(artist.trim())}\"")
        }.joinToString(" AND ")
        val url = "${endpoints.musicBrainz}/release-group?" + MusicHttp.query(listOf("query" to lucene, "fmt" to "json", "limit" to limit.coerceIn(1, 50).toString()))
        val root = cachedJson(MUSICBRAINZ, "release-group:$lucene:$limit", url, rateLimited = true) ?: return emptyList()
        return root["release-groups"].array().mapNotNull { it.obj()?.let(::toReleaseGroup) }
    }

    /** Editions titled like [title], optionally by [artist]. */
    fun searchReleases(title: String, artist: String? = null, limit: Int = 12): List<MbRelease> {
        requireProvider(MUSICBRAINZ, "MusicBrainz")
        if (title.isBlank()) return emptyList()
        val lucene = buildList {
            add("release:\"${escape(title.trim())}\"")
            if (!artist.isNullOrBlank()) add("artist:\"${escape(artist.trim())}\"")
        }.joinToString(" AND ")
        val url = "${endpoints.musicBrainz}/release?" + MusicHttp.query(listOf("query" to lucene, "fmt" to "json", "limit" to limit.coerceIn(1, 50).toString()))
        val root = cachedJson(MUSICBRAINZ, "release:$lucene:$limit", url, rateLimited = true) ?: return emptyList()
        return root["releases"].array().mapNotNull { it.obj()?.let(::toRelease) }
    }

    /** An album with all of its editions. Null when MusicBrainz does not know the id. */
    fun releaseGroup(id: String): MbReleaseGroupDetail? {
        requireProvider(MUSICBRAINZ, "MusicBrainz")
        val url = "${endpoints.musicBrainz}/release-group/${MusicHttp.encode(id)}?inc=releases%2Bartist-credits&fmt=json"
        val root = cachedJson(MUSICBRAINZ, "release-group-id:$id", url, rateLimited = true) ?: return null
        val group = toReleaseGroup(root) ?: return null
        val releases = root["releases"].array().mapNotNull { it.obj()?.let(::toRelease) }
            .map { it.copy(artist = it.artist ?: group.artist, releaseGroupId = group.id) }
            .sortedBy { it.date ?: "9999" }
        return MbReleaseGroupDetail(group, releases)
    }

    /** One edition with its track listing. Null when MusicBrainz does not know the id. */
    fun release(id: String): MbReleaseDetail? {
        requireProvider(MUSICBRAINZ, "MusicBrainz")
        val url = "${endpoints.musicBrainz}/release/${MusicHttp.encode(id)}?inc=recordings%2Bartist-credits%2Brelease-groups%2Blabels&fmt=json"
        val root = cachedJson(MUSICBRAINZ, "release-id:$id", url, rateLimited = true) ?: return null
        val release = toRelease(root) ?: return null
        val media = root["media"].array().mapNotNull { it.obj() }
        val tracks = media.flatMapIndexed { index, medium ->
            val disc = medium.int("position") ?: (index + 1)
            medium["tracks"].array().mapNotNull { it.obj() }.mapIndexed { trackIndex, track ->
                val recording = track["recording"].obj()
                MbTrack(
                    disc = disc,
                    position = track.int("position") ?: (trackIndex + 1),
                    number = track.string("number"),
                    title = track.string("title") ?: recording?.string("title") ?: "Track ${trackIndex + 1}",
                    artist = credit(track) ?: recording?.let(::credit),
                    lengthMs = track.long("length") ?: recording?.long("length"),
                    recordingId = recording?.string("id"),
                )
            }
        }
        val group = root["release-group"].obj()?.let(::toReleaseGroup)?.let { it.copy(artist = it.artist ?: release.artist) }
        return MbReleaseDetail(
            release = release.copy(trackCount = release.trackCount ?: tracks.size, releaseGroupId = group?.id ?: release.releaseGroupId),
            releaseGroup = group,
            tracks = tracks,
            discCount = media.size.coerceAtLeast(1),
        )
    }

    /** What an artist has released, oldest first. */
    fun artistReleaseGroups(artistId: String, limit: Int = 100): List<MbReleaseGroup> {
        requireProvider(MUSICBRAINZ, "MusicBrainz")
        val url = "${endpoints.musicBrainz}/release-group?" + MusicHttp.query(
            listOf("artist" to artistId, "limit" to limit.coerceIn(1, 100).toString(), "fmt" to "json"),
        )
        val root = cachedJson(MUSICBRAINZ, "artist-release-groups:$artistId:$limit", url, rateLimited = true) ?: return emptyList()
        return root["release-groups"].array().mapNotNull { it.obj()?.let(::toReleaseGroup) }.sortedBy { it.firstReleaseDate ?: "9999" }
    }

    /** Editions that could be [albumId] of the library, the most plausible first. */
    fun findReleasesForAlbum(profileId: Long, albumId: Long, limit: Int = 8): List<MbRelease> {
        val album = library.album(profileId, albumId) ?: return emptyList()
        val localCount = library.albumTracks(profileId, albumId).size
        val found = searchReleases(album.title, album.artist, limit = 20)
        fun rank(release: MbRelease): Int {
            var value = release.score * 10
            if (MusicText.key(release.title) == MusicText.key(album.title)) value += 120
            if (release.trackCount != null && localCount > 0) value += (60 - abs(release.trackCount - localCount) * 12).coerceAtLeast(0)
            if (album.year != null && release.year == album.year) value += 25
            if (release.status.equals("Official", ignoreCase = true)) value += 10
            return value
        }
        return found.sortedByDescending(::rank).take(limit)
    }

    /**
     * Brings the songs of an album in line with a MusicBrainz edition: each song is paired with the
     * track it most plausibly is (position, title, length), and its unlocked fields are filled in.
     * Songs that pair with nothing are left alone.
     */
    fun applyRelease(profileId: Long, albumId: Long, releaseId: String, fetchCover: Boolean = true): AlbumUpdate {
        val detail = release(releaseId) ?: throw MetadataException("MusicBrainz no longer lists this edition.")
        val local = library.albumTracks(profileId, albumId)
        val remaining = detail.tracks.toMutableList()
        var matched = 0
        var changed = 0
        val perDisc = detail.tracks.groupBy { it.disc }.mapValues { it.value.size }
        local.forEach { track ->
            val best = remaining.maxByOrNull { pairing(track, it) } ?: return@forEach
            if (pairing(track, best) < MINIMUM_PAIRING) return@forEach
            remaining.remove(best)
            matched += 1
            val values = linkedMapOf(
                TrackField.TITLE to best.title,
                TrackField.ARTIST to (best.artist ?: detail.release.artist),
                TrackField.ALBUM to detail.release.title,
                TrackField.ALBUM_ARTIST to detail.release.artist,
                TrackField.YEAR to (detail.releaseGroup?.year ?: detail.release.year)?.toString(),
                TrackField.TRACK_NO to best.position.toString(),
                TrackField.TRACK_TOTAL to perDisc[best.disc]?.toString(),
                TrackField.DISC_NO to best.disc.toString(),
                TrackField.DISC_TOTAL to detail.discCount.toString(),
            )
            changed += library.applyProviderMetadata(profileId, track.id, values).size
            library.setTrackIdentifiers(profileId, track.id, best.recordingId, detail.release.id)
        }
        // A corrected title or artist can have moved the songs to another album row.
        val newAlbumId = local.firstNotNullOfOrNull { library.track(profileId, it.id)?.albumId } ?: albumId
        library.setAlbumIdentifiers(profileId, newAlbumId, detail.release.id, detail.release.releaseGroupId)
        var coverApplied = false
        if (fetchCover && providerEnabled(COVER_ART_ARCHIVE) && library.album(profileId, newAlbumId)?.artwork == null) {
            coverApplied = runCatching {
                coverOptions(releaseId = detail.release.id).firstOrNull { it.front }?.let { applyAlbumCover(profileId, newAlbumId, it) } == true
            }.getOrDefault(false)
        }
        library.notifyLibraryChanged()
        return AlbumUpdate(newAlbumId, matched, local.size - matched, changed, coverApplied)
    }

    /** Brings one song up to date from the recording it was matched with before. */
    fun refreshTrack(profileId: Long, trackId: Long): MetadataRefresh {
        requireProvider(MUSICBRAINZ, "MusicBrainz")
        val ids = library.trackIdentifiers(profileId, trackId)
        val recordingId = ids?.recordingId ?: return MetadataRefresh.NeedsMatch
        val track = library.track(profileId, trackId) ?: return MetadataRefresh.NeedsMatch
        forget(MUSICBRAINZ, "recording-id:$recordingId")
        val url = "${endpoints.musicBrainz}/recording/${MusicHttp.encode(recordingId)}?inc=artist-credits%2Breleases%2Bmedia&fmt=json"
        val root = cachedJson(MUSICBRAINZ, "recording-id:$recordingId", url, rateLimited = true) ?: return MetadataRefresh.NeedsMatch
        val releases = root["releases"].array().mapNotNull { it.obj() }
        val release = releases.firstOrNull { it.string("id") == ids.releaseId } ?: releases.firstOrNull()
        val medium = release?.get("media").array().firstOrNull().obj()
        val match = RecordingMatch(
            recordingId = recordingId,
            title = root.string("title") ?: track.title,
            artist = credit(root),
            releaseId = release?.string("id"),
            album = release?.string("title"),
            albumArtist = release?.let(::credit) ?: credit(root),
            year = release?.string("date")?.take(4)?.toIntOrNull(),
            trackNo = medium?.get("track").array().firstOrNull().obj()?.string("number")?.toIntOrNull() ?: medium?.int("track-offset")?.plus(1),
            trackTotal = medium?.int("track-count"),
            durationMs = root.long("length"),
            score = 100,
        )
        return MetadataRefresh.Updated(apply(profileId, trackId, match, fetchCover = true))
    }

    /** Brings an album up to date from the edition it was matched with before. */
    fun refreshAlbum(profileId: Long, albumId: Long): MetadataRefresh {
        requireProvider(MUSICBRAINZ, "MusicBrainz")
        val releaseId = library.albumIdentifiers(profileId, albumId)?.releaseId ?: return MetadataRefresh.NeedsMatch
        forget(MUSICBRAINZ, "release-id:$releaseId")
        val update = applyRelease(profileId, albumId, releaseId)
        return MetadataRefresh.Updated(if (update.fieldsChanged > 0) setOf("${update.fieldsChanged} fields") else emptySet())
    }

    private fun pairing(local: Track, remote: MbTrack): Double {
        var value = 0.0
        if (local.trackNo != null && local.trackNo == remote.position && (local.discNo ?: 1) == remote.disc) value += 3.0
        val localKey = MusicText.key(local.title)
        val remoteKey = MusicText.key(remote.title)
        value += when {
            localKey == remoteKey -> 4.0
            localKey.isNotEmpty() && (remoteKey.contains(localKey) || localKey.contains(remoteKey)) -> 2.5
            else -> {
                val a = localKey.split(' ').filter { it.isNotBlank() }.toSet()
                val b = remoteKey.split(' ').filter { it.isNotBlank() }.toSet()
                if (a.isEmpty() || b.isEmpty()) 0.0 else 4.0 * a.intersect(b).size / (a.size + b.size - a.intersect(b).size)
            }
        }
        if (local.durationMs > 0 && remote.lengthMs != null) {
            val difference = abs(local.durationMs - remote.lengthMs)
            value += when {
                difference <= 3_000 -> 2.0
                difference <= 10_000 -> 1.0
                difference > 45_000 -> -2.0
                else -> 0.0
            }
        }
        return value
    }

    private fun credit(node: JsonObject): String? = node["artist-credit"].array().mapNotNull { it.obj()?.let { part ->
            (part.string("name") ?: part["artist"].obj()?.string("name")).orEmpty() + (part["joinphrase"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
    }.joinToString("").trim().takeIf { it.isNotEmpty() }

    private fun toArtist(node: JsonObject): MbArtist? {
        val id = node.string("id") ?: return null
        val life = node["life-span"].obj()
        return MbArtist(
            id = id,
            name = node.string("name") ?: return null,
            sortName = node.string("sort-name"),
            type = node.string("type"),
            country = node.string("country") ?: node["area"].obj()?.string("name"),
            disambiguation = node.string("disambiguation"),
            begin = life?.string("begin"),
            end = life?.string("end"),
            score = node.int("score") ?: 0,
        )
    }

    private fun toReleaseGroup(node: JsonObject): MbReleaseGroup? {
        val id = node.string("id") ?: return null
        return MbReleaseGroup(
            id = id,
            title = node.string("title") ?: return null,
            artist = credit(node),
            artistId = node["artist-credit"].array().firstOrNull().obj()?.get("artist").obj()?.string("id"),
            primaryType = node.string("primary-type"),
            secondaryTypes = node["secondary-types"].array().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            firstReleaseDate = node.string("first-release-date"),
            score = node.int("score") ?: 0,
        )
    }

    private fun toRelease(node: JsonObject): MbRelease? {
        val id = node.string("id") ?: return null
        val media = node["media"].array().mapNotNull { it.obj() }
        return MbRelease(
            id = id,
            title = node.string("title") ?: return null,
            artist = credit(node),
            date = node.string("date"),
            country = node.string("country"),
            status = node.string("status"),
            trackCount = node.int("track-count") ?: media.sumOf { it.int("track-count") ?: 0 }.takeIf { it > 0 },
            format = media.mapNotNull { it.string("format") }.distinct().joinToString(" + ").takeIf { it.isNotEmpty() },
            releaseGroupId = node["release-group"].obj()?.string("id"),
            label = node["label-info"].array().firstOrNull().obj()?.get("label").obj()?.string("name"),
            score = node.int("score") ?: 0,
        )
    }

    // ---------------------------------------------------------------- Cover Art Archive

    /**
     * Every image the archive holds for an edition (or, with [releaseGroupId], for the edition the
     * archive shows for the album). Empty when it holds none: many releases have no artwork.
     */
    fun coverOptions(releaseId: String? = null, releaseGroupId: String? = null): List<CoverOption> {
        requireProvider(COVER_ART_ARCHIVE, "the Cover Art Archive")
        val (kind, id) = when {
            releaseId != null -> "release" to releaseId
            releaseGroupId != null -> "release-group" to releaseGroupId
            else -> return emptyList()
        }
        val root = cachedJson(COVER_ART_ARCHIVE, "$kind:$id", "${endpoints.coverArtArchive}/$kind/${MusicHttp.encode(id)}", rateLimited = false)
            ?: return emptyList()
        val owner = root.string("release")?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: releaseId ?: id
        return root["images"].array().mapNotNull { it.obj() }.mapNotNull { image ->
            val full = image.string("image")?.let(::secure) ?: return@mapNotNull null
            val thumbnails = image["thumbnails"].obj()
            val types = image["types"].array().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            CoverOption(
                id = (image["id"] as? JsonPrimitive)?.contentOrNull ?: full,
                releaseId = owner,
                types = types,
                front = image["front"]?.toString() == "true" || "Front" in types,
                back = image["back"]?.toString() == "true" || "Back" in types,
                comment = image.string("comment"),
                thumbnailUrl = (thumbnails?.string("250") ?: thumbnails?.string("small") ?: thumbnails?.string("500") ?: full).let(::secure),
                imageUrl = (thumbnails?.string("1200") ?: thumbnails?.string("large") ?: full).let(::secure),
            )
        }.sortedWith(compareByDescending<CoverOption> { it.front }.thenByDescending { it.back })
    }

    /**
     * Covers to choose from for an album of the library: those of the edition it was matched with,
     * otherwise those of the editions MusicBrainz finds for its title and artist.
     */
    fun albumCoverOptions(profileId: Long, albumId: Long, maxReleases: Int = 3): List<CoverOption> {
        requireProvider(COVER_ART_ARCHIVE, "the Cover Art Archive")
        val ids = library.albumIdentifiers(profileId, albumId)
        ids?.releaseId?.let { known ->
            val own = coverOptions(releaseId = known)
            if (own.isNotEmpty()) return own
        }
        ids?.releaseGroupId?.let { group ->
            val shared = coverOptions(releaseGroupId = group)
            if (shared.isNotEmpty()) return shared
        }
        if (!providerEnabled(MUSICBRAINZ)) return emptyList()
        return findReleasesForAlbum(profileId, albumId, limit = maxReleases)
            .flatMap { runCatching { coverOptions(releaseId = it.id) }.getOrDefault(emptyList()) }
            .distinctBy { it.imageUrl }
    }

    /** The small preview of [option], for showing the choice. Null when it cannot be fetched. */
    fun coverPreview(option: CoverOption): Path? = cachedImage(option.thumbnailUrl)

    /** Makes [option] the cover of an album. Returns false when the image could not be stored. */
    fun applyAlbumCover(profileId: Long, albumId: Long, option: CoverOption): Boolean {
        val artworkId = downloadCover(profileId, option) ?: return false
        library.setAlbumArtwork(profileId, albumId, artworkId, custom = true)
        return true
    }

    /** Makes [option] the cover of one song. */
    fun applyTrackCover(profileId: Long, trackId: Long, option: CoverOption): Boolean {
        val artworkId = downloadCover(profileId, option) ?: return false
        library.setTrackArtwork(profileId, trackId, artworkId, custom = true)
        return true
    }

    private fun downloadCover(profileId: Long, option: CoverOption): Long? {
        requireProvider(COVER_ART_ARCHIVE, "the Cover Art Archive")
        val image = try {
            MusicHttp.get(option.imageUrl, maxBytes = 16 * 1024 * 1024)
        } catch (e: HttpStatusException) {
            if (e.status == 404) return null
            throw MetadataException("The Cover Art Archive answered with an error (${e.status}).", e)
        } catch (e: IOException) {
            throw MetadataException("The Cover Art Archive could not be reached: ${e.message ?: e::class.simpleName}", e)
        }
        return library.artwork.store(profileId, image.body, ArtworkStore.ORIGIN_ONLINE)
    }

    private fun cachedImage(url: String): Path? {
        val directory = previewDirectory ?: return null
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = directory.resolve("$name.img")
        if (Files.isRegularFile(file) && Files.size(file) > 0) return file
        return runCatching {
            Files.createDirectories(directory)
            val image = MusicHttp.get(url, maxBytes = 4 * 1024 * 1024)
            val temporary = Files.createTempFile(directory, name, ".part")
            Files.write(temporary, image.body)
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            file
        }.getOrNull()
    }

    /** The archive still lists its images with plain http addresses; they are served over https too. */
    private fun secure(url: String): String {
        if (!url.startsWith("http://")) return url
        val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
        return if (host.endsWith("coverartarchive.org") || host.endsWith("archive.org")) "https://" + url.removePrefix("http://") else url
    }

    // ---------------------------------------------------------------- lyrics: searching by hand

    /**
     * Lyrics LRCLIB offers for a song, the closest in length first. [query] replaces title and
     * artist with free text when the user wants to search differently.
     */
    fun searchLyrics(title: String, artist: String?, album: String? = null, durationMs: Long = 0, query: String? = null): List<LyricsCandidate> {
        requireProvider(LRCLIB, "LRCLIB")
        val parameters = if (!query.isNullOrBlank()) {
            listOf("q" to query.trim())
        } else {
            if (title.isBlank()) return emptyList()
            buildList {
                add("track_name" to title.trim())
                if (!artist.isNullOrBlank()) add("artist_name" to artist.trim())
                if (!album.isNullOrBlank()) add("album_name" to album.trim())
            }
        }
        val key = "search:" + MusicHttp.query(parameters)
        val results = cachedJson(LRCLIB, key, "${endpoints.lrclib}/search?" + MusicHttp.query(parameters), rateLimited = false, arrayKey = "results")
        val candidates = results?.get("results").array().mapNotNull { it.obj() }.mapNotNull { node ->
            val lyrics = toLyrics(node)
            LyricsCandidate(
                id = node.long("id") ?: return@mapNotNull null,
                title = node.string("trackName") ?: node.string("name") ?: return@mapNotNull null,
                artist = node.string("artistName"),
                album = node.string("albumName"),
                durationSeconds = (node["duration"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt(),
                instrumental = node["instrumental"]?.toString() == "true",
                synced = lyrics?.synced?.isNotEmpty() == true,
                lyrics = lyrics,
            )
        }
        val wanted = (durationMs / 1000).toInt()
        return candidates.sortedWith(
            compareBy<LyricsCandidate> { if (wanted > 0 && it.durationSeconds != null) abs(it.durationSeconds - wanted) else Int.MAX_VALUE / 2 }
                .thenByDescending { it.synced },
        )
    }

    fun searchLyricsFor(profileId: Long, trackId: Long, query: String? = null): List<LyricsCandidate> {
        val track = library.track(profileId, trackId) ?: return emptyList()
        return searchLyrics(track.title, track.artist ?: track.albumArtist, null, track.durationMs, query)
    }

    /** Saves the lyrics the user picked from a search. */
    fun applyLyrics(profileId: Long, trackId: Long, candidate: LyricsCandidate): Lyrics? {
        if (library.track(profileId, trackId) == null) return null
        val lyrics = candidate.lyrics ?: if (candidate.instrumental) Lyrics(null, emptyList(), ORIGIN_LRCLIB, instrumental = true) else return null
        store(profileId, trackId, lyrics)
        return lyrics
    }

    /**
     * Looks the lyrics up again, ignoring earlier answers. Lyrics the user typed are kept: only
     * [clearLyrics] removes those.
     */
    fun refreshLyrics(profileId: Long, trackId: Long): Lyrics? {
        val current = stored(profileId, trackId)
        if (current?.origin == ORIGIN_MANUAL) return current
        database.execute("DELETE FROM music_lyrics WHERE profile_id = ? AND track_id = ?", profileId, trackId)
        database.execute("DELETE FROM music_metadata_cache WHERE provider = ?", LRCLIB)
        return lyrics(profileId, trackId, online = true)
    }

    /** True when the stored lyrics of [trackId] are known to be missing online (nothing was found). */
    fun hasStoredLyrics(profileId: Long, trackId: Long): Boolean = stored(profileId, trackId) != null

    // ---------------------------------------------------------------- provider health

    /**
     * Asks the provider one small real question and reports whether it answered properly. The
     * result is kept and shown next to the provider.
     */
    fun testProvider(providerId: String): ProviderHealth {
        val started = System.currentTimeMillis()
        val failure: String? = try {
            when (providerId) {
                EMBEDDED_TAGS -> null
                MUSICBRAINZ -> {
                    waitForTurn()
                    val url = "${endpoints.musicBrainz}/artist?" + MusicHttp.query(listOf("query" to "artist:\"Queen\"", "fmt" to "json", "limit" to "1"))
                    val root = Json.parseToJsonElement(MusicHttp.get(url, mapOf("Accept" to "application/json")).text()).obj()
                    if (root?.get("artists") == null) "MusicBrainz answered, but not with a search result." else null
                }
                COVER_ART_ARCHIVE -> {
                    val root = Json.parseToJsonElement(MusicHttp.get("${endpoints.coverArtArchive}/release/${endpoints.coverArtProbeRelease}").text()).obj()
                    if (root?.get("images") == null) "The Cover Art Archive answered, but not with a list of images." else null
                }
                LRCLIB -> {
                    val body = Json.parseToJsonElement(MusicHttp.get("${endpoints.lrclib}/search?" + MusicHttp.query(listOf("q" to "yesterday beatles"))).text())
                    if (body !is kotlinx.serialization.json.JsonArray) "LRCLIB answered, but not with search results." else null
                }
                else -> "DebridOne has no provider called \"$providerId\"."
            }
        } catch (e: HttpStatusException) {
            if (e.status == 503 || e.status == 429) "The service is busy (it answered ${e.status}); try again in a moment." else "The service answered with an error (${e.status})."
        } catch (e: IOException) {
            "Could not be reached: ${e.message ?: e::class.simpleName}"
        } catch (e: Exception) {
            "Unexpected answer: ${e.message ?: e::class.simpleName}"
        }
        val elapsed = System.currentTimeMillis() - started
        val message = failure ?: if (providerId == EMBEDDED_TAGS) "Built in: reads the tags inside your files." else "Working (answered in $elapsed ms)"
        return recordHealth(providerId, ok = failure == null, message = message, latencyMs = elapsed.takeIf { failure == null && providerId != EMBEDDED_TAGS })
    }

    private fun recordHealth(provider: String, ok: Boolean, message: String, latencyMs: Long?): ProviderHealth {
        val entry = ProviderHealth(provider, ok, message, latencyMs, clock())
        runCatching {
            database.execute(
                "INSERT INTO music_provider_health (provider, ok, message, latency_ms, checked_at) VALUES (?, ?, ?, ?, ?) " +
                    "ON CONFLICT(provider) DO UPDATE SET ok = excluded.ok, message = excluded.message, latency_ms = excluded.latency_ms, checked_at = excluded.checked_at",
                provider, if (ok) 1 else 0, message, latencyMs, entry.checkedAt,
            )
        }
        _health.value = _health.value + (provider to entry)
        return entry
    }

    private fun loadHealth(): Map<String, ProviderHealth> = runCatching {
        database.query("SELECT provider, ok, message, latency_ms, checked_at FROM music_provider_health") { rs ->
            ProviderHealth(rs.getString(1), rs.getInt(2) == 1, rs.getString(3).orEmpty(), rs.getLong(4).takeIf { !rs.wasNull() }, rs.getLong(5))
        }.associateBy { it.providerId }
    }.getOrDefault(emptyMap())

    private fun forget(provider: String, key: String) {
        database.execute("DELETE FROM music_metadata_cache WHERE provider = ? AND cache_key = ?", provider, key)
    }

    // ---------------------------------------------------------------- plumbing

    private fun requireProvider(id: String, name: String) {
        if (!providerEnabled(id)) throw MetadataException("$name is switched off in Music's metadata providers.")
    }

    /**
     * GETs [url] as JSON, answering from the cache while the cached answer is fresh. A 404 is cached
     * as "nothing there" so the same miss is not asked again and again.
     */
    private fun cachedJson(provider: String, key: String, url: String, rateLimited: Boolean, arrayKey: String? = null): JsonObject? {
        val now = clock()
        database.queryOne("SELECT value, fetched_at FROM music_metadata_cache WHERE provider = ? AND cache_key = ?", provider, key) { rs ->
            rs.getString(1) to rs.getLong(2)
        }?.takeIf { now - it.second < CACHE_TTL_MS }?.let { (value, _) -> return parse(value, arrayKey) }

        if (rateLimited) waitForTurn()
        val started = System.currentTimeMillis()
        val text = try {
            // MusicBrainz answers 503 when it is asked too often from one address, which on a
            // shared connection happens through no fault of this client. Ask again, a little more
            // patiently each time, before telling the user it is busy.
            var attempt = 0
            var answer: String? = null
            while (answer == null) {
                try {
                    answer = MusicHttp.get(url, mapOf("Accept" to "application/json")).text()
                } catch (e: HttpStatusException) {
                    if (!rateLimited || (e.status != 503 && e.status != 429) || attempt >= BUSY_RETRIES) throw e
                    attempt += 1
                    Thread.sleep(busyRetryDelayMs * attempt)
                    waitForTurn()
                }
            }
            answer
        } catch (e: HttpStatusException) {
            if (e.status != 404) {
                val message = if (e.status == 503 || e.status == 429) "The service is busy; try again in a moment." else "The service answered with an error (${e.status})."
                recordHealth(provider, ok = false, message = message, latencyMs = null)
                throw MetadataException(message, e)
            }
            MISSING
        } catch (e: IOException) {
            val message = "The service could not be reached: ${e.message ?: e::class.simpleName}"
            recordHealth(provider, ok = false, message = message, latencyMs = null)
            throw MetadataException(message, e)
        }
        recordHealth(provider, ok = true, message = "Answering", latencyMs = System.currentTimeMillis() - started)
        database.execute(
            "INSERT INTO music_metadata_cache (provider, cache_key, value, fetched_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(provider, cache_key) DO UPDATE SET value = excluded.value, fetched_at = excluded.fetched_at",
            provider, key, text, now,
        )
        return parse(text, arrayKey)
    }

    private fun parse(text: String, arrayKey: String?): JsonObject? {
        if (text == MISSING) return null
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
        return element.obj() ?: arrayKey?.let { JsonObject(mapOf(it to element)) }
    }

    /** MusicBrainz allows one request a second per client. */
    private fun waitForTurn() {
        synchronized(rateLock) {
            val wait = lastMusicBrainzRequest + minimumRequestIntervalMs - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastMusicBrainzRequest = System.currentTimeMillis()
        }
    }

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun sameText(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    companion object {
        const val MUSICBRAINZ = "musicbrainz"
        const val COVER_ART_ARCHIVE = "cover-art-archive"
        const val LRCLIB = "lrclib"
        const val EMBEDDED_TAGS = "embedded-tags"

        /** A song and a track are taken to be the same from this pairing score up. */
        private const val MINIMUM_PAIRING = 4.0

        const val ORIGIN_EMBEDDED = "embedded"
        const val ORIGIN_LRCLIB = "lrclib"
        const val ORIGIN_MANUAL = "manual"

        private const val MISSING = "\u0000missing"
        private const val INSTRUMENTAL_MARK = "\u0000instrumental"
        private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val BUSY_RETRIES = 3

        private val timeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

        /** Reads LRC text ("[01:02.50] words"). A line may carry several time stamps. */
        fun parseLrc(text: String): List<LyricLine> {
            val lines = ArrayList<LyricLine>()
            for (raw in text.lineSequence()) {
                val tags = timeTag.findAll(raw).toList()
                if (tags.isEmpty() || tags.first().range.first != raw.indexOfFirst { !it.isWhitespace() }) continue
                val words = raw.substring(tags.last().range.last + 1).trim()
                for (tag in tags) {
                    val (minutes, seconds, fraction) = tag.destructured
                    val millis = when (fraction.length) {
                        0 -> 0
                        1 -> fraction.toLong() * 100
                        2 -> fraction.toLong() * 10
                        else -> fraction.take(3).toLong()
                    }
                    lines += LyricLine(minutes.toLong() * 60_000 + seconds.toLong() * 1000 + millis, words)
                }
            }
            return lines.sortedBy { it.timeMs }
        }

        fun formatLrcLine(line: LyricLine): String {
            val minutes = line.timeMs / 60_000
            val seconds = (line.timeMs % 60_000) / 1000
            val hundredths = (line.timeMs % 1000) / 10
            return "[%02d:%02d.%02d]%s".format(minutes, seconds, hundredths, line.text)
        }
    }
}
