package com.debridone.app.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.DebridOneRuntime
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.SettingsPage
import com.debridone.app.shell.ShellSection
import com.debridone.app.shell.ShellState
import com.debridone.app.shell.StatusPill
import com.debridone.cloud.CloudFileRow
import com.debridone.cloud.CloudItemRow
import com.debridone.cloud.CloudServices
import com.debridone.cloud.MediaClass
import com.nuvio.app.AppShellHost
import com.nuvio.app.ShellLibrarySource
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * "Connected Accounts" inside Streaming's Library: the video files of the profile's accounts, to
 * be played in Streaming's own player. Streaming draws the chip and lends its list; the rows are
 * drawn here, so Streaming never learns of an account, a file listing or an address. Nothing here
 * touches Streaming's own library, add-ons or debrid settings.
 */
internal class StreamingCloudLibrary(private val cloud: CloudServices) {
    private class Entry(val item: CloudItemRow, val files: List<CloudFileRow>)

    private var entries by mutableStateOf<List<Entry>?>(null)
    private var hasAccounts by mutableStateOf(true)
    private var openEntry by mutableStateOf<Long?>(null)
    private var query by mutableStateOf("")

    fun install() {
        AppShellHost.librarySource = ShellLibrarySource("Connected Accounts") { rows() }
    }

    private fun LazyListScope.rows() {
        item(key = "debridone-cloud-header") { Header() }
        val list = entries
        when {
            !hasAccounts -> item(key = "debridone-cloud-none") {
                EmptyState(
                    "No account connected for this profile",
                    "Connect Real-Debrid or TorBox in Settings to play the videos that are already in your account.",
                ) { CloudButton("Open Connected Accounts", { ShellState.showInPlace(SettingsPage.CONNECTED_ACCOUNTS) }, primary = true) }
            }
            list == null -> Unit
            list.isEmpty() -> item(key = "debridone-cloud-empty") {
                EmptyState(
                    if (query.isBlank()) "No video files" else "Nothing matches “$query”",
                    if (query.isBlank()) "There is no finished video file in your connected accounts. Refresh after something new arrives there." else "Try fewer words.",
                )
            }
            else -> list.forEach { entry ->
                item(key = "debridone-cloud-entry-${entry.item.id}") { EntryRow(entry) }
                if (openEntry == entry.item.id && entry.files.size > 1) {
                    items(entry.files, key = { "debridone-cloud-file-${it.id}" }) { FileRow(it) }
                }
            }
        }
        item(key = "debridone-cloud-foot") { Spacer(Modifier.height(32.dp)) }
    }

    @Composable
    private fun Header() {
        val tokens = MaterialTheme.nuvio
        val profileId by DebridOneRuntime.activeProfileId.collectAsStateWithLifecycle()
        val revision by cloud.files.revision.collectAsStateWithLifecycle()
        val accounts by cloud.accounts.accounts.collectAsStateWithLifecycle()
        val refreshing by cloud.files.refreshing.collectAsStateWithLifecycle()
        val visible = accounts.filter { it.visibleTo(profileId) }

        // What is listed comes from the same cache the Downloads section shows; it is read again when that changes.
        LaunchedEffect(profileId, revision, visible.map { it.id }, query) {
            hasAccounts = visible.isNotEmpty()
            entries = withContext(Dispatchers.IO) { load(profileId, query) }
        }
        // A listing older than ten minutes is read again, quietly.
        LaunchedEffect(profileId, visible.map { it.id }) {
            val now = System.currentTimeMillis()
            visible.filter { (it.lastListedAt ?: 0) < now - 10 * 60_000 && it.id !in refreshing }.forEach { CloudApp.refresh(profileId, it, fresh = false, announce = false) }
        }

        val message by CloudApp.message.collectAsStateWithLifecycle()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // What went wrong with a file (its account wants a new sign-in, the provider is busy) is said here, where it was asked.
            message?.let { text ->
                LaunchedEffect(text) {
                    delay(7_000)
                    if (CloudApp.message.value == text) CloudApp.say(null)
                }
                Text(
                    text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(tokens.colors.surfaceElevated).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Text(
                "Video files in your connected Real-Debrid and TorBox accounts. They play in this player and resume from Continue Watching; your add-ons are not involved.",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
            if (visible.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CloudTextField(query, { query = it; openEntry = null }, label = null, modifier = Modifier.width(320.dp), placeholder = "Search your videos")
                    Text(
                        entries?.let { list -> "${plural(list.size, "entry").replace("entrys", "entries")}  ·  ${plural(list.sumOf { it.files.size }, "video")}" }.orEmpty(),
                        style = small(), color = tokens.colors.textMuted,
                    )
                    Spacer(Modifier.weight(1f))
                    CloudButton(
                        if (visible.any { it.id in refreshing }) "Reading…" else "Refresh",
                        { visible.forEach { CloudApp.refresh(profileId, it, fresh = true, announce = false) } },
                        icon = Icons.Rounded.Refresh, enabled = visible.none { it.id in refreshing },
                    )
                    CloudButton("All files", {
                        DownloadsUi.showShelf(MediaClass.MOVIE)
                        ShellState.select(ShellSection.DOWNLOADS)
                    }, icon = Icons.Rounded.Folder)
                }
            }
        }
    }

    private fun load(profileId: Long?, query: String): List<Entry> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return cloud.files.filesOf(profileId, setOf(MediaClass.MOVIE, MediaClass.TV_SHOW))
            .groupBy { it.itemId }
            .mapNotNull { (itemId, files) -> cloud.files.item(profileId, itemId)?.let { Entry(it, files.sortedBy { file -> file.path.lowercase() }) } }
            .filter { entry -> words.all { word -> entry.item.name.lowercase().contains(word) || entry.files.any { it.path.lowercase().contains(word) } } }
            .sortedBy { it.item.name.lowercase() }
    }

    @Composable
    private fun EntryRow(entry: Entry) {
        val tokens = MaterialTheme.nuvio
        val single = entry.files.singleOrNull()
        val open = openEntry == entry.item.id
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface)
                .clickable(role = Role.Button, onClickLabel = if (single != null) "Play ${entry.item.name}" else "Show the videos of ${entry.item.name}") {
                    if (single != null) play(single) else openEntry = if (open) null else entry.item.id
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProviderMark(entry.item.provider, 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.item.name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        entry.item.provider.displayName, entry.item.kind.label,
                        if (single == null) plural(entry.files.size, "video") else null,
                        formatBytes(entry.files.sumOf { it.sizeBytes }).takeIf { entry.files.any { file -> file.sizeBytes > 0 } },
                    ).joinToString("  ·  "),
                    style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (single != null) Icons.Rounded.PlayArrow else if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null, tint = if (single != null) tokens.colors.accent else tokens.colors.textMuted, modifier = Modifier.size(24.dp),
            )
        }
    }

    @Composable
    private fun FileRow(file: CloudFileRow) {
        val tokens = MaterialTheme.nuvio
        val downloads by cloud.downloads.downloads.collectAsStateWithLifecycle()
        val saved = downloads.any { it.fileExists && it.accountId == file.accountId && it.kind == file.kind && it.itemRemoteId == file.itemRemoteId && it.fileRef == file.ref }
        Row(
            Modifier.fillMaxWidth().padding(start = 64.dp, end = 16.dp, top = 1.dp, bottom = 1.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = "Play ${file.name}") { play(file) }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(file.folder.takeIf { it.isNotEmpty() }, formatBytes(file.sizeBytes).takeIf { file.sizeBytes > 0 }).joinToString("  ·  "),
                    style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (saved) StatusPill("On this computer", PillTone.SUCCESS)
        }
    }

    private fun play(file: CloudFileRow) {
        val profileId = DebridOneRuntime.activeProfileId.value ?: return CloudApp.say("Choose a profile first.")
        CloudBridges.video?.play(profileId, file, cloud.downloads.localCopy(profileId, file))
    }
}
