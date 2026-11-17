package com.debridone.manga.library

import com.debridone.manga.MangaModule
import com.debridone.module.ModuleId
import com.debridone.module.storage.ModuleStorage
import com.debridone.platform.AppPaths
import com.debridone.platform.secrets.FileSecretStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MangaRepositoryTest {
    private lateinit var dir: Path
    private lateinit var module: MangaModule
    private lateinit var repository: MangaRepository
    private var now = 1_000_000L
    private val alice = 1001L
    private val bob = 1002L

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("manga-repository")
        val paths = AppPaths(dir.resolve("data"), dir.resolve("cache"))
        module = MangaModule(ModuleStorage(ModuleId.MANGA, paths, FileSecretStore(paths.secretsDir)), clock = { now })
        runBlocking { module.start() }
        repository = module.services.value!!.repository
    }

    @AfterTest
    fun tearDown() {
        module.close()
        dir.toFile().deleteRecursively()
    }

    private fun add(profile: Long, id: String, title: String, details: SeriesDetails = SeriesDetails(), extension: String = "ext-a"): Series =
        repository.ensureSeries(profile, SeriesOrigin.EXTENSION, extension, id, "Source A", details.copy(title = title))

    private fun chapters(series: Series, vararg numbers: String, language: String? = "ENGLISH"): List<Chapter> {
        repository.syncChapters(series.id, numbers.map { ChapterDraft("c$it", "Chapter $it", it, "", language, "Group", 0) })
        return repository.chapters(series.id)
    }

    @Test
    fun aSeriesKeepsItsSourceAndIsOutsideTheLibraryUntilAdded() {
        val series = add(alice, "s1", "Paper Lanterns", SeriesDetails(description = "About", authors = listOf("A"), tags = listOf("Drama"), status = "Ongoing", originalLanguage = "JAPANESE", coverUrl = "https://c/1.jpg"))
        assertEquals("ext-a", series.extensionId)
        assertEquals("s1", series.sourceId)
        assertEquals("Source A", series.sourceName)
        assertFalse(series.inLibrary)
        assertEquals(MediaType.MANGA, series.mediaType)
        assertTrue(repository.library(alice).isEmpty())

        repository.setInLibrary(series.id, true)
        val listed = repository.library(alice).single()
        assertEquals("Paper Lanterns", listed.series.title)
        assertEquals(LibraryStatus.PLAN, listed.series.libraryStatus)
        // Opening the same series again does not create a second row or reset anything.
        assertEquals(series.id, add(alice, "s1", "Paper Lanterns").id)
        assertTrue(repository.series(series.id)!!.inLibrary)
    }

    @Test
    fun koreanAndWebtoonSeriesAreShelvedAsManhwaAndCanBeMovedByHand() {
        val korean = add(alice, "k", "Tower", SeriesDetails(originalLanguage = "KOREAN"))
        val tagged = add(alice, "t", "Strip", SeriesDetails(originalLanguage = "ENGLISH", tags = listOf("Action", "Long Strip")))
        val japanese = add(alice, "j", "Lanterns", SeriesDetails(originalLanguage = "JAPANESE", tags = listOf("Action")))
        assertEquals(MediaType.MANHWA, korean.mediaType)
        assertEquals(MediaType.MANHWA, tagged.mediaType)
        assertEquals(MediaType.MANGA, japanese.mediaType)

        // A wrong guess is corrected by hand, and the correction survives later refreshes.
        repository.setMediaType(korean.id, MediaType.MANGA)
        repository.applyDetails(korean.id, SeriesDetails(originalLanguage = "KOREAN", tags = listOf("Webtoon")))
        assertEquals(MediaType.MANGA, repository.series(korean.id)!!.mediaType)
        assertTrue(SeriesField.MEDIA_TYPE in repository.series(korean.id)!!.lockedFields)
    }

    @Test
    fun lockedFieldsSurviveARefreshAndUnlockedOnesAreUpdated() {
        val series = add(alice, "s1", "Original Title", SeriesDetails(description = "Original synopsis", tags = listOf("Drama")))
        repository.editSeries(series.id, SeriesEdit(title = "My Title", description = "My synopsis"))
        val edited = repository.series(series.id)!!
        assertEquals(setOf(SeriesField.TITLE, SeriesField.DESCRIPTION), edited.lockedFields)

        val changed = repository.applyDetails(series.id, SeriesDetails(title = "Provider Title", description = "Provider synopsis", tags = listOf("Drama", "Romance"), status = "Completed"))
        val refreshed = repository.series(series.id)!!
        assertEquals("My Title", refreshed.title)
        assertEquals("My synopsis", refreshed.description)
        assertEquals(listOf("Drama", "Romance"), refreshed.tags)
        assertEquals("Completed", refreshed.status)
        assertEquals(setOf(SeriesField.TAGS, SeriesField.STATUS), changed)

        // Unlocking lets the next refresh through.
        repository.setFieldLocked(series.id, SeriesField.TITLE, false)
        repository.applyDetails(series.id, SeriesDetails(title = "Provider Title"))
        assertEquals("Provider Title", repository.series(series.id)!!.title)
        assertEquals("My synopsis", repository.series(series.id)!!.description)
    }

    @Test
    fun aRefreshThatSaysNothingDoesNotEraseWhatIsKnown() {
        val series = add(alice, "s1", "Title", SeriesDetails(description = "Synopsis", authors = listOf("A"), tags = listOf("Webtoon"), coverUrl = "https://c/1.jpg"))
        // A search result often carries only a title.
        repository.applyDetails(series.id, SeriesDetails(title = "Title", description = "", authors = emptyList(), tags = emptyList(), coverUrl = ""))
        val after = repository.series(series.id)!!
        assertEquals("Synopsis", after.description)
        assertEquals(listOf("A"), after.authors)
        assertEquals("https://c/1.jpg", after.coverUrl)
        assertEquals(MediaType.MANHWA, after.mediaType)
    }

    @Test
    fun chapterProgressStaysWithItsChapterWhenTheSourceListsMore() {
        val series = add(alice, "s1", "Title")
        val first = chapters(series, "1", "2")
        repository.saveProgress(first[0].id, page = 4, pageCount = 20)
        repository.setRead(listOf(first[1].id), true)

        val added = repository.syncChapters(series.id, listOf("3", "2", "1").map { ChapterDraft("c$it", "Chapter $it (renamed)", it, "1", "ENGLISH", "Group", 5) })
        assertEquals(1, added)
        val after = repository.chapters(series.id)
        assertEquals(listOf("1", "2", "3"), after.map { it.chapterNumber })
        assertEquals(4, after[0].lastPage)
        assertEquals(20, after[0].pageCount)
        assertTrue(after[1].read)
        assertFalse(after[2].read)
        assertEquals("Chapter 1 (renamed)", after[0].title)
    }

    @Test
    fun aChapterTheSourceDropsIsKeptOnlyIfItWasReadOrDownloaded() {
        val series = add(alice, "s1", "Title")
        val all = chapters(series, "1", "2", "3")
        repository.setRead(listOf(all[0].id), true)
        repository.queueDownloads(listOf(all[1].id))
        repository.syncChapters(series.id, emptyList())
        assertEquals(listOf("1", "2"), repository.chapters(series.id).map { it.chapterNumber })
    }

    @Test
    fun readingOrderHandlesDecimalsAndUnnumberedChapters() {
        val series = add(alice, "s1", "Title")
        repository.syncChapters(
            series.id,
            listOf("10", "2", "1.5", "", "1").mapIndexed { index, number -> ChapterDraft("x$index", if (number.isEmpty()) "Extra" else "", number, "", "ENGLISH", "", 0) },
        )
        assertEquals(listOf("1", "1.5", "2", "10", ""), repository.chapters(series.id).map { it.chapterNumber })
    }

    @Test
    fun nextChapterPrefersTheSameLanguageAndGroup() {
        val series = add(alice, "s1", "Title")
        repository.syncChapters(
            series.id,
            listOf(
                ChapterDraft("1-en-a", "", "1", "", "ENGLISH", "A", 0),
                ChapterDraft("1-fr", "", "1", "", "FRENCH", "F", 0),
                ChapterDraft("2-fr", "", "2", "", "FRENCH", "F", 0),
                ChapterDraft("2-en-b", "", "2", "", "ENGLISH", "B", 0),
                ChapterDraft("2-en-a", "", "2", "", "ENGLISH", "A", 0),
                ChapterDraft("3-en-b", "", "3", "", "ENGLISH", "B", 0),
            ),
        )
        val all = repository.chapters(series.id)
        fun byId(id: String) = all.first { it.sourceId == id }
        assertEquals("2-en-a", ChapterOrder.next(byId("1-en-a"), all)!!.sourceId)
        assertEquals("2-fr", ChapterOrder.next(byId("1-fr"), all)!!.sourceId)
        // No release by the same group: the same language is the next best.
        assertEquals("3-en-b", ChapterOrder.next(byId("2-en-a"), all)!!.sourceId)
        assertEquals("1-en-a", ChapterOrder.previous(byId("2-en-a"), all)!!.sourceId)
        assertNull(ChapterOrder.next(byId("3-en-b"), all))
        assertNull(ChapterOrder.previous(byId("1-fr"), all))
    }

    @Test
    fun chapterLanguagesLimitWhatIsListedAndCounted() {
        val series = add(alice, "s1", "Title")
        repository.setInLibrary(series.id, true)
        repository.syncChapters(
            series.id,
            listOf(ChapterDraft("1-en", "", "1", "", "ENGLISH", "", 0), ChapterDraft("1-fr", "", "1", "", "FRENCH", "", 0), ChapterDraft("2-fr", "", "2", "", "FRENCH", "", 0)),
        )
        assertEquals(3, repository.library(alice).single().chapterCount)
        repository.setChapterLanguages(alice, setOf("ENGLISH"))
        assertEquals(listOf("1-en"), repository.chapters(series.id).map { it.sourceId })
        assertEquals(1, repository.library(alice).single().unreadCount)
        // Another profile's choice is its own.
        assertTrue(repository.chapterLanguages(bob).isEmpty())
    }

    @Test
    fun progressIsSavedAndContinueReadingPicksTheRightChapter() {
        val series = add(alice, "s1", "Title")
        repository.setInLibrary(series.id, true)
        val all = chapters(series, "1", "2", "3")
        assertTrue(repository.continueReading(alice).isEmpty())

        now += 1000
        repository.saveProgress(all[0].id, page = 6, pageCount = 18, scrollOffset = 0.4f)
        var entry = repository.continueReading(alice).single()
        assertEquals(all[0].id, entry.chapter.id)
        assertEquals(6, entry.chapter.lastPage)
        assertEquals(0.4f, entry.chapter.scrollOffset)
        assertEquals(18, entry.chapter.pageCount)
        // Reading something from "Plan to Read" moves it to "Currently Reading".
        assertEquals(LibraryStatus.READING, entry.summary.series.libraryStatus)

        // Finishing a chapter offers the next one.
        now += 1000
        repository.saveProgress(all[0].id, page = 17, pageCount = 18, finished = true)
        entry = repository.continueReading(alice).single()
        assertEquals(all[1].id, entry.chapter.id)
        assertEquals(1, entry.summary.readCount)
        assertEquals(2, entry.summary.unreadCount)

        // When everything is read there is nothing to continue.
        repository.setAllRead(series.id, true)
        assertTrue(repository.continueReading(alice).isEmpty())
        repository.setRead(listOf(all[2].id), false)
        assertEquals(0, repository.chapter(all[2].id)!!.lastPage)
    }

    @Test
    fun historyRecordsWhatWasReadMostRecentFirstWithoutRepeatingAPageTurn() {
        val one = add(alice, "s1", "One")
        val two = add(alice, "s2", "Two")
        val a = chapters(one, "1").single()
        val b = chapters(two, "1").single()
        repository.saveProgress(a.id, 1, 10); now += 1000
        repository.saveProgress(a.id, 2, 10); now += 1000
        repository.saveProgress(b.id, 5, 10); now += 1000
        repository.saveProgress(a.id, 3, 10)
        val history = repository.history(alice)
        assertEquals(listOf("One" to 3, "Two" to 5, "One" to 2), history.map { it.series.title to it.page })
        assertTrue(repository.history(bob).isEmpty())
        repository.clearHistory(alice)
        assertTrue(repository.history(alice).isEmpty())
        // Clearing history does not forget where the reader was.
        assertEquals(3, repository.chapter(a.id)!!.lastPage)
    }

    @Test
    fun bookmarksToggleAndBelongToTheirChapter() {
        val series = add(alice, "s1", "Title")
        val chapter = chapters(series, "1").single()
        assertTrue(repository.toggleBookmark(chapter.id, 7, "the reveal"))
        assertTrue(repository.toggleBookmark(chapter.id, 2))
        assertEquals(setOf(7, 2), repository.bookmarks(series.id).map { it.page }.toSet())
        assertEquals("the reveal", repository.bookmark(chapter.id, 7)!!.note)
        assertFalse(repository.toggleBookmark(chapter.id, 7))
        assertEquals(listOf(2), repository.bookmarks(series.id).map { it.page })
        repository.deleteSeries(series.id)
        assertTrue(repository.bookmarks(series.id).isEmpty())
    }

    @Test
    fun libraryFiltersAndSorts() {
        val a = add(alice, "a", "Zebra", SeriesDetails(status = "Completed", originalLanguage = "KOREAN"))
        val b = add(alice, "b", "apple pie", SeriesDetails(status = "Ongoing", authors = listOf("Tanaka")))
        val c = add(alice, "c", "Mango", SeriesDetails(status = "Ongoing"))
        listOf(a, b, c).forEach { now += 10; repository.setInLibrary(it.id, true) }
        repository.setLibraryStatus(a.id, LibraryStatus.COMPLETED)
        repository.setFavorite(b.id, true)
        chapters(a, "1", "2")
        val bc = chapters(b, "1", "2", "3")
        repository.setRead(bc.map { it.id }, true)
        now += 10; repository.saveProgress(chapters(c, "1").single().id, 1, 5)

        fun titles(filter: LibraryFilter) = repository.library(alice, filter).map { it.series.title }
        assertEquals(listOf("apple pie", "Mango", "Zebra"), titles(LibraryFilter()))
        assertEquals(listOf("Zebra", "Mango", "apple pie"), titles(LibraryFilter(descending = true)))
        assertEquals(listOf("Zebra"), titles(LibraryFilter(mediaType = MediaType.MANHWA)))
        assertEquals(listOf("Zebra"), titles(LibraryFilter(status = LibraryStatus.COMPLETED)))
        assertEquals(listOf("apple pie"), titles(LibraryFilter(favoritesOnly = true)))
        assertEquals(listOf("Mango", "Zebra"), titles(LibraryFilter(unreadOnly = true)))
        assertEquals(listOf("apple pie", "Mango"), titles(LibraryFilter(publicationStatus = "Ongoing")))
        assertEquals(listOf("apple pie"), titles(LibraryFilter(query = "tanaka")))
        assertEquals(listOf("apple pie"), titles(LibraryFilter(query = "APPLE")))
        assertEquals(listOf("Mango", "apple pie", "Zebra"), titles(LibraryFilter(sort = LibrarySort.DATE_ADDED)))
        assertEquals("Mango", titles(LibraryFilter(sort = LibrarySort.RECENTLY_READ)).first())
        assertEquals("apple pie", titles(LibraryFilter(sort = LibrarySort.PROGRESS)).first())
        assertEquals("Zebra", titles(LibraryFilter(sort = LibrarySort.UNREAD)).first())
        assertEquals(listOf("Zebra", "apple pie", "Mango"), titles(LibraryFilter(sort = LibrarySort.STATUS)))
    }

    @Test
    fun collectionsHoldSeriesAndAreRemovedCleanly() {
        val a = add(alice, "a", "A")
        val b = add(alice, "b", "B")
        val shelf = repository.createCollection(alice, "Weekend")
        assertFailsWith<IllegalArgumentException> { repository.createCollection(alice, "weekend") }
        assertFailsWith<IllegalArgumentException> { repository.createCollection(alice, "  ") }
        repository.setInCollection(shelf.id, a.id, true)
        repository.setInCollection(shelf.id, b.id, true)
        // Putting a series in a collection puts it in the library.
        assertTrue(repository.series(a.id)!!.inLibrary)
        assertEquals(2, repository.collections(alice).single().seriesCount)
        assertEquals(listOf("A", "B"), repository.library(alice, LibraryFilter(collectionId = shelf.id)).map { it.series.title })
        assertEquals(setOf(shelf.id), repository.collectionsOf(a.id))

        repository.setInCollection(shelf.id, a.id, false)
        repository.renameCollection(shelf.id, "Weekdays")
        assertEquals("Weekdays" to 1, repository.collections(alice).single().let { it.name to it.seriesCount })
        repository.setInLibrary(b.id, false)
        assertEquals(0, repository.collections(alice).single().seriesCount)
        repository.deleteCollection(shelf.id)
        assertTrue(repository.collections(alice).isEmpty())
        assertTrue(repository.collections(bob).isEmpty())
        // Another profile may use the same name.
        repository.createCollection(bob, "Weekend")
    }

    @Test
    fun theSameSeriesFromTwoSourcesStaysTwoSeriesAndIsReportedAsADuplicate() {
        val a = add(alice, "x1", "One Piece!", extension = "ext-a")
        val b = add(alice, "9", "one piece", extension = "ext-b")
        val other = add(alice, "z", "Something Else")
        listOf(a, b, other).forEach { repository.setInLibrary(it.id, true) }
        assertEquals(3, repository.libraryCount(alice))
        val group = repository.duplicates(alice).single()
        assertEquals(setOf(a.id, b.id), group.map { it.series.id }.toSet())
        assertEquals(setOf("ext-a", "ext-b"), repository.editions(alice, "ONE PIECE").map { it.series.extensionId }.toSet())
        // Progress on one edition is not the other's.
        val ca = chapters(a, "1").single()
        chapters(b, "1")
        repository.saveProgress(ca.id, 3, 10, finished = true)
        assertTrue(repository.chapters(a.id).single().read)
        assertFalse(repository.chapters(b.id).single().read)
    }

    @Test
    fun sourcePreferencesArePerProfile() {
        repository.setSourceEnabled(alice, "ext-a", false)
        repository.setPreferredSources(alice, listOf("ext-c", "ext-b"))
        val prefs = repository.sourcePreferences(alice)
        assertFalse(prefs.getValue("ext-a").enabled)
        assertEquals(0, prefs.getValue("ext-c").preferredRank)
        assertEquals(1, prefs.getValue("ext-b").preferredRank)
        assertNull(prefs.getValue("ext-a").preferredRank)
        assertTrue(repository.sourcePreferences(bob).isEmpty())
        repository.setPreferredSources(alice, listOf("ext-b"))
        assertNull(repository.sourcePreferences(alice).getValue("ext-c").preferredRank)
        assertEquals(0, repository.sourcePreferences(alice).getValue("ext-b").preferredRank)
    }

    @Test
    fun sourceTestResultsAreStoredWithTheirSteps() {
        repository.saveSourceHealth(SourceHealth("ext-a", 123, false, listOf(HealthStep("Browse", true, "12 series"), HealthStep("Search", false, "timed out")), "timed out"))
        val health = repository.sourceHealth().getValue("ext-a")
        assertFalse(health.ok)
        assertEquals(listOf("Browse" to true, "Search" to false), health.steps.map { it.name to it.ok })
        assertEquals("timed out", health.message)
        repository.saveSourceHealth(SourceHealth("ext-a", 456, true, emptyList(), null))
        assertTrue(repository.sourceHealth().getValue("ext-a").ok)
    }

    @Test
    fun twoProfilesHaveSeparateLibrariesProgressAndEdits() {
        val mine = add(alice, "s1", "Shared Title")
        val theirs = add(bob, "s1", "Shared Title")
        assertTrue(mine.id != theirs.id)
        repository.setInLibrary(mine.id, true)
        repository.setFavorite(mine.id, true)
        repository.editSeries(mine.id, SeriesEdit(title = "Alice's Title"))
        val chapter = chapters(mine, "1").single()
        chapters(theirs, "1")
        repository.saveProgress(chapter.id, 9, 10, finished = true)
        repository.toggleBookmark(chapter.id, 9)

        assertTrue(repository.library(bob).isEmpty())
        assertTrue(repository.continueReading(bob).isEmpty())
        assertEquals("Shared Title", repository.series(theirs.id)!!.title)
        assertFalse(repository.chapters(theirs.id).single().read)
        assertTrue(repository.bookmarks(theirs.id).isEmpty())
        assertEquals("Alice's Title", repository.library(alice).single().series.title)
    }

    @Test
    fun deletingAProfileRemovesEverythingItOwnedAndNothingElse() {
        val mine = add(alice, "s1", "Mine")
        val theirs = add(bob, "s1", "Theirs")
        repository.setInLibrary(mine.id, true)
        repository.setInLibrary(theirs.id, true)
        val chapter = chapters(mine, "1").single()
        repository.saveProgress(chapter.id, 1, 2)
        repository.createCollection(alice, "Shelf")
        repository.setSourceEnabled(alice, "ext-a", false)
        repository.queueDownloads(listOf(chapter.id))
        repository.setDownloadState(chapter.id, DownloadState.DONE, dir = "/tmp/somewhere", pages = 2)

        assertEquals(listOf("/tmp/somewhere"), repository.deleteProfile(alice))
        assertTrue(repository.library(alice).isEmpty())
        assertTrue(repository.history(alice).isEmpty())
        assertTrue(repository.collections(alice).isEmpty())
        assertTrue(repository.sourcePreferences(alice).isEmpty())
        assertNull(repository.series(mine.id))
        assertEquals("Theirs", repository.library(bob).single().series.title)
    }

    @Test
    fun previewsThatWereNeverUsedAreForgottenAndEverythingElseIsKept() {
        val preview = add(alice, "p", "Only Looked At")
        val read = add(alice, "r", "Read Once")
        val kept = add(alice, "k", "In Library")
        repository.saveProgress(chapters(read, "1").single().id, 1, 5)
        repository.setInLibrary(kept.id, true)
        now += 40L * 24 * 60 * 60 * 1000
        assertEquals(1, repository.pruneUnused(alice, 30L * 24 * 60 * 60 * 1000))
        assertNull(repository.series(preview.id))
        assertNotNull(repository.series(read.id))
        assertNotNull(repository.series(kept.id))
    }

    @Test
    fun downloadStatesMoveThroughTheQueue() {
        val series = add(alice, "s1", "Title")
        val all = chapters(series, "1", "2")
        repository.queueDownloads(all.map { it.id })
        assertEquals(all[0].id, repository.nextQueuedDownload()!!.id)
        repository.setDownloadState(all[0].id, DownloadState.RUNNING)
        repository.setDownloadProgress(all[0].id, 3, 12)
        assertEquals(1, repository.requeueInterruptedDownloads())
        assertEquals(DownloadState.QUEUED, repository.chapter(all[0].id)!!.downloadState)
        assertEquals(3, repository.chapter(all[0].id)!!.downloadPages)
        repository.setDownloadState(all[0].id, DownloadState.DONE, dir = "/d/1", pages = 12)
        assertTrue(repository.chapter(all[0].id)!!.isDownloaded)
        repository.setDownloadState(all[1].id, DownloadState.FAILED, error = "offline")
        assertEquals("offline", repository.chapter(all[1].id)!!.downloadError)
        assertEquals(1, repository.summary(series.id)!!.downloadedCount)
        assertEquals(2, repository.downloads(alice).size)
        // A failed download can be queued again; a finished one is left alone.
        repository.queueDownloads(all.map { it.id })
        assertEquals(DownloadState.QUEUED, repository.chapter(all[1].id)!!.downloadState)
        assertEquals(DownloadState.DONE, repository.chapter(all[0].id)!!.downloadState)
        repository.setDownloadState(all[0].id, null)
        assertNull(repository.chapter(all[0].id)!!.downloadDir)
    }

    @Test
    fun titlesAreComparedWithoutCaseAccentsOrPunctuation() {
        assertEquals("pokemon special", MangaRepository.titleKey("Pokémon: Special!"))
        assertEquals(MangaRepository.titleKey("ONE-PIECE"), MangaRepository.titleKey("one piece"))
        assertEquals("", MangaRepository.titleKey("  ---  "))
    }
}
