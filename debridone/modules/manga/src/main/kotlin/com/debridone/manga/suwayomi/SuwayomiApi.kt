package com.debridone.manga.suwayomi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// ---- what the server answers, as DebridOne reads it ------------------------------------------------
//
// Times are as Suwayomi stores them: `uploadDate` in milliseconds, every other time in seconds.

data class SwAbout(val name: String, val version: String, val buildType: String, val buildTime: Long, val github: String)

data class SwChapter(
    val id: Int,
    val mangaId: Int,
    val name: String,
    val number: Double,
    /** The position the source gave the chapter; the first chapter has the lowest. Also the index in page addresses. */
    val sourceOrder: Int,
    val scanlator: String?,
    val uploadDateMs: Long,
    val fetchedAt: Long,
    /** -1 until the chapter's pages were asked for once. */
    val pageCount: Int,
    val read: Boolean,
    val bookmarked: Boolean,
    val downloaded: Boolean,
    val lastPageRead: Int,
    val lastReadAt: Long,
    val url: String,
    val realUrl: String?,
    val meta: Map<String, String>,
)

data class SwManga(
    val id: Int,
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genres: List<String>,
    /** UNKNOWN, ONGOING, COMPLETED, LICENSED, PUBLISHING_FINISHED, CANCELLED or ON_HIATUS. */
    val status: String,
    val sourceId: String,
    val url: String,
    val realUrl: String?,
    /** A path of the server (`/api/v1/manga/<id>/thumbnail`), or null when the series has no cover. */
    val thumbnailUrl: String?,
    val thumbnailFetchedAt: Long?,
    val inLibrary: Boolean,
    val inLibraryAt: Long,
    /** False until the series' details were fetched from its source once. */
    val initialized: Boolean,
    val lastFetchedAt: Long?,
    val chaptersLastFetchedAt: Long?,
    val unreadCount: Int,
    val downloadCount: Int,
    val bookmarkCount: Int,
    val chapterCount: Int,
    val categoryIds: List<Int>,
    val meta: Map<String, String>,
    val sourceName: String?,
    val sourceLang: String?,
    val lastReadChapter: SwChapter?,
    val firstUnreadChapter: SwChapter?,
    val latestUploadedChapter: SwChapter?,
)

data class SwPage<T>(val items: List<T>, val hasNextPage: Boolean)

data class SwCategory(val id: Int, val name: String, val order: Int, val isDefault: Boolean, val mangaCount: Int, val meta: Map<String, String>)

data class SwSource(
    val id: String,
    val name: String,
    val displayName: String,
    val lang: String,
    val iconUrl: String,
    val configurable: Boolean,
    val supportsLatest: Boolean,
    /** SAFE, MIXED or NSFW. */
    val contentWarning: String,
    val homeUrl: String?,
    val meta: Map<String, String>,
)

/** A setting an extension defines for one of its sources. [position] is how Suwayomi addresses it. */
sealed interface SwPreference {
    val position: Int
    val key: String?
    val title: String?
    val summary: String?
    val enabled: Boolean
    val visible: Boolean

    data class Toggle(
        override val position: Int, override val key: String?, override val title: String?, override val summary: String?,
        override val enabled: Boolean, override val visible: Boolean,
        val value: Boolean, val default: Boolean,
        /** Suwayomi tells a switch from a check box; the change has to name the right one. */
        val isSwitch: Boolean,
    ) : SwPreference

    data class Text(
        override val position: Int, override val key: String?, override val title: String?, override val summary: String?,
        override val enabled: Boolean, override val visible: Boolean,
        val value: String?, val default: String?, val dialogTitle: String?, val dialogMessage: String?,
    ) : SwPreference

    data class Choice(
        override val position: Int, override val key: String?, override val title: String?, override val summary: String?,
        override val enabled: Boolean, override val visible: Boolean,
        val value: String?, val default: String?, val entries: List<String>, val entryValues: List<String>,
    ) : SwPreference

    data class MultiChoice(
        override val position: Int, override val key: String?, override val title: String?, override val summary: String?,
        override val enabled: Boolean, override val visible: Boolean,
        val values: List<String>, val default: List<String>, val entries: List<String>, val entryValues: List<String>,
    ) : SwPreference
}

/** A change to one source preference. Exactly one of the values is set, matching the preference's kind. */
data class SwPreferenceChange(
    val position: Int,
    val switch: Boolean? = null,
    val checkBox: Boolean? = null,
    val text: String? = null,
    val choice: String? = null,
    val multiChoice: List<String>? = null,
)

/** A search filter a source defines. Its position in the list is how a change addresses it. */
sealed interface SwFilter {
    val name: String

    data class Header(override val name: String) : SwFilter
    data class Separator(override val name: String) : SwFilter
    data class CheckBox(override val name: String, val default: Boolean) : SwFilter

    /** [default] is IGNORE, INCLUDE or EXCLUDE. */
    data class TriState(override val name: String, val default: String) : SwFilter
    data class Text(override val name: String, val default: String) : SwFilter
    data class Select(override val name: String, val values: List<String>, val default: Int) : SwFilter
    data class Sort(override val name: String, val values: List<String>, val defaultIndex: Int?, val defaultAscending: Boolean) : SwFilter
    data class Group(override val name: String, val filters: List<SwFilter>) : SwFilter
}

data class SwFilterChange(
    val position: Int,
    val checkBox: Boolean? = null,
    val text: String? = null,
    val select: Int? = null,
    val triState: String? = null,
    val sortIndex: Int? = null,
    val sortAscending: Boolean? = null,
    /** For a filter inside a group: [position] is the group's, this is the change inside it. */
    val group: SwFilterChange? = null,
)

data class SwSourceDetails(val source: SwSource, val preferences: List<SwPreference>, val filters: List<SwFilter>)

data class SwStore(
    val indexUrl: String,
    val name: String,
    val badgeLabel: String,
    val website: String?,
    val discord: String?,
    val signingKey: String,
    /** True for a repository in the older `index.min.json` form. */
    val legacy: Boolean,
)

data class SwExtension(
    val pkgName: String,
    val name: String,
    val lang: String,
    val versionName: String,
    val versionCode: Long,
    val installed: Boolean,
    val hasUpdate: Boolean,
    /** Installed, but no longer offered by any store. */
    val obsolete: Boolean,
    val iconUrl: String,
    val contentWarning: String,
    val storeIndexUrl: String?,
    val storeName: String?,
)

data class SwDownload(
    val chapterId: Int,
    val mangaId: Int,
    val chapterName: String,
    val mangaTitle: String,
    val position: Int,
    /** 0.0 to 1.0. */
    val progress: Double,
    /** QUEUED, DOWNLOADING, FINISHED or ERROR. */
    val state: String,
    val tries: Int,
)

data class SwDownloadStatus(val running: Boolean, val queue: List<SwDownload>)

data class SwLibraryUpdate(val running: Boolean, val finished: Int, val total: Int)

data class SwTracker(
    val id: Int,
    val name: String,
    val icon: String,
    val loggedIn: Boolean,
    /** Where the person signs in with their browser, for trackers that work that way. */
    val authUrl: String?,
    val tokenExpired: Boolean,
    val statuses: List<Pair<Int, String>>,
    val scores: List<String>,
    val supportsTrackDeletion: Boolean,
    val supportsReadingDates: Boolean,
    val supportsPrivateTracking: Boolean,
)

data class SwTrackRecord(
    val id: Int,
    val mangaId: Int,
    val trackerId: Int,
    val remoteId: String,
    val remoteUrl: String,
    val title: String,
    val status: Int,
    val lastChapterRead: Double,
    val totalChapters: Int,
    val displayScore: String,
    val startDate: Long,
    val finishDate: Long,
    val private: Boolean,
)

data class SwTrackSearch(
    val trackerId: Int,
    val remoteId: String,
    val title: String,
    val coverUrl: String,
    val summary: String,
    val publishingStatus: String,
    val publishingType: String,
    val startDate: String,
    val totalChapters: Int,
    val trackingUrl: String,
)

data class SwRestoreStatus(
    /** IDLE, SUCCESS, FAILURE or one of the RESTORING_… steps. */
    val state: String,
    val done: Int,
    val total: Int,
)

/**
 * Suwayomi-Server's GraphQL API, one function per operation DebridOne uses. Written against the
 * schema of the pinned server version (see `fetch-suwayomi-runtime.sh`); `SuwayomiApiLiveTest`
 * runs every document against that server, so a field that does not exist fails a test, not a
 * person's library.
 *
 * Functions block; call them off the UI thread. They throw [SuwayomiException].
 */
class SuwayomiApi(private val transport: SuwayomiTransport) {

    // ---- server ----------------------------------------------------------------------------------

    fun about(): SwAbout {
        val about = transport.execute(ABOUT, timeoutSeconds = 5).need("aboutServer")
        return SwAbout(
            name = about.text("name").orEmpty(),
            version = about.text("version").orEmpty(),
            buildType = about.text("buildType").orEmpty(),
            buildTime = about.long("buildTime") ?: 0,
            github = about.text("github").orEmpty(),
        )
    }

    /** The named server settings, as JSON values (the set is large and typed loosely on purpose). */
    fun settings(names: Collection<String>): Map<String, JsonElement> {
        require(names.all { NAME.matches(it) }) { "not a setting name" }
        if (names.isEmpty()) return emptyMap()
        val data = transport.execute("query { settings { ${names.joinToString(" ")} } }").need("settings")
        return names.associateWith { data[it] ?: JsonNull }
    }

    fun setSettings(values: Map<String, JsonElement>) {
        if (values.isEmpty()) return
        require(values.keys.all { NAME.matches(it) }) { "not a setting name" }
        transport.execute(SET_SETTINGS, input { put("settings", JsonObject(values)) })
    }

    // ---- library and series ------------------------------------------------------------------------

    /** Every series in the library. */
    fun library(): List<SwManga> {
        val all = ArrayList<SwManga>()
        while (true) {
            val page = transport.execute(LIBRARY, buildJsonObject { put("first", PAGE); put("offset", all.size) }).nodes("mangas")
            page.mapTo(all) { manga(it) }
            if (page.size < PAGE) return all
        }
    }

    fun manga(id: Int): SwManga = manga(transport.execute(MANGA, buildJsonObject { put("id", id) }).need("manga"))

    fun mangas(ids: Collection<Int>): List<SwManga> {
        if (ids.isEmpty()) return emptyList()
        return transport.execute(MANGAS_BY_ID, buildJsonObject { put("ids", ints(ids)) }).nodes("mangas").map { manga(it) }
    }

    /** Series the server already knows from [sourceId] whose address at the source is [url]. */
    fun mangasByUrl(sourceId: String, url: String): List<SwManga> =
        transport.execute(MANGAS_BY_URL, buildJsonObject { put("source", sourceId); put("url", url) }).nodes("mangas").map { manga(it) }

    /**
     * Asks the series' source for its details and/or its chapter list, and returns both as the
     * server now has them. This is a request to someone else's site and can take long or fail.
     */
    fun refresh(mangaId: Int, details: Boolean = true, chapters: Boolean = true): Pair<SwManga, List<SwChapter>> {
        val payload = transport.execute(
            FETCH_MANGA_AND_CHAPTERS,
            input { put("id", mangaId); put("fetchManga", details); put("fetchChapters", chapters) },
            timeoutSeconds = SOURCE_TIMEOUT,
        ).need("fetchMangaAndChapters")
        return manga(payload.need("manga")) to payload.objects("chapters").map { chapter(it) }
    }

    fun setInLibrary(mangaIds: Collection<Int>, inLibrary: Boolean) {
        if (mangaIds.isEmpty()) return
        transport.execute(UPDATE_MANGAS, input { put("ids", ints(mangaIds)); putJsonObject("patch") { put("inLibrary", inLibrary) } })
    }

    fun setMangaMeta(mangaId: Int, key: String, value: String) {
        transport.execute(SET_MANGA_META, input { putJsonObject("meta") { put("mangaId", mangaId); put("key", key); put("value", value) } })
    }

    fun deleteMangaMeta(mangaId: Int, key: String) {
        transport.execute(DELETE_MANGA_META, input { put("mangaId", mangaId); put("key", key) })
    }

    // ---- chapters and pages ------------------------------------------------------------------------

    /** The chapters the server has for a series, first chapter first. Nothing is asked of the source. */
    fun chapters(mangaId: Int): List<SwChapter> =
        transport.execute(CHAPTERS, buildJsonObject { put("mangaId", mangaId) }).nodes("chapters").map { chapter(it) }

    fun chapter(id: Int): SwChapter = chapter(transport.execute(CHAPTER, buildJsonObject { put("id", id) }).need("chapter"))

    /** Chapters read since [sinceSeconds], most recent first, in and outside the library. */
    fun recentlyRead(sinceSeconds: Long, limit: Int): List<SwChapter> =
        transport.execute(RECENTLY_READ, buildJsonObject { put("since", sinceSeconds.toString()); put("first", limit) })
            .nodes("chapters").map { chapter(it) }

    /** Downloaded chapters across the library. */
    fun downloadedChapters(): List<SwChapter> = transport.execute(DOWNLOADED_CHAPTERS).nodes("chapters").map { chapter(it) }

    /** Bookmarked chapters across the library. */
    fun bookmarkedChapters(): List<SwChapter> = transport.execute(BOOKMARKED_CHAPTERS).nodes("chapters").map { chapter(it) }

    /**
     * The addresses of a chapter's pages (paths of the server), fetched from the source, or from
     * the disk for a downloaded chapter. Also returns the chapter, whose page count is now known.
     */
    fun chapterPages(chapterId: Int): Pair<SwChapter, List<String>> {
        val payload = transport.execute(FETCH_CHAPTER_PAGES, input { put("chapterId", chapterId) }, timeoutSeconds = SOURCE_TIMEOUT).need("fetchChapterPages")
        return chapter(payload.need("chapter")) to payload.strings("pages")
    }

    fun updateChapters(ids: Collection<Int>, read: Boolean? = null, bookmarked: Boolean? = null, lastPageRead: Int? = null): List<SwChapter> {
        if (ids.isEmpty()) return emptyList()
        val payload = transport.execute(
            UPDATE_CHAPTERS,
            input {
                put("ids", ints(ids))
                putJsonObject("patch") {
                    if (read != null) put("isRead", read)
                    if (bookmarked != null) put("isBookmarked", bookmarked)
                    if (lastPageRead != null) put("lastPageRead", lastPageRead)
                }
            },
        ).need("updateChapters")
        return payload.objects("chapters").map { chapter(it) }
    }

    fun setChapterMeta(chapterId: Int, key: String, value: String) {
        transport.execute(SET_CHAPTER_META, input { putJsonObject("meta") { put("chapterId", chapterId); put("key", key); put("value", value) } })
    }

    // ---- values that belong to no series -----------------------------------------------------------

    fun globalMeta(): Map<String, String> =
        transport.execute(GLOBAL_METAS).nodes("metas").mapNotNull { m -> m.text("key")?.let { it to m.text("value").orEmpty() } }.toMap()

    fun setGlobalMeta(key: String, value: String) {
        transport.execute(SET_GLOBAL_META, input { putJsonObject("meta") { put("key", key); put("value", value) } })
    }

    fun deleteGlobalMeta(key: String) {
        transport.execute(DELETE_GLOBAL_META, input { put("key", key) })
    }

    // ---- categories --------------------------------------------------------------------------------

    fun categories(): List<SwCategory> = transport.execute(CATEGORIES).nodes("categories").map { category(it) }.sortedBy { it.order }

    fun createCategory(name: String): SwCategory =
        category(transport.execute(CREATE_CATEGORY, input { put("name", name) }).need("createCategory").need("category"))

    fun renameCategory(id: Int, name: String) {
        transport.execute(UPDATE_CATEGORY, input { put("id", id); putJsonObject("patch") { put("name", name) } })
    }

    fun moveCategory(id: Int, position: Int) {
        transport.execute(UPDATE_CATEGORY_ORDER, input { put("id", id); put("position", position) })
    }

    fun deleteCategory(id: Int) {
        transport.execute(DELETE_CATEGORY, input { put("categoryId", id) })
    }

    fun setMangaCategories(mangaId: Int, add: Collection<Int> = emptyList(), remove: Collection<Int> = emptyList(), clear: Boolean = false) {
        if (add.isEmpty() && remove.isEmpty() && !clear) return
        transport.execute(
            UPDATE_MANGA_CATEGORIES,
            input {
                put("id", mangaId)
                putJsonObject("patch") {
                    if (clear) put("clearCategories", true)
                    if (add.isNotEmpty()) put("addToCategories", ints(add))
                    if (remove.isNotEmpty()) put("removeFromCategories", ints(remove))
                }
            },
        )
    }

    // ---- sources -----------------------------------------------------------------------------------

    fun sources(): List<SwSource> = transport.execute(SOURCES).nodes("sources").map { source(it) }

    /** A source with the settings and search filters its extension defines. */
    fun sourceDetails(id: String): SwSourceDetails {
        val data = transport.execute(SOURCE_DETAILS, buildJsonObject { put("id", id) }, timeoutSeconds = SOURCE_TIMEOUT).need("source")
        return SwSourceDetails(source(data), preferences(data.objects("preferences")), data.objects("filters").map { filter(it) })
    }

    /** Popular, latest or searched series of one source. A request to someone else's site. */
    fun browse(sourceId: String, type: BrowseType, page: Int, query: String? = null, filters: List<SwFilterChange> = emptyList()): SwPage<SwManga> {
        val payload = transport.execute(
            FETCH_SOURCE_MANGA,
            input {
                put("source", sourceId)
                put("type", type.name)
                put("page", page)
                if (type == BrowseType.SEARCH) {
                    put("query", query.orEmpty())
                    if (filters.isNotEmpty()) put("filters", buildJsonArray { filters.forEach { add(filterChange(it)) } })
                }
            },
            timeoutSeconds = SOURCE_TIMEOUT,
        ).need("fetchSourceManga")
        return SwPage(payload.objects("mangas").map { manga(it) }, payload.bool("hasNextPage") == true)
    }

    enum class BrowseType { SEARCH, POPULAR, LATEST }

    /** Changes one setting of a source and returns the settings as they are afterwards. */
    fun setSourcePreference(sourceId: String, change: SwPreferenceChange): List<SwPreference> {
        val payload = transport.execute(
            UPDATE_SOURCE_PREFERENCE,
            input {
                put("source", sourceId)
                putJsonObject("change") {
                    put("position", change.position)
                    change.switch?.let { put("switchState", it) }
                    change.checkBox?.let { put("checkBoxState", it) }
                    change.text?.let { put("editTextState", it) }
                    change.choice?.let { put("listState", it) }
                    change.multiChoice?.let { put("multiSelectState", JsonArray(it.map(::JsonPrimitive))) }
                }
            },
        ).need("updateSourcePreference")
        return preferences(payload.objects("preferences"))
    }

    fun setSourceMeta(sourceId: String, key: String, value: String) {
        transport.execute(SET_SOURCE_META, input { putJsonObject("meta") { put("sourceId", sourceId); put("key", key); put("value", value) } })
    }

    // ---- extension stores and extensions -----------------------------------------------------------

    fun stores(): List<SwStore> = transport.execute(STORES).nodes("extensionStores").map { store(it) }

    /** Adds a store by the address of its index. Suwayomi fetches and checks it; its reason is thrown when it refuses. */
    fun addStore(indexUrl: String): SwStore =
        store(transport.execute(ADD_STORE, input { put("indexUrl", indexUrl) }, timeoutSeconds = SOURCE_TIMEOUT).need("addExtensionStore").need("extensionStore"))

    fun removeStore(indexUrl: String) {
        transport.execute(REMOVE_STORE, input { put("indexUrl", indexUrl) })
    }

    /** The extensions the server knows: installed ones and what the stores offered when last asked. */
    fun extensions(): List<SwExtension> = transport.execute(EXTENSIONS).nodes("extensions").map { extension(it) }

    /** Asks every store for its current list and returns the result. */
    fun fetchExtensions(): List<SwExtension> =
        transport.execute(FETCH_EXTENSIONS, input { }, timeoutSeconds = SOURCE_TIMEOUT).need("fetchExtensions").objects("extensions").map { extension(it) }

    fun installExtension(pkgName: String): SwExtension? = changeExtension(pkgName, "install")
    fun updateExtension(pkgName: String): SwExtension? = changeExtension(pkgName, "update")
    fun uninstallExtension(pkgName: String): SwExtension? = changeExtension(pkgName, "uninstall")

    private fun changeExtension(pkgName: String, action: String): SwExtension? =
        transport.execute(UPDATE_EXTENSION, input { put("id", pkgName); putJsonObject("patch") { put(action, true) } }, timeoutSeconds = INSTALL_TIMEOUT)
            .need("updateExtension").obj("extension")?.let { extension(it) }

    /** Installs an extension from a package file (`.apk`) the person chose. */
    fun installExtensionFile(fileName: String, bytes: ByteArray): SwExtension {
        val payload = transport.upload(
            INSTALL_EXTERNAL_EXTENSION, input { put("extensionFile", JsonNull) }, "variables.input.extensionFile", fileName, bytes, INSTALL_TIMEOUT,
        ).need("installExternalExtension")
        return extension(payload.need("extension"))
    }

    /** Which sources each installed extension provides (package name to source ids). */
    fun extensionSources(): Map<String, List<String>> =
        transport.execute(EXTENSION_SOURCES).nodes("extensions").associate { e ->
            e.text("pkgName").orEmpty() to e.nodes("source").mapNotNull { it.text("id") }
        }

    // ---- downloads ---------------------------------------------------------------------------------

    fun downloadStatus(): SwDownloadStatus = downloadStatus(transport.execute(DOWNLOAD_STATUS).need("downloadStatus"))

    fun enqueueDownloads(chapterIds: Collection<Int>): SwDownloadStatus = downloadChange(ENQUEUE_DOWNLOADS, "enqueueChapterDownloads", chapterIds)
    fun dequeueDownloads(chapterIds: Collection<Int>): SwDownloadStatus = downloadChange(DEQUEUE_DOWNLOADS, "dequeueChapterDownloads", chapterIds)

    fun deleteDownloadedChapters(chapterIds: Collection<Int>) {
        if (chapterIds.isEmpty()) return
        transport.execute(DELETE_DOWNLOADED, input { put("ids", ints(chapterIds)) })
    }

    fun startDownloader(): SwDownloadStatus = downloadStatus(transport.execute(START_DOWNLOADER, input { }).need("startDownloader").need("downloadStatus"))
    fun stopDownloader(): SwDownloadStatus = downloadStatus(transport.execute(STOP_DOWNLOADER, input { }).need("stopDownloader").need("downloadStatus"))
    fun clearDownloader(): SwDownloadStatus = downloadStatus(transport.execute(CLEAR_DOWNLOADER, input { }).need("clearDownloader").need("downloadStatus"))

    private fun downloadChange(document: String, field: String, ids: Collection<Int>): SwDownloadStatus {
        if (ids.isEmpty()) return downloadStatus()
        return downloadStatus(transport.execute(document, input { put("ids", ints(ids)) }).need(field).need("downloadStatus"))
    }

    // ---- looking for new chapters ------------------------------------------------------------------

    fun updateLibrary() {
        transport.execute(UPDATE_LIBRARY, input { })
    }

    fun stopLibraryUpdate() {
        transport.execute(UPDATE_STOP, input { })
    }

    fun libraryUpdateStatus(): SwLibraryUpdate {
        val jobs = transport.execute(LIBRARY_UPDATE_STATUS).need("libraryUpdateStatus").need("jobsInfo")
        return SwLibraryUpdate(jobs.bool("isRunning") == true, jobs.int("finishedJobs") ?: 0, jobs.int("totalJobs") ?: 0)
    }

    // ---- trackers ----------------------------------------------------------------------------------

    fun trackers(): List<SwTracker> = transport.execute(TRACKERS).nodes("trackers").map { tracker(it) }

    fun loginTrackerCredentials(trackerId: Int, username: String, password: String): Boolean =
        transport.execute(LOGIN_TRACKER_CREDENTIALS, input { put("trackerId", trackerId); put("username", username); put("password", password) }, timeoutSeconds = SOURCE_TIMEOUT)
            .need("loginTrackerCredentials").bool("isLoggedIn") == true

    /** Completes a browser sign-in: [callbackUrl] is the address the tracker sent the browser to at the end. */
    fun loginTrackerOAuth(trackerId: Int, callbackUrl: String): Boolean =
        transport.execute(LOGIN_TRACKER_OAUTH, input { put("trackerId", trackerId); put("callbackUrl", callbackUrl) }, timeoutSeconds = SOURCE_TIMEOUT)
            .need("loginTrackerOAuth").bool("isLoggedIn") == true

    fun logoutTracker(trackerId: Int) {
        transport.execute(LOGOUT_TRACKER, input { put("trackerId", trackerId) })
    }

    fun searchTracker(trackerId: Int, query: String): List<SwTrackSearch> =
        transport.execute(SEARCH_TRACKER, buildJsonObject { put("trackerId", trackerId); put("query", query) }, timeoutSeconds = SOURCE_TIMEOUT)
            .need("searchTracker").objects("trackSearches").map {
                SwTrackSearch(
                    trackerId = it.int("trackerId") ?: trackerId,
                    remoteId = it.text("remoteId").orEmpty(),
                    title = it.text("title").orEmpty(),
                    coverUrl = it.text("coverUrl").orEmpty(),
                    summary = it.text("summary").orEmpty(),
                    publishingStatus = it.text("publishingStatus").orEmpty(),
                    publishingType = it.text("publishingType").orEmpty(),
                    startDate = it.text("startDate").orEmpty(),
                    totalChapters = it.int("totalChapters") ?: 0,
                    trackingUrl = it.text("trackingUrl").orEmpty(),
                )
            }

    fun trackRecords(mangaId: Int): List<SwTrackRecord> =
        transport.execute(TRACK_RECORDS, buildJsonObject { put("mangaId", mangaId) }).need("manga").nodes("trackRecords").map { trackRecord(it) }

    fun bindTrack(mangaId: Int, trackerId: Int, remoteId: String): SwTrackRecord =
        trackRecord(
            transport.execute(BIND_TRACK, input { put("mangaId", mangaId); put("trackerId", trackerId); put("remoteId", remoteId) }, timeoutSeconds = SOURCE_TIMEOUT)
                .need("bindTrack").need("trackRecord"),
        )

    fun unbindTrack(recordId: Int, deleteRemote: Boolean = false) {
        transport.execute(UNBIND_TRACK, input { put("recordId", recordId); put("deleteRemoteTrack", deleteRemote) }, timeoutSeconds = SOURCE_TIMEOUT)
    }

    fun updateTrack(recordId: Int, status: Int? = null, lastChapterRead: Double? = null, score: String? = null): SwTrackRecord? =
        transport.execute(
            UPDATE_TRACK,
            input {
                put("recordId", recordId)
                if (status != null) put("status", status)
                if (lastChapterRead != null) put("lastChapterRead", lastChapterRead)
                if (score != null) put("scoreString", score)
            },
            timeoutSeconds = SOURCE_TIMEOUT,
        ).need("updateTrack").obj("trackRecord")?.let { trackRecord(it) }

    /** Tells the series' trackers how far it has been read. */
    fun trackProgress(mangaId: Int) {
        transport.execute(TRACK_PROGRESS, input { put("mangaId", mangaId) }, timeoutSeconds = SOURCE_TIMEOUT)
    }

    // ---- backup ------------------------------------------------------------------------------------

    /** Has the server write a backup of the library and returns it (Suwayomi's own format, `.tachibk`). */
    fun createBackup(): ByteArray {
        val url = transport.execute(CREATE_BACKUP, timeoutSeconds = SOURCE_TIMEOUT).need("createBackup").text("url")
            ?: throw SuwayomiException(SuwayomiException.Kind.PROTOCOL, "The manga server did not say where the backup is.")
        return transport.bytes(url, timeoutSeconds = SOURCE_TIMEOUT)
    }

    /** Starts restoring a backup and returns the id to ask [restoreStatus] with. */
    fun restoreBackup(fileName: String, bytes: ByteArray): String =
        transport.upload(RESTORE_BACKUP, input { put("backup", JsonNull) }, "variables.input.backup", fileName, bytes)
            .need("restoreBackup").text("id").orEmpty()

    fun restoreStatus(id: String): SwRestoreStatus? =
        transport.execute(RESTORE_STATUS, buildJsonObject { put("id", id) }).obj("restoreStatus")?.let {
            SwRestoreStatus(it.text("state").orEmpty(), it.int("mangaProgress") ?: 0, it.int("totalManga") ?: 0)
        }

    // ---- reading answers ---------------------------------------------------------------------------

    private fun meta(json: JsonObject): Map<String, String> =
        json.objects("meta").mapNotNull { m -> m.text("key")?.let { it to m.text("value").orEmpty() } }.toMap()

    private fun chapter(json: JsonObject) = SwChapter(
        id = json.int("id") ?: throw protocol("chapter id"),
        mangaId = json.int("mangaId") ?: 0,
        name = json.text("name").orEmpty(),
        number = json.text("chapterNumber")?.toDoubleOrNull() ?: -1.0,
        sourceOrder = json.int("sourceOrder") ?: 0,
        scanlator = json.text("scanlator")?.takeIf { it.isNotBlank() },
        uploadDateMs = json.long("uploadDate") ?: 0,
        fetchedAt = json.long("fetchedAt") ?: 0,
        pageCount = json.int("pageCount") ?: -1,
        read = json.bool("isRead") == true,
        bookmarked = json.bool("isBookmarked") == true,
        downloaded = json.bool("isDownloaded") == true,
        lastPageRead = json.int("lastPageRead") ?: 0,
        lastReadAt = json.long("lastReadAt") ?: 0,
        url = json.text("url").orEmpty(),
        realUrl = json.text("realUrl"),
        meta = meta(json),
    )

    private fun manga(json: JsonObject): SwManga {
        val source = json.obj("source")
        return SwManga(
            id = json.int("id") ?: throw protocol("series id"),
            title = json.text("title").orEmpty(),
            author = json.text("author")?.takeIf { it.isNotBlank() },
            artist = json.text("artist")?.takeIf { it.isNotBlank() },
            description = json.text("description")?.takeIf { it.isNotBlank() },
            genres = json.strings("genre").filter { it.isNotBlank() },
            status = json.text("status") ?: "UNKNOWN",
            sourceId = json.text("sourceId").orEmpty(),
            url = json.text("url").orEmpty(),
            realUrl = json.text("realUrl"),
            thumbnailUrl = json.text("thumbnailUrl")?.takeIf { it.isNotBlank() },
            thumbnailFetchedAt = json.long("thumbnailUrlLastFetched"),
            inLibrary = json.bool("inLibrary") == true,
            inLibraryAt = json.long("inLibraryAt") ?: 0,
            initialized = json.bool("initialized") == true,
            lastFetchedAt = json.long("lastFetchedAt"),
            chaptersLastFetchedAt = json.long("chaptersLastFetchedAt"),
            unreadCount = json.int("unreadCount") ?: 0,
            downloadCount = json.int("downloadCount") ?: 0,
            bookmarkCount = json.int("bookmarkCount") ?: 0,
            chapterCount = json.obj("chapters")?.int("totalCount") ?: 0,
            categoryIds = json.nodes("categories").mapNotNull { it.int("id") },
            meta = meta(json),
            sourceName = source?.text("name"),
            sourceLang = source?.text("lang"),
            lastReadChapter = json.obj("lastReadChapter")?.let { chapter(it) },
            firstUnreadChapter = json.obj("firstUnreadChapter")?.let { chapter(it) },
            latestUploadedChapter = json.obj("latestUploadedChapter")?.let { chapter(it) },
        )
    }

    private fun category(json: JsonObject) = SwCategory(
        id = json.int("id") ?: throw protocol("category id"),
        name = json.text("name").orEmpty(),
        order = json.int("order") ?: 0,
        isDefault = json.int("id") == 0,
        mangaCount = json.obj("mangas")?.int("totalCount") ?: 0,
        meta = meta(json),
    )

    private fun source(json: JsonObject) = SwSource(
        id = json.text("id") ?: throw protocol("source id"),
        name = json.text("name").orEmpty(),
        displayName = json.text("displayName") ?: json.text("name").orEmpty(),
        lang = json.text("lang").orEmpty(),
        iconUrl = json.text("iconUrl").orEmpty(),
        configurable = json.bool("isConfigurable") == true,
        supportsLatest = json.bool("supportsLatest") == true,
        contentWarning = json.text("contentWarning") ?: "SAFE",
        homeUrl = json.text("homeUrl")?.takeIf { it.isNotBlank() },
        meta = meta(json),
    )

    private fun preferences(list: List<JsonObject>): List<SwPreference> = list.mapIndexedNotNull { position, p ->
        val key = p.text("key")
        val title = p.text("title")
        val summary = p.text("summary")
        val enabled = p.bool("enabled") != false
        val visible = p.bool("visible") != false
        when (p.text("__typename")) {
            "SwitchPreference", "CheckBoxPreference" -> SwPreference.Toggle(
                position, key, title, summary, enabled, visible,
                value = p.bool("boolValue") ?: (p.bool("boolDefault") == true), default = p.bool("boolDefault") == true,
                isSwitch = p.text("__typename") == "SwitchPreference",
            )
            "EditTextPreference" -> SwPreference.Text(
                position, key, title, summary, enabled, visible,
                value = p.text("textValue") ?: p.text("textDefault"), default = p.text("textDefault"),
                dialogTitle = p.text("dialogTitle"), dialogMessage = p.text("dialogMessage"),
            )
            "ListPreference" -> SwPreference.Choice(
                position, key, title, summary, enabled, visible,
                value = p.text("textValue") ?: p.text("textDefault"), default = p.text("textDefault"),
                entries = p.strings("entries"), entryValues = p.strings("entryValues"),
            )
            "MultiSelectListPreference" -> SwPreference.MultiChoice(
                position, key, title, summary, enabled, visible,
                values = if (p["listValue"] is JsonArray) p.strings("listValue") else p.strings("listDefault"), default = p.strings("listDefault"),
                entries = p.strings("entries"), entryValues = p.strings("entryValues"),
            )
            // A kind this version of DebridOne does not know keeps its position (positions address
            // the others) but is not shown.
            else -> null
        }
    }

    /** Never drops a filter: a change addresses a filter by its place in the list. */
    private fun filter(json: JsonObject): SwFilter {
        val name = json.text("name").orEmpty()
        return when (json.text("__typename")) {
            "SeparatorFilter" -> SwFilter.Separator(name)
            "CheckBoxFilter" -> SwFilter.CheckBox(name, json.bool("boolDefault") == true)
            "TriStateFilter" -> SwFilter.TriState(name, json.text("triDefault") ?: "IGNORE")
            "TextFilter" -> SwFilter.Text(name, json.text("textDefault").orEmpty())
            "SelectFilter" -> SwFilter.Select(name, json.strings("values"), json.int("selectDefault") ?: 0)
            "SortFilter" -> SwFilter.Sort(name, json.strings("values"), json.obj("sortDefault")?.int("index"), json.obj("sortDefault")?.bool("ascending") == true)
            "GroupFilter" -> SwFilter.Group(name, json.objects("filters").map { filter(it) })
            // A header, or a kind this version does not know: shown as its name only.
            else -> SwFilter.Header(name)
        }
    }

    private fun filterChange(change: SwFilterChange): JsonObject = buildJsonObject {
        put("position", change.position)
        change.checkBox?.let { put("checkBoxState", it) }
        change.text?.let { put("textState", it) }
        change.select?.let { put("selectState", it) }
        change.triState?.let { put("triState", it) }
        if (change.sortIndex != null) putJsonObject("sortState") { put("index", change.sortIndex); put("ascending", change.sortAscending == true) }
        change.group?.let { put("groupChange", filterChange(it)) }
    }

    private fun store(json: JsonObject) = SwStore(
        indexUrl = json.text("indexUrl") ?: throw protocol("store address"),
        name = json.text("name").orEmpty(),
        badgeLabel = json.text("badgeLabel").orEmpty(),
        website = json.text("contactWebsite")?.takeIf { it.isNotBlank() },
        discord = json.text("contactDiscord")?.takeIf { it.isNotBlank() },
        signingKey = json.text("signingKey").orEmpty(),
        legacy = json.bool("isLegacy") == true,
    )

    private fun extension(json: JsonObject) = SwExtension(
        pkgName = json.text("pkgName") ?: throw protocol("extension package name"),
        name = json.text("name").orEmpty(),
        lang = json.text("lang").orEmpty(),
        versionName = json.text("versionName").orEmpty(),
        versionCode = json.long("versionCodeLong") ?: 0,
        installed = json.bool("isInstalled") == true,
        hasUpdate = json.bool("hasUpdate") == true,
        obsolete = json.bool("isObsolete") == true,
        iconUrl = json.text("iconUrl").orEmpty(),
        contentWarning = json.text("contentWarning") ?: "SAFE",
        storeIndexUrl = json.text("storeIndexUrl"),
        storeName = json.obj("extensionStore")?.text("name"),
    )

    private fun downloadStatus(json: JsonObject) = SwDownloadStatus(
        running = json.text("state") == "STARTED",
        queue = json.objects("queue").map { d ->
            val chapter = d.obj("chapter")
            val manga = d.obj("manga")
            SwDownload(
                chapterId = chapter?.int("id") ?: 0,
                mangaId = manga?.int("id") ?: chapter?.int("mangaId") ?: 0,
                chapterName = chapter?.text("name").orEmpty(),
                mangaTitle = manga?.text("title").orEmpty(),
                position = d.int("position") ?: 0,
                progress = d.text("progress")?.toDoubleOrNull() ?: 0.0,
                state = d.text("state") ?: "QUEUED",
                tries = d.int("tries") ?: 0,
            )
        },
    )

    private fun tracker(json: JsonObject) = SwTracker(
        id = json.int("id") ?: throw protocol("tracker id"),
        name = json.text("name").orEmpty(),
        icon = json.text("icon").orEmpty(),
        loggedIn = json.bool("isLoggedIn") == true,
        authUrl = json.text("authUrl")?.takeIf { it.isNotBlank() },
        tokenExpired = json.bool("isTokenExpired") == true,
        statuses = json.objects("statuses").mapNotNull { s -> s.int("value")?.let { it to s.text("name").orEmpty() } },
        scores = json.strings("scores"),
        supportsTrackDeletion = json.bool("supportsTrackDeletion") == true,
        supportsReadingDates = json.bool("supportsReadingDates") == true,
        supportsPrivateTracking = json.bool("supportsPrivateTracking") == true,
    )

    private fun trackRecord(json: JsonObject) = SwTrackRecord(
        id = json.int("id") ?: throw protocol("track record id"),
        mangaId = json.int("mangaId") ?: 0,
        trackerId = json.int("trackerId") ?: 0,
        remoteId = json.text("remoteId").orEmpty(),
        remoteUrl = json.text("remoteUrl").orEmpty(),
        title = json.text("title").orEmpty(),
        status = json.int("status") ?: 0,
        lastChapterRead = json.text("lastChapterRead")?.toDoubleOrNull() ?: 0.0,
        totalChapters = json.int("totalChapters") ?: 0,
        displayScore = json.text("displayScore").orEmpty(),
        startDate = json.long("startDate") ?: 0,
        finishDate = json.long("finishDate") ?: 0,
        private = json.bool("private") == true,
    )

    private fun protocol(what: String) = SuwayomiException(SuwayomiException.Kind.PROTOCOL, "The manga server's answer had no $what.")

    private fun ints(values: Collection<Int>) = JsonArray(values.map(::JsonPrimitive))

    private inline fun input(build: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject { put("input", buildJsonObject(build)) }

    /** The documents, so that a test can run each of them against the pinned server. */
    internal companion object {
        private const val PAGE = 400
        private const val SOURCE_TIMEOUT = 120
        private const val INSTALL_TIMEOUT = 300
        private val NAME = Regex("[A-Za-z][A-Za-z0-9]*")

        /** GraphQL variables are written `^name` here, because `$` is Kotlin's. */
        private fun document(text: String): String = text.trimIndent().replace('^', '$')

        private const val CHAPTER_FIELDS = """
            fragment ChapterFields on ChapterType {
              id mangaId name chapterNumber sourceOrder scanlator uploadDate fetchedAt pageCount
              isRead isBookmarked isDownloaded lastPageRead lastReadAt url realUrl
              meta { key value }
            }"""

        private const val MANGA_FIELDS = """
            fragment MangaFields on MangaType {
              id title author artist description genre status sourceId url realUrl
              thumbnailUrl thumbnailUrlLastFetched inLibrary inLibraryAt initialized lastFetchedAt chaptersLastFetchedAt
              unreadCount downloadCount bookmarkCount
              chapters { totalCount }
              categories { nodes { id } }
              meta { key value }
              source { name lang }
              lastReadChapter { ...ChapterFields }
              firstUnreadChapter { ...ChapterFields }
              latestUploadedChapter { ...ChapterFields }
            }"""

        private const val SOURCE_FIELDS = """
            fragment SourceFields on SourceType {
              id name displayName lang iconUrl isConfigurable supportsLatest contentWarning homeUrl
              meta { key value }
            }"""

        private const val PREFERENCE_FIELDS = """
            fragment PreferenceFields on Preference {
              __typename
              ... on CheckBoxPreference { key title summary enabled visible boolValue: currentValue boolDefault: default }
              ... on SwitchPreference { key title summary enabled visible boolValue: currentValue boolDefault: default }
              ... on EditTextPreference { key title summary enabled visible textValue: currentValue textDefault: default dialogTitle dialogMessage }
              ... on ListPreference { key title summary enabled visible textValue: currentValue textDefault: default entries entryValues }
              ... on MultiSelectListPreference { key title summary enabled visible listValue: currentValue listDefault: default entries entryValues }
            }"""

        private const val LEAF_FILTERS = """
              ... on HeaderFilter { name }
              ... on SeparatorFilter { name }
              ... on CheckBoxFilter { name boolDefault: default }
              ... on TriStateFilter { name triDefault: default }
              ... on TextFilter { name textDefault: default }
              ... on SelectFilter { name values selectDefault: default }
              ... on SortFilter { name values sortDefault: default { index ascending } }"""

        private const val FILTER_FIELDS = """
            fragment FilterFields on Filter {
              __typename
              $LEAF_FILTERS
              ... on GroupFilter { name filters { __typename $LEAF_FILTERS } }
            }"""

        private const val EXTENSION_FIELDS = """
            fragment ExtensionFields on ExtensionType {
              pkgName name lang versionName versionCodeLong isInstalled hasUpdate isObsolete iconUrl contentWarning storeIndexUrl
              extensionStore { name }
            }"""

        private const val STORE_FIELDS = """
            fragment StoreFields on ExtensionStoreType { indexUrl name badgeLabel contactWebsite contactDiscord signingKey isLegacy }"""

        private const val DOWNLOAD_FIELDS = """
            fragment DownloadFields on DownloadStatus {
              state
              queue { position progress state tries chapter { id mangaId name } manga { id title } }
            }"""

        private const val TRACKER_FIELDS = """
            fragment TrackerFields on TrackerType {
              id name icon isLoggedIn authUrl isTokenExpired scores statuses { name value }
              supportsTrackDeletion supportsReadingDates supportsPrivateTracking
            }"""

        private const val TRACK_RECORD_FIELDS = """
            fragment TrackRecordFields on TrackRecordType {
              id mangaId trackerId remoteId remoteUrl title status lastChapterRead totalChapters displayScore startDate finishDate private
            }"""

        val ABOUT = document("query { aboutServer { name version buildType buildTime github } }")
        val SET_SETTINGS = document("mutation(^input: SetSettingsInput!) { setSettings(input: ^input) { settings { port } } }")

        val LIBRARY = document(
            """query(^first: Int!, ^offset: Int!) {
              mangas(condition: { inLibrary: true }, order: [{ by: ID }], first: ^first, offset: ^offset) { nodes { ...MangaFields } }
            } $MANGA_FIELDS $CHAPTER_FIELDS""",
        )
        val MANGA = document("query(^id: Int!) { manga(id: ^id) { ...MangaFields } } $MANGA_FIELDS $CHAPTER_FIELDS")
        val MANGAS_BY_ID = document("query(^ids: [Int!]!) { mangas(filter: { id: { in: ^ids } }) { nodes { ...MangaFields } } } $MANGA_FIELDS $CHAPTER_FIELDS")
        val MANGAS_BY_URL = document(
            """query(^source: LongString!, ^url: String!) {
              mangas(condition: { sourceId: ^source, url: ^url }) { nodes { ...MangaFields } }
            } $MANGA_FIELDS $CHAPTER_FIELDS""",
        )
        val FETCH_MANGA_AND_CHAPTERS = document(
            """mutation(^input: FetchMangaAndChaptersInput!) {
              fetchMangaAndChapters(input: ^input) { manga { ...MangaFields } chapters { ...ChapterFields } }
            } $MANGA_FIELDS $CHAPTER_FIELDS""",
        )
        val UPDATE_MANGAS = document("mutation(^input: UpdateMangasInput!) { updateMangas(input: ^input) { mangas { id inLibrary } } }")
        val SET_MANGA_META = document("mutation(^input: SetMangaMetaInput!) { setMangaMeta(input: ^input) { meta { key value } } }")
        val DELETE_MANGA_META = document("mutation(^input: DeleteMangaMetaInput!) { deleteMangaMeta(input: ^input) { clientMutationId } }")

        val CHAPTERS = document(
            """query(^mangaId: Int!) {
              chapters(condition: { mangaId: ^mangaId }, order: [{ by: SOURCE_ORDER, byType: ASC }]) { nodes { ...ChapterFields } }
            } $CHAPTER_FIELDS""",
        )
        val CHAPTER = document("query(^id: Int!) { chapter(id: ^id) { ...ChapterFields } } $CHAPTER_FIELDS")
        val RECENTLY_READ = document(
            """query(^since: LongString!, ^first: Int!) {
              chapters(filter: { lastReadAt: { greaterThan: ^since } }, order: [{ by: LAST_READ_AT, byType: DESC }], first: ^first) {
                nodes { ...ChapterFields }
              }
            } $CHAPTER_FIELDS""",
        )
        val DOWNLOADED_CHAPTERS = document(
            """query { chapters(condition: { isDownloaded: true }, order: [{ by: FETCHED_AT, byType: DESC }]) { nodes { ...ChapterFields } } } $CHAPTER_FIELDS""",
        )
        val BOOKMARKED_CHAPTERS = document(
            """query { chapters(condition: { isBookmarked: true }, order: [{ by: LAST_READ_AT, byType: DESC }]) { nodes { ...ChapterFields } } } $CHAPTER_FIELDS""",
        )
        val FETCH_CHAPTER_PAGES = document(
            """mutation(^input: FetchChapterPagesInput!) { fetchChapterPages(input: ^input) { pages chapter { ...ChapterFields } } } $CHAPTER_FIELDS""",
        )
        val UPDATE_CHAPTERS = document(
            """mutation(^input: UpdateChaptersInput!) { updateChapters(input: ^input) { chapters { ...ChapterFields } } } $CHAPTER_FIELDS""",
        )
        val SET_CHAPTER_META = document("mutation(^input: SetChapterMetaInput!) { setChapterMeta(input: ^input) { meta { key value } } }")

        val GLOBAL_METAS = document("query { metas { nodes { key value } } }")
        val SET_GLOBAL_META = document("mutation(^input: SetGlobalMetaInput!) { setGlobalMeta(input: ^input) { meta { key value } } }")
        val DELETE_GLOBAL_META = document("mutation(^input: DeleteGlobalMetaInput!) { deleteGlobalMeta(input: ^input) { clientMutationId } }")

        private const val CATEGORY_FIELDS = "id name order mangas { totalCount } meta { key value }"
        val CATEGORIES = document("query { categories { nodes { $CATEGORY_FIELDS } } }")
        val CREATE_CATEGORY = document("mutation(^input: CreateCategoryInput!) { createCategory(input: ^input) { category { $CATEGORY_FIELDS } } }")
        val UPDATE_CATEGORY = document("mutation(^input: UpdateCategoryInput!) { updateCategory(input: ^input) { category { id name } } }")
        val UPDATE_CATEGORY_ORDER = document("mutation(^input: UpdateCategoryOrderInput!) { updateCategoryOrder(input: ^input) { categories { id order } } }")
        val DELETE_CATEGORY = document("mutation(^input: DeleteCategoryInput!) { deleteCategory(input: ^input) { category { id } } }")
        val UPDATE_MANGA_CATEGORIES = document(
            "mutation(^input: UpdateMangaCategoriesInput!) { updateMangaCategories(input: ^input) { manga { id categories { nodes { id } } } } }",
        )

        val SOURCES = document("query { sources { nodes { ...SourceFields } } } $SOURCE_FIELDS")
        val SOURCE_DETAILS = document(
            """query(^id: LongString!) {
              source(id: ^id) { ...SourceFields preferences { ...PreferenceFields } filters { ...FilterFields } }
            } $SOURCE_FIELDS $PREFERENCE_FIELDS $FILTER_FIELDS""",
        )
        val FETCH_SOURCE_MANGA = document(
            """mutation(^input: FetchSourceMangaInput!) {
              fetchSourceManga(input: ^input) { hasNextPage mangas { ...MangaFields } }
            } $MANGA_FIELDS $CHAPTER_FIELDS""",
        )
        val UPDATE_SOURCE_PREFERENCE = document(
            """mutation(^input: UpdateSourcePreferenceInput!) {
              updateSourcePreference(input: ^input) { preferences { ...PreferenceFields } }
            } $PREFERENCE_FIELDS""",
        )
        val SET_SOURCE_META = document("mutation(^input: SetSourceMetaInput!) { setSourceMeta(input: ^input) { meta { key value } } }")

        val STORES = document("query { extensionStores { nodes { ...StoreFields } } } $STORE_FIELDS")
        val ADD_STORE = document("mutation(^input: AddExtensionStoreInput!) { addExtensionStore(input: ^input) { extensionStore { ...StoreFields } } } $STORE_FIELDS")
        val REMOVE_STORE = document("mutation(^input: RemoveExtensionStoreInput!) { removeExtensionStore(input: ^input) { clientMutationId } }")
        val EXTENSIONS = document("query { extensions { nodes { ...ExtensionFields } } } $EXTENSION_FIELDS")
        val EXTENSION_SOURCES = document("query { extensions(condition: { isInstalled: true }) { nodes { pkgName source { nodes { id } } } } }")
        val FETCH_EXTENSIONS = document("mutation(^input: FetchExtensionsInput!) { fetchExtensions(input: ^input) { extensions { ...ExtensionFields } } } $EXTENSION_FIELDS")
        val UPDATE_EXTENSION = document("mutation(^input: UpdateExtensionInput!) { updateExtension(input: ^input) { extension { ...ExtensionFields } } } $EXTENSION_FIELDS")
        val INSTALL_EXTERNAL_EXTENSION = document(
            "mutation(^input: InstallExternalExtensionInput!) { installExternalExtension(input: ^input) { extension { ...ExtensionFields } } } $EXTENSION_FIELDS",
        )

        val DOWNLOAD_STATUS = document("query { downloadStatus { ...DownloadFields } } $DOWNLOAD_FIELDS")
        val ENQUEUE_DOWNLOADS = document(
            "mutation(^input: EnqueueChapterDownloadsInput!) { enqueueChapterDownloads(input: ^input) { downloadStatus { ...DownloadFields } } } $DOWNLOAD_FIELDS",
        )
        val DEQUEUE_DOWNLOADS = document(
            "mutation(^input: DequeueChapterDownloadsInput!) { dequeueChapterDownloads(input: ^input) { downloadStatus { ...DownloadFields } } } $DOWNLOAD_FIELDS",
        )
        val DELETE_DOWNLOADED = document("mutation(^input: DeleteDownloadedChaptersInput!) { deleteDownloadedChapters(input: ^input) { chapters { id isDownloaded } } }")
        val START_DOWNLOADER = document("mutation(^input: StartDownloaderInput!) { startDownloader(input: ^input) { downloadStatus { ...DownloadFields } } } $DOWNLOAD_FIELDS")
        val STOP_DOWNLOADER = document("mutation(^input: StopDownloaderInput!) { stopDownloader(input: ^input) { downloadStatus { ...DownloadFields } } } $DOWNLOAD_FIELDS")
        val CLEAR_DOWNLOADER = document("mutation(^input: ClearDownloaderInput!) { clearDownloader(input: ^input) { downloadStatus { ...DownloadFields } } } $DOWNLOAD_FIELDS")

        val UPDATE_LIBRARY = document("mutation(^input: UpdateLibraryInput!) { updateLibrary(input: ^input) { updateStatus { jobsInfo { isRunning } } } }")
        val UPDATE_STOP = document("mutation(^input: UpdateStopInput!) { updateStop(input: ^input) { clientMutationId } }")
        val LIBRARY_UPDATE_STATUS = document("query { libraryUpdateStatus { jobsInfo { isRunning finishedJobs totalJobs } } }")

        val TRACKERS = document("query { trackers { nodes { ...TrackerFields } } } $TRACKER_FIELDS")
        val LOGIN_TRACKER_CREDENTIALS = document("mutation(^input: LoginTrackerCredentialsInput!) { loginTrackerCredentials(input: ^input) { isLoggedIn } }")
        val LOGIN_TRACKER_OAUTH = document("mutation(^input: LoginTrackerOAuthInput!) { loginTrackerOAuth(input: ^input) { isLoggedIn } }")
        val LOGOUT_TRACKER = document("mutation(^input: LogoutTrackerInput!) { logoutTracker(input: ^input) { isLoggedIn } }")
        val SEARCH_TRACKER = document(
            """query(^trackerId: Int!, ^query: String!) {
              searchTracker(input: { trackerId: ^trackerId, query: ^query }) {
                trackSearches { trackerId remoteId title coverUrl summary publishingStatus publishingType startDate totalChapters trackingUrl }
              }
            }""",
        )
        val TRACK_RECORDS = document("query(^mangaId: Int!) { manga(id: ^mangaId) { trackRecords { nodes { ...TrackRecordFields } } } } $TRACK_RECORD_FIELDS")
        val BIND_TRACK = document("mutation(^input: BindTrackInput!) { bindTrack(input: ^input) { trackRecord { ...TrackRecordFields } } } $TRACK_RECORD_FIELDS")
        val UNBIND_TRACK = document("mutation(^input: UnbindTrackInput!) { unbindTrack(input: ^input) { clientMutationId } }")
        val UPDATE_TRACK = document("mutation(^input: UpdateTrackInput!) { updateTrack(input: ^input) { trackRecord { ...TrackRecordFields } } } $TRACK_RECORD_FIELDS")
        val TRACK_PROGRESS = document("mutation(^input: TrackProgressInput!) { trackProgress(input: ^input) { clientMutationId } }")

        val CREATE_BACKUP = document("mutation { createBackup(input: {}) { url } }")
        val RESTORE_BACKUP = document("mutation(^input: RestoreBackupInput!) { restoreBackup(input: ^input) { id status { state mangaProgress totalManga } } }")
        val RESTORE_STATUS = document("query(^id: String!) { restoreStatus(id: ^id) { state mangaProgress totalManga } }")

        /** Every document with a name, for the test that validates them against the pinned server. */
        val ALL: Map<String, String> by lazy {
            mapOf(
                "ABOUT" to ABOUT, "SET_SETTINGS" to SET_SETTINGS, "LIBRARY" to LIBRARY, "MANGA" to MANGA, "MANGAS_BY_ID" to MANGAS_BY_ID,
                "MANGAS_BY_URL" to MANGAS_BY_URL, "FETCH_MANGA_AND_CHAPTERS" to FETCH_MANGA_AND_CHAPTERS, "UPDATE_MANGAS" to UPDATE_MANGAS,
                "SET_MANGA_META" to SET_MANGA_META, "DELETE_MANGA_META" to DELETE_MANGA_META, "CHAPTERS" to CHAPTERS, "CHAPTER" to CHAPTER,
                "RECENTLY_READ" to RECENTLY_READ, "DOWNLOADED_CHAPTERS" to DOWNLOADED_CHAPTERS, "BOOKMARKED_CHAPTERS" to BOOKMARKED_CHAPTERS,
                "FETCH_CHAPTER_PAGES" to FETCH_CHAPTER_PAGES, "UPDATE_CHAPTERS" to UPDATE_CHAPTERS, "SET_CHAPTER_META" to SET_CHAPTER_META,
                "GLOBAL_METAS" to GLOBAL_METAS, "SET_GLOBAL_META" to SET_GLOBAL_META, "DELETE_GLOBAL_META" to DELETE_GLOBAL_META,
                "CATEGORIES" to CATEGORIES, "CREATE_CATEGORY" to CREATE_CATEGORY, "UPDATE_CATEGORY" to UPDATE_CATEGORY,
                "UPDATE_CATEGORY_ORDER" to UPDATE_CATEGORY_ORDER, "DELETE_CATEGORY" to DELETE_CATEGORY, "UPDATE_MANGA_CATEGORIES" to UPDATE_MANGA_CATEGORIES,
                "SOURCES" to SOURCES, "SOURCE_DETAILS" to SOURCE_DETAILS, "FETCH_SOURCE_MANGA" to FETCH_SOURCE_MANGA,
                "UPDATE_SOURCE_PREFERENCE" to UPDATE_SOURCE_PREFERENCE, "SET_SOURCE_META" to SET_SOURCE_META,
                "STORES" to STORES, "ADD_STORE" to ADD_STORE, "REMOVE_STORE" to REMOVE_STORE, "EXTENSIONS" to EXTENSIONS,
                "EXTENSION_SOURCES" to EXTENSION_SOURCES, "FETCH_EXTENSIONS" to FETCH_EXTENSIONS, "UPDATE_EXTENSION" to UPDATE_EXTENSION,
                "INSTALL_EXTERNAL_EXTENSION" to INSTALL_EXTERNAL_EXTENSION,
                "DOWNLOAD_STATUS" to DOWNLOAD_STATUS, "ENQUEUE_DOWNLOADS" to ENQUEUE_DOWNLOADS, "DEQUEUE_DOWNLOADS" to DEQUEUE_DOWNLOADS,
                "DELETE_DOWNLOADED" to DELETE_DOWNLOADED, "START_DOWNLOADER" to START_DOWNLOADER, "STOP_DOWNLOADER" to STOP_DOWNLOADER,
                "CLEAR_DOWNLOADER" to CLEAR_DOWNLOADER, "UPDATE_LIBRARY" to UPDATE_LIBRARY, "UPDATE_STOP" to UPDATE_STOP,
                "LIBRARY_UPDATE_STATUS" to LIBRARY_UPDATE_STATUS, "TRACKERS" to TRACKERS, "LOGIN_TRACKER_CREDENTIALS" to LOGIN_TRACKER_CREDENTIALS,
                "LOGIN_TRACKER_OAUTH" to LOGIN_TRACKER_OAUTH, "LOGOUT_TRACKER" to LOGOUT_TRACKER, "SEARCH_TRACKER" to SEARCH_TRACKER,
                "TRACK_RECORDS" to TRACK_RECORDS, "BIND_TRACK" to BIND_TRACK, "UNBIND_TRACK" to UNBIND_TRACK, "UPDATE_TRACK" to UPDATE_TRACK,
                "TRACK_PROGRESS" to TRACK_PROGRESS, "CREATE_BACKUP" to CREATE_BACKUP, "RESTORE_BACKUP" to RESTORE_BACKUP, "RESTORE_STATUS" to RESTORE_STATUS,
            )
        }
    }
}
