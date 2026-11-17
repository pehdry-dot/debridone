package com.debridone.manga.sources

import com.debridone.manga.host.HostException
import com.debridone.manga.host.MangaExtensions
import com.debridone.manga.host.RemoteChapter
import com.debridone.manga.host.RemoteSeries
import com.debridone.manga.host.SeriesPage
import com.debridone.manga.host.SourceDescriptor
import com.debridone.manga.library.ChapterDraft
import com.debridone.manga.library.HealthStep
import com.debridone.manga.library.MangaRepository
import com.debridone.manga.library.Series
import com.debridone.manga.library.SeriesDetails
import com.debridone.manga.library.SeriesOrigin
import com.debridone.manga.library.SourceHealth
import com.debridone.platform.secrets.ScopedSecretStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** A source as the Sources tab shows it: what the package says, this profile's choices, and the last test. */
data class SourceEntry(
    val descriptor: SourceDescriptor,
    val enabled: Boolean,
    val preferredRank: Int?,
    val health: SourceHealth?,
    /** True when the source has settings and none have been saved for this profile yet. */
    val needsSetup: Boolean,
) {
    val id: String get() = descriptor.id
    val name: String get() = descriptor.name
}

/** What one source answered to a search, or why it could not. */
data class SourceSearchResult(
    val source: SourceDescriptor,
    val series: List<RemoteSeries>,
    val hasMore: Boolean,
    val error: SourceProblem?,
)

/** A failure put in words a reader can act on. */
data class SourceProblem(val code: String, val message: String, val temporary: Boolean) {
    companion object {
        fun of(sourceName: String, failure: Throwable): SourceProblem {
            val code = (failure as? HostException)?.code
            val detail = failure.message.orEmpty()
            return when (code) {
                "NETWORK" -> SourceProblem("NETWORK", "$sourceName could not be reached. Check the connection, or the site may be down.", true)
                "TIMEOUT" -> SourceProblem("TIMEOUT", "$sourceName did not answer in time.", true)
                "BROWSER_CHECK" -> SourceProblem("BROWSER_CHECK", "$sourceName asks for a browser check, which DebridOne does not pass. This source cannot be used here.", false)
                "UNEXPECTED_ANSWER" -> SourceProblem("UNEXPECTED_ANSWER", "$sourceName answered in a way its extension does not understand. The site has probably changed, or needs to be set up. ($detail)", false)
                "HTTP_ERROR" -> SourceProblem("HTTP_ERROR", "$sourceName refused the request: $detail", false)
                "UNSUPPORTED" -> SourceProblem("UNSUPPORTED", detail, false)
                "UNKNOWN_SOURCE" -> SourceProblem("UNKNOWN_SOURCE", "$sourceName is not installed any more.", false)
                "HOST_STOPPED" -> SourceProblem("HOST_STOPPED", "The extension host stopped while $sourceName was working. Try again.", true)
                "HOST_UNSTABLE", "NO_RUNTIME", "START_FAILED" -> SourceProblem(code, detail, false)
                null -> SourceProblem("ERROR", detail.ifBlank { "Something went wrong." }, false)
                else -> SourceProblem(code, "$sourceName reported an error: $detail", false)
            }
        }
    }
}

/**
 * Sources, per profile: which are enabled and preferred, their settings, searching and browsing,
 * and turning what a source returns into rows of the library.
 */
class MangaSources(
    private val extensions: MangaExtensions,
    private val repository: MangaRepository,
    private val secrets: ScopedSecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * False for a series whose details come from a metadata provider that ranks above its source:
     * the source then only fills in what is missing instead of replacing those details.
     */
    private val sourceLeads: (seriesId: Long) -> Boolean = { true },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Sources of the loaded packages for this profile. Preferred sources come first, then by name. */
    fun list(profileId: Long): List<SourceEntry> {
        val state = extensions.state.value ?: return emptyList()
        val preferences = repository.sourcePreferences(profileId)
        val health = repository.sourceHealth()
        return state.sources.map { descriptor ->
            val preference = preferences[descriptor.id]
            SourceEntry(
                descriptor = descriptor,
                enabled = preference?.enabled ?: true,
                preferredRank = preference?.preferredRank,
                health = health[descriptor.id],
                needsSetup = descriptor.settingTypes.values.any { it == "STRING" } && storedSettings(profileId, descriptor.id) == null &&
                    descriptor.defaultSettings.values.any { (it as? JsonPrimitive)?.contentOrNull == "" },
            )
        }.sortedWith(compareBy<SourceEntry> { it.preferredRank ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() })
    }

    fun enabled(profileId: Long): List<SourceEntry> = list(profileId).filter { it.enabled }

    fun find(extensionId: String): SourceDescriptor? = extensions.state.value?.source(extensionId)

    fun setEnabled(profileId: Long, extensionId: String, enabled: Boolean) = repository.setSourceEnabled(profileId, extensionId, enabled)

    fun setPreferred(profileId: Long, extensionIds: List<String>) = repository.setPreferredSources(profileId, extensionIds)

    // ---- settings -------------------------------------------------------------------------------

    /**
     * A source's saved settings for this profile, or null. They are kept in the system's credential
     * store (the Keychain on macOS), never in the database: a source's settings are where its
     * server address, user name and password live.
     */
    fun storedSettings(profileId: Long, extensionId: String): JsonObject? =
        secrets.child("profile.$profileId").get("source.$extensionId")?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    /** The values to show in the settings dialog: the saved ones over the source's defaults. */
    fun currentSettings(profileId: Long, descriptor: SourceDescriptor): JsonObject {
        val stored = storedSettings(profileId, descriptor.id)
        return buildJsonObject {
            descriptor.defaultSettings.forEach { (key, value) -> put(key, stored?.get(key) ?: value) }
        }
    }

    /** Saves a source's settings for this profile and applies them to the running source. Only settings the source declares are kept. */
    suspend fun saveSettings(profileId: Long, descriptor: SourceDescriptor, values: JsonObject) {
        val accepted = buildJsonObject {
            values.forEach { (key, value) ->
                val type = descriptor.settingTypes[key] ?: return@forEach
                if (value is JsonPrimitive && matchesType(type, value)) put(key, value)
            }
        }
        secrets.child("profile.$profileId").put("source.${descriptor.id}", accepted.toString())
        extensions.applySettings(descriptor.id, accepted)
    }

    fun clearSettings(profileId: Long, extensionId: String) {
        secrets.child("profile.$profileId").delete("source.$extensionId")
    }

    private fun matchesType(type: String, value: JsonPrimitive): Boolean = when (type) {
        "STRING", "LANGUAGE_KEY" -> value.isString && value.content.length <= MAX_SETTING_LENGTH
        "BOOLEAN" -> !value.isString && value.booleanOrNull != null
        "NUMBER" -> !value.isString && value.doubleOrNull != null
        else -> false
    }

    // ---- searching and browsing -----------------------------------------------------------------

    /**
     * Searches several sources at once ([extensionIds], or every enabled source). Each source's
     * answer is delivered through [onResult] as soon as it arrives; a source that fails reports why
     * and the others carry on. Results are never merged: the same title from two sources stays two.
     */
    suspend fun search(
        profileId: Long,
        text: String,
        extensionIds: List<String>? = null,
        onResult: (SourceSearchResult) -> Unit = {},
    ): List<SourceSearchResult> = coroutineScope {
        extensions.ensureLoaded()
        val chosen = enabled(profileId).filter { extensionIds == null || it.id in extensionIds }
        val limit = Semaphore(PARALLEL_SOURCES)
        chosen.map { entry ->
            async {
                limit.withPermit {
                    val result = try {
                        val page = extensions.search(entry.id, text, 1)
                        SourceSearchResult(entry.descriptor, page.series, page.hasMore, null)
                    } catch (failure: HostException) {
                        SourceSearchResult(entry.descriptor, emptyList(), false, SourceProblem.of(entry.name, failure))
                    }
                    onResult(result)
                    result
                }
            }
        }.awaitAll()
    }

    /** One page of one source: its directory when [text] is blank, a search otherwise, with the source's own filters. */
    suspend fun browse(extensionId: String, text: String, page: Int, filters: JsonObject = JsonObject(emptyMap())): SeriesPage =
        if (text.isBlank()) extensions.directory(extensionId, page, filters) else extensions.search(extensionId, text.trim(), page, filters)

    // ---- from a source into the library ---------------------------------------------------------

    /** Gives a series found in a source a row for this profile (outside the library until it is added). */
    fun remember(profileId: Long, remote: RemoteSeries): Series {
        val name = find(remote.extensionId)?.name.orEmpty()
        val known = repository.seriesBySource(profileId, remote.extensionId, remote.sourceId)
        return repository.ensureSeries(
            profileId, SeriesOrigin.EXTENSION, remote.extensionId, remote.sourceId, name, details(remote), remote.raw.toString(),
            onlyFillEmpty = known != null && !sourceLeads(known.id),
        )
    }

    /**
     * Fetches a series' details and chapters from its source and stores them. Locked fields and
     * reading progress are untouched. Returns the number of chapters that are new.
     */
    suspend fun refresh(seriesId: Long): Int {
        val series = repository.series(seriesId) ?: return 0
        if (series.isLocal) return 0
        val remote = extensions.series(series.extensionId, series.sourceId)
        if (remote != null) {
            repository.ensureSeries(
                series.profileId, SeriesOrigin.EXTENSION, series.extensionId, series.sourceId, find(series.extensionId)?.name ?: series.sourceName,
                details(remote), remote.raw.toString(), onlyFillEmpty = !sourceLeads(seriesId),
            )
        }
        val chapters = extensions.chapters(series.extensionId, series.sourceId)
        return repository.syncChapters(seriesId, chapters.map(::draft))
    }

    /** The details a series' source gives for it right now. */
    suspend fun sourceDetails(series: Series): SeriesDetails? {
        if (series.isLocal || find(series.extensionId) == null) return null
        return extensions.series(series.extensionId, series.sourceId)?.let(::details)
    }

    private fun details(remote: RemoteSeries): SeriesDetails = SeriesDetails(
        title = remote.title,
        altTitles = remote.altTitles,
        description = remote.description,
        authors = remote.authors,
        artists = remote.artists,
        tags = remote.tags,
        status = remote.status,
        originalLanguage = remote.originalLanguage,
        coverUrl = remote.coverUrl,
    )

    private fun draft(chapter: RemoteChapter): ChapterDraft = ChapterDraft(
        sourceId = chapter.sourceId,
        title = chapter.title,
        chapterNumber = chapter.chapterNumber,
        volumeNumber = chapter.volumeNumber,
        language = chapter.language,
        groupName = chapter.groupName,
        releasedAt = chapter.time,
    )

    // ---- testing --------------------------------------------------------------------------------

    /**
     * Exercises a source the way a reader would: browse, search, open a series, list its chapters,
     * and ask for the pages of one. The outcome of each step is stored and shown in the Sources tab,
     * so "working" always means "it worked on this computer on that date". No page image is
     * downloaded by the test.
     */
    suspend fun test(extensionId: String): SourceHealth {
        val descriptor = extensions.ensureLoaded().source(extensionId)
            ?: return SourceHealth(extensionId, clock(), false, emptyList(), "This source is not installed.").also(repository::saveSourceHealth)
        val steps = ArrayList<HealthStep>()
        var message: String? = null
        suspend fun <T> step(name: String, describe: (T) -> String, block: suspend () -> T): T? = try {
            val value = block()
            steps += HealthStep(name, true, describe(value))
            value
        } catch (failure: HostException) {
            val problem = SourceProblem.of(descriptor.name, failure)
            steps += HealthStep(name, false, problem.message)
            if (message == null) message = problem.message
            null
        }

        val directory = step<SeriesPage>("Browse", { "${it.series.size} series" }) {
            extensions.directory(extensionId, 1).also { if (it.series.isEmpty()) throw HostException("EMPTY", "The source listed nothing.") }
        }
        val sample = directory?.series?.firstOrNull()
        if (sample != null) {
            val word = sample.title.split(Regex("\\s+")).firstOrNull { it.length >= 3 } ?: sample.title
            step<SeriesPage>("Search", { "${it.series.size} results for \"$word\"" }) { extensions.search(extensionId, word, 1) }
            val series = step<RemoteSeries>("Series details", { it.title }) {
                extensions.series(extensionId, sample.sourceId) ?: throw HostException("EMPTY", "The source returned no details.")
            }
            val chapters = step<List<RemoteChapter>>("Chapters", { "${it.size} chapters" }) {
                extensions.chapters(extensionId, sample.sourceId).also { if (it.isEmpty()) throw HostException("EMPTY", "The series has no chapters.") }
            }
            val chapter = chapters?.firstOrNull()
            if (series != null && chapter != null) {
                step<List<String>>("Pages", { "${it.size} pages in ${chapter.title.ifBlank { "chapter ${chapter.chapterNumber}" }}" }) {
                    extensions.pages(extensionId, sample.sourceId, chapter.sourceId).also { if (it.isEmpty()) throw HostException("EMPTY", "The chapter has no pages.") }
                }
            }
        }
        val health = SourceHealth(extensionId, clock(), steps.isNotEmpty() && steps.all { it.ok } && steps.size >= 5, steps, message)
        repository.saveSourceHealth(health)
        return health
    }

    companion object {
        private const val PARALLEL_SOURCES = 6
        private const val MAX_SETTING_LENGTH = 2_000
    }
}
