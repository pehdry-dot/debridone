package com.debridone.accounts

import com.debridone.platform.AppPaths
import com.debridone.platform.db.DatabaseSpec
import com.debridone.platform.db.Migration
import com.debridone.platform.db.OpenOutcome
import com.debridone.platform.db.SqliteDatabase
import com.debridone.platform.db.SqliteDatabases
import com.debridone.platform.db.getBooleanInt
import com.debridone.platform.db.getNullableLong
import java.nio.file.Path
import java.sql.ResultSet
import java.util.UUID

/**
 * `accounts.db`: accounts, profiles, the remembered device session and shared settings.
 *
 * This database holds identity only. Libraries, history, plugins and metadata belong to the
 * individual entertainment modules and live in their own storage, keyed by [Profile.id].
 */
class AccountsDatabase private constructor(
    private val database: SqliteDatabase,
    private val spec: DatabaseSpec,
    /** How the file was opened: created, migrated, restored from a backup after damage, ... */
    val openOutcome: OpenOutcome,
    private val passwordHasher: PasswordHasher,
    private val pinHasher: PasswordHasher,
    private val clock: () -> Long,
) : AutoCloseable {

    // ---------------------------------------------------------------- accounts

    fun listAccounts(): List<Account> =
        database.query("SELECT * FROM accounts ORDER BY created_at ASC", map = ::readAccount)

    fun findAccount(accountId: String): Account? =
        database.queryOne("SELECT * FROM accounts WHERE id = ?", accountId, map = ::readAccount)

    /** Creates a password-protected account and signs it in on this device. */
    fun createAccount(login: String, password: String, displayName: String? = null): Account {
        val normalizedLogin = validateLogin(login)
        validatePassword(password)
        val hash = passwordHasher.hash(password.toCharArray())
        val now = clock()
        val id = UUID.randomUUID().toString()
        database.transaction {
            if (loginExists(normalizedLogin)) {
                throw AccountException(AccountError.LOGIN_TAKEN, "An account with this sign-in name already exists on this device.")
            }
            execute(
                "INSERT INTO accounts (id, login, display_name, password_hash, is_guest, created_at, updated_at, last_login_at) " +
                    "VALUES (?, ?, ?, ?, 0, ?, ?, ?)",
                id, normalizedLogin, resolveDisplayName(displayName, normalizedLogin), hash, now, now, now,
            )
            writeSession(id, now)
        }
        return checkNotNull(findAccount(id))
    }

    /** Verifies the password and signs the account in on this device. */
    fun signIn(login: String, password: String): Account {
        val normalizedLogin = login.trim()
        val now = clock()
        val row = database.queryOne(
            "SELECT id, password_hash, failed_logins, locked_until FROM accounts WHERE login = ? COLLATE NOCASE AND is_guest = 0",
            normalizedLogin,
        ) { rs ->
            LoginRow(rs.getString("id"), rs.getString("password_hash"), rs.getInt("failed_logins"), rs.getNullableLong("locked_until"))
        }
        if (row == null) {
            // Spend comparable time whether or not the account exists.
            passwordHasher.verify(password.toCharArray(), DUMMY_HASH)
            throw invalidCredentials()
        }
        val lockedUntil = row.lockedUntil
        if (lockedUntil != null && lockedUntil > now) {
            val seconds = secondsUntil(lockedUntil, now)
            throw AccountException(AccountError.TOO_MANY_ATTEMPTS, "Too many attempts. Try again in $seconds seconds.", seconds)
        }
        if (!passwordHasher.verify(password.toCharArray(), row.passwordHash)) {
            val failures = row.failedLogins + 1
            val lock = lockDuration(failures, LOGIN_FREE_ATTEMPTS, LOGIN_BASE_LOCK_MS, LOGIN_MAX_LOCK_MS)
            database.execute(
                "UPDATE accounts SET failed_logins = ?, locked_until = ? WHERE id = ?",
                failures, lock?.let { now + it }, row.id,
            )
            throw invalidCredentials()
        }
        database.transaction {
            val upgraded = if (passwordHasher.needsRehash(row.passwordHash)) passwordHasher.hash(password.toCharArray()) else row.passwordHash
            execute(
                "UPDATE accounts SET failed_logins = 0, locked_until = NULL, last_login_at = ?, password_hash = ? WHERE id = ?",
                now, upgraded, row.id,
            )
            writeSession(row.id, now)
        }
        return checkNotNull(findAccount(row.id))
    }

    /** Signs in the device's password-less guest account, creating it on first use. */
    fun signInGuest(): Account {
        val now = clock()
        val id = database.transaction {
            val existing = queryOne("SELECT id FROM accounts WHERE is_guest = 1 ORDER BY created_at ASC LIMIT 1") { it.getString(1) }
            val accountId = existing ?: UUID.randomUUID().toString().also { fresh ->
                execute(
                    "INSERT INTO accounts (id, login, display_name, password_hash, is_guest, created_at, updated_at) VALUES (?, NULL, ?, NULL, 1, ?, ?)",
                    fresh, "Guest", now, now,
                )
            }
            execute("UPDATE accounts SET last_login_at = ? WHERE id = ?", now, accountId)
            writeSession(accountId, now)
            accountId
        }
        return checkNotNull(findAccount(id))
    }

    /** Turns the guest account into a regular password-protected account, keeping all its profiles. */
    fun upgradeGuest(accountId: String, login: String, password: String, displayName: String? = null): Account {
        val normalizedLogin = validateLogin(login)
        validatePassword(password)
        val hash = passwordHasher.hash(password.toCharArray())
        val now = clock()
        database.transaction {
            val account = findAccount(accountId) ?: throw accountNotFound()
            require(account.isGuest) { "Account is not a guest account" }
            if (loginExists(normalizedLogin)) {
                throw AccountException(AccountError.LOGIN_TAKEN, "An account with this sign-in name already exists on this device.")
            }
            execute(
                "UPDATE accounts SET login = ?, display_name = ?, password_hash = ?, is_guest = 0, updated_at = ? WHERE id = ?",
                normalizedLogin, resolveDisplayName(displayName, normalizedLogin), hash, now, accountId,
            )
        }
        return checkNotNull(findAccount(accountId))
    }

    fun changePassword(accountId: String, currentPassword: String, newPassword: String) {
        validatePassword(newPassword)
        val stored = database.queryOne("SELECT password_hash FROM accounts WHERE id = ?", accountId) { it.getString(1) }
        if (stored == null && findAccount(accountId) == null) throw accountNotFound()
        if (!passwordHasher.verify(currentPassword.toCharArray(), stored)) throw invalidCredentials()
        database.execute(
            "UPDATE accounts SET password_hash = ?, failed_logins = 0, locked_until = NULL, updated_at = ? WHERE id = ?",
            passwordHasher.hash(newPassword.toCharArray()), clock(), accountId,
        )
    }

    fun renameAccount(accountId: String, displayName: String) {
        val name = displayName.trim()
        require(name.isNotEmpty() && name.length <= MAX_NAME_LENGTH) { "Invalid display name" }
        if (database.execute("UPDATE accounts SET display_name = ?, updated_at = ? WHERE id = ?", name, clock(), accountId) == 0) {
            throw accountNotFound()
        }
    }

    /** True when [password] is the account's password. Guest accounts have none and always return false. */
    fun verifyPassword(accountId: String, password: String): Boolean {
        val stored = database.queryOne("SELECT password_hash FROM accounts WHERE id = ?", accountId) { it.getString(1) }
        return passwordHasher.verify(password.toCharArray(), stored)
    }

    /**
     * Permanently deletes an account and its profiles. Password accounts must confirm with their
     * password. Returns the ids of the deleted profiles so each module can erase what they owned.
     */
    fun deleteAccount(accountId: String, password: String?): List<Long> {
        val account = findAccount(accountId) ?: throw accountNotFound()
        if (account.hasPassword && !verifyPassword(accountId, password.orEmpty())) throw invalidCredentials()
        return database.transaction {
            val profileIds = query("SELECT id FROM profiles WHERE account_id = ?", accountId) { it.getLong(1) }
            profileIds.forEach { deleteSettings(SettingsScope.OfProfile(it)) }
            deleteSettings(SettingsScope.OfAccount(accountId))
            execute("DELETE FROM accounts WHERE id = ?", accountId)
            profileIds
        }
    }

    // ----------------------------------------------------------------- session

    /** The account that stayed signed in, if any. Survives restarts. */
    fun currentSession(): DeviceSession? = database.queryOne(
        "SELECT s.account_id, a.last_profile_id, a.has_ever_selected_profile, a.remember_last_profile " +
            "FROM device_session s JOIN accounts a ON a.id = s.account_id WHERE s.id = 1",
    ) { rs ->
        DeviceSession(
            accountId = rs.getString("account_id"),
            activeProfileId = rs.getNullableLong("last_profile_id"),
            hasEverSelectedProfile = rs.getBooleanInt("has_ever_selected_profile"),
            rememberLastProfile = rs.getBooleanInt("remember_last_profile"),
        )
    }

    /** Signs out on this device. Nothing the account owns is deleted. */
    fun signOut() {
        database.execute("DELETE FROM device_session")
    }

    fun setActiveProfile(accountId: String, profileId: Long?) {
        database.transaction {
            if (profileId != null && profileOwner(profileId) != accountId) {
                throw AccountException(AccountError.PROFILE_NOT_FOUND, "Profile not found.")
            }
            execute(
                "UPDATE accounts SET last_profile_id = ?, has_ever_selected_profile = CASE WHEN ? THEN 1 ELSE has_ever_selected_profile END WHERE id = ?",
                profileId, profileId != null, accountId,
            )
        }
    }

    fun setRememberLastProfile(accountId: String, enabled: Boolean) {
        database.execute("UPDATE accounts SET remember_last_profile = ? WHERE id = ?", enabled, accountId)
    }

    // ---------------------------------------------------------------- profiles

    fun listProfiles(accountId: String): List<Profile> = database.query(
        "SELECT * FROM profiles WHERE account_id = ? ORDER BY sort_order ASC, id ASC", accountId, map = ::readProfile,
    )

    fun findProfile(profileId: Long): Profile? =
        database.queryOne("SELECT * FROM profiles WHERE id = ?", profileId, map = ::readProfile)

    /**
     * Reserves a profile id that has never been used on this device and never will be again.
     * Ids are unique across accounts, so per-profile data in the modules can never collide, and a
     * deleted profile's id is not recycled for a new one.
     */
    fun reserveProfileId(): Long = database.transaction {
        val current = queryOne("SELECT seq FROM sqlite_sequence WHERE name = 'profiles'") { it.getLong(1) }
        val next = maxOf(current ?: 0, FIRST_PROFILE_ID - 1) + 1
        if (current == null) {
            execute("INSERT INTO sqlite_sequence (name, seq) VALUES ('profiles', ?)", next)
        } else {
            execute("UPDATE sqlite_sequence SET seq = ? WHERE name = 'profiles'", next)
        }
        next
    }

    fun createProfile(accountId: String, draft: ProfileDraft): Profile {
        val id = database.transaction {
            if (findAccount(accountId) == null) throw accountNotFound()
            val count = queryOne("SELECT COUNT(*) FROM profiles WHERE account_id = ?", accountId) { it.getInt(1) } ?: 0
            if (count >= MAX_PROFILES_PER_ACCOUNT) {
                throw AccountException(AccountError.PROFILE_LIMIT_REACHED, "An account can have up to $MAX_PROFILES_PER_ACCOUNT profiles.")
            }
            val profileId = draft.id ?: reserveProfileId()
            insertProfile(accountId, profileId, draft, sortOrder = count)
            profileId
        }
        return checkNotNull(findProfile(id))
    }

    fun updateProfile(accountId: String, draft: ProfileDraft): Profile {
        val profileId = requireNotNull(draft.id) { "Profile id is required" }
        database.transaction {
            if (profileOwner(profileId) != accountId) throw AccountException(AccountError.PROFILE_NOT_FOUND, "Profile not found.")
            updateProfileRow(profileId, draft)
        }
        return checkNotNull(findProfile(profileId))
    }

    /** Deletes one profile. Returns false when it did not exist. Callers must then erase its module data. */
    fun deleteProfile(accountId: String, profileId: Long): Boolean = database.transaction {
        if (profileOwner(profileId) != accountId) return@transaction false
        deleteSettings(SettingsScope.OfProfile(profileId))
        execute("UPDATE accounts SET last_profile_id = NULL WHERE id = ? AND last_profile_id = ?", accountId, profileId)
        execute("DELETE FROM profiles WHERE id = ?", profileId) > 0
    }

    /** Marks one profile as the one to open automatically (or none when [profileId] is null). */
    fun setDefaultProfile(accountId: String, profileId: Long?) {
        database.transaction {
            if (profileId != null && profileOwner(profileId) != accountId) {
                throw AccountException(AccountError.PROFILE_NOT_FOUND, "Profile not found.")
            }
            execute("UPDATE profiles SET is_default = 0 WHERE account_id = ?", accountId)
            if (profileId != null) execute("UPDATE profiles SET is_default = 1 WHERE id = ?", profileId)
        }
    }

    /**
     * Makes the account's profiles match [drafts] in one transaction: listed profiles are created
     * or updated (PINs and the default flag are left untouched), profiles that are not listed are
     * deleted. Returns the ids of deleted profiles.
     */
    fun replaceProfiles(accountId: String, drafts: List<ProfileDraft>): List<Long> =
        writeProfiles(accountId, drafts, deleteMissing = true)

    /**
     * Creates or updates the listed profiles and never deletes any. Use this when the caller's
     * list may be incomplete; deletion is always an explicit [deleteProfile].
     */
    fun saveProfiles(accountId: String, drafts: List<ProfileDraft>) {
        writeProfiles(accountId, drafts, deleteMissing = false)
    }

    private fun writeProfiles(accountId: String, drafts: List<ProfileDraft>, deleteMissing: Boolean): List<Long> = database.transaction {
        if (findAccount(accountId) == null) throw accountNotFound()
        val existing = query("SELECT id FROM profiles WHERE account_id = ?", accountId) { it.getLong(1) }.toSet()
        val keep = HashSet<Long>()
        var created = 0
        drafts.forEachIndexed { index, draft ->
            val profileId = draft.id ?: reserveProfileId()
            if (profileId in existing) {
                updateProfileRow(profileId, draft)
                if (deleteMissing) execute("UPDATE profiles SET sort_order = ? WHERE id = ?", index, profileId)
            } else {
                if (profileOwner(profileId) != null) {
                    throw AccountException(AccountError.INVALID_PROFILE, "Profile id $profileId belongs to another account.")
                }
                insertProfile(accountId, profileId, draft, sortOrder = if (deleteMissing) index else existing.size + created)
                created++
            }
            keep += profileId
        }
        val removed = if (deleteMissing) (existing - keep).toList() else emptyList()
        removed.forEach { profileId ->
            deleteSettings(SettingsScope.OfProfile(profileId))
            execute("DELETE FROM profiles WHERE id = ?", profileId)
        }
        val total = queryOne("SELECT COUNT(*) FROM profiles WHERE account_id = ?", accountId) { it.getInt(1) } ?: 0
        if (total > MAX_PROFILES_PER_ACCOUNT) {
            throw AccountException(AccountError.PROFILE_LIMIT_REACHED, "An account can have up to $MAX_PROFILES_PER_ACCOUNT profiles.")
        }
        execute(
            "UPDATE accounts SET last_profile_id = NULL WHERE id = ? AND last_profile_id IS NOT NULL " +
                "AND last_profile_id NOT IN (SELECT id FROM profiles WHERE account_id = ?)",
            accountId, accountId,
        )
        removed
    }

    // -------------------------------------------------------------------- PINs

    fun setPin(profileId: Long, pin: String, currentPin: String?): PinCheck {
        if (!isValidPin(pin)) return PinCheck(false, reason = PinFailure.INVALID_FORMAT)
        val row = pinRow(profileId) ?: return PinCheck(false, reason = PinFailure.PROFILE_NOT_FOUND)
        if (row.hash != null) {
            val check = verifyPin(profileId, currentPin.orEmpty())
            if (!check.unlocked) return check
        }
        database.execute(
            "UPDATE profiles SET pin_hash = ?, pin_failed_attempts = 0, pin_locked_until = NULL, updated_at = ? WHERE id = ?",
            pinHasher.hash(pin.toCharArray()), clock(), profileId,
        )
        return PinCheck(true)
    }

    fun clearPin(profileId: Long, currentPin: String?): PinCheck {
        val row = pinRow(profileId) ?: return PinCheck(false, reason = PinFailure.PROFILE_NOT_FOUND)
        if (row.hash != null) {
            val check = verifyPin(profileId, currentPin.orEmpty())
            if (!check.unlocked) return check
        }
        removePin(profileId)
        return PinCheck(true)
    }

    /** Removes a forgotten PIN after the account password was confirmed. */
    fun clearPinWithAccountPassword(accountId: String, profileId: Long, accountPassword: String): Boolean {
        if (profileOwner(profileId) != accountId) return false
        if (!verifyPassword(accountId, accountPassword)) return false
        removePin(profileId)
        return true
    }

    fun verifyPin(profileId: Long, pin: String): PinCheck {
        val now = clock()
        val row = pinRow(profileId) ?: return PinCheck(false, reason = PinFailure.PROFILE_NOT_FOUND)
        val hash = row.hash ?: return PinCheck(false, reason = PinFailure.NOT_SET)
        val lockedUntil = row.lockedUntil
        if (lockedUntil != null && lockedUntil > now) {
            return PinCheck(false, secondsUntil(lockedUntil, now), PinFailure.LOCKED)
        }
        if (isValidPin(pin) && pinHasher.verify(pin.toCharArray(), hash)) {
            database.execute("UPDATE profiles SET pin_failed_attempts = 0, pin_locked_until = NULL WHERE id = ?", profileId)
            return PinCheck(true)
        }
        val failures = row.failedAttempts + 1
        val lock = lockDuration(failures, PIN_FREE_ATTEMPTS, PIN_BASE_LOCK_MS, PIN_MAX_LOCK_MS)
        database.execute(
            "UPDATE profiles SET pin_failed_attempts = ?, pin_locked_until = ? WHERE id = ?",
            failures, lock?.let { now + it }, profileId,
        )
        return PinCheck(false, lock?.let { (it / 1000).toInt() } ?: 0, PinFailure.INCORRECT)
    }

    // ---------------------------------------------------------------- settings

    fun getSetting(scope: SettingsScope, key: String): String? = database.queryOne(
        "SELECT value FROM settings WHERE scope = ? AND scope_id = ? AND key = ?", scope.kind, scope.scopeId, key,
    ) { it.getString(1) }

    fun setSetting(scope: SettingsScope, key: String, value: String?) {
        require(key.isNotBlank() && key.length <= 200) { "Invalid settings key" }
        if (value == null) {
            database.execute("DELETE FROM settings WHERE scope = ? AND scope_id = ? AND key = ?", scope.kind, scope.scopeId, key)
        } else {
            database.execute(
                "INSERT INTO settings (scope, scope_id, key, value) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(scope, scope_id, key) DO UPDATE SET value = excluded.value",
                scope.kind, scope.scopeId, key, value,
            )
        }
    }

    fun allSettings(scope: SettingsScope): Map<String, String> = database.query(
        "SELECT key, value FROM settings WHERE scope = ? AND scope_id = ?", scope.kind, scope.scopeId,
    ) { it.getString(1) to it.getString(2) }.toMap()

    // ------------------------------------------------------------- maintenance

    /** Writes a snapshot of accounts.db now and returns its location. */
    fun backupNow(): Path = SqliteDatabases.backupNow(spec, database)

    fun listBackups(): List<Path> = SqliteDatabases.listBackups(spec)

    val databaseFile: Path get() = spec.file

    override fun close() = database.close()

    // ----------------------------------------------------------------- helpers

    private class LoginRow(val id: String, val passwordHash: String?, val failedLogins: Int, val lockedUntil: Long?)

    private class PinRow(val hash: String?, val failedAttempts: Int, val lockedUntil: Long?)

    private fun pinRow(profileId: Long): PinRow? = database.queryOne(
        "SELECT pin_hash, pin_failed_attempts, pin_locked_until FROM profiles WHERE id = ?", profileId,
    ) { rs -> PinRow(rs.getString("pin_hash"), rs.getInt("pin_failed_attempts"), rs.getNullableLong("pin_locked_until")) }

    private fun removePin(profileId: Long) {
        database.execute(
            "UPDATE profiles SET pin_hash = NULL, pin_failed_attempts = 0, pin_locked_until = NULL, updated_at = ? WHERE id = ?",
            clock(), profileId,
        )
    }

    private fun SqliteDatabase.writeSession(accountId: String, now: Long) {
        execute(
            "INSERT INTO device_session (id, account_id, updated_at) VALUES (1, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET account_id = excluded.account_id, updated_at = excluded.updated_at",
            accountId, now,
        )
    }

    private fun SqliteDatabase.loginExists(login: String): Boolean =
        queryOne("SELECT 1 FROM accounts WHERE login = ? COLLATE NOCASE", login) { true } == true

    private fun profileOwner(profileId: Long): String? =
        database.queryOne("SELECT account_id FROM profiles WHERE id = ?", profileId) { it.getString(1) }

    private fun SqliteDatabase.insertProfile(accountId: String, profileId: Long, draft: ProfileDraft, sortOrder: Int) {
        if (profileId < FIRST_PROFILE_ID) {
            throw AccountException(AccountError.INVALID_PROFILE, "Profile ids start at $FIRST_PROFILE_ID.")
        }
        val now = clock()
        execute(
            "INSERT INTO profiles (id, account_id, name, avatar_color_hex, avatar_id, avatar_url, background_id, background_url, " +
                "uses_primary_addons, uses_primary_plugins, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            profileId, accountId, validateProfileName(draft.name), validateColour(draft.avatarColorHex), draft.avatarId, draft.avatarUrl,
            draft.backgroundId, draft.backgroundUrl, draft.usesPrimaryAddons, draft.usesPrimaryPlugins, sortOrder, now, now,
        )
    }

    private fun SqliteDatabase.updateProfileRow(profileId: Long, draft: ProfileDraft) {
        execute(
            "UPDATE profiles SET name = ?, avatar_color_hex = ?, avatar_id = ?, avatar_url = ?, background_id = ?, background_url = ?, " +
                "uses_primary_addons = ?, uses_primary_plugins = ?, updated_at = ? WHERE id = ?",
            validateProfileName(draft.name), validateColour(draft.avatarColorHex), draft.avatarId, draft.avatarUrl,
            draft.backgroundId, draft.backgroundUrl, draft.usesPrimaryAddons, draft.usesPrimaryPlugins, clock(), profileId,
        )
    }

    private fun SqliteDatabase.deleteSettings(scope: SettingsScope) {
        execute("DELETE FROM settings WHERE scope = ? AND scope_id = ?", scope.kind, scope.scopeId)
    }

    private fun readAccount(rs: ResultSet): Account = Account(
        id = rs.getString("id"),
        login = rs.getString("login"),
        displayName = rs.getString("display_name"),
        isGuest = rs.getBooleanInt("is_guest"),
        hasPassword = rs.getString("password_hash") != null,
        createdAtEpochMillis = rs.getLong("created_at"),
        lastLoginAtEpochMillis = rs.getNullableLong("last_login_at"),
    )

    private fun readProfile(rs: ResultSet): Profile {
        val lockedUntil = rs.getNullableLong("pin_locked_until")
        return Profile(
            id = rs.getLong("id"),
            accountId = rs.getString("account_id"),
            name = rs.getString("name"),
            avatarColorHex = rs.getString("avatar_color_hex"),
            avatarId = rs.getString("avatar_id"),
            avatarUrl = rs.getString("avatar_url"),
            backgroundId = rs.getString("background_id"),
            backgroundUrl = rs.getString("background_url"),
            usesPrimaryAddons = rs.getBooleanInt("uses_primary_addons"),
            usesPrimaryPlugins = rs.getBooleanInt("uses_primary_plugins"),
            pinEnabled = rs.getString("pin_hash") != null,
            pinLockedUntilEpochMillis = lockedUntil?.takeIf { it > clock() },
            isDefault = rs.getBooleanInt("is_default"),
            sortOrder = rs.getInt("sort_order"),
            createdAtEpochMillis = rs.getLong("created_at"),
            updatedAtEpochMillis = rs.getLong("updated_at"),
        )
    }

    private fun validateLogin(login: String): String {
        val trimmed = login.trim()
        val valid = trimmed.length in MIN_LOGIN_LENGTH..MAX_LOGIN_LENGTH && trimmed.none { it.isWhitespace() || it.isISOControl() }
        if (!valid) {
            throw AccountException(AccountError.INVALID_LOGIN, "Enter an email address or a name of at least $MIN_LOGIN_LENGTH characters without spaces.")
        }
        return trimmed
    }

    private fun validatePassword(password: String) {
        if (password.length < MIN_PASSWORD_LENGTH || password.length > MAX_PASSWORD_LENGTH) {
            throw AccountException(AccountError.WEAK_PASSWORD, "Use a password of at least $MIN_PASSWORD_LENGTH characters.")
        }
    }

    private fun validateProfileName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) {
            throw AccountException(AccountError.INVALID_PROFILE, "Enter a profile name of up to $MAX_NAME_LENGTH characters.")
        }
        return trimmed
    }

    private fun validateColour(hex: String): String =
        if (COLOUR.matches(hex)) hex.uppercase() else DEFAULT_COLOUR

    private fun resolveDisplayName(displayName: String?, login: String): String =
        displayName?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME_LENGTH)
            ?: login.substringBefore('@').ifBlank { login }.take(MAX_NAME_LENGTH)

    private fun invalidCredentials() = AccountException(AccountError.INVALID_CREDENTIALS, "The sign-in name or password is incorrect.")

    private fun accountNotFound() = AccountException(AccountError.ACCOUNT_NOT_FOUND, "Account not found.")

    private fun secondsUntil(until: Long, now: Long): Int = (((until - now) + 999) / 1000).toInt().coerceAtLeast(1)

    /** No lock for the first [freeAttempts]; then [base], doubling per further failure up to [max]. */
    private fun lockDuration(failures: Int, freeAttempts: Int, base: Long, max: Long): Long? {
        if (failures < freeAttempts) return null
        val doublings = (failures - freeAttempts).coerceAtMost(20)
        return (base shl doublings).coerceIn(base, max)
    }

    companion object {
        const val DATABASE_NAME = "accounts"

        /** Local profile ids start here so they can never be confused with a sync server's small indices. */
        const val FIRST_PROFILE_ID = 1001L
        const val MAX_PROFILES_PER_ACCOUNT = 6
        const val MIN_PASSWORD_LENGTH = 6
        private const val MAX_PASSWORD_LENGTH = 1024
        private const val MIN_LOGIN_LENGTH = 3
        private const val MAX_LOGIN_LENGTH = 254
        private const val MAX_NAME_LENGTH = 40
        private const val DEFAULT_COLOUR = "#1E88E5"
        private val COLOUR = Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")

        private const val LOGIN_FREE_ATTEMPTS = 5
        private const val LOGIN_BASE_LOCK_MS = 30_000L
        private const val LOGIN_MAX_LOCK_MS = 15 * 60_000L
        private const val PIN_FREE_ATTEMPTS = 5
        private const val PIN_BASE_LOCK_MS = 60_000L
        private const val PIN_MAX_LOCK_MS = 60 * 60_000L

        /** A syntactically valid hash that matches no password; used to equalise sign-in timing. */
        private const val DUMMY_HASH = "argon2id\$v=19\$m=65536,t=3,p=1\$AAAAAAAAAAAAAAAAAAAAAA\$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        fun isValidPin(pin: String): Boolean = pin.length in 4..8 && pin.all { it in '0'..'9' }

        val MIGRATIONS: List<Migration> = listOf(
            Migration(
                1, "accounts, profiles, device session, settings",
                """
                CREATE TABLE accounts (
                    id TEXT PRIMARY KEY NOT NULL,
                    login TEXT COLLATE NOCASE,
                    display_name TEXT NOT NULL,
                    password_hash TEXT,
                    is_guest INTEGER NOT NULL DEFAULT 0,
                    failed_logins INTEGER NOT NULL DEFAULT 0,
                    locked_until INTEGER,
                    last_profile_id INTEGER,
                    has_ever_selected_profile INTEGER NOT NULL DEFAULT 0,
                    remember_last_profile INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    last_login_at INTEGER
                )
                """.trimIndent(),
                "CREATE UNIQUE INDEX accounts_login_unique ON accounts (login COLLATE NOCASE) WHERE login IS NOT NULL",
                """
                CREATE TABLE profiles (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    account_id TEXT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
                    name TEXT NOT NULL,
                    avatar_color_hex TEXT NOT NULL,
                    avatar_id TEXT,
                    avatar_url TEXT,
                    background_id TEXT,
                    background_url TEXT,
                    uses_primary_addons INTEGER NOT NULL DEFAULT 0,
                    uses_primary_plugins INTEGER NOT NULL DEFAULT 0,
                    pin_hash TEXT,
                    pin_failed_attempts INTEGER NOT NULL DEFAULT 0,
                    pin_locked_until INTEGER,
                    is_default INTEGER NOT NULL DEFAULT 0,
                    sort_order INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
                "CREATE INDEX profiles_by_account ON profiles (account_id, sort_order, id)",
                "INSERT INTO sqlite_sequence (name, seq) VALUES ('profiles', ${FIRST_PROFILE_ID - 1})",
                """
                CREATE TABLE device_session (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    account_id TEXT NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
                """
                CREATE TABLE settings (
                    scope TEXT NOT NULL,
                    scope_id TEXT NOT NULL,
                    key TEXT NOT NULL,
                    value TEXT NOT NULL,
                    PRIMARY KEY (scope, scope_id, key)
                )
                """.trimIndent(),
            ),
        )

        fun spec(paths: AppPaths): DatabaseSpec = DatabaseSpec(
            name = DATABASE_NAME,
            file = paths.accountsDir.resolve("accounts.db"),
            migrations = MIGRATIONS,
            backupDir = paths.backupDirFor(DATABASE_NAME),
        )

        fun open(
            paths: AppPaths,
            passwordHasher: PasswordHasher = PasswordHasher(),
            pinHasher: PasswordHasher = PasswordHasher.forPins(),
            clock: () -> Long = System::currentTimeMillis,
        ): AccountsDatabase {
            val spec = spec(paths)
            val opened = SqliteDatabases.open(spec)
            return AccountsDatabase(opened.database, spec, opened.outcome, passwordHasher, pinHasher, clock)
        }
    }
}
