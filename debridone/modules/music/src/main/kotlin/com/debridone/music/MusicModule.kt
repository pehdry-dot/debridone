package com.debridone.music

import com.debridone.module.AppearanceOption
import com.debridone.module.ChoiceValue
import com.debridone.module.MetadataProvider
import com.debridone.module.ModuleId
import com.debridone.module.ModuleReadiness
import com.debridone.module.PluginKind
import com.debridone.module.StandardAppearanceOptions
import com.debridone.module.storage.MetadataProviderTableStore
import com.debridone.module.storage.ModuleStorage
import com.debridone.module.storage.PluginTableStore
import com.debridone.module.storage.ProfileSettingsTableStore
import com.debridone.module.storage.SqliteBackedModule
import com.debridone.music.library.ArtworkStore
import com.debridone.music.library.LibraryFolder
import com.debridone.music.library.MusicLibraryRepository
import com.debridone.music.library.MusicScanner
import com.debridone.music.library.MusicSchema
import com.debridone.music.library.ScanResult
import com.debridone.music.library.Track
import com.debridone.music.playback.MpvAudioEngine
import com.debridone.music.metadata.MetadataEndpoints
import com.debridone.music.metadata.MusicMetadataService
import com.debridone.music.playback.MusicPlaybackEngine
import com.debridone.music.playlists.PlaylistRepository
import com.debridone.music.sources.IntegrationKind
import com.debridone.music.sources.MusicPluginManifest
import com.debridone.music.sources.MusicSourceManager
import com.debridone.music.sources.RemotePlaylist
import com.debridone.music.sources.RemoteTrack
import com.debridone.music.sources.SyncSummary
import com.debridone.module.PluginActionResult
import com.debridone.module.storage.PluginDescriptor
import com.debridone.platform.db.Migration
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Music's working parts, available once the module has opened its storage. */
class MusicServices internal constructor(
    val library: MusicLibraryRepository,
    val scanner: MusicScanner,
    val player: MusicPlayer,
    val playlists: PlaylistRepository,
    val preferences: MusicPreferences,
    val sources: MusicSourceManager,
    val metadata: MusicMetadataService,
)

/**
 * Music module. Original DebridOne code: Eclipse Music is the feature reference only, none of its
 * source is used (it is not publicly available).
 *
 * Owns `modules/music/music.db`. Nothing in here may reference Streaming, Games or Manga.
 */
class MusicModule(
    storage: ModuleStorage,
    clock: () -> Long = System::currentTimeMillis,
    /** Creates the audio engine on first use. The host passes the bundled libmpv on macOS. */
    private val engineFactory: () -> MusicPlaybackEngine = { MpvAudioEngine() },
    /** Where the online metadata providers are reached; tests use a local stand-in. */
    private val metadataEndpoints: MetadataEndpoints = MetadataEndpoints(),
) : SqliteBackedModule(ModuleId.MUSIC, storage, clock) {

    private val _services = MutableStateFlow<MusicServices?>(null)

    /** Null until the module is running. */
    val services: StateFlow<MusicServices?> = _services.asStateFlow()

    private val _activeProfile = MutableStateFlow<Long?>(null)

    /** The profile whose library, playlists and player session are loaded. */
    val activeProfile: StateFlow<Long?> = _activeProfile.asStateFlow()

    private val scanMutex = Mutex()

    @Volatile
    private var activatedOnce = false

    override fun onStarted() {
        val database = requireDatabase()
        val artwork = ArtworkStore(database, storage.dataDir.resolve("artwork"), clock)
        val library = MusicLibraryRepository(database, artwork, clock)
        val sources = MusicSourceManager(
            database, library, storage.secrets, storage.dataDir.resolve("offline"), clock,
            artworkCacheDirectory = storage.cacheDir.resolve("catalogue-art"),
        )
        val player = MusicPlayer(library, database, engineFactory, moduleScope, sources::resolveUri, sources::resolveAtPlayTime, clock)
        _services.value = MusicServices(
            library = library,
            scanner = MusicScanner(library, clock),
            player = player,
            playlists = PlaylistRepository(database, library, clock),
            preferences = MusicPreferences(::settingsStore),
            sources = sources,
            metadata = MusicMetadataService(
                database, library,
                providerEnabled = { id -> metadataProviders.providers.value.firstOrNull { it.id == id }?.enabled == true },
                endpoints = metadataEndpoints, clock = clock,
                previewDirectory = storage.cacheDir.resolve("cover-previews"),
            ),
        )
        // The kinds of source Music can use, listed in Music's own plugin registry.
        runBlocking { builtInSources.forEach { runCatching { registerPlugin(it) } } }
    }

    override fun onActiveProfileChanged(profileId: Long?) {
        val services = _services.value ?: return
        val changed = _activeProfile.value != profileId
        // Music never carries over: the old profile's session is saved and stopped first.
        services.preferences.load(profileId)
        services.player.setAudioDevice(services.preferences[MusicPreferences.AUDIO_DEVICE] ?: "auto")
        services.player.switchProfile(profileId)
        _activeProfile.value = profileId
        // Catalogue songs that were played once, never added and not heard for a month are forgotten.
        if (changed && profileId != null) {
            moduleScope.launch(Dispatchers.IO) { runCatching { services.library.purgeHidden(profileId, clock() - HIDDEN_RETENTION_MS) } }
        }
        if (changed && profileId != null && services.preferences.boolean(MusicPreferences.RESCAN_ON_START, true)) {
            rescan()
            syncAllSources()
        }
        // Only when the app starts, never on a profile switch: pick the music up where it stopped.
        if (profileId != null && !activatedOnce && services.preferences.boolean(MusicPreferences.RESUME_ON_START, false)) {
            services.player.resume()
        }
        if (profileId != null) activatedOnce = true
    }

    override fun onProfileDeleted(profileId: Long) {
        val services = _services.value ?: return
        services.player.deleteProfile(profileId)
        // Before the library rows go: the connections are needed to find their stored passwords.
        services.sources.deleteProfile(profileId)
        services.library.deleteProfile(profileId)
    }

    override fun onClosing() {
        _services.value?.player?.close()
    }

    private val builtInSources = listOf(
        PluginDescriptor("local-files", "Local Music", PluginKind.SOURCE, description = "Folders and files you add. Read in place, never copied.", builtIn = true),
        PluginDescriptor("audius", "Audius", PluginKind.SOURCE, description = "Open music catalogue of independent artists. No account needed.", builtIn = true),
        PluginDescriptor("subsonic", "Navidrome / OpenSubsonic", PluginKind.LIBRARY_INTEGRATION, description = "Your own Subsonic-compatible music server.", builtIn = true),
        PluginDescriptor("jellyfin", "Jellyfin Music", PluginKind.LIBRARY_INTEGRATION, description = "The music libraries of your own Jellyfin server.", builtIn = true),
        PluginDescriptor("stream-url", "Radio and stream addresses", PluginKind.SOURCE, description = "Internet radio and other direct audio streams.", builtIn = true),
    )

    /** Import works: a Music plugin manifest, from an address or a file. */
    override val pluginImportUnavailableReason: String? = null

    /**
     * Installs a Music plugin from its manifest: an https address, or a `.json` / `.zip` file on
     * this computer (the zip must hold a `manifest.json`). The manifest is data only; see
     * [MusicPluginManifest]. A plugin that needs no sign-in is set up for the active profile at
     * once; the others wait for the user to connect.
     */
    override suspend fun importPlugin(location: String): PluginActionResult = withContext(Dispatchers.IO) {
        val services = _services.value ?: return@withContext PluginActionResult.Failure("Music is not running.")
        val text = try {
            readManifestText(location)
        } catch (e: Exception) {
            return@withContext PluginActionResult.Failure(e.message ?: "The manifest could not be read.")
        }
        val manifest = MusicPluginManifest.parse(text).getOrElse {
            return@withContext PluginActionResult.Failure(it.message ?: "That is not a Music plugin manifest.")
        }
        if (builtInSources.any { it.id == manifest.id }) {
            return@withContext PluginActionResult.Failure("\"${manifest.id}\" is the id of a built-in integration; a plugin cannot replace it.")
        }
        services.sources.saveManifest(manifest, location)
        val installed = registerPlugin(
            PluginDescriptor(
                id = manifest.id,
                name = manifest.name,
                kind = if (manifest.kind == IntegrationKind.PERSONAL_SERVER) PluginKind.LIBRARY_INTEGRATION else PluginKind.SOURCE,
                version = manifest.version,
                description = manifest.description,
                origin = location,
            ),
        )
        val profileId = _activeProfile.value
        if (profileId != null && manifest.connectsWithoutSignIn) {
            val setup = runCatching { services.sources.connectPlugin(profileId, manifest) }
            setup.exceptionOrNull()?.let { reportPluginError(manifest.id, it.message) }
            setup.getOrNull()?.let { reportPluginError(manifest.id, it.problems.takeIf { p -> p.isNotEmpty() }?.joinToString("; ")?.take(600)) }
        }
        PluginActionResult.Success(plugins.installed.value.firstOrNull { it.id == manifest.id } ?: installed)
    }

    private fun readManifestText(location: String): String {
        val trimmed = location.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return com.debridone.music.sources.manifestFromAddress(trimmed)
        }
        val path = runCatching { java.nio.file.Paths.get(trimmed.removePrefix("file://")) }.getOrNull()
            ?: throw IllegalArgumentException("Enter the https address of a manifest, or the path of a manifest file.")
        if (!java.nio.file.Files.isRegularFile(path)) throw IllegalArgumentException("There is no file at $path.")
        if (java.nio.file.Files.size(path) > 8L * 1024 * 1024) throw IllegalArgumentException("That file is too large to be a plugin package.")
        if (path.fileName.toString().lowercase().endsWith(".zip")) {
            java.util.zip.ZipFile(path.toFile()).use { zip ->
                val entry = zip.getEntry("manifest.json") ?: throw IllegalArgumentException("The package has no manifest.json at its top level.")
                if (entry.size > MusicPluginManifest.MAX_BYTES) throw IllegalArgumentException("The package's manifest is too large.")
                return zip.getInputStream(entry).use { String(it.readNBytes(MusicPluginManifest.MAX_BYTES + 1), Charsets.UTF_8) }
            }
        }
        return String(java.nio.file.Files.readAllBytes(path), Charsets.UTF_8)
    }

    override fun onPluginRemoved(pluginId: String) {
        val services = _services.value ?: return
        services.sources.removePlugin(pluginId)
        services.player.removeDeletedFromQueue()
    }

    // ---------------------------------------------------------------- online catalogues

    /**
     * Plays songs found in a source's catalogue. They play from the source and stay out of the
     * library unless [keep]; their covers are fetched once playback has started.
     */
    fun playRemote(sourceId: Long, tracks: List<RemoteTrack>, startIndex: Int = 0, keep: Boolean = false, onDone: (Result<Int>) -> Unit = {}): Job =
        moduleScope.launch(Dispatchers.IO) {
            val profileId = _activeProfile.value ?: return@launch
            val services = _services.value ?: return@launch
            val result = runCatching {
                val wanted = tracks.getOrNull(startIndex)
                val playable = services.sources.materialize(profileId, sourceId, tracks, keep)
                if (playable.isEmpty()) throw com.debridone.music.sources.SourceException("The source does not allow these songs to be played.")
                val index = playable.indexOfFirst { it.remoteId == wanted?.remoteId }.coerceAtLeast(0)
                services.player.play(playable, index)
                playable.size
            }
            onDone(result)
            if (result.isSuccess) runCatching { services.sources.fetchArtwork(profileId, sourceId, tracks) }
        }

    /** Adds songs found in a source's catalogue to the active profile's library. */
    fun keepRemote(sourceId: Long, tracks: List<RemoteTrack>, onDone: (Result<List<Track>>) -> Unit = {}): Job = moduleScope.launch(Dispatchers.IO) {
        val profileId = _activeProfile.value ?: return@launch
        val services = _services.value ?: return@launch
        val result = runCatching { services.sources.materialize(profileId, sourceId, tracks, keep = true) }
        onDone(result)
        if (result.isSuccess) runCatching { services.sources.fetchArtwork(profileId, sourceId, tracks) }
    }

    /** Copies a playlist kept on a source into the active profile's own playlists. */
    fun importRemotePlaylist(sourceId: Long, playlist: RemotePlaylist, onDone: (Result<Int>) -> Unit = {}): Job = moduleScope.launch(Dispatchers.IO) {
        val profileId = _activeProfile.value ?: return@launch
        val services = _services.value ?: return@launch
        val result = runCatching {
            val remote = services.sources.playlistTracks(profileId, sourceId, playlist.remoteId)
            val tracks = services.sources.materialize(profileId, sourceId, remote, keep = true)
            if (tracks.isEmpty()) throw com.debridone.music.sources.SourceException("\"${playlist.name}\" has no songs that can be played.")
            val created = services.playlists.create(profileId, playlist.name, playlist.description)
            services.playlists.add(profileId, created.id, tracks.map { it.id })
            runCatching { services.sources.fetchArtwork(profileId, sourceId, remote) }
            tracks.size
        }
        onDone(result)
    }

    // ---------------------------------------------------------------- source actions

    /** Reads a server's library into the active profile's library, in the background. */
    fun syncSource(sourceId: Long, onDone: (Result<SyncSummary>) -> Unit = {}): Job = moduleScope.launch(Dispatchers.IO) {
        val profileId = _activeProfile.value ?: return@launch
        val services = _services.value ?: return@launch
        val result = runCatching { sourceMutex.withLock { services.sources.sync(profileId, sourceId) } }
        if (result.exceptionOrNull() is kotlinx.coroutines.CancellationException) throw result.exceptionOrNull()!!
        services.player.removeDeletedFromQueue()
        onDone(result)
    }

    fun syncAllSources(): Job = moduleScope.launch(Dispatchers.IO) {
        val profileId = _activeProfile.value ?: return@launch
        val services = _services.value ?: return@launch
        for (connection in services.sources.connections(profileId)) {
            if (!connection.enabled || connection.adapter == MusicSourceManager.STREAM_ADAPTER) continue
            runCatching { sourceMutex.withLock { services.sources.sync(profileId, connection.id) } }
        }
        services.player.removeDeletedFromQueue()
    }

    /** Saves offline copies of remote songs, in the background. */
    fun download(trackIds: List<Long>, onDone: (Int) -> Unit = {}): Job = moduleScope.launch(Dispatchers.IO) {
        val profileId = _activeProfile.value ?: return@launch
        val services = _services.value ?: return@launch
        onDone(runCatching { services.sources.download(profileId, trackIds) }.getOrDefault(0))
    }

    private val sourceMutex = Mutex()

    // ---------------------------------------------------------------- library actions

    /** Adds a folder to the active profile's library and scans it in the background. */
    fun addFolder(path: Path): Job = libraryJob { profileId, services ->
        val folder: LibraryFolder = services.library.addFolder(profileId, path.toString())
        services.scanner.scanFolder(profileId, folder.id)
    }

    /** Adds single files to the active profile's library. */
    fun importFiles(files: List<Path>): Job = libraryJob { profileId, services -> services.scanner.importFiles(profileId, files) }

    /** Looks for new, changed, moved and deleted files in every folder of the active profile. */
    fun rescan(): Job = libraryJob { profileId, services -> services.scanner.scanAll(profileId) }

    fun removeFolder(folderId: Long): Job = moduleScope.launch(Dispatchers.IO) {
        val profileId = _activeProfile.value ?: return@launch
        val services = _services.value ?: return@launch
        scanMutex.withLock {
            runCatching {
                services.library.removeFolder(profileId, folderId)
                services.player.removeDeletedFromQueue()
            }
        }
    }

    /** Scans run one at a time; a failure is shown in the scan progress, not thrown at the module. */
    private fun libraryJob(block: suspend (Long, MusicServices) -> ScanResult): Job = moduleScope.launch(Dispatchers.IO) {
        val profileId = _activeProfile.value ?: return@launch
        val services = _services.value ?: return@launch
        scanMutex.withLock {
            try {
                block(profileId, services)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Already recorded in scanner.progress. A scan that fails must not take the whole
                // Music section down with it: the library that is already there stays usable.
            }
        }
    }

    override val readiness: ModuleReadiness = ModuleReadiness.Ready

    override val pluginTable = "music_plugins"
    override val metadataTable = "music_metadata_providers"
    override val settingsTable = "music_profile_settings"

    override val migrations = listOf(
        Migration(
            1, "music plugin registry, metadata providers, profile settings",
            PluginTableStore.createTableSql("music_plugins"),
            MetadataProviderTableStore.createTableSql("music_metadata_providers"),
            ProfileSettingsTableStore.createTableSql("music_profile_settings"),
        ),
        MusicSchema.library,
        MusicSchema.catalogue,
    )

    override val supportedPluginKinds = setOf(
        PluginKind.SOURCE,
        PluginKind.METADATA,
        PluginKind.TEXT_TRACKS,
        PluginKind.LIBRARY_INTEGRATION,
        PluginKind.UTILITY,
    )

    override val pluginImportHint =
        "The https address or the file of a DebridOne Music plugin manifest. A manifest names one of Music's adapters and its settings; it carries no code."

    override val defaultMetadataProviders = listOf(
        MetadataProvider("embedded-tags", "Embedded tags", "Title, artist, album and artwork stored inside your music files.", enabled = true, priority = 0, implemented = true),
        MetadataProvider("musicbrainz", "MusicBrainz", "Open music encyclopedia: releases, artists, track listings. Asked only when you look a song up.", enabled = true, priority = 10, implemented = true),
        MetadataProvider("cover-art-archive", "Cover Art Archive", "Album artwork linked to MusicBrainz releases, for songs without a cover.", enabled = true, priority = 20, implemented = true),
        MetadataProvider("lrclib", "LRCLIB", "Community lyrics, including time-synced lyrics. Asked when you open the lyrics of a song that has none.", enabled = true, priority = 30, implemented = true),
    )

    private companion object {
        const val HIDDEN_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }

    override val appearanceOptions: List<AppearanceOption> = listOf(
        StandardAppearanceOptions.accent(),
        StandardAppearanceOptions.cardSize("Album grid size"),
        StandardAppearanceOptions.density(),
        AppearanceOption.Choice(
            "playlist_presentation", "Playlist presentation",
            listOf(ChoiceValue("grid", "Cover grid"), ChoiceValue("list", "Track list")), "grid",
        ),
        AppearanceOption.Choice(
            "player_layout", "Full-screen player",
            listOf(ChoiceValue("expanded", "Artwork with queue or lyrics beside it"), ChoiceValue("compact", "Artwork only; queue and lyrics on request")), "expanded",
        ),
        AppearanceOption.Choice(
            "player_background", "Player background",
            listOf(ChoiceValue("gradient", "Colour from the artwork"), ChoiceValue("blur", "Blurred artwork"), ChoiceValue("plain", "Plain")), "gradient",
        ),
        AppearanceOption.Choice(
            "artwork_size", "Artwork size",
            listOf(ChoiceValue("small", "Small"), ChoiceValue("medium", "Medium"), ChoiceValue("large", "Large")), "large",
        ),
        AppearanceOption.Choice(
            "text_size", "Text size",
            listOf(ChoiceValue("small", "Small"), ChoiceValue("medium", "Standard"), ChoiceValue("large", "Large")), "medium",
        ),
        AppearanceOption.Toggle("dynamic_album_colours", "Dynamic album colours", true, "Tint album, artist and playlist pages and the player from the artwork."),
        AppearanceOption.Choice(
            "lyrics_style", "Lyrics appearance",
            listOf(ChoiceValue("standard", "Standard"), ChoiceValue("large", "Large, centred"), ChoiceValue("minimal", "Minimal")), "standard",
        ),
    )
}
