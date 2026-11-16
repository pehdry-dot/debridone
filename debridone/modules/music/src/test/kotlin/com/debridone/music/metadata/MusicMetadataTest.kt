package com.debridone.music.metadata

import com.debridone.music.MusicServices
import com.debridone.music.MusicTestEnvironment
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_A
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_B
import com.debridone.music.library.Track
import com.debridone.music.library.TrackField
import com.debridone.music.sources.StandInServers
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

/** Online metadata against a local stand-in that answers in the providers' real formats. */
class MusicMetadataTest {
    private val env = MusicTestEnvironment()
    private val servers = StandInServers()

    @AfterTest
    fun tearDown() {
        env.close()
        servers.close()
    }

    private fun open(): MusicServices = env.open(metadataEndpoints = servers.metadataEndpoints).also { music ->
        runBlocking { music.scanner.scanFolder(PROFILE_A, music.library.addFolder(PROFILE_A, env.musicFolder.toString()).id) }
    }

    private fun MusicServices.byTitle(title: String, profileId: Long = PROFILE_A): Track = library.tracks(profileId).first { it.title == title }

    @Test
    fun musicBrainzCandidatesAreRankedAndMapped() {
        val music = open()
        val track = music.byTitle("Second Light")
        val matches = music.metadata.findRecordings(PROFILE_A, track.id)
        assertEquals(3, matches.size)
        val best = matches.first()
        assertEquals("rec-studio", best.recordingId)
        assertEquals("rel-album", best.releaseId, "the release that matches the album already on the file comes first")
        assertEquals("Second Light", best.title)
        assertEquals("Test Artist feat. A Guest", best.artist)
        assertEquals("First Album", best.album)
        assertEquals("Test Artist", best.albumArtist)
        assertEquals(2021, best.year)
        assertEquals(2, best.trackNo)
        assertEquals(7, best.trackTotal)
        assertEquals(100, best.score)
        assertEquals("rel-live", matches.last().releaseId)
        assertTrue(servers.metadataRequests.single().contains("+recording:\"Second Light\" +artist:\"Test Artist\" release:\"First Album\""), servers.metadataRequests.single())
        assertTrue(music.metadata.findRecordings(PROFILE_A, music.byTitle("Elsewhere").id).isEmpty())
    }

    @Test
    fun applyingAMatchRespectsLocksAndKeepsExistingCovers() {
        val music = open()
        val track = music.byTitle("Second Light")
        music.library.editTrack(PROFILE_A, track.id, mapOf(TrackField.ARTIST to "My Spelling"))
        val compilation = music.metadata.findRecordings(PROFILE_A, track.id).first { it.releaseId == "rel-comp" }

        val applied = music.metadata.apply(PROFILE_A, track.id, compilation)
        assertEquals(setOf(TrackField.ALBUM, TrackField.ALBUM_ARTIST, TrackField.YEAR, TrackField.TRACK_NO, TrackField.TRACK_TOTAL), applied)
        val after = music.library.track(PROFILE_A, track.id)!!
        assertEquals("My Spelling", after.artist, "a locked field is never overwritten by a provider")
        assertEquals("Best Of", after.album)
        assertEquals("Various Artists", after.albumArtist)
        assertEquals(2024, after.year)
        assertEquals(5, after.trackNo)
        assertEquals(track.artwork!!.id, after.artwork!!.id, "a song that has a cover keeps it")
        assertTrue(servers.metadataRequests.none { it.contains("/caa/") }, "no cover is fetched for a song that has one")
        // What the file says is still on record, and one click away.
        music.library.restoreOriginal(PROFILE_A, track.id)
        assertEquals("First Album", music.library.track(PROFILE_A, track.id)!!.album)
    }

    @Test
    fun aSongWithoutACoverGetsOneFromTheCoverArtArchive() {
        val music = open()
        val wav = music.byTitle("Fourth Wall")
        // Make it a cover-less single so nothing is inherited from the album.
        music.library.editTrack(PROFILE_A, wav.id, mapOf(TrackField.ALBUM to null))
        music.library.setFieldLocked(PROFILE_A, wav.id, TrackField.ALBUM, false)
        assertNull(music.library.track(PROFILE_A, wav.id)!!.artwork)

        val match = RecordingMatch("rec-studio", "Fourth Wall", "Test Artist", "rel-album", "Found Album", "Test Artist", 2021, 4, 7, 2000, 100)
        val applied = music.metadata.apply(PROFILE_A, wav.id, match)
        assertTrue(TrackField.ARTWORK in applied && TrackField.ALBUM in applied, "applied: $applied")
        val after = music.library.track(PROFILE_A, wav.id)!!
        assertTrue(Files.isRegularFile(Path.of(assertNotNull(after.artwork).filePath)))
        assertNotNull(music.library.album(PROFILE_A, after.albumId!!)!!.artwork, "the new album shows the fetched cover")

        // A release the archive has no cover for is not an error.
        assertNull(music.metadata.fetchCover(PROFILE_A, "rel-without-cover"))
    }

    @Test
    fun answersAreCachedAndMusicBrainzIsAskedAtMostOnceASecond() {
        val music = open()
        val first = music.byTitle("Second Light")
        music.metadata.findRecordings(PROFILE_A, first.id)
        music.metadata.findRecordings(PROFILE_A, first.id)
        assertEquals(1, servers.metadataRequests.size, "the same question is answered from the cache")

        music.metadata.findRecordings(PROFILE_A, music.byTitle("Opening").id)
        music.metadata.findRecordings(PROFILE_A, music.byTitle("Elsewhere").id)
        assertEquals(3, servers.metadataRequests.size)
        val gaps = servers.metadataRequestTimes.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it >= 1_000 }, "requests were only $gaps ms apart")
    }

    @Test
    fun aProviderThatIsSwitchedOffIsNotContacted() {
        val music = open()
        val track = music.byTitle("Second Light")
        runBlocking {
            env.currentModule.metadataProviders.setEnabled(MusicMetadataService.MUSICBRAINZ, false)
            env.currentModule.metadataProviders.setEnabled(MusicMetadataService.LRCLIB, false)
            env.currentModule.metadataProviders.setEnabled(MusicMetadataService.COVER_ART_ARCHIVE, false)
        }
        assertFailsWith<MetadataException> { music.metadata.findRecordings(PROFILE_A, track.id) }
        assertFailsWith<MetadataException> { music.metadata.fetchCover(PROFILE_A, "rel-album") }
        assertNull(music.metadata.lyrics(PROFILE_A, music.byTitle("Opening").id))
        assertTrue(servers.metadataRequests.isEmpty(), "requests made: ${servers.metadataRequests}")
    }

    @Test
    fun lyricsFromLrclibAreParsedStoredAndEditable() {
        val music = open()
        val opening = music.byTitle("Opening")
        val lyrics = assertNotNull(music.metadata.lyrics(PROFILE_A, opening.id))
        assertEquals(MusicMetadataService.ORIGIN_LRCLIB, lyrics.origin)
        assertEquals("First line\nSecond line", lyrics.plain)
        assertEquals(listOf(LyricLine(200, "First line"), LyricLine(1_100, "Second line")), lyrics.synced)
        val request = servers.metadataRequests.single()
        assertTrue(request.contains("artist_name=Test Artist") && request.contains("track_name=Opening") && request.contains("duration=2"), request)

        assertEquals(lyrics, music.metadata.lyrics(PROFILE_A, opening.id))
        assertEquals(1, servers.metadataRequests.size, "stored lyrics are not fetched again")
        assertNull(music.metadata.lyrics(PROFILE_B, opening.id), "another profile cannot read them")

        val edited = assertNotNull(music.metadata.saveLyrics(PROFILE_A, opening.id, "[00:05.00]My own words\n[00:07.5]and more"))
        assertEquals(MusicMetadataService.ORIGIN_MANUAL, edited.origin)
        assertEquals(listOf(LyricLine(5_000, "My own words"), LyricLine(7_500, "and more")), edited.synced)
        assertEquals(edited, music.metadata.lyrics(PROFILE_A, opening.id))

        assertNull(music.metadata.saveLyrics(PROFILE_A, opening.id, "   "))
        assertEquals(MusicMetadataService.ORIGIN_LRCLIB, music.metadata.lyrics(PROFILE_A, opening.id)!!.origin, "cleared lyrics are looked up again (from the cache)")
        assertEquals(1, servers.metadataRequests.size)
    }

    @Test
    fun lyricsFallBackToSearchAndKnowInstrumentals() {
        val music = open()
        val searched = assertNotNull(music.metadata.lyrics(PROFILE_A, music.byTitle("Third Wave").id))
        assertEquals("The right version", searched.plain, "the entry whose length matches the file is used")
        assertTrue(searched.synced.isEmpty())

        val instrumental = assertNotNull(music.metadata.lyrics(PROFILE_A, music.byTitle("Fifth Element").id))
        assertTrue(instrumental.instrumental)
        assertNull(instrumental.plain)
        assertTrue(music.metadata.lyrics(PROFILE_A, music.byTitle("Fifth Element").id)!!.instrumental)

        val none = music.byTitle("Sixth Sense")
        assertNull(music.metadata.lyrics(PROFILE_A, none.id))
        val requests = servers.metadataRequests.size
        assertNull(music.metadata.lyrics(PROFILE_A, none.id))
        assertEquals(requests, servers.metadataRequests.size, "a miss is remembered instead of asked again")
        assertNull(music.metadata.lyrics(PROFILE_A, music.byTitle("Untagged Tone").id), "no artist, nothing to look up")
    }

    @Test
    fun lyricsEmbeddedInTheFileComeFirst() {
        val music = env.open(metadataEndpoints = servers.metadataEndpoints)
        val file = env.firstAlbum.resolve("01 Opening.mp3")
        AudioFileIO.read(file.toFile()).apply {
            tag.setField(FieldKey.LYRICS, "Words that live in the file")
            commit()
        }
        runBlocking { music.scanner.scanFolder(PROFILE_A, music.library.addFolder(PROFILE_A, env.musicFolder.toString()).id) }
        val lyrics = assertNotNull(music.metadata.lyrics(PROFILE_A, music.byTitle("Opening").id))
        assertEquals(MusicMetadataService.ORIGIN_EMBEDDED, lyrics.origin)
        assertEquals("Words that live in the file", lyrics.plain)
        assertTrue(servers.metadataRequests.isEmpty(), "embedded lyrics need no lookup")
    }

    @Test
    fun anUnreachableServiceIsReportedPlainly() {
        val unused = ServerSocket(0).use { it.localPort }
        val music = env.open(metadataEndpoints = MetadataEndpoints("http://127.0.0.1:$unused/ws/2", "http://127.0.0.1:$unused", "http://127.0.0.1:$unused/api"))
        runBlocking { music.scanner.scanFolder(PROFILE_A, music.library.addFolder(PROFILE_A, env.musicFolder.toString()).id) }
        val failure = assertFailsWith<MetadataException> { music.metadata.findRecordings(PROFILE_A, music.byTitle("Second Light").id) }
        assertTrue(failure.message!!.startsWith("The service could not be reached"), failure.message)
        assertFailsWith<MetadataException> { music.metadata.lyrics(PROFILE_A, music.byTitle("Opening").id) }
        assertFalse(music.library.track(PROFILE_A, music.byTitle("Second Light").id)!!.title.isEmpty())
    }

    @Test
    fun lrcParsing() {
        val lines = MusicMetadataService.parseLrc("[ar: Someone]\n[00:12.00]One\n[01:02.5][00:01]Two\nno tag here\n[00:03.123] Three ")
        assertEquals(listOf(LyricLine(1_000, "Two"), LyricLine(3_123, "Three"), LyricLine(12_000, "One"), LyricLine(62_500, "Two")), lines)
        assertTrue(MusicMetadataService.parseLrc("Just words\non two lines").isEmpty())
        assertEquals("[01:02.50]Two", MusicMetadataService.formatLrcLine(LyricLine(62_500, "Two")))
    }
}
