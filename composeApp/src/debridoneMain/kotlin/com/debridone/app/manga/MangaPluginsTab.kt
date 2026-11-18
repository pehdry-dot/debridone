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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.StatusPill
import com.debridone.manga.MangaAppSettings
import com.debridone.manga.MangaPreferences
import com.debridone.manga.suwayomi.MangaConnection
import com.debridone.manga.suwayomi.MangaServer
import com.debridone.manga.suwayomi.RemoteServer
import com.debridone.manga.suwayomi.SwExtension
import com.debridone.manga.suwayomi.SwStore
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class MangaPluginsTab(val label: String) {
    EXTENSIONS("Extensions"),
    STORES("Stores"),
    SOURCES("Sources"),
    TRACKERS("Trackers"),
    METADATA("Metadata"),
    SERVER("Server"),
}

/** State of Plugins › Manga that outlives leaving the tab: the sub-tab, what is being worked on, and the last outcome. */
internal object MangaPluginsState {
    var tab by mutableStateOf(MangaPluginsTab.EXTENSIONS)

    /** Package names of extensions being installed, updated or removed right now. */
    val working = mutableStateMapOf<String, String>()

    /** "Asking the stores for their lists…" while a long request runs. */
    var progress by mutableStateOf<String?>(null)
    var problem by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    /** What the extension list is narrowed to. Kept so that coming back shows the same list. */
    var query by mutableStateOf("")
    var language by mutableStateOf<String?>(null)
    var installedOnly by mutableStateOf(false)
    var showAdult by mutableStateOf(false)

    /** The source whose settings are open. */
    var configuring by mutableStateOf<String?>(null)

    /** A store address waiting for the person to confirm they have read what an extension may do. */
    var pendingStore by mutableStateOf<String?>(null)

    /** Goes to another of these pages, wherever they are shown: in Plugins › Manga or in Manga's settings panel. */
    fun show(target: MangaPluginsTab) {
        tab = target
        MangaNav.settingsSection = MangaSettingsSections.of(target)
    }

    fun say(message: String) {
        notice = message
        problem = null
    }

    fun fail(message: String?) {
        problem = message ?: "That did not work."
        notice = null
    }
}

/**
 * Plugins › Manga. Manga's sources are Mihon/Tachiyomi extensions, installed and run by the
 * profile's Suwayomi server from extension stores the person adds. This tab talks only to Manga's
 * own module; nothing here is shared with Streaming or Music.
 */
@Composable
fun MangaPluginsTab() {
    MangaProvider {
        val manga = LocalManga.current
        // The extensions live in the profile's manga server: looking at them needs it running.
        LaunchedEffect(manga.profileId) { manga.module.ensureServer() }
        val connection by manga.services.server.state.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(manga.style.background)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                MangaPluginsTab.entries.forEach { tab -> MangaChip(tab.label, MangaPluginsState.tab == tab) { MangaPluginsState.tab = tab } }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val needsServer = MangaPluginsState.tab != MangaPluginsTab.SERVER && MangaPluginsState.tab != MangaPluginsTab.METADATA
                if (needsServer && connection !is MangaConnection.Ready) {
                    MangaServerStatus(connection)
                } else when (MangaPluginsState.tab) {
                    MangaPluginsTab.EXTENSIONS -> ExtensionsTab()
                    MangaPluginsTab.STORES -> StoresTab()
                    MangaPluginsTab.SOURCES -> SourcesTab()
                    MangaPluginsTab.TRACKERS -> TrackersTab()
                    MangaPluginsTab.METADATA -> MetadataTab()
                    MangaPluginsTab.SERVER -> ServerTab()
                }
            }
        }
        // "Configure" opens here, in this tab, over the list it was clicked in.
        MangaSourceConfigurationPanel()
        }
        MangaPluginsState.pendingStore?.let { address -> ExtensionNoticeDialog(address) }
        MangaDialogs()
    }
}

/** Outlives the tab and its dialogs: an install that was started finishes, and says how it went, wherever the person went meanwhile. */
private val pluginScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

/** Runs something that talks to the manga server, off the UI thread, and reports how it went in the tab. */
private fun pluginWork(progress: String? = null, block: suspend () -> String?) {
    if (progress != null) MangaPluginsState.progress = progress
    pluginScope.launch {
        try {
            block()?.let(MangaPluginsState::say)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            MangaPluginsState.fail(failure.message)
        } finally {
            if (progress != null) MangaPluginsState.progress = null
        }
    }
}

@Composable
private fun PluginsMessages() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MangaPluginsState.progress?.let { text ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
            }
        }
        MangaPluginsState.problem?.let { Notice(it, PillTone.DANGER) }
        MangaPluginsState.notice?.let { Notice(it, PillTone.SUCCESS) }
    }
}

// ---------------------------------------------------------------------------- Extensions

/** What the server knows about extensions: the installed ones, and what the stores offer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExtensionsTab() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val revision by manga.services.extensions.revision.collectAsState()
    val generation by manga.services.server.generation.collectAsState()
    val extensions by produceState<List<SwExtension>?>(null, revision, generation) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.extensions.list() }.getOrNull() }
    }
    val stores by produceState<List<SwStore>>(emptyList(), revision, generation) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.extensions.stores() }.getOrDefault(emptyList()) }
    }
    val all = extensions.orEmpty()
    val installed = all.filter { it.installed }
    val outdated = installed.filter { it.hasUpdate }
    val languages = all.map { it.lang }.filter { it.isNotBlank() }.distinct().sortedBy(::languageName)
    val needle = MangaPluginsState.query.trim().lowercase()
    val shown = all.filter { extension ->
        (needle.isEmpty() || extension.name.lowercase().contains(needle) || extension.pkgName.lowercase().contains(needle)) &&
            (MangaPluginsState.language == null || extension.lang == MangaPluginsState.language) &&
            (!MangaPluginsState.installedOnly || extension.installed) &&
            (MangaPluginsState.showAdult || extension.installed || extension.contentWarning != "NSFW")
    }

    fun change(extension: SwExtension, doing: String, done: String, action: (String) -> Unit) {
        if (extension.pkgName in MangaPluginsState.working) return
        MangaPluginsState.working[extension.pkgName] = doing
        pluginScope.launch {
            try {
                action(extension.pkgName)
                MangaPluginsState.say("${extension.name} $done.")
            } catch (failure: Throwable) {
                MangaPluginsState.fail("${extension.name}: ${failure.message ?: "that did not work."}")
            } finally {
                MangaPluginsState.working.remove(extension.pkgName)
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 14.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Extensions", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                Text(
                    "A source is provided by an extension: the same extension packages Mihon and Tachiyomi use. The manga server installs them from the stores you added, or from a package file, and runs them itself.",
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MangaButton("Check the stores", {
                        pluginWork("Asking the stores for their lists…") {
                            val fresh = manga.services.extensions.list(refresh = true)
                            val updates = fresh.count { it.installed && it.hasUpdate }
                            "${plural(fresh.size, "extension")} listed" + if (updates > 0) ", ${plural(updates, "update")} available." else "."
                        }
                    }, primary = false, icon = Icons.Rounded.Refresh, enabled = stores.isNotEmpty() && MangaPluginsState.progress == null)
                    if (outdated.isNotEmpty()) {
                        MangaButton("Update all (${outdated.size})", {
                            pluginWork("Updating ${plural(outdated.size, "extension")}…") { "${plural(manga.services.extensions.updateAll(), "extension")} updated." }
                        }, icon = Icons.Rounded.SystemUpdateAlt, enabled = MangaPluginsState.progress == null)
                    }
                    MangaButton("Install from a file…", {
                        MangaPickers.choosePackage()?.let { file ->
                            pluginWork("Installing ${file.fileName}…") { "${manga.services.extensions.installFile(file).name} installed from ${file.fileName}." }
                        }
                    }, primary = false, icon = Icons.Rounded.InsertDriveFile, enabled = MangaPluginsState.progress == null)
                    MangaButton("Stores (${stores.size})", { MangaPluginsState.show(MangaPluginsTab.STORES) }, primary = false, icon = Icons.Rounded.Storefront)
                }
                PluginsMessages()
                if (extensions != null && stores.isEmpty() && installed.isEmpty()) {
                    MangaEmptyState(
                        Icons.Rounded.Extension, "No extension store yet",
                        "DebridOne comes with no store and no sources of its own. Add the address of an extension store you trust, then install the extensions you want from it. Your own files need none of this: add them from Manga's Library.",
                    ) {
                        MangaButton("Add a store", { MangaPluginsState.show(MangaPluginsTab.STORES) }, icon = Icons.Rounded.Storefront)
                    }
                }
                if (all.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MangaSearchField(MangaPluginsState.query, { MangaPluginsState.query = it }, Modifier.width(300.dp), placeholder = "Find an extension")
                        MangaChip("Installed (${installed.size})", MangaPluginsState.installedOnly) { MangaPluginsState.installedOnly = !MangaPluginsState.installedOnly }
                        LanguagePicker(languages, MangaPluginsState.language) { MangaPluginsState.language = it }
                        MangaChip("Show 18+", MangaPluginsState.showAdult) { MangaPluginsState.showAdult = !MangaPluginsState.showAdult }
                    }
                    Text(
                        "${plural(shown.size, "extension")} shown of ${all.size}" + if (!MangaPluginsState.showAdult) " · extensions their store marks as 18+ are hidden" else "",
                        style = small(), color = tokens.colors.textMuted,
                    )
                }
            }
        }
        items(shown, key = { it.pkgName }) { extension ->
            ExtensionRow(
                extension, MangaPluginsState.working[extension.pkgName],
                onInstall = { change(extension, "Installing…", "installed") { manga.services.extensions.install(it) } },
                onUpdate = { change(extension, "Updating…", "updated") { manga.services.extensions.update(it) } },
                onRemove = { change(extension, "Removing…", "removed") { manga.services.extensions.uninstall(it) } },
            )
        }
    }
}

@Composable
private fun ExtensionRow(extension: SwExtension, working: String?, onInstall: () -> Unit, onUpdate: () -> Unit, onRemove: () -> Unit) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ServerIcon(extension.iconUrl, Modifier.size(40.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(extension.name, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(languageName(extension.lang), "version ${extension.versionName}", extension.storeName.orEmpty(), extension.pkgName).filter { it.isNotBlank() }.joinToString("  ·  "),
                style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (extension.contentWarning == "NSFW") StatusPill("18+", PillTone.WARNING)
        if (extension.obsolete) StatusPill("No longer in a store", PillTone.WARNING)
        when {
            working != null -> {
                CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(working, style = small(), color = tokens.colors.textSecondary)
            }
            extension.installed -> {
                if (extension.hasUpdate) MangaButton("Update", onUpdate, icon = Icons.Rounded.SystemUpdateAlt) else StatusPill("Installed", PillTone.SUCCESS)
                MangaIconButton(Icons.Rounded.Delete, "Remove ${extension.name}", onRemove, size = 34.dp)
            }
            else -> MangaButton("Install", onInstall, primary = false, icon = Icons.Rounded.Download)
        }
    }
}

// ---------------------------------------------------------------------------- Stores

private const val EXTENSION_NOTICE =
    "An extension is a small program written by someone else. The manga server runs it with your own rights on this computer: " +
        "it can reach the internet and read and write your files. It runs inside the manga server, never inside DebridOne itself, " +
        "and neither DebridOne nor Suwayomi reviews what a store offers. Add only stores you trust."

/** Extension stores: addresses the person adds. None is shipped or suggested. */
@Composable
internal fun StoresTab() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val revision by manga.services.extensions.revision.collectAsState()
    val generation by manga.services.server.generation.collectAsState()
    val stores by produceState<List<SwStore>?>(null, revision, generation) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.extensions.stores() }.getOrNull() }
    }
    val settings by manga.services.appSettings.values.collectAsState()
    var address by remember { mutableStateOf("") }
    // The store was added (also when that went through the notice): the field starts empty again.
    LaunchedEffect(stores?.size) { if (stores?.any { it.indexUrl == address.trim() } == true) address = "" }

    fun add() {
        val wanted = address.trim()
        if (wanted.isEmpty() || MangaPluginsState.progress != null) return
        if (settings[MangaAppSettings.EXTENSION_NOTICE_SEEN] != "true") {
            MangaPluginsState.pendingStore = wanted
        } else {
            addStore(manga, wanted) { address = "" }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 14.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Extension stores", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                Text(
                    "A store is a list of extensions published at a web address. The manga server reads the store formats Suwayomi supports: the current one, and older \"index.min.json\" repositories. " +
                        "DebridOne includes no store: you add the address of one you trust, for this profile.",
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                )
                Notice(EXTENSION_NOTICE, PillTone.WARNING)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaTextField(address, { address = it }, "Address of the store's index", Modifier.weight(1f), onSubmit = ::add, hint = "It starts with https:// and usually ends in repo.json, index.pb or index.min.json.")
                    MangaButton("Add store", ::add, icon = Icons.Rounded.Add, enabled = address.isNotBlank() && MangaPluginsState.progress == null)
                }
                PluginsMessages()
            }
        }
        val list = stores
        if (list != null && list.isEmpty()) {
            item { Text("No store has been added for this profile.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted) }
        }
        items(list.orEmpty(), key = { it.indexUrl }) { store ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Storefront, contentDescription = null, tint = manga.style.accent, modifier = Modifier.size(26.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(store.name.ifBlank { "Unnamed store" }, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
                    SelectionContainer { Text(store.indexUrl, style = small(), color = tokens.colors.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (store.signingKey.isNotBlank()) Text("Signing key ${store.signingKey.take(24)}…", style = small(), color = tokens.colors.textMuted, maxLines = 1)
                }
                if (store.legacy) StatusPill("Older format") else StatusPill("Current format", PillTone.SUCCESS)
                store.website?.let { site -> MangaButton("Its site", { openInBrowser(site) }, primary = false, icon = Icons.Rounded.OpenInNew) }
                MangaButton("Remove", {
                    pluginWork("Removing ${store.name}…") {
                        manga.services.extensions.removeStore(store.indexUrl)
                        "${store.name.ifBlank { "The store" }} was removed. Extensions installed from it stay until you remove them."
                    }
                }, primary = false, danger = true, icon = Icons.Rounded.Delete, enabled = MangaPluginsState.progress == null)
            }
        }
    }
}

private fun addStore(manga: MangaContext, address: String, onAdded: () -> Unit) {
    pluginWork("Adding the store and reading its list…") {
        val store = manga.services.extensions.addStore(address)
        val offered = runCatching { manga.services.extensions.list().count { it.storeIndexUrl == store.indexUrl } }.getOrDefault(0)
        withContext(Dispatchers.Main) { onAdded() }
        "${store.name.ifBlank { "The store" }} was added" + if (offered > 0) ": ${plural(offered, "extension")} to choose from in Extensions." else "."
    }
}

/** Shown once, before the first store is added: what an extension is allowed to do. */
@Composable
internal fun ExtensionNoticeDialog(address: String) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val dismiss = { MangaPluginsState.pendingStore = null }
    MangaDialogFrame("Before you add a store", dismiss, width = 560) {
        Text(EXTENSION_NOTICE, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        Text(
            "Earlier versions of DebridOne ran Houdoku's extensions in a restricted helper. Mihon/Tachiyomi extensions cannot be restricted that way: this is how Suwayomi runs them.",
            style = small(), color = tokens.colors.textMuted,
        )
        SelectionContainer { Text(address, style = small().copy(fontFamily = FontFamily.Monospace), color = tokens.colors.textPrimary) }
        MangaDialogButtons("I understand, add the store", dismiss) {
            manga.services.appSettings.set(MangaAppSettings.EXTENSION_NOTICE_SEEN, "true")
            dismiss()
            addStore(manga, address) {}
        }
    }
}

// ---------------------------------------------------------------------------- Server

/** The profile's manga server: what it is, how it is doing, and — instead of it — a server of the person's own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServerTab() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val connection by manga.services.server.state.collectAsStateWithLifecycle()
    val preferences by manga.services.preferences.values.collectAsState()
    val remote = preferences[MangaPreferences.SERVER_MODE] == MangaPreferences.SERVER_MODE_REMOTE
    var url by remember(manga.profileId) { mutableStateOf(preferences[MangaPreferences.SERVER_URL].orEmpty()) }
    var username by remember(manga.profileId) { mutableStateOf(preferences[MangaPreferences.SERVER_USERNAME].orEmpty()) }
    var password by remember(manga.profileId) { mutableStateOf("") }
    val hasPassword = remember(manga.profileId, connection) { manga.module.hasRemotePassword(manga.profileId) }
    var timeout by remember(manga.profileId) { mutableStateOf(preferences[MangaPreferences.SERVER_TIMEOUT] ?: "60") }
    var requireHttps by remember(manga.profileId) { mutableStateOf(preferences[MangaPreferences.SERVER_REQUIRE_HTTPS] != "false") }
    var testing by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var showLog by remember { mutableStateOf(false) }
    val runtimeProblem = remember { manga.module.runtimeProblem() }

    fun entered() = RemoteServer(
        url = url, username = username.takeIf { it.isNotBlank() },
        password = password.ifEmpty { manga.module.remoteServerOf(manga.profileId)?.password },
        timeoutSeconds = timeout.toIntOrNull() ?: 60, requireHttps = requireHttps,
    )

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 14.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Manga server", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
            Text(
                "Manga's library, sources, downloads and trackers are kept by Suwayomi-Server. DebridOne starts one for each profile, on this computer only, when Manga is first opened; the screens and the reader are DebridOne's own.",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
        }
        item {
            SettingsBlock("Now") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (val state = connection) {
                        is MangaConnection.Ready -> {
                            StatusPill("Running", PillTone.SUCCESS)
                            Text(
                                (if (state.remote) "Your own server at ${state.address}" else "This profile's server, on this computer only") + " · Suwayomi-Server ${state.version}",
                                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary,
                            )
                        }
                        is MangaConnection.Connecting -> {
                            CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Text(if (state.remote) "Connecting…" else "Starting…", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                        }
                        is MangaConnection.Failed -> {
                            StatusPill(if (state.retrying) "Not reachable, trying again" else "Not running", PillTone.DANGER)
                            Text(state.message, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary, modifier = Modifier.weight(1f))
                        }
                        MangaConnection.Idle -> {
                            StatusPill("Not started")
                            Text("It starts when Manga is opened.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                        }
                    }
                }
                if (runtimeProblem != null && !remote) Notice(runtimeProblem, PillTone.DANGER)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MangaButton(if (connection is MangaConnection.Ready) "Restart" else "Start", { manga.services.server.reconnect() }, primary = connection !is MangaConnection.Ready, icon = Icons.Rounded.RestartAlt)
                    if (!remote) {
                        MangaButton(if (showLog) "Hide the server's log" else "Show the server's log", { showLog = !showLog }, primary = false)
                        MangaButton("Show its folder", { revealInFileManager(manga.services.server.manager.directory(manga.profileId)) }, primary = false, icon = Icons.Rounded.FolderOpen)
                    }
                }
                if (showLog && !remote) {
                    val lines = remember(connection, showLog) { manga.services.server.manager.log().takeLast(120) }
                    SelectionContainer {
                        Text(
                            lines.joinToString("\n").ifBlank { "Nothing was printed yet." },
                            style = small().copy(fontFamily = FontFamily.Monospace), color = tokens.colors.textMuted,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).background(tokens.colors.background).verticalScroll(rememberScrollState()).padding(12.dp),
                        )
                    }
                }
                if (!remote) {
                    Text(
                        "Only this computer can reach it, and only with a password DebridOne makes anew at every start. Each profile has its own server folder, so profiles never share a library.",
                        style = small(), color = tokens.colors.textMuted,
                    )
                }
            }
        }
        item {
            SettingsBlock(
                "Which server this profile reads from",
                "By default, the one DebridOne runs. If you already run a Suwayomi server (at home, on a NAS), this profile can use that instead: its library is then the server's, shared with whatever else uses it, " +
                    "and files of this computer cannot be opened through it.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MangaChip("DebridOne's own", !remote) {
                        manga.services.preferences.set(MangaPreferences.SERVER_MODE, null)
                        outcome = null
                        manga.services.server.reconnect()
                    }
                    MangaChip("A server of my own", remote) { manga.services.preferences.set(MangaPreferences.SERVER_MODE, MangaPreferences.SERVER_MODE_REMOTE) }
                }
                if (remote) {
                    MangaTextField(url, { url = it }, "Address", Modifier.widthIn(max = 620.dp).fillMaxWidth(), hint = "For example https://manga.example.org or http://192.168.1.20:4567")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(max = 620.dp)) {
                        MangaTextField(username, { username = it }, "User name (if the server asks for one)", Modifier.weight(1f))
                        MangaTextField(
                            password, { password = it }, if (hasPassword) "Password (saved; type to replace it)" else "Password", Modifier.weight(1f), secret = true,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                        MangaTextField(timeout, { timeout = it.filter(Char::isDigit).take(3) }, "Seconds to wait for an answer", Modifier.width(220.dp))
                        MangaChip("Require a secure connection (https)", requireHttps) { requireHttps = !requireHttps }
                    }
                    Text(
                        "The password is kept in the system's credential store, for this profile. Servers that use Suwayomi's \"basic\" sign-in, or none, are supported.",
                        style = small(), color = tokens.colors.textMuted,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MangaButton("Test the connection", {
                            testing = true
                            outcome = null
                            val candidate = entered()
                            scope.launch(Dispatchers.IO) {
                                outcome = try {
                                    true to "It answers: Suwayomi-Server ${MangaServer.test(candidate)}."
                                } catch (failure: Exception) {
                                    false to (failure.message ?: "The server could not be reached.")
                                } finally {
                                    testing = false
                                }
                            }
                        }, primary = false, enabled = url.isNotBlank() && !testing)
                        MangaButton("Save and connect", {
                            manga.services.preferences.set(MangaPreferences.SERVER_URL, url.trim())
                            manga.services.preferences.set(MangaPreferences.SERVER_USERNAME, username.trim().ifEmpty { null })
                            manga.services.preferences.set(MangaPreferences.SERVER_TIMEOUT, timeout.ifBlank { null })
                            manga.services.preferences.set(MangaPreferences.SERVER_REQUIRE_HTTPS, if (requireHttps) null else "false")
                            if (password.isNotEmpty()) manga.module.setRemotePassword(manga.profileId, password)
                            if (username.isBlank()) manga.module.setRemotePassword(manga.profileId, null)
                            password = ""
                            outcome = null
                            manga.services.server.reconnect()
                        }, enabled = url.isNotBlank())
                        if (testing) CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    }
                    outcome?.let { (ok, text) -> Notice(text, if (ok) PillTone.SUCCESS else PillTone.DANGER) }
                }
            }
        }
        // The server's own settings can only be shown, and changed, while it answers.
        if (connection is MangaConnection.Ready) {
            item { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { ServerOptionsBlocks(remote = (connection as MangaConnection.Ready).remote) } }
        }
    }
}

/** Shows a folder in Finder (or the file manager of the system). */
internal fun revealInFileManager(folder: java.nio.file.Path) {
    runCatching {
        java.nio.file.Files.createDirectories(folder)
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) java.awt.Desktop.getDesktop().open(folder.toFile())
    }
}
