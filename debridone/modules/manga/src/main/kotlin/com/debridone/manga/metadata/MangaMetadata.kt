package com.debridone.manga.metadata

import com.debridone.manga.library.MangaRepository
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.Series
import com.debridone.manga.library.SeriesDetails
import com.debridone.platform.secrets.ScopedSecretStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Details from AniList, MangaDex and MyAnimeList: for finding a series' description, people, tags
 * and cover, and for the charts on Home. Reading lists (tracking) are not here: they are the
 * manga server's, see `tracking/MangaTrackers`.
 *
 * Rules:
 *  - a field the person edited and locked is never changed by any provider;
 *  - providers are consulted in the order set in Plugins › Manga › Metadata, and each field takes
 *    the first value offered;
 *  - a provider that is switched off is not contacted at all;
 *  - nothing here is shared with Streaming or Music: these are Manga's own providers, links and
 *    credentials, kept in Manga's database and Manga's part of the credential store.
 */
class MangaMetadata(
    private val repository: MangaRepository,
    private val secrets: ScopedSecretStore,
    private val providerEnabled: (String) -> Boolean,
    /** Position of a provider in the priority order; lower comes first. */
    private val providerPriority: (String) -> Int,
    /** The details a series' own source gives for it now, or null when the source is not available. */
    private val extensionDetails: suspend (Series) -> SeriesDetails?,
    endpoints: MetadataEndpoints = MetadataEndpoints(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val aniList = AniListSource(endpoints)
    val mangaDex = MangaDexSource(endpoints)
    val myAnimeList = MyAnimeListSource(endpoints) { myAnimeListClientId() }
    private val all: List<MetadataSource> = listOf(aniList, mangaDex, myAnimeList)

    private val popularCache = ConcurrentHashMap<String, Pair<Long, List<MetadataMatch>>>()

    fun source(providerId: String): MetadataSource? = all.firstOrNull { it.id == providerId }

    /** Providers that are switched on, in priority order. */
    fun enabledSources(): List<MetadataSource> = all.filter { providerEnabled(it.id) }.sortedBy { providerPriority(it.id) }

    suspend fun search(providerId: String, query: String): List<MetadataMatch> {
        val source = source(providerId) ?: throw MetadataException("Unknown metadata provider.")
        if (!providerEnabled(providerId)) throw MetadataException("${source.name} is switched off in Plugins › Manga › Metadata.")
        if (query.isBlank()) return emptyList()
        return source.search(query.trim())
    }

    /**
     * Uses [match] for a series: its details are applied (locked fields excepted), the series is
     * linked to that entry so it can be refreshed later, and the same work's ids on other services
     * are remembered. Returns the fields that changed.
     */
    suspend fun apply(seriesId: Long, match: MetadataMatch): Set<String> {
        val full = runCatching { source(match.provider)?.details(match.remoteId) }.getOrNull() ?: match
        val changed = repository.applyDetails(seriesId, full.toDetails())
        repository.linkMetadata(seriesId, full.provider, full.remoteId, full.url)
        val existing = repository.metadataLinks(seriesId).map { it.provider }.toSet()
        for ((provider, remoteId) in full.externalIds) {
            if (provider !in existing && source(provider) != null) repository.linkMetadata(seriesId, provider, remoteId, null)
        }
        return changed
    }

    /**
     * Fetches the series' details again from its source and from every provider it is linked to,
     * and applies them by priority. Locked fields keep the person's edits. Returns the fields that changed.
     */
    suspend fun refresh(seriesId: Long): Set<String> {
        val series = repository.series(seriesId) ?: return emptySet()
        val links = repository.metadataLinks(seriesId).associateBy { it.provider }
        val offers = ArrayList<Pair<Int, SeriesDetails>>()
        var failure: MetadataException? = null
        if (!series.isLocal && providerEnabled(EXTENSION)) {
            runCatching { extensionDetails(series) }.getOrNull()?.let { offers += providerPriority(EXTENSION) to it }
        }
        for (source in enabledSources()) {
            val link = links[source.id] ?: continue
            try {
                source.details(link.remoteId)?.let { offers += providerPriority(source.id) to it.toDetails() }
            } catch (problem: MetadataException) {
                failure = problem
            }
        }
        if (offers.isEmpty()) {
            failure?.let { throw it }
            return emptySet()
        }
        return repository.applyDetails(seriesId, merge(offers.sortedBy { it.first }.map { it.second }))
    }

    /** True while the series' own source may overwrite details: no linked, enabled provider comes before it. */
    fun extensionLeads(seriesId: Long): Boolean {
        val extension = providerPriority(EXTENSION)
        return repository.metadataLinks(seriesId).none { providerEnabled(it.provider) && providerPriority(it.provider) < extension }
    }

    /** Each field from the first offer that has it. */
    private fun merge(offers: List<SeriesDetails>): SeriesDetails {
        fun <T> first(pick: (SeriesDetails) -> T?): T? = offers.firstNotNullOfOrNull(pick)
        return SeriesDetails(
            title = first { it.title?.takeIf(String::isNotBlank) },
            altTitles = offers.flatMap { it.altTitles.orEmpty() }.distinct().takeIf { it.isNotEmpty() },
            description = first { it.description?.takeIf(String::isNotBlank) },
            authors = first { it.authors?.takeIf { list -> list.isNotEmpty() } },
            artists = first { it.artists?.takeIf { list -> list.isNotEmpty() } },
            tags = first { it.tags?.takeIf { list -> list.isNotEmpty() } },
            status = first { it.status },
            originalLanguage = first { it.originalLanguage?.takeIf { key -> key != "MULTI" } } ?: first { it.originalLanguage },
            mediaType = first { it.mediaType },
            coverUrl = first { it.coverUrl?.takeIf(String::isNotBlank) },
            bannerUrl = first { it.bannerUrl?.takeIf(String::isNotBlank) },
        )
    }

    /** What AniList lists as trending for a shelf (Japan for Manga, Korea for Manhwa). Kept for half an hour. */
    suspend fun popular(type: MediaType): List<MetadataMatch> {
        if (!providerEnabled(aniList.id)) return emptyList()
        val country = if (type == MediaType.MANHWA) "KR" else "JP"
        popularCache[country]?.takeIf { clock() - it.first < POPULAR_TTL_MS }?.let { return it.second }
        val list = aniList.popular(country)
        popularCache[country] = clock() to list
        return list
    }

    // ---- credentials ----------------------------------------------------------------------------

    /** The person's MyAnimeList API client ID. One per installation, kept in the credential store. */
    fun myAnimeListClientId(): String? = secrets.get(MAL_CLIENT_ID)?.takeIf { it.isNotBlank() }

    fun setMyAnimeListClientId(value: String?) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) secrets.delete(MAL_CLIENT_ID) else secrets.put(MAL_CLIENT_ID, trimmed)
    }

    /**
     * Removes what the earlier version of Manga kept for a deleted profile: its own AniList and
     * MyAnimeList sign-ins. (Tracker sign-ins are now the manga server's, in the profile's folder.)
     */
    fun deleteProfile(profileId: Long) {
        val store = profile(profileId)
        for (provider in listOf(aniList.id, myAnimeList.id)) {
            listOf("token", "access", "refresh", "name").forEach { store.delete("tracker.$provider.$it") }
        }
    }

    /** True when the earlier version of Manga left a tracker sign-in of its own for this profile. */
    fun hasLegacyTrackerSignIn(profileId: Long): Boolean {
        val store = profile(profileId)
        return listOf(aniList.id, myAnimeList.id).any { store.get("tracker.$it.name") != null }
    }

    private fun profile(profileId: Long): ScopedSecretStore = secrets.child("profile.$profileId")

    companion object {
        const val EXTENSION = "extension"
        private const val MAL_CLIENT_ID = "myanimelist.client-id"
        private const val POPULAR_TTL_MS = 30 * 60 * 1000L
    }
}
