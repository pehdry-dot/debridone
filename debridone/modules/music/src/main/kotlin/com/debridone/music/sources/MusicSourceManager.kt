package com.debridone.music.sources

import com.debridone.music.library.ArtworkStore
import com.debridone.music.library.MusicLibraryRepository
import com.debridone.music.library.Track
import com.debridone.music.library.TrackFilter
import com.debridone.music.library.TrackInput
import com.debridone.platform.db.SqliteDatabase
import com.debridone.platform.db.getBooleanInt
import com.debridone.platform.db.getNullableLong
import com.debridone.platform.secrets.ScopedSecretStore
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.ResultSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SyncSummary(val total: Int, val added: Int, val removed: Int, val artworkFetched: Int)

/** What the sources are doing right now, for progress display. Keyed by connection id. */
data class SourceActivity(
    val syncing: Map<Long, String> = emptyMap(),
    val downloading: Int = 0,
    val downloadTitle: String? = null,
    val lastDownloadError: String? = null,
)

/**
 * The connections a profile has to music sources outside this computer, and everything done
 * through them: sign-in, library synchronisation, stream addresses, covers and offline copies.
 *
 * Passwords and tokens live in the module's secret store (the macOS Keychain), in a namespace of
 * the profile that owns the connection. The database only ever holds the address and user name.
 */
class MusicSourceManager(
    private val database: SqliteDatabase,
    private val library: MusicLibraryRepository,
    private val secrets: ScopedSecretStore,
    private val offlineDirectory: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The kinds of server-backed source this build can talk to. */
    val adapters: List<MusicSourceAdapter> = listOf(SubsonicAdapter(), JellyfinAdapter())

    private val _activity = MutableStateFlow(SourceActivity())
    val activity: StateFlow<SourceActivity> = _activity.asStateFlow()

    fun adapter(id: String): MusicSourceAdapter? = adapters.firstOrNull { it.id == id }

    /** Where passwords and tokens are kept ("macOS Keychain"), for display. */
    val credentialStoreName: String get() = secrets.backendName

    /** True when that store is protected by the operating system. */
    val credentialStoreIsProtected: Boolean get() = secrets.isOsProtected

    /** The radio stations and stream addresses of a profile. */
    fun streams(profileId: Long): List<Track> {
        val source = connections(profileId).firstOrNull { it.adapter == STREAM_ADAPTER } ?: return emptyList()
        return library.tracks(profileId, TrackFilter(sourceId = source.id))
    }

    /** True when [track] comes from a server it can be downloaded from (not a live stream). */
    fun canDownload(profileId: Long, track: Track): Boolean =
        !track.isLocal && connection(profileId, track.sourceId)?.let { it.enabled && it.adapter != STREAM_ADAPTER } == true

    // ---------------------------------------------------------------- connections

    fun connections(profileId: Long): List<SourceConnection> = database.query(
        """
        SELECT s.*, (SELECT COUNT(*) FROM music_tracks t WHERE t.source_id = s.id) AS track_count
        FROM music_sources s WHERE s.profile_id = ? ORDER BY s.priority, s.id
        """.trimIndent(),
        profileId, map = ::mapConnection,
    )

    fun connection(profileId: Long, sourceId: Long): SourceConnection? = connections(profileId).firstOrNull { it.id == sourceId }

    /**
     * Signs in with [draft] and saves the connection only if that works. Nothing is stored for an
     * address that is unreachable, is not that kind of server, or rejects the credentials.
     */
    fun add(profileId: Long, adapterId: String, draft: SourceDraft): SourceConnection {
        val adapter = adapter(adapterId) ?: throw SourceException("This kind of source is not supported.")
        val session = try {
            adapter.connect(draft)
        } catch (e: IllegalArgumentException) {
            throw SourceException(e.message ?: "That address is not valid.")
        }
        val baseUrl = MusicHttp.normalizeBaseUrl(draft.baseUrl)
        val name = draft.name.trim().ifEmpty { session.serverName ?: adapter.displayName }.take(80)
        val now = clock()
        val id = database.transaction {
            val duplicate = queryOne(
                "SELECT id FROM music_sources WHERE profile_id = ? AND adapter = ? AND base_url = ? AND username = ?",
                profileId, adapterId, baseUrl, draft.username.trim(),
            ) { it.getLong(1) }
            if (duplicate != null) throw SourceException("This profile is already connected to that server with that user.")
            val priority = (queryOne("SELECT COALESCE(MAX(priority), 0) FROM music_sources WHERE profile_id = ?", profileId) { it.getInt(1) } ?: 0) + 1
            insert(
                """
                INSERT INTO music_sources (profile_id, adapter, name, base_url, username, config, enabled, priority, created_at, updated_at, last_test_at, last_test_ok)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, 1)
                """.trimIndent(),
                profileId, adapterId, name, baseUrl, draft.username.trim(), toJson(session.config), priority, now, now, now,
            )
        }
        try {
            profileSecrets(profileId).put(secretName(id), session.secret)
        } catch (t: Throwable) {
            database.execute("DELETE FROM music_sources WHERE id = ?", id)
            throw SourceException("The password could not be saved securely: ${t.message}", t)
        }
        library.notifyChanged()
        return checkNotNull(connection(profileId, id))
    }

    /** Removes the connection with its songs, covers, offline copies and stored password. */
    fun remove(profileId: Long, sourceId: Long) {
        val existing = connection(profileId, sourceId) ?: return
        val trackIds = library.tracks(profileId, TrackFilter(sourceId = sourceId)).map { it.id }
        library.removeTracks(profileId, trackIds)
        database.execute("DELETE FROM music_sources WHERE profile_id = ? AND id = ?", profileId, sourceId)
        database.execute("DELETE FROM music_metadata_cache WHERE provider = ?", artworkCacheName(sourceId))
        runCatching { profileSecrets(profileId).delete(secretName(existing.id)) }
        runCatching { offlineFolder(profileId, sourceId).toFile().deleteRecursively() }
        library.notifyChanged()
    }

    /**
     * Turns a source off or on. While it is off its songs stay in the library and in playlists but
     * are shown as unavailable and are not played.
     */
    fun setEnabled(profileId: Long, sourceId: Long, enabled: Boolean) {
        if (connection(profileId, sourceId) == null) return
        database.execute("UPDATE music_sources SET enabled = ?, updated_at = ? WHERE profile_id = ? AND id = ?", enabled, clock(), profileId, sourceId)
        markAvailability(profileId, sourceId, available = enabled)
    }

    fun rename(profileId: Long, sourceId: Long, name: String) {
        val clean = name.trim().take(80)
        if (clean.isEmpty()) return
        database.execute("UPDATE music_sources SET name = ?, updated_at = ? WHERE profile_id = ? AND id = ?", clean, clock(), profileId, sourceId)
        library.notifyChanged()
    }

    /** Moves the connection one place up or down in the list; sources higher up are synchronised first. */
    fun move(profileId: Long, sourceId: Long, up: Boolean) {
        database.transaction {
            val ids = query("SELECT id FROM music_sources WHERE profile_id = ? ORDER BY priority, id", profileId) { it.getLong(1) }.toMutableList()
            val index = ids.indexOf(sourceId)
            val target = if (up) index - 1 else index + 1
            if (index < 0 || target !in ids.indices) return@transaction
            ids.add(target, ids.removeAt(index))
            ids.forEachIndexed { position, id -> execute("UPDATE music_sources SET priority = ? WHERE id = ?", position + 1, id) }
        }
        library.notifyChanged()
    }

    /** Checks that the server still accepts the saved sign-in. The outcome is recorded on the connection. */
    fun test(profileId: Long, sourceId: Long): Result<Unit> {
        val existing = connection(profileId, sourceId) ?: return Result.failure(SourceException("That connection no longer exists."))
        val result = runCatching {
            if (existing.adapter == STREAM_ADAPTER) return@runCatching
            val adapter = adapter(existing.adapter) ?: throw SourceException("This kind of source is not supported.")
            adapter.test(existing, requireSecret(profileId, existing))
        }
        recordTest(sourceId, result.exceptionOrNull())
        return result
    }

    // ---------------------------------------------------------------- synchronisation

    /** Brings the library in line with the server: new songs are added, songs gone from it removed. */
    fun sync(profileId: Long, sourceId: Long): SyncSummary {
        val existing = connection(profileId, sourceId) ?: throw SourceException("That connection no longer exists.")
        if (existing.adapter == STREAM_ADAPTER) return SyncSummary(existing.trackCount, 0, 0, 0)
        if (!existing.enabled) throw SourceException("This source is turned off.")
        val adapter = adapter(existing.adapter) ?: throw SourceException("This kind of source is not supported.")
        setSyncing(sourceId, "Reading the library…")
        try {
            val secret = requireSecret(profileId, existing)
            val known = library.tracks(profileId, TrackFilter(sourceId = sourceId)).associate { it.remoteId to it.id }
            val seen = HashSet<String>()
            val artworkKeys = LinkedHashMap<String, MutableList<Long>>()
            val cachedArtwork = cachedArtworkIds(profileId, sourceId)
            var added = 0
            adapter.listTracks(existing, secret) { page ->
                library.batch {
                    with(library) {
                        for (remote in page) {
                            if (!seen.add(remote.remoteId)) continue
                            val artworkId = remote.artworkKey?.let { cachedArtwork[it] }
                            val id = upsertInTransaction(profileId, remote.toInput(sourceId, artworkId), existingId = known[remote.remoteId])
                            if (remote.remoteId !in known) added++
                            if (remote.artworkKey != null && artworkId == null) artworkKeys.getOrPut(remote.artworkKey) { ArrayList() } += id
                        }
                    }
                }
                setSyncing(sourceId, "${seen.size} songs…")
                library.notifyChanged()
            }
            val gone = known.filterKeys { it !in seen }.values
            library.removeTracks(profileId, gone)

            var fetched = 0
            for ((key, trackIds) in artworkKeys) {
                setSyncing(sourceId, "Covers ${fetched + 1} of ${artworkKeys.size}…")
                val artworkId = runCatching {
                    val image = MusicHttp.get(adapter.artworkUrl(existing, secret, key), maxBytes = 12 * 1024 * 1024)
                    library.artwork.store(profileId, image.body, ArtworkStore.ORIGIN_SOURCE)
                }.getOrNull() ?: continue
                rememberArtwork(sourceId, key, artworkId)
                trackIds.forEach { library.setTrackArtwork(profileId, it, artworkId, custom = false) }
                fetched++
            }
            library.finishScan(profileId)
            database.execute("UPDATE music_sources SET last_sync_at = ?, last_error = NULL, last_test_at = ?, last_test_ok = 1 WHERE id = ?", clock(), clock(), sourceId)
            library.notifyChanged()
            return SyncSummary(seen.size, added, gone.size, fetched)
        } catch (e: SourceException) {
            recordTest(sourceId, e)
            throw e
        } catch (e: IOException) {
            val wrapped = SourceException("The server could not be reached: ${e.message ?: e::class.simpleName}", e)
            recordTest(sourceId, wrapped)
            throw wrapped
        } finally {
            setSyncing(sourceId, null)
        }
    }

    private fun RemoteTrack.toInput(sourceId: Long, artworkId: Long?) = TrackInput(
        sourceId = sourceId,
        remoteId = remoteId,
        fileSize = sizeBytes,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        genre = genre,
        year = year,
        trackNo = trackNo,
        discNo = discNo,
        durationMs = durationMs,
        format = format?.uppercase(),
        bitrateKbps = bitrateKbps,
        artworkId = artworkId,
    )

    // ---------------------------------------------------------------- playback and offline copies

    /**
     * What the audio engine should open for [track]: the file, a downloaded copy, or the source's
     * stream address. Null when the track cannot be played right now (source off, sign-in missing).
     */
    fun resolveUri(profileId: Long, track: Track): String? {
        track.offlinePath?.takeIf { Files.isRegularFile(Paths.get(it)) }?.let { return it }
        if (track.isLocal) return track.path
        val source = connection(profileId, track.sourceId)?.takeIf { it.enabled } ?: return null
        val remoteId = track.remoteId ?: return null
        if (source.adapter == STREAM_ADAPTER) return remoteId
        val adapter = adapter(source.adapter) ?: return null
        val secret = runCatching { profileSecrets(profileId).get(secretName(source.id)) }.getOrNull() ?: return null
        return adapter.streamUrl(source, secret, remoteId)
    }

    /** Saves copies of remote songs so they play without the server. Returns how many were saved. */
    fun download(profileId: Long, trackIds: List<Long>): Int {
        var saved = 0
        val tracks = library.tracksByIds(profileId, trackIds).filter { !it.isLocal && it.offlinePath == null }
        _activity.update { it.copy(downloading = tracks.size, lastDownloadError = null) }
        try {
            for (track in tracks) {
                _activity.update { it.copy(downloadTitle = track.title) }
                try {
                    val source = connection(profileId, track.sourceId)?.takeIf { it.enabled && it.adapter != STREAM_ADAPTER }
                        ?: throw SourceException("Live streams cannot be downloaded.")
                    val adapter = adapter(source.adapter) ?: throw SourceException("This kind of source is not supported.")
                    val secret = requireSecret(profileId, source)
                    val target = offlineFolder(profileId, source.id).resolve("${track.id}.${adapter.fileExtension(track.format)}")
                    MusicHttp.download(adapter.streamUrl(source, secret, track.remoteId.orEmpty()), target)
                    library.setOfflinePath(profileId, track.id, target.toString())
                    saved++
                } catch (e: Exception) {
                    _activity.update { it.copy(lastDownloadError = "${track.title}: ${e.message ?: e::class.simpleName}") }
                }
                _activity.update { it.copy(downloading = (it.downloading - 1).coerceAtLeast(0)) }
            }
        } finally {
            _activity.update { it.copy(downloading = 0, downloadTitle = null) }
        }
        return saved
    }

    fun removeDownload(profileId: Long, trackId: Long) {
        val track = library.track(profileId, trackId) ?: return
        val path = track.offlinePath ?: return
        // Only files inside Music's own offline folder are ever deleted.
        val file = Paths.get(path).toAbsolutePath().normalize()
        if (file.startsWith(offlineDirectory.toAbsolutePath().normalize())) runCatching { Files.deleteIfExists(file) }
        library.setOfflinePath(profileId, trackId, null)
    }

    // ---------------------------------------------------------------- stream addresses

    /**
     * Adds an internet radio station or other direct audio stream. The address is opened first and
     * is accepted only if it really answers with audio (or with a playlist that points at audio).
     */
    fun addStream(profileId: Long, name: String, address: String): Track {
        val url = validateStreamAddress(address)
        val probed = try {
            MusicHttp.probe(url, mapOf("Icy-MetaData" to "0"))
        } catch (e: HttpStatusException) {
            throw SourceException("That address answered with an error (${e.status}).", e)
        } catch (e: IOException) {
            throw SourceException("That address could not be reached: ${e.message ?: e::class.simpleName}", e)
        }
        val type = probed.contentType.orEmpty().substringBefore(';').trim().lowercase()
        val streamUrl = when {
            type in PLAYLIST_TYPES || url.substringBefore('?').lowercase().let { it.endsWith(".m3u") || it.endsWith(".pls") || it.endsWith(".m3u8") } ->
                firstUrlInPlaylist(String(probed.body, Charsets.UTF_8))?.let(::validateStreamAddress)
                    ?: throw SourceException("That playlist does not point at an audio stream.")
            type.startsWith("audio/") || type == "application/ogg" -> url
            else -> throw SourceException("That address does not serve audio (it answered with “${type.ifEmpty { "an unknown type" }}”).")
        }
        val sourceId = streamSourceId(profileId)
        val title = name.trim().ifEmpty { URI(streamUrl).host ?: "Stream" }.take(120)
        val id = library.upsert(
            profileId,
            TrackInput(sourceId = sourceId, remoteId = streamUrl, title = title, genre = "Radio", format = formatOfContentType(type)),
        )
        library.editTrack(profileId, id, mapOf("title" to title))
        return checkNotNull(library.track(profileId, id))
    }

    private fun validateStreamAddress(address: String): String {
        val trimmed = address.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        if (uri == null || uri.host.isNullOrBlank() || !(uri.scheme.equals("http", true) || uri.scheme.equals("https", true))) {
            throw SourceException("Enter an http or https address.")
        }
        if (uri.userInfo != null) throw SourceException("Addresses with a user name or password in them are not accepted.")
        return trimmed
    }

    private fun firstUrlInPlaylist(text: String): String? = text.lineSequence()
        .map { it.trim().substringAfter('=', it.trim()).trim() }
        .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }

    private fun formatOfContentType(type: String): String? = when (type) {
        "audio/mpeg", "audio/mp3" -> "MP3"
        "audio/aac", "audio/aacp", "audio/mp4" -> "AAC"
        "audio/ogg", "application/ogg" -> "OGG"
        "audio/flac", "audio/x-flac" -> "FLAC"
        "audio/opus" -> "Opus"
        else -> null
    }

    /** The one "streams" pseudo-connection of a profile, created the first time a stream is added. */
    private fun streamSourceId(profileId: Long): Long = database.transaction {
        queryOne("SELECT id FROM music_sources WHERE profile_id = ? AND adapter = ?", profileId, STREAM_ADAPTER) { it.getLong(1) }
            ?: run {
                val now = clock()
                val priority = (queryOne("SELECT COALESCE(MAX(priority), 0) FROM music_sources WHERE profile_id = ?", profileId) { it.getInt(1) } ?: 0) + 1
                insert(
                    "INSERT INTO music_sources (profile_id, adapter, name, config, enabled, priority, created_at, updated_at) VALUES (?, ?, ?, '{}', 1, ?, ?, ?)",
                    profileId, STREAM_ADAPTER, "Radio and streams", priority, now, now,
                )
            }
    }

    // ---------------------------------------------------------------- profiles

    /** Forgets every connection, password and offline copy of a deleted profile. */
    fun deleteProfile(profileId: Long) {
        val store = profileSecrets(profileId)
        connections(profileId).forEach { runCatching { store.delete(secretName(it.id)) } }
        database.execute("DELETE FROM music_sources WHERE profile_id = ?", profileId)
        runCatching { offlineDirectory.resolve(profileId.toString()).toFile().deleteRecursively() }
    }

    // ---------------------------------------------------------------- internals

    private fun profileSecrets(profileId: Long): ScopedSecretStore = secrets.child("profile").child(profileId.toString())

    private fun secretName(sourceId: Long) = "source-$sourceId"

    private fun requireSecret(profileId: Long, connection: SourceConnection): String =
        runCatching { profileSecrets(profileId).get(secretName(connection.id)) }.getOrNull()
            ?: throw SourceException("The saved sign-in for ${connection.name} is missing. Remove the connection and add it again.")

    private fun offlineFolder(profileId: Long, sourceId: Long): Path = offlineDirectory.resolve(profileId.toString()).resolve(sourceId.toString())

    private fun markAvailability(profileId: Long, sourceId: Long, available: Boolean) {
        val ids = library.tracks(profileId, TrackFilter(sourceId = sourceId)).map { it.id }
        library.setMissing(ids, !available)
        library.notifyChanged()
    }

    private fun recordTest(sourceId: Long, failure: Throwable?) {
        database.execute(
            "UPDATE music_sources SET last_test_at = ?, last_test_ok = ?, last_error = ? WHERE id = ?",
            clock(), failure == null, failure?.message, sourceId,
        )
        library.notifyChanged()
    }

    private fun setSyncing(sourceId: Long, message: String?) {
        _activity.update { current ->
            current.copy(syncing = if (message == null) current.syncing - sourceId else current.syncing + (sourceId to message))
        }
    }

    private fun artworkCacheName(sourceId: Long) = "source-artwork:$sourceId"

    /** Covers already fetched from this source, by the source's own key, that still exist. */
    private fun cachedArtworkIds(profileId: Long, sourceId: Long): Map<String, Long> = database.query(
        "SELECT cache_key, value FROM music_metadata_cache WHERE provider = ?", artworkCacheName(sourceId),
    ) { rs -> rs.getString(1) to rs.getString(2).toLongOrNull() }
        .mapNotNull { (key, id) -> id?.takeIf { library.artwork.find(profileId, it) != null }?.let { key to it } }
        .toMap()

    private fun rememberArtwork(sourceId: Long, key: String, artworkId: Long) {
        database.execute(
            "INSERT INTO music_metadata_cache (provider, cache_key, value, fetched_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(provider, cache_key) DO UPDATE SET value = excluded.value, fetched_at = excluded.fetched_at",
            artworkCacheName(sourceId), key, artworkId.toString(), clock(),
        )
    }

    private fun toJson(config: Map<String, String>): String = buildJsonObject { config.forEach { (key, value) -> put(key, value) } }.toString()

    private fun mapConnection(rs: ResultSet): SourceConnection = SourceConnection(
        id = rs.getLong("id"),
        adapter = rs.getString("adapter"),
        name = rs.getString("name"),
        baseUrl = rs.getString("base_url"),
        username = rs.getString("username"),
        config = runCatching {
            (Json.parseToJsonElement(rs.getString("config")) as JsonObject).mapValues { (it.value as JsonPrimitive).content }
        }.getOrDefault(emptyMap()),
        enabled = rs.getBooleanInt("enabled"),
        priority = rs.getInt("priority"),
        lastSyncAt = rs.getNullableLong("last_sync_at"),
        lastTestAt = rs.getNullableLong("last_test_at"),
        lastTestOk = rs.getNullableLong("last_test_ok")?.let { it != 0L },
        lastError = rs.getString("last_error"),
        trackCount = rs.getInt("track_count"),
    )

    companion object {
        /** Direct stream addresses (internet radio). Not a server sign-in, so not one of [adapters]. */
        const val STREAM_ADAPTER = "stream-url"

        private val PLAYLIST_TYPES = setOf(
            "audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl",
            "audio/x-scpls", "application/pls+xml", "application/pls",
        )
    }
}
