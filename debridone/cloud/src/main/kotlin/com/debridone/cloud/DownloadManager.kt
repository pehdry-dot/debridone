package com.debridone.cloud

import com.debridone.cloud.CloudException.Reason
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DownloadState { QUEUED, RUNNING, PAUSED, FAILED, DONE, CANCELLED }

/** What happens when a file of the same name is already where a download should go. */
enum class ConflictPolicy(val label: String) {
    KEEP_BOTH("Keep both (the new file gets a number)"),
    SKIP("Skip the download"),
    REPLACE("Replace the existing file"),
}

/** One file being fetched from a connected account to this computer, or the record that it was. */
data class Download(
    val id: Long,
    val profileId: Long,
    val accountId: Long,
    val provider: ProviderId,
    val kind: ItemKind,
    val itemRemoteId: String,
    val fileRef: String,
    val itemName: String,
    /** The file's path inside its entry. */
    val filePath: String,
    val mediaClass: MediaClass,
    /** Where the finished file is (or will be) on this computer. */
    val destination: String,
    val totalBytes: Long,
    val receivedBytes: Long,
    val expectedMd5: String?,
    /** True: the checksum the provider gave matched. Null: the provider gave none. */
    val verified: Boolean?,
    val state: DownloadState,
    val error: String?,
    /** The section that imported the finished file ("music", "manga"), and its own reference to it. */
    val importedInto: String?,
    val importRef: String?,
    val createdAt: Long,
    val finishedAt: Long?,
    /** When the local copy was deleted through DebridOne; the record stays as history. */
    val fileDeletedAt: Long?,
    val bytesPerSecond: Long = 0,
    /** A remark about the transfer in progress ("the server does not resume; starting over"). */
    val note: String? = null,
) {
    val fileName: String get() = Paths.get(destination).fileName?.toString() ?: filePath.substringAfterLast('/')
    val fraction: Float? get() = if (totalBytes > 0) (receivedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else null
    val secondsLeft: Long? get() = if (state == DownloadState.RUNNING && bytesPerSecond > 0 && totalBytes > receivedBytes) (totalBytes - receivedBytes) / bytesPerSecond else null
    val active: Boolean get() = state == DownloadState.QUEUED || state == DownloadState.RUNNING || state == DownloadState.PAUSED

    /** True when the finished file is still where DebridOne put it. */
    val fileExists: Boolean get() = state == DownloadState.DONE && fileDeletedAt == null && Files.isRegularFile(Paths.get(destination))
}

/** What [DownloadManager.enqueue] did: what was queued, and why anything was not. */
data class EnqueueResult(val queued: List<Download>, val problems: List<String>)

/**
 * The download preferences of one profile. Folders are plain paths chosen by the user; nothing is
 * written anywhere else.
 */
class DownloadSettings internal constructor(private val store: CloudStore, private val defaultRoot: Path) {
    private fun scope(profileId: Long) = "profile:$profileId"

    private fun get(profileId: Long, key: String): String? = store.setting(scope(profileId), key)

    private fun set(profileId: Long, key: String, value: String?) = store.setSetting(scope(profileId), key, value)

    fun defaultFolder(profileId: Long): Path = get(profileId, "folder.default")?.let { Paths.get(it) } ?: defaultRoot

    /** The folder for one shelf: the user's choice, else a sub-folder of the default folder. */
    fun folder(profileId: Long, mediaClass: MediaClass): Path {
        val (key, name) = folderKey(mediaClass)
        return key?.let { get(profileId, it) }?.let { Paths.get(it) } ?: name?.let { defaultFolder(profileId).resolve(it) } ?: defaultFolder(profileId)
    }

    /** True when the folder for [mediaClass] is the user's own choice rather than the default. */
    fun isCustomFolder(profileId: Long, mediaClass: MediaClass?): Boolean =
        get(profileId, mediaClass?.let { folderKey(it).first } ?: "folder.default") != null

    /** Sets (or with null, resets) the default folder ([mediaClass] null) or the folder of a shelf. */
    fun setFolder(profileId: Long, mediaClass: MediaClass?, folder: Path?) {
        val key = if (mediaClass == null) "folder.default" else folderKey(mediaClass).first ?: return
        set(profileId, key, folder?.toAbsolutePath()?.normalize()?.toString())
    }

    private fun folderKey(mediaClass: MediaClass): Pair<String?, String?> = when (mediaClass) {
        MediaClass.MUSIC -> "folder.music" to "Music"
        MediaClass.COMIC -> "folder.manga" to "Manga"
        MediaClass.MOVIE, MediaClass.TV_SHOW -> "folder.video" to "Video"
        MediaClass.OTHER -> null to null
    }

    fun maxConcurrent(profileId: Long): Int = get(profileId, "max_concurrent")?.toIntOrNull()?.coerceIn(1, 8) ?: 3
    fun setMaxConcurrent(profileId: Long, value: Int) = set(profileId, "max_concurrent", value.coerceIn(1, 8).toString())

    fun conflictPolicy(profileId: Long): ConflictPolicy = get(profileId, "conflict")?.let { runCatching { ConflictPolicy.valueOf(it) }.getOrNull() } ?: ConflictPolicy.KEEP_BOTH
    fun setConflictPolicy(profileId: Long, policy: ConflictPolicy) = set(profileId, "conflict", policy.name)

    /** Free space that must remain after a download, in megabytes. 0 switches the warning off. */
    fun spaceReserveMb(profileId: Long): Long = get(profileId, "space_reserve_mb")?.toLongOrNull()?.coerceIn(0, 1_000_000) ?: 500
    fun setSpaceReserveMb(profileId: Long, value: Long) = set(profileId, "space_reserve_mb", value.coerceIn(0, 1_000_000).toString())

    /** Whether a finished download on [mediaClass]'s shelf is added to that section's library by itself. */
    fun autoImport(profileId: Long, mediaClass: MediaClass): Boolean = get(profileId, "auto_import.${mediaClass.key}")?.toBooleanStrictOrNull() ?: false
    fun setAutoImport(profileId: Long, mediaClass: MediaClass, value: Boolean) = set(profileId, "auto_import.${mediaClass.key}", value.toString())

    fun autoRetry(profileId: Long): Boolean = get(profileId, "auto_retry")?.toBooleanStrictOrNull() ?: true
    fun setAutoRetry(profileId: Long, value: Boolean) = set(profileId, "auto_retry", value.toString())

    internal fun deleteProfile(profileId: Long) = store.deleteSettings(scope(profileId))
}

/**
 * The one download manager of the application: it fetches files of connected accounts to this
 * computer, for every section.
 *
 * A download is a row of `cloud.db` from the moment it is queued, so the queue and the history
 * survive a restart; an unfinished file is kept as `<name>.part` and continued with an HTTP range
 * request where the server allows it. The address of a file is asked from its provider when the
 * download (re)starts and never stored; when an address stops working a new one is asked for.
 * A finished file is never written over another file unless the profile chose "replace".
 */
class DownloadManager internal constructor(
    private val store: CloudStore,
    private val files: CloudFileRepository,
    val settings: DownloadSettings,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val freeSpace: (Path) -> Long,
    private val retryDelayMs: (attempt: Int) -> Long = { attempt -> (2_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(60_000L) },
) {
    private val database get() = store.raw
    private val _downloads = MutableStateFlow<List<Download>>(emptyList())

    /** Every download of every profile, newest first; see [forProfile]. */
    val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()

    private class Live(@Volatile var bytesPerSecond: Long = 0, @Volatile var received: Long = 0, @Volatile var total: Long = 0, @Volatile var note: String? = null)

    private val live = ConcurrentHashMap<Long, Live>()
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val connections = ConcurrentHashMap<Long, HttpURLConnection>()
    private val pumpLock = Any()

    @Volatile
    private var closed = false

    fun forProfile(profileId: Long?): List<Download> = _downloads.value.filter { it.profileId == profileId }

    /** After a restart: what was running is queued again and continues from its partial file. */
    fun start() {
        database.execute("UPDATE downloads SET state = 'QUEUED' WHERE state = 'RUNNING'")
        publish()
        pump()
    }

    // ---------------------------------------------------------------- queueing

    /**
     * Queues files of the profile's accounts. [folder] overrides the profile's folder for their
     * shelf. A file that cannot be queued (already downloaded, name taken and the policy is to
     * skip, not enough disk space) is named in the result instead.
     */
    fun enqueue(profileId: Long, fileIds: List<Long>, folder: Path? = null): EnqueueResult {
        val queued = ArrayList<Download>()
        val problems = ArrayList<String>()
        val policy = settings.conflictPolicy(profileId)
        val reserve = settings.spaceReserveMb(profileId) * 1024 * 1024
        var planned = 0L
        for (fileId in fileIds.distinct()) {
            val row = files.file(profileId, fileId)
            if (row == null) {
                problems += "A file is no longer listed in your account."
                continue
            }
            val existing = _downloads.value.firstOrNull { it.profileId == profileId && it.accountId == row.accountId && it.kind == row.kind && it.itemRemoteId == row.itemRemoteId && it.fileRef == row.ref }
            if (existing != null && (existing.active || existing.fileExists)) {
                problems += if (existing.active) "${row.name} is already in the queue." else "${row.name} is already downloaded."
                continue
            }
            val root = (folder ?: settings.folder(profileId, row.mediaClass)).toAbsolutePath().normalize()
            var target = destination(root, row)
            if (taken(target)) {
                when (policy) {
                    ConflictPolicy.SKIP -> {
                        problems += "${row.name}: a file of that name is already in ${target.parent}."
                        continue
                    }
                    ConflictPolicy.KEEP_BOTH -> target = nextFree(target)
                    ConflictPolicy.REPLACE -> Unit
                }
            }
            val space = runCatching { freeSpace(existingAncestor(root)) }.getOrNull()
            if (space != null && reserve > 0 && row.sizeBytes > 0 && space - planned - row.sizeBytes < reserve) {
                problems += "${row.name}: not enough free disk space in $root (${megabytes(space - planned)} free, the file is ${megabytes(row.sizeBytes)}, and ${megabytes(reserve)} is kept free)."
                continue
            }
            planned += row.sizeBytes
            // A record of an earlier, deleted or failed copy of the same file is replaced by the new one.
            existing?.let { database.execute("DELETE FROM downloads WHERE id = ?", it.id) }
            val id = database.insert(
                """
                INSERT INTO downloads (profile_id, account_id, provider, item_kind, item_remote_id, file_ref, item_name, file_path, media_class, destination, total_bytes, expected_md5, state, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?)
                """.trimIndent(),
                profileId, row.accountId, row.provider.key, row.kind.key, row.itemRemoteId, row.ref, row.itemName, row.path, row.mediaClass.key, target.toString(), row.sizeBytes, row.md5, clock(),
            )
            queued += checkNotNull(load(id))
        }
        publish()
        pump()
        return EnqueueResult(queued, problems)
    }

    /** Where a file goes: `<folder>/<entry>/<path inside the entry>`, with every part made safe for a file system. */
    private fun destination(root: Path, row: CloudFileRow): Path {
        val segments = row.path.split('/').map(::safeSegment).filter { it.isNotEmpty() }.ifEmpty { listOf(safeSegment(row.itemName)) }
        val entry = safeSegment(row.itemName)
        val relative = when {
            // A single file that is its own entry (a generated link), or a path that already starts with the entry's folder.
            segments.size == 1 && (row.kind == ItemKind.LINK || segments[0] == entry) -> segments
            segments.size > 1 && segments[0] == entry -> segments
            else -> listOf(entry) + segments
        }
        val target = relative.fold(root) { path, segment -> path.resolve(segment) }.normalize()
        check(target.startsWith(root)) { "A download may not leave its folder." }
        return target
    }

    private fun taken(target: Path): Boolean =
        Files.exists(target) || _downloads.value.any { it.active && it.destination == target.toString() }

    private fun nextFree(target: Path): Path {
        val name = target.fileName.toString()
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var number = 2
        while (true) {
            val candidate = target.resolveSibling("$stem ($number)$extension")
            if (!taken(candidate)) return candidate
            number++
        }
    }

    // ---------------------------------------------------------------- controlling

    /** Stops a transfer and keeps what was fetched, to continue later. */
    fun pause(id: Long) {
        val download = load(id) ?: return
        if (download.state != DownloadState.RUNNING && download.state != DownloadState.QUEUED) return
        setState(id, DownloadState.PAUSED, null)
        stopJob(id)
        publish()
        pump()
    }

    fun resume(id: Long) {
        val download = load(id) ?: return
        if (download.state != DownloadState.PAUSED) return
        setState(id, DownloadState.QUEUED, null)
        publish()
        pump()
    }

    /** Stops a transfer and deletes what was fetched. The record stays in the history. */
    fun cancel(id: Long) {
        val download = load(id) ?: return
        if (!download.active) return
        setState(id, DownloadState.CANCELLED, null)
        stopJob(id)
        runCatching { Files.deleteIfExists(part(download)) }
        database.execute("UPDATE downloads SET received_bytes = 0, finished_at = ? WHERE id = ?", clock(), id)
        publish()
        pump()
    }

    /** Queues a failed or cancelled download again. */
    fun retry(id: Long) {
        val download = load(id) ?: return
        if (download.state != DownloadState.FAILED && download.state != DownloadState.CANCELLED) return
        database.execute("UPDATE downloads SET state = 'QUEUED', error = NULL, attempts = 0, finished_at = NULL WHERE id = ?", id)
        publish()
        pump()
    }

    /** Deletes the downloaded file from this computer. The cloud is not touched; the record stays as history. */
    fun deleteLocalCopy(id: Long): Boolean {
        val download = load(id) ?: return false
        if (download.state != DownloadState.DONE) return false
        val deleted = runCatching { Files.deleteIfExists(Paths.get(download.destination)) }.isSuccess
        database.execute("UPDATE downloads SET file_deleted_at = ?, imported_into = NULL, import_ref = NULL WHERE id = ?", clock(), id)
        publish()
        return deleted
    }

    /** Removes a finished, failed or cancelled record from the history. Files are left where they are. */
    fun removeRecord(id: Long) {
        val download = load(id) ?: return
        if (download.active) return
        database.execute("DELETE FROM downloads WHERE id = ?", id)
        publish()
    }

    /** Clears the history of a profile: every record that is not in the queue. Files are left where they are. */
    fun clearHistory(profileId: Long) {
        database.execute("DELETE FROM downloads WHERE profile_id = ? AND state IN ('DONE', 'FAILED', 'CANCELLED')", profileId)
        publish()
    }

    /** Records that a section took the finished file into its library (null: it no longer has it). */
    fun markImported(id: Long, section: String?, reference: String?) {
        database.execute("UPDATE downloads SET imported_into = ?, import_ref = ? WHERE id = ?", section, reference, id)
        publish()
    }

    /** The finished local copy of a cloud file, if this profile downloaded it and it is still there. */
    fun localCopy(profileId: Long?, file: CloudFileRow): Download? = _downloads.value.firstOrNull {
        it.profileId == profileId && it.accountId == file.accountId && it.kind == file.kind && it.itemRemoteId == file.itemRemoteId && it.fileRef == file.ref && it.fileExists
    }

    /** A profile was deleted: its queue, history and partial files go; finished files stay in the user's folders. */
    fun deleteProfile(profileId: Long) {
        forProfile(profileId).forEach { download ->
            if (download.active) {
                stopJob(download.id)
                runCatching { Files.deleteIfExists(part(download)) }
            }
        }
        database.execute("DELETE FROM downloads WHERE profile_id = ?", profileId)
        settings.deleteProfile(profileId)
        publish()
    }

    /** An account was disconnected: its unfinished transfers cannot continue. Finished files and records stay. */
    fun accountRemoved(accountId: Long) {
        _downloads.value.filter { it.accountId == accountId && it.active }.forEach { download ->
            stopJob(download.id)
            runCatching { Files.deleteIfExists(part(download)) }
            database.execute("UPDATE downloads SET state = 'FAILED', error = ?, finished_at = ? WHERE id = ?", "The ${download.provider.displayName} account was disconnected.", clock(), download.id)
        }
        publish()
    }

    /** Stops every transfer where it is; the queue stays in the database and continues at the next [start]. */
    fun close() {
        closed = true
        jobs.keys.toList().forEach(::stopJob)
    }

    // ---------------------------------------------------------------- running

    private fun pump(): Unit = synchronized(pumpLock) {
        if (closed) return
        val all = loadAll()
        val running = all.count { it.state == DownloadState.RUNNING && jobs[it.id]?.isActive == true }
        var free = (all.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.RUNNING }.minOfOrNull { settings.maxConcurrent(it.profileId) } ?: 3) - running
        for (download in all.filter { it.state == DownloadState.QUEUED }.sortedBy { it.createdAt }) {
            if (free <= 0) break
            if (jobs[download.id]?.isActive == true) continue
            setState(download.id, DownloadState.RUNNING, null)
            database.execute("UPDATE downloads SET started_at = COALESCE(started_at, ?) WHERE id = ?", clock(), download.id)
            live[download.id] = Live(received = download.receivedBytes, total = download.totalBytes)
            jobs[download.id] = scope.launch {
                try {
                    run(download)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // Anything unforeseen ends this one download with its reason; the queue goes on.
                    if (!closed) runCatching { fail(download.id, "The download stopped unexpectedly (${t::class.simpleName}).") }
                } finally {
                    jobs.remove(download.id)
                    live.remove(download.id)
                    connections.remove(download.id)
                    if (!closed) runCatching { pump() }
                }
            }
            free--
        }
        publish()
    }

    private fun stopJob(id: Long) {
        jobs[id]?.cancel()
        // A blocked read does not notice cancellation; closing the connection ends it.
        connections.remove(id)?.let { connection -> runCatching { connection.disconnect() } }
    }

    private suspend fun run(download: Download) {
        val id = download.id
        val part = part(download)
        var linkFailures = 0
        var failures = 0
        var fresh = false
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val before = if (Files.exists(part)) Files.size(part) else 0L
            try {
                fetch(download, part, fresh)
                finish(download, part)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkExpired) {
                // The address no longer works: ask the provider for a new one, a few times at most.
                fresh = true
                if (++linkFailures > MAX_FRESH_LINKS) return fail(id, "The download address is no longer valid, and a new one did not work either.")
            } catch (e: CloudException) {
                val temporary = e.reason == Reason.RATE_LIMITED || e.reason == Reason.UNAVAILABLE || e.reason == Reason.NETWORK
                if (!temporary || !settings.autoRetry(download.profileId) || ++failures > MAX_ATTEMPTS) return fail(id, e.message ?: "The provider refused the download.")
                delay((e.retryAfterSeconds?.coerceIn(0, 120)?.times(1000L)) ?: retryDelayMs(failures))
            } catch (e: Permanent) {
                return fail(id, e.message ?: "The download failed.")
            } catch (e: IOException) {
                if (jobs[id]?.isActive != true) throw CancellationException("stopped")
                // Progress since the last failure means the connection works: the count starts again.
                val after = if (Files.exists(part)) Files.size(part) else 0L
                failures = if (after > before) 1 else failures + 1
                if (!settings.autoRetry(download.profileId) || failures > MAX_ATTEMPTS) return fail(id, "The connection was lost and did not come back (${e.message?.take(80) ?: "network error"}).")
                database.execute("UPDATE downloads SET attempts = attempts + 1 WHERE id = ?", id)
                live[id]?.note = "Connection lost; trying again"
                publish()
                delay(retryDelayMs(failures))
            }
        }
    }

    private class LinkExpired : IOException("expired")
    private class Permanent(message: String) : Exception(message)

    /** One attempt: a fresh address, a range request from where the partial file ends, bytes to disk. */
    private suspend fun fetch(download: Download, part: Path, fresh: Boolean) {
        val id = download.id
        val reserve = settings.spaceReserveMb(download.profileId) * 1024 * 1024
        Files.createDirectories(part.parent)
        var have = if (Files.exists(part)) Files.size(part) else 0L
        val remaining = (download.totalBytes - have).coerceAtLeast(0)
        val space = runCatching { freeSpace(part.parent) }.getOrNull()
        if (space != null && reserve > 0 && remaining > 0 && space - remaining < reserve) {
            throw Permanent("Not enough free disk space in ${part.parent} (${megabytes(space)} free, ${megabytes(remaining)} still to fetch, ${megabytes(reserve)} is kept free).")
        }
        val link = files.resolve(download.profileId, download.accountId, download.kind, download.itemRemoteId, download.fileRef, fresh)
        val etag = database.queryOne("SELECT etag FROM downloads WHERE id = ?", id) { it.getString(1) }
        val headers = if (have > 0) mapOf("Range" to "bytes=$have-") + (etag?.let { mapOf("If-Range" to it) } ?: emptyMap()) else emptyMap()
        val connection = try {
            CloudHttp.open(link.url, headers)
        } catch (e: IllegalArgumentException) {
            throw Permanent("The provider gave an address that cannot be used.")
        }
        connections[id] = connection
        try {
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                // Not the exception's own text: it can quote the address.
                throw IOException("the download server could not be reached")
            }
            var total = download.totalBytes
            when (status) {
                200 -> {
                    if (have > 0) live[id]?.note = "The server does not continue partial downloads; starting over"
                    have = 0
                    connection.contentLengthLong.takeIf { it > 0 }?.let { total = it }
                }
                206 -> {
                    val range = connection.getHeaderField("Content-Range").orEmpty()
                    val start = Regex("bytes (\\d+)-").find(range)?.groupValues?.get(1)?.toLongOrNull()
                    if (start != have) throw IOException("the server continued from the wrong place")
                    range.substringAfter('/', "").toLongOrNull()?.takeIf { it > 0 }?.let { total = it }
                    live[id]?.note = null
                }
                416 -> {
                    // Asked to continue past the end: either the file is complete, or the partial file is no good.
                    if (total > 0 && have == total) return
                    Files.deleteIfExists(part)
                    throw IOException("the partial file did not match; starting over")
                }
                401, 403, 404, 410 -> throw LinkExpired()
                429, 503 -> throw CloudException(Reason.UNAVAILABLE, "The download server is busy.", connection.getHeaderField("Retry-After")?.trim()?.toIntOrNull())
                else -> throw Permanent("The download server answered $status.")
            }
            database.execute("UPDATE downloads SET total_bytes = ?, etag = ? WHERE id = ?", total, connection.getHeaderField("ETag"), id)
            val state = live.getOrPut(id) { Live() }
            state.total = total
            state.received = have
            val options = if (have > 0) arrayOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND) else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            var windowStart = clock()
            var windowBytes = 0L
            var lastSaved = clock()
            var lastShown = clock()
            connection.inputStream.use { input ->
                Files.newOutputStream(part, *options).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        have += read
                        windowBytes += read
                        state.received = have
                        val now = clock()
                        if (now - windowStart >= SPEED_WINDOW_MS) {
                            val current = windowBytes * 1000 / (now - windowStart).coerceAtLeast(1)
                            // Smoothed, so the figure does not jump with every burst.
                            state.bytesPerSecond = if (state.bytesPerSecond == 0L) current else (state.bytesPerSecond * 6 + current * 4) / 10
                            windowStart = now
                            windowBytes = 0
                        }
                        if (now - lastShown >= SHOW_EVERY_MS) {
                            lastShown = now
                            publish()
                        }
                        if (now - lastSaved >= SAVE_EVERY_MS) {
                            database.execute("UPDATE downloads SET received_bytes = ? WHERE id = ?", have, id)
                            lastSaved = now
                        }
                    }
                }
            }
            database.execute("UPDATE downloads SET received_bytes = ? WHERE id = ?", have, id)
            if (total > 0 && have < total) throw IOException("the transfer ended early")
        } finally {
            connections.remove(id)
            runCatching { connection.disconnect() }
            // However the transfer ended (paused, dropped), what is on disk is what was received.
            runCatching {
                if (Files.isRegularFile(part)) {
                    database.execute("UPDATE downloads SET received_bytes = ? WHERE id = ? AND state IN ('QUEUED', 'RUNNING', 'PAUSED', 'FAILED')", Files.size(part), id)
                    publish()
                }
            }
        }
    }

    /** Checks the finished file and moves it into place, never onto another file unless the profile chose so. */
    private fun finish(download: Download, part: Path) {
        val id = download.id
        val size = Files.size(part)
        var verified: Boolean? = null
        download.expectedMd5?.let { expected ->
            live[id]?.note = "Checking the file"
            publish()
            verified = md5(part).equals(expected, ignoreCase = true)
            if (verified == false) {
                Files.deleteIfExists(part)
                database.execute("UPDATE downloads SET verified = 0, received_bytes = 0 WHERE id = ?", id)
                return fail(id, "The file did not match the checksum ${download.provider.displayName} gave for it, so it was not kept.")
            }
        }
        var target = Paths.get(download.destination)
        val replace = settings.conflictPolicy(download.profileId) == ConflictPolicy.REPLACE
        if (Files.exists(target) && !replace) target = nextFree(target)
        try {
            if (replace) Files.move(part, target, StandardCopyOption.REPLACE_EXISTING) else Files.move(part, target)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            target = nextFree(target)
            Files.move(part, target)
        }
        database.execute(
            "UPDATE downloads SET state = 'DONE', destination = ?, total_bytes = ?, received_bytes = ?, verified = ?, error = NULL, finished_at = ?, file_deleted_at = NULL WHERE id = ?",
            target.toString(), size, size, verified?.let { if (it) 1 else 0 }, clock(), id,
        )
    }

    private fun fail(id: Long, message: String) {
        database.execute("UPDATE downloads SET state = 'FAILED', error = ?, finished_at = ? WHERE id = ? AND state = 'RUNNING'", message.take(300), clock(), id)
    }

    private fun setState(id: Long, state: DownloadState, error: String?) {
        database.execute("UPDATE downloads SET state = ?, error = ? WHERE id = ?", state.name, error, id)
    }

    private fun part(download: Download): Path = Paths.get(download.destination + ".part")

    private fun md5(file: Path): String {
        val digest = MessageDigest.getInstance("MD5")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- rows

    private fun loadAll(): List<Download> = database.query("SELECT * FROM downloads ORDER BY created_at DESC, id DESC", map = ::row)

    private fun load(id: Long): Download? = database.queryOne("SELECT * FROM downloads WHERE id = ?", id, map = ::row)

    private fun publish() {
        if (closed) return
        _downloads.value = loadAll().map { download ->
            val state = live[download.id]?.takeIf { download.state == DownloadState.RUNNING } ?: return@map download
            download.copy(receivedBytes = maxOf(download.receivedBytes, state.received), totalBytes = maxOf(download.totalBytes, state.total), bytesPerSecond = state.bytesPerSecond, note = state.note)
        }
    }

    private fun row(rs: ResultSet) = Download(
        id = rs.getLong("id"),
        profileId = rs.getLong("profile_id"),
        accountId = rs.getLong("account_id"),
        provider = ProviderId.of(rs.getString("provider")) ?: ProviderId.REAL_DEBRID,
        kind = ItemKind.of(rs.getString("item_kind")) ?: ItemKind.TORRENT,
        itemRemoteId = rs.getString("item_remote_id"),
        fileRef = rs.getString("file_ref"),
        itemName = rs.getString("item_name"),
        filePath = rs.getString("file_path"),
        mediaClass = MediaClass.of(rs.getString("media_class")),
        destination = rs.getString("destination"),
        totalBytes = rs.getLong("total_bytes"),
        receivedBytes = rs.getLong("received_bytes"),
        expectedMd5 = rs.getString("expected_md5"),
        verified = rs.getInt("verified").let { value -> if (rs.wasNull()) null else value != 0 },
        state = runCatching { DownloadState.valueOf(rs.getString("state")) }.getOrDefault(DownloadState.FAILED),
        error = rs.getString("error"),
        importedInto = rs.getString("imported_into"),
        importRef = rs.getString("import_ref"),
        createdAt = rs.getLong("created_at"),
        finishedAt = rs.getLong("finished_at").let { value -> if (rs.wasNull()) null else value },
        fileDeletedAt = rs.getLong("file_deleted_at").let { value -> if (rs.wasNull()) null else value },
    )

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val MAX_FRESH_LINKS = 3
        private const val SPEED_WINDOW_MS = 1_000L
        private const val SAVE_EVERY_MS = 2_000L
        private const val SHOW_EVERY_MS = 250L

        private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        /** One part of a path, made safe: no separators, no control characters, never `.` or `..`, not too long. */
        fun safeSegment(text: String): String {
            val cleaned = text.replace(UNSAFE, "_").trim().trim('.').trim()
            return cleaned.take(180).ifEmpty { if (text.isBlank()) "" else "_" }
        }

        /** The nearest folder that exists at or above [path]: where free space can be asked for. */
        fun existingAncestor(path: Path): Path {
            var current: Path? = path.toAbsolutePath()
            while (current != null && !Files.exists(current)) current = current.parent
            return current ?: path.toAbsolutePath().root
        }

        fun megabytes(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) "%.1f GB".format(bytes / (1024.0 * 1024 * 1024)) else "${bytes / (1024 * 1024)} MB"
    }
}
