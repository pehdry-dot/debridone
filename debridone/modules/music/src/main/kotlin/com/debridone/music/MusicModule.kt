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
import com.debridone.music.playback.MusicPlaybackEngine
import com.debridone.platform.db.Migration
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Music's working parts, available once the module has opened its storage. */
class MusicServices internal constructor(
    val library: MusicLibraryRepository,
    val scanner: MusicScanner,
    val player: MusicPlayer,
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
) : SqliteBackedModule(ModuleId.MUSIC, storage, clock) {

    private val _services = MutableStateFlow<MusicServices?>(null)

    /** Null until the module is running. */
    val services: StateFlow<MusicServices?> = _services.asStateFlow()

    private val _activeProfile = MutableStateFlow<Long?>(null)

    /** The profile whose library, playlists and player session are loaded. */
    val activeProfile: StateFlow<Long?> = _activeProfile.asStateFlow()

    private val scanMutex = Mutex()

    override fun onStarted() {
        val database = requireDatabase()
        val artwork = ArtworkStore(database, storage.dataDir.resolve("artwork"), clock)
        val library = MusicLibraryRepository(database, artwork, clock)
        val player = MusicPlayer(library, database, engineFactory, moduleScope, ::resolveUri, clock)
        _services.value = MusicServices(library, MusicScanner(library, clock), player)
    }

    override fun onActiveProfileChanged(profileId: Long?) {
        val services = _services.value ?: return
        val changed = _activeProfile.value != profileId
        // Music never carries over: the old profile's session is saved and stopped first.
        services.player.switchProfile(profileId)
        _activeProfile.value = profileId
        if (changed && profileId != null) rescan()
    }

    override fun onProfileDeleted(profileId: Long) {
        val services = _services.value ?: return
        services.player.deleteProfile(profileId)
        services.library.deleteProfile(profileId)
    }

    override fun onClosing() {
        _services.value?.player?.close()
    }

    /** What the engine should open for [track]: its file, or its downloaded copy. */
    private fun resolveUri(profileId: Long, track: Track): String? = track.offlinePath ?: if (track.isLocal) track.path else null

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
            services.library.removeFolder(profileId, folderId)
            services.player.removeDeletedFromQueue()
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
            } catch (_: Exception) {
                // Already recorded in scanner.progress.
            }
        }
    }

    override val readiness: ModuleReadiness = ModuleReadiness.FoundationOnly(
        summary = "Music storage, plugin registry, metadata settings and appearance are in place. " +
            "The player and library are not built yet.",
        upcoming = listOf(
            "Local folder import with embedded tag reading",
            "Playback with queue, shuffle, repeat, gapless and media keys",
            "Playlists, favourites, history and lyrics",
            "Navidrome/Subsonic and Jellyfin libraries",
            "MusicBrainz and Cover Art Archive metadata",
            "Music plugins installed by manifest URL",
        ),
    )

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
    )

    override val supportedPluginKinds = setOf(
        PluginKind.SOURCE,
        PluginKind.METADATA,
        PluginKind.TEXT_TRACKS,
        PluginKind.LIBRARY_INTEGRATION,
        PluginKind.UTILITY,
    )

    override val pluginImportHint =
        "Music sources, library integrations (Navidrome/Subsonic, Jellyfin), metadata and lyrics providers."

    override val defaultMetadataProviders = listOf(
        MetadataProvider("embedded-tags", "Embedded tags", "Title, artist, album and artwork stored inside your music files.", enabled = true, priority = 0, implemented = true),
        MetadataProvider("musicbrainz", "MusicBrainz", "Open music encyclopedia: releases, artists, track listings.", enabled = true, priority = 10, implemented = false),
        MetadataProvider("cover-art-archive", "Cover Art Archive", "Album artwork linked to MusicBrainz releases.", enabled = true, priority = 20, implemented = false),
        MetadataProvider("lrclib", "LRCLIB", "Community lyrics, including time-synced lyrics.", enabled = true, priority = 30, implemented = false),
    )

    override val appearanceOptions: List<AppearanceOption> = listOf(
        StandardAppearanceOptions.accent(),
        StandardAppearanceOptions.cardSize("Album grid size"),
        StandardAppearanceOptions.density(),
        AppearanceOption.Choice(
            "playlist_presentation", "Playlist presentation",
            listOf(ChoiceValue("grid", "Cover grid"), ChoiceValue("list", "Track list")), "grid",
        ),
        AppearanceOption.Choice(
            "player_layout", "Player layout",
            listOf(ChoiceValue("compact", "Compact bar"), ChoiceValue("expanded", "Expanded with artwork")), "expanded",
        ),
        AppearanceOption.Choice(
            "artwork_size", "Now-playing artwork size",
            listOf(ChoiceValue("small", "Small"), ChoiceValue("medium", "Medium"), ChoiceValue("large", "Large")), "large",
        ),
        AppearanceOption.Toggle("dynamic_album_colours", "Dynamic album colours", true, "Tint the player from the current album artwork."),
        AppearanceOption.Choice(
            "lyrics_style", "Lyrics appearance",
            listOf(ChoiceValue("standard", "Standard"), ChoiceValue("large", "Large, centred"), ChoiceValue("minimal", "Minimal")), "standard",
        ),
    )
}
