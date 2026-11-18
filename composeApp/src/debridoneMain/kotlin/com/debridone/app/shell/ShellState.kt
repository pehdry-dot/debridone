package com.debridone.app.shell

import com.debridone.app.DebridOneRuntime
import com.debridone.module.ModuleId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Top-level destinations of the application shell, in sidebar order. */
enum class ShellSection(val key: String, val label: String, val module: ModuleId? = null) {
    HOME("home", "Home"),
    STREAMING("streaming", "Streaming", ModuleId.STREAMING),
    MUSIC("music", "Music", ModuleId.MUSIC),
    MANGA("manga", "Manga", ModuleId.MANGA),
    PLUGINS("plugins", "Plugins"),
    DOWNLOADS("downloads", "Downloads"),
    SETTINGS("settings", "Settings"),
    ;

    companion object {
        fun fromKey(key: String?): ShellSection? = entries.firstOrNull { it.key == key }

        /** The entertainment sections, in Cmd+1…Cmd+3 order. (Games is not part of the app for now.) */
        val modules: List<ShellSection> = listOf(STREAMING, MUSIC, MANGA)
    }
}

enum class SidebarStyle(val key: String, val label: String) {
    AUTO("auto", "Expand on hover"),
    EXPANDED("expanded", "Always expanded"),
    COMPACT("compact", "Always compact"),
    ;

    companion object {
        fun fromKey(key: String?): SidebarStyle = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

enum class SettingsPage(val label: String) {
    ACCOUNT("Account"),
    PROFILES("Profiles"),
    CONNECTED_ACCOUNTS("Connected Accounts"),
    DOWNLOADS("Downloads"),
    APPEARANCE("Appearance"),
    STORAGE("Storage & backups"),
    ABOUT("About"),
}

/**
 * Shell navigation state. It lives outside composition so it survives the profile chooser being
 * shown and is restored after a restart (per profile, with a device-wide fallback).
 */
object ShellState {
    private const val KEY_SECTION = "shell.section"
    private const val KEY_SIDEBAR = "shell.sidebar_style"
    private const val KEY_REDUCE_MOTION = "shell.reduce_motion"

    private val _section = MutableStateFlow(ShellSection.HOME)
    val section: StateFlow<ShellSection> = _section.asStateFlow()

    private val _pluginsTab = MutableStateFlow(ModuleId.STREAMING)
    val pluginsTab: StateFlow<ModuleId> = _pluginsTab.asStateFlow()

    private val _settingsPage = MutableStateFlow(SettingsPage.ACCOUNT)
    val settingsPage: StateFlow<SettingsPage> = _settingsPage.asStateFlow()

    private val _sidebarStyle = MutableStateFlow(SidebarStyle.AUTO)
    val sidebarStyle: StateFlow<SidebarStyle> = _sidebarStyle.asStateFlow()

    private val _reduceMotion = MutableStateFlow(false)
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()

    private val _mangaImmersive = MutableStateFlow(false)

    /** True while Manga's reader is open: it takes the whole window, as Streaming's player does. */
    val mangaImmersive: StateFlow<Boolean> = _mangaImmersive.asStateFlow()

    fun setMangaImmersive(immersive: Boolean) {
        _mangaImmersive.value = immersive
    }

    private val _panel = MutableStateFlow<SettingsPage?>(null)

    /** A page of the application's own settings shown over the section that asked for it, or null. */
    val panel: StateFlow<SettingsPage?> = _panel.asStateFlow()

    /**
     * Shows one page of the application's own settings (connected accounts, download folders) as
     * a panel over the section the person is in, so that closing it leaves them where they were.
     */
    fun showInPlace(page: SettingsPage) {
        if (_section.value == ShellSection.SETTINGS) selectSettingsPage(page) else _panel.value = page
    }

    fun closePanel() {
        _panel.value = null
    }

    fun select(section: ShellSection) {
        _panel.value = null
        _section.value = section
        DebridOneRuntime.setShellSetting(KEY_SECTION, section.key)
    }

    fun openPlugins(module: ModuleId) {
        _pluginsTab.value = module
        select(ShellSection.PLUGINS)
    }

    fun selectPluginsTab(module: ModuleId) {
        _pluginsTab.value = module
    }

    /** Opens the application's own settings (account, profiles, connected accounts, downloads, look, storage). */
    fun openSettings(page: SettingsPage) {
        _settingsPage.value = page
        select(ShellSection.SETTINGS)
    }

    fun selectSettingsPage(page: SettingsPage) {
        _settingsPage.value = page
    }

    fun setSidebarStyle(style: SidebarStyle) {
        _sidebarStyle.value = style
        DebridOneRuntime.setShellSetting(KEY_SIDEBAR, style.key)
    }

    fun setReduceMotion(enabled: Boolean) {
        _reduceMotion.value = enabled
        DebridOneRuntime.setShellSetting(KEY_REDUCE_MOTION, enabled.toString())
    }

    /** Loads the saved state for the profile that just became active. */
    fun restore() {
        ShellSection.fromKey(DebridOneRuntime.shellSetting(KEY_SECTION))?.let { _section.value = it }
        _sidebarStyle.value = SidebarStyle.fromKey(DebridOneRuntime.shellSetting(KEY_SIDEBAR))
        _reduceMotion.value = DebridOneRuntime.shellSetting(KEY_REDUCE_MOTION)?.toBooleanStrictOrNull() ?: false
    }
}
