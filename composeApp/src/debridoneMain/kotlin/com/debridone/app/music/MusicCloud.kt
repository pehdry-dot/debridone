package com.debridone.app.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.music.library.Track
import com.debridone.music.library.TrackFilter
import com.debridone.music.library.TrackSort
import com.debridone.music.sources.SourceConnection
import com.nuvio.app.core.ui.nuvio

private enum class CloudView(val label: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists") }

/** Songs of the cloud that belong together: an album or an artist, as their file names and tags say. */
private class CloudGroup(val key: String, val title: String, val subtitle: String, val tracks: List<Track>)

/** What the Cloud Music tab is showing. Kept outside composables so it survives leaving the tab. */
private object CloudMusicState {
    var view by mutableStateOf(CloudView.SONGS)

    /** The one account being shown (its connection in Music), or null for all of them. */
    var account by mutableStateOf<Long?>(null)

    /** The album or artist that is open. */
    var open by mutableStateOf<String?>(null)
}

/**
 * Library › Cloud Music: the audio files of the accounts connected in Settings, listed as songs,
 * albums and artists. They are **not** in the library until the listener adds them, and nothing
 * here changes the accounts. Names come from file names and folders until a song is saved to this
 * computer, when its own tags and cover are read.
 */
@Composable
internal fun CloudMusicTab() {
    val tokens = MaterialTheme.nuvio
    val connections by rememberMusicQuery(initial = null as List<SourceConnection>?) { services, profile -> services.sources.cloudConnections(profile) }
    val accounts = connections ?: return
    if (accounts.isEmpty()) {
        MusicEmptyState(
            Icons.Rounded.Cloud, "No cloud account for this profile",
            "Connect Real-Debrid or TorBox in Settings › Connected Accounts. The audio files already in your account are then listed here. Nothing is added to your account, and nothing joins your library until you add it.",
        ) { MusicCloudBridge.openConnectedAccounts?.let { open -> MusicButton("Open Connected Accounts", open) } }
        return
    }
    val shown = accounts.filter { CloudMusicState.account == null || it.id == CloudMusicState.account }.ifEmpty { accounts }
    val ids = shown.map { it.id }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CloudView.entries.forEach { view ->
                MusicChip(view.label, CloudMusicState.view == view) {
                    CloudMusicState.view = view
                    CloudMusicState.open = null
                }
            }
            if (accounts.size > 1) {
                Spacer(Modifier.width(12.dp))
                MusicChip("All accounts", CloudMusicState.account == null) { CloudMusicState.account = null; CloudMusicState.open = null }
                accounts.forEach { account ->
                    MusicChip(account.name, CloudMusicState.account == account.id) { CloudMusicState.account = account.id; CloudMusicState.open = null }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (accounts.size == 1) "From ${accounts[0].name}" else "From ${plural(accounts.size, "account")}",
                style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            MusicCloudBridge.refreshAccounts?.let { refresh ->
                MusicButton("Refresh", {
                    refresh()
                    MusicNav.message = "Reading your ${if (accounts.size == 1) "account" else "accounts"} again…"
                }, primary = false)
            }
            MusicCloudBridge.openCloudFiles?.let { open -> MusicButton("Show files", open, primary = false) }
        }
        when (CloudMusicState.view) {
            CloudView.SONGS -> SongsTab(
                TrackFilter(sourceIds = ids, includeHidden = true),
                emptyTitle = "No audio files",
                emptyMessage = "There is no finished audio file in ${if (shown.size == 1) shown[0].name else "these accounts"}. Refresh after something new arrives in your account.",
            )
            CloudView.ALBUMS, CloudView.ARTISTS -> CloudGroups(ids, CloudMusicState.view)
        }
    }
}

@Composable
private fun CloudGroups(sourceIds: List<Long>, view: CloudView) {
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val tracks by rememberMusicQuery(sourceIds, initial = null as List<Track>?) { services, profile ->
        services.library.tracks(profile, TrackFilter(sourceIds = sourceIds, includeHidden = true), TrackSort.ALBUM)
    }
    val all = tracks ?: return
    val groups = if (view == CloudView.ALBUMS) albums(all) else artists(all)
    val open = groups.firstOrNull { it.key == CloudMusicState.open }
    if (open != null) {
        CloudGroupPage(open, view)
        return
    }
    if (groups.isEmpty()) {
        MusicEmptyState(
            if (view == CloudView.ALBUMS) Icons.Rounded.Album else Icons.Rounded.Person,
            if (view == CloudView.ALBUMS) "No albums" else "No artists",
            if (all.isEmpty()) "There is no finished audio file in these accounts." else "The files carry no folder or name to group by. They are all under Songs.",
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(music.style.cardSize),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups, key = { it.key }) { group ->
            MediaCard(group.title, group.subtitle, onClick = { CloudMusicState.open = group.key }, onPlay = { actions.play(group.tracks) }) {
                ArtworkImage(
                    group.tracks.firstNotNullOfOrNull { it.artwork }, Modifier.fillMaxSize(),
                    shape = if (view == CloudView.ALBUMS) RoundedCornerShape(12.dp) else CircleShape,
                    placeholder = if (view == CloudView.ALBUMS) Icons.Rounded.Album else Icons.Rounded.Person,
                )
            }
        }
    }
}

@Composable
private fun CloudGroupPage(group: CloudGroup, view: CloudView) {
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val tokens = MaterialTheme.nuvio
    val playerState by music.services.player.state.collectAsStateWithLifecycle()
    val notInLibrary = group.tracks.filter { it.hidden }
    val notSaved = group.tracks.filter { it.offlinePath == null }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MusicIconButton(Icons.AutoMirrored.Rounded.ArrowBack, if (view == CloudView.ALBUMS) "Back to the albums" else "Back to the artists", { CloudMusicState.open = null })
            Column(Modifier.weight(1f)) {
                Text(group.title, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(group.subtitle, style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayShuffleButtons({ actions.play(group.tracks) }, { actions.play(group.tracks, shuffle = true) })
            if (notInLibrary.isNotEmpty()) MusicButton("Add to library", { actions.keep(notInLibrary) }, icon = Icons.Rounded.LibraryAdd, primary = false)
            if (notSaved.isNotEmpty()) MusicButton("Download", { actions.download(notSaved) }, icon = Icons.Rounded.DownloadForOffline, primary = false)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp)) {
            itemsIndexed(group.tracks, key = { _, track -> track.id }) { index, track ->
                TrackRow(
                    track = track,
                    actions = rememberTrackActions(track, group.tracks, index),
                    leading = if (view == CloudView.ALBUMS) (track.trackNo ?: (index + 1)).toString() else null,
                    showAlbum = view != CloudView.ALBUMS,
                    isCurrent = track.id == playerState.current?.trackId,
                )
            }
        }
    }
}

private fun albums(tracks: List<Track>): List<CloudGroup> =
    tracks.filter { !it.album.isNullOrBlank() }
        .groupBy { (it.album.orEmpty().lowercase()) to (it.albumArtist ?: it.artist).orEmpty().lowercase() }
        .map { (key, songs) ->
            val artist = songs.firstNotNullOfOrNull { it.albumArtist ?: it.artist }
            val ordered = songs.sortedWith(compareBy({ it.discNo ?: 1 }, { it.trackNo ?: 9999 }, { it.title.lowercase() }))
            CloudGroup("${key.first}\u0000${key.second}", songs.first().displayAlbum, details(artist, plural(songs.size, "song")), ordered)
        }
        .sortedBy { it.title.lowercase() }

private fun artists(tracks: List<Track>): List<CloudGroup> =
    tracks.filter { !(it.artist ?: it.albumArtist).isNullOrBlank() }
        .groupBy { it.displayArtist.lowercase() }
        .map { (key, songs) ->
            val albums = songs.mapNotNull { it.album?.lowercase() }.distinct().size
            val ordered = songs.sortedWith(compareBy({ it.album.orEmpty().lowercase() }, { it.discNo ?: 1 }, { it.trackNo ?: 9999 }, { it.title.lowercase() }))
            CloudGroup(key, songs.first().displayArtist, details(if (albums > 0) plural(albums, "album") else null, plural(songs.size, "song")), ordered)
        }
        .sortedBy { it.title.lowercase() }
