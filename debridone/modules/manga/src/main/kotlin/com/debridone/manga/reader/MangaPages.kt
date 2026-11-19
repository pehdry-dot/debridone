package com.debridone.manga.reader

import com.debridone.manga.library.Chapter
import com.debridone.manga.library.MangaRepository
import com.debridone.manga.library.Series
import com.debridone.manga.net.MangaHttp
import com.debridone.manga.suwayomi.MangaServer
import com.debridone.manga.suwayomi.SuwayomiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
    /** "local" (a file of the Local source), "downloaded" (kept by the server for offline reading) or "online". */
    val origin: String,
    /** Each page's address on the server. */
    internal val pages: List<String>,
    /** The connection the addresses belong to. */
    internal val generation: Int,
)

/** Why a page could not be shown, so the reader can offer what helps. */
enum class PageProblem {
    /** The manga server could not be reached, or was restarted. */
    SERVER,
    /** The source (the site behind the extension) failed to provide it. */
    SOURCE,
    /** Bytes arrived, but they are a cut-off picture or something else than a picture. */
    DAMAGED,
    /** A downloaded chapter's copy of the page is damaged: downloading the chapter again helps. */
    DAMAGED_DOWNLOAD,
    /** A file of the person's own is damaged. */
    DAMAGED_FILE,
    /** A whole picture in a format this computer cannot draw. */
    UNSUPPORTED,
    OTHER,
}

/** The page could not be shown. [message] is written for the reader; [temporary] means trying again may help. */
class PageException(
    message: String,
    val temporary: Boolean,
    cause: Throwable? = null,
    val problem: PageProblem = PageProblem.OTHER,
) : IOException(message, cause)

/**
 * Turns "page 7 of this chapter" into image bytes. Every page comes from Suwayomi-Server: it
 * fetches it from the source (keeping its own cache), or reads it from a downloaded chapter or a
 * local file. The reader never sees an address, only bytes.
 *
 * Pages just shown are kept in memory, so paging back and forth does not ask again. Covers that
 * come from a metadata provider (a web address, not the server) are kept on disk.
 */
class MangaPages(
    private val server: MangaServer,
    private val repository: MangaRepository,
    private val cacheDir: Path,
    /** Bytes of page images kept in memory. */
    private val memoryBudget: Long = 160L * 1024 * 1024,
    /** The copies of pages the server DebridOne runs keeps for itself. */
    private val cache: ServerPageCache = ServerPageCache { server.managedDirectory() },
) {
    private val fetches = Semaphore(6)
    private val pageLocks = ConcurrentHashMap<String, Mutex>()
    private val remembered = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {}
    private var rememberedBytes = 0L

    private val coverCacheDir: Path get() = cacheDir.resolve("covers")

    /** Asks the server for the chapter's pages. For a chapter of an online source this is a request to that source. */
    suspend fun open(chapterId: Long): OpenChapter = withContext(Dispatchers.IO) {
        val chapter = repository.chapter(chapterId) ?: throw PageException("This chapter is no longer in the library.", false)
        val series = repository.series(chapter.seriesId) ?: throw PageException("This series is no longer in the library.", false)
        val generation = server.generation.value
        val (opened, pages) = try {
            server.api().chapterPages(chapter.id.toInt())
        } catch (failure: SuwayomiException) {
            throw problem(series, failure)
        }
        if (pages.isEmpty()) throw PageException("\"${chapter.displayName}\" has no pages.", false)
        // The page count is known now; the chapter list shows it from here on.
        runCatching { repository.reloadChapters(opened.mangaId) }
        OpenChapter(
            chapter = repository.chapter(chapterId) ?: chapter,
            series = series,
            pageCount = pages.size,
            origin = if (series.isLocal) "local" else if (opened.downloaded) "downloaded" else "online",
            pages = pages,
            generation = generation,
        )
    }

    /**
     * The image of one page (zero-based), checked to be a whole picture before it is handed out.
     *
     * A picture decoder reads only the header of the bytes at first and the rest when it draws:
     * bytes that stop short (a connection the source closed early, a copy the server kept while it
     * was still arriving) or a web page where a picture should be would otherwise reach the screen
     * as a black page with no error. Such bytes are never handed out. For a page of an online
     * source, the server's broken copy of that one page is removed and the page asked for again
     * once; if the source sends the same again, the reason is reported.
     */
    suspend fun page(open: OpenChapter, index: Int): ByteArray = withContext(Dispatchers.IO) {
        if (index !in 0 until open.pageCount) throw PageException("This chapter has no page ${index + 1}.", false)
        val key = key(open, index)
        remembered(key)?.let { return@withContext it }
        val lock = pageLocks.getOrPut(key) { Mutex() }
        try {
            lock.withLock {
                remembered(key) ?: fetches.withPermit {
                    var bytes = fetchPage(open, index)
                    var checked = PageImageCheck.inspect(bytes)
                    if (checked !is PageBytes.Whole && open.origin == "online" && cache.forgetPage(index, bytes, open.chapter.title)) {
                        bytes = fetchPage(open, index)
                        checked = PageImageCheck.inspect(bytes)
                    }
                    if (checked !is PageBytes.Whole) throw damaged(open, index, checked)
                    remember(key, bytes)
                    bytes
                }
            }
        } finally {
            pageLocks.remove(key, lock)
        }
    }

    private fun fetchPage(open: OpenChapter, index: Int): ByteArray {
        if (open.generation != server.generation.value) {
            throw PageException("The manga server was restarted. Open the chapter again.", true, problem = PageProblem.SERVER)
        }
        return try {
            server.transport().bytes(open.pages[index], timeoutSeconds = PAGE_TIMEOUT_SECONDS)
        } catch (failure: SuwayomiException) {
            throw problem(open.series, failure)
        }
    }

    private fun damaged(open: OpenChapter, index: Int, checked: PageBytes): PageException {
        val what = PageImageCheck.problem(checked, index + 1) ?: "Page ${index + 1} could not be read."
        return when (open.origin) {
            "downloaded" -> PageException(
                "$what The downloaded copy of this chapter is damaged; download it again to repair it.",
                temporary = false, problem = PageProblem.DAMAGED_DOWNLOAD,
            )
            "local" -> PageException("$what The file itself is damaged.", temporary = false, problem = PageProblem.DAMAGED_FILE)
            else -> PageException(
                what + when (checked) {
                    is PageBytes.Cut -> " It was asked for again and came back the same way: the source is not sending it whole right now."
                    is PageBytes.NotImage -> if (checked.what == PageBytes.Content.BROWSER_CHECK) " A FlareSolverr of your own (Manga settings › Server) can get past such checks." else ""
                    else -> ""
                },
                temporary = true, problem = PageProblem.DAMAGED,
            )
        }
    }

    /**
     * Bytes that are a whole picture but could not be drawn. The kept copies are forgotten (here and,
     * for an online page, the server's copy), so trying again asks the source afresh.
     */
    fun forgetUndrawable(open: OpenChapter, index: Int, bytes: ByteArray) {
        synchronized(remembered) {
            remembered.remove(key(open, index))?.let { rememberedBytes -= it.size }
        }
        if (open.origin == "online") runCatching { cache.forgetPage(index, bytes, open.chapter.title) }
    }

    /**
     * Forgets every kept copy of one chapter's pages — the ones in memory and the server's own
     * copies of an online chapter — so the next read asks the source for each page again. Nothing
     * else is touched: other chapters, the library and downloaded chapters stay as they are.
     * Returns how many of the server's page copies were removed.
     */
    suspend fun reloadChapter(open: OpenChapter): Int = withContext(Dispatchers.IO) {
        forgetChapter(open.chapter.id)
        if (open.origin != "online") return@withContext 0
        val stored = runCatching { server.api().chapter(open.chapter.id.toInt()) }.getOrNull() ?: return@withContext 0
        val manga = runCatching { server.api().manga(stored.mangaId) }.getOrNull() ?: return@withContext 0
        val source = listOfNotNull(manga.sourceName, manga.sourceLang?.uppercase()?.let { "($it)" }).joinToString(" ")
        cache.forgetChapter(source, manga.title, stored.name, stored.scanlator)
    }

    /** True when the page will show without a request. */
    fun isCached(open: OpenChapter, index: Int): Boolean = synchronized(remembered) { remembered.containsKey(key(open, index)) }

    private fun key(open: OpenChapter, index: Int) = "${open.generation}/${open.chapter.id}/$index"

    private fun remembered(key: String): ByteArray? = synchronized(remembered) { remembered[key] }

    private fun remember(key: String, bytes: ByteArray) = synchronized(remembered) {
        remembered.put(key, bytes)?.let { rememberedBytes -= it.size }
        rememberedBytes += bytes.size
        val eldest = remembered.entries.iterator()
        while (rememberedBytes > memoryBudget && remembered.size > 1 && eldest.hasNext()) {
            val entry = eldest.next()
            if (entry.key == key) continue
            rememberedBytes -= entry.value.size
            eldest.remove()
        }
    }

    private fun problem(series: Series, failure: SuwayomiException): PageException {
        val detail = failure.message.orEmpty()
        val source = series.sourceName.ifBlank { "The source" }
        return when (failure.kind) {
            SuwayomiException.Kind.UNREACHABLE -> PageException(detail.ifBlank { "The manga server is not running." }, true, failure, PageProblem.SERVER)
            SuwayomiException.Kind.TIMEOUT -> PageException("$source did not answer in time.", true, failure, PageProblem.SOURCE)
            SuwayomiException.Kind.UNAUTHORIZED -> PageException(detail, false, failure, PageProblem.SERVER)
            else -> if (series.isLocal) {
                PageException("The file could not be read: $detail", temporary = true, cause = failure, problem = PageProblem.DAMAGED_FILE)
            } else {
                PageException("$source could not provide this: $detail", temporary = true, cause = failure, problem = PageProblem.SOURCE)
            }
        }
    }

    // ---- covers and icons -----------------------------------------------------------------------

    /** The cover of a series, or null when it has none. */
    suspend fun cover(series: Series): ByteArray? = withContext(Dispatchers.IO) {
        series.coverFile?.let { path ->
            runCatching { Files.readAllBytes(Paths.get(path)) }.getOrNull()?.takeIf(::looksLikeImage)?.let { return@withContext it }
        }
        fetch(series.coverUrl)
    }

    /** A cover by address: a path of the server, or a web address from a metadata provider. */
    suspend fun remoteCover(url: String): ByteArray? = withContext(Dispatchers.IO) { fetch(url) }

    /** The icon of a source or an extension (a path of the server). */
    suspend fun icon(path: String): ByteArray? = withContext(Dispatchers.IO) { fetch(path) }

    private suspend fun fetch(address: String): ByteArray? {
        if (address.isBlank()) return null
        if (address.startsWith("/")) {
            // The server keeps its own copy of covers and icons; asking it again costs nothing. It
            // can hand out a cover while it is still writing it (after the series was refreshed):
            // one request per address at a time, and a picture that stops short is asked for again.
            val lock = pageLocks.getOrPut("cover:$address") { Mutex() }
            return try {
                lock.withLock {
                    var last: ByteArray? = null
                    for (attempt in 1..COVER_ATTEMPTS) {
                        last = fetches.withPermit { runCatching { server.transport().bytes(address) }.getOrNull() }?.takeIf(::looksLikeImage)
                        if (last == null || isComplete(last)) break
                        kotlinx.coroutines.delay(250L * attempt)
                    }
                    last?.takeIf(::isComplete)
                }
            } finally {
                pageLocks.remove("cover:$address", lock)
            }
        }
        if (!address.startsWith("https://") && !address.startsWith("http://")) return null
        val file = coverCacheDir.resolve(sha1(address))
        if (Files.isRegularFile(file)) {
            runCatching { Files.readAllBytes(file) }.getOrNull()?.takeIf(::looksLikeImage)?.let { return it }
        }
        val bytes = fetches.withPermit {
            runCatching { MangaHttp.get(address, mapOf("Accept" to "image/*"), MAX_IMAGE_BYTES).body }.getOrNull()?.takeIf(::looksLikeImage)
        } ?: return null
        runCatching {
            Files.createDirectories(coverCacheDir)
            val partial = file.resolveSibling(file.fileName.toString() + ".part")
            Files.write(partial, bytes)
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING)
        }
        return bytes
    }

    // ---- cache ----------------------------------------------------------------------------------

    /** Bytes used by covers kept on disk and pages kept in memory. */
    fun cacheSize(): Long = sizeOf(coverCacheDir) + synchronized(remembered) { rememberedBytes }

    /** Deletes the oldest kept covers until they take less than [maxBytes]. */
    fun trimCache(maxBytes: Long) {
        if (!Files.isDirectory(coverCacheDir)) return
        val files = Files.newDirectoryStream(coverCacheDir).use { it.toList() }
            .map { Triple(it, runCatching { Files.size(it) }.getOrDefault(0), runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0)) }
            .sortedBy { it.third }
        var total = files.sumOf { it.second }
        for ((file, size, _) in files) {
            if (total <= maxBytes) break
            runCatching { Files.deleteIfExists(file) }
            total -= size
        }
    }

    /** Forgets kept pages and covers here, and asks the server to drop the page images it keeps from sources. */
    fun clearCache() {
        synchronized(remembered) {
            remembered.clear()
            rememberedBytes = 0
        }
        if (Files.exists(coverCacheDir)) {
            Files.walk(coverCacheDir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
        }
    }

    fun forgetChapter(chapterId: Long) = synchronized(remembered) {
        val entries = remembered.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key.substringAfter('/').substringBefore('/') == chapterId.toString()) {
                rememberedBytes -= entry.value.size
                entries.remove()
            }
        }
    }

    private fun sizeOf(path: Path): Long {
        if (!Files.exists(path)) return 0
        return Files.walk(path).use { stream -> stream.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0) }.sum() }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_IMAGE_BYTES = 64 * 1024 * 1024
        private const val PAGE_TIMEOUT_SECONDS = 90
        private const val COVER_ATTEMPTS = 4

        /** False for a picture that stops before its end (see [PageImageCheck]). Bytes that are not a picture count as complete here. */
        fun isComplete(bytes: ByteArray): Boolean = PageImageCheck.inspect(bytes) !is PageBytes.Cut

        /** JPEG, PNG, GIF, WebP, BMP, AVIF/HEIF or JPEG XL, by their first bytes. */
        fun looksLikeImage(bytes: ByteArray): Boolean = imageExtension(bytes) != null

        /** The file extension matching the image's real format, or null when it is not an image. */
        fun imageExtension(bytes: ByteArray): String? = if (bytes.size < 12) null else PageImageCheck.format(bytes)?.extension
    }
}
