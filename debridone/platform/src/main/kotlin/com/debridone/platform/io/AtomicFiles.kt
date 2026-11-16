package com.debridone.platform.io

import java.io.IOException
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Crash-safe file replacement.
 *
 * A plain `Files.newOutputStream(target)` truncates the file first, so a crash or power loss in
 * the middle of a write leaves an empty or half-written file. [write] instead writes a sibling
 * temp file, flushes it to disk, keeps the previous version as `<name>.bak`, and only then swaps
 * the new file into place with an atomic rename.
 */
object AtomicFiles {
    private const val BACKUP_SUFFIX = ".bak"

    fun backupPathOf(target: Path): Path = target.resolveSibling(target.fileName.toString() + BACKUP_SUFFIX)

    fun write(target: Path, keepBackup: Boolean = true, writer: (OutputStream) -> Unit) {
        val dir = target.toAbsolutePath().parent
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, target.fileName.toString() + ".", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val out = java.nio.channels.Channels.newOutputStream(channel)
                writer(out)
                out.flush()
                channel.force(true)
            }
            if (keepBackup && Files.isRegularFile(target)) {
                runCatching {
                    Files.copy(target, backupPathOf(target), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            syncDirectory(dir)
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    fun writeBytes(target: Path, bytes: ByteArray, keepBackup: Boolean = true) {
        write(target, keepBackup) { it.write(bytes) }
    }

    /**
     * Reads [target] with [parse]; if the file is missing or [parse] throws, falls back to the
     * `.bak` copy written by [write]. Returns null when neither yields a value.
     */
    fun <T : Any> readWithBackup(target: Path, parse: (ByteArray) -> T): T? {
        fun attempt(path: Path): T? {
            if (!Files.isRegularFile(path)) return null
            return try {
                parse(Files.readAllBytes(path))
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }
        return attempt(target) ?: attempt(backupPathOf(target))
    }

    private fun syncDirectory(dir: Path) {
        // Best effort: makes the rename itself durable on POSIX file systems. Not supported on
        // Windows, where opening a directory channel throws.
        runCatching {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
