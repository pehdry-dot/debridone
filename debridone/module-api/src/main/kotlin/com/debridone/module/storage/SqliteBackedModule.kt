package com.debridone.module.storage

import com.debridone.module.AppearanceOption
import com.debridone.module.AvailablePlugin
import com.debridone.module.EntertainmentModule
import com.debridone.module.InstalledPlugin
import com.debridone.module.MetadataProvider
import com.debridone.module.MetadataProviderRegistry
import com.debridone.module.ModuleAppearance
import com.debridone.module.ModuleAppearanceStore
import com.debridone.module.ModuleId
import com.debridone.module.ModuleRuntime
import com.debridone.module.ModuleStatus
import com.debridone.module.PluginActionResult
import com.debridone.module.PluginKind
import com.debridone.module.PluginRegistry
import com.debridone.platform.db.Migration
import com.debridone.platform.db.OpenOutcome
import com.debridone.platform.db.SqliteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** What a module needs to know to register a plugin in its own registry. */
data class PluginDescriptor(
    val id: String,
    val name: String,
    val kind: PluginKind,
    val version: String? = null,
    val description: String? = null,
    val origin: String? = null,
    val builtIn: Boolean = false,
    val enabledByDefault: Boolean = true,
)

/**
 * Shared skeleton for a module whose state lives in its own SQLite file. It is plumbing only:
 * every subclass names its own tables, declares its own migrations, plugin kinds, metadata
 * providers and appearance options, and is constructed with its own [ModuleStorage]. Two modules
 * never share an instance, a table or a file.
 */
abstract class SqliteBackedModule(
    final override val id: ModuleId,
    protected val storage: ModuleStorage,
    protected val clock: () -> Long = System::currentTimeMillis,
) : EntertainmentModule {
    init {
        require(storage.moduleId == id) { "Module ${id.key} was given storage of ${storage.moduleId.key}" }
    }

    protected abstract val migrations: List<Migration>
    protected abstract val pluginTable: String
    protected abstract val metadataTable: String
    protected abstract val settingsTable: String
    protected abstract val supportedPluginKinds: Set<PluginKind>
    protected abstract val pluginImportHint: String
    protected abstract val defaultMetadataProviders: List<MetadataProvider>
    protected abstract val appearanceOptions: List<AppearanceOption>

    /** Override when the module can install plugins. The default states honestly that it cannot yet. */
    protected open val pluginImportUnavailableReason: String? =
        "Plugin installation for ${id.displayName} arrives together with the ${id.displayName} engine."

    protected open suspend fun importPlugin(location: String): PluginActionResult =
        PluginActionResult.NotAvailableYet(pluginImportUnavailableReason ?: "Not available")

    protected open suspend fun availablePlugins(): List<AvailablePlugin> = emptyList()

    protected open suspend fun updatePlugins(pluginId: String?): PluginActionResult =
        PluginActionResult.NotAvailableYet(pluginImportUnavailableReason ?: "Not available")

    private val runtime = ModuleRuntime(id)
    final override val status: StateFlow<ModuleStatus> get() = runtime.status

    @Volatile
    private var database: SqliteDatabase? = null

    /** How the database was opened last time (created, migrated, restored from backup...). */
    @Volatile
    var lastOpenOutcome: OpenOutcome? = null
        private set

    @Volatile
    private var activeProfileId: Long? = null

    private val installedPlugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    private val providerState = MutableStateFlow<List<MetadataProvider>>(emptyList())
    private val appearanceState = MutableStateFlow(ModuleAppearance.Empty)

    protected fun requireDatabase(): SqliteDatabase =
        database ?: error("${id.displayName} module is not running")

    private fun pluginStore() = PluginTableStore(requireDatabase(), pluginTable)
    private fun providerStore() = MetadataProviderTableStore(requireDatabase(), metadataTable)
    protected fun settingsStore() = ProfileSettingsTableStore(requireDatabase(), settingsTable)

    final override suspend fun start() {
        runtime.start {
            withContext(Dispatchers.IO) {
                val opened = storage.openDatabase(migrations)
                database = opened.database
                lastOpenOutcome = opened.outcome
                installedPlugins.value = pluginStore().list()
                providerState.value = providerStore().resolve(defaultMetadataProviders)
                reloadAppearance()
                onStarted()
            }
        }
    }

    /** Hook for subclasses; runs on the IO dispatcher after storage is open. */
    protected open fun onStarted() {}

    final override suspend fun onProfileChanged(profileId: Long?) {
        activeProfileId = profileId
        if (database == null) return
        guarded {
            reloadAppearance()
            onActiveProfileChanged(profileId)
        }
    }

    /**
     * Hook for subclasses; runs on the IO dispatcher once storage is open, every time the active
     * profile is set (including the first time after start, and null on sign-out). May be called
     * again with the same profile.
     */
    protected open fun onActiveProfileChanged(profileId: Long?) {}

    /** The module's own supervised scope: a failure in it marks only this module as failed. */
    protected val moduleScope: kotlinx.coroutines.CoroutineScope get() = runtime.scope

    /**
     * Removes everything [profileId] owned in this module. Called when a profile is deleted so its
     * data cannot resurface under a later profile.
     */
    suspend fun deleteProfileData(profileId: Long) {
        if (database == null) return
        guarded {
            settingsStore().deleteProfile(profileId)
            onProfileDeleted(profileId)
        }
    }

    protected open fun onProfileDeleted(profileId: Long) {}

    /** Registers (or refreshes) a plugin in THIS module's registry. Used by the module's own importers. */
    suspend fun registerPlugin(descriptor: PluginDescriptor): InstalledPlugin {
        require(descriptor.kind in supportedPluginKinds) {
            "${id.displayName} does not accept ${descriptor.kind} plugins"
        }
        return withContext(Dispatchers.IO) {
            val store = pluginStore()
            store.upsert(
                id = descriptor.id,
                name = descriptor.name,
                kind = descriptor.kind,
                version = descriptor.version,
                description = descriptor.description,
                origin = descriptor.origin,
                builtIn = descriptor.builtIn,
                enabledByDefault = descriptor.enabledByDefault,
                now = clock(),
            )
            installedPlugins.value = store.list()
            checkNotNull(store.find(descriptor.id))
        }
    }

    suspend fun reportPluginError(pluginId: String, message: String?) {
        withContext(Dispatchers.IO) {
            val store = pluginStore()
            store.recordError(pluginId, message, clock())
            installedPlugins.value = store.list()
        }
    }

    final override val plugins: PluginRegistry = object : PluginRegistry {
        override val moduleId: ModuleId = id
        override val supportedKinds: Set<PluginKind> get() = supportedPluginKinds
        override val importHint: String get() = pluginImportHint
        override val importAvailable: Boolean get() = pluginImportUnavailableReason == null
        override val installed: StateFlow<List<InstalledPlugin>> = installedPlugins.asStateFlow()

        override suspend fun available(): List<AvailablePlugin> = availablePlugins()

        override suspend fun importFromLocation(location: String): PluginActionResult {
            val trimmed = location.trim()
            if (trimmed.isEmpty()) return PluginActionResult.Failure("Enter a location to import from.")
            return runAction { importPlugin(trimmed) }
        }

        override suspend fun setEnabled(pluginId: String, enabled: Boolean): PluginActionResult = runAction {
            withContext(Dispatchers.IO) {
                val store = pluginStore()
                if (!store.setEnabled(pluginId, enabled, clock())) {
                    return@withContext PluginActionResult.Failure("Plugin not found in ${id.displayName}.")
                }
                installedPlugins.value = store.list()
                PluginActionResult.Success(store.find(pluginId))
            }
        }

        override suspend fun remove(pluginId: String): PluginActionResult = runAction {
            withContext(Dispatchers.IO) {
                val store = pluginStore()
                val existing = store.find(pluginId)
                    ?: return@withContext PluginActionResult.Failure("Plugin not found in ${id.displayName}.")
                if (existing.builtIn) {
                    return@withContext PluginActionResult.Failure("Built-in plugins can be disabled but not removed.")
                }
                store.remove(pluginId)
                onPluginRemoved(pluginId)
                installedPlugins.value = store.list()
                PluginActionResult.Success()
            }
        }

        override suspend fun checkForUpdates(pluginId: String?): PluginActionResult = runAction { updatePlugins(pluginId) }
    }

    /** Hook to drop plugin-owned data and credentials; runs inside the module only. */
    protected open fun onPluginRemoved(pluginId: String) {}

    final override val metadataProviders: MetadataProviderRegistry = object : MetadataProviderRegistry {
        override val moduleId: ModuleId = id
        override val providers: StateFlow<List<MetadataProvider>> = providerState.asStateFlow()

        override suspend fun setEnabled(providerId: String, enabled: Boolean) {
            update(providerId) { it.copy(enabled = enabled) }
        }

        override suspend fun setPriority(providerId: String, priority: Int) {
            update(providerId) { it.copy(priority = priority) }
        }

        private suspend fun update(providerId: String, change: (MetadataProvider) -> MetadataProvider) {
            guarded {
                val current = providerStore().resolve(defaultMetadataProviders).firstOrNull { it.id == providerId }
                    ?: return@guarded
                val changed = change(current)
                providerStore().save(changed.id, changed.enabled, changed.priority)
                providerState.value = providerStore().resolve(defaultMetadataProviders)
            }
        }
    }

    final override val appearance: ModuleAppearanceStore = object : ModuleAppearanceStore {
        override val moduleId: ModuleId = id
        override val options: List<AppearanceOption> get() = appearanceOptions
        override val current: StateFlow<ModuleAppearance> = appearanceState.asStateFlow()

        override suspend fun set(key: String, value: String?) {
            val profileId = activeProfileId ?: return
            guarded {
                val accepted = value?.let { sanitizeAppearance(appearanceOptions, mapOf(key to it))[key] }
                if (value != null && accepted == null) return@guarded
                settingsStore().set(profileId, APPEARANCE_PREFIX + key, accepted)
                reloadAppearance()
            }
        }

        override suspend fun replaceAll(values: Map<String, String>) {
            val profileId = activeProfileId ?: return
            guarded {
                settingsStore().replaceAll(profileId, APPEARANCE_PREFIX, sanitizeAppearance(appearanceOptions, values))
                reloadAppearance()
            }
        }

        override suspend fun resetToDefaults() {
            val profileId = activeProfileId ?: return
            guarded {
                settingsStore().replaceAll(profileId, APPEARANCE_PREFIX, emptyMap())
                reloadAppearance()
            }
        }
    }

    private fun reloadAppearance() {
        val profileId = activeProfileId
        appearanceState.value = if (profileId == null) {
            ModuleAppearance.Empty
        } else {
            val stored = settingsStore().all(profileId, APPEARANCE_PREFIX)
                .mapKeys { it.key.removePrefix(APPEARANCE_PREFIX) }
            ModuleAppearance(sanitizeAppearance(appearanceOptions, stored))
        }
    }

    private suspend fun runAction(block: suspend () -> PluginActionResult): PluginActionResult =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            PluginActionResult.Failure(t.message ?: "Unexpected error in ${id.displayName}")
        }

    private suspend fun guarded(block: () -> Unit) {
        withContext(Dispatchers.IO) { block() }
    }

    /** Hook for subclasses to release what they own; runs before the database is closed. */
    protected open fun onClosing() {}

    override fun close() {
        runCatching { onClosing() }
        runtime.stop()
        database?.let { runCatching { it.close() } }
        database = null
    }

    protected companion object {
        const val APPEARANCE_PREFIX = "appearance."
    }
}
