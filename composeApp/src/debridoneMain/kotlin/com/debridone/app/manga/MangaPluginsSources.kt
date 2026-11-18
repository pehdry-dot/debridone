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
import androidx.compose.material.icons.rounded.FolderOpen
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.StatusPill
import com.debridone.manga.MangaPreferences
import com.debridone.manga.sources.SourceEntry
import com.debridone.manga.suwayomi.SwPreference
import com.debridone.manga.suwayomi.SwPreferenceChange
import com.debridone.module.MetadataProvider
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------- Sources

/** The sources of the installed extensions, for this profile: on or off, preferred, configured, and whether they work here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourcesTab() {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val actions = LocalMangaActions.current
    val sources by rememberMangaQuery(initial = null as List<SourceEntry>?) { services, profile -> services.sources.list(profile) }
    var filter by remember { mutableStateOf("") }
    var language by remember { mutableStateOf<String?>(null) }
    var only by remember { mutableStateOf("all") }
    var testing by remember { mutableStateOf(setOf<String>()) }
    var details by remember { mutableStateOf<String?>(null) }
    val all = sources.orEmpty()
    val languages = all.map { it.language }.filter { it.isNotBlank() }.distinct().sortedBy(::languageName)
    val needle = filter.trim().lowercase()
    val listed = all.filter { source ->
        (needle.isEmpty() || source.name.lowercase().contains(needle) || source.homeUrl.orEmpty().lowercase().contains(needle)) &&
            (language == null || source.language == language) &&
            when (only) {
                "on" -> source.enabled
                "working" -> source.health?.ok == true
                "failing" -> source.health?.ok == false
                "configurable" -> source.configurable
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
                    "${plural(all.count { !it.isLocal }, "source")} from the installed extensions, ${all.count { it.enabled && !it.isLocal }} switched on for this profile, and your own files. " +
                        "\"Working\" and \"Failing\" are results of tests run on this computer, with their date — nothing is called working that has not been tried.",
                    style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
                )
                if (all.isNotEmpty()) {
                    MangaSearchField(filter, { filter = it }, Modifier.widthIn(max = 420.dp).fillMaxWidth(), placeholder = "Find a source")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "All", "on" to "Switched on", "working" to "Working", "failing" to "Failing", "configurable" to "With settings").forEach { (key, label) ->
                            MangaChip(label, only == key) { only = key }
                        }
                        LanguagePicker(languages, language) { language = it }
                        MangaButton(
                            if (testing.isEmpty()) "Test the ones shown (${listed.count { it.enabled }})" else "Testing… ${testing.size} left",
                            { test(listed.filter { it.enabled }.map { it.id }) },
                            primary = false, icon = Icons.Rounded.PlayArrow, enabled = testing.isEmpty() && listed.any { it.enabled },
                        )
                    }
                    Text(
                        "A test lists and searches the source, opens one series and its chapters, and loads the first page of one chapter. Nothing is added to your library.",
                        style = small(), color = tokens.colors.textMuted,
                    )
                }
            }
        }
        if (sources != null && all.none { !it.isLocal }) {
            item {
                MangaEmptyState(
                    Icons.Rounded.Extension, "No online sources",
                    "Sources come with extensions. Add an extension store, then install the extensions you want; each brings one or more sources.",
                ) { MangaButton("Extensions", { MangaPluginsState.tab = MangaPluginsTab.EXTENSIONS }, icon = Icons.Rounded.Extension) }
            }
        }
        items(listed, key = { it.id }) { source ->
            SourceRow(
                source = source,
                testing = source.id in testing,
                expanded = details == source.id,
                onDetails = { details = if (details == source.id) null else source.id },
                onConfigure = { MangaPluginsState.configuring = source.id },
                onTest = { test(listOf(source.id)) },
                onToggle = { actions.io { manga.services.sources.setEnabled(manga.profileId, source.id, !source.enabled) } },
                onPrefer = {
                    val next = if (source.id in preferred) preferred - source.id else preferred + source.id
                    actions.io { manga.services.sources.setPreferred(manga.profileId, next) }
                },
                onMove = { delta ->
                    val index = preferred.indexOf(source.id)
                    val target = index + delta
                    if (index >= 0 && target in preferred.indices) {
                        val next = preferred.toMutableList().apply { add(target, removeAt(index)) }
                        actions.io { manga.services.sources.setPreferred(manga.profileId, next) }
                    }
                },
            )
        }
    }
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
    onToggle: () -> Unit,
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
            if (!source.isLocal) {
                MangaIconButton(
                    if (rank != null) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    if (rank != null) "Preferred source ${rank + 1}: click to stop preferring it" else "Prefer this source: it is searched and listed first",
                    onPrefer, size = 30.dp, tint = if (rank != null) manga.style.accent else tokens.colors.textMuted,
                )
            }
            ServerIcon(source.iconUrl, Modifier.size(34.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(source.name, style = MaterialTheme.typography.titleSmall, color = if (source.enabled) tokens.colors.textPrimary else tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (source.isLocal) "Archives and folders of this computer, read through the manga server's Local source"
                    else listOf(languageName(source.language), source.homeUrl.orEmpty().removePrefix("https://").removePrefix("http://").trimEnd('/')).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (source.contentWarning == "NSFW") StatusPill("18+", PillTone.WARNING)
            when {
                testing -> CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                health == null -> StatusPill("Not tested")
                health.ok -> StatusPill("Working · ${formatDate(health.testedAt)}", PillTone.SUCCESS)
                else -> StatusPill("Failing · ${formatDate(health.testedAt)}", PillTone.DANGER)
            }
            if (!source.isLocal) MangaChip(if (source.enabled) "On" else "Off", source.enabled, onClick = onToggle)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (source.configurable) MangaButton("Configure", onConfigure, primary = false, icon = Icons.Rounded.Tune)
            MangaButton("Test", onTest, primary = false, icon = Icons.Rounded.PlayArrow, enabled = !testing && source.enabled)
            if (!source.isLocal) {
                MangaButton("Browse", {
                    DiscoverState.open(source.id, "")
                    manga.services.preferences.set(MangaPreferences.DISCOVER_SOURCE, source.id)
                    MangaNav.root(MangaRoute.Discover)
                    com.debridone.app.shell.ShellState.select(com.debridone.app.shell.ShellSection.MANGA)
                }, primary = false, icon = Icons.Rounded.Explore, enabled = source.enabled)
            } else {
                manga.services.local.folder()?.let { folder -> MangaButton("Show its folder", { revealInFileManager(folder) }, primary = false, icon = Icons.Rounded.FolderOpen) }
            }
            source.homeUrl?.takeIf { it.startsWith("http") }?.let { site -> MangaButton("Its site", { openInBrowser(site) }, primary = false, icon = Icons.Rounded.OpenInNew) }
            if (health != null) MangaButton(if (expanded) "Hide the last test" else "Last test", onDetails, primary = false)
            if (source.preferredRank != null) {
                MangaIconButton(Icons.Rounded.ArrowUpward, "Move up among the preferred sources", { onMove(-1) }, size = 34.dp)
                MangaIconButton(Icons.Rounded.ArrowDownward, "Move down among the preferred sources", { onMove(1) }, size = 34.dp)
            }
        }
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

// ---------------------------------------------------------------------------- a source's own settings

private fun isSecretSetting(name: String): Boolean = Regex("pass|token|secret|api.?key", RegexOption.IGNORE_CASE).containsMatchIn(name)

/** The settings a source's extension defines, opened in a dialog. */
@Composable
internal fun SourceSettingsDialog(sourceId: String, onDismiss: () -> Unit) {
    val manga = LocalManga.current
    val source = remember(sourceId) { manga.services.sources.find(sourceId) }
    MangaDialogFrame("Configure ${source?.name ?: "source"}", onDismiss, width = 600) {
        Column(Modifier.weight(1f, fill = false).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SourcePreferencesEditor(sourceId)
        }
        MangaDialogDone(onDismiss)
    }
}

/**
 * The settings of one source, exactly as its extension defines them: switches, text fields, single
 * and multiple choices. A change is sent to the manga server as it is made and what comes back is
 * shown, because one setting can change another. Nothing is invented: a source without settings
 * says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourcePreferencesEditor(sourceId: String) {
    val manga = LocalManga.current
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var preferences by remember(sourceId) { mutableStateOf<List<SwPreference>?>(null) }
    var problem by remember(sourceId) { mutableStateOf<String?>(null) }
    var saving by remember(sourceId) { mutableStateOf<Int?>(null) }
    var saved by remember(sourceId) { mutableStateOf<Int?>(null) }

    LaunchedEffect(sourceId) {
        try {
            preferences = withContext(Dispatchers.IO) { manga.services.sources.preferences(sourceId) }
        } catch (failure: Exception) {
            problem = failure.message ?: "The source's settings could not be read."
        }
    }
    fun send(change: SwPreferenceChange) {
        saving = change.position
        problem = null
        scope.launch(Dispatchers.IO) {
            try {
                preferences = manga.services.sources.setPreference(sourceId, change)
                saved = change.position
            } catch (failure: Exception) {
                problem = failure.message ?: "The setting could not be saved."
            } finally {
                saving = null
            }
        }
    }

    problem?.let { Notice(it, PillTone.DANGER) }
    val list = preferences
    when {
        list == null && problem == null -> CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        list != null && list.none { it.visible } -> Text("This source has no settings.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        list != null -> list.filter { it.visible }.forEach { preference ->
            val title = preference.title?.takeIf { it.isNotBlank() } ?: preference.key.orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (preference) {
                    is SwPreference.Toggle -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
                            preference.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = small(), color = tokens.colors.textMuted) }
                        }
                        MangaChip(if (preference.value) "On" else "Off", preference.value) {
                            if (preference.enabled) {
                                send(if (preference.isSwitch) SwPreferenceChange(preference.position, switch = !preference.value) else SwPreferenceChange(preference.position, checkBox = !preference.value))
                            }
                        }
                    }
                    is SwPreference.Text -> {
                        var text by remember(sourceId, preference.position, preference.value) { mutableStateOf(preference.value.orEmpty()) }
                        val changed = text != preference.value.orEmpty()
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MangaTextField(
                                text, { text = it }, title, Modifier.weight(1f), secret = isSecretSetting(title) || isSecretSetting(preference.key.orEmpty()),
                                onSubmit = { if (changed) send(SwPreferenceChange(preference.position, text = text)) },
                                hint = (preference.dialogMessage ?: preference.summary)?.takeIf { it.isNotBlank() && it != text },
                            )
                            MangaButton(
                                when {
                                    saving == preference.position -> "Saving…"
                                    !changed && saved == preference.position -> "Saved"
                                    else -> "Save"
                                },
                                { send(SwPreferenceChange(preference.position, text = text)) }, primary = changed, enabled = changed && saving == null,
                            )
                        }
                    }
                    is SwPreference.Choice -> {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
                        preference.summary?.takeIf { it.isNotBlank() && it != "%s" }?.let { Text(it.replace("%s", preference.entries.getOrNull(preference.entryValues.indexOf(preference.value)).orEmpty()), style = small(), color = tokens.colors.textMuted) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            preference.entryValues.forEachIndexed { index, value ->
                                MangaChip(preference.entries.getOrNull(index) ?: value, preference.value == value) { if (preference.enabled) send(SwPreferenceChange(preference.position, choice = value)) }
                            }
                        }
                    }
                    is SwPreference.MultiChoice -> {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
                        preference.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = small(), color = tokens.colors.textMuted) }
                        if (preference.entryValues.isEmpty()) {
                            Text("Nothing to choose from yet. Some sources fill this in once their address and login are saved.", style = small(), color = tokens.colors.textMuted)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            preference.entryValues.forEachIndexed { index, value ->
                                val on = value in preference.values
                                MangaChip(preference.entries.getOrNull(index) ?: value, on) {
                                    if (preference.enabled) send(SwPreferenceChange(preference.position, multiChoice = if (on) preference.values - value else preference.values + value))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (list != null && list.any { it.visible }) {
        Text("These settings are the extension's own and are kept by the manga server. Changes are saved as you make them.", style = small(), color = tokens.colors.textMuted)
    }
}

// ---------------------------------------------------------------------------- Metadata

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
