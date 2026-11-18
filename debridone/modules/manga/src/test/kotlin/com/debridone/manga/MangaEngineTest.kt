package com.debridone.manga

import com.debridone.manga.library.LibraryFilter
import com.debridone.manga.library.LibrarySort
import com.debridone.manga.library.LibraryStatus
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.SeriesEdit
import com.debridone.manga.library.SeriesField
import com.debridone.manga.local.LocalImportException
import com.debridone.manga.metadata.MetadataEndpoints
import com.debridone.manga.reader.PageException
import com.debridone.manga.sources.BrowseMode
import com.debridone.manga.sources.MangaSources
import com.debridone.manga.suwayomi.MangaConnection
import com.debridone.manga.suwayomi.SampleComics
import com.debridone.manga.suwayomi.SuwayomiTestRuntime
import com.debridone.module.ModuleId
import com.debridone.module.storage.ModuleStorage
import com.debridone.platform.AppPaths
import com.debridone.platform.secrets.FileSecretStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Manga engine as the screens use it, on the real Suwayomi-Server: the person's own files are
 * read through Suwayomi's Local source, and everything DebridOne shows or keeps goes through the
 * server. Nothing leaves this computer (the metadata provider is a stand-in on a local port).
 *
 * One server is shared by the tests of this class; each test works on series of its own.
 */
class MangaEngineTest {
    companion object {
        private const val ALICE = 1001L
        private const val BOB = 1002L
        private lateinit var dir: Path
        private lateinit var files: Path
        private lateinit var provider: HttpServer
        private lateinit var module: MangaModule
        private lateinit var services: MangaServices

        private const val MEDIA = """
            {"id":105398,"idMal":121496,"title":{"romaji":"Na Honjaman Level Up","english":"Solo Leveling","native":"나 혼자만 레벨업"},
             "synonyms":["Only I Level Up"],"description":"Ten years ago, the Gate appeared.","status":"FINISHED",
             "countryOfOrigin":"KR","format":"MANGA","genres":["Action","Fantasy"],"tags":[],
             "coverImage":{"extraLarge":"https://img.invalid/cover-xl.jpg","large":"https://img.invalid/cover-l.jpg"},"bannerImage":"https://img.invalid/banner.jpg",
             "chapters":201,"startDate":{"year":2018},"siteUrl":"https://anilist.co/manga/105398",
             "staff":{"edges":[{"role":"Story","node":{"name":{"full":"Chugong"}}},{"role":"Art","node":{"name":{"full":"Jang Sung-rak"}}}]}}
        """

        @BeforeClass
        @JvmStatic
        fun start() {
            SuwayomiTestRuntime.runtimeOrSkip()
            dir = Files.createTempDirectory("manga-engine")
            files = Files.createDirectories(dir.resolve("my comics"))
            provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            provider.createContext("/") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val answer = if (body.contains("Media(id")) """{"data":{"Media":$MEDIA}}""" else """{"data":{"Page":{"media":[$MEDIA]}}}"""
                exchange.reply(200, answer.toByteArray(), "application/json")
            }
            provider.start()
            open(ALICE)
        }

        /** Starts the module (again) and waits for the profile's server. */
        private fun open(profile: Long) {
            val runtime = SuwayomiTestRuntime.runtimeOrSkip()
            val paths = AppPaths(dir.resolve("data"), dir.resolve("cache"))
            val url = "http://127.0.0.1:${provider.address.port}"
            module = MangaModule(
                ModuleStorage(ModuleId.MANGA, paths, FileSecretStore(paths.secretsDir)),
                runtime = { runtime },
                metadataEndpoints = MetadataEndpoints("$url/anilist", "$url/mangadex", "$url/covers", "$url/mal", "$url/malauth"),
            )
            runBlocking {
                module.start()
                module.onProfileChanged(profile)
            }
            services = module.services.value!!
            assertIs<MangaConnection.Idle>(services.server.state.value, "nothing is started before Manga is opened")
            module.ensureServer()
            awaitReady(profile)
        }

        private fun awaitReady(profile: Long) {
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                when (val state = services.server.state.value) {
                    is MangaConnection.Ready -> if (state.profileId == profile) return
                    is MangaConnection.Failed -> if (!state.retrying) throw AssertionError("The server failed: ${state.message}\n" + state.log.takeLast(15).joinToString("\n"))
                    else -> Unit
                }
                Thread.sleep(50)
            }
            throw AssertionError("The manga server did not become ready: ${services.server.state.value}")
        }

        @AfterClass
        @JvmStatic
        fun stop() {
            if (::module.isInitialized) module.close()
            if (::provider.isInitialized) provider.stop(0)
            if (::dir.isInitialized) dir.toFile().deleteRecursively()
        }
    }

    private val repository get() = services.repository

    /** A series folder with one archive per chapter, somewhere among the person's own files. */
    private fun comic(title: String, chapters: Int = 3, pages: Int = 3): Path {
        val folder = files.resolve(title)
        repeat(chapters) { SampleComics.cbz(folder.resolve("Chapter %02d.cbz".format(it + 1)), pages) }
        return folder
    }

    @Test
    fun aFolderOfTheirOwnIsReadAndEverythingAboutItIsKeptAcrossARestart() = runBlocking {
        val folder = comic("Keeper of Things")
        val series = services.local.import(ALICE, folder)
        assertEquals("Keeper of Things", series.title)
        assertTrue(series.isLocal && series.inLibrary)
        assertEquals(LibraryStatus.PLAN, series.libraryStatus, "a series that was just added is planned")
        assertEquals("Local files", series.sourceName)
        // Nothing was copied: the series is a link to the person's folder.
        val link = services.local.folder()!!.resolve("Keeper of Things")
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(series.id, services.local.import(ALICE, folder).id, "importing the same folder again is the same series")

        val chapters = repository.chapters(series.id)
        assertEquals(listOf("Chapter 01", "Chapter 02", "Chapter 03"), chapters.map { it.displayName.removeSuffix(".cbz") })
        assertEquals(chapters.map { it.id }, com.debridone.manga.library.ChapterOrder.readingOrder(chapters.shuffled()).map { it.id })

        // Reading: the pages are the ones in the archive, in order.
        val open = services.pages.open(chapters[0].id)
        assertEquals(3, open.pageCount)
        assertEquals("local", open.origin)
        repeat(3) { assertContentEquals(SampleComics.page(it), services.pages.page(open, it), "page ${it + 1}") }
        assertTrue(services.pages.isCached(open, 2))
        assertFailsWith<PageException> { services.pages.page(open, 3) }
        assertNotNull(services.pages.cover(series), "the first page serves as the cover")
        assertEquals(3, repository.chapter(chapters[0].id)!!.pageCount, "the page count is known once the chapter was opened")

        // Progress, the way the reader reports it.
        repository.saveProgress(chapters[0].id, page = 1, pageCount = 3, scrollOffset = 0.42f)
        var current = repository.series(series.id)!!
        assertEquals(LibraryStatus.READING, current.libraryStatus, "reading a planned series makes it current")
        assertEquals(chapters[0].id, current.lastChapterId)
        assertEquals(1, repository.chapter(chapters[0].id)!!.lastPage)
        assertEquals(0.42f, repository.chapter(chapters[0].id)!!.scrollOffset, 0.001f)
        assertEquals(chapters[0].id, repository.resumeChapter(current)!!.id, "an unfinished chapter is where to continue")
        repository.saveProgress(chapters[0].id, page = 2, pageCount = 3, finished = true)
        current = repository.series(series.id)!!
        assertEquals(chapters[1].id, repository.resumeChapter(current)!!.id, "after a finished chapter, the next one")
        val entry = repository.continueReading(ALICE).first { it.summary.series.id == series.id }
        assertEquals(chapters[1].id, entry.chapter.id)
        assertEquals(1, entry.summary.readCount)
        assertEquals(2, entry.summary.unreadCount)

        // Shelves, favourites, collections, bookmarks, a note, a reading preset for this series.
        repository.setLibraryStatus(series.id, LibraryStatus.ON_HOLD)
        repository.setFavorite(series.id, true)
        val collection = repository.createCollection(ALICE, "Weekend")
        assertFailsWith<IllegalArgumentException> { repository.createCollection(ALICE, "weekend") }
        repository.setInCollection(collection.id, series.id, true)
        assertTrue(repository.toggleBookmark(chapters[1].id, 2, "the reveal"))
        repository.setReaderOverrides(series.id, """{"mode":"WEBTOON"}""")
        repository.editSeries(series.id, SeriesEdit(customTags = listOf("gift", "re-read"), mediaType = MediaType.MANHWA))

        fun check() {
            val kept = repository.series(series.id)!!
            assertTrue(kept.inLibrary)
            assertEquals(LibraryStatus.ON_HOLD, kept.libraryStatus)
            assertTrue(kept.favorite)
            assertEquals(MediaType.MANHWA, kept.mediaType)
            assertEquals(listOf("gift", "re-read"), kept.customTags)
            assertEquals("""{"mode":"WEBTOON"}""", kept.readerOverrides)
            assertEquals(setOf(collection.id), repository.collectionsOf(series.id))
            assertEquals(1, repository.collections(ALICE).single { it.id == collection.id }.seriesCount)
            val bookmark = repository.bookmarks(series.id).single()
            assertEquals(chapters[1].id, bookmark.chapterId)
            assertEquals(2, bookmark.page)
            assertEquals("the reveal", bookmark.note)
            assertEquals(bookmark.id, repository.bookmark(chapters[1].id, 2)!!.id)
            assertTrue(repository.chapter(chapters[0].id)!!.read)
            assertEquals(listOf(series.id), repository.library(ALICE, LibraryFilter(collectionId = collection.id)).map { it.series.id })
            assertTrue(repository.library(ALICE, LibraryFilter(favoritesOnly = true, mediaType = MediaType.MANHWA, status = LibraryStatus.ON_HOLD, localOnly = true, unreadOnly = true, query = "keeper")).any { it.series.id == series.id })
            assertTrue(repository.library(ALICE, LibraryFilter(mediaType = MediaType.MANGA)).none { it.series.id == series.id })
        }
        check()

        // Close DebridOne and open it again: a new server process, the same library.
        module.close()
        open(ALICE)
        check()
        assertEquals(chapters[1].id, repository.continueReading(ALICE).first { it.summary.series.id == series.id }.chapter.id)

        // Marking read and unread, clearing the history, removing the bookmark and the collection.
        repository.setAllRead(series.id, true)
        assertEquals(0, repository.summary(series.id)!!.unreadCount)
        assertNull(repository.resumeChapter(repository.series(series.id)!!), "nothing to continue when all is read")
        repository.setRead(listOf(chapters[2].id), false)
        assertEquals(0, repository.chapter(chapters[2].id)!!.lastPage)
        assertEquals(1, repository.summary(series.id)!!.unreadCount)
        repository.clearHistory(ALICE)
        assertTrue(repository.continueReading(ALICE).none { it.summary.series.id == series.id }, "a cleared history starts empty")
        assertTrue(repository.chapter(chapters[0].id)!!.read, "clearing the history does not unread anything")
        assertFalse(repository.toggleBookmark(chapters[1].id, 2))
        assertTrue(repository.bookmarks(series.id).isEmpty())
        repository.renameCollection(collection.id, "Weekends")
        assertEquals("Weekends", repository.collections(ALICE).single { it.id == collection.id }.name)
        repository.deleteCollection(collection.id)
        assertTrue(repository.collectionsOf(series.id).isEmpty())

        // Removing the series removes DebridOne's link, never the person's files.
        services.local.remove(repository.series(series.id)!!)
        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(folder.resolve("Chapter 01.cbz")), "the person's own file is untouched")
        assertTrue(repository.library(ALICE).none { it.series.id == series.id })
    }

    @Test
    fun oneArchiveOrAFolderOfImagesIsASeriesOfOneChapter() = runBlocking {
        val archive = files.resolve("One Shot v01 [group].cbz").also { SampleComics.cbz(it, 4) }
        val single = services.local.import(ALICE, archive, MediaType.MANHWA)
        assertEquals("One Shot v01 [group]", single.title)
        assertEquals(MediaType.MANHWA, single.mediaType)
        assertTrue(SeriesField.MEDIA_TYPE in single.lockedFields, "a shelf chosen at import is the person's choice")
        val chapter = repository.chapters(single.id).single()
        assertEquals(4, services.pages.open(chapter.id).pageCount)
        // A hard link: the same data under a second name, so nothing was copied and nothing can be lost.
        val linked = services.local.folder()!!.resolve("One Shot v01 [group]").resolve("One Shot v01 [group].cbz")
        assertTrue(Files.isSameFile(linked, archive))
        assertEquals(single.id, services.local.import(ALICE, archive).id)

        val images = Files.createDirectories(files.resolve("Loose Pages"))
        repeat(3) { Files.write(images.resolve("%02d.png".format(it + 1)), SampleComics.page(it)) }
        val loose = services.local.import(ALICE, images)
        val only = repository.chapters(loose.id).single()
        val open = services.pages.open(only.id)
        assertEquals(3, open.pageCount)
        assertContentEquals(SampleComics.page(1), services.pages.page(open, 1))

        // What cannot be read is refused in words, and leaves nothing behind.
        val text = Files.writeString(files.resolve("notes.txt"), "not a comic")
        assertTrue(assertFailsWith<LocalImportException> { services.local.import(ALICE, text) }.message!!.contains("not a comic archive"))
        val empty = Files.createDirectories(files.resolve("Empty"))
        assertFailsWith<LocalImportException> { services.local.import(ALICE, empty) }
        val broken = Files.write(files.resolve("Broken.cbz"), "this is not a zip".toByteArray())
        assertFailsWith<LocalImportException> { services.local.import(ALICE, broken) }
        assertFalse(Files.exists(services.local.folder()!!.resolve("Broken")), "a failed import leaves no entry")
        assertFailsWith<LocalImportException> { services.local.import(ALICE, files.resolve("missing.cbz")) }

        services.local.remove(single)
        assertTrue(Files.isRegularFile(archive), "removing the series keeps the person's archive")
        services.local.remove(loose)
        assertTrue(Files.isRegularFile(images.resolve("01.png")))
    }

    @Test
    fun aMatchFromAProviderIsShownAndAHandEditOutlivesARefresh() = runBlocking {
        val series = services.local.import(ALICE, comic("solo leveling (scan)", chapters = 1))
        val match = services.metadata.search("anilist", "solo leveling").single()
        val changed = services.metadata.apply(series.id, match)
        var current = repository.series(series.id)!!
        assertEquals("Solo Leveling", current.title)
        assertEquals("Ten years ago, the Gate appeared.", current.description)
        assertEquals("https://img.invalid/cover-xl.jpg", current.coverUrl)
        assertEquals("https://img.invalid/banner.jpg", current.bannerUrl)
        assertEquals(MediaType.MANHWA, current.mediaType)
        assertEquals(listOf("Chugong"), current.authors)
        assertEquals("Completed", current.status)
        assertTrue(changed.containsAll(setOf(SeriesField.TITLE, SeriesField.DESCRIPTION, SeriesField.COVER, SeriesField.TAGS)), changed.toString())
        assertEquals(setOf("anilist", "myanimelist"), repository.metadataLinks(series.id).map { it.provider }.toSet())
        // The library finds it by its new name, its other names and its author.
        assertTrue(repository.library(ALICE, LibraryFilter(query = "only i level")).any { it.series.id == series.id })
        assertTrue(repository.library(ALICE, LibraryFilter(query = "chugong", sort = LibrarySort.TITLE)).any { it.series.id == series.id })

        // A title and a synopsis typed by hand are locked; the synopsis is unlocked again.
        repository.editSeries(series.id, SeriesEdit(title = "Solo Leveling (my edition)", description = "My own synopsis."))
        assertEquals(setOf(SeriesField.TITLE, SeriesField.DESCRIPTION), repository.series(series.id)!!.lockedFields)
        repository.setFieldLocked(series.id, SeriesField.DESCRIPTION, false)

        // A refresh asks AniList again: the unlocked synopsis is AniList's once more, the locked title
        // stays the person's, and nothing else changes.
        val refreshed = services.metadata.refresh(series.id)
        current = repository.series(series.id)!!
        assertEquals("Solo Leveling (my edition)", current.title)
        assertEquals("Ten years ago, the Gate appeared.", current.description)
        assertEquals("https://img.invalid/cover-xl.jpg", current.coverUrl)
        assertEquals(setOf(SeriesField.DESCRIPTION), refreshed)

        // What the source itself says is still there underneath: taking the provider's title away shows it again.
        repository.setFieldLocked(series.id, SeriesField.TITLE, false)
        repository.applyDetails(series.id, com.debridone.manga.library.SeriesDetails(title = "solo leveling (scan)"))
        assertEquals("solo leveling (scan)", repository.series(series.id)!!.title)

        // Clearing the status by hand holds against the provider too.
        repository.editSeries(series.id, SeriesEdit(clearStatus = true))
        services.metadata.refresh(series.id)
        assertNull(repository.series(series.id)!!.status)

        repository.unlinkMetadata(series.id, "myanimelist")
        assertEquals(listOf("anilist"), repository.metadataLinks(series.id).map { it.provider })
        services.local.remove(repository.series(series.id)!!)
    }

    @Test
    fun theLocalSourceIsListedSwitchedAndTested() = runBlocking {
        val sample = services.local.import(ALICE, comic("Tested Series", chapters = 2))
        val local = services.sources.list(ALICE).single()
        assertTrue(local.isLocal && local.enabled)
        assertEquals("Local files", local.name)
        assertEquals(MangaSources.LOCAL_ID, local.id)
        assertTrue(services.sources.enabled(ALICE).isEmpty(), "the Local source is not searched like a site")
        assertTrue(services.extensions.stores().isEmpty(), "DebridOne configures no store")
        assertTrue(services.extensions.list().isEmpty())

        val page = services.sources.browse(local.id, BrowseMode.SEARCH, "tested")
        assertEquals(listOf(sample.id), page.series.map { it.id })
        assertTrue(page.series.single().inLibrary, "a result that is in the library says so")
        val results = services.sources.search(ALICE, "tested", sourceIds = listOf(local.id))
        assertEquals(sample.id, results.single().series.single().id)
        assertNull(results.single().error)

        val health = services.sources.test(local.id)
        assertTrue(health.ok, health.steps.joinToString { "${it.name}: ${it.detail}" })
        assertEquals(listOf("List series", "Search", "Open a series", "Open a chapter", "Load a page"), health.steps.map { it.name })
        assertEquals(health.testedAt, services.sources.find(local.id)!!.health!!.testedAt, "the result is kept with the source")

        services.sources.setEnabled(ALICE, local.id, false)
        assertFalse(services.sources.find(local.id)!!.enabled)
        services.sources.setEnabled(ALICE, local.id, true)
        services.sources.setPreferred(ALICE, listOf(local.id))
        assertEquals(0, services.sources.find(local.id)!!.preferredRank)
        services.local.remove(sample)
    }

    @Test
    fun eachProfileHasALibraryAndAServerFolderOfItsOwn() = runBlocking {
        val series = services.local.import(ALICE, comic("Alice Only", chapters = 1))
        repository.setFavorite(series.id, true)
        assertTrue(repository.library(ALICE).any { it.series.title == "Alice Only" })

        // Bob becomes active: his server replaces Alice's, and his library is his own.
        module.onProfileChanged(BOB)
        awaitReady(BOB)
        assertTrue(repository.library(BOB).isEmpty(), "another profile starts with an empty library")
        assertTrue(repository.library(ALICE).isEmpty(), "a screen still asking for the previous profile is shown nothing")
        assertNull(repository.series(series.id)?.takeIf { it.title == "Alice Only" }, "Alice's series does not exist for Bob")
        val bobs = services.local.import(BOB, comic("Bob Only", chapters = 1))
        assertEquals(listOf("Bob Only"), repository.library(BOB).map { it.series.title })
        assertTrue(Files.isDirectory(module.serverDataDir.resolve("profile-$BOB")))
        assertFalse(Files.exists(module.serverDataDir.resolve("profile-$BOB/local/Alice Only"), java.nio.file.LinkOption.NOFOLLOW_LINKS))

        // Back to Alice: her library is as she left it, without Bob's series.
        module.onProfileChanged(ALICE)
        awaitReady(ALICE)
        val hers = repository.library(ALICE).map { it.series.title }
        assertTrue("Alice Only" in hers && "Bob Only" !in hers, hers.toString())
        assertTrue(repository.library(ALICE).single { it.series.title == "Alice Only" }.series.favorite)

        // Deleting Bob erases his folder; Alice's is untouched.
        module.deleteProfileData(BOB)
        assertFalse(Files.exists(module.serverDataDir.resolve("profile-$BOB")))
        assertTrue(Files.isDirectory(module.serverDataDir.resolve("profile-$ALICE")))
        assertTrue(bobs.id > 0)
        services.local.remove(repository.library(ALICE).single { it.series.title == "Alice Only" }.series)
    }
}
