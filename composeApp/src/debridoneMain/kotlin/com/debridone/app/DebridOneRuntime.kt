package com.debridone.app

import co.touchlab.kermit.Logger
import com.debridone.accounts.AccountsDatabase
import com.debridone.cloud.CloudServices
import com.debridone.app.cloud.CloudAutomation
import com.debridone.app.cloud.MusicCloudSource
import com.debridone.accounts.SettingsScope
import com.debridone.app.accounts.AvatarImages
import com.debridone.app.accounts.DesktopLocalAccountBackend
import com.debridone.app.accounts.LocalProfilePayloads
import com.debridone.app.modules.ModuleHost
import com.debridone.app.shell.DebridOneShell
import com.debridone.platform.AppPaths
import com.debridone.platform.db.DatabaseNewerThanAppException
import com.debridone.platform.db.OpenOutcome
import com.debridone.platform.secrets.SecretStore
import com.debridone.platform.secrets.SecretStores
import com.nuvio.app.AppShellHost
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.LocalAccounts
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.profiles.LocalAvatarImporter
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Process-wide DebridOne services, started once from the desktop entry point before Nuvio reads
 * any profile data.
 *
 * Ownership: `accounts.db` belongs to the shell (identity only), each module engine owns its own
 * database through [ModuleHost], and Nuvio keeps its own stores as the Streaming module.
 */
object DebridOneRuntime {
    private val log = Logger.withTag("DebridOne")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val paths: AppPaths by lazy { AppPaths.system() }
    val secrets: SecretStore by lazy { SecretStores.forPlatform(paths) }

    /** Set when start-up failed in a way that makes the app unable to keep data. */
    @Volatile
    var startupError: String? = null
        private set

    @Volatile
    private var accountsDb: AccountsDatabase? = null

    @Volatile
    private var moduleHost: ModuleHost? = null

    @Volatile
    private var profilePayloads: LocalProfilePayloads? = null

    /** Something the user should know about how storage was opened (e.g. restored from a backup). */
    private val _storageNotice = MutableStateFlow<String?>(null)
    val storageNotice: StateFlow<String?> = _storageNotice.asStateFlow()

    private val _activeProfileId = MutableStateFlow<Long?>(null)

    /** The profile in use, or null while signed out / choosing a profile. */
    val activeProfileId: StateFlow<Long?> = _activeProfileId.asStateFlow()

    @Volatile
    private var cloudServices: CloudServices? = null

    /** Why the cloud layer could not be opened, when it could not. */
    @Volatile
    var cloudError: String? = null
        private set

    /**
     * The connected cloud accounts, their listings and the download manager; null only when their
     * database could not be opened (the rest of the application still works).
     */
    val cloud: CloudServices? get() = cloudServices

    val accounts: AccountsDatabase get() = checkNotNull(accountsDb) { "DebridOne is not started" }
    val modules: ModuleHost get() = checkNotNull(moduleHost) { "DebridOne is not started" }

    @Synchronized
    fun start() {
        if (accountsDb != null || startupError != null) return
        try {
            val database = AccountsDatabase.open(paths)
            accountsDb = database
            when (database.openOutcome) {
                OpenOutcome.RESTORED_FROM_BACKUP -> _storageNotice.value =
                    "Your accounts database was damaged and has been restored from the latest backup. " +
                    "Changes made after that backup could not be recovered."
                OpenOutcome.RECREATED_AFTER_CORRUPTION -> _storageNotice.value =
                    "Your accounts database was damaged and no backup was available, so a new one was created. " +
                    "The damaged file was kept in the backups folder."
                else -> Unit
            }

            profilePayloads = LocalProfilePayloads(database)
            LocalAccounts.backend = DesktopLocalAccountBackend(database, ::eraseProfileData)
            LocalAvatarImporter.pickImage = { AvatarImages.pickAndImport(paths) }

            // The connected accounts are the application's own: opened beside the sections, never inside one.
            try {
                cloudServices = CloudServices.open(paths, secrets)
            } catch (t: Throwable) {
                log.e(t) { "The cloud layer could not be opened" }
                cloudError = t.message ?: t::class.simpleName
            }
            // Music is built with its one narrow view of them (audio files, by name); the other sections are reached file by file.
            val cloudMusic = cloudServices?.let(::MusicCloudSource)
            val host = ModuleHost(paths, secrets, cloudMusic)
            moduleHost = host
            host.start()
            cloudServices?.let { CloudAutomation.start(it, cloudMusic) }
            // Quitting the app saves each section's state (Music: queue and position), stops playback
            // and leaves unfinished downloads to continue at the next start.
            Runtime.getRuntime().addShutdownHook(Thread({ runCatching { host.close() }; runCatching { cloudServices?.close() } }, "debridone-shutdown"))

            AppShellHost.shell = { streaming, onSwitchProfile -> DebridOneShell(streaming, onSwitchProfile) }
            observeActiveProfile(host)
            scope.launch { runCatching { AvatarImages.sweepUnused(paths, database) } }
        } catch (e: DatabaseNewerThanAppException) {
            log.e(e) { "accounts.db is from a newer build" }
            startupError = "Your DebridOne data was created by a newer version of the app. " +
                "Install the latest DebridOne to continue. Nothing was changed."
        } catch (t: Throwable) {
            log.e(t) { "DebridOne start-up failed" }
            startupError = "DebridOne could not open its data folder (${paths.dataRoot}).\n\n${t.message ?: t::class.simpleName}"
        }
    }

    internal fun loadProfilePayload(): String? =
        runCatching { profilePayloads?.load() }
            .onFailure { e -> log.e(e) { "Could not read profiles" } }
            .getOrNull()

    internal fun saveProfilePayload(payload: String) {
        runCatching { profilePayloads?.save(payload) }
            .onFailure { e -> log.e(e) { "Could not save profiles" } }
    }

    /** Reads a shell preference of the active profile, falling back to the device-wide value. */
    fun shellSetting(key: String): String? {
        val database = accountsDb ?: return null
        return runCatching {
            _activeProfileId.value?.let { database.getSetting(SettingsScope.OfProfile(it), key) }
                ?: database.getSetting(SettingsScope.Global, key)
        }.getOrNull()
    }

    /** Stores a shell preference for the active profile and as the device-wide fallback. */
    fun setShellSetting(key: String, value: String?) {
        val database = accountsDb ?: return
        runCatching {
            _activeProfileId.value?.let { database.setSetting(SettingsScope.OfProfile(it), key, value) }
            database.setSetting(SettingsScope.Global, key, value)
        }.onFailure { e -> log.e(e) { "Could not save setting $key" } }
    }

    /**
     * Permanently deletes the signed-in on-device account, its profiles and everything they own
     * in every module. Returns null on success or a message to show.
     */
    fun deleteCurrentAccount(password: String?): String? {
        val database = accountsDb ?: return "DebridOne is not started."
        val session = database.currentSession() ?: return "No account is signed in."
        return try {
            val profileIds = database.deleteAccount(session.accountId, password)
            profileIds.forEach(::eraseProfileData)
            AuthRepository.refreshLocalSession()
            null
        } catch (e: com.debridone.accounts.AccountException) {
            e.message
        }
    }

    private fun observeActiveProfile(host: ModuleHost) {
        scope.launch {
            combine(AuthRepository.state, ProfileRepository.state) { auth, profiles ->
                if (auth is AuthState.Authenticated && LocalAccounts.isActive) {
                    profiles.activeProfile?.profileIndex?.toLong()
                } else if (auth is AuthState.Authenticated) {
                    // Sync-server mode: upstream profile indices are small integers per account.
                    profiles.activeProfile?.profileIndex?.toLong()
                } else {
                    null
                }
            }.distinctUntilChanged().collect { profileId ->
                _activeProfileId.value = profileId
                host.onProfileChanged(profileId)
            }
        }
    }

    /** A profile was deleted: erase what it owned in Streaming and in every other module. */
    private fun eraseProfileData(profileId: Long) {
        runCatching { DesktopStorage.removeProfileScopedKeys(Math.toIntExact(profileId)) }
            .onFailure { e -> log.e(e) { "Could not erase Streaming data of profile $profileId" } }
        moduleHost?.let { host -> runBlocking { host.deleteProfileData(profileId) } }
        runCatching { cloudServices?.deleteProfile(profileId) }
            .onFailure { e -> log.e(e) { "Could not erase the cloud data of profile $profileId" } }
        accountsDb?.let { database -> runCatching { AvatarImages.sweepUnused(paths, database) } }
    }
}
