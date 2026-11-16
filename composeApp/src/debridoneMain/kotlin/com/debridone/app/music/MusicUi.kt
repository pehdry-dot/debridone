package com.debridone.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.DebridOneRuntime
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.ShellState
import com.debridone.app.shell.parseHex
import com.debridone.module.CommonAppearanceKeys
import com.debridone.module.ModuleStatus
import com.debridone.music.MusicPreferences
import com.debridone.music.MusicServices
import com.debridone.music.library.Track
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class LibraryTab(val key: String, val label: String) {
    SONGS("songs", "Songs"),
    ALBUMS("albums", "Albums"),
    ARTISTS("artists", "Artists"),
    GENRES("genres", "Genres"),
    PLAYLISTS("playlists", "Playlists"),
    FAVOURITES("favourites", "Favourites"),
    OFFLINE("offline", "Available offline"),
    ;

    companion object {
        fun fromKey(key: String?): LibraryTab = entries.firstOrNull { it.key == key } ?: SONGS
    }
}

internal sealed interface MusicRoute {
    data object Home : MusicRoute
    data class Library(val tab: LibraryTab) : MusicRoute
    data object Search : MusicRoute
    data class AlbumPage(val id: Long) : MusicRoute
    data class ArtistPage(val id: Long) : MusicRoute
    data class GenrePage(val name: String) : MusicRoute
    data class PlaylistPage(val id: Long) : MusicRoute
    data object Settings : MusicRoute
}

internal sealed interface MusicDialog {
    data object AddFolder : MusicDialog
    data class AddToPlaylist(val trackIds: List<Long>) : MusicDialog
    data class NewPlaylist(val trackIds: List<Long> = emptyList()) : MusicDialog
    data class RenamePlaylist(val id: Long, val name: String) : MusicDialog
    data class DeletePlaylist(val id: Long, val name: String) : MusicDialog
    data class RemoveTracks(val trackIds: List<Long>, val label: String) : MusicDialog
    data class RemoveFolder(val id: Long, val path: String) : MusicDialog
    data class EditTrack(val trackId: Long) : MusicDialog
}

/** Where the user is inside the Music section. Kept outside composables so it survives leaving it. */
internal object MusicNav {
    val stack = mutableStateListOf<MusicRoute>(MusicRoute.Home)
    val current: MusicRoute get() = stack.last()
    var searchText by mutableStateOf("")
    var playerExpanded by mutableStateOf(false)

    /** Whether the full-screen player shows lyrics instead of the queue. */
    var lyricsVisible by mutableStateOf(false)

    /** In the compact player layout: whether the queue/lyrics panel was asked for. */
    var sidePanelRequested by mutableStateOf(false)
    var dialog by mutableStateOf<MusicDialog?>(null)

    /** A short confirmation or error shown at the bottom of the section. */
    var message by mutableStateOf<String?>(null)

    fun open(route: MusicRoute) {
        if (current != route) stack += route
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Jumps to a top-level page, forgetting the pages opened from the previous one. */
    fun root(route: MusicRoute) {
        stack.clear()
        stack += route
    }

    /** Incremented to ask the search field to take keyboard focus (Cmd+F). */
    var searchFocusRequests by mutableIntStateOf(0)
        private set

    fun focusSearch() {
        searchFocusRequests++
    }

    private var profileId: Long? = null

    /** Starts from Home when a different profile becomes active; a profile never sees another's page. */
    fun onProfile(id: Long) {
        if (profileId == id) return
        profileId = id
        root(MusicRoute.Home)
        searchText = ""
        playerExpanded = false
        dialog = null
        message = null
    }
}

/** Things screens ask the engine to do. Each runs off the UI thread and reports failures. */
internal class MusicActions(private val services: MusicServices, private val profileId: Long, private val scope: CoroutineScope) {
    fun io(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                MusicNav.message = t.message ?: "Something went wrong."
            }
        }
    }

    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean? = null) {
        val playable = tracks.filterNot { it.missing }
        if (playable.isEmpty()) {
            MusicNav.message = "These files could not be found."
            return
        }
        val start = tracks.getOrNull(startIndex)?.let { wanted -> playable.indexOfFirst { it.id == wanted.id } }?.coerceAtLeast(0) ?: 0
        services.player.play(playable, start, shuffle)
        services.player.engineError.value?.let { MusicNav.message = "Audio is unavailable: $it" }
    }

    fun playAlbum(albumId: Long, shuffle: Boolean = false) = io { play(services.library.albumTracks(profileId, albumId), shuffle = shuffle) }

    fun playArtist(artistId: Long, shuffle: Boolean = false) = io { play(services.library.artistTracks(profileId, artistId), shuffle = shuffle) }

    fun playPlaylist(playlistId: Long, shuffle: Boolean = false) = io { play(services.playlists.tracks(profileId, playlistId), shuffle = shuffle) }

    fun playNext(tracks: List<Track>) {
        services.player.playNext(tracks.filterNot { it.missing })
        MusicNav.message = if (tracks.size == 1) "Playing next: ${tracks[0].title}" else "${tracks.size} songs play next"
    }

    fun addToQueue(tracks: List<Track>) {
        services.player.addToQueue(tracks.filterNot { it.missing })
        MusicNav.message = if (tracks.size == 1) "Added to the queue: ${tracks[0].title}" else "${tracks.size} songs added to the queue"
    }

    fun toggleFavorite(track: Track) = io { services.library.setTrackFavorite(profileId, track.id, !track.favorite) }

    fun addToPlaylist(playlistId: Long, trackIds: List<Long>) = io {
        val added = services.playlists.add(profileId, playlistId, trackIds)
        val name = services.playlists.playlist(profileId, playlistId)?.name.orEmpty()
        MusicNav.message = "${plural(added, "song")} added to $name"
    }

    fun createPlaylist(name: String, trackIds: List<Long>, open: Boolean) = io {
        val playlist = services.playlists.create(profileId, name)
        if (trackIds.isNotEmpty()) services.playlists.add(profileId, playlist.id, trackIds)
        if (open) MusicNav.open(MusicRoute.PlaylistPage(playlist.id)) else MusicNav.message = "Created ${playlist.name}"
    }

    fun removeTracks(trackIds: List<Long>) = io {
        services.library.removeTracks(profileId, trackIds)
        services.player.removeDeletedFromQueue()
        MusicNav.message = "${plural(trackIds.size, "song")} removed from the library"
    }

    fun download(tracks: List<Track>) {
        MusicNav.message = if (tracks.size == 1) "Downloading ${tracks[0].title}…" else "Downloading ${tracks.size} songs…"
        com.debridone.app.DebridOneRuntime.modules.music.download(tracks.map { it.id }) { saved ->
            val error = services.sources.activity.value.lastDownloadError
            MusicNav.message = if (saved == tracks.size) "${plural(saved, "song")} saved for offline listening" else "Saved $saved of ${tracks.size}. ${error.orEmpty()}"
        }
    }

    fun removeDownload(track: Track) = io { services.sources.removeDownload(profileId, track.id) }

    /** Opens the folder that holds [track] in the system file manager. */
    fun revealInFileManager(track: Track) = io {
        val path = track.path ?: return@io
        val file = java.io.File(path)
        val os = System.getProperty("os.name").orEmpty().lowercase()
        when {
            os.contains("mac") -> ProcessBuilder("open", "-R", file.absolutePath).start()
            os.contains("win") -> ProcessBuilder("explorer", "/select,", file.absolutePath).start()
            else -> ProcessBuilder("xdg-open", file.parentFile.absolutePath).start()
        }
    }
}

internal val LocalMusicActions = androidx.compose.runtime.staticCompositionLocalOf<MusicActions> { error("no Music actions") }

/**
 * Provides the Music engine to [content]. Renders nothing while the module is starting or when no
 * profile is active, so callers do not have to check.
 */
@Composable
fun MusicProvider(content: @Composable () -> Unit) {
    val module = DebridOneRuntime.modules.music
    val services by module.services.collectAsStateWithLifecycle()
    val profileId by module.activeProfile.collectAsStateWithLifecycle()
    val appearance by module.appearance.current.collectAsStateWithLifecycle()
    val reduceMotion by ShellState.reduceMotion.collectAsStateWithLifecycle()
    val tokens = MaterialTheme.nuvio
    val ready = services ?: return
    val profile = profileId ?: return

    val accent = parseHex(appearance.accentHex) ?: tokens.colors.accent
    val style = MusicStyle(
        accent = accent,
        onAccent = if (accent.luminance() > 0.55f) Color(0xFF101014) else Color.White,
        cardSize = when (appearance.values[CommonAppearanceKeys.CARD_SIZE]) {
            "small" -> 140.dp
            "large" -> 210.dp
            else -> 172.dp
        },
        rowHeight = when (appearance.values[CommonAppearanceKeys.DENSITY]) {
            "compact" -> 48.dp
            "spacious" -> 68.dp
            else -> 58.dp
        },
        dynamicColours = appearance.values["dynamic_album_colours"]?.toBooleanStrictOrNull() ?: true,
        playlistGrid = appearance.values["playlist_presentation"] != "list",
        heroArtwork = when (appearance.values["artwork_size"]) {
            "small" -> 150.dp
            "medium" -> 186.dp
            else -> 220.dp
        },
        playerArtwork = when (appearance.values["artwork_size"]) {
            "small" -> 300.dp
            "medium" -> 410.dp
            else -> 520.dp
        },
        playerSidePanel = appearance.values["player_layout"] != "compact",
        playerBackground = appearance.values["player_background"] ?: "gradient",
        reduceMotion = reduceMotion,
    )
    val textScale = when (appearance.values["text_size"]) {
        "small" -> 0.9f
        "large" -> 1.15f
        else -> 1f
    }
    val baseDensity = LocalDensity.current
    val density = remember(baseDensity, textScale) { Density(baseDensity.density, baseDensity.fontScale * textScale) }
    val scope = rememberCoroutineScope()
    val actions = remember(ready, profile) { MusicActions(ready, profile, scope) }
    val context = remember(ready, profile, style) { MusicContext(ready, profile, style) }
    MusicNav.onProfile(profile)
    CompositionLocalProvider(LocalMusic provides context, LocalMusicActions provides actions, LocalDensity provides density, content = content)
}

/** The Music section. */
@Composable
fun MusicScreen() {
    val tokens = MaterialTheme.nuvio
    val module = DebridOneRuntime.modules.music
    val status by module.status.collectAsStateWithLifecycle()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    (status as? ModuleStatus.Failed)?.let { failed ->
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            Notice("Music ran into a problem and stopped: ${failed.message}\nThe other sections are not affected. Restart DebridOne to try again.", PillTone.DANGER)
        }
        return
    }
    if (status != ModuleStatus.Running) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = tokens.colors.accent) }
        return
    }

    MusicProvider {
        val music = LocalMusic.current
        // Open on the view this profile chose as its starting point.
        LaunchedEffect(music.profileId) {
            when (music.services.preferences[MusicPreferences.DEFAULT_VIEW]) {
                "library" -> MusicNav.root(MusicRoute.Library(LibraryTab.fromKey(music.services.preferences[MusicPreferences.LIBRARY_TAB])))
                else -> Unit
            }
        }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(top = topInset)) {
                MusicTopBar()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val route = MusicNav.current) {
                        MusicRoute.Home -> MusicHome()
                        is MusicRoute.Library -> MusicLibrary(route.tab)
                        MusicRoute.Search -> MusicSearch()
                        is MusicRoute.AlbumPage -> AlbumScreen(route.id)
                        is MusicRoute.ArtistPage -> ArtistScreen(route.id)
                        is MusicRoute.GenrePage -> GenreScreen(route.name)
                        is MusicRoute.PlaylistPage -> PlaylistScreen(route.id)
                        MusicRoute.Settings -> MusicSettings()
                    }
                }
            }
            MusicMessageBar(Modifier.align(Alignment.BottomCenter))
            MusicDialogs()
        }
    }
}

@Composable
private fun MusicTopBar() {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val route = MusicNav.current
    val scan by music.services.scanner.progress.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (MusicNav.stack.size > 1) {
            MusicIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", MusicNav::back, tint = tokens.colors.textPrimary, background = tokens.colors.surface)
        }
        Text("Music", style = MaterialTheme.typography.headlineLarge, color = tokens.colors.textPrimary)
        Spacer(Modifier.width(10.dp))
        MusicChip("Home", route == MusicRoute.Home) { MusicNav.root(MusicRoute.Home) }
        MusicChip("Library", route is MusicRoute.Library) {
            MusicNav.root(MusicRoute.Library(LibraryTab.fromKey(music.services.preferences[MusicPreferences.LIBRARY_TAB])))
        }
        Spacer(Modifier.weight(1f))
        if (scan.running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = music.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text(
                    if (scan.filesFound > 0) "Scanning ${scan.filesProcessed} of ${scan.filesFound}" else "Scanning…",
                    style = small(), color = tokens.colors.textSecondary,
                )
            }
        }
        SearchField(
            value = MusicNav.searchText,
            onValueChange = { text ->
                MusicNav.searchText = text
                if (text.isNotBlank() && MusicNav.current != MusicRoute.Search) MusicNav.open(MusicRoute.Search)
            },
            modifier = Modifier.width(300.dp),
            focusRequests = MusicNav.searchFocusRequests,
        )
        MusicIconButton(Icons.Rounded.Sync, "Rescan the library", { DebridOneRuntime.modules.music.rescan() }, enabled = !scan.running)
        MusicIconButton(
            Icons.Rounded.Settings, "Music settings", { MusicNav.open(MusicRoute.Settings) },
            tint = if (route == MusicRoute.Settings) music.style.accent else tokens.colors.textSecondary,
        )
    }
}

@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search your music",
    focusRequests: Int = 0,
) {
    val tokens = MaterialTheme.nuvio
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) runCatching { focus.requestFocus() }
    }
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(50))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(50))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(LocalMusic.current.style.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close, contentDescription = "Clear search", tint = tokens.colors.textMuted,
                modifier = Modifier.size(18.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onValueChange("") },
            )
        }
    }
}

/** Shows [MusicNav.message] for a few seconds. */
@Composable
private fun MusicMessageBar(modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val message = MusicNav.message ?: return
    LaunchedEffect(message) {
        delay(3_500)
        if (MusicNav.message == message) MusicNav.message = null
    }
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.textPrimary,
        modifier = modifier
            .padding(bottom = 18.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceElevated)
            .border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(12.dp))
            .clickable { MusicNav.message = null }
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}
