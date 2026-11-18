package com.debridone.manga.migration

import com.debridone.manga.MangaModule
import com.debridone.manga.MangaServices
import com.debridone.manga.library.LibraryFilter
import com.debridone.manga.library.LibraryStatus
import com.debridone.manga.library.MediaType
import com.debridone.manga.suwayomi.MangaConnection
import com.debridone.manga.suwayomi.SampleComics
import com.debridone.manga.suwayomi.SuwayomiTestRuntime
import com.debridone.module.ModuleId
import com.debridone.module.storage.ModuleStorage
import com.debridone.platform.AppPaths
import com.debridone.platform.secrets.FileSecretStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The library of the earlier, Houdoku-based Manga carried over to the real Suwayomi-Server: a
 * database in the earlier format is written by hand, the module is opened on it, and what a
 * person would find afterwards is checked. Nothing leaves this computer.
 */
class MangaMigrationTest {
    private val alice = 2001L
    private val bob = 2002L
    private lateinit var dir: Path
    private lateinit var files: Path
    private lateinit var storage: ModuleStorage
    private lateinit var module: MangaModule
    private lateinit var services: MangaServices

    @AfterTest
    fun stop() {
        if (::module.isInitialized) module.close()
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    private fun newModule(): MangaModule {
        val runtime = SuwayomiTestRuntime.runtimeOrSkip()
        val paths = AppPaths(dir.resolve("data"), dir.resolve("cache"))
        storage = ModuleStorage(ModuleId.MANGA, paths, FileSecretStore(paths.secretsDir))
        return MangaModule(storage, runtime = { runtime })
    }

    private fun open(profile: Long) {
        module = newModule()
        runBlocking {
            module.start()
            module.onProfileChanged(profile)
        }
        services = module.services.value!!
        module.ensureServer()
        awaitMigrated(profile)
    }

    /** Waits for the profile's server and for the migration that follows its first start. */
    private fun awaitMigrated(profile: Long) {
        val deadline = System.currentTimeMillis() + 150_000
        while (System.currentTimeMillis() < deadline) {
            val state = services.server.state.value
            if (state is MangaConnection.Failed && !state.retrying) throw AssertionError("The server failed: ${state.message}")
            module.migrationProblem.value?.let { throw AssertionError("The migration failed: $it") }
            if (state is MangaConnection.Ready && state.profileId == profile && !services.migration.needsRun(profile)) return
            Thread.sleep(50)
        }
        throw AssertionError("The earlier library was not carried over in time: ${services.server.state.value}")
    }

    private fun comic(title: String, chapters: Int): Path {
        val folder = files.resolve(title)
        repeat(chapters) { SampleComics.cbz(folder.resolve("Chapter %02d.cbz".format(it + 1)), pages = 3) }
        return folder
    }

    private fun Connection.insert(sql: String, vararg args: Any?): Long {
        prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
        return createStatement().use { it.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
    }

    private fun Connection.count(table: String): Long = createStatement().use { it.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getLong(1) } }

    private fun Connection.series(
        profile: Long, origin: String, sourceId: String, sourceName: String, title: String, inLibrary: Boolean = true, status: String? = null, favorite: Boolean = false,
        mediaType: String = "manga", locked: String = "", description: String = "", customTags: String = "[]", readerOverrides: String? = null,
    ): Long = insert(
        """
        INSERT INTO manga_series (profile_id, origin, extension_id, source_id, source_name, title, title_key, description, custom_tags, media_type, locked_fields,
            in_library, library_status, favorite, reader_overrides, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1)
        """.trimIndent(),
        profile, origin, if (origin == "local") "local" else "tiyo-extension", sourceId, sourceName, title, title.lowercase(), description, customTags, mediaType, locked,
        if (inLibrary) 1 else 0, status, if (favorite) 1 else 0, readerOverrides,
    )

    private fun Connection.chapter(
        series: Long, sourceId: String, title: String, number: String, position: Int, read: Boolean = false, lastPage: Int = 0, pageCount: Int = 3, downloaded: Boolean = false,
    ): Long = insert(
        """
        INSERT INTO manga_chapters (series_id, source_id, title, chapter_number, chapter_sort, position, page_count, last_page, read, first_seen_at, download_state)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
        """.trimIndent(),
        series, sourceId, title, number, number.toDoubleOrNull(), position, pageCount, lastPage, if (read) 1 else 0, if (downloaded) "downloaded" else null,
    )

    @Test
    fun theEarlierLibraryIsBackedUpCarriedOverAndReportedWithoutLosingAnything() {
        SuwayomiTestRuntime.runtimeOrSkip()
        dir = Files.createTempDirectory("manga-migration")
        files = Files.createDirectories(dir.resolve("my comics"))

        // The database as the earlier version left it: the schema is the module's own, the rows are written here.
        module = newModule()
        runBlocking { module.start() }
        module.close()
        val kept = comic("Old Local Series", chapters = 3)
        val gone = files.resolve("Moved Away")
        val bobs = comic("Bobs Book", chapters = 1)
        Files.createDirectories(storage.dataDir.resolve("downloads/2001/7/9"))
        Files.write(storage.dataDir.resolve("downloads/2001/7/9/001.jpg"), SampleComics.page(0))
        Files.createDirectories(storage.dataDir.resolve("covers"))
        val chosenCover = Files.write(storage.dataDir.resolve("covers/chosen.png"), SampleComics.page(1))
        val before = HashMap<String, Long>()
        var localId = 0L
        var goneId = 0L
        var onlineId = 0L
        DriverManager.getConnection("jdbc:sqlite:${storage.databaseFile}").use { db ->
            localId = db.series(
                alice, "local", kept.toString(), "Local files", "My Title For It", status = "reading", favorite = true, mediaType = "manhwa",
                locked = "title,description", description = "typed by me", customTags = """["mine"]""", readerOverrides = """{"mode":"WEBTOON"}""",
            )
            db.createStatement().use { it.execute("UPDATE manga_series SET cover_file = '${chosenCover.toString().replace("'", "''")}' WHERE id = $localId") }
            db.chapter(localId, kept.resolve("Chapter 01.cbz").toString(), "Chapter 01", "1", 0, read = true)
            val second = db.chapter(localId, kept.resolve("Chapter 02.cbz").toString(), "Chapter 02", "2", 1, lastPage = 1)
            db.chapter(localId, kept.resolve("Chapter 03.cbz").toString(), "Chapter 03", "3", 2)
            db.insert("INSERT INTO manga_bookmarks (profile_id, series_id, chapter_id, page, note, created_at) VALUES (?, ?, ?, 2, 'the reveal', 1)", alice, localId, second)
            val shelf = db.insert("INSERT INTO manga_collections (profile_id, name, created_at) VALUES (?, 'Old Shelf', 1)", alice)
            db.insert("INSERT INTO manga_collection_items (collection_id, series_id, added_at) VALUES (?, ?, 1)", shelf, localId)
            db.insert("INSERT INTO manga_metadata_links (series_id, provider, remote_id, url, linked_at) VALUES (?, 'anilist', '105398', 'https://anilist.co/manga/105398', 1)", localId)

            goneId = db.series(alice, "local", gone.toString(), "Local files", "Moved Away")
            db.chapter(goneId, gone.resolve("Chapter 01.cbz").toString(), "Chapter 01", "1", 0, read = true)

            onlineId = db.series(alice, "extension", "a1b2c3", "MangaDex", "Solo Quest", status = "on_hold", favorite = true)
            db.chapter(onlineId, "c1", "The Gate", "1", 0, read = true, downloaded = true)
            db.chapter(onlineId, "c2", "The Key", "2", 1, read = true)
            db.chapter(onlineId, "c3", "The Door", "3", 2)
            db.insert("INSERT INTO manga_collection_items (collection_id, series_id, added_at) VALUES (?, ?, 1)", shelf, onlineId)

            // Only looked at once: not the person's data, and not carried over.
            val browsed = db.series(alice, "extension", "zzz", "MangaDex", "Just Browsing", inLibrary = false)
            db.chapter(browsed, "b1", "One", "1", 0)

            val bobId = db.series(bob, "local", bobs.toString(), "Local files", "Bobs Book", status = "completed")
            db.chapter(bobId, bobs.resolve("Chapter 01.cbz").toString(), "Chapter 01", "1", 0, read = true)

            for (table in listOf("manga_series", "manga_chapters", "manga_bookmarks", "manga_collections", "manga_collection_items", "manga_metadata_links")) before[table] = db.count(table)
        }

        // DebridOne is opened; Alice opens Manga.
        open(alice)
        val migration = services.migration
        val repository = services.repository
        var report = migration.report(alice)
        assertEquals(setOf("My Title For It", "Moved Away", "Solo Quest"), report.entries.map { it.title }.toSet(), "what was only browsed is not listed")

        // First of all, a copy that holds everything.
        val backup = assertNotNull(report.backupFolder)
        assertTrue(backup.startsWith(storage.dataDir) && backup.fileName.toString().startsWith("backup-before-suwayomi-"))
        DriverManager.getConnection("jdbc:sqlite:${backup.resolve("manga.db")}").use { copy ->
            before.forEach { (table, rows) -> assertEquals(rows, copy.count(table), "$table in the backup") }
        }
        assertTrue(Files.isRegularFile(backup.resolve("downloads/2001/7/9/001.jpg")))
        assertTrue(Files.isRegularFile(backup.resolve("covers/chosen.png")))
        assertTrue(Files.isRegularFile(backup.resolve("READ ME.txt")))

        // The series read from her own files is back, with everything that was known about it.
        val local = report.entries.single { it.oldSeriesId == localId }
        assertEquals(MigrationOutcome.CARRIED_OVER, local.outcome, local.detail)
        val series = repository.series(assertNotNull(local.newSeriesId))!!
        assertEquals("My Title For It", series.title)
        assertEquals("typed by me", series.description)
        assertTrue(series.inLibrary && series.favorite)
        assertEquals(LibraryStatus.READING, series.libraryStatus)
        assertEquals(MediaType.MANHWA, series.mediaType)
        assertEquals(listOf("mine"), series.customTags)
        assertEquals("""{"mode":"WEBTOON"}""", series.readerOverrides)
        assertEquals(chosenCover.toString(), series.coverFile)
        val chapters = repository.chapters(series.id)
        assertEquals(listOf(true, false, false), chapters.map { it.read })
        assertEquals(1, chapters[1].lastPage)
        assertEquals("the reveal", repository.bookmarks(series.id).single().let { assertEquals(2, it.page); assertEquals(chapters[1].id, it.chapterId); it.note })
        val shelf = repository.collections(alice).single { it.name == "Old Shelf" }
        assertEquals(listOf(series.id), repository.library(alice, LibraryFilter(collectionId = shelf.id)).map { it.series.id })
        assertEquals("105398", repository.metadataLinks(series.id).single { it.provider == "anilist" }.remoteId)
        assertEquals(1, local.chaptersRead)

        // Files that are no longer there are said to be missing, with where they were.
        val missing = report.entries.single { it.oldSeriesId == goneId }
        assertEquals(MigrationOutcome.FILES_MISSING, missing.outcome)
        assertTrue(missing.detail.contains(gone.toString()))

        // A series from a source of the earlier version waits for the person, with what was read.
        val waiting = report.entries.single { it.oldSeriesId == onlineId }
        assertEquals(MigrationOutcome.WAITING, waiting.outcome)
        assertEquals("MangaDex", waiting.source)
        assertEquals(LibraryStatus.ON_HOLD, waiting.libraryStatus)
        assertEquals(2, waiting.chaptersRead)
        assertEquals("2", waiting.lastChapterRead)
        assertNull(waiting.candidateSeriesId)
        assertTrue(report.needsAttention)
        assertTrue(report.notes.any { it.contains("1 chapter the earlier version had saved") }, report.notes.toString())
        val written = Files.readString(backup.resolve("migration-report-profile-$alice.txt"))
        assertTrue(written.contains("[TO ADD AGAIN] Solo Quest — MangaDex") && written.contains("[FILES MISSING] Moved Away") && written.contains("[CARRIED OVER] My Title For It"), written)

        // She adds the same series again from a new source (here: a folder of the same name), and carries its progress over.
        val again = services.local.import(alice, comic("Solo Quest", chapters = 3))
        repository.setInLibrary(again.id, true)
        report = migration.report(alice)
        assertEquals(again.id, report.entries.single { it.oldSeriesId == onlineId }.candidateSeriesId)
        val carried = migration.carryOver(alice, onlineId, again.id)
        assertEquals(MigrationOutcome.CARRIED_OVER, carried.outcome)
        assertTrue(carried.detail.contains("2 of 2 read chapters"), carried.detail)
        val solo = repository.series(again.id)!!
        assertEquals(LibraryStatus.ON_HOLD, solo.libraryStatus)
        assertTrue(solo.favorite)
        assertEquals(listOf(true, true, false), repository.chapters(again.id).map { it.read })
        assertTrue(again.id in repository.library(alice, LibraryFilter(collectionId = shelf.id)).map { it.series.id })

        // The files come back: the next run finds them. Nothing that was done is done twice.
        SampleComics.cbz(gone.resolve("Chapter 01.cbz"), pages = 2)
        val libraryBefore = repository.library(alice).size
        report = migration.run(alice)
        assertEquals(MigrationOutcome.CARRIED_OVER, report.entries.single { it.oldSeriesId == goneId }.outcome)
        assertEquals(libraryBefore + 1, repository.library(alice).size)
        assertEquals(1, repository.library(alice).count { it.series.title == "My Title For It" })
        assertFalse(report.needsAttention)
        assertEquals(backup, migration.run(alice).backupFolder, "one backup, however often it runs")

        // The other profile's earlier library is carried over to its own server when it is opened; the backup is the same one.
        runBlocking { module.onProfileChanged(bob) }
        awaitMigrated(bob)
        val his = services.migration.report(bob)
        assertEquals(listOf("Bobs Book"), his.entries.map { it.title })
        assertEquals(MigrationOutcome.CARRIED_OVER, his.entries.single().outcome)
        assertEquals(backup, his.backupFolder)
        val book = repository.library(bob).single().series
        assertEquals("Bobs Book", book.title)
        assertEquals(LibraryStatus.COMPLETED, book.libraryStatus)
        assertTrue(repository.chapters(book.id).single().read)

        // The earlier library itself was only read: every row is still there.
        module.close()
        DriverManager.getConnection("jdbc:sqlite:${storage.databaseFile}").use { db ->
            before.forEach { (table, rows) -> assertEquals(rows, db.count(table), "$table after the migration") }
            assertEquals(4, db.count("manga_migration"))
        }
        assertTrue(Files.isRegularFile(storage.dataDir.resolve("downloads/2001/7/9/001.jpg")), "the earlier downloads are still where they were")
    }

    @Test
    fun aProfileWithoutAnEarlierLibraryIsLeftAlone() {
        SuwayomiTestRuntime.runtimeOrSkip()
        dir = Files.createTempDirectory("manga-migration-none")
        files = Files.createDirectories(dir.resolve("my comics"))
        open(alice)
        assertFalse(services.migration.hasEarlierLibrary(alice))
        val report = services.migration.run(alice)
        assertTrue(report.entries.isEmpty() && report.notes.isEmpty())
        assertNull(report.backupFolder, "nothing to back up, so no backup folder is made")
        assertFalse(Files.list(storage.dataDir).use { stream -> stream.anyMatch { it.fileName.toString().startsWith("backup-before-suwayomi") } })
    }
}
