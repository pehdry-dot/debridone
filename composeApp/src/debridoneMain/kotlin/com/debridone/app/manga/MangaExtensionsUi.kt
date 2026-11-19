package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
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
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.StatusPill
import com.debridone.manga.MangaPreferences
import com.debridone.manga.library.SourceHealth
import com.debridone.manga.mangayomi.ExtensionImports
import com.debridone.manga.mangayomi.Support
import com.debridone.manga.sources.FailureKind
import com.debridone.manga.sources.SourceEntry
import com.debridone.manga.suwayomi.SwExtension
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/** What the Installed tab is doing, kept while the person looks elsewhere. */
internal object InstalledState {
    var query by mutableStateOf("")
    var expanded by mutableStateOf<String?>(null)

    /** Sources being tested right now. */
    var testing by mutableStateOf(setOf<String>())

    /** Mangayomi sources whose index has a newer version (found with "Check for updates"). */
    var mangayomiUpdates by mutableStateOf(mapOf<String, String>())
    var checking by mutableStateOf(false)

    /** The extension the person asked to remove, waiting for confirmation. */
    var removing by mutableStateOf<SwExtension?>(null)
}

private val extensionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal fun isMangayomiPackage(pkgName: String) = pkgName.startsWith("debridone.mangayomi.")

/** Tests sources two at a time, so neither the sites nor the server are crowded. */
internal fun testSources(manga: MangaContext, ids: List<String>) {
    val fresh = ids.filter { it !in InstalledState.testing }
    if (fresh.isEmpty()) return
    InstalledState.testing = InstalledState.testing + fresh
    val permits = Semaphore(2)
    for (id in fresh) {
        extensionScope.launch {
            permits.acquire()
            try {
                runCatching { manga.services.sources.test(id) }
            } finally {
                permits.release()
                withContext(Dispatchers.Main) { InstalledState.testing = InstalledState.testing - id }
            }
        }
    }
}

/**
 * The health to show for an extension: a source that fails, else one that works. A spare source
 * that only waits for its settings (a second Komga server never filled in) does not hide one that
 * works; it is counted on the card instead.
 */
private fun summaryHealth(sources: List<SourceEntry>): SourceHealth? {
    val tested = sources.filter { it.enabled }.mapNotNull { it.health }
    val failing = tested.filter { !it.ok }
    return failing.filter { it.kind != FailureKind.NEEDS_SETTINGS.name }.maxByOrNull { it.testedAt }
        ?: tested.filter { it.ok }.maxByOrNull { it.testedAt }
        ?: failing.maxByOrNull { it.testedAt }
}

/**
 * Manga › Extensions › Installed: one card per extension of this profile, Mihon/Tachiyomi or
 * Mangayomi, with what it is, whether it works here (the last test of its sources) and everything
 * that can be done with it. Its sources and their test steps open below it, in place.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InstalledTab() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val actions = LocalMangaActions.current
    val revision by manga.services.extensions.revision.collectAsState()
    val generation by manga.services.server.generation.collectAsState()
    val extensions by produceState<List<SwExtension>?>(null, revision, generation) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.extensions.list() }.getOrNull() }
    }
    val bySource by produceState(emptyMap<String, List<String>>(), revision, generation) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.extensions.sourcesByExtension() }.getOrDefault(emptyMap()) }
    }
    val records by produceState(emptyMap<String, ExtensionImports.InstalledRecord>(), revision, generation) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.imports.installedRecords() }.getOrDefault(emptyMap()) }
    }
    val sources by rememberMangaQuery(revision, generation, initial = emptyList<SourceEntry>()) { services, profile -> services.sources.list(profile) }
    val sourceById = sources.associateBy { it.id }
    val installed = extensions.orEmpty().filter { it.installed }
    val needle = InstalledState.query.trim().lowercase()
    val shown = installed.filter { needle.isEmpty() || it.name.lowercase().contains(needle) || it.pkgName.lowercase().contains(needle) }
    val preferred = sources.filter { it.preferredRank != null }.sortedBy { it.preferredRank }.map { it.id }
    val updates = installed.count { it.hasUpdate } + InstalledState.mangayomiUpdates.size

    fun change(extension: SwExtension, doing: String, done: String, action: suspend () -> Unit) {
        if (extension.pkgName in MangaPluginsState.working) return
        MangaPluginsState.working[extension.pkgName] = doing
        extensionScope.launch {
            try {
                action()
                MangaPluginsState.say("${extension.name} $done.")
            } catch (failure: Throwable) {
                MangaPluginsState.fail("${extension.name}: ${failure.message ?: "that did not work."}")
            } finally {
                MangaPluginsState.working.remove(extension.pkgName)
            }
        }
    }

    fun checkUpdates() {
        if (InstalledState.checking) return
        InstalledState.checking = true
        extensionScope.launch {
            try {
                val store = runCatching { manga.services.extensions.list(refresh = true).count { it.installed && it.hasUpdate } }
                val indexes = runCatching { manga.services.imports.checkUpdates() }
                indexes.getOrNull()?.let { found -> InstalledState.mangayomiUpdates = found.associate { it.key to it.version } }
                val total = (store.getOrNull() ?: 0) + (indexes.getOrNull()?.size ?: 0)
                val problems = listOfNotNull(
                    store.exceptionOrNull()?.let { "the stores could not be read (${it.message})" },
                    indexes.exceptionOrNull()?.let { "the indexes could not be read (${it.message})" },
                )
                if (problems.isEmpty()) MangaPluginsState.say(if (total == 0) "Everything installed is up to date." else "${plural(total, "update")} available.")
                else MangaPluginsState.fail("Updates: " + problems.joinToString("; ") + ".")
            } finally {
                InstalledState.checking = false
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 14.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Installed extensions", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                Text(
                    "Each extension brings one or more sources. Mihon/Tachiyomi extensions run in the manga server as they are; Mangayomi JavaScript sources run there through DebridOne's bridge. " +
                        "A status is the last test run on this computer, with its date — nothing is called working that has not been tried.",
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    if (installed.isNotEmpty()) MangaSearchField(InstalledState.query, { InstalledState.query = it }, Modifier.width(280.dp), placeholder = "Find an installed extension")
                    MangaButton(if (InstalledState.checking) "Checking…" else "Check for updates", ::checkUpdates, primary = false, icon = Icons.Rounded.Refresh, enabled = !InstalledState.checking && installed.isNotEmpty())
                    if (installed.any { it.hasUpdate }) {
                        MangaButton("Update all from the stores (${installed.count { it.hasUpdate }})", {
                            MangaPluginsState.progress = "Updating…"
                            extensionScope.launch {
                                try {
                                    MangaPluginsState.say("${plural(manga.services.extensions.updateAll(), "extension")} updated.")
                                } catch (failure: Throwable) {
                                    MangaPluginsState.fail(failure.message)
                                } finally {
                                    MangaPluginsState.progress = null
                                }
                            }
                        }, icon = Icons.Rounded.SystemUpdateAlt, enabled = MangaPluginsState.progress == null)
                    }
                    val testable = shown.flatMap { bySource[it.pkgName].orEmpty() }.filter { sourceById[it]?.enabled == true }
                    MangaButton(
                        if (InstalledState.testing.isEmpty()) "Test the ones shown (${testable.size})" else "Testing… ${InstalledState.testing.size} left",
                        { testSources(manga, testable) }, primary = false, icon = Icons.Rounded.PlayArrow, enabled = InstalledState.testing.isEmpty() && testable.isNotEmpty(),
                    )
                    MangaButton("Add extensions", { MangaPluginsState.show(MangaPluginsTab.IMPORT) }, icon = Icons.Rounded.Add)
                }
                if (updates > 0) Text("${plural(updates, "update")} waiting.", style = small(), color = tokens.colors.warning)
                PluginsMessagesBox()
                if (extensions != null && installed.isEmpty()) {
                    MangaEmptyState(
                        Icons.Rounded.Extension, "No extension installed",
                        "DebridOne comes with no sources of its own. Paste the address of an index (a Mihon/Tachiyomi store or a Mangayomi index) under Import, see what it offers, and install what you want. Your own files need none of this.",
                    ) { MangaButton("Import", { MangaPluginsState.show(MangaPluginsTab.IMPORT) }, icon = Icons.Rounded.Add) }
                }
            }
        }
        items(shown, key = { it.pkgName }) { extension ->
            val ids = bySource[extension.pkgName].orEmpty()
            val own = ids.mapNotNull { sourceById[it] }
            InstalledCard(
                extension = extension,
                sources = own,
                record = records[extension.pkgName],
                newerVersion = InstalledState.mangayomiUpdates[extension.pkgName],
                working = MangaPluginsState.working[extension.pkgName],
                expanded = InstalledState.expanded == extension.pkgName,
                onExpand = { InstalledState.expanded = if (InstalledState.expanded == extension.pkgName) null else extension.pkgName },
                onToggle = {
                    val on = own.none { it.enabled }
                    actions.io { own.forEach { manga.services.sources.setEnabled(manga.profileId, it.id, on) } }
                },
                onFavourite = {
                    val favourite = own.any { it.preferredRank != null }
                    val next = if (favourite) preferred - own.map { it.id }.toSet() else preferred + own.map { it.id }
                    actions.io { manga.services.sources.setPreferred(manga.profileId, next) }
                },
                onUpdate = {
                    if (isMangayomiPackage(extension.pkgName)) {
                        change(extension, "Updating…", "was updated") {
                            manga.services.imports.update(extension.pkgName)
                            InstalledState.mangayomiUpdates = InstalledState.mangayomiUpdates - extension.pkgName
                            manga.services.extensions.refreshed()
                        }
                    } else {
                        change(extension, "Updating…", "was updated") { manga.services.extensions.update(extension.pkgName) }
                    }
                },
                onRemove = { InstalledState.removing = extension },
            )
        }
    }
    InstalledState.removing?.let { extension ->
        val dismiss = { InstalledState.removing = null }
        MangaDialogFrame("Remove ${extension.name}?", dismiss, width = 520) {
            Text(
                "Its sources disappear from Discover and Search. Series you added from them stay in your library, with your progress and downloads; they cannot be refreshed until the extension is installed again.",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
            MangaDialogButtons("Remove", dismiss, danger = true) {
                dismiss()
                change(extension, "Removing…", "was removed") {
                    if (isMangayomiPackage(extension.pkgName)) manga.services.imports.uninstall(extension.pkgName) else manga.services.extensions.uninstall(extension.pkgName)
                    InstalledState.mangayomiUpdates = InstalledState.mangayomiUpdates - extension.pkgName
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledCard(
    extension: SwExtension,
    sources: List<SourceEntry>,
    record: ExtensionImports.InstalledRecord?,
    newerVersion: String?,
    working: String?,
    expanded: Boolean,
    onExpand: () -> Unit,
    onToggle: () -> Unit,
    onFavourite: () -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val mangayomi = isMangayomiPackage(extension.pkgName)
    val on = sources.any { it.enabled }
    val favourite = sources.any { it.preferredRank != null }
    val health = summaryHealth(sources)
    val testing = sources.any { it.id in InstalledState.testing }
    val site = (record?.baseUrl?.takeIf { it.isNotBlank() } ?: sources.firstNotNullOfOrNull { it.homeUrl ?: it.baseUrl }).orEmpty()
    val languages = sources.map { it.language }.filter { it.isNotBlank() }.distinct()
    val version = if (mangayomi) record?.version ?: extension.versionName else extension.versionName
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MangaIconButton(
                if (favourite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                if (favourite) "Favourite: its sources are searched and listed first. Click to stop." else "Make it a favourite: its sources are searched and listed first",
                onFavourite, size = 30.dp, tint = if (favourite) manga.style.accent else tokens.colors.textMuted, enabled = sources.isNotEmpty(),
            )
            val icon = record?.iconUrl?.takeIf { it.startsWith("http") }
            if (icon != null) UrlCover(icon, Modifier.size(42.dp), RoundedCornerShape(10.dp), width = 96) else ServerIcon(extension.iconUrl, Modifier.size(42.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(extension.name, style = MaterialTheme.typography.titleSmall, color = if (on) tokens.colors.textPrimary else tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(
                        when {
                            languages.size > 3 -> "${languages.size} languages"
                            languages.isNotEmpty() -> languages.joinToString(", ") { languageName(it) }
                            else -> languageName(extension.lang)
                        },
                        site.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                        "version $version",
                    ).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (extension.contentWarning == "NSFW") StatusPill("18+", PillTone.WARNING)
            StatusPill(if (mangayomi) "Mangayomi · JavaScript" else "Mihon/Tachiyomi")
            if (testing) CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else SourceStatusPill(health)
            MangaChip(if (on) "On" else "Off", on, onClick = onToggle)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            StatusPill(if (mangayomi) Support.ADAPTER.label else Support.DIRECT.label, if (mangayomi) PillTone.ACCENT else PillTone.SUCCESS)
            if (sources.size > 1) {
                val waiting = sources.count { it.enabled && it.health?.kind == FailureKind.NEEDS_SETTINGS.name }
                Text(
                    "${plural(sources.size, "source")} · ${sources.count { it.enabled }} on" + if (waiting > 0) " · $waiting waiting for settings" else "",
                    style = small(), color = tokens.colors.textMuted,
                )
            }
            if (working != null) {
                CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(working, style = small(), color = tokens.colors.textSecondary)
            } else {
                sources.firstOrNull { it.configurable }?.let { first ->
                    MangaButton(if (sources.count { it.configurable } > 1) "Configure ${first.displayName.ifBlank { first.name }}" else "Configure", { MangaPluginsState.configuring = first.id }, primary = false, icon = Icons.Rounded.Tune)
                }
                MangaButton("Test", { testSources(manga, sources.filter { it.enabled }.map { it.id }) }, primary = false, icon = Icons.Rounded.PlayArrow, enabled = !testing && on)
                sources.firstOrNull { it.enabled }?.let { first -> MangaButton("Browse", { browseSource(manga, first.id) }, primary = false, icon = Icons.Rounded.Explore) }
                when {
                    extension.hasUpdate -> MangaButton("Update", onUpdate, icon = Icons.Rounded.SystemUpdateAlt)
                    newerVersion != null -> MangaButton("Update to $newerVersion", onUpdate, icon = Icons.Rounded.SystemUpdateAlt)
                }
                MangaButton(if (expanded) "Hide diagnosis" else "Diagnosis", onExpand, primary = false, icon = Icons.Rounded.MedicalServices)
                MangaButton("Uninstall", onRemove, primary = false, danger = true, icon = Icons.Rounded.Delete)
            }
        }
        if (!expanded && health != null && !health.ok && health.message != null) {
            Text(health.message!!, style = small(), color = tokens.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    buildList {
                        add("Package ${extension.pkgName}")
                        if (mangayomi && record != null) {
                            add("from ${record.indexAddress}")
                            add("installed ${formatDate(record.installedAt)}")
                        } else {
                            extension.storeName?.takeIf { it.isNotBlank() }?.let { add("from the store $it") }
                        }
                        if (extension.obsolete) add("no longer offered by any store")
                    }.joinToString("  ·  "),
                    style = small(), color = tokens.colors.textMuted,
                )
                if (sources.isEmpty()) Text("The manga server lists no source for it. Removing and installing it again usually helps; the server's log (Server tab) says why it did not load.", style = small(), color = tokens.colors.textSecondary)
                sources.forEach { source -> ExtensionSourceLine(source) }
            }
        }
    }
}

/** One source of an expanded extension card: its status, its own actions, its last test step by step. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtensionSourceLine(source: SourceEntry) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val actions = LocalMangaActions.current
    val testing = source.id in InstalledState.testing
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tokens.colors.background).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(source.displayName.ifBlank { source.name }, style = MaterialTheme.typography.bodyMedium, color = if (source.enabled) tokens.colors.textPrimary else tokens.colors.textMuted)
                (source.baseUrl ?: source.homeUrl)?.let { Text(it, style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (testing) CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else SourceStatusPill(source.health)
            MangaChip(if (source.enabled) "On" else "Off", source.enabled) { actions.io { manga.services.sources.setEnabled(manga.profileId, source.id, !source.enabled) } }
            if (source.configurable) MangaIconButton(Icons.Rounded.Tune, "Configure ${source.displayName}", { MangaPluginsState.configuring = source.id }, size = 32.dp)
            MangaIconButton(Icons.Rounded.PlayArrow, "Test ${source.displayName}", { testSources(manga, listOf(source.id)) }, size = 32.dp, enabled = !testing && source.enabled)
            MangaIconButton(Icons.Rounded.Explore, "Browse ${source.displayName}", { browseSource(manga, source.id) }, size = 32.dp, enabled = source.enabled)
        }
        val health = source.health
        if (health != null) SourceTestSteps(health, source.baseUrl ?: source.homeUrl)
        else Text("Not tested yet on this computer.", style = small(), color = tokens.colors.textMuted)
    }
}

/** Opens a source in Manga's Discover, leaving the settings where they were. */
internal fun browseSource(manga: MangaContext, sourceId: String) {
    DiscoverState.open(sourceId, "")
    manga.services.preferences.set(MangaPreferences.DISCOVER_SOURCE, sourceId)
    MangaNav.root(MangaRoute.Discover)
    MangaNav.settingsOpen = false
    com.debridone.app.shell.ShellState.select(com.debridone.app.shell.ShellSection.MANGA)
}

/** What the stores offer that is not installed, to install with one click. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AvailableTab() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val revision by manga.services.extensions.revision.collectAsState()
    val generation by manga.services.server.generation.collectAsState()
    val extensions by produceState<List<SwExtension>?>(null, revision, generation) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.extensions.list() }.getOrNull() }
    }
    val storeCount by produceState(0, revision, generation) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.extensions.stores().size }.getOrDefault(0) }
    }
    val offered = extensions.orEmpty().filter { !it.installed }
    val languages = offered.map { it.lang }.filter { it.isNotBlank() }.distinct().sortedBy(::languageName)
    val needle = MangaPluginsState.query.trim().lowercase()
    val shown = offered.filter { extension ->
        (needle.isEmpty() || extension.name.lowercase().contains(needle) || extension.pkgName.lowercase().contains(needle)) &&
            (MangaPluginsState.language == null || extension.lang == MangaPluginsState.language) &&
            (MangaPluginsState.showAdult || extension.contentWarning != "NSFW")
    }

    fun install(extension: SwExtension) {
        if (extension.pkgName in MangaPluginsState.working) return
        MangaPluginsState.working[extension.pkgName] = "Installing…"
        extensionScope.launch {
            try {
                manga.services.extensions.install(extension.pkgName) ?: error("The manga server did not install it.")
                MangaPluginsState.say("${extension.name} installed. Its sources are under Installed and Sources.")
            } catch (failure: Throwable) {
                MangaPluginsState.fail("${extension.name}: ${failure.message ?: "that did not work."}")
            } finally {
                MangaPluginsState.working.remove(extension.pkgName)
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 14.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Available", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                Text(
                    "What your ${plural(storeCount, "store")} offer and is not installed in this profile. Mangayomi indexes are opened under Import, where each source shows whether it can run here.",
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    if (offered.isNotEmpty()) MangaSearchField(MangaPluginsState.query, { MangaPluginsState.query = it }, Modifier.width(280.dp), placeholder = "Find an extension")
                    LanguagePicker(languages, MangaPluginsState.language) { MangaPluginsState.language = it }
                    MangaChip("Show 18+", MangaPluginsState.showAdult) { MangaPluginsState.showAdult = !MangaPluginsState.showAdult }
                    MangaButton(MangaPluginsState.progress ?: "Read the stores again", {
                        MangaPluginsState.progress = "Asking the stores for their lists…"
                        extensionScope.launch {
                            try {
                                val fresh = manga.services.extensions.list(refresh = true)
                                MangaPluginsState.say("${plural(fresh.count { !it.installed }, "extension")} available.")
                            } catch (failure: Throwable) {
                                MangaPluginsState.fail(failure.message)
                            } finally {
                                MangaPluginsState.progress = null
                            }
                        }
                    }, primary = false, icon = Icons.Rounded.Refresh, enabled = storeCount > 0 && MangaPluginsState.progress == null)
                    MangaButton("Add a store or index", { MangaPluginsState.show(MangaPluginsTab.IMPORT) }, primary = false, icon = Icons.Rounded.Add)
                }
                if (offered.isNotEmpty()) {
                    Text(
                        "${plural(shown.size, "extension")} shown of ${offered.size}" + if (!MangaPluginsState.showAdult) " · those their store marks as 18+ are hidden" else "",
                        style = small(), color = tokens.colors.textMuted,
                    )
                }
                PluginsMessagesBox()
                if (extensions != null && offered.isEmpty()) {
                    Text(
                        if (storeCount == 0) "No store yet: add one under Import." else "Everything your stores offer is installed.",
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted,
                    )
                }
            }
        }
        items(shown, key = { it.pkgName }) { extension ->
            val working = MangaPluginsState.working[extension.pkgName]
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ServerIcon(extension.iconUrl, Modifier.size(38.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(extension.name, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(languageName(extension.lang), "version ${extension.versionName}", extension.storeName.orEmpty()).filter { it.isNotBlank() }.joinToString("  ·  "),
                        style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (extension.contentWarning == "NSFW") StatusPill("18+", PillTone.WARNING)
                StatusPill(Support.DIRECT.label, PillTone.SUCCESS)
                if (working != null) {
                    CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text(working, style = small(), color = tokens.colors.textSecondary)
                } else {
                    MangaButton("Install", { install(extension) }, primary = false, icon = Icons.Rounded.Download)
                }
            }
        }
    }
}
