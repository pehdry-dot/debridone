package com.debridone.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.DebridOneRuntime
import com.debridone.music.MusicPreferences
import com.debridone.music.library.Album
import com.debridone.music.library.AlbumSort
import com.debridone.music.library.Artist
import com.debridone.music.library.AudioTagReader
import com.debridone.music.library.LibraryStats
import com.debridone.music.library.Track
import com.debridone.music.library.TrackFilter
import com.debridone.music.library.TrackSort
import com.debridone.music.playlists.Playlist
import com.nuvio.app.core.ui.nuvio
import java.util.Calendar

/** The sections Home can show; a profile may hide any of them in Music settings. */
internal enum class HomeSection(val key: String, val label: String) {
    JUMP_BACK_IN("jump_back_in", "Jump back in"),
    RECENTLY_ADDED("recently_added", "Recently added"),
    MOST_PLAYED("most_played", "Most played"),
    PLAYLISTS("playlists", "Your playlists"),
    FAVOURITES("favourites", "Favourite songs"),
    ARTISTS("artists", "Artists"),
    ;

    companion object {
        fun hidden(preference: String?): Set<String> = preference.orEmpty().split(',').filter { it.isNotBlank() }.toSet()
    }
}

private class HomeData(
    val stats: LibraryStats = LibraryStats(0, 0, 0, 0, 0, 0),
    val loaded: Boolean = false,
    val jumpBackIn: List<Album> = emptyList(),
    val recentlyAdded: List<Album> = emptyList(),
    val mostPlayed: List<Track> = emptyList(),
    val favourites: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val artists: List<Artist> = emptyList(),
)

@Composable
internal fun MusicHome() {
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val tokens = MaterialTheme.nuvio
    val preferences by music.services.preferences.values.collectAsStateWithLifecycle()
    val hidden = HomeSection.hidden(preferences[MusicPreferences.HOME_HIDDEN_SECTIONS])
    val data by rememberMusicQuery(initial = HomeData()) { services, profile ->
        val library = services.library
        HomeData(
            stats = library.stats(profile),
            loaded = true,
            jumpBackIn = library.recentlyPlayedAlbums(profile, 12),
            recentlyAdded = library.albums(profile, AlbumSort.RECENTLY_ADDED, limit = 14),
            mostPlayed = library.mostPlayed(profile, 8),
            favourites = library.tracks(profile, TrackFilter(favoritesOnly = true), TrackSort.RECENTLY_ADDED, limit = 8),
            playlists = services.playlists.playlists(profile),
            artists = library.artists(profile, limit = 14),
        )
    }
    val playerState by music.services.player.state.collectAsStateWithLifecycle()
    val currentTrackId = playerState.current?.trackId

    if (!data.loaded) return
    if (data.stats.tracks == 0) {
        EmptyLibrary()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item("hero") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(music.style.accent.copy(alpha = 0.34f), tokens.colors.surface)))
                    .border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(22.dp))
                    .padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(greeting(), style = MaterialTheme.typography.headlineLarge, color = tokens.colors.textPrimary)
                Text(
                    details(
                        plural(data.stats.tracks, "song"), plural(data.stats.albums, "album"), plural(data.stats.artists, "artist"),
                        formatLongDuration(data.stats.totalDurationMs).takeIf { it.isNotEmpty() }?.let { "$it of music" },
                    ),
                    style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    MusicButton("Shuffle everything", icon = Icons.Rounded.Shuffle, onClick = {
                        actions.io { actions.play(music.services.library.tracks(music.profileId), shuffle = true) }
                    })
                    MusicButton("Open library", icon = Icons.Rounded.LibraryMusic, primary = false, onClick = {
                        MusicNav.root(MusicRoute.Library(LibraryTab.fromKey(music.services.preferences[MusicPreferences.LIBRARY_TAB])))
                    })
                }
            }
        }

        if (HomeSection.JUMP_BACK_IN.key !in hidden && data.jumpBackIn.isNotEmpty()) {
            item("jump") { AlbumShelf(HomeSection.JUMP_BACK_IN.label, data.jumpBackIn) }
        }
        if (HomeSection.RECENTLY_ADDED.key !in hidden && data.recentlyAdded.isNotEmpty()) {
            item("recent") {
                AlbumShelf(HomeSection.RECENTLY_ADDED.label, data.recentlyAdded, action = "All albums") { MusicNav.root(MusicRoute.Library(LibraryTab.ALBUMS)) }
            }
        }
        if (HomeSection.MOST_PLAYED.key !in hidden && data.mostPlayed.isNotEmpty()) {
            item("most") { TrackShelf(HomeSection.MOST_PLAYED.label, data.mostPlayed, currentTrackId) }
        }
        if (HomeSection.PLAYLISTS.key !in hidden && data.playlists.isNotEmpty()) {
            item("playlists") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicSectionHeader(HomeSection.PLAYLISTS.label, action = "All playlists") { MusicNav.root(MusicRoute.Library(LibraryTab.PLAYLISTS)) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(data.playlists, key = { it.id }) { playlist ->
                            PlaylistCard(playlist, { MusicNav.open(MusicRoute.PlaylistPage(playlist.id)) }, { actions.playPlaylist(playlist.id) }, Modifier.width(music.style.cardSize))
                        }
                    }
                }
            }
        }
        if (HomeSection.FAVOURITES.key !in hidden && data.favourites.isNotEmpty()) {
            item("favourites") {
                TrackShelf(HomeSection.FAVOURITES.label, data.favourites, currentTrackId, action = "All favourites") { MusicNav.root(MusicRoute.Library(LibraryTab.FAVOURITES)) }
            }
        }
        if (HomeSection.ARTISTS.key !in hidden && data.artists.isNotEmpty()) {
            item("artists") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicSectionHeader(HomeSection.ARTISTS.label, action = "All artists") { MusicNav.root(MusicRoute.Library(LibraryTab.ARTISTS)) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(data.artists, key = { it.id }) { artist ->
                            ArtistCard(artist, { MusicNav.open(MusicRoute.ArtistPage(artist.id)) }, Modifier.width(music.style.cardSize - 20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumShelf(title: String, albums: List<Album>, action: String? = null, onAction: (() -> Unit)? = null) {
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MusicSectionHeader(title, action = action, onAction = onAction)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(albums, key = { it.id }) { album ->
                AlbumCard(album, { MusicNav.open(MusicRoute.AlbumPage(album.id)) }, { actions.playAlbum(album.id) }, Modifier.width(music.style.cardSize))
            }
        }
    }
}

/** A short song list laid out in two columns. */
@Composable
private fun TrackShelf(title: String, tracks: List<Track>, currentTrackId: Long?, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MusicSectionHeader(title, action = action, onAction = onAction)
        val half = (tracks.size + 1) / 2
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (column in listOf(tracks.take(half), tracks.drop(half))) {
                Column(Modifier.weight(1f)) {
                    column.forEach { track ->
                        TrackRow(track, rememberTrackActions(track, tracks, tracks.indexOf(track)), showAlbum = false, isCurrent = track.id == currentTrackId)
                    }
                }
            }
        }
    }
}

/** First run: nothing is in the library yet. Says what to do and what will happen. */
@Composable
internal fun EmptyLibrary() {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val scan by music.services.scanner.progress.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(tokens.colors.surface)
                .border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(24.dp))
                .padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = music.style.accent, modifier = Modifier.size(52.dp))
            Text("Add your music", style = MaterialTheme.typography.headlineLarge, color = tokens.colors.textPrimary)
            Text(
                "Choose a folder and DebridOne lists its songs with their tags and covers. " +
                    "Files stay where they are: nothing is copied, moved or changed.",
                style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                MusicButton("Add a folder", icon = Icons.Rounded.CreateNewFolder, onClick = { MusicNav.dialog = MusicDialog.AddFolder })
                MusicButton("Add files", icon = Icons.Rounded.NoteAdd, primary = false, onClick = {
                    val files = MusicPickers.chooseAudioFiles()
                    if (files.isNotEmpty()) DebridOneRuntime.modules.music.importFiles(files)
                })
            }
            if (scan.running) {
                Text("Scanning ${scan.filesProcessed} of ${scan.filesFound} files…", style = small(), color = tokens.colors.textSecondary)
            } else if (scan.error != null) {
                Text("The last scan failed: ${scan.error}", style = small(), color = tokens.colors.danger)
            }
            Text(
                "Plays " + AudioTagReader.supportedExtensions.sorted().joinToString(", ") { it.uppercase() },
                style = small(), color = tokens.colors.textMuted,
            )
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/** A play / shuffle pair used at the top of lists. */
@Composable
internal fun PlayShuffleButtons(onPlay: () -> Unit, onShuffle: () -> Unit, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MusicButton("Play", onPlay, icon = Icons.Rounded.PlayArrow, enabled = enabled)
        MusicButton("Shuffle", onShuffle, icon = Icons.Rounded.Shuffle, primary = false, enabled = enabled)
    }
}
