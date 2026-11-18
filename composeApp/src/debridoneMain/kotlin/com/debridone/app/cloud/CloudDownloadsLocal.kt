package com.debridone.app.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.DebridOneRuntime
import com.debridone.app.manga.MangaDownloads
import com.debridone.app.manga.MangaProvider
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.SectionTitle
import com.debridone.app.shell.SettingsPage
import com.debridone.app.shell.ShellCard
import com.debridone.app.shell.ShellPage
import com.debridone.app.shell.ShellSection
import com.debridone.app.shell.ShellState
import com.debridone.app.shell.StatusPill
import com.debridone.app.streaming.StreamingDownloadsTab
import com.debridone.cloud.CloudFileRow
import com.debridone.cloud.CloudServices
import com.debridone.cloud.ConflictPolicy
import com.debridone.cloud.Download
import com.debridone.cloud.DownloadState
import com.debridone.cloud.MediaClass
import com.debridone.cloud.MediaFileClassifier
import com.nuvio.app.core.ui.nuvio
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser

/** One thing that can be done with a file: a button, or a line of its menu. */
internal class CloudAction(val label: String, val icon: ImageVector, val primary: Boolean = false, val danger: Boolean = false, val run: () -> Unit)

private val isMac: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
internal val revealLabel: String = if (isMac) "Reveal in Finder" else "Show in its folder"

/** Asks the user for a folder with the system's own dialog. */
internal fun chooseFolder(title: String = "Choose a folder"): Path? {
    if (isMac) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
            dialog.isVisible = true
            val directory = dialog.directory ?: return null
            val file = dialog.file ?: return null
            return Paths.get(directory, file)
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath() else null
}

/** What can be done with a file of the cloud, given whether a copy of it is on this computer. */
internal fun fileActions(cloud: CloudServices, profileId: Long?, file: CloudFileRow, local: Download?): List<CloudAction> {
    val actions = ArrayList<CloudAction>()
    val copy = local?.takeIf { it.fileExists }
    val video = CloudBridges.video
    val music = CloudBridges.music
    val manga = CloudBridges.manga
    if (profileId != null) {
        when {
            file.mediaClass.isVideo && video != null -> actions += CloudAction("Play", Icons.Rounded.PlayArrow, primary = true) { video.play(profileId, file, copy) }
            file.mediaClass == MediaClass.MUSIC && music != null -> actions += CloudAction("Play", Icons.Rounded.PlayArrow, primary = true) { music.play(profileId, listOf(file)) }
            manga != null && (file.mediaClass == MediaClass.COMIC || manga.canRead(file.name)) -> {
                // The reader needs the whole archive on this computer: said in the label, and again before it starts.
                val label = if (copy != null) "Read" else "Download and read"
                actions += CloudAction(label, Icons.Rounded.MenuBook, primary = file.mediaClass == MediaClass.COMIC) { CloudAutomation.read(cloud, profileId, file, copy) }
            }
        }
    }
    if (local == null) {
        actions += CloudAction("Download", Icons.Rounded.Download, primary = actions.none { it.primary }) { CloudApp.background { enqueue(cloud, profileId, listOf(file.id)) } }
        actions += CloudAction("Download to…", Icons.Rounded.FolderOpen) { chooseFolder("Download ${file.name} to")?.let { folder -> CloudApp.background { enqueue(cloud, profileId, listOf(file.id), folder) } } }
    }
    if (profileId != null && file.mediaClass == MediaClass.MUSIC && music != null) {
        actions += CloudAction("Add to Music library", Icons.Rounded.LibraryAdd) { CloudApp.background { CloudApp.say(music.addCloudFilesToLibrary(profileId, listOf(file))) } }
    }
    if (copy != null) actions += localActions(cloud, profileId, copy, includeOpen = false)
    actions += CloudAction("Copy file name", Icons.Rounded.ContentCopy) { copyToClipboard(file.name); CloudApp.say("Copied.") }
    actions += CloudAction("View details", Icons.Rounded.Info) { DownloadsUi.dialog = DownloadsDialog.FileDetails(file, local) }
    if (copy != null) actions += CloudAction("Delete local copy…", Icons.Rounded.Delete, danger = true) { DownloadsUi.dialog = DownloadsDialog.DeleteLocal(copy) }
    return actions
}

/** What can be done with a finished download on this computer (opening, its library, the Finder). */
private fun localActions(cloud: CloudServices, profileId: Long?, download: Download, includeOpen: Boolean): List<CloudAction> {
    val actions = ArrayList<CloudAction>()
    val path = Paths.get(download.destination)
    val importer = CloudBridges.importer(download.mediaClass, download.fileName)
    if (includeOpen && profileId != null) {
        val video = CloudBridges.video
        val music = CloudBridges.music
        val manga = CloudBridges.manga
        when {
            download.mediaClass.isVideo && video != null -> actions += CloudAction("Play", Icons.Rounded.PlayArrow, primary = true) { video.playLocal(profileId, download) }
            download.mediaClass == MediaClass.MUSIC && music != null -> actions += CloudAction("Play", Icons.Rounded.PlayArrow, primary = true) { music.playLocal(profileId, download) }
            manga != null && (download.mediaClass == MediaClass.COMIC || manga.canRead(download.fileName)) ->
                actions += CloudAction("Read", Icons.Rounded.MenuBook, primary = true) { CloudApp.background { manga.read(profileId, download)?.let(CloudApp::say) } }
            // Anything that could run is shown in the Finder, never opened.
            !MediaFileClassifier.isExecutable(download.fileName) -> actions += CloudAction("Open", Icons.Rounded.OpenInNew, primary = true) { openWithDefaultApp(path)?.let(CloudApp::say) }
        }
    }
    if (profileId != null && importer != null) {
        if (download.importedInto == null) {
            actions += CloudAction("Add to ${importer.sectionName} library", Icons.Rounded.LibraryAdd) { CloudApp.background { CloudApp.say(importer.addToLibrary(profileId, download)) } }
        } else {
            actions += CloudAction("Remove from ${importer.sectionName} library", Icons.Rounded.Close) {
                CloudApp.background {
                    importer.removeFromLibrary(profileId, download)
                    cloud.downloads.markImported(download.id, null, null)
                    CloudApp.say("${download.fileName} is no longer in the ${importer.sectionName} library. The file is still on this computer and in your cloud.")
                }
            }
        }
    }
    actions += CloudAction(revealLabel, Icons.Rounded.FolderOpen, primary = includeOpen && actions.none { it.primary }) { revealInFileManager(path)?.let(CloudApp::say) }
    return actions
}

/** What can be done with a download record, whatever its state. */
internal fun downloadActions(cloud: CloudServices, profileId: Long?, download: Download): List<CloudAction> {
    val actions = ArrayList<CloudAction>()
    when {
        download.fileExists -> actions += localActions(cloud, profileId, download, includeOpen = true)
        download.state == DownloadState.RUNNING || download.state == DownloadState.QUEUED -> {
            actions += CloudAction("Pause", Icons.Rounded.Pause, primary = true) { CloudApp.background { cloud.downloads.pause(download.id) } }
            actions += CloudAction("Cancel", Icons.Rounded.Close) { CloudApp.background { cloud.downloads.cancel(download.id) } }
        }
        download.state == DownloadState.PAUSED -> {
            actions += CloudAction("Resume", Icons.Rounded.PlayArrow, primary = true) { CloudApp.background { cloud.downloads.resume(download.id) } }
            actions += CloudAction("Cancel", Icons.Rounded.Close) { CloudApp.background { cloud.downloads.cancel(download.id) } }
        }
        download.state == DownloadState.FAILED || download.state == DownloadState.CANCELLED ->
            actions += CloudAction("Try again", Icons.Rounded.Replay, primary = true) { CloudApp.background { cloud.downloads.retry(download.id) } }
    }
    actions += CloudAction("Copy file name", Icons.Rounded.ContentCopy) { copyToClipboard(download.fileName); CloudApp.say("Copied.") }
    actions += CloudAction("View details", Icons.Rounded.Info) { DownloadsUi.dialog = DownloadsDialog.DownloadDetails(download) }
    if (download.fileExists) actions += CloudAction("Delete local copy…", Icons.Rounded.Delete, danger = true) { DownloadsUi.dialog = DownloadsDialog.DeleteLocal(download) }
    if (!download.active) actions += CloudAction("Remove from history", Icons.Rounded.Close) { CloudApp.background { cloud.downloads.removeRecord(download.id) } }
    return actions
}

// ---------------------------------------------------------------- transfers

/** The transfers in progress, waiting, paused or failed: shown above the lists while there are any. */
@Composable
internal fun QueueStrip(cloud: CloudServices, profileId: Long?, downloads: List<Download>) {
    val tokens = MaterialTheme.nuvio
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tokens.colors.surfaceElevated).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val running = downloads.count { it.state == DownloadState.RUNNING }
        Text(
            "Transfers" + (if (running > 0) "  ·  $running running" else "") + (downloads.count { it.state == DownloadState.QUEUED }.takeIf { it > 0 }?.let { "  ·  $it waiting" } ?: ""),
            style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary,
        )
        downloads.sortedBy { it.createdAt }.forEach { download -> DownloadRow(cloud, profileId, download, embedded = true) }
    }
}

private fun progressLine(download: Download): String = when (download.state) {
    DownloadState.RUNNING -> listOfNotNull(
        if (download.totalBytes > 0) "${formatBytes(download.receivedBytes)} of ${formatBytes(download.totalBytes)}" else formatBytes(download.receivedBytes),
        download.bytesPerSecond.takeIf { it > 0 }?.let { "${formatBytes(it)}/s" },
        download.secondsLeft?.let(::formatTimeLeft),
        download.note,
    ).joinToString("  ·  ")
    DownloadState.QUEUED -> "Waiting for its turn"
    DownloadState.PAUSED -> "Paused" + (if (download.receivedBytes > 0) " at ${formatBytes(download.receivedBytes)} of ${formatBytes(download.totalBytes)}" else "")
    DownloadState.FAILED -> download.error ?: "Failed"
    DownloadState.CANCELLED -> "Cancelled"
    DownloadState.DONE -> when {
        download.fileExists -> download.destination
        download.fileDeletedAt != null -> "Local copy deleted ${formatDate(download.fileDeletedAt)}"
        else -> "The file is no longer at ${download.destination}"
    }
}

/** One download: in the transfers strip while it is under way, in the history afterwards. */
@Composable
internal fun DownloadRow(cloud: CloudServices, profileId: Long?, download: Download, embedded: Boolean = false) {
    val tokens = MaterialTheme.nuvio
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (embedded) tokens.colors.surface else tokens.colors.surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(iconOf(download.mediaClass), contentDescription = download.mediaClass.label, tint = tokens.colors.textSecondary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(download.fileName, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(download.provider.displayName, formatBytes(download.totalBytes).takeIf { download.totalBytes > 0 }, download.mediaClass.label, "added ${formatDate(download.createdAt)}").joinToString("  ·  "),
                style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (download.state == DownloadState.RUNNING || download.state == DownloadState.PAUSED) {
                val fraction = download.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, color = tokens.colors.accent, trackColor = tokens.colors.borderSubtle, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)))
                } else {
                    LinearProgressIndicator(color = tokens.colors.accent, trackColor = tokens.colors.borderSubtle, modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)))
                }
            }
            Text(progressLine(download), style = small(), color = if (download.state == DownloadState.FAILED) tokens.colors.danger else tokens.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            download.fileExists -> StatusPill(if (download.importedInto != null) "In ${download.importedInto!!.replaceFirstChar { it.uppercase() }}" else "On this computer", PillTone.SUCCESS)
            download.state == DownloadState.DONE -> StatusPill(if (download.fileDeletedAt != null) "Deleted" else "File missing", PillTone.WARNING)
            download.state == DownloadState.RUNNING -> StatusPill(download.fraction?.let { "${(it * 100).toInt()}%" } ?: "Running", PillTone.ACCENT)
            download.state == DownloadState.FAILED -> StatusPill("Failed", PillTone.DANGER)
            else -> StatusPill(download.state.name.lowercase().replaceFirstChar { it.uppercase() })
        }
        val actions = downloadActions(cloud, profileId, download)
        actions.firstOrNull { it.primary }?.let { action -> CloudButton(action.label, action.run, icon = action.icon, primary = download.fileExists) }
        ActionMenu("More about ${download.fileName}", actions)
    }
}

// ---------------------------------------------------------------- what is on this computer

internal enum class LocalSection(val label: String) {
    CLOUD("From your cloud"),
    STREAMING("Streaming downloads"),
    MANGA("Manga chapters"),
    MUSIC("Music offline"),
}

/**
 * The Local tab: files DebridOne fetched from the connected accounts, with where they are, and
 * beside them what each section downloaded on its own account (Streaming's downloads, Manga's
 * chapters, Music's offline songs), each shown by the section that owns it.
 */
@Composable
internal fun LocalTab(cloud: CloudServices, profileId: Long?, downloads: List<Download>) {
    val tokens = MaterialTheme.nuvio
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        LocalSection.entries.forEach { section -> CloudChip(section.label, DownloadsUi.localSection == section) { DownloadsUi.localSection = section } }
        Box(Modifier.weight(1f))
        if (DownloadsUi.localSection == LocalSection.CLOUD) {
            CloudButton("Download settings", { ShellState.showInPlace(SettingsPage.DOWNLOADS) }, icon = Icons.Rounded.Settings)
            if (profileId != null && downloads.any { !it.active }) CloudButton("Clear history", { CloudApp.background { cloud.downloads.clearHistory(profileId); CloudApp.say("The history is cleared. No file was deleted.") } })
        }
    }
    Box(Modifier.fillMaxSize().padding(top = 12.dp)) {
        when (DownloadsUi.localSection) {
            LocalSection.CLOUD -> {
                if (downloads.isEmpty()) {
                    EmptyState("Nothing has been downloaded from your cloud yet.", "Files you download from Real-Debrid or TorBox are listed here, with where they are on this computer.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 110.dp)) {
                        item("where") {
                            Text(
                                "Downloads go to ${profileId?.let { cloud.downloads.settings.defaultFolder(it) } ?: "your Downloads folder"} unless you chose another folder. Deleting a local copy never touches your cloud.",
                                style = small(), color = tokens.colors.textMuted,
                            )
                        }
                        items(downloads, key = { it.id }) { download -> DownloadRow(cloud, profileId, download) }
                    }
                }
            }
            // Playback belongs to Streaming, so opening one of its downloads continues there.
            LocalSection.STREAMING -> StreamingDownloadsTab(onOpenInStreaming = { ShellState.select(ShellSection.STREAMING) })
            LocalSection.MANGA -> MangaProvider { MangaDownloads(embedded = true) }
            LocalSection.MUSIC -> EmptyState(
                "Songs saved for offline listening are in Music.",
                "Music keeps them under Library › Available offline, with their albums and playlists.",
            ) { CloudButton("Open Music", { ShellState.select(ShellSection.MUSIC) }, primary = true) }
        }
    }
}

// ---------------------------------------------------------------- details and confirmations

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Facts(facts: List<Pair<String, String?>>) {
    val tokens = MaterialTheme.nuvio
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            facts.filter { !it.second.isNullOrBlank() }.forEach { (label, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = small(), color = tokens.colors.textMuted, modifier = Modifier.padding(top = 2.dp).fillMaxWidth(0.26f))
                    Text(value!!, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary)
                }
            }
        }
    }
}

@Composable
internal fun DownloadsDialogs(cloud: CloudServices, profileId: Long?) {
    val close = { DownloadsUi.dialog = null }
    when (val dialog = DownloadsUi.dialog) {
        null -> Unit
        is DownloadsDialog.ItemDetails -> CloudDialog(dialog.item.name, close, width = 600) {
            val item = dialog.item
            Facts(
                listOf(
                    "Provider" to item.provider.displayName,
                    "Kind" to item.kind.label,
                    "Status" to item.status.label + (item.rawStatus?.let { " (${item.provider.displayName} says: $it)" } ?: ""),
                    "Progress" to item.progress?.let { "${(it * 100).toInt()}%" },
                    "Size" to formatBytes(item.sizeBytes).takeIf { item.sizeBytes > 0 },
                    "Files" to when (item.filesState) {
                        "packed" -> "Kept by ${item.provider.displayName} as an archive; its files cannot be listed one by one."
                        "none" -> "Not listed yet"
                        else -> plural(item.fileCount, "file")
                    },
                    "Shelves" to item.classes.sortedBy { it.ordinal }.joinToString(", ") { it.label }.takeIf { it.isNotEmpty() },
                    "Added" to item.createdAt?.let(::formatDateTime),
                    "Completed" to item.completedAt?.let(::formatDateTime),
                    "Kept until" to item.expiresAt?.let(::formatDateTime),
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { CloudButton("Close", close) }
        }
        is DownloadsDialog.FileDetails -> CloudDialog(dialog.file.name, close, width = 600) {
            val file = dialog.file
            val local = dialog.local
            Facts(
                listOf(
                    "Provider" to file.provider.displayName,
                    "In" to "${file.itemName} (${file.kind.label.lowercase()})",
                    "Path" to file.path,
                    "Size" to formatBytes(file.sizeBytes).takeIf { file.sizeBytes > 0 },
                    "Type" to listOfNotNull(MediaFileClassifier.extension(file.path).takeIf { it.isNotEmpty() }?.let { ".$it" }, file.mimeType).joinToString("  ·  ").takeIf { it.isNotEmpty() },
                    "Shelf" to file.mediaClass.label,
                    "Checksum (MD5)" to file.md5,
                    "Added" to file.addedAt?.let(::formatDateTime),
                    "On this computer" to when {
                        local?.fileExists == true -> local.destination
                        local?.active == true -> "Being downloaded to ${local.destination}"
                        else -> "No"
                    },
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { CloudButton("Close", close) }
        }
        is DownloadsDialog.DownloadDetails -> CloudDialog(dialog.download.fileName, close, width = 600) {
            val download = dialog.download
            Facts(
                listOf(
                    "Provider" to download.provider.displayName,
                    "From" to "${download.itemName} (${download.kind.label.lowercase()})",
                    "Path in the cloud" to download.filePath,
                    "Status" to download.state.name.lowercase().replaceFirstChar { it.uppercase() } + (download.error?.let { ": $it" } ?: ""),
                    "Size" to formatBytes(download.totalBytes).takeIf { download.totalBytes > 0 },
                    "Shelf" to download.mediaClass.label,
                    "Location" to download.destination,
                    "File" to when {
                        download.fileExists -> "On this computer"
                        download.fileDeletedAt != null -> "Deleted through DebridOne on ${formatDate(download.fileDeletedAt)}"
                        download.state == DownloadState.DONE -> "No longer at that location"
                        else -> null
                    },
                    "Checksum" to when (download.verified) {
                        true -> "Matched the one ${download.provider.displayName} gave"
                        false -> "Did not match"
                        null -> if (download.state == DownloadState.DONE) "${download.provider.displayName} gave none for this file" else null
                    },
                    "In a library" to download.importedInto?.replaceFirstChar { it.uppercase() },
                    "Queued" to formatDateTime(download.createdAt),
                    "Finished" to download.finishedAt?.let(::formatDateTime),
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { CloudButton("Close", close) }
        }
        is DownloadsDialog.DeleteFromCloud -> {
            val item = dialog.item
            val name = item.provider.displayName
            CloudConfirmDialog(
                title = "Delete from $name?",
                message = "\"${item.name}\" will be removed from your $name account, permanently. This cannot be undone from DebridOne.\n\n" +
                    "Copies you already downloaded stay on this computer, and so does what you imported from them into Music or Manga. " +
                    "Songs from it that you added to your Music library without downloading them go with it: there would be nothing left to play them from.",
                confirm = "Delete from $name", danger = true, onDismiss = close,
            ) {
                CloudApp.background {
                    cloud.files.deleteFromCloud(profileId, item.id)
                    if (DownloadsUi.openItemId == item.id) DownloadsUi.openItemId = null
                    CloudApp.say("\"${item.name}\" was deleted from $name. Local copies were not touched.")
                }
            }
        }
        is DownloadsDialog.DownloadToRead -> {
            val file = dialog.file
            CloudConfirmDialog(
                title = "Download to read?",
                message = "The Manga reader opens archives from this computer, so \"${file.name}\" (${formatBytes(file.sizeBytes)}) is downloaded completely first" +
                    (profileId?.let { " to ${cloud.downloads.settings.folder(it, MediaClass.COMIC)}" } ?: "") +
                    ". It opens in Manga by itself when the download is complete, and is added to your Manga library.",
                confirm = "Download and read", onDismiss = close,
            ) { if (profileId != null) CloudApp.background { CloudAutomation.downloadAndRead(cloud, profileId, file) } }
        }
        is DownloadsDialog.DeleteLocal -> {
            val download = dialog.download
            CloudConfirmDialog(
                title = "Delete the local copy?",
                message = "${download.fileName} will be deleted from this computer (${download.destination})." +
                    (download.importedInto?.let { " It is first taken out of the ${it.replaceFirstChar { c -> c.uppercase() }} library." } ?: "") +
                    "\n\nThe file in your ${download.provider.displayName} account is not touched; you can download it again.",
                confirm = "Delete local copy", danger = true, onDismiss = close,
            ) {
                CloudApp.background {
                    if (profileId != null && download.importedInto != null) runCatching { CloudBridges.importer(download.mediaClass, download.fileName)?.removeFromLibrary(profileId, download) }
                    cloud.downloads.deleteLocalCopy(download.id)
                    if (profileId != null && download.mediaClass == MediaClass.MUSIC) runCatching { CloudBridges.music?.localCopyDeleted(profileId, download) }
                    CloudApp.say("The local copy of ${download.fileName} was deleted. Your cloud was not touched.")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Settings › Downloads

/** The download preferences of the active profile. */
@Composable
internal fun DownloadSettingsPage() {
    val tokens = MaterialTheme.nuvio
    val cloud = CloudApp.services
    val profileId by DebridOneRuntime.activeProfileId.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }

    ShellPage(title = "Downloads", subtitle = "Where files from your connected accounts go on this computer, and how they are fetched. These settings belong to this profile.") {
        val profile = profileId
        if (cloud == null || profile == null) {
            Notice(if (cloud == null) "Downloads could not be opened on this computer." else "Choose a profile first.", PillTone.NEUTRAL)
            return@ShellPage
        }
        val settings = cloud.downloads.settings
        // Read again after every change.
        revision.let { }

        ShellCard(Modifier.fillMaxWidth()) {
            SectionTitle("Folders", "A download goes to the folder of its shelf, inside a folder named after its cloud entry. Choose any folder you can write to.")
            @Composable
            fun folderRow(label: String, shelf: MediaClass?) {
                val path = if (shelf == null) settings.defaultFolder(profile) else settings.folder(profile, shelf)
                val custom = settings.isCustomFolder(profile, shelf)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                        Text(path.toString() + if (custom) "" else "  (default)", style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CloudButton("Choose…", { chooseFolder("Folder for $label")?.let { settings.setFolder(profile, shelf, it); revision++ } })
                    if (custom) CloudButton("Reset", { settings.setFolder(profile, shelf, null); revision++ })
                }
            }
            folderRow("Default folder", null)
            folderRow("Video", MediaClass.MOVIE)
            folderRow("Music", MediaClass.MUSIC)
            folderRow("Manga & comics", MediaClass.COMIC)
        }

        ShellCard(Modifier.fillMaxWidth()) {
            SectionTitle("Transfers")
            ChoiceRow("Downloads at the same time", (1..5).map { it to it.toString() }, settings.maxConcurrent(profile)) { settings.setMaxConcurrent(profile, it); revision++ }
            ChoiceRow("Try again by itself when the connection drops", listOf(true to "Yes", false to "No"), settings.autoRetry(profile)) { settings.setAutoRetry(profile, it); revision++ }
            ChoiceRow(
                "When a file of the same name is already there",
                ConflictPolicy.entries.map { it to it.label }, settings.conflictPolicy(profile),
            ) { settings.setConflictPolicy(profile, it); revision++ }
            ChoiceRow(
                "Refuse a download that would leave less free disk space than",
                listOf(0L to "No limit", 500L to "500 MB", 2_048L to "2 GB", 5_120L to "5 GB", 10_240L to "10 GB"), settings.spaceReserveMb(profile),
            ) { settings.setSpaceReserveMb(profile, it); revision++ }
        }

        ShellCard(Modifier.fillMaxWidth()) {
            SectionTitle("Libraries", "A finished download can join the library of its section by itself. Each section keeps its own library, details and covers; nothing is shared between them.")
            ChoiceRow("Add downloaded music to the Music library", listOf(true to "Yes", false to "No"), settings.autoImport(profile, MediaClass.MUSIC)) { settings.setAutoImport(profile, MediaClass.MUSIC, it); revision++ }
            ChoiceRow("Add downloaded comics to the Manga library", listOf(true to "Yes", false to "No"), settings.autoImport(profile, MediaClass.COMIC)) { settings.setAutoImport(profile, MediaClass.COMIC, it); revision++ }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(label: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (value, text) -> CloudChip(text, value == selected) { onSelect(value) } }
        }
    }
}
