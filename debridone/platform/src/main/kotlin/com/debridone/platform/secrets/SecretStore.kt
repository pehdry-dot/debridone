package com.debridone.platform.secrets

import com.debridone.platform.AppPaths
import java.util.Locale

/**
 * Addresses one secret. [namespace] says who owns it, for example `module.music.profile.1001`,
 * so a module can only name secrets inside its own namespace (see [ScopedSecretStore]).
 */
data class SecretRef(val namespace: String, val name: String) {
    init {
        require(NAMESPACE.matches(namespace)) { "Invalid secret namespace: '$namespace'" }
        require(name.isNotBlank() && name.length <= 128 && name.none { it.isISOControl() }) { "Invalid secret name" }
    }

    private companion object {
        val NAMESPACE = Regex("[a-z0-9]+(\\.[a-z0-9_-]+)*")
    }
}

/**
 * Storage for credentials and keys. On macOS this is the login Keychain; values never touch
 * DebridOne's own files there.
 */
interface SecretStore {
    /** Human-readable backend name for diagnostics and the settings screen. */
    val backendName: String

    /** True when the backend is an OS-protected credential store. */
    val isOsProtected: Boolean

    fun get(ref: SecretRef): String?

    fun put(ref: SecretRef, value: String)

    /** Returns true when a secret was removed. */
    fun delete(ref: SecretRef): Boolean
}

class SecretStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A view of a [SecretStore] restricted to one namespace prefix. Modules receive one of these, so
 * a plugin credential stored by Music is not addressable from Gaming, and vice versa.
 */
class ScopedSecretStore(
    private val delegate: SecretStore,
    private val namespace: String,
) {
    val backendName: String get() = delegate.backendName
    val isOsProtected: Boolean get() = delegate.isOsProtected

    fun get(name: String): String? = delegate.get(SecretRef(namespace, name))

    fun put(name: String, value: String) = delegate.put(SecretRef(namespace, name), value)

    fun delete(name: String): Boolean = delegate.delete(SecretRef(namespace, name))

    /** A narrower view, for example per profile: `module.music` -> `module.music.profile.1001`. */
    fun child(segment: String): ScopedSecretStore = ScopedSecretStore(delegate, "$namespace.$segment")
}

object SecretStores {
    /**
     * The platform secret store: the macOS Keychain on macOS, otherwise an encrypted file under
     * the app data directory. The file fallback exists so development builds work on other
     * systems; it is not equivalent to an OS credential store and reports [SecretStore.isOsProtected] = false.
     */
    fun forPlatform(
        paths: AppPaths,
        osName: String = System.getProperty("os.name").orEmpty(),
    ): SecretStore =
        if (osName.lowercase(Locale.ROOT).contains("mac")) {
            MacKeychainSecretStore()
        } else {
            FileSecretStore(paths.secretsDir)
        }
}
