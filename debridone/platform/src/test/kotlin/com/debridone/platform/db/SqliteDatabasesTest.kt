package com.debridone.platform.db

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteDatabasesTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("debridone-db-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private val v1 = Migration(1, "notes", "CREATE TABLE notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
    private val v2 = Migration(2, "note colour", "ALTER TABLE notes ADD COLUMN colour TEXT NOT NULL DEFAULT 'none'")

    private fun spec(vararg migrations: Migration, keep: Int = 5, interval: Long = 24L * 60 * 60 * 1000) = DatabaseSpec(
        name = "sample",
        file = dir.resolve("data/sample.db"),
        migrations = migrations.toList(),
        backupDir = dir.resolve("backups/sample"),
        backupsToKeep = keep,
        backupIntervalMillis = interval,
    )

    @Test
    fun createsDatabaseAndAppliesMigrations() {
        val result = SqliteDatabases.open(spec(v1, v2))
        result.database.use { db ->
            assertEquals(OpenOutcome.CREATED, result.outcome)
            assertEquals(0, result.fromVersion)
            assertEquals(2, db.userVersion)
            db.execute("INSERT INTO notes (body, colour) VALUES (?, ?)", "hello", "red")
            assertEquals(listOf("hello|red"), db.query("SELECT body, colour FROM notes") { "${it.getString(1)}|${it.getString(2)}" })
        }
    }

    @Test
    fun dataSurvivesCloseAndReopen() {
        SqliteDatabases.open(spec(v1)).database.use { it.execute("INSERT INTO notes (body) VALUES (?)", "persist me") }
        val reopened = SqliteDatabases.open(spec(v1))
        reopened.database.use { db ->
            assertEquals(OpenOutcome.OPENED, reopened.outcome)
            assertEquals("persist me", db.queryOne("SELECT body FROM notes") { it.getString(1) })
        }
    }

    @Test
    fun upgradeKeepsDataAndTakesPreMigrationSnapshot() {
        SqliteDatabases.open(spec(v1)).database.use { it.execute("INSERT INTO notes (body) VALUES (?)", "before upgrade") }

        val upgraded = SqliteDatabases.open(spec(v1, v2))
        upgraded.database.use { db ->
            assertEquals(OpenOutcome.MIGRATED, upgraded.outcome)
            assertEquals(1, upgraded.fromVersion)
            assertEquals(2, db.userVersion)
            assertEquals("before upgrade|none", db.queryOne("SELECT body, colour FROM notes") { "${it.getString(1)}|${it.getString(2)}" })
        }
        val snapshots = dir.resolve("backups/sample").listDirectoryEntries("sample-*-pre-v2.db")
        assertEquals(1, snapshots.size, "expected one pre-migration snapshot")
    }

    @Test
    fun failedMigrationRollsBackCompletely() {
        SqliteDatabases.open(spec(v1)).database.use { it.execute("INSERT INTO notes (body) VALUES (?)", "keep") }
        val broken = Migration(2, "broken") {
            executeRaw("ALTER TABLE notes ADD COLUMN colour TEXT")
            executeRaw("THIS IS NOT SQL")
        }

        assertFailsWith<MigrationFailedException> { SqliteDatabases.open(spec(v1, broken)) }

        // The half-applied ALTER must be gone and the version unchanged.
        SqliteDatabases.open(spec(v1)).database.use { db ->
            assertEquals(1, db.userVersion)
            val columns = db.query("PRAGMA table_info(notes)") { it.getString("name") }
            assertEquals(listOf("id", "body"), columns)
            assertEquals("keep", db.queryOne("SELECT body FROM notes") { it.getString(1) })
        }
    }

    @Test
    fun refusesDatabaseFromNewerBuildWithoutTouchingIt() {
        SqliteDatabases.open(spec(v1, v2)).database.use { it.execute("INSERT INTO notes (body) VALUES (?)", "from the future") }

        assertFailsWith<DatabaseNewerThanAppException> { SqliteDatabases.open(spec(v1)) }

        SqliteDatabases.open(spec(v1, v2)).database.use { db ->
            assertEquals(2, db.userVersion)
            assertEquals("from the future", db.queryOne("SELECT body FROM notes") { it.getString(1) })
        }
    }

    @Test
    fun corruptDatabaseIsQuarantinedAndRestoredFromBackup() {
        val spec = spec(v1)
        SqliteDatabases.open(spec).database.use { db ->
            db.execute("INSERT INTO notes (body) VALUES (?)", "backed up")
            SqliteDatabases.backupNow(spec, db)
            db.execute("INSERT INTO notes (body) VALUES (?)", "written after the backup")
        }
        corrupt(spec.file)

        val recovered = SqliteDatabases.open(spec)
        recovered.database.use { db ->
            assertEquals(OpenOutcome.RESTORED_FROM_BACKUP, recovered.outcome)
            assertNotNull(recovered.restoredFrom)
            assertEquals(listOf("backed up"), db.query("SELECT body FROM notes ORDER BY id") { it.getString(1) })
            assertTrue(db.passesIntegrityCheck())
        }
        val quarantined = assertNotNull(recovered.quarantinedFile)
        assertTrue(quarantined.exists(), "the damaged file must be kept, not deleted")
        assertTrue(quarantined.name.endsWith(".corrupt"))
    }

    @Test
    fun corruptDatabaseWithoutBackupIsRecreated() {
        val spec = spec(v1)
        Files.createDirectories(spec.file.parent)
        spec.file.writeBytes(ByteArray(8192) { 0x5A })

        val recovered = SqliteDatabases.open(spec)
        recovered.database.use { db ->
            assertEquals(OpenOutcome.RECREATED_AFTER_CORRUPTION, recovered.outcome)
            assertNull(recovered.restoredFrom)
            assertEquals(1, db.userVersion)
            assertEquals(0, db.queryOne("SELECT COUNT(*) FROM notes") { it.getInt(1) })
        }
        assertTrue(assertNotNull(recovered.quarantinedFile).exists())
    }

    @Test
    fun skipsDamagedBackupsAndUsesNewestHealthyOne() {
        val spec = spec(v1)
        SqliteDatabases.open(spec).database.use { db ->
            db.execute("INSERT INTO notes (body) VALUES (?)", "good")
            SqliteDatabases.backupNow(spec, db, Instant.parse("2026-01-01T00:00:00Z"))
        }
        // A newer backup that is itself unreadable.
        spec.backupDir.resolve("sample-20270101-000000-000-manual.db").writeBytes(ByteArray(4096) { 1 })
        corrupt(spec.file)

        val recovered = SqliteDatabases.open(spec)
        recovered.database.use { db ->
            assertEquals(OpenOutcome.RESTORED_FROM_BACKUP, recovered.outcome)
            assertEquals("good", db.queryOne("SELECT body FROM notes") { it.getString(1) })
        }
    }

    @Test
    fun routineBackupsAreTakenWhenDueAndPruned() {
        val spec = spec(v1, keep = 2, interval = 1000)
        SqliteDatabases.open(spec).database.close()
        var clock = Instant.parse("2026-03-01T00:00:00Z")
        repeat(4) {
            clock = clock.plusSeconds(3600)
            SqliteDatabases.open(spec) { clock }.database.use { db ->
                SqliteDatabases.backupNow(spec, db, clock)
            }
        }
        val backups = SqliteDatabases.listBackups(spec)
        assertEquals(2, backups.size, "only the newest backups are kept")
        assertTrue(backups.all { Files.size(it) > 0 })
    }

    @Test
    fun transactionsRollBackOnFailure() {
        SqliteDatabases.open(spec(v1)).database.use { db ->
            assertFailsWith<IllegalStateException> {
                db.transaction {
                    execute("INSERT INTO notes (body) VALUES (?)", "one")
                    transaction { execute("INSERT INTO notes (body) VALUES (?)", "nested") }
                    error("boom")
                }
            }
            assertEquals(0, db.queryOne("SELECT COUNT(*) FROM notes") { it.getInt(1) })

            db.transaction {
                execute("INSERT INTO notes (body) VALUES (?)", "one")
                transaction { execute("INSERT INTO notes (body) VALUES (?)", "nested") }
            }
            assertEquals(2, db.queryOne("SELECT COUNT(*) FROM notes") { it.getInt(1) })
        }
    }

    @Test
    fun foreignKeysAreEnforced() {
        val parentChild = Migration(
            1, "fk",
            "CREATE TABLE parent (id INTEGER PRIMARY KEY)",
            "CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER NOT NULL REFERENCES parent(id) ON DELETE CASCADE)",
        )
        SqliteDatabases.open(spec(parentChild)).database.use { db ->
            assertFailsWith<java.sql.SQLException> { db.execute("INSERT INTO child (parent_id) VALUES (?)", 99) }
            db.execute("INSERT INTO parent (id) VALUES (?)", 1)
            db.execute("INSERT INTO child (parent_id) VALUES (?)", 1)
            db.execute("DELETE FROM parent WHERE id = ?", 1)
            assertEquals(0, db.queryOne("SELECT COUNT(*) FROM child") { it.getInt(1) })
        }
    }

    @Test
    fun rejectsNonContiguousMigrations() {
        assertFailsWith<IllegalArgumentException> { spec(v1, Migration(3, "gap", "SELECT 1")) }
    }

    private fun corrupt(file: Path) {
        // Remove WAL sidecars so the damage cannot be papered over by replay, then overwrite the
        // file (including the SQLite header) with garbage.
        listOf("-wal", "-shm").forEach { Files.deleteIfExists(file.resolveSibling(file.name + it)) }
        file.writeBytes(ByteArray(Files.size(file).toInt().coerceAtLeast(4096)) { 0x7F })
    }
}
