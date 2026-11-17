package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.StatusPill
import com.debridone.manga.MangaAppSettings
import com.debridone.manga.host.HostPluginState
import com.debridone.manga.plugins.AvailablePackage
import com.debridone.manga.plugins.CompatibilityLevel
import com.debridone.manga.plugins.PackageKind
import com.debridone.manga.plugins.PackageStatus
import com.debridone.manga.plugins.PluginRules
import com.debridone.manga.plugins.PreparedInstall
import com.debridone.module.InstalledPlugin
import com.debridone.module.PluginActionResult
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class MangaPluginsTab(val label: String) {
    INSTALLED("Installed"),
    BROWSE("Browse"),
    SOURCES("Sources"),
    METADATA("Metadata"),
    SETTINGS("Settings"),
}

/** State of Plugins › Manga that outlives leaving the tab: the sub-tab, and an install in progress. */
internal object MangaPluginsState {
    var tab by mutableStateOf(MangaPluginsTab.INSTALLED)

    /** "Downloading and checking @tiyo/core 1.0.3 (12 of 79)". */
    var progress by mutableStateOf<String?>(null)

    /** A package that has been checked and is waiting for the person's decision. */
    var review by mutableStateOf<PreparedInstall?>(null)
    var problem by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    /** Bumped after an install, update or removal so the lists are read again. */
    var changes by mutableStateOf(0)
}

/**
 * Plugins › Manga. Manga's extensions are Houdoku's: one package, Tiyo, that brings the sources.
 * This tab talks only to Manga's own module; nothing here is shared with Streaming or Music.
 */
@Composable
fun MangaPluginsTab() {
    MangaProvider {
        val manga = LocalManga.current
        Column(Modifier.fillMaxSize().background(manga.style.background)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                MangaPluginsTab.entries.forEach { tab -> MangaChip(tab.label, MangaPluginsState.tab == tab) { MangaPluginsState.tab = tab } }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (MangaPluginsState.tab) {
                    MangaPluginsTab.INSTALLED -> InstalledTab()
                    MangaPluginsTab.BROWSE -> BrowseTab()
                    MangaPluginsTab.SOURCES -> SourcesTab()
                    MangaPluginsTab.METADATA -> MetadataTab()
                    MangaPluginsTab.SETTINGS -> HostSettingsTab()
                }
            }
        }
        MangaPluginsState.review?.let { InstallReviewDialog(it) }
        MangaDialogs()
    }
}

/** Starts an install: download, verify, trial load. Ends with the review dialog, or with the reason it was refused. */
internal fun beginInstall(manga: MangaContext, scope: kotlinx.coroutines.CoroutineScope, name: String, version: String) {
    if (MangaPluginsState.progress != null) return
    MangaPluginsState.problem = null
    MangaPluginsState.notice = null
    MangaPluginsState.progress = "Downloading and checking $name $version…"
    scope.launch(Dispatchers.IO) {
        try {
            MangaPluginsState.review = manga.services.plugins.prepare(name, version) { done, total ->
                MangaPluginsState.progress = "Downloading and checking $name $version ($done of $total packages)…"
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            MangaPluginsState.problem = failure.message ?: "$name could not be installed."
        } finally {
            MangaPluginsState.progress = null
        }
    }
}

private fun beginImport(manga: MangaContext, scope: kotlinx.coroutines.CoroutineScope) {
    val file = MangaPickers.choosePackage() ?: return
    if (MangaPluginsState.progress != null) return
    MangaPluginsState.problem = null
    MangaPluginsState.notice = null
    MangaPluginsState.progress = "Checking ${file.fileName}…"
    scope.launch(Dispatchers.IO) {
        try {
            MangaPluginsState.review = manga.services.plugins.prepareFromFile(file) { done, total ->
                MangaPluginsState.progress = "Checking ${file.fileName} ($done of $total packages)…"
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            MangaPluginsState.problem = failure.message ?: "${file.fileName} could not be imported."
        } finally {
            MangaPluginsState.progress = null
        }
    }
}

@Composable
private fun PluginsMessages() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    MangaPluginsState.progress?.let { text ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        }
    }
    MangaPluginsState.problem?.let { Notice(it, PillTone.DANGER) }
    MangaPluginsState.notice?.let { Notice(it, PillTone.SUCCESS) }
    manga.module.runtimeProblem()?.let { Notice(it, PillTone.WARNING) }
}

private fun compatibilityTone(level: CompatibilityLevel): PillTone = when (level) {
    CompatibilityLevel.VERIFIED -> PillTone.SUCCESS
    CompatibilityLevel.UNTESTED -> PillTone.WARNING
    CompatibilityLevel.UNSUPPORTED -> PillTone.DANGER
}

private fun compatibilityLabel(level: CompatibilityLevel): String = when (level) {
    CompatibilityLevel.VERIFIED -> "Verified"
    CompatibilityLevel.UNTESTED -> "Not tested"
    CompatibilityLevel.UNSUPPORTED -> "Not supported"
}

private fun displayName(name: String): String = when (PluginRules.kindOf(name)) {
    PackageKind.TIYO -> "Tiyo"
    PackageKind.LEGACY -> name.substringAfter("extension-").replaceFirstChar(Char::uppercase) + " (legacy)"
    null -> name
}

// ---------------------------------------------------------------------------- Installed

@Composable
private fun InstalledTab() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val registry by manga.module.plugins.installed.collectAsStateWithLifecycle()
    val hostState by manga.services.extensions.state.collectAsStateWithLifecycle()
    val disabled = registry.filter { !it.enabled }.map { it.id }.toSet()
    val packages by produceState<List<PackageStatus>?>(null, hostState, registry, MangaPluginsState.changes) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.plugins.installed(disabled) }.getOrDefault(emptyList()) }
    }
    var updates by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var checking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    fun checkUpdates(announce: Boolean) {
        if (checking) return
        checking = true
        scope.launch(Dispatchers.IO) {
            updates = runCatching { manga.services.plugins.updates() }.getOrElse {
                if (announce) MangaPluginsState.problem = "The registry could not be asked for updates: ${it.message}"
                emptyMap()
            }
            if (announce && updates.isEmpty() && MangaPluginsState.problem == null) MangaPluginsState.notice = "Everything is up to date."
            checking = false
        }
    }
    LaunchedEffect(packages?.size) { if (!packages.isNullOrEmpty()) checkUpdates(announce = false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Installed", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                    Text("Extension packages in use by Manga. They are shared by every profile; each profile chooses its sources and keeps its own logins.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                }
                MangaButton(if (checking) "Checking…" else "Check for updates", { checkUpdates(announce = true) }, primary = false, icon = Icons.Rounded.Update, enabled = !checking && !packages.isNullOrEmpty())
            }
        }
        item { PluginsMessages() }
        items(packages.orEmpty(), key = { it.name }) { pkg ->
            val entry = registry.firstOrNull { it.id == pkg.name }
            PackageCard(pkg, entry, updates[pkg.name], onRemove = { removing = pkg.name })
        }
        item {
            // The built-in source: always there, nothing to install.
            val local = registry.firstOrNull { it.id == "local-files" }
            PluginCardFrame {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(local?.name ?: "Local files", style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                        Text(local?.description ?: "CBZ and ZIP archives and image folders on this computer.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                    }
                    StatusPill("Built in", PillTone.SUCCESS)
                }
            }
        }
        if (packages?.isEmpty() == true) {
            item {
                MangaEmptyState(
                    Icons.Rounded.Extension, "No extension package installed",
                    "Online sources come from Tiyo, the extension package of the Houdoku manga reader. Local files work without it.",
                ) { MangaButton("Browse", { MangaPluginsState.tab = MangaPluginsTab.BROWSE }, icon = Icons.Rounded.Extension) }
            }
        }
    }

    removing?.let { name ->
        MangaDialogFrame("Uninstall ${displayName(name)}?", { removing = null }, width = 460) {
            Text(
                "Its sources stop being available. Series you added from them stay in your library with their progress, and downloaded chapters stay readable; the rest needs the package again.",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
            MangaDialogButtons("Uninstall", { removing = null }, danger = true) {
                removing = null
                scope.launch(Dispatchers.IO) {
                    when (val result = manga.module.plugins.remove(name)) {
                        is PluginActionResult.Failure -> MangaPluginsState.problem = result.message
                        else -> MangaPluginsState.notice = "${displayName(name)} was uninstalled."
                    }
                    MangaPluginsState.changes++
                }
            }
        }
    }
}

@Composable
private fun PluginCardFrame(content: @Composable () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(18.dp)).padding(20.dp)) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackageCard(pkg: PackageStatus, entry: InstalledPlugin?, update: String?, onRemove: () -> Unit) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val enabled = entry?.enabled ?: true
    PluginCardFrame {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${displayName(pkg.name)}  ${pkg.version}", style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                    Text(pkg.name + "  ·  from the npm registry", style = small(), color = tokens.colors.textMuted)
                }
                when {
                    !enabled -> StatusPill("Switched off")
                    pkg.error != null -> StatusPill("Failed to load", PillTone.DANGER)
                    pkg.loaded -> StatusPill("Running", PillTone.SUCCESS)
                    else -> StatusPill("Not loaded", PillTone.WARNING)
                }
                StatusPill(compatibilityLabel(pkg.compatibility.level), compatibilityTone(pkg.compatibility.level))
            }
            Text(
                when (pkg.kind) {
                    PackageKind.TIYO -> "The extension package of the Houdoku manga reader (MIT licence). It runs unmodified in DebridOne's extension host."
                    PackageKind.LEGACY -> "A source in Houdoku's older format, run through an adapter."
                    null -> "Not a Houdoku extension package; it is ignored."
                } + if (pkg.loaded) "  ${plural(pkg.sourceCount, "source")}." else "",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
            Text(pkg.compatibility.explanation, style = small(), color = tokens.colors.textMuted)
            pkg.error?.let { Notice("It could not be loaded: $it", PillTone.DANGER) }
            pkg.shadowedBy?.let { Text("Its source is also in ${displayName(it)}, whose copy is the one in use.", style = small(), color = tokens.colors.textMuted) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (update != null) {
                    MangaButton("Update to $update", { beginInstall(manga, scope, pkg.name, update) }, icon = Icons.Rounded.Update, enabled = MangaPluginsState.progress == null)
                }
                MangaChip(if (enabled) "Enabled" else "Disabled", enabled) {
                    scope.launch(Dispatchers.IO) {
                        val result = manga.module.plugins.setEnabled(pkg.name, !enabled)
                        if (result is PluginActionResult.Failure) MangaPluginsState.problem = result.message
                    }
                }
                MangaButton("Configure sources", { MangaPluginsState.tab = MangaPluginsTab.SOURCES }, primary = false, icon = Icons.Rounded.Tune, enabled = pkg.loaded)
                MangaButton("Uninstall", onRemove, primary = false, danger = true, icon = Icons.Rounded.Delete)
            }
        }
    }
}

// ---------------------------------------------------------------------------- Browse

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseTab() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val app by manga.services.appSettings.values.collectAsState()
    val showLegacy = app[MangaAppSettings.SHOW_LEGACY] == "true"
    var reload by remember { mutableStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    val offered by produceState<List<AvailablePackage>?>(null, showLegacy, reload, MangaPluginsState.changes) {
        value = null
        failure = null
        value = withContext(Dispatchers.IO) {
            runCatching { manga.services.plugins.available(showLegacy) }.getOrElse {
                failure = "The package registry could not be reached: ${it.message ?: "network error"}"
                emptyList()
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Browse", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                    Text(
                        "What the npm registry lists for Houdoku. Current Houdoku loads one package, Tiyo, which contains all of its sources. Nothing here is made up: if the registry cannot be reached, the list is empty.",
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                    )
                }
                MangaIconButton(Icons.Rounded.Refresh, "Ask the registry again", { reload++ })
            }
        }
        item { PluginsMessages() }
        failure?.let { text -> item { Notice(text, PillTone.WARNING) } }
        if (offered == null) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp)) } }
        }
        items(offered.orEmpty().filter { it.kind == PackageKind.TIYO }, key = { it.name }) { pkg -> AvailableCard(pkg) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                MangaButton("Import a package file…", { beginImport(manga, scope) }, primary = false, icon = Icons.Rounded.InsertDriveFile, enabled = MangaPluginsState.progress == null)
                MangaChip("Show older Houdoku extensions", showLegacy) { manga.services.appSettings.set(MangaAppSettings.SHOW_LEGACY, (!showLegacy).toString()) }
            }
            Text(
                "A package file is an npm archive (.tgz) of a published Houdoku extension package. It goes through the same checks and review as an install from the registry.",
                style = small(), color = tokens.colors.textMuted, modifier = Modifier.padding(top = 8.dp),
            )
        }
        val legacy = offered.orEmpty().filter { it.kind == PackageKind.LEGACY }
        if (showLegacy && offered != null) {
            item {
                Notice(
                    "These are Houdoku's pre-Tiyo extensions: one source per package, last published in 2022–2023. Current Houdoku no longer loads them. " +
                        "DebridOne runs them through an adapter; one of them (Komga) was tested, the others were not, and many of their sites have changed since.",
                    PillTone.WARNING,
                )
            }
            if (legacy.isEmpty() && failure == null) item { Text("The registry lists none.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted) }
        }
        items(legacy, key = { it.name }) { pkg -> AvailableCard(pkg) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AvailableCard(pkg: AvailablePackage) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var version by remember(pkg.name, pkg.latest) { mutableStateOf(pkg.latest ?: pkg.versions.firstOrNull()?.version.orEmpty()) }
    var versionMenu by remember { mutableStateOf(false) }
    val compatibility = PluginRules.compatibility(pkg.name, version)
    val published = pkg.versions.firstOrNull { it.version == version }?.publishedAt?.take(10)
    PluginCardFrame {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(displayName(pkg.name), style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                    Text(listOfNotNull(pkg.name, pkg.license?.let { "$it licence" }, published?.let { "published $it" }).joinToString("  ·  "), style = small(), color = tokens.colors.textMuted)
                }
                pkg.installedVersion?.let { StatusPill("Installed: $it", PillTone.SUCCESS) }
                StatusPill(compatibilityLabel(compatibility.level), compatibilityTone(compatibility.level))
            }
            Text(
                // Tiyo's registry entry carries no description of its own (only a build-tool note), so it is described here.
                if (pkg.kind == PackageKind.TIYO) "The extension package of the Houdoku manga reader: its manga sources, as one package. Published by Houdoku's author under the MIT licence."
                else pkg.description?.takeIf { it.isNotBlank() } ?: "A Houdoku source in the older format.",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
            Text(compatibility.explanation, style = small(), color = tokens.colors.textMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val isInstalled = pkg.installedVersion == version
                MangaButton(
                    when {
                        isInstalled -> "Installed"
                        pkg.installedVersion == null -> "Install $version"
                        PluginRules.compareVersions(version, pkg.installedVersion!!) > 0 -> "Update to $version"
                        else -> "Install $version instead"
                    },
                    { beginInstall(manga, scope, pkg.name, version) },
                    icon = Icons.Rounded.Download,
                    enabled = !isInstalled && version.isNotBlank() && compatibility.level != CompatibilityLevel.UNSUPPORTED && MangaPluginsState.progress == null,
                )
                if (pkg.versions.size > 1) {
                    Box {
                        MangaChip("Version $version", false) { versionMenu = true }
                        DropdownMenu(versionMenu, { versionMenu = false }) {
                            pkg.versions.take(12).forEach { option ->
                                val level = PluginRules.compatibility(pkg.name, option.version).level
                                MangaMenuItem("${option.version}  ·  ${compatibilityLabel(level).lowercase()}", checked = option.version == version) {
                                    version = option.version
                                    versionMenu = false
                                }
                            }
                        }
                    }
                }
                (pkg.homepage ?: "https://www.npmjs.com/package/${pkg.name}").let { url ->
                    MangaButton("Its page", { openInBrowser(url) }, primary = false, icon = Icons.Rounded.OpenInNew)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- review before installing

/**
 * The last step before a package is installed: what it is, what its code will and will not be able
 * to do, the sources it brings and the sites they talk to. Nothing has been installed at this point.
 */
@Composable
private fun InstallReviewDialog(prepared: PreparedInstall) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var installing by remember { mutableStateOf(false) }
    fun decline() {
        if (installing) return
        MangaPluginsState.review = null
        scope.launch(Dispatchers.IO) { runCatching { manga.services.plugins.cancel(prepared) } }
    }
    MangaDialogFrame(
        (if (prepared.replacesVersion != null) "Update" else "Install") + " ${displayName(prepared.name)} ${prepared.version}?",
        ::decline, width = 640,
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(compatibilityLabel(prepared.compatibility.level), compatibilityTone(prepared.compatibility.level))
                Text(
                    "${prepared.name}  ·  ${plural(prepared.staged.packages, "package")}, ${formatBytes(prepared.staged.bytes)}, every archive matched its checksum" +
                        (prepared.replacesVersion?.let { "  ·  replaces $it" } ?: ""),
                    style = small(), color = tokens.colors.textMuted,
                )
            }
            Text("What this package is", style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
            Text(
                "Program code written by other people, not by DebridOne. It was loaded once on trial in a separate helper process and registered ${plural(prepared.sources.size, "source")}.",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
            Text("What its code can do", style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
            Text(
                "• Make network requests — to the sites of its sources (listed below), and technically to any address.\n" +
                    "• See what you search for and which series and chapters you open in its sources, and the settings you enter for them (such as a server login).\n" +
                    "• Hand page images to DebridOne through one hand-over folder.",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
            Text("What it cannot do", style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
            Text(
                "• Read or change your files: it can only read its own folder.\n" +
                    "• Reach your DebridOne account, your library database, Streaming or Music, or anything in the Keychain.\n" +
                    "• Start other programs, or keep running after DebridOne quits.\n" +
                    "• Take the app down: if it crashes, only the extension helper restarts.",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
            prepared.warnings.forEach { Notice(it, PillTone.WARNING) }
            Text("${plural(prepared.sources.size, "source")} in this package", style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
            Column(
                Modifier.fillMaxWidth().heightIn(max = 190.dp).clip(RoundedCornerShape(12.dp)).background(tokens.colors.background).padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                prepared.sources.forEach { source ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(source.name, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, modifier = Modifier.weight(0.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(languageName(source.language), style = small(), color = tokens.colors.textMuted, modifier = Modifier.weight(0.25f), maxLines = 1)
                        Text(source.url.removePrefix("https://").removePrefix("http://"), style = small(), color = tokens.colors.textMuted, modifier = Modifier.weight(0.35f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text(
                "DebridOne has not checked what these sites offer or whether you may read it where you live; that is between you and each source. Sources can be switched off one by one after installing.",
                style = small(), color = tokens.colors.textMuted,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (installing) CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp).padding(end = 0.dp))
            MangaButton("Don't install", ::decline, primary = false, enabled = !installing, modifier = Modifier.padding(start = 10.dp))
            MangaButton(if (prepared.replacesVersion != null) "Update" else "Install", {
                installing = true
                scope.launch(Dispatchers.IO) {
                    when (val result = manga.module.confirmInstall(prepared)) {
                        is PluginActionResult.Failure -> MangaPluginsState.problem = result.message
                        else -> {
                            MangaPluginsState.notice = "${displayName(prepared.name)} ${prepared.version} is installed: ${plural(prepared.sources.size, "source")} available."
                            MangaPluginsState.tab = MangaPluginsTab.SOURCES
                        }
                    }
                    MangaPluginsState.changes++
                    MangaPluginsState.review = null
                    installing = false
                }
            }, enabled = !installing, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/** For screens outside this file: what the running host has loaded. */
@Composable
internal fun rememberHostState(): HostPluginState? {
    val manga = LocalManga.current
    val state by manga.services.extensions.state.collectAsStateWithLifecycle()
    return state
}
