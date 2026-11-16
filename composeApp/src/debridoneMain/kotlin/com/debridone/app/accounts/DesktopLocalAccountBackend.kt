package com.debridone.app.accounts

import com.debridone.accounts.Account
import com.debridone.accounts.AccountException
import com.debridone.accounts.AccountsDatabase
import com.debridone.accounts.PinCheck
import com.debridone.accounts.PinFailure
import com.debridone.accounts.ProfileDraft
import com.nuvio.app.core.auth.LocalAccountBackend
import com.nuvio.app.core.auth.LocalAccountException
import com.nuvio.app.core.auth.LocalAccountSession
import com.nuvio.app.core.auth.LocalPinResult

/**
 * Serves Nuvio's sign-in and profile screens from `accounts.db`.
 *
 * [onProfileDeleted] is invoked for every profile that is removed so each module can erase the
 * data that profile owned.
 */
internal class DesktopLocalAccountBackend(
    private val accounts: AccountsDatabase,
    private val onProfileDeleted: (profileId: Long) -> Unit,
) : LocalAccountBackend {

    override fun restoreSession(): LocalAccountSession? {
        val session = accounts.currentSession() ?: return null
        return accounts.findAccount(session.accountId)?.also(::ensureFirstProfile)?.toSession()
    }

    override fun signUp(login: String, password: String): LocalAccountSession =
        translate { accounts.createAccount(login, password).also(::ensureFirstProfile).toSession() }

    override fun signIn(login: String, password: String): LocalAccountSession =
        translate { accounts.signIn(login, password).also(::ensureFirstProfile).toSession() }

    override fun signInGuest(): LocalAccountSession =
        translate { accounts.signInGuest().also(::ensureFirstProfile).toSession() }

    /**
     * An account always has at least one profile, named after the account, so signing in leads
     * straight into the app. More profiles are added from the profile chooser.
     */
    private fun ensureFirstProfile(account: Account) {
        if (accounts.listProfiles(account.id).isEmpty()) {
            accounts.createProfile(account.id, ProfileDraft(id = null, name = account.displayName))
        }
    }

    override fun signOut() = accounts.signOut()

    override fun reserveProfileIndex(): Int = Math.toIntExact(accounts.reserveProfileId())

    override fun deleteProfile(profileIndex: Int) {
        val accountId = accounts.currentSession()?.accountId ?: return
        if (accounts.deleteProfile(accountId, profileIndex.toLong())) {
            onProfileDeleted(profileIndex.toLong())
        }
    }

    override fun verifyPin(profileIndex: Int, pin: String): LocalPinResult =
        accounts.verifyPin(profileIndex.toLong(), pin).toResult()

    override fun setPin(profileIndex: Int, pin: String, currentPin: String?): LocalPinResult =
        accounts.setPin(profileIndex.toLong(), pin, currentPin).toResult()

    override fun clearPin(profileIndex: Int, currentPin: String?): LocalPinResult =
        accounts.clearPin(profileIndex.toLong(), currentPin).toResult()

    override fun clearPinWithAccountPassword(profileIndex: Int, accountPassword: String): Boolean {
        val accountId = accounts.currentSession()?.accountId ?: return false
        return accounts.clearPinWithAccountPassword(accountId, profileIndex.toLong(), accountPassword)
    }

    private fun Account.toSession() = LocalAccountSession(userId = id, login = login, isGuest = isGuest)

    private inline fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (e: AccountException) {
            throw LocalAccountException(e.message ?: "The account operation failed.")
        }

    private fun PinCheck.toResult(): LocalPinResult = LocalPinResult(
        unlocked = unlocked,
        retryAfterSeconds = retryAfterSeconds,
        message = when (reason) {
            null -> null
            PinFailure.INCORRECT ->
                if (retryAfterSeconds > 0) "Incorrect PIN. Try again in ${formatWait(retryAfterSeconds)}." else "Incorrect PIN."
            PinFailure.LOCKED -> "Too many attempts. Try again in ${formatWait(retryAfterSeconds)}."
            PinFailure.NOT_SET -> "This profile has no PIN."
            PinFailure.INVALID_FORMAT -> "A PIN is 4 to 8 digits."
            PinFailure.PROFILE_NOT_FOUND -> "Profile not found."
        },
    )

    private fun formatWait(seconds: Int): String =
        if (seconds >= 120) "${(seconds + 59) / 60} minutes" else "$seconds seconds"
}
