package com.debridone.manga.download

import com.debridone.manga.library.Chapter
import com.debridone.manga.library.DownloadState
import com.debridone.manga.library.MangaRepository
import com.debridone.manga.reader.MangaPages
import com.debridone.manga.reader.OpenChapter
import com.debridone.manga.reader.PageException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Saves chapters for reading without a connection.
 *
 * Chapters wait in a queue (kept in the database, so it survives a restart) and are fetched one
 * after another through the same page pipeline the reader uses — nothing a source would not hand to
 * the reader is requested. Pages are written one file each under
 * `downloads/<profile>/<series>/<chapter>/0001.jpg…`; a page is only given its final name once it is
 * complete, so an interrupted download resumes with the pages it already has.
 */
class MangaDownloads(
    private val repository: MangaRepository,
    private val pages: MangaPages,
    private val downloadsDir: Path,
    private val scope: CoroutineScope,
    private val parallel: () -> Int = { 1 },
) {
    /** Pause before a page is asked for again; doubled for the third attempt. */
    @Volatile
    var retryDelayMs: Long = 1_500

    private val guard = Mutex()
    private val running = HashMap<Long, Job>()
    private val _active = MutableStateFlow<Set<Long>>(emptySet())

    /** Ids of the chapters being downloaded right now. */
    val active: StateFlow<Set<Long>> = _active.asStateFlow()

    /** Called once when the module starts: downloads cut short by quitting go back in the queue. */
    fun resume() {
        repository.requeueInterruptedDownloads()
        pump()
    }

    fun enqueue(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        repository.queueDownloads(chapterIds)
        pump()
    }

    /** Stops a download that is waiting or running, and deletes what was saved for these chapters. */
    fun delete(chapterIds: Collection<Long>) {
        scope.launch(Dispatchers.IO) {
            for (id in chapterIds) {
                val job = guard.withLock { running.remove(id) }
                job?.cancel()
                job?.join()
                val chapter = repository.chapter(id) ?: continue
                val dir = chapter.downloadDir?.let(Paths::get) ?: directory(chapter)
                deleteTree(dir)
                repository.setDownloadState(id, null)
                _active.update { it - id }
            }
            pump()
        }
    }

    /** Pauses everything that is waiting or running; saved pages are kept. */
    fun cancelAll(profileId: Long) {
        scope.launch(Dispatchers.IO) {
            val waiting = repository.downloads(profileId).filter { it.second.downloadState == DownloadState.QUEUED || it.second.downloadState == DownloadState.RUNNING }
            for ((_, chapter) in waiting) {
                val job = guard.withLock { running.remove(chapter.id) }
                job?.cancel()
                job?.join()
                repository.setDownloadState(chapter.id, DownloadState.FAILED, error = "Stopped. Retry to carry on from page ${chapter.downloadPages + 1}.")
                _active.update { it - chapter.id }
            }
        }
    }

    private fun pump() {
        scope.launch(Dispatchers.IO) {
            guard.withLock {
                while (running.size < parallel().coerceIn(1, 3)) {
                    val next = repository.nextQueuedDownload() ?: break
                    repository.setDownloadState(next.id, DownloadState.RUNNING)
                    _active.update { it + next.id }
                    running[next.id] = scope.launch(Dispatchers.IO) {
                        try {
                            download(next)
                        } finally {
                            guard.withLock { running.remove(next.id) }
                            _active.update { it - next.id }
                            pump()
                        }
                    }
                }
            }
        }
    }

    private suspend fun download(chapter: Chapter) {
        val dir = directory(chapter)
        try {
            Files.createDirectories(dir)
            val open = pages.open(chapter.id)
            val existing = Files.newDirectoryStream(dir).use { stream -> stream.map { it.fileName.toString() }.filter { !it.endsWith(".part") }.toSet() }
            var done = 0
            for (index in 0 until open.pageCount) {
                val prefix = "%04d.".format(index + 1)
                if (existing.none { it.startsWith(prefix) }) {
                    val bytes = pageWithRetries(open, index)
                    val extension = MangaPages.imageExtension(bytes) ?: "img"
                    val partial = dir.resolve("$prefix$extension.part")
                    Files.write(partial, bytes)
                    Files.move(partial, dir.resolve("$prefix$extension"), StandardCopyOption.REPLACE_EXISTING)
                }
                done++
                repository.setDownloadProgress(chapter.id, done, open.pageCount)
            }
            repository.setDownloadState(chapter.id, DownloadState.DONE, dir = dir.toString(), pages = open.pageCount)
            // The reader now uses the saved copy; the cached one is no longer needed.
            pages.forgetChapter(chapter.id)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: PageException) {
            repository.setDownloadState(chapter.id, DownloadState.FAILED, error = failure.message)
        } catch (failure: Exception) {
            repository.setDownloadState(chapter.id, DownloadState.FAILED, error = failure.message ?: "The download failed.")
        }
    }

    /**
     * One page, asked for again when the failure looks temporary (a dropped connection, a source
     * that did not answer in time, a busy server). A chapter of fifty pages should not fail because
     * one request stalled; a failure that would repeat (a missing page, a login that is refused)
     * is not retried.
     */
    private suspend fun pageWithRetries(open: OpenChapter, index: Int): ByteArray {
        var attempt = 1
        while (true) {
            try {
                return pages.page(open, index)
            } catch (failure: PageException) {
                if (!failure.temporary || attempt >= PAGE_ATTEMPTS) throw failure
                delay(retryDelayMs * attempt)
                attempt++
            }
        }
    }

    private fun directory(chapter: Chapter): Path {
        val profile = repository.series(chapter.seriesId)?.profileId ?: 0
        return downloadsDir.resolve(profile.toString()).resolve(chapter.seriesId.toString()).resolve(chapter.id.toString())
    }

    /** Bytes used by saved chapters. */
    fun size(): Long {
        if (!Files.isDirectory(downloadsDir)) return 0
        return Files.walk(downloadsDir).use { stream -> stream.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0) }.sum() }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } } }
    }

    private companion object {
        /** How often one page is asked for before the chapter is reported as failed. */
        const val PAGE_ATTEMPTS = 3
    }
}
