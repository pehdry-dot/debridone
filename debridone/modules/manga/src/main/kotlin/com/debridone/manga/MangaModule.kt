package com.debridone.manga

import com.debridone.manga.download.MangaDownloads
import com.debridone.manga.host.ExtensionHost
import com.debridone.manga.host.HostException
import com.debridone.manga.host.HostLaunch
import com.debridone.manga.host.HostRuntime
import com.debridone.manga.host.MangaExtensions
import com.debridone.manga.host.MangaHostRuntime
import com.debridone.manga.library.MangaBackup
import com.debridone.manga.library.MangaRepository
import com.debridone.manga.library.MangaSchema
import com.debridone.manga.local.LocalLibrary
import com.debridone.manga.metadata.MangaMetadata
import com.debridone.manga.metadata.MetadataEndpoints
import com.debridone.manga.plugins.MangaPlugins
import com.debridone.manga.plugins.PackageKind
import com.debridone.manga.plugins.PluginInstaller
import com.debridone.manga.plugins.PluginRules
import com.debridone.manga.plugins.PreparedInstall
import com.debridone.manga.reader.MangaPages
import com.debridone.manga.sources.MangaSources
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/** Everything the Manga screens work with. Created when the module starts. */
class MangaServices internal constructor(
    val repository: MangaRepository,
    val host: ExtensionHost,
    val extensions: MangaExtensions,
    val plugins: MangaPlugins,
    val sources: MangaSources,
    val pages: MangaPages,
    val local: LocalLibrary,
    val downloads: MangaDownloads,
    val metadata: MangaMetadata,
    val backup: MangaBackup,
    val preferences: MangaPreferences,
    val appSettings: MangaAppSettings,
    /** Where extension packages are installed (shared by all profiles). */
    val pluginsDir: Path,
    val downloadsDir: Path,
)

/**
 * Manga & Manhwa module.
 *
 * Its sources are the extensions of Houdoku (https://github.com/xgi/houdoku, MIT): the unmodified
 * Tiyo package (https://github.com/xgi/tiyo, MIT) runs in a bundled Node.js helper process, loaded
 * with Houdoku's own plugin manager (aki-plugin-manager, MIT). See debridone/docs/MANGA.md and
 * THIRD_PARTY_NOTICES.md. Local files (CBZ, ZIP, image folders) are read without any of that.
 *
 * Owns `modules/manga/manga.db`. Nothing in here may reference Streaming, Music or Games.
 */
class MangaModule(
    storage: ModuleStorage,
    clock: () -> Long = System::currentTimeMillis,
    /** Finds the Node.js runtime. The app uses the bundled copy; tests name theirs. */
    private val runtimeLocator: () -> HostRuntime = { MangaHostRuntime.locate() },
    /** Package registry address; tests use a local stand-in. */
    private val registry: String = PluginInstaller.DEFAULT_REGISTRY,
    /** Where the metadata services are reached; tests use local stand-ins. */
    private val metadataEndpoints: MetadataEndpoints = MetadataEndpoints(),
) : SqliteBackedModule(ModuleId.MANGA, storage, clock) {

    private val _services = MutableStateFlow<MangaServices?>(null)

    /** Null until the module is running. */
    val services: StateFlow<MangaServices?> = _services.asStateFlow()

    private val _activeProfile = MutableStateFlow<Long?>(null)

    /** The profile whose library and reading state are shown. */
    val activeProfile: StateFlow<Long?> = _activeProfile.asStateFlow()

    private val pluginsDir: Path get() = storage.dataDir.resolve("plugins")

    override fun onStarted() {
        val database = requireDatabase()
        val repository = MangaRepository(database, clock)
        val appSettings = MangaAppSettings(::settingsStore).also { it.load() }
        val preferences = MangaPreferences(::settingsStore)
        val spool = storage.cacheDir.resolve("host-spool")
        // Images handed over by a host that is gone are of no use to anyone.
        runCatching { PluginInstaller.deleteRecursively(spool) }

        fun launch(plugins: Path, name: String) = HostLaunch(
            runtime = runtimeLocator(),
            pluginsDir = plugins,
            spoolDir = storage.cacheDir.resolve(if (name == "main") "host-spool" else "host-spool-$name"),
            homeDir = storage.cacheDir.resolve("host-home"),
            proxy = appSettings.proxy ?: environmentProxy(),
        )
        val host = ExtensionHost({ launch(pluginsDir, "main") }, clock)
        lateinit var sources: MangaSources
        val extensions = MangaExtensions(
            host,
            storedSettings = { descriptor -> _activeProfile.value?.let { profile -> sources.storedSettings(profile, descriptor.id) } },
            disabledPackages = { plugins.installed.value.filter { !it.enabled && !it.builtIn }.map { it.id }.toSet() },
        ).also { it.timeoutMs = appSettings.timeoutSeconds * 1000L }
        lateinit var metadata: MangaMetadata
        sources = MangaSources(extensions, repository, storage.secrets, clock, sourceLeads = { seriesId -> metadata.extensionLeads(seriesId) })
        metadata = MangaMetadata(
            repository, storage.secrets,
            providerEnabled = { id -> metadataProviders.providers.value.firstOrNull { it.id == id }?.enabled == true },
            providerPriority = { id -> metadataProviders.providers.value.firstOrNull { it.id == id }?.priority ?: Int.MAX_VALUE },
            extensionDetails = { series -> sources.sourceDetails(series) },
            endpoints = metadataEndpoints, clock = clock,
        )
        val installer = PluginInstaller(pluginsDir, storage.dataDir.resolve("plugin-staging"), registry)
        runCatching { installer.cleanUp() }
        val local = LocalLibrary(repository)
        val pages = MangaPages(repository, extensions, local, storage.cacheDir, storage.dataDir.resolve("covers"), clock)
        val services = MangaServices(
            repository = repository,
            host = host,
            extensions = extensions,
            plugins = MangaPlugins(installer, extensions, host) { staging -> launch(staging, "trial") },
            sources = sources,
            pages = pages,
            local = local,
            downloads = MangaDownloads(repository, pages, storage.dataDir.resolve("downloads"), moduleScope) { appSettings.parallelDownloads },
            metadata = metadata,
            backup = MangaBackup(repository, clock),
            preferences = preferences,
            appSettings = appSettings,
            pluginsDir = pluginsDir,
            downloadsDir = storage.dataDir.resolve("downloads"),
        )
        _services.value = services

        runBlocking {
            runCatching { registerPlugin(LOCAL_FILES) }
            // Packages on disk that the registry does not know yet (for example after restoring a backup).
            for (pkg in installer.installed()) {
                if (pkg.kind != null && plugins.installed.value.none { it.id == pkg.name }) runCatching { registerPlugin(descriptor(pkg.name, pkg.version, 0)) }
            }
        }
        // Switching a package on or off takes effect at once: the host loads the packages again.
        moduleScope.launch {
            plugins.installed.map { list -> list.filter { !it.enabled }.map { it.id }.toSet() }.distinctUntilChanged().drop(1).collect {
                runCatching { if (installer.installed().isNotEmpty()) extensions.reload() }
            }
        }
        // Load the installed packages in the background so the sources are there when Manga is opened.
        // With nothing installed the host is not started at all.
        moduleScope.launch(Dispatchers.IO) {
            runCatching { services.pages.trimCache(appSettings.cacheMegabytes * 1024L * 1024L) }
            if (installer.installed().any { it.kind != null }) runCatching { extensions.ensureLoaded() }
            // Downloads that were under way when the app last closed carry on.
            runCatching { services.downloads.resume() }
        }
    }

    override fun onActiveProfileChanged(profileId: Long?) {
        val services = _services.value ?: return
        val changed = _activeProfile.value != profileId
        services.preferences.load(profileId)
        if (profileId != null) services.repository.setChapterLanguages(profileId, services.preferences.chapterLanguages())
        _activeProfile.value = profileId
        if (changed && profileId != null) {
            moduleScope.launch(Dispatchers.IO) {
                // Series that were only looked at, a month ago or more, are forgotten.
                runCatching { services.repository.pruneUnused(profileId, PREVIEW_RETENTION_MS) }
                // Each profile has its own source settings (server addresses, logins): load the sources afresh with them.
                if (services.extensions.state.value != null) runCatching { services.extensions.reload() }
            }
        }
    }

    override fun onProfileDeleted(profileId: Long) {
        val services = _services.value ?: return
        services.extensions.state.value?.sources?.forEach { services.sources.clearSettings(profileId, it.id) }
        services.metadata.deleteProfile(profileId)
        val downloadDirs = services.repository.deleteProfile(profileId)
        downloadDirs.forEach { dir -> runCatching { PluginInstaller.deleteRecursively(Path.of(dir)) } }
        runCatching { PluginInstaller.deleteRecursively(services.downloadsDir.resolve(profileId.toString())) }
    }

    override fun onClosing() {
        _services.value?.host?.close()
    }

    // ---- installing extension packages ----------------------------------------------------------

    /**
     * Second step of an install: the person has read the review. Puts the package in place, loads
     * it, and lists it in Manga's plugin registry.
     */
    suspend fun confirmInstall(prepared: PreparedInstall): PluginActionResult {
        val services = _services.value ?: return PluginActionResult.Failure("Manga is not running.")
        return try {
            val state = services.plugins.confirm(prepared)
            val loaded = state.packages.firstOrNull { it.name == prepared.name }
            val plugin = registerPlugin(descriptor(prepared.name, prepared.version, loaded?.sourceCount ?: prepared.sources.size))
            if (loaded != null && !loaded.loaded && loaded.error != null) reportPluginError(prepared.name, loaded.error)
            PluginActionResult.Success(plugin)
        } catch (failure: Exception) {
            PluginActionResult.Failure(failure.message ?: "The package could not be installed.")
        }
    }

    private fun descriptor(name: String, version: String, sourceCount: Int): PluginDescriptor = when (PluginRules.kindOf(name)) {
        PackageKind.TIYO -> PluginDescriptor(
            id = name, name = "Tiyo", kind = PluginKind.SOURCE, version = version,
            description = "Houdoku's extension package" + if (sourceCount > 0) ": $sourceCount manga sources." else ".",
            origin = "npm:$name",
        )
        else -> PluginDescriptor(
            id = name, name = name.substringAfter("extension-").replaceFirstChar(Char::uppercase) + " (legacy)", kind = PluginKind.SOURCE, version = version,
            description = "Older Houdoku extension format: one source.", origin = "npm:$name",
        )
    }

    override val pluginImportUnavailableReason: String? = null

    /**
     * The generic import of the Plugins screen. Manga installs packages from its own tab, where a
     * package is shown for review before it is installed; this entry point has no such step, so it
     * only accepts the exact package DebridOne has verified and pinned.
     */
    override suspend fun importPlugin(location: String): PluginActionResult {
        val services = _services.value ?: return PluginActionResult.Failure("Manga is not running.")
        val text = location.trim().removePrefix("npm:")
        val name = if (text.startsWith("@")) "@" + text.drop(1).substringBefore("@") else text.substringBefore("@")
        val version = text.removePrefix(name).removePrefix("@").ifBlank { null }
        if (PluginRules.kindOf(name) == null) {
            return PluginActionResult.Failure(
                "\"$location\" is not a Houdoku extension package. Houdoku's sources come in one package, ${PluginRules.TIYO_PACKAGE}; install it from Plugins › Manga › Browse.",
            )
        }
        val wanted = version ?: PluginRules.VERIFIED_TIYO_VERSIONS.maxWithOrNull(PluginRules::compareVersions)
        if (name != PluginRules.TIYO_PACKAGE || wanted !in PluginRules.VERIFIED_TIYO_VERSIONS) {
            return PluginActionResult.Failure("Only verified versions can be installed here. Use Plugins › Manga › Browse to review and install $name${version?.let { " $it" }.orEmpty()}.")
        }
        return try {
            confirmInstall(services.plugins.prepare(name, wanted!!))
        } catch (failure: Exception) {
            PluginActionResult.Failure(failure.message ?: "The package could not be installed.")
        }
    }

    override suspend fun availablePlugins(): List<AvailablePlugin> {
        val services = _services.value ?: return emptyList()
        return runCatching { services.plugins.available() }.getOrDefault(emptyList())
            .filter { it.installedVersion == null }
            .map { AvailablePlugin(it.name, "Tiyo", PluginKind.SOURCE, it.description, "npm:${it.name}") }
    }

    /** The registry entry is gone; remove the package's files as well. */
    override fun onPluginRemoved(pluginId: String) {
        val services = _services.value ?: return
        if (PluginRules.kindOf(pluginId) == null) return
        runBlocking { runCatching { services.plugins.remove(pluginId) } }
    }

    private fun environmentProxy(): String? =
        listOf("HTTPS_PROXY", "https_proxy").firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }

    /** Why online sources cannot run in this build, or null when the runtime is present. */
    fun runtimeProblem(): String? = try {
        runtimeLocator()
        null
    } catch (failure: HostException) {
        failure.message
    }

    override val readiness: ModuleReadiness = ModuleReadiness.Ready

    /** Houdoku/Tiyo extensions live here, separate from every other module's plugins. */
    override val pluginTable = "manga_extensions"
    override val metadataTable = "manga_metadata_providers"
    override val settingsTable = "manga_profile_settings"

    override val migrations = listOf(
        Migration(
            1, "tiyo extension registry, manga metadata providers, profile settings",
            PluginTableStore.createTableSql("manga_extensions"),
            MetadataProviderTableStore.createTableSql("manga_metadata_providers"),
            ProfileSettingsTableStore.createTableSql("manga_profile_settings"),
        ),
        MangaSchema.library,
    )

    override val supportedPluginKinds = setOf(
        PluginKind.SOURCE,
        PluginKind.METADATA,
        PluginKind.UTILITY,
    )

    override val pluginImportHint =
        "Houdoku's extension package (@tiyo/core). Other packages are not Houdoku extensions and are refused."

    override val defaultMetadataProviders = listOf(
        MetadataProvider("manual", "Manual edits", "Details you set yourself. Locked fields are never overwritten.", enabled = true, priority = -10, implemented = true),
        MetadataProvider("extension", "Extension metadata", "Details supplied by the source the series comes from.", enabled = true, priority = 0, implemented = true),
        MetadataProvider("anilist", "AniList", "Titles, synopsis, genres, status, authors, covers and banners. Optional reading-list tracking with your own account.", enabled = true, priority = 10, implemented = true),
        MetadataProvider("mangadex", "MangaDex", "Titles, synopsis, tags, status, authors and covers.", enabled = true, priority = 20, implemented = true),
        MetadataProvider("myanimelist", "MyAnimeList", "The official API. Needs your own API client ID; tracking needs you to sign in.", enabled = false, priority = 30, implemented = true, requiresCredentials = true),
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
        private const val PREVIEW_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        val LOCAL_FILES = PluginDescriptor(
            "local-files", "Local files", PluginKind.SOURCE,
            description = "CBZ and ZIP archives and image folders on this computer. Needs no extension.", builtIn = true,
        )
    }
}
