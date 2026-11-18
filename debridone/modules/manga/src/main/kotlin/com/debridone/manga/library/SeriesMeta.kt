package com.debridone.manga.library

import com.debridone.manga.suwayomi.SwChapter
import com.debridone.manga.suwayomi.SwDownload
import com.debridone.manga.suwayomi.SwManga
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What DebridOne keeps about a series that Suwayomi has no field for. It is stored in Suwayomi's own
 * per-series key-value store ("meta"), under keys that start with `debridone.`, so it lives with
 * that profile's library, is part of Suwayomi's backups, and is left alone by other Suwayomi clients.
 */
internal object MetaKeys {
    /** [SeriesState] as JSON. */
    const val STATE = "debridone.state"

    /** [SeriesOverlay] as JSON. */
    const val OVERLAY = "debridone.details"

    /** A list of [StoredBookmark] as JSON. */
    const val BOOKMARKS = "debridone.bookmarks"

    /** On a chapter: how far into the last page the reader was, 0..1. */
    const val SCROLL_OFFSET = "debridone.offset"

    /** Global: reading before this time (seconds) is not shown as history. */
    const val HISTORY_CUTOFF = "debridone.historyCutoff"

    /** Global: an id DebridOne gave this library, to tell one server's data from another's. */
    const val LIBRARY_ID = "debridone.libraryId"
}

@Serializable
internal data class StoredLink(val provider: String, val remoteId: String, val url: String? = null, val linkedAt: Long = 0, val tracked: Boolean = false)

/** The person's own choices about a series. */
@Serializable
internal data class SeriesState(
    val status: String? = null,
    val favorite: Boolean = false,
    /** Set when the person (or a metadata provider) decided the shelf; otherwise it is worked out from the genres. */
    val mediaType: String? = null,
    val customTags: List<String> = emptyList(),
    val reader: String? = null,
    val links: List<StoredLink> = emptyList(),
)

/**
 * Details that did not come from the series' source: typed by hand, or taken from a metadata
 * provider. A field that is set here is shown instead of the source's; a field that is null
 * follows the source. A field named in [locked] was typed by hand and is never changed by a
 * provider or a refresh.
 */
@Serializable
internal data class SeriesOverlay(
    val title: String? = null,
    val altTitles: List<String>? = null,
    val description: String? = null,
    val authors: List<String>? = null,
    val artists: List<String>? = null,
    val tags: List<String>? = null,
    val status: String? = null,
    /** The person said the publication status is unknown, whatever the source says. */
    val statusCleared: Boolean = false,
    val originalLanguage: String? = null,
    val coverUrl: String? = null,
    val coverFile: String? = null,
    val bannerUrl: String? = null,
    val locked: List<String> = emptyList(),
)

@Serializable
internal data class StoredBookmark(val chapterId: Int, val page: Int, val note: String = "", val at: Long = 0)

internal object MetaCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val bookmarkList = ListSerializer(StoredBookmark.serializer())

    fun state(meta: Map<String, String>): SeriesState =
        meta[MetaKeys.STATE]?.let { runCatching { json.decodeFromString(SeriesState.serializer(), it) }.getOrNull() } ?: SeriesState()

    fun overlay(meta: Map<String, String>): SeriesOverlay =
        meta[MetaKeys.OVERLAY]?.let { runCatching { json.decodeFromString(SeriesOverlay.serializer(), it) }.getOrNull() } ?: SeriesOverlay()

    fun bookmarks(meta: Map<String, String>): List<StoredBookmark> =
        meta[MetaKeys.BOOKMARKS]?.let { runCatching { json.decodeFromString(bookmarkList, it) }.getOrNull() }.orEmpty()

    fun encode(state: SeriesState): String = json.encodeToString(SeriesState.serializer(), state)
    fun encode(overlay: SeriesOverlay): String = json.encodeToString(SeriesOverlay.serializer(), overlay)
    fun encode(bookmarks: List<StoredBookmark>): String = json.encodeToString(bookmarkList, bookmarks)
}

/** Turns what Suwayomi returns into the series and chapters the screens show. */
internal class SeriesMapper(
    private val profileId: Long,
    /** Reading at or before this time (seconds) has been cleared from the history. */
    private val historyCutoff: Long,
) {
    fun series(manga: SwManga): Series {
        val state = MetaCodec.state(manga.meta)
        val overlay = MetaCodec.overlay(manga.meta)
        val locked = overlay.locked.toSet()

        // What was typed by hand or taken from a metadata provider is shown instead of the source's
        // own value; a field nobody set follows the source.
        fun text(fromSource: String?, other: String?): String? = other?.takeIf { it.isNotBlank() } ?: fromSource?.takeIf { it.isNotBlank() }
        fun list(fromSource: List<String>, other: List<String>?): List<String> = other ?: fromSource

        val local = manga.sourceId == LOCAL_SOURCE_ID
        val tags = list(manga.genres, overlay.tags)
        val language = overlay.originalLanguage?.takeIf { it.isNotBlank() }
        val lastRead = manga.lastReadChapter?.takeIf { it.lastReadAt > historyCutoff }
        return Series(
            id = manga.id.toLong(),
            profileId = profileId,
            origin = if (local) SeriesOrigin.LOCAL else SeriesOrigin.EXTENSION,
            extensionId = manga.sourceId,
            sourceId = manga.url,
            sourceName = if (local) "Local files" else manga.sourceName.orEmpty(),
            title = text(manga.title, overlay.title) ?: manga.title,
            altTitles = overlay.altTitles.orEmpty(),
            description = text(manga.description, overlay.description).orEmpty(),
            authors = list(people(manga.author), overlay.authors),
            artists = list(people(manga.artist), overlay.artists),
            tags = tags,
            customTags = state.customTags,
            status = if (overlay.statusCleared) null else text(publicationStatus(manga.status), overlay.status),
            originalLanguage = language,
            mediaType = state.mediaType?.let(MediaType::fromKey) ?: MangaClassifier.classify(language, tags),
            coverUrl = text(sourceCover(manga), overlay.coverUrl).orEmpty(),
            coverFile = overlay.coverFile?.takeIf { it.isNotBlank() },
            bannerUrl = overlay.bannerUrl?.takeIf { it.isNotBlank() },
            lockedFields = locked,
            sourceRaw = null,
            inLibrary = manga.inLibrary,
            libraryStatus = if (manga.inLibrary) LibraryStatus.fromKey(state.status) else null,
            favorite = manga.inLibrary && state.favorite,
            addedAt = manga.inLibraryAt.takeIf { manga.inLibrary && it > 0 }?.times(1000),
            createdAt = (manga.lastFetchedAt ?: 0) * 1000,
            updatedAt = (manga.lastFetchedAt ?: 0) * 1000,
            chaptersRefreshedAt = manga.chaptersLastFetchedAt?.takeIf { it > 0 }?.times(1000),
            lastReadAt = lastRead?.lastReadAt?.times(1000),
            lastChapterId = lastRead?.id?.toLong(),
            readerOverrides = state.reader,
        )
    }

    fun summary(manga: SwManga): SeriesSummary = SeriesSummary(
        series = series(manga),
        chapterCount = manga.chapterCount,
        readCount = (manga.chapterCount - manga.unreadCount).coerceAtLeast(0),
        downloadedCount = manga.downloadCount,
        latestChapterAt = manga.latestUploadedChapter?.uploadDateMs?.takeIf { it > 0 },
    )

    companion object {
        /** The id Suwayomi gives its built-in source that reads folders and archives on the server's disk. */
        const val LOCAL_SOURCE_ID = "0"

        /** The address of the source's own cover; it changes when the server fetches the cover again. */
        fun sourceCover(manga: SwManga): String? = manga.thumbnailUrl?.let { "$it?v=${manga.thumbnailFetchedAt ?: 0}" }

        fun people(text: String?): List<String> = text.orEmpty().split(Regex("\\s*[,;]\\s*")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        fun publicationStatus(status: String): String? = when (status) {
            "ONGOING" -> "Ongoing"
            "COMPLETED", "PUBLISHING_FINISHED" -> "Completed"
            "CANCELLED" -> "Cancelled"
            "ON_HIATUS" -> "Hiatus"
            else -> null
        }

        fun chapter(chapter: SwChapter, language: String?, queued: SwDownload?): Chapter {
            val number = chapter.number.takeIf { it >= 0 }
            val state = when {
                chapter.downloaded -> DownloadState.DONE
                queued == null -> null
                queued.state == "DOWNLOADING" -> DownloadState.RUNNING
                queued.state == "ERROR" -> DownloadState.FAILED
                queued.state == "FINISHED" -> DownloadState.DONE
                else -> DownloadState.QUEUED
            }
            val pages = chapter.pageCount.coerceAtLeast(0)
            return Chapter(
                id = chapter.id.toLong(),
                seriesId = chapter.mangaId.toLong(),
                sourceId = chapter.url,
                // A Mihon source names a chapter in full ("Vol.2 Ch.14 - Title"); nothing is added to it.
                title = chapter.name.ifBlank { number?.let { "Chapter ${trim(it)}" } ?: "Chapter" },
                chapterNumber = "",
                chapterSort = number,
                volumeNumber = "",
                language = language?.takeIf { it.isNotBlank() && it != "all" && it != "localsourcelang" },
                groupName = chapter.scanlator.orEmpty(),
                releasedAt = chapter.uploadDateMs,
                position = chapter.sourceOrder,
                pageCount = pages,
                lastPage = chapter.lastPageRead.coerceAtLeast(0),
                scrollOffset = chapter.meta[MetaKeys.SCROLL_OFFSET]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f,
                read = chapter.read,
                readAt = chapter.lastReadAt.takeIf { chapter.read && it > 0 }?.times(1000),
                progressAt = chapter.lastReadAt.takeIf { it > 0 }?.times(1000),
                firstSeenAt = chapter.fetchedAt * 1000,
                downloadState = state,
                // Downloaded chapters are on the server's disk and are read through the server.
                downloadDir = if (chapter.downloaded) "server" else null,
                downloadPages = if (chapter.downloaded) pages else ((queued?.progress ?: 0.0) * pages).toInt(),
                downloadError = if (state == DownloadState.FAILED) "The download failed after ${queued?.tries ?: 0} attempts. The source may be unreachable." else null,
            )
        }

        private fun trim(number: Double): String = if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()
    }
}
