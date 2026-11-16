package com.debridone.music.playlists

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** One line of a playlist file that names something to play. */
data class M3uEntry(
    /** Absolute file path, or null when the entry is a network URL. */
    val path: Path?,
    val url: String?,
    /** The `#EXTINF` text ("Artist - Title"), when present. */
    val label: String?,
    val durationSeconds: Int?,
) {
    val display: String get() = label ?: path?.fileName?.toString() ?: url.orEmpty()
}

/** Reads and writes M3U and M3U8 playlists (plain and extended). */
object M3uCodec {
    fun read(file: Path): List<M3uEntry> = parse(decode(Files.readAllBytes(file), file), file.toAbsolutePath().parent)

    /**
     * `.m3u8` is UTF-8 by definition. `.m3u` has no declared encoding: UTF-8 is tried first and
     * Windows-1252 used when the bytes are not valid UTF-8, which is what older players wrote.
     */
    internal fun decode(bytes: ByteArray, file: Path? = null): String {
        val withoutBom = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        val strictUtf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strictUtf8.decode(ByteBuffer.wrap(withoutBom)).toString()
        } catch (_: CharacterCodingException) {
            val fallback = if (file?.fileName?.toString()?.endsWith(".m3u8", ignoreCase = true) == true) StandardCharsets.UTF_8 else charset("windows-1252")
            String(withoutBom, fallback)
        }
    }

    internal fun parse(text: String, baseDirectory: Path?): List<M3uEntry> {
        val entries = ArrayList<M3uEntry>()
        var label: String? = null
        var duration: Int? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                    val body = line.substring(8)
                    val comma = body.indexOf(',')
                    duration = (if (comma >= 0) body.substring(0, comma) else body).trim().substringBefore(' ').toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
                    label = if (comma >= 0) body.substring(comma + 1).trim().takeIf { it.isNotEmpty() } else null
                }
                continue
            }
            entries += toEntry(line, baseDirectory, label, duration)
            label = null
            duration = null
        }
        return entries
    }

    private fun toEntry(line: String, baseDirectory: Path?, label: String?, duration: Int?): M3uEntry {
        val lower = line.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return M3uEntry(null, line, label, duration)
        val path = runCatching {
            when {
                lower.startsWith("file:") -> Paths.get(URI(line))
                // Windows-style separators in a playlist written on another system.
                else -> Paths.get(if ('\\' in line && '/' !in line && !line.startsWith("\\\\")) line.replace('\\', '/') else line)
            }
        }.getOrNull() ?: return M3uEntry(null, null, label ?: line, duration)
        val absolute = if (path.isAbsolute || baseDirectory == null) path else baseDirectory.resolve(path)
        return M3uEntry(absolute.normalize(), null, label, duration)
    }

    /** One track for [write]. */
    data class Line(val location: String, val label: String, val durationSeconds: Int)

    /** Writes an extended M3U in UTF-8. Paths inside the playlist's own folder are made relative. */
    fun write(target: Path, lines: List<Line>) {
        val directory = target.toAbsolutePath().parent
        val text = buildString {
            append("#EXTM3U\n")
            for (line in lines) {
                append("#EXTINF:").append(line.durationSeconds).append(',').append(line.label.replace('\n', ' ')).append('\n')
                append(relativize(directory, line.location)).append('\n')
            }
        }
        Files.createDirectories(directory)
        Files.write(target, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun relativize(directory: Path, location: String): String {
        if (location.startsWith("http://") || location.startsWith("https://")) return location
        val path = runCatching { Paths.get(location) }.getOrNull() ?: return location
        return if (path.isAbsolute && path.startsWith(directory)) directory.relativize(path).toString() else location
    }
}
