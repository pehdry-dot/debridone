package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.StatusPill
import com.debridone.manga.migration.MigrationEntry
import com.debridone.manga.migration.MigrationOutcome
import com.debridone.manga.migration.MigrationReport
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What became of the active profile's earlier library; null while there is none (or it is not known yet). */
@Composable
internal fun rememberMigrationReport(): State<MigrationReport?> {
    val manga = LocalManga.current
    val migrated by manga.services.migration.revision.collectAsState()
    val library by manga.services.repository.revision.collectAsState()
    val generation by manga.services.server.generation.collectAsState()
    // The library changes often while reading; the report only cares that it changed at all, now and then.
    return produceState<MigrationReport?>(null, manga.profileId, migrated, library / 20, generation) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.migration.report(manga.profileId) }.getOrNull()?.takeIf { it.entries.isNotEmpty() } }
    }
}

private fun summary(report: MigrationReport): String = listOfNotNull(
    report.carriedOver.takeIf { it > 0 }?.let { "$it carried over" },
    report.waiting.takeIf { it > 0 }?.let { "$it to add again from a new source" },
    report.missing.takeIf { it > 0 }?.let { "$it whose files were not found" },
    report.failed.takeIf { it > 0 }?.let { "$it that could not be carried over" },
).joinToString("  ·  ")

/**
 * Says, where the reader will see it, that the library of the earlier version of Manga was carried
 * over and what still needs them. Shown while something is open, or until it is put away.
 */
@Composable
internal fun MigrationCard(modifier: Modifier = Modifier, always: Boolean = false) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val report by rememberMigrationReport()
    val problem by manga.module.migrationProblem.collectAsState()
    val revision by manga.services.migration.revision.collectAsState()
    val dismissed = remember(manga.profileId, revision) { manga.services.migration.isDismissed(manga.profileId) }
    val current = report
    if (current == null && problem == null) return
    if (!always && problem == null && current != null && !current.needsAttention && dismissed) return

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(16.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Your earlier Manga library", style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
        problem?.let { Notice(it, PillTone.DANGER) }
        if (current != null) {
            Text(
                "Manga now reads through Suwayomi-Server. What you had before was copied to a backup folder and then carried over: " + summary(current) + ".",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MangaButton("See what happened", { MangaNav.dialog = MangaDialog.Migration }, primary = current.needsAttention)
                if (!always && !current.needsAttention) MangaButton("Hide this", { manga.services.migration.dismiss(manga.profileId) }, primary = false)
            }
        }
    }
}

/** The migration's report: every series of the earlier library, what became of it, and what can still be done. */
@Composable
internal fun MigrationDialog(onDismiss: () -> Unit) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val report by rememberMigrationReport()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun run(done: String? = null, action: () -> Unit) {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching(action) }
            busy = false
            outcome.onSuccess { if (done != null) MangaNav.message = done }.onFailure { problem = it.message ?: "That did not work." }
        }
    }

    MangaDialogFrame("Your earlier Manga library", onDismiss, width = 780) {
        val current = report
        if (current == null) {
            Text("This profile has no library from the earlier version of Manga.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
            MangaDialogButtons("Close", onDismiss, cancel = "Cancel") { onDismiss() }
            return@MangaDialogFrame
        }
        Text(summary(current), style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // What needs the reader comes first.
            current.entries.sortedBy { if (it.outcome == MigrationOutcome.CARRIED_OVER) 1 else 0 }.forEach { entry ->
                MigrationRow(entry, busy, onDismiss) { done, action -> run(done, action) }
            }
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
        current.notes.forEach { Text(it, style = small(), color = tokens.colors.textMuted) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (busy) CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            current.backupFolder?.let { folder -> MangaButton("Show the backup folder", { revealInFileManager(folder) }, primary = false, icon = Icons.Rounded.FolderOpen) }
            MangaButton("Close", onDismiss)
        }
    }
}

@Composable
private fun MigrationRow(entry: MigrationEntry, busy: Boolean, onDismiss: () -> Unit, run: (done: String?, action: () -> Unit) -> Unit) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tokens.colors.surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                when (entry.outcome) {
                    MigrationOutcome.CARRIED_OVER -> StatusPill("Carried over", PillTone.SUCCESS)
                    MigrationOutcome.WAITING -> StatusPill("To add again", PillTone.WARNING)
                    MigrationOutcome.FILES_MISSING -> StatusPill("Files not found", PillTone.WARNING)
                    MigrationOutcome.FAILED -> StatusPill("Not carried over", PillTone.DANGER)
                }
            }
            Text(
                listOfNotNull(
                    entry.source, entry.libraryStatus?.label ?: "In your library".takeIf { entry.inLibrary }, "Favourite".takeIf { entry.favorite },
                    "${entry.chaptersRead} of ${entry.chapters} chapters read".takeIf { entry.chapters > 0 }, entry.lastChapterRead?.let { "last read: $it" },
                ).joinToString("  ·  "),
                style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (entry.outcome == MigrationOutcome.WAITING && entry.candidateSeriesId != null) "A series of the same title is in your library now: carry over what was known about this one to it."
                else entry.detail,
                style = small(), color = tokens.colors.textSecondary,
            )
        }
        val profile = manga.profileId
        val newId = entry.newSeriesId
        val candidate = entry.candidateSeriesId
        when {
            entry.outcome == MigrationOutcome.CARRIED_OVER && newId != null ->
                MangaButton("Open", { onDismiss(); MangaNav.open(MangaRoute.SeriesPage(newId)) }, primary = false)
            entry.outcome == MigrationOutcome.WAITING && candidate != null ->
                MangaButton("Carry over", {
                    run("What was known about \"${entry.title}\" was carried over.") { manga.services.migration.carryOver(profile, entry.oldSeriesId, candidate) }
                }, enabled = !busy)
            entry.outcome == MigrationOutcome.WAITING ->
                MangaButton("Find it", {
                    onDismiss()
                    SearchState.reset()
                    SearchState.text = entry.title
                    MangaNav.root(MangaRoute.Search)
                    MangaNav.message = "Press Return to search your sources for \"${entry.title}\". Add it to your library, then carry its progress over from the report."
                }, primary = false, icon = Icons.Rounded.Search)
            else -> MangaButton("Look again", { run(null) { manga.services.migration.run(profile) } }, primary = false, enabled = !busy)
        }
    }
}
