package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.StatusPill
import com.debridone.manga.MangaAppSettings
import com.debridone.manga.mangayomi.ImportItem
import com.debridone.manga.mangayomi.ImportPreview
import com.debridone.manga.mangayomi.IndexFormat
import com.debridone.manga.mangayomi.KnownIndex
import com.debridone.manga.mangayomi.Support
import com.debridone.manga.suwayomi.SwStore
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The import panel's state: it outlives leaving the tab, so a long look or installation is not lost. */
internal object ImportState {
    var address by mutableStateOf("")
    var preview by mutableStateOf<ImportPreview?>(null)
    var selected by mutableStateOf(setOf<String>())
    var looking by mutableStateOf(false)
    var installing by mutableStateOf<String?>(null)
    var compatibleOnly by mutableStateOf(true)
    var language by mutableStateOf<String?>(null)
    var showAdult by mutableStateOf(false)
    var expanded by mutableStateOf<String?>(null)

    /** An address or file waiting for the one-time notice about what extensions may do. */
    var pending by mutableStateOf<(() -> Unit)?>(null)
}

/**
 * Manga › Extensions › Import: paste the address of an index (or choose a JSON file), see what it
 * offers and whether each entry can run here, choose, install. Stores of the manga server's own
 * kind are registered with it on the way, so it keeps finding their updates; Mangayomi indexes are
 * kept with the profile for the same reason. Nothing opens elsewhere.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImportTab() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val settings by manga.services.appSettings.values.collectAsState()
    val revision by manga.services.extensions.revision.collectAsState()
    val generation by manga.services.server.generation.collectAsState()
    val stores by produceState<List<SwStore>>(emptyList(), revision, generation) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.extensions.stores() }.getOrDefault(emptyList()) }
    }
    val indexes by produceState<List<KnownIndex>>(emptyList(), revision, generation, ImportState.preview) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.imports.indexes() }.getOrDefault(emptyList()) }
    }

    fun guarded(action: () -> Unit) {
        if (settings[MangaAppSettings.EXTENSION_NOTICE_SEEN] == "true") action() else ImportState.pending = action
    }

    fun look(file: java.nio.file.Path? = null) {
        val address = ImportState.address.trim()
        if ((file == null && address.isEmpty()) || ImportState.looking) return
        guarded {
            ImportState.looking = true
            ImportState.preview = null
            MangaPluginsState.problem = null
            MangaPluginsState.notice = null
            importScope.launch {
                try {
                    val preview = if (file != null) manga.services.imports.inspectFile(file) else manga.services.imports.inspect(address)
                    ImportState.preview = preview
                    // A short list starts chosen; a long one starts empty, so nothing is installed in bulk by surprise.
                    val ready = preview.items.filter { it.installable && it.installedVersion == null && (ImportState.showAdult || !it.nsfw) }
                    ImportState.selected = if (ready.size <= 10) ready.map { it.key }.toSet() else emptySet()
                    if (preview.format != IndexFormat.MANGAYOMI) manga.services.extensions.refreshed()
                } catch (failure: Exception) {
                    MangaPluginsState.fail(failure.message ?: "That address could not be read.")
                } finally {
                    ImportState.looking = false
                }
            }
        }
    }

    fun install(keys: Collection<String>) {
        val preview = ImportState.preview ?: return
        if (keys.isEmpty() || ImportState.installing != null) return
        guarded {
            ImportState.installing = "Starting…"
            importScope.launch {
                try {
                    val report = manga.services.imports.install(preview, keys) { done, total, name ->
                        ImportState.installing = if (name.isBlank()) "Finishing…" else "Installing ${done + 1} of $total: $name"
                    }
                    val parts = buildList {
                        if (report.installed.isNotEmpty()) add("${plural(report.installed.size, "extension")} installed (${report.installed.take(4).joinToString()}${if (report.installed.size > 4) "…" else ""})")
                        if (report.skipped.isNotEmpty()) add("${report.skipped.size} cannot run here")
                    }
                    if (report.failed.isEmpty()) {
                        MangaPluginsState.say(parts.joinToString("; ") + ". Their sources are under Sources; test one there to see it work.")
                    } else {
                        MangaPluginsState.fail((parts + report.failed.map { "${it.first}: ${it.second}" }).joinToString("\n"))
                    }
                    // Look again, so what is installed now says so.
                    ImportState.preview = runCatching {
                        if (preview.format == IndexFormat.MANGAYOMI && preview.address.startsWith("http")) manga.services.imports.inspect(preview.address) else preview
                    }.getOrDefault(preview)
                    ImportState.selected = emptySet()
                } finally {
                    ImportState.installing = null
                }
            }
        }
    }

    val preview = ImportState.preview
    val languages = preview?.items?.map { it.lang }?.filter { it.isNotBlank() }?.distinct()?.sortedBy(::languageName).orEmpty()
    val shown = preview?.items.orEmpty().filter { item ->
        (!ImportState.compatibleOnly || item.installable) && (ImportState.language == null || item.lang == ImportState.language) && (ImportState.showAdult || !item.nsfw)
    }
    val compatible = preview?.items.orEmpty().filter { it.installable && it.installedVersion == null && (ImportState.showAdult || !it.nsfw) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 14.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Add extensions", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                Text(
                    "Paste the address of an index, or choose a JSON file. DebridOne recognises Mihon/Tachiyomi stores (index.min.json, index.json, repo.json, index.pb) and Mangayomi indexes, " +
                        "shows what each entry is and whether it can run here, and installs what you choose. A store is registered with the manga server on the way, so its updates are found.",
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                )
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaTextField(
                        ImportState.address, { ImportState.address = it }, "Address of the index", Modifier.weight(1f), onSubmit = { look() },
                        hint = "For example …/index.json of a Mangayomi repository, or a store's repo.json.",
                    )
                    MangaButton("Look", { look() }, icon = Icons.Rounded.Search, enabled = ImportState.address.isNotBlank() && !ImportState.looking && ImportState.installing == null)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MangaButton("Choose a JSON file…", { MangaPickers.chooseIndexFile()?.let { look(it) } }, primary = false, icon = Icons.Rounded.InsertDriveFile, enabled = !ImportState.looking)
                    MangaButton("Install an extension file (.apk, .jar)…", {
                        MangaPickers.choosePackage()?.let { file ->
                            guarded {
                                ImportState.installing = "Installing ${file.fileName}…"
                                importScope.launch {
                                    try {
                                        MangaPluginsState.say("${manga.services.extensions.installFile(file).name} installed from ${file.fileName}.")
                                    } catch (failure: Exception) {
                                        MangaPluginsState.fail(failure.message)
                                    } finally {
                                        ImportState.installing = null
                                    }
                                }
                            }
                        }
                    }, primary = false, icon = Icons.Rounded.Download, enabled = ImportState.installing == null)
                }
                if (ImportState.looking) Working("Reading the index and the code it points to…")
                ImportState.installing?.let { Working(it) }
                PluginsMessagesBox()
            }
        }
        if (preview != null) {
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusPill(preview.format.label, PillTone.ACCENT)
                        Text(preview.title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
                    }
                    if (preview.note.isNotBlank()) Text(preview.note, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                    if (preview.items.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            MangaChip("Only what can run here", ImportState.compatibleOnly) { ImportState.compatibleOnly = !ImportState.compatibleOnly }
                            LanguagePicker(languages, ImportState.language) { ImportState.language = it }
                            MangaChip("Show 18+", ImportState.showAdult) { ImportState.showAdult = !ImportState.showAdult }
                            val choosable = shown.filter { it.installable && it.installedVersion == null }.map { it.key }
                            if (choosable.isNotEmpty() && !ImportState.selected.containsAll(choosable)) {
                                MangaButton("Choose the ${plural(choosable.size, "shown one")}", { ImportState.selected = ImportState.selected + choosable }, primary = false)
                            }
                            if (ImportState.selected.isNotEmpty()) MangaButton("Clear the choice", { ImportState.selected = emptySet() }, primary = false)
                            MangaButton(
                                "Install selected (${ImportState.selected.size})", { install(ImportState.selected) }, icon = Icons.Rounded.Download,
                                enabled = ImportState.selected.isNotEmpty() && ImportState.installing == null,
                            )
                            MangaButton(
                                "Install all that can run (${compatible.size})", { install(compatible.map { it.key }) }, primary = false, icon = Icons.Rounded.Add,
                                enabled = compatible.isNotEmpty() && ImportState.installing == null,
                            )
                        }
                        Text("${plural(shown.size, "entry", "entries")} shown of ${preview.items.size}", style = small(), color = tokens.colors.textMuted)
                    }
                }
            }
            items(shown, key = { "import:" + it.key }) { item -> ImportRow(item) }
        }
        item {
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your stores and indexes", style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                if (stores.isEmpty() && indexes.isEmpty()) Text("Nothing imported yet for this profile.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
            }
        }
        items(stores, key = { "store:" + it.indexUrl }) { store -> KnownRow(store.name.ifBlank { "Store" }, store.indexUrl, "Mihon/Tachiyomi store", onLook = { ImportState.address = store.indexUrl; look() }) {
            importScope.launch {
                runCatching { manga.services.extensions.removeStore(store.indexUrl) }
                    .onSuccess { MangaPluginsState.say("${store.name.ifBlank { "The store" }} was removed. Extensions installed from it stay until you remove them.") }
                    .onFailure { MangaPluginsState.fail(it.message) }
            }
        } }
        items(indexes, key = { "index:" + it.address }) { index -> KnownRow(index.title, index.address, "Mangayomi index", onLook = { ImportState.address = index.address; look() }) {
            importScope.launch {
                runCatching { manga.services.imports.forgetIndex(index.address) }
                manga.services.extensions.refreshed()
                MangaPluginsState.say("The index was forgotten. Sources installed from it stay until you remove them under Installed.")
            }
        } }
    }
}

private val importScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

@Composable
private fun Working(text: String) {
    val manga = LocalManga.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.nuvio.colors.textSecondary)
    }
}

@Composable
internal fun PluginsMessagesBox() {
    MangaPluginsState.problem?.let { Notice(it, PillTone.DANGER) }
    MangaPluginsState.notice?.let { Notice(it, PillTone.SUCCESS) }
}

/** One entry of an index: what it is, whether it can run here and why, and whether it is chosen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportRow(item: ImportItem) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val chosen = item.key in ImportState.selected
    val selectable = item.installable && ImportState.installing == null
    val open = ImportState.expanded == item.key
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).border(1.dp, if (chosen) manga.style.accent.copy(alpha = 0.6f) else tokens.colors.borderSubtle, RoundedCornerShape(14.dp))
            .clickable { ImportState.expanded = if (open) null else item.key }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MangaIconButton(
                if (chosen) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, if (chosen) "Do not install ${item.name}" else "Install ${item.name}",
                { if (selectable) ImportState.selected = if (chosen) ImportState.selected - item.key else ImportState.selected + item.key },
                size = 30.dp, tint = if (selectable) manga.style.accent else tokens.colors.textMuted, enabled = selectable,
            )
            if (item.iconUrl.startsWith("http")) UrlCover(item.iconUrl, Modifier.size(36.dp), RoundedCornerShape(9.dp), width = 96) else ServerIcon(item.iconUrl, Modifier.size(36.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(languageName(item.lang), item.kind, "version ${item.version}", item.site.removePrefix("https://").removePrefix("http://").trimEnd('/')).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.nsfw) StatusPill("18+", PillTone.WARNING)
            when {
                item.installedVersion != null && item.updateAvailable -> StatusPill("Update: ${item.installedVersion} → ${item.version}", PillTone.WARNING)
                item.installedVersion != null -> StatusPill("Installed", PillTone.SUCCESS)
            }
            StatusPill(
                item.compatibility.support.label,
                when (item.compatibility.support) { Support.DIRECT -> PillTone.SUCCESS; Support.ADAPTER -> PillTone.ACCENT; Support.NONE -> PillTone.DANGER },
            )
        }
        if (open || item.compatibility.support == Support.NONE) {
            Text(item.compatibility.reason, style = small(), color = tokens.colors.textSecondary)
            item.compatibility.warnings.forEach { Text("· $it", style = small(), color = tokens.colors.warning) }
            item.alternative?.let { Text(it, style = small(), color = tokens.colors.textSecondary) }
        } else if (item.compatibility.warnings.isNotEmpty()) {
            Text("${plural(item.compatibility.warnings.size, "note")} — click to read", style = small(), color = tokens.colors.warning)
        }
    }
}

@Composable
private fun KnownRow(title: String, address: String, kind: String, onLook: () -> Unit, onRemove: () -> Unit) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Storefront, contentDescription = null, tint = manga.style.accent, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SelectionContainer { Text(address, style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        StatusPill(kind)
        MangaButton("Look again", onLook, primary = false, icon = Icons.Rounded.Refresh, enabled = !ImportState.looking)
        MangaIconButton(Icons.Rounded.Delete, "Remove $title", onRemove, size = 34.dp)
    }
}

/** Shown once, before the first index or store is used: what extensions may do. */
@Composable
internal fun ImportNoticeDialog() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val action = ImportState.pending ?: return
    val dismiss = { ImportState.pending = null }
    MangaDialogFrame("Before you add extensions", dismiss, width = 560) {
        Text(EXTENSION_NOTICE, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        Text(
            "A Mangayomi JavaScript source runs in a closed JavaScript engine inside the manga server: it can reach the sites it reads through the server, and nothing else on this computer.",
            style = small(), color = tokens.colors.textMuted,
        )
        MangaDialogButtons("I understand", dismiss) {
            manga.services.appSettings.set(MangaAppSettings.EXTENSION_NOTICE_SEEN, "true")
            dismiss()
            action()
        }
    }
}
