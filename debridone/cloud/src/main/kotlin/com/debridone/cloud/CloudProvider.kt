package com.debridone.cloud

/**
 * One cloud service as DebridOne uses it: the signed-in user's **own** account, read through the
 * service's public API. A provider lists what already exists in the account, hands out download
 * addresses for it, and can remove an entry when the user explicitly asks. It has no way to add
 * or search for content, and none may be given one (an architecture test checks the sources).
 *
 * Providers hold no account state: credentials are passed in, and come from the secret store.
 */
interface CloudProvider {
    val id: ProviderId

    /** The kinds of entry an account of this service has, in display order. */
    val kinds: List<ItemKind>

    /** True when the user can sign in with a code on the service's own page. */
    val supportsCodeSignIn: Boolean

    /** What the service calls the key a user can paste instead ("API token"), and where they find it. */
    val keyName: String
    val keyHelp: String

    /** The kinds of entry that can be removed from the account through the API. */
    val deletableKinds: Set<ItemKind>

    /** Where the API is. Not the real service only when a developer points DebridOne at a test server. */
    val address: String
    val isTestServer: Boolean

    /** Step one of a sign-in with a code. */
    fun beginSignIn(): DeviceSignIn

    /** Step two, repeated: null while the user has not allowed it yet. */
    fun pollSignIn(signIn: DeviceSignIn): CloudCredentials?

    /** Credentials from a key the user pasted. Checked for shape only; [account] proves them. */
    fun credentialsFromKey(key: String): CloudCredentials

    /** For sign-ins that expire: the credentials to keep in place of [credentials], else null. */
    fun renew(credentials: CloudCredentials): CloudCredentials?

    fun account(credentials: CloudCredentials): AccountInfo

    /** Every entry of one kind. [fresh] asks the service to skip its own cache where it has one. */
    fun list(credentials: CloudCredentials, kind: ItemKind, fresh: Boolean = false): List<CloudItem>

    /** The files of an entry whose listing did not include them ([CloudItem.files] was null). */
    fun files(credentials: CloudCredentials, item: CloudItem): List<CloudFile>

    /**
     * A download address for one file, made now. [fresh] insists on a new one, for when the last
     * one turned out to have expired.
     */
    fun resolve(credentials: CloudCredentials, kind: ItemKind, itemId: String, fileRef: String, fresh: Boolean = false): ResolvedLink

    /** Removes an entry from the account, permanently. Only ever called after the user confirmed it. */
    fun delete(credentials: CloudCredentials, kind: ItemKind, itemId: String)
}

/**
 * Where the providers' APIs are. Always the real services, except when a developer sets
 * `DEBRIDONE_REAL_DEBRID_API` / `DEBRIDONE_TORBOX_API` to a test server **on this same computer**;
 * any other value is ignored, so the variables cannot be used to send a sign-in elsewhere.
 */
data class CloudEndpoints(val realDebrid: String = RealDebridClient.DEFAULT_BASE_URL, val torBox: String = TorBoxClient.DEFAULT_BASE_URL) {
    companion object {
        private val LOOPBACK = Regex("^http://(127\\.0\\.0\\.1|localhost):\\d{1,5}$")

        fun fromEnvironment(environment: (String) -> String? = System::getenv): CloudEndpoints {
            fun local(name: String): String? = environment(name)?.trim()?.trimEnd('/')?.takeIf { LOOPBACK.matches(it) }
            return CloudEndpoints(
                realDebrid = local("DEBRIDONE_REAL_DEBRID_API") ?: RealDebridClient.DEFAULT_BASE_URL,
                torBox = local("DEBRIDONE_TORBOX_API") ?: TorBoxClient.DEFAULT_BASE_URL,
            )
        }
    }
}
