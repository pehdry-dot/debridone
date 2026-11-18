package com.nuvio.app

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A video file the shell hands to Streaming's player: an address made for the moment (or a local
 * file), and how to recognise the same file again for resuming. Streaming keeps the player, the
 * watch progress and Continue Watching; the shell never sees them.
 */
data class ShellPlaybackRequest(
    val title: String,
    val subtitle: String?,
    val sourceUrl: String,
    val providerName: String,
    val providerId: String,
    /** Stable for the entry the file belongs to; starts with [AppShellHost.SHELL_CONTENT_PREFIX]. */
    val contentId: String,
    /** Stable for the file itself. */
    val videoId: String,
    val startFromBeginning: Boolean = false,
)

/** See [AppShellHost.librarySource]. [label] names the chip; [content] adds the rows to the Library's list. */
class ShellLibrarySource(val label: String, val content: LazyListScope.() -> Unit)

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

    /**
     * Streaming's settings shown as a panel over the tab that is open, instead of as a tab of their
     * own: the tab underneath (its scroll position, a search, what is selected) stays as it was.
     * Used on desktop while the shell is active; other platforms keep the Settings tab.
     */
    var settingsOverlayOpen by mutableStateOf(false)

    /**
     * The addon whose "Configure" was clicked. Under the shell it opens as a panel where it was
     * clicked (see `AddonConfigurationPanel`) instead of sending the person straight to a browser.
     */
    var addonConfiguration by mutableStateOf<com.nuvio.app.features.addons.AddonConfigurationRequest?>(null)

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

    private val playbackRequests = Channel<ShellPlaybackRequest>(Channel.CONFLATED)

    /**
     * Videos the shell asks Streaming to play in its own player. A channel, not a shared flow: a
     * request made before Streaming has been shown once waits until it is.
     */
    val streamingPlaybackRequests: Flow<ShellPlaybackRequest> = playbackRequests.receiveAsFlow()

    fun requestPlayback(request: ShellPlaybackRequest) {
        playbackRequests.trySend(request)
    }

    /**
     * Continue Watching on something the shell asked to be played: the shell finds the file again
     * and requests playback anew. Returns false when it cannot.
     */
    @Volatile
    var resumeShellContent: ((contentId: String, videoId: String, startFromBeginning: Boolean) -> Boolean)? = null

    /**
     * A list the shell adds to Streaming's Library, beside "Saved" and "Cloud". Streaming draws
     * the chip and hands over the list; the rows, and what happens when one is chosen, are the
     * shell's. Null: there is no such list.
     */
    @Volatile
    var librarySource: ShellLibrarySource? = null

    /** Content ids of what the shell plays start with this, so they are never mistaken for Streaming's own. */
    const val SHELL_CONTENT_PREFIX = "debridone:"

    @Composable
    fun Wrap(onSwitchProfile: () -> Unit, streaming: @Composable () -> Unit) {
        val installed = shell
        if (installed == null) streaming() else installed(streaming, onSwitchProfile)
    }
}
