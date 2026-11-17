package com.debridone.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debridone.app.streaming.StreamingDownloadsTab
import com.debridone.app.manga.MangaDownloads
import com.debridone.app.manga.MangaProvider
import com.debridone.module.ModuleId

/** Downloads, one tab per section. Each tab shows only that section's own downloads. */
@Composable
internal fun DownloadsScreen() {
    var tabKey by rememberSaveable { mutableStateOf(ModuleId.STREAMING.key) }
    val tab = ModuleId.fromKey(tabKey) ?: ModuleId.STREAMING
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = topInset + 20.dp, bottom = 8.dp)) {
            SegmentedTabs(
                items = ModuleId.active.map { it to it.displayName },
                selected = tab,
                onSelect = { tabKey = it.key },
            )
        }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                ModuleId.STREAMING -> StreamingDownloadsTab(
                    // Playback belongs to Streaming, so opening a download continues there.
                    onOpenInStreaming = { ShellState.select(ShellSection.STREAMING) },
                )
                // Manga's saved chapters, from Manga's own download queue.
                ModuleId.MANGA -> MangaProvider { MangaDownloads(embedded = true) }
                ModuleId.MUSIC -> ShellPage(
                    title = "Music downloads",
                    subtitle = "Songs saved from your music servers are listed in Music, under Library › Available offline.",
                ) {}
                else -> Unit
            }
        }
    }
}
