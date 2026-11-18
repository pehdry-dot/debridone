package com.debridone.app.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.music.sources.SourceConnection
import com.debridone.app.DebridOneRuntime
import com.debridone.app.shell.ModuleAppearanceEditor
import com.debridone.app.shell.ModuleSettingsPanel
import com.debridone.app.shell.PillTone
import com.nuvio.app.core.ui.PluginConfigurationPanel
import com.debridone.app.shell.SettingsCategory
import com.debridone.app.shell.StatusPill
import com.debridone.module.ModuleId
import com.debridone.music.sources.MusicPluginManifest
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The pages of Music's settings, in the order they are listed. */
internal object MusicSettingsSections {
    const val APPEARANCE = "appearance"
    const val PLAYBACK = "playback"
    const val LIBRARY = "library"
    const val HOME = "home"
    const val SOURCES = "sources"
    const val METADATA = "metadata"

    val all = listOf(
        SettingsCategory(APPEARANCE, "Appearance", Icons.Rounded.Palette),
        SettingsCategory(PLAYBACK, "Playback", Icons.Rounded.PlayCircle),
        SettingsCategory(LIBRARY, "Library", Icons.Rounded.LibraryMusic),
        SettingsCategory(HOME, "Home", Icons.Rounded.Home),
        SettingsCategory(SOURCES, "Plugins", Icons.Rounded.Extension),
        SettingsCategory(METADATA, "Metadata", Icons.Rounded.Label),
    )
}

/** Which source's configuration is open. One at a time, wherever "Configure" or "Connect" was clicked. */
internal sealed interface MusicSourceConfig {
    /** The folders of this computer. */
    data object LocalLibrary : MusicSourceConfig

    /** Signing in to a server of the given adapter, or to the server a plugin names. */
    data class Server(val adapterId: String, val plugin: MusicPluginManifest? = null) : MusicSourceConfig

    /** A radio or other direct stream address. */
    data object Stream : MusicSourceConfig

    /** The settings of an installed music addon. */
    data class Addon(val connectionId: Long) : MusicSourceConfig
}

internal object MusicSourcesUi {
    var configuring by mutableStateOf<MusicSourceConfig?>(null)
}

/**
 * Music's settings, inside Music: a panel over the page that was open, which stays as it was
 * underneath (and whatever is playing keeps playing). Appearance, playback, the library, Home, and
 * where music and its details come from are all here; nothing sends the listener elsewhere.
 */
@Composable
internal fun MusicSettingsPanel(modifier: Modifier = Modifier) {
    val music = LocalMusic.current
    ModuleSettingsPanel(
        open = MusicNav.settingsOpen,
        title = "Music settings",
        categories = MusicSettingsSections.all,
        selected = MusicNav.settingsSection,
        onSelect = { MusicNav.settingsSection = it },
        onClose = { MusicNav.settingsOpen = false },
        accent = music.style.accent,
        modifier = modifier,
        selfScrolling = setOf(MusicSettingsSections.SOURCES),
    ) { section ->
        when (section) {
            MusicSettingsSections.APPEARANCE -> ModuleAppearanceEditor(ModuleId.MUSIC)
            MusicSettingsSections.PLAYBACK -> MusicPlaybackSettings()
            MusicSettingsSections.LIBRARY -> MusicLibrarySettings()
            MusicSettingsSections.HOME -> MusicHomeSettings()
            MusicSettingsSections.SOURCES -> MusicSourcesContent()
            MusicSettingsSections.METADATA -> MusicMetadataSettings()
        }
    }
    // A source's configuration opens over the panel, and closes back to it.
    MusicSourceConfigurationPanel(modifier)
}

/** The configuration of the source chosen with "Configure" or "Connect", wherever that was clicked. */
@Composable
internal fun MusicSourceConfigurationPanel(modifier: Modifier = Modifier) {
    val music = LocalMusic.current
    val config = MusicSourcesUi.configuring
    // Kept while the panel slides away, so it does not blank out.
    var shown by remember { mutableStateOf<MusicSourceConfig?>(null) }
    if (config != null) shown = config
    val close = { MusicSourcesUi.configuring = null }
    val current = shown
    val adapter = (current as? MusicSourceConfig.Server)?.let { music.services.sources.adapter(it.adapterId) }
    val connections by rememberMusicQuery<List<SourceConnection>?>(initial = null) { services, profile -> services.sources.connections(profile) }
    val addon = (current as? MusicSourceConfig.Addon)?.let { wanted -> connections?.firstOrNull { it.id == wanted.connectionId } }
    // An addon that was removed while its settings were open has nothing left to configure.
    LaunchedEffect(config, connections) {
        val known = connections
        if (config is MusicSourceConfig.Addon && known != null && known.none { it.id == config.connectionId }) MusicSourcesUi.configuring = null
    }
    PluginConfigurationPanel(
        open = config != null,
        title = when (current) {
            MusicSourceConfig.LocalLibrary -> "Local Music"
            is MusicSourceConfig.Server -> "Connect " + (current.plugin?.name ?: adapter?.displayName ?: "a server")
            MusicSourceConfig.Stream -> "Add a stream address"
            is MusicSourceConfig.Addon -> addon?.name ?: "Addon"
            null -> ""
        },
        kind = when (current) {
            MusicSourceConfig.LocalLibrary -> "Music source · folders and files on this computer"
            is MusicSourceConfig.Server -> if (current.plugin != null) "Music plugin · sign-in" else "Music source · sign-in"
            MusicSourceConfig.Stream -> "Music source · radio and direct streams"
            is MusicSourceConfig.Addon -> "Music addon · changes are saved as you make them"
            null -> ""
        },
        onClose = close,
        modifier = modifier,
    ) {
        when (current) {
            MusicSourceConfig.LocalLibrary -> MusicLibrarySettings()
            is MusicSourceConfig.Server -> if (adapter != null) ServerConnectionForm(adapter, current.plugin, close)
            MusicSourceConfig.Stream -> StreamAddressForm(close)
            is MusicSourceConfig.Addon -> if (addon != null) AddonSettingsForm(addon)
            null -> Unit
        }
    }
}

/** Where details, covers and lyrics come from. They belong to Music only. */
@Composable
internal fun MusicMetadataSettings() {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val module = DebridOneRuntime.modules.music
    val scope = rememberCoroutineScope()
    val providers by module.metadataProviders.providers.collectAsStateWithLifecycle()
    val health by music.services.metadata.health.collectAsStateWithLifecycle()
    var testing by remember { mutableStateOf(setOf<String>()) }
    SettingsCard(
        "Metadata and lyrics",
        "Where song details, covers and lyrics are looked up. A provider that is switched off is not contacted. The tags and covers inside your own files always come first.",
    ) {
        providers.forEach { provider ->
            val state = health[provider.id]
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(provider.name, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                        Text(provider.description, style = small(), color = tokens.colors.textMuted)
                    }
                    when {
                        !provider.enabled -> StatusPill("Off")
                        state == null -> StatusPill("On, not tested yet")
                        state.ok -> StatusPill("Working", PillTone.SUCCESS)
                        else -> StatusPill("Not answering", PillTone.DANGER)
                    }
                    if (provider.id != "embedded-tags") {
                        MusicButton(if (provider.id in testing) "Testing…" else "Test", {
                            testing = testing + provider.id
                            actions.io {
                                try {
                                    music.services.metadata.testProvider(provider.id)
                                } finally {
                                    testing = testing - provider.id
                                }
                            }
                        }, primary = false, enabled = provider.id !in testing && provider.enabled)
                        Switch(
                            checked = provider.enabled,
                            onCheckedChange = { enabled -> scope.launch { module.metadataProviders.setEnabled(provider.id, enabled) } },
                            colors = SwitchDefaults.colors(checkedTrackColor = music.style.accent, checkedThumbColor = music.style.onAccent),
                        )
                    }
                }
                // Said where it happened: the provider's own last answer, with its time.
                state?.let {
                    Text(
                        (if (it.ok) it.message else "Last error: ${it.message}") + "  ·  " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.checkedAt)),
                        style = small(), color = if (it.ok) tokens.colors.textSecondary else tokens.colors.danger,
                    )
                }
            }
        }
    }
}
