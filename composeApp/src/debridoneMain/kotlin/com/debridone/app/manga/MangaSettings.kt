package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.debridone.app.shell.ModuleAppearanceEditor
import com.debridone.app.shell.ModuleSettingsPanel
import com.nuvio.app.core.ui.PluginConfigurationPanel
import com.debridone.app.shell.SettingsCategory
import com.debridone.manga.library.MediaType
import com.debridone.module.ModuleId
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A titled block of settings. */
@Composable
internal fun SettingsBlock(title: String, caption: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(18.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = tokens.colors.textPrimary)
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        content()
    }
}

/** The pages of Manga's settings, in the order they are listed. */
internal object MangaSettingsSections {
    const val APPEARANCE = "appearance"
    const val READING = "reading"
    const val LIBRARY = "library"
    const val EXTENSIONS = "extensions"
    const val STORES = "stores"
    const val SOURCES = "sources"
    const val METADATA = "metadata"
    const val SERVER = "server"

    val all = listOf(
        SettingsCategory(APPEARANCE, "Appearance", Icons.Rounded.Palette),
        SettingsCategory(READING, "Reading", Icons.Rounded.AutoStories),
        SettingsCategory(LIBRARY, "Library", Icons.Rounded.CollectionsBookmark),
        SettingsCategory(EXTENSIONS, "Extensions", Icons.Rounded.Extension),
        SettingsCategory(STORES, "Extension stores", Icons.Rounded.Storefront),
        SettingsCategory(SOURCES, "Sources", Icons.Rounded.Public),
        SettingsCategory(METADATA, "Metadata", Icons.Rounded.Label),
        SettingsCategory(SERVER, "Server", Icons.Rounded.Dns),
    )

    /** The pages that are also tabs of Plugins › Manga: one implementation, shown in both places. */
    fun of(tab: MangaPluginsTab): String = when (tab) {
        MangaPluginsTab.EXTENSIONS -> EXTENSIONS
        MangaPluginsTab.STORES -> STORES
        MangaPluginsTab.SOURCES -> SOURCES
        MangaPluginsTab.METADATA -> METADATA
        MangaPluginsTab.SERVER -> SERVER
    }
}

/**
 * Manga's settings, inside Manga: a panel over the page that was open, which stays as it was
 * underneath. Appearance, reading, the library's files and backups, and everything about where
 * manga comes from (extensions, stores, sources, metadata, the server) are here; nothing sends
 * the reader to another part of the application.
 */
@Composable
internal fun MangaSettingsPanel(modifier: Modifier = Modifier) {
    val manga = LocalManga.current
    val connection by manga.services.server.state.collectAsState()
    ModuleSettingsPanel(
        open = MangaNav.settingsOpen,
        title = "Manga settings",
        categories = MangaSettingsSections.all,
        selected = MangaNav.settingsSection,
        onSelect = { MangaNav.settingsSection = it },
        onClose = { MangaNav.settingsOpen = false },
        accent = manga.style.accent,
        modifier = modifier,
        selfScrolling = setOf(MangaSettingsSections.EXTENSIONS, MangaSettingsSections.STORES, MangaSettingsSections.SOURCES, MangaSettingsSections.METADATA, MangaSettingsSections.SERVER),
    ) { section ->
        // What lives in the manga server can only be shown while it runs; the rest is always there.
        val needsServer = section == MangaSettingsSections.EXTENSIONS || section == MangaSettingsSections.STORES || section == MangaSettingsSections.SOURCES
        when {
            needsServer && connection !is com.debridone.manga.suwayomi.MangaConnection.Ready -> MangaServerStatus(connection)
            section == MangaSettingsSections.APPEARANCE -> ModuleAppearanceEditor(ModuleId.MANGA)
            section == MangaSettingsSections.READING -> ReadingSettings()
            section == MangaSettingsSections.LIBRARY -> LibrarySettings()
            section == MangaSettingsSections.EXTENSIONS -> ExtensionsTab()
            section == MangaSettingsSections.STORES -> StoresTab()
            section == MangaSettingsSections.SOURCES -> SourcesTab()
            section == MangaSettingsSections.METADATA -> MetadataTab()
            section == MangaSettingsSections.SERVER -> ServerTab()
        }
    }
    // A source's own settings open over the panel, and close back to it.
    MangaSourceConfigurationPanel(modifier)
    MangaPluginsState.pendingStore?.let { address -> ExtensionNoticeDialog(address) }
}

/** The settings of the source chosen with "Configure", wherever that was clicked. */
@Composable
internal fun MangaSourceConfigurationPanel(modifier: Modifier = Modifier) {
    val manga = LocalManga.current
    val sourceId = MangaPluginsState.configuring
    // The name is kept while the panel slides away, so its title does not blank out.
    val name = remember(sourceId) { sourceId?.let { manga.services.sources.find(it)?.name } }
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    if (sourceId != null) shown = sourceId to (name ?: "Source")
    PluginConfigurationPanel(
        open = sourceId != null,
        title = shown?.second ?: "Source",
        kind = "Manga source · settings defined by its extension",
        onClose = { MangaPluginsState.configuring = null },
        modifier = modifier,
    ) { shown?.first?.let { SourcePreferencesEditor(it) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingSettings() {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    val preferences by manga.services.preferences.values.collectAsState()
    Text(
        "Manga and Manhwa each keep their own way of reading, for this profile. A series can also be given settings of its own from its page or inside the reader.",
        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MediaType.entries.forEach { type ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(type.label, style = MaterialTheme.typography.titleMedium, color = manga.style.accent)
                val settings = remember(preferences, type) { manga.services.preferences.readerSettings(type) }
                ReaderSettingsPanel(
                    settings = settings, shelf = type.label, ownSettings = null,
                    onChange = { manga.services.preferences.setReaderSettings(type, it) }, onOwnSettings = {}, onClose = null,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibrarySettings() {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    val preferences by manga.services.preferences.values.collectAsState()
    val sources by rememberMangaSources()
    val chosen = remember(preferences) { manga.services.preferences.sourceLanguages() }
    // The languages the installed sources publish in, plus any already chosen.
    val languages = remember(sources, chosen) {
        (sources.map { it.language }.filter { it.isNotBlank() && it != "all" } + chosen).distinct().sortedBy(::languageName)
    }
    val revision by manga.services.repository.revision.collectAsState()
    val cacheSize by produceState(0L, revision / 50) { value = withContext(Dispatchers.IO) { manga.services.pages.cacheSize() } }

    SettingsBlock("Your own files", "CBZ, ZIP, CBR, RAR and EPUB files, a folder of chapters or a folder of images. They are read where they are, through the manga server's Local source; nothing is copied.") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MangaButton("Add files", { importLocal(manga, actions, MangaPickers.chooseArchives()) }, primary = false, icon = Icons.Rounded.InsertDriveFile)
            MangaButton("Add a folder", { MangaPickers.chooseFolder()?.let { importLocal(manga, actions, listOf(it)) } }, primary = false, icon = Icons.Rounded.FolderOpen)
        }
        if (!manga.services.local.available) {
            Text("This profile reads from a manga server on another computer, which cannot open files of this one.", style = small(), color = tokens.colors.warning)
        }
    }
    SettingsBlock(
        "Source languages",
        "An extension often brings one source per language. Sources in these languages are switched on when they appear; any single source can still be switched on or off under Sources. With none chosen, every language is on.",
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MangaChip("All languages", chosen.isEmpty()) {
                manga.services.preferences.setSourceLanguages(emptySet())
                manga.services.sources.invalidate()
            }
            languages.forEach { language ->
                MangaChip(languageName(language), language in chosen) {
                    val next = if (language in chosen) chosen - language else chosen + language
                    // Deselecting the last language would mean "all"; keep one instead.
                    if (next.isNotEmpty()) {
                        manga.services.preferences.setSourceLanguages(next)
                        manga.services.sources.invalidate()
                    }
                }
            }
        }
    }
    SettingsBlock(
        "Library backup",
        "One file with this profile's library, in the backup format of Suwayomi and Mihon (.tachibk): the series and where each comes from, categories, read chapters, trackers, and what DebridOne keeps with them (reading status, favourites, your edits, bookmarks). It holds no pages, downloads or passwords.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MangaButton("Save a backup…", {
                MangaPickers.saveFile("Save the Manga library", "debridone-manga-library.tachibk")?.let { target ->
                    actions.io {
                        val count = manga.services.repository.exportBackup(target)
                        MangaNav.message = "${plural(count, "series", "series")} saved to ${target.fileName}"
                    }
                }
            }, primary = false, icon = Icons.Rounded.Save)
            MangaButton("Restore from a backup…", {
                MangaPickers.chooseBackup()?.let { source ->
                    actions.io {
                        MangaNav.message = "Restoring ${source.fileName}…"
                        val restored = manga.services.repository.restoreBackup(source)
                        MangaNav.message = "Restored ${plural(restored, "series", "series")}"
                    }
                }
            }, primary = false, icon = Icons.Rounded.SettingsBackupRestore)
        }
        Text("Restoring adds to the library that is here; nothing already in it is removed. A backup made by Mihon or another Suwayomi can be restored too.", style = small(), color = tokens.colors.textMuted)
    }
    SettingsBlock("History", "What you read, most recent first, is kept for Continue Reading.") {
        MangaButton("Clear reading history", {
            actions.io {
                manga.services.repository.clearHistory(manga.profileId)
                MangaNav.message = "History cleared. Your place in each chapter is kept."
            }
        }, primary = false)
    }
    SettingsBlock("Storage", "Covers from metadata providers and the pages just read are kept so they show at once: ${formatBytes(cacheSize)} now.") {
        MangaButton("Empty the cache", {
            actions.io {
                manga.services.pages.clearCache()
                MangaCovers.clear()
                MangaNav.message = "Cache emptied. Downloads were not touched."
            }
        }, primary = false)
    }
}
