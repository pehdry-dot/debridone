package com.nuvio.app.core.storage

import com.debridone.platform.AppPaths
import com.debridone.platform.io.AtomicFiles
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.Locale
import java.util.Properties
import kotlin.io.path.exists

internal object DesktopStorage {
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = mutableMapOf<String, Store>()

    val rootDir: Path by lazy {
        resolveAppDataDir().also { Files.createDirectories(it) }
    }

    val cacheDir: Path by lazy {
        resolveCacheDir().also { Files.createDirectories(it) }
    }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties")) }
    }

    fun wipe() {
        synchronized(stores) {
            stores.values.forEach(Store::clearInMemory)
            stores.clear()
        }
        if (!rootDir.exists()) return
        Files.walk(rootDir).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .filter { it != rootDir }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    // DebridOne: Nuvio is the Streaming module, so its files live in the Streaming module's own
    // directories (macOS: ~/Library/Application Support/DebridOne/modules/streaming and
    // ~/Library/Caches/DebridOne/streaming). Accounts and the other modules are outside this
    // tree, which also means [wipe] can only ever remove Streaming data.
    private fun resolveAppDataDir(): Path = AppPaths.system().moduleDataDir(STREAMING_MODULE_KEY)

    private fun resolveCacheDir(): Path = AppPaths.system().moduleCacheDir(STREAMING_MODULE_KEY)

    private const val STREAMING_MODULE_KEY = "streaming"

    /**
     * DebridOne: removes every key that belongs to [profileId] from all stores. Nuvio scopes
     * per-profile data by appending `_<profileId>` to the key (see ProfileScopedKey); profile ids
     * are unique and never reused, so this cannot touch another profile's data.
     */
    fun removeProfileScopedKeys(profileId: Int) {
        val suffix = "_$profileId"
        val names = runCatching {
            Files.list(rootDir).use { stream ->
                stream.map { it.fileName.toString() }
                    .filter { it.endsWith(".properties") }
                    .map { it.removeSuffix(".properties") }
                    .toList()
            }
        }.getOrDefault(emptyList())
        names.forEach { name -> store(name).removeKeysEndingWith(suffix) }
    }

    internal class Store(
        private val file: Path,
    ) {
        private val lock = Any()
        private val properties = Properties()
        private var loaded = false

        fun contains(key: String): Boolean = synchronized(lock) {
            ensureLoaded()
            properties.containsKey(key)
        }

        fun getString(key: String): String? = synchronized(lock) {
            ensureLoaded()
            properties.getProperty(key)
        }

        fun putString(key: String, value: String?) = synchronized(lock) {
            ensureLoaded()
            val changed = if (value == null) {
                properties.remove(key) != null
            } else {
                properties.setProperty(key, value) != value
            }
            if (changed) persist()
        }

        fun getBoolean(key: String): Boolean? =
            getString(key)?.toBooleanStrictOrNull()

        fun putBoolean(key: String, value: Boolean) {
            putString(key, value.toString())
        }

        fun getInt(key: String): Int? =
            getString(key)?.toIntOrNull()

        fun putInt(key: String, value: Int) {
            putString(key, value.toString())
        }

        fun getFloat(key: String): Float? =
            getString(key)?.toFloatOrNull()

        fun putFloat(key: String, value: Float) {
            putString(key, value.toString())
        }

        fun getStringSet(key: String): Set<String>? =
            getString(key)?.let { payload ->
                runCatching { json.decodeFromString<List<String>>(payload).toSet() }.getOrNull()
            }

        fun putStringSet(key: String, values: Set<String>) {
            putString(key, json.encodeToString(values.toList()))
        }

        fun remove(key: String) = synchronized(lock) {
            ensureLoaded()
            if (properties.remove(key) != null) persist()
        }

        fun removeAll(keys: Iterable<String>) = synchronized(lock) {
            ensureLoaded()
            var changed = false
            keys.forEach { key ->
                if (properties.remove(key) != null) changed = true
            }
            if (changed) persist()
        }

        fun clearInMemory() = synchronized(lock) {
            properties.clear()
            loaded = false
        }

        private fun ensureLoaded() {
            if (loaded) return
            loaded = true
            properties.clear()
            // DebridOne: fall back to the previous good copy if the file is missing or unreadable.
            AtomicFiles.readWithBackup(file) { bytes ->
                Properties().apply { load(bytes.inputStream()) }
            }?.let(properties::putAll)
        }

        fun removeKeysEndingWith(suffix: String) = synchronized(lock) {
            ensureLoaded()
            val doomed = properties.stringPropertyNames().filter { it.endsWith(suffix) }
            if (doomed.isEmpty()) return@synchronized
            doomed.forEach(properties::remove)
            persist()
        }

        private fun persist() {
            // DebridOne: write to a temp file, fsync, keep a .bak, then rename into place, so a
            // crash or power loss mid-write can no longer truncate the store.
            AtomicFiles.write(file) { output ->
                properties.store(output, "DebridOne streaming preferences")
            }
        }
    }
}
