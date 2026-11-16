package com.nuvio.app

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DebridOne seam between Nuvio (the Streaming module) and the application shell around it.
 *
 * Nuvio's main content is everything shown after sign-in and profile selection. When a shell is
 * installed, that content is handed to it as one composable slot; Nuvio keeps owning its own
 * navigation inside the slot and reports when it needs the whole window (video playback).
 */
object AppShellHost {
    /** Installed once at start-up by the desktop app. Null on platforms without a shell. */
    @Volatile
    var shell: (@Composable (streaming: @Composable () -> Unit, onSwitchProfile: () -> Unit) -> Unit)? = null

    val isActive: Boolean get() = shell != null

    private val _streamingImmersive = MutableStateFlow(false)

    /** True while Streaming shows a route that hides navigation, such as the video player. */
    val streamingImmersive: StateFlow<Boolean> = _streamingImmersive.asStateFlow()

    fun reportStreamingImmersive(immersive: Boolean) {
        _streamingImmersive.value = immersive
    }

    private val _streamingTabRequests = MutableSharedFlow<AppScreenTab>(extraBufferCapacity = 4)

    /** Requests from the shell to open one of Streaming's own tabs. */
    val streamingTabRequests: SharedFlow<AppScreenTab> = _streamingTabRequests.asSharedFlow()

    fun requestStreamingTab(tab: AppScreenTab) {
        _streamingTabRequests.tryEmit(tab)
    }

    @Composable
    fun Wrap(onSwitchProfile: () -> Unit, streaming: @Composable () -> Unit) {
        val installed = shell
        if (installed == null) streaming() else installed(streaming, onSwitchProfile)
    }
}
