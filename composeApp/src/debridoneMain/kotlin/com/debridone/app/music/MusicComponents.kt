package com.debridone.app.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debridone.music.MusicServices
import com.debridone.music.library.Album
import com.debridone.music.library.Artist
import com.debridone.music.library.ArtworkRef
import com.debridone.music.library.Track
import com.debridone.music.playlists.Playlist
import com.nuvio.app.core.ui.nuvio
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How the Music section looks for the active profile (from its appearance settings). */
@Immutable
internal data class MusicStyle(
    val accent: Color,
    val onAccent: Color,
    val cardSize: Dp = 172.dp,
    val rowHeight: Dp = 58.dp,
    val dynamicColours: Boolean = true,
    val playlistGrid: Boolean = true,
    /** Side of the artwork on album, artist and playlist pages. */
    val heroArtwork: Dp = 220.dp,
    /** Largest side of the artwork in the full-screen player. */
    val playerArtwork: Dp = 520.dp,
    /** The full-screen player shows the queue or lyrics beside the artwork. */
    val playerSidePanel: Boolean = true,
    /** `gradient`, `blur` or `plain`. */
    val playerBackground: String = "gradient",
    val reduceMotion: Boolean = false,
)

/** Everything a Music screen needs: the engine's services, the profile and the look. */
@Immutable
internal class MusicContext(val services: MusicServices, val profileId: Long, val style: MusicStyle)

internal val LocalMusic = compositionLocalOf<MusicContext> { error("Music screens must be inside MusicScreen") }

/**
 * Runs a library query off the UI thread and runs it again whenever the library changes or one of
 * [keys] does. Bursts of changes (a scan in progress) collapse into one query.
 */
@Composable
internal fun <T> rememberMusicQuery(vararg keys: Any?, initial: T, query: (MusicServices, Long) -> T): State<T> {
    val music = LocalMusic.current
    val revision by music.services.library.revision.collectAsState()
    return produceState(initial, music.services, music.profileId, revision, *keys) {
        delay(60)
        value = withContext(Dispatchers.IO) { runCatching { query(music.services, music.profileId) }.getOrDefault(initial) }
    }
}

// ---------------------------------------------------------------------------- artwork

/** Decoded cover images, least recently used first out. */
internal object ArtworkBitmaps {
    private const val MAX_THUMBS = 160
    private const val MAX_LARGE = 4
    private val thumbs = lru(MAX_THUMBS)
    private val large = lru(MAX_LARGE)

    private fun lru(limit: Int) = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > limit
    }

    fun cached(path: String, full: Boolean): ImageBitmap? = synchronized(this) { (if (full) large else thumbs)[path] }

    suspend fun load(path: String, full: Boolean): ImageBitmap? {
        cached(path, full)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                org.jetbrains.skia.Image.makeFromEncoded(Files.readAllBytes(Paths.get(path))).toComposeImageBitmap()
            }.getOrNull()?.also { bitmap -> synchronized(this@ArtworkBitmaps) { (if (full) large else thumbs)[path] = bitmap } }
        }
    }
}

/** A cover, or a quiet placeholder when there is none. [full] loads the large image. */
@Composable
internal fun ArtworkImage(
    artwork: ArtworkRef?,
    modifier: Modifier = Modifier,
    full: Boolean = false,
    shape: Shape = RoundedCornerShape(10.dp),
    placeholder: ImageVector = Icons.Rounded.MusicNote,
) {
    val tokens = MaterialTheme.nuvio
    val path = artwork?.let { if (full) it.filePath else it.thumbPath }
    val bitmap by produceState(path?.let { ArtworkBitmaps.cached(it, full) }, path) {
        value = path?.let { ArtworkBitmaps.cached(it, full) ?: ArtworkBitmaps.load(it, full) }
    }
    Box(
        modifier = modifier.clip(shape).background(tokens.colors.surfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(placeholder, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.fillMaxSize(0.42f))
        }
    }
}

/** Up to four covers in a 2x2 grid, for playlists without a picture of their own. */
@Composable
internal fun PlaylistArtwork(playlist: Playlist, modifier: Modifier = Modifier, full: Boolean = false, shape: Shape = RoundedCornerShape(10.dp)) {
    val chosen = playlist.artwork
    when {
        chosen != null -> ArtworkImage(chosen, modifier, full, shape)
        playlist.covers.size >= 4 -> Column(modifier.clip(shape)) {
            for (row in 0..1) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    for (column in 0..1) {
                        ArtworkImage(playlist.covers[row * 2 + column], Modifier.weight(1f).fillMaxSize(), shape = RoundedCornerShape(0.dp))
                    }
                }
            }
        }
        else -> ArtworkImage(playlist.covers.firstOrNull(), modifier, full, shape)
    }
}

// ---------------------------------------------------------------------------- text and buttons

internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0) + 500) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** "3 hr 12 min" for album and playlist headers. */
internal fun formatLongDuration(millis: Long): String {
    if (millis <= 0) return ""
    val minutes = (millis + 30_000) / 60_000
    return when {
        minutes < 1 -> "under a minute"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }
}

internal fun plural(count: Int, singular: String, plural: String = singular + "s"): String = "$count ${if (count == 1) singular else plural}"

/** Joins the parts of a details line, leaving out the ones that have nothing to say. */
internal fun details(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString("  ·  ")

@Composable
internal fun small(): TextStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp)

@Composable
internal fun MusicSectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    val tokens = MaterialTheme.nuvio
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = LocalMusic.current.style.accent,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** A filled or outlined pill button with an optional icon. */
@Composable
internal fun MusicButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    val style = LocalMusic.current.style
    val background = if (primary) style.accent else tokens.colors.surfaceElevated
    val content = if (primary) style.onAccent else tokens.colors.textPrimary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .then(if (primary) Modifier else Modifier.border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(50)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** A round icon-only button. [description] is what a screen reader announces. */
@Composable
internal fun MusicIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color = MaterialTheme.nuvio.colors.textSecondary,
    background: Color = Color.Transparent,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(size * 0.56f))
    }
}

@Composable
internal fun MusicChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val style = LocalMusic.current.style
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) style.onAccent else tokens.colors.textSecondary,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) style.accent else tokens.colors.surface)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Centred message for a list that has nothing to show, with an optional way forward. */
@Composable
internal fun MusicEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(52.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp), content = actions)
    }
}

// ---------------------------------------------------------------------------- cards

@Composable
internal fun AlbumCard(album: Album, onClick: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    MediaCard(
        title = album.title,
        subtitle = listOfNotNull(album.displayArtist, album.year?.toString()).joinToString(" · "),
        onClick = onClick,
        onPlay = onPlay,
        modifier = modifier,
    ) { ArtworkImage(album.artwork, Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp)) }
}

@Composable
internal fun PlaylistCard(playlist: Playlist, onClick: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    MediaCard(
        title = playlist.name,
        subtitle = plural(playlist.trackCount, "song"),
        onClick = onClick,
        onPlay = onPlay,
        modifier = modifier,
    ) { PlaylistArtwork(playlist, Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp)) }
}

@Composable
internal fun ArtistCard(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ArtworkImage(artist.artwork, Modifier.fillMaxWidth().aspectRatio(1f), shape = CircleShape, placeholder = Icons.Rounded.Person)
        Text(artist.name, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(plural(artist.albumCount, "album"), plural(artist.trackCount, "song")).joinToString(" · "),
            style = small(), color = tokens.colors.textMuted, maxLines = 1,
        )
    }
}

/** Square artwork with a title and a subtitle; a play button appears on hover. */
@Composable
private fun MediaCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val style = LocalMusic.current.style
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .hoverable(hover)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            artwork()
            if (hovered) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(style.accent)
                        .clickable(role = Role.Button, onClickLabel = "Play $title", onClick = onPlay),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Play $title", tint = style.onAccent, modifier = Modifier.size(24.dp)) }
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------- track rows

/** What a track row's menu and buttons can do. Screens supply only what makes sense for them. */
internal class TrackActions(
    val onPlay: () -> Unit,
    val menu: @Composable (dismiss: () -> Unit) -> Unit,
    val onToggleFavorite: (() -> Unit)? = null,
)

/**
 * One song in a list. [leading] is the track number or position when given; otherwise the cover is
 * shown. The row highlights when it is the song that is playing.
 */
@Composable
internal fun TrackRow(
    track: Track,
    actions: TrackActions,
    modifier: Modifier = Modifier,
    leading: String? = null,
    showArtwork: Boolean = true,
    showAlbum: Boolean = true,
    isCurrent: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    val style = LocalMusic.current.style
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val titleColour = when {
        track.missing -> tokens.colors.textMuted
        isCurrent -> style.accent
        else -> tokens.colors.textPrimary
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(style.rowHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered || menuOpen) tokens.colors.surface else Color.Transparent)
            .hoverable(hover)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) menuOpen = true
                    }
                }
            }
            .clickable(role = Role.Button, onClickLabel = "Play ${track.title}", onClick = actions.onPlay)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                if (hovered && !track.missing) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.colors.textPrimary, modifier = Modifier.size(20.dp))
                } else {
                    Text(leading, style = MaterialTheme.typography.bodyMedium, color = if (isCurrent) style.accent else tokens.colors.textMuted)
                }
            }
        }
        if (showArtwork) ArtworkImage(track.artwork, Modifier.size(style.rowHeight - 16.dp), shape = RoundedCornerShape(6.dp))
        Column(Modifier.weight(1.4f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal),
                    color = titleColour, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (track.missing) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = "File not found", tint = tokens.colors.warning, modifier = Modifier.size(15.dp))
                }
            }
            Text(
                if (track.missing) (if (track.isLocal) "File not found" else "Source unavailable") else track.displayArtist,
                style = small(), color = if (track.missing) tokens.colors.warning else tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (showAlbum) {
            Text(
                track.album.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        actions.onToggleFavorite?.let { toggle ->
            if (track.favorite || hovered) {
                MusicIconButton(
                    icon = if (track.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    description = if (track.favorite) "Remove from favourites" else "Add to favourites",
                    onClick = toggle, size = 30.dp, tint = if (track.favorite) style.accent else tokens.colors.textMuted,
                )
            } else {
                Spacer(Modifier.width(30.dp))
            }
        }
        Text(if (track.durationMs > 0) formatDuration(track.durationMs) else "—", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, modifier = Modifier.width(48.dp))
        Box {
            MusicIconButton(Icons.Rounded.MoreHoriz, "More actions for ${track.title}", { menuOpen = true }, size = 30.dp)
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                actions.menu { menuOpen = false }
            }
        }
    }
}

@Composable
internal fun MenuItem(text: String, icon: ImageVector? = null, danger: Boolean = false, onClick: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val colour = if (danger) tokens.colors.danger else tokens.colors.textPrimary
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = colour) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, tint = colour, modifier = Modifier.size(18.dp)) } },
        onClick = onClick,
    )
}

@Composable
internal fun MenuDivider() {
    HorizontalDivider(color = MaterialTheme.nuvio.colors.borderSubtle, modifier = Modifier.padding(vertical = 4.dp))
}

/**
 * A thin horizontal slider for progress and volume. [onValueChange] follows the pointer;
 * [onValueChangeFinished] fires when it is released.
 */
@Composable
internal fun LineSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    description: String,
    onValueChangeFinished: () -> Unit = {},
    enabled: Boolean = true,
    active: Color = LocalMusic.current.style.accent,
    inactive: Color = MaterialTheme.nuvio.colors.borderDefault,
    trackHeight: Dp = 4.dp,
    alwaysShowThumb: Boolean = false,
) {
    val change by androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val finished by androidx.compose.runtime.rememberUpdatedState(onValueChangeFinished)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val fraction = value.coerceIn(0f, 1f)
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .height(20.dp)
            .hoverable(hover)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    dragging = true
                    change((down.position.x / size.width).coerceIn(0f, 1f))
                    drag(down.id) { pointer ->
                        change((pointer.position.x / size.width).coerceIn(0f, 1f))
                        pointer.consume()
                    }
                    dragging = false
                    finished()
                }
            }
            .then(
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = description
                    progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(fraction, 0f..1f)
                    if (enabled) {
                        setProgress { target ->
                            change(target.coerceIn(0f, 1f))
                            finished()
                            true
                        }
                    }
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(trackHeight).clip(RoundedCornerShape(50)).background(inactive)) {
            Box(Modifier.fillMaxWidth(fraction).height(trackHeight).background(if (enabled) active else inactive))
        }
        if (enabled && (alwaysShowThumb || hovered || dragging)) {
            val thumb = 12.dp
            Box(
                Modifier
                    .padding(start = ((maxWidth - thumb) * fraction).coerceAtLeast(0.dp))
                    .size(thumb)
                    .clip(CircleShape)
                    .background(active),
            )
        }
    }
}
