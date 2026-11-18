package com.debridone.cloud

import com.debridone.platform.AppPaths
import com.debridone.platform.db.DatabaseSpec
import com.debridone.platform.db.Migration
import com.debridone.platform.db.OpenOutcome
import com.debridone.platform.db.SqliteDatabase
import com.debridone.platform.db.SqliteDatabases
import java.sql.ResultSet
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class AccountStatus(val key: String) {
    CONNECTED("connected"),

    /** The provider no longer accepts the saved sign-in. */
    NEEDS_SIGN_IN("sign-in"),

    /** The last attempt to reach the provider failed for another reason. */
    ERROR("error");

    companion object {
        fun of(key: String): AccountStatus = entries.firstOrNull { it.key == key } ?: ERROR
    }
}

/** One connected account. Its credentials are not here: they are in the secret store. */
data class CloudAccount(
    val id: Long,
    val provider: ProviderId,
    val remoteUserId: String,
    val username: String?,
    val email: String?,
    val plan: String?,
    val premium: Boolean,
    val premiumUntil: Long?,
    val details: Map<String, String>,
    /** "code" or "key". */
    val authKind: String,
    /** Null: every profile may use it. Otherwise only that profile. */
    val scopeProfileId: Long?,
    val status: AccountStatus,
    val lastError: String?,
    val connectedAt: Long,
    val lastRefreshAt: Long?,
    val lastListedAt: Long?,
) {
    val shared: Boolean get() = scopeProfileId == null
    val label: String get() = username ?: email ?: provider.displayName
    fun visibleTo(profileId: Long?): Boolean = scopeProfileId == null || scopeProfileId == profileId
}

/** An entry of an account as it is cached for browsing. */
data class CloudItemRow(
    val id: Long,
    val accountId: Long,
    val provider: ProviderId,
    val kind: ItemKind,
    val remoteId: String,
    val name: String,
    val sizeBytes: Long,
    val status: ItemStatus,
    val rawStatus: String?,
    val progress: Float?,
    val createdAt: Long?,
    val completedAt: Long?,
    val expiresAt: Long?,
    val fileCount: Int,
    /** "none" (not asked yet), "listed" or "packed" (the provider holds it as archives that cannot be opened here). */
    val filesState: String,
    /** The shelves its files belong to. */
    val classes: Set<MediaClass>,
)

/** A file of an entry as it is cached for browsing. */
data class CloudFileRow(
    val id: Long,
    val itemId: Long,
    val accountId: Long,
    val provider: ProviderId,
    val kind: ItemKind,
    val itemRemoteId: String,
    val itemName: String,
    val ref: String,
    val path: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val md5: String?,
    val mediaClass: MediaClass,
    val addedAt: Long?,
) {
    val name: String get() = path.substringAfterLast('/')
    val folder: String get() = path.substringBeforeLast('/', "")
}

enum class SortBy { NAME, DATE, SIZE, STATUS }

/** What the browser is showing. An empty [kinds] or a null filter means "all". */
data class CloudQuery(
    val provider: ProviderId? = null,
    val accountId: Long? = null,
    val kinds: Set<ItemKind> = emptySet(),
    val mediaClass: MediaClass? = null,
    val search: String = "",
    val sort: SortBy = SortBy.DATE,
    val descending: Boolean = true,
)

/**
 * `cloud.db`: the connected accounts, what was last seen in them, the download queue with its
 * history, and the cloud settings. It never holds a key, a token or a download address.
 */
internal class CloudStore(private val database: SqliteDatabase, val outcome: OpenOutcome) : AutoCloseable {
    override fun close() = database.close()

    fun <T> transaction(block: () -> T): T = database.transaction { block() }

    // ---------------------------------------------------------------- accounts

    fun accounts(): List<CloudAccount> = database.query("SELECT * FROM cloud_accounts ORDER BY provider, id", map = ::account)

    fun account(id: Long): CloudAccount? = database.queryOne("SELECT * FROM cloud_accounts WHERE id = ?", id, map = ::account)

    fun insertAccount(provider: ProviderId, info: AccountInfo, authKind: String, scopeProfileId: Long?, now: Long): Long = database.insert(
        """
        INSERT INTO cloud_accounts (provider, remote_user_id, username, email, plan, premium, premium_until, details, auth_kind, scope_profile_id, status, created_at, updated_at, last_refresh_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'connected', ?, ?, ?)
        """.trimIndent(),
        provider.key, info.remoteUserId, info.username, info.email, info.plan, if (info.premium) 1 else 0, info.premiumUntil, json(info.details), authKind, scopeProfileId, now, now, now,
    )

    fun updateAccountInfo(id: Long, info: AccountInfo, now: Long) {
        database.execute(
            "UPDATE cloud_accounts SET username = ?, email = ?, plan = ?, premium = ?, premium_until = ?, details = ?, status = 'connected', last_error = NULL, updated_at = ?, last_refresh_at = ? WHERE id = ?",
            info.username, info.email, info.plan, if (info.premium) 1 else 0, info.premiumUntil, json(info.details), now, now, id,
        )
    }

    fun setAccountStatus(id: Long, status: AccountStatus, error: String?, now: Long) {
        database.execute("UPDATE cloud_accounts SET status = ?, last_error = ?, updated_at = ? WHERE id = ?", status.key, error?.take(300), now, id)
    }

    fun setAccountScope(id: Long, scopeProfileId: Long?, now: Long) {
        database.execute("UPDATE cloud_accounts SET scope_profile_id = ?, updated_at = ? WHERE id = ?", scopeProfileId, now, id)
    }

    fun markListed(id: Long, now: Long) {
        database.execute("UPDATE cloud_accounts SET last_listed_at = ?, status = 'connected', last_error = NULL WHERE id = ?", now, id)
    }

    fun deleteAccount(id: Long) = database.transaction {
        execute("DELETE FROM cloud_files WHERE account_id = ?", id)
        execute("DELETE FROM cloud_items WHERE account_id = ?", id)
        execute("DELETE FROM cloud_accounts WHERE id = ?", id)
    }

    private fun account(rs: ResultSet) = CloudAccount(
        id = rs.getLong("id"),
        provider = ProviderId.of(rs.getString("provider")) ?: ProviderId.REAL_DEBRID,
        remoteUserId = rs.getString("remote_user_id"),
        username = rs.getString("username"),
        email = rs.getString("email"),
        plan = rs.getString("plan"),
        premium = rs.getInt("premium") != 0,
        premiumUntil = rs.nullableLong("premium_until"),
        details = map(rs.getString("details")),
        authKind = rs.getString("auth_kind"),
        scopeProfileId = rs.nullableLong("scope_profile_id"),
        status = AccountStatus.of(rs.getString("status")),
        lastError = rs.getString("last_error"),
        connectedAt = rs.getLong("created_at"),
        lastRefreshAt = rs.nullableLong("last_refresh_at"),
        lastListedAt = rs.nullableLong("last_listed_at"),
    )

    // ---------------------------------------------------------------- entries and files

    /** What is known about an entry before a new listing: its row and whether its files must be asked for again. */
    class Known(val id: Long, val signature: String?, val filesState: String)

    fun knownItems(accountId: Long, kind: ItemKind): Map<String, Known> = database.query(
        "SELECT id, remote_id, signature, files_state FROM cloud_items WHERE account_id = ? AND kind = ?", accountId, kind.key,
    ) { it.getString(2) to Known(it.getLong(1), it.getString(3), it.getString(4)) }.toMap()

    /**
     * Writes one entry of a listing and returns its row id. The signature of an entry that is
     * already known is left alone: it changes only together with its files ([setFiles]).
     */
    fun upsertItem(accountId: Long, item: CloudItem, seenAt: Long): Long {
        database.execute(
            """
            INSERT INTO cloud_items (account_id, kind, remote_id, name, name_key, size, status, raw_status, progress, created_at, completed_at, expires_at, signature, origin, seen_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(account_id, kind, remote_id) DO UPDATE SET name = excluded.name, name_key = excluded.name_key, size = excluded.size, status = excluded.status,
                raw_status = excluded.raw_status, progress = excluded.progress, created_at = excluded.created_at, completed_at = excluded.completed_at,
                expires_at = excluded.expires_at, origin = excluded.origin, seen_at = excluded.seen_at
            """.trimIndent(),
            accountId, item.kind.key, item.remoteId, item.name, searchKey(item.name), item.sizeBytes, item.status.name, item.rawStatus, item.progress?.toDouble(),
            item.createdAt, item.completedAt, item.expiresAt, item.signature, item.origin, seenAt,
        )
        return checkNotNull(database.queryOne("SELECT id FROM cloud_items WHERE account_id = ? AND kind = ? AND remote_id = ?", accountId, item.kind.key, item.remoteId) { it.getLong(1) })
    }

    /** Replaces the files of an entry, keeping the rows (and ids) of files that are still there. */
    fun setFiles(itemId: Long, accountId: Long, files: List<CloudFile>, state: String, signature: String?) = database.transaction {
        val keep = HashSet<String>()
        for (file in files) {
            if (!keep.add(file.ref)) continue
            execute(
                """
                INSERT INTO cloud_files (item_id, account_id, remote_ref, path, name, name_key, extension, size, mime, md5, media_class)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(item_id, remote_ref) DO UPDATE SET path = excluded.path, name = excluded.name, name_key = excluded.name_key, extension = excluded.extension,
                    size = excluded.size, mime = excluded.mime, md5 = excluded.md5, media_class = excluded.media_class
                """.trimIndent(),
                itemId, accountId, file.ref, file.path, file.name, searchKey(file.path), MediaFileClassifier.extension(file.path), file.sizeBytes, file.mimeType, file.md5,
                MediaFileClassifier.classify(file.path, file.mimeType).key,
            )
        }
        query("SELECT id, remote_ref FROM cloud_files WHERE item_id = ?", itemId) { it.getLong(1) to it.getString(2) }
            .filter { it.second !in keep }
            .forEach { execute("DELETE FROM cloud_files WHERE id = ?", it.first) }
        execute("UPDATE cloud_items SET files_state = ?, signature = ? WHERE id = ?", state, signature, itemId)
    }

    /** Forgets entries of [kind] that the listing which just finished at [seenAt] no longer contained. */
    fun removeUnseen(accountId: Long, kind: ItemKind, seenAt: Long): Int = database.transaction {
        val gone = query("SELECT id FROM cloud_items WHERE account_id = ? AND kind = ? AND seen_at < ?", accountId, kind.key, seenAt) { it.getLong(1) }
        gone.forEach { removeItemRow(it) }
        gone.size
    }

    fun removeItemRow(itemId: Long) = database.transaction {
        execute("DELETE FROM cloud_files WHERE item_id = ?", itemId)
        execute("DELETE FROM cloud_items WHERE id = ?", itemId)
    }

    /**
     * A generated link whose origin is a file of one of the account's torrents is the same file
     * listed twice: it is hidden, and shown again if the torrent goes away.
     */
    fun hideDuplicates(accountId: Long) {
        database.execute(
            """
            UPDATE cloud_items SET hidden = CASE WHEN origin IS NOT NULL AND EXISTS (
                SELECT 1 FROM cloud_files f JOIN cloud_items t ON t.id = f.item_id
                WHERE f.account_id = cloud_items.account_id AND t.kind <> 'link' AND f.remote_ref = 'l:' || cloud_items.origin
            ) THEN 1 ELSE 0 END
            WHERE account_id = ? AND kind = 'link'
            """.trimIndent(),
            accountId,
        )
    }

    fun items(accountIds: Collection<Long>, query: CloudQuery): List<CloudItemRow> {
        if (accountIds.isEmpty()) return emptyList()
        val where = StringBuilder("i.hidden = 0 AND i.account_id IN (${accountIds.joinToString(",")})")
        val arguments = ArrayList<Any?>()
        query.provider?.let { where.append(" AND a.provider = ?"); arguments += it.key }
        query.accountId?.let { where.append(" AND i.account_id = ?"); arguments += it }
        if (query.kinds.isNotEmpty()) where.append(" AND i.kind IN (${query.kinds.joinToString(",") { "'${it.key}'" }})")
        query.mediaClass?.let { where.append(" AND EXISTS (SELECT 1 FROM cloud_files f WHERE f.item_id = i.id AND f.media_class = ?)"); arguments += it.key }
        searchKey(query.search).takeIf { it.isNotEmpty() }?.let { text ->
            where.append(" AND (i.name_key LIKE ? ESCAPE '\\' OR EXISTS (SELECT 1 FROM cloud_files f WHERE f.item_id = i.id AND f.name_key LIKE ? ESCAPE '\\'))")
            val pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            arguments += pattern
            arguments += pattern
        }
        val direction = if (query.descending) "DESC" else "ASC"
        val order = when (query.sort) {
            SortBy.NAME -> "i.name_key $direction"
            SortBy.DATE -> "COALESCE(i.created_at, 0) $direction, i.name_key"
            SortBy.SIZE -> "i.size $direction, i.name_key"
            SortBy.STATUS -> "i.status $direction, i.name_key"
        }
        return database.query(
            """
            SELECT i.*, a.provider,
                (SELECT COUNT(*) FROM cloud_files f WHERE f.item_id = i.id) AS file_count,
                (SELECT GROUP_CONCAT(DISTINCT f.media_class) FROM cloud_files f WHERE f.item_id = i.id) AS classes
            FROM cloud_items i JOIN cloud_accounts a ON a.id = i.account_id
            WHERE $where ORDER BY $order
            """.trimIndent(),
            *arguments.toTypedArray(), map = ::item,
        )
    }

    fun item(itemId: Long): CloudItemRow? = database.queryOne(
        """
        SELECT i.*, a.provider,
            (SELECT COUNT(*) FROM cloud_files f WHERE f.item_id = i.id) AS file_count,
            (SELECT GROUP_CONCAT(DISTINCT f.media_class) FROM cloud_files f WHERE f.item_id = i.id) AS classes
        FROM cloud_items i JOIN cloud_accounts a ON a.id = i.account_id WHERE i.id = ?
        """.trimIndent(),
        itemId, map = ::item,
    )

    private fun item(rs: ResultSet) = CloudItemRow(
        id = rs.getLong("id"),
        accountId = rs.getLong("account_id"),
        provider = ProviderId.of(rs.getString("provider")) ?: ProviderId.REAL_DEBRID,
        kind = ItemKind.of(rs.getString("kind")) ?: ItemKind.TORRENT,
        remoteId = rs.getString("remote_id"),
        name = rs.getString("name"),
        sizeBytes = rs.getLong("size"),
        status = runCatching { ItemStatus.valueOf(rs.getString("status")) }.getOrDefault(ItemStatus.UNKNOWN),
        rawStatus = rs.getString("raw_status"),
        progress = rs.getDouble("progress").let { value -> if (rs.wasNull()) null else value.toFloat() },
        createdAt = rs.nullableLong("created_at"),
        completedAt = rs.nullableLong("completed_at"),
        expiresAt = rs.nullableLong("expires_at"),
        fileCount = rs.getInt("file_count"),
        filesState = rs.getString("files_state"),
        classes = rs.getString("classes").orEmpty().split(',').filter { it.isNotEmpty() }.map(MediaClass::of).toSet(),
    )

    private val fileSelect = """
        SELECT f.*, i.kind, i.remote_id AS item_remote_id, i.name AS item_name, i.created_at AS added_at, a.provider
        FROM cloud_files f JOIN cloud_items i ON i.id = f.item_id JOIN cloud_accounts a ON a.id = f.account_id
    """.trimIndent()

    fun files(itemId: Long): List<CloudFileRow> = database.query("$fileSelect WHERE f.item_id = ? ORDER BY f.path COLLATE NOCASE", itemId, map = ::file)

    fun file(fileId: Long): CloudFileRow? = database.queryOne("$fileSelect WHERE f.id = ?", fileId, map = ::file)

    /** Every file of the given accounts on one shelf (or of several), for a module's own view of the cloud. */
    fun filesOf(accountIds: Collection<Long>, classes: Set<MediaClass>, extensions: Set<String> = emptySet()): List<CloudFileRow> {
        if (accountIds.isEmpty() || (classes.isEmpty() && extensions.isEmpty())) return emptyList()
        val byClass = classes.takeIf { it.isNotEmpty() }?.let { "f.media_class IN (${it.joinToString(",") { c -> "'${c.key}'" }})" }
        val byExtension = extensions.takeIf { it.isNotEmpty() }?.let { "f.extension IN (${it.joinToString(",") { e -> "'${e.filter(Char::isLetterOrDigit)}'" }})" }
        return database.query(
            "$fileSelect WHERE i.hidden = 0 AND i.status = 'READY' AND f.account_id IN (${accountIds.joinToString(",")}) AND (${listOfNotNull(byClass, byExtension).joinToString(" OR ")}) ORDER BY i.name_key, f.path COLLATE NOCASE",
            map = ::file,
        )
    }

    private fun file(rs: ResultSet) = CloudFileRow(
        id = rs.getLong("id"),
        itemId = rs.getLong("item_id"),
        accountId = rs.getLong("account_id"),
        provider = ProviderId.of(rs.getString("provider")) ?: ProviderId.REAL_DEBRID,
        kind = ItemKind.of(rs.getString("kind")) ?: ItemKind.TORRENT,
        itemRemoteId = rs.getString("item_remote_id"),
        itemName = rs.getString("item_name"),
        ref = rs.getString("remote_ref"),
        path = rs.getString("path"),
        sizeBytes = rs.getLong("size"),
        mimeType = rs.getString("mime"),
        md5 = rs.getString("md5"),
        mediaClass = MediaClass.of(rs.getString("media_class")),
        addedAt = rs.nullableLong("added_at"),
    )

    // ---------------------------------------------------------------- settings

    fun setting(scope: String, key: String): String? = database.queryOne("SELECT value FROM cloud_settings WHERE scope = ? AND key = ?", scope, key) { it.getString(1) }

    fun setSetting(scope: String, key: String, value: String?) {
        if (value == null) {
            database.execute("DELETE FROM cloud_settings WHERE scope = ? AND key = ?", scope, key)
        } else {
            database.execute("INSERT INTO cloud_settings (scope, key, value) VALUES (?, ?, ?) ON CONFLICT(scope, key) DO UPDATE SET value = excluded.value", scope, key, value)
        }
    }

    fun deleteSettings(scope: String) {
        database.execute("DELETE FROM cloud_settings WHERE scope = ?", scope)
    }

    // ---------------------------------------------------------------- downloads (see DownloadManager)

    val raw: SqliteDatabase get() = database

    companion object {
        const val DATABASE_NAME = "cloud"

        /** What names are compared by: lower case, without accents. */
        fun searchKey(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

        private fun json(values: Map<String, String>): String = buildJsonObject { values.forEach { (key, value) -> put(key, value) } }.toString()

        private fun map(text: String?): Map<String, String> =
            (runCatching { Json.parseToJsonElement(text.orEmpty()) }.getOrNull() as? JsonObject)?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }?.toMap().orEmpty()

        private fun ResultSet.nullableLong(column: String): Long? = getLong(column).let { if (wasNull()) null else it }

        val MIGRATIONS: List<Migration> = listOf(
            Migration(
                1, "connected accounts, cached listings, downloads, settings",
                """
                CREATE TABLE cloud_accounts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    provider TEXT NOT NULL,
                    remote_user_id TEXT NOT NULL,
                    username TEXT,
                    email TEXT,
                    plan TEXT,
                    premium INTEGER NOT NULL DEFAULT 0,
                    premium_until INTEGER,
                    details TEXT NOT NULL DEFAULT '{}',
                    auth_kind TEXT NOT NULL,
                    scope_profile_id INTEGER,
                    status TEXT NOT NULL DEFAULT 'connected',
                    last_error TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    last_refresh_at INTEGER,
                    last_listed_at INTEGER
                )
                """.trimIndent(),
                """
                CREATE TABLE cloud_items (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    account_id INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    remote_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    name_key TEXT NOT NULL,
                    size INTEGER NOT NULL DEFAULT 0,
                    status TEXT NOT NULL,
                    raw_status TEXT,
                    progress REAL,
                    created_at INTEGER,
                    completed_at INTEGER,
                    expires_at INTEGER,
                    files_state TEXT NOT NULL DEFAULT 'none',
                    signature TEXT,
                    origin TEXT,
                    hidden INTEGER NOT NULL DEFAULT 0,
                    seen_at INTEGER NOT NULL,
                    UNIQUE (account_id, kind, remote_id)
                )
                """.trimIndent(),
                "CREATE INDEX cloud_items_by_account ON cloud_items (account_id, kind, status)",
                """
                CREATE TABLE cloud_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_id INTEGER NOT NULL,
                    account_id INTEGER NOT NULL,
                    remote_ref TEXT NOT NULL,
                    path TEXT NOT NULL,
                    name TEXT NOT NULL,
                    name_key TEXT NOT NULL,
                    extension TEXT NOT NULL DEFAULT '',
                    size INTEGER NOT NULL DEFAULT 0,
                    mime TEXT,
                    md5 TEXT,
                    media_class TEXT NOT NULL,
                    UNIQUE (item_id, remote_ref)
                )
                """.trimIndent(),
                "CREATE INDEX cloud_files_by_class ON cloud_files (account_id, media_class)",
                "CREATE INDEX cloud_files_by_ref ON cloud_files (account_id, remote_ref)",
                """
                CREATE TABLE downloads (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    profile_id INTEGER NOT NULL,
                    account_id INTEGER NOT NULL,
                    provider TEXT NOT NULL,
                    item_kind TEXT NOT NULL,
                    item_remote_id TEXT NOT NULL,
                    file_ref TEXT NOT NULL,
                    item_name TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    media_class TEXT NOT NULL,
                    destination TEXT NOT NULL,
                    total_bytes INTEGER NOT NULL DEFAULT 0,
                    received_bytes INTEGER NOT NULL DEFAULT 0,
                    etag TEXT,
                    expected_md5 TEXT,
                    verified INTEGER,
                    state TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    error TEXT,
                    imported_into TEXT,
                    import_ref TEXT,
                    created_at INTEGER NOT NULL,
                    started_at INTEGER,
                    finished_at INTEGER,
                    file_deleted_at INTEGER
                )
                """.trimIndent(),
                "CREATE INDEX downloads_by_profile ON downloads (profile_id, state)",
                """
                CREATE TABLE cloud_settings (
                    scope TEXT NOT NULL,
                    key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    PRIMARY KEY (scope, key)
                )
                """.trimIndent(),
            ),
        )

        fun spec(paths: AppPaths): DatabaseSpec = DatabaseSpec(
            name = DATABASE_NAME,
            file = paths.cloudDir.resolve("cloud.db"),
            migrations = MIGRATIONS,
            backupDir = paths.backupDirFor(DATABASE_NAME),
        )

        fun open(paths: AppPaths): CloudStore {
            paths.ensureCreated(paths.cloudDir)
            val opened = SqliteDatabases.open(spec(paths))
            return CloudStore(opened.database, opened.outcome)
        }
    }
}
