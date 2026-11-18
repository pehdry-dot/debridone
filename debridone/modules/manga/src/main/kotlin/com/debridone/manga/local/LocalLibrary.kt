package com.debridone.manga.local

import com.debridone.manga.library.ChapterDraft
import com.debridone.manga.library.LOCAL_EXTENSION_ID
import com.debridone.manga.library.MangaRepository
import com.debridone.manga.library.MediaType
import com.debridone.manga.library.Series
import com.debridone.manga.library.SeriesDetails
import com.debridone.manga.library.SeriesOrigin
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.Collator
import java.util.Locale
import java.util.zip.ZipFile

/** The pages of one local chapter, in reading order. */
class LocalChapterPages(
    /** The archive file, or the folder. */
    val container: Path,
    val isArchive: Boolean,
    /** Entry names inside the archive, or file names inside the folder. */
    val pages: List<String>,
)

/**
 * Manga kept as files on this computer: CBZ and ZIP archives, folders of images, and folders of
 * such chapters. Nothing here needs an extension, the extension host or the network — a CBZ opens
 * even when no plugin is installed.
 *
 * Layout rules follow Houdoku's filesystem source: inside a series folder, every archive is a
 * chapter and every folder that directly holds images is a chapter; the chapter and volume numbers
 * are read from the name ("c012", "v03", or the first number).
 */
class LocalLibrary(private val repository: MangaRepository) {

    /**
     * Adds a file or folder as a series of [profileId] (or rescans it when it is already there) and
     * puts it in the library. Throws [IOException] with a readable message when there is nothing to read.
     */
    fun import(profileId: Long, path: Path, mediaType: MediaType? = null): Series {
        val source = path.toAbsolutePath().normalize()
        if (!Files.exists(source)) throw IOException("${source.fileName} does not exist.")
        val chapters = scan(source)
        if (chapters.isEmpty()) {
            throw IOException(
                if (Files.isDirectory(source)) "No images or comic archives were found in \"${source.fileName}\"."
                else "\"${source.fileName}\" is not a CBZ or ZIP archive with images in it.",
            )
        }
        val title = titleOf(source)
        val series = repository.ensureSeries(
            profileId, SeriesOrigin.LOCAL, LOCAL_EXTENSION_ID, source.toString(), "Local files",
            SeriesDetails(title = title, mediaType = mediaType),
        )
        repository.syncChapters(series.id, chapters)
        if (!series.inLibrary) repository.setInLibrary(series.id, true)
        return repository.series(series.id)!!
    }

    /** Looks at the series' folder again: new chapters are added, progress on existing ones is kept. */
    fun rescan(seriesId: Long): Int {
        val series = repository.series(seriesId)?.takeIf { it.isLocal } ?: return 0
        val source = Paths.get(series.sourceId)
        if (!Files.exists(source)) throw IOException("\"${source.fileName}\" is no longer where it was (${source.parent}).")
        return repository.syncChapters(seriesId, scan(source))
    }

    /** The chapters found at [source], first chapter first. */
    fun scan(source: Path): List<ChapterDraft> {
        val found = ArrayList<Path>()
        when {
            Files.isRegularFile(source) -> if (isReadableArchive(source)) found.add(source)
            Files.isDirectory(source) -> collect(source, found, depth = 0)
        }
        val ordered = found.sortedWith { a, b -> naturalOrder.compare(source.relativize(a).toString(), source.relativize(b).toString()) }
        return ordered.mapNotNull { chapterPath ->
            val pages = runCatching { pages(chapterPath) }.getOrNull()?.pages?.size ?: 0
            if (pages == 0) return@mapNotNull null
            val name = if (chapterPath == source) titleOf(source) else chapterPath.fileName.toString().substringBeforeLastDot(Files.isRegularFile(chapterPath))
            val parsed = ChapterName.parse(name)
            ChapterDraft(
                sourceId = chapterPath.toString(),
                title = parsed.title,
                // A single archive or image folder is one chapter whatever its name says.
                chapterNumber = if (ordered.size == 1 && parsed.chapter.isEmpty()) "1" else parsed.chapter,
                volumeNumber = parsed.volume,
                language = null,
                groupName = parsed.group,
                releasedAt = runCatching { Files.getLastModifiedTime(chapterPath).toMillis() }.getOrDefault(0),
                pageCount = pages,
            )
        }
    }

    private fun collect(dir: Path, found: MutableList<Path>, depth: Int) {
        if (depth > MAX_DEPTH) return
        var hasImages = false
        val children = Files.newDirectoryStream(dir).use { it.toList() }
        for (child in children) {
            val name = child.fileName.toString()
            if (name.startsWith(".")) continue
            when {
                Files.isDirectory(child) -> collect(child, found, depth + 1)
                isReadableArchive(child) -> found.add(child)
                isImage(name) -> hasImages = true
            }
        }
        if (hasImages) found.add(dir)
    }

    /** Page names of a chapter (an archive or a folder), in natural order: page2 before page10. */
    fun pages(chapter: Path): LocalChapterPages {
        if (Files.isDirectory(chapter)) {
            val names = Files.newDirectoryStream(chapter).use { stream ->
                stream.filter { Files.isRegularFile(it) && isImage(it.fileName.toString()) && !it.fileName.toString().startsWith(".") }
                    .map { it.fileName.toString() }
            }
            return LocalChapterPages(chapter, false, names.sortedWith(naturalOrder))
        }
        if (!Files.isRegularFile(chapter)) throw IOException("\"${chapter.fileName}\" is no longer where it was.")
        if (!isReadableArchive(chapter)) throw IOException(unsupportedMessage(chapter))
        try {
            ZipFile(chapter.toFile()).use { zip ->
                val names = zip.entries().asSequence()
                    .filter { !it.isDirectory && isImage(it.name) && !it.name.substringAfterLast('/').startsWith(".") && !it.name.startsWith("__MACOSX/") }
                    .map { it.name }
                    .toList()
                return LocalChapterPages(chapter, true, names.sortedWith(naturalOrder))
            }
        } catch (failure: IOException) {
            throw IOException("\"${chapter.fileName}\" could not be opened: it is not a valid ZIP/CBZ archive.")
        }
    }

    /** The bytes of one page. */
    fun read(pages: LocalChapterPages, index: Int): ByteArray {
        val name = pages.pages.getOrNull(index) ?: throw IOException("This chapter has no page ${index + 1}.")
        if (!pages.isArchive) {
            val file = pages.container.resolve(name)
            if (Files.size(file) > MAX_PAGE_BYTES) throw IOException("Page ${index + 1} is too large to open.")
            return Files.readAllBytes(file)
        }
        ZipFile(pages.container.toFile()).use { zip ->
            val entry = zip.getEntry(name) ?: throw IOException("Page ${index + 1} is missing from the archive.")
            if (entry.size > MAX_PAGE_BYTES) throw IOException("Page ${index + 1} is too large to open.")
            return zip.getInputStream(entry).use { input ->
                // The size in the archive's directory is not trusted: stop at the limit whatever it says.
                val bytes = input.readNBytes(MAX_PAGE_BYTES + 1)
                if (bytes.size > MAX_PAGE_BYTES) throw IOException("Page ${index + 1} is too large to open.")
                bytes
            }
        }
    }

    /** The first page of the first chapter: the default cover of a local series. */
    fun firstPage(source: Path): ByteArray? {
        val first = scan(source).firstOrNull() ?: return null
        val pages = pages(Paths.get(first.sourceId))
        return if (pages.pages.isEmpty()) null else read(pages, 0)
    }

    private fun titleOf(source: Path): String =
        source.fileName.toString().substringBeforeLastDot(Files.isRegularFile(source)).replace('_', ' ').trim().ifEmpty { "Untitled" }

    private fun String.substringBeforeLastDot(isFile: Boolean): String = if (isFile && contains('.')) substringBeforeLast('.') else this

    companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        val ARCHIVE_EXTENSIONS = setOf("cbz", "zip")

        /** Recognised, but not readable here: RAR archives need a decoder DebridOne does not include. */
        val UNSUPPORTED_ARCHIVE_EXTENSIONS = setOf("cbr", "rar", "cb7", "7z")
        private const val MAX_DEPTH = 6
        private const val MAX_PAGE_BYTES = 96 * 1024 * 1024

        private val collator: Collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.SECONDARY }

        /** "page2" before "page10": runs of digits compare as numbers. */
        val naturalOrder: Comparator<String> = Comparator { a, b ->
            val left = CHUNKS.findAll(a).map { it.value }.toList()
            val right = CHUNKS.findAll(b).map { it.value }.toList()
            for (index in 0 until minOf(left.size, right.size)) {
                val x = left[index]
                val y = right[index]
                val result = if (x[0].isDigit() && y[0].isDigit()) {
                    val numeric = x.trimStart('0').ifEmpty { "0" }.let { xs -> y.trimStart('0').ifEmpty { "0" }.let { ys -> if (xs.length != ys.length) xs.length - ys.length else xs.compareTo(ys) } }
                    if (numeric != 0) numeric else x.length - y.length
                } else {
                    collator.compare(x, y)
                }
                if (result != 0) return@Comparator result
            }
            left.size - right.size
        }
        private val CHUNKS = Regex("\\d+|\\D+")

        fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

        fun isImage(name: String): Boolean = extension(name) in IMAGE_EXTENSIONS

        /**
         * True for a CBZ or ZIP file, and for a file named `.cbr` that is in fact a ZIP archive (it
         * happens): what is inside decides, and a real RAR archive stays unreadable.
         */
        fun isReadableArchive(path: Path): Boolean {
            if (!Files.isRegularFile(path)) return false
            val extension = extension(path.fileName.toString())
            return extension in ARCHIVE_EXTENSIONS || (extension == "cbr" && hasZipSignature(path))
        }

        private fun hasZipSignature(path: Path): Boolean = runCatching {
            Files.newInputStream(path).use { input -> input.readNBytes(4).contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) }
        }.getOrDefault(false)

        fun unsupportedMessage(path: Path): String {
            val extension = extension(path.fileName.toString())
            return if (extension in UNSUPPORTED_ARCHIVE_EXTENSIONS) {
                "\"${path.fileName}\" is a ${extension.uppercase()} archive. DebridOne reads CBZ and ZIP; RAR-based archives (CBR) are not supported in this version. Converting it to CBZ makes it readable."
            } else {
                "\"${path.fileName}\" is not a CBZ or ZIP archive."
            }
        }
    }
}

/** Reads chapter number, volume number and group from a file or folder name, as Houdoku's filesystem source does. */
object ChapterName {
    data class Parsed(val title: String, val chapter: String, val volume: String, val group: String)

    private val chapterPattern = Regex("(?i)(?:^|[^a-z])c(?:h(?:apter)?)?[\\s._-]*(\\d+(?:\\.\\d+)?)")
    private val volumePattern = Regex("(?i)(?:^|[^a-z])v(?:ol(?:ume)?)?[\\s._-]*(\\d+)")
    private val groupPattern = Regex("\\[([^\\]]+)]")
    private val anyNumber = Regex("\\d+(?:\\.\\d+)?")

    fun parse(name: String): Parsed {
        val text = name.trim()
        val volume = volumePattern.find(text)?.groupValues?.get(1)
        val chapter = chapterPattern.find(text)?.groupValues?.get(1)
            // No "c012": a lone number is the chapter, unless the name only carries a volume.
            ?: if (volume == null) anyNumber.findAll(groupPattern.replace(text, " ")).lastOrNull()?.value else null
        return Parsed(
            title = text,
            chapter = chapter?.let(::trimNumber).orEmpty(),
            volume = volume?.let(::trimNumber).orEmpty(),
            group = groupPattern.find(text)?.groupValues?.get(1)?.trim().orEmpty(),
        )
    }

    private fun trimNumber(text: String): String = text.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: text
}
