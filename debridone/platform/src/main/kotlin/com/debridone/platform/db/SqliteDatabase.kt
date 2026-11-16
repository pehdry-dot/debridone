package com.debridone.platform.db

import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One SQLite file, one connection, serialized access.
 *
 * DebridOne's databases are small and written by a single process, so a single connection guarded
 * by a lock is both the simplest and the safest arrangement: every write is a real transaction and
 * there is no pool to misconfigure. Open instances through [SqliteDatabases.open], which also runs
 * integrity checks, migrations and backups.
 */
class SqliteDatabase internal constructor(
    val path: Path,
    private val connection: Connection,
) : AutoCloseable {
    private val lock = ReentrantLock()
    private var transactionDepth = 0

    @Volatile
    var isClosed: Boolean = false
        private set

    /** Runs [block] atomically. Nested calls join the outer transaction. */
    fun <T> transaction(block: SqliteDatabase.() -> T): T = lock.withLock {
        checkOpen()
        if (transactionDepth > 0) {
            transactionDepth++
            try {
                return@withLock block()
            } finally {
                transactionDepth--
            }
        }
        connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        transactionDepth = 1
        try {
            val result = block()
            connection.createStatement().use { it.execute("COMMIT") }
            result
        } catch (t: Throwable) {
            runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
            throw t
        } finally {
            transactionDepth = 0
        }
    }

    /** Executes a write statement and returns the number of affected rows. */
    fun execute(sql: String, vararg args: Any?): Int = lock.withLock {
        checkOpen()
        connection.prepareStatement(sql).use { statement ->
            statement.bind(args)
            statement.executeUpdate()
        }
    }

    /** Executes an INSERT and returns the generated row id. */
    fun insert(sql: String, vararg args: Any?): Long = lock.withLock {
        checkOpen()
        connection.prepareStatement(sql).use { statement ->
            statement.bind(args)
            statement.executeUpdate()
        }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT last_insert_rowid()").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

    /** Executes raw SQL that takes no parameters (DDL, PRAGMA). */
    fun executeRaw(sql: String) = lock.withLock {
        checkOpen()
        connection.createStatement().use { it.execute(sql) }
        Unit
    }

    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> = lock.withLock {
        checkOpen()
        connection.prepareStatement(sql).use { statement ->
            statement.bind(args)
            statement.executeQuery().use { rs ->
                val rows = ArrayList<T>()
                while (rs.next()) rows += map(rs)
                rows
            }
        }
    }

    fun <T> queryOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
        query(sql, *args, map = map).firstOrNull()

    var userVersion: Int
        get() = queryOne("PRAGMA user_version") { it.getInt(1) } ?: 0
        internal set(value) {
            require(value >= 0)
            executeRaw("PRAGMA user_version = $value")
        }

    /** True when SQLite's own consistency check passes. */
    fun passesIntegrityCheck(): Boolean = runCatching {
        queryOne("PRAGMA quick_check") { it.getString(1) }.equals("ok", ignoreCase = true)
    }.getOrDefault(false)

    /**
     * Writes a consistent, compacted snapshot of the whole database to [target].
     * Safe to call while the database is in use; must not be called inside [transaction].
     */
    fun snapshotTo(target: Path) = lock.withLock {
        checkOpen()
        check(transactionDepth == 0) { "snapshotTo cannot run inside a transaction" }
        connection.prepareStatement("VACUUM INTO ?").use { statement ->
            statement.setString(1, target.toAbsolutePath().toString())
            statement.execute()
        }
        Unit
    }

    override fun close() = lock.withLock {
        if (isClosed) return@withLock
        isClosed = true
        runCatching { connection.createStatement().use { it.execute("PRAGMA optimize") } }
        connection.close()
    }

    private fun checkOpen() {
        check(!isClosed) { "Database is closed: $path" }
    }

    private fun PreparedStatement.bind(args: Array<out Any?>) {
        args.forEachIndexed { index, value ->
            val position = index + 1
            when (value) {
                null -> setNull(position, Types.NULL)
                is String -> setString(position, value)
                is Int -> setInt(position, value)
                is Long -> setLong(position, value)
                is Boolean -> setInt(position, if (value) 1 else 0)
                is Double -> setDouble(position, value)
                is Float -> setDouble(position, value.toDouble())
                is ByteArray -> setBytes(position, value)
                else -> throw IllegalArgumentException("Unsupported SQL parameter type: ${value::class}")
            }
        }
    }
}

fun ResultSet.getBooleanInt(column: String): Boolean = getInt(column) != 0

fun ResultSet.getNullableString(column: String): String? = getString(column)

fun ResultSet.getNullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}
