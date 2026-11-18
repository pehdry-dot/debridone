package com.debridone.app.streaming

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debridone.app.shell.SegmentedTabs
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonConfigurationPanel
import com.nuvio.app.features.addons.AddonsScreen
import com.nuvio.app.features.downloads.DownloadsScreen
import com.nuvio.app.features.settings.PluginsSettingsScreen

/**
 * Streaming's side of the shell screens. Everything here is Nuvio's own UI and Nuvio's own
 * repositories: its addon system, its plugin (provider) system and its download manager. Nothing
 * from Music or Manga is referenced.
 */
@Composable
internal fun StreamingPluginsTab() {
    var page by rememberSaveable { mutableStateOf("addons") }
    Column(Modifier.fillMaxSize()) {
        if (AppFeaturePolicy.pluginsEnabled) {
            SegmentedTabs(
                items = listOf("addons" to "Addons", "providers" to "Provider plugins"),
                selected = page,
                onSelect = { page = it },
                modifier = Modifier.padding(start = 40.dp, top = 4.dp, bottom = 4.dp),
            )
        }
        Box(Modifier.fillMaxSize()) {
            if (page == "providers" && AppFeaturePolicy.pluginsEnabled) {
                PluginsSettingsScreen(onBack = { page = "addons" })
            } else {
                AddonsScreen(modifier = Modifier.fillMaxSize())
            }
            // An addon's "Configure" opens here, in this tab, over the list it was clicked in.
            AddonConfigurationPanel()
        }
    }
}

/** Streaming downloads, shown by Nuvio's own downloads screen. */
@Composable
internal fun StreamingDownloadsTab(onOpenInStreaming: () -> Unit) {
    DownloadsScreen(
        onBack = {},
        onOpenDownload = { onOpenInStreaming() },
    )
}
