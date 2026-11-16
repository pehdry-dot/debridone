package com.debridone.app.shell

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.rememberLifecycleOwner
import com.debridone.app.DebridOneRuntime
import com.debridone.module.ModuleId
import com.nuvio.app.AppShellHost
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.nuvio

internal val ShellSidebarCollapsedWidth = 68.dp
internal val ShellSidebarExpandedWidth = 208.dp

/**
 * The DebridOne application shell: one sidebar, and one content area that shows exactly one
 * section at a time. Streaming is Nuvio, passed in as [streaming]; Music, Games and Manga are
 * rendered from their own module engines. The shell never mixes content between modules.
 */
@Composable
fun DebridOneShell(
    streaming: @Composable () -> Unit,
    onSwitchProfile: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val section by ShellState.section.collectAsStateWithLifecycle()
    val immersive by AppShellHost.streamingImmersive.collectAsStateWithLifecycle()
    val sidebarStyle by ShellState.sidebarStyle.collectAsStateWithLifecycle()
    val reduceMotion by ShellState.reduceMotion.collectAsStateWithLifecycle()
    val activeProfileId by DebridOneRuntime.activeProfileId.collectAsStateWithLifecycle()

    LaunchedEffect(activeProfileId) {
        if (activeProfileId != null) ShellState.restore()
    }

    // Streaming needs the whole window while it plays video.
    val sidebarVisible = !(section == ShellSection.STREAMING && immersive)
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val expanded = when (sidebarStyle) {
        SidebarStyle.EXPANDED -> true
        SidebarStyle.COMPACT -> false
        SidebarStyle.AUTO -> hovered
    }
    val sidebarWidth by animateDpAsState(
        targetValue = if (expanded) ShellSidebarExpandedWidth else ShellSidebarCollapsedWidth,
        animationSpec = if (reduceMotion) snap() else tween(200, easing = FastOutSlowInEasing),
        label = "shell_sidebar_width",
    )
    val contentInset = when {
        !sidebarVisible -> 0.dp
        sidebarStyle == SidebarStyle.EXPANDED -> ShellSidebarExpandedWidth
        else -> ShellSidebarCollapsedWidth
    }

    // Streaming stays alive once opened so its navigation and playback state survive visits to
    // other sections, but it is paused and receives no input while it is not the active section.
    var streamingOpened by remember { mutableStateOf(false) }
    if (section == ShellSection.STREAMING) streamingOpened = true

    Box(modifier = Modifier.fillMaxSize().background(tokens.colors.background)) {
        Box(modifier = Modifier.fillMaxSize().padding(start = contentInset)) {
            if (streamingOpened) {
                KeepAlivePane(active = section == ShellSection.STREAMING, content = streaming)
            }
            when (section) {
                ShellSection.STREAMING -> Unit
                ShellSection.HOME -> HomeDashboard()
                ShellSection.MUSIC -> ModuleScreen(ModuleId.MUSIC)
                ShellSection.GAMES -> ModuleScreen(ModuleId.GAMING)
                ShellSection.MANGA -> ModuleScreen(ModuleId.MANGA)
                ShellSection.PLUGINS -> PluginsScreen()
                ShellSection.DOWNLOADS -> DownloadsScreen()
                ShellSection.SETTINGS -> ShellSettingsScreen(onSwitchProfile = onSwitchProfile)
            }
        }
        if (sidebarVisible) {
            ShellSidebar(
                selected = section,
                expanded = expanded,
                width = sidebarWidth,
                hoverSource = hoverSource,
                onSelect = ShellState::select,
                onProfileClick = onSwitchProfile,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}

/** Keeps [content] composed; when inactive it is not laid out, not interactive and lifecycle-paused. */
@Composable
private fun KeepAlivePane(active: Boolean, content: @Composable () -> Unit) {
    val lifecycleOwner = rememberLifecycleOwner(
        maxLifecycle = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED,
    )
    CompositionLocalProvider(
        LocalLifecycleOwner provides lifecycleOwner,
        LocalScreenActive provides active,
    ) {
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(active) {
                    if (!active) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .then(if (active) Modifier else Modifier.clearAndSetSemantics {}),
            content = { Box(Modifier.fillMaxSize()) { content() } },
        ) { measurables, constraints ->
            val placeable = measurables.first().measure(constraints)
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (active) placeable.placeRelative(0, 0)
            }
        }
    }
}
