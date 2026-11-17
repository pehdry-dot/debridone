package com.debridone.manga.local

import com.debridone.manga.MangaModule
import com.debridone.manga.host.HostException
import com.debridone.manga.library.LibraryStatus
import com.debridone.manga.library.MediaType
import com.debridone.manga.pageImage
import com.debridone.manga.reader.PageException
import com.debridone.manga.writeZip
import com.debridone.module.ModuleId
import com.debridone.module.storage.ModuleStorage
import com.debridone.platform.AppPaths
import com.debridone.platform.secrets.FileSecretStore
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Local files are read without the extension host: here the module is given no Node.js runtime at
 * all, so anything that passes does so with no plugin and no network.
 */
class LocalLibraryTest {
    private lateinit var dir: Path
    private lateinit var files: Path
    private lateinit var storage: ModuleStorage
    private lateinit var module: MangaModule
    private val alice = 1001L

    private fun newModule(): MangaModule = MangaModule(storage, runtimeLocator = { throw HostException("NO_RUNTIME", "There is no extension runtime in this test.") })
        .also { runBlocking { it.start(); it.onProfileChanged(alice) } }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("manga-local")
        files = dir.resolve("files")
        val paths = AppPaths(dir.resolve("data"), dir.resolve("cache"))
        storage = ModuleStorage(ModuleId.MANGA, paths, FileSecretStore(paths.secretsDir))
        module = newModule()
    }

    @AfterTest
    fun tearDown() {
        module.close()
        dir.toFile().deleteRecursively()
    }

    private val services get() = module.services.value!!

    private fun pages(prefix: String, count: Int) = (1..count).map { pageImage("$prefix p$it", 120, 180) }

    @Test
    fun aCbzIsImportedReadAndResumedAfterARestartWithoutAnyPlugin(): Unit = runBlocking {
        val images = pages("book", 12)
        // Entry order in the archive is deliberately not page order, and there is clutter to skip.
        val entries = images.mapIndexed { index, bytes -> "Paper Lanterns/page${index + 1}.png" to bytes }.shuffled(java.util.Random(7)) +
            listOf("__MACOSX/._page1.png" to byteArrayOf(1), "Paper Lanterns/ComicInfo.xml" to "<x/>".toByteArray(), "Paper Lanterns/.hidden.png" to byteArrayOf(2))
        val cbz = files.resolve("Paper Lanterns v01.cbz")
        writeZip(cbz, entries)

        // 1. Import. 2. It is in the library.
        val series = services.local.import(alice, cbz)
        val listed = services.repository.library(alice).single()
        assertEquals("Paper Lanterns v01", listed.series.title)
        assertTrue(listed.series.isLocal)
        assertEquals(LibraryStatus.PLAN, listed.series.libraryStatus)
        assertEquals(1, listed.chapterCount)
        val chapter = services.repository.chapters(series.id).single()
        assertEquals(12, chapter.pageCount)
        assertEquals("1", chapter.volumeNumber)

        // 3. Open the reader. 4. Navigate several pages: page 2 comes before page 10.
        val open = services.pages.open(chapter.id)
        assertEquals("local", open.origin)
        assertEquals(12, open.pageCount)
        for (index in listOf(0, 1, 2, 9, 11)) {
            assertTrue(services.pages.page(open, index).contentEquals(images[index]), "page ${index + 1}")
        }
        // The cover is the first page.
        assertTrue(services.pages.cover(services.repository.series(series.id)!!)!!.contentEquals(images[0]))

        // 5. Save progress. 6. Restart.
        services.repository.saveProgress(chapter.id, page = 7, pageCount = 12)
        module.close()
        module = newModule()

        // 7. Resume from the last page.
        val resumed = services.repository.continueReading(alice).single()
        assertEquals("Paper Lanterns v01", resumed.summary.series.title)
        assertEquals(chapter.id, resumed.chapter.id)
        assertEquals(7, resumed.chapter.lastPage)
        val reopened = services.pages.open(resumed.chapter.id)
        assertTrue(services.pages.page(reopened, resumed.chapter.lastPage).contentEquals(images[7]))
        // And the extension host was never started.
        assertEquals(0, services.host.generation)
    }

    @Test
    fun aSeriesFolderBecomesChaptersFromArchivesAndImageFolders(): Unit = runBlocking {
        val root = files.resolve("Tower of Stairs")
        writeZip(root.resolve("Tower of Stairs c002 [Group].cbz"), pages("c2", 3).mapIndexed { i, b -> "${i + 1}.png" to b })
        writeZip(root.resolve("Tower of Stairs c010.zip"), pages("c10", 2).mapIndexed { i, b -> "${i + 1}.png" to b })
        val loose = root.resolve("Chapter 1")
        Files.createDirectories(loose)
        val firstPages = pages("c1", 4)
        firstPages.forEachIndexed { index, bytes -> Files.write(loose.resolve("img_${index + 1}.png"), bytes) }
        Files.writeString(loose.resolve("notes.txt"), "not a page")
        Files.writeString(root.resolve("readme.txt"), "not a chapter")

        val series = services.local.import(alice, root, MediaType.MANHWA)
        assertEquals("Tower of Stairs", series.title)
        assertEquals(MediaType.MANHWA, series.mediaType)
        val chapters = services.repository.chapters(series.id)
        assertEquals(listOf("1", "2", "10"), chapters.map { it.chapterNumber })
        assertEquals(listOf(4, 3, 2), chapters.map { it.pageCount })
        assertEquals("Group", chapters[1].groupName)

        val open = services.pages.open(chapters[0].id)
        assertTrue(services.pages.page(open, 3).contentEquals(firstPages[3]))

        // A chapter added later is picked up by a rescan; what was read stays read.
        services.repository.saveProgress(chapters[0].id, 3, 4, finished = true)
        writeZip(root.resolve("Tower of Stairs c011.cbz"), pages("c11", 1).mapIndexed { i, b -> "${i + 1}.png" to b })
        assertEquals(1, services.local.rescan(series.id))
        val after = services.repository.chapters(series.id)
        assertEquals(listOf("1", "2", "10", "11"), after.map { it.chapterNumber })
        assertTrue(after[0].read)
        // Importing the same folder again is a rescan, not a second series.
        assertEquals(series.id, services.local.import(alice, root).id)
        assertEquals(1, services.repository.libraryCount(alice))
    }

    @Test
    fun aFolderOfImagesIsOneChapter(): Unit = runBlocking {
        val folder = files.resolve("One Shot")
        Files.createDirectories(folder)
        val images = pages("one", 11)
        images.forEachIndexed { index, bytes -> Files.write(folder.resolve("${index + 1}.png"), bytes) }
        val series = services.local.import(alice, folder)
        val chapter = services.repository.chapters(series.id).single()
        assertEquals(11, chapter.pageCount)
        val open = services.pages.open(chapter.id)
        // 2.png before 10.png.
        assertTrue(services.pages.page(open, 1).contentEquals(images[1]))
        assertTrue(services.pages.page(open, 10).contentEquals(images[10]))
    }

    @Test
    fun problemsWithFilesAreExplained(): Unit = runBlocking {
        Files.createDirectories(files)
        val notAnArchive = files.resolve("broken.cbz")
        Files.writeString(notAnArchive, "this is not a zip file")
        assertTrue(assertFailsWith<IOException> { services.local.import(alice, notAnArchive) }.message!!.contains("not a CBZ or ZIP"))

        val empty = files.resolve("empty.cbz")
        writeZip(empty, listOf("readme.txt" to "hello".toByteArray()))
        assertFailsWith<IOException> { services.local.import(alice, empty) }

        val emptyFolder = files.resolve("Nothing Here")
        Files.createDirectories(emptyFolder)
        assertTrue(assertFailsWith<IOException> { services.local.import(alice, emptyFolder) }.message!!.contains("No images"))
        assertFailsWith<IOException> { services.local.import(alice, files.resolve("missing.cbz")) }

        // RAR archives are named for what they are instead of failing obscurely.
        val rar = files.resolve("old.cbr")
        Files.write(rar, "Rar!".toByteArray())
        assertTrue(LocalLibrary.unsupportedMessage(rar).contains("CBR"))
        assertTrue(assertFailsWith<IOException> { services.local.import(alice, rar) }.message!!.contains("not a CBZ or ZIP"))
        assertTrue(services.repository.library(alice).isEmpty())

        // A file that disappears after import is reported when it is opened.
        val cbz = files.resolve("gone.cbz")
        writeZip(cbz, pages("gone", 2).mapIndexed { i, b -> "${i + 1}.png" to b })
        val series = services.local.import(alice, cbz)
        Files.delete(cbz)
        val failure = assertFailsWith<PageException> { services.pages.open(services.repository.chapters(series.id).single().id) }
        assertTrue(failure.message!!.contains("no longer where it was"))

        // A file with an image extension that is not an image does not reach the screen.
        val fake = files.resolve("fake.cbz")
        writeZip(fake, listOf("1.png" to "<html>error</html>".toByteArray()))
        val fakeSeries = services.local.import(alice, fake)
        val open = services.pages.open(services.repository.chapters(fakeSeries.id).single().id)
        assertTrue(assertFailsWith<PageException> { services.pages.page(open, 0) }.message!!.contains("not an image"))
    }

    @Test
    fun chapterAndVolumeNumbersAreReadFromNames() {
        fun parsed(name: String) = ChapterName.parse(name).let { Triple(it.chapter, it.volume, it.group) }
        assertEquals(Triple("12", "", ""), parsed("Series c012"))
        assertEquals(Triple("12.5", "3", "Group"), parsed("[Group] Series v03 c012.5"))
        assertEquals(Triple("7", "", ""), parsed("Chapter 7"))
        assertEquals(Triple("7", "2", ""), parsed("Vol. 2 Ch. 7 - The Stairs"))
        assertEquals(Triple("4", "", ""), parsed("Series 004"))
        assertEquals(Triple("", "5", ""), parsed("Series Volume 5"))
        assertEquals(Triple("", "", ""), parsed("Extras"))
        assertEquals(Triple("3", "", "Scans 2000"), parsed("[Scans 2000] Title 3"))
    }

    @Test
    fun namesSortTheWayPeopleCount() {
        val sorted = listOf("page10.png", "page2.png", "Page1.png", "page02b.png", "cover.png").sortedWith(LocalLibrary.naturalOrder)
        assertEquals(listOf("cover.png", "Page1.png", "page2.png", "page02b.png", "page10.png"), sorted)
    }

    @Test
    fun localSeriesOfOneProfileAreNotSeenByAnother(): Unit = runBlocking {
        val cbz = files.resolve("mine.cbz")
        writeZip(cbz, pages("mine", 2).mapIndexed { i, b -> "${i + 1}.png" to b })
        services.local.import(alice, cbz)
        assertTrue(services.repository.library(1002L).isEmpty())
        assertNotNull(services.repository.library(alice).singleOrNull())
    }
}
