package com.debridone.manga.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@Serializable
internal data class BackupChapter(
    val sourceId: String,
    val title: String = "",
    val chapterNumber: String = "",
    val volumeNumber: String = "",
    val language: String? = null,
    val groupName: String = "",
    val releasedAt: Long = 0,
    val pageCount: Int = 0,
    val read: Boolean = false,
    val lastPage: Int = 0,
    val scrollOffset: Float = 0f,
    val bookmarks: List<BackupBookmark> = emptyList(),
)

@Serializable
internal data class BackupBookmark(val page: Int, val note: String = "")

@Serializable
internal data class BackupSeries(
    val origin: String,
    val extensionId: String,
    val sourceId: String,
    val sourceName: String = "",
    val title: String,
    val altTitles: List<String> = emptyList(),
    val description: String = "",
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val customTags: List<String> = emptyList(),
    val status: String? = null,
    val originalLanguage: String? = null,
    val mediaType: String = "manga",
    val coverUrl: String = "",
    val bannerUrl: String? = null,
    val lockedFields: List<String> = emptyList(),
    val libraryStatus: String? = null,
    val favorite: Boolean = false,
    val readerOverrides: String? = null,
    val collections: List<String> = emptyList(),
    val links: List<BackupLink> = emptyList(),
    val chapters: List<BackupChapter> = emptyList(),
)

@Serializable
internal data class BackupLink(val provider: String, val remoteId: String, val url: String? = null, val tracked: Boolean = false)

@Serializable
internal data class BackupFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long,
    val series: List<BackupSeries>,
    val collections: List<String> = emptyList(),
) {
    companion object {
        const val FORMAT = "debridone-manga-library"
    }
}

data class RestoreSummary(val series: Int, val chapters: Int, val collections: Int)

/**
 * A profile's library as one file: every series in the library with where it comes from, the
 * person's edits and locks, reading progress, bookmarks, collections and metadata links.
 *
 * It holds no pages, no downloads and no credentials. Restoring merges into the library that is
 * there: a series already present keeps its row and gains whatever the backup knows.
 */
class MangaBackup(private val repository: MangaRepository, private val clock: () -> Long = System::currentTimeMillis) {
    // Defaults are written out too: the file must say what it is ("format") and be readable without this code.
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun export(profileId: Long, target: Path): Int {
        val collectionNames = repository.collections(profileId).associate { it.id to it.name }
        val series = repository.library(profileId).map { summary ->
            val item = summary.series
            val bookmarks = repository.bookmarks(item.id).groupBy { it.chapterId }
            BackupSeries(
                origin = item.origin.key, extensionId = item.extensionId, sourceId = item.sourceId, sourceName = item.sourceName,
                title = item.title, altTitles = item.altTitles, description = item.description, authors = item.authors, artists = item.artists,
                tags = item.tags, customTags = item.customTags, status = item.status, originalLanguage = item.originalLanguage,
                mediaType = item.mediaType.key, coverUrl = item.coverUrl, bannerUrl = item.bannerUrl, lockedFields = item.lockedFields.sorted(),
                libraryStatus = item.libraryStatus?.key, favorite = item.favorite, readerOverrides = item.readerOverrides,
                collections = repository.collectionsOf(item.id).mapNotNull(collectionNames::get).sorted(),
                links = repository.metadataLinks(item.id).map { BackupLink(it.provider, it.remoteId, it.url, it.tracked) },
                chapters = repository.chapters(item.id, allLanguages = true).map { chapter ->
                    BackupChapter(
                        sourceId = chapter.sourceId, title = chapter.title, chapterNumber = chapter.chapterNumber, volumeNumber = chapter.volumeNumber,
                        language = chapter.language, groupName = chapter.groupName, releasedAt = chapter.releasedAt, pageCount = chapter.pageCount,
                        read = chapter.read, lastPage = chapter.lastPage, scrollOffset = chapter.scrollOffset,
                        bookmarks = bookmarks[chapter.id].orEmpty().map { BackupBookmark(it.page, it.note) },
                    )
                },
            )
        }
        val file = BackupFile(exportedAt = clock(), series = series, collections = collectionNames.values.sorted())
        val partial = target.resolveSibling(target.fileName.toString() + ".part")
        Files.writeString(partial, json.encodeToString(BackupFile.serializer(), file))
        Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return series.size
    }

    fun restore(profileId: Long, source: Path): RestoreSummary {
        if (Files.size(source) > MAX_BYTES) throw IOException("That file is too large to be a Manga library backup.")
        val file = try {
            json.decodeFromString(BackupFile.serializer(), Files.readString(source))
        } catch (failure: Exception) {
            throw IOException("That file is not a DebridOne Manga library backup.")
        }
        if (file.format != BackupFile.FORMAT) throw IOException("That file is not a DebridOne Manga library backup.")
        if (file.version > 1) throw IOException("That backup was made by a newer version of DebridOne.")

        val collections = HashMap<String, Long>()
        repository.collections(profileId).forEach { collections[it.name.lowercase()] = it.id }
        var created = 0
        fun collection(name: String): Long = collections.getOrPut(name.lowercase()) {
            created++
            repository.createCollection(profileId, name).id
        }
        file.collections.forEach { runCatching { collection(it) } }

        var chapters = 0
        for (item in file.series) {
            val series = repository.ensureSeries(
                profileId, SeriesOrigin.fromKey(item.origin), item.extensionId, item.sourceId, item.sourceName,
                SeriesDetails(
                    title = item.title, altTitles = item.altTitles, description = item.description, authors = item.authors, artists = item.artists,
                    tags = item.tags, status = item.status, originalLanguage = item.originalLanguage, mediaType = MediaType.fromKey(item.mediaType),
                    coverUrl = item.coverUrl, bannerUrl = item.bannerUrl,
                ),
            )
            // The person's own edits come back exactly, with their locks.
            repository.editSeries(
                series.id,
                SeriesEdit(
                    title = item.title, altTitles = item.altTitles, description = item.description, authors = item.authors, artists = item.artists,
                    tags = item.tags, customTags = item.customTags, status = item.status, clearStatus = item.status == null,
                    mediaType = MediaType.fromKey(item.mediaType),
                ),
                lock = false,
            )
            SeriesField.all.forEach { field -> repository.setFieldLocked(series.id, field, field in item.lockedFields) }
            repository.setInLibrary(series.id, true, LibraryStatus.fromKey(item.libraryStatus))
            if (item.favorite) repository.setFavorite(series.id, true)
            if (item.readerOverrides != null) repository.setReaderOverrides(series.id, item.readerOverrides)
            item.collections.forEach { name -> runCatching { repository.setInCollection(collection(name), series.id, true) } }
            item.links.forEach { link ->
                repository.linkMetadata(series.id, link.provider, link.remoteId, link.url)
                if (link.tracked) repository.setTracked(series.id, link.provider, true)
            }
            if (item.chapters.isNotEmpty()) {
                val known = repository.chapters(series.id, allLanguages = true).map { it.sourceId }.toSet()
                // Chapters the library already has keep their place; the backup only adds the ones it does not know.
                val drafts = repository.chapters(series.id, allLanguages = true).map { ChapterDraft(it.sourceId, it.title, it.chapterNumber, it.volumeNumber, it.language, it.groupName, it.releasedAt, it.pageCount) } +
                    item.chapters.filter { it.sourceId !in known }.map { ChapterDraft(it.sourceId, it.title, it.chapterNumber, it.volumeNumber, it.language, it.groupName, it.releasedAt, it.pageCount) }
                repository.syncChapters(series.id, drafts)
                val bySource = repository.chapters(series.id, allLanguages = true).associateBy { it.sourceId }
                for (chapter in item.chapters) {
                    val row = bySource[chapter.sourceId] ?: continue
                    chapters++
                    if (chapter.read && !row.read) repository.setRead(listOf(row.id), true)
                    if (!chapter.read && chapter.lastPage > row.lastPage) repository.restorePosition(row.id, chapter.lastPage, chapter.scrollOffset, chapter.pageCount)
                    chapter.bookmarks.forEach { bookmark -> if (repository.bookmark(row.id, bookmark.page) == null) repository.toggleBookmark(row.id, bookmark.page, bookmark.note) }
                }
            }
        }
        return RestoreSummary(file.series.size, chapters, created)
    }

    private companion object {
        const val MAX_BYTES = 256L * 1024 * 1024
    }
}
