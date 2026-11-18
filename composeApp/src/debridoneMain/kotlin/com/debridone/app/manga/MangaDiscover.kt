package com.debridone.app.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debridone.app.shell.Notice
import com.debridone.app.shell.PillTone
import com.debridone.manga.MangaPreferences
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.Series
import com.debridone.manga.sources.BrowseMode
import com.debridone.manga.sources.SourceEntry
import com.debridone.manga.sources.SourceProblem
import com.debridone.manga.suwayomi.SwFilter
import com.debridone.manga.suwayomi.SwFilterChange
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which filter a choice belongs to: its place in the source's list, and its place inside a group. */
internal data class FilterKey(val position: Int, val inner: Int? = null)

/** What is chosen in one filter. Only choices that differ from the filter's default are kept. */
internal sealed interface FilterChoice {
    data class Check(val on: Boolean) : FilterChoice

    /** IGNORE, INCLUDE or EXCLUDE. */
    data class Tri(val state: String) : FilterChoice
    data class Text(val text: String) : FilterChoice
    data class Select(val index: Int) : FilterChoice
    data class Sort(val index: Int, val ascending: Boolean) : FilterChoice
}

/** The Discover page's state: one source, browsed page by page. */
internal object DiscoverState {
    var sourceId by mutableStateOf<String?>(null)
    var text by mutableStateOf("")

    /** The text the listed results belong to ("" is the source's own listing). */
    var applied by mutableStateOf("")

    /** The source's own listing shown while nothing is searched or filtered. */
    var listing by mutableStateOf(BrowseMode.POPULAR)
    var type by mutableStateOf<MediaType?>(null)
    var language by mutableStateOf<String?>(null)
    val items = mutableStateListOf<Series>()
    var page by mutableStateOf(0)
    var hasMore by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var problem by mutableStateOf<SourceProblem?>(null)

    /** The source's own filters, and what is chosen in them. */
    var filters by mutableStateOf<List<SwFilter>>(emptyList())
    val choices = mutableStateMapOf<FilterKey, FilterChoice>()
    var filtersFor by mutableStateOf<String?>(null)
    var titleSort by mutableStateOf(false)
    var job: Job? = null
    var requested by mutableStateOf(0)

    fun reset() {
        job?.cancel()
        sourceId = null
        text = ""
        applied = ""
        listing = BrowseMode.POPULAR
        type = null
        language = null
        clearResults()
        filters = emptyList()
        choices.clear()
        filtersFor = null
        titleSort = false
    }

    fun clearResults() {
        job?.cancel()
        items.clear()
        page = 0
        hasMore = false
        loading = false
        problem = null
    }

    /** Shows [source] with [query] already searched (from the Search page's "See all"). */
    fun open(source: String, query: String) {
        sourceId = source
        text = query
        applied = query
        clearResults()
        requested++
    }

    /** The choices as the server wants them: one change per filter that is not at its default. */
    fun changes(): List<SwFilterChange> = choices.entries.sortedWith(compareBy({ it.key.position }, { it.key.inner ?: -1 })).map { (key, choice) ->
        fun change(position: Int): SwFilterChange = when (choice) {
            is FilterChoice.Check -> SwFilterChange(position, checkBox = choice.on)
            is FilterChoice.Tri -> SwFilterChange(position, triState = choice.state)
            is FilterChoice.Text -> SwFilterChange(position, text = choice.text)
            is FilterChoice.Select -> SwFilterChange(position, select = choice.index)
            is FilterChoice.Sort -> SwFilterChange(position, sortIndex = choice.index, sortAscending = choice.ascending)
        }
        if (key.inner == null) change(key.position) else SwFilterChange(key.position, group = change(key.inner))
    }
}

/**
 * Discover: browse one source at a time, the way the source itself offers its catalogue — its
 * popular and latest listings, its search, and its own filters and sort orders (genre, status,
 * and whatever else its extension defines). Manga or Manhwa narrows what is shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MangaDiscover() {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    val scope = rememberCoroutineScope()
    val sources by rememberMangaQuery(initial = emptyList<SourceEntry>()) { services, profile -> services.sources.enabled(profile) }
    val languages = sources.map { it.language }.filter { it.isNotBlank() }.distinct().sorted()
    val offered = sources.filter { DiscoverState.language == null || it.language == DiscoverState.language }
    val source = offered.firstOrNull { it.id == DiscoverState.sourceId }
    var filtersOpen by remember { mutableStateOf(false) }

    // Pick a source: the one used last time, else a preferred one, else one whose last test passed
    // on this computer, else the first of the list.
    LaunchedEffect(offered.map { it.id }, DiscoverState.sourceId) {
        if (offered.isNotEmpty() && offered.none { it.id == DiscoverState.sourceId }) {
            val remembered = manga.services.preferences[MangaPreferences.DISCOVER_SOURCE]
            val pick = offered.firstOrNull { it.id == remembered }
                ?: offered.filter { it.preferredRank != null }.minByOrNull { it.preferredRank ?: Int.MAX_VALUE }
                ?: offered.firstOrNull { it.health?.ok == true }
                ?: offered.first()
            DiscoverState.sourceId = pick.id
            DiscoverState.clearResults()
            DiscoverState.requested++
        }
    }

    fun load(reset: Boolean) {
        val current = source ?: return
        if (DiscoverState.loading && !reset) return
        if (reset) DiscoverState.clearResults()
        DiscoverState.loading = true
        DiscoverState.problem = null
        val nextPage = DiscoverState.page + 1
        val query = DiscoverState.applied
        val changes = DiscoverState.changes()
        // A source applies its filters to a search; with neither text nor filters, its own listing is shown.
        val mode = if (query.isNotBlank() || changes.isNotEmpty()) BrowseMode.SEARCH else DiscoverState.listing
        DiscoverState.job = scope.launch(Dispatchers.IO) {
            try {
                val result = manga.services.sources.browse(current.id, mode, query, nextPage, changes)
                val known = DiscoverState.items.map { it.id }.toSet()
                DiscoverState.items += result.series.filter { it.id !in known }
                DiscoverState.page = nextPage
                // A page that brings nothing new means the source has no more, whatever it claims.
                DiscoverState.hasMore = result.hasMore && result.series.any { it.id !in known }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                DiscoverState.problem = SourceProblem.of(current.name, failure)
                DiscoverState.hasMore = false
            } finally {
                DiscoverState.loading = false
            }
        }
    }

    suspend fun loadFilters(current: SourceEntry, refresh: Boolean) {
        DiscoverState.filters = withContext(Dispatchers.IO) { runCatching { manga.services.sources.filters(current.id, refresh) }.getOrDefault(emptyList()) }
    }

    // The source's own filters, loaded when the source changes.
    LaunchedEffect(source?.id) {
        val current = source ?: return@LaunchedEffect
        if (DiscoverState.filtersFor != current.id) {
            DiscoverState.filtersFor = current.id
            DiscoverState.filters = emptyList()
            DiscoverState.choices.clear()
            if (!current.supportsLatest && DiscoverState.listing == BrowseMode.LATEST) DiscoverState.listing = BrowseMode.POPULAR
            loadFilters(current, refresh = false)
        }
    }
    LaunchedEffect(source?.id, DiscoverState.requested) {
        if (source != null && DiscoverState.items.isEmpty() && DiscoverState.problem == null) load(reset = true)
    }

    val shown by remember {
        derivedStateOf {
            val typed = DiscoverState.items.filter { DiscoverState.type == null || it.mediaType == DiscoverState.type }
            if (DiscoverState.titleSort) typed.sortedBy { it.title.lowercase() } else typed
        }
    }
    val gridState = rememberLazyGridState()
    // Incremental loading: fetch the next page when the end of the list comes into view.
    val nearEnd by remember { derivedStateOf { (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= gridState.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, DiscoverState.hasMore, DiscoverState.loading, DiscoverState.items.size) {
        if (nearEnd && DiscoverState.hasMore && !DiscoverState.loading && DiscoverState.items.isNotEmpty()) load(reset = false)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(manga.style.coverWidth),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 34.dp, end = 34.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(manga.style.gridSpacing - 12.dp),
        verticalArrangement = Arrangement.spacedBy(manga.style.gridSpacing - 8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MangaPageHeading("Discover", if (source != null) "Browsing ${source.name}" else "Browse the catalogues of your sources.")
                MangaSearchField(
                    DiscoverState.text, { DiscoverState.text = it }, Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                    placeholder = if (source != null) "Search ${source.name}" else "Search a source",
                    focusRequests = MangaNav.searchFocusRequests, large = true,
                    onSubmit = {
                        DiscoverState.applied = DiscoverState.text.trim()
                        load(reset = true)
                    },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MangaChip("All", DiscoverState.type == null) { DiscoverState.type = null }
                    MangaChip("Manga", DiscoverState.type == MediaType.MANGA) { DiscoverState.type = MediaType.MANGA }
                    MangaChip("Manhwa", DiscoverState.type == MediaType.MANHWA) { DiscoverState.type = MediaType.MANHWA }
                    if (sources.isNotEmpty()) {
                        DiscoverSourcePicker(offered, source) { picked ->
                            DiscoverState.sourceId = picked.id
                            manga.services.preferences.set(MangaPreferences.DISCOVER_SOURCE, picked.id)
                            DiscoverState.applied = DiscoverState.text.trim()
                            DiscoverState.clearResults()
                            DiscoverState.requested++
                        }
                        LanguagePicker(languages, DiscoverState.language) { DiscoverState.language = it }
                    }
                    if (source != null) {
                        val changed = DiscoverState.choices.size
                        MangaChip(if (changed > 0) "Filters ($changed)" else "Filters", changed > 0 || filtersOpen, icon = Icons.Rounded.FilterAlt) { filtersOpen = !filtersOpen }
                        MangaChip("Sort by title", DiscoverState.titleSort) { DiscoverState.titleSort = !DiscoverState.titleSort }
                        MangaIconButton(Icons.Rounded.Refresh, "Reload", { load(reset = true) }, enabled = !DiscoverState.loading)
                    }
                }
                // The source's own listings. They show while nothing is searched or filtered.
                if (source != null) {
                    val listed = DiscoverState.applied.isBlank() && DiscoverState.choices.isEmpty()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MangaChip("Popular", listed && DiscoverState.listing == BrowseMode.POPULAR, icon = Icons.Rounded.Whatshot) {
                            DiscoverState.listing = BrowseMode.POPULAR
                            DiscoverState.text = ""
                            DiscoverState.applied = ""
                            DiscoverState.choices.clear()
                            load(reset = true)
                        }
                        if (source.supportsLatest) {
                            MangaChip("Latest", listed && DiscoverState.listing == BrowseMode.LATEST, icon = Icons.Rounded.NewReleases) {
                                DiscoverState.listing = BrowseMode.LATEST
                                DiscoverState.text = ""
                                DiscoverState.applied = ""
                                DiscoverState.choices.clear()
                                load(reset = true)
                            }
                        }
                        if (!listed) MangaChip(if (DiscoverState.applied.isBlank()) "Filtered" else "Results for \"${DiscoverState.applied}\"", true) {}
                    }
                }
                if (filtersOpen && source != null) {
                    FilterPanel(
                        filters = DiscoverState.filters,
                        sourceName = source.name,
                        onApply = {
                            filtersOpen = false
                            load(reset = true)
                        },
                        onReset = {
                            DiscoverState.choices.clear()
                            // Some sources only know their filters (their libraries, their genres) once asked again.
                            scope.launch { loadFilters(source, refresh = true) }
                            load(reset = true)
                        },
                    )
                }
                DiscoverState.problem?.let { problem ->
                    Notice(problem.message, if (problem.temporary) PillTone.WARNING else PillTone.DANGER)
                    if (source?.configurable == true) {
                        MangaButton("Configure ${source.name}", { openMangaPlugins(MangaPluginsTab.SOURCES) }, primary = false, icon = Icons.Rounded.Tune)
                    }
                }
            }
        }

        if (sources.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                MangaEmptyState(
                    Icons.Rounded.Extension, "No sources to browse yet",
                    "Discover shows the catalogues of the sources your extensions provide. Add an extension store and install an extension to browse and read online.",
                ) { MangaButton("Add extensions", { openMangaPlugins() }, icon = Icons.Rounded.Extension) }
            }
        }

        items(shown, key = { it.id }) { series -> SourceSeriesCard(series, source?.name.orEmpty()) }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                when {
                    DiscoverState.loading -> CircularProgressIndicator(color = manga.style.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    DiscoverState.hasMore -> MangaButton("Load more", { load(reset = false) }, primary = false)
                    source != null && DiscoverState.items.isNotEmpty() && shown.isEmpty() -> Text(
                        "None of the ${DiscoverState.items.size} titles loaded so far are ${DiscoverState.type?.label}.",
                        style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textMuted,
                    )
                    source != null && DiscoverState.items.isEmpty() && DiscoverState.problem == null && DiscoverState.page > 0 -> MangaEmptyState(
                        Icons.Rounded.Explore, "Nothing here",
                        if (DiscoverState.applied.isBlank()) "${source.name} listed no series." + if (source.configurable) " It may need to be configured first (its address or account)." else ""
                        else "${source.name} found nothing for \"${DiscoverState.applied}\".",
                    )
                    DiscoverState.items.isNotEmpty() -> Text("End of the list · ${plural(DiscoverState.items.size, "title")}", style = small(), color = tokens.colors.textMuted)
                }
            }
        }
    }
}

@Composable
private fun DiscoverSourcePicker(sources: List<SourceEntry>, selected: SourceEntry?, onPick: (SourceEntry) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        MangaChip(selected?.name ?: "Choose a source", true, icon = Icons.Rounded.Public) { open = true }
        DropdownMenu(open, { open = false }, modifier = Modifier.width(300.dp)) {
            sources.forEach { source ->
                MangaMenuItem(source.name + languageName(source.language).let { if (it.isBlank()) "" else "  ·  $it" }, checked = source.id == selected?.id) {
                    open = false
                    onPick(source)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- source filters

/**
 * A source's own filters, drawn from the extension's description of them: whatever the source
 * supports, nothing invented. A choice that equals the filter's default is not sent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(filters: List<SwFilter>, sourceName: String, onApply: () -> Unit, onReset: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(16.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (filters.none { it !is SwFilter.Header && it !is SwFilter.Separator }) {
            Text("$sourceName offers no filters or sort orders of its own.", style = MaterialTheme.typography.bodyMedium, color = tokens.colors.textSecondary)
        }
        filters.forEachIndexed { position, filter -> FilterControl(filter, FilterKey(position), onApply) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            MangaButton("Apply filters", onApply)
            MangaButton("Reset", onReset, primary = false)
        }
        Text("These filters are the source's own; other sources offer different ones. Reset also asks the source for its filters again.", style = small(), color = tokens.colors.textMuted)
    }
}

/** Keeps [choice] only when it differs from the filter's default. */
private fun choose(key: FilterKey, choice: FilterChoice, isDefault: Boolean) {
    if (isDefault) DiscoverState.choices.remove(key) else DiscoverState.choices[key] = choice
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterControl(filter: SwFilter, key: FilterKey, onApply: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val chosen = DiscoverState.choices[key]
    when (filter) {
        is SwFilter.Header -> if (filter.name.isNotBlank()) Text(filter.name, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, modifier = Modifier.padding(top = 4.dp))
        is SwFilter.Separator -> MangaMenuDivider()
        is SwFilter.CheckBox -> {
            val on = (chosen as? FilterChoice.Check)?.on ?: filter.default
            MangaChip(filter.name, on) { choose(key, FilterChoice.Check(!on), !on == filter.default) }
        }
        is SwFilter.TriState -> {
            val state = (chosen as? FilterChoice.Tri)?.state ?: filter.default
            TriStateChip(filter.name, state) { next -> choose(key, FilterChoice.Tri(next), next == filter.default) }
        }
        is SwFilter.Text -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(filter.name, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
            MangaSearchField(
                (chosen as? FilterChoice.Text)?.text ?: filter.default, { choose(key, FilterChoice.Text(it), it == filter.default) },
                Modifier.widthIn(max = 360.dp).fillMaxWidth(), placeholder = filter.name, onSubmit = onApply,
            )
        }
        is SwFilter.Select -> {
            val index = (chosen as? FilterChoice.Select)?.index ?: filter.default
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(filter.name, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
                if (filter.values.size <= 14) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        filter.values.forEachIndexed { option, label -> MangaChip(label.ifBlank { "Any" }, index == option) { choose(key, FilterChoice.Select(option), option == filter.default) } }
                    }
                } else {
                    // A long list (dozens of genres, publishers) folds into a menu.
                    var open by remember { mutableStateOf(false) }
                    Box {
                        MangaChip(filter.values.getOrNull(index)?.ifBlank { "Any" } ?: "Choose", index != filter.default, icon = Icons.Rounded.Tune) { open = true }
                        DropdownMenu(open, { open = false }, modifier = Modifier.width(300.dp)) {
                            filter.values.forEachIndexed { option, label ->
                                MangaMenuItem(label.ifBlank { "Any" }, checked = index == option) {
                                    open = false
                                    choose(key, FilterChoice.Select(option), option == filter.default)
                                }
                            }
                        }
                    }
                }
            }
        }
        is SwFilter.Sort -> {
            val current = chosen as? FilterChoice.Sort
            val index = current?.index ?: filter.defaultIndex
            val ascending = current?.ascending ?: filter.defaultAscending
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(filter.name.ifBlank { "Sort" }, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filter.values.forEachIndexed { option, label ->
                        val active = index == option
                        // Clicking the active order flips its direction.
                        MangaChip(label, active, icon = if (active) (if (ascending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward) else null) {
                            val next = FilterChoice.Sort(option, if (active) !ascending else ascending)
                            choose(key, next, next.index == filter.defaultIndex && next.ascending == filter.defaultAscending)
                        }
                    }
                }
            }
        }
        is SwFilter.Group -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(filter.name, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
            val simple = filter.filters.all { it is SwFilter.CheckBox || it is SwFilter.TriState }
            if (simple) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filter.filters.forEachIndexed { inner, member -> FilterControl(member, FilterKey(key.position, inner), onApply) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 12.dp)) {
                    filter.filters.forEachIndexed { inner, member -> FilterControl(member, FilterKey(key.position, inner), onApply) }
                }
            }
        }
    }
}

/** Off → include → exclude → off, as the source defines a three-state filter. */
@Composable
private fun TriStateChip(label: String, state: String, onChange: (String) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val style = LocalManga.current.style
    val (background, content, prefix) = when (state) {
        "INCLUDE" -> Triple(style.accent, style.onAccent, "+ ")
        "EXCLUDE" -> Triple(tokens.colors.danger.copy(alpha = 0.22f), tokens.colors.danger, "− ")
        else -> Triple(tokens.colors.surfaceElevated, tokens.colors.textSecondary, "")
    }
    Text(
        prefix + label,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(role = Role.Checkbox) { onChange(if (state == "IGNORE") "INCLUDE" else if (state == "INCLUDE") "EXCLUDE" else "IGNORE") }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
