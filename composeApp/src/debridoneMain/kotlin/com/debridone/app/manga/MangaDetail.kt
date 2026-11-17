package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.StatusPill
import com.debridone.manga.MangaPreferences
import com.debridone.manga.library.Bookmark
import com.debridone.manga.library.Chapter
import com.debridone.manga.library.DownloadState
import com.debridone.manga.library.LibraryStatus
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.Series
import com.debridone.manga.library.SeriesSummary
import com.debridone.manga.sources.SourceProblem
import com.nuvio.app.core.ui.nuvio
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

internal fun formatDate(epochMillis: Long): String =
    if (epochMillis <= 0) "" else dateFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** A series' own page: who made it, what it is about, where it comes from, and its chapters. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeriesScreen(seriesId: Long) {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    val scope = rememberCoroutineScope()
    val summary by rememberMangaQuery(seriesId, initial = null as SeriesSummary?) { services, _ -> services.repository.summary(seriesId) }
    val chapters by rememberMangaQuery(seriesId, initial = emptyList<Chapter>()) { services, _ -> services.repository.chapters(seriesId) }
    val bookmarks by rememberMangaQuery(seriesId, initial = emptyList<Bookmark>()) { services, _ -> services.repository.bookmarks(seriesId) }
    val resume by rememberMangaQuery(seriesId, initial = null as Chapter?) { services, _ -> services.repository.series(seriesId)?.let(services.repository::resumeChapter) }

    var refreshing by remember(seriesId) { mutableStateOf(false) }
    var problem by remember(seriesId) { mutableStateOf<SourceProblem?>(null) }
    var descending by remember { mutableStateOf(manga.services.preferences.boolean(MangaPreferences.CHAPTERS_DESCENDING, true)) }
    var filter by rememberSaveable(seriesId) { mutableStateOf("") }
    var selecting by remember(seriesId) { mutableStateOf(false) }
    var selected by remember(seriesId) { mutableStateOf(setOf<Long>()) }
    var synopsisOpen by remember(seriesId) { mutableStateOf(false) }

    fun refresh(announce: Boolean) {
        val series = summary?.series ?: return
        if (refreshing) return
        refreshing = true
        problem = null
        scope.launch(Dispatchers.IO) {
            try {
                val added = if (series.isLocal) manga.services.local.rescan(series.id) else manga.services.sources.refresh(series.id)
                if (announce) MangaNav.message = if (added > 0) "${plural(added, "new chapter")}" else "Up to date"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                problem = SourceProblem.of(series.sourceName.ifBlank { "The source" }, failure)
            } finally {
                refreshing = false
            }
        }
    }

    // Details and chapters are fetched when the page is opened, unless they were fetched recently.
    LaunchedEffect(summary?.series?.id) {
        val series = summary?.series ?: return@LaunchedEffect
        val age = System.currentTimeMillis() - (series.chaptersRefreshedAt ?: 0)
        if (!series.isLocal && age > REFRESH_AFTER_MS) refresh(announce = false)
    }

    val current = summary
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = manga.style.accent) }
        return
    }
    val series = current.series
    val sourceInstalled = series.isLocal || manga.services.sources.find(series.extensionId) != null
    val needle = filter.trim().lowercase()
    val listed = chapters
        .filter { needle.isEmpty() || it.displayName.lowercase().contains(needle) || it.groupName.lowercase().contains(needle) }
        .let { if (descending) it.reversed() else it }

    Box(Modifier.fillMaxSize()) {
        // The cover again, large and blurred, as the page's backdrop.
        Box(Modifier.fillMaxWidth().height(380.dp)) {
            if (series.bannerUrl != null) {
                UrlCover(series.bannerUrl, Modifier.fillMaxSize().alpha(0.5f), shape = RoundedCornerShape(0.dp), width = 1400)
            } else {
                SeriesCover(series, Modifier.fillMaxSize().blur(48.dp).alpha(0.42f), shape = RoundedCornerShape(0.dp))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, manga.style.background))))
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 18.dp, bottom = 56.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    SeriesCover(series, Modifier.width(220.dp).aspectRatio(COVER_ASPECT), shape = RoundedCornerShape(16.dp), large = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            series.title,
                            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp, fontWeight = FontWeight.Bold),
                            color = tokens.colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                        if (series.altTitles.isNotEmpty()) {
                            Text(series.altTitles.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusPill(series.mediaType.label, PillTone.ACCENT, manga.style.accent)
                            series.status?.let { StatusPill(it, if (it == "Completed") PillTone.SUCCESS else PillTone.NEUTRAL) }
                            StatusPill(if (series.isLocal) "Local files" else series.sourceName.ifBlank { "Unknown source" })
                            series.originalLanguage?.takeIf { it != "MULTI" }?.let { StatusPill("Original: ${languageName(it)}") }
                            series.libraryStatus?.let { StatusPill(it.label, PillTone.ACCENT, manga.style.accent) }
                        }
                        val people = listOfNotNull(
                            series.authors.takeIf { it.isNotEmpty() }?.let { "Author: ${it.joinToString(", ")}" },
                            series.artists.takeIf { it.isNotEmpty() && it != series.authors }?.let { "Artist: ${it.joinToString(", ")}" },
                        )
                        if (people.isNotEmpty()) Text(people.joinToString("   ·   "), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                        if (series.description.isNotBlank()) {
                            Text(
                                series.description.trim(),
                                style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary,
                                maxLines = if (synopsisOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 860.dp).clip(RoundedCornerShape(8.dp)).clickable { synopsisOpen = !synopsisOpen },
                            )
                        }
                        val tags = series.tags + series.customTags
                        if (tags.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                tags.take(if (synopsisOpen) tags.size else 12).forEach { tag ->
                                    Text(
                                        tag, style = small(), color = tokens.colors.textSecondary,
                                        modifier = Modifier.clip(RoundedCornerShape(50)).background(tokens.colors.surface).padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }
                        if (current.chapterCount > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.width(220.dp).height(6.dp).clip(RoundedCornerShape(50)).background(tokens.colors.borderDefault)) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(current.progress).background(manga.style.accent))
                                }
                                Text(
                                    "${current.readCount} of ${plural(current.chapterCount, "chapter")} read" + if (current.downloadedCount > 0) "  ·  ${current.downloadedCount} downloaded" else "",
                                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                                )
                            }
                        }
                        SeriesActions(series, current, resume, chapters, refreshing, onRefresh = { refresh(announce = true) })
                    }
                }
            }

            item {
                Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!sourceInstalled) {
                        Notice(
                            "The source this series came from (${series.sourceName.ifBlank { "unknown" }}) is not installed or is switched off. " +
                                "Downloaded chapters can still be read; the rest needs the source back, or another one through Change Source.",
                            PillTone.WARNING,
                        )
                    }
                    problem?.let { Notice(it.message, if (it.temporary) PillTone.WARNING else PillTone.DANGER) }
                    if (bookmarks.isNotEmpty()) {
                        MangaSectionHeader("Bookmarks")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            bookmarks.forEach { bookmark ->
                                val chapter = chapters.firstOrNull { it.id == bookmark.chapterId } ?: return@forEach
                                MangaChip("${chapter.displayName.take(28)} · page ${bookmark.page + 1}", false, icon = Icons.Rounded.Bookmark) {
                                    MangaNav.open(MangaRoute.Reader(chapter.id, bookmark.page))
                                }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (chapters.isEmpty()) "Chapters" else "${plural(chapters.size, "chapter")}",
                            style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f),
                        )
                        if (refreshing) CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        if (chapters.size > 8) MangaSearchField(filter, { filter = it }, Modifier.width(220.dp), placeholder = "Find a chapter")
                        MangaIconButton(Icons.AutoMirrored.Rounded.Sort, if (descending) "Newest first" else "Oldest first", {
                            descending = !descending
                            manga.services.preferences.set(MangaPreferences.CHAPTERS_DESCENDING, descending.toString())
                        })
                        MangaIconButton(
                            Icons.Rounded.Checklist, "Select chapters",
                            {
                                selecting = !selecting
                                selected = emptySet()
                            },
                            tint = if (selecting) manga.style.accent else tokens.colors.textSecondary,
                        )
                    }
                    if (selecting) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MangaChip(if (selected.size == listed.size && listed.isNotEmpty()) "Select none" else "Select all", false) {
                                selected = if (selected.size == listed.size) emptySet() else listed.map { it.id }.toSet()
                            }
                            MangaChip("Select unread", false) { selected = listed.filter { !it.read }.map { it.id }.toSet() }
                            val ids = selected.toList()
                            MangaButton("Mark read", { actions.io { manga.services.repository.setRead(ids, true) }; selected = emptySet() }, primary = false, enabled = ids.isNotEmpty(), icon = Icons.Rounded.Check)
                            MangaButton("Mark unread", { actions.io { manga.services.repository.setRead(ids, false) }; selected = emptySet() }, primary = false, enabled = ids.isNotEmpty())
                            if (!series.isLocal) {
                                MangaButton("Download", { MangaDownloadActions.queue(manga, ids); selected = emptySet() }, primary = false, enabled = ids.isNotEmpty(), icon = Icons.Rounded.Download)
                                MangaButton("Delete downloads", { MangaDownloadActions.delete(manga, actions, ids); selected = emptySet() }, primary = false, enabled = ids.isNotEmpty(), icon = Icons.Rounded.Delete)
                            }
                            Text("${selected.size} selected", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, modifier = Modifier.padding(top = 9.dp))
                        }
                    }
                }
            }

            if (chapters.isEmpty() && !refreshing && problem == null) {
                item {
                    MangaEmptyState(BookIcon, "No chapters", if (series.isLocal) "Nothing readable was found in this folder." else "The source lists no chapters for this series.") {
                        MangaButton("Check again", { refresh(announce = true) }, primary = false, icon = Icons.Rounded.Refresh)
                    }
                }
            }

            items(listed, key = { it.id }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    series = series,
                    selecting = selecting,
                    selected = chapter.id in selected,
                    compact = manga.style.compactChapters,
                    onClick = {
                        if (selecting) selected = if (chapter.id in selected) selected - chapter.id else selected + chapter.id
                        else actions.read(chapter.id)
                    },
                    onMarkPrevious = {
                        val index = chapters.indexOfFirst { it.id == chapter.id }
                        actions.io { manga.services.repository.setRead(chapters.take(index + 1).map { it.id }, true) }
                    },
                )
            }
        }
    }
}

private const val REFRESH_AFTER_MS = 6 * 60 * 60 * 1000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesActions(series: Series, summary: SeriesSummary, resume: Chapter?, chapters: List<Chapter>, refreshing: Boolean, onRefresh: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    var libraryMenu by remember { mutableStateOf(false) }
    var readMenu by remember { mutableStateOf(false) }
    var downloadMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        val started = summary.readCount > 0 || (resume?.lastPage ?: 0) > 0
        val label = when {
            resume == null && summary.chapterCount > 0 && summary.readCount == summary.chapterCount -> "Read again"
            started && resume != null -> "Continue · ${resume.displayName.take(26)}" + if (resume.lastPage > 0) " · p. ${resume.lastPage + 1}" else ""
            else -> "Start Reading"
        }
        MangaButton(label, {
            if (resume != null) actions.read(resume.id) else chapters.firstOrNull()?.let { actions.read(it.id) } ?: actions.continueReading(series)
        }, icon = Icons.Rounded.PlayArrow, enabled = summary.chapterCount > 0 || !series.isLocal)

        Box {
            MangaButton(
                if (series.inLibrary) (series.libraryStatus?.label ?: "In Library") else "Add to Library",
                { if (series.inLibrary) libraryMenu = true else actions.toggleLibrary(series) },
                icon = if (series.inLibrary) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd, primary = false,
            )
            DropdownMenu(libraryMenu, { libraryMenu = false }) {
                LibraryStatus.entries.forEach { status ->
                    MangaMenuItem(status.label, checked = series.libraryStatus == status) {
                        libraryMenu = false
                        actions.io { manga.services.repository.setLibraryStatus(series.id, status) }
                    }
                }
                MangaMenuDivider()
                MangaMenuItem("Collections…") { libraryMenu = false; MangaNav.dialog = MangaDialog.Collections(listOf(series.id)) }
                MangaMenuItem("Remove from library", danger = true) { libraryMenu = false; actions.toggleLibrary(series) }
            }
        }

        MangaIconButton(
            if (series.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (series.favorite) "Remove from favourites" else "Favourite",
            { actions.toggleFavorite(series) },
            size = 40.dp, tint = if (series.favorite) manga.style.accent else tokens.colors.textPrimary, background = tokens.colors.surfaceElevated,
        )

        Box {
            MangaButton("Mark as Read", { readMenu = true }, icon = Icons.Rounded.TaskAlt, primary = false, enabled = summary.chapterCount > 0)
            DropdownMenu(readMenu, { readMenu = false }) {
                MangaMenuItem("Mark every chapter read") { readMenu = false; actions.io { manga.services.repository.setAllRead(series.id, true) } }
                MangaMenuItem("Mark every chapter unread") { readMenu = false; actions.io { manga.services.repository.setAllRead(series.id, false) } }
            }
        }

        if (!series.isLocal) {
            Box {
                MangaButton("Download", { downloadMenu = true }, icon = Icons.Rounded.Download, primary = false, enabled = summary.chapterCount > 0)
                DropdownMenu(downloadMenu, { downloadMenu = false }) {
                    val unread = chapters.filter { !it.read && it.downloadState == null }
                    val all = chapters.filter { it.downloadState == null || it.downloadState == DownloadState.FAILED }
                    MangaMenuItem("Unread chapters (${unread.size})") { downloadMenu = false; MangaDownloadActions.queue(manga, unread.map { it.id }) }
                    MangaMenuItem("Every chapter (${all.size})") { downloadMenu = false; MangaDownloadActions.queue(manga, all.map { it.id }) }
                    if (summary.downloadedCount > 0) {
                        MangaMenuDivider()
                        MangaMenuItem("Delete downloaded chapters", danger = true) {
                            downloadMenu = false
                            MangaDownloadActions.delete(manga, actions, chapters.filter { it.downloadState != null }.map { it.id })
                        }
                    }
                }
            }
            MangaButton("Track Series", { MangaNav.dialog = MangaDialog.Tracking(series.id) }, icon = Icons.Rounded.Sync, primary = false)
            MangaButton("Change Source", { MangaNav.dialog = MangaDialog.ChangeSource(series.id) }, icon = Icons.Rounded.SwapHoriz, primary = false)
        }
        MangaButton("Edit Metadata", { MangaNav.dialog = MangaDialog.EditMetadata(series.id) }, icon = Icons.Rounded.Edit, primary = false)

        Box {
            MangaIconButton(Icons.Rounded.MoreHoriz, "More", { moreMenu = true }, size = 40.dp, tint = tokens.colors.textPrimary, background = tokens.colors.surfaceElevated)
            DropdownMenu(moreMenu, { moreMenu = false }) {
                MangaMenuItem(if (series.isLocal) "Look for new files" else "Refresh from the source", Icons.Rounded.Refresh) { moreMenu = false; if (!refreshing) onRefresh() }
                MangaMenuItem("Find details online…") { moreMenu = false; MangaNav.dialog = MangaDialog.MatchMetadata(series.id) }
                MangaMenuDivider()
                MediaType.entries.forEach { type ->
                    MangaMenuItem("Shelve as ${type.label}", checked = series.mediaType == type) {
                        moreMenu = false
                        actions.io { manga.services.repository.setMediaType(series.id, type) }
                    }
                }
                MangaMenuItem("Reading settings for this series…") { moreMenu = false; MangaNav.dialog = MangaDialog.SeriesReader(series.id) }
                MangaMenuDivider()
                MangaMenuItem(if (series.isLocal) "Remove from DebridOne (files stay)" else "Forget this series", danger = true) {
                    moreMenu = false
                    MangaNav.dialog = MangaDialog.RemoveSeries(listOf(series.id))
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    series: Series,
    selecting: Boolean,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    onMarkPrevious: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    val inProgress = !chapter.read && chapter.lastPage > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (compact) 2.dp else 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) manga.style.accent.copy(alpha = 0.16f) else if (hovered) tokens.colors.surface else Color.Transparent)
            .hoverable(hover)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (compact) 7.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (selected) manga.style.accent else Color.Transparent)
                    .border(1.5.dp, if (selected) manga.style.accent else tokens.colors.borderStrong, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = manga.style.onAccent, modifier = Modifier.size(14.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                chapter.displayName,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (chapter.read) FontWeight.Normal else FontWeight.SemiBold),
                color = if (chapter.read) tokens.colors.textMuted else tokens.colors.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                chapter.groupName.takeIf { it.isNotBlank() },
                chapter.language?.takeIf { it != "MULTI" }?.let(::languageName),
                if (inProgress) "page ${chapter.lastPage + 1}" + (if (chapter.pageCount > 0) " of ${chapter.pageCount}" else "") else null,
                chapter.pageCount.takeIf { it > 0 && !inProgress }?.let { plural(it, "page") },
            )
            if (details.isNotEmpty() && !compact) Text(details.joinToString("  ·  "), style = small(), color = if (inProgress) manga.style.accent else tokens.colors.textMuted, maxLines = 1)
        }
        if (chapter.releasedAt > 0) Text(formatDate(chapter.releasedAt), style = small(), color = tokens.colors.textMuted)
        ReadMark(chapter.read, inProgress)
        if (!series.isLocal) DownloadMark(chapter)
        Box {
            MangaIconButton(Icons.Rounded.MoreHoriz, "Chapter options", { menu = true }, size = 30.dp)
            DropdownMenu(menu, { menu = false }) {
                MangaMenuItem(if (chapter.read) "Mark unread" else "Mark read") { menu = false; actions.io { manga.services.repository.setRead(listOf(chapter.id), !chapter.read) } }
                MangaMenuItem("Mark this and earlier read") { menu = false; onMarkPrevious() }
                if (!series.isLocal) {
                    MangaMenuDivider()
                    when (chapter.downloadState) {
                        null, DownloadState.FAILED -> MangaMenuItem("Download", Icons.Rounded.Download) { menu = false; MangaDownloadActions.queue(manga, listOf(chapter.id)) }
                        else -> MangaMenuItem(if (chapter.downloadState == DownloadState.DONE) "Delete download" else "Cancel download", Icons.Rounded.Delete, danger = true) {
                            menu = false
                            MangaDownloadActions.delete(manga, actions, listOf(chapter.id))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadMark(read: Boolean, inProgress: Boolean) {
    val tokens = MaterialTheme.nuvio
    val style = LocalManga.current.style
    when {
        read -> Icon(Icons.Rounded.CheckCircle, contentDescription = "Read", tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
        inProgress -> Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).border(2.dp, style.accent, RoundedCornerShape(50)))
        else -> Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(style.accent))
    }
}

@Composable
private fun DownloadMark(chapter: Chapter) {
    val tokens = MaterialTheme.nuvio
    val style = LocalManga.current.style
    val (icon: ImageVector?, description, tint) = when (chapter.downloadState) {
        DownloadState.DONE -> Triple(Icons.Rounded.DownloadDone, "Downloaded", tokens.colors.success)
        DownloadState.FAILED -> Triple(Icons.Rounded.ErrorOutline, "Download failed: ${chapter.downloadError.orEmpty()}", tokens.colors.danger)
        DownloadState.QUEUED -> Triple(Icons.Rounded.Download, "Waiting to download", tokens.colors.textMuted)
        DownloadState.RUNNING -> Triple(null, "Downloading", style.accent)
        null -> Triple(Icons.Rounded.CloudOff, "Not downloaded", Color.Transparent)
    }
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        if (icon == null) {
            val total = chapter.pageCount.coerceAtLeast(1)
            CircularProgressIndicator(progress = { (chapter.downloadPages.toFloat() / total).coerceIn(0.02f, 1f) }, color = tint, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        } else if (tint != Color.Transparent) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}
