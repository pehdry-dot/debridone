package com.debridone.manga.plugins

import com.debridone.manga.net.MangaHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

data class InstalledPackage(val name: String, val version: String, val kind: PackageKind?, val dir: Path)

data class RegistryVersion(val version: String, val publishedAt: String?)

data class RegistryInfo(
    val name: String,
    val latest: String?,
    val description: String?,
    val license: String?,
    val homepage: String?,
    val versions: List<RegistryVersion>,
)

data class RegistrySearchHit(val name: String, val version: String, val description: String?, val date: String?)

/** What a package archive on disk says about itself. */
data class ArchiveInfo(val name: String, val version: String, val sha512: String, val sizeBytes: Long)

/**
 * A package that has been downloaded, verified and unpacked, but is not in use yet.
 * [root] is shaped like a plugin folder (`<root>/@tiyo/core/…`), so a host can be pointed at it to
 * try the package before it replaces anything.
 */
class StagedPackage(
    val name: String,
    val version: String,
    val root: Path,
    val packageDir: Path,
    val packages: Int,
    val files: Int,
    val bytes: Long,
    /** True when the list of archives and checksums came with DebridOne rather than from a live lookup. */
    val pinned: Boolean,
    /** For an imported file: whether it is byte-for-byte the archive the registry publishes. Null when installed from the registry. */
    val matchesPublished: Boolean?,
)

/**
 * Installs Houdoku extension packages into the plugin folder, in the layout Houdoku's own plugin
 * manager (aki) produces: `<plugins>/<name>/` with each dependency under the `node_modules` of the
 * package that needs it.
 *
 * What it adds over aki's installer:
 *  - only Houdoku extension packages are accepted (see [PluginRules]);
 *  - archives come from the package registry only, and each must match its SHA-512;
 *  - archives are unpacked by [TarGz], which refuses links and paths outside the package;
 *  - no install script is ever run;
 *  - the package is unpacked to a staging folder first and only moved into place by [activate],
 *    so a failed or declined install leaves the working package untouched.
 */
class PluginInstaller(
    private val pluginsDir: Path,
    private val workDir: Path,
    registry: String = DEFAULT_REGISTRY,
) {
    val registry: String = registry.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val counter = AtomicInteger(0)

    /** What is in the plugin folder now. The same reading rule as aki's `list`. */
    fun installed(): List<InstalledPackage> {
        if (!Files.isDirectory(pluginsDir)) return emptyList()
        val found = ArrayList<InstalledPackage>()
        fun add(dir: Path) {
            val manifest = readManifest(dir) ?: return
            val name = manifest.string("name") ?: return
            val version = manifest.string("version") ?: return
            found += InstalledPackage(name, version, PluginRules.kindOf(name), dir)
        }
        Files.newDirectoryStream(pluginsDir).use { entries ->
            for (entry in entries.sortedBy { it.fileName.toString() }) {
                if (!Files.isDirectory(entry)) continue
                if (entry.fileName.toString().startsWith("@")) {
                    Files.newDirectoryStream(entry).use { scoped -> scoped.filter { Files.isDirectory(it) }.sortedBy { it.fileName.toString() }.forEach(::add) }
                } else {
                    add(entry)
                }
            }
        }
        return found
    }

    /** The archive list that ships with DebridOne for [name]@[version], if there is one. */
    fun pinnedPlan(name: String, version: String): InstallPlan? {
        val resource = "/manga/plans/${name.replace("@", "").replace("/", "-")}-$version.json"
        val text = PluginInstaller::class.java.getResourceAsStream(resource)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        val plan = json.decodeFromString(InstallPlan.serializer(), text)
        return plan.takeIf { it.name == name && it.version == version }
    }

    /** Asks the registry which versions of [name] exist. */
    suspend fun registryInfo(name: String): RegistryInfo = withContext(Dispatchers.IO) {
        requireKind(name)
        val document = json.parseToJsonElement(MangaHttp.get("$registry/${name.replace("/", "%2F")}", mapOf("Accept" to "application/json")).text()).jsonObject
        val times = document["time"] as? JsonObject
        val versions = (document["versions"] as? JsonObject)?.keys.orEmpty()
            .filter(PluginRules::isVersion)
            .sortedWith { a, b -> PluginRules.compareVersions(b, a) }
            .map { RegistryVersion(it, times?.get(it)?.jsonPrimitive?.contentOrNull) }
        val latest = (document["dist-tags"] as? JsonObject)?.get("latest")?.jsonPrimitive?.contentOrNull
        val latestManifest = latest?.let { (document["versions"] as? JsonObject)?.get(it) as? JsonObject }
        RegistryInfo(
            name = name,
            latest = latest,
            description = document.string("description") ?: latestManifest?.string("description"),
            license = document.string("license") ?: latestManifest?.string("license"),
            homepage = document.string("homepage") ?: latestManifest?.string("homepage"),
            versions = versions,
        )
    }

    /** The older per-source packages published under the `@houdoku` scope. */
    suspend fun searchLegacyPackages(): List<RegistrySearchHit> = withContext(Dispatchers.IO) {
        val document = json.parseToJsonElement(MangaHttp.get("$registry/-/v1/search?text=%40houdoku&size=250").text()).jsonObject
        document["objects"]?.jsonArray.orEmpty().mapNotNull { item ->
            val pkg = (item as? JsonObject)?.get("package") as? JsonObject ?: return@mapNotNull null
            val name = pkg.string("name") ?: return@mapNotNull null
            if (PluginRules.kindOf(name) != PackageKind.LEGACY) return@mapNotNull null
            RegistrySearchHit(name, pkg.string("version") ?: return@mapNotNull null, pkg.string("description"), pkg.string("date"))
        }.sortedBy { it.name }
    }

    /** Reads the name, version and checksum of a package archive the user picked. */
    fun inspectArchive(file: Path): ArchiveInfo {
        if (!Files.isRegularFile(file)) throw PackageRejectedException("That file does not exist.")
        val size = Files.size(file)
        if (size > MAX_ARCHIVE_BYTES) throw PackageRejectedException("That file is too large to be an extension package.")
        val manifestBytes = Files.newInputStream(file).use { TarGz.readFile(it, "package.json") }
            ?: throw PackageRejectedException("This archive has no package.json, so it is not an extension package.")
        val manifest = runCatching { json.parseToJsonElement(manifestBytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
            ?: throw PackageRejectedException("The package.json in this archive cannot be read.")
        val name = manifest.string("name") ?: throw PackageRejectedException("The package does not state its name.")
        val version = manifest.string("version") ?: throw PackageRejectedException("The package does not state its version.")
        requireKind(name)
        if (!PluginRules.isVersion(version)) throw PackageRejectedException("\"$version\" is not a version number.")
        val digest = MessageDigest.getInstance("SHA-512")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return ArchiveInfo(name, version, "sha512-" + Base64.getEncoder().encodeToString(digest.digest()), size)
    }

    /**
     * Downloads, verifies and unpacks everything in [plan] into a staging folder.
     * With [rootArchive], the package itself is taken from that file instead of the registry (its
     * dependencies still come from the registry).
     */
    suspend fun stage(
        plan: InstallPlan,
        pinned: Boolean,
        rootArchive: Path? = null,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): StagedPackage = withContext(Dispatchers.IO) {
        requireKind(plan.name)
        val compatibility = PluginRules.compatibility(plan.name, plan.version)
        if (compatibility.level == CompatibilityLevel.UNSUPPORTED) throw PackageRejectedException(compatibility.explanation)
        if (plan.packages.isEmpty() || plan.packages.first().path != "" || plan.packages.first().name != plan.name) {
            throw PackageRejectedException("The install plan does not start with the package itself.")
        }
        if (plan.packages.size > MAX_PACKAGES) throw PackageRejectedException("The package would install more than $MAX_PACKAGES packages.")
        val seen = HashSet<String>()
        for (entry in plan.packages) {
            validatePlanEntry(entry)
            if (!seen.add(entry.path)) throw PackageRejectedException("The install plan lists ${entry.path.ifEmpty { plan.name }} twice.")
        }

        Files.createDirectories(workDir)
        val root = workDir.resolve("staging-${System.currentTimeMillis()}-${counter.incrementAndGet()}")
        val packageDir = root.resolve(plan.name)
        val downloads = root.resolve(".downloads")
        Files.createDirectories(downloads)
        val done = AtomicInteger(0)
        val files = AtomicInteger(0)
        val bytes = java.util.concurrent.atomic.AtomicLong(0)
        var matchesPublished: Boolean? = null
        try {
            // The package itself first, so its folder exists before its dependencies are placed inside it.
            val first = plan.packages.first()
            val rootResult = if (rootArchive != null) {
                val info = inspectArchive(rootArchive)
                if (info.name != plan.name || info.version != plan.version) {
                    throw PackageRejectedException("The file is ${info.name} ${info.version}, not ${plan.name} ${plan.version}.")
                }
                matchesPublished = first.integrity != null && first.integrity == info.sha512
                Files.newInputStream(rootArchive).use { TarGz.extract(it, packageDir) }
            } else {
                fetchAndExtract(first, packageDir, downloads)
            }
            files.addAndGet(rootResult.files)
            bytes.addAndGet(rootResult.bytes)
            progress(done.incrementAndGet(), plan.packages.size)

            val limit = Semaphore(PARALLEL_DOWNLOADS)
            coroutineScope {
                plan.packages.drop(1).map { entry ->
                    async {
                        limit.withPermit {
                            val result = fetchAndExtract(entry, packageDir.resolve(entry.path), downloads)
                            files.addAndGet(result.files)
                            if (bytes.addAndGet(result.bytes) > MAX_INSTALL_BYTES) {
                                throw PackageRejectedException("The package unpacks to more than ${MAX_INSTALL_BYTES / (1024 * 1024)} MB.")
                            }
                            progress(done.incrementAndGet(), plan.packages.size)
                        }
                    }
                }.awaitAll()
            }
            deleteRecursively(downloads)
            verifyUnpacked(plan, packageDir)
            StagedPackage(plan.name, plan.version, root, packageDir, plan.packages.size, files.get(), bytes.get(), pinned, matchesPublished)
        } catch (failure: Throwable) {
            deleteRecursively(root)
            throw failure
        }
    }

    /** Moves a staged package into the plugin folder, replacing an installed version of the same package. */
    fun activate(staged: StagedPackage) {
        requireKind(staged.name)
        val target = pluginsDir.resolve(staged.name)
        Files.createDirectories(target.parent)
        Files.createDirectories(workDir)
        val previous = workDir.resolve("replaced-${System.currentTimeMillis()}-${counter.incrementAndGet()}")
        val hadPrevious = Files.exists(target)
        if (hadPrevious) move(target, previous)
        try {
            move(staged.packageDir, target)
        } catch (failure: Exception) {
            if (hadPrevious) runCatching { move(previous, target) }
            throw IOException("The package could not be put in place: ${failure.message}", failure)
        }
        deleteRecursively(previous)
        deleteRecursively(staged.root)
    }

    fun discard(staged: StagedPackage) {
        deleteRecursively(staged.root)
    }

    /** Deletes an installed extension package. Returns false when it was not installed. */
    fun remove(name: String): Boolean {
        requireKind(name)
        val target = pluginsDir.resolve(name).normalize()
        if (!target.startsWith(pluginsDir.normalize()) || !Files.isDirectory(target)) return false
        // The same safety check aki's uninstall makes: the folder must really be that package.
        if (readManifest(target)?.string("name") != name) return false
        deleteRecursively(target)
        val scope = target.parent
        if (scope != pluginsDir && Files.isDirectory(scope) && Files.newDirectoryStream(scope).use { !it.iterator().hasNext() }) {
            Files.deleteIfExists(scope)
        }
        return true
    }

    /** Removes staging folders left behind by an install that was interrupted (for example by quitting). */
    fun cleanUp() {
        if (!Files.isDirectory(workDir)) return
        Files.newDirectoryStream(workDir).use { entries ->
            entries.filter { it.fileName.toString().startsWith("staging-") || it.fileName.toString().startsWith("replaced-") }
                .forEach(::deleteRecursively)
        }
    }

    private fun validatePlanEntry(entry: PlannedPackage) {
        if (!entry.tarball.startsWith("$registry/")) {
            throw PackageRejectedException("${entry.name} would be downloaded from somewhere other than the package registry (${entry.tarball.take(80)}).")
        }
        if (entry.integrity?.startsWith("sha512-") != true) {
            throw PackageRejectedException("${entry.name} ${entry.version} has no SHA-512 checksum in the registry, so it cannot be verified.")
        }
        if (entry.path.isNotEmpty()) {
            val segments = entry.path.split('/')
            val valid = segments.none { it.isEmpty() || it == "." || it == ".." } &&
                !entry.path.startsWith("/") && !entry.path.contains('\\') && !entry.path.contains('\u0000') &&
                entry.path.endsWith("node_modules/${entry.name}")
            if (!valid) throw PackageRejectedException("The install plan contains an unsafe path for ${entry.name}.")
        }
        if (!Regex("^(@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*$", RegexOption.IGNORE_CASE).matches(entry.name)) {
            throw PackageRejectedException("\"${entry.name.take(60)}\" is not a valid package name.")
        }
    }

    private fun fetchAndExtract(entry: PlannedPackage, target: Path, downloads: Path): TarGz.Result {
        val archive = downloads.resolve("${counter.incrementAndGet()}.tgz")
        val digest = MessageDigest.getInstance("SHA-512")
        try {
            MangaHttp.download(entry.tarball, archive, maxBytes = MAX_ARCHIVE_BYTES) { buffer, read -> digest.update(buffer, 0, read) }
            val actual = "sha512-" + Base64.getEncoder().encodeToString(digest.digest())
            if (actual != entry.integrity) {
                throw PackageRejectedException("${entry.name} ${entry.version} does not match its checksum; the download was discarded.")
            }
            return Files.newInputStream(archive).use { TarGz.extract(it, target) }
        } finally {
            runCatching { Files.deleteIfExists(archive) }
        }
    }

    private fun verifyUnpacked(plan: InstallPlan, packageDir: Path) {
        val manifest = readManifest(packageDir) ?: throw PackageRejectedException("The package has no readable package.json.")
        if (manifest.string("name") != plan.name) throw PackageRejectedException("The archive contains ${manifest.string("name")}, not ${plan.name}.")
        if (manifest.string("version") != plan.version) throw PackageRejectedException("The archive contains version ${manifest.string("version")}, not ${plan.version}.")
        val main = manifest.string("main") ?: "index.js"
        val mainFile = packageDir.resolve(main).normalize()
        val exists = mainFile.startsWith(packageDir) &&
            (Files.isRegularFile(mainFile) || Files.isRegularFile(packageDir.resolve("$main.js")) || Files.isRegularFile(mainFile.resolve("index.js")))
        if (!exists) throw PackageRejectedException("The package's entry file ($main) is missing.")
        for (entry in plan.packages.drop(1)) {
            val dependency = readManifest(packageDir.resolve(entry.path))
            if (dependency?.string("name") != entry.name) {
                throw PackageRejectedException("${entry.path} does not contain ${entry.name}.")
            }
        }
    }

    private fun requireKind(name: String): PackageKind = PluginRules.kindOf(name)
        ?: throw PackageRejectedException(PluginRules.compatibility(name, "0.0.0").explanation)

    private fun readManifest(dir: Path): JsonObject? {
        val file = dir.resolve("package.json")
        if (!Files.isRegularFile(file) || Files.size(file) > 2 * 1024 * 1024) return null
        return runCatching { json.parseToJsonElement(Files.readString(file)).jsonObject }.getOrNull()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

    private fun move(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to)
        }
    }

    companion object {
        const val DEFAULT_REGISTRY = "https://registry.npmjs.org"
        private const val PARALLEL_DOWNLOADS = 6
        private const val MAX_PACKAGES = 400
        private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
        private const val MAX_INSTALL_BYTES = 400L * 1024 * 1024

        fun deleteRecursively(path: Path) {
            if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }
}
