package com.debridone.music.metadata

import com.debridone.music.library.ArtworkStore
import com.debridone.music.library.AudioTagReader
import com.debridone.music.library.MusicLibraryRepository
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
import java.nio.file.Paths
import kotlin.math.abs
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
) {
    private val rateLock = Any()
    private var lastMusicBrainzRequest = 0L

    // ---------------------------------------------------------------- song details

    /** Candidates for [trackId] from MusicBrainz, best first. Empty when nothing plausible exists. */
    fun findRecordings(profileId: Long, trackId: Long, limit: Int = 8): List<RecordingMatch> {
        requireProvider(MUSICBRAINZ, "MusicBrainz")
        val track = library.track(profileId, trackId) ?: return emptyList()
        return findRecordings(track.title, track.artist ?: track.albumArtist, track.album, track.durationMs, limit)
    }

    fun findRecordings(title: String, artist: String?, album: String?, durationMs: Long, limit: Int = 8): List<RecordingMatch> {
        requireProvider(MUSICBRAINZ, "MusicBrainz")
        val terms = buildList {
            add("recording:\"${escape(title)}\"")
            if (!artist.isNullOrBlank()) add("artist:\"${escape(artist)}\"")
        }
        val query = terms.joinToString(" AND ")
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
        val text = try {
            MusicHttp.get(url, mapOf("Accept" to "application/json")).text()
        } catch (e: HttpStatusException) {
            if (e.status != 404) throw MetadataException(if (e.status == 503 || e.status == 429) "The service is busy; try again in a moment." else "The service answered with an error (${e.status}).", e)
            MISSING
        } catch (e: IOException) {
            throw MetadataException("The service could not be reached: ${e.message ?: e::class.simpleName}", e)
        }
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

        const val ORIGIN_EMBEDDED = "embedded"
        const val ORIGIN_LRCLIB = "lrclib"
        const val ORIGIN_MANUAL = "manual"

        private const val MISSING = "\u0000missing"
        private const val INSTRUMENTAL_MARK = "\u0000instrumental"
        private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1000

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
