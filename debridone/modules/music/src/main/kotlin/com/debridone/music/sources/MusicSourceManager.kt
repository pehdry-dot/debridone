package com.debridone.music.sources

import com.debridone.music.library.ArtworkStore
import com.debridone.music.library.MusicLibraryRepository
import com.debridone.music.library.Track
import com.debridone.music.library.AudioTagReader
import com.debridone.music.library.TrackField
import com.debridone.music.library.TrackFilter
import com.debridone.music.library.TrackInput
import com.debridone.platform.db.SqliteDatabase
import com.debridone.platform.db.getBooleanInt
import com.debridone.platform.db.getNullableLong
import com.debridone.music.playback.DEFERRED_SCHEME
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

/** The outcome of setting a plugin up: its connection, and the streams of a list that were left out. */
data class PluginSetup(val connection: SourceConnection, val problems: List<String>)

/** What the sources are doing right now, for progress display. Keyed by connection id. */
data class SourceActivity(
    val syncing: Map<Long, String> = emptyMap(),
    val downloading: Int = 0,
    val downloadTitle: String? = null,
    val lastDownloadError: String? = null,
    /** What the application said when it was handed songs to save ("3 downloads queued"). */
    val downloadNote: String? = null,
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
    /** The kinds of source this build can talk to. Each is a tested adapter; nothing else is accepted. */
    val adapters: List<MusicSourceAdapter> = builtInAdapters(),
    /** Where covers shown while browsing a catalogue are kept. Null: they are fetched each time. */
    private val artworkCacheDirectory: Path? = null,
) {

    private val _activity = MutableStateFlow(SourceActivity())
    val activity: StateFlow<SourceActivity> = _activity.asStateFlow()

    fun adapter(id: String): MusicSourceAdapter? = adapters.firstOrNull { it.id == id }

    /** Where passwords and tokens are kept ("macOS Keychain"), for display. */
    val credentialStoreName: String get() = secrets.backendName

    /** True when that store is protected by the operating system. */
    val credentialStoreIsProtected: Boolean get() = secrets.isOsProtected

    /** The radio stations and stream addresses of a profile. */
    fun streams(profileId: Long): List<Track> {
        return connections(profileId).filter { it.adapter == STREAM_ADAPTER }
            .flatMap { library.tracks(profileId, TrackFilter(sourceId = it.id)) }
    }

    /** True when [track] comes from a server it can be downloaded from (not a live stream). */
    fun canDownload(profileId: Long, track: Track): Boolean =
        !track.isLocal && connection(profileId, track.sourceId)?.let { it.enabled && adapter(it.adapter)?.allowsDownload(it) == true } == true

    // ---------------------------------------------------------------- connections

    fun connections(profileId: Long): List<SourceConnection> = database.query(
        """
        SELECT s.*, (SELECT COUNT(*) FROM music_tracks t WHERE t.source_id = s.id AND t.hidden = 0) AS track_count
        FROM music_sources s WHERE s.profile_id = ? ORDER BY s.priority, s.id
        """.trimIndent(),
        profileId, map = ::mapConnection,
    )

    fun connection(profileId: Long, sourceId: Long): SourceConnection? = connections(profileId).firstOrNull { it.id == sourceId }

    /**
     * Signs in with [draft] and saves the connection only if that works. Nothing is stored for an
     * address that is unreachable, is not that kind of server, or rejects the credentials.
     */
    fun add(profileId: Long, adapterId: String, draft: SourceDraft, pluginId: String? = null): SourceConnection {
        val adapter = adapter(adapterId) ?: throw SourceException("This kind of source is not supported.")
        val address = draft.baseUrl.ifBlank { adapter.defaultBaseUrl.orEmpty() }
        val session = try {
            adapter.connect(draft.copy(baseUrl = address))
        } catch (e: IllegalArgumentException) {
            throw SourceException(e.message ?: "That address is not valid.")
        }
        // An address that may carry a token is never written down: what is saved in its place is
        // the part the adapter says may be shown, and the addon's id tells two of them apart.
        val baseUrl = if (adapter.addressIsSecret) session.publicAddress.orEmpty() else MusicHttp.normalizeBaseUrl(address)
        val username = session.account ?: draft.username.trim()
        val name = draft.name.trim().ifEmpty { session.serverName ?: adapter.displayName }.take(80)
        val now = clock()
        val id = database.transaction {
            val duplicate = queryOne(
                "SELECT id FROM music_sources WHERE profile_id = ? AND adapter = ? AND base_url = ? AND username = ?",
                profileId, adapterId, baseUrl, username,
            ) { it.getLong(1) }
            if (duplicate != null) {
                throw SourceException(
                    when {
                        adapter.addressIsSecret -> "${session.serverName ?: "This addon"} is already installed for this profile. Remove it first to add it with another address."
                        adapter.needsCredentials -> "This profile is already connected to that server with that user."
                        else -> "${adapter.displayName} is already set up for this profile."
                    },
                )
            }
            val priority = (queryOne("SELECT COALESCE(MAX(priority), 0) FROM music_sources WHERE profile_id = ?", profileId) { it.getInt(1) } ?: 0) + 1
            insert(
                """
                INSERT INTO music_sources (profile_id, adapter, name, base_url, username, config, enabled, priority, created_at, updated_at, last_test_at, last_test_ok, plugin_id)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, 1, ?)
                """.trimIndent(),
                profileId, adapterId, name, baseUrl, username, toJson(session.config), priority, now, now, now, pluginId,
            )
        }
        try {
            // A source that needs no sign-in leaves nothing in the Keychain.
            if (session.secret.isNotEmpty()) profileSecrets(profileId).put(secretName(id), session.secret)
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
        val trackIds = library.tracks(profileId, TrackFilter(sourceId = sourceId, includeHidden = true)).map { it.id }
        // What the listener added and saved from a cloud account stays, as the files it was saved as.
        val kept = if (adapter(existing.adapter)?.listsOutsideLibrary == true || existing.adapter == CloudLibraryAdapter.ID) library.adoptSavedCopies(profileId, trackIds) else emptySet()
        library.removeTracks(profileId, trackIds - kept)
        database.execute("DELETE FROM music_sources WHERE profile_id = ? AND id = ?", profileId, sourceId)
        database.execute("DELETE FROM music_metadata_cache WHERE provider IN (?, ?)", artworkCacheName(sourceId), listingCacheName(sourceId))
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
            val secret = requireSecret(profileId, existing)
            adapter.test(existing, secret)
            adapter.refreshedConfig(existing, secret)?.let { fresh -> updateConfig(profileId, sourceId) { old -> keepChoices(old) + fresh } }
            Unit
        }
        recordTest(sourceId, result.exceptionOrNull())
        return result
    }

    /** What the user chose for a connection (an addon's settings, whether saving is allowed) outlives a new manifest. */
    private fun keepChoices(config: Map<String, String>): Map<String, String> =
        config.filterKeys { it.startsWith(AddonManifest.VALUE_PREFIX) || it == EclipseAddonAdapter.DOWNLOADS_KEY }

    private fun updateConfig(profileId: Long, sourceId: Long, change: (Map<String, String>) -> Map<String, String>) {
        val existing = connection(profileId, sourceId) ?: return
        database.execute(
            "UPDATE music_sources SET config = ?, updated_at = ? WHERE profile_id = ? AND id = ?",
            toJson(change(existing.config)), clock(), profileId, sourceId,
        )
        library.notifyChanged()
    }

    // ---------------------------------------------------------------- synchronisation

    /** Brings the library in line with the server: new songs are added, songs gone from it removed. */
    fun sync(profileId: Long, sourceId: Long): SyncSummary {
        val existing = connection(profileId, sourceId) ?: throw SourceException("That connection no longer exists.")
        if (existing.adapter == STREAM_ADAPTER) return SyncSummary(existing.trackCount, 0, 0, 0)
        if (!existing.enabled) throw SourceException("This source is turned off.")
        val adapter = adapter(existing.adapter) ?: throw SourceException("This kind of source is not supported.")
        // A catalogue is searched, not copied: there is nothing to synchronise.
        if (!adapter.syncsLibrary) return SyncSummary(existing.trackCount, 0, 0, 0)
        setSyncing(sourceId, "Reading the library…")
        try {
            val secret = requireSecret(profileId, existing)
            // Songs listed outside the library are known too: they must be recognised, and removed when they are gone.
            val knownTracks = library.tracks(profileId, TrackFilter(sourceId = sourceId, includeHidden = true))
            val known = knownTracks.associate { it.remoteId to it.id }
            // A song saved to this computer is described by its file's own tags from then on: what its
            // source guesses from a file name does not replace them.
            val describedByFile = if (adapter.listsOutsideLibrary) knownTracks.filter { it.offlinePath != null }.mapNotNullTo(HashSet()) { it.remoteId } else emptySet()
            val seen = HashSet<String>()
            val artworkKeys = LinkedHashMap<String, MutableList<Long>>()
            val cachedArtwork = cachedArtworkIds(profileId, sourceId)
            var added = 0
            adapter.listTracks(existing, secret, listingMemory(sourceId)) { page ->
                library.batch {
                    with(library) {
                        for (remote in page) {
                            if (!seen.add(remote.remoteId) || remote.remoteId in describedByFile) continue
                            val artworkId = remote.artworkKey?.let { cachedArtwork[it] }
                            // A song the user already added stays added; a new one starts where its source lists it.
                            val id = upsertInTransaction(profileId, remote.toInput(sourceId, artworkId).copy(hidden = adapter.listsOutsideLibrary), existingId = known[remote.remoteId])
                            if (remote.remoteId !in known) added++
                            if (remote.artworkKey != null && artworkId == null) artworkKeys.getOrPut(remote.artworkKey) { ArrayList() } += id
                        }
                    }
                }
                setSyncing(sourceId, "${seen.size} songs…")
                library.notifyChanged()
            }
            val gone = known.filterKeys { it !in seen }.values
            // A cloud entry that was deleted takes its listing with it, not the songs the listener added and saved.
            val kept = if (adapter.listsOutsideLibrary) library.adoptSavedCopies(profileId, gone) else emptySet()
            library.removeTracks(profileId, gone - kept)

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


    // ---------------------------------------------------------------- catalogues: search and discovery

    /** The profile's enabled sources that can be searched, in the user's order. */
    fun catalogs(profileId: Long): List<SourceConnection> =
        connections(profileId).filter { it.enabled && adapter(it.adapter) is MusicCatalogAdapter }

    fun capabilities(connection: SourceConnection): CatalogCapabilities =
        (adapter(connection.adapter) as? MusicCatalogAdapter)?.let { adapter -> runCatching { adapter.capabilities(connection) }.getOrNull() } ?: CatalogCapabilities()

    fun search(profileId: Long, sourceId: Long, query: String, limit: Int = 24): CatalogResults =
        onCatalog(profileId, sourceId) { adapter, connection, secret -> adapter.search(connection, secret, query.trim(), limit) }

    /** Songs the source puts forward (trending on Audius, random on a personal server). */
    fun featured(profileId: Long, sourceId: Long, genre: String? = null, limit: Int = 30): List<RemoteTrack> =
        onCatalog(profileId, sourceId) { adapter, connection, secret -> adapter.featured(connection, secret, genre, limit) }

    fun genres(profileId: Long, sourceId: Long): List<String> =
        onCatalog(profileId, sourceId) { adapter, connection, secret -> adapter.genres(connection, secret) }

    fun artistTracks(profileId: Long, sourceId: Long, artistId: String, limit: Int = 60): List<RemoteTrack> =
        onCatalog(profileId, sourceId) { adapter, connection, secret -> adapter.artistTracks(connection, secret, artistId, limit) }

    fun albumTracks(profileId: Long, sourceId: Long, albumId: String): List<RemoteTrack> =
        onCatalog(profileId, sourceId) { adapter, connection, secret -> adapter.albumTracks(connection, secret, albumId) }

    fun playlistTracks(profileId: Long, sourceId: Long, playlistId: String): List<RemoteTrack> =
        onCatalog(profileId, sourceId) { adapter, connection, secret -> adapter.playlistTracks(connection, secret, playlistId) }

    /** The playlists the signed-in user keeps on a personal server. */
    fun ownPlaylists(profileId: Long, sourceId: Long): List<RemotePlaylist> =
        onCatalog(profileId, sourceId) { adapter, connection, secret -> adapter.ownPlaylists(connection, secret) }

    private fun <T> onCatalog(profileId: Long, sourceId: Long, block: (MusicCatalogAdapter, SourceConnection, String) -> T): T {
        val connection = connection(profileId, sourceId) ?: throw SourceException("That source no longer exists.")
        if (!connection.enabled) throw SourceException("${connection.name} is turned off.")
        val adapter = adapter(connection.adapter) as? MusicCatalogAdapter ?: throw SourceException("${connection.name} cannot be searched.")
        return try {
            block(adapter, connection, requireSecret(profileId, connection)).also {
                if (connection.lastError != null) recordTest(sourceId, null)
            }
        } catch (e: SourceException) {
            recordTest(sourceId, e)
            throw e
        } catch (e: IOException) {
            val wrapped = SourceException("${connection.name} could not be reached: ${e.message ?: e::class.simpleName}", e)
            recordTest(sourceId, wrapped)
            throw wrapped
        }
    }

    /**
     * Makes catalogue results playable: each becomes a song of the profile that plays from the
     * source. With [keep] the songs join the library; without it they stay out of every list
     * except the listening history until the user adds them. Songs the source will not play are
     * left out. The result keeps the order of [tracks].
     */
    fun materialize(profileId: Long, sourceId: Long, tracks: List<RemoteTrack>, keep: Boolean): List<Track> {
        val connection = connection(profileId, sourceId) ?: throw SourceException("That source no longer exists.")
        val playable = tracks.filter { it.playable }.distinctBy { it.remoteId }
        if (playable.isEmpty()) return emptyList()
        val cached = cachedArtworkIds(profileId, connection.id)
        val ids = library.batch {
            with(library) {
                playable.map { remote ->
                    val artworkId = (remote.artworkKey ?: remote.artworkUrl)?.let { cached[it] }
                    upsertInTransaction(profileId, remote.toInput(connection.id, artworkId).copy(hidden = !keep), existingId = null)
                }
            }
        }
        library.notifyChanged()
        val byId = library.tracksByIds(profileId, ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /**
     * Fetches the covers of songs that came from a catalogue and have none yet. Slow (one request a
     * cover), so it is run after playback has started. Returns how many covers were stored.
     */
    fun fetchArtwork(profileId: Long, sourceId: Long, tracks: List<RemoteTrack>): Int {
        val connection = connection(profileId, sourceId) ?: return 0
        val adapter = adapter(connection.adapter) ?: return 0
        val secret = runCatching { requireSecret(profileId, connection) }.getOrNull() ?: return 0
        val cached = cachedArtworkIds(profileId, sourceId).toMutableMap()
        var stored = 0
        for (remote in tracks.distinctBy { it.remoteId }) {
            val key = remote.artworkKey ?: remote.artworkUrl ?: continue
            val trackId = database.queryOne(
                "SELECT id FROM music_tracks WHERE profile_id = ? AND source_id = ? AND remote_id = ? AND artwork_id IS NULL AND custom_artwork = 0",
                profileId, sourceId, remote.remoteId,
            ) { it.getLong(1) } ?: continue
            val artworkId = cached[key] ?: run {
                val bytes = downloadArtwork(adapter, connection, secret, remote.artworkKey, remote.artworkUrl, remote.artworkAlternates) ?: return@run null
                library.artwork.store(profileId, bytes, ArtworkStore.ORIGIN_SOURCE)?.also {
                    rememberArtwork(sourceId, key, it)
                    cached[key] = it
                }
            } ?: continue
            library.setTrackArtwork(profileId, trackId, artworkId, custom = false)
            stored++
        }
        if (stored > 0) library.notifyChanged()
        return stored
    }

    /**
     * The cover of something shown while browsing a catalogue, as a local file. Null when the
     * source has none or it cannot be fetched; that is ordinary and shows as a placeholder.
     */
    fun artworkFile(profileId: Long, sourceId: Long, artworkKey: String?, artworkUrl: String?, alternates: List<String> = emptyList()): Path? {
        val key = artworkKey ?: artworkUrl ?: return null
        val directory = artworkCacheDirectory ?: return null
        val name = java.security.MessageDigest.getInstance("SHA-1").digest("$sourceId|$key".toByteArray()).joinToString("") { "%02x".format(it) }
        val file = directory.resolve("$name.img")
        if (Files.isRegularFile(file) && Files.size(file) > 0) return file
        val connection = connection(profileId, sourceId) ?: return null
        val adapter = adapter(connection.adapter) ?: return null
        val secret = runCatching { requireSecret(profileId, connection) }.getOrNull() ?: return null
        val bytes = downloadArtwork(adapter, connection, secret, artworkKey, artworkUrl, alternates) ?: return null
        return runCatching {
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, name, ".part")
            Files.write(temporary, bytes)
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            file
        }.getOrNull()
    }

    private fun downloadArtwork(
        adapter: MusicSourceAdapter,
        connection: SourceConnection,
        secret: String,
        artworkKey: String?,
        artworkUrl: String?,
        alternates: List<String>,
    ): ByteArray? {
        val addresses = buildList {
            artworkKey?.let { add(adapter.artworkUrl(connection, secret, it)) }
            artworkUrl?.let(::add)
            addAll(alternates)
        }.filter { it.startsWith("https://", true) || it.startsWith("http://", true) }.distinct()
        for (address in addresses.take(6)) {
            val image = runCatching { MusicHttp.get(address, maxBytes = 12 * 1024 * 1024) }.getOrNull() ?: continue
            if (image.body.isNotEmpty() && image.contentType.orEmpty().lowercase().let { it.startsWith("image/") || it.isEmpty() || it.startsWith("application/octet-stream") }) {
                return image.body
            }
        }
        return null
    }

    // ---------------------------------------------------------------- plugins installed from a manifest

    /** The manifests installed in this copy of DebridOne. Each profile connects to them separately. */
    fun manifests(): List<MusicPluginManifest> = database.query("SELECT manifest FROM music_plugin_manifests ORDER BY installed_at") { it.getString(1) }
        .mapNotNull { MusicPluginManifest.parse(it).getOrNull() }

    fun manifest(pluginId: String): MusicPluginManifest? =
        database.queryOne("SELECT manifest FROM music_plugin_manifests WHERE plugin_id = ?", pluginId) { it.getString(1) }
            ?.let { MusicPluginManifest.parse(it).getOrNull() }

    fun saveManifest(manifest: MusicPluginManifest, origin: String?) {
        database.execute(
            "INSERT INTO music_plugin_manifests (plugin_id, manifest, origin, installed_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(plugin_id) DO UPDATE SET manifest = excluded.manifest, origin = excluded.origin",
            manifest.id, manifest.toJson(), origin, clock(),
        )
    }

    /** Whether [profileId] has set this plugin up. */
    fun pluginConnection(profileId: Long, pluginId: String): SourceConnection? = connections(profileId).firstOrNull { it.pluginId == pluginId }

    /**
     * Sets a plugin up for one profile. Stream lists are added as they are (each address is opened
     * first; the ones that do not answer with audio are reported, not added). Everything else is a
     * connection through the plugin's adapter, signed in with [draft].
     */
    fun connectPlugin(profileId: Long, manifest: MusicPluginManifest, draft: SourceDraft = SourceDraft("", "", "", "")): PluginSetup {
        pluginConnection(profileId, manifest.id)?.let { return PluginSetup(it, emptyList()) }
        if (manifest.adapter == MusicPluginManifest.ADAPTER_STREAMS) {
            val failures = ArrayList<String>()
            manifest.streams.forEach { stream ->
                runCatching { addStream(profileId, stream.name, stream.url, stream.genre, pluginId = manifest.id, listName = manifest.name) }
                    .onFailure { failures += "${stream.name}: ${it.message ?: it::class.simpleName}" }
            }
            val connection = pluginConnection(profileId, manifest.id)
                ?: throw SourceException("None of the ${manifest.streams.size} streams of ${manifest.name} answered with audio. ${failures.firstOrNull().orEmpty()}")
            return PluginSetup(connection, failures)
        }
        val connection = add(
            profileId, manifest.adapter,
            draft.copy(name = draft.name.ifBlank { manifest.name }, baseUrl = manifest.baseUrl ?: draft.baseUrl),
            pluginId = manifest.id,
        )
        return PluginSetup(connection, emptyList())
    }

    /** Removes a plugin for every profile: its connections, their songs, covers, copies and stored sign-ins. */
    fun removePlugin(pluginId: String) {
        val owners = database.query("SELECT profile_id, id FROM music_sources WHERE plugin_id = ?", pluginId) { it.getLong(1) to it.getLong(2) }
        owners.forEach { (profileId, sourceId) -> remove(profileId, sourceId) }
        database.execute("DELETE FROM music_plugin_manifests WHERE plugin_id = ?", pluginId)
        library.notifyChanged()
    }

    // ---------------------------------------------------------------- addons (Eclipse Music / BeatBoss format)

    private val addons: EclipseAddonAdapter? get() = adapters.filterIsInstance<EclipseAddonAdapter>().firstOrNull()

    /** Reads the manifest behind [address] and says what DebridOne would do with it. Nothing is saved. */
    fun previewAddon(address: String): AddonManifest =
        (addons ?: throw SourceException("Addons are not supported by this build.")).preview(address)

    /**
     * Installs an addon for one profile. Its address goes to the credential store only; other
     * profiles neither see the addon nor share it.
     */
    fun addAddon(profileId: Long, address: String): SourceConnection =
        add(profileId, EclipseAddonAdapter.ID, SourceDraft(name = "", baseUrl = address, username = "", secret = ""))

    /** The addons of a profile, in the user's order. */
    fun addonConnections(profileId: Long): List<SourceConnection> = connections(profileId).filter { it.adapter == EclipseAddonAdapter.ID }

    fun addonManifest(connection: SourceConnection): AddonManifest? = AddonManifest.fromConfig(connection.name, connection.config)

    /** The value in use for each of an addon's settings: the user's choice, else the addon's default. */
    fun addonSettingValues(connection: SourceConnection): Map<String, String> =
        addonManifest(connection)?.settings.orEmpty().associate { it.key to (connection.config[AddonManifest.VALUE_PREFIX + it.key] ?: it.default) }

    /** Sets one of an addon's settings for this profile; null goes back to the addon's default. */
    fun setAddonSetting(profileId: Long, sourceId: Long, key: String, value: String?) {
        val connection = connection(profileId, sourceId) ?: return
        val setting = addonManifest(connection)?.settings?.firstOrNull { it.key == key } ?: throw SourceException("${connection.name} has no setting \"$key\".")
        val clean = value?.let { checkedSetting(setting, it) }
        updateConfig(profileId, sourceId) { config ->
            if (clean == null || clean == setting.default) config - (AddonManifest.VALUE_PREFIX + key) else config + (AddonManifest.VALUE_PREFIX + key to clean)
        }
    }

    private fun checkedSetting(setting: AddonSetting, value: String): String = when (setting.type) {
        "toggle" -> if (value == "true") "true" else "false"
        "select" -> value.takeIf { v -> setting.options.any { it.first == v } } ?: throw SourceException("\"$value\" is not one of the choices of ${setting.label}.")
        "number" -> {
            val number = value.trim().toDoubleOrNull() ?: throw SourceException("${setting.label} must be a number.")
            if (setting.min != null && number < setting.min) throw SourceException("${setting.label} must be at least ${trim(setting.min)}.")
            if (setting.max != null && number > setting.max) throw SourceException("${setting.label} must be at most ${trim(setting.max)}.")
            trim(number)
        }
        else -> value.trim().take(setting.maxLength ?: 500)
    }

    private fun trim(number: Double): String = if (number == Math.floor(number) && !number.isInfinite()) number.toLong().toString() else number.toString()

    /**
     * Whether songs of this addon may be saved for offline listening. Off until the user switches
     * it on: installing an addon says nothing about what its provider permits.
     */
    fun setAddonDownloads(profileId: Long, sourceId: Long, allowed: Boolean) {
        updateConfig(profileId, sourceId) { config ->
            if (allowed) config + (EclipseAddonAdapter.DOWNLOADS_KEY to "on") else config - EclipseAddonAdapter.DOWNLOADS_KEY
        }
    }

    /** Tries an addon step by step and records the outcome on the connection. */
    fun diagnoseAddon(profileId: Long, sourceId: Long, query: String): List<AddonCheck> {
        val connection = connection(profileId, sourceId) ?: return listOf(AddonCheck("Addon", false, "That addon is no longer installed."))
        val adapter = addons ?: return listOf(AddonCheck("Addon", false, "Addons are not supported by this build."))
        val secret = runCatching { requireSecret(profileId, connection) }.getOrElse { return listOf(AddonCheck("Address", false, it.message.orEmpty())) }
        val checks = adapter.diagnose(connection, secret, query.trim().ifEmpty { "a" })
        val failed = checks.firstOrNull { !it.ok }
        recordTest(sourceId, failed?.let { SourceException("${it.step}: ${it.detail}") })
        if (failed == null) adapter.refreshedConfig(connection, secret)?.let { fresh -> updateConfig(profileId, sourceId) { old -> keepChoices(old) + fresh } }
        return checks
    }

    /** The rows an addon offers for browsing. */
    fun addonRows(connection: SourceConnection): List<AddonRow> = addonManifest(connection)?.rows.orEmpty()

    fun addonRow(profileId: Long, sourceId: Long, rowId: String, skip: Int = 0): CatalogResults =
        onCatalog(profileId, sourceId) { adapter, connection, secret ->
            (adapter as? EclipseAddonAdapter ?: throw SourceException("${connection.name} has no rows.")).row(connection, secret, rowId, skip)
        }

    fun addonArtistAlbums(profileId: Long, sourceId: Long, artistId: String): List<RemoteAlbum> =
        onCatalog(profileId, sourceId) { adapter, connection, secret ->
            (adapter as? EclipseAddonAdapter)?.artistAlbums(connection, secret, artistId).orEmpty()
        }

    /**
     * Lyrics for a song from the profile's addons that offer them (BeatBoss's `lyrics` resource),
     * the song's own addon first. Null when none has any.
     */
    fun addonLyrics(profileId: Long, track: Track): String? {
        val adapter = addons ?: return null
        val candidates = addonConnections(profileId).filter { it.enabled && addonManifest(it)?.hasLyrics == true }
            .sortedByDescending { it.id == track.sourceId }
        for (connection in candidates) {
            val secret = runCatching { requireSecret(profileId, connection) }.getOrNull() ?: continue
            runCatching { adapter.lyrics(connection, secret, track.artist.orEmpty(), track.title) }.getOrNull()?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------------- the connected cloud accounts

    /** The profile's connections to cloud accounts. */
    fun cloudConnections(profileId: Long): List<SourceConnection> = connections(profileId).filter { it.adapter == CloudLibraryAdapter.ID }

    /**
     * Brings the profile's cloud connections in line with the accounts the application offers it:
     * one connection per account, made when the account appears and removed (with its songs) when
     * the profile may no longer use it. Returns the connections that exist afterwards.
     */
    fun reconcileCloud(profileId: Long, accounts: List<CloudAudioSource.Account>): List<SourceConnection> {
        // The first version of cloud access kept a Real-Debrid sign-in inside Music; it is gone, and so is what it left.
        connections(profileId).filter { it.adapter == "real-debrid" }.forEach { remove(profileId, it.id) }
        if (adapter(CloudLibraryAdapter.ID) == null) return emptyList()
        val existing = cloudConnections(profileId)
        existing.filter { connection -> accounts.none { it.key == connection.username } }.forEach { remove(profileId, it.id) }
        val now = clock()
        for (account in accounts) {
            val known = existing.firstOrNull { it.username == account.key }
            if (known != null) {
                if (known.name != account.label) database.execute("UPDATE music_sources SET name = ?, updated_at = ? WHERE id = ?", account.label.take(80), now, known.id)
                continue
            }
            val priority = (database.queryOne("SELECT COALESCE(MAX(priority), 0) FROM music_sources WHERE profile_id = ?", profileId) { it.getInt(1) } ?: 0) + 1
            database.insert(
                """
                INSERT INTO music_sources (profile_id, adapter, name, base_url, username, config, enabled, priority, created_at, updated_at, last_test_at, last_test_ok)
                VALUES (?, ?, ?, '', ?, ?, 1, ?, ?, ?, ?, 1)
                """.trimIndent(),
                profileId, CloudLibraryAdapter.ID, account.label.take(80), account.key, toJson(mapOf(CloudLibraryAdapter.PROFILE to profileId.toString())), priority, now, now, now,
            )
        }
        library.notifyChanged()
        return cloudConnections(profileId)
    }

    /**
     * A cloud song was saved to this computer by the application: from now on it plays from that
     * file and counts as available offline, and it joins the library. Returns false when Music has
     * no such cloud song (the file can then be added as a local file instead).
     */
    fun cloudFileSaved(profileId: Long, accountKey: String, fileKey: String, path: Path?, addToLibrary: Boolean = true): Boolean {
        val track = cloudTracks(profileId, accountKey, listOf(fileKey)).firstOrNull() ?: return false
        library.setOfflinePath(profileId, track.id, path?.toString())
        if (path != null) {
            // The file knows what a file name can only suggest: its tags and its cover are used from now on.
            readSavedFile(profileId, track.id, path)
            if (addToLibrary) library.keepTracks(profileId, listOf(track.id))
        }
        library.notifyChanged()
        return true
    }

    /** The songs Music lists for these files of a cloud account, in the order asked. Files it does not list are left out. */
    fun cloudTracks(profileId: Long, accountKey: String, fileKeys: Collection<String>): List<Track> {
        val connection = cloudConnections(profileId).firstOrNull { it.username == accountKey } ?: return emptyList()
        val byKey = library.tracks(profileId, TrackFilter(sourceId = connection.id, includeHidden = true)).associateBy { it.remoteId }
        return fileKeys.mapNotNull { byKey[it] }
    }

    /** True for a song whose source lists it outside the library until the listener adds it (a cloud account). */
    fun listsOutsideLibrary(profileId: Long, track: Track): Boolean =
        !track.isLocal && connection(profileId, track.sourceId)?.let { adapter(it.adapter)?.listsOutsideLibrary } == true

    /**
     * "Remove from library". A song of a source that lists outside the library goes back to being
     * only listed there; any other song is forgotten. Files and cloud entries are never touched.
     */
    fun removeFromLibrary(profileId: Long, trackIds: Collection<Long>) {
        val outside = connections(profileId).filter { adapter(it.adapter)?.listsOutsideLibrary == true }.mapTo(HashSet()) { it.id }
        val (listed, others) = library.tracksByIds(profileId, trackIds.toList()).partition { it.sourceId in outside }
        library.unkeepTracks(profileId, listed.map { it.id })
        library.removeTracks(profileId, others.map { it.id })
    }

    private fun readSavedFile(profileId: Long, trackId: Long, path: Path) {
        if (!Files.isRegularFile(path) || !AudioTagReader.isSupported(path)) return
        val tags = runCatching { AudioTagReader.read(path) }.getOrNull()?.takeIf { it.readable } ?: return
        library.applyProviderMetadata(
            profileId, trackId,
            mapOf(
                TrackField.TITLE to tags.title, TrackField.ARTIST to tags.artist, TrackField.ALBUM to tags.album, TrackField.ALBUM_ARTIST to tags.albumArtist,
                TrackField.GENRE to tags.genre, TrackField.COMPOSER to tags.composer, TrackField.YEAR to tags.year?.toString(),
                TrackField.TRACK_NO to tags.trackNo?.toString(), TrackField.TRACK_TOTAL to tags.trackTotal?.toString(),
                TrackField.DISC_NO to tags.discNo?.toString(), TrackField.DISC_TOTAL to tags.discTotal?.toString(),
            ),
        )
        library.setAudioDetails(profileId, trackId, tags.durationMs, tags.format.uppercase(), tags.codec, tags.bitrateKbps, tags.sampleRate, tags.channels, tags.bitDepth)
        val locked = library.track(profileId, trackId)?.lockedFields.orEmpty()
        if (TrackField.ARTWORK !in locked) {
            tags.artwork?.let { cover ->
                runCatching { library.artwork.store(profileId, cover.bytes, ArtworkStore.ORIGIN_EMBEDDED) }.getOrNull()?.let { library.setTrackArtwork(profileId, trackId, it, custom = false) }
            }
        }
    }

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
        // An addon is asked for a song's address when the song starts, not when it is queued:
        // such addresses expire, and a queue of fifty songs must not mean fifty requests.
        if (adapter.resolvesAtPlayTime) return DEFERRED_SCHEME + track.id
        val secret = runCatching { profileSecrets(profileId).get(secretName(source.id)) }.getOrNull()
            ?: if (adapter.needsCredentials) return null else ""
        return adapter.streamUrl(source, secret, remoteId)
    }

    /**
     * The address of a song marked as deferred by [resolveUri], asked from its source now. Throws
     * [SourceException] with a message for the listener when there is none to be had.
     */
    fun resolveAtPlayTime(profileId: Long, trackId: Long): String {
        val track = library.track(profileId, trackId) ?: throw SourceException("This song is no longer in the library.")
        track.offlinePath?.takeIf { Files.isRegularFile(Paths.get(it)) }?.let { return it }
        val source = connection(profileId, track.sourceId) ?: throw SourceException("The source this song came from was removed.")
        if (!source.enabled) throw SourceException("${source.name} is switched off.")
        val adapter = adapter(source.adapter) ?: throw SourceException("This kind of source is not supported.")
        val remoteId = track.remoteId ?: throw SourceException("This song has no address.")
        return try {
            adapter.streamUrl(source, requireSecret(profileId, source), remoteId).also { if (source.lastError != null) recordTest(source.id, null) }
        } catch (e: SourceException) {
            recordTest(source.id, e)
            throw e
        } catch (e: IOException) {
            // The exception's own text may contain the address; it is not repeated.
            val wrapped = SourceException("${source.name} could not be reached.")
            recordTest(source.id, wrapped)
            throw wrapped
        }
    }

    /** Saves copies of remote songs so they play without the server. Returns how many were saved. */
    fun download(profileId: Long, trackIds: List<Long>): Int {
        var saved = 0
        _activity.update { it.copy(lastDownloadError = null, downloadNote = null) }
        val wanted = library.tracksByIds(profileId, trackIds).filter { !it.isLocal && it.offlinePath == null }
        // Songs of a source whose files the application fetches are handed to it; the rest Music saves itself.
        val handed = HashSet<Long>()
        wanted.groupBy { it.sourceId }.forEach { (sourceId, group) ->
            val source = connection(profileId, sourceId)?.takeIf { it.enabled } ?: return@forEach
            val message = runCatching { adapter(source.adapter)?.saveThroughApplication(source, group.mapNotNull { it.remoteId }) }
                .getOrElse { failure -> _activity.update { it.copy(lastDownloadError = failure.message) }; handed += group.map { it.id }; null } ?: return@forEach
            handed += group.map { it.id }
            _activity.update { it.copy(downloadNote = message) }
        }
        val tracks = wanted.filter { it.id !in handed }
        _activity.update { it.copy(downloading = tracks.size) }
        try {
            for (track in tracks) {
                _activity.update { it.copy(downloadTitle = track.title) }
                try {
                    val source = connection(profileId, track.sourceId)?.takeIf { it.enabled && it.adapter != STREAM_ADAPTER }
                        ?: throw SourceException("Live streams cannot be downloaded.")
                    val adapter = adapter(source.adapter) ?: throw SourceException("This kind of source is not supported.")
                    if (!adapter.allowsDownload(source)) {
                        throw SourceException(
                            if (adapter.addressIsSecret) "Saving songs is not switched on for ${source.name}." else "${adapter.displayName} offers streaming only.",
                        )
                    }
                    val secret = requireSecret(profileId, source)
                    val declared = adapter.fileExtension(track.format)
                    val target = offlineFolder(profileId, source.id).resolve("${track.id}.$declared")
                    MusicHttp.download(adapter.streamUrl(source, secret, track.remoteId.orEmpty()), target)
                    // What the file is called follows what it is, not what the source said it would be.
                    val file = if (adapter.addressIsSecret || declared == "bin") named(target, track.id) else target
                    library.setOfflinePath(profileId, track.id, file.toString())
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

    /**
     * Gives a downloaded file the extension of the audio it really contains. A file that is not
     * audio (an error page, a playlist of segments) is deleted and reported instead of kept.
     */
    private fun named(file: Path, trackId: Long): Path {
        val head = Files.newInputStream(file).use { it.readNBytes(4_096) }
        val kind = AudioSniffer.fromBytes(head)
        val extension = kind?.let(AudioSniffer::extension)
        if (extension == null) {
            runCatching { Files.deleteIfExists(file) }
            throw SourceException(
                if (kind != null) "This song is served as ${AudioSniffer.label(kind)}, which cannot be saved as one file." else "What the source sent is not an audio file DebridOne recognises.",
            )
        }
        val target = file.resolveSibling("$trackId.$extension")
        if (target != file) Files.move(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return target
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
    fun addStream(profileId: Long, name: String, address: String, genre: String? = null, pluginId: String? = null, listName: String? = null): Track {
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
        val sourceId = if (pluginId == null) streamSourceId(profileId) else streamSourceId(profileId, pluginId, listName ?: "Streams")
        val title = name.trim().ifEmpty { URI(streamUrl).host ?: "Stream" }.take(120)
        val id = library.upsert(
            profileId,
            TrackInput(sourceId = sourceId, remoteId = streamUrl, title = title, genre = genre?.trim()?.ifEmpty { null } ?: "Radio", format = formatOfContentType(type)),
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
    /**
     * The connection that holds a profile's stream addresses: the user's own ([pluginId] null), or
     * the list a "streams" plugin brought, which is removed with that plugin.
     */
    private fun streamSourceId(profileId: Long, pluginId: String? = null, name: String = "Radio and streams"): Long = database.transaction {
        val existing = if (pluginId == null) {
            queryOne("SELECT id FROM music_sources WHERE profile_id = ? AND adapter = ? AND plugin_id IS NULL", profileId, STREAM_ADAPTER) { it.getLong(1) }
        } else {
            queryOne("SELECT id FROM music_sources WHERE profile_id = ? AND adapter = ? AND plugin_id = ?", profileId, STREAM_ADAPTER, pluginId) { it.getLong(1) }
        }
        existing ?: run {
            val now = clock()
            val priority = (queryOne("SELECT COALESCE(MAX(priority), 0) FROM music_sources WHERE profile_id = ?", profileId) { it.getInt(1) } ?: 0) + 1
            insert(
                "INSERT INTO music_sources (profile_id, adapter, name, config, enabled, priority, created_at, updated_at, plugin_id) VALUES (?, ?, ?, '{}', 1, ?, ?, ?, ?)",
                profileId, STREAM_ADAPTER, name.take(80), priority, now, now, pluginId,
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

    private val renewals = java.util.concurrent.ConcurrentHashMap<Long, Any>()

    /**
     * The stored sign-in of a connection, renewed first when it is of the kind that expires. One
     * renewal at a time per connection: two songs starting together must not both spend the same
     * refresh token.
     */
    private fun requireSecret(profileId: Long, connection: SourceConnection): String {
        val adapter = adapter(connection.adapter)
        synchronized(renewals.computeIfAbsent(connection.id) { Any() }) {
            val stored = runCatching { profileSecrets(profileId).get(secretName(connection.id)) }.getOrNull()
                ?: if (adapter?.needsCredentials == false) return "" else
                    throw SourceException("The saved sign-in for ${connection.name} is missing. Remove the connection and add it again.")
            val renewed = adapter?.renewedSecret(connection, stored) ?: return stored
            runCatching { profileSecrets(profileId).put(secretName(connection.id), renewed) }
            return renewed
        }
    }

    private fun offlineFolder(profileId: Long, sourceId: Long): Path = offlineDirectory.resolve(profileId.toString()).resolve(sourceId.toString())

    private fun markAvailability(profileId: Long, sourceId: Long, available: Boolean) {
        val ids = library.tracks(profileId, TrackFilter(sourceId = sourceId, includeHidden = true)).map { it.id }
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

    private fun listingCacheName(sourceId: Long) = "source-listing:$sourceId"

    /** What the adapter of a connection remembers between listings, kept with Music's other cached answers. */
    private fun listingMemory(sourceId: Long) = object : ListingMemory {
        override fun get(key: String): String? = database.queryOne(
            "SELECT value FROM music_metadata_cache WHERE provider = ? AND cache_key = ?", listingCacheName(sourceId), key,
        ) { it.getString(1) }

        override fun put(key: String, value: String) {
            database.execute(
                "INSERT INTO music_metadata_cache (provider, cache_key, value, fetched_at) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(provider, cache_key) DO UPDATE SET value = excluded.value, fetched_at = excluded.fetched_at",
                listingCacheName(sourceId), key, value, clock(),
            )
        }
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
        pluginId = rs.getString("plugin_id"),
    )

    companion object {
        /** Direct stream addresses (internet radio). Not a server sign-in, so not one of [adapters]. */
        const val STREAM_ADAPTER = "stream-url"

        /** The kinds of source Music itself knows; the application adds the cloud adapter when it has accounts to offer. */
        fun builtInAdapters(): List<MusicSourceAdapter> = listOf(AudiusAdapter(), SubsonicAdapter(), JellyfinAdapter(), HttpCatalogAdapter(), EclipseAddonAdapter())

        private val PLAYLIST_TYPES = setOf(
            "audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl",
            "audio/x-scpls", "application/pls+xml", "application/pls",
        )
    }
}
