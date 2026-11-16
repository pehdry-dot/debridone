package com.nuvio.app.core.auth

import com.nuvio.app.core.network.ServerConfigurationRepository

/**
 * DebridOne seam: accounts and profiles stored on this device.
 *
 * When a backend is installed (desktop does this at start-up) and no sync server is configured,
 * sign-up, sign-in, guest access, profiles and profile PINs are all served locally and Supabase is
 * never contacted. Connecting a sync server switches back to upstream's remote behaviour.
 */
interface LocalAccountBackend {
    /** The account that stayed signed in on this device, if any. */
    fun restoreSession(): LocalAccountSession?

    /** Creates an account and signs it in. Throws [LocalAccountException] with a user-facing message. */
    fun signUp(login: String, password: String): LocalAccountSession

    /** Throws [LocalAccountException] with a user-facing message. */
    fun signIn(login: String, password: String): LocalAccountSession

    fun signInGuest(): LocalAccountSession

    /** Ends the session. Must not delete anything the account owns. */
    fun signOut()

    /** Reserves a profile index that is unique on this device and never reused. */
    fun reserveProfileIndex(): Int

    /** Deletes a profile of the signed-in account together with the data it owned. */
    fun deleteProfile(profileIndex: Int)

    fun verifyPin(profileIndex: Int, pin: String): LocalPinResult

    fun setPin(profileIndex: Int, pin: String, currentPin: String?): LocalPinResult

    fun clearPin(profileIndex: Int, currentPin: String?): LocalPinResult

    fun clearPinWithAccountPassword(profileIndex: Int, accountPassword: String): Boolean
}

data class LocalAccountSession(
    val userId: String,
    /** Sign-in name, shown where upstream shows the e-mail address. Null for the guest account. */
    val login: String?,
    val isGuest: Boolean,
)

data class LocalPinResult(
    val unlocked: Boolean,
    val retryAfterSeconds: Int = 0,
    val message: String? = null,
)

class LocalAccountException(message: String) : Exception(message)

object LocalAccounts {
    @Volatile
    var backend: LocalAccountBackend? = null

    /** The backend to use right now, or null when a sync server is configured (remote mode). */
    val active: LocalAccountBackend?
        get() = backend?.takeIf { ServerConfigurationRepository.active.value.backendUrl.isBlank() }

    val isActive: Boolean get() = active != null
}

/**
 * Local accounts never sync, so they reuse upstream's "anonymous" code paths everywhere remote
 * calls are skipped. [AuthState.Authenticated.email] carries the sign-in name for display.
 */
internal fun LocalAccountSession.toAuthState(): AuthState.Authenticated = AuthState.Authenticated(
    userId = userId,
    email = login,
    isAnonymous = true,
)
