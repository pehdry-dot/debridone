package com.debridone.music.metadata

import com.debridone.music.MusicServices
import com.debridone.music.MusicTestEnvironment
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_A
import com.debridone.music.library.Album
import com.debridone.music.library.Track
import com.debridone.music.library.TrackField
import com.debridone.music.sources.StandInServers
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * MusicBrainz artists, albums and track listings, the Cover Art Archive's choice of images,
 * searching LRCLIB by hand, and provider health, against a stand-in answering in the real formats.
 */
class MusicMetadataCatalogTest {
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

    private fun MusicServices.byTitle(title: String): Track = library.tracks(PROFILE_A).first { it.title == title }
    private fun MusicServices.album(title: String): Album = library.albums(PROFILE_A).first { it.title == title }

    // ---------------------------------------------------------------- MusicBrainz

    @Test
    fun artistsAlbumsAndEditionsAreSearched() {
        val music = open()
        val artists = music.metadata.searchArtists("Test Artist")
        assertEquals(listOf("art-1", "art-9"), artists.map { it.id })
        assertEquals("Group", artists.first().type)
        assertEquals("PT", artists.first().country)
        assertEquals("electronic duo", artists.first().disambiguation)
        assertEquals("2015", artists.first().begin)
        assertEquals("Brazil", artists.last().country, "the area stands in when no country code is given")
        assertTrue(music.metadata.searchArtists("Nobody At All").isEmpty())
        assertTrue(music.metadata.searchArtists("  ").isEmpty())

        val group = music.metadata.searchReleaseGroups("First Album", "Test Artist").single()
        assertEquals("rg-first", group.id)
        assertEquals("Album", group.primaryType)
        assertEquals(listOf("Live"), group.secondaryTypes)
        assertEquals(2021, group.year)
        assertEquals("art-1", group.artistId)

        val releases = music.metadata.searchReleases("First Album", "Test Artist")
        assertEquals(listOf("rel-deluxe", "rel-album", "rel-other"), releases.map { it.id })
        assertEquals("Own Label", releases[1].label)
        assertEquals("Digital Media", releases[1].format)
        assertEquals(7, releases[1].trackCount)
        assertEquals("rg-first", releases[1].releaseGroupId)

        val discography = music.metadata.artistReleaseGroups("art-1")
        assertEquals(listOf("First Album", "Second Thoughts"), discography.map { it.title }, "oldest first")
        assertTrue(servers.metadataRequests.all { it.startsWith("/ws/2/") })
    }

    @Test
    fun anEditionComesWithItsTrackListingAndAnAlbumWithItsEditions() {
        val music = open()
        val detail = assertNotNull(music.metadata.release("rel-album"))
        assertEquals("First Album (Remastered)", detail.release.title)
        assertEquals("2021-03-12", detail.release.date)
        assertEquals(7, detail.tracks.size)
        assertEquals(1, detail.discCount)
        val second = detail.tracks[1]
        assertEquals(2, second.position)
        assertEquals("Second Light", second.title)
        assertEquals("Test Artist feat. A Guest", second.artist)
        assertEquals(2_000L, second.lengthMs)
        assertEquals("rec-studio", second.recordingId)
        assertEquals("rg-first", detail.releaseGroup?.id)
        assertEquals(2020, detail.releaseGroup?.year, "the album's first release date, not this edition's")
        assertNull(music.metadata.release("rel-nope"), "an unknown id is simply not found")

        val group = assertNotNull(music.metadata.releaseGroup("rg-first"))
        assertEquals(listOf("rel-album", "rel-deluxe"), group.releases.map { it.id }, "editions oldest first")
        assertEquals("Test Artist", group.releases.first().artist)
    }

    @Test
    fun anAlbumIsMatchedToTheEditionWithTheSameNumberOfSongs() {
        val music = open()
        val candidates = music.metadata.findReleasesForAlbum(PROFILE_A, music.album("First Album").id)
        assertEquals("rel-album", candidates.first().id, "seven songs here, seven on that edition")
        assertEquals("rel-other", candidates.last().id)
    }

    @Test
    fun applyingAnEditionCorrectsTheSongsItCanPairAndRespectsLocks() {
        val music = open()
        val album = music.album("First Album")
        val locked = music.byTitle("Third Wave")
        music.library.editTrack(PROFILE_A, locked.id, mapOf(TrackField.TITLE to "Third Wave (my title)"))
        val before = music.library.albumTracks(PROFILE_A, album.id).map { it.id }

        val update = music.metadata.applyRelease(PROFILE_A, album.id, "rel-album")
        assertEquals(6, update.tracksMatched, "six of the seven songs are on that edition")
        assertEquals(1, update.tracksUnmatched)
        assertTrue(update.fieldsChanged > 0)

        val after = music.library.tracksByIds(PROFILE_A, before).associateBy { it.id }
        val opening = after.values.first { it.trackNo == 1 }
        assertEquals("Opening Song", opening.title, "the title was corrected")
        assertEquals("First Album (Remastered)", opening.album)
        assertEquals(2020, opening.year, "the year the album first came out")
        assertEquals(7, opening.trackTotal)
        assertEquals("Test Artist feat. A Guest", after.values.first { it.title == "Second Light" }.artist)
        assertEquals("Third Wave (my title)", after.getValue(locked.id).title, "a field the user edited is left alone")
        assertEquals("Seventh Heaven", after.values.first { it.trackNo == 7 }.title, "a song that pairs with nothing is untouched")
        assertEquals("First Album", after.values.first { it.title == "Seventh Heaven" }.album)

        // The songs moved to the album under its corrected title; the identifiers went with them.
        val newAlbum = music.album("First Album (Remastered)")
        assertEquals(update.albumId, newAlbum.id)
        assertEquals("rel-album", music.library.albumIdentifiers(PROFILE_A, newAlbum.id)?.releaseId)
        assertEquals("rg-first", music.library.albumIdentifiers(PROFILE_A, newAlbum.id)?.releaseGroupId)
        val ids = assertNotNull(music.library.trackIdentifiers(PROFILE_A, opening.id))
        assertEquals("rec-1", ids.recordingId)
        assertEquals("rel-album", ids.releaseId)

        // Refreshing asks again for that edition and finds nothing more to change.
        val refreshed = music.metadata.refreshAlbum(PROFILE_A, newAlbum.id)
        assertEquals(MetadataRefresh.Updated(emptySet()), refreshed)
        assertEquals(MetadataRefresh.NeedsMatch, music.metadata.refreshAlbum(PROFILE_A, music.album("Second Album").id))
    }

    @Test
    fun aSongIsRefreshedFromTheRecordingItWasMatchedWith() {
        val music = open()
        val track = music.byTitle("Second Light")
        assertEquals(MetadataRefresh.NeedsMatch, music.metadata.refreshTrack(PROFILE_A, track.id), "nothing identifies it yet")

        val match = music.metadata.findRecordings(PROFILE_A, track.id).first()
        music.metadata.apply(PROFILE_A, track.id, match, fetchCover = false)
        assertEquals("rec-studio", music.library.trackIdentifiers(PROFILE_A, track.id)?.recordingId)

        val refresh = music.metadata.refreshTrack(PROFILE_A, track.id)
        assertTrue(refresh is MetadataRefresh.Updated && TrackField.TITLE in refresh.changed, "got $refresh")
        val now = music.library.track(PROFILE_A, track.id)!!
        assertEquals("Second Light (2024 mix)", now.title, "MusicBrainz's current title")
        assertEquals("First Album", now.album, "it stays on the edition it was matched with")
        assertEquals(2, now.trackNo)
    }

    // ---------------------------------------------------------------- Cover Art Archive

    @Test
    fun theArchivesImagesAreListedFrontFirst() {
        val music = open()
        val options = music.metadata.coverOptions(releaseId = "rel-album")
        assertEquals(listOf("110", "111", "112"), options.map { it.id }, "front, back, then the rest")
        assertTrue(options[0].front && !options[0].back)
        assertTrue(options[1].back)
        assertEquals("Front · digital booklet", options[0].label)
        assertEquals("Back", options[1].label)
        assertEquals("Booklet · page 2", options[2].label)
        assertTrue(options[0].thumbnailUrl.endsWith("/front-250.jpg"))
        assertTrue(options.all { it.releaseId == "rel-album" })
        assertEquals(3, music.metadata.coverOptions(releaseGroupId = "rg-first").size, "an album can be asked for as a whole")
        assertTrue(music.metadata.coverOptions(releaseId = "rel-deluxe").isEmpty(), "many editions have no artwork; that is not an error")
        assertTrue(music.metadata.coverOptions().isEmpty())
    }

    @Test
    fun aCoverIsChosenByHandAndKept() {
        val music = open()
        val album = music.album("First Album")
        val originalCover = music.library.album(PROFILE_A, album.id)!!.artwork

        val options = music.metadata.albumCoverOptions(PROFILE_A, album.id)
        assertEquals(3, options.size, "found through MusicBrainz, as the album has no identifier yet")
        val preview = assertNotNull(music.metadata.coverPreview(options.first { it.back }))
        assertTrue(Files.size(preview) > 0)
        val requests = servers.metadataRequests.size
        assertEquals(preview, music.metadata.coverPreview(options.first { it.back }), "previews are kept")
        assertEquals(requests, servers.metadataRequests.size)

        assertTrue(music.metadata.applyAlbumCover(PROFILE_A, album.id, options.first { it.front }))
        val chosen = assertNotNull(music.library.album(PROFILE_A, album.id)!!.artwork)
        assertTrue(chosen.id != originalCover?.id)
        assertEquals(chosen.id, music.byTitle("Opening").artwork?.id, "the album's songs show the chosen cover")

        runBlocking { music.scanner.scanAll(PROFILE_A) }
        assertEquals(chosen.id, music.library.album(PROFILE_A, album.id)!!.artwork?.id, "a rescan does not undo the choice")
        assertFalse(music.metadata.applyAlbumCover(PROFILE_A, album.id, options.last()), "an image the archive no longer has is reported, not applied")
        assertEquals(chosen.id, music.library.album(PROFILE_A, album.id)!!.artwork?.id)

        music.library.resetAlbumArtwork(PROFILE_A, album.id)
        assertEquals(originalCover?.id, music.library.album(PROFILE_A, album.id)!!.artwork?.id)
    }

    // ---------------------------------------------------------------- LRCLIB

    @Test
    fun lyricsAreSearchedByHandAndTheClosestLengthComesFirst() {
        val music = open()
        val track = music.byTitle("Opening")
        val found = music.metadata.searchLyricsFor(PROFILE_A, track.id, query = "opening")
        assertEquals(listOf(41L, 42L, 40L), found.map { it.id }, "the two-second versions before the three-minute cover; timed lyrics first")
        assertTrue(found[0].synced)
        assertTrue(found[1].instrumental)
        assertEquals(201, found[2].durationSeconds)
        assertTrue(music.metadata.searchLyrics("Nothing", "Nobody", query = "zzz").isEmpty())

        val applied = assertNotNull(music.metadata.applyLyrics(PROFILE_A, track.id, found[0]))
        assertEquals(MusicMetadataService.ORIGIN_LRCLIB, applied.origin)
        assertEquals(listOf(200L, 1_100L), applied.synced.map { it.timeMs }, "time stamps come from LRCLIB, never invented")
        assertEquals(applied, music.metadata.lyrics(PROFILE_A, track.id, online = false))

        val instrumental = assertNotNull(music.metadata.applyLyrics(PROFILE_A, track.id, found[1]))
        assertTrue(instrumental.instrumental && instrumental.synced.isEmpty())

        val plain = assertNotNull(music.metadata.applyLyrics(PROFILE_A, track.id, found[2]))
        assertEquals("A cover", plain.plain)
        assertTrue(plain.synced.isEmpty(), "plain lyrics get no time stamps")
    }

    @Test
    fun refreshingLyricsKeepsWhatTheUserTyped() {
        val music = open()
        val track = music.byTitle("Opening")
        assertEquals(MusicMetadataService.ORIGIN_LRCLIB, music.metadata.lyrics(PROFILE_A, track.id)?.origin)
        val requests = servers.metadataRequests.size
        assertEquals(MusicMetadataService.ORIGIN_LRCLIB, music.metadata.refreshLyrics(PROFILE_A, track.id)?.origin)
        assertTrue(servers.metadataRequests.size > requests, "a refresh really asks again")

        music.metadata.saveLyrics(PROFILE_A, track.id, "My own words")
        val kept = assertNotNull(music.metadata.refreshLyrics(PROFILE_A, track.id))
        assertEquals(MusicMetadataService.ORIGIN_MANUAL, kept.origin)
        assertEquals("My own words", kept.plain)
    }

    // ---------------------------------------------------------------- provider health

    @Test
    fun everyProviderCanBeTestedAndItsHealthIsKept() {
        val music = open()
        assertTrue(music.metadata.health.value.isEmpty())
        val results = listOf("embedded-tags", "musicbrainz", "cover-art-archive", "lrclib").associateWith { music.metadata.testProvider(it) }
        assertTrue(results.values.all { it.ok }, results.toString())
        assertTrue(results.getValue("musicbrainz").message.startsWith("Working"))
        assertNotNull(results.getValue("lrclib").latencyMs)
        assertTrue(results.getValue("embedded-tags").message.startsWith("Built in"))
        assertFalse(music.metadata.testProvider("made-up").ok)

        servers.metadataDown = true
        val down = music.metadata.testProvider("musicbrainz")
        assertFalse(down.ok)
        assertTrue(down.message.contains("busy") || down.message.contains("503"), down.message)
        assertFalse(music.metadata.testProvider("cover-art-archive").ok)
        assertEquals(false, music.metadata.health.value["musicbrainz"]?.ok)

        // The last outcome is still shown after a restart.
        val reopened = env.open(metadataEndpoints = servers.metadataEndpoints)
        assertEquals(false, reopened.metadata.health.value["musicbrainz"]?.ok)
        assertEquals(true, reopened.metadata.health.value["lrclib"]?.ok)

        // An ordinary lookup that fails or works updates it too.
        assertFailsWith<MetadataException> { reopened.metadata.searchArtists("Test Artist") }
        assertEquals(false, reopened.metadata.health.value["musicbrainz"]?.ok)
        servers.metadataDown = false
        assertEquals(2, reopened.metadata.searchArtists("Test Artist").size)
        assertEquals(true, reopened.metadata.health.value["musicbrainz"]?.ok)
    }

    @Test
    fun aProviderThatIsSwitchedOffIsNotAsked() {
        val music = open()
        runBlocking { env.currentModule.metadataProviders.setEnabled("musicbrainz", false) }
        runBlocking { env.currentModule.metadataProviders.setEnabled("cover-art-archive", false) }
        runBlocking { env.currentModule.metadataProviders.setEnabled("lrclib", false) }
        servers.metadataRequests.clear()
        assertFailsWith<MetadataException> { music.metadata.searchArtists("Test Artist") }
        assertFailsWith<MetadataException> { music.metadata.release("rel-album") }
        assertFailsWith<MetadataException> { music.metadata.coverOptions(releaseId = "rel-album") }
        assertFailsWith<MetadataException> { music.metadata.searchLyrics("Opening", "Test Artist") }
        assertTrue(servers.metadataRequests.isEmpty(), "nothing was sent: ${servers.metadataRequests}")

        // The setting is remembered.
        val reopened = env.open(metadataEndpoints = servers.metadataEndpoints)
        assertFailsWith<MetadataException> { reopened.metadata.searchArtists("Test Artist") }
        assertTrue(env.currentModule.metadataProviders.providers.value.first { it.id == "embedded-tags" }.enabled)
    }
}
