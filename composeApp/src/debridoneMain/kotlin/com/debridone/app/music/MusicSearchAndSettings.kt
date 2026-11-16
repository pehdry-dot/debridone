package com.debridone.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.DebridOneRuntime
import com.debridone.app.shell.SettingsPage
import com.debridone.app.shell.ShellState
import com.debridone.module.ModuleId
import com.debridone.music.MusicPreferences
import com.debridone.music.library.AudioTagReader
import com.debridone.music.library.LibraryFolder
import com.debridone.music.library.LibraryStats
import com.debridone.music.library.SearchResults
import com.debridone.music.library.Track
import com.debridone.music.playback.AudioDevice
import com.debridone.music.playlists.Playlist
import com.nuvio.app.core.ui.nuvio
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------------------- search

@Composable
internal fun MusicSearch() {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val text = MusicNav.searchText
    val results by rememberMusicQuery(text, initial = null as Pair<SearchResults, List<Playlist>>?) { services, profile ->
        val key = text.trim().lowercase()
        services.library.search(profile, text) to
            if (key.isEmpty()) emptyList() else services.playlists.playlists(profile).filter { it.name.lowercase().contains(key) }
    }
    val playerState by music.services.player.state.collectAsStateWithLifecycle()

    if (text.isBlank()) {
        MusicEmptyState(Icons.Rounded.Search, "Search your music", "Songs, albums, artists, genres and playlists. Accents and capitals do not matter.")
        return
    }
    val (found, playlists) = results ?: return
    if (found.isEmpty && playlists.isEmpty()) {
        MusicEmptyState(Icons.Rounded.Search, "Nothing found for “$text”", "Check the spelling, or try fewer words.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (found.artists.isNotEmpty()) {
            item("artists") {
                MusicSectionHeader("Artists")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(found.artists, key = { it.id }) { artist ->
                        ArtistCard(artist, { MusicNav.open(MusicRoute.ArtistPage(artist.id)) }, Modifier.width(music.style.cardSize - 24.dp))
                    }
                }
            }
        }
        if (found.albums.isNotEmpty()) {
            item("albums") {
                MusicSectionHeader("Albums")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(found.albums, key = { it.id }) { album ->
                        AlbumCard(album, { MusicNav.open(MusicRoute.AlbumPage(album.id)) }, { actions.playAlbum(album.id) }, Modifier.width(music.style.cardSize))
                    }
                }
            }
        }
        if (playlists.isNotEmpty()) {
            item("playlists") {
                MusicSectionHeader("Playlists")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        PlaylistCard(playlist, { MusicNav.open(MusicRoute.PlaylistPage(playlist.id)) }, { actions.playPlaylist(playlist.id) }, Modifier.width(music.style.cardSize))
                    }
                }
            }
        }
        if (found.tracks.isNotEmpty()) {
            item("songs") {
                MusicSectionHeader("Songs")
                Text("${found.tracks.size}${if (found.tracks.size >= 40) "+" else ""} matching", style = small(), color = tokens.colors.textMuted)
            }
            itemsIndexed(found.tracks, key = { _, track -> track.id }) { index, track ->
                TrackRow(track, rememberTrackActions(track, found.tracks, index), isCurrent = playerState.current?.trackId == track.id)
            }
        }
    }
}

// ---------------------------------------------------------------------------- settings

private class SettingsData(
    val folders: List<LibraryFolder> = emptyList(),
    val stats: LibraryStats = LibraryStats(0, 0, 0, 0, 0, 0),
    val duplicates: List<List<Track>> = emptyList(),
    val historySize: Int = 0,
)

@Composable
internal fun MusicSettings() {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val module = DebridOneRuntime.modules.music
    val preferences by music.services.preferences.values.collectAsStateWithLifecycle()
    val scan by music.services.scanner.progress.collectAsStateWithLifecycle()
    val engine by music.services.player.engineDescription.collectAsStateWithLifecycle()
    val engineError by music.services.player.engineError.collectAsStateWithLifecycle()
    val data by rememberMusicQuery(initial = SettingsData()) { services, profile ->
        SettingsData(
            services.library.folders(profile), services.library.stats(profile),
            services.library.duplicateGroups(profile), services.library.history(profile, 5_000).size,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("library") {
            SettingsCard("Music folders", "Songs are read where they are. Nothing is copied, moved or changed.") {
                if (data.folders.isEmpty()) {
                    Text("No folder added yet.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                }
                data.folders.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(
                            if (folder.available) Icons.Rounded.Folder else Icons.Rounded.FolderOff, contentDescription = null,
                            tint = if (folder.available) music.style.accent else tokens.colors.warning, modifier = Modifier.size(22.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(folder.path, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            Text(
                                if (!folder.available) "This folder cannot be reached right now. Its songs are kept and marked as missing."
                                else listOfNotNull(
                                    plural(folder.trackCount, "song"),
                                    folder.lastScanAt?.let { "checked " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
                                ).joinToString(" · "),
                                style = small(), color = if (folder.available) tokens.colors.textMuted else tokens.colors.warning,
                            )
                        }
                        MusicIconButton(Icons.Rounded.Delete, "Remove ${folder.path} from the library", { MusicNav.dialog = MusicDialog.RemoveFolder(folder.id, folder.path) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    MusicButton("Add a folder", { MusicNav.dialog = MusicDialog.AddFolder }, icon = Icons.Rounded.CreateNewFolder)
                    MusicButton("Add files", {
                        val files = MusicPickers.chooseAudioFiles()
                        if (files.isNotEmpty()) module.importFiles(files)
                    }, icon = Icons.Rounded.NoteAdd, primary = false)
                    MusicButton("Rescan now", { module.rescan() }, icon = Icons.Rounded.Sync, primary = false, enabled = !scan.running)
                }
                val summary = when {
                    scan.running -> "Scanning ${scan.filesProcessed} of ${scan.filesFound} files" + (scan.currentFile?.let { ": $it" } ?: "")
                    scan.error != null -> "The last scan failed: ${scan.error}"
                    scan.finishedAt != null -> with(scan.result) {
                        "Last scan: " + listOf(
                            "$added added", "$updated updated", "$relinked moved or renamed", "$missing missing",
                            "$unreadable unreadable", "$duplicates duplicate",
                        ).joinToString(", ")
                    }
                    else -> null
                }
                summary?.let { Text(it, style = small(), color = if (scan.error != null) tokens.colors.danger else tokens.colors.textSecondary) }
                ToggleRow(
                    "Check for changes when DebridOne starts",
                    "Looks for new, changed, moved and deleted files. Unchanged files are not opened again.",
                    preferences[MusicPreferences.RESCAN_ON_START]?.toBooleanStrictOrNull() ?: true,
                ) { music.services.preferences.set(MusicPreferences.RESCAN_ON_START, it.toString()) }
            }
        }

        if (data.stats.missing > 0 || data.duplicates.isNotEmpty()) {
            item("health") {
                SettingsCard("Library check", null) {
                    if (data.stats.missing > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${plural(data.stats.missing, "song")} cannot be found on disk. They stay in your playlists and come back when the files do.",
                                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            MusicButton("Remove them", { actions.io { music.services.library.removeMissing(music.profileId); music.services.player.removeDeletedFromQueue() } }, primary = false)
                        }
                    }
                    if (data.duplicates.isNotEmpty()) {
                        Text(
                            "${plural(data.duplicates.size, "song")} exist as identical copies in more than one place:",
                            style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                        )
                        data.duplicates.take(20).forEach { group ->
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(group.first().title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
                                group.forEach { copy ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(copy.path.orEmpty(), style = small(), color = tokens.colors.textMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                                        MusicIconButton(Icons.Rounded.Delete, "Remove this copy from the library", { MusicNav.dialog = MusicDialog.RemoveTracks(listOf(copy.id), "this copy of “${copy.title}”") }, size = 28.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item("playback") {
            SettingsCard("Playback", null) {
                OutputDeviceRow()
                ToggleRow(
                    "Continue playing when DebridOne starts",
                    "Otherwise your last queue is restored paused, at the position you left it.",
                    preferences[MusicPreferences.RESUME_ON_START] == "true",
                ) { music.services.preferences.set(MusicPreferences.RESUME_ON_START, if (it) "true" else null) }
                engineError?.let { Text("The audio engine could not be started: $it", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
                Text(
                    "Engine: " + (engine ?: "starts when you first play something"),
                    style = small(), color = tokens.colors.textMuted,
                )
                Text(
                    "Plays " + AudioTagReader.supportedExtensions.sorted().joinToString(", ") { it.uppercase() } + " (MP3, FLAC, AAC, ALAC, WAV, AIFF, Ogg Vorbis, Opus).",
                    style = small(), color = tokens.colors.textMuted,
                )
            }
        }

        item("home") {
            SettingsCard("Home", "Choose what the Music home page shows for this profile.") {
                val hidden = HomeSection.hidden(preferences[MusicPreferences.HOME_HIDDEN_SECTIONS])
                HomeSection.entries.forEach { section ->
                    ToggleRow(section.label, null, section.key !in hidden) { visible ->
                        val next = if (visible) hidden - section.key else hidden + section.key
                        music.services.preferences.set(MusicPreferences.HOME_HIDDEN_SECTIONS, next.sorted().joinToString(",").ifEmpty { null })
                    }
                }
                ToggleRow(
                    "Open Music on the library",
                    "Otherwise Music opens on Home.",
                    preferences[MusicPreferences.DEFAULT_VIEW] == "library",
                ) { music.services.preferences.set(MusicPreferences.DEFAULT_VIEW, if (it) "library" else null) }
            }
        }

        item("more") {
            SettingsCard("More", null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MusicButton("Music appearance", { ShellState.openSettings(SettingsPage.APPEARANCE, ModuleId.MUSIC) }, icon = Icons.Rounded.Palette, primary = false)
                    MusicButton("Music sources", { ShellState.openPlugins(ModuleId.MUSIC) }, icon = Icons.Rounded.Extension, primary = false)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (data.historySize == 0) "No listening history yet." else "Listening history: ${plural(data.historySize, "play")} recorded for this profile.",
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, modifier = Modifier.weight(1f),
                    )
                    MusicButton("Clear history", { actions.io { music.services.library.clearHistory(music.profileId) } }, primary = false, enabled = data.historySize > 0)
                }
            }
        }
    }
}

@Composable
internal fun SettingsCard(title: String, caption: String?, content: @Composable ColumnScope.() -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(18.dp))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        content()
    }
}

@Composable
internal fun ToggleRow(title: String, caption: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val accent = LocalMusic.current.style.accent
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
            if (caption != null) Text(caption, style = small(), color = tokens.colors.textMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = LocalMusic.current.style.onAccent),
        )
    }
}

@Composable
private fun OutputDeviceRow() {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val preferences by music.services.preferences.values.collectAsStateWithLifecycle()
    val selected = preferences[MusicPreferences.AUDIO_DEVICE] ?: "auto"
    var open by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(emptyList<AudioDevice>()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Output device", style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
            Text(
                if (selected == "auto") "Follows the system output, including when you plug in or switch devices."
                else "Fixed to one device. If it is unplugged, playback falls back to the system output.",
                style = small(), color = tokens.colors.textMuted,
            )
        }
        Box {
            Text(
                devices.firstOrNull { it.id == selected }?.description ?: if (selected == "auto") "System default" else selected,
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(50))
                    .background(tokens.colors.surfaceElevated)
                    .clickable(role = Role.DropdownList) {
                        devices = music.services.player.audioDevices()
                        open = true
                    }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                val shown = devices.ifEmpty { listOf(AudioDevice("auto", "System default")) }
                shown.forEach { device ->
                    MenuItem(if (device.id == "auto") "System default" else device.description, if (device.id == selected) Icons.Rounded.Check else null) {
                        open = false
                        music.services.preferences.set(MusicPreferences.AUDIO_DEVICE, device.id.takeIf { it != "auto" })
                        music.services.player.setAudioDevice(device.id)
                    }
                }
                if (devices.isEmpty()) {
                    Text("Other devices are listed once something has been played.", style = small(), color = tokens.colors.textMuted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp).width(260.dp))
                }
            }
        }
    }
}
