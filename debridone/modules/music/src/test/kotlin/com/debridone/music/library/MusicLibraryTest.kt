package com.debridone.music.library

import com.debridone.music.Fixtures
import com.debridone.music.MusicServices
import com.debridone.music.MusicTestEnvironment
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_A
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_B
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

/** Scanning real files into a real `music.db`, and everything the library does with them. */
class MusicLibraryTest {
    private val env = MusicTestEnvironment()

    @AfterTest
    fun tearDown() = env.close()

    private fun MusicServices.addAndScan(profileId: Long = PROFILE_A, folder: Path = env.musicFolder): ScanResult = runBlocking {
        val added = library.addFolder(profileId, folder.toString())
        scanner.scanFolder(profileId, added.id)
    }

    private fun MusicServices.rescan(profileId: Long = PROFILE_A): ScanResult = runBlocking { scanner.scanAll(profileId) }

    private fun MusicLibraryRepository.byTitle(title: String, profileId: Long = PROFILE_A): Track =
        tracks(profileId).firstOrNull { it.title == title } ?: error("no track titled $title among ${tracks(profileId).map { it.title }}")

    @Test
    fun scanImportsTagsArtworkAlbumsArtistsAndGenres() {
        val music = env.open()
        val result = music.addAndScan()
        assertEquals(Fixtures.TOTAL_TRACKS, result.added, "scan result: $result")
        assertEquals(0, result.unreadable)

        val library = music.library
        assertEquals(Fixtures.TOTAL_TRACKS, library.trackCount(PROFILE_A))
        assertTrue(library.tracks(PROFILE_A).none { it.path!!.endsWith("notes.txt") })

        val albums = library.albums(PROFILE_A)
        assertEquals(listOf("First Album", "Second Album", "Singles"), albums.map { it.title })
        val first = albums[0]
        assertEquals("Test Artist", first.artist)
        assertEquals(7, first.trackCount)
        assertEquals(2021, first.year)
        assertTrue(first.durationMs in 13_500..14_700, "album duration ${first.durationMs}")

        val tracks = library.albumTracks(PROFILE_A, first.id)
        assertEquals(listOf("Opening", "Second Light", "Third Wave", "Fourth Wall", "Fifth Element", "Sixth Sense", "Seventh Heaven"), tracks.map { it.title })
        assertEquals(listOf("MP3", "FLAC", "AAC", "ALAC", "WAV", "OGG Vorbis", "Opus"), tracks.map { it.format })
        assertTrue(tracks.all { it.artist == "Test Artist" && it.genre == "Electronic" && it.year == 2021 })

        // Embedded cover: stored once, shared by the album and its tracks, readable from disk.
        val cover = assertNotNull(first.artwork, "the album should have its embedded cover")
        assertTrue(Files.size(Path.of(cover.filePath)) > 500)
        assertTrue(Files.size(Path.of(cover.thumbPath)) > 300)
        assertNotNull(cover.dominantColor)
        assertTrue(tracks.all { it.artwork?.id == cover.id }, "tracks without a cover of their own show the album's")

        // A cover.jpg next to the files is used when nothing is embedded.
        val second = albums[1]
        assertEquals("Other Artist", second.artist)
        assertNotNull(second.artwork, "folder image should be picked up")
        assertNotEquals(cover.id, second.artwork!!.id)

        assertEquals(listOf("João Só", "Other Artist", "Test Artist"), library.artists(PROFILE_A).map { it.name })
        val testArtist = library.artists(PROFILE_A).last()
        assertEquals(1, testArtist.albumCount)
        assertEquals(7, testArtist.trackCount)
        assertEquals(cover.id, testArtist.artwork?.id)

        assertEquals(mapOf("Electronic" to 7, "Fado" to 1, "Jazz" to 1), library.genres(PROFILE_A).associate { it.name to it.trackCount })

        val untagged = library.byTitle("Untagged Tone")
        assertNull(untagged.artist)
        assertNull(untagged.albumId)
        assertEquals(12, untagged.trackNo)

        val stats = library.stats(PROFILE_A)
        assertEquals(Fixtures.TOTAL_TRACKS, stats.tracks)
        assertEquals(3, stats.albums)
        assertEquals(3, stats.artists)
        assertEquals(0, stats.missing)
    }

    @Test
    fun aSecondScanOpensNothingAgain() {
        val music = env.open()
        music.addAndScan()
        val again = music.rescan()
        assertEquals(ScanResult(unchanged = Fixtures.TOTAL_TRACKS), again)
        assertEquals(Fixtures.TOTAL_TRACKS, music.library.trackCount(PROFILE_A))
        assertNotNull(music.library.folders(PROFILE_A).single().lastScanAt)
    }

    @Test
    fun theLibrarySurvivesARestart() {
        var music = env.open()
        music.addAndScan()
        val favourite = music.library.byTitle("Third Wave")
        music.library.setTrackFavorite(PROFILE_A, favourite.id, true)
        val before = music.library.tracks(PROFILE_A, sort = TrackSort.ALBUM)

        music = env.open()
        val after = music.library.tracks(PROFILE_A, sort = TrackSort.ALBUM)
        assertEquals(before.map { it.copy(artwork = null) }, after.map { it.copy(artwork = null) })
        assertEquals(before.map { it.artwork?.filePath }, after.map { it.artwork?.filePath })
        assertTrue(after.first { it.id == favourite.id }.favorite)
        assertTrue(Files.isRegularFile(Path.of(after.first { it.artwork != null }.artwork!!.thumbPath)))
        assertEquals(listOf(env.musicFolder.toString()), music.library.folders(PROFILE_A).map { it.path })
        assertEquals(3, music.library.albums(PROFILE_A).size)
    }

    @Test
    fun aMovedOrRenamedFileKeepsItsTrack() {
        val music = env.open()
        music.addAndScan()
        val track = music.library.byTitle("Second Light")
        music.library.setTrackFavorite(PROFILE_A, track.id, true)
        music.library.editTrack(PROFILE_A, track.id, mapOf(TrackField.GENRE to "Ambient"))

        val newHome = Files.createDirectories(env.musicFolder.resolve("Reorganised/Deep"))
        val moved = Files.move(Path.of(track.path!!), newHome.resolve("renamed track.flac"))

        val result = music.rescan()
        assertEquals(1, result.relinked, "scan result: $result")
        assertEquals(0, result.added)
        assertEquals(0, result.missing)
        val after = assertNotNull(music.library.track(PROFILE_A, track.id))
        assertEquals(moved.toString(), after.path)
        assertFalse(after.missing)
        assertTrue(after.favorite)
        assertEquals("Ambient", after.genre)
        assertEquals(Fixtures.TOTAL_TRACKS, music.library.trackCount(PROFILE_A))
    }

    @Test
    fun aDeletedFileIsMarkedMissingAndComesBack() {
        val music = env.open()
        music.addAndScan()
        val track = music.library.byTitle("Sixth Sense")
        val file = Path.of(track.path!!)
        val backup = env.root.resolve("backup.ogg")
        Files.copy(file, backup)
        Files.delete(file)

        val result = music.rescan()
        assertEquals(1, result.missing, "scan result: $result")
        assertTrue(music.library.track(PROFILE_A, track.id)!!.missing)
        assertEquals(1, music.library.stats(PROFILE_A).missing)
        assertEquals(listOf(track.id), music.library.tracks(PROFILE_A, TrackFilter(missingOnly = true)).map { it.id })
        assertEquals(7, music.library.album(PROFILE_A, track.albumId!!)!!.trackCount, "a missing track stays in its album")

        Files.copy(backup, file)
        music.rescan()
        assertFalse(music.library.track(PROFILE_A, track.id)!!.missing)
    }

    @Test
    fun anUnreachableFolderKeepsItsTracksAsMissing() {
        val music = env.open()
        music.addAndScan()
        env.musicFolder.toFile().deleteRecursively()
        val result = music.rescan()
        assertEquals(Fixtures.TOTAL_TRACKS, result.missing)
        assertEquals(Fixtures.TOTAL_TRACKS, music.library.trackCount(PROFILE_A))
        assertFalse(music.library.folders(PROFILE_A).single().available)
        assertEquals(Fixtures.TOTAL_TRACKS, music.library.removeMissing(PROFILE_A))
        assertEquals(0, music.library.trackCount(PROFILE_A))
        assertTrue(music.library.albums(PROFILE_A).isEmpty())
        assertTrue(music.library.artists(PROFILE_A).isEmpty())
    }

    @Test
    fun changedTagsAreReadAgainButLockedFieldsAreKept() {
        val music = env.open()
        music.addAndScan()
        val track = music.library.byTitle("Second Light")
        music.library.editTrack(PROFILE_A, track.id, mapOf(TrackField.ARTIST to "My Own Artist Name"))

        // Another program rewrites the file's tags.
        val file = Path.of(track.path!!)
        AudioFileIO.read(file.toFile()).apply {
            tag.setField(FieldKey.TITLE, "Retitled Elsewhere")
            tag.setField(FieldKey.ARTIST, "Retagged Artist")
            commit()
        }
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000))

        val result = music.rescan()
        assertEquals(1, result.updated, "scan result: $result")
        val after = music.library.track(PROFILE_A, track.id)!!
        assertEquals("Retitled Elsewhere", after.title, "an unlocked field follows the file")
        assertEquals("My Own Artist Name", after.artist, "a field edited by hand is not overwritten")
        assertEquals(setOf(TrackField.ARTIST), after.lockedFields)
        assertEquals("Retagged Artist", music.library.originalFields(PROFILE_A, track.id)!![TrackField.ARTIST])
    }

    @Test
    fun manualEditsLocksAndRestore() {
        val music = env.open()
        music.addAndScan()
        val library = music.library
        val track = library.byTitle("Opening")
        val originalAlbum = track.albumId!!

        library.editTrack(PROFILE_A, track.id, mapOf(TrackField.TITLE to "  Overture  ", TrackField.ALBUM to "Special Edition", TrackField.YEAR to "1987"))
        val edited = library.track(PROFILE_A, track.id)!!
        assertEquals("Overture", edited.title)
        assertEquals("Special Edition", edited.album)
        assertEquals(1987, edited.year)
        assertEquals(setOf(TrackField.TITLE, TrackField.ALBUM, TrackField.YEAR), edited.lockedFields)
        assertNotEquals(originalAlbum, edited.albumId)
        assertEquals(6, library.album(PROFILE_A, originalAlbum)!!.trackCount)
        assertEquals(1, library.album(PROFILE_A, edited.albumId!!)!!.trackCount)
        assertEquals(listOf(edited.id), library.tracks(PROFILE_A, TrackFilter(text = "overture special")).map { it.id })

        // A metadata provider may fill unlocked fields only.
        val applied = library.applyProviderMetadata(PROFILE_A, track.id, mapOf(TrackField.TITLE to "Provider Title", TrackField.COMPOSER to "Provider Composer"))
        assertEquals(setOf(TrackField.COMPOSER), applied)
        assertEquals("Overture", library.track(PROFILE_A, track.id)!!.title)
        assertEquals("Provider Composer", library.track(PROFILE_A, track.id)!!.composer)

        // Unlocking gives the field back to providers and rescans.
        library.setFieldLocked(PROFILE_A, track.id, TrackField.TITLE, false)
        assertEquals(setOf(TrackField.TITLE), library.applyProviderMetadata(PROFILE_A, track.id, mapOf(TrackField.TITLE to "Provider Title")))

        library.restoreOriginal(PROFILE_A, track.id)
        val restored = library.track(PROFILE_A, track.id)!!
        assertEquals("Opening", restored.title)
        assertEquals("First Album", restored.album)
        assertEquals(2021, restored.year)
        assertEquals("Test Composer", restored.composer)
        assertTrue(restored.lockedFields.isEmpty())
        assertEquals(originalAlbum, restored.albumId)
        assertEquals(listOf("First Album", "Second Album", "Singles"), library.albums(PROFILE_A).map { it.title }, "the emptied album is removed")
    }

    @Test
    fun customArtworkSurvivesARescanUntilRestored() {
        val music = env.open()
        music.addAndScan()
        val library = music.library
        val track = library.byTitle("Third Wave")
        val embedded = track.artwork!!.id
        val custom = assertNotNull(library.artwork.storeFile(PROFILE_A, env.musicFolder.resolve("Other Artist/Second Album/cover.jpg"), ArtworkStore.ORIGIN_CUSTOM))
        library.setTrackArtwork(PROFILE_A, track.id, custom)
        assertEquals(custom, library.track(PROFILE_A, track.id)!!.artwork?.id)

        val file = Path.of(track.path!!)
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        assertEquals(1, music.rescan().updated)
        assertEquals(custom, library.track(PROFILE_A, track.id)!!.artwork?.id, "a chosen picture is not replaced by the embedded one")

        library.restoreOriginal(PROFILE_A, track.id)
        assertEquals(embedded, library.track(PROFILE_A, track.id)!!.artwork?.id)
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        val music = env.open()
        music.addAndScan()
        val library = music.library
        assertEquals(listOf("Canção Nona"), library.search(PROFILE_A, "cancao").tracks.map { it.title })
        assertEquals(listOf("Canção Nona"), library.search(PROFILE_A, "JOAO so").tracks.map { it.title })
        assertEquals(listOf("João Só"), library.search(PROFILE_A, "joão").artists.map { it.name })
        assertEquals(listOf("Opening"), library.search(PROFILE_A, "first opening").tracks.map { it.title })
        assertEquals(listOf("Second Album"), library.search(PROFILE_A, "other second").albums.map { it.title })
        assertEquals(7, library.search(PROFILE_A, "electronic").tracks.size)
        assertTrue(library.search(PROFILE_A, "100% _nothing_").isEmpty)
        assertTrue(library.search(PROFILE_A, "   ").isEmpty)
    }

    @Test
    fun sortingAndFiltering() {
        val music = env.open()
        music.addAndScan()
        val library = music.library
        val byTitle = library.tracks(PROFILE_A, sort = TrackSort.TITLE).map { it.title }
        assertEquals(byTitle.sortedBy { MusicText.sortKey(it) }, byTitle)
        assertEquals(byTitle.reversed(), library.tracks(PROFILE_A, sort = TrackSort.TITLE, descending = true).map { it.title })
        assertEquals("Elsewhere", library.tracks(PROFILE_A, TrackFilter(genre = "jazz")).single().title)
        assertEquals(2, library.tracks(PROFILE_A, TrackFilter(format = "MP3")).size)
        assertEquals(listOf("Canção Nona"), library.tracks(PROFILE_A, sort = TrackSort.ARTIST, limit = 1).map { it.title })
        assertEquals(3, library.tracks(PROFILE_A, limit = 3, offset = 2).size)
        assertEquals(byTitle.drop(2).take(3), library.tracks(PROFILE_A, limit = 3, offset = 2).map { it.title })

        val album = library.albums(PROFILE_A).first()
        library.setAlbumFavorite(PROFILE_A, album.id, true)
        assertEquals(listOf(album.id), library.albums(PROFILE_A, favoritesOnly = true).map { it.id })
        assertEquals(listOf("Second Album"), library.albums(PROFILE_A, genre = "Jazz").map { it.title })
        assertEquals(listOf("First Album"), library.albums(PROFILE_A, sort = AlbumSort.YEAR, limit = 1).map { it.title })
        val artist = library.artists(PROFILE_A).first { it.name == "Test Artist" }
        assertEquals(listOf("First Album"), library.albums(PROFILE_A, artistId = artist.id).map { it.title })
        assertEquals(7, library.artistTracks(PROFILE_A, artist.id).size)
        library.setArtistFavorite(PROFILE_A, artist.id, true)
        assertEquals(listOf("Test Artist"), library.artists(PROFILE_A, favoritesOnly = true).map { it.name })
    }

    @Test
    fun favouritesAndListeningHistory() {
        val music = env.open()
        music.addAndScan()
        val library = music.library
        val opening = library.byTitle("Opening")
        val elsewhere = library.byTitle("Elsewhere")

        library.setTrackFavorite(PROFILE_A, opening.id, true)
        assertEquals(listOf(opening.id), library.tracks(PROFILE_A, TrackFilter(favoritesOnly = true)).map { it.id })
        assertEquals(1, library.stats(PROFILE_A).favorites)
        library.setTrackFavorite(PROFILE_A, opening.id, false)
        assertTrue(library.tracks(PROFILE_A, TrackFilter(favoritesOnly = true)).isEmpty())

        library.recordPlay(PROFILE_A, opening.id, listenedMs = 2_000, completed = true)
        library.recordPlay(PROFILE_A, elsewhere.id, listenedMs = 300, completed = false)
        library.recordPlay(PROFILE_A, opening.id, listenedMs = 1_200, completed = false)
        assertEquals(2, library.track(PROFILE_A, opening.id)!!.playCount, "a full play and a more-than-half play both count")
        assertEquals(0, library.track(PROFILE_A, elsewhere.id)!!.playCount, "a skipped track is not a play")
        assertEquals(setOf(opening.id, elsewhere.id), library.recentlyPlayed(PROFILE_A, 10).map { it.id }.toSet())
        assertEquals(listOf(opening.id), library.mostPlayed(PROFILE_A, 10).map { it.id })
        assertEquals(listOf(opening.id, elsewhere.id, opening.id), library.history(PROFILE_A).map { it.track.id })
        assertEquals(setOf("First Album", "Second Album"), library.recentlyPlayedAlbums(PROFILE_A, 10).map { it.title }.toSet())

        library.clearHistory(PROFILE_A)
        assertTrue(library.history(PROFILE_A).isEmpty())
        assertTrue(library.recentlyPlayed(PROFILE_A, 10).isEmpty())
        assertEquals(0, library.track(PROFILE_A, opening.id)!!.playCount)
    }

    @Test
    fun duplicateFilesAreDetected() {
        val music = env.open()
        music.addAndScan()
        Files.copy(env.firstAlbum.resolve("01 Opening.mp3"), env.musicFolder.resolve("copy of opening.mp3"))
        val result = music.rescan()
        assertEquals(1, result.duplicates, "scan result: $result")
        assertEquals(1, result.added)
        val groups = music.library.duplicateGroups(PROFILE_A)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().size)
        assertEquals(setOf("Opening"), groups.single().map { it.title }.toSet())
    }

    @Test
    fun singleFilesCanBeImportedWithoutAFolder() {
        val music = env.open()
        val outside = Files.createDirectories(env.root.resolve("Downloads"))
        val song = Files.copy(Fixtures.flac, outside.resolve("single.flac"))
        val text = Files.writeString(outside.resolve("readme.txt"), "x")

        val first = runBlocking { music.scanner.importFiles(PROFILE_A, listOf(song, text, outside)) }
        assertEquals(ScanResult(added = 1), first)
        assertTrue(music.library.folders(PROFILE_A).isEmpty())
        val track = music.library.tracks(PROFILE_A).single()
        assertEquals("Second Light", track.title)
        assertEquals(song.toString(), track.path, "the file is referenced where it is, not copied")

        assertEquals(ScanResult(unchanged = 1), runBlocking { music.scanner.importFiles(PROFILE_A, listOf(song)) })

        Files.delete(song)
        assertEquals(1, music.rescan().missing)
        assertTrue(music.library.track(PROFILE_A, track.id)!!.missing)
    }

    @Test
    fun removingAFolderRemovesItsTracksButNotTheFiles() {
        val music = env.open()
        music.addAndScan()
        val folder = music.library.folders(PROFILE_A).single()
        assertEquals(Fixtures.TOTAL_TRACKS, folder.trackCount)
        music.library.removeFolder(PROFILE_A, folder.id)
        assertEquals(0, music.library.trackCount(PROFILE_A))
        assertTrue(music.library.albums(PROFILE_A).isEmpty())
        assertTrue(music.library.folders(PROFILE_A).isEmpty())
        assertTrue(Files.isRegularFile(env.firstAlbum.resolve("01 Opening.mp3")))
    }

    @Test
    fun profilesHaveSeparateLibraries() {
        val music = env.open()
        music.addAndScan(PROFILE_A)
        val library = music.library
        assertEquals(0, library.trackCount(PROFILE_B))
        assertTrue(library.albums(PROFILE_B).isEmpty())
        assertTrue(library.folders(PROFILE_B).isEmpty())
        assertTrue(library.search(PROFILE_B, "opening").isEmpty)

        // The same folder in a second profile is a second, independent set of rows.
        val smaller = env.musicFolder.resolve("Other Artist")
        music.addAndScan(PROFILE_B, smaller)
        assertEquals(1, library.trackCount(PROFILE_B))
        val inA = library.byTitle("Elsewhere", PROFILE_A)
        val inB = library.byTitle("Elsewhere", PROFILE_B)
        assertNotEquals(inA.id, inB.id)

        library.setTrackFavorite(PROFILE_A, inA.id, true)
        library.editTrack(PROFILE_A, inA.id, mapOf(TrackField.TITLE to "Renamed in A"))
        library.recordPlay(PROFILE_A, inA.id, 2_000, true)
        val untouched = library.track(PROFILE_B, inB.id)!!
        assertEquals("Elsewhere", untouched.title)
        assertFalse(untouched.favorite)
        assertEquals(0, untouched.playCount)
        assertNull(library.track(PROFILE_B, inA.id), "one profile cannot read another profile's track")
        library.setTrackFavorite(PROFILE_B, inA.id, false)
        assertTrue(library.track(PROFILE_A, inA.id)!!.favorite, "one profile cannot change another profile's track")

        env.deleteProfile(PROFILE_A)
        assertEquals(0, library.trackCount(PROFILE_A))
        assertTrue(library.folders(PROFILE_A).isEmpty())
        assertTrue(library.albums(PROFILE_A).isEmpty())
        val survivor = library.byTitle("Elsewhere", PROFILE_B)
        assertTrue(Files.isRegularFile(Path.of(survivor.artwork!!.filePath)), "artwork still used by another profile stays on disk")
        assertEquals(1, library.trackCount(PROFILE_B))
    }

    @Test
    fun fingerprintFollowsContentNotName() {
        val copy = Files.copy(Fixtures.flac, env.root.resolve("other name.flac"), StandardCopyOption.REPLACE_EXISTING)
        assertEquals(MusicScanner.fingerprint(Fixtures.flac), MusicScanner.fingerprint(copy))
        assertNotEquals(MusicScanner.fingerprint(Fixtures.flac), MusicScanner.fingerprint(Fixtures.mp3))
    }
}
