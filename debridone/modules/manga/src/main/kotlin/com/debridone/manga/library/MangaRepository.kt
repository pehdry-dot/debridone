package com.debridone.manga.library

import com.debridone.manga.suwayomi.MangaServer
import com.debridone.manga.suwayomi.SuwayomiApi
import com.debridone.manga.suwayomi.SwCategory
import com.debridone.manga.suwayomi.SwChapter
import com.debridone.manga.suwayomi.SwDownload
import com.debridone.manga.suwayomi.SwDownloadStatus
import com.debridone.manga.suwayomi.SwManga
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The library as the Manga screens see it. Suwayomi-Server is the system of record: which series
 * are in the library, their categories, chapters, what was read and how far, what is downloaded.
 * This class asks the server, remembers the answers for the session, and changes them through the
 * server. What Suwayomi has no field for (reading status, favourites, details typed by hand, page
 * bookmarks) is kept in Suwayomi's own per-series key-value store; see [MetaKeys].
 *
 * Every function blocks on the server: call them off the UI thread. Lookups of one thing return
 * null when it does not exist or the server is not reachable; functions that list or change things
 * throw [com.debridone.manga.suwayomi.SuwayomiException] with a sentence for the person.
 *
 * There is one server per profile and only the active profile's is connected, so the `profileId`
 * parameters only guard against a screen that still belongs to the previous profile.
 */
class MangaRepository(
    private val server: MangaServer,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _revision = MutableStateFlow(0L)

    /** Changes whenever something the screens show may have changed. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    internal fun changed() = _revision.update { it + 1 }

    private val mangas = ConcurrentHashMap<Int, SwManga>()
    private val chapterLists = ConcurrentHashMap<Int, List<SwChapter>>()
    private val chapterOwner = ConcurrentHashMap<Int, Int>()
    private val libraryLock = Any()

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var categories: List<SwCategory>? = null

    @Volatile
    private var globals: Map<String, String>? = null

    @Volatile
    private var queue: Map<Int, SwDownload> = emptyMap()

    init {
        // Another server (another profile, a restart, a remote one coming back): nothing remembered holds.
        scope.launch {
            server.generation.drop(1).collect {
                forgetEverything()
                changed()
            }
        }
    }

    private fun forgetEverything() {
        synchronized(libraryLock) {
            mangas.clear()
            chapterLists.clear()
            chapterOwner.clear()
            libraryLoaded = false
            categories = null
            globals = null
            queue = emptyMap()
        }
    }

    private val api: SuwayomiApi get() = server.api()

    private fun active(profileId: Long): Boolean = server.profileId == profileId

    private fun mapper(): SeriesMapper = SeriesMapper(server.profileId ?: 0, historyCutoff())

    // ---- what is remembered ---------------------------------------------------------------------

    internal fun remember(manga: SwManga): SwManga {
        mangas[manga.id] = manga
        return manga
    }

    internal fun remember(list: Collection<SwManga>) = list.forEach { mangas[it.id] = it }

    internal fun rememberChapters(mangaId: Int, chapters: List<SwChapter>) {
        chapterLists[mangaId] = chapters.sortedBy { it.sourceOrder }
        chapters.forEach { chapterOwner[it.id] = mangaId }
    }

    internal fun raw(seriesId: Long): SwManga? {
        val id = seriesId.toInt()
        return mangas[id] ?: runCatching { api.manga(id) }.getOrNull()?.let(::remember)
    }

    internal fun rawChapters(mangaId: Int): List<SwChapter> = chapterLists[mangaId] ?: api.chapters(mangaId).also { rememberChapters(mangaId, it) }.let { chapterLists.getValue(mangaId) }

    internal fun rawChapter(chapterId: Long): SwChapter? {
        val id = chapterId.toInt()
        val owner = chapterOwner[id] ?: runCatching { api.chapter(id) }.getOrNull()?.mangaId ?: return null
        return runCatching { rawChapters(owner) }.getOrNull()?.firstOrNull { it.id == id }
    }

    /** Asks the server again for these series (their counts change when chapters are read or downloaded). */
    internal fun refreshMangas(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        if (ids.size == 1) remember(api.manga(ids.first())) else remember(api.mangas(ids))
    }

    internal fun reloadChapters(mangaId: Int) {
        rememberChapters(mangaId, api.chapters(mangaId))
    }

    private fun replaceChapters(updated: Collection<SwChapter>) {
        for ((mangaId, changed) in updated.groupBy { it.mangaId }) {
            val byId = changed.associateBy { it.id }
            chapterLists.computeIfPresent(mangaId) { _, list -> list.map { byId[it.id] ?: it } }
        }
    }

    /** The download queue as the server last reported it. Returns true when it differs from before. */
    internal fun setQueue(status: SwDownloadStatus): Boolean {
        val next = status.queue.associateBy { it.chapterId }
        val differs = next != queue
        queue = next
        return differs
    }

    internal fun queued(): Map<Int, SwDownload> = queue

    /** Loads the library from the server again: after it looked for new chapters, or on request. */
    fun reloadLibrary() {
        synchronized(libraryLock) {
            val fresh = api.library()
            val ids = fresh.map { it.id }.toSet()
            // A series that left the library elsewhere keeps its entry, as a series outside the library.
            mangas.values.filter { it.inLibrary && it.id !in ids }.forEach { mangas[it.id] = it.copy(inLibrary = false) }
            remember(fresh)
            categories = null
            libraryLoaded = true
        }
        changed()
    }

    private fun libraryMangas(): List<SwManga> {
        if (!libraryLoaded) {
            synchronized(libraryLock) {
                if (!libraryLoaded) {
                    remember(api.library())
                    libraryLoaded = true
                }
            }
        }
        return mangas.values.filter { it.inLibrary }
    }

    private fun globalMeta(): Map<String, String> = globals ?: runCatching { api.globalMeta() }.getOrNull()?.also { globals = it } ?: emptyMap()

    private fun historyCutoff(): Long = globalMeta()[MetaKeys.HISTORY_CUTOFF]?.toLongOrNull() ?: 0

    private fun categoryList(): List<SwCategory> = categories ?: api.categories().also { categories = it }

    // ---- series ---------------------------------------------------------------------------------

    fun series(seriesId: Long): Series? = raw(seriesId)?.let(mapper()::series)

    fun summary(seriesId: Long): SeriesSummary? = raw(seriesId)?.let(mapper()::summary)

    /** A series the server already knows by its source and its address there. */
    fun seriesBySource(profileId: Long, extensionId: String, sourceId: String): Series? {
        if (!active(profileId)) return null
        mangas.values.firstOrNull { it.sourceId == extensionId && it.url == sourceId }?.let { return mapper().series(it) }
        return runCatching { api.mangasByUrl(extensionId, sourceId) }.getOrNull()?.firstOrNull()?.let { mapper().series(remember(it)) }
    }

    private fun writeMeta(manga: SwManga, key: String, value: String?): SwManga {
        val current = manga.meta[key]
        if (current == value) return manga
        if (value == null) api.deleteMangaMeta(manga.id, key) else api.setMangaMeta(manga.id, key, value)
        val latest = mangas[manga.id] ?: manga
        return remember(latest.copy(meta = if (value == null) latest.meta - key else latest.meta + (key to value)))
    }

    private fun writeState(manga: SwManga, state: SeriesState): SwManga =
        writeMeta(manga, MetaKeys.STATE, MetaCodec.encode(state).takeIf { state != SeriesState() })

    private fun writeOverlay(manga: SwManga, overlay: SeriesOverlay): SwManga =
        writeMeta(manga, MetaKeys.OVERLAY, MetaCodec.encode(overlay).takeIf { overlay != SeriesOverlay() })

    private fun need(seriesId: Long): SwManga = raw(seriesId) ?: throw IllegalStateException("This series is no longer known to the manga server.")

    /** What the series' own source says about it, as Suwayomi has it now. */
    fun sourceDetails(seriesId: Long): SeriesDetails? {
        val manga = raw(seriesId) ?: return null
        return SeriesDetails(
            title = manga.title.takeIf { it.isNotBlank() },
            description = manga.description,
            authors = SeriesMapper.people(manga.author).takeIf { it.isNotEmpty() },
            artists = SeriesMapper.people(manga.artist).takeIf { it.isNotEmpty() },
            tags = manga.genres.takeIf { it.isNotEmpty() },
            status = SeriesMapper.publicationStatus(manga.status),
            coverUrl = SeriesMapper.sourceCover(manga),
        )
    }

    /**
     * Applies details from a metadata provider (or the merged offer of several). Fields the person
     * typed by hand are left alone, and so is anything the details say nothing about. A value that
     * is the source's own is not copied: the field then simply follows the source again. Returns
     * the fields whose shown value changed.
     */
    fun applyDetails(
        seriesId: Long,
        details: SeriesDetails,
        notify: Boolean = true,
        overwriteWithEmpty: Boolean = false,
        /** Only fill in what the series does not show yet. */
        onlyFillEmpty: Boolean = false,
    ): Set<String> {
        val manga = raw(seriesId) ?: return emptySet()
        val before = mapper().series(manga)
        val overlay = MetaCodec.overlay(manga.meta)
        val source = sourceDetails(seriesId) ?: SeriesDetails()
        val skip: Set<String> = before.lockedFields + if (!onlyFillEmpty) emptySet() else buildSet {
            if (before.title.isNotBlank()) add(SeriesField.TITLE)
            if (before.altTitles.isNotEmpty()) add(SeriesField.ALT_TITLES)
            if (before.description.isNotBlank()) add(SeriesField.DESCRIPTION)
            if (before.authors.isNotEmpty()) add(SeriesField.AUTHORS)
            if (before.artists.isNotEmpty()) add(SeriesField.ARTISTS)
            if (before.tags.isNotEmpty()) add(SeriesField.TAGS)
            if (before.status != null) add(SeriesField.STATUS)
            if (before.originalLanguage != null) add(SeriesField.LANGUAGE)
            if (before.coverUrl.isNotBlank()) add(SeriesField.COVER)
            add(SeriesField.MEDIA_TYPE)
        }
        fun text(field: String, value: String?, fromSource: String?, current: String?): String? {
            val offered = value?.trim()?.takeIf { it.isNotEmpty() || overwriteWithEmpty }
            return if (field in skip || offered == null) current else offered.takeIf { it != fromSource?.trim() }
        }
        fun list(field: String, value: List<String>?, fromSource: List<String>?, current: List<String>?): List<String>? {
            val offered = value?.let(::clean)?.takeIf { it.isNotEmpty() || overwriteWithEmpty }
            return if (field in skip || offered == null) current else offered.takeIf { it != fromSource.orEmpty() }
        }
        val next = overlay.copy(
            title = text(SeriesField.TITLE, details.title?.takeIf { it.isNotBlank() }, source.title, overlay.title),
            altTitles = list(SeriesField.ALT_TITLES, details.altTitles, null, overlay.altTitles),
            description = text(SeriesField.DESCRIPTION, details.description, source.description, overlay.description),
            authors = list(SeriesField.AUTHORS, details.authors, source.authors, overlay.authors),
            artists = list(SeriesField.ARTISTS, details.artists, source.artists, overlay.artists),
            tags = list(SeriesField.TAGS, details.tags, source.tags, overlay.tags),
            status = text(SeriesField.STATUS, details.status?.takeIf { it.isNotBlank() }, source.status, overlay.status),
            statusCleared = overlay.statusCleared && (SeriesField.STATUS in skip || details.status.isNullOrBlank()),
            originalLanguage = text(SeriesField.LANGUAGE, details.originalLanguage?.takeIf { it.isNotBlank() }, null, overlay.originalLanguage),
            coverUrl = text(SeriesField.COVER, details.coverUrl?.takeIf { it.isNotBlank() }, source.coverUrl, overlay.coverUrl),
            bannerUrl = details.bannerUrl?.takeIf { it.isNotBlank() && !(onlyFillEmpty && overlay.bannerUrl != null) } ?: overlay.bannerUrl,
        )
        var latest = writeOverlay(manga, next)
        val type = details.mediaType
        if (type != null && SeriesField.MEDIA_TYPE !in skip) {
            val state = MetaCodec.state(latest.meta)
            if (state.mediaType != type.key) latest = writeState(latest, state.copy(mediaType = type.key))
        }
        val after = mapper().series(latest)
        val fields = changedFields(before, after)
        if (fields.isNotEmpty() && notify) changed()
        return fields
    }

    private fun changedFields(before: Series, after: Series): Set<String> = buildSet {
        if (before.title != after.title) add(SeriesField.TITLE)
        if (before.altTitles != after.altTitles) add(SeriesField.ALT_TITLES)
        if (before.description != after.description) add(SeriesField.DESCRIPTION)
        if (before.authors != after.authors) add(SeriesField.AUTHORS)
        if (before.artists != after.artists) add(SeriesField.ARTISTS)
        if (before.tags != after.tags) add(SeriesField.TAGS)
        if (before.status != after.status) add(SeriesField.STATUS)
        if (before.originalLanguage != after.originalLanguage) add(SeriesField.LANGUAGE)
        if (before.coverUrl != after.coverUrl || before.coverFile != after.coverFile) add(SeriesField.COVER)
        if (before.mediaType != after.mediaType) add(SeriesField.MEDIA_TYPE)
    }

    /** Applies changes made by hand. With [lock], every edited field is protected from later refreshes. */
    fun editSeries(seriesId: Long, edit: SeriesEdit, lock: Boolean = true) {
        val manga = raw(seriesId) ?: return
        val overlay = MetaCodec.overlay(manga.meta)
        val next = overlay.copy(
            title = edit.title?.trim()?.takeIf { it.isNotEmpty() } ?: overlay.title,
            altTitles = edit.altTitles?.let(::clean) ?: overlay.altTitles,
            description = edit.description?.trim() ?: overlay.description,
            authors = edit.authors?.let(::clean) ?: overlay.authors,
            artists = edit.artists?.let(::clean) ?: overlay.artists,
            tags = edit.tags?.let(::clean) ?: overlay.tags,
            status = if (edit.clearStatus) null else edit.status ?: overlay.status,
            statusCleared = if (edit.clearStatus) true else if (edit.status != null) false else overlay.statusCleared,
            originalLanguage = edit.originalLanguage?.let { it.ifBlank { null } } ?: overlay.originalLanguage.takeIf { edit.originalLanguage == null },
            coverFile = edit.coverFile?.let { it.ifBlank { null } } ?: overlay.coverFile.takeIf { edit.coverFile == null },
            coverUrl = edit.coverUrl ?: overlay.coverUrl,
            locked = if (lock) (overlay.locked + edit.touchedFields()).distinct().sorted() else overlay.locked,
        )
        var latest = writeOverlay(manga, next)
        if (edit.customTags != null || edit.mediaType != null) {
            val state = MetaCodec.state(latest.meta)
            latest = writeState(latest, state.copy(customTags = edit.customTags?.let(::clean) ?: state.customTags, mediaType = edit.mediaType?.key ?: state.mediaType))
        }
        changed()
    }

    fun setFieldLocked(seriesId: Long, field: String, locked: Boolean) {
        if (field !in SeriesField.all) return
        val manga = raw(seriesId) ?: return
        val overlay = MetaCodec.overlay(manga.meta)
        val fields = if (locked) overlay.locked + field else overlay.locked - field
        writeOverlay(manga, overlay.copy(locked = fields.distinct().sorted()))
        changed()
    }

    fun setMediaType(seriesId: Long, type: MediaType, lock: Boolean = true) = editSeries(seriesId, SeriesEdit(mediaType = type), lock)

    fun setCoverFile(seriesId: Long, file: String?) {
        val manga = raw(seriesId) ?: return
        writeOverlay(manga, MetaCodec.overlay(manga.meta).copy(coverFile = file))
        changed()
    }

    fun setReaderOverrides(seriesId: Long, overrides: String?) {
        val manga = raw(seriesId) ?: return
        writeState(manga, MetaCodec.state(manga.meta).copy(reader = overrides))
        changed()
    }

    fun setInLibrary(seriesId: Long, inLibrary: Boolean, status: LibraryStatus? = null) {
        val manga = need(seriesId)
        if (manga.inLibrary != inLibrary) api.setInLibrary(listOf(manga.id), inLibrary)
        val state = MetaCodec.state(manga.meta)
        if (inLibrary) {
            writeState(manga, state.copy(status = status?.key ?: state.status ?: LibraryStatus.PLAN.key))
        } else {
            writeState(manga, state.copy(status = null, favorite = false))
            if (manga.categoryIds.isNotEmpty()) api.setMangaCategories(manga.id, clear = true)
        }
        refreshMangas(listOf(manga.id))
        changed()
    }

    fun setLibraryStatus(seriesId: Long, status: LibraryStatus) {
        val manga = need(seriesId)
        if (!manga.inLibrary) api.setInLibrary(listOf(manga.id), true)
        writeState(manga, MetaCodec.state(manga.meta).copy(status = status.key))
        if (!manga.inLibrary) refreshMangas(listOf(manga.id))
        changed()
    }

    /** Marking a favourite also puts the series in the library: favourites are a shelf of it. */
    fun setFavorite(seriesId: Long, favorite: Boolean) {
        val manga = need(seriesId)
        val state = MetaCodec.state(manga.meta)
        if (favorite) {
            if (!manga.inLibrary) api.setInLibrary(listOf(manga.id), true)
            writeState(manga, state.copy(favorite = true, status = state.status ?: LibraryStatus.PLAN.key))
            if (!manga.inLibrary) refreshMangas(listOf(manga.id))
        } else {
            writeState(manga, state.copy(favorite = false))
        }
        changed()
    }

    /**
     * Takes a series out of DebridOne's sight: out of the library, its downloaded chapters deleted,
     * and what DebridOne kept about it forgotten. (Suwayomi keeps a row for every series it has
     * seen; that row, and the read marks in it, stay with the server.)
     */
    fun deleteSeries(seriesId: Long) {
        val manga = raw(seriesId) ?: return
        val downloaded = runCatching { rawChapters(manga.id) }.getOrDefault(emptyList()).filter { it.downloaded }.map { it.id }
        if (downloaded.isNotEmpty()) api.deleteDownloadedChapters(downloaded)
        if (manga.inLibrary) api.setInLibrary(listOf(manga.id), false)
        if (manga.categoryIds.isNotEmpty()) api.setMangaCategories(manga.id, clear = true)
        for (key in listOf(MetaKeys.STATE, MetaKeys.OVERLAY, MetaKeys.BOOKMARKS)) if (key in manga.meta) api.deleteMangaMeta(manga.id, key)
        runCatching { refreshMangas(listOf(manga.id)) }
        chapterLists.remove(manga.id)
        changed()
    }

    // ---- library queries ------------------------------------------------------------------------

    fun library(profileId: Long, filter: LibraryFilter = LibraryFilter()): List<SeriesSummary> {
        if (!active(profileId)) return emptyList()
        val mapper = mapper()
        val collection = filter.collectionId?.toInt()
        val needle = titleKey(filter.query)
        val filtered = libraryMangas().asSequence()
            .filter { collection == null || collection in it.categoryIds }
            .map(mapper::summary)
            .filter { filter.mediaType == null || it.series.mediaType == filter.mediaType }
            .filter { filter.status == null || it.series.libraryStatus == filter.status }
            .filter { !filter.favoritesOnly || it.series.favorite }
            .filter { !filter.localOnly || it.series.isLocal }
            .filter { filter.publicationStatus == null || it.series.status == filter.publicationStatus }
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

    fun libraryCount(profileId: Long): Int = if (active(profileId)) libraryMangas().size else 0

    /** Library series that look like the same work (same title, ignoring case and punctuation), most copies first. */
    fun duplicates(profileId: Long): List<List<SeriesSummary>> = library(profileId)
        .groupBy { titleKey(it.series.title) }
        .values.filter { it.size > 1 }
        .sortedByDescending { it.size }

    /** Every series of the library with this title: the editions/sources a reader can switch between. */
    fun editions(profileId: Long, title: String): List<SeriesSummary> {
        val key = titleKey(title)
        return library(profileId).filter { titleKey(it.series.title) == key }
    }

    /** Series to pick up again, most recently read first, each with the chapter to open. */
    fun continueReading(profileId: Long, limit: Int = 20): List<ContinueEntry> {
        if (!active(profileId)) return emptyList()
        val recent = api.recentlyRead(historyCutoff(), limit * 8)
        val ids = recent.map { it.mangaId }.distinct().take(limit * 2)
        val missing = ids.filter { !mangas.containsKey(it) }
        if (missing.isNotEmpty()) remember(api.mangas(missing))
        val mapper = mapper()
        val entries = ArrayList<ContinueEntry>()
        for (id in ids) {
            val manga = mangas[id] ?: continue
            val summary = mapper.summary(manga)
            if (summary.series.lastReadAt == null) continue
            val chapter = resumeChapter(summary.series) ?: continue
            entries += ContinueEntry(summary, chapter)
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
        var candidate = last
        while (candidate != null) {
            candidate = ChapterOrder.next(candidate, chapters)
            if (candidate != null && !candidate.read) return candidate
        }
        return chapters.firstOrNull { !it.read }
    }

    // ---- chapters -------------------------------------------------------------------------------

    /**
     * The chapters the server has for a series, in reading order (first chapter first). The source
     * is not asked; see `MangaSources.refresh` for that. [allLanguages] is kept for callers written
     * before Suwayomi: a Mihon source has one language, so there is nothing to leave out.
     */
    @Suppress("UNUSED_PARAMETER")
    fun chapters(seriesId: Long, allLanguages: Boolean = false): List<Chapter> {
        val id = seriesId.toInt()
        val language = mangas[id]?.sourceLang
        val waiting = queue
        return rawChapters(id).map { SeriesMapper.chapter(it, language, waiting[it.id]) }
    }

    fun chapter(chapterId: Long): Chapter? {
        val raw = rawChapter(chapterId) ?: return null
        return SeriesMapper.chapter(raw, mangas[raw.mangaId]?.sourceLang, queue[raw.id])
    }

    /** Records where the reader is. [finished] marks the chapter read. */
    @Suppress("UNUSED_PARAMETER")
    fun saveProgress(chapterId: Long, page: Int, pageCount: Int, scrollOffset: Float = 0f, finished: Boolean = false) {
        val current = rawChapter(chapterId) ?: return
        var updated = api.updateChapters(listOf(current.id), read = if (finished) true else null, lastPageRead = page.coerceAtLeast(0)).firstOrNull() ?: return
        val offset = String.format(Locale.ROOT, "%.3f", scrollOffset.coerceIn(0f, 1f))
        if ((current.meta[MetaKeys.SCROLL_OFFSET] ?: "0.000") != offset) {
            api.setChapterMeta(current.id, MetaKeys.SCROLL_OFFSET, offset)
            updated = updated.copy(meta = updated.meta + (MetaKeys.SCROLL_OFFSET to offset))
        }
        replaceChapters(listOf(updated))
        refreshMangas(listOf(current.mangaId))
        // Reading something that was only planned makes it "currently reading".
        mangas[current.mangaId]?.takeIf { it.inLibrary }?.let { manga ->
            val state = MetaCodec.state(manga.meta)
            if (state.status == null || state.status == LibraryStatus.PLAN.key) writeState(manga, state.copy(status = LibraryStatus.READING.key))
        }
        changed()
    }

    /** Marks chapters read or unread. Unread also forgets the page reached. */
    fun setRead(chapterIds: Collection<Long>, read: Boolean) {
        if (chapterIds.isEmpty()) return
        val updated = api.updateChapters(chapterIds.map { it.toInt() }, read = read, lastPageRead = if (read) null else 0)
        replaceChapters(updated)
        refreshMangas(updated.map { it.mangaId }.distinct())
        changed()
    }

    fun setAllRead(seriesId: Long, read: Boolean) = setRead(chapters(seriesId).map { it.id }, read)

    /**
     * When the reader moves to another edition of a work (another source): gives the new edition the
     * same place in the library (status, favourite, collections) and, on request, marks as read
     * there the chapter numbers that were read in the old one. The old edition is not changed.
     */
    fun carryOver(fromSeriesId: Long, toSeriesId: Long, copyReadChapters: Boolean): Int {
        val from = raw(fromSeriesId) ?: return 0
        var to = raw(toSeriesId) ?: return 0
        if (from.inLibrary) {
            if (!to.inLibrary) api.setInLibrary(listOf(to.id), true)
            val old = MetaCodec.state(from.meta)
            val state = MetaCodec.state(to.meta)
            to = writeState(to, state.copy(status = old.status ?: state.status ?: LibraryStatus.PLAN.key, favorite = state.favorite || old.favorite))
            val add = from.categoryIds - to.categoryIds.toSet()
            if (add.isNotEmpty()) api.setMangaCategories(to.id, add = add)
        }
        var marked = 0
        if (copyReadChapters) {
            val readNumbers = rawChapters(from.id).filter { it.read && it.number >= 0 }.map { it.number }.toSet()
            val targets = rawChapters(to.id).filter { !it.read && it.number in readNumbers }.map { it.id }
            if (targets.isNotEmpty()) {
                replaceChapters(api.updateChapters(targets, read = true))
                marked = targets.size
            }
        }
        refreshMangas(listOf(to.id))
        changed()
        return marked
    }

    // ---- history and bookmarks ------------------------------------------------------------------

    fun history(profileId: Long, limit: Int = 100): List<HistoryEntry> {
        if (!active(profileId)) return emptyList()
        val recent = api.recentlyRead(historyCutoff(), limit)
        val missing = recent.map { it.mangaId }.distinct().filter { !mangas.containsKey(it) }
        if (missing.isNotEmpty()) remember(api.mangas(missing))
        val mapper = mapper()
        return recent.mapNotNull { chapter ->
            val manga = mangas[chapter.mangaId] ?: return@mapNotNull null
            HistoryEntry(chapter.id.toLong(), mapper.series(manga), SeriesMapper.chapter(chapter, manga.sourceLang, queue[chapter.id]), chapter.lastPageRead, chapter.lastReadAt * 1000)
        }
    }

    /** Forgets what was read when (Continue Reading starts empty). Read marks and pages reached are kept. */
    fun clearHistory(profileId: Long) {
        if (!active(profileId)) return
        val now = (clock() / 1000).toString()
        api.setGlobalMeta(MetaKeys.HISTORY_CUTOFF, now)
        globals = globalMeta() + (MetaKeys.HISTORY_CUTOFF to now)
        changed()
    }

    private fun bookmark(mangaId: Int, stored: StoredBookmark) =
        Bookmark(bookmarkId(stored.chapterId, stored.page), mangaId.toLong(), stored.chapterId.toLong(), stored.page, stored.note, stored.at)

    private fun bookmarkId(chapterId: Int, page: Int): Long = (chapterId.toLong() shl PAGE_BITS) or page.toLong().coerceIn(0, (1L shl PAGE_BITS) - 1)

    fun bookmarks(seriesId: Long): List<Bookmark> {
        val manga = raw(seriesId) ?: return emptyList()
        return MetaCodec.bookmarks(manga.meta).sortedByDescending { it.at }.map { bookmark(manga.id, it) }
    }

    fun bookmark(chapterId: Long, page: Int): Bookmark? {
        val chapter = rawChapter(chapterId) ?: return null
        val manga = raw(chapter.mangaId.toLong()) ?: return null
        return MetaCodec.bookmarks(manga.meta).firstOrNull { it.chapterId == chapter.id && it.page == page }?.let { bookmark(manga.id, it) }
    }

    /** Adds a bookmark on a page, or removes the one that is there. Returns true when the page is bookmarked afterwards. */
    fun toggleBookmark(chapterId: Long, page: Int, note: String = ""): Boolean {
        val chapter = rawChapter(chapterId) ?: return false
        val manga = raw(chapter.mangaId.toLong()) ?: return false
        val stored = MetaCodec.bookmarks(manga.meta)
        val existing = stored.firstOrNull { it.chapterId == chapter.id && it.page == page }
        val next = if (existing != null) stored - existing else stored + StoredBookmark(chapter.id, page, note, clock())
        saveBookmarks(manga, chapter, next)
        return existing == null
    }

    fun removeBookmark(bookmarkId: Long) {
        val chapterId = bookmarkId shr PAGE_BITS
        val page = (bookmarkId and ((1L shl PAGE_BITS) - 1)).toInt()
        val chapter = rawChapter(chapterId) ?: return
        val manga = raw(chapter.mangaId.toLong()) ?: return
        saveBookmarks(manga, chapter, MetaCodec.bookmarks(manga.meta).filterNot { it.chapterId == chapter.id && it.page == page })
    }

    private fun saveBookmarks(manga: SwManga, chapter: SwChapter, bookmarks: List<StoredBookmark>) {
        writeMeta(manga, MetaKeys.BOOKMARKS, MetaCodec.encode(bookmarks).takeIf { bookmarks.isNotEmpty() })
        // Suwayomi's own bookmark is per chapter; it is kept in step so other clients of the server see it.
        val marked = bookmarks.any { it.chapterId == chapter.id }
        if (chapter.bookmarked != marked) {
            replaceChapters(api.updateChapters(listOf(chapter.id), bookmarked = marked))
            runCatching { refreshMangas(listOf(manga.id)) }
        }
        changed()
    }

    // ---- collections (Suwayomi's categories) ----------------------------------------------------

    fun collections(profileId: Long): List<MangaCollection> {
        if (!active(profileId)) return emptyList()
        val library = libraryMangas()
        return categoryList().filter { !it.isDefault }
            .map { category -> MangaCollection(category.id.toLong(), category.name, 0, library.count { category.id in it.categoryIds }) }
            .sortedBy { it.name.lowercase() }
    }

    fun createCollection(profileId: Long, name: String): MangaCollection {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A collection needs a name." }
        require(collections(profileId).none { it.name.equals(trimmed, ignoreCase = true) }) { "There is already a collection called \"$trimmed\"." }
        val created = api.createCategory(trimmed)
        categories = null
        changed()
        return MangaCollection(created.id.toLong(), created.name, clock(), 0)
    }

    fun renameCollection(collectionId: Long, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A collection needs a name." }
        api.renameCategory(collectionId.toInt(), trimmed)
        categories = null
        changed()
    }

    fun deleteCollection(collectionId: Long) {
        api.deleteCategory(collectionId.toInt())
        categories = null
        val id = collectionId.toInt()
        mangas.values.filter { id in it.categoryIds }.forEach { mangas[it.id] = it.copy(categoryIds = it.categoryIds - id) }
        changed()
    }

    /** Adds to or removes from a collection. Adding also puts the series in the library. */
    fun setInCollection(collectionId: Long, seriesId: Long, member: Boolean) {
        val manga = need(seriesId)
        val id = collectionId.toInt()
        if (member) {
            if (!manga.inLibrary) {
                api.setInLibrary(listOf(manga.id), true)
                val state = MetaCodec.state(manga.meta)
                writeState(manga, state.copy(status = state.status ?: LibraryStatus.PLAN.key))
            }
            api.setMangaCategories(manga.id, add = listOf(id))
        } else {
            api.setMangaCategories(manga.id, remove = listOf(id))
        }
        refreshMangas(listOf(manga.id))
        changed()
    }

    fun collectionsOf(seriesId: Long): Set<Long> = raw(seriesId)?.categoryIds?.filter { it != 0 }?.map { it.toLong() }?.toSet().orEmpty()

    // ---- metadata links -------------------------------------------------------------------------

    fun metadataLinks(seriesId: Long): List<MetadataLink> {
        val manga = raw(seriesId) ?: return emptyList()
        return MetaCodec.state(manga.meta).links.map { MetadataLink(seriesId, it.provider, it.remoteId, it.url, it.linkedAt, it.tracked) }
    }

    fun linkMetadata(seriesId: Long, provider: String, remoteId: String, url: String?) {
        val manga = raw(seriesId) ?: return
        val state = MetaCodec.state(manga.meta)
        val tracked = state.links.firstOrNull { it.provider == provider }?.tracked == true
        writeState(manga, state.copy(links = state.links.filterNot { it.provider == provider } + StoredLink(provider, remoteId, url, clock(), tracked)))
        changed()
    }

    fun unlinkMetadata(seriesId: Long, provider: String) {
        val manga = raw(seriesId) ?: return
        val state = MetaCodec.state(manga.meta)
        writeState(manga, state.copy(links = state.links.filterNot { it.provider == provider }))
        changed()
    }

    fun setTracked(seriesId: Long, provider: String, tracked: Boolean) {
        val manga = raw(seriesId) ?: return
        val state = MetaCodec.state(manga.meta)
        writeState(manga, state.copy(links = state.links.map { if (it.provider == provider) it.copy(tracked = tracked) else it }))
        changed()
    }

    // ---- downloads ------------------------------------------------------------------------------

    /** Every chapter that is waiting, downloading, failed or downloaded: the queue first, in its order. */
    fun downloads(profileId: Long): List<Pair<Series, Chapter>> {
        if (!active(profileId)) return emptyList()
        val waiting = queue.values.sortedBy { it.position }
        val downloaded = api.downloadedChapters()
        val missing = (waiting.map { it.mangaId } + downloaded.map { it.mangaId }).distinct().filter { !mangas.containsKey(it) }
        if (missing.isNotEmpty()) remember(api.mangas(missing))
        val mapper = mapper()
        val series = HashMap<Int, Series>()
        fun seriesOf(mangaId: Int): Series? = series[mangaId] ?: mangas[mangaId]?.let(mapper::series)?.also { series[mangaId] = it }
        val result = ArrayList<Pair<Series, Chapter>>()
        val listed = HashSet<Int>()
        for (entry in waiting) {
            val chapter = rawChapter(entry.chapterId.toLong()) ?: continue
            val owner = seriesOf(chapter.mangaId) ?: continue
            if (listed.add(chapter.id)) result += owner to SeriesMapper.chapter(chapter, mangas[chapter.mangaId]?.sourceLang, entry)
        }
        for (chapter in downloaded) {
            val owner = seriesOf(chapter.mangaId) ?: continue
            if (listed.add(chapter.id)) result += owner to SeriesMapper.chapter(chapter, mangas[chapter.mangaId]?.sourceLang, null)
        }
        return result
    }

    // ---- backup --------------------------------------------------------------------------------

    /**
     * Writes Suwayomi's own backup of this library to [target]: the format Mihon and Suwayomi read
     * (`.tachibk`), with the library, categories, read chapters and trackers — and DebridOne's
     * extras, which are stored in the library itself. Returns how many series are in it.
     */
    fun exportBackup(target: java.nio.file.Path): Int {
        val bytes = api.createBackup()
        java.nio.file.Files.write(target, bytes)
        return libraryMangas().size
    }

    /**
     * Restores a backup made by Suwayomi or Mihon. What is in the library stays; the backup's
     * series are added and their read marks merged. Waits for the server to finish and returns how
     * many series it restored. Series from sources that are not installed stay unreadable until
     * their extension is.
     */
    fun restoreBackup(source: java.nio.file.Path, timeoutMs: Long = 15 * 60 * 1000L): Int {
        val id = api.restoreBackup(source.fileName.toString(), java.nio.file.Files.readAllBytes(source))
        val deadline = clock() + timeoutMs
        var done = 0
        while (true) {
            val status = api.restoreStatus(id)
            done = status?.done ?: done
            when (status?.state) {
                "SUCCESS" -> break
                "FAILURE" -> throw java.io.IOException("The manga server could not restore this backup. It may not be a Suwayomi or Mihon backup file.")
                else -> Unit
            }
            if (clock() > deadline) throw java.io.IOException("Restoring the backup is taking very long; it continues on the server.")
            Thread.sleep(400)
        }
        forgetEverything()
        changed()
        return done
    }

    private fun matches(series: Series, needle: String): Boolean =
        titleKey(series.title).contains(needle) ||
            series.altTitles.any { titleKey(it).contains(needle) } ||
            series.authors.any { titleKey(it).contains(needle) } ||
            (series.tags + series.customTags).any { titleKey(it).contains(needle) }

    private fun clean(values: List<String>): List<String> = values.map(String::trim).filter(String::isNotEmpty).distinct()

    companion object {
        private const val PAGE_BITS = 20

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
    private val webtoonLanguages = setOf("KOREAN", "CHINESE_SIMP", "CHINESE_TRAD", "ko", "zh", "zh-Hans", "zh-Hant")
    private val webtoonTags = setOf("manhwa", "manhua", "webtoon", "webtoons", "long strip", "web comic", "full color")

    /**
     * Korean and Chinese originals, and anything a source tags as a webtoon or long strip, go to
     * Manhwa; everything else to Manga. It is a starting point: the reader can move a series, and
     * that choice is then kept.
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

    /** First chapter first: the order the source lists them in, which is also the order Suwayomi keeps. */
    fun readingOrder(chapters: List<Chapter>): List<Chapter> = chapters.sortedWith(compareBy<Chapter> { it.position }.thenBy { it.id })

    /** The chapter after [current]: the next number, preferring the same scanlation group. */
    fun next(current: Chapter, chapters: List<Chapter>): Chapter? = neighbour(current, chapters, forward = true)

    fun previous(current: Chapter, chapters: List<Chapter>): Chapter? = neighbour(current, chapters, forward = false)

    private fun neighbour(current: Chapter, chapters: List<Chapter>, forward: Boolean): Chapter? {
        val ordered = readingOrder(chapters)
        val index = ordered.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        val onward = if (forward) ordered.drop(index + 1) else ordered.take(index).reversed()
        val sort = current.chapterSort ?: return onward.firstOrNull()
        // A source can list the same chapter from several groups: those are not "the next chapter".
        val candidates = onward.filter { it.chapterSort != sort }
        val first = candidates.firstOrNull() ?: return null
        val target = first.chapterSort ?: return first
        val same = candidates.takeWhile { it.chapterSort == target || it.chapterSort == null }.filter { it.chapterSort == target }
        return same.firstOrNull { it.groupName == current.groupName } ?: same.first()
    }
}
