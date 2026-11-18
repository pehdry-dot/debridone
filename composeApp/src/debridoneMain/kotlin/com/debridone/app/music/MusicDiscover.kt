package com.debridone.app.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.debridone.app.DebridOneRuntime
import com.debridone.app.shell.ShellState
import com.debridone.module.ModuleId
import com.debridone.music.sources.AudiusAdapter
import com.debridone.music.sources.CatalogCapabilities
import com.debridone.music.sources.CatalogResults
import com.debridone.music.sources.RemoteAlbum
import com.debridone.music.sources.RemoteArtist
import com.debridone.music.sources.RemotePlaylist
import com.debridone.music.sources.RemoteTrack
import com.debridone.music.sources.SourceConnection
import com.debridone.music.sources.SourceDraft
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** An album, playlist or artist of a source's catalogue, as the pages need it. */
internal data class RemoteItem(
    val sourceId: Long,
    val kind: Kind,
    val remoteId: String,
    val title: String,
    val subtitle: String?,
    val artworkKey: String?,
    val artworkUrl: String?,
    val artworkAlternates: List<String> = emptyList(),
    val description: String? = null,
) {
    enum class Kind(val label: String) { ALBUM("Album"), PLAYLIST("Playlist"), ARTIST("Artist") }
}

/** What Discover is showing. Kept outside composables so it is still there after a visit elsewhere. */
internal object DiscoverState {
    var sourceId by mutableStateOf<Long?>(null)
    var query by mutableStateOf("")

    /** The text that was actually searched for (the field's text, settled). */
    var submitted by mutableStateOf("")
    var genre by mutableStateOf<String?>(null)

    /** Featured songs already fetched this session, by "source|genre". */
    val featured = mutableStateMapOf<String, List<RemoteTrack>>()

    fun reset() {
        sourceId = null
        query = ""
        submitted = ""
        genre = null
        featured.clear()
    }
}

private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Ready<T>(val value: T) : Loaded<T>
    data class Failed(val message: String) : Loaded<Nothing>
}

// ---------------------------------------------------------------------------- artwork of catalogue items

/** A cover that lives on a source. Fetched once, off the UI thread; a placeholder until then. */
@Composable
internal fun RemoteArtwork(
    sourceId: Long,
    artworkKey: String?,
    artworkUrl: String?,
    alternates: List<String>,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    placeholder: ImageVector = Icons.Rounded.MusicNote,
) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val bitmap by produceState<ImageBitmap?>(null, sourceId, artworkKey, artworkUrl) {
        value = null
        if (artworkKey == null && artworkUrl == null) return@produceState
        val path = withContext(Dispatchers.IO) {
            runCatching { music.services.sources.artworkFile(music.profileId, sourceId, artworkKey, artworkUrl, alternates)?.toString() }.getOrNull()
        } ?: return@produceState
        value = ArtworkBitmaps.cached(path, full = false) ?: ArtworkBitmaps.load(path, full = false)
    }
    Box(modifier.clip(shape).background(tokens.colors.surfaceElevated), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(placeholder, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.fillMaxSize(0.42f))
        }
    }
}

// ---------------------------------------------------------------------------- what can be done with a result

private object RemoteActions {
    private val module get() = DebridOneRuntime.modules.music

    fun play(sourceId: Long, tracks: List<RemoteTrack>, index: Int = 0) {
        module.playRemote(sourceId, tracks, index) { result -> result.exceptionOrNull()?.let { MusicNav.message = it.message ?: "That could not be played." } }
    }

    fun keep(sourceId: Long, tracks: List<RemoteTrack>, then: (List<Long>) -> Unit = {}) {
        module.keepRemote(sourceId, tracks) { result ->
            result.fold(
                onSuccess = { kept ->
                    MusicNav.message = if (kept.isEmpty()) "The source does not allow these songs to be played." else "Added ${plural(kept.size, "song")} to your library."
                    then(kept.map { it.id })
                },
                onFailure = { MusicNav.message = it.message ?: "That could not be added." },
            )
        }
    }

    fun savePlaylist(sourceId: Long, item: RemoteItem) {
        module.importRemotePlaylist(sourceId, RemotePlaylist(item.remoteId, item.title, description = item.description)) { result ->
            MusicNav.message = result.fold({ "Saved “${item.title}” to your playlists with ${plural(it, "song")}." }, { it.message ?: "That playlist could not be saved." })
        }
    }
}

// ---------------------------------------------------------------------------- Discover

/**
 * Search and browse the catalogues of the profile's sources (Audius, a Navidrome or Jellyfin server,
 * a plugin). Results play from the source; nothing joins the library unless the user adds it.
 */
@Composable
internal fun MusicDiscover() {
    val music = LocalMusic.current
    val catalogs by rememberMusicQuery(initial = null as List<SourceConnection>?) { services, profile -> services.sources.catalogs(profile) }
    val available = catalogs ?: return
    if (available.isEmpty()) {
        DiscoverSetup()
        return
    }
    val source = available.firstOrNull { it.id == DiscoverState.sourceId } ?: available.first()
    val capabilities = remember(source.id, source.adapter) { music.services.sources.capabilities(source) }

    // Search once typing pauses; Enter searches at once.
    LaunchedEffect(DiscoverState.query) {
        if (DiscoverState.query.trim() == DiscoverState.submitted) return@LaunchedEffect
        delay(550)
        DiscoverState.submitted = DiscoverState.query.trim()
    }

    val submitted = DiscoverState.submitted
    val genre = DiscoverState.genre
    val results by produceState<Loaded<CatalogResults>?>(null, source.id, submitted) {
        if (submitted.isBlank()) {
            value = null
            return@produceState
        }
        value = Loaded.Loading
        value = withContext(Dispatchers.IO) {
            runCatching { music.services.sources.search(music.profileId, source.id, submitted, 24) }
                .fold({ Loaded.Ready(it) }, { Loaded.Failed(it.message ?: "The search failed.") })
        }
    }
    val featuredKey = "${source.id}|${genre.orEmpty()}"
    val featured by produceState<Loaded<List<RemoteTrack>>>(DiscoverState.featured[featuredKey]?.let { Loaded.Ready(it) } ?: Loaded.Loading, featuredKey, capabilities.featured) {
        if (!capabilities.featured) {
            value = Loaded.Ready(emptyList())
            return@produceState
        }
        DiscoverState.featured[featuredKey]?.let {
            value = Loaded.Ready(it)
            return@produceState
        }
        value = Loaded.Loading
        value = withContext(Dispatchers.IO) {
            runCatching { music.services.sources.featured(music.profileId, source.id, genre, 30) }
                .fold({ DiscoverState.featured[featuredKey] = it; Loaded.Ready(it) }, { Loaded.Failed(it.message ?: "The source did not answer.") })
        }
    }
    val genres by produceState(emptyList<String>(), source.id, capabilities.genres) {
        value = if (!capabilities.genres) emptyList() else withContext(Dispatchers.IO) {
            runCatching { music.services.sources.genres(music.profileId, source.id) }.getOrDefault(emptyList())
        }
    }
    val ownPlaylists by produceState(emptyList<RemotePlaylist>(), source.id, capabilities.ownPlaylists) {
        value = if (!capabilities.ownPlaylists) emptyList() else withContext(Dispatchers.IO) {
            runCatching { music.services.sources.ownPlaylists(music.profileId, source.id) }.getOrDefault(emptyList())
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item("heading") {
            MusicPageHeading("Discover", "Search and play from your sources. Nothing joins your library unless you add it.")
        }
        item("search") {
            SearchField(
                value = DiscoverState.query,
                onValueChange = { DiscoverState.query = it },
                modifier = Modifier.fillMaxWidth().widthIn(max = 860.dp),
                placeholder = "Search ${source.name}: " + searchable(capabilities),
                focusRequests = MusicNav.searchFocusRequests,
                large = true,
                onSubmit = { DiscoverState.submitted = DiscoverState.query.trim() },
            )
        }
        item("sources") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                available.forEach { connection ->
                    MusicChip(connection.name, connection.id == source.id) {
                        DiscoverState.sourceId = connection.id
                        DiscoverState.genre = null
                    }
                }
                MusicChip("Add a source", false) { ShellState.openPlugins(ModuleId.MUSIC) }
            }
        }
        if (genres.isNotEmpty() && submitted.isBlank()) {
            item("genres") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item("all") { MusicChip("All genres", genre == null) { DiscoverState.genre = null } }
                    items(genres.take(40), key = { it }) { name -> MusicChip(name, genre == name) { DiscoverState.genre = name } }
                }
            }
        }

        when (val found = results) {
            null -> {
                when (val list = featured) {
                    Loaded.Loading -> item("loading") { LoadingRow("Asking ${source.name}…") }
                    is Loaded.Failed -> item("failed") { FailureRow("${source.name}: ${list.message}") { DiscoverState.featured.remove(featuredKey); DiscoverState.genre = genre } }
                    is Loaded.Ready -> if (list.value.isNotEmpty()) {
                        item("featured-title") {
                            MusicSectionHeader(
                                when {
                                    genre != null -> "$genre on ${source.name}"
                                    source.adapter == AudiusAdapter.ID -> "Trending on Audius"
                                    else -> "From ${source.name}"
                                },
                                action = "Play all", onAction = { RemoteActions.play(source.id, list.value) },
                            )
                        }
                        item("featured") { RemoteTrackGrid(source.id, list.value) }
                    } else if (capabilities.featured) {
                        item("nothing") { MusicEmptyState(Icons.Rounded.Explore, "Nothing to show", if (genre != null) "${source.name} has nothing filed under $genre." else "${source.name} put nothing forward. Try a search.") }
                    }
                }
                if (ownPlaylists.isNotEmpty()) {
                    item("own-playlists") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MusicSectionHeader("Playlists on ${source.name}")
                            RemoteItemRow(ownPlaylists.map { it.toItem(source.id) })
                        }
                    }
                }
            }
            Loaded.Loading -> item("searching") { LoadingRow("Searching ${source.name}…") }
            is Loaded.Failed -> item("search-failed") { FailureRow("${source.name}: ${found.message}") { DiscoverState.submitted = ""; DiscoverState.submitted = DiscoverState.query.trim() } }
            is Loaded.Ready -> {
                val value = found.value
                if (value.isEmpty) {
                    item("none") { MusicEmptyState(Icons.Rounded.Explore, "Nothing found for “$submitted”", "Check the spelling, try fewer words, or look in another source.") }
                }
                if (value.tracks.isNotEmpty()) {
                    item("songs-title") { MusicSectionHeader("Songs", action = "Play all", onAction = { RemoteActions.play(source.id, value.tracks) }) }
                    itemsIndexed(value.tracks, key = { _, track -> "t-" + track.remoteId }) { index, track ->
                        RemoteTrackRow(source.id, track, value.tracks, index)
                    }
                }
                if (value.artists.isNotEmpty()) {
                    item("artists") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MusicSectionHeader("Artists")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(value.artists, key = { it.remoteId }) { artist -> RemoteArtistCard(source.id, artist) }
                            }
                        }
                    }
                }
                if (value.albums.isNotEmpty()) {
                    item("albums") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MusicSectionHeader("Albums")
                            RemoteItemRow(value.albums.map { it.toItem(source.id) })
                        }
                    }
                }
                if (value.playlists.isNotEmpty()) {
                    item("playlists") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MusicSectionHeader("Playlists")
                            RemoteItemRow(value.playlists.map { it.toItem(source.id) })
                        }
                    }
                }
            }
        }
    }
}

private fun searchable(capabilities: CatalogCapabilities): String = listOfNotNull(
    "songs".takeIf { capabilities.searchTracks }, "artists".takeIf { capabilities.searchArtists },
    "albums".takeIf { capabilities.searchAlbums }, "playlists".takeIf { capabilities.searchPlaylists },
).joinToString(", ")

internal fun RemoteAlbum.toItem(sourceId: Long) = RemoteItem(
    sourceId, RemoteItem.Kind.ALBUM, remoteId, title,
    listOfNotNull(artist, year?.toString(), trackCount?.let { plural(it, "song") }).joinToString("  ·  ").ifEmpty { null },
    artworkKey, artworkUrl, artworkAlternates,
)

internal fun RemotePlaylist.toItem(sourceId: Long) = RemoteItem(
    sourceId, RemoteItem.Kind.PLAYLIST, remoteId, name,
    listOfNotNull(owner, trackCount?.let { plural(it, "song") }).joinToString("  ·  ").ifEmpty { null },
    artworkKey, artworkUrl, artworkAlternates, description,
)

internal fun RemoteArtist.toItem(sourceId: Long) = RemoteItem(
    sourceId, RemoteItem.Kind.ARTIST, remoteId, name,
    listOfNotNull(trackCount?.let { plural(it, "song") }, followerCount?.let { "$it followers" }).joinToString("  ·  ").ifEmpty { null },
    artworkKey, artworkUrl, artworkAlternates, description,
)

/** Shown instead of Discover while the profile has no source that can be searched. */
@Composable
private fun DiscoverSetup() {
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    var installing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MusicPageHeading("Discover", "Connect a source to search and play music beyond the files on this computer.")
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
            SetupCard(Icons.Rounded.Album, "Your own server", "Navidrome, another OpenSubsonic server, or Jellyfin.", "Connect", Modifier.weight(1f)) {
                ShellState.openPlugins(ModuleId.MUSIC)
            }
            SetupCard(Icons.Rounded.LibraryAdd, "A plugin", "Import a Music plugin from its address or a file.", "Open Plugins", Modifier.weight(1f)) {
                ShellState.openPlugins(ModuleId.MUSIC)
            }
        }
    }
}

/** A compact card offering one way to get started. */
@Composable
internal fun SetupCard(icon: ImageVector, title: String, text: String, action: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(18.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = LocalMusic.current.style.accent, modifier = Modifier.size(28.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary, maxLines = 1)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, minLines = 2, maxLines = 3)
        Spacer(Modifier.height(2.dp))
        MusicButton(action, onClick = onClick, primary = false, enabled = enabled)
    }
}

@Composable
private fun LoadingRow(text: String) {
    val tokens = MaterialTheme.nuvio
    Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = LocalMusic.current.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
    }
}

@Composable
private fun FailureRow(text: String, onRetry: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.danger.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
        MusicButton("Try again", onClick = onRetry, primary = false)
    }
}

// ---------------------------------------------------------------------------- cards and rows

/** Songs as a grid of square covers, as many to a row as the window allows. */
@Composable
internal fun RemoteTrackGrid(sourceId: Long, tracks: List<RemoteTrack>, maxRows: Int = Int.MAX_VALUE) {
    val cardSize = LocalMusic.current.style.cardSize
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / cardSize).toInt().coerceAtLeast(2)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            tracks.chunked(columns).take(maxRows).forEachIndexed { rowIndex, row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEachIndexed { column, track ->
                        RemoteTrackCard(sourceId, track, Modifier.weight(1f)) { RemoteActions.play(sourceId, tracks, rowIndex * columns + column) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun RemoteTrackCard(sourceId: Long, track: RemoteTrack, modifier: Modifier = Modifier, onPlay: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val style = LocalMusic.current.style
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .hoverable(hover)
            .clickable(enabled = track.playable, role = Role.Button, onClickLabel = "Play ${track.title}", onClick = onPlay)
            .alpha(if (track.playable) 1f else 0.45f)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            RemoteArtwork(sourceId, track.artworkKey, track.artworkUrl, track.artworkAlternates, Modifier.fillMaxSize())
            if (hovered && track.playable) {
                Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MusicIconButton(Icons.Rounded.LibraryAdd, "Add to library", { RemoteActions.keep(sourceId, listOf(track)) }, size = 36.dp, tint = tokens.colors.textPrimary, background = tokens.colors.surfacePopover)
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(style.accent).clickable(role = Role.Button, onClickLabel = "Play ${track.title}", onClick = onPlay),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = style.onAccent, modifier = Modifier.size(24.dp)) }
                }
            }
        }
        Text(track.title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (track.playable) listOfNotNull(track.artist, track.genre).joinToString(" · ") else "Not available to stream",
            style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One song of a catalogue in a list: play it, queue it, add it to the library or to a playlist. */
@Composable
internal fun RemoteTrackRow(sourceId: Long, track: RemoteTrack, all: List<RemoteTrack>, index: Int, showNumber: Boolean = false) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(music.style.rowHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered) tokens.colors.surface else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(hover)
            .clickable(enabled = track.playable, role = Role.Button, onClickLabel = "Play ${track.title}") { RemoteActions.play(sourceId, all, index) }
            .alpha(if (track.playable) 1f else 0.45f)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showNumber) {
            Text((track.trackNo ?: (index + 1)).toString(), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, modifier = Modifier.width(26.dp))
        }
        RemoteArtwork(sourceId, track.artworkKey, track.artworkUrl, track.artworkAlternates, Modifier.size(music.style.rowHeight - 14.dp), shape = RoundedCornerShape(8.dp))
        Column(Modifier.weight(1.4f)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (track.playable) track.artist ?: "Unknown artist" else "Not available to stream",
                style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(track.album.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (hovered && track.playable) {
            MusicIconButton(Icons.AutoMirrored.Rounded.PlaylistPlay, "Play next", {
                actions.io {
                    val playable = music.services.sources.materialize(music.profileId, sourceId, listOf(track), keep = false)
                    music.services.player.playNext(playable)
                    MusicNav.message = "“${track.title}” plays next."
                    music.services.sources.fetchArtwork(music.profileId, sourceId, listOf(track))
                }
            }, size = 32.dp)
            MusicIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to a playlist", {
                RemoteActions.keep(sourceId, listOf(track)) { ids -> if (ids.isNotEmpty()) MusicNav.dialog = MusicDialog.AddToPlaylist(ids) }
            }, size = 32.dp)
            MusicIconButton(Icons.Rounded.LibraryAdd, "Add to library", { RemoteActions.keep(sourceId, listOf(track)) }, size = 32.dp)
        }
        Text(
            if (track.durationMs > 0) formatDuration(track.durationMs) else "", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted,
            maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(72.dp),
        )
    }
}

@Composable
private fun RemoteArtistCard(sourceId: Long, artist: RemoteArtist) {
    val tokens = MaterialTheme.nuvio
    val width = LocalMusic.current.style.cardSize - 20.dp
    Column(
        modifier = Modifier.width(width).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { MusicNav.open(MusicRoute.RemoteCollection(artist.toItem(sourceId))) }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RemoteArtwork(sourceId, artist.artworkKey, artist.artworkUrl, artist.artworkAlternates, Modifier.fillMaxWidth().aspectRatio(1f), shape = CircleShape, placeholder = Icons.Rounded.Person)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(artist.name, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (artist.verified) Icon(Icons.Rounded.Verified, contentDescription = "Verified", tint = LocalMusic.current.style.accent, modifier = Modifier.size(16.dp))
        }
        Text(
            listOfNotNull(artist.trackCount?.let { plural(it, "song") }, artist.albumCount?.let { plural(it, "album") }).joinToString(" · "),
            style = small(), color = tokens.colors.textMuted, maxLines = 1,
        )
    }
}

/** A shelf of catalogue albums or playlists. */
@Composable
internal fun RemoteItemRow(items: List<RemoteItem>) {
    val tokens = MaterialTheme.nuvio
    val width: Dp = LocalMusic.current.style.cardSize
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items, key = { it.kind.name + it.remoteId }) { item ->
            Column(
                modifier = Modifier.width(width).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { MusicNav.open(MusicRoute.RemoteCollection(item)) }.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RemoteArtwork(item.sourceId, item.artworkKey, item.artworkUrl, item.artworkAlternates, Modifier.fillMaxWidth().aspectRatio(1f), placeholder = Icons.Rounded.Album)
                Text(item.title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle.orEmpty(), style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------------------- an album, playlist or artist of a catalogue

@Composable
internal fun RemoteCollectionScreen(item: RemoteItem) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val tracks by produceState<Loaded<List<RemoteTrack>>>(Loaded.Loading, item) {
        value = Loaded.Loading
        value = withContext(Dispatchers.IO) {
            runCatching {
                val sources = music.services.sources
                when (item.kind) {
                    RemoteItem.Kind.ALBUM -> sources.albumTracks(music.profileId, item.sourceId, item.remoteId)
                    RemoteItem.Kind.PLAYLIST -> sources.playlistTracks(music.profileId, item.sourceId, item.remoteId)
                    RemoteItem.Kind.ARTIST -> sources.artistTracks(music.profileId, item.sourceId, item.remoteId)
                }
            }.fold({ Loaded.Ready(it) }, { Loaded.Failed(it.message ?: "The source did not answer.") })
        }
    }
    val loaded = (tracks as? Loaded.Ready)?.value.orEmpty()
    val playable = loaded.filter { it.playable }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item("header") {
            Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(26.dp), verticalAlignment = Alignment.Bottom) {
                RemoteArtwork(
                    item.sourceId, item.artworkKey, item.artworkUrl, item.artworkAlternates, Modifier.size(music.style.heroArtwork),
                    shape = if (item.kind == RemoteItem.Kind.ARTIST) CircleShape else RoundedCornerShape(16.dp),
                    placeholder = if (item.kind == RemoteItem.Kind.ARTIST) Icons.Rounded.Person else Icons.Rounded.Album,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.kind.label.uppercase(), style = MaterialTheme.typography.labelLarge, color = tokens.colors.textMuted)
                    MusicPageHeading(item.title)
                    Text(
                        // The source may already have said how many songs there are; do not say it twice.
                        details(
                            item.subtitle,
                            plural(loaded.size, "song").takeIf { loaded.isNotEmpty() && item.subtitle?.contains(it) != true },
                            loaded.sumOf { it.durationMs }.takeIf { it > 0 }?.let(::formatLongDuration),
                        ),
                        style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary,
                    )
                    item.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                        MusicButton("Play", icon = Icons.Rounded.PlayArrow, enabled = playable.isNotEmpty(), onClick = { RemoteActions.play(item.sourceId, loaded) })
                        MusicButton("Shuffle", icon = Icons.Rounded.Shuffle, primary = false, enabled = playable.isNotEmpty(), onClick = { RemoteActions.play(item.sourceId, loaded.shuffled()) })
                        MusicButton("Add to library", icon = Icons.Rounded.Add, primary = false, enabled = playable.isNotEmpty(), onClick = { RemoteActions.keep(item.sourceId, loaded) })
                        if (item.kind != RemoteItem.Kind.ARTIST) {
                            MusicButton("Save as playlist", icon = Icons.AutoMirrored.Rounded.PlaylistAdd, primary = false, enabled = playable.isNotEmpty(), onClick = { RemoteActions.savePlaylist(item.sourceId, item) })
                        }
                    }
                }
            }
        }
        when (val state = tracks) {
            Loaded.Loading -> item("loading") { LoadingRow("Loading…") }
            is Loaded.Failed -> item("failed") { FailureRow(state.message) { MusicNav.back() } }
            is Loaded.Ready -> if (state.value.isEmpty()) {
                item("empty") { MusicEmptyState(Icons.Rounded.MusicNote, "No songs here", "The source lists nothing for this ${item.kind.label.lowercase()}.") }
            } else {
                itemsIndexed(state.value, key = { index, track -> "$index-${track.remoteId}" }) { index, track ->
                    RemoteTrackRow(item.sourceId, track, state.value, index, showNumber = item.kind != RemoteItem.Kind.ARTIST)
                }
            }
        }
    }
}
