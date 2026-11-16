package com.debridone.music.library

import java.io.BufferedInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag

class EmbeddedArtwork(val bytes: ByteArray, val mimeType: String?)

/** What a file says about itself. Null means the file does not say. */
data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val composer: String? = null,
    val year: Int? = null,
    val trackNo: Int? = null,
    val trackTotal: Int? = null,
    val discNo: Int? = null,
    val discTotal: Int? = null,
    val lyrics: String? = null,
    val musicBrainzRecordingId: String? = null,
    val musicBrainzReleaseId: String? = null,
    val durationMs: Long = 0,
    val format: String,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val bitDepth: Int? = null,
    val artwork: EmbeddedArtwork? = null,
    /** False when the file could not be parsed and only its name was used. */
    val readable: Boolean = true,
)

/**
 * Reads embedded tags, technical details and cover art.
 *
 * MP3, FLAC, M4A (AAC and ALAC), WAV, AIFF and Ogg Vorbis go through jaudiotagger. Ogg Opus and raw
 * ADTS AAC, which it does not handle, are parsed here. A file nothing can parse still gets a title
 * from its name, so it can be listed and played.
 */
object AudioTagReader {
    /** Extensions the library imports. Each one has a playback test and a tag-reading test. */
    val supportedExtensions: Set<String> = setOf("mp3", "flac", "m4a", "aac", "wav", "aiff", "aif", "ogg", "opus")

    init {
        // jaudiotagger logs every frame it reads at INFO/WARNING level.
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
    }

    fun isSupported(file: Path): Boolean = file.extension.lowercase(Locale.ROOT) in supportedExtensions

    fun read(file: Path): AudioTags {
        val extension = file.extension.lowercase(Locale.ROOT)
        val parsed = runCatching {
            when (extension) {
                "opus" -> readOpus(file)
                "aac" -> readAdts(file)
                "ogg" -> if (isOpusStream(file)) readOpus(file) else readWithTagger(file, extension)
                else -> readWithTagger(file, extension)
            }
        }.getOrNull()
        val tags = parsed ?: AudioTags(format = extension.uppercase(Locale.ROOT), readable = false)
        return withFileNameFallbacks(file, tags)
    }

    // ---------------------------------------------------------------- jaudiotagger

    private fun readWithTagger(file: Path, extension: String): AudioTags {
        val audio = AudioFileIO.read(file.toFile())
        val header = audio.audioHeader
        val tag: Tag? = audio.tag
        fun text(key: FieldKey): String? = runCatching { MusicText.clean(tag?.getFirst(key)) }.getOrNull()

        val (trackNo, trackTotalFromTrack) = splitNumberPair(text(FieldKey.TRACK))
        val (discNo, discTotalFromDisc) = splitNumberPair(text(FieldKey.DISC_NO))
        val artwork = runCatching {
            tag?.firstArtwork?.takeIf { !it.isLinked }?.let { art ->
                art.binaryData?.takeIf { it.isNotEmpty() }?.let { EmbeddedArtwork(it, art.mimeType) }
            }
        }.getOrNull()
        val encoding = MusicText.clean(header.encodingType)
        val lossless = runCatching { header.isLossless }.getOrDefault(false)

        return AudioTags(
            title = text(FieldKey.TITLE),
            artist = text(FieldKey.ARTIST),
            album = text(FieldKey.ALBUM),
            albumArtist = text(FieldKey.ALBUM_ARTIST),
            genre = text(FieldKey.GENRE)?.let(::cleanGenre),
            composer = text(FieldKey.COMPOSER),
            year = parseYear(text(FieldKey.YEAR)),
            trackNo = trackNo,
            trackTotal = text(FieldKey.TRACK_TOTAL)?.toIntOrNull() ?: trackTotalFromTrack,
            discNo = discNo,
            discTotal = text(FieldKey.DISC_TOTAL)?.toIntOrNull() ?: discTotalFromDisc,
            lyrics = text(FieldKey.LYRICS),
            musicBrainzRecordingId = text(FieldKey.MUSICBRAINZ_TRACK_ID),
            musicBrainzReleaseId = text(FieldKey.MUSICBRAINZ_RELEASEID),
            durationMs = (runCatching { header.preciseTrackLength }.getOrDefault(0.0) * 1000).toLong()
                .takeIf { it > 0 } ?: header.trackLength * 1000L,
            format = formatName(extension, encoding, lossless),
            codec = encoding,
            bitrateKbps = runCatching { header.bitRateAsNumber.toInt() }.getOrNull()?.takeIf { it > 0 },
            sampleRate = runCatching { header.sampleRateAsNumber }.getOrNull()?.takeIf { it > 0 },
            channels = parseChannels(header.channels),
            bitDepth = runCatching { header.bitsPerSample }.getOrNull()?.takeIf { it > 0 },
            artwork = artwork,
        )
    }

    private fun formatName(extension: String, encoding: String?, lossless: Boolean): String = when (extension) {
        "mp3" -> "MP3"
        "flac" -> "FLAC"
        "wav" -> "WAV"
        "aiff", "aif" -> "AIFF"
        "ogg" -> "OGG Vorbis"
        "m4a" -> if (lossless || encoding.orEmpty().contains("lossless", ignoreCase = true) ||
            encoding.orEmpty().contains("alac", ignoreCase = true)
        ) "ALAC" else "AAC"
        else -> extension.uppercase(Locale.ROOT)
    }

    private fun parseChannels(value: String?): Int? {
        val text = value?.trim()?.lowercase(Locale.ROOT) ?: return null
        text.toIntOrNull()?.let { return it }
        return when {
            text.startsWith("mono") -> 1
            text.contains("stereo") -> 2
            else -> Regex("\\d+").find(text)?.value?.toIntOrNull()
        }
    }

    // ---------------------------------------------------------------- Ogg Opus

    private fun isOpusStream(file: Path): Boolean = runCatching {
        Files.newInputStream(file).use { input ->
            val head = input.readNBytes(64)
            String(head, Charsets.ISO_8859_1).contains("OpusHead")
        }
    }.getOrDefault(false)

    /**
     * Ogg Opus (RFC 7845): the first packet is `OpusHead`, the second `OpusTags` in Vorbis-comment
     * layout. The duration is the last page's granule position at 48 kHz minus the pre-skip.
     */
    private fun readOpus(file: Path): AudioTags {
        RandomAccessFile(file.toFile(), "r").use { raf ->
            val packets = readOggPackets(raf, count = 2, maxBytes = 16L * 1024 * 1024)
            val head = packets.getOrNull(0)?.takeIf { it.size >= 19 && String(it, 0, 8, Charsets.ISO_8859_1) == "OpusHead" }
                ?: error("not an Opus stream")
            val channels = head[9].toInt() and 0xFF
            val preSkip = ByteBuffer.wrap(head, 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            val comments = packets.getOrNull(1)
                ?.takeIf { it.size >= 8 && String(it, 0, 8, Charsets.ISO_8859_1) == "OpusTags" }
                ?.let { parseVorbisComments(it, offset = 8) }
                .orEmpty()
            fun first(key: String): String? = MusicText.clean(comments[key]?.firstOrNull())

            val lastGranule = lastOggGranule(raf)
            val durationMs = if (lastGranule > preSkip) (lastGranule - preSkip) * 1000 / 48_000 else 0
            val size = raf.length()
            val (trackNo, trackTotalFromTrack) = splitNumberPair(first("TRACKNUMBER"))
            val (discNo, discTotalFromDisc) = splitNumberPair(first("DISCNUMBER"))
            return AudioTags(
                title = first("TITLE"),
                artist = first("ARTIST"),
                album = first("ALBUM"),
                albumArtist = first("ALBUMARTIST") ?: first("ALBUM_ARTIST") ?: first("ALBUM ARTIST"),
                genre = first("GENRE")?.let(::cleanGenre),
                composer = first("COMPOSER"),
                year = parseYear(first("DATE") ?: first("YEAR")),
                trackNo = trackNo,
                trackTotal = (first("TRACKTOTAL") ?: first("TOTALTRACKS"))?.toIntOrNull() ?: trackTotalFromTrack,
                discNo = discNo,
                discTotal = (first("DISCTOTAL") ?: first("TOTALDISCS"))?.toIntOrNull() ?: discTotalFromDisc,
                lyrics = first("LYRICS") ?: first("UNSYNCEDLYRICS"),
                musicBrainzRecordingId = first("MUSICBRAINZ_TRACKID"),
                musicBrainzReleaseId = first("MUSICBRAINZ_ALBUMID"),
                durationMs = durationMs,
                format = "Opus",
                codec = "Opus",
                bitrateKbps = if (durationMs > 0) (size * 8 / durationMs).toInt() else null,
                sampleRate = 48_000,
                channels = channels,
                artwork = comments["METADATA_BLOCK_PICTURE"]?.firstNotNullOfOrNull(::decodeFlacPicture),
            )
        }
    }

    /** Reassembles the first [count] packets of an Ogg stream from its pages. */
    private fun readOggPackets(raf: RandomAccessFile, count: Int, maxBytes: Long): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        var current = java.io.ByteArrayOutputStream()
        var consumed = 0L
        raf.seek(0)
        val header = ByteArray(27)
        while (packets.size < count && consumed < maxBytes) {
            if (raf.read(header) != header.size) break
            if (header[0] != 'O'.code.toByte() || header[1] != 'g'.code.toByte() ||
                header[2] != 'g'.code.toByte() || header[3] != 'S'.code.toByte()
            ) break
            val segments = header[26].toInt() and 0xFF
            val lacing = ByteArray(segments)
            if (raf.read(lacing) != segments) break
            for (value in lacing) {
                val length = value.toInt() and 0xFF
                val chunk = ByteArray(length)
                if (length > 0 && raf.read(chunk) != length) return packets
                consumed += length
                if (packets.size < count) current.write(chunk)
                if (length < 255) {
                    if (packets.size < count) packets += current.toByteArray()
                    current = java.io.ByteArrayOutputStream()
                }
            }
        }
        return packets
    }

    private fun lastOggGranule(raf: RandomAccessFile): Long {
        val length = raf.length()
        val window = minOf(length, 128L * 1024).toInt()
        val tail = ByteArray(window)
        raf.seek(length - window)
        raf.readFully(tail)
        for (index in window - 14 downTo 0) {
            if (tail[index] == 'O'.code.toByte() && tail[index + 1] == 'g'.code.toByte() &&
                tail[index + 2] == 'g'.code.toByte() && tail[index + 3] == 'S'.code.toByte()
            ) {
                val granule = ByteBuffer.wrap(tail, index + 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
                if (granule >= 0) return granule
            }
        }
        return 0
    }

    private fun parseVorbisComments(packet: ByteArray, offset: Int): Map<String, List<String>> {
        val buffer = ByteBuffer.wrap(packet, offset, packet.size - offset).order(ByteOrder.LITTLE_ENDIAN)
        val result = LinkedHashMap<String, MutableList<String>>()
        val vendorLength = buffer.int
        if (vendorLength < 0 || vendorLength > buffer.remaining()) return result
        buffer.position(buffer.position() + vendorLength)
        if (buffer.remaining() < 4) return result
        val entries = buffer.int
        repeat(entries.coerceIn(0, 10_000)) {
            if (buffer.remaining() < 4) return result
            val length = buffer.int
            if (length < 0 || length > buffer.remaining()) return result
            val raw = ByteArray(length)
            buffer.get(raw)
            val text = String(raw, Charsets.UTF_8)
            val separator = text.indexOf('=')
            if (separator > 0) {
                result.getOrPut(text.substring(0, separator).uppercase(Locale.ROOT)) { ArrayList() } += text.substring(separator + 1)
            }
        }
        return result
    }

    /** Base64 of a FLAC picture block: type, MIME, description, dimensions, then the image bytes. */
    private fun decodeFlacPicture(encoded: String): EmbeddedArtwork? = runCatching {
        val buffer = ByteBuffer.wrap(Base64.getMimeDecoder().decode(encoded))
        buffer.int // picture type
        val mime = ByteArray(buffer.int).also(buffer::get)
        buffer.position(buffer.position() + buffer.int) // description
        buffer.position(buffer.position() + 16) // width, height, depth, colours
        val data = ByteArray(buffer.int).also(buffer::get)
        EmbeddedArtwork(data, String(mime, Charsets.US_ASCII)).takeIf { data.isNotEmpty() }
    }.getOrNull()

    // ---------------------------------------------------------------- raw AAC (ADTS)

    private val adtsSampleRates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** A raw AAC stream has no tags; its length is the number of 1024-sample frames it contains. */
    private fun readAdts(file: Path): AudioTags {
        val size = Files.size(file)
        var sampleRate = 0
        var channels = 0
        var frames = 0L
        BufferedInputStream(Files.newInputStream(file), 1 shl 16).use { input ->
            val header = ByteArray(10)
            input.mark(16)
            // Some encoders put an ID3v2 block in front of the stream.
            if (input.read(header, 0, 10) == 10 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                val tagSize = ((header[6].toInt() and 0x7F) shl 21) or ((header[7].toInt() and 0x7F) shl 14) or
                    ((header[8].toInt() and 0x7F) shl 7) or (header[9].toInt() and 0x7F)
                input.skipNBytes(tagSize.toLong())
            } else {
                input.reset()
            }
            val frame = ByteArray(7)
            while (true) {
                if (input.readNBytes(frame, 0, 7) != 7) break
                if ((frame[0].toInt() and 0xFF) != 0xFF || (frame[1].toInt() and 0xF0) != 0xF0) break
                val length = ((frame[3].toInt() and 0x03) shl 11) or ((frame[4].toInt() and 0xFF) shl 3) or ((frame[5].toInt() and 0xE0) shr 5)
                if (length < 7) break
                if (frames == 0L) {
                    sampleRate = adtsSampleRates.getOrElse((frame[2].toInt() and 0x3C) shr 2) { 0 }
                    channels = ((frame[2].toInt() and 0x01) shl 2) or ((frame[3].toInt() and 0xC0) shr 6)
                }
                frames++
                val toSkip = (length - 7).toLong()
                if (input.skip(toSkip) != toSkip) break
            }
        }
        check(frames > 0 && sampleRate > 0) { "not an ADTS stream" }
        val durationMs = frames * 1024 * 1000 / sampleRate
        return AudioTags(
            durationMs = durationMs,
            format = "AAC",
            codec = "AAC (ADTS)",
            bitrateKbps = if (durationMs > 0) (size * 8 / durationMs).toInt() else null,
            sampleRate = sampleRate,
            channels = channels.takeIf { it > 0 },
        )
    }

    // ---------------------------------------------------------------- shared helpers

    private val leadingNumber = Regex("^\\s*(\\d{1,3})\\s*[-._)]?\\s+(.+)$")

    /**
     * "07 - Song" names a track number and a title. The name fills in whichever of the two the tags
     * do not provide (WAV files, for one, have no standard field for the track number).
     */
    private fun withFileNameFallbacks(file: Path, tags: AudioTags): AudioTags {
        if (tags.title != null && tags.trackNo != null) return tags
        val name = file.nameWithoutExtension.replace('_', ' ').trim()
        val match = leadingNumber.matchEntire(name)
        return tags.copy(
            title = tags.title ?: match?.groupValues?.get(2)?.trim() ?: name.ifEmpty { file.fileName.toString() },
            trackNo = tags.trackNo ?: match?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 },
        )
    }

    /** "3/12" -> (3, 12); "3" -> (3, null). */
    private fun splitNumberPair(value: String?): Pair<Int?, Int?> {
        if (value == null) return null to null
        val parts = value.split('/')
        return parts[0].trim().toIntOrNull()?.takeIf { it > 0 } to parts.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }

    private val yearPattern = Regex("(1[5-9]\\d\\d|2\\d\\d\\d)")

    private fun parseYear(value: String?): Int? = value?.let { yearPattern.find(it)?.value?.toIntOrNull() }

    private fun cleanGenre(value: String): String? = MusicText.clean(value)
}
