package com.debridone.module.storage

import com.debridone.module.AppearanceOption
import com.debridone.module.InstalledPlugin
import com.debridone.module.MetadataProvider
import com.debridone.module.ModuleAppearance
import com.debridone.module.ModuleId
import com.debridone.module.PluginKind
import com.debridone.platform.AppPaths
import com.debridone.platform.db.DatabaseSpec
import com.debridone.platform.db.Migration
import com.debridone.platform.db.OpenResult
import com.debridone.platform.db.SqliteDatabase
import com.debridone.platform.db.SqliteDatabases
import com.debridone.platform.db.getBooleanInt
import com.debridone.platform.db.getNullableLong
import com.debridone.platform.secrets.ScopedSecretStore
import com.debridone.platform.secrets.SecretStore
import java.nio.file.Path

/**
 * Storage plumbing handed to exactly one module: its own directory, its own database file, its own
 * cache directory and its own secret namespace. There is no API here to reach another module.
 */
class ModuleStorage(
    val moduleId: ModuleId,
    private val paths: AppPaths,
    secretStore: SecretStore,
) {
    val dataDir: Path = paths.moduleDataDir(moduleId.key)
    val cacheDir: Path = paths.moduleCacheDir(moduleId.key)
    val databaseFile: Path = dataDir.resolve("${moduleId.key}.db")

    /** Secrets visible to this module only (`module.<key>`). */
    val secrets: ScopedSecretStore = ScopedSecretStore(secretStore, "module.${moduleId.key}")

    fun databaseSpec(migrations: List<Migration>): DatabaseSpec = DatabaseSpec(
        name = moduleId.key,
        file = databaseFile,
        migrations = migrations,
        backupDir = paths.backupDirFor(moduleId.key),
    )

    fun openDatabase(migrations: List<Migration>): OpenResult {
        paths.ensureCreated(dataDir, cacheDir)
        return SqliteDatabases.open(databaseSpec(migrations))
    }
}

/**
 * CRUD for the columns the Plugins tab needs, over a table that belongs to one module.
 *
 * Each module creates the table in its own migrations (see [createTableSql]) under its own name
 * and is free to add module-specific columns and companion tables. Instances are never shared.
 */
class PluginTableStore(
    private val database: SqliteDatabase,
    private val table: String,
) {
    init {
        require(IDENTIFIER.matches(table)) { "Invalid table name: $table" }
    }

    fun list(): List<InstalledPlugin> = database.query(
        "SELECT * FROM $table ORDER BY built_in DESC, name COLLATE NOCASE ASC, id ASC",
    ) { rs ->
        InstalledPlugin(
            id = rs.getString("id"),
            name = rs.getString("name"),
            kind = runCatching { PluginKind.valueOf(rs.getString("kind")) }.getOrDefault(PluginKind.UTILITY),
            version = rs.getString("version"),
            description = rs.getString("description"),
            origin = rs.getString("origin"),
            enabled = rs.getBooleanInt("enabled"),
            builtIn = rs.getBooleanInt("built_in"),
            installedAtEpochMillis = rs.getLong("installed_at"),
            updatedAtEpochMillis = rs.getLong("updated_at"),
            lastError = rs.getString("last_error"),
            lastErrorAtEpochMillis = rs.getNullableLong("last_error_at"),
        )
    }

    fun find(id: String): InstalledPlugin? = list().firstOrNull { it.id == id }

    /** Inserts or updates the descriptive fields. The user's enabled/disabled choice is preserved on update. */
    fun upsert(
        id: String,
        name: String,
        kind: PluginKind,
        version: String?,
        description: String?,
        origin: String?,
        builtIn: Boolean,
        enabledByDefault: Boolean,
        now: Long,
    ) {
        require(id.isNotBlank() && id.length <= 256) { "Invalid plugin id" }
        require(name.isNotBlank()) { "Plugin name is required" }
        database.transaction {
            val updated = execute(
                "UPDATE $table SET name = ?, kind = ?, version = ?, description = ?, origin = ?, built_in = ?, updated_at = ? WHERE id = ?",
                name, kind.name, version, description, origin, builtIn, now, id,
            )
            if (updated == 0) {
                execute(
                    "INSERT INTO $table (id, name, kind, version, description, origin, enabled, built_in, installed_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, name, kind.name, version, description, origin, enabledByDefault, builtIn, now, now,
                )
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean, now: Long): Boolean =
        database.execute("UPDATE $table SET enabled = ?, updated_at = ? WHERE id = ?", enabled, now, id) > 0

    fun remove(id: String): Boolean = database.execute("DELETE FROM $table WHERE id = ?", id) > 0

    fun recordError(id: String, message: String?, now: Long): Boolean = database.execute(
        "UPDATE $table SET last_error = ?, last_error_at = ? WHERE id = ?",
        message, if (message == null) null else now, id,
    ) > 0

    companion object {
        private val IDENTIFIER = Regex("[a-z][a-z0-9_]{0,62}")

        fun createTableSql(table: String): String {
            require(IDENTIFIER.matches(table)) { "Invalid table name: $table" }
            return """
                CREATE TABLE $table (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    version TEXT,
                    description TEXT,
                    origin TEXT,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    built_in INTEGER NOT NULL DEFAULT 0,
                    installed_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    last_error TEXT,
                    last_error_at INTEGER
                )
            """.trimIndent()
        }
    }
}

/** Metadata provider preferences (enabled, priority) of one module, in that module's database. */
class MetadataProviderTableStore(
    private val database: SqliteDatabase,
    private val table: String,
) {
    init {
        require(IDENTIFIER.matches(table)) { "Invalid table name: $table" }
    }

    /** Merges the module's built-in provider list with what the user changed. */
    fun resolve(defaults: List<MetadataProvider>): List<MetadataProvider> {
        val stored = database.query("SELECT id, enabled, priority FROM $table") { rs ->
            rs.getString("id") to (rs.getBooleanInt("enabled") to rs.getInt("priority"))
        }.toMap()
        return defaults.map { provider ->
            val override = stored[provider.id]
            if (override == null) provider else provider.copy(enabled = override.first, priority = override.second)
        }.sortedWith(compareBy({ it.priority }, { it.name }))
    }

    fun save(id: String, enabled: Boolean, priority: Int) {
        database.execute(
            "INSERT INTO $table (id, enabled, priority) VALUES (?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET enabled = excluded.enabled, priority = excluded.priority",
            id, enabled, priority,
        )
    }

    companion object {
        private val IDENTIFIER = Regex("[a-z][a-z0-9_]{0,62}")

        fun createTableSql(table: String): String {
            require(IDENTIFIER.matches(table)) { "Invalid table name: $table" }
            return "CREATE TABLE $table (id TEXT PRIMARY KEY NOT NULL, enabled INTEGER NOT NULL, priority INTEGER NOT NULL)"
        }
    }
}

/**
 * Key/value settings scoped by profile, in one module's database. `profileId = 0` holds
 * module-wide values that are not tied to a profile.
 */
class ProfileSettingsTableStore(
    private val database: SqliteDatabase,
    private val table: String,
) {
    init {
        require(IDENTIFIER.matches(table)) { "Invalid table name: $table" }
    }

    fun all(profileId: Long, prefix: String = ""): Map<String, String> = database.query(
        "SELECT key, value FROM $table WHERE profile_id = ? AND key LIKE ? ESCAPE '\\'",
        profileId, escapeLike(prefix) + "%",
    ) { rs -> rs.getString("key") to rs.getString("value") }.toMap()

    fun get(profileId: Long, key: String): String? = database.queryOne(
        "SELECT value FROM $table WHERE profile_id = ? AND key = ?", profileId, key,
    ) { it.getString(1) }

    fun set(profileId: Long, key: String, value: String?) {
        if (value == null) {
            database.execute("DELETE FROM $table WHERE profile_id = ? AND key = ?", profileId, key)
        } else {
            database.execute(
                "INSERT INTO $table (profile_id, key, value) VALUES (?, ?, ?) " +
                    "ON CONFLICT(profile_id, key) DO UPDATE SET value = excluded.value",
                profileId, key, value,
            )
        }
    }

    fun replaceAll(profileId: Long, prefix: String, values: Map<String, String>) {
        database.transaction {
            execute("DELETE FROM $table WHERE profile_id = ? AND key LIKE ? ESCAPE '\\'", profileId, escapeLike(prefix) + "%")
            values.forEach { (key, value) ->
                execute("INSERT INTO $table (profile_id, key, value) VALUES (?, ?, ?)", profileId, prefix + key, value)
            }
        }
    }

    /** Removes everything a deleted profile owned in this module. */
    fun deleteProfile(profileId: Long): Int = database.execute("DELETE FROM $table WHERE profile_id = ?", profileId)

    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    companion object {
        private val IDENTIFIER = Regex("[a-z][a-z0-9_]{0,62}")
        const val MODULE_WIDE: Long = 0

        fun createTableSql(table: String): String {
            require(IDENTIFIER.matches(table)) { "Invalid table name: $table" }
            return "CREATE TABLE $table (profile_id INTEGER NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (profile_id, key))"
        }
    }
}

/** Drops values that do not match the module's declared options. */
fun sanitizeAppearance(options: List<AppearanceOption>, values: Map<String, String>): Map<String, String> {
    val byKey = options.associateBy { it.key }
    return values.filter { (key, value) ->
        when (val option = byKey[key]) {
            null -> false
            is AppearanceOption.Choice -> option.choices.any { it.value == value }
            is AppearanceOption.Toggle -> value.toBooleanStrictOrNull() != null
            is AppearanceOption.Colour -> ModuleAppearance.isHexColour(value)
        }
    }
}
