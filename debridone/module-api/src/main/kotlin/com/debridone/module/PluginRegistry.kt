package com.debridone.module

import kotlinx.coroutines.flow.StateFlow

/**
 * What a plugin does inside its module. Every module declares which kinds it supports through
 * [PluginRegistry.supportedKinds]; the Plugins screen only ever offers those.
 */
enum class PluginKind(val label: String) {
    /** Supplies playable/readable/downloadable content (addon, source, extension). */
    SOURCE("Source"),

    /** Supplies descriptive data only (artwork, credits, tags). */
    METADATA("Metadata"),

    /** Subtitles (Streaming) or lyrics (Music). */
    TEXT_TRACKS("Subtitles & lyrics"),

    /** Connects an existing personal library or account. */
    LIBRARY_INTEGRATION("Library integration"),

    /** Anything else scoped to the module. */
    UTILITY("Utility"),
}

/** A plugin that is installed in exactly one module's registry. */
data class InstalledPlugin(
    /** Unique within its module only. The same id in two modules refers to unrelated plugins. */
    val id: String,
    val name: String,
    val kind: PluginKind,
    val version: String?,
    val description: String?,
    /** Where it was installed from (manifest URL, package name, file). Null for built-ins. */
    val origin: String?,
    val enabled: Boolean,
    val builtIn: Boolean,
    val installedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    /** The most recent failure reported for this plugin, shown in the tab's error list. */
    val lastError: String? = null,
    val lastErrorAtEpochMillis: Long? = null,
)

/** Something a module itself offers for installation. Never pre-installed and never shared. */
data class AvailablePlugin(
    val id: String,
    val name: String,
    val kind: PluginKind,
    val description: String?,
    val origin: String,
)

sealed interface PluginActionResult {
    data class Success(val plugin: InstalledPlugin? = null) : PluginActionResult

    data class Failure(val message: String) : PluginActionResult

    /** The action is defined by the contract but this module has not implemented it yet. */
    data class NotAvailableYet(val reason: String) : PluginActionResult
}

/**
 * UI contract for one tab of the Plugins screen.
 *
 * This is NOT a shared plugin pool. Each module provides its own implementation, backed by its own
 * tables in its own database, and the shell asks each registry separately. There is deliberately
 * no method that lists plugins across modules.
 */
interface PluginRegistry {
    val moduleId: ModuleId

    val supportedKinds: Set<PluginKind>

    /** Explains, in the module's own terms, what can be installed here. */
    val importHint: String

    /** Whether [importFromLocation] is functional in this build. */
    val importAvailable: Boolean

    val installed: StateFlow<List<InstalledPlugin>>

    /** Catalogue offered by this module. Empty unless the module ships one. */
    suspend fun available(): List<AvailablePlugin>

    /** Installs from a manifest URL, package name or file path, as defined by the module. */
    suspend fun importFromLocation(location: String): PluginActionResult

    suspend fun setEnabled(pluginId: String, enabled: Boolean): PluginActionResult

    suspend fun remove(pluginId: String): PluginActionResult

    suspend fun checkForUpdates(pluginId: String? = null): PluginActionResult
}

/** One metadata provider of one module, e.g. MusicBrainz for Music. */
data class MetadataProvider(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
    /** Lower runs first. */
    val priority: Int,
    /** False while the provider's engine has not been implemented yet. */
    val implemented: Boolean,
    val requiresCredentials: Boolean = false,
)

/**
 * Per-module metadata provider settings (the "Metadata" subsection of each Plugins tab).
 * Like [PluginRegistry], every module owns its own instance and storage.
 */
interface MetadataProviderRegistry {
    val moduleId: ModuleId

    val providers: StateFlow<List<MetadataProvider>>

    suspend fun setEnabled(providerId: String, enabled: Boolean)

    suspend fun setPriority(providerId: String, priority: Int)
}
