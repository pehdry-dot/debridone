package com.debridone.manga.metadata

import com.debridone.manga.MangaModule
import com.debridone.manga.MangaServices
import com.debridone.manga.library.MediaType
import com.debridone.manga.reply
import com.debridone.module.ModuleId
import com.debridone.module.storage.ModuleStorage
import com.debridone.platform.AppPaths
import com.debridone.platform.secrets.FileSecretStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AniList, MangaDex and MyAnimeList against local stand-ins that answer in each service's own
 * format. No manga server is started here: looking things up at a provider needs none. What happens
 * when a match is applied to a series is in MangaEngineTest.
 */
class MangaMetadataTest {
    private lateinit var dir: Path
    private lateinit var server: HttpServer
    private lateinit var module: MangaModule
    private lateinit var services: MangaServices
    private val requests = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()
    private val alice = 1001L

    private val aniListMedia = """
        {"id":105398,"idMal":121496,"title":{"romaji":"Na Honjaman Level Up","english":"Solo Leveling","native":"나 혼자만 레벨업"},
         "synonyms":["Only I Level Up"],"description":"Ten years ago, <i>the Gate</i> appeared.<br><br>Hunters fight monsters.","status":"FINISHED",
         "countryOfOrigin":"KR","format":"MANGA","genres":["Action","Fantasy"],
         "tags":[{"name":"Dungeon","rank":90,"isMediaSpoiler":false},{"name":"Twist","rank":95,"isMediaSpoiler":true},{"name":"Minor","rank":20,"isMediaSpoiler":false}],
         "coverImage":{"extraLarge":"https://img.invalid/cover-xl.jpg","large":"https://img.invalid/cover-l.jpg"},"bannerImage":"https://img.invalid/banner.jpg",
         "chapters":201,"startDate":{"year":2018},"siteUrl":"https://anilist.co/manga/105398",
         "staff":{"edges":[{"role":"Story","node":{"name":{"full":"Chugong"}}},{"role":"Art","node":{"name":{"full":"Jang Sung-rak"}}},{"role":"Translator","node":{"name":{"full":"Someone"}}}]}}
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("manga-metadata")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests += exchange.requestMethod + " " + exchange.requestURI.toString()
            bodies += body
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            fun json(status: Int, text: String) = exchange.reply(status, text.toByteArray(), "application/json")
            when {
                path == "/anilist" && body.contains("TRENDING_DESC") -> json(200, """{"data":{"Page":{"media":[$aniListMedia]}}}""")
                path == "/anilist" && body.contains("Media(id") -> json(200, """{"data":{"Media":$aniListMedia}}""")
                path == "/anilist" -> json(200, """{"data":{"Page":{"media":[$aniListMedia]}}}""")
                path == "/mangadex/manga" -> json(200, """{"data":[$MANGADEX_ENTRY],"total":1}""")
                path.startsWith("/mangadex/manga/") -> json(200, """{"data":$MANGADEX_ENTRY}""")
                path.startsWith("/mal/") && exchange.requestHeaders.getFirst("X-MAL-CLIENT-ID") != "my-client" && authorization == null -> json(403, """{"error":"bad client"}""")
                path == "/mal/manga" -> json(200, """{"data":[{"node":$MAL_NODE}]}""")
                path == "/mal/manga/121496" -> json(200, MAL_NODE)
                else -> json(404, "{}")
            }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}"
        val paths = AppPaths(dir.resolve("data"), dir.resolve("cache"))
        module = MangaModule(
            ModuleStorage(ModuleId.MANGA, paths, FileSecretStore(paths.secretsDir)),
            metadataEndpoints = MetadataEndpoints("$url/anilist", "$url/mangadex", "$url/covers", "$url/mal"),
        )
        runBlocking {
            module.start()
            module.onProfileChanged(alice)
        }
        services = module.services.value!!
    }

    @AfterTest
    fun tearDown() {
        module.close()
        server.stop(0)
        dir.toFile().deleteRecursively()
    }

    @Test
    fun aniListEntriesAreReadInFull(): Unit = runBlocking {
        val match = services.metadata.search("anilist", "solo leveling").single()
        assertEquals("anilist", match.provider)
        assertEquals("105398", match.remoteId)
        assertEquals("Solo Leveling", match.title)
        assertEquals(listOf("Na Honjaman Level Up", "나 혼자만 레벨업", "Only I Level Up"), match.altTitles)
        // HTML is removed; spoiler and low-ranked tags are left out.
        assertEquals("Ten years ago, the Gate appeared.\n\nHunters fight monsters.", match.description)
        assertEquals(listOf("Action", "Fantasy", "Dungeon"), match.tags)
        assertEquals(listOf("Chugong"), match.authors)
        assertEquals(listOf("Jang Sung-rak"), match.artists)
        assertEquals("Completed", match.status)
        assertEquals("KOREAN", match.originalLanguage)
        assertEquals(MediaType.MANHWA, match.mediaType)
        assertEquals("https://img.invalid/cover-xl.jpg", match.coverUrl)
        assertEquals("https://img.invalid/banner.jpg", match.bannerUrl)
        assertEquals(201, match.chapters)
        assertEquals(2018, match.year)
        assertEquals("https://anilist.co/manga/105398", match.url)
        assertEquals(mapOf("myanimelist" to "121496"), match.externalIds)
    }

    @Test
    fun aProviderThatIsSwitchedOffIsNotContacted(): Unit = runBlocking {
        module.metadataProviders.setEnabled("mangadex", false)
        requests.clear()
        val failure = assertFailsWith<MetadataException> { services.metadata.search("mangadex", "solo") }
        assertTrue(failure.message!!.contains("switched off"))
        assertTrue(requests.isEmpty())
        assertEquals(listOf("anilist"), services.metadata.enabledSources().map { it.id })
    }

    @Test
    fun mangaDexEntriesAreRead(): Unit = runBlocking {
        val match = services.metadata.search("mangadex", "solo").single()
        assertEquals("32d76d19-8a05-4db0-9fc2-e0b0648fe9d0", match.remoteId)
        assertEquals("Solo Leveling", match.title)
        assertEquals(listOf("I Alone Level Up", "나 혼자만 레벨업"), match.altTitles)
        assertEquals("The MangaDex synopsis.", match.description)
        assertEquals(listOf("Chugong"), match.authors)
        assertEquals(listOf("Jang Sung-rak"), match.artists)
        assertEquals(listOf("Action", "Long Strip"), match.tags)
        assertEquals("Completed", match.status)
        assertEquals("KOREAN", match.originalLanguage)
        assertEquals(MediaType.MANHWA, match.mediaType)
        assertTrue(match.coverUrl!!.endsWith("/covers/32d76d19-8a05-4db0-9fc2-e0b0648fe9d0/cover-file.jpg.512.jpg"))
        assertEquals("https://mangadex.org/title/32d76d19-8a05-4db0-9fc2-e0b0648fe9d0", match.url)
        assertEquals(200, match.chapters)
        assertEquals(mapOf("anilist" to "105398", "myanimelist" to "121496"), match.externalIds)
        assertNotNull(services.metadata.mangaDex.details(match.remoteId))
        assertNull(services.metadata.mangaDex.details("not-an-id"))
    }

    @Test
    fun myAnimeListIsOnlyAskedWithThePersonsOwnClientId(): Unit = runBlocking {
        module.metadataProviders.setEnabled("myanimelist", true)
        requests.clear()
        val missing = assertFailsWith<MetadataException> { services.metadata.search("myanimelist", "solo leveling") }
        assertTrue(missing.message!!.contains("client ID"))
        assertTrue(requests.isEmpty(), "without a client ID nothing is sent")

        services.metadata.setMyAnimeListClientId("  my-client ")
        assertEquals("my-client", services.metadata.myAnimeListClientId())
        val match = services.metadata.search("myanimelist", "solo leveling").single()
        assertEquals("121496", match.remoteId)
        assertEquals("Solo Leveling", match.title)
        assertEquals(listOf("俺だけレベルアップな件", "Only I Level Up"), match.altTitles)
        assertEquals("A hunter levels up.", match.description)
        assertEquals("Completed", match.status)
        assertEquals(MediaType.MANHWA, match.mediaType)
        assertEquals(listOf("Chugong"), match.authors)
        assertEquals(listOf("Sung-rak Jang"), match.artists)
        assertEquals("https://cdn.invalid/mal-large.jpg", match.coverUrl)
        assertEquals(179, match.chapters)
        // A query the API would refuse is not sent.
        requests.clear()
        assertTrue(services.metadata.search("myanimelist", "so").isEmpty())
        assertTrue(requests.isEmpty())
        // A wrong client ID is reported as such.
        services.metadata.setMyAnimeListClientId("someone-elses")
        assertTrue(assertFailsWith<MetadataException> { services.metadata.search("myanimelist", "solo leveling") }.message!!.contains("refused"))
        services.metadata.setMyAnimeListClientId(null)
        assertNull(services.metadata.myAnimeListClientId())
    }

    @Test
    fun theChartsForHomeComeFromAniListAndAreKeptForAWhile(): Unit = runBlocking {
        requests.clear()
        val first = services.metadata.popular(MediaType.MANHWA)
        assertEquals("Solo Leveling", first.single().title)
        services.metadata.popular(MediaType.MANHWA)
        assertEquals(1, requests.size)
        assertTrue(bodies.last().contains("\"country\":\"KR\""))
        module.metadataProviders.setEnabled("anilist", false)
        assertTrue(services.metadata.popular(MediaType.MANGA).isEmpty())
    }

    @Test
    fun aServiceThatIsDownIsReportedAsTemporary(): Unit = runBlocking {
        server.stop(0)
        val failure = assertFailsWith<MetadataException> { services.metadata.search("anilist", "anything") }
        assertTrue(failure.temporary)
        assertTrue(failure.message!!.contains("could not be reached"))
    }

    companion object {
        private val MANGADEX_ENTRY = """
            {"id":"32d76d19-8a05-4db0-9fc2-e0b0648fe9d0","type":"manga","attributes":{"title":{"en":"Solo Leveling"},
             "altTitles":[{"en":"I Alone Level Up"},{"ko":"나 혼자만 레벨업"}],"description":{"en":"The MangaDex synopsis."},"originalLanguage":"ko",
             "status":"completed","year":2018,"lastChapter":"200","links":{"al":"105398","mal":"121496"},
             "tags":[{"attributes":{"name":{"en":"Action"},"group":"genre"}},{"attributes":{"name":{"en":"Long Strip"},"group":"format"}}]},
             "relationships":[{"type":"author","attributes":{"name":"Chugong"}},{"type":"artist","attributes":{"name":"Jang Sung-rak"}},
              {"type":"cover_art","attributes":{"fileName":"cover-file.jpg"}}]}
        """.trimIndent()

        private val MAL_NODE = """
            {"id":121496,"title":"Solo Leveling","alternative_titles":{"synonyms":["Only I Level Up"],"en":"Solo Leveling","ja":"俺だけレベルアップな件"},
             "synopsis":"A hunter levels up.","main_picture":{"medium":"https://cdn.invalid/mal.jpg","large":"https://cdn.invalid/mal-large.jpg"},
             "genres":[{"id":1,"name":"Action"}],"status":"finished","media_type":"manhwa","num_chapters":179,"start_date":"2018-03-04",
             "authors":[{"node":{"first_name":"","last_name":"Chugong"},"role":"Story"},{"node":{"first_name":"Sung-rak","last_name":"Jang"},"role":"Art"}]}
        """.trimIndent()
    }
}
