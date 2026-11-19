package com.debridone.manga

import com.debridone.manga.download.MangaDownloads
import com.debridone.manga.library.MangaRepository
import com.debridone.manga.library.MangaSchema
import com.debridone.manga.local.LocalFiles
import com.debridone.manga.migration.MangaMigration
import com.debridone.manga.metadata.MangaMetadata
import com.debridone.manga.metadata.MetadataEndpoints
import com.debridone.manga.reader.MangaPages
import com.debridone.manga.sources.MangaExtensions
import com.debridone.manga.sources.MangaSources
import com.debridone.manga.suwayomi.MangaConnection
import com.debridone.manga.tracking.MangaTrackers
import com.debridone.manga.suwayomi.MangaServer
import com.debridone.manga.suwayomi.MangaServerSettings
import com.debridone.manga.suwayomi.RemoteServer
import com.debridone.manga.suwayomi.SuwayomiRuntime
import com.debridone.manga.suwayomi.SuwayomiRuntimeLocator
import com.debridone.manga.suwayomi.SuwayomiServerManager
import com.debridone.manga.suwayomi.SuwayomiUnavailable
import com.debridone.module.AppearanceOption
import com.debridone.module.AvailablePlugin
import com.debridone.module.ChoiceValue
import com.debridone.module.MetadataProvider
import com.debridone.module.ModuleId
import com.debridone.module.ModuleReadiness
import com.debridone.module.PluginActionResult
import com.debridone.module.PluginKind
import com.debridone.module.StandardAppearanceOptions
import com.debridone.module.storage.MetadataProviderTableStore
import com.debridone.module.storage.ModuleStorage
import com.debridone.module.storage.PluginDescriptor
import com.debridone.module.storage.PluginTableStore
import com.debridone.module.storage.ProfileSettingsTableStore
import com.debridone.module.storage.SqliteBackedModule
import com.debridone.platform.db.Migration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/** Everything the Manga screens work with. Created when the module starts. */
class MangaServices internal constructor(
    /** The connection to the active profile's Suwayomi-Server. */
    val server: MangaServer,
    val repository: MangaRepository,
    val sources: MangaSources,
    val extensions: MangaExtensions,
    val pages: MangaPages,
    val local: LocalFiles,
    val downloads: MangaDownloads,
    val metadata: MangaMetadata,
    /** Reading lists on AniList, MyAnimeList and the others, as the manga server keeps them. */
    val trackers: MangaTrackers,
    /** The manga server's own settings (downloads, checks for new chapters, proxy). */
    val serverSettings: MangaServerSettings,
    /** What became of the library of the earlier, Houdoku-based version of Manga. */
    val migration: MangaMigration,
    val preferences: MangaPreferences,
    val appSettings: MangaAppSettings,
    /** Where covers the person chose themselves are kept. */
    val coversDir: Path,
    /** Finds where a connection to a source's site stops, from DebridOne and from the manga server's Java. */
    val diagnosis: com.debridone.manga.net.NetworkDiagnosis = com.debridone.manga.net.NetworkDiagnosis({ server.managedJava() }),
)

/**
 * Manga & Manhwa module.
 *
 * Its backend is Suwayomi-Server (https://github.com/Suwayomi/Suwayomi-Server, MPL-2.0), the
 * unmodified release jar, run as a separate process with a bundled Java 21 runtime and reached
 * through its GraphQL API. Sources are the Mihon/Tachiyomi extensions Suwayomi loads; the library,
 * categories, chapters, reading progress, downloads and trackers are Suwayomi's. The screens and
 * the reader are DebridOne's own. See debridone/docs/MANGA.md and THIRD_PARTY_NOTICES.md.
 *
 * Each DebridOne profile has a Suwayomi data folder of its own (`modules/manga/suwayomi/profile-N`)
 * and only the active profile's server runs: Suwayomi has no user accounts, so separate folders
 * are what keeps profiles apart. A profile can instead be pointed at a server the person runs.
 *
 * Owns `modules/manga/manga.db` (its settings and metadata-provider choices, and the library of
 * the earlier Houdoku-based version, kept for migration). Nothing in here may reference Streaming,
 * Music or Games.
 */
class MangaModule(
    storage: ModuleStorage,
    clock: () -> Long = System::currentTimeMillis,
    /** Finds Suwayomi-Server and its Java runtime. The app uses the bundled copies; tests name theirs. */
    private val runtime: () -> SuwayomiRuntime = { SuwayomiRuntimeLocator.locate() },
    /** Where the metadata services are reached; tests use local stand-ins. */
    private val metadataEndpoints: MetadataEndpoints = MetadataEndpoints(),
) : SqliteBackedModule(ModuleId.MANGA, storage, clock) {

    private val _services = MutableStateFlow<MangaServices?>(null)

    /** Null until the module is running. */
    val services: StateFlow<MangaServices?> = _services.asStateFlow()

    private val _activeProfile = MutableStateFlow<Long?>(null)

    /** The profile whose library and reading state are shown. */
    val activeProfile: StateFlow<Long?> = _activeProfile.asStateFlow()

    private val _migrationProblem = MutableStateFlow<String?>(null)

    /** Why the earlier library could not be carried over, when that failed (nothing was changed then). */
    val migrationProblem: StateFlow<String?> = _migrationProblem.asStateFlow()

    /** Where every profile's Suwayomi data folder is. */
    val serverDataDir: Path get() = storage.dataDir.resolve("suwayomi")

    override fun onStarted() {
        requireDatabase()
        val appSettings = MangaAppSettings(::settingsStore).also { it.load() }
        val preferences = MangaPreferences(::settingsStore)
        val manager = SuwayomiServerManager(serverDataDir, runtime)
        val server = MangaServer(manager, moduleScope) { profile -> remoteServer(profile) }
        val repository = MangaRepository(server, moduleScope, clock)
        val sources = MangaSources(server, repository, clock) { preferences.sourceLanguages() }
        val pages = MangaPages(server, repository, storage.cacheDir)
        val downloads = MangaDownloads(server, repository, moduleScope)
        val metadata = MangaMetadata(
            repository, storage.secrets,
            providerEnabled = { id -> metadataProviders.providers.value.firstOrNull { it.id == id }?.enabled == true },
            providerPriority = { id -> metadataProviders.providers.value.firstOrNull { it.id == id }?.priority ?: Int.MAX_VALUE },
            // The source is asked again, and what Suwayomi then has is the source's offer.
            extensionDetails = { series ->
                runCatching { sources.refresh(series.id) }
                repository.sourceDetails(series.id)
            },
            endpoints = metadataEndpoints, clock = clock,
        )
        val localFiles = LocalFiles(server, repository, sources)
        val migration = MangaMigration(
            requireDatabase(), storage.dataDir, server, repository, localFiles,
            hasLegacyTrackerSignIn = { profile -> metadata.hasLegacyTrackerSignIn(profile) }, clock = clock,
        )
        _services.value = MangaServices(
            server = server,
            repository = repository,
            sources = sources,
            extensions = MangaExtensions(server, sources),
            pages = pages,
            local = localFiles,
            downloads = downloads,
            metadata = metadata,
            trackers = MangaTrackers(server),
            serverSettings = MangaServerSettings(server),
            migration = migration,
            preferences = preferences,
            appSettings = appSettings,
            coversDir = storage.dataDir.resolve("covers"),
        )
        runBlocking { runCatching { registerPlugin(LOCAL_FILES) } }
        downloads.watch()
        // A profile's earlier library is carried over the first time its own server is up. It is
        // copied to a backup first and only ever read; what could not be carried over is in the report.
        moduleScope.launch(Dispatchers.IO) {
            server.state.collect { state ->
                if (state is MangaConnection.Ready && !state.remote) {
                    runCatching { if (migration.needsRun(state.profileId)) migration.run(state.profileId) }
                        .onFailure { failure -> _migrationProblem.value = failure.message ?: "The earlier library could not be carried over." }
                        .onSuccess { _migrationProblem.value = null }
                }
            }
        }
        moduleScope.launch(Dispatchers.IO) { runCatching { pages.trimCache(appSettings.cacheMegabytes * 1024L * 1024L) } }
    }

    /** The server a profile chose to use instead of the one DebridOne starts, or null. */
    private fun remoteServer(profileId: Long): RemoteServer? {
        val values = settingsStore().all(profileId, SERVER_PREFIX).mapKeys { it.key.removePrefix(SERVER_PREFIX) }
        if (values[MangaPreferences.SERVER_MODE] != MangaPreferences.SERVER_MODE_REMOTE) return null
        val url = values[MangaPreferences.SERVER_URL]?.takeIf { it.isNotBlank() } ?: return null
        return RemoteServer(
            url = url,
            username = values[MangaPreferences.SERVER_USERNAME]?.takeIf { it.isNotBlank() },
            password = storage.secrets.child("profile.$profileId").get(SERVER_PASSWORD),
            timeoutSeconds = values[MangaPreferences.SERVER_TIMEOUT]?.toIntOrNull() ?: 60,
            requireHttps = values[MangaPreferences.SERVER_REQUIRE_HTTPS] != "false",
        )
    }

    /** Saves (or, with null, forgets) the password of the active profile's remote server. It is kept in the credential store only. */
    fun setRemotePassword(profileId: Long, password: String?) {
        val store = storage.secrets.child("profile.$profileId")
        if (password.isNullOrEmpty()) store.delete(SERVER_PASSWORD) else store.put(SERVER_PASSWORD, password)
    }

    fun hasRemotePassword(profileId: Long): Boolean = storage.secrets.child("profile.$profileId").get(SERVER_PASSWORD) != null

    /** The remote server as the active profile's settings describe it now, with the stored password; null when it uses its own. */
    fun remoteServerOf(profileId: Long): RemoteServer? = remoteServer(profileId)

    override fun onActiveProfileChanged(profileId: Long?) {
        val services = _services.value ?: return
        services.preferences.load(profileId)
        _activeProfile.value = profileId
        // Each profile has a server of its own. One that is running is replaced now; otherwise
        // nothing starts until Manga is opened.
        services.server.activate(profileId)
    }

    /** Starts the active profile's server if it is not running yet. Called when Manga (or its settings) is opened. */
    fun ensureServer() {
        _services.value?.server?.ensureStarted()
    }

    /**
     * Starts the active profile's server if needed and waits until it answers. Throws, with the
     * server's own reason, when it cannot be used. For things that reach Manga from outside it (a
     * comic archive handed over by the download manager) before Manga was ever opened.
     */
    suspend fun awaitServer(timeoutMs: Long = 120_000) {
        val server = _services.value?.server ?: throw java.io.IOException("Manga is not running.")
        server.ensureStarted()
        val settled = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            server.state.first { it is MangaConnection.Ready || (it is MangaConnection.Failed && !it.retrying) }
        }
        when (settled) {
            is MangaConnection.Ready -> Unit
            is MangaConnection.Failed -> throw java.io.IOException(settled.message)
            else -> throw java.io.IOException("The manga server did not start in time.")
        }
    }

    override fun onProfileDeleted(profileId: Long) {
        val services = _services.value ?: return
        services.metadata.deleteProfile(profileId)
        runCatching { storage.secrets.child("profile.$profileId").delete(SERVER_PASSWORD) }
        // The profile's whole Suwayomi folder goes: library, downloads, extensions, tracker sign-ins.
        // A deleted profile is never the active one, so its server is not running.
        val folder = services.server.manager.directory(profileId)
        if (Files.exists(folder)) {
            runCatching { Files.walk(folder).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
        }
    }

    override fun onClosing() {
        _services.value?.server?.close()
    }

    override val pluginImportUnavailableReason: String? =
        "Manga's sources are Mihon/Tachiyomi extensions, installed by its Suwayomi server from an extension store you add. Use the Manga tab."

    override suspend fun importPlugin(location: String): PluginActionResult =
        PluginActionResult.Failure(pluginImportUnavailableReason!!)

    override suspend fun availablePlugins(): List<AvailablePlugin> = emptyList()

    /** Why the bundled server cannot run in this build, or null when it is present. */
    fun runtimeProblem(): String? = try {
        runtime()
        null
    } catch (failure: SuwayomiUnavailable) {
        failure.message
    }

    override val readiness: ModuleReadiness = ModuleReadiness.Ready

    /** Manga's own plugin registry table, separate from every other module's. Suwayomi's extensions are listed by the server, not here. */
    override val pluginTable = "manga_extensions"
    override val metadataTable = "manga_metadata_providers"
    override val settingsTable = "manga_profile_settings"

    override val migrations = listOf(
        Migration(
            // The description is part of the stored migration history: it stays as it was written.
            1, "tiyo extension registry, manga metadata providers, profile settings",
            PluginTableStore.createTableSql("manga_extensions"),
            MetadataProviderTableStore.createTableSql("manga_metadata_providers"),
            ProfileSettingsTableStore.createTableSql("manga_profile_settings"),
        ),
        MangaSchema.library,
        MangaMigration.schema,
    )

    override val supportedPluginKinds = setOf(
        PluginKind.SOURCE,
        PluginKind.METADATA,
        PluginKind.UTILITY,
    )

    override val pluginImportHint = "Extensions are installed from an extension store, in the Manga tab."

    override val defaultMetadataProviders = listOf(
        MetadataProvider("manual", "Manual edits", "Details you set yourself. Locked fields are never overwritten.", enabled = true, priority = -10, implemented = true),
        MetadataProvider("extension", "Source metadata", "Details supplied by the source the series comes from, through its extension.", enabled = true, priority = 0, implemented = true),
        MetadataProvider("anilist", "AniList", "Titles, synopsis, genres, status, authors, covers and banners.", enabled = true, priority = 10, implemented = true),
        MetadataProvider("mangadex", "MangaDex", "Titles, synopsis, tags, status, authors and covers.", enabled = true, priority = 20, implemented = true),
        MetadataProvider("myanimelist", "MyAnimeList", "The official API. Needs your own API client ID.", enabled = false, priority = 30, implemented = true, requiresCredentials = true),
    )

    override val appearanceOptions: List<AppearanceOption> = listOf(
        StandardAppearanceOptions.accent(),
        StandardAppearanceOptions.cardSize("Cover size"),
        StandardAppearanceOptions.layout("Library layout"),
        StandardAppearanceOptions.density(),
        AppearanceOption.Choice(
            "card_style", "Card style",
            listOf(ChoiceValue("poster", "Cover with title below"), ChoiceValue("overlay", "Title over the cover"), ChoiceValue("cover", "Cover only")), "poster",
        ),
        AppearanceOption.Choice(
            "library_background", "Library background",
            listOf(ChoiceValue("black", "Black"), ChoiceValue("dark", "Dark grey"), ChoiceValue("accent", "Tinted with the accent colour")), "black",
        ),
        AppearanceOption.Choice(
            "typography", "Titles",
            listOf(ChoiceValue("standard", "Standard"), ChoiceValue("compact", "Compact"), ChoiceValue("large", "Large")), "standard",
        ),
        AppearanceOption.Choice(
            "chapter_display", "Chapter list",
            listOf(ChoiceValue("list", "Comfortable"), ChoiceValue("compact", "Compact")), "list",
        ),
        AppearanceOption.Choice(
            "animations", "Animations",
            listOf(ChoiceValue("on", "On"), ChoiceValue("reduced", "Reduced")), "on",
        ),
    )

    companion object {
        private const val SERVER_PREFIX = "pref."
        private const val SERVER_PASSWORD = "server.password"

        val LOCAL_FILES = PluginDescriptor(
            "local-files", "Local files", PluginKind.SOURCE,
            description = "CBZ, ZIP, CBR, RAR and EPUB files and image folders on this computer, read through the manga server's Local source. Needs no extension.", builtIn = true,
        )
    }
}
