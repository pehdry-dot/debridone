package com.debridone.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import com.debridone.music.metadata.CoverOption
import com.debridone.music.metadata.LyricsCandidate
import com.debridone.music.metadata.MbRelease
import com.debridone.music.metadata.MetadataRefresh
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.DebridOneRuntime
import com.debridone.music.library.Track
import com.debridone.music.metadata.Lyrics
import com.debridone.music.metadata.MusicMetadataService
import com.debridone.music.metadata.RecordingMatch
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The online lookup inside the song editor. It asks MusicBrainz only when the button is pressed,
 * lists what was found, and applies the chosen entry to the fields that are not locked.
 */
@Composable
internal fun MusicMetadataTools(track: Track, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val scope = rememberCoroutineScope()
    val providers by DebridOneRuntime.modules.music.metadataProviders.providers.collectAsStateWithLifecycle()
    val enabled = providers.firstOrNull { it.id == MusicMetadataService.MUSICBRAINZ }?.enabled == true
    var searching by remember(track.id) { mutableStateOf(false) }
    var matches by remember(track.id) { mutableStateOf<List<RecordingMatch>?>(null) }
    var error by remember(track.id) { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tokens.colors.background).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Look this song up", style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                Text(
                    if (enabled) "Sends the title and artist to MusicBrainz and lists what it finds. Locked fields are left alone."
                    else "MusicBrainz is switched off in Plugins › Music.",
                    style = small(), color = tokens.colors.textMuted,
                )
            }
            if (searching) CircularProgressIndicator(color = music.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            MusicButton(
                if (matches == null) "Search MusicBrainz" else "Search again",
                {
                    searching = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { music.services.metadata.findRecordings(music.profileId, track.id) } }
                        searching = false
                        result.fold({ matches = it }, { error = it.message ?: "The lookup failed." })
                    }
                },
                icon = Icons.Rounded.TravelExplore, primary = false, enabled = enabled && !searching,
            )
        }
        error?.let { Text(it, style = small(), color = tokens.colors.danger) }
        matches?.let { found ->
            if (found.isEmpty()) Text("MusicBrainz has nothing that matches this title and artist.", style = small(), color = tokens.colors.textSecondary)
            found.forEach { match ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(details(match.title, match.artist), style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            details(
                                match.album, match.year?.toString(),
                                match.trackNo?.let { number -> "track $number" + (match.trackTotal?.let { " of $it" } ?: "") },
                                match.durationMs?.let(::formatDuration), "match ${match.score}%",
                            ),
                            style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    MusicButton("Use", {
                        // Close the editor so that it reopens showing the new values.
                        onDismiss()
                        scope.launch {
                            val applied = withContext(Dispatchers.IO) { runCatching { music.services.metadata.apply(music.profileId, track.id, match) } }
                            MusicNav.message = applied.fold(
                                { if (it.isEmpty()) "Nothing to change: those fields are already set or locked." else "Updated " + it.sorted().joinToString(", ") { field -> field.replace('_', ' ') } },
                                { it.message ?: "The details could not be applied." },
                            )
                            MusicNav.dialog = MusicDialog.EditTrack(track.id)
                        }
                    }, primary = false)
                }
            }
        }
    }
}

/** The lyrics of the playing song, following the music when they are time-stamped. */
@Composable
internal fun LyricsPanel(track: Track?, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val scope = rememberCoroutineScope()
    val appearance by DebridOneRuntime.modules.music.appearance.current.collectAsStateWithLifecycle()
    val style = appearance.values["lyrics_style"] ?: "standard"
    val fontSize = when (style) {
        "large" -> 26.sp
        "minimal" -> 15.sp
        else -> 19.sp
    }
    var reload by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var state by remember(track?.id) { mutableStateOf<Result<Lyrics?>?>(null) }
    LaunchedEffect(track?.id, reload) {
        state = null
        val id = track?.id ?: return@LaunchedEffect
        state = withContext(Dispatchers.IO) { runCatching { music.services.metadata.lyrics(music.profileId, id) } }
    }

    Column(modifier) {
        val loaded = state
        val lyrics = loaded?.getOrNull()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                track == null -> Unit
                loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = music.style.accent, strokeWidth = 2.dp) }
                loaded.isFailure -> MusicEmptyState(Icons.Rounded.Lyrics, "Lyrics could not be loaded", loaded.exceptionOrNull()?.message ?: "Try again later.")
                lyrics == null -> MusicEmptyState(Icons.Rounded.Lyrics, "No lyrics for this song", "None are stored in the file, and none were found online.")
                lyrics.instrumental -> MusicEmptyState(Icons.Rounded.Lyrics, "Instrumental", "This piece has no words.")
                lyrics.synced.isNotEmpty() -> {
                    val current = lyrics.synced.indexOfLast { it.timeMs <= positionMs + 150 }
                    val listState = rememberLazyListState()
                    LaunchedEffect(current) {
                        val target = (current - 2).coerceAtLeast(0)
                        if (current >= 0) runCatching { if (music.style.reduceMotion) listState.scrollToItem(target) else listState.animateScrollToItem(target) }
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(if (style == "minimal") 6.dp else 12.dp)) {
                        itemsIndexed(lyrics.synced) { index, line ->
                            val active = index == current
                            // The line being sung stands out; the others fade with their distance from it.
                            val distance = if (current < 0) 3 else kotlin.math.abs(index - current)
                            val emphasis = when {
                                active -> 1f
                                distance == 1 -> 0.62f
                                distance == 2 -> 0.46f
                                else -> 0.34f
                            }
                            Text(
                                line.text.ifBlank { "♪" },
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = if (active) fontSize * 1.16 else fontSize,
                                    lineHeight = fontSize * 1.36,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                                ),
                                color = tokens.colors.textPrimary.copy(alpha = emphasis),
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = "Jump to this line") { onSeek(line.timeMs) }.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                else -> Text(
                    lyrics.plain.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize, lineHeight = fontSize * 1.45),
                    color = tokens.colors.textPrimary,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 14.dp),
                )
            }
        }
        if (track != null && loaded != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (lyrics?.origin) {
                        MusicMetadataService.ORIGIN_EMBEDDED -> "From the file"
                        MusicMetadataService.ORIGIN_LRCLIB -> "From LRCLIB"
                        MusicMetadataService.ORIGIN_MANUAL -> "Entered by you"
                        else -> ""
                    },
                    style = small(), color = tokens.colors.textMuted, modifier = Modifier.weight(1f),
                )
                MusicButton("Search", { searching = true }, primary = false)
                if (lyrics != null && lyrics.origin != MusicMetadataService.ORIGIN_MANUAL) {
                    MusicButton("Look up again", {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { music.services.metadata.refreshLyrics(music.profileId, track.id) } }
                            result.exceptionOrNull()?.let { MusicNav.message = it.message ?: "The lyrics could not be looked up." }
                            reload++
                        }
                    }, primary = false)
                }
                MusicButton(if (lyrics == null) "Add lyrics" else "Edit", { editing = true }, primary = false)
                if (lyrics != null) {
                    MusicButton("Remove", {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { music.services.metadata.clearLyrics(music.profileId, track.id) } }
                            reload++
                        }
                    }, primary = false)
                }
            }
        }
    }

    if (searching && track != null) {
        LyricsSearchDialog(track, onDismiss = { searching = false }) {
            searching = false
            reload++
        }
    }

    if (editing && track != null) {
        val existing = state?.getOrNull()
        var text by remember(track.id) {
            mutableStateOf(existing?.synced?.takeIf { it.isNotEmpty() }?.joinToString("\n") { MusicMetadataService.formatLrcLine(it) } ?: existing?.plain.orEmpty())
        }
        MusicDialogFrame("Lyrics of ${track.title}", onDismiss = { editing = false }, width = 620) {
            Text("Plain text, or LRC lines such as [00:12.50] words to make them follow the music.", style = small(), color = tokens.colors.textMuted)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(music.style.accent),
                modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(10.dp)).background(tokens.colors.background)
                    .border(1.dp, tokens.colors.borderDefault, RoundedCornerShape(10.dp)).padding(12.dp),
            )
            DialogButtons("Save", onDismiss = { editing = false }) {
                editing = false
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { music.services.metadata.saveLyrics(music.profileId, track.id, text) } }
                    reload++
                }
            }
        }
    }
}


/** Searching LRCLIB by hand, when the automatic lookup found nothing or the wrong words. */
@Composable
private fun LyricsSearchDialog(track: Track, onDismiss: () -> Unit, onApplied: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val scope = rememberCoroutineScope()
    var query by remember(track.id) { mutableStateOf(listOfNotNull(track.title, track.artist).joinToString(" ")) }
    var results by remember(track.id) { mutableStateOf<Result<List<LyricsCandidate>>?>(null) }
    var working by remember { mutableStateOf(false) }
    val search = {
        if (!working && query.isNotBlank()) {
            working = true
            scope.launch {
                results = withContext(Dispatchers.IO) { runCatching { music.services.metadata.searchLyricsFor(music.profileId, track.id, query) } }
                working = false
            }
        }
    }
    LaunchedEffect(track.id) { search() }
    MusicDialogFrame("Search lyrics for ${track.title}", onDismiss = onDismiss, width = 640) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogTextField(query, { query = it }, "Song and artist", Modifier.weight(1f), onSubmit = { search() })
            MusicButton(if (working) "Searching…" else "Search", { search() }, enabled = !working && query.isNotBlank())
        }
        val found = results
        when {
            found == null -> Text("Searching LRCLIB…", style = small(), color = tokens.colors.textMuted)
            found.isFailure -> Text(found.exceptionOrNull()?.message ?: "The search failed.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger)
            found.getOrThrow().isEmpty() -> Text("LRCLIB has nothing for that. Try other words, or type the lyrics yourself with “Add lyrics”.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
            else -> LazyColumn(Modifier.fillMaxWidth().height(320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(found.getOrThrow()) { _, candidate ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tokens.colors.background).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(candidate.title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1)
                            Text(
                                details(
                                    candidate.artist, candidate.album, candidate.durationSeconds?.let { formatDuration(it * 1000L) },
                                    when {
                                        candidate.instrumental -> "instrumental"
                                        candidate.synced -> "follows the music"
                                        else -> "plain text"
                                    },
                                ),
                                style = small(), color = tokens.colors.textMuted, maxLines = 1,
                            )
                        }
                        MusicButton("Use", {
                            scope.launch {
                                withContext(Dispatchers.IO) { runCatching { music.services.metadata.applyLyrics(music.profileId, track.id, candidate) } }
                                onApplied()
                            }
                        }, primary = false)
                    }
                }
            }
        }
        Text("The length of each version is shown: the one closest to your file (${formatDuration(track.durationMs)}) is listed first.", style = small(), color = tokens.colors.textMuted)
    }
}

/**
 * Online details of an album: the MusicBrainz editions it could be, with their covers from the
 * Cover Art Archive. Applying an edition corrects the album's songs; fields you edited are kept.
 */
@Composable
internal fun AlbumMetadataTools(album: com.debridone.music.library.Album, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val scope = rememberCoroutineScope()
    var releases by remember(album.id) { mutableStateOf<Result<List<MbRelease>>?>(null) }
    var covers by remember(album.id) { mutableStateOf<Result<List<CoverOption>>?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(album.id) {
        releases = withContext(Dispatchers.IO) { runCatching { music.services.metadata.findReleasesForAlbum(music.profileId, album.id) } }
        covers = withContext(Dispatchers.IO) { runCatching { music.services.metadata.albumCoverOptions(music.profileId, album.id) } }
    }
    MusicDialogFrame("Details of ${album.title} online", onDismiss = { if (working == null) onDismiss() }, width = 680) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Editions on MusicBrainz", style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
            MusicButton("Refresh from the matched edition", {
                working = "refresh"
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { music.services.metadata.refreshAlbum(music.profileId, album.id) } }
                    working = null
                    note = result.fold(
                        { if (it is MetadataRefresh.NeedsMatch) "This album has not been matched yet: choose an edition below." else "The album is up to date with MusicBrainz." },
                        { it.message ?: "The refresh failed." },
                    )
                }
            }, primary = false, enabled = working == null)
        }
        val found = releases
        when {
            found == null -> Text("Asking MusicBrainz…", style = small(), color = tokens.colors.textMuted)
            found.isFailure -> Text(found.exceptionOrNull()?.message ?: "MusicBrainz could not be asked.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger)
            found.getOrThrow().isEmpty() -> Text("MusicBrainz lists no release with this title and artist.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
            else -> LazyColumn(Modifier.fillMaxWidth().height(190.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(found.getOrThrow()) { _, release ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tokens.colors.background).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(release.title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1)
                            Text(
                                details(release.artist, release.date, release.country, release.format, release.trackCount?.let { plural(it, "track") }, release.label, release.status),
                                style = small(), color = tokens.colors.textMuted, maxLines = 1,
                            )
                        }
                        MusicButton(if (working == release.id) "Applying…" else "Use", {
                            working = release.id
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching { music.services.metadata.applyRelease(music.profileId, album.id, release.id) } }
                                working = null
                                result.fold(
                                    { update ->
                                        MusicNav.message = "Matched ${update.tracksMatched} of ${update.tracksMatched + update.tracksUnmatched} songs; ${update.fieldsChanged} details corrected." +
                                            if (update.coverApplied) " A cover was added." else ""
                                        onDismiss()
                                        update.albumId?.takeIf { it != album.id }?.let { MusicNav.back(); MusicNav.open(MusicRoute.AlbumPage(it)) }
                                    },
                                    { note = it.message ?: "The edition could not be applied." },
                                )
                            }
                        }, primary = false, enabled = working == null)
                    }
                }
            }
        }
        Text("Covers in the Cover Art Archive", style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
        val offered = covers
        when {
            offered == null -> Text("Asking the Cover Art Archive…", style = small(), color = tokens.colors.textMuted)
            offered.isFailure -> Text(offered.exceptionOrNull()?.message ?: "The archive could not be asked.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger)
            offered.getOrThrow().isEmpty() -> Text("The archive has no artwork for this album. You can still choose a picture of your own from the album's menu.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
            else -> androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(offered.getOrThrow().size) { index ->
                    val option = offered.getOrThrow()[index]
                    Column(
                        Modifier.width(132.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = working == null, role = Role.Button, onClickLabel = "Use this cover") {
                            working = "cover"
                            scope.launch {
                                val applied = withContext(Dispatchers.IO) { runCatching { music.services.metadata.applyAlbumCover(music.profileId, album.id, option) } }
                                working = null
                                note = applied.fold({ if (it) "The cover was changed." else "The archive no longer has that image." }, { it.message ?: "The cover could not be fetched." })
                            }
                        }.padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        CoverPreview(option, Modifier.fillMaxWidth().aspectRatio(1f))
                        Text(option.label, style = small(), color = tokens.colors.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary) }
        Text("Applying an edition fills in the fields you have not edited yourself. Edited fields are locked and stay as they are.", style = small(), color = tokens.colors.textMuted)
    }
}

@Composable
private fun CoverPreview(option: CoverOption, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val bitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, option.thumbnailUrl) {
        val path = withContext(Dispatchers.IO) { runCatching { music.services.metadata.coverPreview(option)?.toString() }.getOrNull() } ?: return@produceState
        value = ArtworkBitmaps.cached(path, full = false) ?: ArtworkBitmaps.load(path, full = false)
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(tokens.colors.surfaceElevated), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            androidx.compose.foundation.Image(bitmap = image, contentDescription = option.label, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.Lyrics, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.fillMaxSize(0.3f).alpha(0f))
        }
    }
}
