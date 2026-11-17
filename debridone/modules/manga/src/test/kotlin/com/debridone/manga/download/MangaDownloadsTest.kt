package com.debridone.manga.download

import com.debridone.manga.FakeTiyo
import com.debridone.manga.MangaModule
import com.debridone.manga.MangaServices
import com.debridone.manga.TestRuntime
import com.debridone.manga.library.Chapter
import com.debridone.manga.library.DownloadState
import com.debridone.manga.library.RestoreSummary
import com.debridone.manga.library.SeriesEdit
import com.debridone.manga.library.SeriesField
import com.debridone.manga.pageImage
import com.debridone.module.ModuleId
import com.debridone.module.storage.ModuleStorage
import com.debridone.platform.AppPaths
import com.debridone.platform.secrets.FileSecretStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saving chapters for offline reading, and the library backup, with a real extension host. */
class MangaDownloadsTest {
    private lateinit var dir: Path
    private lateinit var paths: AppPaths
    private lateinit var module: MangaModule
    private lateinit var services: MangaServices
    private val alice = 1001L
    private val bob = 1002L

    private fun start(): MangaModule = MangaModule(ModuleStorage(ModuleId.MANGA, paths, FileSecretStore(paths.secretsDir)), runtimeLocator = { TestRuntime.runtimeOrSkip() })
        .also { started ->
            runBlocking {
                started.start()
                started.onProfileChanged(alice)
            }
            module = started
            services = started.services.value!!
        }

    @BeforeTest
    fun setUp() {
        TestRuntime.runtimeOrSkip()
        dir = Files.createTempDirectory("manga-downloads")
        paths = AppPaths(dir.resolve("data"), dir.resolve("cache"))
        FakeTiyo.installInto(paths.moduleDataDir("manga").resolve("plugins"))
        start()
        runBlocking { services.extensions.ensureLoaded() }
    }

    @AfterTest
    fun tearDown() {
        if (::module.isInitialized) module.close()
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private suspend fun book(profile: Long = alice): List<Chapter> {
        val hit = services.sources.search(profile, "book", listOf(FakeTiyo.SOURCE_ID)).single().series.single()
        val series = services.sources.remember(profile, hit)
        services.sources.refresh(series.id)
        services.repository.setInLibrary(series.id, true)
        return services.repository.chapters(series.id)
    }

    private suspend fun await(chapterId: Long, vararg states: DownloadState?): Chapter {
        repeat(200) {
            val chapter = services.repository.chapter(chapterId)!!
            if (chapter.downloadState in states) return chapter
            delay(50)
        }
        return services.repository.chapter(chapterId)!!
    }

    @Test
    fun chaptersAreSavedInOrderAndReadWithoutTheSource(): Unit = runBlocking {
        val chapters = book()
        services.downloads.enqueue(chapters.take(2).map { it.id })
        val first = await(chapters[0].id, DownloadState.DONE)
        val second = await(chapters[1].id, DownloadState.DONE)
        assertEquals(DownloadState.DONE, first.downloadState)
        assertEquals(DownloadState.DONE, second.downloadState)
        assertEquals(4, first.downloadPages)

        // One file per page, named so that they sort in reading order, under Manga's own data folder.
        val folder = Paths.get(first.downloadDir!!)
        assertTrue(folder.startsWith(paths.moduleDataDir("manga").resolve("downloads").resolve(alice.toString())))
        assertEquals(listOf("0001.png", "0002.png", "0003.png", "0004.png"), Files.list(folder).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() })
        assertTrue(Files.readAllBytes(folder.resolve("0003.png")).contentEquals(FakeTiyo.pages[2]))
        assertEquals(2, services.repository.summary(first.seriesId)!!.downloadedCount)

        // With the extension gone, the saved chapter still opens; one that was not saved does not.
        services.plugins.remove("@tiyo/core")
        val open = services.pages.open(first.id)
        assertEquals("downloaded", open.origin)
        assertEquals(4, open.pageCount)
        assertTrue(services.pages.page(open, 3).contentEquals(FakeTiyo.pages[3]))
        assertFailsWith<IOException> { services.pages.open(chapters[2].id) }

        // Deleting removes the files and the record.
        services.downloads.delete(listOf(first.id))
        val deleted = await(first.id, null)
        assertNull(deleted.downloadState)
        assertFalse(Files.exists(folder))
        assertEquals(1, services.repository.downloads(alice).size)
    }

    @Test
    fun anInterruptedDownloadCarriesOnWithThePagesItHas(): Unit = runBlocking {
        val chapter = book().first()
        // As if the app had been closed after two pages: two finished pages, one half-written, state "running".
        val series = services.repository.series(chapter.seriesId)!!
        val folder = paths.moduleDataDir("manga").resolve("downloads").resolve(alice.toString()).resolve(series.id.toString()).resolve(chapter.id.toString())
        Files.createDirectories(folder)
        val kept = pageImage("already saved")
        Files.write(folder.resolve("0001.png"), kept)
        Files.write(folder.resolve("0002.png"), FakeTiyo.pages[1])
        Files.write(folder.resolve("0003.png.part"), byteArrayOf(1, 2, 3))
        services.repository.queueDownloads(listOf(chapter.id))
        services.repository.setDownloadState(chapter.id, DownloadState.RUNNING)

        // Restart: the download is picked up again.
        module.close()
        start()
        val done = await(chapter.id, DownloadState.DONE)
        assertEquals(DownloadState.DONE, done.downloadState)
        assertEquals(listOf("0001.png", "0002.png", "0003.png", "0004.png"), Files.list(folder).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() })
        assertTrue(Files.readAllBytes(folder.resolve("0001.png")).contentEquals(kept), "a page that was already saved is not fetched again")
        assertTrue(Files.readAllBytes(folder.resolve("0003.png")).contentEquals(FakeTiyo.pages[2]))
    }

    @Test
    fun aDownloadThatFailsSaysWhyAndCanBeRetried(): Unit = runBlocking {
        // This series' pages are addresses on a host that does not exist.
        val unreachable = services.sources.remember(alice, services.sources.search(alice, "nowhere", listOf(FakeTiyo.SOURCE_ID)).single().series.single())
        services.repository.syncChapters(unreachable.id, listOf(com.debridone.manga.library.ChapterDraft("x", "Only", "1", "", "ENGLISH", "", 0)))
        val chapter = services.repository.chapters(unreachable.id).single()
        services.downloads.enqueue(listOf(chapter.id))
        val failed = await(chapter.id, DownloadState.FAILED)
        assertEquals(DownloadState.FAILED, failed.downloadState)
        assertNotNull(failed.downloadError)
        assertEquals(1, services.repository.downloads(alice).count { it.second.downloadState == DownloadState.FAILED })
        // Retrying puts it back in the queue (and it fails again, for the same reason).
        services.downloads.enqueue(listOf(chapter.id))
        assertEquals(DownloadState.FAILED, await(chapter.id, DownloadState.FAILED).downloadState)
        // The other source is unaffected.
        val good = book().first()
        services.downloads.enqueue(listOf(good.id))
        assertEquals(DownloadState.DONE, await(good.id, DownloadState.DONE).downloadState)
    }

    @Test
    fun aLibraryBackupRestoresSeriesProgressEditsBookmarksAndCollections(): Unit = runBlocking {
        val chapters = book()
        val seriesId = chapters.first().seriesId
        services.repository.setRead(listOf(chapters[0].id), true)
        services.repository.saveProgress(chapters[1].id, page = 2, pageCount = 4, scrollOffset = 0.25f)
        services.repository.toggleBookmark(chapters[1].id, 1, "here")
        services.repository.setFavorite(seriesId, true)
        services.repository.editSeries(seriesId, SeriesEdit(title = "My Book", customTags = listOf("weekend")))
        val shelf = services.repository.createCollection(alice, "Shelf")
        services.repository.setInCollection(shelf.id, seriesId, true)
        services.repository.linkMetadata(seriesId, "anilist", "42", "https://anilist.co/manga/42")
        services.sources.saveSettings(alice, services.sources.find(FakeTiyo.SOURCE_ID)!!, buildJsonObject { put("Token", "super-secret-token") })

        val file = dir.resolve("backup.json")
        assertEquals(1, services.backup.export(alice, file))
        val text = Files.readString(file)
        assertTrue(text.contains("\"debridone-manga-library\""))
        assertFalse(text.contains("super-secret-token"), "a backup holds no credentials")

        // Restore into another profile: everything is there, and nothing of the first profile changed.
        val summary: RestoreSummary = services.backup.restore(bob, file)
        assertEquals(1, summary.series)
        val restored = services.repository.library(bob).single()
        assertEquals("My Book", restored.series.title)
        assertTrue(SeriesField.TITLE in restored.series.lockedFields)
        assertEquals(listOf("weekend"), restored.series.customTags)
        assertTrue(restored.series.favorite)
        assertEquals(FakeTiyo.SOURCE_ID, restored.series.extensionId)
        assertEquals("book-1", restored.series.sourceId)
        val restoredChapters = services.repository.chapters(restored.series.id)
        assertEquals(chapters.size, restoredChapters.size)
        assertTrue(restoredChapters[0].read)
        assertEquals(2, restoredChapters[1].lastPage)
        assertEquals(0.25f, restoredChapters[1].scrollOffset)
        assertEquals("here", services.repository.bookmarks(restored.series.id).single().note)
        assertEquals(listOf("Shelf" to 1), services.repository.collections(bob).map { it.name to it.seriesCount })
        assertEquals("42", services.repository.metadataLinks(restored.series.id).single().remoteId)
        assertNull(services.sources.storedSettings(bob, FakeTiyo.SOURCE_ID))
        assertEquals(1, services.repository.libraryCount(alice))

        // Restoring again adds nothing twice.
        services.backup.restore(bob, file)
        assertEquals(1, services.repository.libraryCount(bob))
        assertEquals(chapters.size, services.repository.chapters(restored.series.id).size)
        assertEquals(1, services.repository.bookmarks(restored.series.id).size)
        assertEquals(1, services.repository.collections(bob).size)

        // Other files are refused.
        val other = dir.resolve("other.json")
        Files.writeString(other, """{"format":"something-else","exportedAt":1,"series":[]}""")
        assertTrue(assertFailsWith<IOException> { services.backup.restore(bob, other) }.message!!.contains("not a DebridOne Manga library backup"))
        Files.writeString(other, "not json at all")
        assertFailsWith<IOException> { services.backup.restore(bob, other) }
    }
}
