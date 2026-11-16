package com.debridone.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.DebridOneRuntime
import com.debridone.app.streaming.StreamingPluginsTab
import com.debridone.module.AvailablePlugin
import com.debridone.module.EntertainmentModule
import com.debridone.module.InstalledPlugin
import com.debridone.module.ModuleId
import com.debridone.module.ModuleStatus
import com.debridone.module.PluginActionResult
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.launch

/**
 * Plugins: four tabs, four separate management screens. A tab is not a filter over a shared list:
 * each one talks only to its own module's registry, backed by that module's own storage.
 */
@Composable
internal fun PluginsScreen() {
    val tab by ShellState.pluginsTab.collectAsStateWithLifecycle()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = topInset + 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentedTabs(
                items = ModuleId.entries.map { it to it.displayName },
                selected = tab,
                onSelect = ShellState::selectPluginsTab,
            )
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                // Streaming's plugins are Nuvio's own addons and plugins, managed by Nuvio's screens.
                ModuleId.STREAMING -> StreamingPluginsTab()
                else -> {
                    val module = DebridOneRuntime.modules.module(tab)
                    if (module != null) ModulePluginsTab(module)
                }
            }
        }
    }
}

@Composable
private fun ModulePluginsTab(module: EntertainmentModule) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val name = module.id.displayName
    val registry = module.plugins
    val status by module.status.collectAsStateWithLifecycle()
    val installed by registry.installed.collectAsStateWithLifecycle()
    val providers by module.metadataProviders.providers.collectAsStateWithLifecycle()
    val available by produceState(emptyList<AvailablePlugin>(), module.id, status) {
        value = if (status == ModuleStatus.Running) runCatching { registry.available() }.getOrDefault(emptyList()) else emptyList()
    }
    var location by remember(module.id) { mutableStateOf("") }
    var message by remember(module.id) { mutableStateOf<Pair<String, PillTone>?>(null) }

    fun report(result: PluginActionResult, success: String) {
        message = when (result) {
            is PluginActionResult.Success -> success to PillTone.SUCCESS
            is PluginActionResult.Failure -> result.message to PillTone.DANGER
            is PluginActionResult.NotAvailableYet -> result.reason to PillTone.WARNING
        }
    }

    ShellPage(
        title = "$name plugins",
        subtitle = registry.importHint,
    ) {
        (status as? ModuleStatus.Failed)?.let { failed ->
            Notice("$name could not open its storage, so its plugins are unavailable: ${failed.message}", PillTone.DANGER)
            return@ShellPage
        }
        message?.let { (text, tone) -> Notice(text, tone) }

        ShellCard(Modifier.fillMaxWidth()) {
            SectionTitle("Import", "Supported here: " + registry.supportedKinds.joinToString { it.label.lowercase() } + ".")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    singleLine = true,
                    enabled = registry.importAvailable,
                    placeholder = { Text("Manifest URL, package or file") },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = registry.importAvailable && location.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val result = registry.importFromLocation(location)
                            report(result, "Imported into $name.")
                            if (result is PluginActionResult.Success) location = ""
                        }
                    },
                ) { Text("Import") }
            }
            if (!registry.importAvailable) {
                Text(
                    "Importing is not available yet: it arrives together with the $name engine. Nothing is pre-installed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            }
        }

        ShellCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { SectionTitle("Installed", "Only $name plugins appear here.") }
                OutlinedButton(
                    enabled = installed.isNotEmpty(),
                    onClick = { scope.launch { report(registry.checkForUpdates(), "$name plugins are up to date.") } },
                ) { Text("Check for updates") }
            }
            if (installed.isEmpty()) {
                Text("No $name plugins installed.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
            }
            installed.forEach { plugin ->
                InstalledPluginRow(
                    plugin = plugin,
                    onToggle = { enabled ->
                        scope.launch { report(registry.setEnabled(plugin.id, enabled), if (enabled) "Enabled ${plugin.name}." else "Disabled ${plugin.name}.") }
                    },
                    onRemove = { scope.launch { report(registry.remove(plugin.id), "Removed ${plugin.name} from $name.") } },
                )
            }
        }

        ShellCard(Modifier.fillMaxWidth()) {
            SectionTitle("Available", "Offered by $name itself. Nothing is installed until you choose it.")
            if (available.isEmpty()) {
                Text("$name does not offer a catalogue in this build.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
            }
            available.forEach { plugin ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(plugin.name, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                        plugin.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted) }
                    }
                    OutlinedButton(onClick = { scope.launch { report(registry.importFromLocation(plugin.origin), "Installed ${plugin.name}.") } }) {
                        Text("Install")
                    }
                }
            }
        }

        ShellCard(Modifier.fillMaxWidth()) {
            SectionTitle("Metadata", "$name metadata providers. They never supply data to another section.")
            providers.forEach { provider ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(provider.name, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                            if (!provider.implemented) StatusPill("Not built yet", PillTone.WARNING)
                            if (provider.requiresCredentials) StatusPill("Needs your own credentials")
                        }
                        Text(provider.description, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted)
                    }
                    Switch(
                        checked = provider.enabled,
                        onCheckedChange = { enabled -> scope.launch { module.metadataProviders.setEnabled(provider.id, enabled) } },
                    )
                }
            }
        }

        val errors = installed.filter { it.lastError != null }
        if (errors.isNotEmpty()) {
            ShellCard(Modifier.fillMaxWidth()) {
                SectionTitle("Problems")
                errors.forEach { plugin ->
                    Text("${plugin.name}: ${plugin.lastError}", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger)
                }
            }
        }
    }
}

@Composable
private fun InstalledPluginRow(plugin: InstalledPlugin, onToggle: (Boolean) -> Unit, onRemove: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(plugin.name, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
                StatusPill(plugin.kind.label)
                plugin.version?.let { StatusPill("v$it") }
                if (plugin.builtIn) StatusPill("Built in")
            }
            (plugin.description ?: plugin.origin)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, maxLines = 2)
            }
        }
        Switch(checked = plugin.enabled, onCheckedChange = onToggle)
        if (!plugin.builtIn) TextButton(onClick = onRemove) { Text("Remove", color = tokens.colors.danger) }
    }
}
