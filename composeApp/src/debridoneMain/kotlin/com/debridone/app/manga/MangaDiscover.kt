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
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.debridone.manga.host.RemoteSeries
import com.debridone.manga.library.MangaClassifier
import com.debridone.manga.library.MediaType
import com.debridone.manga.sources.SourceEntry
import com.debridone.manga.sources.SourceProblem
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The Discover page's state: one source, browsed page by page. */
internal object DiscoverState {
    var sourceId by mutableStateOf<String?>(null)
    var text by mutableStateOf("")

    /** The text the listed results belong to ("" is the source's own directory). */
    var applied by mutableStateOf("")
    var type by mutableStateOf<MediaType?>(null)
    var language by mutableStateOf<String?>(null)
    val items = mutableStateListOf<RemoteSeries>()
    var page by mutableStateOf(0)
    var hasMore by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var problem by mutableStateOf<SourceProblem?>(null)

    /** The source's own filter controls, and what is chosen in them. */
    var filterOptions by mutableStateOf<JsonArray>(JsonArray(emptyList()))
    var filterValues by mutableStateOf<Map<String, JsonElement>>(emptyMap())
    var filtersFor by mutableStateOf<String?>(null)
    var titleSort by mutableStateOf(false)
    var job: Job? = null
    var requested by mutableStateOf(0)

    fun reset() {
        job?.cancel()
        sourceId = null
        text = ""
        applied = ""
        type = null
        language = null
        clearResults()
        filterOptions = JsonArray(emptyList())
        filterValues = emptyMap()
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
}

/**
 * Discover: browse one source at a time, the way the source itself offers its catalogue — its
 * directory, its search, and its own filters and sort orders (genre, status, popularity, latest,
 * title, where the source has them). Manga or Manhwa narrows what is shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MangaDiscover() {
    val tokens = MaterialTheme.nuvio
    val manga = LocalManga.current
    val scope = rememberCoroutineScope()
    val hostState by manga.services.extensions.state.collectAsStateWithLifecycle()
    val sources by rememberMangaQuery(hostState, initial = emptyList<SourceEntry>()) { services, profile -> services.sources.enabled(profile) }
    val languages = sources.mapNotNull { it.descriptor.language }.distinct().sorted()
    val offered = sources.filter { DiscoverState.language == null || it.descriptor.language == DiscoverState.language }
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
        val filters = JsonObject(DiscoverState.filterValues)
        DiscoverState.job = scope.launch(Dispatchers.IO) {
            try {
                val result = manga.services.sources.browse(current.id, query, nextPage, filters)
                val known = DiscoverState.items.map { it.sourceId }.toSet()
                DiscoverState.items += result.series.filter { it.sourceId !in known }
                DiscoverState.page = nextPage
                // A page that brings nothing new means the source has no more, whatever it claims.
                DiscoverState.hasMore = result.hasMore && result.series.any { it.sourceId !in known }
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

    // The source's own filter controls, loaded when the source changes.
    LaunchedEffect(source?.id) {
        val current = source ?: return@LaunchedEffect
        if (DiscoverState.filtersFor != current.id) {
            DiscoverState.filtersFor = current.id
            DiscoverState.filterOptions = JsonArray(emptyList())
            DiscoverState.filterValues = emptyMap()
            if (current.descriptor.filterCount > 0) {
                val options = runCatching { manga.services.extensions.filterOptions(current.id) }.getOrDefault(JsonArray(emptyList()))
                DiscoverState.filterOptions = options
                DiscoverState.filterValues = SourceFilters.defaults(options)
            }
        }
    }
    LaunchedEffect(source?.id, DiscoverState.requested) {
        if (source != null && DiscoverState.items.isEmpty() && DiscoverState.problem == null) load(reset = true)
    }

    val shown by remember {
        derivedStateOf {
            val typed = DiscoverState.items.filter { DiscoverState.type == null || MangaClassifier.classify(it.originalLanguage, it.tags) == DiscoverState.type }
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
                    if (source != null && DiscoverState.filterOptions.isNotEmpty()) {
                        val changed = SourceFilters.changedCount(DiscoverState.filterOptions, DiscoverState.filterValues)
                        MangaChip(if (changed > 0) "Filters ($changed)" else "Filters", changed > 0, icon = Icons.Rounded.FilterAlt) { filtersOpen = !filtersOpen }
                    }
                    if (source != null) {
                        MangaChip("Sort by title", DiscoverState.titleSort) { DiscoverState.titleSort = !DiscoverState.titleSort }
                        MangaIconButton(Icons.Rounded.Refresh, "Reload", { load(reset = true) }, enabled = !DiscoverState.loading)
                    }
                }
                // Sort orders the source itself offers (popularity, latest update, title…) as one-click chips.
                if (source != null) {
                    SourceFilters.sortControl(DiscoverState.filterOptions)?.let { control ->
                        SortChips(control, DiscoverState.filterValues[control.id]) { value ->
                            DiscoverState.filterValues = DiscoverState.filterValues + (control.id to value)
                            load(reset = true)
                        }
                    }
                }
                if (filtersOpen && source != null) {
                    FilterPanel(
                        options = DiscoverState.filterOptions,
                        values = DiscoverState.filterValues,
                        onChange = { DiscoverState.filterValues = it },
                        onApply = {
                            filtersOpen = false
                            load(reset = true)
                        },
                        onReset = {
                            DiscoverState.filterValues = SourceFilters.defaults(DiscoverState.filterOptions)
                            load(reset = true)
                        },
                    )
                }
                if (source != null && source.descriptor.filterCount == 0) {
                    Text("${source.name} offers no filters or sort orders of its own.", style = small(), color = tokens.colors.textMuted)
                }
                if (source?.needsSetup == true) {
                    Notice("${source.name} has to be set up before it can be browsed (its address or account). Open Plugins › Manga › Sources.", PillTone.WARNING)
                }
                DiscoverState.problem?.let { problem -> Notice(problem.message, if (problem.temporary) PillTone.WARNING else PillTone.DANGER) }
            }
        }

        if (sources.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                MangaEmptyState(
                    Icons.Rounded.Extension, "No sources to browse yet",
                    "Discover shows the catalogues of the sources in Houdoku's extension package. Add it to browse and read online.",
                ) { MangaButton("Add Source", { openMangaPlugins() }, icon = Icons.Rounded.Extension) }
            }
        }

        items(shown, key = { it.extensionId + ":" + it.sourceId }) { remote -> RemoteSeriesCard(remote, source?.name.orEmpty()) }

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
                        if (DiscoverState.applied.isBlank()) "${source.name} listed no series." else "${source.name} found nothing for \"${DiscoverState.applied}\".",
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
                MangaMenuItem(source.name + "  ·  " + languageName(source.descriptor.language), checked = source.id == selected?.id) {
                    open = false
                    onPick(source)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- source filters

/** One filter control of a source, as Tiyo describes it (`FilterOption`). */
internal class FilterControl(val json: JsonObject) {
    val kind: String = json.text("kind").orEmpty()
    val id: String = json.text("id").orEmpty()
    val label: String = json.text("label").orEmpty()
    val default: JsonElement = json["defaultValue"] ?: JsonNull

    /** `options` of select and cycle controls. */
    val options: List<Pair<String, String>> = pairs("options", "value")

    /** `fields` of multi-toggle and sort controls. */
    val fields: List<Pair<String, String>> = pairs("fields", "key")
    val isTriState: Boolean = (json["isTriState"] as? JsonPrimitive)?.booleanOrNull ?: false
    val bothDirections: Boolean = (json["supportsBothDirections"] as? JsonPrimitive)?.booleanOrNull ?: false
    val placeholder: String = json.text("placeholder").orEmpty()

    private fun pairs(name: String, keyName: String): List<Pair<String, String>> = (json[name] as? JsonArray).orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val key = item.text(keyName) ?: return@mapNotNull null
        key to (item.text("label") ?: key)
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Reads and builds the values of a source's filter controls (Tiyo's `FilterValues`: control id to value). */
internal object SourceFilters {
    private val passive = setOf("header", "separator")

    fun controls(options: JsonArray): List<FilterControl> = options.mapNotNull { (it as? JsonObject)?.let(::FilterControl) }

    fun defaults(options: JsonArray): Map<String, JsonElement> =
        controls(options).filter { it.kind !in passive && it.id.isNotEmpty() }.associate { it.id to it.default }

    fun changedCount(options: JsonArray, values: Map<String, JsonElement>): Int =
        controls(options).count { it.kind !in passive && it.kind != "sort" && values[it.id] != null && values[it.id] != it.default }

    fun sortControl(options: JsonArray): FilterControl? = controls(options).firstOrNull { it.kind == "sort" && it.fields.isNotEmpty() }
}

/** The source's sort orders as chips; clicking the active one flips its direction when the source allows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortChips(control: FilterControl, value: JsonElement?, onChange: (JsonElement) -> Unit) {
    val current = value as? JsonObject
    val key = current?.text("key")
    val direction = (current?.get("direction") as? JsonPrimitive)?.intOrNull ?: 1
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sort", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.nuvio.colors.textMuted, modifier = Modifier.padding(top = 8.dp, end = 4.dp))
        control.fields.forEach { (fieldKey, label) ->
            val active = key == fieldKey
            MangaChip(
                label, active,
                icon = if (active && control.bothDirections) (if (direction == 0) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward) else null,
            ) {
                val next = if (active && control.bothDirections) 1 - direction else direction
                onChange(buildJsonObject { put("key", fieldKey); put("direction", next) })
            }
        }
    }
}

/** A source's own filters, drawn from its description of them: whatever the source supports, nothing invented. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    options: JsonArray,
    values: Map<String, JsonElement>,
    onChange: (Map<String, JsonElement>) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tokens.colors.surface).border(1.dp, tokens.colors.borderSubtle, RoundedCornerShape(16.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SourceFilters.controls(options).forEach { control ->
            val value = values[control.id]
            when (control.kind) {
                "header" -> Text(control.label, style = MaterialTheme.typography.titleSmall, color = tokens.colors.textPrimary, modifier = Modifier.padding(top = 4.dp))
                "separator" -> MangaMenuDivider()
                "checkbox" -> {
                    val on = (value as? JsonPrimitive)?.booleanOrNull ?: false
                    MangaChip(control.label, on) { onChange(values + (control.id to JsonPrimitive(!on))) }
                }
                "tristatecheckbox" -> {
                    val state = (value as? JsonPrimitive)?.intOrNull ?: 0
                    TriStateChip(control.label, state) { onChange(values + (control.id to JsonPrimitive(it))) }
                }
                "select", "cycle" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(control.label, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        control.options.forEach { (optionValue, label) ->
                            MangaChip(label, (value as? JsonPrimitive)?.contentOrNull == optionValue) { onChange(values + (control.id to JsonPrimitive(optionValue))) }
                        }
                    }
                }
                "input" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(control.label, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
                    MangaSearchField(
                        (value as? JsonPrimitive)?.contentOrNull.orEmpty(), { onChange(values + (control.id to JsonPrimitive(it))) },
                        Modifier.widthIn(max = 360.dp).fillMaxWidth(), placeholder = control.placeholder.ifBlank { control.label }, onSubmit = onApply,
                    )
                }
                "multitoggle" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(control.label, style = MaterialTheme.typography.labelLarge, color = tokens.colors.textSecondary)
                    val chosen = (value as? JsonObject) ?: JsonObject(emptyMap())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        control.fields.forEach { (fieldKey, label) ->
                            val state = (chosen[fieldKey] as? JsonPrimitive)?.intOrNull ?: 0
                            TriStateChip(label, state, exclude = control.isTriState) { next ->
                                val updated = JsonObject(if (next == 0) chosen - fieldKey else chosen + (fieldKey to JsonPrimitive(next)))
                                onChange(values + (control.id to updated))
                            }
                        }
                    }
                }
                // Sort orders are offered as chips above the panel.
                "sort" -> Unit
                else -> Unit
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            MangaButton("Apply filters", onApply)
            MangaButton("Reset", onReset, primary = false)
        }
        Text("These filters are the source's own; other sources offer different ones.", style = small(), color = tokens.colors.textMuted)
    }
}

/** Off → include → (exclude) → off. Tiyo's TriState: 0 ignore, 1 include, 2 exclude. */
@Composable
private fun TriStateChip(label: String, state: Int, exclude: Boolean = true, onChange: (Int) -> Unit) {
    val tokens = MaterialTheme.nuvio
    val style = LocalManga.current.style
    val (background, content, prefix) = when (state) {
        1 -> Triple(style.accent, style.onAccent, "+ ")
        2 -> Triple(tokens.colors.danger.copy(alpha = 0.22f), tokens.colors.danger, "− ")
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
            .clickable(role = Role.Checkbox) { onChange(if (state == 0) 1 else if (state == 1 && exclude) 2 else 0) }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
