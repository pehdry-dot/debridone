package com.debridone.manga.plugins

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** The archive is not something this installer will unpack. The message says why, for the user. */
class PackageRejectedException(message: String) : IOException(message)

/**
 * Reads npm package archives (`.tgz`: a gzip-compressed tar file).
 *
 * A package archive is untrusted input, so this reader is strict: only ordinary files and folders
 * are written; links, devices and anything else are refused; every path must stay inside the target
 * folder; and there are limits on the number of entries and on the unpacked size.
 */
object TarGz {
    data class Limits(
        val maxEntries: Int = 20_000,
        val maxTotalBytes: Long = 300L * 1024 * 1024,
        val maxFileBytes: Long = 64L * 1024 * 1024,
    )

    data class Result(val files: Int, val bytes: Long)

    private const val BLOCK = 512

    /**
     * Unpacks [archive] into [target], dropping the first [stripComponents] path segments of every
     * entry (npm archives keep everything under one top folder, usually `package/`).
     */
    fun extract(archive: InputStream, target: Path, stripComponents: Int = 1, limits: Limits = Limits()): Result {
        Files.createDirectories(target)
        val root = target.toAbsolutePath().normalize()
        var files = 0
        var bytes = 0L
        walk(archive, limits) { entry, content ->
            val relative = safeRelativePath(entry.name, stripComponents) ?: return@walk
            val destination = root.resolve(relative).normalize()
            if (!destination.startsWith(root)) throw PackageRejectedException("The archive contains a path that leaves its folder: ${entry.name}")
            when (entry.type) {
                EntryType.DIRECTORY -> Files.createDirectories(destination)
                EntryType.FILE -> {
                    if (entry.size > limits.maxFileBytes) throw PackageRejectedException("The archive contains a file that is too large: ${entry.name}")
                    bytes += entry.size
                    if (bytes > limits.maxTotalBytes) throw PackageRejectedException("The archive unpacks to more than ${limits.maxTotalBytes / (1024 * 1024)} MB.")
                    Files.createDirectories(destination.parent)
                    Files.newOutputStream(destination).use { output -> content.copyTo(output, entry.size) }
                    files++
                }
            }
        }
        return Result(files, bytes)
    }

    /** Returns the content of the entry whose path (after stripping) is [path], or null. */
    fun readFile(archive: InputStream, path: String, stripComponents: Int = 1, maxBytes: Long = 4L * 1024 * 1024): ByteArray? {
        var found: ByteArray? = null
        walk(archive, Limits()) { entry, content ->
            if (found == null && entry.type == EntryType.FILE && safeRelativePath(entry.name, stripComponents) == path) {
                if (entry.size > maxBytes) throw PackageRejectedException("$path is too large.")
                val buffer = ByteArrayOutputStream(entry.size.toInt())
                content.copyTo(buffer, entry.size)
                found = buffer.toByteArray()
            }
        }
        return found
    }

    private enum class EntryType { FILE, DIRECTORY }

    private class Entry(val name: String, val type: EntryType, val size: Long)

    private class Content(private val input: InputStream) {
        var consumed = 0L
        fun copyTo(output: java.io.OutputStream, size: Long) {
            val buffer = ByteArray(64 * 1024)
            var remaining = size
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw EOFException("The archive ends in the middle of a file.")
                output.write(buffer, 0, read)
                remaining -= read
            }
            consumed = size
        }
    }

    private fun walk(archive: InputStream, limits: Limits, visit: (Entry, Content) -> Unit) {
        val input = try {
            GZIPInputStream(archive, 64 * 1024)
        } catch (failure: IOException) {
            throw PackageRejectedException("This is not a package archive (.tgz).")
        }
        val header = ByteArray(BLOCK)
        var entries = 0
        var longName: String? = null
        var paxPath: String? = null
        var paxSize: Long? = null
        while (true) {
            if (!readBlock(input, header)) break
            if (header.all { it == 0.toByte() }) break
            if (++entries > limits.maxEntries) throw PackageRejectedException("The archive has more than ${limits.maxEntries} entries.")
            val type = header[156].toInt().toChar()
            val headerSize = parseNumber(header, 124, 12)
            val size = paxSize ?: headerSize
            when (type) {
                // Extended headers describe the entry that follows.
                'x' -> {
                    val records = parsePax(readExact(input, headerSize, 1024 * 1024))
                    paxPath = records["path"]
                    paxSize = records["size"]?.toLongOrNull()
                    skipPadding(input, headerSize)
                    continue
                }
                'g' -> {
                    skip(input, headerSize)
                    skipPadding(input, headerSize)
                    continue
                }
                'L' -> {
                    longName = String(readExact(input, headerSize, 64 * 1024), StandardCharsets.UTF_8).trimEnd('\u0000')
                    skipPadding(input, headerSize)
                    continue
                }
                'K' -> throw PackageRejectedException("The archive contains a link, which is not allowed in an extension package.")
            }
            val name = paxPath ?: longName ?: headerName(header)
            paxPath = null
            paxSize = null
            longName = null
            when (type) {
                '0', '\u0000', '7' -> {
                    val content = Content(input)
                    visit(Entry(name, EntryType.FILE, size), content)
                    skip(input, size - content.consumed)
                    skipPadding(input, size)
                }
                '5' -> {
                    visit(Entry(name, EntryType.DIRECTORY, 0), Content(input))
                    skip(input, size)
                    skipPadding(input, size)
                }
                '1', '2' -> throw PackageRejectedException("The archive contains a link ($name), which is not allowed in an extension package.")
                else -> throw PackageRejectedException("The archive contains an entry that is not a file or folder ($name).")
            }
        }
    }

    /** Null means "skip this entry" (it is the stripped top folder itself). */
    private fun safeRelativePath(name: String, stripComponents: Int): String? {
        if (name.contains('\u0000')) throw PackageRejectedException("The archive contains an invalid path.")
        val unified = name.replace('\\', '/')
        if (unified.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(unified)) {
            throw PackageRejectedException("The archive contains an absolute path: $name")
        }
        val segments = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) throw PackageRejectedException("The archive contains a path that leaves its folder: $name")
        val kept = segments.drop(stripComponents)
        return if (kept.isEmpty()) null else kept.joinToString("/")
    }

    private fun headerName(header: ByteArray): String {
        val name = text(header, 0, 100)
        val isUstar = text(header, 257, 5) == "ustar"
        val prefix = if (isUstar) text(header, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun text(block: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && block[end] != 0.toByte()) end++
        return String(block, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun parseNumber(block: ByteArray, offset: Int, length: Int): Long {
        // GNU base-256 for sizes that do not fit in octal.
        if (block[offset].toInt() and 0x80 != 0) {
            var value = 0L
            for (index in offset + 1 until offset + length) value = (value shl 8) or (block[index].toLong() and 0xff)
            return value
        }
        val digits = text(block, offset, length).trim()
        if (digits.isEmpty()) return 0
        return digits.toLongOrNull(8) ?: throw PackageRejectedException("The archive is damaged (unreadable size).")
    }

    private fun parsePax(data: ByteArray): Map<String, String> {
        val records = HashMap<String, String>()
        var position = 0
        while (position < data.size) {
            var space = position
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            if (space >= data.size) break
            val length = String(data, position, space - position, StandardCharsets.US_ASCII).toIntOrNull() ?: break
            if (length <= 0 || position + length > data.size) break
            val record = String(data, space + 1, length - (space - position) - 2, StandardCharsets.UTF_8)
            val equals = record.indexOf('=')
            if (equals > 0) records[record.substring(0, equals)] = record.substring(equals + 1)
            position += length
        }
        return records
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var offset = 0
        while (offset < block.size) {
            val read = input.read(block, offset, block.size - offset)
            if (read < 0) {
                if (offset == 0) return false
                throw PackageRejectedException("The archive is damaged (it ends in the middle of an entry).")
            }
            offset += read
        }
        return true
    }

    private fun readExact(input: InputStream, size: Long, max: Int): ByteArray {
        if (size < 0 || size > max) throw PackageRejectedException("The archive is damaged (oversized header).")
        val data = ByteArray(size.toInt())
        var offset = 0
        while (offset < data.size) {
            val read = input.read(data, offset, data.size - offset)
            if (read < 0) throw PackageRejectedException("The archive is damaged (it ends in the middle of an entry).")
            offset += read
        }
        return data
    }

    private fun skip(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(16 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw PackageRejectedException("The archive is damaged (it ends in the middle of an entry).")
            remaining -= read
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val remainder = (size % BLOCK).toInt()
        if (remainder != 0) skip(input, (BLOCK - remainder).toLong())
    }
}
