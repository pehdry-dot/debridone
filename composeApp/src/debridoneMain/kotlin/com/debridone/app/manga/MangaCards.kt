package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debridone.manga.library.Series
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.SeriesSummary
import com.nuvio.app.core.ui.nuvio

/** The shape of a book cover. */
internal const val COVER_ASPECT = 2f / 3f

/**
 * A series of the library as a portrait cover card, in the manner of Streaming's posters: cover,
 * title, Manga/Manhwa, publication status, reading progress and the number of unread chapters.
 * Hovering shows the actions; clicking opens the series page.
 */
@Composable
internal fun SeriesCard(
    summary: SeriesSummary,
    modifier: Modifier = Modifier,
    /** Shown under the title instead of status and source (for example "Ch. 12 · page 7"). */
    caption: String? = null,
    selected: Boolean? = null,
    /** For cards whose click resumes reading: the hover action leads to the series page instead. */
    detailsAction: Boolean = false,
    onClick: () -> Unit = { MangaNav.open(MangaRoute.SeriesPage(summary.series.id)) },
) {
    val series = summary.series
    val actions = LocalMangaActions.current
    val status = listOfNotNull(series.status, series.sourceName.takeIf { it.isNotBlank() }).joinToString(" · ")
    CoverCard(
        title = series.title,
        subtitle = caption ?: status,
        typeLabel = series.mediaType.label,
        cornerBadge = if (summary.unreadCount > 0 && summary.chapterCount > 0) summary.unreadCount.toString() else null,
        cornerBadgeDescription = "${plural(summary.unreadCount, "unread chapter")}",
        progress = summary.progress.takeIf { summary.readCount > 0 },
        selected = selected,
        modifier = modifier,
        onClick = onClick,
        cover = { SeriesCover(series, Modifier.fillMaxSize()) },
        hoverActions = {
            // Three at most: a fourth does not fit the smallest card. Where the click already
            // resumes reading, the first action leads to the series page instead.
            if (detailsAction) {
                HoverAction(Icons.Rounded.Info, "Series page of ${series.title}", primary = true) { MangaNav.open(MangaRoute.SeriesPage(series.id)) }
            } else {
                HoverAction(Icons.Rounded.PlayArrow, if (summary.readCount > 0) "Continue reading ${series.title}" else "Start reading ${series.title}", primary = true) {
                    actions.continueReading(series)
                }
            }
            HoverAction(if (series.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (series.favorite) "Remove from favourites" else "Add to favourites") {
                actions.toggleFavorite(series)
            }
            if (!series.inLibrary) HoverAction(Icons.Rounded.Add, "Add to library") { actions.toggleLibrary(series) }
        },
    )
}

/** A series as a source lists it: maybe in the library, but shown without progress. */
@Composable
internal fun SourceSeriesCard(series: Series, sourceName: String, modifier: Modifier = Modifier) {
    val actions = LocalMangaActions.current
    CoverCard(
        title = series.title,
        subtitle = listOfNotNull(series.status, sourceName.takeIf { it.isNotBlank() }).joinToString(" · "),
        typeLabel = series.mediaType.label,
        cornerBadge = if (series.inLibrary) "In library" else null,
        progress = null,
        modifier = modifier,
        onClick = { actions.open(series) },
        cover = { SeriesCover(series, Modifier.fillMaxSize()) },
        hoverActions = {
            if (series.inLibrary) HoverAction(Icons.Rounded.Check, "In your library", primary = true) { actions.open(series) }
            else HoverAction(Icons.Rounded.Add, "Add to library") { actions.toggleLibrary(series) }
        },
    )
}

/** A title that is not readable by itself (from a metadata provider): opens a search of the sources. */
@Composable
internal fun MetadataCard(title: String, subtitle: String, coverUrl: String?, type: MediaType, modifier: Modifier = Modifier, onClick: () -> Unit) {
    CoverCard(
        title = title,
        subtitle = subtitle,
        typeLabel = type.label,
        cornerBadge = null,
        progress = null,
        modifier = modifier,
        onClick = onClick,
        cover = { UrlCover(coverUrl, Modifier.fillMaxSize()) },
        hoverActions = {},
    )
}

@Composable
private fun CoverCard(
    title: String,
    subtitle: String,
    typeLabel: String,
    cornerBadge: String?,
    progress: Float?,
    modifier: Modifier = Modifier,
    cornerBadgeDescription: String = "",
    selected: Boolean? = null,
    onClick: () -> Unit,
    cover: @Composable () -> Unit,
    hoverActions: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val style = LocalManga.current.style
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val titleStyle = MaterialTheme.typography.titleSmall.copy(fontSize = (14 * style.titleScale).sp, fontWeight = FontWeight.SemiBold)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .hoverable(hover)
            .clickable(role = Role.Button, onClickLabel = "Open $title", onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT).clip(RoundedCornerShape(12.dp))) {
            cover()
            if (style.cardStyle == "overlay") {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.5f)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f)))),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = if (progress != null) 12.dp else 10.dp)) {
                    Text(title, style = titleStyle, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) Text(subtitle, style = small(), color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Badge(typeLabel, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (cornerBadge != null) {
                Badge(
                    cornerBadge,
                    Modifier.align(Alignment.TopEnd).padding(8.dp).semantics { contentDescription = cornerBadgeDescription },
                    background = style.accent, content = style.onAccent,
                )
            }
            if (selected != null) {
                Box(Modifier.fillMaxSize().background(if (selected) style.accent.copy(alpha = 0.28f) else Color.Transparent))
                Box(
                    Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape)
                        .background(if (selected) style.accent else Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = style.onAccent, modifier = Modifier.size(20.dp))
                }
            } else if (hovered) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(start = 8.dp, end = 8.dp, bottom = if (progress != null) 12.dp else 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { hoverActions() }
            }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.55f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(style.accent))
                }
            }
        }
        if (style.cardStyle == "poster") {
            Text(title, style = titleStyle, color = tokens.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HoverAction(icon: ImageVector, description: String, primary: Boolean = false, onClick: () -> Unit) {
    val style = LocalManga.current.style
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (primary) style.accent else Color.Black.copy(alpha = 0.7f))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = if (primary) style.onAccent else Color.White, modifier = Modifier.size(20.dp)) }
}

/** A horizontally scrolling shelf of cover cards with a heading, as on Streaming's home. */
@Composable
internal fun <T> CoverShelf(
    title: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    caption: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    key: (T) -> Any,
    card: @Composable (T, Modifier) -> Unit,
) {
    if (items.isEmpty()) return
    val style = LocalManga.current.style
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MangaSectionHeader(title, Modifier.padding(horizontal = 40.dp), caption = caption, action = action, onAction = onAction)
        LazyRow(contentPadding = PaddingValues(horizontal = 34.dp), horizontalArrangement = Arrangement.spacedBy(style.gridSpacing - 12.dp)) {
            items(items, key = key) { item -> card(item, Modifier.width(style.coverWidth)) }
        }
    }
}
