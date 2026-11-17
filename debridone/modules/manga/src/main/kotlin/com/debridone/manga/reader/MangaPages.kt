package com.debridone.manga.reader

import com.debridone.manga.host.HostException
import com.debridone.manga.host.ImageRef
import com.debridone.manga.host.MangaExtensions
import com.debridone.manga.library.Chapter
import com.debridone.manga.library.MangaRepository
import com.debridone.manga.library.Series
import com.debridone.manga.local.LocalChapterPages
import com.debridone.manga.local.LocalLibrary
import com.debridone.manga.net.HttpStatusException
import com.debridone.manga.net.MangaHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A chapter that is ready to be read: how many pages it has and where they come from. */
class OpenChapter internal constructor(
    val chapter: Chapter,
    val series: Series,
    val pageCount: Int,
    /** "local", "downloaded" or "online". */
    val origin: String,
    internal var source: PageSource,
)

internal sealed interface PageSource {
    class Local(val pages: LocalChapterPages) : PageSource
    class Downloaded(val dir: Path, val files: List<String>) : PageSource
    class Remote(val urls: List<String>, val resolvedAt: Long) : PageSource
}

/** The page could not be shown. [message] is written for the reader; [temporary] means trying again may help. */
class PageException(message: String, val temporary: Boolean, cause: Throwable? = null) : IOException(message, cause)

/**
 * Turns "page 7 of this chapter" into image bytes, wherever the chapter lives:
 *  - a local archive or folder (read directly; no extension host involved);
 *  - a downloaded chapter (read from the downloads folder);
 *  - a source extension (page addresses from the extension, images through it or straight from the
 *    address it hands back), kept in a size-limited cache on disk.
 */
class MangaPages(
    private val repository: MangaRepository,
    private val extensions: MangaExtensions,
    private val local: LocalLibrary,
    private val cacheDir: Path,
    private val coverDir: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val directFetches = Semaphore(4)
    private val pageLocks = ConcurrentHashMap<String, Mutex>()

    /** Sources seen to hand back the address unchanged: their images are fetched without asking the host each time. */
    private val plainImageSources = ConcurrentHashMap.newKeySet<String>()

    private val pagesDir: Path get() = cacheDir.resolve("pages")
    private val coverCacheDir: Path get() = cacheDir.resolve("covers")

    /** Works out the page list of a chapter. Offline copies are preferred over the source. */
    suspend fun open(chapterId: Long): OpenChapter = withContext(Dispatchers.IO) {
        val chapter = repository.chapter(chapterId) ?: throw PageException("This chapter is no longer in the library.", false)
        val series = repository.series(chapter.seriesId) ?: throw PageException("This series is no longer in the library.", false)
        val opened = when {
            series.isLocal -> {
                val pages = try {
                    local.pages(Paths.get(chapter.sourceId))
                } catch (failure: IOException) {
                    throw PageException(failure.message ?: "The file could not be opened.", false, failure)
                }
                if (pages.pages.isEmpty()) throw PageException("\"${Paths.get(chapter.sourceId).fileName}\" contains no images.", false)
                OpenChapter(chapter, series, pages.pages.size, "local", PageSource.Local(pages))
            }
            chapter.isDownloaded && downloadedFiles(chapter).isNotEmpty() -> {
                val files = downloadedFiles(chapter)
                OpenChapter(chapter, series, files.size, "downloaded", PageSource.Downloaded(Paths.get(chapter.downloadDir!!), files))
            }
            else -> {
                val urls = resolve(series, chapter)
                OpenChapter(chapter, series, urls.size, "online", PageSource.Remote(urls, clock()))
            }
        }
        repository.setPageCount(chapterId, opened.pageCount)
        opened
    }

    /** The image of one page (zero-based). */
    suspend fun page(open: OpenChapter, index: Int): ByteArray = withContext(Dispatchers.IO) {
        if (index !in 0 until open.pageCount) throw PageException("This chapter has no page ${index + 1}.", false)
        when (val source = open.source) {
            is PageSource.Local -> try {
                checked(local.read(source.pages, index))
            } catch (failure: PageException) {
                throw failure
            } catch (failure: IOException) {
                throw PageException(failure.message ?: "The page could not be read.", false, failure)
            }
            is PageSource.Downloaded -> try {
                checked(Files.readAllBytes(source.dir.resolve(source.files[index])))
            } catch (failure: PageException) {
                throw failure
            } catch (failure: IOException) {
                throw PageException("Page ${index + 1} of the downloaded chapter could not be read. Deleting and downloading the chapter again will repair it.", false, failure)
            }
            is PageSource.Remote -> remotePage(open, source, index)
        }
    }

    /** True when the page is already on disk and will show without a request. */
    fun isCached(open: OpenChapter, index: Int): Boolean =
        open.source !is PageSource.Remote || Files.isRegularFile(cacheFile(open.chapter.id, index))

    private suspend fun remotePage(open: OpenChapter, source: PageSource.Remote, index: Int): ByteArray {
        val file = cacheFile(open.chapter.id, index)
        val lock = pageLocks.getOrPut("${open.chapter.id}/$index") { Mutex() }
        return lock.withLock {
            if (Files.isRegularFile(file)) {
                runCatching { Files.readAllBytes(file) }.getOrNull()?.takeIf(::looksLikeImage)?.let { return@withLock it }
            }
            val bytes = try {
                fetchImage(open.series, source.urls[index])
            } catch (failure: Exception) {
                val problem = asPageException(open.series, failure)
                // Some sources (MangaDex) hand out page addresses that expire after a while: when an old
                // address is refused, ask the source for the chapter's pages once more.
                val expired = clock() - source.resolvedAt > STALE_ADDRESSES_MS && problem.cause is HttpStatusException
                if (!expired) throw problem
                val fresh = PageSource.Remote(resolve(open.series, open.chapter), clock())
                open.source = fresh
                try {
                    fetchImage(open.series, fresh.urls.getOrNull(index) ?: throw PageException("The source no longer lists page ${index + 1}.", false))
                } catch (second: Exception) {
                    throw asPageException(open.series, second)
                }
            }
            Files.createDirectories(file.parent)
            val partial = file.resolveSibling(file.fileName.toString() + ".part")
            Files.write(partial, bytes)
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING)
            bytes
        }.also { pageLocks.remove("${open.chapter.id}/$index") }
    }

    private suspend fun resolve(series: Series, chapter: Chapter): List<String> {
        val urls = try {
            extensions.pages(series.extensionId, series.sourceId, chapter.sourceId)
        } catch (failure: HostException) {
            throw asPageException(series, failure)
        }
        if (urls.isEmpty()) throw PageException("${series.sourceName.ifBlank { "The source" }} lists no pages for this chapter.", false)
        return urls
    }

    /**
     * One image from a source. The extension decides: it either returns the bytes itself (when the
     * site needs its login or headers) or an address, which is then fetched here.
     */
    private suspend fun fetchImage(series: Series, url: String): ByteArray {
        if (series.extensionId in plainImageSources) return direct(url)
        val raw = series.sourceRaw?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        return when (val image = extensions.image(series.extensionId, raw, url)) {
            is ImageRef.Url -> {
                if (image.url == url) plainImageSources += series.extensionId
                direct(image.url)
            }
            is ImageRef.Spooled -> try {
                checked(Files.readAllBytes(image.file))
            } finally {
                runCatching { Files.deleteIfExists(image.file) }
            }
        }
    }

    private suspend fun direct(url: String): ByteArray {
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw PageException("The source returned an address that cannot be opened.", false)
        return directFetches.withPermit {
            try {
                checked(MangaHttp.get(url, maxBytes = MAX_IMAGE_BYTES).body)
            } catch (failure: HttpStatusException) {
                throw PageException("The image host answered ${failure.status}.", failure.status >= 500 || failure.status == 429, failure)
            } catch (failure: PageException) {
                throw failure
            } catch (failure: IOException) {
                throw PageException("The image could not be downloaded (${failure.message ?: "network error"}).", true, failure)
            }
        }
    }

    private fun asPageException(series: Series, failure: Throwable): PageException = when (failure) {
        is PageException -> failure
        is HostException -> {
            val problem = com.debridone.manga.sources.SourceProblem.of(series.sourceName.ifBlank { "The source" }, failure)
            PageException(problem.message, problem.temporary, failure)
        }
        else -> PageException(failure.message ?: "The page could not be loaded.", true, failure)
    }

    private fun checked(bytes: ByteArray): ByteArray {
        if (!looksLikeImage(bytes)) throw PageException("The source returned something that is not an image.", false)
        return bytes
    }

    // ---- covers ---------------------------------------------------------------------------------

    /** The cover of a series, or null when it has none. Remote covers are cached on disk. */
    suspend fun cover(series: Series): ByteArray? = withContext(Dispatchers.IO) {
        series.coverFile?.let { path ->
            runCatching { Files.readAllBytes(Paths.get(path)) }.getOrNull()?.takeIf(::looksLikeImage)?.let { return@withContext it }
        }
        if (series.isLocal) {
            val bytes = runCatching { local.firstPage(Paths.get(series.sourceId)) }.getOrNull()?.takeIf(::looksLikeImage) ?: return@withContext null
            // Keep it, so the library does not open an archive for every cover on every start.
            runCatching {
                Files.createDirectories(coverDir)
                val file = coverDir.resolve("local-${series.id}")
                Files.write(file, bytes)
                repository.setCoverFile(series.id, file.toString())
            }
            return@withContext bytes
        }
        remoteCover(series.coverUrl, series)
    }

    /** A cover by address, for search results and metadata matches that have no row yet. [series] routes it through its source when needed. */
    suspend fun remoteCover(url: String, series: Series? = null, extensionId: String? = null, raw: JsonObject? = null): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val file = coverCacheDir.resolve(sha1(url))
        if (Files.isRegularFile(file)) {
            runCatching { Files.readAllBytes(file) }.getOrNull()?.takeIf(::looksLikeImage)?.let { return@withContext it }
        }
        val bytes = runCatching {
            val source = series?.extensionId?.takeIf { !series.isLocal } ?: extensionId
            val sourceCover = (series?.sourceRaw?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: raw)
            val fromSource = source != null && (sourceCover?.get("remoteCoverUrl") as? JsonPrimitive)?.contentOrNull == url
            when {
                // The source's own cover goes through the source, like a page: it may need the source's login.
                fromSource && source !in plainImageSources -> when (val image = extensions.image(source!!, sourceCover ?: JsonObject(emptyMap()), url)) {
                    is ImageRef.Url -> {
                        if (image.url == url) plainImageSources += source
                        direct(image.url)
                    }
                    is ImageRef.Spooled -> try {
                        checked(Files.readAllBytes(image.file))
                    } finally {
                        runCatching { Files.deleteIfExists(image.file) }
                    }
                }
                else -> direct(url)
            }
        }.getOrNull() ?: return@withContext null
        runCatching {
            Files.createDirectories(coverCacheDir)
            val partial = file.resolveSibling(file.fileName.toString() + ".part")
            Files.write(partial, bytes)
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING)
        }
        bytes
    }

    // ---- cache ----------------------------------------------------------------------------------

    private fun cacheFile(chapterId: Long, index: Int): Path = pagesDir.resolve(chapterId.toString()).resolve(index.toString())

    private fun downloadedFiles(chapter: Chapter): List<String> {
        val dir = chapter.downloadDir?.let(Paths::get)?.takeIf { Files.isDirectory(it) } ?: return emptyList()
        return Files.newDirectoryStream(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".part") }.map { it.fileName.toString() }
        }.sortedWith(LocalLibrary.naturalOrder)
    }

    /** Bytes used by cached pages and covers. */
    fun cacheSize(): Long = sizeOf(pagesDir) + sizeOf(coverCacheDir)

    /** Deletes cached pages, least recently used chapters first, until the cache is under [maxBytes]. Downloads are not touched. */
    fun trimCache(maxBytes: Long) {
        if (!Files.isDirectory(pagesDir)) return
        val chapters = Files.newDirectoryStream(pagesDir).use { it.toList() }
            .map { Triple(it, sizeOf(it), runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0)) }
            .sortedBy { it.third }
        var total = chapters.sumOf { it.second }
        for ((dir, size, _) in chapters) {
            if (total <= maxBytes) break
            deleteTree(dir)
            total -= size
        }
    }

    fun clearCache() {
        deleteTree(pagesDir)
        deleteTree(coverCacheDir)
    }

    fun forgetChapter(chapterId: Long) = deleteTree(pagesDir.resolve(chapterId.toString()))

    private fun sizeOf(path: Path): Long {
        if (!Files.exists(path)) return 0
        return Files.walk(path).use { stream -> stream.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0) }.sum() }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
        private const val STALE_ADDRESSES_MS = 5 * 60 * 1000L

        /** JPEG, PNG, GIF, WebP or BMP, by their first bytes. */
        fun looksLikeImage(bytes: ByteArray): Boolean = imageExtension(bytes) != null

        /** The file extension matching the image's real format, or null when it is not an image. */
        fun imageExtension(bytes: ByteArray): String? {
            if (bytes.size < 12) return null
            fun at(index: Int) = bytes[index].toInt() and 0xff
            return when {
                at(0) == 0xFF && at(1) == 0xD8 -> "jpg"
                at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code && at(3) == 'G'.code -> "png"
                at(0) == 'G'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == '8'.code -> "gif"
                at(0) == 'R'.code && at(1) == 'I'.code && at(2) == 'F'.code && at(3) == 'F'.code &&
                    at(8) == 'W'.code && at(9) == 'E'.code && at(10) == 'B'.code && at(11) == 'P'.code -> "webp"
                at(0) == 'B'.code && at(1) == 'M'.code -> "bmp"
                else -> null
            }
        }
    }
}
