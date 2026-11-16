package com.debridone.accounts

/** A DebridOne account that lives on this device. */
data class Account(
    val id: String,
    /** Sign-in name. An email address is conventional but not required. Null for the guest account. */
    val login: String?,
    val displayName: String,
    val isGuest: Boolean,
    val hasPassword: Boolean,
    val createdAtEpochMillis: Long,
    val lastLoginAtEpochMillis: Long?,
)

/** One profile inside an account. [id] is unique across ALL accounts on this device. */
data class Profile(
    val id: Long,
    val accountId: String,
    val name: String,
    val avatarColorHex: String,
    val avatarId: String?,
    val avatarUrl: String?,
    val backgroundId: String?,
    val backgroundUrl: String?,
    val usesPrimaryAddons: Boolean,
    val usesPrimaryPlugins: Boolean,
    val pinEnabled: Boolean,
    val pinLockedUntilEpochMillis: Long?,
    val isDefault: Boolean,
    val sortOrder: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/** Editable profile fields. [id] = null creates a new profile. */
data class ProfileDraft(
    val id: Long?,
    val name: String,
    val avatarColorHex: String = "#1E88E5",
    val avatarId: String? = null,
    val avatarUrl: String? = null,
    val backgroundId: String? = null,
    val backgroundUrl: String? = null,
    val usesPrimaryAddons: Boolean = false,
    val usesPrimaryPlugins: Boolean = false,
)

/** The signed-in state remembered across restarts. One per device. */
data class DeviceSession(
    val accountId: String,
    val activeProfileId: Long?,
    val hasEverSelectedProfile: Boolean,
    val rememberLastProfile: Boolean,
)

data class PinCheck(
    val unlocked: Boolean,
    /** Seconds until another attempt is allowed; 0 when not locked. */
    val retryAfterSeconds: Int = 0,
    val reason: PinFailure? = null,
)

enum class PinFailure { INCORRECT, LOCKED, NOT_SET, INVALID_FORMAT, PROFILE_NOT_FOUND }

enum class AccountError {
    INVALID_LOGIN,
    WEAK_PASSWORD,
    LOGIN_TAKEN,
    INVALID_CREDENTIALS,
    TOO_MANY_ATTEMPTS,
    ACCOUNT_NOT_FOUND,
    PROFILE_LIMIT_REACHED,
    PROFILE_NOT_FOUND,
    INVALID_PROFILE,
    LAST_PROFILE,
}

class AccountException(val error: AccountError, message: String, val retryAfterSeconds: Int = 0) : RuntimeException(message)

/** Where a setting applies. Profile settings are removed with their profile. */
sealed interface SettingsScope {
    val kind: String
    val scopeId: String

    data object Global : SettingsScope {
        override val kind = "global"
        override val scopeId = ""
    }

    data class OfAccount(val accountId: String) : SettingsScope {
        override val kind = "account"
        override val scopeId = accountId
    }

    data class OfProfile(val profileId: Long) : SettingsScope {
        override val kind = "profile"
        override val scopeId = profileId.toString()
    }
}

/**
 * Optional cross-device synchronisation. DebridOne ships with NO implementation: accounts are
 * local-only until a backend is configured, and the UI says so through [SyncStatus.LocalOnly].
 * This interface is the seam a real backend plugs into; it must never be reported as active
 * unless [status] says [SyncStatus.Connected].
 */
interface SyncBackend {
    val displayName: String
    val status: SyncStatus
}

sealed interface SyncStatus {
    /** Nothing leaves this device. */
    data object LocalOnly : SyncStatus

    data class Connected(val server: String) : SyncStatus

    data class Error(val message: String) : SyncStatus
}
