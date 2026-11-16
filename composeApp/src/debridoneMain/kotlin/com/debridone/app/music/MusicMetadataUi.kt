package com.debridone.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
                            Text(
                                line.text.ifBlank { "♪" },
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize, lineHeight = fontSize * 1.3, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                                color = if (active) tokens.colors.textPrimary else tokens.colors.textMuted,
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
