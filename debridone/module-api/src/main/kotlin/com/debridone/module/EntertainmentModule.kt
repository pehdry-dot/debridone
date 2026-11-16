package com.debridone.module

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ModuleStatus {
    data object Stopped : ModuleStatus

    data object Starting : ModuleStatus

    data object Running : ModuleStatus

    /** The module could not start or crashed. Other modules keep running. */
    data class Failed(val message: String, val cause: Throwable? = null) : ModuleStatus
}

/**
 * How much of a module exists in this build. The shell shows this verbatim so an unfinished module
 * is never presented as working.
 */
sealed interface ModuleReadiness {
    data object Ready : ModuleReadiness

    /** Storage, plugin registry and settings exist; the engine and screens are still to come. */
    data class FoundationOnly(val summary: String, val upcoming: List<String>) : ModuleReadiness
}

/**
 * One independent entertainment module as seen by the app shell.
 *
 * Implementations must not reference another module. The shell never passes one module's objects
 * to another module.
 */
interface EntertainmentModule : AutoCloseable {
    val id: ModuleId
    val readiness: ModuleReadiness
    val status: StateFlow<ModuleStatus>

    /** This module's own plugin registry. */
    val plugins: PluginRegistry

    /** This module's own metadata provider settings. */
    val metadataProviders: MetadataProviderRegistry

    /** This module's own per-profile appearance settings. */
    val appearance: ModuleAppearanceStore

    /** Opens the module's storage. Must be safe to call once; failures are reported via [status]. */
    suspend fun start()

    /** Called when the active profile changes (null = signed out). */
    suspend fun onProfileChanged(profileId: Long?)
}

/**
 * Failure boundary for one module: its own supervised coroutine scope and status. An uncaught
 * exception in one module's scope marks only that module as failed.
 */
class ModuleRuntime(val id: ModuleId) {
    private val _status = MutableStateFlow<ModuleStatus>(ModuleStatus.Stopped)
    val status: StateFlow<ModuleStatus> = _status.asStateFlow()

    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            _status.value = ModuleStatus.Failed(describe(throwable), throwable)
        },
    )

    /** Runs the module's start-up; converts any failure into [ModuleStatus.Failed] instead of throwing. */
    suspend fun start(block: suspend () -> Unit) {
        if (_status.value == ModuleStatus.Running || _status.value == ModuleStatus.Starting) return
        _status.value = ModuleStatus.Starting
        try {
            block()
            _status.value = ModuleStatus.Running
        } catch (e: CancellationException) {
            _status.value = ModuleStatus.Stopped
            throw e
        } catch (t: Throwable) {
            _status.value = ModuleStatus.Failed(describe(t), t)
        }
    }

    fun reportFailure(throwable: Throwable) {
        _status.value = ModuleStatus.Failed(describe(throwable), throwable)
    }

    fun stop() {
        scope.cancel()
        _status.value = ModuleStatus.Stopped
    }

    private fun describe(throwable: Throwable): String =
        throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.simpleName ?: "Unknown error"
}
