package com.debridone.app.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.manga.MangaPreferences
import com.debridone.manga.library.LibraryFilter
import com.debridone.manga.library.LibrarySort
import com.debridone.manga.library.LibraryStatus
import com.debridone.manga.library.MangaCollection
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.SeriesSummary
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** What the Library page is showing, kept while the reader looks at a series and comes back. */
internal object LibraryState {
    var type by mutableStateOf<MediaType?>(null)
    var status by mutableStateOf<LibraryStatus?>(null)
    var favorites by mutableStateOf(false)
    var collectionId by mutableStateOf<Long?>(null)
    var unreadOnly by mutableStateOf(false)
    var downloadedOnly by mutableStateOf(false)
    var localOnly by mutableStateOf(false)
    var query by mutableStateOf("")
    var selecting by mutableStateOf(false)
    var selected by mutableStateOf(setOf<Long>())
    var updating by mutableStateOf(false)
    var updateNote by mutableStateOf<String?>(null)

    fun reset() {
        type = null
        status = null
        favorites = false
        collectionId = null
        unreadOnly = false
        downloadedOnly = false
        localOnly = false
        query = ""
        selecting = false
        selected = emptySet()
        updating = false
        updateNote = null
    }
}

/** The profile's library: shelves by reading status, favourites and collections, with sorting and bulk actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MangaLibrary() {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    val scope = rememberCoroutineScope()
    val preferences = manga.services.preferences
    var sort by remember { mutableStateOf(runCatching { LibrarySort.valueOf(preferences[MangaPreferences.LIBRARY_SORT] ?: "TITLE") }.getOrDefault(LibrarySort.TITLE)) }
    var descending by remember { mutableStateOf(preferences.boolean(MangaPreferences.LIBRARY_SORT_DESCENDING, false)) }
    var sortMenu by remember { mutableStateOf(false) }
    var collectionMenu by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }

    val filter = LibraryFilter(
        mediaType = LibraryState.type, status = LibraryState.status, favoritesOnly = LibraryState.favorites, collectionId = LibraryState.collectionId,
        unreadOnly = LibraryState.unreadOnly, downloadedOnly = LibraryState.downloadedOnly, localOnly = LibraryState.localOnly,
        query = LibraryState.query, sort = sort, descending = descending,
    )
    val series by rememberMangaQuery(filter, initial = null as List<SeriesSummary>?) { services, profile -> services.repository.library(profile, filter) }
    val total by rememberMangaQuery(initial = 0) { services, profile -> services.repository.libraryCount(profile) }
    val collections by rememberMangaQuery(initial = emptyList<MangaCollection>()) { services, profile -> services.repository.collections(profile) }
    val duplicates by rememberMangaQuery(initial = 0) { services, profile -> services.repository.duplicates(profile).size }
    val listed = series.orEmpty()
    val selectedIds = LibraryState.selected.filter { id -> listed.any { it.series.id == id } }

    /** Asks every online series' source for new chapters, a few at a time. */
    fun updateAll() {
        if (LibraryState.updating) return
        LibraryState.updating = true
        LibraryState.updateNote = null
        scope.launch(Dispatchers.IO) {
            var added = 0
            var failed = 0
            val all = manga.services.repository.library(manga.profileId)
            all.forEachIndexed { index, summary ->
                LibraryState.updateNote = "Checking ${index + 1} of ${all.size}: ${summary.series.title.take(40)}"
                try {
                    added += if (summary.series.isLocal) manga.services.local.rescan(summary.series.id) else manga.services.sources.refresh(summary.series.id)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failed++
                }
            }
            LibraryState.updating = false
            LibraryState.updateNote = "${plural(added, "new chapter")}" + if (failed > 0) " · $failed series could not be checked" else ""
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(manga.style.coverWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 34.dp, end = 34.dp, bottom = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(manga.style.gridSpacing - 12.dp),
        verticalArrangement = Arrangement.spacedBy(manga.style.gridSpacing - 8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    MangaPageHeading("Library", "${plural(total, "series", "series")}" + if (listed.size != total) " · ${listed.size} shown" else "", Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                        if (LibraryState.updating) CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.width(18.dp))
                        MangaIconButton(Icons.Rounded.Refresh, "Check every series for new chapters", ::updateAll, enabled = !LibraryState.updating && total > 0)
                        MangaIconButton(
                            Icons.Rounded.Checklist, "Select series",
                            {
                                LibraryState.selecting = !LibraryState.selecting
                                LibraryState.selected = emptySet()
                            },
                            tint = if (LibraryState.selecting) manga.style.accent else tokens.colors.textSecondary, enabled = total > 0,
                        )
                        Box {
                            MangaButton("Add", { addMenu = true }, icon = Icons.Rounded.FolderOpen, primary = false)
                            DropdownMenu(addMenu, { addMenu = false }) {
                                MangaMenuItem("Comic archives (CBZ, ZIP)…", Icons.Rounded.InsertDriveFile) { addMenu = false; importLocal(manga, actions, MangaPickers.chooseArchives(), LibraryState.type) }
                                MangaMenuItem("A folder…", Icons.Rounded.FolderOpen) { addMenu = false; MangaPickers.chooseFolder()?.let { importLocal(manga, actions, listOf(it), LibraryState.type) } }
                                MangaCloudBridge.openCloudBrowser?.let { openCloud -> MangaMenuItem("From your cloud…", Icons.Rounded.CloudDownload) { addMenu = false; openCloud() } }
                                MangaMenuDivider()
                                MangaMenuItem("From a source…") { addMenu = false; MangaNav.root(MangaRoute.Discover) }
                            }
                        }
                    }
                }
                MangaSearchField(LibraryState.query, { LibraryState.query = it }, Modifier.fillMaxWidth(0.5f), placeholder = "Filter by title, author or tag")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MangaChip("All", LibraryState.type == null) { LibraryState.type = null }
                    MangaChip("Manga", LibraryState.type == MediaType.MANGA) { LibraryState.type = MediaType.MANGA }
                    MangaChip("Manhwa", LibraryState.type == MediaType.MANHWA) { LibraryState.type = MediaType.MANHWA }
                    Box {
                        MangaChip(LibraryState.status?.label ?: "Any status", LibraryState.status != null) { statusMenu = true }
                        DropdownMenu(statusMenu, { statusMenu = false }) {
                            MangaMenuItem("Any status", checked = LibraryState.status == null) { LibraryState.status = null; statusMenu = false }
                            MangaMenuDivider()
                            LibraryStatus.entries.forEach { status ->
                                MangaMenuItem(status.label, checked = LibraryState.status == status) { LibraryState.status = status; statusMenu = false }
                            }
                        }
                    }
                    MangaChip("Favorites", LibraryState.favorites, icon = Icons.Rounded.Favorite) { LibraryState.favorites = !LibraryState.favorites }
                    if (collections.isNotEmpty()) {
                        Box {
                            MangaChip(
                                collections.firstOrNull { it.id == LibraryState.collectionId }?.name ?: "Collections", LibraryState.collectionId != null,
                                icon = Icons.Rounded.CollectionsBookmark,
                            ) { collectionMenu = true }
                            DropdownMenu(collectionMenu, { collectionMenu = false }) {
                                MangaMenuItem("Every collection", checked = LibraryState.collectionId == null) { LibraryState.collectionId = null; collectionMenu = false }
                                MangaMenuDivider()
                                collections.forEach { collection ->
                                    MangaMenuItem("${collection.name}  (${collection.seriesCount})", checked = LibraryState.collectionId == collection.id) {
                                        LibraryState.collectionId = collection.id
                                        collectionMenu = false
                                    }
                                }
                                if (LibraryState.collectionId != null) {
                                    MangaMenuDivider()
                                    MangaMenuItem("Delete this collection", danger = true) {
                                        val id = LibraryState.collectionId
                                        collectionMenu = false
                                        LibraryState.collectionId = null
                                        if (id != null) actions.io { manga.services.repository.deleteCollection(id) }
                                    }
                                }
                            }
                        }
                    }
                    MangaChip("Unread", LibraryState.unreadOnly) { LibraryState.unreadOnly = !LibraryState.unreadOnly }
                    MangaChip("Downloaded", LibraryState.downloadedOnly, icon = Icons.Rounded.Download) { LibraryState.downloadedOnly = !LibraryState.downloadedOnly }
                    MangaChip("Local files", LibraryState.localOnly) { LibraryState.localOnly = !LibraryState.localOnly }
                    Box {
                        MangaChip("${sort.label}${if (descending) " ↓" else " ↑"}", false, icon = Icons.AutoMirrored.Rounded.Sort) { sortMenu = true }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            LibrarySort.entries.forEach { option ->
                                MangaMenuItem(option.label, checked = sort == option) {
                                    sort = option
                                    preferences.set(MangaPreferences.LIBRARY_SORT, option.name)
                                    sortMenu = false
                                }
                            }
                            MangaMenuDivider()
                            MangaMenuItem(if (descending) "Reverse order: on" else "Reverse order: off", checked = descending) {
                                descending = !descending
                                preferences.set(MangaPreferences.LIBRARY_SORT_DESCENDING, descending.toString())
                                sortMenu = false
                            }
                        }
                    }
                }
                LibraryState.updateNote?.let { Text(it, style = small(), color = tokens.colors.textMuted) }
                if (duplicates > 0 && !LibraryState.selecting) {
                    Notice(
                        "${plural(duplicates, "title")} in your library ${if (duplicates == 1) "appears" else "appear"} more than once (from different sources). " +
                            "They are kept apart on purpose; open one and use Change Source if you want to move to one edition.",
                        PillTone.NEUTRAL,
                    )
                }
                if (LibraryState.selecting) BulkBar(listed, selectedIds)
            }
        }

        if (series != null && listed.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (total == 0) {
                    MangaEmptyState(BookIcon, "Your library is empty", "Add a series from a source, or files you already have.") {
                        MangaButton("Discover", { MangaNav.root(MangaRoute.Discover) })
                        MangaButton("Add files", { importLocal(manga, actions, MangaPickers.chooseArchives()) }, primary = false, icon = Icons.Rounded.InsertDriveFile)
                        MangaButton("Add a folder", { MangaPickers.chooseFolder()?.let { importLocal(manga, actions, listOf(it)) } }, primary = false, icon = Icons.Rounded.FolderOpen)
                        MangaCloudBridge.openCloudBrowser?.let { openCloud -> MangaButton("From your cloud", openCloud, primary = false, icon = Icons.Rounded.CloudDownload) }
                    }
                } else {
                    MangaEmptyState(BookIcon, "Nothing matches", "No series in your library fits these filters.") { MangaButton("Clear filters", { LibraryState.reset() }, primary = false) }
                }
            }
        }

        items(listed, key = { it.series.id }) { summary ->
            val id = summary.series.id
            if (LibraryState.selecting) {
                SeriesCard(summary, selected = id in LibraryState.selected) {
                    LibraryState.selected = if (id in LibraryState.selected) LibraryState.selected - id else LibraryState.selected + id
                }
            } else {
                SeriesCard(summary)
            }
        }
    }
}

/** What can be done to several series at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulkBar(listed: List<SeriesSummary>, selectedIds: List<Long>) {
    val manga = LocalManga.current
    val actions = LocalMangaActions.current
    val tokens = MaterialTheme.nuvio
    var statusMenu by remember { mutableStateOf(false) }
    val some = selectedIds.isNotEmpty()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MangaChip(if (selectedIds.size == listed.size && listed.isNotEmpty()) "Select none" else "Select all", false) {
            LibraryState.selected = if (selectedIds.size == listed.size) emptySet() else listed.map { it.series.id }.toSet()
        }
        Box {
            MangaButton("Set status", { statusMenu = true }, primary = false, enabled = some)
            DropdownMenu(statusMenu, { statusMenu = false }) {
                LibraryStatus.entries.forEach { status ->
                    MangaMenuItem(status.label) {
                        statusMenu = false
                        actions.io { selectedIds.forEach { manga.services.repository.setLibraryStatus(it, status) } }
                    }
                }
            }
        }
        MangaButton("Collections…", { MangaNav.dialog = MangaDialog.Collections(selectedIds) }, primary = false, enabled = some, icon = Icons.Rounded.CollectionsBookmark)
        MangaButton("Mark read", { actions.io { selectedIds.forEach { manga.services.repository.setAllRead(it, true) } } }, primary = false, enabled = some)
        MangaButton("Mark unread", { actions.io { selectedIds.forEach { manga.services.repository.setAllRead(it, false) } } }, primary = false, enabled = some)
        MangaButton("Favorite", { actions.io { selectedIds.forEach { manga.services.repository.setFavorite(it, true) } } }, primary = false, enabled = some, icon = Icons.Rounded.Favorite)
        MangaButton("Download unread", {
            actions.io {
                val ids = selectedIds.flatMap { id -> manga.services.repository.chapters(id).filter { !it.read && it.downloadState == null }.map { it.id } }
                val online = ids.filter { id -> manga.services.repository.chapter(id)?.let { manga.services.repository.series(it.seriesId)?.isLocal } == false }
                MangaDownloadActions.queue(manga, online)
            }
        }, primary = false, enabled = some, icon = Icons.Rounded.Download)
        MangaButton("Refresh details", {
            actions.io {
                var changed = 0
                selectedIds.forEach { id -> changed += runCatching { manga.services.metadata.refresh(id).size }.getOrDefault(0) }
                MangaCovers.clear()
                MangaNav.message = if (changed == 0) "Details are up to date" else "${plural(changed, "field")} updated (your locked fields were kept)"
            }
        }, primary = false, enabled = some, icon = Icons.Rounded.Sync)
        MangaButton("Remove from library", {
            actions.io { selectedIds.forEach { manga.services.repository.setInLibrary(it, false) } }
            LibraryState.selected = emptySet()
        }, primary = false, enabled = some)
        MangaButton("Forget…", { MangaNav.dialog = MangaDialog.RemoveSeries(selectedIds) }, primary = false, enabled = some, danger = true, icon = Icons.Rounded.Delete)
        Text("${selectedIds.size} selected", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted, modifier = Modifier.padding(top = 9.dp))
    }
}
