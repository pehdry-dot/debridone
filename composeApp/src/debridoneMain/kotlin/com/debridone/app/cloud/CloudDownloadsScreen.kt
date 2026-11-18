package com.debridone.app.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.SegmentedTabs
import com.debridone.app.shell.SettingsPage
import com.debridone.app.shell.ShellPage
import com.debridone.app.shell.ShellState
import com.debridone.app.shell.StatusPill
import com.debridone.cloud.CloudAccount
import com.debridone.cloud.CloudFileRow
import com.debridone.cloud.CloudItemRow
import com.debridone.cloud.CloudQuery
import com.debridone.cloud.CloudServices
import com.debridone.cloud.Download
import com.debridone.cloud.ItemKind
import com.debridone.cloud.ItemStatus
import com.debridone.cloud.MediaClass
import com.debridone.cloud.ProviderId
import com.debridone.cloud.SortBy
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class DownloadsTab(val label: String, val provider: ProviderId?) {
    ALL("All", null),
    REAL_DEBRID("Real-Debrid", ProviderId.REAL_DEBRID),
    TORBOX("TorBox", ProviderId.TORBOX),
    LOCAL("Local", null),
}

/** What the Downloads section is showing. It lives outside composition so it is as the user left it. */
internal object DownloadsUi {
    var tab by mutableStateOf(DownloadsTab.ALL)
    var kind by mutableStateOf<ItemKind?>(null)
    var mediaClass by mutableStateOf<MediaClass?>(null)
    var search by mutableStateOf("")
    var sort by mutableStateOf(SortBy.DATE)
    var descending by mutableStateOf(true)
    var grid by mutableStateOf(false)

    /** The entry whose files are shown, and the folder inside it. */
    var openItemId by mutableStateOf<Long?>(null)
    var folder by mutableStateOf("")

    /** Files ticked for a download together. */
    var selected by mutableStateOf<Set<Long>>(emptySet())

    var localSection by mutableStateOf(LocalSection.CLOUD)
    var dialog by mutableStateOf<DownloadsDialog?>(null)

    fun showTab(target: DownloadsTab) {
        tab = target
        kind = null
        openItemId = null
        folder = ""
        selected = emptySet()
    }

    /** Opens the section on one shelf, for a section that sends the user here ("From your cloud…"). */
    fun showShelf(shelf: MediaClass) {
        showTab(DownloadsTab.ALL)
        mediaClass = shelf
        search = ""
    }

    fun open(item: CloudItemRow) {
        openItemId = item.id
        folder = ""
        selected = emptySet()
    }
}

internal sealed interface DownloadsDialog {
    class ItemDetails(val item: CloudItemRow) : DownloadsDialog
    class FileDetails(val file: CloudFileRow, val local: Download?) : DownloadsDialog
    class DownloadDetails(val download: Download) : DownloadsDialog
    class DeleteFromCloud(val item: CloudItemRow) : DownloadsDialog
    class DeleteLocal(val download: Download) : DownloadsDialog

    /** Reading a comic of the cloud needs the whole archive on this computer first: the user is told, and agrees. */
    class DownloadToRead(val file: CloudFileRow) : DownloadsDialog
}

internal fun iconOf(mediaClass: MediaClass): ImageVector = when (mediaClass) {
    MediaClass.MOVIE -> Icons.Rounded.Movie
    MediaClass.TV_SHOW -> Icons.Rounded.Tv
    MediaClass.MUSIC -> Icons.Rounded.MusicNote
    MediaClass.COMIC -> Icons.Rounded.MenuBook
    MediaClass.OTHER -> Icons.Rounded.InsertDriveFile
}

/**
 * The Downloads section: the files that are in the connected accounts, the transfers to this
 * computer, and what is on this computer. It manages files; it does not describe them: titles,
 * covers and progress belong to Streaming, Music and Manga.
 */
@Composable
internal fun CloudDownloadsScreen() {
    val cloud = CloudApp.services
    if (cloud == null) {
        ShellPage(title = "Downloads") { Notice("Downloads could not be opened on this computer: ${DebridOneRuntime.cloudError ?: "unknown reason"}", PillTone.DANGER) }
        return
    }
    val tokens = MaterialTheme.nuvio
    val profileId by DebridOneRuntime.activeProfileId.collectAsStateWithLifecycle()
    val allAccounts by cloud.accounts.accounts.collectAsStateWithLifecycle()
    val accounts = allAccounts.filter { it.visibleTo(profileId) }
    val refreshing by cloud.files.refreshing.collectAsStateWithLifecycle()
    val allDownloads by cloud.downloads.downloads.collectAsStateWithLifecycle()
    val downloads = allDownloads.filter { it.profileId == profileId }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val tab = DownloadsUi.tab

    // What was read more than ten minutes ago (or never) is read again when the section is opened.
    LaunchedEffect(profileId, accounts.map { it.id }) {
        val now = System.currentTimeMillis()
        accounts.filter { (it.lastListedAt ?: 0) < now - 10 * 60_000 && it.id !in refreshing }.forEach { CloudApp.refresh(profileId, it, fresh = false, announce = false) }
    }

    Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = topInset + 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedTabs(items = DownloadsTab.entries.map { it to it.label }, selected = tab, onSelect = { DownloadsUi.showTab(it) })
            Box(Modifier.weight(1f))
            if (tab != DownloadsTab.LOCAL) {
                SearchField(DownloadsUi.search) { DownloadsUi.search = it }
                SortControl()
                CloudIconButton(if (DownloadsUi.grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, if (DownloadsUi.grid) "Show as a list" else "Show as a grid", { DownloadsUi.grid = !DownloadsUi.grid })
                val shown = accounts.filter { tab.provider == null || it.provider == tab.provider }
                if (shown.any { it.id in refreshing }) {
                    CircularProgressIndicator(color = tokens.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    CloudIconButton(Icons.Rounded.Refresh, "Read the accounts again", { shown.forEach { CloudApp.refresh(profileId, it, fresh = true) } }, enabled = shown.isNotEmpty())
                }
            }
        }
        if (tab == DownloadsTab.LOCAL) {
            LocalTab(cloud, profileId, downloads)
        } else {
            CloudTab(cloud, profileId, accounts, downloads, tab)
        }
    }

    DownloadsDialogs(cloud, profileId)
    CloudMessage()
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(
        Modifier.width(260.dp).clip(RoundedCornerShape(50)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(50)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = tokens.colors.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(tokens.colors.accent),
            decorationBox = { inner ->
                Box(Modifier.padding(vertical = 9.dp)) {
                    if (value.isEmpty()) Text("Search by file name", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SortControl() {
    var open by remember { mutableStateOf(false) }
    val labels = mapOf(SortBy.NAME to "Name", SortBy.DATE to "Date added", SortBy.SIZE to "Size", SortBy.STATUS to "Status")
    Box {
        CloudButton(labels.getValue(DownloadsUi.sort), { open = true }, icon = if (DownloadsUi.descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            labels.forEach { (sort, label) ->
                DropdownMenuItem(text = { Text("Sort by ${label.lowercase()}") }, onClick = { DownloadsUi.sort = sort; open = false })
            }
            DropdownMenuItem(text = { Text(if (DownloadsUi.descending) "Ascending" else "Descending") }, onClick = { DownloadsUi.descending = !DownloadsUi.descending; open = false })
        }
    }
}

// ---------------------------------------------------------------- the accounts' entries

@Composable
private fun CloudTab(cloud: CloudServices, profileId: Long?, accounts: List<CloudAccount>, downloads: List<Download>, tab: DownloadsTab) {
    val tokens = MaterialTheme.nuvio
    val revision by cloud.files.revision.collectAsStateWithLifecycle()
    val provider = tab.provider
    val providerAccounts = accounts.filter { provider == null || it.provider == provider }

    // Filters: the shelves, and for one provider its kinds of entry.
    Row(Modifier.fillMaxWidth().padding(top = 14.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        CloudChip("Everything", DownloadsUi.mediaClass == null) { DownloadsUi.mediaClass = null }
        MediaClass.entries.forEach { shelf -> CloudChip(shelf.label, DownloadsUi.mediaClass == shelf) { DownloadsUi.mediaClass = if (DownloadsUi.mediaClass == shelf) null else shelf } }
        if (provider != null) {
            Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(22.dp).background(tokens.colors.borderDefault))
            cloud.accounts.provider(provider).kinds.forEach { kind -> CloudChip(kind.plural, DownloadsUi.kind == kind) { DownloadsUi.kind = if (DownloadsUi.kind == kind) null else kind } }
        }
    }
    if (DownloadsUi.mediaClass == MediaClass.MOVIE || DownloadsUi.mediaClass == MediaClass.TV_SHOW) {
        Text(
            "Files are sorted by what they are, not by what they are about: \"TV Shows\" are videos whose path carries an episode marker (S01E02, 1x02, a Season folder); every other video is under \"Movies\".",
            style = small(), color = tokens.colors.textMuted, modifier = Modifier.padding(top = 8.dp),
        )
    }

    val query = CloudQuery(provider, kinds = setOfNotNull(DownloadsUi.kind), mediaClass = DownloadsUi.mediaClass, search = DownloadsUi.search, sort = DownloadsUi.sort, descending = DownloadsUi.descending)
    val items by produceState<List<CloudItemRow>?>(null, revision, query, profileId) { value = withContext(Dispatchers.IO) { cloud.files.items(profileId, query) } }
    val openItem = DownloadsUi.openItemId?.let { id -> items?.firstOrNull { it.id == id } }
    val active = downloads.filter { it.active || it.state == com.debridone.cloud.DownloadState.FAILED }

    LazyColumn(Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 110.dp)) {
        if (active.isNotEmpty()) item("queue") { QueueStrip(cloud, profileId, active) }
        when {
            providerAccounts.isEmpty() -> item("empty") {
                EmptyState(
                    if (provider == null) "No account is connected for this profile." else "No ${provider.displayName} account is connected for this profile.",
                    "Connect your account in Settings › Connected Accounts. DebridOne then shows the files that are already in it.",
                ) { CloudButton("Open Connected Accounts", { ShellState.showInPlace(SettingsPage.CONNECTED_ACCOUNTS) }, icon = Icons.Rounded.Link, primary = true) }
            }
            items == null -> item("loading") { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = tokens.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) } }
            openItem != null -> {
                item("crumbs") { Breadcrumb(openItem) }
                item("files") { ItemFiles(cloud, profileId, openItem, revision, downloads) }
            }
            items!!.isEmpty() -> item("nothing") {
                val filtered = DownloadsUi.search.isNotBlank() || DownloadsUi.mediaClass != null || DownloadsUi.kind != null
                EmptyState(
                    if (filtered) "Nothing matches." else "Nothing is listed yet.",
                    if (filtered) "No entry of the connected accounts matches the search and the filters." else "Either the accounts are empty or they have not been read yet. Use the refresh button to read them now.",
                ) { if (filtered) CloudButton("Clear search and filters", { DownloadsUi.search = ""; DownloadsUi.mediaClass = null; DownloadsUi.kind = null }) }
            }
            DownloadsUi.grid -> item("grid") { ItemGrid(items!!) }
            else -> items(items!!, key = { it.id }) { item -> ItemRow(cloud, profileId, item, downloads) }
        }
        if (openItem == null && tab == DownloadsTab.ALL) {
            // What is on this computer follows the same shelf and search as the list above it.
            val wanted = DownloadsUi.search.trim().lowercase()
            val finished = downloads.filter { it.fileExists && (DownloadsUi.mediaClass == null || it.mediaClass == DownloadsUi.mediaClass) && (wanted.isEmpty() || it.fileName.lowercase().contains(wanted)) }.take(40)
            if (finished.isNotEmpty()) {
                item("local-title") { Text("On this computer", style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)) }
                items(finished, key = { "d${it.id}" }) { download -> DownloadRow(cloud, profileId, download) }
            }
        }
    }
}

@Composable
internal fun EmptyState(title: String, text: String, action: @Composable () -> Unit = {}) {
    val tokens = MaterialTheme.nuvio
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        action()
    }
}

private fun statusTone(status: ItemStatus): PillTone = when (status) {
    ItemStatus.READY -> PillTone.SUCCESS
    ItemStatus.ERROR -> PillTone.DANGER
    ItemStatus.DOWNLOADING, ItemStatus.QUEUED -> PillTone.ACCENT
    else -> PillTone.NEUTRAL
}

private fun itemSummary(item: CloudItemRow): String = listOfNotNull(
    item.kind.label,
    when {
        item.filesState == "packed" -> "packed by ${item.provider.displayName}"
        item.fileCount > 1 -> "${item.fileCount} files"
        else -> null
    },
    formatBytes(item.sizeBytes).takeIf { item.sizeBytes > 0 },
    item.createdAt?.let { "added ${formatDate(it)}" },
).joinToString("  ·  ")

@Composable
private fun ItemRow(cloud: CloudServices, profileId: Long?, item: CloudItemRow, downloads: List<Download>) {
    val tokens = MaterialTheme.nuvio
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).clickable { if (item.fileCount > 0) DownloadsUi.open(item) else DownloadsUi.dialog = DownloadsDialog.ItemDetails(item) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderMark(item.provider, 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${item.provider.displayName}  ·  ${itemSummary(item)}", style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.status == ItemStatus.DOWNLOADING && item.progress != null) {
                LinearProgressIndicator(progress = { item.progress!! }, color = tokens.colors.accent, trackColor = tokens.colors.borderSubtle, modifier = Modifier.fillMaxWidth(0.5f).height(3.dp).clip(RoundedCornerShape(50)))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            item.classes.sortedBy { it.ordinal }.forEach { shelf -> Icon(iconOf(shelf), contentDescription = shelf.label, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp)) }
        }
        StatusPill(if (item.status == ItemStatus.DOWNLOADING && item.progress != null) "${(item.progress!! * 100).toInt()}%" else item.status.label, statusTone(item.status))
        ItemMenu(cloud, profileId, item, downloads)
        if (item.fileCount > 0) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemGrid(items: List<CloudItemRow>) {
    val tokens = MaterialTheme.nuvio
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { item ->
            Column(
                Modifier.width(214.dp).clip(RoundedCornerShape(16.dp)).background(tokens.colors.surface).clickable { if (item.fileCount > 0) DownloadsUi.open(item) else DownloadsUi.dialog = DownloadsDialog.ItemDetails(item) }.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(iconOf(item.classes.minByOrNull { it.ordinal } ?: MediaClass.OTHER), contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(30.dp))
                    Box(Modifier.weight(1f))
                    ProviderMark(item.provider, 26.dp)
                }
                Text(item.name, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(item.kind.label, formatBytes(item.sizeBytes).takeIf { item.sizeBytes > 0 }).joinToString(" · "), style = small(), color = tokens.colors.textMuted, maxLines = 1)
                StatusPill(item.status.label, statusTone(item.status))
            }
        }
    }
}

@Composable
private fun ItemMenu(cloud: CloudServices, profileId: Long?, item: CloudItemRow, downloads: List<Download>) {
    var open by remember { mutableStateOf(false) }
    Box {
        CloudIconButton(Icons.Rounded.MoreHoriz, "More about ${item.name}", { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (item.status == ItemStatus.READY && item.fileCount > 0) {
                DropdownMenuItem(text = { Text(if (item.fileCount == 1) "Download" else "Download all ${item.fileCount} files") }, onClick = {
                    open = false
                    CloudApp.background { enqueue(cloud, profileId, cloud.files.files(profileId, item.id).map { it.id }) }
                })
            }
            DropdownMenuItem(text = { Text("Copy name") }, onClick = { open = false; copyToClipboard(item.name); CloudApp.say("Copied.") })
            DropdownMenuItem(text = { Text("View details") }, onClick = { open = false; DownloadsUi.dialog = DownloadsDialog.ItemDetails(item) })
            if (cloud.files.canDeleteFromCloud(item)) {
                DropdownMenuItem(text = { Text("Delete from ${item.provider.displayName}…", color = MaterialTheme.nuvio.colors.danger) }, onClick = { open = false; DownloadsUi.dialog = DownloadsDialog.DeleteFromCloud(item) })
            } else {
                DropdownMenuItem(text = { Text("Manage on ${item.provider.displayName}") }, onClick = { open = false; openInBrowser(item.provider.manageUrl) })
            }
        }
    }
}

/** Queues files of the cloud for download and says what happened. */
internal fun enqueue(cloud: CloudServices, profileId: Long?, fileIds: List<Long>, folder: java.nio.file.Path? = null) {
    val profile = profileId ?: return CloudApp.say("Choose a profile first.")
    val result = cloud.downloads.enqueue(profile, fileIds, folder)
    CloudApp.say(
        when {
            result.queued.isEmpty() -> result.problems.firstOrNull() ?: "Nothing to download."
            result.problems.isEmpty() -> "${plural(result.queued.size, "download")} queued."
            else -> "${plural(result.queued.size, "download")} queued; ${result.problems.size} not: ${result.problems.first()}"
        },
    )
}

// ---------------------------------------------------------------- inside an entry

@Composable
private fun Breadcrumb(item: CloudItemRow) {
    val tokens = MaterialTheme.nuvio
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        @Composable
        fun crumb(text: String, last: Boolean, onClick: () -> Unit) {
            Text(
                text, style = MaterialTheme.typography.titleMedium, color = if (last) tokens.colors.textPrimary else tokens.colors.textSecondary, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !last, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        val parts = DownloadsUi.folder.split('/').filter { it.isNotEmpty() }
        // A top folder that only repeats the entry's name is not shown a second time.
        val repeated = parts.firstOrNull() == item.name
        crumb("All entries", false) { DownloadsUi.openItemId = null; DownloadsUi.folder = ""; DownloadsUi.selected = emptySet() }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
        crumb(item.name, parts.isEmpty() || (repeated && parts.size == 1)) { DownloadsUi.folder = if (repeated) parts.first() else "" }
        parts.forEachIndexed { index, part ->
            if (index == 0 && repeated) return@forEachIndexed
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(18.dp))
            crumb(part, index == parts.lastIndex) { DownloadsUi.folder = parts.take(index + 1).joinToString("/") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemFiles(cloud: CloudServices, profileId: Long?, item: CloudItemRow, revision: Long, downloads: List<Download>) {
    val tokens = MaterialTheme.nuvio
    val all by produceState<List<CloudFileRow>?>(null, item.id, revision) { value = withContext(Dispatchers.IO) { cloud.files.files(profileId, item.id) } }
    val files = all ?: return
    val shelf = DownloadsUi.mediaClass
    val search = DownloadsUi.search.trim().lowercase()
    val matching = files.filter { (shelf == null || it.mediaClass == shelf) && (search.isEmpty() || it.path.lowercase().contains(search)) }

    // A single top folder that holds everything is stepped into, so the user is not made to click through it.
    LaunchedEffect(item.id, matching.size) {
        if (DownloadsUi.folder.isEmpty()) {
            val tops = matching.map { it.path.substringBefore('/', "") }.toSet()
            if (tops.size == 1 && tops.first().isNotEmpty() && matching.none { !it.path.contains('/') }) DownloadsUi.folder = tops.first()
        }
    }
    val prefix = DownloadsUi.folder.let { if (it.isEmpty()) "" else "$it/" }
    val inside = matching.filter { it.path.startsWith(prefix) }
    val folders = inside.mapNotNull { it.path.removePrefix(prefix).substringBefore('/', "").takeIf { name -> name.isNotEmpty() && it.path.removePrefix(prefix).contains('/') } }.distinct().sorted()
    val here = inside.filter { !it.path.removePrefix(prefix).contains('/') }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill(item.status.label, statusTone(item.status))
            Text("${item.provider.displayName}  ·  ${itemSummary(item)}", style = small(), color = tokens.colors.textMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val chosen = DownloadsUi.selected.intersect(inside.map { it.id }.toSet())
            if (chosen.isNotEmpty()) {
                CloudButton("Download ${plural(chosen.size, "selected file")}", { CloudApp.background { enqueue(cloud, profileId, chosen.toList()); DownloadsUi.selected = emptySet() } }, icon = Icons.Rounded.Download, primary = true)
                CloudButton("Clear", { DownloadsUi.selected = emptySet() })
            } else if (inside.isNotEmpty() && item.status == ItemStatus.READY) {
                CloudButton(if (inside.size == 1) "Download" else "Download these ${inside.size} files", { CloudApp.background { enqueue(cloud, profileId, inside.map { it.id }) } }, icon = Icons.Rounded.Download)
            }
            ItemMenu(cloud, profileId, item, downloads)
        }
        if (item.filesState == "packed") {
            Notice("${item.provider.displayName} keeps this torrent as an archive, so its files cannot be listed or opened one by one here. It can be downloaded as it is.", PillTone.NEUTRAL)
        }
        if (inside.isEmpty()) {
            EmptyState("No files here.", if (files.isEmpty()) "This entry has no files to hand out yet." else "No file of this entry matches the search and the filters.")
            return@Column
        }
        folders.forEach { name ->
            val count = inside.count { it.path.removePrefix(prefix).startsWith("$name/") }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tokens.colors.surface).clickable { DownloadsUi.folder = prefix + name }.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Folder, contentDescription = null, tint = tokens.colors.accent, modifier = Modifier.size(22.dp))
                Text(name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(plural(count, "file"), style = small(), color = tokens.colors.textMuted)
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = tokens.colors.textMuted, modifier = Modifier.size(20.dp))
            }
        }
        if (DownloadsUi.grid) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                here.forEach { file -> FileCard(cloud, profileId, file, downloads) }
            }
        } else {
            here.forEach { file -> FileRow(cloud, profileId, file, downloads) }
        }
    }
}

private fun localOf(file: CloudFileRow, downloads: List<Download>): Download? =
    downloads.firstOrNull { it.accountId == file.accountId && it.kind == file.kind && it.itemRemoteId == file.itemRemoteId && it.fileRef == file.ref && (it.active || it.fileExists) }

@Composable
private fun FileRow(cloud: CloudServices, profileId: Long?, file: CloudFileRow, downloads: List<Download>) {
    val tokens = MaterialTheme.nuvio
    val local = localOf(file, downloads)
    val ticked = file.id in DownloadsUi.selected
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tokens.colors.surface).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CloudIconButton(if (ticked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, if (ticked) "Deselect ${file.name}" else "Select ${file.name}", {
            DownloadsUi.selected = if (ticked) DownloadsUi.selected - file.id else DownloadsUi.selected + file.id
        }, size = 30.dp, tint = if (ticked) tokens.colors.accent else tokens.colors.textMuted)
        Icon(iconOf(file.mediaClass), contentDescription = file.mediaClass.label, tint = tokens.colors.textSecondary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(formatBytes(file.sizeBytes).takeIf { file.sizeBytes > 0 }, file.mediaClass.label).joinToString("  ·  "), style = small(), color = tokens.colors.textMuted, maxLines = 1)
        }
        when {
            local?.fileExists == true -> StatusPill("On this computer", PillTone.SUCCESS)
            local?.active == true -> StatusPill(local.fraction?.let { "${(it * 100).toInt()}%" } ?: "Queued", PillTone.ACCENT)
        }
        val actions = fileActions(cloud, profileId, file, local)
        actions.firstOrNull { it.primary }?.let { action -> CloudButton(action.label, action.run, icon = action.icon, primary = true) }
        ActionMenu("More about ${file.name}", actions)
    }
}

@Composable
private fun FileCard(cloud: CloudServices, profileId: Long?, file: CloudFileRow, downloads: List<Download>) {
    val tokens = MaterialTheme.nuvio
    val local = localOf(file, downloads)
    val actions = fileActions(cloud, profileId, file, local)
    Column(Modifier.width(214.dp).clip(RoundedCornerShape(16.dp)).background(tokens.colors.surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(iconOf(file.mediaClass), contentDescription = file.mediaClass.label, tint = tokens.colors.accent, modifier = Modifier.size(30.dp))
            Box(Modifier.weight(1f))
            ActionMenu("More about ${file.name}", actions)
        }
        Text(file.name, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(formatBytes(file.sizeBytes).takeIf { file.sizeBytes > 0 }, "on this computer".takeIf { local?.fileExists == true }).joinToString(" · "), style = small(), color = tokens.colors.textMuted, maxLines = 1)
        actions.firstOrNull { it.primary }?.let { action -> CloudButton(action.label, action.run, icon = action.icon, primary = true) }
    }
}

@Composable
internal fun ActionMenu(description: String, actions: List<CloudAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        CloudIconButton(Icons.Rounded.MoreHoriz, description, { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label, color = if (action.danger) MaterialTheme.nuvio.colors.danger else MaterialTheme.nuvio.colors.textPrimary) },
                    leadingIcon = { Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (action.danger) MaterialTheme.nuvio.colors.danger else MaterialTheme.nuvio.colors.textSecondary) },
                    onClick = { open = false; action.run() },
                )
            }
        }
    }
}
