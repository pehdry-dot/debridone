package com.debridone.music.playlists

import com.debridone.music.MusicPreferences
import com.debridone.music.MusicServices
import com.debridone.music.MusicTestEnvironment
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_A
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_B
import com.debridone.music.library.ArtworkStore
import com.debridone.music.library.Track
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PlaylistTest {
    private val env = MusicTestEnvironment()

    @AfterTest
    fun tearDown() = env.close()

    private fun MusicServices.scan(profileId: Long = PROFILE_A) = runBlocking {
        scanner.scanFolder(profileId, library.addFolder(profileId, env.musicFolder.toString()).id)
    }

    private fun MusicServices.album(profileId: Long = PROFILE_A): List<Track> =
        library.albumTracks(profileId, library.albums(profileId).first { it.title == "First Album" }.id)

    private fun MusicServices.titles(playlistId: Long, profileId: Long = PROFILE_A) = playlists.tracks(profileId, playlistId).map { it.title }

    @Test
    fun createAddReorderRemoveRenameDelete() {
        val music = env.open()
        music.scan()
        val album = music.album()
        val playlist = music.playlists.create(PROFILE_A, "  Morning   mix ", "For the commute")
        assertEquals("Morning mix", playlist.name)
        assertEquals("For the commute", playlist.description)
        assertEquals(0, playlist.trackCount)

        assertEquals(3, music.playlists.add(PROFILE_A, playlist.id, listOf(album[0].id, album[1].id, album[2].id)))
        assertEquals(1, music.playlists.add(PROFILE_A, playlist.id, listOf(album[0].id, album[3].id), skipExisting = true))
        assertEquals(1, music.playlists.add(PROFILE_A, playlist.id, listOf(album[0].id)), "the same song may be added twice on purpose")
        assertEquals(listOf("Opening", "Second Light", "Third Wave", "Fourth Wall", "Opening"), music.titles(playlist.id))

        music.playlists.move(PROFILE_A, playlist.id, fromIndex = 3, toIndex = 0)
        assertEquals(listOf("Fourth Wall", "Opening", "Second Light", "Third Wave", "Opening"), music.titles(playlist.id))
        music.playlists.move(PROFILE_A, playlist.id, fromIndex = 0, toIndex = 4)
        assertEquals(listOf("Opening", "Second Light", "Third Wave", "Opening", "Fourth Wall"), music.titles(playlist.id))

        val entries = music.playlists.entries(PROFILE_A, playlist.id)
        assertEquals(listOf(0, 1, 2, 3, 4), entries.map { it.position })
        music.playlists.removeItems(PROFILE_A, playlist.id, listOf(entries[3].itemId))
        assertEquals(listOf("Opening", "Second Light", "Third Wave", "Fourth Wall"), music.titles(playlist.id))
        assertEquals(listOf(0, 1, 2, 3), music.playlists.entries(PROFILE_A, playlist.id).map { it.position })

        val loaded = music.playlists.playlist(PROFILE_A, playlist.id)!!
        assertEquals(4, loaded.trackCount)
        assertTrue(loaded.durationMs in 7_800..8_300)
        assertEquals(1, loaded.covers.size, "all four tracks share one album cover")

        music.playlists.rename(PROFILE_A, playlist.id, "Evening mix")
        assertEquals(listOf("Evening mix"), music.playlists.playlists(PROFILE_A).map { it.name })
        assertEquals("evening mix 2", music.playlists.create(PROFILE_A, "evening mix").name)

        music.playlists.delete(PROFILE_A, playlist.id)
        assertNull(music.playlists.playlist(PROFILE_A, playlist.id))
        assertEquals(7, music.album().size, "deleting a playlist never deletes songs")
    }

    @Test
    fun playlistsSurviveARestartAndFollowLibraryChanges() {
        var music = env.open()
        music.scan()
        val album = music.album()
        val playlist = music.playlists.create(PROFILE_A, "Keepers")
        music.playlists.add(PROFILE_A, playlist.id, album.map { it.id })
        val cover = assertNotNull(music.library.artwork.storeFile(PROFILE_A, env.musicFolder.resolve("Other Artist/Second Album/cover.jpg"), ArtworkStore.ORIGIN_CUSTOM))
        music.playlists.setArtwork(PROFILE_A, playlist.id, cover)

        music = env.open()
        val restored = music.playlists.playlists(PROFILE_A).single()
        assertEquals("Keepers", restored.name)
        assertEquals(7, restored.trackCount)
        assertEquals(cover, restored.artwork?.id)
        assertTrue(Files.isRegularFile(Path.of(restored.artwork!!.filePath)))
        assertEquals(album.map { it.title }, music.titles(restored.id))

        // A track removed from the library leaves the playlist; a missing file stays in it.
        music.library.removeTracks(PROFILE_A, listOf(album[1].id))
        Files.delete(Path.of(album[2].path!!))
        runBlocking { music.scanner.scanAll(PROFILE_A) }
        val entries = music.playlists.entries(PROFILE_A, restored.id)
        assertEquals(6, entries.size)
        assertTrue(entries.first { it.track.title == "Third Wave" }.track.missing)
    }

    @Test
    fun playlistsBelongToOneProfile() {
        val music = env.open()
        music.scan(PROFILE_A)
        music.scan(PROFILE_B)
        val mine = music.playlists.create(PROFILE_A, "Mine")
        music.playlists.add(PROFILE_A, mine.id, music.album(PROFILE_A).take(2).map { it.id })

        assertTrue(music.playlists.playlists(PROFILE_B).isEmpty())
        assertNull(music.playlists.playlist(PROFILE_B, mine.id))
        assertTrue(music.playlists.entries(PROFILE_B, mine.id).isEmpty())
        // Another profile can neither add to it, rename it nor delete it, nor smuggle its tracks in.
        assertEquals(0, music.playlists.add(PROFILE_B, mine.id, music.album(PROFILE_B).map { it.id }))
        music.playlists.rename(PROFILE_B, mine.id, "Hijacked")
        music.playlists.delete(PROFILE_B, mine.id)
        assertEquals(0, music.playlists.add(PROFILE_A, mine.id, music.album(PROFILE_B).map { it.id }), "tracks of another profile are refused")
        val untouched = music.playlists.playlist(PROFILE_A, mine.id)!!
        assertEquals("Mine", untouched.name)
        assertEquals(2, untouched.trackCount)

        env.deleteProfile(PROFILE_A)
        assertTrue(music.playlists.playlists(PROFILE_A).isEmpty())
    }

    @Test
    fun exportsAndReimportsM3u() {
        val music = env.open()
        music.scan()
        val album = music.album()
        val playlist = music.playlists.create(PROFILE_A, "Exported")
        music.playlists.add(PROFILE_A, playlist.id, listOf(album[2].id, album[0].id, album[6].id))

        val target = env.musicFolder.resolve("exported.m3u8")
        assertEquals(3, music.playlists.exportM3u(PROFILE_A, playlist.id, target))
        val text = Files.readString(target)
        assertTrue(text.startsWith("#EXTM3U\n"))
        assertTrue(text.contains("#EXTINF:2,Test Artist - Third Wave\nTest Artist/First Album/03 Third Wave.m4a"), text)

        val imported = runBlocking { music.playlists.importM3u(PROFILE_A, target, music.scanner) }
        assertEquals(3, imported.matched)
        assertTrue(imported.skipped.isEmpty())
        assertEquals("exported 2", imported.playlist.name, "named after the file, and told apart from the existing playlist")
        assertEquals(listOf("Third Wave", "Opening", "Seventh Heaven"), music.titles(imported.playlist.id))
    }

    @Test
    fun importingAPlaylistAddsItsFilesToTheLibraryAndReportsWhatItCouldNotUse() {
        val music = env.open()
        val file = env.root.resolve("legacy.m3u")
        val accented = env.musicFolder.resolve("Loose Files/Cancao Nona.aiff")
        val lines = listOf(
            "#EXTM3U",
            "#EXTINF:2,João Só - Canção Nona",
            accented.toString(),
            "#EXTINF:2,Test Artist - Opening",
            "My Music/Test Artist/First Album/01 Opening.mp3",
            "# a comment",
            "",
            "My Music\\Test Artist\\First Album\\02 Second Light.flac",
            "#EXTINF:215,Gone - Deleted Song",
            "My Music/nowhere/deleted.mp3",
            "#EXTINF:-1,Radio Somewhere",
            "https://radio.example/stream",
            "My Music/Test Artist/First Album/notes.txt",
        )
        // An old-style playlist in Windows-1252, as many players wrote them.
        Files.write(file, lines.joinToString("\r\n").toByteArray(charset("windows-1252")))

        val imported = runBlocking { music.playlists.importM3u(PROFILE_A, file, music.scanner, name = "Legacy") }
        assertEquals("Legacy", imported.playlist.name)
        assertEquals(3, imported.matched)
        assertEquals(listOf("Canção Nona", "Opening", "Second Light"), music.titles(imported.playlist.id))
        assertEquals(listOf("Gone - Deleted Song", "Radio Somewhere", "notes.txt"), imported.skipped)
        assertEquals(3, music.library.trackCount(PROFILE_A), "the playlist's files joined the library")
        assertEquals(accented.toString(), music.library.tracks(PROFILE_A).first { it.title == "Canção Nona" }.path)
    }

    @Test
    fun m3uTextDecoding() {
        val utf8 = "#EXTM3U\n#EXTINF:1,Canção\na.mp3\n".toByteArray(StandardCharsets.UTF_8)
        assertTrue(M3uCodec.decode(utf8).contains("Canção"))
        assertTrue(M3uCodec.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8).startsWith("#EXTM3U"))
        assertTrue(M3uCodec.decode("Canção".toByteArray(charset("windows-1252"))).contains("Canção"))

        val entries = M3uCodec.parse("#EXTINF:12.7 tvg-id=x,Name\nsub/a.flac\nfile:///tmp/b%20c.mp3\nhttp://x/y\n", Path.of("/base"))
        assertEquals(Path.of("/base/sub/a.flac"), entries[0].path)
        assertEquals(12, entries[0].durationSeconds)
        assertEquals("Name", entries[0].label)
        assertEquals(Path.of("/tmp/b c.mp3"), entries[1].path)
        assertEquals("http://x/y", entries[2].url)
    }

    @Test
    fun preferencesArePerProfileAndPersist() {
        var music = env.open(PROFILE_A)
        music.preferences.set(MusicPreferences.LIBRARY_TAB, "albums")
        music.preferences.set(MusicPreferences.RESUME_ON_START, "true")
        env.switchProfile(PROFILE_B)
        assertNull(music.preferences[MusicPreferences.LIBRARY_TAB])
        music.preferences.set(MusicPreferences.LIBRARY_TAB, "artists")

        music = env.open(PROFILE_A)
        assertEquals("albums", music.preferences[MusicPreferences.LIBRARY_TAB])
        assertTrue(music.preferences.boolean(MusicPreferences.RESUME_ON_START, false))
        env.switchProfile(PROFILE_B)
        assertEquals("artists", music.preferences[MusicPreferences.LIBRARY_TAB])
        music.preferences.set(MusicPreferences.LIBRARY_TAB, null)
        assertNull(music.preferences[MusicPreferences.LIBRARY_TAB])
    }
}
