package com.nuvio.app.features.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nuvio.app.AppShellHost
import com.nuvio.app.core.ui.PluginConfigurationPanel
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.launch

/** An installed addon that says it can be configured, and where its own configuration page is. */
data class AddonConfigurationRequest(val name: String, val manifestUrl: String, val configureUrl: String)

/**
 * DebridOne: an addon's configuration, opened where "Configure" was clicked.
 *
 * A Stremio-style addon is configured on a web page of the addon's own server, and the outcome is a
 * new address for the addon (the settings are part of it). DebridOne does not show web pages
 * inside itself, so the panel says what will happen, opens that page in the person's browser when
 * they ask, and takes the new address here, replacing the addon in the list it sits in.
 */
@Composable
fun AddonConfigurationPanel(modifier: Modifier = Modifier) {
    val request = AppShellHost.addonConfiguration
    // Kept while the panel slides away, so it does not blank out.
    var shown by remember { mutableStateOf<AddonConfigurationRequest?>(null) }
    if (request != null) shown = request
    val close = { AppShellHost.addonConfiguration = null }
    PluginConfigurationPanel(
        open = request != null,
        title = shown?.name ?: "Addon",
        kind = "Streaming addon · configured on the addon's own page",
        onClose = close,
        modifier = modifier,
    ) { shown?.let { AddonConfigurationContent(it, close) } }
}

@Composable
private fun AddonConfigurationContent(request: AddonConfigurationRequest, onDone: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var address by remember(request.manifestUrl) { mutableStateOf("") }
    var working by remember(request.manifestUrl) { mutableStateOf(false) }
    var problem by remember(request.manifestUrl) { mutableStateOf<String?>(null) }
    var opened by remember(request.manifestUrl) { mutableStateOf(false) }

    Text(
        "${request.name} is set up on a page of its own server. That page opens in your browser: DebridOne does not show web pages inside itself, and never sees what you type there.",
        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("1. Open its configuration page", style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
        SelectionContainer {
            Text(request.configureUrl, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = tokens.colors.textMuted)
        }
        Button(onClick = {
            opened = runCatching { uriHandler.openUri(request.configureUrl) }.isSuccess
            if (!opened) problem = "The page could not be opened in a browser. Its address is above: copy it into one."
        }) { Text("Open in my browser") }
        if (opened) Text("Opened in your browser.", style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(14.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("2. Bring the configured addon back", style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary)
        Text(
            "When you are done there, the page offers the configured addon as a link (often behind an \"Install\" button: copy the link rather than clicking it). Paste it here.",
            style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; problem = null },
            singleLine = true,
            enabled = !working,
            placeholder = { Text("https://…/manifest.json or stremio://…") },
            modifier = Modifier.fillMaxWidth(),
        )
        problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = address.isNotBlank() && !working,
                onClick = {
                    val wanted = normalizeAddonAddress(address)
                    working = true
                    problem = null
                    scope.launch {
                        when (val result = AddonRepository.addAddon(wanted)) {
                            is AddAddonResult.Success -> {
                                // The configured addon takes the place of the one it was made from.
                                if (!sameAddonAddress(wanted, request.manifestUrl)) AddonRepository.removeAddon(request.manifestUrl)
                                working = false
                                onDone()
                            }
                            is AddAddonResult.Error -> {
                                working = false
                                problem = addonAddressProblem(result.message)
                            }
                        }
                    }
                },
            ) { Text(if (working) "Checking the address…" else "Replace with this address") }
            OutlinedButton(onClick = onDone, enabled = !working) { Text("Cancel") }
        }
        Text(
            "The address is checked before anything changes: if it is not an addon, ${request.name} stays as it is.",
            style = MaterialTheme.typography.bodySmall, color = tokens.colors.textMuted,
        )
    }
}

/** `stremio://host/…` is the same address as `https://host/…`. */
internal fun normalizeAddonAddress(text: String): String {
    val trimmed = text.trim()
    return if (trimmed.startsWith("stremio://", ignoreCase = true)) "https://" + trimmed.substring("stremio://".length) else trimmed
}

internal fun sameAddonAddress(a: String, b: String): Boolean =
    normalizeAddonAddress(a).trimEnd('/').equals(normalizeAddonAddress(b).trimEnd('/'), ignoreCase = true)

/** What went wrong with a pasted address, as a sentence rather than a parser's report. */
internal fun addonAddressProblem(message: String?): String {
    val text = message?.trim().orEmpty()
    val notAManifest = text.isEmpty() || text.contains("JSON", ignoreCase = true) || text.contains("serializ", ignoreCase = true) ||
        text.contains("Unexpected", ignoreCase = true) || text.contains("Expected", ignoreCase = true)
    return if (notAManifest) {
        "What answers at that address is not an addon. An addon's address usually ends in /manifest.json."
    } else {
        text.lineSequence().first().take(300)
    }
}
