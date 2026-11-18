package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.StatusPill
import com.debridone.manga.library.Chapter
import com.debridone.manga.library.DownloadState
import com.debridone.manga.library.Series
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Starting and removing downloads, with a line saying what happened. */
internal object MangaDownloadActions {
    fun queue(manga: MangaContext, chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) {
            MangaNav.message = "There is nothing to download."
            return
        }
        MangaBackground.run {
            manga.services.downloads.enqueue(chapterIds)
            MangaNav.message = if (chapterIds.size == 1) "Download queued" else "${chapterIds.size} chapters queued for download"
        }
    }

    fun delete(manga: MangaContext, actions: MangaActions, chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        actions.io {
            val chapters = chapterIds.mapNotNull { manga.services.repository.chapter(it) }.filter { it.downloadState != null }
            val saved = chapters.filter { it.downloadState == DownloadState.DONE }
            // What is still waiting is taken out of the queue; what is on disk is deleted there.
            manga.services.downloads.cancel(chapters.filter { it.downloadState != DownloadState.DONE }.map { it.id })
            manga.services.downloads.delete(saved.map { it.id })
            MangaNav.message = when {
                chapters.isEmpty() -> "None of these chapters is downloaded."
                saved.isEmpty() -> "${plural(chapters.size, "download")} cancelled"
                else -> "${plural(saved.size, "download")} removed"
            }
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    bytes > 0 -> "%.0f KB".format(bytes / 1024.0)
    else -> "0 MB"
}

/** Chapters saved for reading offline: what is waiting, what is being fetched, what failed and why, and what is ready. */
@Composable
fun MangaDownloads(embedded: Boolean = false) {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    val downloads by rememberMangaQuery(initial = null as List<Pair<Series, Chapter>>?) { services, profile -> services.repository.downloads(profile) }
    val revision by manga.services.repository.revision.collectAsState()
    // Measured now and then while pages arrive, and again whenever a chapter starts, finishes or is deleted.
    val states = downloads.orEmpty().map { it.second.id to it.second.downloadState }
    // Null: the chapters are on a server elsewhere, whose disk DebridOne cannot measure.
    val size by produceState(0L as Long?, revision / 20, states) { value = withContext(Dispatchers.IO) { manga.services.downloads.size() } }
    val all = downloads.orEmpty()
    val failed = all.filter { it.second.downloadState == DownloadState.FAILED }
    val waiting = all.filter { it.second.downloadState == DownloadState.QUEUED || it.second.downloadState == DownloadState.RUNNING }
    val bySeries = all.groupBy { it.first.id }.values.sortedBy { it.first().first.title.lowercase() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                if (embedded) {
                    Column(Modifier.weight(1f).padding(top = 10.dp)) {
                        Text("Manga downloads", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                        Text(summaryLine(all, size), style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                    }
                } else {
                    MangaPageHeading("Downloads", summaryLine(all, size), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    if (failed.isNotEmpty()) MangaButton("Retry failed (${failed.size})", { actions.io { manga.services.downloads.retry(failed.map { it.second.id }) } }, primary = false, icon = Icons.Rounded.Refresh)
                    if (waiting.isNotEmpty()) MangaButton("Stop all", { actions.io { manga.services.downloads.cancelAll(manga.profileId) } }, primary = false, icon = Icons.Rounded.Stop)
                }
            }
        }
        if (downloads != null && all.isEmpty()) {
            item {
                MangaEmptyState(
                    Icons.Rounded.Download, "No downloads",
                    "Chapters you download from a series' page are kept by the manga server for reading without a connection. Your own files are already offline.",
                ) { MangaButton("Library", { MangaNav.root(MangaRoute.Library) }, primary = false) }
            }
        }
        bySeries.forEach { group ->
            val series = group.first().first
            item(key = "series-${series.id}") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { actions.open(series) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SeriesCover(series, Modifier.width(40.dp).aspectRatio(COVER_ASPECT), shape = RoundedCornerShape(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(series.title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${series.sourceName} · ${plural(group.count { it.second.downloadState == DownloadState.DONE }, "chapter")} saved", style = small(), color = tokens.colors.textMuted)
                    }
                    MangaButton("Delete all", { MangaDownloadActions.delete(manga, actions, group.map { it.second.id }) }, primary = false, icon = Icons.Rounded.Delete)
                }
            }
            items(group.sortedBy { it.second.chapterSort ?: Double.MAX_VALUE }, key = { "chapter-${it.second.id}" }) { (_, chapter) -> DownloadRow(chapter) }
        }
    }
}

private fun summaryLine(all: List<Pair<Series, Chapter>>, size: Long?): String {
    val done = all.count { it.second.downloadState == DownloadState.DONE }
    val waiting = all.count { it.second.downloadState == DownloadState.QUEUED || it.second.downloadState == DownloadState.RUNNING }
    val failed = all.count { it.second.downloadState == DownloadState.FAILED }
    return listOfNotNull(
        "${plural(done, "chapter")} saved",
        if (waiting > 0) "$waiting waiting" else null,
        if (failed > 0) "$failed failed" else null,
        if (size == null) "kept on your manga server" else formatBytes(size),
    ).joinToString("  ·  ")
}

@Composable
private fun DownloadRow(chapter: Chapter) {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    Row(
        Modifier.fillMaxWidth().padding(start = 58.dp).clip(RoundedCornerShape(10.dp)).background(tokens.colors.surface)
            .clickable(enabled = chapter.downloadState == DownloadState.DONE, role = Role.Button) { actions.read(chapter.id) }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(chapter.displayName, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (chapter.downloadState) {
                DownloadState.RUNNING -> {
                    val total = chapter.pageCount.coerceAtLeast(1)
                    Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(50)).background(tokens.colors.borderDefault)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((chapter.downloadPages.toFloat() / total).coerceIn(0f, 1f)).background(manga.style.accent))
                    }
                    Text("Page ${chapter.downloadPages} of ${if (chapter.pageCount > 0) chapter.pageCount.toString() else "…"}", style = small(), color = tokens.colors.textMuted)
                }
                DownloadState.FAILED -> Text(chapter.downloadError ?: "The download failed.", style = small(), color = tokens.colors.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                DownloadState.DONE -> Text("${plural(chapter.downloadPages, "page")} · click to read offline", style = small(), color = tokens.colors.textMuted)
                else -> Unit
            }
        }
        when (chapter.downloadState) {
            DownloadState.DONE -> StatusPill("Saved", PillTone.SUCCESS)
            DownloadState.RUNNING -> StatusPill("Downloading", PillTone.ACCENT, manga.style.accent)
            DownloadState.QUEUED -> StatusPill("Waiting")
            DownloadState.FAILED -> StatusPill("Failed", PillTone.DANGER)
            null -> Unit
        }
        if (chapter.downloadState == DownloadState.FAILED) {
            MangaIconButton(Icons.Rounded.Refresh, "Retry", { actions.io { manga.services.downloads.retry(listOf(chapter.id)) } })
        }
        MangaIconButton(
            Icons.Rounded.Delete, if (chapter.downloadState == DownloadState.DONE) "Delete this download" else "Cancel this download",
            { MangaDownloadActions.delete(manga, actions, listOf(chapter.id)) },
        )
    }
}
