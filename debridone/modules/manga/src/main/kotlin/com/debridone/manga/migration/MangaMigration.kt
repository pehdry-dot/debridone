package com.debridone.manga.migration

import com.debridone.manga.library.Chapter
import com.debridone.manga.library.LibraryStatus
import com.debridone.manga.library.MangaRepository
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.Series
import com.debridone.manga.library.SeriesEdit
import com.debridone.manga.library.SeriesField
import com.debridone.manga.local.LocalFiles
import com.debridone.manga.suwayomi.MangaServer
import com.debridone.platform.db.Migration
import com.debridone.platform.db.SqliteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** What became of one series of the earlier library. */
enum class MigrationOutcome(val key: String) {
    /** It is in the new library, with what was known about it. */
    CARRIED_OVER("carried_over"),

    /** It came from a source of the earlier version: once it is added again from a new source, its progress can be carried over. */
    WAITING("waiting"),

    /** Files of this computer that are no longer where they were. */
    FILES_MISSING("files_missing"),

    /** It could not be carried over; the reason is in the entry. */
    FAILED("failed");

    companion object {
        fun fromKey(key: String?): MigrationOutcome? = entries.firstOrNull { it.key == key }
    }
}

/** One series of the earlier library, as the report lists it. */
data class MigrationEntry(
    val oldSeriesId: Long,
    val title: String,
    /** "Local files", or the name of the source of the earlier version. */
    val source: String,
    val local: Boolean,
    val outcome: MigrationOutcome,
    val detail: String,
    val inLibrary: Boolean,
    val libraryStatus: LibraryStatus?,
    val favorite: Boolean,
    val chapters: Int,
    val chaptersRead: Int,
    /** The furthest chapter that was read, as the earlier library named it. */
    val lastChapterRead: String?,
    /** The series in the new library it became. */
    val newSeriesId: Long?,
    /** A series now in the library with the same title, which this one's progress can be carried over to. */
    val candidateSeriesId: Long?,
)

/** What the migration did for one profile. Nothing in it was deleted from the earlier library. */
data class MigrationReport(
    val profileId: Long,
    /** Where the earlier library was copied before anything else was done. */
    val backupFolder: Path?,
    val entries: List<MigrationEntry>,
    val notes: List<String>,
) {
    val carriedOver: Int get() = entries.count { it.outcome == MigrationOutcome.CARRIED_OVER }
    val waiting: Int get() = entries.count { it.outcome == MigrationOutcome.WAITING }
    val missing: Int get() = entries.count { it.outcome == MigrationOutcome.FILES_MISSING }
    val failed: Int get() = entries.count { it.outcome == MigrationOutcome.FAILED }

    /** Something still needs the person: a series to add again, files to find, or a failure to read about. */
    val needsAttention: Boolean get() = waiting + missing + failed > 0
}

/** The migration could not go ahead; nothing was changed. [message] is a sentence for the person. */
class MigrationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Carries the library of the earlier, Houdoku-based version of Manga over to the manga server.
 *
 * The earlier library lives in tables of Manga's own database. They are only ever read here: the
 * database, the chapters it had downloaded and the covers people chose are copied to a dated
 * backup folder first, the copy is checked, and only then is anything carried over. What was
 * done with each series is kept in a table of its own, so the migration can be repeated safely
 * and reported at any time.
 *
 *  - A series read from the person's own files is linked into the server's Local source again
 *    and gets back its place in the library, favourite, tags, collections, hand edits, read
 *    chapters, the page it was left on and its bookmarks.
 *  - A series from a source of the earlier version cannot be fetched by the new server on its
 *    own (its sources are different programs). It is listed as waiting; when the person has
 *    added the same series again from a new source, [carryOver] gives that one the library
 *    status, favourite, tags, collections and the chapters that were read, matched by number.
 *  - Chapters the earlier version downloaded and its own tracker sign-ins cannot be adopted;
 *    the report says so.
 */
class MangaMigration(
    private val database: SqliteDatabase,
    private val dataDir: Path,
    private val server: MangaServer,
    private val repository: MangaRepository,
    private val local: LocalFiles,
    private val hasLegacyTrackerSignIn: (profileId: Long) -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class OldSeries(
        val id: Long, val local: Boolean, val sourceId: String, val sourceName: String, val title: String,
        val altTitles: List<String>, val description: String, val authors: List<String>, val artists: List<String>, val tags: List<String>,
        val customTags: List<String>, val status: String?, val originalLanguage: String?, val mediaType: MediaType, val coverUrl: String,
        val coverFile: String?, val locked: Set<String>, val inLibrary: Boolean, val libraryStatus: LibraryStatus?, val favorite: Boolean,
        val readerOverrides: String?,
    )

    private class OldChapter(
        val id: Long, val sourceId: String, val title: String, val number: String, val sort: Double?, val position: Int,
        val pageCount: Int, val lastPage: Int, val scrollOffset: Float, val read: Boolean, val downloaded: Boolean,
    )

    private class OldBookmark(val chapterId: Long, val page: Int, val note: String)

    private val _revision = MutableStateFlow(0L)

    /** Changes when the state of the migration changed. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val lock = Any()

    // ---- what there is -----------------------------------------------------------------------------

    /**
     * The earlier library's series worth carrying over for a profile: in its library, or with
     * something read or bookmarked. Series that were only looked at are not the person's data.
     */
    private fun oldSeries(profileId: Long): List<OldSeries> = database.query(
        """
        SELECT s.* FROM manga_series s WHERE s.profile_id = ? AND (
            s.in_library = 1 OR s.favorite = 1
            OR EXISTS (SELECT 1 FROM manga_chapters c WHERE c.series_id = s.id AND (c.read = 1 OR c.last_page > 0))
            OR EXISTS (SELECT 1 FROM manga_bookmarks b WHERE b.series_id = s.id)
        ) ORDER BY s.title_key, s.id
        """.trimIndent(),
        profileId,
    ) { rs ->
        OldSeries(
            id = rs.getLong("id"), local = rs.getString("origin") == "local", sourceId = rs.getString("source_id"),
            sourceName = rs.getString("source_name").orEmpty(), title = rs.getString("title"),
            altTitles = strings(rs.getString("alt_titles")), description = rs.getString("description").orEmpty(),
            authors = strings(rs.getString("authors")), artists = strings(rs.getString("artists")), tags = strings(rs.getString("tags")),
            customTags = strings(rs.getString("custom_tags")), status = rs.getString("status"), originalLanguage = rs.getString("original_language"),
            mediaType = MediaType.fromKey(rs.getString("media_type")), coverUrl = rs.getString("cover_url").orEmpty(), coverFile = rs.getString("cover_file"),
            locked = rs.getString("locked_fields").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
            inLibrary = rs.getInt("in_library") == 1, libraryStatus = LibraryStatus.fromKey(rs.getString("library_status")),
            favorite = rs.getInt("favorite") == 1, readerOverrides = rs.getString("reader_overrides"),
        )
    }

    private fun oldChapters(seriesId: Long): List<OldChapter> = database.query(
        "SELECT * FROM manga_chapters WHERE series_id = ? ORDER BY position, id", seriesId,
    ) { rs ->
        OldChapter(
            id = rs.getLong("id"), sourceId = rs.getString("source_id"), title = rs.getString("title").orEmpty(), number = rs.getString("chapter_number").orEmpty(),
            sort = rs.getDouble("chapter_sort").takeIf { !rs.wasNull() }, position = rs.getInt("position"), pageCount = rs.getInt("page_count"),
            lastPage = rs.getInt("last_page"), scrollOffset = rs.getFloat("scroll_offset"), read = rs.getInt("read") == 1,
            downloaded = rs.getString("download_state") == "downloaded",
        )
    }

    private fun oldBookmarks(seriesId: Long): List<OldBookmark> = database.query(
        "SELECT chapter_id, page, note FROM manga_bookmarks WHERE series_id = ? ORDER BY id", seriesId,
    ) { rs -> OldBookmark(rs.getLong(1), rs.getInt(2), rs.getString(3).orEmpty()) }

    private fun oldCollections(seriesId: Long): List<String> = database.query(
        "SELECT c.name FROM manga_collection_items i JOIN manga_collections c ON c.id = i.collection_id WHERE i.series_id = ? ORDER BY c.name", seriesId,
    ) { it.getString(1) }

    private fun oldLinks(seriesId: Long): List<Triple<String, String, String?>> = database.query(
        "SELECT provider, remote_id, url FROM manga_metadata_links WHERE series_id = ?", seriesId,
    ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }

    private fun strings(json: String?): List<String> = runCatching {
        (Json.parseToJsonElement(json.orEmpty()) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }
    }.getOrNull().orEmpty()

    /** True when a profile has an earlier library to carry over (whether or not that was already done). */
    fun hasEarlierLibrary(profileId: Long): Boolean = oldSeries(profileId).isNotEmpty()

    /** True when the earlier library of a profile has series the migration has not looked at yet. */
    fun needsRun(profileId: Long): Boolean {
        val done = states(profileId)
        return oldSeries(profileId).any { it.id !in done }
    }

    // ---- state -------------------------------------------------------------------------------------

    private class State(val outcome: MigrationOutcome, val newSeriesId: Long?, val detail: String)

    private fun states(profileId: Long): Map<Long, State> = database.query(
        "SELECT old_series_id, outcome, new_series_id, detail FROM manga_migration WHERE profile_id = ?", profileId,
    ) { rs ->
        rs.getLong(1) to State(MigrationOutcome.fromKey(rs.getString(2)) ?: MigrationOutcome.FAILED, rs.getLong(3).takeIf { !rs.wasNull() }, rs.getString(4).orEmpty())
    }.toMap()

    private fun record(profileId: Long, oldSeriesId: Long, outcome: MigrationOutcome, newSeriesId: Long?, detail: String) {
        database.execute(
            """
            INSERT INTO manga_migration (old_series_id, profile_id, outcome, new_series_id, detail, updated_at) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (old_series_id) DO UPDATE SET outcome = excluded.outcome, new_series_id = excluded.new_series_id, detail = excluded.detail, updated_at = excluded.updated_at
            """.trimIndent(),
            oldSeriesId, profileId, outcome.key, newSeriesId, detail, clock(),
        )
    }

    private fun info(key: String): String? = database.queryOne("SELECT value FROM manga_migration_info WHERE key = ?", key) { it.getString(1) }

    private fun setInfo(key: String, value: String?) {
        if (value == null) database.execute("DELETE FROM manga_migration_info WHERE key = ?", key)
        else database.execute("INSERT INTO manga_migration_info (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value", key, value)
    }

    /** The folder the earlier library was copied to, when that has been done and the folder is still there. */
    fun backupFolder(): Path? = info(BACKUP_KEY)?.let { Paths.get(it) }?.takeIf { Files.isDirectory(it) }

    /** The person has read the report and does not want it shown on Home any more. Nothing else changes. */
    fun dismiss(profileId: Long) {
        setInfo("$DISMISSED_KEY.$profileId", "1")
        _revision.update { it + 1 }
    }

    fun isDismissed(profileId: Long): Boolean = info("$DISMISSED_KEY.$profileId") == "1"

    // ---- backup ------------------------------------------------------------------------------------

    /**
     * Copies the earlier library (the whole database, the chapters it had downloaded, the covers
     * people chose) to a dated folder beside it and checks the copy. Done once; afterwards the
     * same folder is returned. Throws, having carried nothing over, when the copy cannot be made
     * or does not check out.
     */
    fun ensureBackup(): Path = synchronized(lock) {
        backupFolder()?.let { return it }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(clock()))
        val folder = dataDir.resolve("backup-before-suwayomi-$stamp")
        try {
            Files.createDirectories(folder)
            val copy = folder.resolve("manga.db")
            Files.deleteIfExists(copy)
            // SQLite writes a consistent copy of the open database itself.
            database.executeRaw("VACUUM INTO '" + copy.toString().replace("'", "''") + "'")
            for (table in TABLES) {
                val original = database.queryOne("SELECT COUNT(*) FROM $table") { it.getLong(1) } ?: 0
                val copied = DriverManager.getConnection("jdbc:sqlite:$copy").use { connection ->
                    connection.createStatement().use { statement -> statement.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getLong(1) } }
                }
                if (original != copied) throw MigrationException("The copy of the earlier library does not match it ($table: $copied of $original rows).")
            }
            for (name in listOf("downloads", "covers")) {
                val source = dataDir.resolve(name)
                if (!Files.isDirectory(source)) continue
                val target = folder.resolve(name)
                copyTree(source, target)
                if (measure(source) != measure(target)) throw MigrationException("The copy of the earlier $name does not match the original.")
            }
            Files.writeString(
                folder.resolve("READ ME.txt"),
                "This folder is a copy of DebridOne's Manga library as it was before Manga moved to Suwayomi-Server.\n" +
                    "manga.db is the earlier library (series, chapters read, bookmarks, collections); downloads holds the chapters the earlier version had saved; covers holds covers you chose.\n" +
                    "DebridOne does not read this folder again. Keep it for as long as you like; deleting it changes nothing in DebridOne.\n",
            )
        } catch (failure: Exception) {
            // A half-made copy must not be mistaken for a backup.
            runCatching { Files.walk(folder).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
            throw failure as? MigrationException ?: MigrationException("The earlier library could not be copied to a backup folder, so nothing was carried over: ${failure.message ?: failure::class.simpleName}", failure)
        }
        setInfo(BACKUP_KEY, folder.toString())
        // The earlier version's extensions are not programs the manga server can run: their entries in
        // Manga's plugin list would only name things that no longer exist. They are in the copy.
        runCatching { database.execute("DELETE FROM manga_extensions WHERE id <> ?", "local-files") }
        folder
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val to = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(to) else Files.copy(path, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }

    /** Number of files and their total size under a folder. */
    private fun measure(folder: Path): Pair<Long, Long> = Files.walk(folder).use { stream ->
        var files = 0L
        var bytes = 0L
        stream.filter { Files.isRegularFile(it) }.forEach { files++; bytes += Files.size(it) }
        files to bytes
    }

    // ---- carrying over -----------------------------------------------------------------------------

    /**
     * Carries over what can be carried over for the active profile, and returns the report. Safe
     * to repeat: a series already carried over is left alone, files that were missing are looked
     * for again. Needs the profile's own manga server to be running; a profile that reads from a
     * server elsewhere is not migrated into it.
     */
    fun run(profileId: Long): MigrationReport = synchronized(lock) {
        val all = oldSeries(profileId)
        if (all.isEmpty()) return report(profileId)
        if (server.profileId != profileId || !server.isReady) throw MigrationException("The manga server of this profile is not running yet.")
        if (server.isRemote) throw MigrationException("This profile reads from a manga server of its own. Its earlier library is carried over when it uses DebridOne's own server.")
        ensureBackup()
        val done = states(profileId)
        for (old in all) {
            val state = done[old.id]
            if (state?.outcome == MigrationOutcome.CARRIED_OVER) continue
            if (!old.local) {
                if (state == null) record(profileId, old.id, MigrationOutcome.WAITING, null, "Add it again from one of your new sources; its progress can then be carried over.")
                continue
            }
            val path = runCatching { Paths.get(old.sourceId) }.getOrNull()
            if (path == null || !Files.exists(path)) {
                record(profileId, old.id, MigrationOutcome.FILES_MISSING, null, "Its files are no longer at ${old.sourceId}.")
                continue
            }
            try {
                val series = local.import(profileId, path, old.mediaType)
                val detail = apply(profileId, old, series, byFileName = true)
                record(profileId, old.id, MigrationOutcome.CARRIED_OVER, series.id, detail)
            } catch (failure: Exception) {
                record(profileId, old.id, MigrationOutcome.FAILED, null, failure.message ?: "It could not be read from ${old.sourceId}.")
            }
        }
        _revision.update { it + 1 }
        val result = report(profileId)
        runCatching { writeReport(result) }
        result
    }

    /**
     * Gives a series of the new library what the earlier library knew about the same work: its
     * place in the library, favourite, tags, collections, and the chapters that were read,
     * matched by chapter number. The person chooses the pair; nothing is matched silently.
     */
    fun carryOver(profileId: Long, oldSeriesId: Long, newSeriesId: Long): MigrationEntry = synchronized(lock) {
        val old = oldSeries(profileId).firstOrNull { it.id == oldSeriesId } ?: throw MigrationException("That series is not in the earlier library of this profile.")
        if (server.profileId != profileId || !server.isReady) throw MigrationException("The manga server of this profile is not running yet.")
        val target = repository.series(newSeriesId) ?: throw MigrationException("The series to carry over to is not there any more.")
        ensureBackup()
        val detail = apply(profileId, old, target, byFileName = old.local)
        record(profileId, old.id, MigrationOutcome.CARRIED_OVER, target.id, detail)
        _revision.update { it + 1 }
        val result = report(profileId)
        runCatching { writeReport(result) }
        result.entries.first { it.oldSeriesId == oldSeriesId }
    }

    private fun apply(profileId: Long, old: OldSeries, target: Series, byFileName: Boolean): String {
        val oldChapters = oldChapters(old.id)
        val newChapters = repository.chapters(target.id, allLanguages = true)
        val mapping = match(oldChapters, newChapters, byFileName)

        if (old.inLibrary || old.favorite) repository.setInLibrary(target.id, true, old.libraryStatus)
        if (old.libraryStatus != null) repository.setLibraryStatus(target.id, old.libraryStatus)
        if (old.favorite) repository.setFavorite(target.id, true)
        // What the person had typed themselves is theirs still; what the earlier source had said is not carried over.
        val edit = SeriesEdit(
            title = old.title.takeIf { SeriesField.TITLE in old.locked },
            altTitles = old.altTitles.takeIf { SeriesField.ALT_TITLES in old.locked },
            description = old.description.takeIf { SeriesField.DESCRIPTION in old.locked },
            authors = old.authors.takeIf { SeriesField.AUTHORS in old.locked },
            artists = old.artists.takeIf { SeriesField.ARTISTS in old.locked },
            tags = old.tags.takeIf { SeriesField.TAGS in old.locked },
            customTags = old.customTags.takeIf { it.isNotEmpty() },
            status = old.status.takeIf { SeriesField.STATUS in old.locked },
            originalLanguage = old.originalLanguage.takeIf { SeriesField.LANGUAGE in old.locked },
            coverUrl = old.coverUrl.takeIf { SeriesField.COVER in old.locked && it.isNotBlank() && old.coverFile == null },
        )
        if (edit != SeriesEdit()) repository.editSeries(target.id, edit)
        repository.setMediaType(target.id, old.mediaType, lock = SeriesField.MEDIA_TYPE in old.locked)
        old.coverFile?.takeIf { runCatching { Files.isRegularFile(Paths.get(it)) }.getOrDefault(false) }?.let { repository.setCoverFile(target.id, it) }
        old.readerOverrides?.takeIf { it.isNotBlank() }?.let { repository.setReaderOverrides(target.id, it) }

        val read = oldChapters.filter { it.read }
        val readNow = read.mapNotNull { mapping[it.id]?.id }
        if (readNow.isNotEmpty()) repository.setRead(readNow, true)
        var resumed = 0
        for (chapter in oldChapters.filter { !it.read && it.lastPage > 0 }) {
            val now = mapping[chapter.id] ?: continue
            // What was read since, in the new library, is further along and is not put back.
            if (now.read || now.lastPage >= chapter.lastPage) continue
            // The server keeps a page only for a chapter whose pages it has listed once (it knows
            // no page count before that, and holds the page to the count it knows).
            val pages = if (now.pageCount > 0) now.pageCount else runCatching { server.api().chapterPages(now.id.toInt()).second.size }.getOrDefault(0)
            if (pages <= 0 || chapter.lastPage >= pages) continue
            repository.saveProgress(now.id, chapter.lastPage, pages, chapter.scrollOffset)
            resumed++
        }
        var bookmarks = 0
        for (bookmark in oldBookmarks(old.id)) {
            val now = mapping[bookmark.chapterId] ?: continue
            if (repository.bookmark(now.id, bookmark.page) == null && repository.toggleBookmark(now.id, bookmark.page, bookmark.note)) bookmarks++
        }
        val shelves = oldCollections(old.id)
        if (shelves.isNotEmpty()) {
            val existing = repository.collections(profileId)
            for (name in shelves) {
                val collection = existing.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: repository.collections(profileId).firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: repository.createCollection(profileId, name)
                repository.setInCollection(collection.id, target.id, true)
            }
        }
        for ((provider, remoteId, url) in oldLinks(old.id)) runCatching { repository.linkMetadata(target.id, provider, remoteId, url) }

        return buildList {
            if (read.isNotEmpty()) add("${readNow.size} of ${read.size} read chapters marked read")
            if (resumed > 0) add("the page you were on in $resumed chapter${if (resumed == 1) "" else "s"}")
            if (bookmarks > 0) add("$bookmarks bookmark${if (bookmarks == 1) "" else "s"}")
            if (shelves.isNotEmpty()) add("${shelves.size} collection${if (shelves.size == 1) "" else "s"}")
            if (read.size > readNow.size) add("${read.size - readNow.size} read chapter${if (read.size - readNow.size == 1) "" else "s"} had no counterpart")
        }.joinToString(", ").ifEmpty { "its place in your library" }
    }

    /** Which chapter of the new series each chapter of the earlier one is. */
    private fun match(old: List<OldChapter>, new: List<Chapter>, byFileName: Boolean): Map<Long, Chapter> {
        if (old.isEmpty() || new.isEmpty()) return emptyMap()
        if (old.size == 1 && new.size == 1 && byFileName) return mapOf(old.single().id to new.single())
        val result = HashMap<Long, Chapter>()
        if (byFileName) {
            val byName = new.groupBy { fileKey(it.sourceId) }
            val byTitle = new.groupBy { it.title.trim().lowercase() }
            for (chapter in old) {
                (byName[fileKey(chapter.sourceId)]?.singleOrNull() ?: byTitle[chapter.title.trim().lowercase()]?.singleOrNull())?.let { result[chapter.id] = it }
            }
            // The same files in the same order, should a name not have matched.
            if (result.size < old.size && old.size == new.size) {
                val sortedOld = old.sortedBy { it.position }
                val sortedNew = new.sortedBy { it.position }
                sortedOld.forEachIndexed { index, chapter -> result.putIfAbsent(chapter.id, sortedNew[index]) }
            }
        } else {
            val numbered = new.filter { it.chapterSort != null }
            for (chapter in old) {
                val number = chapter.sort
                val same = if (number != null) numbered.filter { abs(it.chapterSort!! - number) < 0.001 } else emptyList()
                // Several uploads of one chapter are one chapter read: all of them are marked.
                (same.firstOrNull() ?: new.singleOrNull { number == null && it.title.trim().equals(chapter.title.trim(), ignoreCase = true) && chapter.title.isNotBlank() })
                    ?.let { result[chapter.id] = it }
            }
        }
        return result
    }

    private fun fileKey(path: String): String = path.replace('\\', '/').trimEnd('/').substringAfterLast('/').substringBeforeLast('.', path.replace('\\', '/').trimEnd('/').substringAfterLast('/')).lowercase()

    // ---- the report --------------------------------------------------------------------------------

    /** What the migration has done for a profile so far, and what is still open. Reads only. */
    fun report(profileId: Long): MigrationReport {
        val all = oldSeries(profileId)
        val states = states(profileId)
        val library = if (server.profileId == profileId && server.isReady) runCatching { repository.library(profileId).map { it.series } }.getOrDefault(emptyList()) else emptyList()
        val byTitle = library.groupBy { MangaRepository.titleKey(it.title) }
        val taken = states.values.mapNotNull { it.newSeriesId }.toSet()
        var downloaded = 0
        val entries = all.map { old ->
            val chapters = oldChapters(old.id)
            downloaded += chapters.count { it.downloaded }
            val state = states[old.id]
            val read = chapters.filter { it.read }
            val furthest = read.maxWithOrNull(compareBy<OldChapter> { it.sort ?: Double.NEGATIVE_INFINITY }.thenBy { it.position })
            val outcome = state?.outcome ?: if (old.local) MigrationOutcome.FILES_MISSING else MigrationOutcome.WAITING
            MigrationEntry(
                oldSeriesId = old.id, title = old.title, source = if (old.local) "Local files" else old.sourceName.ifBlank { "A source of the earlier version" }, local = old.local,
                outcome = outcome, detail = state?.detail ?: "Not looked at yet.",
                inLibrary = old.inLibrary, libraryStatus = old.libraryStatus, favorite = old.favorite,
                chapters = chapters.size, chaptersRead = read.size,
                lastChapterRead = furthest?.let { it.number.ifBlank { it.title }.ifBlank { null } },
                newSeriesId = state?.newSeriesId,
                candidateSeriesId = if (outcome == MigrationOutcome.CARRIED_OVER) null
                else byTitle[MangaRepository.titleKey(old.title)]?.firstOrNull { it.id !in taken }?.id,
            )
        }
        val backup = backupFolder()
        val notes = buildList {
            if (backup != null) add("The earlier library was copied to $backup before anything was done, and is still in DebridOne's own database too. Nothing of it was deleted.")
            if (downloaded > 0) {
                add(
                    "$downloaded chapter${if (downloaded == 1) "" else "s"} the earlier version had saved for offline reading cannot be used by the new manga server. " +
                        "The files are in the backup folder; save the chapters again from their new source.",
                )
            }
            if (entries.any { !it.local && it.outcome != MigrationOutcome.CARRIED_OVER }) {
                add("Series from sources of the earlier version are read through Mihon/Tachiyomi extensions now. Add such a series again from one of your new sources, then carry its progress over here.")
            }
            if (hasLegacyTrackerSignIn(profileId)) add("The AniList or MyAnimeList sign-in of the earlier version is not used any more. Sign in again under Settings › Trackers.")
        }
        return MigrationReport(profileId, backup, entries, notes)
    }

    /** Saves the report as a text file beside the backup, for reading outside DebridOne. */
    private fun writeReport(report: MigrationReport) {
        val folder = report.backupFolder ?: return
        val text = buildString {
            appendLine("DebridOne — Manga library carried over to Suwayomi-Server")
            appendLine("Profile ${report.profileId} · written ${DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(clock()))}")
            appendLine("${report.carriedOver} carried over · ${report.waiting} to add again from a new source · ${report.missing} with files missing · ${report.failed} failed")
            appendLine()
            for (entry in report.entries) {
                val what = when (entry.outcome) {
                    MigrationOutcome.CARRIED_OVER -> "CARRIED OVER"
                    MigrationOutcome.WAITING -> "TO ADD AGAIN"
                    MigrationOutcome.FILES_MISSING -> "FILES MISSING"
                    MigrationOutcome.FAILED -> "FAILED"
                }
                appendLine("[$what] ${entry.title} — ${entry.source}")
                appendLine(
                    "    " + listOfNotNull(
                        entry.libraryStatus?.label ?: "In the library".takeIf { entry.inLibrary }, "favourite".takeIf { entry.favorite },
                        "${entry.chaptersRead} of ${entry.chapters} chapters read", entry.lastChapterRead?.let { "last read: $it" },
                    ).joinToString(" · "),
                )
                appendLine("    ${entry.detail}")
            }
            if (report.notes.isNotEmpty()) appendLine()
            report.notes.forEach { appendLine(it) }
        }
        Files.writeString(folder.resolve("migration-report-profile-${report.profileId}.txt"), text)
    }

    companion object {
        private const val BACKUP_KEY = "backup.folder"
        private const val DISMISSED_KEY = "dismissed"

        /** The tables of the earlier library, whose rows a backup must hold in full. */
        private val TABLES = listOf(
            "manga_series", "manga_chapters", "manga_bookmarks", "manga_history", "manga_collections", "manga_collection_items",
            "manga_source_prefs", "manga_metadata_links", "manga_profile_settings",
        )

        /** Where the migration keeps what it did. The earlier library's own tables are never changed. */
        val schema = Migration(
            3, "what became of each series of the earlier manga library",
            """
            CREATE TABLE manga_migration (
                old_series_id INTEGER PRIMARY KEY,
                profile_id INTEGER NOT NULL,
                outcome TEXT NOT NULL,
                new_series_id INTEGER,
                detail TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL
            )
            """,
            "CREATE INDEX manga_migration_profile ON manga_migration (profile_id)",
            "CREATE TABLE manga_migration_info (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        )
    }
}
