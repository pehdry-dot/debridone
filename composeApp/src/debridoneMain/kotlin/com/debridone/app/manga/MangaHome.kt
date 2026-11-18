package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debridone.manga.library.ContinueEntry
import com.debridone.manga.library.LibraryFilter
import com.debridone.manga.library.LibrarySort
import com.debridone.manga.library.MangaCollection
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.SeriesSummary
import com.debridone.manga.metadata.MetadataMatch
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class HomeData(
    val continueReading: List<ContinueEntry> = emptyList(),
    val updated: List<SeriesSummary> = emptyList(),
    val manga: List<SeriesSummary> = emptyList(),
    val manhwa: List<SeriesSummary> = emptyList(),
    val library: List<SeriesSummary> = emptyList(),
    val recentlyAdded: List<SeriesSummary> = emptyList(),
    val favorites: List<SeriesSummary> = emptyList(),
    val downloaded: List<SeriesSummary> = emptyList(),
    val collections: List<Pair<MangaCollection, List<SeriesSummary>>> = emptyList(),
    val libraryCount: Int = -1,
)

/**
 * Manga's home: what to carry on with, what is new, and what the library holds — each shelf filled
 * only from what is really there (the library, the installed sources, AniList's public charts).
 * With nothing set up yet it shows how to begin instead.
 */
@Composable
internal fun MangaHome() {
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    val sources by rememberMangaSources()
    val data by rememberMangaQuery(initial = HomeData()) { services, profile ->
        val library = services.repository.library(profile)
        HomeData(
            continueReading = services.repository.continueReading(profile, 18),
            updated = library.filter { it.unreadCount > 0 && it.readCount > 0 }.sortedByDescending { it.latestChapterAt ?: 0 }.take(24),
            manga = library.filter { it.series.mediaType == MediaType.MANGA }.sortedByDescending { it.series.lastReadAt ?: it.series.addedAt ?: 0 }.take(24),
            manhwa = library.filter { it.series.mediaType == MediaType.MANHWA }.sortedByDescending { it.series.lastReadAt ?: it.series.addedAt ?: 0 }.take(24),
            library = library.take(24),
            recentlyAdded = services.repository.library(profile, LibraryFilter(sort = LibrarySort.DATE_ADDED)).take(24),
            favorites = library.filter { it.series.favorite }.take(24),
            downloaded = library.filter { it.downloadedCount > 0 }.sortedByDescending { it.downloadedCount }.take(24),
            collections = services.repository.collections(profile).filter { it.seriesCount > 0 }.take(6).map { collection ->
                collection to services.repository.library(profile, LibraryFilter(collectionId = collection.id)).take(24)
            },
            libraryCount = library.size,
        )
    }
    val sourceCount = sources.count { !it.isLocal && it.enabled }
    // AniList's public charts: titles to look for, not titles to read. Clicking one searches the sources.
    val popularManga by produceState(emptyList<MetadataMatch>(), manga.profileId) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.metadata.popular(MediaType.MANGA) }.getOrDefault(emptyList()) }
    }
    val popularManhwa by produceState(emptyList<MetadataMatch>(), manga.profileId) {
        value = withContext(Dispatchers.IO) { runCatching { manga.services.metadata.popular(MediaType.MANHWA) }.getOrDefault(emptyList()) }
    }
    fun lookFor(match: MetadataMatch) {
        SearchState.reset()
        SearchState.text = match.title
        MangaNav.root(MangaRoute.Search)
        MangaNav.message = if (sourceCount == 0) "Add a source to read \"${match.title}\"." else "Press Return to search your sources for \"${match.title}\"."
    }

    if (data.libraryCount < 0) return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // Somebody who used the earlier version is told first what became of their library.
        item { MigrationCard(Modifier.padding(start = 40.dp, end = 40.dp, top = 8.dp)) }
        if (data.libraryCount == 0 && data.continueReading.isEmpty()) {
            item { HomeSetup(sourceCount) }
        } else {
            item {
                MangaPageHeading(
                    "Manga", "${plural(data.libraryCount, "series", "series")} in your library" + if (sourceCount > 0) " · ${plural(sourceCount, "source")}" else "",
                    Modifier.padding(horizontal = 40.dp),
                )
            }
        }
        item {
            CoverShelf("Continue Reading", data.continueReading, key = { it.summary.series.id }) { entry, modifier ->
                // Short enough for a card: "Ch. 12 · page 7".
                val chapter = if (entry.chapter.chapterNumber.isNotBlank()) "Ch. ${entry.chapter.chapterNumber}" else entry.chapter.title.take(18).ifBlank { "Chapter" }
                val page = if (entry.chapter.lastPage > 0) " · page ${entry.chapter.lastPage + 1}" else ""
                SeriesCard(entry.summary, modifier, caption = chapter + page, detailsAction = true) { actions.read(entry.chapter.id) }
            }
        }
        item { CoverShelf("Recently Updated", data.updated, caption = "Series you are reading with unread chapters", key = { it.series.id }) { summary, modifier -> SeriesCard(summary, modifier) } }
        // The two shelves, only once both hold something: with one alone, "Your Library" already says it all.
        if (data.manga.isNotEmpty() && data.manhwa.isNotEmpty()) {
            item { CoverShelf("Manga", data.manga, key = { it.series.id }) { summary, modifier -> SeriesCard(summary, modifier) } }
            item { CoverShelf("Manhwa", data.manhwa, key = { it.series.id }) { summary, modifier -> SeriesCard(summary, modifier) } }
        }
        item {
            CoverShelf(
                "Your Library", data.library, caption = plural(data.libraryCount, "series", "series"),
                action = "See all", onAction = { MangaNav.root(MangaRoute.Library) }, key = { it.series.id },
            ) { summary, modifier -> SeriesCard(summary, modifier) }
        }
        item { CoverShelf("Recently Added", data.recentlyAdded, key = { it.series.id }) { summary, modifier -> SeriesCard(summary, modifier) } }
        item { CoverShelf("Favorites", data.favorites, key = { it.series.id }) { summary, modifier -> SeriesCard(summary, modifier) } }
        item {
            CoverShelf(
                "Downloaded Chapters", data.downloaded, caption = "Readable without a connection",
                action = "Downloads", onAction = { MangaNav.root(MangaRoute.Downloads) }, key = { it.series.id },
            ) { summary, modifier -> SeriesCard(summary, modifier, caption = plural(summary.downloadedCount, "chapter") + " saved") }
        }
        data.collections.forEach { (collection, series) ->
            item(key = "collection-${collection.id}") {
                CoverShelf(collection.name, series, caption = "Your collection · ${plural(collection.seriesCount, "series", "series")}", key = { it.series.id }) { summary, modifier ->
                    SeriesCard(summary, modifier)
                }
            }
        }
        // AniList's public charts come last: they are titles to look for, not titles that can be read here.
        item {
            CoverShelf("Popular Manga", popularManga, caption = "Trending on AniList, not from your sources · click one to look for it in them", key = { it.remoteId }) { match, modifier ->
                MetadataCard(match.title, listOfNotNull(match.status, match.year?.toString()).joinToString(" · "), match.coverUrl, MediaType.MANGA, modifier) { lookFor(match) }
            }
        }
        item {
            CoverShelf("Popular Manhwa", popularManhwa, caption = "Trending on AniList, not from your sources · click one to look for it in them", key = { it.remoteId }) { match, modifier ->
                MetadataCard(match.title, listOfNotNull(match.status, match.year?.toString()).joinToString(" · "), match.coverUrl, MediaType.MANHWA, modifier) { lookFor(match) }
            }
        }
    }
}

/** Shown while there is nothing to read yet: the two ways to begin. */
@Composable
private fun HomeSetup(sourceCount: Int) {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Manga & Manhwa", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 38.sp, fontWeight = FontWeight.Bold), color = tokens.colors.textPrimary)
        Text(
            if (sourceCount == 0) "Read from the sources of the extensions you install, or from files you already have."
            else "${plural(sourceCount, "source")} ready. Find something to read, or add files you already have.",
            style = MaterialTheme.typography.bodyLarge, color = tokens.colors.textSecondary,
        )
        Row(Modifier.padding(top = 18.dp).widthIn(max = 900.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SetupCard(
                Icons.Rounded.Extension,
                if (sourceCount == 0) "Add sources" else "Browse your sources",
                if (sourceCount == 0) "Sources come with extensions: the Mihon/Tachiyomi extensions that the manga server (Suwayomi) runs. Add an extension store you trust, then install the extensions you want."
                else "Discover shows each source's catalogue; Search looks through all of them.",
                Modifier.weight(1f),
            ) {
                if (sourceCount == 0) MangaButton("Add extensions", { openMangaPlugins() }, icon = Icons.Rounded.Extension)
                else MangaButton("Discover", { MangaNav.root(MangaRoute.Discover) })
            }
            SetupCard(
                Icons.Rounded.FolderOpen, "Read your own files",
                "CBZ, ZIP, CBR, RAR and EPUB files, a folder of chapters, or a folder of images. They are read where they are; nothing is copied or uploaded, and no extension is needed.",
                Modifier.weight(1f),
            ) {
                MangaButton("Add files", { importLocal(manga, actions, MangaPickers.chooseArchives()) }, icon = Icons.Rounded.InsertDriveFile, primary = sourceCount > 0)
                MangaButton("Add a folder", { MangaPickers.chooseFolder()?.let { importLocal(manga, actions, listOf(it)) } }, icon = Icons.Rounded.FolderOpen, primary = false)
                MangaCloudBridge.openCloudBrowser?.let { openCloud -> MangaButton("From your cloud", openCloud, icon = Icons.Rounded.CloudDownload, primary = false) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(20.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(LocalManga.current.style.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = LocalManga.current.style.accent, modifier = Modifier.size(24.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = tokens.colors.textPrimary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        // The buttons go onto a second line rather than being cut off in a narrow card.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) { actions() }
    }
}
