package com.debridone.platform.db

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** One forward-only schema step. [version] values must be contiguous starting at 1. */
class Migration(
    val version: Int,
    val description: String,
    val apply: SqliteDatabase.() -> Unit,
) {
    constructor(version: Int, description: String, vararg statements: String) : this(
        version,
        description,
        { statements.forEach { executeRaw(it) } },
    )
}

/** Everything needed to open one database file. Each owner (accounts, or one module) has its own. */
class DatabaseSpec(
    /** Stable name, used for the backup directory and diagnostics. */
    val name: String,
    val file: Path,
    val migrations: List<Migration>,
    val backupDir: Path,
    val backupsToKeep: Int = 5,
    /** A routine snapshot is taken on open when the newest one is older than this. */
    val backupIntervalMillis: Long = 24L * 60 * 60 * 1000,
    /** `FULL` survives power loss at the cost of an fsync per commit; right for small, precious data. */
    val synchronous: String = "FULL",
) {
    init {
        require(migrations.isNotEmpty()) { "Database '$name' needs at least one migration" }
        migrations.forEachIndexed { index, migration ->
            require(migration.version == index + 1) {
                "Database '$name': migration versions must be contiguous from 1 (found ${migration.version} at position ${index + 1})"
            }
        }
        require(backupsToKeep >= 1)
    }

    val targetVersion: Int get() = migrations.last().version
}

enum class OpenOutcome {
    /** No file existed; a new database was created. */
    CREATED,

    /** Opened as-is. */
    OPENED,

    /** Opened and upgraded to a newer schema. */
    MIGRATED,

    /** The file was damaged; the newest healthy backup was restored. */
    RESTORED_FROM_BACKUP,

    /** The file was damaged and no usable backup existed; a new database was created. */
    RECREATED_AFTER_CORRUPTION,
}

class OpenResult(
    val database: SqliteDatabase,
    val outcome: OpenOutcome,
    val fromVersion: Int,
    val toVersion: Int,
    /** Where the damaged file was moved, when [outcome] reports a recovery. */
    val quarantinedFile: Path? = null,
    /** The backup that was restored, when [outcome] is [OpenOutcome.RESTORED_FROM_BACKUP]. */
    val restoredFrom: Path? = null,
)

/** The file was written by a newer DebridOne. It is left untouched rather than risk damaging it. */
class DatabaseNewerThanAppException(name: String, found: Int, supported: Int) : IllegalStateException(
    "Database '$name' has schema version $found but this build supports up to $supported. " +
        "Update DebridOne; the file was not modified.",
)

class MigrationFailedException(name: String, version: Int, cause: Throwable) : IllegalStateException(
    "Database '$name': migration to version $version failed and was rolled back. " +
        "The database is unchanged at the previous version.",
    cause,
)

/**
 * Opens DebridOne databases safely:
 *
 * 1. If the file is damaged it is moved aside (never deleted) and the newest healthy backup is
 *    restored, or a new database is created when there is none.
 * 2. A snapshot is taken before any schema upgrade, so a bad migration can be undone by hand.
 * 3. Every migration runs in its own transaction together with its version bump: it either fully
 *    applies or leaves the database exactly as it was.
 * 4. A routine snapshot is taken at most once per [DatabaseSpec.backupIntervalMillis], keeping the
 *    newest [DatabaseSpec.backupsToKeep].
 */
object SqliteDatabases {
    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

    fun open(spec: DatabaseSpec, now: () -> Instant = Instant::now): OpenResult {
        Files.createDirectories(spec.file.toAbsolutePath().parent)
        Files.createDirectories(spec.backupDir)

        val existed = spec.file.isRegularFile() && spec.file.fileSize() > 0
        var outcome = if (existed) OpenOutcome.OPENED else OpenOutcome.CREATED
        var quarantined: Path? = null
        var restoredFrom: Path? = null

        var database = if (existed) openHealthyOrNull(spec.file, spec.synchronous) else null
        if (existed && database == null) {
            quarantined = quarantine(spec, now())
            restoredFrom = restoreNewestHealthyBackup(spec)
            outcome = if (restoredFrom != null) OpenOutcome.RESTORED_FROM_BACKUP else OpenOutcome.RECREATED_AFTER_CORRUPTION
        }
        if (database == null) database = connect(spec.file, spec.synchronous)

        val fromVersion = database.userVersion
        try {
            if (fromVersion > spec.targetVersion) {
                throw DatabaseNewerThanAppException(spec.name, fromVersion, spec.targetVersion)
            }
            if (fromVersion < spec.targetVersion) {
                if (fromVersion > 0) {
                    snapshot(spec, database, now(), label = "pre-v${spec.targetVersion}")
                    if (outcome == OpenOutcome.OPENED) outcome = OpenOutcome.MIGRATED
                }
                migrate(spec, database, fromVersion)
            }
            if (outcome == OpenOutcome.OPENED || outcome == OpenOutcome.MIGRATED) {
                snapshotIfDue(spec, database, now())
            }
        } catch (t: Throwable) {
            runCatching { database.close() }
            throw t
        }
        return OpenResult(
            database = database,
            outcome = outcome,
            fromVersion = fromVersion,
            toVersion = spec.targetVersion,
            quarantinedFile = quarantined,
            restoredFrom = restoredFrom,
        )
    }

    /** Takes a snapshot now, regardless of the interval. Returns the snapshot file. */
    fun backupNow(spec: DatabaseSpec, database: SqliteDatabase, now: Instant = Instant.now()): Path =
        snapshot(spec, database, now, label = "manual")

    fun listBackups(spec: DatabaseSpec): List<Path> = backupFiles(spec)

    private fun migrate(spec: DatabaseSpec, database: SqliteDatabase, fromVersion: Int) {
        for (migration in spec.migrations) {
            if (migration.version <= fromVersion) continue
            try {
                database.transaction {
                    migration.apply(this)
                    userVersion = migration.version
                }
            } catch (t: Throwable) {
                throw MigrationFailedException(spec.name, migration.version, t)
            }
        }
    }

    private fun connect(file: Path, synchronous: String): SqliteDatabase {
        val properties = Properties().apply {
            // Applied by the driver on every connection, before any statement runs.
            setProperty("foreign_keys", "true")
            setProperty("busy_timeout", "5000")
        }
        val connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}", properties)
        try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA synchronous = ${sanitizeSynchronous(synchronous)}")
                statement.execute("PRAGMA foreign_keys = ON")
            }
        } catch (e: SQLException) {
            runCatching { connection.close() }
            throw e
        }
        return SqliteDatabase(file, connection)
    }

    private fun sanitizeSynchronous(value: String): String = when (value.uppercase()) {
        "OFF", "NORMAL", "FULL", "EXTRA" -> value.uppercase()
        else -> "FULL"
    }

    /** Opens [file] and returns it only if SQLite accepts it as a healthy database. */
    private fun openHealthyOrNull(file: Path, synchronous: String): SqliteDatabase? {
        val database = try {
            connect(file, synchronous)
        } catch (_: SQLException) {
            return null
        }
        if (database.passesIntegrityCheck()) return database
        runCatching { database.close() }
        return null
    }

    private fun quarantine(spec: DatabaseSpec, now: Instant): Path {
        val dir = spec.backupDir.resolve("corrupt")
        Files.createDirectories(dir)
        val stamp = STAMP.format(now)
        val target = dir.resolve("${spec.file.name}.$stamp.corrupt")
        Files.move(spec.file, target, StandardCopyOption.REPLACE_EXISTING)
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val sidecar = spec.file.resolveSibling(spec.file.name + suffix)
            if (sidecar.exists()) {
                runCatching {
                    Files.move(sidecar, dir.resolve("${spec.file.name}.$stamp.corrupt$suffix"), StandardCopyOption.REPLACE_EXISTING)
                }.onFailure { runCatching { Files.deleteIfExists(sidecar) } }
            }
        }
        return target
    }

    private fun restoreNewestHealthyBackup(spec: DatabaseSpec): Path? {
        for (candidate in backupFiles(spec).asReversed()) {
            val healthy = openHealthyOrNull(candidate, spec.synchronous)?.also { it.close() } != null
            // Opening in WAL mode can leave sidecar files next to the backup; they are not needed.
            cleanSidecars(candidate)
            if (!healthy) continue
            Files.copy(candidate, spec.file, StandardCopyOption.REPLACE_EXISTING)
            return candidate
        }
        return null
    }

    private fun cleanSidecars(file: Path) {
        for (suffix in listOf("-wal", "-shm")) {
            runCatching { Files.deleteIfExists(file.resolveSibling(file.name + suffix)) }
        }
    }

    private fun snapshotIfDue(spec: DatabaseSpec, database: SqliteDatabase, now: Instant) {
        val newest = backupFiles(spec).lastOrNull()
        val due = newest == null ||
            now.toEpochMilli() - Files.getLastModifiedTime(newest).toMillis() >= spec.backupIntervalMillis
        if (due) {
            // A failed routine backup must never stop the app from opening its data.
            runCatching { snapshot(spec, database, now, label = "auto") }
        }
    }

    private fun snapshot(spec: DatabaseSpec, database: SqliteDatabase, now: Instant, label: String): Path {
        Files.createDirectories(spec.backupDir)
        val target = spec.backupDir.resolve("${spec.name}-${STAMP.format(now)}-$label.db")
        val temp = spec.backupDir.resolve("${target.name}.partial")
        Files.deleteIfExists(temp)
        try {
            database.snapshotTo(temp)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
        prune(spec)
        return target
    }

    private fun prune(spec: DatabaseSpec) {
        val backups = backupFiles(spec)
        backups.dropLast(spec.backupsToKeep).forEach { runCatching { Files.deleteIfExists(it) } }
    }

    /** Oldest first. File names embed a UTC timestamp, so name order is chronological order. */
    private fun backupFiles(spec: DatabaseSpec): List<Path> {
        if (!spec.backupDir.exists()) return emptyList()
        return spec.backupDir.listDirectoryEntries("${spec.name}-*.db")
            .filter { it.isRegularFile() }
            .sortedBy { it.name }
    }
}
