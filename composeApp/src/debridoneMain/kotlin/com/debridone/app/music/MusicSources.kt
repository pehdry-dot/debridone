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
        )
    }
    var adding by remember { mutableStateOf<MusicSourceAdapter?>(null) }
    var addingStream by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SourceConnection?>(null) }
    val servers = data.connections.filter { it.adapter != MusicSourceManager.STREAM_ADAPTER }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 12.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("title") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Music sources", style = MaterialTheme.typography.headlineLarge, color = tokens.colors.textPrimary)
                Text(
                    "Where this profile's music comes from. These belong to Music only and to this profile only.",
                    style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary,
                )
            }
        }

        item("local") {
            SettingsCard("Files on this computer", "Folders and files you add. Read in place, never copied.") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = music.style.accent, modifier = Modifier.size(22.dp))
                    Text(
                        details(plural(data.folderCount, "folder"), plural(data.localSongs, "song")),
                        style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f),
                    )
                    StatusPill("Always on", PillTone.SUCCESS)
                    MusicButton("Manage folders", {
                        ShellState.select(ShellSection.MUSIC)
                        MusicNav.root(MusicRoute.Settings)
                    }, primary = false)
                }
            }
        }

        item("servers") {
            SettingsCard("Your music servers", "Sign in to a server you run or have an account on. Its songs join this profile's library and play from the server.") {
                if (servers.isEmpty()) Text("No server connected.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
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
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    sources.adapters.forEach { adapter -> MusicButton("Add ${adapter.displayName}", { adding = adapter }, icon = Icons.Rounded.Cloud, primary = servers.isEmpty()) }
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

        item("metadata") {
            SettingsCard("Metadata providers", "Where song details, covers and lyrics come from. Used by Music only.") {
                providers.forEach { provider ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(provider.name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
                                if (!provider.implemented) StatusPill("Not in this build", PillTone.WARNING)
                            }
                            Text(provider.description, style = small(), color = tokens.colors.textMuted)
                        }
                        Switch(
                            checked = provider.enabled && provider.implemented,
                            enabled = provider.implemented && provider.id != "embedded-tags",
                            onCheckedChange = { enabled -> scope.launch { module.metadataProviders.setEnabled(provider.id, enabled) } },
                            colors = SwitchDefaults.colors(checkedTrackColor = music.style.accent, checkedThumbColor = music.style.onAccent),
                        )
                    }
                }
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
                    "Plugin files or addresses from elsewhere are not accepted either: a source only exists here as a connection DebridOne itself can sign in to and check. " +
                        "Eclipse Music add-ons are not supported; their format is not published.",
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                )
            }
        }
    }

    adding?.let { adapter -> AddServerDialog(adapter, onDismiss = { adding = null }) }
    if (addingStream) AddStreamDialog(onDismiss = { addingStream = false })
    removing?.let { connection ->
        ConfirmDialog(
            title = "Remove ${connection.name}?",
            message = "Its ${plural(connection.trackCount, "song")} leave this profile's library and playlists, downloaded copies are deleted, and the saved sign-in is erased. Nothing changes on the server.",
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
                    connection.lastSyncAt != null -> StatusPill("Connected", PillTone.SUCCESS)
                    else -> StatusPill("Not synchronised yet", PillTone.WARNING)
                }
            }
            Text(
                details(adapterName, connection.baseUrl, connection.username, plural(connection.trackCount, "song")),
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
            MusicIconButton(Icons.Rounded.Sync, "Synchronise ${connection.name} now", onSync, enabled = connection.enabled)
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

/** Sign-in form of a server. The connection is saved only if the server accepts the sign-in. */
@Composable
private fun AddServerDialog(adapter: MusicSourceAdapter, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = submit@{
        if (working || address.isBlank() || user.isBlank()) return@submit
        working = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { music.services.sources.add(music.profileId, adapter.id, SourceDraft(name, address, user, password)) }
            }
            working = false
            result.fold(
                { connection ->
                    onDismiss()
                    MusicNav.message = "Connected to ${connection.name}. Reading its library…"
                    DebridOneRuntime.modules.music.syncSource(connection.id) { synced ->
                        MusicNav.message = synced.fold({ "${connection.name}: ${plural(it.total, "song")} added to your library" }, { "${connection.name}: ${it.message}" })
                    }
                },
                { error = it.message ?: "The connection could not be made." },
            )
        }
    }
    MusicDialogFrame("Add ${adapter.displayName}", onDismiss = { if (!working) onDismiss() }, width = 520) {
        Text(adapter.description, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        DialogTextField(address, { address = it; error = null }, "Server address", focusRequester = focus, onSubmit = submit)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DialogTextField(user, { user = it; error = null }, "User name", Modifier.weight(1f), onSubmit = submit)
            DialogTextField(password, { password = it; error = null }, "Password", Modifier.weight(1f), onSubmit = submit, secret = true)
        }
        DialogTextField(name, { name = it }, "Name shown in DebridOne (optional)", onSubmit = submit)
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
        Text("DebridOne signs in now and saves the connection only if the server accepts it.", style = small(), color = tokens.colors.textMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (working) CircularProgressIndicator(color = music.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Box(Modifier.size(10.dp))
            MusicButton("Cancel", onDismiss, primary = false, enabled = !working)
            Box(Modifier.size(10.dp))
            MusicButton(if (working) "Signing in…" else "Connect", submit, enabled = !working && address.isNotBlank() && user.isNotBlank())
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
