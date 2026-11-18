package com.debridone.cloud

import com.debridone.cloud.CloudException.Reason
import com.debridone.platform.secrets.ScopedSecretStore
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The cloud accounts the user connected, and the only way to their credentials.
 *
 * An account is either shared with every profile or belongs to one profile; every use of an
 * account names the profile that asks and is refused when that profile may not use it. Credentials
 * live in the system's credential store (the macOS Keychain) under `app.cloud`, one entry per
 * account; the database holds what may be shown (user name, plan, expiry) and nothing else.
 */
class DebridAccountManager internal constructor(
    private val store: CloudStore,
    private val secrets: ScopedSecretStore,
    val providers: List<CloudProvider>,
    private val clock: () -> Long,
) {
    private val _accounts = MutableStateFlow(store.accounts())

    /** Every connected account, of every profile. Use [visibleTo] for what one profile may see. */
    val accounts: StateFlow<List<CloudAccount>> = _accounts.asStateFlow()

    /** Where credentials are kept ("macOS Keychain"), for display, and whether the system protects it. */
    val credentialStoreName: String get() = secrets.backendName
    val credentialStoreIsProtected: Boolean get() = secrets.isOsProtected

    fun provider(id: ProviderId): CloudProvider = providers.first { it.id == id }

    fun visibleTo(profileId: Long?): List<CloudAccount> = _accounts.value.filter { it.visibleTo(profileId) }

    fun account(profileId: Long?, accountId: Long): CloudAccount? = _accounts.value.firstOrNull { it.id == accountId && it.visibleTo(profileId) }

    // ---------------------------------------------------------------- connecting

    /** Starts a sign-in with a code. Nothing is saved yet. */
    fun beginSignIn(provider: ProviderId): DeviceSignIn = provider(provider).beginSignIn()

    /** Asks whether the code was allowed: null while it is not; then the account is connected. */
    fun finishSignIn(signIn: DeviceSignIn, profileId: Long?, shared: Boolean): CloudAccount? {
        val provider = provider(signIn.provider)
        val credentials = provider.pollSignIn(signIn) ?: return null
        return connect(provider, credentials, "code", profileId, shared)
    }

    /** Connects with a key the user pasted. It is checked against the provider before anything is saved. */
    fun connectWithKey(provider: ProviderId, key: String, profileId: Long?, shared: Boolean): CloudAccount {
        val client = provider(provider)
        return connect(client, client.credentialsFromKey(key), "key", profileId, shared)
    }

    private fun connect(provider: CloudProvider, credentials: CloudCredentials, authKind: String, profileId: Long?, shared: Boolean): CloudAccount {
        if (!shared && profileId == null) throw CloudException(Reason.NOT_ALLOWED, "Choose a profile first.")
        val info = try {
            provider.account(credentials)
        } catch (e: CloudException) {
            if (e.reason == Reason.AUTH_EXPIRED) throw CloudException(Reason.AUTH_EXPIRED, "${provider.id.displayName} does not accept this ${if (authKind == "key") provider.keyName else "sign-in"}.")
            throw e
        }
        val now = clock()
        val existing = _accounts.value.firstOrNull { it.provider == provider.id && it.remoteUserId == info.remoteUserId && it.visibleTo(profileId) }
        if (existing != null) {
            // Signing in again to an account whose sign-in had expired renews it in place: its
            // downloads and history stay. A second connection of a working account is refused.
            if (existing.status == AccountStatus.CONNECTED) {
                throw CloudException(Reason.NOT_ALLOWED, "This ${provider.id.displayName} account is already connected" + if (existing.shared) " for all profiles." else " for this profile.")
            }
            saveSecret(existing.id, credentials)
            store.updateAccountInfo(existing.id, info, now)
            publish()
            return checkNotNull(store.account(existing.id))
        }
        val id = store.insertAccount(provider.id, info, authKind, if (shared) null else profileId, now)
        try {
            saveSecret(id, credentials)
        } catch (t: Throwable) {
            store.deleteAccount(id)
            throw CloudException(Reason.PROTOCOL, "The sign-in could not be saved securely.")
        }
        publish()
        return checkNotNull(store.account(id))
    }

    // ---------------------------------------------------------------- managing

    /** Asks the provider for the account's details again (plan, expiry) and records whether it still answers. */
    fun refresh(profileId: Long?, accountId: Long): CloudAccount {
        try {
            val info = use(profileId, accountId) { provider, credentials -> provider.account(credentials) }
            store.updateAccountInfo(accountId, info, clock())
        } catch (e: CloudException) {
            if (e.reason != Reason.AUTH_EXPIRED && e.reason != Reason.NOT_ALLOWED) store.setAccountStatus(accountId, AccountStatus.ERROR, e.message, clock())
            publish()
            throw e
        }
        publish()
        return checkNotNull(store.account(accountId))
    }

    /** Shares an account with every profile, or keeps it to [profileId] alone. */
    fun setShared(profileId: Long?, accountId: Long, shared: Boolean) {
        requireVisible(profileId, accountId)
        if (!shared && profileId == null) throw CloudException(Reason.NOT_ALLOWED, "Choose a profile first.")
        store.setAccountScope(accountId, if (shared) null else profileId, clock())
        publish()
    }

    /**
     * Forgets an account: its credentials are erased from the credential store and what was cached
     * about it is dropped. Nothing changes at the provider, and files already downloaded stay.
     */
    fun disconnect(profileId: Long?, accountId: Long) {
        requireVisible(profileId, accountId)
        remove(accountId)
    }

    /** A profile was deleted: the accounts that belonged to it alone go with it. */
    fun deleteProfile(profileId: Long) {
        _accounts.value.filter { it.scopeProfileId == profileId }.forEach { remove(it.id) }
    }

    private fun remove(accountId: Long) {
        runCatching { secrets.delete(secretName(accountId)) }
        store.deleteAccount(accountId)
        locks.remove(accountId)
        publish()
    }

    // ---------------------------------------------------------------- using an account

    private val locks = ConcurrentHashMap<Long, Any>()

    /**
     * Runs [block] with the provider and the credentials of [accountId], on behalf of [profileId].
     * Refused when that profile may not use the account. Credentials that are about to expire are
     * renewed first (one renewal at a time per account); an account whose sign-in the provider no
     * longer accepts is marked so, and the failure is passed on.
     */
    internal fun <T> use(profileId: Long?, accountId: Long, block: (CloudProvider, CloudCredentials) -> T): T {
        val account = requireVisible(profileId, accountId)
        val provider = provider(account.provider)
        try {
            val credentials = synchronized(locks.computeIfAbsent(accountId) { Any() }) {
                val stored = runCatching { secrets.get(secretName(accountId)) }.getOrNull()
                    ?: throw CloudException(Reason.AUTH_EXPIRED, "The saved sign-in for ${account.provider.displayName} is missing. Sign in again.")
                val current = CloudCredentials(stored, "account-$accountId")
                provider.renew(current)?.also { runCatching { secrets.put(secretName(accountId), it.secret) } }?.let { CloudCredentials(it.secret, "account-$accountId") } ?: current
            }
            val result = block(provider, credentials)
            if (account.status != AccountStatus.CONNECTED) {
                store.setAccountStatus(accountId, AccountStatus.CONNECTED, null, clock())
                publish()
            }
            return result
        } catch (e: CloudException) {
            if (e.reason == Reason.AUTH_EXPIRED) {
                store.setAccountStatus(accountId, AccountStatus.NEEDS_SIGN_IN, e.message, clock())
                publish()
            }
            throw e
        }
    }

    private fun requireVisible(profileId: Long?, accountId: Long): CloudAccount {
        val account = store.account(accountId) ?: throw CloudException(Reason.NOT_FOUND, "That account is no longer connected.")
        if (!account.visibleTo(profileId)) throw CloudException(Reason.NOT_ALLOWED, "This profile may not use that ${account.provider.displayName} account.")
        return account
    }

    private fun saveSecret(accountId: Long, credentials: CloudCredentials) = secrets.put(secretName(accountId), credentials.secret)

    private fun secretName(accountId: Long) = "account-$accountId"

    internal fun publish() {
        _accounts.value = store.accounts()
    }
}
