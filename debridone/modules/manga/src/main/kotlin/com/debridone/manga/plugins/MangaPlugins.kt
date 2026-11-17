package com.debridone.manga.plugins

import com.debridone.manga.host.ExtensionHost
import com.debridone.manga.host.HostException
import com.debridone.manga.host.HostLaunch
import com.debridone.manga.host.HostPluginState
import com.debridone.manga.host.MangaExtensions
import com.debridone.manga.host.SourceDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** An installed extension package and what is known about it. */
data class PackageStatus(
    val name: String,
    val version: String,
    val kind: PackageKind?,
    val compatibility: Compatibility,
    /** Loaded by the running host. False with an [error] when loading failed, and false when disabled. */
    val loaded: Boolean,
    val sourceCount: Int,
    val error: String?,
    /** A legacy package whose source the Tiyo package also provides; Tiyo's copy is the one in use. */
    val shadowedBy: String?,
)

/** A package the registry offers, with what DebridOne knows about each version. */
data class AvailablePackage(
    val name: String,
    val kind: PackageKind,
    val description: String?,
    val license: String?,
    val homepage: String?,
    val latest: String?,
    val versions: List<RegistryVersion>,
    val installedVersion: String?,
) {
    val updateAvailable: Boolean
        get() = installedVersion != null && latest != null && PluginRules.compareVersions(latest, installedVersion) > 0
}

/**
 * A package that has been downloaded, checked and loaded once on trial — everything the person
 * needs to decide whether to install it. Nothing is in use until [MangaPlugins.confirm].
 */
class PreparedInstall internal constructor(
    val staged: StagedPackage,
    val kind: PackageKind,
    val compatibility: Compatibility,
    /** The sources the package registered when it was loaded on trial. */
    val sources: List<SourceDescriptor>,
    val replacesVersion: String?,
    /** Things the person should know before saying yes. */
    val warnings: List<String>,
) {
    val name: String get() = staged.name
    val version: String get() = staged.version

    /** The sites the package's sources talk to. */
    val sites: List<String> get() = sources.mapNotNull { it.url.takeIf(String::isNotBlank) }.distinct()
}

/**
 * Installing, updating and removing extension packages.
 *
 * Installing is deliberately two steps. [prepare] downloads the package, verifies every archive,
 * unpacks it into a staging folder and loads it once in a separate, throw-away host process to see
 * that it really is an extension package and which sources it brings. Only [confirm] — called after
 * the person has read the review — moves it into the plugin folder and loads it into the running
 * host. Declining ([cancel]) leaves no trace.
 */
class MangaPlugins(
    private val installer: PluginInstaller,
    private val extensions: MangaExtensions,
    /** The running host; used to resolve dependencies of versions that have no pinned plan. */
    private val host: ExtensionHost,
    /** Launch settings for a trial host that reads [pluginsDir] instead of the real plugin folder. */
    private val trialLaunch: (pluginsDir: Path) -> HostLaunch,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** Packages in the plugin folder, with how the running host fared with each. */
    suspend fun installed(disabled: Set<String> = emptySet()): List<PackageStatus> {
        val onDisk = withContext(Dispatchers.IO) { installer.installed() }
        val state: HostPluginState? = extensions.state.value
        return onDisk.map { pkg ->
            val loaded = state?.packages?.firstOrNull { it.name == pkg.name }
            PackageStatus(
                name = pkg.name,
                version = pkg.version,
                kind = pkg.kind,
                compatibility = PluginRules.compatibility(pkg.name, pkg.version),
                loaded = loaded?.loaded == true && pkg.name !in disabled,
                sourceCount = loaded?.sourceCount ?: 0,
                error = loaded?.error,
                shadowedBy = loaded?.shadowedBy,
            )
        }
    }

    /**
     * What can be installed: the Tiyo package — the one package current Houdoku loads — as the
     * registry lists it, followed (when [includeLegacy]) by Houdoku's older per-source packages.
     * Everything here comes from the registry's own answers; nothing is listed that it does not have.
     */
    suspend fun available(includeLegacy: Boolean = false): List<AvailablePackage> {
        val installed = withContext(Dispatchers.IO) { installer.installed() }.associate { it.name to it.version }
        val tiyo = installer.registryInfo(PluginRules.TIYO_PACKAGE)
        val result = ArrayList<AvailablePackage>()
        result += AvailablePackage(
            name = tiyo.name,
            kind = PackageKind.TIYO,
            description = tiyo.description,
            license = tiyo.license,
            homepage = tiyo.homepage,
            latest = tiyo.latest,
            versions = tiyo.versions.filter { PluginRules.compatibility(tiyo.name, it.version).level != CompatibilityLevel.UNSUPPORTED },
            installedVersion = installed[tiyo.name],
        )
        if (includeLegacy) {
            for (hit in installer.searchLegacyPackages()) {
                result += AvailablePackage(hit.name, PackageKind.LEGACY, legacyDescription(hit.description), null, null, hit.version, listOf(RegistryVersion(hit.version, hit.date)), installed[hit.name])
            }
        }
        return result
    }

    /** Legacy packages carry their source's metadata as JSON in the description field. */
    private fun legacyDescription(description: String?): String? {
        val text = description?.trim().orEmpty()
        if (!text.startsWith("{")) return text.ifEmpty { null }
        val name = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        return listOfNotNull(name, url).joinToString(" — ").ifEmpty { null }
    }

    /** Downloads, verifies and trial-loads [name]@[version] from the registry. */
    suspend fun prepare(name: String, version: String, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): PreparedInstall = lock.withLock {
        val kind = PluginRules.kindOf(name) ?: throw PackageRejectedException(PluginRules.compatibility(name, version).explanation)
        val compatibility = PluginRules.compatibility(name, version)
        if (compatibility.level == CompatibilityLevel.UNSUPPORTED) throw PackageRejectedException(compatibility.explanation)
        val pinned = installer.pinnedPlan(name, version)
        val plan = pinned ?: resolve(name, version)
        val staged = installer.stage(plan, pinned = pinned != null, progress = progress)
        review(staged, kind, compatibility)
    }

    /** The same for a package archive (`.tgz`) picked from disk. Its dependencies still come from the registry. */
    suspend fun prepareFromFile(file: Path, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): PreparedInstall = lock.withLock {
        val info = withContext(Dispatchers.IO) { installer.inspectArchive(file) }
        val kind = PluginRules.kindOf(info.name)!!
        val compatibility = PluginRules.compatibility(info.name, info.version)
        if (compatibility.level == CompatibilityLevel.UNSUPPORTED) throw PackageRejectedException(compatibility.explanation)
        val pinned = installer.pinnedPlan(info.name, info.version)
        val plan = pinned ?: try {
            resolve(info.name, info.version)
        } catch (failure: HostException) {
            throw PackageRejectedException(
                "The registry does not know ${info.name} ${info.version}, so the packages it depends on cannot be worked out. " +
                    "Only archives of published versions can be imported.",
            )
        }
        val staged = installer.stage(plan, pinned = pinned != null, rootArchive = file, progress = progress)
        review(staged, kind, compatibility)
    }

    private suspend fun resolve(name: String, version: String): InstallPlan {
        val answer = host.call(
            "plugins.resolve",
            buildJsonObject {
                put("name", name)
                put("version", version)
                put("registry", installer.registry)
            },
            timeoutMs = 180_000,
        )
        return json.decodeFromJsonElement(InstallPlan.serializer(), answer)
    }

    /** Loads the staged package in a throw-away host that can read nothing but the staging folder. */
    private suspend fun review(staged: StagedPackage, kind: PackageKind, compatibility: Compatibility): PreparedInstall {
        val trial = ExtensionHost({ trialLaunch(staged.root) })
        val state = try {
            MangaExtensions(trial).ensureLoaded()
        } catch (failure: HostException) {
            installer.discard(staged)
            throw PackageRejectedException("The package could not be loaded on trial: ${failure.message}")
        } finally {
            trial.close()
        }
        val pkg = state.packages.firstOrNull { it.name == staged.name }
        if (pkg == null || !pkg.loaded || state.sources.isEmpty()) {
            installer.discard(staged)
            throw PackageRejectedException(
                "${staged.name} ${staged.version} is not a working extension package: " +
                    (pkg?.error ?: "it registered no sources."),
            )
        }
        val broken = state.sources.filter { it.problems.isNotEmpty() }
        val warnings = ArrayList<String>()
        if (compatibility.level == CompatibilityLevel.UNTESTED) warnings += compatibility.explanation
        if (staged.matchesPublished == false) warnings += "This file is not the archive the registry publishes for ${staged.name} ${staged.version}. Install it only if you know who built it."
        if (!staged.pinned) warnings += "The packages this version depends on were looked up in the registry just now (each was verified against the registry's checksum)."
        if (broken.isNotEmpty()) warnings += "${broken.size} of its sources do not implement the whole extension interface: ${broken.take(5).joinToString { it.name }}."
        val windowed = state.sources.count { it.usesBrowserWindow == true }
        if (windowed > 0) warnings += "$windowed of its sources load pages through Houdoku's hidden browser window. DebridOne loads those pages without running their scripts, so sites that demand a browser check will not work."
        val replaces = withContext(Dispatchers.IO) { installer.installed() }.firstOrNull { it.name == staged.name }?.version
        return PreparedInstall(staged, kind, compatibility, state.sources, replaces, warnings)
    }

    /** Puts a reviewed package in place and loads it. Returns what the running host has loaded afterwards. */
    suspend fun confirm(prepared: PreparedInstall): HostPluginState = lock.withLock {
        // The running host must let go of the old files before they are replaced.
        extensions.unload()
        try {
            withContext(Dispatchers.IO) { installer.activate(prepared.staged) }
        } finally {
            runCatching { extensions.reload() }
        }
        extensions.ensureLoaded()
    }

    suspend fun cancel(prepared: PreparedInstall) = lock.withLock {
        withContext(Dispatchers.IO) { installer.discard(prepared.staged) }
    }

    /** Uninstalls a package. Returns false when it was not installed. */
    suspend fun remove(name: String): Boolean = lock.withLock {
        extensions.unload()
        try {
            withContext(Dispatchers.IO) { installer.remove(name) }
        } finally {
            runCatching { extensions.reload() }
        }
    }

    /** Installed packages for which the registry has a newer version that DebridOne supports: name to that version. */
    suspend fun updates(): Map<String, String> {
        val result = HashMap<String, String>()
        for (pkg in withContext(Dispatchers.IO) { installer.installed() }) {
            if (pkg.kind == null) continue
            val info = runCatching { installer.registryInfo(pkg.name) }.getOrNull() ?: continue
            val newest = info.versions.firstOrNull { PluginRules.compatibility(pkg.name, it.version).level != CompatibilityLevel.UNSUPPORTED && !it.version.contains('-') }
                ?: continue
            if (PluginRules.compareVersions(newest.version, pkg.version) > 0) result[pkg.name] = newest.version
        }
        return result
    }

    fun cleanUp() = installer.cleanUp()

    /** Size on disk of the installed packages, for the settings page. */
    fun installedBytes(pluginsDir: Path): Long {
        if (!Files.isDirectory(pluginsDir)) return 0
        return Files.walk(pluginsDir).use { stream -> stream.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0) }.sum() }
    }
}
