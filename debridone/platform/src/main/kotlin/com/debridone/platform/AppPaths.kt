package com.debridone.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/**
 * Where DebridOne keeps its files.
 *
 * macOS (the primary target) uses the standard per-user locations:
 * `~/Library/Application Support/DebridOne` for durable data and `~/Library/Caches/DebridOne`
 * for anything that can be rebuilt.
 *
 * Layout under [dataRoot]:
 * ```
 * accounts/accounts.db            accounts, profiles, device session, shared settings
 * modules/streaming/              Nuvio's own stores (owned by the Streaming module)
 * modules/music/music.db          owned by the Music module
 * modules/gaming/gaming.db        owned by the Gaming module
 * modules/manga/manga.db          owned by the Manga module
 * backups/<database>/             rolling database snapshots
 * secrets/                        non-macOS fallback secret store (macOS uses the Keychain)
 * ```
 * Each module only ever receives its own directory, so one module cannot address another's files
 * by construction.
 */
class AppPaths(
    val dataRoot: Path,
    val cacheRoot: Path,
) {
    val accountsDir: Path get() = dataRoot.resolve("accounts")
    val backupsDir: Path get() = dataRoot.resolve("backups")
    val secretsDir: Path get() = dataRoot.resolve("secrets")
    val logsDir: Path get() = dataRoot.resolve("logs")

    /** Connected cloud accounts, their cached file listings and the download queue (application level). */
    val cloudDir: Path get() = dataRoot.resolve("cloud")

    /** Durable storage owned by exactly one module. [moduleKey] is the module's stable key. */
    fun moduleDataDir(moduleKey: String): Path = dataRoot.resolve("modules").resolve(requireSafeSegment(moduleKey))

    /** Rebuildable cache owned by exactly one module. */
    fun moduleCacheDir(moduleKey: String): Path = cacheRoot.resolve(requireSafeSegment(moduleKey))

    fun backupDirFor(databaseName: String): Path = backupsDir.resolve(requireSafeSegment(databaseName))

    fun ensureCreated(vararg dirs: Path) {
        dirs.forEach { Files.createDirectories(it) }
    }

    companion object {
        const val APP_DIR_NAME = "DebridOne"

        /** Overrides the data root (tests, portable installs). */
        const val DATA_DIR_PROPERTY = "debridone.data.dir"
        const val DATA_DIR_ENV = "DEBRIDONE_DATA_DIR"

        /** Overrides the cache root. */
        const val CACHE_DIR_PROPERTY = "debridone.cache.dir"
        const val CACHE_DIR_ENV = "DEBRIDONE_CACHE_DIR"

        @Volatile
        private var systemPaths: AppPaths? = null

        /** Process-wide paths, resolved once. */
        fun system(): AppPaths =
            systemPaths ?: synchronized(this) {
                systemPaths ?: resolveSystem().also { systemPaths = it }
            }

        /** Test hook: forget the cached [system] paths so overrides are re-read. */
        fun resetSystemForTests() {
            synchronized(this) { systemPaths = null }
        }

        internal fun resolveSystem(
            osName: String = System.getProperty("os.name").orEmpty(),
            userHome: String = System.getProperty("user.home").orEmpty(),
            property: (String) -> String? = System::getProperty,
            env: (String) -> String? = System::getenv,
        ): AppPaths {
            val os = osName.lowercase(Locale.ROOT)
            val home = Paths.get(userHome)
            fun override(prop: String, envName: String): Path? =
                (property(prop) ?: env(envName))?.trim()?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }

            val data = override(DATA_DIR_PROPERTY, DATA_DIR_ENV) ?: when {
                os.contains("mac") -> home.resolve("Library/Application Support").resolve(APP_DIR_NAME)
                os.contains("win") -> {
                    val appData = env("APPDATA")?.takeIf { it.isNotBlank() }
                    (appData?.let { Paths.get(it) } ?: home.resolve("AppData/Roaming")).resolve(APP_DIR_NAME)
                }
                else -> {
                    val xdg = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                    (xdg?.let { Paths.get(it) } ?: home.resolve(".config")).resolve("debridone")
                }
            }
            val cache = override(CACHE_DIR_PROPERTY, CACHE_DIR_ENV) ?: when {
                os.contains("mac") -> home.resolve("Library/Caches").resolve(APP_DIR_NAME)
                os.contains("win") -> {
                    val local = env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                    (local?.let { Paths.get(it) } ?: home.resolve("AppData/Local")).resolve(APP_DIR_NAME).resolve("Cache")
                }
                else -> {
                    val xdg = env("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
                    (xdg?.let { Paths.get(it) } ?: home.resolve(".cache")).resolve("debridone")
                }
            }
            return AppPaths(dataRoot = data.toAbsolutePath().normalize(), cacheRoot = cache.toAbsolutePath().normalize())
        }

        private val SAFE_SEGMENT = Regex("[a-z0-9][a-z0-9_-]{0,63}")

        internal fun requireSafeSegment(value: String): String {
            require(SAFE_SEGMENT.matches(value)) { "Unsafe storage segment: '$value'" }
            return value
        }
    }
}
