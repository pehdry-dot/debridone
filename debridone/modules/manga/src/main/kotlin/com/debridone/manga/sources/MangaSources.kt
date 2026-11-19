package com.debridone.manga.sources

import com.debridone.manga.library.HealthStep
import com.debridone.manga.library.MangaRepository
import com.debridone.manga.library.Series
import com.debridone.manga.library.SourceHealth
import com.debridone.manga.suwayomi.MangaServer
import com.debridone.manga.suwayomi.SuwayomiApi
import com.debridone.manga.suwayomi.SuwayomiException
import com.debridone.manga.suwayomi.SwFilter
import com.debridone.manga.suwayomi.SwFilterChange
import com.debridone.manga.suwayomi.SwPreference
import com.debridone.manga.suwayomi.SwPreferenceChange
import com.debridone.manga.suwayomi.SwSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A source as the screens show it: what Suwayomi says about it, and this library's choices. */
data class SourceEntry(
    val id: String,
    val name: String,
    /** Name with the language, as Suwayomi shows it ("MangaDex (EN)"). */
    val displayName: String,
    /** Language code of what the source offers: "en", "pt-BR", "all" for several, "" for the Local source. */
    val language: String,
    /** A path of the server for the source's icon. */
    val iconUrl: String,
    val homeUrl: String?,
    /** The address the source's requests go to, when it has one (what a connection diagnosis tries). */
    val baseUrl: String?,
    /** The source has settings of its own (an address, a login, quality options). */
    val configurable: Boolean,
    val supportsLatest: Boolean,
    /** SAFE, MIXED or NSFW, as its extension declares. */
    val contentWarning: String,
    /** Suwayomi's own source that reads folders and archives on the server's disk. */
    val isLocal: Boolean,
    val enabled: Boolean,
    val preferredRank: Int?,
    val health: SourceHealth?,
)

/** What one source answered to a search, or why it could not. */
data class SourceSearchResult(
    val source: SourceEntry,
    val series: List<Series>,
    val hasMore: Boolean,
    val error: SourceProblem?,
)

/** One page of a source's listing. */
data class SeriesPage(val series: List<Series>, val hasMore: Boolean)

enum class BrowseMode { POPULAR, LATEST, SEARCH }

/** A failure put in words a reader can act on. [failure] has the kind and the technical detail. */
data class SourceProblem(val code: String, val message: String, val temporary: Boolean, val failure: SourceFailure? = null) {
    companion object {
        fun of(sourceName: String, failure: Throwable, baseUrl: String? = null): SourceProblem {
            val classified = SourceFailure.of(sourceName, failure, baseUrl)
            return SourceProblem(classified.kind.name, classified.explanation, classified.temporary, classified)
        }
    }
}

/**
 * The sources of the connected Suwayomi server: the ones its installed extensions provide, plus
 * Suwayomi's Local source. Which are switched on, their order, and the last test of each are kept
 * in Suwayomi's own key-value store for sources, so they belong to that library.
 */
class MangaSources(
    private val server: MangaServer,
    private val repository: MangaRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Language codes of the sources that are on unless the person said otherwise. Empty: every language. */
    private val languages: () -> Set<String> = { emptySet() },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val api: SuwayomiApi get() = server.api()

    @Volatile
    private var known: List<SwSource>? = null

    @Volatile
    private var knownGeneration = -1
    private val filterCache = ConcurrentHashMap<String, List<SwFilter>>()

    @Serializable
    private data class StoredHealth(val testedAt: Long, val ok: Boolean, val steps: List<StoredStep>, val message: String? = null, val kind: String? = null)

    @Serializable
    private data class StoredStep(val name: String, val ok: Boolean, val detail: String, val kind: String? = null, val millis: Long? = null, val technical: String? = null)

    /**
     * Checks that a page's picture can be decoded, the way the reader will: set by the app, which
     * has the decoder. Returns a short description ("800 × 1200") or throws with the reason.
     */
    @Volatile
    var pictureDecoder: ((ByteArray) -> String)? = null

    private fun load(force: Boolean = false): List<SwSource> {
        val generation = server.generation.value
        val cached = known
        if (!force && cached != null && knownGeneration == generation) return cached
        val fresh = api.sources()
        known = fresh
        knownGeneration = generation
        if (force) filterCache.clear()
        return fresh
    }

    /** Forgets the list: after an extension was installed, updated or removed. */
    fun invalidate() {
        known = null
        filterCache.clear()
        repository.changed()
    }

    private fun entry(source: SwSource, preferred: List<String>): SourceEntry {
        val local = source.id == LOCAL_ID
        val wanted = languages()
        val byLanguage = local || wanted.isEmpty() || source.lang == "all" || source.lang in wanted || source.lang.substringBefore('-') in wanted
        return SourceEntry(
            id = source.id,
            name = if (local) "Local files" else source.name,
            displayName = if (local) "Local files" else source.displayName,
            language = if (local) "" else source.lang,
            iconUrl = source.iconUrl,
            homeUrl = source.homeUrl,
            baseUrl = source.baseUrl ?: source.homeUrl,
            configurable = source.configurable,
            supportsLatest = source.supportsLatest,
            contentWarning = source.contentWarning,
            isLocal = local,
            enabled = source.meta[ENABLED]?.toBooleanStrictOrNull() ?: byLanguage,
            preferredRank = preferred.indexOf(source.id).takeIf { it >= 0 },
            health = source.meta[HEALTH]?.let { text ->
                runCatching { json.decodeFromString(StoredHealth.serializer(), text) }.getOrNull()
                    ?.let { SourceHealth(source.id, it.testedAt, it.ok, it.steps.map { step -> HealthStep(step.name, step.ok, step.detail, step.kind, step.millis, step.technical) }, it.message, it.kind) }
            },
        )
    }

    private fun preferred(): List<String> =
        known?.firstOrNull { it.id == LOCAL_ID }?.meta?.get(PREFERRED)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    /** Every source of the server. Preferred sources come first, then by name. Asks the server when [refresh]. */
    fun list(profileId: Long, refresh: Boolean = false): List<SourceEntry> {
        if (server.profileId != profileId) return emptyList()
        val sources = load(refresh)
        val preferred = preferred()
        return sources.map { entry(it, preferred) }.sortedWith(compareBy<SourceEntry> { it.preferredRank ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() }.thenBy { it.language })
    }

    /** The sources that are switched on, without the Local source (which is not searched like a site). */
    fun enabled(profileId: Long): List<SourceEntry> = list(profileId).filter { it.enabled && !it.isLocal }

    fun find(sourceId: String): SourceEntry? {
        val sources = runCatching { load() }.getOrNull() ?: return null
        return sources.firstOrNull { it.id == sourceId }?.let { entry(it, preferred()) }
    }

    fun setEnabled(profileId: Long, sourceId: String, enabled: Boolean) {
        if (server.profileId != profileId) return
        api.setSourceMeta(sourceId, ENABLED, enabled.toString())
        load(force = true)
        repository.changed()
    }

    /** The sources to search first, in order. Kept on the Local source's entry: it is the one source every server has. */
    fun setPreferred(profileId: Long, sourceIds: List<String>) {
        if (server.profileId != profileId) return
        api.setSourceMeta(LOCAL_ID, PREFERRED, sourceIds.distinct().joinToString(","))
        load(force = true)
        repository.changed()
    }

    // ---- a source's own settings and filters ----------------------------------------------------

    /** The settings a source's extension defines (an address, a login, quality…), with their current values. */
    fun preferences(sourceId: String): List<SwPreference> = api.sourceDetails(sourceId).preferences

    /** Changes one of them and returns all of them as they are afterwards (one change can alter others). */
    fun setPreference(sourceId: String, change: SwPreferenceChange): List<SwPreference> {
        val result = api.setSourcePreference(sourceId, change)
        // A new address or login changes what the source lists and which filters it offers.
        filterCache.remove(sourceId)
        return result
    }

    /** The search filters a source defines. Some sources only know theirs after a first request. */
    fun filters(sourceId: String, refresh: Boolean = false): List<SwFilter> {
        if (!refresh) filterCache[sourceId]?.let { return it }
        return api.sourceDetails(sourceId).filters.also { filterCache[sourceId] = it }
    }

    // ---- searching and browsing -----------------------------------------------------------------

    /**
     * Asks several sources at once. One source failing does not hide the others: each result says
     * for itself whether it worked. [onResult] is told of each source's answer as it arrives.
     */
    suspend fun search(
        profileId: Long,
        query: String,
        sourceIds: List<String>? = null,
        parallel: Int = 4,
        onResult: (SourceSearchResult) -> Unit = {},
    ): List<SourceSearchResult> {
        val all = withContext(Dispatchers.IO) { list(profileId) }
        val chosen = if (sourceIds != null) all.filter { it.id in sourceIds } else all.filter { it.enabled && !it.isLocal }
        val permits = Semaphore(parallel.coerceIn(1, 8))
        return coroutineScope {
            chosen.map { source ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        val result = try {
                            val page = kotlinx.coroutines.runInterruptible { retrying(source.name, source.baseUrl) { browse(source.id, BrowseMode.SEARCH, query, 1) } }
                            SourceSearchResult(source, page.series, page.hasMore, null)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            SourceSearchResult(source, emptyList(), false, SourceProblem.of(source.name, failure, source.baseUrl))
                        }
                        onResult(result)
                        result
                    }
                }
            }.awaitAll()
        }
    }

    /** One page of a source: its popular or latest series, or a search with the source's own filters. */
    fun browse(sourceId: String, mode: BrowseMode, text: String = "", page: Int = 1, filters: List<SwFilterChange> = emptyList()): SeriesPage {
        val type = when (mode) {
            BrowseMode.POPULAR -> SuwayomiApi.BrowseType.POPULAR
            BrowseMode.LATEST -> SuwayomiApi.BrowseType.LATEST
            BrowseMode.SEARCH -> SuwayomiApi.BrowseType.SEARCH
        }
        val result = api.browse(sourceId, type, page, text, filters)
        repository.remember(result.items)
        return SeriesPage(result.items.mapNotNull { repository.series(it.id.toLong()) }, result.hasNextPage)
    }

    /**
     * Fetches a series' details and chapter list from its source again. Returns how many chapters
     * are new. A source that lists no chapters is not an error: the series then simply has none.
     */
    fun refresh(seriesId: Long): Int {
        val id = seriesId.toInt()
        val before = runCatching { repository.rawChapters(id).map { it.id }.toSet() }.getOrDefault(emptySet())
        var problem: SuwayomiException? = null
        try {
            repository.remember(api.refresh(id, details = true, chapters = false).first)
        } catch (failure: SuwayomiException) {
            problem = failure
        }
        val added = try {
            val (manga, chapters) = api.refresh(id, details = false, chapters = true)
            repository.remember(manga)
            repository.rememberChapters(id, chapters)
            chapters.count { it.id !in before }
        } catch (failure: SuwayomiException) {
            if (!failure.message.orEmpty().contains("No chapters found", ignoreCase = true)) throw failure
            repository.rememberChapters(id, emptyList())
            0
        }
        repository.changed()
        problem?.let { throw it }
        return added
    }

    // ---- retries ------------------------------------------------------------------------------

    /**
     * Runs [block] and runs it again, after a pause, when it fails in a way that passes by itself:
     * a timeout, a site that is down for a moment or answers 5xx, or asks for fewer requests (429).
     * A failure that will not change by waiting (a name that does not resolve, no route, a refusal,
     * an extension error) is reported at once. Interrupting the thread (cancelling) stops the wait.
     */
    fun <T> retrying(sourceName: String, baseUrl: String?, attempts: Int = RETRY_ATTEMPTS, block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (failure: Exception) {
                attempt++
                val classified = SourceFailure.of(sourceName, failure, baseUrl)
                val waitable = classified.kind == FailureKind.TIMEOUT || classified.kind == FailureKind.UNAVAILABLE || classified.httpStatus == 429
                if (!waitable || attempt >= attempts) throw failure
                Thread.sleep(RETRY_PAUSE_MS * attempt * attempt)
            }
        }
    }

    // ---- testing --------------------------------------------------------------------------------

    /**
     * Tries a source for real, step by step: the extension loads, the source lists and searches,
     * one series' details and chapters come, one chapter's pages are listed, the first page arrives
     * and is a picture that decodes. Each step says how it went and, when it failed, what kind of
     * failure it was, so a network problem is not taken for a broken extension or a reader problem.
     * The result is kept with the source. Nothing is added to the library, and only one page is
     * fetched.
     */
    fun test(sourceId: String): SourceHealth {
        val source = find(sourceId) ?: throw IllegalStateException("This source is not installed any more.")
        val steps = ArrayList<HealthStep>()
        var failure: SourceFailure? = null
        fun stop(name: String, kind: FailureKind, detail: String) {
            steps += HealthStep(name, false, detail, kind.name)
            failure = SourceFailure(kind, kind.label, detail, "")
        }
        fun <T> step(name: String, describe: (T) -> String, onFailure: FailureKind? = null, block: () -> T): T? {
            if (failure != null) return null
            val start = System.nanoTime()
            return try {
                block().also { steps += HealthStep(name, true, describe(it), millis = (System.nanoTime() - start) / 1_000_000) }
            } catch (problem: Exception) {
                val classified = SourceFailure.of(source.name, problem, source.baseUrl).let { found ->
                    // A failure inside the extension at this step is named by the step (a chapter or a picture that did not come).
                    if (onFailure != null && found.kind == FailureKind.EXTENSION) found.copy(kind = onFailure, title = onFailure.label) else found
                }
                steps += HealthStep(name, false, classified.explanation, classified.kind.name, (System.nanoTime() - start) / 1_000_000, classified.detail.takeIf { it.isNotBlank() })
                failure = classified
                null
            }
        }
        steps += HealthStep("Switched on", source.enabled, if (source.enabled) "Yes" else "No: it is not searched or listed until switched on")
        step<Int>("Extension load", { if (it == 0) "Loaded by the manga server" else "Loaded by the manga server · $it settings" }, FailureKind.INCOMPATIBLE) {
            api.sourceDetails(sourceId).preferences.size
        }
        val listed = step<SeriesPage>("Discovery", { "${it.series.size} series listed" }) { retrying(source.name, source.baseUrl) { browse(sourceId, BrowseMode.POPULAR) } }
        val first = listed?.series?.firstOrNull()
        if (listed != null && first == null) stop("Discovery", FailureKind.EXTENSION, "The source listed nothing, so nothing further could be tried. It may need to be configured first.")
        if (first != null) {
            step<SeriesPage>("Search", { "${count(it.series.size, "result")} for \"${word(first.title)}\"" }) { retrying(source.name, source.baseUrl) { browse(sourceId, BrowseMode.SEARCH, word(first.title)) } }
            // The first series may be ones without chapters (e-books on a Komga server, licensed titles): try several.
            var readable: Pair<Series, Int>? = null
            step<Series>("Series metadata", { "\"${it.title}\"" + (it.authors.firstOrNull()?.let { a -> " by $a" } ?: "") }) {
                retrying(source.name, source.baseUrl) { repository.remember(api.refresh(first.id.toInt(), details = true, chapters = false).first) }
                repository.series(first.id) ?: first
            }
            step<Pair<Series, Int>>("Chapters", { "${count(it.second, "chapter")} of \"${it.first.title}\"" + if (it.first.id != first.id) " (the first series listed none)" else "" }, FailureKind.CHAPTER) {
                var last: Pair<Series, Int>? = null
                for (candidate in listed.series.take(TEST_CANDIDATES)) {
                    retrying(source.name, source.baseUrl) { refresh(candidate.id) }
                    val count = repository.chapters(candidate.id).size
                    last = (repository.series(candidate.id) ?: candidate) to count
                    if (count > 0) break
                }
                (last ?: error("No series could be opened.")).also { readable = it }
            }
            val chapter = readable?.takeIf { it.second > 0 }?.let { repository.rawChapters(it.first.id.toInt()).firstOrNull() }
            if (readable != null && chapter == null) stop("Chapters", FailureKind.CHAPTER, "None of the first ${minOf(TEST_CANDIDATES, listed.series.size)} series lists a chapter.")
            if (chapter != null) {
                val pages = step<List<String>>("Page request", { "${count(it.size, "page")} listed" }, FailureKind.CHAPTER) {
                    val (opened, pages) = retrying(source.name, source.baseUrl) { api.chapterPages(chapter.id) }
                    repository.reloadChapters(opened.mangaId)
                    if (pages.isEmpty()) error("The chapter has no pages.")
                    pages
                }
                val bytes = pages?.firstOrNull()?.let { path ->
                    step<ByteArray>("Image download", { "${it.size / 1024} KB" }, FailureKind.IMAGE) {
                        retrying(source.name, source.baseUrl) { server.transport().bytes(path) }
                    }
                }
                if (bytes != null) {
                    when (val checked = com.debridone.manga.reader.PageImageCheck.inspect(bytes)) {
                        is com.debridone.manga.reader.PageBytes.Whole -> {
                            val decoder = pictureDecoder
                            if (decoder == null) {
                                steps += HealthStep("Image decode", true, "A whole ${checked.format.label} picture")
                            } else {
                                step<String>("Image decode", { "A ${checked.format.label} picture, $it" }, FailureKind.IMAGE) { decoder(bytes) }
                            }
                        }
                        else -> stop("Image decode", FailureKind.IMAGE, com.debridone.manga.reader.PageImageCheck.problem(checked, 1) ?: "The first page is not a picture.")
                    }
                }
            }
        }
        val stopped = failure
        val health = SourceHealth(sourceId, clock(), stopped == null, steps, stopped?.explanation, stopped?.kind?.name)
        runCatching {
            api.setSourceMeta(
                sourceId, HEALTH,
                json.encodeToString(
                    StoredHealth.serializer(),
                    StoredHealth(health.testedAt, health.ok, steps.map { StoredStep(it.name, it.ok, it.detail, it.kind, it.millis, it.technical?.take(1500)) }, health.message, health.kind),
                ),
            )
            load(force = true)
        }
        repository.changed()
        return health
    }

    private fun word(title: String): String = title.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.maxByOrNull { it.length } ?: title

    companion object {
        const val LOCAL_ID = "0"
        private const val ENABLED = "debridone.enabled"
        private const val PREFERRED = "debridone.preferredSources"
        private const val HEALTH = "debridone.health"
        private const val TEST_CANDIDATES = 12
        private const val RETRY_ATTEMPTS = 3
        private const val RETRY_PAUSE_MS = 1_500L

        /** The languages a new profile's sources are on for: this computer's language, English, and multi-language sources. */
        fun defaultLanguages(): Set<String> = setOf(Locale.getDefault().language.ifBlank { "en" }, "en")
    }
}

/** "1 page", "31 pages". */
private fun count(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
