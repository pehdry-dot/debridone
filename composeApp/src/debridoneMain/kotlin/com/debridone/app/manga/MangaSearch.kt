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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.manga.library.LibraryFilter
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.SeriesSummary
import com.debridone.manga.sources.SourceEntry
import com.debridone.manga.sources.SourceSearchResult
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The search page's state, kept while the user looks at a series and comes back. */
internal object SearchState {
    var text by mutableStateOf("")

    /** The text the results on screen belong to. */
    var searched by mutableStateOf("")
    var type by mutableStateOf<MediaType?>(null)

    /** Null: every enabled source. */
    var sourceIds by mutableStateOf<Set<String>?>(null)
    var language by mutableStateOf<String?>(null)
    val results = mutableStateListOf<SourceSearchResult>()
    var pending by mutableStateOf(0)
    var job: Job? = null

    fun reset() {
        job?.cancel()
        text = ""
        searched = ""
        type = null
        sourceIds = null
        language = null
        results.clear()
        pending = 0
    }
}

/**
 * Opens where extension stores, extensions, sources and the like are managed: Manga's own settings
 * panel, on the matching page. The reader stays in Manga, on the page they were on.
 */
internal fun openMangaPlugins(tab: MangaPluginsTab = MangaPluginsTab.INSTALLED) {
    MangaPluginsState.tab = tab
    MangaNav.openSettings(MangaSettingsSections.of(tab))
}

/** The name of a source's language code ("en", "pt-BR"), in English. */
internal fun languageName(code: String?): String = when {
    code.isNullOrBlank() -> ""
    code == "all" -> "Multiple languages"
    code == "other" -> "Other"
    else -> java.util.Locale.forLanguageTag(code).getDisplayName(java.util.Locale.ENGLISH).ifBlank { code }
}

/**
 * Search across the profile's sources. Every source answers on its own: results appear as they
 * arrive, grouped by source, and a source that fails says why without holding up the others. The
 * same title from two sources stays two entries.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MangaSearch() {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    val scope = rememberCoroutineScope()
    val sources by rememberMangaQuery(initial = emptyList<SourceEntry>()) { services, profile -> services.sources.enabled(profile) }
    val libraryMatches by rememberMangaQuery(SearchState.searched, initial = emptyList<SeriesSummary>()) { services, profile ->
        if (SearchState.searched.isBlank()) emptyList() else services.repository.library(profile, LibraryFilter(query = SearchState.searched))
    }
    val languages = sources.map { it.language }.filter { it.isNotBlank() }.distinct().sorted()
    val candidates = sources.filter { SearchState.language == null || it.language == SearchState.language }

    fun search() {
        val text = SearchState.text.trim()
        if (text.isEmpty()) return
        SearchState.job?.cancel()
        SearchState.results.clear()
        SearchState.searched = text
        val ids = candidates.filter { SearchState.sourceIds == null || it.id in SearchState.sourceIds!! }.map { it.id }
        SearchState.pending = ids.size
        if (ids.isEmpty()) return
        SearchState.job = scope.launch(Dispatchers.IO) {
            try {
                manga.services.sources.search(manga.profileId, text, ids) { result ->
                    SearchState.results += result
                    SearchState.pending = (SearchState.pending - 1).coerceAtLeast(0)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                MangaNav.message = failure.message ?: "The search could not be run."
            } finally {
                SearchState.pending = 0
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(Modifier.padding(horizontal = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MangaPageHeading("Search", "Find a series in your sources and your library.")
                MangaSearchField(
                    SearchState.text, { SearchState.text = it }, Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                    placeholder = "Title or alternative title", focusRequests = MangaNav.searchFocusRequests, large = true, onSubmit = ::search,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MangaChip("All", SearchState.type == null) { SearchState.type = null }
                    MangaChip("Manga", SearchState.type == MediaType.MANGA) { SearchState.type = MediaType.MANGA }
                    MangaChip("Manhwa", SearchState.type == MediaType.MANHWA) { SearchState.type = MediaType.MANHWA }
                    if (sources.isNotEmpty()) {
                        SourcePicker(candidates, SearchState.sourceIds) { SearchState.sourceIds = it }
                        LanguagePicker(languages, SearchState.language) {
                            SearchState.language = it
                            SearchState.sourceIds = null
                        }
                    }
                    MangaButton("Search", ::search, icon = Icons.Rounded.Search, enabled = SearchState.text.isNotBlank() && sources.isNotEmpty())
                }
            }
        }

        if (sources.isEmpty()) {
            item {
                MangaEmptyState(
                    Icons.Rounded.Extension, "No sources yet",
                    "Search looks through the sources of the extensions you installed. Add an extension store and install an extension to get started; your own files are in the Library.",
                ) { MangaButton("Add extensions", { openMangaPlugins() }, icon = Icons.Rounded.Extension) }
            }
        }

        if (libraryMatches.isNotEmpty()) {
            item {
                CoverShelf("In your library", libraryMatches.filter { SearchState.type == null || it.series.mediaType == SearchState.type }, key = { it.series.id }) { summary, modifier ->
                    SeriesCard(summary, modifier)
                }
            }
        }

        val ordered = SearchState.results.sortedWith(compareBy<SourceSearchResult> { it.error != null }.thenBy { it.series.isEmpty() }.thenBy { it.source.name.lowercase() })
        items(ordered, key = { it.source.id }) { result ->
            val shown = result.series.filter { SearchState.type == null || it.mediaType == SearchState.type }
            when {
                result.error != null -> Column(Modifier.padding(horizontal = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MangaSectionHeader(result.source.name, caption = languageName(result.source.language))
                    FailureNotice(result.error!!, result.source.baseUrl)
                }
                shown.isEmpty() -> Row(Modifier.padding(horizontal = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${result.source.name}: " + if (result.series.isEmpty()) "nothing found" else "nothing found among ${SearchState.type?.label ?: "these"} titles",
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted,
                    )
                }
                else -> CoverShelf(
                    result.source.name, shown,
                    caption = "${plural(shown.size, "result")} · ${languageName(result.source.language)}" + if (result.hasMore) " · more in Discover" else "",
                    action = if (result.hasMore) "See all" else null,
                    onAction = {
                        DiscoverState.open(result.source.id, SearchState.searched)
                        MangaNav.root(MangaRoute.Discover)
                    },
                    key = { it.id },
                ) { series, modifier -> SourceSeriesCard(series, result.source.name, modifier) }
            }
        }

        if (SearchState.pending > 0) {
            item {
                Row(Modifier.padding(horizontal = 40.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text("Waiting for ${plural(SearchState.pending, "source")}…", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
                }
            }
        } else if (SearchState.searched.isNotBlank() && sources.isNotEmpty() && SearchState.results.all { it.series.isEmpty() && it.error == null } && libraryMatches.isEmpty()) {
            item {
                MangaEmptyState(Icons.Rounded.Search, "Nothing found for \"${SearchState.searched}\"", "Try another spelling or an alternative title, or search more sources.")
            }
        }
    }
}

/** Chooses which sources a search goes to. Null means all of them. */
@Composable
internal fun SourcePicker(sources: List<SourceEntry>, selected: Set<String>?, onChange: (Set<String>?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = when {
        selected == null -> "All sources (${sources.size})"
        selected.size == 1 -> sources.firstOrNull { it.id in selected }?.name ?: "1 source"
        else -> "${selected.size} sources"
    }
    Box {
        MangaChip(label, selected != null, icon = Icons.Rounded.Tune) { open = true }
        DropdownMenu(open, { open = false }, modifier = Modifier.width(280.dp)) {
            MangaMenuItem("All sources", checked = selected == null) { onChange(null) }
            MangaMenuDivider()
            sources.forEach { source ->
                val isSelected = selected != null && source.id in selected
                MangaMenuItem(source.name + languageName(source.language).let { if (it.isBlank()) "" else "  ·  $it" }, checked = isSelected) {
                    val next = if (isSelected) selected!! - source.id else selected.orEmpty() + source.id
                    onChange(next.ifEmpty { null })
                }
            }
        }
    }
}

@Composable
internal fun LanguagePicker(languages: List<String>, selected: String?, onChange: (String?) -> Unit) {
    if (languages.size < 2) return
    var open by remember { mutableStateOf(false) }
    Box {
        MangaChip(selected?.let(::languageName) ?: "Any language", selected != null, icon = Icons.Rounded.FilterList) { open = true }
        DropdownMenu(open, { open = false }) {
            MangaMenuItem("Any language", checked = selected == null) { onChange(null); open = false }
            MangaMenuDivider()
            languages.forEach { language ->
                MangaMenuItem(languageName(language), checked = selected == language) { onChange(language); open = false }
            }
        }
    }
}
