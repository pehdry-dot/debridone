package com.debridone.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.music.library.Track
import com.debridone.music.playback.PlayerState
import com.debridone.music.playback.RepeatMode
import com.nuvio.app.core.ui.nuvio

val MusicMiniPlayerHeight: Dp = 78.dp

/** True when there is something in the queue, i.e. when the mini-player should be shown. */
@Composable
fun rememberMusicHasQueue(): Boolean {
    val module = com.debridone.app.DebridOneRuntime.modules.music
    val services by module.services.collectAsStateWithLifecycle()
    val player = services?.player ?: return false
    val state by player.state.collectAsStateWithLifecycle()
    return state.queue.isNotEmpty()
}

/** The shell calls this when Streaming starts a video: music pauses and lets go of the media keys. */
fun pauseMusicForVideo() {
    com.debridone.app.DebridOneRuntime.modules.music.services.value?.player?.pause()
    MusicSystemIntegration.onVideoStarted()
}

/** The video is over; Music takes the media keys back if it still has a queue. It stays paused. */
fun videoEndedForMusic() {
    MusicSystemIntegration.onVideoEnded()
}

/** Keyboard and media-key actions, usable from anywhere in the app. They do nothing without a queue. */
object MusicRemote {
    private val player get() = com.debridone.app.DebridOneRuntime.modules.music.services.value?.player

    val hasQueue: Boolean get() = player?.state?.value?.queue?.isNotEmpty() == true

    fun togglePlayPause(): Boolean = act { it.togglePlayPause() }
    fun next(): Boolean = act { it.next() }
    fun previous(): Boolean = act { it.previous() }
    fun changeVolume(delta: Int): Boolean = act { it.setVolume((it.state.value.volume + delta).coerceIn(0, 100)) }

    /** Moves keyboard focus to the Music search field. */
    fun focusSearch(): Boolean {
        MusicNav.focusSearch()
        return true
    }

    /** Closes the full-screen player if it is open. */
    fun closePlayer(): Boolean {
        if (!MusicNav.playerExpanded) return false
        MusicNav.playerExpanded = false
        return true
    }

    private inline fun act(block: (com.debridone.music.MusicPlayer) -> Unit): Boolean {
        val target = player ?: return false
        if (target.state.value.queue.isEmpty()) return false
        block(target)
        return true
    }
}

/** Opens or closes the full-screen player from outside the Music section. */
fun setMusicPlayerExpanded(expanded: Boolean) {
    MusicNav.playerExpanded = expanded
}

val isMusicPlayerExpanded: Boolean get() = MusicNav.playerExpanded

// ---------------------------------------------------------------------------- shared controls

/** A progress slider that only seeks when the drag ends, so dragging does not stutter the audio. */
@Composable
private fun SeekBar(state: PlayerState, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, thumb: Boolean = true) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    LineSlider(
        value = dragging ?: (state.positionMs.toFloat() / duration),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { onSeek((it * duration).toLong()) }
            dragging = null
        },
        description = "Position in the song",
        enabled = state.current != null && state.durationMs > 0,
        alwaysShowThumb = thumb,
        modifier = modifier,
    )
}

@Composable
private fun VolumeControl(state: PlayerState, modifier: Modifier = Modifier, sliderWidth: Dp = 96.dp) {
    val tokens = MaterialTheme.nuvio
    val player = LocalMusic.current.services.player
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        MusicIconButton(
            if (state.muted || state.volume == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            if (state.muted) "Unmute" else "Mute", player::toggleMute, size = 32.dp,
        )
        LineSlider(
            value = if (state.muted) 0f else state.volume / 100f,
            onValueChange = { player.setVolume((it * 100).toInt()) },
            description = "Volume",
            active = tokens.colors.textSecondary,
            trackHeight = 3.dp,
            modifier = Modifier.width(sliderWidth),
        )
    }
}

@Composable
private fun TransportButtons(state: PlayerState, large: Boolean) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val player = music.services.player
    val small = if (large) 44.dp else 34.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (large) 14.dp else 4.dp)) {
        MusicIconButton(
            Icons.Rounded.Shuffle, if (state.shuffle) "Shuffle is on" else "Shuffle is off", { player.setShuffle(!state.shuffle) },
            size = small, tint = if (state.shuffle) music.style.accent else tokens.colors.textMuted,
        )
        MusicIconButton(Icons.Rounded.SkipPrevious, "Previous", player::previous, size = small, tint = tokens.colors.textPrimary, enabled = state.current != null)
        MusicIconButton(
            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (state.isPlaying) "Pause" else "Play", player::togglePlayPause,
            size = if (large) 64.dp else 42.dp, tint = music.style.onAccent, background = music.style.accent, enabled = state.current != null,
        )
        MusicIconButton(Icons.Rounded.SkipNext, "Next", player::next, size = small, tint = tokens.colors.textPrimary, enabled = state.hasNext)
        MusicIconButton(
            if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            when (state.repeat) {
                RepeatMode.OFF -> "Repeat is off"
                RepeatMode.ALL -> "Repeating the queue"
                RepeatMode.ONE -> "Repeating this song"
            },
            player::cycleRepeat, size = small, tint = if (state.repeat == RepeatMode.OFF) tokens.colors.textMuted else music.style.accent,
        )
    }
}

// ---------------------------------------------------------------------------- mini-player

/**
 * The bar that stays at the bottom of the window in every section while there is a queue. The
 * shell places it; it must be inside [MusicProvider].
 */
@Composable
fun MusicMiniPlayer(modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val player = music.services.player
    val state by player.state.collectAsStateWithLifecycle()
    val track by player.nowPlaying.collectAsStateWithLifecycle()
    val actions = LocalMusicActions.current
    val current = state.current ?: return
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()

    Column(modifier.fillMaxWidth().height(MusicMiniPlayerHeight).background(tokens.colors.surfaceElevated)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.colors.borderSubtle))
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .hoverable(hover)
                    .clickable(role = Role.Button, onClickLabel = "Open the player") { MusicNav.playerExpanded = true }
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box {
                    ArtworkImage(track?.artwork, Modifier.size(52.dp), shape = RoundedCornerShape(8.dp))
                    if (hovered) {
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(Icons.Rounded.OpenInFull, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(current.title, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        state.lastError?.let { "Could not play “${it.title}”" } ?: listOfNotNull(current.artist, current.album).joinToString(" · "),
                        style = small(), color = if (state.lastError != null) tokens.colors.warning else tokens.colors.textMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(Modifier.weight(1.5f).widthIn(max = 620.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TransportButtons(state, large = false)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(22.dp)) {
                    Text(formatDuration(state.positionMs), style = small(), color = tokens.colors.textMuted)
                    SeekBar(state, player::seekTo, Modifier.weight(1f), thumb = false)
                    Text(formatDuration(state.durationMs), style = small(), color = tokens.colors.textMuted)
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                track?.let { playing ->
                    MusicIconButton(
                        if (playing.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (playing.favorite) "Remove from favourites" else "Add to favourites",
                        { actions.toggleFavorite(playing) }, size = 32.dp,
                        tint = if (playing.favorite) music.style.accent else tokens.colors.textMuted,
                    )
                }
                VolumeControl(state)
                MusicIconButton(Icons.AutoMirrored.Rounded.QueueMusic, "Open the player and queue", { MusicNav.playerExpanded = true }, size = 34.dp)
            }
        }
    }
}

// ---------------------------------------------------------------------------- full-screen player

/** The full-window player: large artwork, controls, and the queue. Drawn by the shell above everything. */
@Composable
fun MusicFullPlayer() {
    if (!MusicNav.playerExpanded) return
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val player = music.services.player
    val state by player.state.collectAsStateWithLifecycle()
    val track by player.nowPlaying.collectAsStateWithLifecycle()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val actions = LocalMusicActions.current
    val current = state.current
    LaunchedEffect(current == null) { if (current == null) MusicNav.playerExpanded = false }
    if (current == null) return

    val tint = track?.artwork?.dominantColor?.takeIf { music.style.dynamicColours }?.let { Color(it) } ?: music.style.accent
    val sidePanel = music.style.playerSidePanel || MusicNav.sidePanelRequested
    Box(
        modifier = Modifier
            .zIndex(1000f)
            .fillMaxSize()
            .background(tokens.colors.background)
            .then(
                if (music.style.playerBackground == "gradient") {
                    Modifier.background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), tint.copy(alpha = 0.12f), tokens.colors.background)))
                } else {
                    Modifier
                },
            )
            // Swallow clicks so nothing underneath reacts.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        if (music.style.playerBackground == "blur" && track?.artwork != null) {
            // The cover itself, out of focus and darkened, behind everything.
            ArtworkImage(track?.artwork, Modifier.fillMaxSize().blur(70.dp), full = true, shape = RoundedCornerShape(0.dp))
            Box(Modifier.fillMaxSize().background(tokens.colors.background.copy(alpha = 0.62f)))
        }
        Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 28.dp, top = topInset + 20.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            // Left: artwork and controls.
            Column(Modifier.weight(1.25f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MusicIconButton(Icons.Rounded.KeyboardArrowDown, "Close the player", { MusicNav.playerExpanded = false }, size = 40.dp, tint = tokens.colors.textPrimary, background = tokens.colors.surface.copy(alpha = 0.6f))
                    Spacer(Modifier.weight(1f))
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
                    Spacer(Modifier.weight(1f))
                    if (music.style.playerSidePanel) {
                        Spacer(Modifier.width(40.dp))
                    } else {
                        MusicIconButton(
                            Icons.AutoMirrored.Rounded.QueueMusic, if (MusicNav.sidePanelRequested) "Hide the queue and lyrics" else "Show the queue and lyrics",
                            { MusicNav.sidePanelRequested = !MusicNav.sidePanelRequested }, size = 40.dp,
                            tint = if (MusicNav.sidePanelRequested) music.style.accent else tokens.colors.textPrimary, background = tokens.colors.surface.copy(alpha = 0.6f),
                        )
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                    val side = minOf(maxWidth, maxHeight, music.style.playerArtwork)
                    ArtworkImage(track?.artwork, Modifier.size(side), full = true, shape = RoundedCornerShape(20.dp))
                }
                Row(Modifier.fillMaxWidth().widthIn(max = 640.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(current.title, style = MaterialTheme.typography.headlineLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(current.artist, current.album).joinToString(" · "),
                            style = MaterialTheme.typography.titleMedium, color = tokens.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = track?.albumId != null, role = Role.Button) {
                                track?.albumId?.let { albumId ->
                                    MusicNav.playerExpanded = false
                                    com.debridone.app.shell.ShellState.select(com.debridone.app.shell.ShellSection.MUSIC)
                                    MusicNav.open(MusicRoute.AlbumPage(albumId))
                                }
                            },
                        )
                    }
                    track?.let { playing ->
                        MusicIconButton(
                            if (playing.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (playing.favorite) "Remove from favourites" else "Add to favourites",
                            { actions.toggleFavorite(playing) }, size = 44.dp,
                            tint = if (playing.favorite) music.style.accent else tokens.colors.textSecondary,
                        )
                    }
                }
                state.lastError?.let { error ->
                    Text(
                        "Could not play “${error.title}”: ${error.message}",
                        style = small(), color = tokens.colors.warning,
                        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(6.dp)).clickable { player.dismissError() }.padding(4.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(formatDuration(state.positionMs), style = small(), color = tokens.colors.textSecondary)
                    SeekBar(state, player::seekTo, Modifier.weight(1f))
                    Text(formatDuration(state.durationMs), style = small(), color = tokens.colors.textSecondary)
                }
                Row(Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(track?.format, track?.sampleRate?.let { "%.1f kHz".format(it / 1000.0) }, track?.bitrateKbps?.let { "$it kbps" }).joinToString(" · "),
                        style = small(), color = tokens.colors.textMuted, modifier = Modifier.weight(1f),
                    )
                    TransportButtons(state, large = true)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { VolumeControl(state, sliderWidth = 110.dp) }
                }
            }
            // Right: the queue, or the lyrics.
            if (sidePanel) Column(
                modifier = Modifier
                    .weight(0.75f).widthIn(max = 460.dp).fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(tokens.colors.surface.copy(alpha = 0.72f))
                    .border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(20.dp)),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MusicChip("Queue", !MusicNav.lyricsVisible) { MusicNav.lyricsVisible = false }
                    MusicChip("Lyrics", MusicNav.lyricsVisible) { MusicNav.lyricsVisible = true }
                }
                if (MusicNav.lyricsVisible) {
                    LyricsPanel(track, state.positionMs, player::seekTo, Modifier.fillMaxSize())
                } else {
                    QueuePanel(state, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun QueuePanel(state: PlayerState, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val player = music.services.player
    val ids = state.queue.map { it.trackId }
    val tracks by rememberMusicQuery(ids, initial = emptyMap<Long, Track>()) { services, profile ->
        services.library.tracksByIds(profile, ids.distinct()).associateBy { it.id }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.orderPosition) {
        val target = (state.orderPosition - 1).coerceAtLeast(0)
        if (state.orderPosition >= 0) runCatching { if (music.style.reduceMotion) listState.scrollToItem(target) else listState.animateScrollToItem(target) }
    }
    Column(modifier.padding(top = 10.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    details(plural(state.queue.size, "song"), formatLongDuration(state.queue.sumOf { it.durationMs }), "shuffled".takeIf { state.shuffle }),
                    style = small(), color = tokens.colors.textMuted,
                )
            }
            MusicButton("Clear", {
                MusicNav.playerExpanded = false
                player.clearQueue()
            }, primary = false)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
            itemsIndexed(state.order, key = { position, queueIndex -> "$queueIndex-${state.queue[queueIndex].trackId}-$position" }) { position, queueIndex ->
                val item = state.queue[queueIndex]
                val isCurrent = position == state.orderPosition
                val hover = remember { MutableInteractionSource() }
                val hovered by hover.collectIsHoveredAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isCurrent) music.style.accent.copy(alpha = 0.16f) else if (hovered) tokens.colors.surfaceElevated else Color.Transparent)
                        .hoverable(hover)
                        .clickable(role = Role.Button, onClickLabel = "Play ${item.title}") { player.skipTo(position) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ArtworkImage(tracks[item.trackId]?.artwork, Modifier.size(40.dp), shape = RoundedCornerShape(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.bodyLarge, color = if (isCurrent) music.style.accent else tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.artist.orEmpty(), style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (hovered && !isCurrent) {
                        if (position > state.orderPosition + 1) MusicIconButton(Icons.Rounded.ArrowUpward, "Move up in the queue", { player.moveInQueue(position, position - 1) }, size = 26.dp)
                        if (position > state.orderPosition && position < state.order.lastIndex) MusicIconButton(Icons.Rounded.ArrowDownward, "Move down in the queue", { player.moveInQueue(position, position + 1) }, size = 26.dp)
                        MusicIconButton(Icons.Rounded.Close, "Remove from the queue", { player.removeFromQueue(position) }, size = 26.dp)
                    } else {
                        Text(formatDuration(item.durationMs), style = small(), color = tokens.colors.textMuted)
                    }
                }
            }
        }
    }
}
