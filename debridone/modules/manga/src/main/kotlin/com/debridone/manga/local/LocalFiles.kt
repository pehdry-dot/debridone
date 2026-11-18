package com.debridone.manga.local

import com.debridone.manga.library.MangaRepository
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.Series
import com.debridone.manga.library.SeriesEdit
import com.debridone.manga.sources.BrowseMode
import com.debridone.manga.sources.MangaSources
import com.debridone.manga.suwayomi.MangaServer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The file or folder cannot be read as a comic. [message] says why, for the person. */
class LocalImportException(message: String) : IOException(message)

/**
 * The person's own comics: archives and folders on this computer, read through Suwayomi's Local
 * source. That source reads one folder (`<the profile's server folder>/local`), one sub-folder per
 * series, with a chapter being a sub-folder of images or an archive (ZIP/CBZ, RAR/CBR, EPUB).
 *
 * Importing therefore does not copy anything: it puts a link to the person's file or folder into
 * that layout. A file on the same disk gets a hard link (a second name for the same data); a
 * folder, or a file on another disk, gets a symbolic link. Removing the series removes the link,
 * never the person's file.
 *
 * Only for the server DebridOne runs itself: a remote server reads its own disk, not this one.
 */
class LocalFiles(
    private val server: MangaServer,
    private val repository: MangaRepository,
    private val sources: MangaSources,
) {
    /** Whether files of this computer can be read: false when the profile uses a server elsewhere. */
    val available: Boolean get() = server.localSourceDirectory() != null

    /**
     * Makes [path] (an archive, a folder of images, or a folder of chapters) a series of the Local
     * source and puts it in the library. Importing the same thing again returns the same series.
     */
    fun import(profileId: Long, path: Path, mediaType: MediaType? = null): Series {
        if (server.profileId != profileId) throw LocalImportException("Another profile is active.")
        val root = server.localSourceDirectory()
            ?: throw LocalImportException("This profile reads from a manga server on another computer, which cannot open files of this one. Put the file in that server's local folder instead.")
        val source = path.toAbsolutePath().normalize()
        if (!Files.exists(source)) throw LocalImportException("\"${source.fileName}\" was not found.")
        Files.createDirectories(root)
        val ours = !source.startsWith(root)
        val folder = if (Files.isDirectory(source)) linkFolder(root, source) else linkArchive(root, source)

        // The Local source lists what is in its folder; the series' address there is its folder name.
        val name = folder.fileName.toString()
        val found = sources.browse(MangaSources.LOCAL_ID, BrowseMode.SEARCH, name).series.firstOrNull { it.sourceId == name }
            ?: repository.seriesBySource(profileId, MangaSources.LOCAL_ID, name)
            ?: allLocal().firstOrNull { it.sourceId == name }
            ?: throw LocalImportException("The manga server does not list \"$name\". It may not be able to read this kind of file.")
        runCatching { sources.refresh(found.id) }
        if (repository.chapters(found.id).isEmpty()) {
            if (ours) deleteLinks(folder)
            throw LocalImportException("\"${source.fileName}\" contains nothing the reader can open (no images, and no archive of images).")
        }
        // The Local source lists a file by its name alone; whether it really is a comic shows when it is opened.
        try {
            val first = repository.rawChapters(found.id.toInt()).first()
            val (opened, pages) = server.api().chapterPages(first.id)
            if (pages.isEmpty()) throw LocalImportException("\"${source.fileName}\" contains no images.")
            repository.reloadChapters(opened.mangaId)
        } catch (failure: IOException) {
            if (ours) deleteLinks(folder)
            if (failure is LocalImportException) throw failure
            throw LocalImportException("\"${source.fileName}\" is damaged, or is not really a comic archive (${failure.message}).")
        }
        if (!found.inLibrary) repository.setInLibrary(found.id, true)
        if (mediaType != null) repository.editSeries(found.id, SeriesEdit(mediaType = mediaType), lock = true)
        return repository.series(found.id) ?: found
    }

    private fun allLocal(): List<Series> {
        val all = ArrayList<Series>()
        var page = 1
        while (page <= 50) {
            val result = sources.browse(MangaSources.LOCAL_ID, BrowseMode.POPULAR, page = page)
            all += result.series
            if (!result.hasMore || result.series.isEmpty()) break
            page++
        }
        return all
    }

    /** Looks at the series' folder again for new or removed chapters. Returns how many are new. */
    fun rescan(seriesId: Long): Int = sources.refresh(seriesId)

    /**
     * Takes an imported series out: removes the link DebridOne made (never the person's own file
     * or folder) and the series from the library.
     */
    fun remove(series: Series) {
        val root = server.localSourceDirectory()
        if (root != null && series.isLocal) {
            val folder = root.resolve(series.sourceId).normalize()
            if (folder.startsWith(root) && folder != root) deleteLinks(folder)
        }
        repository.deleteSeries(series.id)
    }

    /** Where the Local source's folder is, to show it to the person; null for a remote server. */
    fun folder(): Path? = server.localSourceDirectory()

    // ---- linking --------------------------------------------------------------------------------

    /** A folder of chapters is linked as the series; a folder of images becomes the one chapter of a series. */
    private fun linkFolder(root: Path, source: Path): Path {
        if (source.startsWith(root)) return root.resolve(root.relativize(source).getName(0))
        val entries = Files.newDirectoryStream(source).use { it.toList() }
        if (entries.isEmpty()) throw LocalImportException("\"${source.fileName}\" is an empty folder.")
        val images = entries.count { Files.isRegularFile(it) && isImage(it) }
        val chapters = entries.count { Files.isDirectory(it) || isArchive(it) }
        if (images == 0 && chapters == 0) throw LocalImportException("\"${source.fileName}\" contains no images and no comic archives.")
        val title = clean(source.fileName.toString())
        return if (chapters > 0) {
            existing(root, title, source) ?: free(root, title).also { link(it, source) }
        } else {
            val series = existingChapter(root, title, source) ?: free(root, title).also {
                Files.createDirectories(it)
                link(it.resolve(title), source)
            }
            series
        }
    }

    private fun linkArchive(root: Path, source: Path): Path {
        if (source.startsWith(root)) return root.resolve(root.relativize(source).getName(0))
        if (!isArchive(source)) {
            throw LocalImportException("\"${source.fileName}\" is not a comic archive. The reader opens CBZ, ZIP, CBR, RAR and EPUB files, and folders of images.")
        }
        val file = source.fileName.toString()
        val title = clean(file.substringBeforeLast('.'))
        existingChapter(root, title, source)?.let { return it }
        val series = free(root, title)
        Files.createDirectories(series)
        link(series.resolve(file), source)
        return series
    }

    /** A series folder that already is a link to [source]. */
    private fun existing(root: Path, title: String, source: Path): Path? =
        candidates(root, title).firstOrNull { Files.isSymbolicLink(it) && sameFile(it, source) }

    /** A series folder made earlier whose one chapter is [source]. */
    private fun existingChapter(root: Path, title: String, source: Path): Path? = candidates(root, title).firstOrNull { series ->
        !Files.isSymbolicLink(series) && Files.isDirectory(series) &&
            Files.newDirectoryStream(series).use { entries -> entries.any { sameFile(it, source) } }
    }

    private fun candidates(root: Path, title: String): List<Path> =
        (listOf(root.resolve(title)) + (2..20).map { root.resolve("$title ($it)") }).filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }

    private fun free(root: Path, title: String): Path {
        var candidate = root.resolve(title)
        var number = 2
        while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) candidate = root.resolve("$title (${number++})")
        return candidate
    }

    private fun sameFile(a: Path, b: Path): Boolean = runCatching { Files.isSameFile(a, b) }.getOrDefault(false)

    private fun link(link: Path, target: Path) {
        if (Files.isRegularFile(target)) {
            // A second name for the same data: nothing is copied, and deleting the name leaves the file.
            if (runCatching { Files.createLink(link, target) }.isSuccess) return
        }
        try {
            Files.createSymbolicLink(link, target)
        } catch (failure: Exception) {
            throw LocalImportException("\"${target.fileName}\" could not be linked into the library: ${failure.message}")
        }
    }

    /** Removes links (and the folder DebridOne made around them). Anything that is a real folder with real files in it is left. */
    private fun deleteLinks(folder: Path) {
        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(folder)) {
            runCatching { Files.delete(folder) }
            return
        }
        if (!Files.isDirectory(folder)) return
        val entries = Files.newDirectoryStream(folder).use { it.toList() }
        for (entry in entries) {
            val hardLinked = Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && (runCatching { Files.getAttribute(entry, "unix:nlink") as Int }.getOrDefault(1) > 1)
            if (Files.isSymbolicLink(entry) || hardLinked || entry.fileName.toString().let { it == "ComicInfo.xml" || it == "cover.jpg" || it == ".noxml" }) {
                runCatching { Files.delete(entry) }
            }
        }
        // Only a folder that is empty now was entirely ours.
        runCatching { Files.delete(folder) }
    }

    private fun clean(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.').ifBlank { "Untitled" }.take(120)

    companion object {
        private val archives = setOf("cbz", "zip", "cbr", "rar", "epub")
        private val images = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "jxl", "heif", "heic")

        fun isArchive(path: Path): Boolean = Files.isRegularFile(path) && extension(path) in archives

        fun isImage(path: Path): Boolean = extension(path) in images

        /** Whether the Manga reader can open a file with this name (used before a download starts). */
        fun canRead(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in archives

        private fun extension(path: Path): String = path.fileName.toString().substringAfterLast('.', "").lowercase()
    }
}
