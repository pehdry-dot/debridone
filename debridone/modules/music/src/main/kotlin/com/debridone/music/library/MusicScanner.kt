package com.debridone.music.library

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.EnumSet
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live state of a library scan, for the progress display. */
data class ScanProgress(
    val running: Boolean = false,
    val folder: String? = null,
    val filesFound: Int = 0,
    val filesProcessed: Int = 0,
    val currentFile: String? = null,
    val result: ScanResult = ScanResult(),
    val finishedAt: Long? = null,
    val error: String? = null,
)

/** What a scan did. */
data class ScanResult(
    val added: Int = 0,
    val updated: Int = 0,
    /** Files found again at a new location; their track kept its playlists, favourites and edits. */
    val relinked: Int = 0,
    val unchanged: Int = 0,
    /** Tracks whose file is no longer there. They stay in the library, marked as missing. */
    val missing: Int = 0,
    /** Files with an audio extension whose contents could not be parsed. */
    val unreadable: Int = 0,
    /** Files that are a byte-identical copy of another track already in the library. */
    val duplicates: Int = 0,
) {
    operator fun plus(other: ScanResult) = ScanResult(
        added + other.added, updated + other.updated, relinked + other.relinked, unchanged + other.unchanged,
        missing + other.missing, unreadable + other.unreadable, duplicates + other.duplicates,
    )
}

/**
 * Brings the library in line with the files on disk.
 *
 * Scans are incremental: a file whose size and modification time are unchanged is not opened again.
 * Files are referenced where they are and never copied. A file that was moved or renamed is
 * recognised by its content fingerprint and keeps its track row; a file that disappeared is marked
 * missing rather than deleted, so playlists survive an unplugged drive.
 */
class MusicScanner(
    private val repository: MusicLibraryRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _progress = MutableStateFlow(ScanProgress())
    val progress: StateFlow<ScanProgress> = _progress.asStateFlow()

    /** Running totals of the scan in progress, shown while it is still working. */
    private var live = ScanResult()

    private fun note(delta: ScanResult): ScanResult {
        live += delta
        _progress.update { it.copy(result = live) }
        return delta
    }

    /** Scans every folder of [profileId] and re-checks individually imported files. */
    suspend fun scanAll(profileId: Long): ScanResult = tracked(profileId, null) {
        var total = ScanResult()
        for (folder in repository.folders(profileId)) {
            total += scanFolderInternal(profileId, folder)
        }
        total += checkLooseFiles(profileId)
        total
    }

    suspend fun scanFolder(profileId: Long, folderId: Long): ScanResult = tracked(profileId, null) {
        val folder = repository.folders(profileId).firstOrNull { it.id == folderId }
            ?: throw IllegalArgumentException("That folder is not in this profile's library.")
        scanFolderInternal(profileId, folder)
    }

    /** Adds single files (not a whole folder) to the library. Directories in [files] are ignored. */
    suspend fun importFiles(profileId: Long, files: List<Path>): ScanResult = tracked(profileId, "Imported files") {
        val candidates = files.map { it.toAbsolutePath().normalize() }
            .filter { Files.isRegularFile(it) && AudioTagReader.isSupported(it) }
            .distinct()
        _progress.update { it.copy(filesFound = candidates.size) }
        val existing = candidates.mapNotNull { repository.fileRecordByPath(profileId, it.toString()) }.associateBy { it.path }
        val onDisk = candidates.mapNotNull { path -> stat(path)?.let { path.toString() to it } }.toMap()
        // A file imported on its own keeps the folder it may already belong to.
        reconcile(profileId, folderId = null, onDisk = onDisk, known = existing, markGoneAsMissing = false, keepFolderOfKnown = true)
    }

    private suspend fun scanFolderInternal(profileId: Long, folder: LibraryFolder): ScanResult {
        val root = Paths.get(folder.path)
        _progress.update { it.copy(folder = folder.path, currentFile = null) }
        val known = repository.fileRecords(profileId, folder.id).associateBy { it.path }
        if (!Files.isDirectory(root)) {
            // The whole folder is unreachable (unplugged drive, deleted). Keep the tracks, flag them.
            val newlyMissing = known.values.filter { !it.missing }
            repository.setMissing(newlyMissing.map { it.id }, true)
            repository.notifyChanged()
            return note(ScanResult(missing = newlyMissing.size))
        }
        val onDisk = listAudioFiles(root)
        _progress.update { it.copy(filesFound = it.filesFound + onDisk.size) }
        val result = reconcile(profileId, folder.id, onDisk, known, markGoneAsMissing = true, keepFolderOfKnown = false)
        repository.markFolderScanned(folder.id)
        return result
    }

    /** Individually imported files are not under a scanned folder; check that they still exist. */
    private fun checkLooseFiles(profileId: Long): ScanResult {
        val loose = repository.fileRecords(profileId, folderId = null)
        val gone = loose.filter { !it.missing && !Files.isRegularFile(Paths.get(it.path)) }
        val back = loose.filter { it.missing && Files.isRegularFile(Paths.get(it.path)) }
        repository.setMissing(gone.map { it.id }, true)
        repository.setMissing(back.map { it.id }, false)
        return note(ScanResult(missing = gone.size, updated = back.size))
    }

    private data class FileStat(val size: Long, val mtime: Long)

    private suspend fun reconcile(
        profileId: Long,
        folderId: Long?,
        onDisk: Map<String, FileStat>,
        known: Map<String, FileRecord>,
        markGoneAsMissing: Boolean,
        keepFolderOfKnown: Boolean,
    ): ScanResult {
        var result = ScanResult()
        val gone = known.values.filter { it.path !in onDisk }.associateBy { it.id }.toMutableMap()
        val reappeared = ArrayList<Long>()
        val pending = ArrayList<PendingWrite>()
        val folderArtwork = HashMap<Path, Long?>()

        suspend fun flush() {
            if (pending.isEmpty()) return
            repository.batch {
                with(repository) {
                    pending.forEach { write -> upsertInTransaction(profileId, write.input, write.existingId) }
                }
            }
            pending.clear()
            repository.notifyChanged()
        }

        for ((pathText, stat) in onDisk) {
            coroutineContext.ensureActive()
            val path = Paths.get(pathText)
            val record = known[pathText]
            _progress.update { it.copy(filesProcessed = it.filesProcessed + 1, currentFile = path.fileName.toString()) }

            if (record != null && record.size == stat.size && record.mtime == stat.mtime && record.fingerprint != null) {
                if (record.missing) reappeared += record.id
                result += note(ScanResult(unchanged = 1))
                continue
            }

            val fingerprint = runCatching { fingerprint(path, stat.size) }.getOrNull()
            var existingId = record?.id
            var relinked = false
            if (record == null && fingerprint != null) {
                // Same content under a new name: was it moved or renamed, or is it a second copy?
                val sameContent = repository.fileRecordsByFingerprint(profileId, fingerprint)
                val moved = sameContent.firstOrNull { it.id in gone } ?: sameContent.firstOrNull { !Files.exists(Paths.get(it.path)) }
                if (moved != null) {
                    existingId = moved.id
                    gone.remove(moved.id)
                    relinked = true
                } else if (sameContent.isNotEmpty()) {
                    result += note(ScanResult(duplicates = 1))
                }
            }

            val tags = AudioTagReader.read(path)
            val artworkId = tags.artwork?.let { repository.artwork.store(profileId, it.bytes, ArtworkStore.ORIGIN_EMBEDDED) }
                ?: folderArtwork.getOrPut(path.parent) { folderCover(path.parent)?.let { repository.artwork.storeFile(profileId, it, ArtworkStore.ORIGIN_FOLDER) } }
            pending += PendingWrite(
                existingId,
                TrackInput(
                    folderId = if (keepFolderOfKnown && record != null) record.folderId else folderId,
                    path = pathText,
                    fileSize = stat.size,
                    fileMtime = stat.mtime,
                    fingerprint = fingerprint,
                    title = tags.title ?: path.fileName.toString(),
                    artist = tags.artist,
                    album = tags.album,
                    albumArtist = tags.albumArtist,
                    genre = tags.genre,
                    composer = tags.composer,
                    year = tags.year,
                    trackNo = tags.trackNo,
                    trackTotal = tags.trackTotal,
                    discNo = tags.discNo,
                    discTotal = tags.discTotal,
                    durationMs = tags.durationMs,
                    format = tags.format,
                    codec = tags.codec,
                    bitrateKbps = tags.bitrateKbps,
                    sampleRate = tags.sampleRate,
                    channels = tags.channels,
                    bitDepth = tags.bitDepth,
                    artworkId = artworkId,
                    musicBrainzRecordingId = tags.musicBrainzRecordingId,
                    musicBrainzReleaseId = tags.musicBrainzReleaseId,
                ),
            )
            result += note(
                ScanResult(
                    added = if (existingId == null) 1 else 0,
                    updated = if (existingId != null && !relinked) 1 else 0,
                    relinked = if (relinked) 1 else 0,
                    unreadable = if (tags.readable) 0 else 1,
                ),
            )
            if (pending.size >= BATCH_SIZE) flush()
        }
        flush()

        repository.setMissing(reappeared, false)
        if (markGoneAsMissing) {
            val newlyMissing = gone.values.filter { !it.missing }
            repository.setMissing(newlyMissing.map { it.id }, true)
            result += note(ScanResult(missing = newlyMissing.size))
        }
        return result
    }

    private class PendingWrite(val existingId: Long?, val input: TrackInput)

    private suspend fun tracked(profileId: Long, label: String?, block: suspend () -> ScanResult): ScanResult {
        live = ScanResult()
        _progress.value = ScanProgress(running = true, folder = label)
        try {
            val result = block()
            repository.finishScan(profileId)
            _progress.update { it.copy(running = false, currentFile = null, result = result, finishedAt = clock()) }
            return result
        } catch (t: Throwable) {
            _progress.update { it.copy(running = false, currentFile = null, finishedAt = clock(), error = t.message ?: t::class.simpleName) }
            throw t
        }
    }

    private fun listAudioFiles(root: Path): Map<String, FileStat> {
        val files = LinkedHashMap<String, FileStat>()
        Files.walkFileTree(
            root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), MAX_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString().orEmpty()
                    // Hidden folders and macOS bundles (Photos libraries, apps) are not music folders.
                    return if (dir != root && (name.startsWith(".") || name.endsWith(".app") || name.endsWith(".photoslibrary"))) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.fileName.toString()
                    if (attrs.isRegularFile && !name.startsWith(".") && AudioTagReader.isSupported(file)) {
                        files[file.toString()] = FileStat(attrs.size(), attrs.lastModifiedTime().toMillis())
                    }
                    return FileVisitResult.CONTINUE
                }

                // An unreadable subfolder or a link loop must not abort the whole scan.
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return files
    }

    private fun stat(path: Path): FileStat? = runCatching {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        FileStat(attributes.size(), attributes.lastModifiedTime().toMillis())
    }.getOrNull()

    /** An image in the album folder, used when the files carry no embedded cover. */
    private fun folderCover(directory: Path?): Path? {
        if (directory == null) return null
        val images = runCatching {
            Files.newDirectoryStream(directory).use { stream ->
                stream.filter { file ->
                    val name = file.fileName.toString().lowercase(Locale.ROOT)
                    Files.isRegularFile(file) && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))
                }
            }
        }.getOrDefault(emptyList())
        if (images.isEmpty()) return null
        fun rank(file: Path): Int {
            val name = file.fileName.toString().lowercase(Locale.ROOT).substringBeforeLast('.')
            return COVER_NAMES.indexOfFirst { name == it || name.startsWith(it) }.let { if (it < 0) COVER_NAMES.size else it }
        }
        return images.minWithOrNull(compareBy<Path>({ rank(it) }, { it.fileName.toString() }))?.takeIf { rank(it) < COVER_NAMES.size || images.size == 1 }
    }

    companion object {
        private const val BATCH_SIZE = 40
        private const val MAX_DEPTH = 24
        private const val FINGERPRINT_WINDOW = 128 * 1024
        private val COVER_NAMES = listOf("cover", "folder", "front", "album", "albumart", "artwork")

        /**
         * Identifies a file by its content, cheaply: the size plus the first and last 128 KB. That is
         * enough to recognise a file that was moved or renamed without reading whole albums again.
         */
        fun fingerprint(file: Path, size: Long = Files.size(file)): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(size.toString().toByteArray())
            FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                val buffer = java.nio.ByteBuffer.allocate(FINGERPRINT_WINDOW)
                fun feed(position: Long) {
                    buffer.clear()
                    var offset = position
                    while (buffer.hasRemaining()) {
                        val read = channel.read(buffer, offset)
                        if (read <= 0) break
                        offset += read
                    }
                    buffer.flip()
                    digest.update(buffer)
                }
                feed(0)
                if (size > FINGERPRINT_WINDOW) feed(maxOf(FINGERPRINT_WINDOW.toLong(), size - FINGERPRINT_WINDOW))
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
