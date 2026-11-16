package com.debridone.music.playlists

import com.debridone.music.library.ArtworkRef
import com.debridone.music.library.MusicLibraryRepository
import com.debridone.music.library.MusicScanner
import com.debridone.music.library.Track
import com.debridone.platform.db.SqliteDatabase
import com.debridone.platform.db.getNullableLong
import java.nio.file.Files
import java.nio.file.Path

data class Playlist(
    val id: Long,
    val name: String,
    val description: String?,
    /** A picture the user chose for the playlist, if any. */
    val artwork: ArtworkRef?,
    /** Covers of the first distinct albums, for a mosaic when no picture was chosen. */
    val covers: List<ArtworkRef>,
    val trackCount: Int,
    val durationMs: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A track at a position of a playlist. The same track may appear more than once. */
data class PlaylistEntry(val itemId: Long, val position: Int, val track: Track)

data class PlaylistImport(
    val playlist: Playlist,
    val matched: Int,
    /** Entries that could not be added: files that do not exist, formats not supported, URLs. */
    val skipped: List<String>,
)

/** Playlists of one profile at a time. A playlist stores references to library tracks, in order. */
class PlaylistRepository(
    private val database: SqliteDatabase,
    private val library: MusicLibraryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun playlists(profileId: Long): List<Playlist> = database.query(
        "$SELECT WHERE p.profile_id = ? ORDER BY p.name COLLATE NOCASE, p.id", profileId,
    ) { rs -> Row(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getNullableLong("artwork_id"), rs.getInt("track_count"), rs.getLong("total_duration"), rs.getLong("created_at"), rs.getLong("updated_at")) }
        .map { toPlaylist(profileId, it) }

    fun playlist(profileId: Long, playlistId: Long): Playlist? = database.queryOne(
        "$SELECT WHERE p.profile_id = ? AND p.id = ?", profileId, playlistId,
    ) { rs -> Row(rs.getLong("id"), rs.getString("name"), rs.getString("description"), rs.getNullableLong("artwork_id"), rs.getInt("track_count"), rs.getLong("total_duration"), rs.getLong("created_at"), rs.getLong("updated_at")) }
        ?.let { toPlaylist(profileId, it) }

    fun create(profileId: Long, name: String, description: String? = null): Playlist {
        val now = clock()
        val id = database.insert(
            "INSERT INTO music_playlists (profile_id, name, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            profileId, uniqueName(profileId, cleanName(name)), description?.trim()?.takeIf { it.isNotEmpty() }, now, now,
        )
        library.notifyChanged()
        return checkNotNull(playlist(profileId, id))
    }

    fun rename(profileId: Long, playlistId: Long, name: String) {
        database.execute(
            "UPDATE music_playlists SET name = ?, updated_at = ? WHERE profile_id = ? AND id = ?",
            cleanName(name), clock(), profileId, playlistId,
        )
        library.notifyChanged()
    }

    fun setDescription(profileId: Long, playlistId: Long, description: String?) {
        database.execute(
            "UPDATE music_playlists SET description = ?, updated_at = ? WHERE profile_id = ? AND id = ?",
            description?.trim()?.takeIf { it.isNotEmpty() }, clock(), profileId, playlistId,
        )
        library.notifyChanged()
    }

    fun setArtwork(profileId: Long, playlistId: Long, artworkId: Long?) {
        database.execute(
            "UPDATE music_playlists SET artwork_id = ?, updated_at = ? WHERE profile_id = ? AND id = ?",
            artworkId, clock(), profileId, playlistId,
        )
        library.artwork.deleteUnreferenced(profileId)
        library.notifyChanged()
    }

    fun delete(profileId: Long, playlistId: Long) {
        database.transaction {
            if (!owns(profileId, playlistId)) return@transaction
            execute("DELETE FROM music_playlist_items WHERE playlist_id = ?", playlistId)
            execute("DELETE FROM music_playlists WHERE id = ?", playlistId)
        }
        library.artwork.deleteUnreferenced(profileId)
        library.notifyChanged()
    }

    fun entries(profileId: Long, playlistId: Long): List<PlaylistEntry> {
        if (!database.owns(profileId, playlistId)) return emptyList()
        val items = database.query(
            "SELECT id, track_id, position FROM music_playlist_items WHERE playlist_id = ? ORDER BY position, id", playlistId,
        ) { rs -> Triple(rs.getLong(1), rs.getLong(2), rs.getInt(3)) }
        val tracks = library.tracksByIds(profileId, items.map { it.second }.distinct()).associateBy { it.id }
        return items.mapNotNull { (itemId, trackId, position) -> tracks[trackId]?.let { PlaylistEntry(itemId, position, it) } }
    }

    fun tracks(profileId: Long, playlistId: Long): List<Track> = entries(profileId, playlistId).map { it.track }

    /**
     * Appends tracks. With [skipExisting] tracks already in the playlist are left out; the number
     * actually added is returned.
     */
    fun add(profileId: Long, playlistId: Long, trackIds: List<Long>, skipExisting: Boolean = false): Int {
        if (trackIds.isEmpty()) return 0
        // Only this profile's tracks can go into this profile's playlist.
        val valid = library.tracksByIds(profileId, trackIds.distinct()).map { it.id }.toSet()
        var added = 0
        database.transaction {
            if (!owns(profileId, playlistId)) return@transaction
            val present = if (skipExisting) query("SELECT track_id FROM music_playlist_items WHERE playlist_id = ?", playlistId) { it.getLong(1) }.toMutableSet() else mutableSetOf()
            var position = queryOne("SELECT COALESCE(MAX(position), -1) FROM music_playlist_items WHERE playlist_id = ?", playlistId) { it.getInt(1) } ?: -1
            val now = clock()
            for (trackId in trackIds) {
                if (trackId !in valid) continue
                if (skipExisting && !present.add(trackId)) continue
                position++
                insert("INSERT INTO music_playlist_items (playlist_id, track_id, position, added_at) VALUES (?, ?, ?, ?)", playlistId, trackId, position, now)
                added++
            }
            execute("UPDATE music_playlists SET updated_at = ? WHERE id = ?", now, playlistId)
        }
        if (added > 0) library.notifyChanged()
        return added
    }

    fun removeItems(profileId: Long, playlistId: Long, itemIds: Collection<Long>) {
        if (itemIds.isEmpty()) return
        database.transaction {
            if (!owns(profileId, playlistId)) return@transaction
            itemIds.chunked(500).forEach { chunk ->
                execute("DELETE FROM music_playlist_items WHERE playlist_id = ? AND id IN (${chunk.joinToString(",") { "?" }})", playlistId, *chunk.toTypedArray())
            }
            renumber(playlistId)
        }
        library.notifyChanged()
    }

    /** Moves the entry at [fromIndex] so that it ends up at [toIndex]. */
    fun move(profileId: Long, playlistId: Long, fromIndex: Int, toIndex: Int) {
        database.transaction {
            if (!owns(profileId, playlistId)) return@transaction
            val ids = query("SELECT id FROM music_playlist_items WHERE playlist_id = ? ORDER BY position, id", playlistId) { it.getLong(1) }.toMutableList()
            if (fromIndex !in ids.indices || toIndex !in ids.indices || fromIndex == toIndex) return@transaction
            ids.add(toIndex, ids.removeAt(fromIndex))
            ids.forEachIndexed { index, id -> execute("UPDATE music_playlist_items SET position = ? WHERE id = ?", index, id) }
            execute("UPDATE music_playlists SET updated_at = ? WHERE id = ?", clock(), playlistId)
        }
        library.notifyChanged()
    }

    /** Writes the playlist as an extended M3U (UTF-8). Returns the number of tracks written. */
    fun exportM3u(profileId: Long, playlistId: Long, target: Path): Int {
        val lines = tracks(profileId, playlistId).mapNotNull { track ->
            val location = track.path ?: return@mapNotNull null
            M3uCodec.Line(location, listOfNotNull(track.artist, track.title).joinToString(" - "), (track.durationMs / 1000).toInt())
        }
        M3uCodec.write(target, lines)
        return lines.size
    }

    /**
     * Creates a playlist from an M3U or M3U8 file. Files it names that are not in the library yet
     * are added to it (referenced in place); entries that cannot be used are reported, not hidden.
     */
    suspend fun importM3u(profileId: Long, file: Path, scanner: MusicScanner, name: String? = null): PlaylistImport {
        val entries = M3uCodec.read(file)
        val skipped = ArrayList<String>()
        val usable = entries.filter { entry ->
            val path = entry.path
            val ok = path != null && Files.isRegularFile(path) && com.debridone.music.library.AudioTagReader.isSupported(path)
            if (!ok) skipped += entry.display
            ok
        }
        val unknown = usable.mapNotNull { it.path }.distinct().filter { library.fileRecordByPath(profileId, it.toString()) == null }
        if (unknown.isNotEmpty()) scanner.importFiles(profileId, unknown)

        val trackIds = usable.mapNotNull { entry ->
            val id = library.fileRecordByPath(profileId, entry.path.toString())?.id
            if (id == null) skipped += entry.display
            id
        }
        val playlist = create(profileId, name ?: file.fileName.toString().substringBeforeLast('.'))
        add(profileId, playlist.id, trackIds)
        return PlaylistImport(checkNotNull(playlist(profileId, playlist.id)), trackIds.size, skipped)
    }

    private class Row(val id: Long, val name: String, val description: String?, val artworkId: Long?, val trackCount: Int, val duration: Long, val createdAt: Long, val updatedAt: Long)

    private fun toPlaylist(profileId: Long, row: Row): Playlist {
        val covers = database.query(
            """
            SELECT DISTINCT aw.id, aw.file, aw.thumb, aw.dominant_color FROM music_playlist_items i
            JOIN music_tracks t ON t.id = i.track_id
            LEFT JOIN music_albums al ON al.id = t.album_id
            JOIN music_artwork aw ON aw.id = COALESCE(t.artwork_id, al.artwork_id)
            WHERE i.playlist_id = ? ORDER BY i.position LIMIT 4
            """.trimIndent(),
            row.id,
        ) { rs -> library.artwork.ref(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getNullableLong("dominant_color")?.toInt()) }
        return Playlist(
            id = row.id,
            name = row.name,
            description = row.description,
            artwork = row.artworkId?.let { library.artwork.find(profileId, it) },
            covers = covers,
            trackCount = row.trackCount,
            durationMs = row.duration,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
        )
    }

    private fun SqliteDatabase.owns(profileId: Long, playlistId: Long): Boolean =
        queryOne("SELECT 1 FROM music_playlists WHERE profile_id = ? AND id = ?", profileId, playlistId) { true } == true

    private fun SqliteDatabase.renumber(playlistId: Long) {
        val ids = query("SELECT id FROM music_playlist_items WHERE playlist_id = ? ORDER BY position, id", playlistId) { it.getLong(1) }
        ids.forEachIndexed { index, id -> execute("UPDATE music_playlist_items SET position = ? WHERE id = ?", index, id) }
        execute("UPDATE music_playlists SET updated_at = ? WHERE id = ?", clock(), playlistId)
    }

    private fun cleanName(name: String): String = name.trim().replace(Regex("\\s+"), " ").take(120).ifEmpty { "Untitled playlist" }

    /** "Road trip", "Road trip 2", ... so two playlists are never indistinguishable. */
    private fun uniqueName(profileId: Long, name: String): String {
        val taken = database.query("SELECT name FROM music_playlists WHERE profile_id = ?", profileId) { it.getString(1).lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        var suffix = 2
        while ("$name $suffix".lowercase() in taken) suffix++
        return "$name $suffix"
    }

    private companion object {
        const val SELECT = """
            SELECT p.*,
                (SELECT COUNT(*) FROM music_playlist_items i JOIN music_tracks t ON t.id = i.track_id WHERE i.playlist_id = p.id) AS track_count,
                (SELECT COALESCE(SUM(t.duration_ms), 0) FROM music_playlist_items i JOIN music_tracks t ON t.id = i.track_id WHERE i.playlist_id = p.id) AS total_duration
            FROM music_playlists p
        """
    }
}
