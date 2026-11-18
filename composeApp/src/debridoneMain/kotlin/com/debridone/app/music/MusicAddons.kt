package com.debridone.app.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.ExtensionOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.debridone.app.shell.PillTone
import com.debridone.app.shell.ShellSection
import com.debridone.app.shell.ShellState
import com.debridone.app.shell.StatusPill
import com.debridone.music.sources.AddonCheck
import com.debridone.music.sources.AddonManifest
import com.debridone.music.sources.AddonSetting
import com.debridone.music.sources.EclipseAddonAdapter
import com.debridone.music.sources.SourceConnection
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An addon whose manifest was read and is waiting for the user's decision. The address stays in memory only. */
internal class AddonReview(val address: String, val manifest: AddonManifest)

/** What the addon part of Plugins › Music is showing. */
internal object AddonUi {
    var review by mutableStateOf<AddonReview?>(null)
    var configuring by mutableStateOf<Long?>(null)
    var testing by mutableStateOf<Long?>(null)
}

/** True for the address of something on the web (as opposed to a file on this computer). */
internal fun isWebAddress(text: String): Boolean = text.trim().let { it.startsWith("https://", true) || it.startsWith("http://", true) }

/**
 * Plugins › Music › Addons: music addons in the Eclipse Music / BeatBoss manifest format, for this
 * profile. An address is read first; what the addon offers and what DebridOne will and will not
 * use is shown before anything is installed.
 */
@Composable
internal fun AddonsCard(addons: List<SourceConnection>, onRemove: (SourceConnection) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val sources = music.services.sources
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun check() {
        val text = address.trim()
        if (text.isEmpty() || checking) return
        checking = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { sources.previewAddon(text) } }
            checking = false
            result.fold(
                { manifest -> AddonUi.review = AddonReview(text, manifest); address = "" },
                { error = it.message ?: "The addon could not be read." },
            )
        }
    }

    SettingsCard(
        "Addons",
        "Music addons in the Eclipse Music / BeatBoss format: a server you add by the address of its manifest. DebridOne reads the manifest, shows what it can use, and installs the addon for this profile only.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogTextField(address, { address = it; error = null }, "Addon address (https://…/manifest.json)", Modifier.weight(1f), onSubmit = ::check)
            MusicButton(if (checking) "Reading…" else "Add addon", ::check, enabled = !checking && address.isNotBlank())
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
        if (addons.isEmpty()) Text("No addon installed for this profile.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
        addons.forEach { connection ->
            AddonRow(
                connection = connection,
                manifest = sources.addonManifest(connection),
                onEnabled = { enabled -> actions.io { sources.setEnabled(music.profileId, connection.id, enabled) } },
                onRemove = { onRemove(connection) },
            )
        }
        Text(
            "An addon's address can contain your personal token, so it is treated like a password: it is kept in the ${sources.credentialStoreName} for this profile, " +
                "is not written to DebridOne's database or logs, and is not shown again. Other profiles do not see your addons. " +
                "DebridOne ships with no addon; what an addon serves is its provider's responsibility and yours.",
            style = small(), color = tokens.colors.textMuted,
        )
    }

    AddonUi.review?.let { review -> AddonReviewDialog(review) }
    addons.firstOrNull { it.id == AddonUi.configuring }?.let { connection -> AddonSettingsDialog(connection) { AddonUi.configuring = null } }
    addons.firstOrNull { it.id == AddonUi.testing }?.let { connection -> AddonTestDialog(connection) { AddonUi.testing = null } }
}

@Composable
private fun AddonRow(connection: SourceConnection, manifest: AddonManifest?, onEnabled: (Boolean) -> Unit, onRemove: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val browsable = manifest != null && (manifest.searches || manifest.rows.isNotEmpty())
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            if (connection.enabled) Icons.Rounded.Extension else Icons.Rounded.ExtensionOff, contentDescription = null,
            tint = if (connection.enabled) music.style.accent else tokens.colors.textMuted, modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(connection.name, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary, maxLines = 1)
                manifest?.version?.takeIf { it.isNotBlank() }?.let { StatusPill("v$it") }
                when (manifest?.contentType) {
                    "audiobook" -> StatusPill("Audiobooks")
                    "podcast" -> StatusPill("Podcasts")
                }
                when {
                    !connection.enabled -> StatusPill("Off")
                    connection.lastTestOk == false -> StatusPill("Needs attention", PillTone.DANGER)
                    else -> StatusPill("Working", PillTone.SUCCESS)
                }
                if (connection.config[EclipseAddonAdapter.DOWNLOADS_KEY] == "on") StatusPill("Saving allowed")
            }
            Text(
                details(hostOf(connection), manifest?.let(::capabilityLine), connection.trackCount.takeIf { it > 0 }?.let { plural(it, "song") + " in your library" }),
                style = small(), color = tokens.colors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (connection.lastTestOk == false) {
                Text(connection.lastError ?: "The last check failed.", style = small(), color = tokens.colors.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (browsable) {
            MusicButton("Open in Discover", {
                DiscoverState.sourceId = connection.id
                ShellState.select(ShellSection.MUSIC)
                MusicNav.root(MusicRoute.Discover)
            }, primary = false, enabled = connection.enabled)
        }
        MusicIconButton(Icons.Rounded.Tune, "Settings of ${connection.name}", { AddonUi.configuring = connection.id })
        MusicIconButton(Icons.Rounded.Verified, "Test ${connection.name}", { AddonUi.testing = connection.id })
        Switch(
            checked = connection.enabled, onCheckedChange = onEnabled,
            colors = SwitchDefaults.colors(checkedTrackColor = music.style.accent, checkedThumbColor = music.style.onAccent),
        )
        MusicIconButton(Icons.Rounded.Delete, "Remove ${connection.name}", onRemove)
    }
}

/** The server an addon is on. Never more than its name: the rest of the address is not kept in the open. */
private fun hostOf(connection: SourceConnection): String? =
    connection.baseUrl?.substringAfter("://")?.substringBefore('/')?.takeIf { it.isNotBlank() }

private fun capabilityLine(manifest: AddonManifest): String = listOfNotNull(
    "search".takeIf { manifest.searches },
    "playback".takeIf { manifest.resolvesStreams },
    "album and artist pages".takeIf { manifest.hasDetailPages },
    manifest.rows.size.takeIf { it > 0 }?.let { plural(it, "row") },
    "lyrics".takeIf { manifest.hasLyrics },
).joinToString(", ").ifEmpty { "nothing DebridOne uses" }

// ---------------------------------------------------------------------------- review before installing

@Composable
private fun AddonReviewDialog(review: AddonReview) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val scope = rememberCoroutineScope()
    val manifest = review.manifest
    val compatibility = remember(manifest) { manifest.compatibility() }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val close = { if (!working) AddonUi.review = null }

    fun install() {
        if (working) return
        working = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { music.services.sources.addAddon(music.profileId, review.address) } }
            working = false
            result.fold(
                { connection ->
                    AddonUi.review = null
                    MusicNav.message = "${connection.name} is installed for this profile."
                },
                { error = it.message ?: "The addon could not be installed." },
            )
        }
    }

    MusicDialogFrame("Add ${manifest.name}?", onDismiss = close, width = 580) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill("v${manifest.version}")
            StatusPill(
                when (manifest.contentType) {
                    "audiobook" -> "Audiobooks"
                    "podcast" -> "Podcasts"
                    else -> "Music"
                },
            )
            StatusPill(if (compatibility.usable) "Compatible" else "Not usable", if (compatibility.usable) PillTone.SUCCESS else PillTone.DANGER)
        }
        Text(details(manifest.id, "served from " + review.address.substringAfter("://").substringBefore('/')), style = small(), color = tokens.colors.textMuted)
        manifest.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary) }

        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ReviewList("DebridOne will use", compatibility.uses.ifEmpty { listOf("Nothing") }, tokens.colors.success)
            if (compatibility.ignores.isNotEmpty()) ReviewList("Offered by the addon, not used by DebridOne", compatibility.ignores, tokens.colors.textMuted)
            if (compatibility.notes.isNotEmpty()) ReviewList("Good to know", compatibility.notes, tokens.colors.warning)
            Text(
                "An addon is someone else's server. DebridOne sends it what you search for and the songs you open, and plays the audio addresses it answers with; " +
                    "no code from it runs here. Saving its songs for offline listening stays off until you switch it on for this addon.",
                style = small(), color = tokens.colors.textMuted,
            )
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (working) CircularProgressIndicator(color = music.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Box(Modifier.size(10.dp))
            MusicButton("Cancel", close, primary = false, enabled = !working)
            Box(Modifier.size(10.dp))
            MusicButton(if (working) "Installing…" else "Install for this profile", ::install, enabled = !working && compatibility.usable)
        }
    }
}

@Composable
private fun ReviewList(title: String, lines: List<String>, bullet: androidx.compose.ui.graphics.Color) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
        lines.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = bullet)
                Text(line, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
            }
        }
    }
}

// ---------------------------------------------------------------------------- settings of one addon

/** The addon's own settings (as its manifest declares them), its name in DebridOne, and whether its songs may be saved. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddonSettingsDialog(connection: SourceConnection, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val actions = LocalMusicActions.current
    val sources = music.services.sources
    val manifest = sources.addonManifest(connection)
    val values = sources.addonSettingValues(connection)
    var name by remember(connection.id) { mutableStateOf(connection.name) }
    val saving = connection.config[EclipseAddonAdapter.DOWNLOADS_KEY] == "on"

    fun set(setting: AddonSetting, value: String?) = actions.io { sources.setAddonSetting(music.profileId, connection.id, setting.key, value) }

    MusicDialogFrame("Settings of ${connection.name}", onDismiss = onDismiss, width = 560) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogTextField(name, { name = it }, "Name shown in DebridOne", Modifier.weight(1f), onSubmit = { actions.io { sources.rename(music.profileId, connection.id, name) } })
                MusicButton("Rename", { actions.io { sources.rename(music.profileId, connection.id, name) } }, primary = false, enabled = name.isNotBlank() && name.trim() != connection.name)
            }

            if (manifest == null || manifest.settings.isEmpty()) {
                Text("This addon declares no settings of its own.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
            } else {
                Text("The addon's own settings. They are sent to it with every request, for this profile only.", style = small(), color = tokens.colors.textMuted)
            }
            manifest?.settings.orEmpty().forEach { setting ->
                val value = values[setting.key] ?: setting.default
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (setting.type) {
                        "toggle" -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(setting.label, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
                                setting.help?.let { Text(it, style = small(), color = tokens.colors.textMuted) }
                            }
                            Switch(
                                checked = value == "true", onCheckedChange = { set(setting, it.toString()) },
                                colors = SwitchDefaults.colors(checkedTrackColor = music.style.accent, checkedThumbColor = music.style.onAccent),
                            )
                        }
                        "select" -> {
                            Text(setting.label, style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
                            setting.help?.let { Text(it, style = small(), color = tokens.colors.textMuted) }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                setting.options.forEach { (option, label) -> MusicChip(label, option == value) { set(setting, option) } }
                            }
                        }
                        else -> {
                            var text by remember(connection.id, setting.key, value) { mutableStateOf(value) }
                            val range = if (setting.type == "number") listOfNotNull(setting.min?.let { "from ${trimNumber(it)}" }, setting.max?.let { "to ${trimNumber(it)}" }).joinToString(" ") else ""
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                DialogTextField(
                                    text, { text = it }, details(setting.label, range.ifBlank { null }, setting.placeholder), Modifier.weight(1f),
                                    onSubmit = { set(setting, text) },
                                )
                                MusicButton("Apply", { set(setting, text) }, primary = false, enabled = text.trim() != value)
                            }
                            setting.help?.let { Text(it, style = small(), color = tokens.colors.textMuted) }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow saving songs from this addon", style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textPrimary)
                    Text(
                        "Off by default. Installing an addon does not mean its provider lets you keep copies. Switch this on only if the provider permits downloading " +
                            "and the files are yours to keep; \"Download for offline\" then appears in the menu of its songs.",
                        style = small(), color = tokens.colors.textMuted,
                    )
                }
                Switch(
                    checked = saving, onCheckedChange = { allowed -> actions.io { sources.setAddonDownloads(music.profileId, connection.id, allowed) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = music.style.accent, checkedThumbColor = music.style.onAccent),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { MusicButton("Done", onDismiss) }
    }
}

private fun trimNumber(number: Double): String = if (number == Math.floor(number)) number.toLong().toString() else number.toString()

// ---------------------------------------------------------------------------- connection test

/** Tries the addon step by step: manifest, search, rows, a song's address, the start of its audio. */
@Composable
private fun AddonTestDialog(connection: SourceConnection, onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val music = LocalMusic.current
    val scope = rememberCoroutineScope()
    var query by remember(connection.id) { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var checks by remember(connection.id) { mutableStateOf<List<AddonCheck>?>(null) }

    fun run() {
        if (running) return
        running = true
        scope.launch {
            checks = withContext(Dispatchers.IO) {
                runCatching { music.services.sources.diagnoseAddon(music.profileId, connection.id, query) }
                    .getOrElse { listOf(AddonCheck("Test", false, it.message ?: "The test could not be run.")) }
            }
            running = false
        }
    }

    MusicDialogFrame("Test ${connection.name}", onDismiss = { if (!running) onDismiss() }, width = 560) {
        Text(
            "Checks the addon's manifest, searches it, and (when a song is found) asks for that song's address and reads the first bytes of its audio to see what it really is. Nothing is saved.",
            style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogTextField(query, { query = it }, "Search for (optional)", Modifier.weight(1f), onSubmit = ::run)
            MusicButton(if (running) "Testing…" else "Run the test", ::run, enabled = !running)
        }
        checks?.let { results ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                results.forEach { check ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            if (check.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, contentDescription = if (check.ok) "Passed" else "Failed",
                            tint = if (check.ok) tokens.colors.success else tokens.colors.danger, modifier = Modifier.size(20.dp),
                        )
                        Column {
                            Text(check.step, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
                            Text(check.detail, style = small(), color = if (check.ok) tokens.colors.textSecondary else tokens.colors.danger)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { MusicButton("Close", { if (!running) onDismiss() }, primary = false) }
    }
}
