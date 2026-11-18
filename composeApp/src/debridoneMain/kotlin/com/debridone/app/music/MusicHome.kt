package com.debridone.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.debridone.app.shell.ShellState
import com.debridone.module.ModuleId
import com.debridone.music.playback.PlaybackStatus
import com.debridone.music.playback.PlayerState
import com.debridone.music.sources.AudiusAdapter
import com.debridone.music.sources.SourceConnection
import com.debridone.music.sources.SourceDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    CONTINUE("continue_listening", "Continue listening"),
    RECENTLY_PLAYED("recently_played", "Recently played"),
    JUMP_BACK_IN("jump_back_in", "Your albums"),
    PLAYLISTS("playlists", "Your playlists"),
    ARTISTS("artists", "Favourite artists"),
    RECENTLY_ADDED("recently_added", "Recently added"),
    FEATURED("featured", "Featured from your sources"),
    MOST_PLAYED("most_played", "Most played"),
    FAVOURITES("favourites", "Favourite songs"),
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
    val favouriteArtists: Boolean = false,
    val recentlyPlayed: List<Track> = emptyList(),
    /** The first source whose catalogue puts songs forward, for the "Featured" row. */
    val featuredSource: SourceConnection? = null,
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
            jumpBackIn = library.recentlyPlayedAlbums(profile, 12).ifEmpty { library.albums(profile, limit = 12) },
            recentlyAdded = library.albums(profile, AlbumSort.RECENTLY_ADDED, limit = 14),
            mostPlayed = library.mostPlayed(profile, 8),
            favourites = library.tracks(profile, TrackFilter(favoritesOnly = true), TrackSort.RECENTLY_ADDED, limit = 8),
            playlists = services.playlists.playlists(profile),
            artists = library.artists(profile, favoritesOnly = true, limit = 14).ifEmpty { library.artists(profile, limit = 14) },
            favouriteArtists = library.artists(profile, favoritesOnly = true, limit = 1).isNotEmpty(),
            recentlyPlayed = library.recentlyPlayed(profile, 8),
            featuredSource = services.sources.catalogs(profile).firstOrNull { services.sources.capabilities(it).featured },
        )
    }
    val playerState by music.services.player.state.collectAsStateWithLifecycle()
    val currentTrackId = playerState.current?.trackId

    if (!data.loaded) return

    // What the first connected catalogue puts forward. Real answers only: nothing is shown until
    // the source has replied, and nothing at all when it has nothing.
    val featuredSource = data.featuredSource
    val featured by produceState(featuredSource?.let { DiscoverState.featured["${it.id}|"] }.orEmpty(), featuredSource?.id) {
        val source = featuredSource ?: return@produceState
        DiscoverState.featured["${source.id}|"]?.let {
            value = it
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching { music.services.sources.featured(music.profileId, source.id, null, 30) }.getOrDefault(emptyList())
        }
        if (value.isNotEmpty()) DiscoverState.featured["${source.id}|"] = value
    }
    val empty = data.stats.tracks == 0

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 0.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item("hero") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MusicPageHeading(
                    if (empty) "Music" else greeting(),
                    if (empty) {
                        "Start with your own files, an open catalogue, or your music server."
                    } else {
                        details(
                            plural(data.stats.tracks, "song"), plural(data.stats.albums, "album"), plural(data.stats.artists, "artist"),
                            formatLongDuration(data.stats.totalDurationMs).takeIf { it.isNotEmpty() }?.let { "$it of music" },
                        )
                    },
                )
                if (!empty) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MusicButton("Shuffle everything", icon = Icons.Rounded.Shuffle, onClick = {
                            actions.io { actions.play(music.services.library.tracks(music.profileId), shuffle = true) }
                        })
                        MusicButton("Discover", icon = Icons.Rounded.Explore, primary = false, onClick = { MusicNav.root(MusicRoute.Discover) })
                        MusicButton("Open library", icon = Icons.Rounded.LibraryMusic, primary = false, onClick = {
                            MusicNav.root(MusicRoute.Library(LibraryTab.fromKey(music.services.preferences[MusicPreferences.LIBRARY_TAB])))
                        })
                    }
                }
            }
        }

        if (empty) {
            item("setup") { HomeSetup(hasCatalogue = featuredSource != null) }
        }

        val resumable = playerState.current?.takeIf { playerState.status != PlaybackStatus.PLAYING }
        if (HomeSection.CONTINUE.key !in hidden && resumable != null) {
            item("continue") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicSectionHeader(HomeSection.CONTINUE.label)
                    Row(
                        modifier = Modifier
                            .widthIn(max = 620.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(tokens.colors.surface)
                            .border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(16.dp))
                            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Resume") { music.services.player.resume() }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ArtworkImage(playerState.currentArtwork(music), Modifier.size(64.dp), shape = RoundedCornerShape(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(resumable.title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, maxLines = 1)
                            Text(details(resumable.artist, resumable.album), style = small(), color = tokens.colors.textMuted, maxLines = 1)
                            Text(
                                "${formatDuration(playerState.positionMs)} of ${formatDuration(playerState.durationMs.takeIf { it > 0 } ?: resumable.durationMs)}",
                                style = small(), color = tokens.colors.textSecondary,
                            )
                        }
                        MusicButton("Resume", icon = Icons.Rounded.PlayArrow, onClick = { music.services.player.resume() })
                    }
                }
            }
        }
        if (HomeSection.RECENTLY_PLAYED.key !in hidden && data.recentlyPlayed.isNotEmpty()) {
            item("recently-played") { TrackShelf(HomeSection.RECENTLY_PLAYED.label, data.recentlyPlayed, currentTrackId) }
        }

        if (HomeSection.JUMP_BACK_IN.key !in hidden && data.jumpBackIn.isNotEmpty()) {
            item("jump") { AlbumShelf(HomeSection.JUMP_BACK_IN.label, data.jumpBackIn) }
        }
        if (HomeSection.RECENTLY_ADDED.key !in hidden && data.recentlyAdded.isNotEmpty()) {
            item("recent") {
                AlbumShelf(HomeSection.RECENTLY_ADDED.label, data.recentlyAdded, action = "All albums") { MusicNav.root(MusicRoute.Library(LibraryTab.ALBUMS)) }
            }
        }
        if (HomeSection.FEATURED.key !in hidden && featuredSource != null && featured.isNotEmpty()) {
            item("featured") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicSectionHeader(
                        if (featuredSource.adapter == AudiusAdapter.ID) "Trending on Audius" else "From ${featuredSource.name}",
                        action = "Discover more",
                    ) {
                        DiscoverState.sourceId = featuredSource.id
                        MusicNav.root(MusicRoute.Discover)
                    }
                    RemoteTrackGrid(featuredSource.id, featured, maxRows = 1)
                }
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
                    MusicSectionHeader(if (data.favouriteArtists) HomeSection.ARTISTS.label else "Your artists", action = "All artists") { MusicNav.root(MusicRoute.Library(LibraryTab.ARTISTS)) }
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

/** The compact start of an empty library: three ways to get music, side by side. */
@Composable
private fun HomeSetup(hasCatalogue: Boolean) {
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val tokens = MaterialTheme.nuvio
    val scan by music.services.scanner.progress.collectAsStateWithLifecycle()
    var installing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SetupCard(Icons.Rounded.CreateNewFolder, "Your files", "Choose a folder. Files stay where they are; nothing is copied or changed.", "Add a folder", Modifier.weight(1f)) {
                MusicNav.dialog = MusicDialog.AddFolder
            }
            if (hasCatalogue) {
                SetupCard(Icons.Rounded.Explore, "Discover", "Search and play from the sources you have connected.", "Open Discover", Modifier.weight(1f)) {
                    MusicNav.root(MusicRoute.Discover)
                }
            } else {
                SetupCard(
                    Icons.Rounded.Explore, "Audius", "An open catalogue of independent artists. Free, no account.",
                    if (installing) "Installing…" else "Install", Modifier.weight(1f), enabled = !installing,
                ) {
                    installing = true
                    actions.io {
                        try {
                            music.services.sources.add(music.profileId, AudiusAdapter.ID, SourceDraft("", "", "", ""))
                            MusicNav.message = "Audius is installed for this profile."
                        } finally {
                            installing = false
                        }
                    }
                }
            }
            SetupCard(Icons.Rounded.LibraryMusic, "Your server", "Navidrome, another OpenSubsonic server, or Jellyfin.", "Connect", Modifier.weight(1f)) {
                MusicNav.openSettings(MusicSettingsSections.SOURCES)
            }
        }
        if (scan.running) {
            Text("Scanning ${scan.filesProcessed} of ${scan.filesFound} files…", style = small(), color = tokens.colors.textSecondary)
        } else if (scan.error != null) {
            Text("The last scan failed: ${scan.error}", style = small(), color = tokens.colors.danger)
        }
        Text("Plays " + AudioTagReader.supportedExtensions.sorted().joinToString(", ") { it.uppercase() }, style = small(), color = tokens.colors.textMuted)
    }
}

/** The artwork of the song the player holds, looked up in the library. */
@Composable
private fun PlayerState.currentArtwork(music: MusicContext): com.debridone.music.library.ArtworkRef? {
    val trackId = current?.trackId
    val artwork by produceState<com.debridone.music.library.ArtworkRef?>(null, trackId) {
        value = trackId?.let { withContext(Dispatchers.IO) { music.services.library.track(music.profileId, it)?.artwork } }
    }
    return artwork
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
