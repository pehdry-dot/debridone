package com.debridone.music.library

import com.debridone.platform.db.SqliteDatabase
import com.debridone.platform.db.getBooleanInt
import com.debridone.platform.db.getNullableLong
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.ResultSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Everything needed to create or refresh one track row, whatever it came from. */
data class TrackInput(
    val sourceId: Long = Track.LOCAL_SOURCE_ID,
    val remoteId: String? = null,
    val folderId: Long? = null,
    val path: String? = null,
    val fileSize: Long = 0,
    val fileMtime: Long = 0,
    val fingerprint: String? = null,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val composer: String? = null,
    val year: Int? = null,
    val trackNo: Int? = null,
    val trackTotal: Int? = null,
    val discNo: Int? = null,
    val discTotal: Int? = null,
    val durationMs: Long = 0,
    val format: String? = null,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bitDepth: Int? = null,
    val artworkId: Long? = null,
    val musicBrainzRecordingId: String? = null,
    val musicBrainzReleaseId: String? = null,
    /** A song from an online catalogue that the user has not added to the library. */
    val hidden: Boolean = false,
)

data class HistoryEntry(val track: Track, val playedAt: Long, val listenedMs: Long, val completed: Boolean)

/** What the scanner needs to know about a row to decide whether a file changed. */
internal data class FileRecord(
    val id: Long,
    val path: String,
    val folderId: Long?,
    val size: Long,
    val mtime: Long,
    val fingerprint: String?,
    val missing: Boolean,
)

/**
 * One profile-scoped view of the Music library in `music.db`.
 *
 * Every method takes the profile id and never reads or writes another profile's rows. Writes bump
 * [revision] so screens know to query again.
 */
class MusicLibraryRepository(
    private val database: SqliteDatabase,
    val artwork: ArtworkStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _revision = MutableStateFlow(0L)

    /** Increases after every change to the library. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun changed() = _revision.update { it + 1 }

    // ------------------------------------------------------------------ folders

    fun folders(profileId: Long): List<LibraryFolder> = database.query(
        """
        SELECT f.*, (SELECT COUNT(*) FROM music_tracks t WHERE t.folder_id = f.id) AS track_count
        FROM music_folders f WHERE f.profile_id = ? ORDER BY f.path COLLATE NOCASE
        """.trimIndent(),
        profileId,
    ) { rs ->
        val path = rs.getString("path")
        LibraryFolder(
            id = rs.getLong("id"),
            path = path,
            addedAt = rs.getLong("added_at"),
            lastScanAt = rs.getNullableLong("last_scan_at"),
            trackCount = rs.getInt("track_count"),
            available = runCatching { Files.isDirectory(Paths.get(path)) }.getOrDefault(false),
        )
    }

    /** Adds [path] to the library (or returns the existing entry). The folder is not scanned here. */
    fun addFolder(profileId: Long, path: String): LibraryFolder {
        val normalized = Paths.get(path).toAbsolutePath().normalize().toString()
        database.transaction {
            val existing = queryOne("SELECT id FROM music_folders WHERE profile_id = ? AND path = ?", profileId, normalized) { it.getLong(1) }
            if (existing == null) {
                insert("INSERT INTO music_folders (profile_id, path, added_at) VALUES (?, ?, ?)", profileId, normalized, clock())
            }
        }
        changed()
        return folders(profileId).first { it.path == normalized }
    }

    /** Removes the folder and its tracks from the library. The files themselves are not touched. */
    fun removeFolder(profileId: Long, folderId: Long) {
        database.transaction {
            val ids = query("SELECT id FROM music_tracks WHERE profile_id = ? AND folder_id = ?", profileId, folderId) { it.getLong(1) }
            deleteTrackRows(profileId, ids)
            execute("DELETE FROM music_folders WHERE profile_id = ? AND id = ?", profileId, folderId)
            prune(profileId)
        }
        artwork.deleteUnreferenced(profileId)
        changed()
    }

    internal fun markFolderScanned(folderId: Long) {
        database.execute("UPDATE music_folders SET last_scan_at = ? WHERE id = ?", clock(), folderId)
    }

    // ------------------------------------------------------------------ reading tracks

    fun tracks(
        profileId: Long,
        filter: TrackFilter = TrackFilter(),
        sort: TrackSort = TrackSort.TITLE,
        descending: Boolean = false,
        limit: Int = -1,
        offset: Int = 0,
    ): List<Track> {
        val (where, args) = trackWhere(profileId, filter)
        return database.query(
            "$TRACK_SELECT WHERE $where ORDER BY ${trackOrder(sort, descending)} LIMIT ? OFFSET ?",
            *args.toTypedArray(), limit, offset, map = ::mapTrack,
        )
    }

    fun trackCount(profileId: Long, filter: TrackFilter = TrackFilter()): Int {
        val (where, args) = trackWhere(profileId, filter)
        return database.queryOne("SELECT COUNT(*) FROM music_tracks t WHERE $where", *args.toTypedArray()) { it.getInt(1) } ?: 0
    }

    fun track(profileId: Long, trackId: Long): Track? =
        database.queryOne("$TRACK_SELECT WHERE t.profile_id = ? AND t.id = ?", profileId, trackId, map = ::mapTrack)

    /** Tracks in the order of [ids]; ids that no longer exist are left out. */
    fun tracksByIds(profileId: Long, ids: List<Long>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val found = HashMap<Long, Track>()
        ids.distinct().chunked(500).forEach { chunk ->
            database.query(
                "$TRACK_SELECT WHERE t.profile_id = ? AND t.id IN (${chunk.joinToString(",") { "?" }})",
                profileId, *chunk.toTypedArray(), map = ::mapTrack,
            ).forEach { found[it.id] = it }
        }
        return ids.mapNotNull { found[it] }
    }

    fun albumTracks(profileId: Long, albumId: Long): List<Track> = database.query(
        "$TRACK_SELECT WHERE t.profile_id = ? AND t.album_id = ? ORDER BY COALESCE(t.disc_no, 1), COALESCE(t.track_no, 9999), t.title_key",
        profileId, albumId, map = ::mapTrack,
    )

    /** Everything an artist appears on: their own tracks plus tracks of albums credited to them. */
    fun artistTracks(profileId: Long, artistId: Long, sort: TrackSort = TrackSort.ALBUM): List<Track> = database.query(
        """
        $TRACK_SELECT WHERE t.profile_id = ? AND (t.artist_id = ? OR al.artist_id = ?)
        ORDER BY ${trackOrder(sort, false)}
        """.trimIndent(),
        profileId, artistId, artistId, map = ::mapTrack,
    )

    fun recentlyPlayed(profileId: Long, limit: Int): List<Track> = database.query(
        "$TRACK_SELECT WHERE t.profile_id = ? AND t.last_played_at IS NOT NULL ORDER BY t.last_played_at DESC LIMIT ?",
        profileId, limit, map = ::mapTrack,
    )

    fun mostPlayed(profileId: Long, limit: Int): List<Track> = database.query(
        "$TRACK_SELECT WHERE t.profile_id = ? AND t.play_count > 0 ORDER BY t.play_count DESC, t.last_played_at DESC LIMIT ?",
        profileId, limit, map = ::mapTrack,
    )

    // ------------------------------------------------------------------ albums, artists, genres

    fun albums(
        profileId: Long,
        sort: AlbumSort = AlbumSort.TITLE,
        descending: Boolean = false,
        text: String? = null,
        artistId: Long? = null,
        genre: String? = null,
        favoritesOnly: Boolean = false,
        limit: Int = -1,
        offset: Int = 0,
    ): List<Album> {
        val where = StringBuilder("al.profile_id = ?")
        val args = mutableListOf<Any?>(profileId)
        tokens(text).forEach { token ->
            where.append(" AND (al.title_key || ' ' || COALESCE(ar.name_key, '')) LIKE ? ESCAPE '\\'")
            args += like(token)
        }
        if (artistId != null) {
            where.append(" AND (al.artist_id = ? OR EXISTS (SELECT 1 FROM music_tracks x WHERE x.album_id = al.id AND x.artist_id = ?))")
            args += artistId
            args += artistId
        }
        if (genre != null) {
            where.append(" AND EXISTS (SELECT 1 FROM music_tracks x WHERE x.album_id = al.id AND x.genre = ? COLLATE NOCASE)")
            args += genre
        }
        if (favoritesOnly) where.append(" AND al.favorite_at IS NOT NULL")
        val direction = if (descending) "DESC" else "ASC"
        val order = when (sort) {
            AlbumSort.TITLE -> "al.title_key $direction"
            AlbumSort.ARTIST -> "COALESCE(ar.name_key, '~') $direction, al.year, al.title_key"
            AlbumSort.YEAR -> "COALESCE(al.year, 0) ${if (descending) "ASC" else "DESC"}, al.title_key"
            AlbumSort.RECENTLY_ADDED -> "al.added_at ${if (descending) "ASC" else "DESC"}, al.id DESC"
        }
        return database.query("$ALBUM_SELECT WHERE $where ORDER BY $order LIMIT ? OFFSET ?", *args.toTypedArray(), limit, offset, map = ::mapAlbum)
    }

    fun album(profileId: Long, albumId: Long): Album? =
        database.queryOne("$ALBUM_SELECT WHERE al.profile_id = ? AND al.id = ?", profileId, albumId, map = ::mapAlbum)

    fun albumCount(profileId: Long): Int =
        database.queryOne("SELECT COUNT(*) FROM music_albums WHERE profile_id = ?", profileId) { it.getInt(1) } ?: 0

    /** Albums listened to most recently, newest first. */
    fun recentlyPlayedAlbums(profileId: Long, limit: Int): List<Album> {
        val ids = database.query(
            """
            SELECT album_id FROM music_tracks WHERE profile_id = ? AND album_id IS NOT NULL AND last_played_at IS NOT NULL
            GROUP BY album_id ORDER BY MAX(last_played_at) DESC LIMIT ?
            """.trimIndent(),
            profileId, limit,
        ) { it.getLong(1) }
        if (ids.isEmpty()) return emptyList()
        val byId = database.query(
            "$ALBUM_SELECT WHERE al.profile_id = ? AND al.id IN (${ids.joinToString(",") { "?" }})",
            profileId, *ids.toTypedArray(), map = ::mapAlbum,
        ).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    fun artists(profileId: Long, text: String? = null, favoritesOnly: Boolean = false, limit: Int = -1, offset: Int = 0): List<Artist> {
        val where = StringBuilder("ar.profile_id = ?")
        val args = mutableListOf<Any?>(profileId)
        tokens(text).forEach { token ->
            where.append(" AND ar.name_key LIKE ? ESCAPE '\\'")
            args += like(token)
        }
        if (favoritesOnly) where.append(" AND ar.favorite_at IS NOT NULL")
        return database.query(
            "$ARTIST_SELECT_PREFIX WHERE $where $ARTIST_SELECT_SUFFIX ORDER BY x.sort_key LIMIT ? OFFSET ?",
            *args.toTypedArray(), limit, offset, map = ::mapArtist,
        )
    }

    fun artist(profileId: Long, artistId: Long): Artist? = database.queryOne(
        "$ARTIST_SELECT_PREFIX WHERE ar.profile_id = ? AND ar.id = ? $ARTIST_SELECT_SUFFIX", profileId, artistId, map = ::mapArtist,
    )

    fun genres(profileId: Long): List<Genre> = database.query(
        """
        SELECT MIN(genre) AS name, COUNT(*) AS track_count, COUNT(DISTINCT album_id) AS album_count
        FROM music_tracks WHERE profile_id = ? AND hidden = 0 AND genre IS NOT NULL AND genre <> ''
        GROUP BY genre COLLATE NOCASE ORDER BY genre COLLATE NOCASE
        """.trimIndent(),
        profileId,
    ) { rs -> Genre(rs.getString("name"), rs.getInt("track_count"), rs.getInt("album_count")) }

    fun search(profileId: Long, text: String, limit: Int = 40): SearchResults {
        if (tokens(text).isEmpty()) return SearchResults()
        return SearchResults(
            tracks = tracks(profileId, TrackFilter(text = text), TrackSort.TITLE, limit = limit),
            albums = albums(profileId, text = text, limit = limit),
            artists = artists(profileId, text = text, limit = limit),
        )
    }

    fun stats(profileId: Long): LibraryStats = database.queryOne(
        """
        SELECT COUNT(*), COALESCE(SUM(duration_ms), 0), COALESCE(SUM(missing), 0),
               COALESCE(SUM(CASE WHEN favorite_at IS NOT NULL THEN 1 ELSE 0 END), 0),
               (SELECT COUNT(*) FROM music_albums WHERE profile_id = ?1),
               (SELECT COUNT(*) FROM music_artists WHERE profile_id = ?1)
        FROM music_tracks WHERE profile_id = ?1 AND hidden = 0
        """.trimIndent(),
        profileId,
    ) { rs -> LibraryStats(rs.getInt(1), rs.getInt(5), rs.getInt(6), rs.getLong(2), rs.getInt(3), rs.getInt(4)) }
        ?: LibraryStats(0, 0, 0, 0, 0, 0)

    /** Files with identical audio content that are in the library more than once. */
    fun duplicateGroups(profileId: Long): List<List<Track>> {
        val fingerprints = database.query(
            """
            SELECT fingerprint FROM music_tracks WHERE profile_id = ? AND fingerprint IS NOT NULL AND source_id = 0
            GROUP BY fingerprint HAVING COUNT(*) > 1
            """.trimIndent(),
            profileId,
        ) { it.getString(1) }
        return fingerprints.map { fingerprint ->
            database.query("$TRACK_SELECT WHERE t.profile_id = ? AND t.fingerprint = ? ORDER BY t.added_at, t.id", profileId, fingerprint, map = ::mapTrack)
        }
    }

    // ------------------------------------------------------------------ favourites and history

    fun setTrackFavorite(profileId: Long, trackId: Long, favorite: Boolean) {
        database.execute(
            "UPDATE music_tracks SET favorite_at = ? WHERE profile_id = ? AND id = ?",
            if (favorite) clock() else null, profileId, trackId,
        )
        // Marking a catalogue song as a favourite is a way of keeping it.
        if (favorite) keepTracks(profileId, listOf(trackId), notify = false)
        changed()
    }

    /**
     * Adds songs that were only played from an online catalogue to the library proper: they get
     * their album and artist and show up in every list from now on.
     */
    fun keepTracks(profileId: Long, trackIds: Collection<Long>, notify: Boolean = true): Int {
        if (trackIds.isEmpty()) return 0
        var kept = 0
        database.transaction {
            trackIds.distinct().forEach { id ->
                val hidden = queryOne("SELECT hidden FROM music_tracks WHERE profile_id = ? AND id = ?", profileId, id) { it.getInt(1) == 1 }
                if (hidden == true) {
                    execute("UPDATE music_tracks SET hidden = 0, added_at = ?, updated_at = ? WHERE id = ?", clock(), clock(), id)
                    link(profileId, id)
                    kept += 1
                }
            }
        }
        if (kept > 0 && notify) changed()
        return kept
    }

    /**
     * The opposite of [keepTracks], for songs whose source lists them outside the library (a cloud
     * account): they leave the library, its albums, the favourites and the playlists, and stay
     * listed where their source shows them. Nothing is deleted anywhere else.
     */
    fun unkeepTracks(profileId: Long, trackIds: Collection<Long>): Int {
        if (trackIds.isEmpty()) return 0
        var hidden = 0
        database.transaction {
            trackIds.distinct().forEach { id ->
                val changed = execute(
                    "UPDATE music_tracks SET hidden = 1, favorite_at = NULL, updated_at = ? WHERE profile_id = ? AND id = ? AND hidden = 0 AND source_id <> 0",
                    clock(), profileId, id,
                )
                if (changed > 0) {
                    execute("DELETE FROM music_playlist_items WHERE track_id = ?", id)
                    link(profileId, id)
                    hidden += 1
                }
            }
            if (hidden > 0) prune(profileId)
        }
        if (hidden > 0) {
            artwork.deleteUnreferenced(profileId)
            changed()
        }
        return hidden
    }

    /**
     * Songs of a source that is going away (a cloud entry was deleted, an account disconnected),
     * for those the listener added to the library **and** saved to this computer: each becomes the
     * file it was saved as, keeping its place in playlists, its favourite mark and its history.
     * Returns the ids that were kept this way; the rest are the caller's to remove.
     */
    fun adoptSavedCopies(profileId: Long, trackIds: Collection<Long>): Set<Long> {
        if (trackIds.isEmpty()) return emptySet()
        val adopted = HashSet<Long>()
        database.transaction {
            trackIds.distinct().forEach { id ->
                val saved = queryOne(
                    "SELECT offline_path FROM music_tracks WHERE profile_id = ? AND id = ? AND source_id <> 0 AND hidden = 0 AND offline_path IS NOT NULL", profileId, id,
                ) { it.getString(1) } ?: return@forEach
                val file = java.nio.file.Paths.get(saved)
                if (!java.nio.file.Files.isRegularFile(file)) return@forEach
                // The same file may already be in the library as a file of a folder the listener added.
                if (queryOne("SELECT 1 FROM music_tracks WHERE profile_id = ? AND source_id = 0 AND path = ?", profileId, saved) { true } == true) return@forEach
                execute(
                    """
                    UPDATE music_tracks SET source_id = 0, remote_id = NULL, folder_id = NULL, path = ?, offline_path = NULL, missing = 0,
                        file_size = ?, file_mtime = 0, fingerprint = NULL, updated_at = ?
                    WHERE id = ?
                    """.trimIndent(),
                    saved, runCatching { java.nio.file.Files.size(file) }.getOrDefault(0L), clock(), id,
                )
                adopted += id
            }
        }
        if (adopted.isNotEmpty()) changed()
        return adopted
    }

    /** The song that is the file at [path] on this computer, when the library has it. */
    fun localTrack(profileId: Long, path: String): Track? =
        database.queryOne("$TRACK_SELECT WHERE t.profile_id = ? AND t.source_id = 0 AND t.path = ?", profileId, path, map = ::mapTrack)

    /** What the file itself says about its sound, for a song whose source could not know. */
    fun setAudioDetails(profileId: Long, trackId: Long, durationMs: Long, format: String?, codec: String?, bitrateKbps: Int?, sampleRate: Int?, channels: Int?, bitDepth: Int?) {
        database.execute(
            """
            UPDATE music_tracks SET duration_ms = CASE WHEN ? > 0 THEN ? ELSE duration_ms END, format = COALESCE(?, format), codec = COALESCE(?, codec),
                bitrate_kbps = COALESCE(?, bitrate_kbps), sample_rate = COALESCE(?, sample_rate), channels = COALESCE(?, channels), bit_depth = COALESCE(?, bit_depth)
            WHERE profile_id = ? AND id = ?
            """.trimIndent(),
            durationMs, durationMs, format, codec, bitrateKbps, sampleRate, channels, bitDepth, profileId, trackId,
        )
    }

    /**
     * Forgets catalogue songs that were never added to the library, are in no playlist, and were
     * last played (or first seen) before [before]. Returns how many were removed.
     */
    fun purgeHidden(profileId: Long, before: Long, exceptSourceIds: Collection<Long> = emptyList()): Int {
        // Songs of a source that lists outside the library on purpose (a cloud account) are that source's listing, not leftovers.
        val kept = if (exceptSourceIds.isEmpty()) "" else " AND t.source_id NOT IN (${exceptSourceIds.joinToString(",")})"
        val ids = database.query(
            """
            SELECT id FROM music_tracks t WHERE t.profile_id = ? AND t.hidden = 1 AND COALESCE(t.last_played_at, t.added_at) < ?$kept
              AND NOT EXISTS (SELECT 1 FROM music_playlist_items i WHERE i.track_id = t.id)
            """.trimIndent(),
            profileId, before,
        ) { it.getLong(1) }
        if (ids.isNotEmpty()) removeTracks(profileId, ids)
        return ids.size
    }

    // ------------------------------------------------------------------ MusicBrainz identifiers

    data class TrackIdentifiers(val recordingId: String?, val releaseId: String?)

    data class AlbumIdentifiers(val releaseId: String?, val releaseGroupId: String?)

    fun trackIdentifiers(profileId: Long, trackId: Long): TrackIdentifiers? = database.queryOne(
        "SELECT musicbrainz_recording_id, musicbrainz_release_id FROM music_tracks WHERE profile_id = ? AND id = ?", profileId, trackId,
    ) { rs -> TrackIdentifiers(rs.getString(1), rs.getString(2)) }

    fun setTrackIdentifiers(profileId: Long, trackId: Long, recordingId: String?, releaseId: String?) {
        database.execute(
            "UPDATE music_tracks SET musicbrainz_recording_id = COALESCE(?, musicbrainz_recording_id), " +
                "musicbrainz_release_id = COALESCE(?, musicbrainz_release_id) WHERE profile_id = ? AND id = ?",
            recordingId, releaseId, profileId, trackId,
        )
    }

    fun albumIdentifiers(profileId: Long, albumId: Long): AlbumIdentifiers? = database.queryOne(
        "SELECT musicbrainz_release_id, musicbrainz_release_group_id FROM music_albums WHERE profile_id = ? AND id = ?", profileId, albumId,
    ) { rs -> AlbumIdentifiers(rs.getString(1), rs.getString(2)) }

    fun setAlbumIdentifiers(profileId: Long, albumId: Long, releaseId: String?, releaseGroupId: String?) {
        database.execute(
            "UPDATE music_albums SET musicbrainz_release_id = COALESCE(?, musicbrainz_release_id), " +
                "musicbrainz_release_group_id = COALESCE(?, musicbrainz_release_group_id) WHERE profile_id = ? AND id = ?",
            releaseId, releaseGroupId, profileId, albumId,
        )
    }

    fun artistIdentifier(profileId: Long, artistId: Long): String? = database.queryOne(
        "SELECT musicbrainz_id FROM music_artists WHERE profile_id = ? AND id = ?", profileId, artistId,
    ) { rs -> rs.getString(1) }

    fun setArtistIdentifier(profileId: Long, artistId: Long, musicBrainzId: String?) {
        database.execute("UPDATE music_artists SET musicbrainz_id = ? WHERE profile_id = ? AND id = ?", musicBrainzId, profileId, artistId)
    }

    fun setAlbumFavorite(profileId: Long, albumId: Long, favorite: Boolean) {
        database.execute(
            "UPDATE music_albums SET favorite_at = ? WHERE profile_id = ? AND id = ?",
            if (favorite) clock() else null, profileId, albumId,
        )
        changed()
    }

    fun setArtistFavorite(profileId: Long, artistId: Long, favorite: Boolean) {
        database.execute(
            "UPDATE music_artists SET favorite_at = ? WHERE profile_id = ? AND id = ?",
            if (favorite) clock() else null, profileId, artistId,
        )
        changed()
    }

    /**
     * Records a listen. It counts as a play when the track finished, or was heard for at least half
     * its length or four minutes, whichever is shorter.
     */
    fun recordPlay(profileId: Long, trackId: Long, listenedMs: Long, completed: Boolean) {
        val now = clock()
        database.transaction {
            val duration = queryOne("SELECT duration_ms FROM music_tracks WHERE profile_id = ? AND id = ?", profileId, trackId) { it.getLong(1) }
                ?: return@transaction
            insert(
                "INSERT INTO music_history (profile_id, track_id, played_at, listened_ms, completed) VALUES (?, ?, ?, ?, ?)",
                profileId, trackId, now, listenedMs, completed,
            )
            val threshold = if (duration > 0) minOf(duration / 2, 240_000L) else 30_000L
            val counts = completed || listenedMs >= threshold
            execute(
                "UPDATE music_tracks SET last_played_at = ?, play_count = play_count + ? WHERE id = ?",
                now, if (counts) 1 else 0, trackId,
            )
            // Keep the log bounded per profile.
            execute(
                """
                DELETE FROM music_history WHERE profile_id = ?1 AND id <=
                    (SELECT id FROM music_history WHERE profile_id = ?1 ORDER BY id DESC LIMIT 1 OFFSET $HISTORY_LIMIT)
                """.trimIndent(),
                profileId,
            )
        }
        changed()
    }

    fun history(profileId: Long, limit: Int = 200): List<HistoryEntry> {
        val rows = database.query(
            "SELECT track_id, played_at, listened_ms, completed FROM music_history WHERE profile_id = ? ORDER BY id DESC LIMIT ?",
            profileId, limit,
        ) { rs -> listOf(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }
        val tracks = tracksByIds(profileId, rows.map { it[0] }.distinct()).associateBy { it.id }
        return rows.mapNotNull { row -> tracks[row[0]]?.let { HistoryEntry(it, row[1], row[2], row[3] != 0L) } }
    }

    fun clearHistory(profileId: Long) {
        database.transaction {
            execute("DELETE FROM music_history WHERE profile_id = ?", profileId)
            execute("UPDATE music_tracks SET last_played_at = NULL, play_count = 0 WHERE profile_id = ?", profileId)
        }
        changed()
    }

    // ------------------------------------------------------------------ writing tracks

    /**
     * Creates the track or refreshes it from its file or source. Fields the user locked keep their
     * value; the freshly read values are still remembered as the track's original.
     */
    fun upsert(profileId: Long, input: TrackInput, existingId: Long? = null, notify: Boolean = true): Long {
        val id = database.transaction { upsertInTransaction(profileId, input, existingId) }
        if (notify) changed()
        return id
    }

    internal fun SqliteDatabase.upsertInTransaction(profileId: Long, input: TrackInput, existingId: Long?): Long {
        val now = clock()
        val fresh = Fields.of(input)
        val existing = existingId ?: findExisting(profileId, input)
        val id: Long
        if (existing == null) {
            id = insert(
                """
                INSERT INTO music_tracks (profile_id, source_id, remote_id, folder_id, path, file_size, file_mtime, fingerprint,
                    format, codec, bitrate_kbps, sample_rate, channels, bit_depth, duration_ms,
                    title, title_key, artwork_id, original_tags, musicbrainz_recording_id, musicbrainz_release_id, added_at, updated_at, hidden)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                profileId, input.sourceId, input.remoteId, input.folderId, input.path, input.fileSize, input.fileMtime, input.fingerprint,
                input.format, input.codec, input.bitrateKbps, input.sampleRate, input.channels, input.bitDepth, input.durationMs,
                fresh.title, MusicText.sortKey(fresh.title), input.artworkId, fresh.toJson(input.artworkId),
                input.musicBrainzRecordingId, input.musicBrainzReleaseId, now, now, if (input.hidden) 1 else 0,
            )
            writeFields(id, fresh)
        } else {
            id = existing
            val current = checkNotNull(queryOne("SELECT * FROM music_tracks WHERE id = ? AND profile_id = ?", id, profileId) { rs ->
                Triple(Fields.of(rs), lockedSet(rs.getString("locked_fields")), rs.getNullableLong("artwork_id") to rs.getBooleanInt("custom_artwork"))
            }) { "Track $id does not belong to profile $profileId" }
            val locked = current.second
            val merged = current.first.mergedWith(fresh, locked)
            val (currentArtwork, customArtwork) = current.third
            val artworkId = if (customArtwork || TrackField.ARTWORK in locked) currentArtwork else input.artworkId ?: currentArtwork
            execute(
                """
                UPDATE music_tracks SET folder_id = ?, path = ?, file_size = ?, file_mtime = ?, fingerprint = ?, missing = 0,
                    format = ?, codec = ?, bitrate_kbps = ?, sample_rate = ?, channels = ?, bit_depth = ?,
                    duration_ms = CASE WHEN ? > 0 THEN ? ELSE duration_ms END,
                    artwork_id = ?, original_tags = ?,
                    musicbrainz_recording_id = COALESCE(?, musicbrainz_recording_id),
                    musicbrainz_release_id = COALESCE(?, musicbrainz_release_id), updated_at = ?,
                    hidden = CASE WHEN ? = 1 THEN hidden ELSE 0 END
                WHERE id = ?
                """.trimIndent(),
                input.folderId, input.path, input.fileSize, input.fileMtime, input.fingerprint,
                input.format, input.codec, input.bitrateKbps, input.sampleRate, input.channels, input.bitDepth,
                input.durationMs, input.durationMs, artworkId, fresh.toJson(input.artworkId),
                input.musicBrainzRecordingId, input.musicBrainzReleaseId, now, if (input.hidden) 1 else 0, id,
            )
            writeFields(id, merged)
        }
        link(profileId, id)
        return id
    }

    private fun SqliteDatabase.findExisting(profileId: Long, input: TrackInput): Long? =
        if (input.sourceId == Track.LOCAL_SOURCE_ID) {
            input.path?.let { queryOne("SELECT id FROM music_tracks WHERE profile_id = ? AND source_id = 0 AND path = ?", profileId, it) { rs -> rs.getLong(1) } }
        } else {
            input.remoteId?.let {
                queryOne("SELECT id FROM music_tracks WHERE profile_id = ? AND source_id = ? AND remote_id = ?", profileId, input.sourceId, it) { rs -> rs.getLong(1) }
            }
        }

    /**
     * Applies the user's own corrections. Each edited field is locked so that later rescans and
     * metadata lookups leave it alone. A null or blank value clears the field.
     */
    fun editTrack(profileId: Long, trackId: Long, changes: Map<String, String?>) {
        if (changes.isEmpty()) return
        database.transaction {
            val (fields, locked) = loadFields(profileId, trackId) ?: return@transaction
            var edited = fields
            changes.forEach { (field, value) -> edited = edited.with(field, value) }
            writeFields(trackId, edited)
            execute(
                "UPDATE music_tracks SET locked_fields = ?, updated_at = ? WHERE id = ?",
                (locked + changes.keys.filter { it in TrackField.all }).sorted().joinToString(","), clock(), trackId,
            )
            link(profileId, trackId)
            prune(profileId)
        }
        changed()
    }

    /**
     * Applies values found by a metadata provider. Locked fields are skipped; the fields that were
     * actually changed are returned.
     */
    fun applyProviderMetadata(profileId: Long, trackId: Long, values: Map<String, String?>): Set<String> {
        val applied = HashSet<String>()
        database.transaction {
            val (fields, locked) = loadFields(profileId, trackId) ?: return@transaction
            var updated = fields
            values.forEach { (field, value) ->
                if (field in locked || value.isNullOrBlank()) return@forEach
                val next = updated.with(field, value)
                if (next != updated) {
                    updated = next
                    applied += field
                }
            }
            if (applied.isNotEmpty()) {
                writeFields(trackId, updated)
                execute("UPDATE music_tracks SET updated_at = ? WHERE id = ?", clock(), trackId)
                link(profileId, trackId)
                prune(profileId)
            }
        }
        if (applied.isNotEmpty()) changed()
        return applied
    }

    fun setFieldLocked(profileId: Long, trackId: Long, field: String, locked: Boolean) {
        if (field !in TrackField.all) return
        database.transaction {
            val current = queryOne("SELECT locked_fields FROM music_tracks WHERE profile_id = ? AND id = ?", profileId, trackId) { lockedSet(it.getString(1)) }
                ?: return@transaction
            val next = if (locked) current + field else current - field
            execute("UPDATE music_tracks SET locked_fields = ? WHERE id = ?", next.sorted().joinToString(","), trackId)
        }
        changed()
    }

    /** Puts back what the file (or source) itself says and removes every lock. */
    fun restoreOriginal(profileId: Long, trackId: Long) {
        database.transaction {
            val original = queryOne("SELECT original_tags FROM music_tracks WHERE profile_id = ? AND id = ?", profileId, trackId) { it.getString(1) }
                ?: return@transaction
            val (fields, originalArtwork) = Fields.fromJson(original) ?: return@transaction
            writeFields(trackId, fields)
            execute(
                "UPDATE music_tracks SET locked_fields = '', custom_artwork = 0, artwork_id = ?, updated_at = ? WHERE id = ?",
                originalArtwork, clock(), trackId,
            )
            link(profileId, trackId)
            prune(profileId)
        }
        artwork.deleteUnreferenced(profileId)
        changed()
    }

    /** The values the file itself carries, for showing next to the edited ones. */
    fun originalFields(profileId: Long, trackId: Long): Map<String, String?>? = database.queryOne(
        "SELECT original_tags FROM music_tracks WHERE profile_id = ? AND id = ?", profileId, trackId,
    ) { it.getString(1) }?.let { Fields.fromJson(it)?.first?.toMap() }

    /** Sets a picture the user chose. It is kept through rescans until the original is restored. */
    fun setTrackArtwork(profileId: Long, trackId: Long, artworkId: Long?, custom: Boolean = true) {
        database.transaction {
            val locked = queryOne("SELECT locked_fields FROM music_tracks WHERE profile_id = ? AND id = ?", profileId, trackId) { lockedSet(it.getString(1)) }
                ?: return@transaction
            val next = if (custom) locked + TrackField.ARTWORK else locked
            execute(
                "UPDATE music_tracks SET artwork_id = ?, custom_artwork = ?, locked_fields = ?, updated_at = ? WHERE id = ?",
                artworkId, custom, next.sorted().joinToString(","), clock(), trackId,
            )
            if (artworkId != null && !custom) {
                // A cover that arrived after the album was created also becomes the album's cover.
                execute(
                    "UPDATE music_albums SET artwork_id = ? WHERE id = (SELECT album_id FROM music_tracks WHERE id = ?) AND artwork_id IS NULL AND custom_artwork = 0",
                    artworkId, trackId,
                )
            }
        }
        changed()
    }

    fun setAlbumArtwork(profileId: Long, albumId: Long, artworkId: Long?, custom: Boolean = true) {
        database.execute(
            "UPDATE music_albums SET artwork_id = ?, custom_artwork = ? WHERE profile_id = ? AND id = ?",
            artworkId, custom && artworkId != null, profileId, albumId,
        )
        changed()
    }

    /** Drops a chosen album cover and goes back to the one the album's files carry. */
    fun resetAlbumArtwork(profileId: Long, albumId: Long) {
        database.execute(
            """
            UPDATE music_albums SET custom_artwork = 0, artwork_id =
                (SELECT t.artwork_id FROM music_tracks t WHERE t.album_id = ?1 AND t.artwork_id IS NOT NULL
                 ORDER BY COALESCE(t.disc_no, 1), COALESCE(t.track_no, 9999) LIMIT 1)
            WHERE profile_id = ?2 AND id = ?1
            """.trimIndent(),
            albumId, profileId,
        )
        artwork.deleteUnreferenced(profileId)
        changed()
    }

    fun setArtistArtwork(profileId: Long, artistId: Long, artworkId: Long?) {
        database.execute("UPDATE music_artists SET artwork_id = ? WHERE profile_id = ? AND id = ?", artworkId, profileId, artistId)
        changed()
    }

    /** Fills in a duration the tag reader could not determine, once the player has measured it. */
    fun updateDuration(profileId: Long, trackId: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val updated = database.execute(
            "UPDATE music_tracks SET duration_ms = ? WHERE profile_id = ? AND id = ? AND duration_ms <= 0", durationMs, profileId, trackId,
        )
        if (updated > 0) changed()
    }

    fun setOfflinePath(profileId: Long, trackId: Long, path: String?) {
        database.execute("UPDATE music_tracks SET offline_path = ? WHERE profile_id = ? AND id = ?", path, profileId, trackId)
        changed()
    }

    /** Removes tracks from the library (and from playlists and history). Files are not deleted. */
    fun removeTracks(profileId: Long, trackIds: Collection<Long>) {
        if (trackIds.isEmpty()) return
        database.transaction {
            deleteTrackRows(profileId, trackIds)
            prune(profileId)
        }
        artwork.deleteUnreferenced(profileId)
        changed()
    }

    fun removeMissing(profileId: Long): Int {
        val ids = database.query("SELECT id FROM music_tracks WHERE profile_id = ? AND missing = 1", profileId) { it.getLong(1) }
        removeTracks(profileId, ids)
        return ids.size
    }

    /** Erases everything [profileId] owns in the library. */
    fun deleteProfile(profileId: Long) {
        database.transaction {
            execute("DELETE FROM music_playlist_items WHERE playlist_id IN (SELECT id FROM music_playlists WHERE profile_id = ?)", profileId)
            for (table in PROFILE_TABLES) execute("DELETE FROM $table WHERE profile_id = ?", profileId)
        }
        artwork.deleteProfile(profileId)
        changed()
    }

    // ------------------------------------------------------------------ scanner support

    internal fun fileRecords(profileId: Long, folderId: Long?): List<FileRecord> {
        val condition = if (folderId == null) "folder_id IS NULL" else "folder_id = ?"
        val args = if (folderId == null) arrayOf<Any?>(profileId) else arrayOf<Any?>(profileId, folderId)
        return database.query(
            "SELECT id, path, folder_id, file_size, file_mtime, fingerprint, missing FROM music_tracks WHERE profile_id = ? AND source_id = 0 AND $condition",
            *args, map = ::mapFileRecord,
        )
    }

    internal fun fileRecordsByFingerprint(profileId: Long, fingerprint: String): List<FileRecord> = database.query(
        "SELECT id, path, folder_id, file_size, file_mtime, fingerprint, missing FROM music_tracks WHERE profile_id = ? AND source_id = 0 AND fingerprint = ?",
        profileId, fingerprint, map = ::mapFileRecord,
    )

    internal fun fileRecordByPath(profileId: Long, path: String): FileRecord? = database.queryOne(
        "SELECT id, path, folder_id, file_size, file_mtime, fingerprint, missing FROM music_tracks WHERE profile_id = ? AND source_id = 0 AND path = ?",
        profileId, path, map = ::mapFileRecord,
    )

    internal fun setMissing(trackIds: Collection<Long>, missing: Boolean) {
        if (trackIds.isEmpty()) return
        database.transaction {
            trackIds.chunked(500).forEach { chunk ->
                execute("UPDATE music_tracks SET missing = ? WHERE id IN (${chunk.joinToString(",") { "?" }})", missing, *chunk.toTypedArray())
            }
        }
    }

    /** Runs a batch of scanner writes in one transaction. */
    internal fun <T> batch(block: SqliteDatabase.() -> T): T = database.transaction(block)

    internal fun finishScan(profileId: Long) {
        database.transaction { prune(profileId) }
        artwork.deleteUnreferenced(profileId)
        changed()
    }

    internal fun notifyChanged() = changed()

    /** Tells every screen that shows the library to read it again. */
    fun notifyLibraryChanged() = changed()

    // ------------------------------------------------------------------ internals

    private fun SqliteDatabase.deleteTrackRows(profileId: Long, trackIds: Collection<Long>) {
        trackIds.chunked(500).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            val args = chunk.toTypedArray()
            execute("DELETE FROM music_playlist_items WHERE track_id IN ($marks)", *args)
            execute("DELETE FROM music_history WHERE track_id IN ($marks)", *args)
            execute("DELETE FROM music_lyrics WHERE track_id IN ($marks)", *args)
            execute("DELETE FROM music_tracks WHERE profile_id = ? AND id IN ($marks)", profileId, *args)
        }
    }

    private fun SqliteDatabase.loadFields(profileId: Long, trackId: Long): Pair<Fields, Set<String>>? =
        queryOne("SELECT * FROM music_tracks WHERE profile_id = ? AND id = ?", profileId, trackId) { rs ->
            Fields.of(rs) to lockedSet(rs.getString("locked_fields"))
        }

    private fun SqliteDatabase.writeFields(trackId: Long, fields: Fields) {
        execute(
            """
            UPDATE music_tracks SET title = ?, title_key = ?, artist = ?, album = ?, album_artist = ?, genre = ?, composer = ?,
                year = ?, track_no = ?, track_total = ?, disc_no = ?, disc_total = ?, search_text = ? WHERE id = ?
            """.trimIndent(),
            fields.title, MusicText.sortKey(fields.title), fields.artist, fields.album, fields.albumArtist, fields.genre, fields.composer,
            fields.year, fields.trackNo, fields.trackTotal, fields.discNo, fields.discTotal, fields.searchText(), trackId,
        )
    }

    /** Points the track at its artist and album rows, creating them when they do not exist yet. */
    private fun SqliteDatabase.link(profileId: Long, trackId: Long) {
        val row = queryOne("SELECT artist, album, album_artist, year, artwork_id FROM music_tracks WHERE id = ?", trackId) { rs ->
            LinkRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getNullableLong("year")?.toInt(), rs.getNullableLong("artwork_id"))
        } ?: return
        // A song that is not in the library yet creates no album or artist there.
        val hidden = queryOne("SELECT hidden FROM music_tracks WHERE id = ?", trackId) { it.getInt(1) == 1 } == true
        if (hidden) {
            execute("UPDATE music_tracks SET artist_id = NULL, album_id = NULL WHERE id = ?", trackId)
            return
        }
        val artistId = row.artist?.let { artistId(profileId, it) }
        val albumId = row.album?.let { title ->
            val albumArtist = row.albumArtist ?: row.artist
            val albumArtistId = albumArtist?.let { artistId(profileId, it) }
            albumId(profileId, title, albumArtistId, MusicText.key(albumArtist), row.year, row.artworkId)
        }
        execute("UPDATE music_tracks SET artist_id = ?, album_id = ? WHERE id = ?", artistId, albumId, trackId)
    }

    private fun SqliteDatabase.artistId(profileId: Long, name: String): Long {
        val key = MusicText.key(name)
        return queryOne("SELECT id FROM music_artists WHERE profile_id = ? AND name_key = ?", profileId, key) { it.getLong(1) }
            ?: insert("INSERT INTO music_artists (profile_id, name, name_key) VALUES (?, ?, ?)", profileId, name, key)
    }

    private fun SqliteDatabase.albumId(profileId: Long, title: String, artistId: Long?, artistKey: String, year: Int?, artworkId: Long?): Long {
        val titleKey = MusicText.key(title)
        val existing = queryOne(
            "SELECT id, year, artwork_id FROM music_albums WHERE profile_id = ? AND title_key = ? AND artist_key = ?", profileId, titleKey, artistKey,
        ) { rs -> Triple(rs.getLong(1), rs.getNullableLong("year"), rs.getNullableLong("artwork_id")) }
        if (existing == null) {
            return insert(
                "INSERT INTO music_albums (profile_id, title, title_key, artist_id, artist_key, year, artwork_id, added_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                profileId, title, titleKey, artistId, artistKey, year, artworkId, clock(),
            )
        }
        if ((existing.second == null && year != null) || (existing.third == null && artworkId != null)) {
            execute(
                "UPDATE music_albums SET year = COALESCE(year, ?), artwork_id = COALESCE(artwork_id, ?) WHERE id = ?",
                year, artworkId, existing.first,
            )
        }
        return existing.first
    }

    /** Drops albums and artists that no track refers to any more. */
    private fun SqliteDatabase.prune(profileId: Long) {
        execute(
            "DELETE FROM music_albums WHERE profile_id = ?1 AND id NOT IN (SELECT album_id FROM music_tracks WHERE profile_id = ?1 AND album_id IS NOT NULL)",
            profileId,
        )
        execute(
            """
            DELETE FROM music_artists WHERE profile_id = ?1
              AND id NOT IN (SELECT artist_id FROM music_tracks WHERE profile_id = ?1 AND artist_id IS NOT NULL)
              AND id NOT IN (SELECT artist_id FROM music_albums WHERE profile_id = ?1 AND artist_id IS NOT NULL)
            """.trimIndent(),
            profileId,
        )
    }

    private fun trackWhere(profileId: Long, filter: TrackFilter): Pair<String, List<Any?>> {
        val where = StringBuilder("t.profile_id = ?")
        val args = mutableListOf<Any?>(profileId)
        if (!filter.includeHidden) where.append(" AND t.hidden = 0")
        tokens(filter.text).forEach { token ->
            where.append(" AND t.search_text LIKE ? ESCAPE '\\'")
            args += like(token)
        }
        if (filter.favoritesOnly) where.append(" AND t.favorite_at IS NOT NULL")
        filter.genre?.let {
            where.append(" AND t.genre = ? COLLATE NOCASE")
            args += it
        }
        filter.artistId?.let {
            where.append(" AND t.artist_id = ?")
            args += it
        }
        filter.albumId?.let {
            where.append(" AND t.album_id = ?")
            args += it
        }
        filter.sourceId?.let {
            where.append(" AND t.source_id = ?")
            args += it
        }
        filter.sourceIds?.let { ids ->
            where.append(if (ids.isEmpty()) " AND 0" else " AND t.source_id IN (${ids.joinToString(",")})")
        }
        if (filter.missingOnly) where.append(" AND t.missing = 1")
        if (filter.offlineOnly) where.append(" AND (t.source_id = 0 OR t.offline_path IS NOT NULL) AND t.missing = 0")
        filter.format?.let {
            where.append(" AND t.format = ? COLLATE NOCASE")
            args += it
        }
        return where.toString() to args
    }

    private fun trackOrder(sort: TrackSort, descending: Boolean): String {
        val direction = if (descending) "DESC" else "ASC"
        val reverse = if (descending) "ASC" else "DESC"
        val albumOrder = "COALESCE(t.disc_no, 1), COALESCE(t.track_no, 9999), t.title_key"
        return when (sort) {
            TrackSort.TITLE -> "t.title_key $direction, t.id"
            // A song that is only listed (not in the library) has no album or artist of its own: its text is used.
            TrackSort.ARTIST -> "COALESCE(ar.sort_key, LOWER(t.artist), '~') $direction, COALESCE(al.title_key, LOWER(t.album), '~'), $albumOrder"
            TrackSort.ALBUM -> "COALESCE(al.title_key, LOWER(t.album), '~') $direction, $albumOrder"
            TrackSort.RECENTLY_ADDED -> "t.added_at $reverse, COALESCE(al.title_key, '~'), $albumOrder"
            TrackSort.YEAR -> "COALESCE(t.year, 0) $reverse, COALESCE(al.title_key, '~'), $albumOrder"
            TrackSort.DURATION -> "t.duration_ms $reverse, t.title_key"
            TrackSort.MOST_PLAYED -> "t.play_count $reverse, t.title_key"
            TrackSort.RECENTLY_PLAYED -> "COALESCE(t.last_played_at, 0) $reverse, t.title_key"
        }
    }

    private fun tokens(text: String?): List<String> = MusicText.key(text).split(' ').filter { it.isNotEmpty() }.take(8)

    private fun like(token: String): String = "%" + token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun mapArtwork(rs: ResultSet): ArtworkRef? {
        val id = rs.getNullableLong("aw_id") ?: return null
        return artwork.ref(id, rs.getString("aw_file"), rs.getString("aw_thumb"), rs.getNullableLong("aw_color")?.toInt())
    }

    private fun mapTrack(rs: ResultSet): Track = Track(
        id = rs.getLong("id"),
        sourceId = rs.getLong("source_id"),
        remoteId = rs.getString("remote_id"),
        path = rs.getString("path"),
        missing = rs.getBooleanInt("missing"),
        title = rs.getString("title"),
        artist = rs.getString("artist"),
        album = rs.getString("album"),
        albumArtist = rs.getString("album_artist"),
        genre = rs.getString("genre"),
        composer = rs.getString("composer"),
        year = rs.getNullableLong("year")?.toInt(),
        trackNo = rs.getNullableLong("track_no")?.toInt(),
        trackTotal = rs.getNullableLong("track_total")?.toInt(),
        discNo = rs.getNullableLong("disc_no")?.toInt(),
        discTotal = rs.getNullableLong("disc_total")?.toInt(),
        durationMs = rs.getLong("duration_ms"),
        format = rs.getString("format"),
        codec = rs.getString("codec"),
        bitrateKbps = rs.getNullableLong("bitrate_kbps")?.toInt(),
        sampleRate = rs.getNullableLong("sample_rate")?.toInt(),
        channels = rs.getNullableLong("channels")?.toInt(),
        bitDepth = rs.getNullableLong("bit_depth")?.toInt(),
        fileSize = rs.getLong("file_size"),
        albumId = rs.getNullableLong("album_id"),
        artistId = rs.getNullableLong("artist_id"),
        artwork = mapArtwork(rs),
        favorite = rs.getNullableLong("favorite_at") != null,
        playCount = rs.getInt("play_count"),
        lastPlayedAt = rs.getNullableLong("last_played_at"),
        addedAt = rs.getLong("added_at"),
        lockedFields = lockedSet(rs.getString("locked_fields")),
        offlinePath = rs.getString("offline_path"),
        hidden = rs.getBooleanInt("hidden"),
    )

    private fun mapAlbum(rs: ResultSet): Album = Album(
        id = rs.getLong("id"),
        title = rs.getString("title"),
        artist = rs.getString("artist_name"),
        artistId = rs.getNullableLong("artist_id"),
        year = rs.getNullableLong("year")?.toInt(),
        trackCount = rs.getInt("track_count"),
        durationMs = rs.getLong("total_duration"),
        artwork = mapArtwork(rs),
        favorite = rs.getNullableLong("favorite_at") != null,
        addedAt = rs.getLong("added_at"),
    )

    private fun mapArtist(rs: ResultSet): Artist = Artist(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        albumCount = rs.getInt("album_count"),
        trackCount = rs.getInt("track_count"),
        artwork = mapArtwork(rs),
        favorite = rs.getNullableLong("favorite_at") != null,
    )

    private fun mapFileRecord(rs: ResultSet): FileRecord = FileRecord(
        id = rs.getLong("id"),
        path = rs.getString("path").orEmpty(),
        folderId = rs.getNullableLong("folder_id"),
        size = rs.getLong("file_size"),
        mtime = rs.getLong("file_mtime"),
        fingerprint = rs.getString("fingerprint"),
        missing = rs.getBooleanInt("missing"),
    )

    private data class LinkRow(val artist: String?, val album: String?, val albumArtist: String?, val year: Int?, val artworkId: Long?)

    /** The user-visible, editable metadata of a track. */
    private data class Fields(
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
    ) {
        fun with(field: String, raw: String?): Fields {
            val text = MusicText.clean(raw)
            val number = text?.toIntOrNull()?.takeIf { it > 0 }
            return when (field) {
                TrackField.TITLE -> if (text == null) this else copy(title = text)
                TrackField.ARTIST -> copy(artist = text)
                TrackField.ALBUM -> copy(album = text)
                TrackField.ALBUM_ARTIST -> copy(albumArtist = text)
                TrackField.GENRE -> copy(genre = text)
                TrackField.COMPOSER -> copy(composer = text)
                TrackField.YEAR -> copy(year = number)
                TrackField.TRACK_NO -> copy(trackNo = number)
                TrackField.TRACK_TOTAL -> copy(trackTotal = number)
                TrackField.DISC_NO -> copy(discNo = number)
                TrackField.DISC_TOTAL -> copy(discTotal = number)
                else -> this
            }
        }

        fun toMap(): Map<String, String?> = linkedMapOf(
            TrackField.TITLE to title,
            TrackField.ARTIST to artist,
            TrackField.ALBUM to album,
            TrackField.ALBUM_ARTIST to albumArtist,
            TrackField.GENRE to genre,
            TrackField.COMPOSER to composer,
            TrackField.YEAR to year?.toString(),
            TrackField.TRACK_NO to trackNo?.toString(),
            TrackField.TRACK_TOTAL to trackTotal?.toString(),
            TrackField.DISC_NO to discNo?.toString(),
            TrackField.DISC_TOTAL to discTotal?.toString(),
        )

        /** Takes [fresh] values except for fields in [locked], which keep this object's value. */
        fun mergedWith(fresh: Fields, locked: Set<String>): Fields {
            if (locked.isEmpty()) return fresh
            val mine = toMap()
            var merged = fresh
            locked.forEach { field -> if (field in mine) merged = merged.with(field, mine[field]) }
            // `with` ignores a null title, which is right: a title can never be empty.
            return merged
        }

        fun searchText(): String = listOf(title, artist, album, albumArtist, genre, composer, year?.toString())
            .mapNotNull { MusicText.key(it).takeIf(String::isNotEmpty) }.joinToString(" ")

        fun toJson(artworkId: Long?): String = buildJsonObject {
            toMap().forEach { (key, value) -> put(key, value) }
            put(TrackField.ARTWORK, artworkId)
        }.toString()

        companion object {
            fun of(input: TrackInput) = Fields(
                title = MusicText.clean(input.title) ?: "Untitled",
                artist = MusicText.clean(input.artist),
                album = MusicText.clean(input.album),
                albumArtist = MusicText.clean(input.albumArtist),
                genre = MusicText.clean(input.genre),
                composer = MusicText.clean(input.composer),
                year = input.year,
                trackNo = input.trackNo,
                trackTotal = input.trackTotal,
                discNo = input.discNo,
                discTotal = input.discTotal,
            )

            fun of(rs: ResultSet) = Fields(
                title = rs.getString("title"),
                artist = rs.getString("artist"),
                album = rs.getString("album"),
                albumArtist = rs.getString("album_artist"),
                genre = rs.getString("genre"),
                composer = rs.getString("composer"),
                year = rs.getNullableLong("year")?.toInt(),
                trackNo = rs.getNullableLong("track_no")?.toInt(),
                trackTotal = rs.getNullableLong("track_total")?.toInt(),
                discNo = rs.getNullableLong("disc_no")?.toInt(),
                discTotal = rs.getNullableLong("disc_total")?.toInt(),
            )

            fun fromJson(json: String): Pair<Fields, Long?>? = runCatching {
                val node = Json.parseToJsonElement(json) as JsonObject
                fun text(key: String): String? = (node[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
                val title = text(TrackField.TITLE) ?: return null
                Fields(
                    title = title,
                    artist = text(TrackField.ARTIST),
                    album = text(TrackField.ALBUM),
                    albumArtist = text(TrackField.ALBUM_ARTIST),
                    genre = text(TrackField.GENRE),
                    composer = text(TrackField.COMPOSER),
                    year = text(TrackField.YEAR)?.toIntOrNull(),
                    trackNo = text(TrackField.TRACK_NO)?.toIntOrNull(),
                    trackTotal = text(TrackField.TRACK_TOTAL)?.toIntOrNull(),
                    discNo = text(TrackField.DISC_NO)?.toIntOrNull(),
                    discTotal = text(TrackField.DISC_TOTAL)?.toIntOrNull(),
                ) to node[TrackField.ARTWORK]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            }.getOrNull()
        }
    }

    private companion object {
        const val HISTORY_LIMIT = 5_000

        val PROFILE_TABLES = listOf(
            "music_history", "music_lyrics", "music_playlists", "music_playback_state", "music_sources",
            "music_tracks", "music_albums", "music_artists", "music_folders",
        )

        fun lockedSet(value: String?): Set<String> = value.orEmpty().split(',').filter { it.isNotBlank() }.toSet()

        /** The artwork a track shows: its own, unless the album was given a custom cover. */
        const val TRACK_SELECT = """
            SELECT t.*, aw.id AS aw_id, aw.file AS aw_file, aw.thumb AS aw_thumb, aw.dominant_color AS aw_color
            FROM music_tracks t
            LEFT JOIN (SELECT id, name_key AS sort_key FROM music_artists) ar ON ar.id = t.artist_id
            LEFT JOIN music_albums al ON al.id = t.album_id
            LEFT JOIN music_artwork aw ON aw.id = (CASE
                WHEN t.custom_artwork = 1 THEN t.artwork_id
                WHEN al.custom_artwork = 1 THEN al.artwork_id
                ELSE COALESCE(t.artwork_id, al.artwork_id) END)
        """

        const val ALBUM_SELECT = """
            SELECT al.*, ar.name AS artist_name,
                (SELECT COUNT(*) FROM music_tracks x WHERE x.album_id = al.id) AS track_count,
                (SELECT COALESCE(SUM(x.duration_ms), 0) FROM music_tracks x WHERE x.album_id = al.id) AS total_duration,
                aw.id AS aw_id, aw.file AS aw_file, aw.thumb AS aw_thumb, aw.dominant_color AS aw_color
            FROM music_albums al
            LEFT JOIN music_artists ar ON ar.id = al.artist_id
            LEFT JOIN music_artwork aw ON aw.id = al.artwork_id
        """

        /** An artist without a picture of their own borrows the cover of their newest album. */
        const val ARTIST_SELECT_PREFIX = """
            SELECT x.*, aw.id AS aw_id, aw.file AS aw_file, aw.thumb AS aw_thumb, aw.dominant_color AS aw_color FROM (
                SELECT ar.id, ar.name, ar.name_key AS sort_key, ar.favorite_at,
                    (SELECT COUNT(*) FROM music_albums a WHERE a.artist_id = ar.id) AS album_count,
                    (SELECT COUNT(*) FROM music_tracks t WHERE t.profile_id = ar.profile_id AND
                        (t.artist_id = ar.id OR t.album_id IN (SELECT a.id FROM music_albums a WHERE a.artist_id = ar.id))) AS track_count,
                    COALESCE(ar.artwork_id,
                        (SELECT a.artwork_id FROM music_albums a WHERE a.artist_id = ar.id AND a.artwork_id IS NOT NULL ORDER BY a.year DESC LIMIT 1),
                        (SELECT t.artwork_id FROM music_tracks t WHERE t.artist_id = ar.id AND t.artwork_id IS NOT NULL LIMIT 1)) AS shown_artwork
                FROM music_artists ar
        """

        const val ARTIST_SELECT_SUFFIX = ") x LEFT JOIN music_artwork aw ON aw.id = x.shown_artwork"
    }
}
