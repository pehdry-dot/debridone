package com.debridone.app.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Storage
import com.debridone.module.PluginActionResult
import com.debridone.music.sources.AudiusAdapter
import com.debridone.music.sources.EclipseAddonAdapter
import com.debridone.music.sources.HttpCatalogAdapter
import com.debridone.music.sources.IntegrationKind
import com.debridone.music.sources.RealDebridAdapter
import com.debridone.music.sources.JellyfinAdapter
import com.debridone.music.sources.MusicPluginManifest
import com.debridone.music.sources.SubsonicAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.DebridOneRuntime
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.ShellSection
import com.debridone.app.shell.ShellState
import com.debridone.app.shell.StatusPill
import com.debridone.module.ModuleStatus
import com.debridone.music.library.Track
import com.debridone.music.sources.MusicSourceAdapter
import com.debridone.music.sources.MusicSourceManager
import com.debridone.music.sources.SourceConnection
import com.debridone.music.sources.SourceDraft
import com.nuvio.app.core.ui.nuvio
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class SourcesData(
    val connections: List<SourceConnection> = emptyList(),
    val streams: List<Track> = emptyList(),
    val folderCount: Int = 0,
    val localSongs: Int = 0,
    val manifests: List<MusicPluginManifest> = emptyList(),
)

/**
 * Plugins › Music: where this profile's music comes from. Sources are connections DebridOne signs
 * in to itself; nothing here is shared with Streaming, Games or Manga.
 */
@Composable
fun MusicSourcesTab() {
    val module = DebridOneRuntime.modules.music
    val status by module.status.collectAsStateWithLifecycle()
    (status as? ModuleStatus.Failed)?.let { failed ->
        Box(Modifier.fillMaxSize().padding(40.dp)) { Notice("Music could not open its storage, so its sources are unavailable: ${failed.message}", PillTone.DANGER) }
        return
    }
    MusicProvider { MusicSourcesContent() }
}

@Composable
private fun MusicSourcesContent() {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val sources = music.services.sources
    val module = DebridOneRuntime.modules.music
    val activity by sources.activity.collectAsStateWithLifecycle()
    val providers by module.metadataProviders.providers.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val data by rememberMusicQuery(initial = SourcesData()) { services, profile ->
        SourcesData(
            services.sources.connections(profile), services.sources.streams(profile),
            services.library.folders(profile).size, services.library.trackCount(profile, com.debridone.music.library.TrackFilter(sourceId = 0)),
            services.sources.manifests(),
        )
    }
    var adding by remember { mutableStateOf<MusicSourceAdapter?>(null) }
    var connectingPlugin by remember { mutableStateOf<MusicPluginManifest?>(null) }
    var signingInToRealDebrid by remember { mutableStateOf(false) }
    var removingPlugin by remember { mutableStateOf<MusicPluginManifest?>(null) }
    var importText by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val health by music.services.metadata.health.collectAsStateWithLifecycle()
    var testing by remember { mutableStateOf(setOf<String>()) }
    val audius = data.connections.firstOrNull { it.adapter == AudiusAdapter.ID }
    fun adapter(id: String) = sources.adapters.first { it.id == id }
    fun import(location: String) {
        if (location.isBlank() || importing) return
        importing = true
        importError = null
        scope.launch {
            // An address may be an Eclipse Music / BeatBoss addon rather than a DebridOne plugin
            // manifest: it is then reviewed and installed as an addon.
            if (isWebAddress(location)) {
                val addon = withContext(Dispatchers.IO) { runCatching { sources.previewAddon(location.trim()) }.getOrNull() }
                if (addon != null) {
                    importing = false
                    importText = ""
                    AddonUi.review = AddonReview(location.trim(), addon)
                    return@launch
                }
            }
            val result = module.plugins.importFromLocation(location.trim())
            importing = false
            when (result) {
                is PluginActionResult.Success -> {
                    importText = ""
                    val manifest = result.plugin?.let { sources.manifest(it.id) }
                    MusicNav.message = "Installed ${result.plugin?.name ?: "the plugin"}."
                    if (manifest != null && !manifest.connectsWithoutSignIn) connectingPlugin = manifest
                    result.plugin?.lastError?.let { importError = "Installed, with a note: $it" }
                }
                is PluginActionResult.Failure -> importError = result.message
                is PluginActionResult.NotAvailableYet -> importError = result.reason
            }
        }
    }
    fun test(providerId: String) {
        testing = testing + providerId
        actions.io {
            try {
                music.services.metadata.testProvider(providerId)
            } finally {
                testing = testing - providerId
            }
        }
    }
    var addingStream by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SourceConnection?>(null) }
    val addons = data.connections.filter { it.adapter == EclipseAddonAdapter.ID }
    val servers = data.connections.filter { it.adapter != MusicSourceManager.STREAM_ADAPTER && it.adapter != EclipseAddonAdapter.ID }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 12.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("title") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Music integrations", style = MaterialTheme.typography.headlineLarge, color = tokens.colors.textPrimary)
                Text(
                    "Where this profile's music, song details, covers and lyrics come from. These belong to Music only: nothing here is shared with Streaming or Manga.",
                    style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary,
                )
            }
        }

        item("addons") { AddonsCard(addons, onRemove = { removing = it }) }

        item("import") {
            SettingsCard("Import a plugin", "The https address or the file of a DebridOne Music plugin manifest. A manifest names one of Music's adapters and its settings; it carries no code.") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DialogTextField(importText, { importText = it; importError = null }, "https://…/manifest.json or a file path", Modifier.weight(1f), onSubmit = { import(importText) })
                    MusicButton(if (importing) "Importing…" else "Import", { import(importText) }, enabled = !importing && importText.isNotBlank())
                    MusicButton("Choose a file", {
                        MusicPickers.choosePluginFile()?.let { import(it.toString()) }
                    }, primary = false, enabled = !importing)
                }
                importError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
                data.manifests.forEach { manifest ->
                    val connection = data.connections.firstOrNull { it.pluginId == manifest.id }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Extension, contentDescription = null, tint = music.style.accent, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(manifest.name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1)
                                StatusPill(manifest.kind.label)
                                manifest.version?.let { StatusPill("v$it") }
                                if (connection != null) StatusPill("Set up for this profile", PillTone.SUCCESS) else StatusPill("Not set up for this profile", PillTone.WARNING)
                            }
                            Text(details(manifest.description, "adapter: ${manifest.adapter}", manifest.baseUrl), style = small(), color = tokens.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (connection == null) {
                            MusicButton(if (manifest.connectsWithoutSignIn) "Set up" else "Connect", {
                                if (manifest.connectsWithoutSignIn) {
                                    actions.io {
                                        val setup = sources.connectPlugin(music.profileId, manifest)
                                        MusicNav.message = if (setup.problems.isEmpty()) "${manifest.name} is set up." else "${manifest.name} is set up; ${setup.problems.size} of its streams did not answer."
                                    }
                                } else {
                                    connectingPlugin = manifest
                                }
                            }, primary = false)
                        }
                        MusicIconButton(Icons.Rounded.Delete, "Remove ${manifest.name}", { removingPlugin = manifest })
                    }
                }
            }
        }

        item("catalogue") {
            SettingsCard("Catalogue", "The integrations built into DebridOne. Each one is a tested adapter; nothing is active until you install, connect or switch it on.") {
                IntegrationCard(
                    Icons.Rounded.Explore, "Audius", IntegrationKind.PLAYBACK_SOURCE,
                    "Open catalogue of independent artists: search, discover, play. Free, no account. Only tracks Audius streams freely are played.",
                    status = when {
                        audius == null -> null
                        !audius.enabled -> "Off" to PillTone.NEUTRAL
                        audius.lastTestOk == false -> "Needs attention" to PillTone.DANGER
                        else -> "Installed" to PillTone.SUCCESS
                    },
                    note = audius?.takeIf { it.lastTestOk == false }?.lastError,
                ) {
                    if (audius == null) {
                        MusicButton("Install", { actions.io { sources.add(music.profileId, AudiusAdapter.ID, SourceDraft("", "", "", "")); MusicNav.message = "Audius is installed for this profile." } })
                    } else {
                        MusicButton("Open in Discover", {
                            DiscoverState.sourceId = audius.id
                            ShellState.select(ShellSection.MUSIC)
                            MusicNav.root(MusicRoute.Discover)
                        }, primary = false)
                    }
                }
                IntegrationCard(
                    Icons.Rounded.Cloud, "Navidrome / OpenSubsonic", IntegrationKind.PERSONAL_SERVER,
                    "Your own Navidrome, Airsonic, Gonic or other Subsonic-compatible server: library, artists, albums, playlists, search, covers, streaming.",
                    status = data.connections.count { it.adapter == SubsonicAdapter.ID }.takeIf { it > 0 }?.let { plural(it, "connection") to PillTone.SUCCESS },
                ) { MusicButton("Connect", { adding = adapter(SubsonicAdapter.ID) }, primary = false) }
                IntegrationCard(
                    Icons.Rounded.Cloud, "Jellyfin Music", IntegrationKind.PERSONAL_SERVER,
                    "The music libraries of your own Jellyfin server: albums, artists, songs, playlists, search, covers, playback.",
                    status = data.connections.count { it.adapter == JellyfinAdapter.ID }.takeIf { it > 0 }?.let { plural(it, "connection") to PillTone.SUCCESS },
                ) { MusicButton("Connect", { adding = adapter(JellyfinAdapter.ID) }, primary = false) }
                val clouds = data.connections.filter { it.adapter == RealDebridAdapter.ID }
                IntegrationCard(
                    Icons.Rounded.Cloud, "Real-Debrid", IntegrationKind.PERSONAL_CLOUD,
                    "The audio files already in your own Real-Debrid cloud: play them and save them. Read-only, signed in on Real-Debrid's own page, and separate from addons.",
                    status = when {
                        clouds.isEmpty() -> null
                        clouds.any { it.lastTestOk == false } -> "Needs attention" to PillTone.DANGER
                        else -> plural(clouds.size, "account") to PillTone.SUCCESS
                    },
                    note = clouds.firstOrNull { it.lastTestOk == false }?.lastError,
                    noteIsError = true,
                ) { MusicButton(if (clouds.isEmpty()) "Sign in" else "Add an account", { signingInToRealDebrid = true }, primary = false) }
                providers.filter { it.id != "embedded-tags" }.forEach { provider ->
                    val state = health[provider.id]
                    IntegrationCard(
                        if (provider.id == "lrclib") Icons.Rounded.Lyrics else Icons.Rounded.Storage,
                        provider.name,
                        if (provider.id == "lrclib") IntegrationKind.LYRICS_PROVIDER else IntegrationKind.METADATA_PROVIDER,
                        provider.description,
                        status = when {
                            !provider.enabled -> "Off" to PillTone.NEUTRAL
                            state == null -> "On, not tested yet" to PillTone.NEUTRAL
                            state.ok -> "Working" to PillTone.SUCCESS
                            else -> "Not answering" to PillTone.DANGER
                        },
                        note = state?.let {
                            (if (it.ok) it.message else "Last error: ${it.message}") + "  ·  " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.checkedAt))
                        },
                        noteIsError = state?.ok == false,
                    ) {
                        MusicButton(if (provider.id in testing) "Testing…" else "Test", { test(provider.id) }, primary = false, enabled = provider.id !in testing)
                        Switch(
                            checked = provider.enabled,
                            onCheckedChange = { enabled -> scope.launch { module.metadataProviders.setEnabled(provider.id, enabled) } },
                            colors = SwitchDefaults.colors(checkedTrackColor = music.style.accent, checkedThumbColor = music.style.onAccent),
                        )
                    }
                }
                IntegrationCard(
                    Icons.Rounded.Folder, "Local Music", IntegrationKind.LOCAL_LIBRARY,
                    "Folders and files on this computer, with the tags and covers inside them. Read in place, never copied or changed.",
                    status = details(plural(data.folderCount, "folder"), plural(data.localSongs, "song")) to PillTone.SUCCESS,
                    note = health["embedded-tags"]?.message,
                ) {
                    MusicButton("Configure", {
                        ShellState.select(ShellSection.MUSIC)
                        MusicNav.root(MusicRoute.Settings)
                    }, primary = false)
                }
            }
        }

        item("servers") {
            SettingsCard("Connected for this profile", "The sources this profile has set up. Switch one off to keep it without using it; remove it to erase its songs and its saved sign-in.") {
                if (servers.isEmpty()) Text("Nothing connected yet.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                servers.forEachIndexed { index, connection ->
                    ConnectionRow(
                        connection = connection,
                        adapterName = sources.adapter(connection.adapter)?.displayName ?: connection.adapter,
                        busy = activity.syncing[connection.id],
                        canMoveUp = index > 0,
                        canMoveDown = index < servers.lastIndex,
                        onSync = {
                            module.syncSource(connection.id) { result ->
                                MusicNav.message = result.fold(
                                    { "${connection.name}: ${plural(it.total, "song")}" + (if (it.added > 0 || it.removed > 0) " (${it.added} new, ${it.removed} removed)" else ", nothing changed") },
                                    { "${connection.name}: ${it.message}" },
                                )
                            }
                        },
                        onTest = {
                            actions.io {
                                val result = sources.test(music.profileId, connection.id)
                                MusicNav.message = result.fold({ "${connection.name} accepts the saved sign-in." }, { "${connection.name}: ${it.message}" })
                            }
                        },
                        onEnabled = { enabled -> actions.io { sources.setEnabled(music.profileId, connection.id, enabled) } },
                        onMove = { up -> actions.io { sources.move(music.profileId, connection.id, up) } },
                        onRemove = { removing = connection },
                        syncs = sources.adapter(connection.adapter)?.syncsLibrary != false,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    MusicButton("Connect a catalogue server", { adding = adapter(HttpCatalogAdapter.ID) }, icon = Icons.Rounded.Cloud, primary = false)
                }
                Text(
                    (if (sources.credentialStoreIsProtected) "Passwords and sign-in tokens are kept in the ${sources.credentialStoreName}, protected by the system."
                    else "Passwords and sign-in tokens are kept in an encrypted file, because this system has no keychain.") +
                        " They are never written to the music database.",
                    style = small(), color = tokens.colors.textMuted,
                )
            }
        }

        item("streams") {
            SettingsCard("Radio and stream addresses", "Internet radio and other direct audio streams. An address is opened first and added only if it really serves audio.") {
                if (data.streams.isEmpty()) Text("No stream added.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                data.streams.forEach { stream ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Radio, contentDescription = null, tint = music.style.accent, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stream.title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1)
                            Text(stream.remoteId.orEmpty().substringBefore('?'), style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                        MusicButton("Play", { actions.play(listOf(stream)) }, primary = false)
                        MusicIconButton(Icons.Rounded.Delete, "Remove ${stream.title}", { MusicNav.dialog = MusicDialog.RemoveTracks(listOf(stream.id), "“${stream.title}”") })
                    }
                }
                MusicButton("Add a stream address", { addingStream = true }, icon = Icons.Rounded.Radio, primary = false, modifier = Modifier.padding(top = 6.dp))
            }
        }

        item("unsupported") {
            SettingsCard("Not offered", null) {
                Text(
                    "Spotify, Apple Music, Tidal, Deezer, YouTube Music and similar services cannot be added. Their terms and copy protection do not allow " +
                        "another player to stream their catalogues, and DebridOne does not work around that.",
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                )
                Text(
                    "Plugins and addons are data and servers, never program code: Streaming add-ons and Manga extensions are refused here, and nothing from a plugin or an addon is run inside DebridOne. " +
                        "Addons that answer the Eclipse app only (they check a token that only Eclipse can issue) will refuse DebridOne, and protected (encrypted) streams are not played.",
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                )
            }
        }
    }

    adding?.let { adapter -> AddServerDialog(adapter, onDismiss = { adding = null }) }
    if (signingInToRealDebrid) RealDebridSignInDialog(onDismiss = { signingInToRealDebrid = false })
    connectingPlugin?.let { manifest -> AddServerDialog(adapter(manifest.adapter), plugin = manifest, onDismiss = { connectingPlugin = null }) }
    removingPlugin?.let { manifest ->
        ConfirmDialog(
            title = "Remove ${manifest.name}?",
            message = "The plugin is removed for every profile, with the songs or stations it brought, their offline copies and any saved sign-in.",
            confirm = "Remove", onDismiss = { removingPlugin = null },
        ) {
            scope.launch {
                val result = module.plugins.remove(manifest.id)
                MusicNav.message = if (result is PluginActionResult.Failure) result.message else "Removed ${manifest.name}."
            }
        }
    }
    if (addingStream) AddStreamDialog(onDismiss = { addingStream = false })
    removing?.let { connection ->
        ConfirmDialog(
            title = "Remove ${connection.name}?",
            message = if (connection.adapter == EclipseAddonAdapter.ID) {
                "The addon is removed from this profile: its ${plural(connection.trackCount, "song")} leave the library and playlists, saved copies are deleted, and its address is erased from the ${sources.credentialStoreName}."
            } else {
                "Its ${plural(connection.trackCount, "song")} leave this profile's library and playlists, downloaded copies are deleted, and the saved sign-in is erased. Nothing changes on the server."
            },
            confirm = "Remove", onDismiss = { removing = null },
        ) {
            actions.io {
                sources.remove(music.profileId, connection.id)
                music.services.player.removeDeletedFromQueue()
            }
        }
    }
    MusicDialogs()
}

@Composable
private fun ConnectionRow(
    connection: SourceConnection,
    adapterName: String,
    busy: String?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSync: () -> Unit,
    onTest: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onMove: (Boolean) -> Unit,
    onRemove: () -> Unit,
    /** False for a catalogue that is searched rather than copied: there is nothing to synchronise. */
    syncs: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            if (connection.enabled) Icons.Rounded.Cloud else Icons.Rounded.CloudOff, contentDescription = null,
            tint = if (connection.enabled) music.style.accent else tokens.colors.textMuted, modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(connection.name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1)
                when {
                    !connection.enabled -> StatusPill("Off")
                    connection.lastTestOk == false -> StatusPill("Needs attention", PillTone.DANGER)
                    connection.lastSyncAt != null || !syncs -> StatusPill("Connected", PillTone.SUCCESS)
                    else -> StatusPill("Not synchronised yet", PillTone.WARNING)
                }
            }
            Text(
                details(adapterName, connection.baseUrl, connection.username?.takeIf { it.isNotBlank() }, plural(connection.trackCount, "song") + if (syncs) "" else " added to your library"),
                style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val line = when {
                busy != null -> busy
                connection.lastTestOk == false -> connection.lastError ?: "The last check failed."
                connection.lastSyncAt != null -> "Synchronised " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(connection.lastSyncAt!!))
                else -> null
            }
            line?.let { Text(it, style = small(), color = if (busy == null && connection.lastTestOk == false) tokens.colors.danger else tokens.colors.textSecondary, maxLines = 2) }
        }
        if (busy != null) {
            CircularProgressIndicator(color = music.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            if (syncs) MusicIconButton(Icons.Rounded.Sync, "Synchronise ${connection.name} now", onSync, enabled = connection.enabled)
            MusicIconButton(Icons.Rounded.Verified, "Check the sign-in of ${connection.name}", onTest)
        }
        MusicIconButton(Icons.Rounded.ArrowUpward, "Move ${connection.name} up", { onMove(true) }, size = 30.dp, enabled = canMoveUp)
        MusicIconButton(Icons.Rounded.ArrowDownward, "Move ${connection.name} down", { onMove(false) }, size = 30.dp, enabled = canMoveDown)
        Switch(
            checked = connection.enabled, onCheckedChange = onEnabled,
            colors = SwitchDefaults.colors(checkedTrackColor = music.style.accent, checkedThumbColor = music.style.onAccent),
        )
        MusicIconButton(Icons.Rounded.Delete, "Remove ${connection.name}", onRemove)
    }
}

/** One row of the catalogue of integrations: what it is, its state, and what can be done with it. */
@Composable
private fun IntegrationCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    kind: IntegrationKind,
    description: String,
    status: Pair<String, PillTone>? = null,
    note: String? = null,
    noteIsError: Boolean = false,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, contentDescription = null, tint = LocalMusic.current.style.accent, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, maxLines = 1)
                StatusPill(kind.label)
                status?.let { (text, tone) -> StatusPill(text, tone) }
            }
            Text(description, style = small(), color = tokens.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            note?.let { Text(it, style = small(), color = if (noteIsError) tokens.colors.danger else tokens.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

/**
 * Sign-in form of a source: the fields its adapter asks for. With [plugin], the address comes from
 * the plugin's manifest. The connection is saved only if the source accepts the sign-in.
 */
@Composable
private fun AddServerDialog(adapter: MusicSourceAdapter, plugin: MusicPluginManifest? = null, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(plugin?.baseUrl.orEmpty()) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val needsUser = adapter.fields.any { it.key == "username" }
    val secretField = adapter.fields.firstOrNull { it.secret }
    val ready = address.isNotBlank() && (!needsUser || user.isNotBlank())
    val submit = submit@{
        if (working || !ready) return@submit
        working = true
        error = null
        scope.launch {
            val draft = SourceDraft(name, address, user, password)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (plugin != null) music.services.sources.connectPlugin(music.profileId, plugin, draft).connection
                    else music.services.sources.add(music.profileId, adapter.id, draft)
                }
            }
            working = false
            result.fold(
                { connection ->
                    onDismiss()
                    if (adapter.syncsLibrary) {
                        MusicNav.message = "Connected to ${connection.name}. Reading its library…"
                        DebridOneRuntime.modules.music.syncSource(connection.id) { synced ->
                            MusicNav.message = synced.fold({ "${connection.name}: ${plural(it.total, "song")} added to your library" }, { "${connection.name}: ${it.message}" })
                        }
                    } else {
                        MusicNav.message = "Connected to ${connection.name}. Find it under Discover."
                    }
                },
                { error = it.message ?: "The connection could not be made." },
            )
        }
    }
    MusicDialogFrame(if (plugin != null) "Connect ${plugin.name}" else "Connect ${adapter.displayName}", onDismiss = { if (!working) onDismiss() }, width = 520) {
        Text(plugin?.description ?: adapter.description, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        if (plugin?.baseUrl != null) {
            Text("Address: ${plugin.baseUrl}", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
        } else {
            DialogTextField(address, { address = it; error = null }, adapter.fields.firstOrNull { it.key == "baseUrl" }?.label ?: "Address", focusRequester = focus, onSubmit = submit)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (needsUser) DialogTextField(user, { user = it; error = null }, "User name", Modifier.weight(1f), onSubmit = submit)
            if (secretField != null) {
                DialogTextField(password, { password = it; error = null }, secretField.label + if (secretField.optional) " (optional)" else "", Modifier.weight(1f), onSubmit = submit, secret = true)
            }
        }
        DialogTextField(name, { name = it }, "Name shown in DebridOne (optional)", onSubmit = submit)
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
        Text(
            "DebridOne signs in now and saves the connection only if the source accepts it. " +
                (if (secretField != null) "The ${secretField.label.lowercase()} goes to the ${music.services.sources.credentialStoreName}, never to the database." else ""),
            style = small(), color = tokens.colors.textMuted,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (working) CircularProgressIndicator(color = music.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Box(Modifier.size(10.dp))
            MusicButton("Cancel", onDismiss, primary = false, enabled = !working)
            Box(Modifier.size(10.dp))
            MusicButton(if (working) "Signing in…" else "Connect", submit, enabled = !working && ready)
        }
    }
}

@Composable
private fun AddStreamDialog(onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = submit@{
        if (working || address.isBlank()) return@submit
        working = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { music.services.sources.addStream(music.profileId, name, address) } }
            working = false
            result.fold({ onDismiss(); MusicNav.message = "Added ${it.title}" }, { error = it.message ?: "That address could not be added." })
        }
    }
    MusicDialogFrame("Add a stream address", onDismiss = { if (!working) onDismiss() }, width = 520) {
        DialogTextField(address, { address = it; error = null }, "Address (http or https)", focusRequester = focus, onSubmit = submit)
        DialogTextField(name, { name = it }, "Name", onSubmit = submit)
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
        Text("The address is opened now. It is added only if it answers with audio, or with a playlist that points at audio.", style = small(), color = tokens.colors.textMuted)
        DialogButtons(if (working) "Checking…" else "Add", onDismiss, enabled = !working && address.isNotBlank(), onConfirm = submit)
    }
}
