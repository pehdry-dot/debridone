package com.debridone.manga.library

/** The two shelves of the module. Both use the same sources and engine; they differ in how they are browsed and read. */
enum class MediaType(val key: String, val label: String) {
    MANGA("manga", "Manga"),
    MANHWA("manhwa", "Manhwa");

    companion object {
        fun fromKey(key: String?): MediaType = entries.firstOrNull { it.key == key } ?: MANGA
    }
}

enum class LibraryStatus(val key: String, val label: String) {
    READING("reading", "Currently Reading"),
    PLAN("plan", "Plan to Read"),
    COMPLETED("completed", "Completed"),
    ON_HOLD("on_hold", "On Hold"),
    DROPPED("dropped", "Dropped");

    companion object {
        fun fromKey(key: String?): LibraryStatus? = entries.firstOrNull { it.key == key }
    }
}

enum class SeriesOrigin(val key: String) {
    /** Comes from a source extension. */
    EXTENSION("extension"),

    /** Files on this computer; needs no extension and no network. */
    LOCAL("local");

    companion object {
        fun fromKey(key: String?): SeriesOrigin = entries.firstOrNull { it.key == key } ?: EXTENSION
    }
}

enum class DownloadState(val key: String) {
    QUEUED("queued"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed");

    companion object {
        fun fromKey(key: String?): DownloadState? = entries.firstOrNull { it.key == key }
    }
}

/** The fields of a series that can be edited by hand and locked against automatic refreshes. */
object SeriesField {
    const val TITLE = "title"
    const val ALT_TITLES = "alt_titles"
    const val DESCRIPTION = "description"
    const val AUTHORS = "authors"
    const val ARTISTS = "artists"
    const val TAGS = "tags"
    const val STATUS = "status"
    const val COVER = "cover"
    const val MEDIA_TYPE = "media_type"
    const val LANGUAGE = "original_language"

    val all = listOf(TITLE, ALT_TITLES, DESCRIPTION, AUTHORS, ARTISTS, TAGS, STATUS, COVER, MEDIA_TYPE, LANGUAGE)

    fun label(field: String): String = when (field) {
        TITLE -> "Title"
        ALT_TITLES -> "Alternative titles"
        DESCRIPTION -> "Synopsis"
        AUTHORS -> "Author"
        ARTISTS -> "Artist"
        TAGS -> "Genres"
        STATUS -> "Publication status"
        COVER -> "Cover"
        MEDIA_TYPE -> "Manga or Manhwa"
        LANGUAGE -> "Original language"
        else -> field
    }
}

const val LOCAL_EXTENSION_ID = "local"

data class Series(
    val id: Long,
    val profileId: Long,
    val origin: SeriesOrigin,
    /** The source's extension id, or [LOCAL_EXTENSION_ID]. */
    val extensionId: String,
    /** The series' id inside its source; for local series, its folder or file. */
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val altTitles: List<String>,
    val description: String,
    val authors: List<String>,
    val artists: List<String>,
    val tags: List<String>,
    val customTags: List<String>,
    /** `Ongoing`, `Completed`, `Cancelled`, `Hiatus`, or null when unknown. */
    val status: String?,
    /** Language code of the original work ("ja", "ko", "zh"…), when known. */
    val originalLanguage: String?,
    val mediaType: MediaType,
    val coverUrl: String,
    /** A cover kept on disk: chosen by the user, or saved for offline use. */
    val coverFile: String?,
    val bannerUrl: String?,
    val lockedFields: Set<String>,
    /** The series exactly as the source returned it; handed back to the source when it asks for it. */
    val sourceRaw: String?,
    val inLibrary: Boolean,
    val libraryStatus: LibraryStatus?,
    val favorite: Boolean,
    val addedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val chaptersRefreshedAt: Long?,
    val lastReadAt: Long?,
    val lastChapterId: Long?,
    val readerOverrides: String?,
) {
    val isLocal: Boolean get() = origin == SeriesOrigin.LOCAL
}

/** A series with the numbers a cover card shows. */
data class SeriesSummary(
    val series: Series,
    val chapterCount: Int,
    val readCount: Int,
    val downloadedCount: Int,
    val latestChapterAt: Long?,
) {
    val unreadCount: Int get() = (chapterCount - readCount).coerceAtLeast(0)

    /** 0..1, by chapters read. */
    val progress: Float get() = if (chapterCount == 0) 0f else readCount.toFloat() / chapterCount
}

data class Chapter(
    val id: Long,
    val seriesId: Long,
    val sourceId: String,
    val title: String,
    val chapterNumber: String,
    /** [chapterNumber] as a number, when it is one. */
    val chapterSort: Double?,
    val volumeNumber: String,
    val language: String?,
    val groupName: String,
    val releasedAt: Long,
    /** Order in which the source (or the folder) listed it. */
    val position: Int,
    val pageCount: Int,
    /** Zero-based page the reader was on. */
    val lastPage: Int,
    /** Position inside [lastPage], 0..1, for continuous scrolling. */
    val scrollOffset: Float,
    val read: Boolean,
    val readAt: Long?,
    val progressAt: Long?,
    val firstSeenAt: Long,
    val downloadState: DownloadState?,
    val downloadDir: String?,
    val downloadPages: Int,
    val downloadError: String?,
) {
    val isDownloaded: Boolean get() = downloadState == DownloadState.DONE && downloadDir != null

    /** "Vol. 2 Ch. 14 — Title", with whatever parts exist. */
    val displayName: String
        get() {
            val parts = ArrayList<String>()
            if (volumeNumber.isNotBlank()) parts += "Vol. $volumeNumber"
            if (chapterNumber.isNotBlank()) parts += "Ch. $chapterNumber"
            val head = parts.joinToString(" ")
            return when {
                head.isEmpty() -> title.ifBlank { "Chapter" }
                title.isBlank() || title == chapterNumber -> head
                else -> "$head — $title"
            }
        }
}

data class Bookmark(
    val id: Long,
    val seriesId: Long,
    val chapterId: Long,
    val page: Int,
    val note: String,
    val createdAt: Long,
)

data class HistoryEntry(
    val id: Long,
    val series: Series,
    val chapter: Chapter,
    val page: Int,
    val readAt: Long,
)

data class MangaCollection(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val seriesCount: Int,
)

/** Where to pick a series up again. */
data class ContinueEntry(
    val summary: SeriesSummary,
    val chapter: Chapter,
)

data class SourcePreference(
    val extensionId: String,
    val enabled: Boolean,
    /** Position among the preferred sources (lower first), or null when it is not one of them. */
    val preferredRank: Int?,
)

/**
 * One step of a source test and how it went. [kind] names the kind of failure (a
 * [com.debridone.manga.sources.FailureKind]) when it failed; [technical] is the server's own
 * report, shown only when the details are opened.
 */
data class HealthStep(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val kind: String? = null,
    val millis: Long? = null,
    val technical: String? = null,
)

/** The result of the last "Test source" on this computer. [kind] is the kind of the failure that stopped it. */
data class SourceHealth(
    val extensionId: String,
    val testedAt: Long,
    val ok: Boolean,
    val steps: List<HealthStep>,
    val message: String?,
    val kind: String? = null,
)

data class MetadataLink(
    val seriesId: Long,
    /** `anilist`, `mangadex` or `myanimelist`. */
    val provider: String,
    val remoteId: String,
    val url: String?,
    val linkedAt: Long,
    /** Reading progress is sent to this service. */
    val tracked: Boolean,
)

enum class LibrarySort(val label: String) {
    TITLE("Title"),
    DATE_ADDED("Date added"),
    RECENTLY_READ("Recently read"),
    RECENTLY_UPDATED("Recently updated"),
    PROGRESS("Reading progress"),
    STATUS("Publication status"),
    UNREAD("Unread chapters"),
}

data class LibraryFilter(
    val mediaType: MediaType? = null,
    val status: LibraryStatus? = null,
    val favoritesOnly: Boolean = false,
    val collectionId: Long? = null,
    val unreadOnly: Boolean = false,
    val downloadedOnly: Boolean = false,
    val localOnly: Boolean = false,
    val publicationStatus: String? = null,
    val query: String = "",
    val sort: LibrarySort = LibrarySort.TITLE,
    val descending: Boolean = false,
)

/** Changes made by hand in the metadata editor. A null value leaves the field as it is. */
data class SeriesEdit(
    val title: String? = null,
    val altTitles: List<String>? = null,
    val description: String? = null,
    val authors: List<String>? = null,
    val artists: List<String>? = null,
    val tags: List<String>? = null,
    val customTags: List<String>? = null,
    val status: String? = null,
    val clearStatus: Boolean = false,
    val mediaType: MediaType? = null,
    val coverFile: String? = null,
    val coverUrl: String? = null,
    val originalLanguage: String? = null,
) {
    /** The lockable fields this edit touches. */
    fun touchedFields(): Set<String> = buildSet {
        if (title != null) add(SeriesField.TITLE)
        if (altTitles != null) add(SeriesField.ALT_TITLES)
        if (description != null) add(SeriesField.DESCRIPTION)
        if (authors != null) add(SeriesField.AUTHORS)
        if (artists != null) add(SeriesField.ARTISTS)
        if (tags != null) add(SeriesField.TAGS)
        if (status != null || clearStatus) add(SeriesField.STATUS)
        if (mediaType != null) add(SeriesField.MEDIA_TYPE)
        if (coverFile != null || coverUrl != null) add(SeriesField.COVER)
        if (originalLanguage != null) add(SeriesField.LANGUAGE)
    }
}

/** Details offered by a source or a metadata provider for a series. Null/empty means "says nothing about this". */
data class SeriesDetails(
    val title: String? = null,
    val altTitles: List<String>? = null,
    val description: String? = null,
    val authors: List<String>? = null,
    val artists: List<String>? = null,
    val tags: List<String>? = null,
    val status: String? = null,
    val originalLanguage: String? = null,
    val mediaType: MediaType? = null,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
)

/** A chapter as it arrives from a source or a folder scan, before it has a row. */
data class ChapterDraft(
    val sourceId: String,
    val title: String,
    val chapterNumber: String,
    val volumeNumber: String,
    val language: String?,
    val groupName: String,
    val releasedAt: Long,
    val pageCount: Int = 0,
)
