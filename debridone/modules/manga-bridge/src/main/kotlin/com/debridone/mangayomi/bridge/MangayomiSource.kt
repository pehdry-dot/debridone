package com.debridone.mangayomi.bridge

import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import rx.Observable

/**
 * A Mangayomi JavaScript source, offered to the manga server as one of its own sources.
 *
 * The server asks this class for popular and latest series, searches with the source's filters,
 * a series' details and chapters, and a chapter's pages; each request runs the source's own code
 * (see [JsRuntime]) and its answer is turned into the server's models. The page pictures are then
 * fetched by the server itself, with the headers the source asks for, like any other source's.
 */
class MangayomiSource : HttpSource(), ConfigurableSource {
    private val meta: SourceMeta get() = SourceMeta.of(MangayomiSource::class.java)

    override val name: String get() = meta.name
    override val lang: String get() = meta.lang
    override val baseUrl: String get() = meta.baseUrl
    override val id: Long get() = meta.sourceId
    override val versionId: Int get() = 1

    override val supportsLatest: Boolean by lazy {
        runCatching { runtime.callOptional("supportsLatest")?.let { Json.parseToJsonElement(it).jsonPrimitive.booleanOrNull } }.getOrNull() ?: true
    }

    private val prefs: SharedPreferences by lazy { getSourcePreferences() }

    private val sourcePreferences: JsonArray by lazy {
        runCatching { runtime.callOptional("getSourcePreferences()")?.let { Json.parseToJsonElement(it) as? JsonArray } }.getOrNull() ?: JsonArray(emptyList())
    }

    /** The headers the source asks for (its getHeaders), applied to every request and picture. */
    private val sourceHeaders: Headers by lazy {
        val builder = Headers.Builder()
        runCatching { runtime.callOptional("getHeaders(${JsonPrimitive(baseUrl)})") }.getOrNull()
            ?.let { (Json.parseToJsonElement(it) as? JsonObject) }
            ?.forEach { (k, v) -> runCatching { builder.set(k, if (v is JsonPrimitive) v.contentOrNull.orEmpty() else v.toString()) } }
        builder.build()
    }

    private val runtime: JsRuntime by lazy {
        JsRuntime(meta, HostCalls({ client }, { sourceHeaders }, { prefs }, { sourcePreferences }, meta.name)).also { ready = it }
    }

    /** Set once the code is loaded, so the definitions below can be asked for before any real call. */
    @Volatile
    private var ready: JsRuntime? = null

    // The server builds `headers` lazily, on first use (covers, pages): the source's code can run by then.
    override fun headersBuilder(): Headers.Builder = super.headersBuilder().also { builder ->
        sourceHeaders.forEach { (k, v) -> runCatching { builder.set(k, v) } }
    }

    // ---- listing ---------------------------------------------------------------------------------

    /** Loads the code, then what the source defines once (its settings and headers), before a real call. */
    private fun loaded(): JsRuntime {
        val js = runtime
        sourcePreferences
        sourceHeaders
        return js
    }

    override suspend fun getPopularManga(page: Int): MangasPage = pageOf(loaded().call("getPopular($page)"))

    override suspend fun getLatestUpdates(page: Int): MangasPage = pageOf(loaded().call("getLatestUpdates($page)"))

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val chosen = if (filters.isEmpty()) getFilterList() else filters
        return pageOf(loaded().call("search(${JsonPrimitive(query)}, $page, ${FilterMapping.toJson(chosen)})"))
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = Observable.fromCallable { pageOf(loaded().call("getPopular($page)")) }
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.fromCallable { pageOf(loaded().call("getLatestUpdates($page)")) }
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        Observable.fromCallable { pageOf(loaded().call("search(${JsonPrimitive(query)}, $page, ${FilterMapping.toJson(if (filters.isEmpty()) getFilterList() else filters)})")) }

    private fun pageOf(json: String): MangasPage {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: throw MangayomiSourceException("$name gave no list of series.")
        val list = root["list"]?.jsonArray.orEmpty().mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val link = entry.text("link") ?: return@mapNotNull null
            SManga.create().apply {
                url = link
                title = entry.text("name").orEmpty().ifBlank { link }
                thumbnail_url = entry.text("imageUrl")?.let(::absolute)
            }
        }
        return MangasPage(list, root["hasNextPage"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    // ---- a series --------------------------------------------------------------------------------

    /** getDetail answers both details and chapters; the answer is kept a short while so asking for both costs one request. */
    private val details = object : LinkedHashMap<String, Pair<Long, JsonObject>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JsonObject>>?) = size > 16
    }

    private fun detail(url: String, fresh: Boolean): JsonObject {
        synchronized(details) {
            details[url]?.takeIf { !fresh && System.currentTimeMillis() - it.first < DETAIL_KEPT_MS }?.let { return it.second }
        }
        val answer = Json.parseToJsonElement(loaded().call("getDetail(${JsonPrimitive(url)})")) as? JsonObject
            ?: throw MangayomiSourceException("$name gave no details for this series.")
        synchronized(details) { details[url] = System.currentTimeMillis() to answer }
        return answer
    }

    private fun mangaOf(manga: SManga, json: JsonObject): SManga = SManga.create().apply {
        url = manga.url
        title = json.text("name")?.takeIf { it.isNotBlank() } ?: manga.title
        thumbnail_url = json.text("imageUrl")?.takeIf { it.isNotBlank() }?.let(::absolute) ?: manga.thumbnail_url
        description = json.text("description")
        author = json.text("author")
        artist = json.text("artist")
        genre = json["genre"]?.let { it as? JsonArray }?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { g -> g.isNotEmpty() } }?.joinToString(", ")
        status = when (json["status"]?.jsonPrimitive?.intOrNull) {
            0 -> SManga.ONGOING
            1 -> SManga.COMPLETED
            2 -> SManga.ON_HIATUS
            3 -> SManga.CANCELLED
            4 -> SManga.PUBLISHING_FINISHED
            else -> SManga.UNKNOWN
        }
        initialized = true
    }

    private fun chaptersOf(json: JsonObject): List<SChapter> =
        (json["chapters"] ?: json["episodes"])?.let { it as? JsonArray }.orEmpty().mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val link = entry.text("url") ?: return@mapNotNull null
            SChapter.create().apply {
                url = link
                name = entry.text("name").orEmpty().ifBlank { link }
                date_upload = entry.text("dateUpload")?.trim()?.toLongOrNull() ?: 0L
                scanlator = entry.text("scanlator")?.takeIf { it.isNotBlank() }
                chapter_number = -1f
            }
        }

    override suspend fun getMangaUpdate(manga: SManga, chapters: List<SChapter>, fetchDetails: Boolean, fetchChapters: Boolean): SMangaUpdate {
        val json = detail(manga.url, fresh = true)
        return SMangaUpdate(if (fetchDetails) mangaOf(manga, json) else manga, if (fetchChapters) chaptersOf(json) else chapters)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = Observable.fromCallable { mangaOf(manga, detail(manga.url, fresh = false)) }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable { chaptersOf(detail(manga.url, fresh = false)) }

    // ---- pages -----------------------------------------------------------------------------------

    private fun pagesOf(chapter: SChapter): List<Page> {
        val answer = Json.parseToJsonElement(loaded().call("getPageList(${JsonPrimitive(chapter.url)})")) as? JsonArray
            ?: throw MangayomiSourceException("$name gave no pages for this chapter.")
        val seen = HashSet<String>()
        return answer.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { it to null }
                is JsonObject -> item.text("url")?.trim()?.takeIf { it.isNotEmpty() }?.let { it to (item["headers"] as? JsonObject) }
                else -> null
            }
        }.filter { seen.add(it.first) }.mapIndexed { index, (url, headers) ->
            // A page's own headers travel in the page's url field, which the server does not otherwise use for these pages.
            Page(index, headers?.toString().orEmpty(), absolute(url), null)
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = pagesOf(chapter)

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = Observable.fromCallable { pagesOf(chapter) }

    override fun imageRequest(page: Page): Request {
        val builder = Request.Builder().url(page.imageUrl ?: throw MangayomiSourceException("This page has no address."))
        headers.forEach { (k, v) -> builder.header(k, v) }
        page.url.takeIf { it.startsWith("{") }?.let { runCatching { Json.parseToJsonElement(it).jsonObject } .getOrNull() }
            ?.forEach { (k, v) -> runCatching { builder.header(k, if (v is JsonPrimitive) v.contentOrNull.orEmpty() else v.toString()) } }
        return builder.build()
    }

    override fun getMangaUrl(manga: SManga): String = absolute(manga.url)

    override fun getChapterUrl(chapter: SChapter): String = absolute(chapter.url)

    // ---- filters and settings --------------------------------------------------------------------

    override fun getFilterList(): FilterList {
        val json = runCatching { loaded().callOptional("getFilterList()") }.getOrNull() ?: return FilterList()
        return FilterMapping.fromJson(Json.parseToJsonElement(json) as? JsonArray ?: return FilterList())
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        for (item in sourcePreferences) {
            val definition = item as? JsonObject ?: continue
            val key = definition.text("key") ?: continue
            definition["listPreference"]?.jsonObject?.let { list ->
                val entries = list.strings("entries")
                val values = list.strings("entryValues")
                screen.addPreference(ListPreference(screen.context).apply {
                    this.key = key
                    title = list.text("title")
                    summary = list.text("summary")?.ifBlank { null } ?: "%s"
                    this.entries = entries.toTypedArray()
                    entryValues = values.toTypedArray()
                    setDefaultValue(values.getOrNull(list["valueIndex"]?.jsonPrimitive?.intOrNull ?: 0) ?: values.firstOrNull())
                })
            }
            definition["multiSelectListPreference"]?.jsonObject?.let { multi ->
                screen.addPreference(MultiSelectListPreference(screen.context).apply {
                    this.key = key
                    title = multi.text("title")
                    summary = multi.text("summary")
                    entries = multi.strings("entries").toTypedArray()
                    entryValues = multi.strings("entryValues").toTypedArray()
                    setDefaultValue(multi.strings("values").toSet())
                })
            }
            definition["switchPreferenceCompat"]?.jsonObject?.let { switch ->
                screen.addPreference(SwitchPreferenceCompat(screen.context).apply {
                    this.key = key
                    title = switch.text("title")
                    summary = switch.text("summary")
                    setDefaultValue(switch["value"]?.jsonPrimitive?.booleanOrNull ?: false)
                })
            }
            definition["checkBoxPreference"]?.jsonObject?.let { box ->
                screen.addPreference(CheckBoxPreference(screen.context).apply {
                    this.key = key
                    title = box.text("title")
                    summary = box.text("summary")
                    setDefaultValue(box["value"]?.jsonPrimitive?.booleanOrNull ?: false)
                })
            }
            definition["editTextPreference"]?.jsonObject?.let { edit ->
                screen.addPreference(EditTextPreference(screen.context).apply {
                    this.key = key
                    title = edit.text("title")
                    summary = edit.text("summary")
                    dialogTitle = edit.text("dialogTitle")
                    dialogMessage = edit.text("dialogMessage")
                    setDefaultValue(edit.text("value") ?: edit.text("text").orEmpty())
                })
            }
        }
    }

    // ---- what the server's request/parse model asks for, unused: every request goes through the code above.

    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException()
    override fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()
    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun absolute(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> baseUrl + url
        url.isBlank() -> url
        else -> "$baseUrl/$url"
    }

    private companion object {
        const val DETAIL_KEPT_MS = 120_000L

        fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

        fun JsonObject.strings(name: String): List<String> =
            (this[name] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
}

/**
 * Mangayomi's filters (JSON objects named by their `type_name`) as the server's filters, and back:
 * each filter keeps its original JSON, and the search sends that JSON with the states chosen.
 */
internal object FilterMapping {
    interface Mapped {
        val json: JsonObject
        fun withState(): JsonObject
    }

    class Select(override val json: JsonObject, options: Array<String>) :
        Filter.Select<String>(json.name(), options, json["state"]?.jsonPrimitive?.intOrNull ?: 0), Mapped {
        override fun withState() = json.with("state", JsonPrimitive(state))
    }

    class Text(override val json: JsonObject) : Filter.Text(json.name(), json["state"]?.jsonPrimitive?.contentOrNull.orEmpty()), Mapped {
        override fun withState() = json.with("state", JsonPrimitive(state))
    }

    class Sort(override val json: JsonObject, options: Array<String>, selection: Selection?) : Filter.Sort(json.name(), options, selection), Mapped {
        override fun withState(): JsonObject {
            val chosen = state ?: return json
            return json.with("state", JsonObject(mapOf("index" to JsonPrimitive(chosen.index), "ascending" to JsonPrimitive(chosen.ascending), "type_name" to JsonPrimitive("SortState"))))
        }
    }

    class CheckBox(override val json: JsonObject) : Filter.CheckBox(json.name(), json["state"]?.jsonPrimitive?.booleanOrNull ?: false), Mapped {
        override fun withState() = json.with("state", JsonPrimitive(state))
    }

    class TriState(override val json: JsonObject) : Filter.TriState(json.name(), json["state"]?.jsonPrimitive?.intOrNull ?: 0), Mapped {
        override fun withState() = json.with("state", JsonPrimitive(state))
    }

    class Group(override val json: JsonObject, children: List<Filter<*>>) : Filter.Group<Filter<*>>(json.name(), children), Mapped {
        override fun withState() = json.with("state", JsonArray(state.map { (it as? Mapped)?.withState() ?: JsonNull }))
    }

    class Header(override val json: JsonObject) : Filter.Header(json.name()), Mapped {
        override fun withState() = json
    }

    class Separator(override val json: JsonObject) : Filter.Separator(), Mapped {
        override fun withState() = json
    }

    fun fromJson(list: JsonArray): FilterList = FilterList(list.mapNotNull { (it as? JsonObject)?.let(::filter) })

    private fun filter(json: JsonObject): Filter<*>? = when (json["type_name"]?.jsonPrimitive?.contentOrNull) {
        "SelectFilter" -> Select(json, json.optionNames())
        "TextFilter" -> Text(json)
        "SortFilter" -> {
            val state = json["state"] as? JsonObject
            Sort(json, json.optionNames(), state?.let { Filter.Sort.Selection(it["index"]?.jsonPrimitive?.intOrNull ?: 0, it["ascending"]?.jsonPrimitive?.booleanOrNull ?: false) })
        }
        "CheckBox" -> CheckBox(json)
        "TriState" -> TriState(json)
        "GroupFilter" -> Group(json, (json["state"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::filter) })
        "HeaderFilter" -> Header(json)
        "SeparatorFilter" -> Separator(json)
        else -> null
    }

    /** The filters as the source's search expects them: Mangayomi's JSON with the chosen states. */
    fun toJson(filters: FilterList): String = JsonArray(filters.mapNotNull { (it as? Mapped)?.withState() }).toString()

    private fun JsonObject.name(): String = (this["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.optionNames(): Array<String> =
        (this["values"] as? JsonArray).orEmpty().map { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty() }.toTypedArray()

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject = JsonObject(this + (key to value))
}
