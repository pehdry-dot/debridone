package com.debridone.manga.metadata

import com.debridone.manga.library.MangaRepository
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.Series
import com.debridone.manga.library.SeriesDetails
import com.debridone.platform.secrets.ScopedSecretStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** A tracking account connected by one profile. */
data class TrackerAccount(val provider: String, val name: String)

/** What happened when reading progress was sent to a tracking service. */
data class TrackingResult(val provider: String, val ok: Boolean, val message: String)

/**
 * Details from AniList, MangaDex and MyAnimeList, and — only when the person has connected their
 * own account — sending reading progress back to AniList or MyAnimeList.
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
    private val pendingVerifiers = ConcurrentHashMap<Long, String>()

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

    fun account(profileId: Long, provider: String): TrackerAccount? =
        profile(profileId).get("tracker.$provider.name")?.let { TrackerAccount(provider, it) }

    /** Connects an AniList account with a token the person obtained from AniList for their own API client. */
    suspend fun connectAniList(profileId: Long, token: String): TrackerAccount {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) throw MetadataException("Paste the token AniList showed you.")
        val name = aniList.viewer(trimmed)
        profile(profileId).put("tracker.anilist.token", trimmed)
        profile(profileId).put("tracker.anilist.name", name)
        return TrackerAccount(aniList.id, name)
    }

    /** The page to open for an AniList token, for the person's own API client (redirect address `https://anilist.co/api/v2/oauth/pin`). */
    fun aniListAuthorizationUrl(clientId: String): String {
        val id = clientId.trim()
        if (id.toIntOrNull() == null) throw MetadataException("An AniList client ID is a number. Create a client at anilist.co › Settings › Developer.")
        return "https://anilist.co/api/v2/oauth/authorize?client_id=$id&response_type=token"
    }

    /** The MyAnimeList sign-in page for this profile. The secret that goes with it is kept until [connectMyAnimeList]. */
    fun myAnimeListAuthorizationUrl(profileId: Long): String {
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        pendingVerifiers[profileId] = verifier
        return myAnimeList.authorizationUrl(verifier)
    }

    /**
     * Finishes the MyAnimeList sign-in. [redirected] is the address the browser ended up at after
     * the person allowed access (it contains `code=…`), or just that code.
     */
    suspend fun connectMyAnimeList(profileId: Long, redirected: String): TrackerAccount {
        val verifier = pendingVerifiers[profileId] ?: throw MetadataException("Open the MyAnimeList sign-in page first.")
        val code = Regex("[?&]code=([^&#\\s]+)").find(redirected)?.groupValues?.get(1) ?: redirected.trim()
        if (code.isEmpty() || code.contains(' ')) throw MetadataException("That does not contain the code MyAnimeList sent back.")
        val (access, refresh) = myAnimeList.exchange(code, verifier)
        val name = myAnimeList.viewer(access)
        val store = profile(profileId)
        store.put("tracker.myanimelist.access", access)
        if (refresh != null) store.put("tracker.myanimelist.refresh", refresh)
        store.put("tracker.myanimelist.name", name)
        pendingVerifiers.remove(profileId)
        return TrackerAccount(myAnimeList.id, name)
    }

    fun disconnect(profileId: Long, provider: String) {
        val store = profile(profileId)
        listOf("token", "access", "refresh", "name").forEach { store.delete("tracker.$provider.$it") }
    }

    /** Removes a deleted profile's tracking sign-ins. */
    fun deleteProfile(profileId: Long) {
        disconnect(profileId, aniList.id)
        disconnect(profileId, myAnimeList.id)
    }

    // ---- tracking -------------------------------------------------------------------------------

    /**
     * Sends how far the series has been read to the services it is tracked on. Does nothing for a
     * series that is not tracked. Each service reports for itself; one failing does not stop the other.
     */
    suspend fun syncProgress(seriesId: Long): List<TrackingResult> {
        val series = repository.series(seriesId) ?: return emptyList()
        val tracked = repository.metadataLinks(seriesId).filter { it.tracked }
        if (tracked.isEmpty()) return emptyList()
        val chapters = repository.chapters(seriesId, allLanguages = true)
        val read = chapters.filter { it.read }
        val progress = read.mapNotNull { it.chapterSort }.maxOrNull()?.toInt() ?: read.size
        val completed = series.status == "Completed" && chapters.isNotEmpty() && chapters.all { it.read }
        val store = profile(series.profileId)
        return tracked.map { link ->
            try {
                when (link.provider) {
                    aniList.id -> {
                        val token = store.get("tracker.anilist.token") ?: throw MetadataException("No AniList account is connected for this profile.")
                        aniList.saveProgress(token, link.remoteId, progress, completed)
                    }
                    myAnimeList.id -> {
                        val access = store.get("tracker.myanimelist.access") ?: throw MetadataException("No MyAnimeList account is connected for this profile.")
                        try {
                            myAnimeList.saveProgress(access, link.remoteId, progress, completed)
                        } catch (expired: MetadataException) {
                            // Access tokens last a month; renew once with the refresh token and try again.
                            val refresh = store.get("tracker.myanimelist.refresh") ?: throw expired
                            val (renewed, nextRefresh) = myAnimeList.refresh(refresh)
                            store.put("tracker.myanimelist.access", renewed)
                            if (nextRefresh != null) store.put("tracker.myanimelist.refresh", nextRefresh)
                            myAnimeList.saveProgress(renewed, link.remoteId, progress, completed)
                        }
                    }
                    else -> throw MetadataException("${link.provider} does not keep reading lists.")
                }
                TrackingResult(link.provider, true, "Chapter $progress")
            } catch (problem: MetadataException) {
                TrackingResult(link.provider, false, problem.message ?: "The update failed.")
            }
        }
    }

    private fun profile(profileId: Long): ScopedSecretStore = secrets.child("profile.$profileId")

    companion object {
        const val EXTENSION = "extension"
        private const val MAL_CLIENT_ID = "myanimelist.client-id"
        private const val POPULAR_TTL_MS = 30 * 60 * 1000L
    }
}
