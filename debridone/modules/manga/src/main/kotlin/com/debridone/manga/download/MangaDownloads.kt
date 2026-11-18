package com.debridone.manga.download

import com.debridone.manga.library.MangaRepository
import com.debridone.manga.suwayomi.MangaServer
import com.debridone.manga.suwayomi.SwDownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files

/**
 * Chapters kept for reading offline. Downloading is Suwayomi-Server's: it has the queue, fetches
 * the pages through the source's extension, keeps the files in its downloads folder, and serves a
 * downloaded chapter's pages from there. This class asks it to, and watches the queue so the
 * screens can show progress.
 */
class MangaDownloads(
    private val server: MangaServer,
    private val repository: MangaRepository,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(SwDownloadStatus(running = false, queue = emptyList()))

    /** The queue as the server last reported it. */
    val status: StateFlow<SwDownloadStatus> = _status.asStateFlow()

    private val _active = MutableStateFlow<Set<Long>>(emptySet())

    /** The chapters that are waiting or being downloaded. */
    val active: StateFlow<Set<Long>> = _active.asStateFlow()

    /** How often the queue is looked at while something is in it, and while nothing is. */
    var busyIntervalMs: Long = 800
    var idleIntervalMs: Long = 8_000

    private var watcher: Job? = null
    private val askedForPages = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** Starts watching the queue. Called once the module runs; does nothing while no server is connected. */
    fun watch() {
        if (watcher?.isActive == true) return
        watcher = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val busy = if (server.isReady) runCatching { poll() }.getOrDefault(false) else false
                delay(if (busy) busyIntervalMs else idleIntervalMs)
            }
        }
    }

    /** Looks at the queue now. Returns true while something is waiting or downloading. */
    fun poll(): Boolean {
        apply(server.api().downloadStatus())
        return _status.value.queue.any { it.state == "QUEUED" || it.state == "DOWNLOADING" }
    }

    private fun apply(next: SwDownloadStatus) {
        val before = _status.value
        _status.value = next
        _active.value = next.queue.filter { it.state == "QUEUED" || it.state == "DOWNLOADING" }.map { it.chapterId.toLong() }.toSet()
        val changed = repository.setQueue(next)
        // A chapter that left the queue, or is reported finished, is on disk now (or was removed): ask again.
        val finished = next.queue.filter { it.state == "FINISHED" }.map { it.mangaId }
        val left = before.queue.filter { old -> next.queue.none { it.chapterId == old.chapterId } }.map { it.mangaId }
        // The page count of a chapter becomes known when its download starts; the progress line needs it.
        val started = next.queue.filter { it.state == "DOWNLOADING" && it.progress > 0 && askedForPages.add(it.chapterId) }.map { it.mangaId }
        val affected = (finished + left + started).distinct()
        if (affected.isNotEmpty()) {
            for (mangaId in affected) runCatching { repository.reloadChapters(mangaId) }
            runCatching { repository.refreshMangas(affected) }
        }
        if (changed || affected.isNotEmpty()) repository.changed()
    }

    fun enqueue(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        val api = server.api()
        apply(api.enqueueDownloads(chapterIds.map { it.toInt() }))
        // A queue that was stopped stays stopped until asked; asking for a download means "download".
        if (!_status.value.running) apply(api.startDownloader())
        watch()
    }

    /** Takes chapters out of the queue. What was already downloaded stays. */
    fun cancel(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        apply(server.api().dequeueDownloads(chapterIds.map { it.toInt() }))
    }

    /** Tries failed chapters again. */
    fun retry(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        val ids = chapterIds.map { it.toInt() }
        val api = server.api()
        runCatching { api.dequeueDownloads(ids) }
        apply(api.enqueueDownloads(ids))
        if (!_status.value.running) apply(api.startDownloader())
    }

    /** Deletes downloaded chapters from the server's disk, and takes them out of the queue. */
    fun delete(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        val ids = chapterIds.map { it.toInt() }
        val api = server.api()
        val queued = _status.value.queue.map { it.chapterId }.toSet()
        ids.filter { it in queued }.takeIf { it.isNotEmpty() }?.let { apply(api.dequeueDownloads(it)) }
        api.deleteDownloadedChapters(ids)
        val owners = chapterIds.mapNotNull { repository.rawChapter(it)?.mangaId }.distinct()
        for (mangaId in owners) runCatching { repository.reloadChapters(mangaId) }
        runCatching { repository.refreshMangas(owners) }
        repository.changed()
    }

    /** Empties the queue. Downloaded chapters stay. */
    fun cancelAll(profileId: Long) {
        if (server.profileId != profileId) return
        apply(server.api().clearDownloader())
    }

    fun pause() = apply(server.api().stopDownloader())

    fun resume() = apply(server.api().startDownloader())

    /**
     * Bytes the managed server's downloads take on this computer, or null when the server is
     * someone else's computer (its disk is not ours to measure).
     */
    fun size(): Long? {
        val profile = server.profileId ?: return 0
        if (server.isRemote) return null
        val folder = server.manager.directory(profile).resolve("downloads")
        if (!Files.isDirectory(folder)) return 0
        return Files.walk(folder).use { stream -> stream.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0) }.sum() }
    }
}
