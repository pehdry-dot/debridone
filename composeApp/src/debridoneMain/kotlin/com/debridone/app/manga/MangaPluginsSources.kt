package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.StatusPill
import com.debridone.manga.MangaAppSettings
import com.debridone.manga.MangaPreferences
import com.debridone.manga.host.HostState
import com.debridone.manga.sources.SourceEntry
import com.debridone.module.MetadataProvider
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------- Sources

/** The sources the installed packages bring, for this profile: on or off, preferred, set up, and whether they work here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourcesTab() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val hostState by manga.services.extensions.state.collectAsStateWithLifecycle()
    val registry by manga.module.plugins.installed.collectAsStateWithLifecycle()
    val sources by rememberMangaQuery(hostState, initial = null as List<SourceEntry>?) { services, profile -> services.sources.list(profile) }
    var filter by remember { mutableStateOf("") }
    var language by remember { mutableStateOf<String?>(null) }
    var only by remember { mutableStateOf("all") }
    var configuring by remember { mutableStateOf<SourceEntry?>(null) }
    var testing by remember { mutableStateOf(setOf<String>()) }
    var details by remember { mutableStateOf<String?>(null) }
    val all = sources.orEmpty()
    val languages = all.mapNotNull { it.descriptor.language }.distinct().sorted()
    val needle = filter.trim().lowercase()
    val listed = all.filter { source ->
        (needle.isEmpty() || source.name.lowercase().contains(needle) || source.descriptor.url.lowercase().contains(needle)) &&
            (language == null || source.descriptor.language == language) &&
            when (only) {
                "on" -> source.enabled
                "working" -> source.health?.ok == true
                "failing" -> source.health?.ok == false
                "setup" -> source.needsSetup
                else -> true
            }
    }
    fun test(ids: List<String>) {
        testing = testing + ids
        scope.launch(Dispatchers.IO) {
            for (id in ids) {
                runCatching { manga.services.sources.test(id) }
                testing = testing - id
            }
        }
    }
    val preferred = all.filter { it.preferredRank != null }.sortedBy { it.preferredRank }.map { it.id }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sources", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
                Text(
                    "${plural(all.size, "source")} from the installed packages, ${all.count { it.enabled }} switched on for this profile. " +
                        "\"Working\" and \"Failing\" are results of tests run on this computer, with their date — nothing is called working that has not been tried.",
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                )
                if (all.isNotEmpty()) {
                    MangaSearchField(filter, { filter = it }, Modifier.widthIn(max = 420.dp).fillMaxWidth(), placeholder = "Find a source")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "All", "on" to "Switched on", "working" to "Working", "failing" to "Failing", "setup" to "Needs setting up").forEach { (key, label) ->
                            MangaChip(label, only == key) { only = key }
                        }
                        LanguagePicker(languages, language) { language = it }
                        MangaButton(
                            if (testing.isEmpty()) "Test the ones shown (${listed.count { it.enabled }})" else "Testing… ${testing.size} left",
                            { test(listed.filter { it.enabled && !it.needsSetup }.map { it.id }) },
                            primary = false, icon = Icons.Rounded.PlayArrow, enabled = testing.isEmpty() && listed.any { it.enabled },
                        )
                    }
                    Text(
                        "A test browses, searches, opens one series, lists its chapters and asks for one chapter's pages. It downloads no page images.",
                        style = small(), color = tokens.colors.textMuted,
                    )
                }
            }
        }
        if (sources != null && all.isEmpty()) {
            item {
                // Say which of the three it is: nothing installed, installed but switched off, or installed and not loading.
                val added = registry.filterNot { it.builtIn }
                val off = added.filterNot { it.enabled }
                when {
                    off.isNotEmpty() -> MangaEmptyState(
                        Icons.Rounded.Extension, "No sources",
                        "${off.joinToString(", ") { it.name }} is installed but switched off. Switch it on to get its sources back.",
                    ) { MangaButton("Installed", { MangaPluginsState.tab = MangaPluginsTab.INSTALLED }, icon = Icons.Rounded.Extension) }
                    added.isNotEmpty() -> MangaEmptyState(
                        Icons.Rounded.Extension, "No sources",
                        "The installed package registered no sources. Installed shows what went wrong.",
                    ) { MangaButton("Installed", { MangaPluginsState.tab = MangaPluginsTab.INSTALLED }, icon = Icons.Rounded.Extension) }
                    else -> MangaEmptyState(Icons.Rounded.Extension, "No sources", "Sources come with an extension package. Install Tiyo to get Houdoku's sources.") {
                        MangaButton("Browse", { MangaPluginsState.tab = MangaPluginsTab.BROWSE }, icon = Icons.Rounded.Extension)
                    }
                }
            }
        }
        items(listed, key = { it.id }) { source ->
            SourceRow(
                source = source,
                testing = source.id in testing,
                expanded = details == source.id,
                onDetails = { details = if (details == source.id) null else source.id },
                onConfigure = { configuring = source },
                onTest = { test(listOf(source.id)) },
                onPrefer = {
                    val next = if (source.id in preferred) preferred - source.id else preferred + source.id
                    manga.services.sources.setPreferred(manga.profileId, next)
                },
                onMove = { delta ->
                    val index = preferred.indexOf(source.id)
                    val target = index + delta
                    if (index >= 0 && target in preferred.indices) {
                        val next = preferred.toMutableList().apply { add(target, removeAt(index)) }
                        manga.services.sources.setPreferred(manga.profileId, next)
                    }
                },
            )
        }
    }
    configuring?.let { source -> SourceSettingsDialog(source) { configuring = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceRow(
    source: SourceEntry,
    testing: Boolean,
    expanded: Boolean,
    onDetails: () -> Unit,
    onConfigure: () -> Unit,
    onTest: () -> Unit,
    onPrefer: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val health = source.health
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val rank = source.preferredRank
            MangaIconButton(
                if (rank != null) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                if (rank != null) "Preferred source ${rank + 1}: click to stop preferring it" else "Prefer this source: it is searched and listed first",
                onPrefer, size = 30.dp, tint = if (rank != null) manga.style.accent else tokens.colors.textMuted,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(source.name, style = MaterialTheme.typography.titleSmall, color = if (source.enabled) tokens.colors.textPrimary else tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(languageName(source.descriptor.language), source.descriptor.url.removePrefix("https://").removePrefix("http://").trimEnd('/'), source.descriptor.origin).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (source.needsSetup) StatusPill("Needs setting up", PillTone.WARNING)
            if (source.descriptor.usesBrowserWindow == true) StatusPill("Browser window", PillTone.WARNING)
            when {
                testing -> CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                health == null -> StatusPill("Not tested")
                health.ok -> StatusPill("Working · ${formatDate(health.testedAt)}", PillTone.SUCCESS)
                else -> StatusPill("Failing · ${formatDate(health.testedAt)}", PillTone.DANGER)
            }
            MangaChip(if (source.enabled) "On" else "Off", source.enabled) { manga.services.sources.setEnabled(manga.profileId, source.id, !source.enabled) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (source.descriptor.settingTypes.isNotEmpty()) MangaButton("Configure", onConfigure, primary = source.needsSetup, icon = Icons.Rounded.Tune)
            MangaButton("Test", onTest, primary = false, icon = Icons.Rounded.PlayArrow, enabled = !testing && source.enabled)
            MangaButton("Browse", {
                DiscoverState.open(source.id, "")
                manga.services.preferences.set(MangaPreferences.DISCOVER_SOURCE, source.id)
                MangaNav.root(MangaRoute.Discover)
                com.debridone.app.shell.ShellState.select(com.debridone.app.shell.ShellSection.MANGA)
            }, primary = false, icon = Icons.Rounded.Explore, enabled = source.enabled)
            if (source.descriptor.url.startsWith("http")) MangaButton("Its site", { openInBrowser(source.descriptor.url) }, primary = false, icon = Icons.Rounded.OpenInNew)
            if (health != null) MangaButton(if (expanded) "Hide the last test" else "Last test", onDetails, primary = false)
            if (source.preferredRank != null) {
                MangaIconButton(Icons.Rounded.ArrowUpward, "Move up among the preferred sources", { onMove(-1) }, size = 34.dp)
                MangaIconButton(Icons.Rounded.ArrowDownward, "Move down among the preferred sources", { onMove(1) }, size = 34.dp)
            }
        }
        if (source.descriptor.usesBrowserWindow == true) {
            Text(
                "In Houdoku this source loads its pages in a hidden browser window. DebridOne loads them without running their scripts, so it works only while the site answers plain requests.",
                style = small(), color = tokens.colors.textMuted,
            )
        }
        if (source.descriptor.problems.isNotEmpty()) Text("This source ${source.descriptor.problems.joinToString("; ")}.", style = small(), color = tokens.colors.warning)
        if (expanded && health != null) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(tokens.colors.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                health.steps.forEach { step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (step.ok) "✓" else "✕", style = MaterialTheme.typography.bodyMedium, color = if (step.ok) tokens.colors.success else tokens.colors.danger)
                        Text(step.name, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textPrimary, modifier = Modifier.width(120.dp))
                        Text(step.detail, style = small(), color = tokens.colors.textSecondary, modifier = Modifier.weight(1f))
                    }
                }
                if (health.steps.isEmpty()) Text(health.message ?: "The test could not start.", style = small(), color = tokens.colors.textSecondary)
            }
        }
    }
}

private fun isSecretSetting(name: String): Boolean = Regex("pass|token|secret|key", RegexOption.IGNORE_CASE).containsMatchIn(name)

/** A source's own settings, exactly the ones it declares. They are stored for this profile in the system's credential store. */
@Composable
private fun SourceSettingsDialog(source: SourceEntry, onDismiss: () -> Unit) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val current = remember(source.id) { manga.services.sources.currentSettings(manga.profileId, source.descriptor) }
    val texts = remember(source.id) {
        mutableStateOf(source.descriptor.settingTypes.filterValues { it == "STRING" || it == "NUMBER" }.mapValues { (key, _) -> (current[key] as? JsonPrimitive)?.contentOrNull.orEmpty() })
    }
    val flags = remember(source.id) {
        mutableStateOf(source.descriptor.settingTypes.filterValues { it == "BOOLEAN" }.mapValues { (key, _) -> (current[key] as? JsonPrimitive)?.booleanOrNull ?: false })
    }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val unsupported = source.descriptor.settingTypes.filterValues { it != "STRING" && it != "NUMBER" && it != "BOOLEAN" }

    MangaDialogFrame("Set up ${source.name}", onDismiss, width = 520) {
        Text(
            "These are the settings this source asks for. They are kept for this profile in the system's credential store, not in the library database, and are given only to this source.",
            style = small(), color = tokens.colors.textMuted,
        )
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            source.descriptor.settingTypes.forEach { (name, type) ->
                when (type) {
                    "STRING", "NUMBER" -> MangaTextField(
                        texts.value[name].orEmpty(), { texts.value = texts.value + (name to it) }, name, secret = isSecretSetting(name),
                        hint = if (type == "NUMBER") "A number" else null,
                    )
                    "BOOLEAN" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary, modifier = Modifier.weight(1f))
                        MangaChip(if (flags.value[name] == true) "On" else "Off", flags.value[name] == true) { flags.value = flags.value + (name to !(flags.value[name] ?: false)) }
                    }
                    else -> Unit
                }
            }
            if (unsupported.isNotEmpty()) {
                Text("Not editable here (list settings): ${unsupported.keys.joinToString(", ")}. They keep the source's defaults.", style = small(), color = tokens.colors.textMuted)
            }
            problem?.let { Notice(it, PillTone.DANGER) }
        }
        MangaDialogButtons(if (saving) "Saving…" else "Save", onDismiss, enabled = !saving) {
            val values = buildJsonObject {
                source.descriptor.settingTypes.forEach { (name, type) ->
                    when (type) {
                        "STRING" -> put(name, texts.value[name].orEmpty().trim())
                        "NUMBER" -> texts.value[name]?.trim()?.toDoubleOrNull()?.let { put(name, it) }
                        "BOOLEAN" -> put(name, flags.value[name] ?: false)
                    }
                }
            }
            saving = true
            scope.launch(Dispatchers.IO) {
                try {
                    manga.services.sources.saveSettings(manga.profileId, source.descriptor, values)
                    MangaNav.message = "${source.name} is set up. Use Test to check that it answers."
                    withContext(Dispatchers.Main) { onDismiss() }
                } catch (failure: Exception) {
                    problem = failure.message ?: "The settings could not be saved."
                } finally {
                    saving = false
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- Metadata

/** Manga's metadata providers: which are used, in which order, and the accounts for tracking. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MetadataTab() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val providers by manga.module.metadataProviders.providers.collectAsStateWithLifecycle()
    val ordered = providers.sortedBy { it.priority }
    var accountsVersion by remember { mutableStateOf(0) }
    var malClientId by remember { mutableStateOf(manga.services.metadata.myAnimeListClientId().orEmpty()) }
    var aniListClient by remember { mutableStateOf("") }
    var aniListToken by remember { mutableStateOf("") }
    var malRedirect by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val aniListAccount = remember(accountsVersion, manga.profileId) { manga.services.metadata.account(manga.profileId, "anilist") }
    val malAccount = remember(accountsVersion, manga.profileId) { manga.services.metadata.account(manga.profileId, "myanimelist") }

    fun run(action: suspend () -> String) {
        if (busy) return
        busy = true
        problem = null
        notice = null
        scope.launch(Dispatchers.IO) {
            try {
                notice = action()
            } catch (failure: Exception) {
                problem = failure.message ?: "That did not work."
            } finally {
                busy = false
                accountsVersion++
            }
        }
    }
    fun move(provider: MetadataProvider, delta: Int) {
        val index = ordered.indexOf(provider)
        val target = index + delta
        if (target !in ordered.indices) return
        val next = ordered.toMutableList().apply { add(target, removeAt(index)) }
        scope.launch(Dispatchers.IO) { next.forEachIndexed { position, item -> manga.module.metadataProviders.setPriority(item.id, position * 10) } }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Metadata", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
            Text(
                "Where a series' details come from. When details are refreshed, each field takes the first value offered going down this list; a field you edited and locked is never replaced. " +
                    "These providers belong to Manga only.",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
        }
        items(ordered, key = { it.id }) { provider ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${ordered.indexOf(provider) + 1}", style = MaterialTheme.typography.titleMedium, color = tokens.colors.textMuted, modifier = Modifier.width(22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(provider.name, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
                    Text(provider.description, style = small(), color = tokens.colors.textMuted)
                }
                if (provider.id == "myanimelist" && malClientId.isBlank()) StatusPill("Needs your client ID", PillTone.WARNING)
                MangaIconButton(Icons.Rounded.ArrowUpward, "Move ${provider.name} up", { move(provider, -1) }, size = 32.dp, enabled = ordered.first() != provider)
                MangaIconButton(Icons.Rounded.ArrowDownward, "Move ${provider.name} down", { move(provider, 1) }, size = 32.dp, enabled = ordered.last() != provider)
                if (provider.id == "manual") {
                    StatusPill("Always on", PillTone.SUCCESS)
                } else {
                    MangaChip(if (provider.enabled) "On" else "Off", provider.enabled) {
                        scope.launch(Dispatchers.IO) { manga.module.metadataProviders.setEnabled(provider.id, !provider.enabled) }
                    }
                }
            }
        }
        item {
            problem?.let { Notice(it, PillTone.DANGER) }
            notice?.let { Notice(it, PillTone.SUCCESS) }
        }
        item {
            SettingsBlock(
                "MyAnimeList API client",
                "MyAnimeList's official API only answers to a registered client. Create one at myanimelist.net/apiconfig (app type \"other\", redirect address http://localhost) and paste its Client ID. " +
                    "It is kept in the system's credential store. Without it, DebridOne asks MyAnimeList nothing.",
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaTextField(malClientId, { malClientId = it }, "Client ID", Modifier.weight(1f), secret = true)
                    MangaButton("Save", {
                        manga.services.metadata.setMyAnimeListClientId(malClientId)
                        notice = if (malClientId.isBlank()) "The client ID was removed." else "Saved. Switch MyAnimeList on above to use it."
                        problem = null
                    }, primary = false)
                    MangaButton("Open apiconfig", { openInBrowser("https://myanimelist.net/apiconfig") }, primary = false, icon = Icons.Rounded.OpenInNew)
                }
            }
        }
        item {
            SettingsBlock(
                "AniList account (optional)",
                "Only needed to keep your AniList reading list up to date. Searching AniList needs no account. " +
                    "AniList gives tokens to API clients: create your own at anilist.co › Settings › Developer with the redirect address https://anilist.co/api/v2/oauth/pin, then get a token for it.",
            ) {
                if (aniListAccount != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusPill("Signed in as ${aniListAccount.name}", PillTone.SUCCESS)
                        MangaButton("Disconnect", {
                            manga.services.metadata.disconnect(manga.profileId, "anilist")
                            accountsVersion++
                        }, primary = false)
                    }
                } else {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MangaTextField(aniListClient, { aniListClient = it }, "1. Your AniList client ID", Modifier.width(220.dp))
                        MangaButton("2. Get a token", {
                            runCatching { manga.services.metadata.aniListAuthorizationUrl(aniListClient) }
                                .onSuccess(::openInBrowser).onFailure { problem = it.message }
                        }, primary = false, icon = Icons.Rounded.OpenInNew, enabled = aniListClient.isNotBlank())
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MangaTextField(aniListToken, { aniListToken = it }, "3. Paste the token AniList shows", Modifier.weight(1f), secret = true)
                        MangaButton("Connect", {
                            run {
                                val account = manga.services.metadata.connectAniList(manga.profileId, aniListToken)
                                aniListToken = ""
                                "Connected to AniList as ${account.name}."
                            }
                        }, enabled = aniListToken.isNotBlank() && !busy)
                    }
                }
            }
        }
        item {
            SettingsBlock(
                "MyAnimeList account (optional)",
                "Only needed to keep your MyAnimeList reading list up to date. You sign in on MyAnimeList's own page; DebridOne never sees your password.",
            ) {
                if (malAccount != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusPill("Signed in as ${malAccount.name}", PillTone.SUCCESS)
                        MangaButton("Disconnect", {
                            manga.services.metadata.disconnect(manga.profileId, "myanimelist")
                            accountsVersion++
                        }, primary = false)
                    }
                } else {
                    MangaButton("1. Open the MyAnimeList sign-in page", {
                        runCatching { manga.services.metadata.myAnimeListAuthorizationUrl(manga.profileId) }
                            .onSuccess(::openInBrowser).onFailure { problem = it.message }
                    }, primary = false, icon = Icons.Rounded.OpenInNew, enabled = malClientId.isNotBlank())
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MangaTextField(
                            malRedirect, { malRedirect = it }, "2. After allowing access, paste the address your browser ended up at", Modifier.weight(1f),
                            hint = "It starts with your redirect address and contains code=… (the page itself may say it cannot be opened; that is expected).",
                        )
                        MangaButton("Connect", {
                            run {
                                val account = manga.services.metadata.connectMyAnimeList(manga.profileId, malRedirect)
                                malRedirect = ""
                                "Connected to MyAnimeList as ${account.name}."
                            }
                        }, enabled = malRedirect.isNotBlank() && !busy)
                    }
                }
            }
        }
        item {
            Text(
                "Accounts are per profile and kept in the system's credential store. Tracking is switched on per series, from the series' page.",
                style = small(), color = tokens.colors.textMuted,
            )
        }
    }
}

// ---------------------------------------------------------------------------- Settings

/** The extension host and what surrounds it: state, time limits, network, cache, downloads, diagnostics. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HostSettingsTab() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val services = manga.services
    val hostState by services.host.state.collectAsStateWithLifecycle()
    val log by services.host.log.collectAsStateWithLifecycle()
    val app by services.appSettings.values.collectAsState()
    val revision by services.repository.revision.collectAsState()
    val sizes by produceState(Triple(0L, 0L, 0L), revision / 50, MangaPluginsState.changes) {
        value = withContext(Dispatchers.IO) { Triple(services.pages.cacheSize(), services.downloads.size(), services.plugins.installedBytes(services.pluginsDir)) }
    }
    var proxy by remember { mutableStateOf(services.appSettings.proxy.orEmpty()) }
    var showLog by remember { mutableStateOf(false) }
    val runtimeProblem = remember { manga.module.runtimeProblem() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary) }
        item {
            SettingsBlock(
                "Extension host",
                "Extension code runs in a separate helper process (Node.js, included with DebridOne) that is started when a source is first needed and stopped when DebridOne quits. " +
                    "It talks to the app through its standard input and output only; no network port is opened.",
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (val state = hostState) {
                        is HostState.Running -> {
                            StatusPill("Running", PillTone.SUCCESS)
                            StatusPill("Node.js ${state.info.nodeVersion}")
                            StatusPill(if (state.info.runtimeOrigin == "bundled") "Bundled runtime" else "Development runtime")
                            StatusPill(if (state.info.restricted) "File access restricted" else "File access NOT restricted", if (state.info.restricted) PillTone.SUCCESS else PillTone.DANGER)
                        }
                        HostState.Starting -> StatusPill("Starting…", PillTone.WARNING)
                        HostState.Stopped -> StatusPill(if (runtimeProblem == null) "Not running (starts when needed)" else "Unavailable", if (runtimeProblem == null) PillTone.NEUTRAL else PillTone.DANGER)
                        is HostState.Unavailable -> StatusPill("Stopped: needs attention", PillTone.DANGER)
                    }
                    if (services.host.crashCount > 0) StatusPill("Restarted ${plural(services.host.crashCount, "time")} after stopping", PillTone.WARNING)
                }
                (hostState as? HostState.Unavailable)?.let { Notice(it.reason, PillTone.DANGER) }
                runtimeProblem?.let { Notice(it, PillTone.WARNING) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaButton("Restart the host", {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                services.host.restart()
                                services.extensions.reload()
                            }.onFailure { MangaPluginsState.problem = it.message }.onSuccess { MangaNav.message = "The extension host was restarted." }
                        }
                    }, primary = false, icon = Icons.Rounded.Refresh, enabled = runtimeProblem == null)
                }
                Text("Installed packages: ${formatBytes(sizes.third)} in ${services.pluginsDir}", style = small(), color = tokens.colors.textMuted)
            }
        }
        item {
            SettingsBlock("Source timeouts", "How long a source may take to answer one request before DebridOne gives up on it. Chapter lists and images get twice as long.") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(20, 30, 45, 60, 90, 120).forEach { seconds ->
                        MangaChip("$seconds s", services.appSettings.timeoutSeconds == seconds && app.isNotEmpty() || services.appSettings.timeoutSeconds == seconds) {
                            services.appSettings.set(MangaAppSettings.TIMEOUT_SECONDS, seconds.toString())
                            services.extensions.timeoutMs = seconds * 1000L
                        }
                    }
                }
            }
        }
        item {
            SettingsBlock(
                "Network",
                "Sources connect directly unless a proxy is given here (http://host:port). It applies to the extensions' requests and takes effect when the host is restarted. " +
                    "Requests to one source are spaced out and limited to three at a time.",
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaTextField(proxy, { proxy = it }, "Proxy for extensions (empty: none)", Modifier.weight(1f))
                    MangaButton("Save", {
                        val value = proxy.trim()
                        if (value.isNotEmpty() && !Regex("^https?://[^\\s/]+(:\\d+)?/?$").matches(value)) {
                            MangaPluginsState.problem = "A proxy address looks like http://host:port."
                        } else {
                            services.appSettings.set(MangaAppSettings.PROXY, value.ifEmpty { null })
                            MangaNav.message = "Saved. Restart the host to use it."
                        }
                    }, primary = false)
                }
            }
        }
        item {
            SettingsBlock("Cache", "Pages read online are kept on disk, up to a limit, so they open at once the next time. Now: ${formatBytes(sizes.first)}.") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(250, 500, 1000, 2000, 5000).forEach { megabytes ->
                        MangaChip(if (megabytes >= 1000) "${megabytes / 1000} GB" else "$megabytes MB", services.appSettings.cacheMegabytes == megabytes) {
                            services.appSettings.set(MangaAppSettings.CACHE_MEGABYTES, megabytes.toString())
                            scope.launch(Dispatchers.IO) { services.pages.trimCache(megabytes * 1024L * 1024L) }
                        }
                    }
                    MangaButton("Empty now", {
                        scope.launch(Dispatchers.IO) {
                            services.pages.clearCache()
                            MangaCovers.clear()
                            MangaNav.message = "Cache emptied."
                        }
                    }, primary = false)
                }
            }
        }
        item {
            SettingsBlock("Downloads", "Saved chapters: ${formatBytes(sizes.second)} in ${services.downloadsDir}. Chapters are fetched through their source, at the pace it allows.") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Chapters at the same time", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                    (1..3).forEach { count ->
                        MangaChip("$count", services.appSettings.parallelDownloads == count) { services.appSettings.set(MangaAppSettings.PARALLEL_DOWNLOADS, count.toString()) }
                    }
                }
            }
        }
        item {
            SettingsBlock("Source priorities", "Preferred sources are searched and listed first. Star a source in the Sources tab and order the starred ones with the arrows.") {
                MangaButton("Open Sources", { MangaPluginsState.tab = MangaPluginsTab.SOURCES }, primary = false)
            }
        }
        item {
            SettingsBlock("Diagnostics", "What the host and the extensions printed since DebridOne started (${log.size} lines). It is kept in memory only and never sent anywhere.") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaButton(if (showLog) "Hide" else "Show", { showLog = !showLog }, primary = false)
                    MangaButton("Copy", {
                        val text = log.joinToString("\n") { "[${it.level}] ${it.text}" }
                        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
                        MangaNav.message = "Copied ${plural(log.size, "line")}"
                    }, primary = false, enabled = log.isNotEmpty())
                    MangaButton("Clear", { services.host.clearLog() }, primary = false, enabled = log.isNotEmpty())
                }
                if (showLog) {
                    SelectionContainer {
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).background(tokens.colors.background).padding(12.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (log.isEmpty()) Text("Nothing has been printed.", style = small(), color = tokens.colors.textMuted)
                            log.takeLast(200).forEach { line ->
                                Text(
                                    "${line.level.padEnd(5)} ${line.text}",
                                    style = small().copy(fontFamily = FontFamily.Monospace),
                                    color = if (line.level == "error") tokens.colors.danger else if (line.level == "warn") tokens.colors.warning else tokens.colors.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
