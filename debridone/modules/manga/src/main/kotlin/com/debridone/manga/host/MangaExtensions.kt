package com.debridone.manga.host

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/** One source, as an extension package describes it (Tiyo's `ExtensionMetadata` plus what the host found out). */
data class SourceDescriptor(
    val id: String,
    val name: String,
    val url: String,
    /** Tiyo `LanguageKey` of the translations it offers (`ENGLISH`, `MULTI`, …), or null. */
    val language: String?,
    val notice: String?,
    /** The package that provides it, e.g. `@tiyo/core`. */
    val origin: String,
    /** Setting name -> Tiyo `SettingType` (`STRING`, `BOOLEAN`, …). */
    val settingTypes: Map<String, String>,
    val defaultSettings: JsonObject,
    val filterCount: Int,
    /** True when the source loads pages through Houdoku's hidden browser window; null when unknown. */
    val usesBrowserWindow: Boolean?,
    val problems: List<String>,
)

data class LoadedPackage(
    val name: String,
    val version: String,
    /** `tiyo`, `legacy` or `unsupported`. */
    val kind: String,
    val loaded: Boolean,
    val sourceCount: Int,
    val error: String?,
    /** For a legacy package whose source Tiyo also provides: the package whose copy is used instead. */
    val shadowedBy: String? = null,
)

data class HostPluginState(
    val packages: List<LoadedPackage>,
    val tiyoVersion: String?,
    val sources: List<SourceDescriptor>,
) {
    fun source(id: String): SourceDescriptor? = sources.firstOrNull { it.id == id }
}

/** A series as a source returns it (Tiyo's `Series`). [raw] is handed back to the source when it asks for it. */
data class RemoteSeries(
    val extensionId: String,
    val sourceId: String,
    val title: String,
    val altTitles: List<String>,
    val description: String,
    val authors: List<String>,
    val artists: List<String>,
    val tags: List<String>,
    /** `Ongoing`, `Completed`, `Cancelled`, or null. */
    val status: String?,
    /** Tiyo `LanguageKey` of the original work. */
    val originalLanguage: String?,
    val coverUrl: String,
    val raw: JsonObject,
)

/** A chapter as a source returns it (Tiyo's `Chapter`). */
data class RemoteChapter(
    val sourceId: String,
    val title: String,
    val chapterNumber: String,
    val volumeNumber: String,
    val language: String?,
    val groupName: String,
    /** Release time in milliseconds, or 0 when the source gives none. */
    val time: Long,
)

data class SeriesPage(val series: List<RemoteSeries>, val hasMore: Boolean)

sealed interface ImageRef {
    /** The app fetches this address itself. */
    data class Url(val url: String) : ImageRef

    /** The source fetched the image (it needed its own headers or login); the bytes are in this file. */
    data class Spooled(val file: Path, val size: Long) : ImageRef
}

/**
 * The operations of Houdoku's extension interface, as Kotlin functions.
 *
 * Mirrors the handlers in Houdoku's `main/services/extension.ts`: each function names a source by
 * its extension id and forwards to the same Tiyo function. Calls to one source are spaced out and
 * limited in number, so a search across many sources or a fast reader cannot flood a site.
 */
class MangaExtensions(
    private val host: ExtensionHost,
    /** The stored settings of a source for whoever is using the app now, applied after every (re)load. */
    private val storedSettings: suspend (SourceDescriptor) -> JsonObject? = { null },
) {
    private val loadLock = Mutex()

    @Volatile
    private var loadedGeneration = -1

    private val _state = MutableStateFlow<HostPluginState?>(null)

    /** What is loaded in the host right now; null before the first load. */
    val state: StateFlow<HostPluginState?> = _state.asStateFlow()

    private val gates = ConcurrentHashMap<String, SourceGate>()

    /** Time limit of one source operation. Changed from Plugins › Manga › Settings. */
    @Volatile
    var timeoutMs: Long = 45_000

    /** Loads the installed packages if the running host has not done so yet. */
    suspend fun ensureLoaded(): HostPluginState {
        val generation = host.ensureStarted()
        _state.value?.takeIf { generation == loadedGeneration }?.let { return it }
        return loadLock.withLock {
            val current = host.ensureStarted()
            _state.value?.takeIf { current == loadedGeneration }?.let { return@withLock it }
            load(current)
        }
    }

    /** Unloads and loads everything again: after an install, an update, a removal, or a change of profile. */
    suspend fun reload(): HostPluginState = loadLock.withLock { load(host.ensureStarted()) }

    /** Releases the packages (so their files can be replaced) without stopping the host. */
    suspend fun unload() = loadLock.withLock {
        runCatching { host.call("plugins.unload") }
        loadedGeneration = -1
        _state.value = null
    }

    private suspend fun load(generation: Int): HostPluginState {
        val answer = host.call("plugins.load", timeoutMs = 60_000) as JsonObject
        val state = parseState(answer)
        loadedGeneration = generation
        _state.value = state
        for (source in state.sources) {
            if (source.settingTypes.isEmpty()) continue
            val stored = runCatching { storedSettings(source) }.getOrNull() ?: continue
            runCatching { call(source.id, "setSettings", buildJsonArray { add(stored) }) }
        }
        return state
    }

    suspend fun search(extensionId: String, text: String, page: Int = 1, filters: JsonObject = EMPTY_OBJECT): SeriesPage =
        parsePage(extensionId, call(extensionId, "getSearch", buildJsonArray { add(JsonPrimitive(text)); add(JsonPrimitive(page)); add(filters) }))

    suspend fun directory(extensionId: String, page: Int = 1, filters: JsonObject = EMPTY_OBJECT): SeriesPage =
        parsePage(extensionId, call(extensionId, "getDirectory", buildJsonArray { add(JsonPrimitive(page)); add(filters) }))

    suspend fun series(extensionId: String, seriesSourceId: String): RemoteSeries? {
        val answer = call(extensionId, "getSeries", buildJsonArray { add(JsonPrimitive(seriesSourceId)) })
        return (answer as? JsonObject)?.let { parseSeries(extensionId, it) }
    }

    suspend fun chapters(extensionId: String, seriesSourceId: String): List<RemoteChapter> {
        val answer = call(extensionId, "getChapters", buildJsonArray { add(JsonPrimitive(seriesSourceId)) }, timeoutMs * 2)
        return (answer as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseChapter) }
    }

    /** Addresses of a chapter's pages, in reading order. Each one goes through [image] before it is shown. */
    suspend fun pages(extensionId: String, seriesSourceId: String, chapterSourceId: String): List<String> {
        ensureLoaded()
        val answer = gate(extensionId).run {
            host.call(
                "ext.pages",
                buildJsonObject {
                    put("extensionId", extensionId)
                    put("seriesSourceId", seriesSourceId)
                    put("chapterSourceId", chapterSourceId)
                },
                timeoutMs,
            )
        } as JsonObject
        return (answer["urls"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    suspend fun image(extensionId: String, series: JsonObject, url: String): ImageRef {
        ensureLoaded()
        val answer = gate(extensionId).run {
            host.call(
                "ext.image",
                buildJsonObject {
                    put("extensionId", extensionId)
                    put("series", series)
                    put("url", url)
                },
                timeoutMs * 2,
            )
        } as JsonObject
        return when (answer.text("kind")) {
            "file" -> ImageRef.Spooled(Paths.get(answer.text("path") ?: throw HostException("NO_IMAGE", "The source returned no image.")), answer.long("size") ?: 0)
            else -> ImageRef.Url(answer.text("url") ?: throw HostException("NO_IMAGE", "The source returned no image."))
        }
    }

    /** Tiyo `FilterOption` objects (kind, id, label, defaultValue, and per-kind fields), as the source returns them. */
    suspend fun filterOptions(extensionId: String): JsonArray =
        call(extensionId, "getFilterOptions", EMPTY_ARRAY) as? JsonArray ?: EMPTY_ARRAY

    suspend fun settings(extensionId: String): JsonObject =
        call(extensionId, "getSettings", EMPTY_ARRAY) as? JsonObject ?: EMPTY_OBJECT

    suspend fun applySettings(extensionId: String, settings: JsonObject) {
        call(extensionId, "setSettings", buildJsonArray { add(settings) })
    }

    private suspend fun call(extensionId: String, function: String, arguments: JsonArray, limitMs: Long = timeoutMs): JsonElement {
        ensureLoaded()
        return gate(extensionId).run {
            host.call(
                "ext.call",
                buildJsonObject {
                    put("extensionId", extensionId)
                    put("fn", function)
                    put("args", arguments)
                },
                limitMs,
            )
        }
    }

    private fun gate(extensionId: String): SourceGate = gates.getOrPut(extensionId) { SourceGate() }

    /** At most [MAX_PARALLEL] calls to one source at a time, started at least [MIN_SPACING_MS] apart. */
    private class SourceGate {
        private val slots = Semaphore(MAX_PARALLEL)
        private val pacing = Mutex()
        private var lastStart = 0L

        suspend fun <T> run(block: suspend () -> T): T = slots.withPermit {
            pacing.withLock {
                val wait = lastStart + MIN_SPACING_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastStart = System.currentTimeMillis()
            }
            block()
        }
    }

    private fun parseState(answer: JsonObject): HostPluginState {
        val packages = (answer["packages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { pkg ->
            LoadedPackage(
                name = pkg.text("name").orEmpty(),
                version = pkg.text("version").orEmpty(),
                kind = pkg.text("kind") ?: "unsupported",
                loaded = (pkg["loaded"] as? JsonPrimitive)?.booleanOrNull ?: false,
                sourceCount = (pkg["sourceCount"] as? JsonPrimitive)?.intOrNull ?: 0,
                error = pkg.text("error"),
                shadowedBy = pkg.text("shadowedBy"),
            )
        }
        val sources = (answer["extensions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { ext ->
            SourceDescriptor(
                id = ext.text("id").orEmpty(),
                name = ext.text("name").orEmpty(),
                url = ext.text("url").orEmpty(),
                language = ext.text("translatedLanguage"),
                notice = ext.text("notice"),
                origin = ext.text("origin").orEmpty(),
                settingTypes = (ext["settingTypes"] as? JsonObject).orEmpty().mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }.toMap(),
                defaultSettings = ext["settings"] as? JsonObject ?: EMPTY_OBJECT,
                filterCount = (ext["filterCount"] as? JsonPrimitive)?.intOrNull ?: 0,
                usesBrowserWindow = (ext["usesBrowserWindow"] as? JsonPrimitive)?.booleanOrNull,
                problems = (ext["problems"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull } +
                    (ext["missingFunctions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map { "does not implement $it" },
            )
        }.sortedBy { it.name.lowercase() }
        return HostPluginState(packages, answer.text("tiyoVersion"), sources)
    }

    private fun parsePage(extensionId: String, answer: JsonElement): SeriesPage {
        val page = answer as? JsonObject ?: return SeriesPage(emptyList(), false)
        val list = (page["seriesList"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { series -> parseSeries(extensionId, series) } }
        return SeriesPage(list, (page["hasMore"] as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    private fun parseSeries(extensionId: String, series: JsonObject): RemoteSeries? {
        val sourceId = series.text("sourceId")?.takeIf { it.isNotBlank() } ?: return null
        return RemoteSeries(
            extensionId = extensionId,
            sourceId = sourceId,
            title = series.text("title")?.trim().orEmpty().ifEmpty { sourceId },
            altTitles = series.strings("altTitles"),
            description = series.text("description").orEmpty(),
            authors = series.strings("authors"),
            artists = series.strings("artists"),
            tags = series.strings("tags"),
            status = series.text("status"),
            originalLanguage = series.text("originalLanguageKey"),
            coverUrl = series.text("remoteCoverUrl").orEmpty(),
            raw = series,
        )
    }

    private fun parseChapter(chapter: JsonObject): RemoteChapter? {
        val sourceId = chapter.text("sourceId")?.takeIf { it.isNotBlank() } ?: return null
        return RemoteChapter(
            sourceId = sourceId,
            title = chapter.text("title").orEmpty(),
            chapterNumber = chapter.text("chapterNumber").orEmpty(),
            volumeNumber = chapter.text("volumeNumber").orEmpty(),
            language = chapter.text("languageKey"),
            groupName = chapter.text("groupName").orEmpty(),
            time = chapter.long("time") ?: 0,
        )
    }

    private fun JsonObject.text(key: String): String? = when (val value = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> value.contentOrNull
        else -> null
    }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { !it.isNaN() }?.toLong()

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    companion object {
        private const val MAX_PARALLEL = 3
        private const val MIN_SPACING_MS = 220L
        private val EMPTY_OBJECT = JsonObject(emptyMap())
        private val EMPTY_ARRAY = JsonArray(emptyList())
    }
}
