package com.debridone.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.music.MusicPreferences
import com.debridone.music.MusicServices
import com.debridone.music.library.Album
import com.debridone.music.library.AlbumSort
import com.debridone.music.library.Artist
import com.debridone.music.library.Genre
import com.debridone.music.library.Track
import com.debridone.music.library.TrackFilter
import com.debridone.music.library.TrackSort
import com.debridone.music.playlists.Playlist
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MusicLibrary(tab: LibraryTab) {
    val music = LocalMusic.current
    val stats by rememberMusicQuery(initial = -1) { services, profile -> services.library.trackCount(profile) }
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LibraryTab.entries) { entry ->
                MusicChip(entry.label, entry == tab) {
                    music.services.preferences.set(MusicPreferences.LIBRARY_TAB, entry.key)
                    MusicNav.root(MusicRoute.Library(entry))
                }
            }
        }
        when {
            stats == 0 && tab != LibraryTab.PLAYLISTS -> EmptyLibrary()
            tab == LibraryTab.SONGS -> SongsTab(TrackFilter())
            tab == LibraryTab.ALBUMS -> AlbumsTab()
            tab == LibraryTab.ARTISTS -> ArtistsTab()
            tab == LibraryTab.GENRES -> GenresTab()
            tab == LibraryTab.PLAYLISTS -> PlaylistsTab()
            tab == LibraryTab.FAVOURITES -> FavouritesTab()
        }
    }
}

// ---------------------------------------------------------------------------- songs (paged)

/**
 * Loads a long song list a page at a time as it scrolls into view, so opening "Songs" on a large
 * library does not read every row. Pages are re-read when the library changes.
 */
@Stable
internal class TrackPager(
    private val scope: CoroutineScope,
    private val services: MusicServices,
    private val profileId: Long,
    private val filter: TrackFilter,
    private val sort: TrackSort,
    private val descending: Boolean,
) {
    var count by mutableIntStateOf(-1)
        private set
    private val pages = mutableStateMapOf<Int, List<Track>>()
    private val loading = HashSet<Int>()

    fun get(index: Int): Track? {
        val page = index / PAGE_SIZE
        val loaded = pages[page]
        if (loaded == null) request(page)
        return loaded?.getOrNull(index % PAGE_SIZE)
    }

    private fun request(page: Int) {
        if (!loading.add(page)) return
        scope.launch {
            val tracks = withContext(Dispatchers.IO) {
                runCatching { services.library.tracks(profileId, filter, sort, descending, limit = PAGE_SIZE, offset = page * PAGE_SIZE) }.getOrDefault(emptyList())
            }
            pages[page] = tracks
            loading.remove(page)
        }
    }

    /** Re-reads the count and the pages that are loaded; pages far from view are dropped. */
    suspend fun refresh(visiblePage: Int) {
        val newCount = withContext(Dispatchers.IO) { runCatching { services.library.trackCount(profileId, filter) }.getOrDefault(0) }
        val keep = pages.keys.filter { kotlin.math.abs(it - visiblePage) <= 2 }
        val fresh = withContext(Dispatchers.IO) {
            keep.associateWith { page -> runCatching { services.library.tracks(profileId, filter, sort, descending, limit = PAGE_SIZE, offset = page * PAGE_SIZE) }.getOrDefault(emptyList()) }
        }
        pages.keys.toList().forEach { if (it !in fresh) pages.remove(it) }
        pages.putAll(fresh)
        count = newCount
    }

    suspend fun all(): List<Track> = withContext(Dispatchers.IO) { services.library.tracks(profileId, filter, sort, descending) }

    companion object {
        const val PAGE_SIZE = 120
    }
}

@Composable
internal fun SongsTab(baseFilter: TrackFilter, emptyTitle: String = "No songs", emptyMessage: String = "Nothing matches.") {
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val tokens = MaterialTheme.nuvio
    val preferences by music.services.preferences.values.collectAsStateWithLifecycle()
    val sort = TrackSort.entries.firstOrNull { it.name == preferences[MusicPreferences.SONG_SORT] } ?: TrackSort.TITLE
    val descending = preferences[MusicPreferences.SONG_SORT_DESCENDING] == "true"
    var filterText by remember { mutableStateOf("") }
    val filter = baseFilter.copy(text = filterText.takeIf { it.isNotBlank() })

    val scope = rememberCoroutineScope()
    val pager = remember(music.services, music.profileId, filter, sort, descending) { TrackPager(scope, music.services, music.profileId, filter, sort, descending) }
    val revision by music.services.library.revision.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(pager, revision) { pager.refresh(listState.firstVisibleItemIndex / TrackPager.PAGE_SIZE) }
    val playerState by music.services.player.state.collectAsStateWithLifecycle()
    val currentTrackId = playerState.current?.trackId

    fun playFrom(track: Track?, shuffle: Boolean) {
        scope.launch {
            val all = pager.all()
            actions.play(all, track?.let { wanted -> all.indexOfFirst { it.id == wanted.id } }?.coerceAtLeast(0) ?: 0, shuffle = shuffle)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayShuffleButtons({ playFrom(null, false) }, { playFrom(null, true) }, enabled = pager.count > 0)
            Text(if (pager.count >= 0) plural(pager.count, "song") else "", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
            Spacer(Modifier.weight(1f))
            SearchField(filterText, { filterText = it }, Modifier.width(220.dp), placeholder = "Filter this list")
            SortMenu(
                options = TrackSort.entries.map { it to it.label },
                selected = sort,
                descending = descending,
                onSelect = { music.services.preferences.set(MusicPreferences.SONG_SORT, it.name) },
                onToggleDirection = { music.services.preferences.set(MusicPreferences.SONG_SORT_DESCENDING, (!descending).toString()) },
            )
        }
        if (pager.count == 0) {
            MusicEmptyState(Icons.Rounded.MusicNote, emptyTitle, if (filterText.isNotBlank()) "No song matches “$filterText”." else emptyMessage)
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp),
        ) {
            items(count = pager.count.coerceAtLeast(0), key = { it }) { index ->
                val track = pager.get(index)
                if (track == null) {
                    Spacer(Modifier.fillMaxWidth().height(music.style.rowHeight))
                } else {
                    TrackRow(
                        track = track,
                        actions = TrackActions(
                            onPlay = { playFrom(track, false) },
                            menu = { dismiss -> TrackMenu(track, dismiss) },
                            onToggleFavorite = { actions.toggleFavorite(track) },
                        ),
                        isCurrent = track.id == currentTrackId,
                    )
                }
            }
        }
    }
}

@Composable
internal fun <T> SortMenu(options: List<Pair<T, String>>, selected: T, descending: Boolean, onSelect: (T) -> Unit, onToggleDirection: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(tokens.colors.surface)
                    .border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(50))
                    .clickable(role = Role.DropdownList) { open = true }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Sort, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(16.dp))
                Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, label) ->
                    MenuItem(if (value == selected) "✓  $label" else "    $label") {
                        open = false
                        onSelect(value)
                    }
                }
            }
        }
        MusicIconButton(
            if (descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
            if (descending) "Descending; switch to ascending" else "Ascending; switch to descending",
            onToggleDirection, size = 34.dp,
        )
    }
}

// ---------------------------------------------------------------------------- albums, artists, genres

@Composable
private fun AlbumsTab() {
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val tokens = MaterialTheme.nuvio
    val preferences by music.services.preferences.values.collectAsStateWithLifecycle()
    val sort = AlbumSort.entries.firstOrNull { it.name == preferences[MusicPreferences.ALBUM_SORT] } ?: AlbumSort.TITLE
    val descending = preferences[MusicPreferences.ALBUM_SORT_DESCENDING] == "true"
    var filterText by remember { mutableStateOf("") }
    val albums by rememberMusicQuery(sort, descending, filterText, initial = null as List<Album>?) { services, profile ->
        services.library.albums(profile, sort, descending, text = filterText.takeIf { it.isNotBlank() })
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(albums?.let { plural(it.size, "album") }.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
            Spacer(Modifier.weight(1f))
            SearchField(filterText, { filterText = it }, Modifier.width(220.dp), placeholder = "Filter albums")
            SortMenu(
                options = AlbumSort.entries.map { it to it.label }, selected = sort, descending = descending,
                onSelect = { music.services.preferences.set(MusicPreferences.ALBUM_SORT, it.name) },
                onToggleDirection = { music.services.preferences.set(MusicPreferences.ALBUM_SORT_DESCENDING, (!descending).toString()) },
            )
        }
        val list = albums ?: return@Column
        if (list.isEmpty()) {
            MusicEmptyState(Icons.Rounded.Album, "No albums", if (filterText.isNotBlank()) "No album matches “$filterText”." else "Songs without an album tag appear under Songs.")
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(music.style.cardSize),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.id }) { album ->
                AlbumCard(album, { MusicNav.open(MusicRoute.AlbumPage(album.id)) }, { actions.playAlbum(album.id) })
            }
        }
    }
}

@Composable
private fun ArtistsTab() {
    val music = LocalMusic.current
    val artists by rememberMusicQuery(initial = null as List<Artist>?) { services, profile -> services.library.artists(profile) }
    val list = artists ?: return
    if (list.isEmpty()) {
        MusicEmptyState(Icons.Rounded.Person, "No artists", "Songs without an artist tag appear under Songs.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(music.style.cardSize - 16.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list, key = { it.id }) { artist -> ArtistCard(artist, { MusicNav.open(MusicRoute.ArtistPage(artist.id)) }) }
    }
}

@Composable
private fun GenresTab() {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val genres by rememberMusicQuery(initial = null as List<Genre>?) { services, profile -> services.library.genres(profile) }
    val list = genres ?: return
    if (list.isEmpty()) {
        MusicEmptyState(Icons.Rounded.Style, "No genres", "None of your songs has a genre tag.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(220.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(list, key = { it.name.lowercase() }) { genre ->
            Column(
                modifier = Modifier
                    .height(96.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(genreColour(genre.name, music.style.accent), tokens.colors.surface)))
                    .clickable(role = Role.Button) { MusicNav.open(MusicRoute.GenrePage(genre.name)) }
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(genre.name, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary, maxLines = 1)
                Text(
                    listOf(plural(genre.trackCount, "song"), plural(genre.albumCount, "album")).joinToString(" · "),
                    style = small(), color = tokens.colors.textSecondary,
                )
            }
        }
    }
}

/** A stable tint per genre name, rotated around the accent's hue. */
private fun genreColour(name: String, accent: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    val hsv = FloatArray(3)
    java.awt.Color.RGBtoHSB((accent.red * 255).toInt(), (accent.green * 255).toInt(), (accent.blue * 255).toInt(), hsv)
    val hue = (hsv[0] + (name.lowercase().hashCode() and 0xFFFF) / 65535f) % 1f
    return androidx.compose.ui.graphics.Color(java.awt.Color.HSBtoRGB(hue, 0.55f, 0.62f)).copy(alpha = 0.75f)
}

// ---------------------------------------------------------------------------- playlists, favourites

@Composable
private fun PlaylistsTab() {
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val tokens = MaterialTheme.nuvio
    val playlists by rememberMusicQuery(initial = null as List<Playlist>?) { services, profile -> services.playlists.playlists(profile) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MusicButton("New playlist", { MusicNav.dialog = MusicDialog.NewPlaylist() }, icon = Icons.AutoMirrored.Rounded.PlaylistAdd)
            MusicButton("Import M3U…", {
                MusicPickers.choosePlaylistFile()?.let { file ->
                    actions.io {
                        val result = music.services.playlists.importM3u(music.profileId, file, music.services.scanner)
                        MusicNav.message = "Imported ${result.playlist.name}: ${plural(result.matched, "song")}" +
                            if (result.skipped.isEmpty()) "" else ", ${result.skipped.size} could not be added"
                        MusicNav.open(MusicRoute.PlaylistPage(result.playlist.id))
                    }
                }
            }, icon = Icons.Rounded.FileOpen, primary = false)
            Spacer(Modifier.weight(1f))
            Text(playlists?.let { plural(it.size, "playlist") }.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        }
        val list = playlists ?: return@Column
        if (list.isEmpty()) {
            MusicEmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "No playlists yet", "Create one, or import an M3U or M3U8 file.")
            return@Column
        }
        if (music.style.playlistGrid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(music.style.cardSize),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { playlist ->
                    PlaylistCard(playlist, { MusicNav.open(MusicRoute.PlaylistPage(playlist.id)) }, { actions.playPlaylist(playlist.id) })
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp)) {
                items(list, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable(role = Role.Button) { MusicNav.open(MusicRoute.PlaylistPage(playlist.id)) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        PlaylistArtwork(playlist, Modifier.size(52.dp), shape = RoundedCornerShape(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1)
                            Text(
                                listOf(plural(playlist.trackCount, "song"), formatLongDuration(playlist.durationMs)).joinToString(" · "),
                                style = small(), color = tokens.colors.textMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavouritesTab() {
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val albums by rememberMusicQuery(initial = emptyList<Album>()) { services, profile -> services.library.albums(profile, favoritesOnly = true) }
    val artists by rememberMusicQuery(initial = emptyList<Artist>()) { services, profile -> services.library.artists(profile, favoritesOnly = true) }
    Column(Modifier.fillMaxSize()) {
        if (albums.isNotEmpty() || artists.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(albums, key = { "album-${it.id}" }) { album ->
                    AlbumCard(album, { MusicNav.open(MusicRoute.AlbumPage(album.id)) }, { actions.playAlbum(album.id) }, Modifier.width(music.style.cardSize - 24.dp))
                }
                items(artists, key = { "artist-${it.id}" }) { artist ->
                    ArtistCard(artist, { MusicNav.open(MusicRoute.ArtistPage(artist.id)) }, Modifier.width(music.style.cardSize - 24.dp))
                }
            }
        }
        Box(Modifier.weight(1f)) {
            SongsTab(
                TrackFilter(favoritesOnly = true),
                emptyTitle = "No favourite songs yet",
                emptyMessage = "Use the heart on a song to keep it here.",
            )
        }
    }
}
