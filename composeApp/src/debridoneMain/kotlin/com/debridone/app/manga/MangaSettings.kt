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
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.shell.SettingsPage
import com.debridone.app.shell.ShellSection
import com.debridone.app.shell.ShellState
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

/**
 * Manga's own settings for the active profile: how Manga and how Manhwa are read, which chapter
 * languages are listed, the profile's files and backups. Sources, metadata providers and the
 * extension host are in Plugins › Manga; colours and card sizes in Settings › Appearance.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MangaSettings() {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    val preferences by manga.services.preferences.values.collectAsState()
    val hostState by manga.services.extensions.state.collectAsStateWithLifecycle()
    val chosen = remember(preferences) { manga.services.preferences.chapterLanguages() }
    // The languages the installed sources publish in, plus any already chosen.
    val languages = remember(hostState, chosen) {
        (hostState?.sources?.mapNotNull { it.language }.orEmpty().filter { it != "MULTI" } + COMMON_LANGUAGES + chosen).distinct().sortedBy(::languageName)
    }
    val revision by manga.services.repository.revision.collectAsState()
    val cacheSize by produceState(0L, revision / 50) { value = withContext(Dispatchers.IO) { manga.services.pages.cacheSize() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 40.dp, end = 40.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { MangaPageHeading("Settings", "For this profile. Each profile has its own reading settings.") }
        item {
            Text("Reading", style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
            Text(
                "Manga and Manhwa each keep their own way of reading. A series can also be given settings of its own from its page or inside the reader.",
                style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary,
            )
        }
        item {
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
        item {
            SettingsBlock("Chapter languages", "Sources such as MangaDex list every translation of a chapter. Choose the languages to list and count; with none chosen, all are shown.") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MangaChip("All languages", chosen.isEmpty()) {
                        manga.services.preferences.setChapterLanguages(emptySet())
                        manga.services.repository.setChapterLanguages(manga.profileId, emptySet())
                    }
                    languages.forEach { language ->
                        MangaChip(languageName(language), language in chosen) {
                            val next = if (language in chosen) chosen - language else chosen + language
                            manga.services.preferences.setChapterLanguages(next)
                            manga.services.repository.setChapterLanguages(manga.profileId, next)
                        }
                    }
                }
            }
        }
        item {
            SettingsBlock("Your own files", "CBZ and ZIP archives, a folder of chapters or a folder of images. They are read in place and need no source.") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaButton("Add files", { importLocal(manga, actions, MangaPickers.chooseArchives()) }, primary = false, icon = Icons.Rounded.InsertDriveFile)
                    MangaButton("Add a folder", { MangaPickers.chooseFolder()?.let { importLocal(manga, actions, listOf(it)) } }, primary = false, icon = Icons.Rounded.FolderOpen)
                }
            }
        }
        item {
            SettingsBlock(
                "Library backup",
                "One file with this profile's library: where each series comes from, your edits and locks, reading progress, bookmarks and collections. It holds no pages, downloads or passwords.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaButton("Save a backup…", {
                        MangaPickers.saveFile("Save the Manga library", "debridone-manga-library.json")?.let { target ->
                            actions.io {
                                val count = manga.services.backup.export(manga.profileId, target)
                                MangaNav.message = "${plural(count, "series", "series")} saved to ${target.fileName}"
                            }
                        }
                    }, primary = false, icon = Icons.Rounded.Save)
                    MangaButton("Restore from a backup…", {
                        MangaPickers.chooseBackup()?.let { source ->
                            actions.io {
                                val summary = manga.services.backup.restore(manga.profileId, source)
                                MangaNav.message = "Restored ${plural(summary.series, "series", "series")} with progress on ${plural(summary.chapters, "chapter")}"
                            }
                        }
                    }, primary = false, icon = Icons.Rounded.SettingsBackupRestore)
                }
                Text("Restoring adds to the library that is here; nothing already in it is removed.", style = small(), color = tokens.colors.textMuted)
            }
        }
        item {
            SettingsBlock("History", "What you read, most recent first, is kept for Continue Reading.") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaButton("Clear reading history", {
                        actions.io {
                            manga.services.repository.clearHistory(manga.profileId)
                            MangaNav.message = "History cleared. Your place in each chapter is kept."
                        }
                    }, primary = false)
                }
            }
        }
        item {
            SettingsBlock("Storage", "Pages you have read online are kept for a while so they open at once next time: ${formatBytes(cacheSize)} now.") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaButton("Empty the page cache", {
                        actions.io {
                            manga.services.pages.clearCache()
                            MangaCovers.clear()
                            MangaNav.message = "Cache emptied. Downloads were not touched."
                        }
                    }, primary = false)
                }
            }
        }
        item {
            SettingsBlock("Elsewhere", modifier = Modifier.widthIn(max = 900.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MangaButton("Sources and extensions", { openMangaPlugins(MangaPluginsTab.INSTALLED) }, primary = false, icon = Icons.Rounded.Extension)
                    MangaButton("Metadata providers", { openMangaPlugins(MangaPluginsTab.METADATA) }, primary = false)
                    MangaButton("Extension host, cache and network", { openMangaPlugins(MangaPluginsTab.SETTINGS) }, primary = false)
                    MangaButton("Colours, covers and cards", {
                        ShellState.selectAppearanceModule(ModuleId.MANGA)
                        ShellState.selectSettingsPage(SettingsPage.APPEARANCE)
                        ShellState.select(ShellSection.SETTINGS)
                    }, primary = false, icon = Icons.Rounded.Palette)
                }
            }
        }
    }
}

private val COMMON_LANGUAGES = listOf("ENGLISH", "SPANISH_ES", "SPANISH_LATAM", "PORTUGUESE_BR", "PORTUGUESE_PT", "FRENCH", "GERMAN", "ITALIAN", "JAPANESE", "KOREAN")
