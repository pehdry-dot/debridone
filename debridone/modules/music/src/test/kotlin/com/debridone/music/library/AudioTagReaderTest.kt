package com.debridone.music.library

import com.debridone.music.Fixtures
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioTagReaderTest {
    @Test
    fun readsTagsOfEveryAlbumFormat() {
        val expected = listOf(
            Triple(Fixtures.mp3, "Opening", "MP3"),
            Triple(Fixtures.flac, "Second Light", "FLAC"),
            Triple(Fixtures.aac, "Third Wave", "AAC"),
            Triple(Fixtures.alac, "Fourth Wall", "ALAC"),
            Triple(Fixtures.wav, "Fifth Element", "WAV"),
            Triple(Fixtures.ogg, "Sixth Sense", "OGG Vorbis"),
            Triple(Fixtures.opus, "Seventh Heaven", "Opus"),
        )
        val problems = mutableListOf<String>()
        expected.forEachIndexed { index, (file, title, format) ->
            val tags = AudioTagReader.read(file)
            fun check(what: String, actual: Any?, wanted: Any?) {
                if (actual != wanted) problems += "${file.fileName}: $what was <$actual>, expected <$wanted>"
            }
            check("readable", tags.readable, true)
            check("title", tags.title, title)
            check("artist", tags.artist, "Test Artist")
            check("album", tags.album, "First Album")
            // WAV's INFO chunk has no album-artist or track-total field; the album still groups by artist.
            val wav = format == "WAV"
            check("album artist", tags.albumArtist, if (wav) null else "Test Artist")
            check("genre", tags.genre, "Electronic")
            check("year", tags.year, 2021)
            check("track number", tags.trackNo, index + 1)
            check("track total", tags.trackTotal, if (wav) null else 7)
            check("format", tags.format, format)
            if (tags.durationMs !in 1_900..2_150) problems += "${file.fileName}: duration ${tags.durationMs} ms"
            if ((tags.sampleRate ?: 0) <= 0) problems += "${file.fileName}: no sample rate"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun readsEmbeddedCoverArt() {
        for (file in listOf(Fixtures.mp3, Fixtures.flac, Fixtures.aac)) {
            val artwork = AudioTagReader.read(file).artwork
            assertNotNull(artwork, "${file.fileName} should carry a cover")
            assertTrue(artwork.bytes.size > 500, "${file.fileName}: cover of ${artwork.bytes.size} bytes")
            assertEquals(0xFF.toByte(), artwork.bytes[0], "${file.fileName}: cover is not a JPEG")
        }
        assertNull(AudioTagReader.read(Fixtures.wav).artwork)
    }

    @Test
    fun readsAccentedTagsFromAiff() {
        val tags = AudioTagReader.read(Fixtures.aiff)
        assertEquals("Canção Nona", tags.title)
        assertEquals("João Só", tags.artist)
        assertEquals("Singles", tags.album)
        assertEquals("Fado", tags.genre)
        assertEquals(2010, tags.year)
        assertEquals("AIFF", tags.format)
        assertTrue(tags.durationMs in 1_900..2_150, "duration ${tags.durationMs}")
    }

    @Test
    fun anUntaggedRawAacFileGetsItsTitleAndNumberFromTheFileName() {
        val tags = AudioTagReader.read(Fixtures.adts)
        assertTrue(tags.readable)
        assertEquals("Untagged Tone", tags.title)
        assertEquals(12, tags.trackNo)
        assertNull(tags.artist)
        assertNull(tags.album)
        assertEquals("AAC", tags.format)
        assertEquals(44_100, tags.sampleRate)
        assertTrue(tags.durationMs in 1_900..2_150, "duration ${tags.durationMs}")
    }

    @Test
    fun aDamagedFileIsStillListedByName() {
        val broken = Files.createTempFile("03 - Broken Song", ".mp3")
        try {
            Files.write(broken, ByteArray(4_096) { (it * 31).toByte() })
            val tags = AudioTagReader.read(broken)
            assertFalse(tags.readable)
            assertTrue(tags.title!!.startsWith("Broken Song"), "title was ${tags.title}")
            assertEquals(0, tags.durationMs)
        } finally {
            Files.deleteIfExists(broken)
        }
    }

    @Test
    fun onlyAudioFilesAreAccepted() {
        assertTrue(AudioTagReader.isSupported(Fixtures.flac))
        assertFalse(AudioTagReader.isSupported(Fixtures.firstAlbum.resolve("notes.txt")))
        assertFalse(AudioTagReader.isSupported(Fixtures.secondAlbum.resolve("cover.jpg")))
    }
}
