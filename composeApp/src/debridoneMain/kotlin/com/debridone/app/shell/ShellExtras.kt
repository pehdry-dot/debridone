package com.debridone.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debridone.app.DebridOneRuntime
import com.nuvio.app.AppScreenTab
import com.nuvio.app.AppShellHost
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/** Shown instead of the app when DebridOne cannot open its data. Deliberately theme-independent. */
@Composable
fun StartupErrorScreen(message: String) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0D0D0D)), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("DebridOne can't start", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            SelectionContainer { Text(message, color = Color(0xFFB8B8B8), fontSize = 15.sp, lineHeight = 22.sp) }
        }
    }
}

/**
 * Application shortcuts: Cmd+1…4 switch sections, Cmd+, opens Settings, Cmd+F searches inside the
 * active section. (Ctrl replaces Cmd on non-macOS development hosts.)
 *
 * Installed at the AWT level so the shortcuts work wherever keyboard focus is.
 */
@Composable
fun ShellKeyboardShortcuts(content: @Composable () -> Unit) {
    DisposableEffect(Unit) {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        val dispatcher = KeyEventDispatcher { event ->
            if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
            val commandDown = if (isMac) event.isMetaDown else event.isControlDown
            if (!commandDown || event.isAltDown || event.isShiftDown) return@KeyEventDispatcher false
            handleShortcut(event.keyCode)
        }
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        manager.addKeyEventDispatcher(dispatcher)
        onDispose { manager.removeKeyEventDispatcher(dispatcher) }
    }
    content()
}

private fun handleShortcut(keyCode: Int): Boolean {
    // Only once a profile is in use (not on the sign-in or profile chooser), and never while
    // Streaming is in the player, which has its own keyboard controls.
    if (DebridOneRuntime.activeProfileId.value == null || !AppShellHost.isActive) return false
    val section = ShellState.section.value
    if (section == ShellSection.STREAMING && AppShellHost.streamingImmersive.value) return false
    return when (keyCode) {
        KeyEvent.VK_1 -> select(ShellSection.STREAMING)
        KeyEvent.VK_2 -> select(ShellSection.MUSIC)
        KeyEvent.VK_3 -> select(ShellSection.GAMES)
        KeyEvent.VK_4 -> select(ShellSection.MANGA)
        KeyEvent.VK_COMMA -> {
            ShellState.openSettings(ShellState.settingsPage.value)
            true
        }
        KeyEvent.VK_F -> if (section == ShellSection.STREAMING) {
            // Search stays inside the active section: Streaming opens its own search.
            AppShellHost.requestStreamingTab(AppScreenTab.Search)
            true
        } else {
            false
        }
        else -> false
    }
}

private fun select(section: ShellSection): Boolean {
    ShellState.select(section)
    return true
}
