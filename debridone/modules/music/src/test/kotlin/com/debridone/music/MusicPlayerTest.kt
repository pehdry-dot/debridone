package com.debridone.music

import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_A
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_B
import com.debridone.music.library.Track
import com.debridone.music.library.TrackSort
import com.debridone.music.playback.FakePlaybackEngine
import com.debridone.music.playback.MpvAudioEngine
import com.debridone.music.playback.PlaybackStatus
import com.debridone.music.playback.PlayerState
import com.debridone.music.playback.RepeatMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The player as the app uses it: library tracks in, history and saved sessions out. */
class MusicPlayerTest {
    private val env = MusicTestEnvironment()
    private var fake = FakePlaybackEngine()

    @AfterTest
    fun tearDown() = env.close()

    private fun openWithFake(profileId: Long? = PROFILE_A): MusicServices {
        fake = FakePlaybackEngine()
        return env.open(profileId) { fake }
    }

    private fun MusicServices.scan(profileId: Long = PROFILE_A, folder: Path = env.musicFolder) = runBlocking {
        scanner.scanFolder(profileId, library.addFolder(profileId, folder.toString()).id)
    }

    private fun MusicServices.album(profileId: Long = PROFILE_A): List<Track> =
        library.albumTracks(profileId, library.albums(profileId).first { it.title == "First Album" }.id)

    private fun MusicServices.await(what: String, timeoutMs: Long = 8_000, predicate: (PlayerState) -> Boolean): PlayerState {
        awaitTrue(timeoutMs, { "$what; last state: ${player.state.value.summary()}" }) { predicate(player.state.value) }
        return player.state.value
    }

    /** Waits until the scripted engine has been asked to load [count] tracks in total. */
    private fun awaitEngineLoads(count: Int = 1) = awaitTrue(describe = { "the engine to be given $count load(s), got ${fake.loads.size}" }) { fake.loads.size >= count }

    @Test
    fun importPlayRestartWithRealAudio() {
        requireLibmpv()
        var music = env.open { MpvAudioEngine(audioOutput = "null") }
        runBlocking { music.scanner.importFiles(PROFILE_A, listOf(env.firstAlbum.resolve("02 Second Light.flac"))) }

        // The imported file shows up with its tags and cover.
        val track = music.library.tracks(PROFILE_A).single()
        assertEquals("Second Light", track.title)
        assertEquals("Test Artist", track.artist)
        assertEquals("First Album", track.album)
        assertEquals("FLAC", track.format)
        assertTrue(Files.isRegularFile(Path.of(assertNotNull(track.artwork).filePath)))

        // It plays through the real engine and the listen is recorded.
        music.player.play(listOf(track))
        val playing = music.await("the track to play") { it.status == PlaybackStatus.PLAYING && it.positionMs > 200 }
        assertEquals("Second Light", playing.current?.title)
        awaitTrue(describe = { "now-playing to follow" }) { music.player.nowPlaying.value?.id == track.id }
        assertTrue(music.player.engineDescription.value!!.contains("mpv"))
        music.await("the track to finish") { it.status == PlaybackStatus.ENDED }
        awaitTrue(describe = { "the play to be counted" }) { music.library.track(PROFILE_A, track.id)!!.playCount == 1 }

        // After a restart the track, its artwork, its play count and the queue are still there.
        music = env.open { MpvAudioEngine(audioOutput = "null") }
        val after = music.library.tracks(PROFILE_A).single()
        assertEquals(track.id, after.id)
        assertEquals(1, after.playCount)
        assertEquals(track.artwork!!.filePath, after.artwork?.filePath)
        assertEquals(listOf(track.id), music.player.state.value.queue.map { it.trackId })
        assertEquals(PlaybackStatus.IDLE, music.player.state.value.status, "nothing plays by itself after a restart")
    }

    @Test
    fun playsAScannedAlbumAndWritesHistory() {
        requireLibmpv()
        val music = env.open { MpvAudioEngine(audioOutput = "null") }
        music.scan()
        val album = music.album()
        music.player.play(album, startIndex = 4)
        music.await("the album to end", timeoutMs = 20_000) { it.status == PlaybackStatus.ENDED }
        awaitTrue(describe = { "history of three tracks" }) { music.library.history(PROFILE_A).size == 3 }
        assertEquals(listOf("Seventh Heaven", "Sixth Sense", "Fifth Element"), music.library.history(PROFILE_A).map { it.track.title })
        assertTrue(music.library.history(PROFILE_A).all { it.completed })
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1), music.album().map { it.playCount })
    }

    @Test
    fun theSessionIsRestoredAfterARestart() {
        var music = openWithFake()
        music.scan()
        val album = music.album()
        music.player.play(album, startIndex = 2)
        music.await("load") { it.current?.title == "Third Wave" }
        awaitEngineLoads()
        fake.start()
        fake.position(1_300)
        music.player.setShuffle(true)
        music.player.setRepeat(RepeatMode.ALL)
        music.player.setVolume(60)
        val before = music.await("settings") { it.shuffle && it.repeat == RepeatMode.ALL && it.volume == 60 && it.positionMs == 1_300L }

        music = openWithFake()
        val restored = music.player.state.value
        assertEquals(before.queue.map { it.trackId }, restored.queue.map { it.trackId })
        assertEquals(before.order, restored.order)
        assertEquals("Third Wave", restored.current?.title)
        assertEquals(1_300, restored.positionMs)
        assertTrue(restored.shuffle)
        assertEquals(RepeatMode.ALL, restored.repeat)
        assertEquals(60, restored.volume)
        assertEquals(PlaybackStatus.IDLE, restored.status)
        assertTrue(fake.loads.isEmpty(), "restoring a session must be silent")
        awaitTrue(describe = { "now-playing after restart" }) { music.player.nowPlaying.value?.title == "Third Wave" }

        music.player.resume()
        awaitTrue(describe = { "resume to load the track" }) { fake.loads.size == 1 }
        assertEquals(1_300, fake.lastLoad.startPositionMs)
        assertEquals(album[2].path, fake.lastLoad.uri)
    }

    @Test
    fun tracksRemovedWhileTheAppWasClosedDropOutOfTheSavedQueue() {
        var music = openWithFake()
        music.scan()
        val album = music.album()
        music.player.play(album, startIndex = 3)
        music.await("load") { it.current?.title == "Fourth Wall" }
        awaitEngineLoads()
        fake.start()
        fake.position(900)
        music.await("position") { it.positionMs == 900L }
        music.library.removeTracks(PROFILE_A, listOf(album[0].id, album[3].id))

        music = openWithFake()
        val restored = music.player.state.value
        assertEquals(5, restored.queue.size)
        assertTrue(restored.queue.none { it.title == "Opening" || it.title == "Fourth Wall" })
        assertEquals(0, restored.positionMs, "the saved position belonged to the removed track")
        assertNotNull(restored.current)
    }

    @Test
    fun switchingProfileStopsMusicAndRestoresEachProfilesOwnSession() {
        val music = openWithFake(PROFILE_A)
        music.scan(PROFILE_A)
        music.scan(PROFILE_B, env.musicFolder.resolve("Other Artist"))

        music.player.play(music.album(PROFILE_A), startIndex = 1)
        music.await("profile A playing") { it.current?.title == "Second Light" }
        awaitEngineLoads()
        fake.start()
        listOf(0L, 500, 1_000, 1_500).forEach { fake.position(it) }
        music.await("position") { it.positionMs == 1_500L }

        env.switchProfile(PROFILE_B)
        val asB = music.player.state.value
        assertTrue(asB.queue.isEmpty(), "profile B must not see profile A's queue")
        assertEquals(PlaybackStatus.IDLE, asB.status)
        assertTrue(fake.stops >= 1, "profile A's playback is stopped")
        assertNull(music.player.nowPlaying.value)
        // The interrupted listen went to profile A's history, not B's.
        assertEquals(listOf("Second Light"), music.library.history(PROFILE_A).map { it.track.title })
        assertTrue(music.library.history(PROFILE_B).isEmpty())

        music.player.play(music.library.tracks(PROFILE_B))
        music.await("profile B playing") { it.current?.title == "Elsewhere" }

        env.switchProfile(PROFILE_A)
        val backInA = music.player.state.value
        assertEquals("Second Light", backInA.current?.title)
        assertEquals(7, backInA.queue.size)
        assertEquals(1_500, backInA.positionMs)
        assertEquals(PlaybackStatus.IDLE, backInA.status, "it comes back paused")

        env.switchProfile(null)
        assertTrue(music.player.state.value.queue.isEmpty(), "signing out clears the player")
    }

    @Test
    fun playingAFileThatWasDeletedFlagsItAndMovesOn() {
        requireLibmpv()
        val music = env.open { MpvAudioEngine(audioOutput = "null") }
        music.scan()
        val album = music.album()
        Files.delete(Path.of(album[0].path!!))

        music.player.play(album.take(2))
        val state = music.await("the second track to play") { it.status == PlaybackStatus.PLAYING && it.current?.title == "Second Light" }
        assertEquals("Opening", state.lastError?.title)
        awaitTrue(describe = { "the deleted file to be flagged" }) { music.library.track(PROFILE_A, album[0].id)!!.missing }
        assertFalse(music.library.track(PROFILE_A, album[1].id)!!.missing)
    }

    @Test
    fun queueActionsUseLibraryTracks() {
        val music = openWithFake()
        music.scan()
        val album = music.album()
        val elsewhere = music.library.tracks(PROFILE_A, sort = TrackSort.TITLE).first { it.title == "Elsewhere" }
        music.player.play(album.take(2))
        music.await("load") { it.current?.title == "Opening" }
        music.player.addToQueue(listOf(album[6]))
        music.player.playNext(listOf(elsewhere))
        val state = music.await("queue of four") { it.queue.size == 4 }
        assertEquals(listOf("Opening", "Elsewhere", "Second Light", "Seventh Heaven"), state.order.map { state.queue[it].title })
        assertEquals("Other Artist", state.queue.first { it.title == "Elsewhere" }.artist)
        assertNotNull(state.queue.first { it.title == "Opening" }.artworkPath)

        music.library.removeTracks(PROFILE_A, listOf(elsewhere.id))
        music.player.removeDeletedFromQueue()
        music.await("removed track to leave the queue") { it.queue.size == 3 }
    }

    @Test
    fun aPlaylistCanBePlayedShuffled() {
        val music = openWithFake()
        music.scan()
        val playlist = music.playlists.create(PROFILE_A, "Mixed bag")
        music.playlists.add(PROFILE_A, playlist.id, music.library.tracks(PROFILE_A, sort = TrackSort.TITLE).map { it.id })
        val inOrder = music.playlists.tracks(PROFILE_A, playlist.id)
        assertEquals(Fixtures.TOTAL_TRACKS, inOrder.size)

        music.player.play(inOrder, startIndex = 0, shuffle = true)
        val state = music.await("the shuffled playlist to load") { it.queue.size == inOrder.size && it.shuffle }
        assertEquals(inOrder.first().title, state.current?.title, "the song you clicked plays first")
        assertEquals(inOrder.indices.toSet(), state.order.toSet(), "every song of the playlist is played exactly once")
        assertTrue(state.order != inOrder.indices.toList(), "the order is shuffled: ${state.order}")

        // The same playlist unshuffled plays in its own order.
        music.player.play(inOrder, shuffle = false)
        val plain = music.await("the playlist in order") { !it.shuffle && it.order == inOrder.indices.toList() }
        assertEquals(inOrder.map { it.id }, plain.queue.map { it.trackId })
    }

    @Test
    fun anEngineThatCannotStartIsReportedNotThrown() {
        val music = env.open { throw IllegalStateException("libmpv is not here") }
        music.scan()
        music.player.play(music.album())
        assertEquals("libmpv is not here", music.player.engineError.value)
        assertEquals(PlaybackStatus.IDLE, music.player.state.value.status)
        // The library keeps working without audio.
        assertEquals(7, music.album().size)
    }
}
