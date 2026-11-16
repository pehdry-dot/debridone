package com.debridone.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.music.library.Album
import com.debridone.music.library.Artist
import com.debridone.music.library.ArtworkStore
import com.debridone.music.library.Track
import com.debridone.music.library.TrackFilter
import com.debridone.music.playlists.Playlist
import com.debridone.music.playlists.PlaylistEntry
import com.nuvio.app.core.ui.nuvio

/** The large header of an album, artist or playlist page, tinted by the artwork when enabled. */
@Composable
private fun DetailHero(
    kind: String,
    title: String,
    meta: String,
    tint: Int?,
    artwork: @Composable (Modifier) -> Unit,
    subtitle: (@Composable () -> Unit)? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val wash = if (music.style.dynamicColours && tint != null) Color(tint).copy(alpha = 0.42f) else music.style.accent.copy(alpha = 0.16f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(wash, tokens.colors.surface.copy(alpha = 0.6f))))
            .padding(26.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        artwork(Modifier.size(if (music.style.largeArtwork) 220.dp else 150.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(kind.uppercase(), style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
            Text(title, style = MaterialTheme.typography.displayLarge, color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.invoke()
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = buttons,
            )
        }
    }
}

@Composable
private fun HeroMenu(description: String, items: @Composable (dismiss: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        MusicIconButton(Icons.Rounded.MoreHoriz, description, { open = true }, size = 40.dp, background = MaterialTheme.nuvio.colors.surfaceElevated)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

@Composable
private fun FavouriteButton(favorite: Boolean, onToggle: () -> Unit) {
    val music = LocalMusic.current
    MusicIconButton(
        if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        if (favorite) "Remove from favourites" else "Add to favourites",
        onToggle, size = 40.dp,
        tint = if (favorite) music.style.accent else MaterialTheme.nuvio.colors.textSecondary,
        background = MaterialTheme.nuvio.colors.surfaceElevated,
    )
}

@Composable
private fun NotFound(what: String) {
    MusicEmptyState(Icons.Rounded.MusicNote, "$what is no longer in your library", "It may have been removed or renamed.") {
        MusicButton("Back", MusicNav::back, primary = false)
    }
}

/** Lets the user pick a picture and returns its artwork id through [onStored]. */
private fun chooseCustomArtwork(music: MusicContext, actions: MusicActions, onStored: (Long) -> Unit) {
    val image = MusicPickers.chooseImage() ?: return
    actions.io {
        val id = music.services.library.artwork.storeFile(music.profileId, image, ArtworkStore.ORIGIN_CUSTOM)
        if (id == null) MusicNav.message = "That picture could not be read." else onStored(id)
    }
}

// ---------------------------------------------------------------------------- album

@Composable
internal fun AlbumScreen(albumId: Long) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val loaded by rememberMusicQuery(albumId, initial = null as Pair<Album?, List<Track>>?) { services, profile ->
        services.library.album(profile, albumId) to services.library.albumTracks(profile, albumId)
    }
    val playerState by music.services.player.state.collectAsStateWithLifecycle()
    val (album, tracks) = loaded ?: return
    if (album == null) {
        NotFound("This album")
        return
    }
    val discs = tracks.mapNotNull { it.discNo }.distinct().sorted()
    val formats = tracks.mapNotNull { it.format }.distinct()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 40.dp)) {
        item("hero") {
            DetailHero(
                kind = "Album",
                title = album.title,
                meta = details(album.year?.toString(), plural(album.trackCount, "song"), formatLongDuration(album.durationMs)),
                tint = album.artwork?.dominantColor,
                artwork = { ArtworkImage(album.artwork, it, full = true, shape = RoundedCornerShape(16.dp)) },
                subtitle = {
                    Text(
                        album.displayArtist,
                        style = MaterialTheme.typography.titleLarge,
                        color = music.style.accent,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = album.artistId != null, role = Role.Button) {
                            album.artistId?.let { MusicNav.open(MusicRoute.ArtistPage(it)) }
                        },
                    )
                },
            ) {
                PlayShuffleButtons({ actions.play(tracks) }, { actions.play(tracks, shuffle = true) }, enabled = tracks.isNotEmpty())
                FavouriteButton(album.favorite) { actions.io { music.services.library.setAlbumFavorite(music.profileId, album.id, !album.favorite) } }
                HeroMenu("More actions for ${album.title}") { dismiss ->
                    MenuItem("Play next", Icons.Rounded.SkipNext) { dismiss(); actions.playNext(tracks) }
                    MenuItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { dismiss(); actions.addToQueue(tracks) }
                    MenuItem("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd) { dismiss(); MusicNav.dialog = MusicDialog.AddToPlaylist(tracks.map { it.id }) }
                    MenuDivider()
                    MenuItem("Choose a cover…", Icons.Rounded.Image) {
                        dismiss()
                        chooseCustomArtwork(music, actions) { id -> music.services.library.setAlbumArtwork(music.profileId, album.id, id) }
                    }
                    MenuItem("Use the cover from the files", Icons.Rounded.HideImage) {
                        dismiss()
                        actions.io { music.services.library.resetAlbumArtwork(music.profileId, album.id) }
                    }
                }
            }
        }
        var lastDisc: Int? = null
        tracks.forEachIndexed { index, track ->
            if (discs.size > 1 && track.discNo != lastDisc) {
                lastDisc = track.discNo
                item("disc-${track.discNo}") {
                    Text("Disc ${track.discNo ?: 1}", style = MaterialTheme.typography.titleMedium, color = tokens.colors.textSecondary, modifier = Modifier.padding(start = 10.dp, top = 18.dp, bottom = 6.dp))
                }
            }
            item(key = track.id) {
                if (index == 0 && discs.size <= 1) androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 14.dp))
                TrackRow(
                    track = track,
                    actions = rememberTrackActions(track, tracks, index),
                    leading = (track.trackNo ?: (index + 1)).toString(),
                    showArtwork = false,
                    showAlbum = false,
                    isCurrent = playerState.current?.trackId == track.id,
                )
            }
        }
        item("footer") {
            Text(
                details(plural(tracks.size, "song"), formatLongDuration(tracks.sumOf { it.durationMs }), formats.joinToString(", ")),
                style = small(), color = tokens.colors.textMuted, modifier = Modifier.padding(start = 10.dp, top = 18.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------- artist

@Composable
internal fun ArtistScreen(artistId: Long) {
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val loaded by rememberMusicQuery(artistId, initial = null as Triple<Artist?, List<Album>, List<Track>>?) { services, profile ->
        Triple(services.library.artist(profile, artistId), services.library.albums(profile, artistId = artistId), services.library.artistTracks(profile, artistId))
    }
    val playerState by music.services.player.state.collectAsStateWithLifecycle()
    val (artist, albums, tracks) = loaded ?: return
    if (artist == null) {
        NotFound("This artist")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 40.dp)) {
        item("hero") {
            DetailHero(
                kind = "Artist",
                title = artist.name,
                meta = details(plural(artist.albumCount, "album"), plural(artist.trackCount, "song"), formatLongDuration(tracks.sumOf { it.durationMs })),
                tint = artist.artwork?.dominantColor,
                artwork = { ArtworkImage(artist.artwork, it, full = true, shape = CircleShape, placeholder = Icons.Rounded.Person) },
            ) {
                PlayShuffleButtons({ actions.play(tracks) }, { actions.play(tracks, shuffle = true) }, enabled = tracks.isNotEmpty())
                FavouriteButton(artist.favorite) { actions.io { music.services.library.setArtistFavorite(music.profileId, artist.id, !artist.favorite) } }
                HeroMenu("More actions for ${artist.name}") { dismiss ->
                    MenuItem("Play next", Icons.Rounded.SkipNext) { dismiss(); actions.playNext(tracks) }
                    MenuItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { dismiss(); actions.addToQueue(tracks) }
                    MenuItem("Add to playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd) { dismiss(); MusicNav.dialog = MusicDialog.AddToPlaylist(tracks.map { it.id }) }
                    MenuDivider()
                    MenuItem("Choose a picture…", Icons.Rounded.Image) {
                        dismiss()
                        chooseCustomArtwork(music, actions) { id -> music.services.library.setArtistArtwork(music.profileId, artist.id, id) }
                    }
                    MenuItem("Remove the picture", Icons.Rounded.HideImage) {
                        dismiss()
                        actions.io { music.services.library.setArtistArtwork(music.profileId, artist.id, null) }
                    }
                }
            }
        }
        if (albums.isNotEmpty()) {
            item("albums") {
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicSectionHeader("Albums")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(albums, key = { it.id }) { album ->
                            AlbumCard(album, { MusicNav.open(MusicRoute.AlbumPage(album.id)) }, { actions.playAlbum(album.id) }, Modifier.width(music.style.cardSize))
                        }
                    }
                }
            }
        }
        item("songs-title") { MusicSectionHeader("Songs", Modifier.padding(top = 14.dp, bottom = 6.dp)) }
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(track, rememberTrackActions(track, tracks, index), isCurrent = playerState.current?.trackId == track.id)
        }
    }
}

// ---------------------------------------------------------------------------- genre

@Composable
internal fun GenreScreen(name: String) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val albums by rememberMusicQuery(name, initial = emptyList<Album>()) { services, profile -> services.library.albums(profile, genre = name) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("GENRE", style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
            Text(name, style = MaterialTheme.typography.displayLarge, color = tokens.colors.textPrimary, maxLines = 1)
        }
        if (albums.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(albums, key = { it.id }) { album ->
                    AlbumCard(album, { MusicNav.open(MusicRoute.AlbumPage(album.id)) }, { actions.playAlbum(album.id) }, Modifier.width(music.style.cardSize - 24.dp))
                }
            }
        }
        Box(Modifier.weight(1f)) { SongsTab(TrackFilter(genre = name)) }
    }
}

// ---------------------------------------------------------------------------- playlist

@Composable
internal fun PlaylistScreen(playlistId: Long) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val loaded by rememberMusicQuery(playlistId, initial = null as Pair<Playlist?, List<PlaylistEntry>>?) { services, profile ->
        services.playlists.playlist(profile, playlistId) to services.playlists.entries(profile, playlistId)
    }
    val playerState by music.services.player.state.collectAsStateWithLifecycle()
    val (playlist, entries) = loaded ?: return
    if (playlist == null) {
        NotFound("This playlist")
        return
    }
    val tracks = entries.map { it.track }
    val playlists = music.services.playlists

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 40.dp)) {
        item("hero") {
            DetailHero(
                kind = "Playlist",
                title = playlist.name,
                meta = details(plural(playlist.trackCount, "song"), formatLongDuration(playlist.durationMs)),
                tint = (playlist.artwork ?: playlist.covers.firstOrNull())?.dominantColor,
                artwork = { PlaylistArtwork(playlist, it, full = true, shape = RoundedCornerShape(16.dp)) },
                subtitle = playlist.description?.let { text -> { Text(text, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary, maxLines = 2) } },
            ) {
                PlayShuffleButtons({ actions.play(tracks) }, { actions.play(tracks, shuffle = true) }, enabled = tracks.isNotEmpty())
                HeroMenu("More actions for ${playlist.name}") { dismiss ->
                    MenuItem("Play next", Icons.Rounded.SkipNext) { dismiss(); actions.playNext(tracks) }
                    MenuItem("Add to queue", Icons.AutoMirrored.Rounded.QueueMusic) { dismiss(); actions.addToQueue(tracks) }
                    MenuDivider()
                    MenuItem("Rename…", Icons.Rounded.Edit) { dismiss(); MusicNav.dialog = MusicDialog.RenamePlaylist(playlist.id, playlist.name) }
                    MenuItem("Choose a picture…", Icons.Rounded.Image) {
                        dismiss()
                        chooseCustomArtwork(music, actions) { id -> playlists.setArtwork(music.profileId, playlist.id, id) }
                    }
                    if (playlist.artwork != null) {
                        MenuItem("Remove the picture", Icons.Rounded.HideImage) { dismiss(); actions.io { playlists.setArtwork(music.profileId, playlist.id, null) } }
                    }
                    MenuItem("Export as M3U…", Icons.Rounded.FileDownload) {
                        dismiss()
                        MusicPickers.saveFile("Export playlist", playlist.name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".m3u8")?.let { target ->
                            actions.io {
                                val written = playlists.exportM3u(music.profileId, playlist.id, target)
                                MusicNav.message = "Exported ${plural(written, "song")} to ${target.fileName}"
                            }
                        }
                    }
                    MenuDivider()
                    MenuItem("Delete playlist", Icons.Rounded.Delete, danger = true) { dismiss(); MusicNav.dialog = MusicDialog.DeletePlaylist(playlist.id, playlist.name) }
                }
            }
        }
        if (entries.isEmpty()) {
            item("empty") {
                MusicEmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "This playlist is empty", "Use “Add to playlist…” on any song, album or artist.")
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.itemId }) { index, entry ->
            if (index == 0) androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 14.dp))
            TrackRow(
                track = entry.track,
                actions = rememberTrackActions(entry.track, tracks, index) { dismiss ->
                    if (index > 0) {
                        MenuItem("Move up", Icons.Rounded.ArrowUpward) { dismiss(); actions.io { playlists.move(music.profileId, playlist.id, index, index - 1) } }
                        MenuItem("Move to top", Icons.Rounded.VerticalAlignTop) { dismiss(); actions.io { playlists.move(music.profileId, playlist.id, index, 0) } }
                    }
                    if (index < entries.lastIndex) {
                        MenuItem("Move down", Icons.Rounded.ArrowDownward) { dismiss(); actions.io { playlists.move(music.profileId, playlist.id, index, index + 1) } }
                    }
                    MenuItem("Remove from this playlist", Icons.Rounded.PlaylistRemove) {
                        dismiss()
                        actions.io { playlists.removeItems(music.profileId, playlist.id, listOf(entry.itemId)) }
                    }
                },
                leading = (index + 1).toString(),
                isCurrent = playerState.current?.trackId == entry.track.id,
            )
        }
    }
}
