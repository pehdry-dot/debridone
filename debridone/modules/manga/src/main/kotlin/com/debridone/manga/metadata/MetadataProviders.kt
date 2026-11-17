package com.debridone.manga.metadata

import com.debridone.manga.library.MangaClassifier
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.SeriesDetails
import com.debridone.manga.net.MangaHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A work as a metadata service describes it. */
data class MetadataMatch(
    /** `anilist`, `mangadex` or `myanimelist`. */
    val provider: String,
    val remoteId: String,
    val title: String,
    val altTitles: List<String> = emptyList(),
    val description: String = "",
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** `Ongoing`, `Completed`, `Cancelled`, `Hiatus`, or null. */
    val status: String? = null,
    /** Tiyo language key of the original work. */
    val originalLanguage: String? = null,
    val mediaType: MediaType? = null,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    /** The work's page on the service, shown as the attribution of these details. */
    val url: String? = null,
    val year: Int? = null,
    /** Number of chapters the service knows of, when it says. */
    val chapters: Int? = null,
    /** The same work's ids on other services (provider id to remote id). */
    val externalIds: Map<String, String> = emptyMap(),
) {
    fun toDetails(): SeriesDetails = SeriesDetails(
        title = title.ifBlank { null },
        altTitles = altTitles.ifEmpty { null },
        description = description.ifBlank { null },
        authors = authors.ifEmpty { null },
        artists = artists.ifEmpty { null },
        tags = tags.ifEmpty { null },
        status = status,
        originalLanguage = originalLanguage,
        mediaType = mediaType,
        coverUrl = coverUrl,
        bannerUrl = bannerUrl,
    )
}

class MetadataException(message: String, val temporary: Boolean = false) : Exception(message)

/** Where each service is reached. Tests point these at local stand-ins. */
data class MetadataEndpoints(
    val aniList: String = "https://graphql.anilist.co",
    val mangaDex: String = "https://api.mangadex.org",
    val mangaDexCovers: String = "https://uploads.mangadex.org",
    val myAnimeList: String = "https://api.myanimelist.net/v2",
    val myAnimeListAuth: String = "https://myanimelist.net/v1/oauth2",
)

interface MetadataSource {
    val id: String
    val name: String
    suspend fun search(query: String): List<MetadataMatch>
    suspend fun details(remoteId: String): MetadataMatch?
}

internal val metadataJson = Json { ignoreUnknownKeys = true }

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.list(key: String): List<JsonElement> = (this[key] as? JsonArray) ?: emptyList()

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** Removes the HTML that AniList and MyAnimeList put in synopses. */
internal fun plainText(html: String?): String = html.orEmpty()
    .replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

internal suspend fun <T> metadataCall(service: String, block: () -> T): T = try {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
} catch (failure: com.debridone.manga.net.HttpStatusException) {
    throw when (failure.status) {
        401, 403 -> MetadataException("$service refused the request (${failure.status}). Check the credentials for it in Plugins › Manga › Metadata.")
        404 -> MetadataException("$service has no such entry.")
        429 -> MetadataException("$service is limiting requests right now. Try again in a minute.", temporary = true)
        else -> MetadataException("$service answered ${failure.status}.", temporary = failure.status >= 500)
    }
} catch (failure: java.io.IOException) {
    throw MetadataException("$service could not be reached (${failure.message ?: "network error"}).", temporary = true)
}

/** AniList, through its public GraphQL API. Searching and reading details needs no account. */
class AniListSource(private val endpoints: MetadataEndpoints) : MetadataSource {
    override val id = "anilist"
    override val name = "AniList"

    private val fields = """
        id idMal title { romaji english native } synonyms description(asHtml: false) status countryOfOrigin format genres
        tags { name rank isMediaSpoiler } coverImage { extraLarge large } bannerImage chapters startDate { year } siteUrl
        staff(perPage: 8, sort: RELEVANCE) { edges { role node { name { full } } } }
    """.trimIndent()

    override suspend fun search(query: String): List<MetadataMatch> {
        val body = buildJsonObject {
            put("query", "query (\$search: String) { Page(perPage: 12) { media(search: \$search, type: MANGA, sort: SEARCH_MATCH) { $fields } } }")
            putJsonObject("variables") { put("search", query) }
        }
        return media(post(body).obj("Page")?.list("media").orEmpty())
    }

    override suspend fun details(remoteId: String): MetadataMatch? {
        val numeric = remoteId.toIntOrNull() ?: return null
        val body = buildJsonObject {
            put("query", "query (\$id: Int) { Media(id: \$id, type: MANGA) { $fields } }")
            putJsonObject("variables") { put("id", numeric) }
        }
        return post(body).obj("Media")?.let(::parse)
    }

    /** What is popular now, by country of origin: `JP` for manga, `KR` for manhwa. */
    suspend fun popular(country: String, trending: Boolean = true): List<MetadataMatch> {
        val sort = if (trending) "TRENDING_DESC" else "POPULARITY_DESC"
        val body = buildJsonObject {
            put("query", "query (\$country: CountryCode) { Page(perPage: 24) { media(type: MANGA, sort: $sort, countryOfOrigin: \$country, isAdult: false) { $fields } } }")
            putJsonObject("variables") { put("country", country) }
        }
        return media(post(body).obj("Page")?.list("media").orEmpty())
    }

    private suspend fun post(body: JsonObject, token: String? = null): JsonObject = metadataCall(name) {
        val headers = if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
        val answer = metadataJson.parseToJsonElement(MangaHttp.postJson(endpoints.aniList, body.toString(), headers).text()).jsonObject
        answer.list("errors").firstOrNull()?.let { error ->
            throw MetadataException("AniList: " + ((error as? JsonObject)?.text("message") ?: "the request was not accepted"))
        }
        answer.obj("data") ?: JsonObject(emptyMap())
    }

    private fun media(items: List<JsonElement>): List<MetadataMatch> = items.mapNotNull { (it as? JsonObject)?.let(::parse) }

    private fun parse(media: JsonObject): MetadataMatch? {
        val id = media.int("id") ?: return null
        val titles = media.obj("title")
        val english = titles?.text("english")
        val romaji = titles?.text("romaji")
        val native = titles?.text("native")
        val title = english ?: romaji ?: native ?: return null
        val country = media.text("countryOfOrigin")
        val staff = media.obj("staff")?.list("edges").orEmpty().mapNotNull { edge ->
            val entry = edge as? JsonObject ?: return@mapNotNull null
            val person = entry.obj("node")?.obj("name")?.text("full") ?: return@mapNotNull null
            (entry.text("role") ?: "") to person
        }
        val genres = media.list("genres").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val tags = media.list("tags").mapNotNull { it as? JsonObject }
            .filter { (it["isMediaSpoiler"] as? JsonPrimitive)?.contentOrNull != "true" && (it.int("rank") ?: 0) >= 60 }
            .mapNotNull { it.text("name") }
        return MetadataMatch(
            provider = id(),
            remoteId = id.toString(),
            title = title,
            altTitles = (listOfNotNull(romaji, native, english) + media.list("synonyms").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }).filter { it != title }.distinct(),
            description = plainText(media.text("description")),
            authors = staff.filter { it.first.contains("Story", true) || it.first.contains("Original", true) }.map { it.second }.distinct(),
            artists = staff.filter { it.first.contains("Art", true) }.map { it.second }.distinct(),
            tags = (genres + tags).distinct(),
            status = when (media.text("status")) {
                "FINISHED" -> "Completed"
                "RELEASING", "NOT_YET_RELEASED" -> "Ongoing"
                "CANCELLED" -> "Cancelled"
                "HIATUS" -> "Hiatus"
                else -> null
            },
            originalLanguage = when (country) {
                "JP" -> "JAPANESE"
                "KR" -> "KOREAN"
                "CN" -> "CHINESE_SIMP"
                "TW" -> "CHINESE_TRAD"
                else -> null
            },
            mediaType = when (country) {
                "KR", "CN", "TW" -> MediaType.MANHWA
                "JP" -> MediaType.MANGA
                else -> null
            },
            coverUrl = media.obj("coverImage")?.let { it.text("extraLarge") ?: it.text("large") },
            bannerUrl = media.text("bannerImage"),
            url = media.text("siteUrl") ?: "https://anilist.co/manga/$id",
            year = media.obj("startDate")?.int("year"),
            chapters = media.int("chapters"),
            externalIds = media.int("idMal")?.let { mapOf("myanimelist" to it.toString()) }.orEmpty(),
        )
    }

    private fun id() = id

    // ---- tracking (only with the person's own token) -------------------------------------------

    /** The name of the account [token] belongs to; proves the token works. */
    suspend fun viewer(token: String): String {
        val body = buildJsonObject { put("query", "query { Viewer { name } }") }
        return post(body, token).obj("Viewer")?.text("name") ?: throw MetadataException("AniList did not accept this token.")
    }

    /** Sets how many chapters have been read on the account's list. */
    suspend fun saveProgress(token: String, remoteId: String, chaptersRead: Int, completed: Boolean) {
        val numeric = remoteId.toIntOrNull() ?: throw MetadataException("This series is not linked to AniList.")
        val status = if (completed) "COMPLETED" else "CURRENT"
        val body = buildJsonObject {
            put("query", "mutation (\$mediaId: Int, \$progress: Int, \$status: MediaListStatus) { SaveMediaListEntry(mediaId: \$mediaId, progress: \$progress, status: \$status) { id progress } }")
            putJsonObject("variables") {
                put("mediaId", numeric)
                put("progress", chaptersRead)
                put("status", status)
            }
        }
        post(body, token)
    }
}

/** MangaDex, through its public API, as a source of details (reading from MangaDex goes through its Tiyo source). */
class MangaDexSource(private val endpoints: MetadataEndpoints) : MetadataSource {
    override val id = "mangadex"
    override val name = "MangaDex"
    private val includes = "includes[]=cover_art&includes[]=author&includes[]=artist"

    override suspend fun search(query: String): List<MetadataMatch> = metadataCall(name) {
        val url = "${endpoints.mangaDex}/manga?title=${MangaHttp.encode(query)}&limit=12&$includes&order[relevance]=desc" +
            "&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica"
        metadataJson.parseToJsonElement(MangaHttp.get(url).text()).jsonObject.list("data").mapNotNull { (it as? JsonObject)?.let(::parse) }
    }

    override suspend fun details(remoteId: String): MetadataMatch? {
        if (!Regex("[0-9a-fA-F-]{36}").matches(remoteId)) return null
        return metadataCall(name) {
            metadataJson.parseToJsonElement(MangaHttp.get("${endpoints.mangaDex}/manga/$remoteId?$includes").text()).jsonObject.obj("data")?.let(::parse)
        }
    }

    private fun parse(entry: JsonObject): MetadataMatch? {
        val id = entry.text("id") ?: return null
        val attributes = entry.obj("attributes") ?: return null
        val titles = attributes.obj("title") ?: JsonObject(emptyMap())
        val title = titles.text("en") ?: titles.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull } ?: return null
        val altTitles = attributes.list("altTitles").flatMap { alt -> (alt as? JsonObject)?.values?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty() }
        val relationships = entry.list("relationships").mapNotNull { it as? JsonObject }
        fun names(type: String) = relationships.filter { it.text("type") == type }.mapNotNull { it.obj("attributes")?.text("name") }.distinct()
        val cover = relationships.firstOrNull { it.text("type") == "cover_art" }?.obj("attributes")?.text("fileName")
        val tags = attributes.list("tags").mapNotNull { (it as? JsonObject)?.obj("attributes")?.obj("name")?.text("en") }
        val language = when (attributes.text("originalLanguage")) {
            "ja" -> "JAPANESE"
            "ko" -> "KOREAN"
            "zh" -> "CHINESE_SIMP"
            "zh-hk" -> "CHINESE_TRAD"
            "en" -> "ENGLISH"
            else -> null
        }
        val links = attributes.obj("links")
        return MetadataMatch(
            provider = this.id,
            remoteId = id,
            title = title,
            altTitles = altTitles.filter { it != title }.distinct().take(12),
            description = attributes.obj("description")?.let { it.text("en") ?: it.values.firstNotNullOfOrNull { value -> (value as? JsonPrimitive)?.contentOrNull } }.orEmpty().trim(),
            authors = names("author"),
            artists = names("artist"),
            tags = tags,
            status = when (attributes.text("status")) {
                "ongoing" -> "Ongoing"
                "completed" -> "Completed"
                "hiatus" -> "Hiatus"
                "cancelled" -> "Cancelled"
                else -> null
            },
            originalLanguage = language,
            mediaType = if (language != null || tags.isNotEmpty()) MangaClassifier.classify(language, tags) else null,
            coverUrl = cover?.let { "${endpoints.mangaDexCovers}/covers/$id/$it.512.jpg" },
            url = "https://mangadex.org/title/$id",
            year = attributes.int("year"),
            chapters = attributes.text("lastChapter")?.toDoubleOrNull()?.toInt(),
            externalIds = buildMap {
                links?.text("al")?.let { put("anilist", it) }
                links?.text("mal")?.let { put("myanimelist", it) }
            },
        )
    }
}

/**
 * MyAnimeList, through its official API. Every request carries the person's own API client ID
 * (from https://myanimelist.net/apiconfig); without one nothing is requested. Updating a list
 * additionally needs the person to sign in through MyAnimeList's own OAuth pages.
 */
class MyAnimeListSource(private val endpoints: MetadataEndpoints, private val clientId: () -> String?) : MetadataSource {
    override val id = "myanimelist"
    override val name = "MyAnimeList"
    // Encoded: the braces of the nested field list are not allowed bare in an address.
    private val fields = MangaHttp.encode("id,title,alternative_titles,synopsis,main_picture,genres,status,media_type,num_chapters,start_date,authors{first_name,last_name}")

    private fun headers(): Map<String, String> {
        val id = clientId()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw MetadataException("MyAnimeList needs your own API client ID. Add it in Plugins › Manga › Metadata.")
        return mapOf("X-MAL-CLIENT-ID" to id)
    }

    override suspend fun search(query: String): List<MetadataMatch> {
        val headers = headers()
        // The API refuses queries shorter than three characters.
        val text = query.trim().take(64)
        if (text.length < 3) return emptyList()
        return metadataCall(name) {
            val url = "${endpoints.myAnimeList}/manga?q=${MangaHttp.encode(text)}&limit=12&fields=$fields&nsfw=false"
            metadataJson.parseToJsonElement(MangaHttp.get(url, headers).text()).jsonObject.list("data").mapNotNull { (it as? JsonObject)?.obj("node")?.let(::parse) }
        }
    }

    override suspend fun details(remoteId: String): MetadataMatch? {
        val headers = headers()
        if (remoteId.toIntOrNull() == null) return null
        return metadataCall(name) {
            parse(metadataJson.parseToJsonElement(MangaHttp.get("${endpoints.myAnimeList}/manga/$remoteId?fields=$fields", headers).text()).jsonObject)
        }
    }

    private fun parse(node: JsonObject): MetadataMatch? {
        val id = node.int("id") ?: return null
        val title = node.text("title") ?: return null
        val alternatives = node.obj("alternative_titles")
        val altTitles = listOfNotNull(alternatives?.text("en")?.takeIf { it.isNotBlank() }, alternatives?.text("ja")?.takeIf { it.isNotBlank() }) +
            alternatives?.list("synonyms").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val people = node.list("authors").mapNotNull { entry ->
            val item = entry as? JsonObject ?: return@mapNotNull null
            val person = item.obj("node") ?: return@mapNotNull null
            val name = listOfNotNull(person.text("first_name"), person.text("last_name")).filter { it.isNotBlank() }.joinToString(" ")
            if (name.isBlank()) null else (item.text("role") ?: "") to name
        }
        val mediaType = node.text("media_type")
        return MetadataMatch(
            provider = this.id,
            remoteId = id.toString(),
            title = title,
            altTitles = altTitles.filter { it != title }.distinct(),
            description = plainText(node.text("synopsis")),
            authors = people.filter { it.first.contains("Story", true) }.map { it.second }.distinct(),
            artists = people.filter { it.first.contains("Art", true) }.map { it.second }.distinct(),
            tags = node.list("genres").mapNotNull { (it as? JsonObject)?.text("name") },
            status = when (node.text("status")) {
                "finished" -> "Completed"
                "currently_publishing", "not_yet_published" -> "Ongoing"
                "on_hiatus" -> "Hiatus"
                "discontinued" -> "Cancelled"
                else -> null
            },
            originalLanguage = when (mediaType) {
                "manhwa" -> "KOREAN"
                "manhua" -> "CHINESE_SIMP"
                "manga", "one_shot", "doujinshi" -> "JAPANESE"
                else -> null
            },
            mediaType = when (mediaType) {
                "manhwa", "manhua" -> MediaType.MANHWA
                "manga", "one_shot", "doujinshi" -> MediaType.MANGA
                else -> null
            },
            coverUrl = node.obj("main_picture")?.let { it.text("large") ?: it.text("medium") },
            url = "https://myanimelist.net/manga/$id",
            year = node.text("start_date")?.take(4)?.toIntOrNull(),
            chapters = node.int("num_chapters")?.takeIf { it > 0 },
        )
    }

    // ---- signing in and tracking (the person's own account, through MyAnimeList's OAuth) -------

    /** The address of MyAnimeList's sign-in page for this client. [verifier] is the PKCE secret to keep until [exchange]. */
    fun authorizationUrl(verifier: String): String {
        val id = clientId()?.trim()?.takeIf { it.isNotEmpty() } ?: throw MetadataException("Add your MyAnimeList API client ID first.")
        // MyAnimeList supports the "plain" PKCE method: the challenge is the verifier itself.
        return "${endpoints.myAnimeListAuth}/authorize?response_type=code&client_id=${MangaHttp.encode(id)}&code_challenge=$verifier&code_challenge_method=plain"
    }

    /** Trades the code MyAnimeList handed back after sign-in for tokens. Returns (access token, refresh token). */
    suspend fun exchange(code: String, verifier: String): Pair<String, String?> {
        val id = clientId()?.trim()?.takeIf { it.isNotEmpty() } ?: throw MetadataException("Add your MyAnimeList API client ID first.")
        return metadataCall(name) {
            val answer = metadataJson.parseToJsonElement(
                MangaHttp.postForm("${endpoints.myAnimeListAuth}/token", mapOf("client_id" to id, "code" to code, "code_verifier" to verifier, "grant_type" to "authorization_code")).text(),
            ).jsonObject
            (answer.text("access_token") ?: throw MetadataException("MyAnimeList did not return a token.")) to answer.text("refresh_token")
        }
    }

    suspend fun refresh(refreshToken: String): Pair<String, String?> {
        val id = clientId()?.trim()?.takeIf { it.isNotEmpty() } ?: throw MetadataException("Add your MyAnimeList API client ID first.")
        return metadataCall(name) {
            val answer = metadataJson.parseToJsonElement(
                MangaHttp.postForm("${endpoints.myAnimeListAuth}/token", mapOf("client_id" to id, "refresh_token" to refreshToken, "grant_type" to "refresh_token")).text(),
            ).jsonObject
            (answer.text("access_token") ?: throw MetadataException("MyAnimeList did not return a token.")) to (answer.text("refresh_token") ?: refreshToken)
        }
    }

    suspend fun viewer(accessToken: String): String = metadataCall(name) {
        metadataJson.parseToJsonElement(MangaHttp.get("${endpoints.myAnimeList}/users/@me", mapOf("Authorization" to "Bearer $accessToken")).text()).jsonObject.text("name")
            ?: throw MetadataException("MyAnimeList did not accept this sign-in.")
    }

    suspend fun saveProgress(accessToken: String, remoteId: String, chaptersRead: Int, completed: Boolean) {
        if (remoteId.toIntOrNull() == null) throw MetadataException("This series is not linked to MyAnimeList.")
        metadataCall(name) {
            val form = "status=${if (completed) "completed" else "reading"}&num_chapters_read=$chaptersRead"
            MangaHttp.send(
                "PUT", "${endpoints.myAnimeList}/manga/$remoteId/my_list_status",
                mapOf("Authorization" to "Bearer $accessToken", "Content-Type" to "application/x-www-form-urlencoded"), form.toByteArray(),
            )
        }
    }
}
