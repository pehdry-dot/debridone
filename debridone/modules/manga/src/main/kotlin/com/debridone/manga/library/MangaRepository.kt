package com.debridone.manga.library

import com.debridone.platform.db.SqliteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.sql.ResultSet
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything Manga stores about series and reading, in `manga.db`. Every query names a profile (or
 * a row that belongs to one), so one profile never sees another's library, progress or edits.
 *
 * [revision] changes after every write, so screens know to query again.
 */
class MangaRepository(
    private val database: SqliteDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val stringList = ListSerializer(String.serializer())

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun changed() = _revision.update { it + 1 }

    /** Per profile: the chapter languages to count and list. Empty means every language. */
    private val chapterLanguages = ConcurrentHashMap<Long, Set<String>>()

    fun setChapterLanguages(profileId: Long, languages: Set<String>) {
        chapterLanguages[profileId] = languages
        changed()
    }

    fun chapterLanguages(profileId: Long): Set<String> = chapterLanguages[profileId].orEmpty()

    // ---- series ---------------------------------------------------------------------------------

    fun series(seriesId: Long): Series? =
        database.queryOne("SELECT * FROM manga_series WHERE id = ?", seriesId, map = ::readSeries)

    fun seriesBySource(profileId: Long, extensionId: String, sourceId: String): Series? = database.queryOne(
        "SELECT * FROM manga_series WHERE profile_id = ? AND extension_id = ? AND source_id = ?",
        profileId, extensionId, sourceId, map = ::readSeries,
    )

    /**
     * Makes sure a series has a row for this profile and returns it. An existing row keeps its
     * library state, progress and hand edits; a new one starts outside the library.
     */
    fun ensureSeries(
        profileId: Long,
        origin: SeriesOrigin,
        extensionId: String,
        sourceId: String,
        sourceName: String,
        details: SeriesDetails,
        sourceRaw: String? = null,
    ): Series {
        val now = clock()
        val existing = seriesBySource(profileId, extensionId, sourceId)
        val id = if (existing == null) {
            val title = details.title?.trim().orEmpty().ifEmpty { sourceId }
            val type = details.mediaType ?: MangaClassifier.classify(details.originalLanguage, details.tags.orEmpty())
            database.insert(
                """
                INSERT INTO manga_series (profile_id, origin, extension_id, source_id, source_name, title, title_key, alt_titles, description,
                    authors, artists, tags, status, original_language, media_type, cover_url, banner_url, source_raw, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                profileId, origin.key, extensionId, sourceId, sourceName, title, titleKey(title), encode(details.altTitles.orEmpty()),
                details.description.orEmpty(), encode(details.authors.orEmpty()), encode(details.artists.orEmpty()), encode(details.tags.orEmpty()),
                details.status, details.originalLanguage, type.key, details.coverUrl.orEmpty(), details.bannerUrl, sourceRaw, now, now,
            )
        } else {
            if (sourceRaw != null || sourceName != existing.sourceName) {
                database.execute(
                    "UPDATE manga_series SET source_raw = COALESCE(?, source_raw), source_name = ? WHERE id = ?",
                    sourceRaw, sourceName.ifBlank { existing.sourceName }, existing.id,
                )
            }
            applyDetails(existing.id, details, notify = false)
            existing.id
        }
        changed()
        return series(id)!!
    }

    /**
     * Applies details from a source or a metadata provider. Fields the user has locked are left
     * alone, and so is anything the details say nothing about. Returns the fields that changed.
     */
    fun applyDetails(seriesId: Long, details: SeriesDetails, notify: Boolean = true, overwriteWithEmpty: Boolean = false): Set<String> {
        val current = series(seriesId) ?: return emptySet()
        val locked = current.lockedFields
        val changedFields = LinkedHashSet<String>()
        val sets = ArrayList<String>()
        val args = ArrayList<Any?>()
        fun set(field: String, column: String, value: Any?, differs: Boolean) {
            if (field in locked || !differs) return
            sets += "$column = ?"
            args += value
            changedFields += field
        }
        fun offered(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() || overwriteWithEmpty }
        fun offered(list: List<String>?): List<String>? = list?.map(String::trim)?.filter(String::isNotEmpty)?.takeIf { it.isNotEmpty() || overwriteWithEmpty }

        offered(details.title)?.takeIf { it.isNotEmpty() }?.let { title ->
            if (SeriesField.TITLE !in locked && title != current.title) {
                sets += "title = ?"; args += title
                sets += "title_key = ?"; args += titleKey(title)
                changedFields += SeriesField.TITLE
            }
        }
        offered(details.altTitles)?.let { set(SeriesField.ALT_TITLES, "alt_titles", encode(it), it != current.altTitles) }
        offered(details.description)?.let { set(SeriesField.DESCRIPTION, "description", it, it != current.description) }
        offered(details.authors)?.let { set(SeriesField.AUTHORS, "authors", encode(it), it != current.authors) }
        offered(details.artists)?.let { set(SeriesField.ARTISTS, "artists", encode(it), it != current.artists) }
        offered(details.tags)?.let { set(SeriesField.TAGS, "tags", encode(it), it != current.tags) }
        offered(details.status)?.takeIf { it.isNotEmpty() }?.let { set(SeriesField.STATUS, "status", it, it != current.status) }
        offered(details.originalLanguage)?.takeIf { it.isNotEmpty() }?.let { set(SeriesField.LANGUAGE, "original_language", it, it != current.originalLanguage) }
        offered(details.coverUrl)?.takeIf { it.isNotEmpty() }?.let { set(SeriesField.COVER, "cover_url", it, it != current.coverUrl) }
        details.bannerUrl?.takeIf { it.isNotBlank() && it != current.bannerUrl }?.let { sets += "banner_url = ?"; args += it }
        val offeredTags = details.tags?.takeIf { it.isNotEmpty() }
        val type = details.mediaType
            ?: if (details.originalLanguage != null || offeredTags != null) {
                MangaClassifier.classify(details.originalLanguage ?: current.originalLanguage, offeredTags ?: current.tags)
            } else {
                null
            }
        type?.let { set(SeriesField.MEDIA_TYPE, "media_type", it.key, it != current.mediaType) }

        if (sets.isEmpty()) return emptySet()
        sets += "updated_at = ?"
        args += clock()
        args += seriesId
        database.execute("UPDATE manga_series SET ${sets.joinToString(", ")} WHERE id = ?", *args.toTypedArray())
        if (notify) changed()
        return changedFields
    }

    /** Applies changes made by hand. With [lock], every edited field is protected from later refreshes. */
    fun editSeries(seriesId: Long, edit: SeriesEdit, lock: Boolean = true) {
        val current = series(seriesId) ?: return
        val sets = ArrayList<String>()
        val args = ArrayList<Any?>()
        edit.title?.trim()?.takeIf { it.isNotEmpty() }?.let { sets += "title = ?"; args += it; sets += "title_key = ?"; args += titleKey(it) }
        edit.altTitles?.let { sets += "alt_titles = ?"; args += encode(clean(it)) }
        edit.description?.let { sets += "description = ?"; args += it.trim() }
        edit.authors?.let { sets += "authors = ?"; args += encode(clean(it)) }
        edit.artists?.let { sets += "artists = ?"; args += encode(clean(it)) }
        edit.tags?.let { sets += "tags = ?"; args += encode(clean(it)) }
        edit.customTags?.let { sets += "custom_tags = ?"; args += encode(clean(it)) }
        if (edit.clearStatus) sets += "status = NULL" else edit.status?.let { sets += "status = ?"; args += it }
        edit.mediaType?.let { sets += "media_type = ?"; args += it.key }
        edit.coverFile?.let { sets += "cover_file = ?"; args += it.ifBlank { null } }
        edit.coverUrl?.let { sets += "cover_url = ?"; args += it }
        edit.originalLanguage?.let { sets += "original_language = ?"; args += it.ifBlank { null } }
        if (lock) {
            sets += "locked_fields = ?"
            args += (current.lockedFields + edit.touchedFields()).sorted().joinToString(",")
        }
        if (sets.isEmpty()) return
        sets += "updated_at = ?"
        args += clock()
        args += seriesId
        database.execute("UPDATE manga_series SET ${sets.joinToString(", ")} WHERE id = ?", *args.toTypedArray())
        changed()
    }

    fun setFieldLocked(seriesId: Long, field: String, locked: Boolean) {
        val current = series(seriesId) ?: return
        if (field !in SeriesField.all) return
        val fields = if (locked) current.lockedFields + field else current.lockedFields - field
        database.execute("UPDATE manga_series SET locked_fields = ? WHERE id = ?", fields.sorted().joinToString(","), seriesId)
        changed()
    }

    fun setMediaType(seriesId: Long, type: MediaType, lock: Boolean = true) = editSeries(seriesId, SeriesEdit(mediaType = type), lock)

    fun setCoverFile(seriesId: Long, file: String?) {
        database.execute("UPDATE manga_series SET cover_file = ? WHERE id = ?", file, seriesId)
        changed()
    }

    fun setReaderOverrides(seriesId: Long, overrides: String?) {
        database.execute("UPDATE manga_series SET reader_overrides = ? WHERE id = ?", overrides, seriesId)
        changed()
    }

    fun setInLibrary(seriesId: Long, inLibrary: Boolean, status: LibraryStatus? = null) {
        val now = clock()
        if (inLibrary) {
            database.execute(
                "UPDATE manga_series SET in_library = 1, added_at = COALESCE(added_at, ?), library_status = COALESCE(?, library_status, ?) WHERE id = ?",
                now, status?.key, LibraryStatus.PLAN.key, seriesId,
            )
        } else {
            database.transaction {
                execute("UPDATE manga_series SET in_library = 0, added_at = NULL, library_status = NULL, favorite = 0 WHERE id = ?", seriesId)
                execute("DELETE FROM manga_collection_items WHERE series_id = ?", seriesId)
            }
        }
        changed()
    }

    fun setLibraryStatus(seriesId: Long, status: LibraryStatus) {
        database.execute(
            "UPDATE manga_series SET library_status = ?, in_library = 1, added_at = COALESCE(added_at, ?) WHERE id = ?",
            status.key, clock(), seriesId,
        )
        changed()
    }

    /** Marking a favourite also puts the series in the library: favourites are a shelf of it. */
    fun setFavorite(seriesId: Long, favorite: Boolean) {
        if (favorite) {
            database.execute(
                "UPDATE manga_series SET favorite = 1, in_library = 1, added_at = COALESCE(added_at, ?), library_status = COALESCE(library_status, ?) WHERE id = ?",
                clock(), LibraryStatus.PLAN.key, seriesId,
            )
        } else {
            database.execute("UPDATE manga_series SET favorite = 0 WHERE id = ?", seriesId)
        }
        changed()
    }

    /** Removes a series with its chapters, progress, bookmarks and history. Files are the caller's business. */
    fun deleteSeries(seriesId: Long) {
        database.execute("DELETE FROM manga_series WHERE id = ?", seriesId)
        changed()
    }

    /** Rows that were only opened or previewed — not in the library, never read, nothing downloaded. */
    fun pruneUnused(profileId: Long, olderThanMs: Long): Int {
        val removed = database.execute(
            """
            DELETE FROM manga_series WHERE profile_id = ? AND in_library = 0 AND last_read_at IS NULL AND created_at < ?
              AND NOT EXISTS (SELECT 1 FROM manga_chapters c WHERE c.series_id = manga_series.id AND c.download_state IS NOT NULL)
              AND NOT EXISTS (SELECT 1 FROM manga_bookmarks b WHERE b.series_id = manga_series.id)
            """,
            profileId, clock() - olderThanMs,
        )
        if (removed > 0) changed()
        return removed
    }

    // ---- library queries ------------------------------------------------------------------------

    fun summary(seriesId: Long): SeriesSummary? {
        val profileId = series(seriesId)?.profileId ?: return null
        return database.queryOne("${summarySelect(profileId)} WHERE s.id = ?", seriesId, map = ::readSummary)
    }

    fun library(profileId: Long, filter: LibraryFilter = LibraryFilter()): List<SeriesSummary> {
        val where = ArrayList<String>()
        val args = ArrayList<Any?>()
        where += "s.profile_id = ?"; args += profileId
        where += "s.in_library = 1"
        filter.mediaType?.let { where += "s.media_type = ?"; args += it.key }
        filter.status?.let { where += "s.library_status = ?"; args += it.key }
        if (filter.favoritesOnly) where += "s.favorite = 1"
        if (filter.localOnly) where += "s.origin = 'local'"
        filter.publicationStatus?.let { where += "s.status = ?"; args += it }
        filter.collectionId?.let { where += "s.id IN (SELECT series_id FROM manga_collection_items WHERE collection_id = ?)"; args += it }
        val rows = database.query("${summarySelect(profileId)} WHERE ${where.joinToString(" AND ")}", *args.toTypedArray(), map = ::readSummary)
        val needle = titleKey(filter.query)
        val filtered = rows.asSequence()
            .filter { !filter.unreadOnly || it.unreadCount > 0 }
            .filter { !filter.downloadedOnly || it.downloadedCount > 0 }
            .filter { needle.isEmpty() || matches(it.series, needle) }
            .toList()
        val sorted = when (filter.sort) {
            LibrarySort.TITLE -> filtered.sortedBy { titleKey(it.series.title) }
            LibrarySort.DATE_ADDED -> filtered.sortedByDescending { it.series.addedAt ?: 0 }
            LibrarySort.RECENTLY_READ -> filtered.sortedByDescending { it.series.lastReadAt ?: 0 }
            LibrarySort.RECENTLY_UPDATED -> filtered.sortedByDescending { it.latestChapterAt ?: 0 }
            LibrarySort.PROGRESS -> filtered.sortedByDescending { it.progress }
            LibrarySort.STATUS -> filtered.sortedWith(compareBy<SeriesSummary> { it.series.status ?: "~" }.thenBy { titleKey(it.series.title) })
            LibrarySort.UNREAD -> filtered.sortedByDescending { it.unreadCount }
        }
        return if (filter.descending) sorted.reversed() else sorted
    }

    fun libraryCount(profileId: Long): Int =
        database.queryOne("SELECT COUNT(*) FROM manga_series WHERE profile_id = ? AND in_library = 1", profileId) { it.getInt(1) } ?: 0

    /** Library series that look like the same work (same title, ignoring case and punctuation), most copies first. */
    fun duplicates(profileId: Long): List<List<SeriesSummary>> = library(profileId)
        .groupBy { titleKey(it.series.title) }
        .values.filter { it.size > 1 }
        .sortedByDescending { it.size }

    /** Every row this profile has for a title: the editions/sources a reader can switch between. */
    fun editions(profileId: Long, title: String): List<SeriesSummary> = database.query(
        "${summarySelect(profileId)} WHERE s.profile_id = ? AND s.title_key = ?", profileId, titleKey(title), map = ::readSummary,
    )

    /** Series to pick up again, most recently read first, each with the chapter to open. */
    fun continueReading(profileId: Long, limit: Int = 20): List<ContinueEntry> {
        val rows = database.query(
            "${summarySelect(profileId)} WHERE s.profile_id = ? AND s.last_read_at IS NOT NULL ORDER BY s.last_read_at DESC LIMIT ?",
            profileId, limit * 3, map = ::readSummary,
        )
        val entries = ArrayList<ContinueEntry>()
        for (row in rows) {
            val chapter = resumeChapter(row.series) ?: continue
            entries += ContinueEntry(row, chapter)
            if (entries.size >= limit) break
        }
        return entries
    }

    /** The chapter to open for "Continue": the one in progress, else the next unread one, else null (finished). */
    fun resumeChapter(series: Series): Chapter? {
        val chapters = chapters(series.id)
        if (chapters.isEmpty()) return null
        val last = series.lastChapterId?.let { id -> chapters.firstOrNull { it.id == id } }
        if (last != null && !last.read) return last
        // The last chapter is finished: the next unread one after it, in the same language and group when there is one.
        var candidate = last
        while (candidate != null) {
            candidate = ChapterOrder.next(candidate, chapters)
            if (candidate != null && !candidate.read) return candidate
        }
        return chapters.firstOrNull { !it.read }
    }

    // ---- chapters -------------------------------------------------------------------------------

    /** Chapters in reading order (first chapter first), limited to the profile's chapter languages. */
    fun chapters(seriesId: Long, allLanguages: Boolean = false): List<Chapter> {
        val series = series(seriesId) ?: return emptyList()
        val all = database.query("SELECT * FROM manga_chapters WHERE series_id = ?", seriesId, map = ::readChapter)
        val languages = chapterLanguages(series.profileId)
        val visible = if (allLanguages || languages.isEmpty()) all else all.filter { it.language == null || it.language in languages || it.read || it.downloadState != null }
        return ChapterOrder.readingOrder(visible)
    }

    fun chapter(chapterId: Long): Chapter? =
        database.queryOne("SELECT * FROM manga_chapters WHERE id = ?", chapterId, map = ::readChapter)

    /**
     * Brings the stored chapter list in line with what the source lists now. Chapters are matched by
     * their source id, so progress, bookmarks and downloads stay with their chapter. A chapter the
     * source no longer lists is kept if it has been read or downloaded, and removed otherwise.
     * Returns the number of chapters that are new.
     */
    fun syncChapters(seriesId: Long, drafts: List<ChapterDraft>): Int {
        val now = clock()
        var added = 0
        database.transaction {
            val existing = query("SELECT id, source_id FROM manga_chapters WHERE series_id = ?", seriesId) { it.getString(2) to it.getLong(1) }.toMap()
            val seen = HashSet<String>()
            drafts.forEachIndexed { index, draft ->
                if (!seen.add(draft.sourceId)) return@forEachIndexed
                val id = existing[draft.sourceId]
                val sort = ChapterOrder.number(draft.chapterNumber)
                if (id == null) {
                    insert(
                        """
                        INSERT INTO manga_chapters (series_id, source_id, title, chapter_number, chapter_sort, volume_number, language, group_name,
                            released_at, position, page_count, first_seen_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        seriesId, draft.sourceId, draft.title, draft.chapterNumber, sort, draft.volumeNumber, draft.language, draft.groupName,
                        draft.releasedAt, index, draft.pageCount, now,
                    )
                    added++
                } else {
                    execute(
                        """
                        UPDATE manga_chapters SET title = ?, chapter_number = ?, chapter_sort = ?, volume_number = ?, language = ?, group_name = ?,
                            released_at = ?, position = ?, page_count = CASE WHEN ? > 0 THEN ? ELSE page_count END WHERE id = ?
                        """,
                        draft.title, draft.chapterNumber, sort, draft.volumeNumber, draft.language, draft.groupName, draft.releasedAt, index,
                        draft.pageCount, draft.pageCount, id,
                    )
                }
            }
            for ((sourceId, id) in existing) {
                if (sourceId in seen) continue
                execute("DELETE FROM manga_chapters WHERE id = ? AND read = 0 AND last_page = 0 AND download_state IS NULL", id)
            }
            execute("UPDATE manga_series SET chapters_refreshed_at = ? WHERE id = ?", now, seriesId)
        }
        changed()
        return added
    }

    /** Records where the reader is. [finished] marks the chapter read. */
    fun saveProgress(chapterId: Long, page: Int, pageCount: Int, scrollOffset: Float = 0f, finished: Boolean = false) {
        val chapter = chapter(chapterId) ?: return
        val series = series(chapter.seriesId) ?: return
        val now = clock()
        database.transaction {
            execute(
                """
                UPDATE manga_chapters SET last_page = ?, scroll_offset = ?, progress_at = ?,
                    page_count = CASE WHEN ? > 0 THEN ? ELSE page_count END,
                    read_at = CASE WHEN ? = 1 AND read = 0 THEN ? ELSE read_at END,
                    read = CASE WHEN ? = 1 THEN 1 ELSE read END
                WHERE id = ?
                """,
                page.coerceAtLeast(0), scrollOffset.coerceIn(0f, 1f).toDouble(), now, pageCount, pageCount,
                if (finished) 1 else 0, now, if (finished) 1 else 0, chapterId,
            )
            execute(
                """
                UPDATE manga_series SET last_read_at = ?, last_chapter_id = ?,
                    library_status = CASE WHEN in_library = 1 AND (library_status IS NULL OR library_status = 'plan') THEN 'reading' ELSE library_status END
                WHERE id = ?
                """,
                now, chapterId, series.id,
            )
            val latest = queryOne(
                "SELECT id, chapter_id, read_at FROM manga_history WHERE profile_id = ? ORDER BY read_at DESC, id DESC LIMIT 1", series.profileId,
            ) { Triple(it.getLong(1), it.getLong(2), it.getLong(3)) }
            if (latest != null && latest.second == chapterId && now - latest.third < HISTORY_MERGE_MS) {
                execute("UPDATE manga_history SET page = ?, read_at = ? WHERE id = ?", page, now, latest.first)
            } else {
                insert("INSERT INTO manga_history (profile_id, series_id, chapter_id, page, read_at) VALUES (?, ?, ?, ?, ?)", series.profileId, series.id, chapterId, page, now)
            }
        }
        changed()
    }

    fun setPageCount(chapterId: Long, pageCount: Int) {
        database.execute("UPDATE manga_chapters SET page_count = ? WHERE id = ? AND page_count <> ?", pageCount, chapterId, pageCount)
    }

    /** Marks chapters read or unread. Unread also forgets the page reached. */
    fun setRead(chapterIds: Collection<Long>, read: Boolean) {
        if (chapterIds.isEmpty()) return
        val now = clock()
        database.transaction {
            for (id in chapterIds) {
                if (read) {
                    execute("UPDATE manga_chapters SET read = 1, read_at = COALESCE(read_at, ?) WHERE id = ?", now, id)
                } else {
                    execute("UPDATE manga_chapters SET read = 0, read_at = NULL, last_page = 0, scroll_offset = 0 WHERE id = ?", id)
                }
            }
        }
        changed()
    }

    fun setAllRead(seriesId: Long, read: Boolean) = setRead(chapters(seriesId).map { it.id }, read)

    // ---- history and bookmarks ------------------------------------------------------------------

    fun history(profileId: Long, limit: Int = 100): List<HistoryEntry> {
        val rows = database.query(
            "SELECT id, series_id, chapter_id, page, read_at FROM manga_history WHERE profile_id = ? ORDER BY read_at DESC, id DESC LIMIT ?", profileId, limit,
        ) { longArrayOf(it.getLong(1), it.getLong(2), it.getLong(3), it.getLong(4), it.getLong(5)) }
        return rows.mapNotNull { row ->
            val series = series(row[1]) ?: return@mapNotNull null
            val chapter = chapter(row[2]) ?: return@mapNotNull null
            HistoryEntry(row[0], series, chapter, row[3].toInt(), row[4])
        }
    }

    fun clearHistory(profileId: Long) {
        database.execute("DELETE FROM manga_history WHERE profile_id = ?", profileId)
        changed()
    }

    fun bookmarks(seriesId: Long): List<Bookmark> = database.query(
        "SELECT * FROM manga_bookmarks WHERE series_id = ? ORDER BY created_at DESC", seriesId, map = ::readBookmark,
    )

    fun bookmark(chapterId: Long, page: Int): Bookmark? = database.queryOne(
        "SELECT * FROM manga_bookmarks WHERE chapter_id = ? AND page = ?", chapterId, page, map = ::readBookmark,
    )

    /** Adds a bookmark on a page, or removes the one that is there. Returns true when the page is bookmarked afterwards. */
    fun toggleBookmark(chapterId: Long, page: Int, note: String = ""): Boolean {
        val chapter = chapter(chapterId) ?: return false
        val series = series(chapter.seriesId) ?: return false
        val existing = bookmark(chapterId, page)
        if (existing != null) {
            database.execute("DELETE FROM manga_bookmarks WHERE id = ?", existing.id)
        } else {
            database.insert(
                "INSERT INTO manga_bookmarks (profile_id, series_id, chapter_id, page, note, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                series.profileId, series.id, chapterId, page, note, clock(),
            )
        }
        changed()
        return existing == null
    }

    fun removeBookmark(bookmarkId: Long) {
        database.execute("DELETE FROM manga_bookmarks WHERE id = ?", bookmarkId)
        changed()
    }

    // ---- collections ----------------------------------------------------------------------------

    fun collections(profileId: Long): List<MangaCollection> = database.query(
        """
        SELECT c.id, c.name, c.created_at, (SELECT COUNT(*) FROM manga_collection_items i WHERE i.collection_id = c.id) AS items
        FROM manga_collections c WHERE c.profile_id = ? ORDER BY c.name COLLATE NOCASE
        """,
        profileId,
    ) { MangaCollection(it.getLong(1), it.getString(2), it.getLong(3), it.getInt(4)) }

    fun createCollection(profileId: Long, name: String): MangaCollection {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A collection needs a name." }
        require(collections(profileId).none { it.name.equals(trimmed, ignoreCase = true) }) { "There is already a collection called \"$trimmed\"." }
        val id = database.insert("INSERT INTO manga_collections (profile_id, name, created_at) VALUES (?, ?, ?)", profileId, trimmed, clock())
        changed()
        return MangaCollection(id, trimmed, clock(), 0)
    }

    fun renameCollection(collectionId: Long, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A collection needs a name." }
        database.execute("UPDATE manga_collections SET name = ? WHERE id = ?", trimmed, collectionId)
        changed()
    }

    fun deleteCollection(collectionId: Long) {
        database.execute("DELETE FROM manga_collections WHERE id = ?", collectionId)
        changed()
    }

    /** Adds to or removes from a collection. Adding also puts the series in the library. */
    fun setInCollection(collectionId: Long, seriesId: Long, member: Boolean) {
        if (member) {
            database.transaction {
                execute("INSERT OR IGNORE INTO manga_collection_items (collection_id, series_id, added_at) VALUES (?, ?, ?)", collectionId, seriesId, clock())
                execute(
                    "UPDATE manga_series SET in_library = 1, added_at = COALESCE(added_at, ?), library_status = COALESCE(library_status, ?) WHERE id = ?",
                    clock(), LibraryStatus.PLAN.key, seriesId,
                )
            }
        } else {
            database.execute("DELETE FROM manga_collection_items WHERE collection_id = ? AND series_id = ?", collectionId, seriesId)
        }
        changed()
    }

    fun collectionsOf(seriesId: Long): Set<Long> =
        database.query("SELECT collection_id FROM manga_collection_items WHERE series_id = ?", seriesId) { it.getLong(1) }.toSet()

    // ---- sources --------------------------------------------------------------------------------

    fun sourcePreferences(profileId: Long): Map<String, SourcePreference> = database.query(
        "SELECT extension_id, enabled, preferred_rank FROM manga_source_prefs WHERE profile_id = ?", profileId,
    ) { SourcePreference(it.getString(1), it.getInt(2) != 0, it.getInt(3).takeIf { _ -> !it.wasNull() }) }.associateBy { it.extensionId }

    fun setSourceEnabled(profileId: Long, extensionId: String, enabled: Boolean) {
        database.execute(
            """
            INSERT INTO manga_source_prefs (profile_id, extension_id, enabled) VALUES (?, ?, ?)
            ON CONFLICT(profile_id, extension_id) DO UPDATE SET enabled = excluded.enabled
            """,
            profileId, extensionId, if (enabled) 1 else 0,
        )
        changed()
    }

    /** The preferred sources in order; a source not in the list stops being preferred. */
    fun setPreferredSources(profileId: Long, extensionIds: List<String>) {
        database.transaction {
            execute("UPDATE manga_source_prefs SET preferred_rank = NULL WHERE profile_id = ?", profileId)
            extensionIds.distinct().forEachIndexed { index, id ->
                execute(
                    """
                    INSERT INTO manga_source_prefs (profile_id, extension_id, enabled, preferred_rank) VALUES (?, ?, 1, ?)
                    ON CONFLICT(profile_id, extension_id) DO UPDATE SET preferred_rank = excluded.preferred_rank
                    """,
                    profileId, id, index,
                )
            }
        }
        changed()
    }

    fun sourceHealth(): Map<String, SourceHealth> = database.query("SELECT extension_id, tested_at, ok, steps, message FROM manga_source_health") { rs ->
        val steps = runCatching { json.parseToJsonElement(rs.getString(4)) as JsonArray }.getOrNull().orEmpty().mapNotNull { element ->
            val step = element as? JsonObject ?: return@mapNotNull null
            HealthStep(
                (step["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (step["ok"] as? JsonPrimitive)?.booleanOrNull ?: false,
                (step["detail"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
        SourceHealth(rs.getString(1), rs.getLong(2), rs.getInt(3) != 0, steps, rs.getString(5))
    }.associateBy { it.extensionId }

    fun saveSourceHealth(health: SourceHealth) {
        val steps = buildJsonArray {
            health.steps.forEach { step ->
                add(buildJsonObject { put("name", step.name); put("ok", step.ok); put("detail", step.detail) })
            }
        }
        database.execute(
            """
            INSERT INTO manga_source_health (extension_id, tested_at, ok, steps, message) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(extension_id) DO UPDATE SET tested_at = excluded.tested_at, ok = excluded.ok, steps = excluded.steps, message = excluded.message
            """,
            health.extensionId, health.testedAt, if (health.ok) 1 else 0, steps.toString(), health.message,
        )
        changed()
    }

    // ---- metadata links -------------------------------------------------------------------------

    fun metadataLinks(seriesId: Long): List<MetadataLink> = database.query(
        "SELECT series_id, provider, remote_id, url, linked_at, tracked FROM manga_metadata_links WHERE series_id = ?", seriesId,
    ) { MetadataLink(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5), it.getInt(6) != 0) }

    fun linkMetadata(seriesId: Long, provider: String, remoteId: String, url: String?) {
        database.execute(
            """
            INSERT INTO manga_metadata_links (series_id, provider, remote_id, url, linked_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(series_id, provider) DO UPDATE SET remote_id = excluded.remote_id, url = excluded.url, linked_at = excluded.linked_at
            """,
            seriesId, provider, remoteId, url, clock(),
        )
        changed()
    }

    fun unlinkMetadata(seriesId: Long, provider: String) {
        database.execute("DELETE FROM manga_metadata_links WHERE series_id = ? AND provider = ?", seriesId, provider)
        changed()
    }

    fun setTracked(seriesId: Long, provider: String, tracked: Boolean) {
        database.execute("UPDATE manga_metadata_links SET tracked = ? WHERE series_id = ? AND provider = ?", if (tracked) 1 else 0, seriesId, provider)
        changed()
    }

    // ---- downloads ------------------------------------------------------------------------------

    fun queueDownloads(chapterIds: Collection<Long>) {
        val now = clock()
        database.transaction {
            chapterIds.forEachIndexed { index, id ->
                execute(
                    """
                    UPDATE manga_chapters SET download_state = 'queued', download_error = NULL, download_queued_at = ?
                    WHERE id = ? AND (download_state IS NULL OR download_state = 'failed')
                    """,
                    now + index, id,
                )
            }
        }
        changed()
    }

    fun nextQueuedDownload(): Chapter? = database.queryOne(
        "SELECT * FROM manga_chapters WHERE download_state = 'queued' ORDER BY download_queued_at, id LIMIT 1", map = ::readChapter,
    )

    /** Sets a chapter's download state. Null forgets the download altogether. */
    fun setDownloadState(chapterId: Long, state: DownloadState?, dir: String? = null, pages: Int? = null, error: String? = null) {
        if (state == null) {
            database.execute(
                "UPDATE manga_chapters SET download_state = NULL, download_dir = NULL, download_pages = 0, download_error = NULL, download_queued_at = NULL WHERE id = ?",
                chapterId,
            )
        } else {
            database.execute(
                "UPDATE manga_chapters SET download_state = ?, download_dir = COALESCE(?, download_dir), download_pages = COALESCE(?, download_pages), download_error = ? WHERE id = ?",
                state.key, dir, pages, error, chapterId,
            )
        }
        changed()
    }

    fun setDownloadProgress(chapterId: Long, pages: Int, total: Int) {
        database.execute(
            "UPDATE manga_chapters SET download_pages = ?, page_count = CASE WHEN ? > 0 THEN ? ELSE page_count END WHERE id = ?",
            pages, total, total, chapterId,
        )
        changed()
    }

    /** Downloads that were running when the app closed go back to the queue. */
    fun requeueInterruptedDownloads(): Int {
        val count = database.execute("UPDATE manga_chapters SET download_state = 'queued' WHERE download_state = 'running'")
        if (count > 0) changed()
        return count
    }

    /** Every chapter of this profile that is queued, running, downloaded or failed, newest request first. */
    fun downloads(profileId: Long): List<Pair<Series, Chapter>> {
        val chapters = database.query(
            """
            SELECT c.* FROM manga_chapters c JOIN manga_series s ON s.id = c.series_id
            WHERE s.profile_id = ? AND c.download_state IS NOT NULL ORDER BY COALESCE(c.download_queued_at, 0) DESC, c.id DESC
            """,
            profileId, map = ::readChapter,
        )
        val seriesById = HashMap<Long, Series?>()
        return chapters.mapNotNull { chapter -> seriesById.getOrPut(chapter.seriesId) { series(chapter.seriesId) }?.let { it to chapter } }
    }

    // ---- profiles -------------------------------------------------------------------------------

    /** Erases everything a deleted profile owned. Returns the folders of its downloads, for the caller to delete. */
    fun deleteProfile(profileId: Long): List<String> {
        val dirs = database.query(
            "SELECT c.download_dir FROM manga_chapters c JOIN manga_series s ON s.id = c.series_id WHERE s.profile_id = ? AND c.download_dir IS NOT NULL", profileId,
        ) { it.getString(1) }
        database.transaction {
            execute("DELETE FROM manga_series WHERE profile_id = ?", profileId)
            execute("DELETE FROM manga_collections WHERE profile_id = ?", profileId)
            execute("DELETE FROM manga_source_prefs WHERE profile_id = ?", profileId)
            execute("DELETE FROM manga_history WHERE profile_id = ?", profileId)
            execute("DELETE FROM manga_bookmarks WHERE profile_id = ?", profileId)
        }
        chapterLanguages.remove(profileId)
        changed()
        return dirs
    }

    // ---- reading rows ---------------------------------------------------------------------------

    private fun summarySelect(profileId: Long): String {
        val languages = chapterLanguages(profileId)
        val clause = if (languages.isEmpty()) "" else {
            val list = languages.joinToString(",") { "'" + it.replace("'", "''") + "'" }
            " AND (c.language IS NULL OR c.language IN ($list) OR c.read = 1 OR c.download_state IS NOT NULL)"
        }
        return """
            SELECT s.*,
                (SELECT COUNT(*) FROM manga_chapters c WHERE c.series_id = s.id$clause) AS chapter_count,
                (SELECT COUNT(*) FROM manga_chapters c WHERE c.series_id = s.id AND c.read = 1$clause) AS read_count,
                (SELECT COUNT(*) FROM manga_chapters c WHERE c.series_id = s.id AND c.download_state = 'done') AS downloaded_count,
                (SELECT MAX(CASE WHEN c.released_at > 0 THEN c.released_at ELSE c.first_seen_at END) FROM manga_chapters c WHERE c.series_id = s.id$clause) AS latest_chapter_at
            FROM manga_series s
        """.trimIndent()
    }

    private fun readSummary(rs: ResultSet): SeriesSummary = SeriesSummary(
        series = readSeries(rs),
        chapterCount = rs.getInt("chapter_count"),
        readCount = rs.getInt("read_count"),
        downloadedCount = rs.getInt("downloaded_count"),
        latestChapterAt = rs.getLong("latest_chapter_at").takeIf { !rs.wasNull() },
    )

    private fun readSeries(rs: ResultSet): Series = Series(
        id = rs.getLong("id"),
        profileId = rs.getLong("profile_id"),
        origin = SeriesOrigin.fromKey(rs.getString("origin")),
        extensionId = rs.getString("extension_id"),
        sourceId = rs.getString("source_id"),
        sourceName = rs.getString("source_name"),
        title = rs.getString("title"),
        altTitles = decode(rs.getString("alt_titles")),
        description = rs.getString("description"),
        authors = decode(rs.getString("authors")),
        artists = decode(rs.getString("artists")),
        tags = decode(rs.getString("tags")),
        customTags = decode(rs.getString("custom_tags")),
        status = rs.getString("status"),
        originalLanguage = rs.getString("original_language"),
        mediaType = MediaType.fromKey(rs.getString("media_type")),
        coverUrl = rs.getString("cover_url"),
        coverFile = rs.getString("cover_file"),
        bannerUrl = rs.getString("banner_url"),
        lockedFields = rs.getString("locked_fields").split(',').filter { it.isNotBlank() }.toSet(),
        sourceRaw = rs.getString("source_raw"),
        inLibrary = rs.getInt("in_library") != 0,
        libraryStatus = LibraryStatus.fromKey(rs.getString("library_status")),
        favorite = rs.getInt("favorite") != 0,
        addedAt = rs.getLong("added_at").takeIf { !rs.wasNull() },
        createdAt = rs.getLong("created_at"),
        updatedAt = rs.getLong("updated_at"),
        chaptersRefreshedAt = rs.getLong("chapters_refreshed_at").takeIf { !rs.wasNull() },
        lastReadAt = rs.getLong("last_read_at").takeIf { !rs.wasNull() },
        lastChapterId = rs.getLong("last_chapter_id").takeIf { !rs.wasNull() },
        readerOverrides = rs.getString("reader_overrides"),
    )

    private fun readChapter(rs: ResultSet): Chapter = Chapter(
        id = rs.getLong("id"),
        seriesId = rs.getLong("series_id"),
        sourceId = rs.getString("source_id"),
        title = rs.getString("title"),
        chapterNumber = rs.getString("chapter_number"),
        chapterSort = rs.getDouble("chapter_sort").takeIf { !rs.wasNull() },
        volumeNumber = rs.getString("volume_number"),
        language = rs.getString("language"),
        groupName = rs.getString("group_name"),
        releasedAt = rs.getLong("released_at"),
        position = rs.getInt("position"),
        pageCount = rs.getInt("page_count"),
        lastPage = rs.getInt("last_page"),
        scrollOffset = rs.getDouble("scroll_offset").toFloat(),
        read = rs.getInt("read") != 0,
        readAt = rs.getLong("read_at").takeIf { !rs.wasNull() },
        progressAt = rs.getLong("progress_at").takeIf { !rs.wasNull() },
        firstSeenAt = rs.getLong("first_seen_at"),
        downloadState = DownloadState.fromKey(rs.getString("download_state")),
        downloadDir = rs.getString("download_dir"),
        downloadPages = rs.getInt("download_pages"),
        downloadError = rs.getString("download_error"),
    )

    private fun readBookmark(rs: ResultSet): Bookmark = Bookmark(
        id = rs.getLong("id"),
        seriesId = rs.getLong("series_id"),
        chapterId = rs.getLong("chapter_id"),
        page = rs.getInt("page"),
        note = rs.getString("note"),
        createdAt = rs.getLong("created_at"),
    )

    private fun matches(series: Series, needle: String): Boolean =
        titleKey(series.title).contains(needle) ||
            series.altTitles.any { titleKey(it).contains(needle) } ||
            series.authors.any { titleKey(it).contains(needle) } ||
            (series.tags + series.customTags).any { titleKey(it).contains(needle) }

    private fun clean(values: List<String>): List<String> = values.map(String::trim).filter(String::isNotEmpty).distinct()

    private fun encode(values: List<String>): String = json.encodeToString(stringList, values)

    private fun decode(text: String?): List<String> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(stringList, text) }.getOrDefault(emptyList())

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

    companion object {
        private const val HISTORY_MERGE_MS = 30 * 60 * 1000L

        /** A title reduced to what matters for sorting, searching and spotting duplicates. */
        fun titleKey(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
    }
}

/** Decides which shelf a series belongs on when nobody has said otherwise. */
object MangaClassifier {
    private val webtoonLanguages = setOf("KOREAN", "CHINESE_SIMP", "CHINESE_TRAD")
    private val webtoonTags = setOf("manhwa", "manhua", "webtoon", "webtoons", "long strip", "web comic", "full color")

    /**
     * Korean and Chinese originals, and anything a source tags as a webtoon or long strip, go to
     * Manhwa; everything else to Manga. It is a starting point: the reader can move a series, and
     * that choice is then locked.
     */
    fun classify(originalLanguage: String?, tags: List<String>): MediaType = when {
        originalLanguage in webtoonLanguages -> MediaType.MANHWA
        tags.any { it.trim().lowercase() in webtoonTags } -> MediaType.MANHWA
        else -> MediaType.MANGA
    }
}

/** Reading order of chapters and "what comes next". */
object ChapterOrder {
    fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

    /** First chapter first. Numbered chapters by number (then volume), unnumbered ones after them in the source's order. */
    fun readingOrder(chapters: List<Chapter>): List<Chapter> {
        val numbered = chapters.filter { it.chapterSort != null }
        val unnumbered = chapters.filter { it.chapterSort == null }
        if (numbered.isEmpty()) {
            // Sources list newest first; a folder lists first chapter first. Position alone cannot tell
            // which, so local series (which set page counts and positions in file order) keep position order.
            return unnumbered.sortedBy { it.position }
        }
        return numbered.sortedWith(compareBy<Chapter> { it.chapterSort }.thenBy { number(it.volumeNumber) ?: Double.MAX_VALUE }.thenBy { it.groupName }.thenBy { it.id }) +
            unnumbered.sortedBy { it.position }
    }

    /** The chapter after [current]: the next number, preferring the same language and group. */
    fun next(current: Chapter, chapters: List<Chapter>): Chapter? = neighbour(current, chapters, forward = true)

    fun previous(current: Chapter, chapters: List<Chapter>): Chapter? = neighbour(current, chapters, forward = false)

    private fun neighbour(current: Chapter, chapters: List<Chapter>, forward: Boolean): Chapter? {
        val ordered = readingOrder(chapters)
        val index = ordered.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        val sort = current.chapterSort
        if (sort == null) return ordered.getOrNull(if (forward) index + 1 else index - 1)
        val candidates = (if (forward) ordered.drop(index + 1) else ordered.take(index).reversed())
            .filter { it.chapterSort != null && it.chapterSort != sort }
        val target = candidates.firstOrNull()?.chapterSort
            ?: return (if (forward) ordered.drop(index + 1) else ordered.take(index).reversed()).firstOrNull { it.chapterSort == null }
        val same = candidates.filter { it.chapterSort == target }
        return same.firstOrNull { it.language == current.language && it.groupName == current.groupName }
            ?: same.firstOrNull { it.language == current.language }
            ?: same.first()
    }
}
